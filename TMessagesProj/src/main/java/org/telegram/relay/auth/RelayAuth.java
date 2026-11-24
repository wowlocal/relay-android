package org.telegram.relay.auth;

import android.net.Uri;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.relay.RelayConfig;
import org.telegram.relay.account.RelayAccount;
import org.telegram.relay.account.RelayDatabase;
import org.telegram.relay.account.RelaySession;
import org.telegram.relay.account.RelaySessionStore;
import org.telegram.relay.account.RelayUsers;
import org.telegram.relay.bridge.RelayBridge;
import org.telegram.relay.mattermost.MattermostClient;
import org.telegram.relay.mattermost.MattermostException;
import org.telegram.relay.mattermost.model.ClientConfig;
import org.telegram.relay.mattermost.model.LoginResult;
import org.telegram.relay.mattermost.model.PingResponse;
import org.telegram.relay.mattermost.model.ServerVersion;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.Locale;

/**
 * Mattermost login for an account slot: server check, credentials (+ MFA), the fake authorised
 * Telegram account ({@link #completeLogin}), and logout. Network work runs on
 * {@code Utilities.globalQueue}; callbacks run on the UI thread.
 */
public final class RelayAuth {
    private RelayAuth() {
    }

    private static final String TAG = RelayConfig.TAG_AUTH;

    public enum Failure {
        INVALID_URL,
        UNREACHABLE,
        NOT_MATTERMOST,
        UNSUPPORTED_VERSION,
        WRONG_CREDENTIALS,
        MFA_REQUIRED,
        WRONG_MFA,
        SERVER_ERROR
    }

    public static final class Error extends Exception {
        public final Failure failure;
        /** Server message or detail to show under the title (may be empty). */
        public final String detail;

        Error(Failure failure, String detail) {
            super(failure + (detail != null && !detail.isEmpty() ? ": " + detail : ""));
            this.failure = failure;
            this.detail = detail == null ? "" : detail;
        }
    }

    public interface Callback<T> {
        void onResult(T result, Error error);
    }

    /** A checked server. */
    public static final class ServerInfo {
        public final String url;
        /** Host (and port) for display, e.g. {@code localhost:8065}. */
        public final String displayHost;
        public final ServerVersion version;
        public final String siteName;

        ServerInfo(String url, String displayHost, ServerVersion version, String siteName) {
            this.url = url;
            this.displayHost = displayHost;
            this.version = version;
            this.siteName = siteName;
        }
    }

    // MARK: - URL

    /**
     * Normalises what the user typed: adds a scheme ({@code http} for localhost/loopback/emulator
     * host, otherwise {@code https}), drops trailing slashes and a trailing {@code /login} or
     * {@code /api/v4}. Returns null when there is no host.
     */
    public static String normalizeServerUrl(String input) {
        if (input == null) {
            return null;
        }
        String text = input.trim();
        if (text.isEmpty() || text.contains(" ")) {
            return null;
        }
        if (!text.contains("://")) {
            String host = text;
            int slash = host.indexOf('/');
            if (slash >= 0) {
                host = host.substring(0, slash);
            }
            int colon = host.indexOf(':');
            if (colon >= 0) {
                host = host.substring(0, colon);
            }
            host = host.toLowerCase(Locale.ROOT);
            boolean local = host.equals("localhost") || host.startsWith("127.") || host.equals("10.0.2.2") || host.endsWith(".local");
            text = (local ? "http://" : "https://") + text;
        }
        Uri uri;
        try {
            uri = Uri.parse(text);
        } catch (Exception e) {
            return null;
        }
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (scheme == null || host == null || host.isEmpty() || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            return null;
        }
        String path = uri.getPath() == null ? "" : uri.getPath();
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        for (String suffix : new String[]{"/login", "/api/v4"}) {
            if (path.toLowerCase(Locale.ROOT).endsWith(suffix)) {
                path = path.substring(0, path.length() - suffix.length());
            }
        }
        StringBuilder url = new StringBuilder();
        url.append(scheme.toLowerCase(Locale.ROOT)).append("://").append(host.toLowerCase(Locale.ROOT));
        if (uri.getPort() > 0) {
            url.append(':').append(uri.getPort());
        }
        url.append(path);
        return url.toString();
    }

    public static String displayHost(String url) {
        try {
            Uri uri = Uri.parse(url);
            String host = uri.getHost();
            if (host == null) {
                return url;
            }
            return host + (uri.getPort() > 0 ? ":" + uri.getPort() : "") + (uri.getPath() != null && !uri.getPath().isEmpty() ? uri.getPath() : "");
        } catch (Exception e) {
            return url;
        }
    }

    // MARK: - Server step

    /** {@code GET /system/ping} + client config: is it a reachable, supported Mattermost server? */
    public static void checkServer(String input, Callback<ServerInfo> callback) {
        String url = normalizeServerUrl(input);
        if (url == null) {
            AndroidUtilities.runOnUIThread(() -> callback.onResult(null, new Error(Failure.INVALID_URL, "")));
            return;
        }
        Utilities.globalQueue.postRunnable(() -> {
            ServerInfo info = null;
            Error error = null;
            try {
                info = checkServerBlocking(url);
            } catch (Error e) {
                error = e;
            }
            final ServerInfo fi = info;
            final Error fe = error;
            AndroidUtilities.runOnUIThread(() -> callback.onResult(fi, fe));
        });
    }

    public static ServerInfo checkServerBlocking(String url) throws Error {
        MattermostClient client = RelayAccount.newClient(url, null);
        client.setTimeouts(10_000, 15_000);
        String host = displayHost(url);
        PingResponse ping;
        try {
            ping = client.ping(false);
        } catch (MattermostException e) {
            RelayConfig.logError(TAG, "ping " + url, e);
            if (e.getKind() == MattermostException.Kind.TRANSPORT) {
                throw new Error(Failure.UNREACHABLE, host);
            }
            throw new Error(Failure.NOT_MATTERMOST, host);
        }
        if (!ping.isOk() && ping.versionHeader == null) {
            throw new Error(Failure.NOT_MATTERMOST, host);
        }
        ServerVersion version = ping.getServerVersion();
        String siteName = "";
        try {
            ClientConfig config = client.clientConfig();
            if (version == null) {
                version = config.getServerVersion();
            }
            String name = config.get("SiteName");
            siteName = name == null ? "" : name;
        } catch (MattermostException e) {
            RelayConfig.logError(TAG, "client config " + url, e);
        }
        if (version != null && !version.isSupported()) {
            throw new Error(Failure.UNSUPPORTED_VERSION, version.toString());
        }
        RelayConfig.log(TAG, "server ok " + url + " version=" + version);
        return new ServerInfo(url, host, version, siteName);
    }

    // MARK: - Credentials step

    /**
     * {@code POST /users/login}; on success stores the session for {@code account} and returns the
     * TL user of the logged-in Mattermost user (callback on the UI thread). Call {@link #completeLogin}
     * next.
     */
    public static void login(int account, ServerInfo server, String loginId, String password, String mfaToken, Callback<TLRPC.TL_user> callback) {
        Utilities.globalQueue.postRunnable(() -> {
            TLRPC.TL_user user = null;
            Error error = null;
            try {
                user = loginBlocking(account, server, loginId, password, mfaToken);
            } catch (Error e) {
                error = e;
            }
            final TLRPC.TL_user fu = user;
            final Error fe = error;
            AndroidUtilities.runOnUIThread(() -> callback.onResult(fu, fe));
        });
    }

    public static TLRPC.TL_user loginBlocking(int account, ServerInfo server, String loginId, String password, String mfaToken) throws Error {
        MattermostClient client = RelayAccount.newClient(server.url, null);
        LoginResult result;
        try {
            result = client.login(loginId.trim(), password, mfaToken, null);
        } catch (MattermostException e) {
            RelayConfig.log(TAG, "login failed: status=" + e.getStatusCode() + " id=" + e.getErrorId());
            if (e.isMfaRequired()) {
                throw new Error(Failure.MFA_REQUIRED, "");
            }
            if (e.isInvalidMfaToken()) {
                throw new Error(Failure.WRONG_MFA, "");
            }
            if (e.getKind() == MattermostException.Kind.TRANSPORT) {
                throw new Error(Failure.UNREACHABLE, server.displayHost);
            }
            String id = e.getErrorId();
            if (id.startsWith("api.user.login.invalid_credentials") || id.equals("api.user.check_user_password.invalid.app_error") || id.equals("store.sql_user.get_for_login.app_error")) {
                throw new Error(Failure.WRONG_CREDENTIALS, "");
            }
            String message = e.getAppError() != null && e.getAppError().message != null ? e.getAppError().message : e.getMessage();
            throw new Error(Failure.SERVER_ERROR, message);
        }
        // A fresh slot: forget whatever the bridge had for a previous user of this slot.
        RelayBridge.get(account).stop();
        RelayAccount.drop(account);
        RelayDatabase.deleteFiles(account);
        RelaySession session = new RelaySession(server.url, result.token, result.user.id, loginId.trim(),
                server.version != null ? server.version.toString() : "", System.currentTimeMillis());
        RelaySessionStore.put(account, session);
        RelaySessionStore.setLastServerUrl(server.url);
        RelayAccount relayAccount = RelayAccount.get(account);
        long tlId = relayAccount.rememberUser(result.user);
        RelayConfig.log(TAG, "logged in account=" + account + " mm=" + result.user.id + " tl=" + tlId);
        return RelayUsers.toTlUser(result.user, tlId, true);
    }

    // MARK: - Fake authorised Telegram account

    /**
     * The trimmed {@code LoginActivity.onAuthSuccess} (internals doc §2.3): Telegram state for the
     * slot is reset and {@code user} becomes the current user. UI thread. Navigation is the caller's.
     */
    public static void completeLogin(int account, TLRPC.TL_user user) {
        MessagesController.getInstance(account).cleanup();
        UserConfig.getInstance(account).clearConfig();
        MessagesController.getInstance(account).cleanup();
        UserConfig.getInstance(account).syncContacts = false;
        UserConfig.getInstance(account).setCurrentUser(user);
        UserConfig.getInstance(account).saveConfig(true);
        MessagesStorage.getInstance(account).cleanup(true);
        ArrayList<TLRPC.User> users = new ArrayList<>();
        users.add(user);
        MessagesStorage.getInstance(account).putUsersAndChats(users, null, true, true);
        MessagesController.getInstance(account).putUser(user, false);
        MessagesController.getInstance(account).loadAppConfig();
        RelayBridge.get(account).start();
    }

    /**
     * Logs the selected account in without typing (debug test hooks): server check, login, then the
     * same completion as the login screen. Callback on the UI thread with the account or an error text.
     */
    public static void loginWithoutUi(String serverInput, String loginId, String password, String mfa, Callback<Integer> callback) {
        int account = UserConfig.selectedAccount;
        if (UserConfig.getInstance(account).isClientActivated()) {
            callback.onResult(null, new Error(Failure.SERVER_ERROR, "account " + account + " is already logged in"));
            return;
        }
        RelayConfig.log(TAG, "login without UI account=" + account + " server=" + serverInput + " user=" + loginId);
        checkServer(serverInput, (info, error) -> {
            if (error != null) {
                callback.onResult(null, error);
                return;
            }
            login(account, info, loginId, password, mfa, (user, loginError) -> {
                if (loginError != null) {
                    callback.onResult(null, loginError);
                    return;
                }
                org.telegram.relay.ui.RelayLoginActivity screen = org.telegram.relay.ui.RelayLoginActivity.visibleInstance();
                if (screen != null && screen.getAccount() == account) {
                    screen.onLoginSuccess(user);
                } else {
                    completeLogin(account, user);
                    if (org.telegram.ui.LaunchActivity.instance != null) {
                        org.telegram.ui.LaunchActivity.instance.switchToAccount(account, true);
                    }
                }
                callback.onResult(account, null);
            });
        });
    }

    // MARK: - Logout

    /**
     * Called from {@code MessagesController.performLogout}: stops the bridge, wipes the Relay state of
     * the slot and revokes the Mattermost session in the background. Idempotent.
     */
    public static void onTelegramLogout(int account) {
        RelaySession session = RelaySessionStore.get(account);
        RelayBridge.get(account).stop();
        RelaySessionStore.clear(account);
        RelayAccount.drop(account);
        RelayDatabase.deleteFiles(account);
        if (session != null) {
            RelayConfig.log(TAG, "logout account=" + account + " " + session);
            Utilities.globalQueue.postRunnable(() -> {
                try {
                    MattermostClient client = RelayAccount.newClient(session.serverUrl, session.token);
                    client.setTimeouts(5_000, 10_000);
                    client.logout();
                    RelayConfig.log(TAG, "mattermost session revoked account=" + account);
                } catch (Exception e) {
                    RelayConfig.logError(TAG, "mattermost logout", e);
                }
            });
        }
    }
}
