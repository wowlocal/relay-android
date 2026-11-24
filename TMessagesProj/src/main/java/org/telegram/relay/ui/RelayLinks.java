package org.telegram.relay.ui;

import android.content.Context;
import android.net.Uri;
import android.text.TextUtils;

import org.telegram.messenger.UserConfig;
import org.telegram.relay.RelayConfig;
import org.telegram.relay.account.RelaySession;
import org.telegram.relay.account.RelaySessionStore;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Links in Relay: Telegram's deep links are not followed, Mattermost links stay in the app.
 *
 * <ul>
 *   <li>{@code t.me}, {@code telegram.me}, {@code telegram.dog} and {@code *.t.me}: opened in the
 *       system browser, never as Telegram flows (the manifest no longer claims them).</li>
 *   <li>{@code tg:}: ignored (logged), except the hosts in {@link #TG_HOSTS_FOR_RELAY}.
 *       {@code ton:}, {@code tonsite:}, {@code tc:}, {@code tgb:}: ignored.</li>
 *   <li>Mattermost web links into a signed-in server ({@code https://<server>/<team>/pl/<post>},
 *       {@code /channels/<name>}, {@code /messages/@<user>}, {@code /threads/<root>},
 *       {@code /_redirect/…}) and the app scheme {@code relay://<host>[:<port>][/<path>]/<route>}
 *       (same layout as Relay for iOS; {@code mattermost://} is understood inside the app):
 *       go to {@link #handler}, which the bridge installs once it can resolve them.</li>
 * </ul>
 *
 * Entry points: {@code Browser.openUrl} (every in-app link tap ends there),
 * {@code ChatActivity.didPressMessageUrl} (Mattermost links skip "Open this link?") and
 * {@code LaunchActivity.handleIntent} (VIEW intents from the system).
 */
public final class RelayLinks {
    private RelayLinks() {
    }

    public static final String TAG = "RelayLinks";

    /** Relay's registered URL scheme (iOS uses the same). */
    public static final String SCHEME = "relay";
    /** Mattermost's mobile-app scheme: understood when tapped inside Relay, not registered. */
    public static final String MATTERMOST_SCHEME = "mattermost";

    /**
     * {@code tg://<host>} links that Relay will handle itself later (none yet). Candidates:
     * {@code user?id=} (profile of a mapped user), {@code privatepost} (jump inside a chat),
     * {@code settings}. Listed hosts are passed on to Telegram's handler; all others are dropped.
     */
    private static final String[] TG_HOSTS_FOR_RELAY = {};

    private static final Pattern T_ME_SUBDOMAIN = Pattern.compile("^[a-z0-9-]+\\.t\\.me$");

    public enum Route {
        /** {@code /<team>/pl/<post id>}: a permalink. */
        POST,
        /** {@code /<team>/channels/<channel name>}. */
        CHANNEL,
        /** {@code /<team>/messages/@<username>}. */
        DIRECT_MESSAGE,
        /** {@code /<team>/messages/<group message channel name>}. */
        GROUP_MESSAGE,
        /** {@code /<team>/threads/<root post id>}. */
        THREAD,
    }

    /** A link into the Mattermost server of one account. */
    public static final class Link {
        public final int account;
        public final String serverUrl;
        public final Route route;
        /** Team name, or null for {@code /_redirect/…} links (Mattermost picks the user's team). */
        public final String teamName;
        /** Post id, channel name, username (without "@") or root post id, per {@link #route}. */
        public final String value;
        public final String url;

        Link(int account, String serverUrl, Route route, String teamName, String value, String url) {
            this.account = account;
            this.serverUrl = serverUrl;
            this.route = route;
            this.teamName = teamName;
            this.value = value;
            this.url = url;
        }

        @Override
        public String toString() {
            return route + " team=" + teamName + " value=" + value + " account=" + account + " server=" + serverUrl;
        }
    }

    /** Opens Mattermost links inside the app. Installed by the bridge (A7: permalinks, channels, DMs). */
    public interface Handler {
        /** Returns true when the link was opened. {@code context} may be null. */
        boolean open(Context context, Link link);
    }

    private static volatile Handler handler;

    public static void setHandler(Handler h) {
        handler = h;
    }

    // MARK: - entry points

    /**
     * {@code Browser.openUrl}: returns true when Relay handled {@code uri} (opened it elsewhere or
     * dropped it); false leaves it to the normal external-link path.
     */
    public static boolean interceptOpenUrl(Context context, Uri uri) {
        if (!RelayConfig.ENABLED || uri == null) {
            return false;
        }
        final String scheme = lower(uri.getScheme());
        if ("tg".equals(scheme)) {
            return !isTgLinkForRelay(uri);
        }
        if ("ton".equals(scheme) || "tonsite".equals(scheme) || "tc".equals(scheme) || "tgb".equals(scheme)) {
            RelayConfig.log(TAG, "ignored " + scheme + " link");
            return true;
        }
        if (SCHEME.equals(scheme) || MATTERMOST_SCHEME.equals(scheme) || "http".equals(scheme) || "https".equals(scheme)) {
            final Link link = parse(uri.toString());
            if (link != null) {
                open(context, link);
                return true;
            }
            if (SCHEME.equals(scheme) || MATTERMOST_SCHEME.equals(scheme)) {
                RelayConfig.log(TAG, "no signed-in server for " + scheme + " link " + uri.getHost());
                return true;
            }
            if (isTelegramHost(uri.getHost())) {
                openInBrowser(context, uri);
                return true;
            }
        }
        return false;
    }

    /**
     * Taps on links in messages: true when {@code url} is a Mattermost link (opened in the app,
     * without Telegram's "Open this link?" alert).
     */
    public static boolean openFromMessage(Context context, String url) {
        if (!RelayConfig.ENABLED || TextUtils.isEmpty(url)) {
            return false;
        }
        final Link link = parse(url);
        if (link == null) {
            return false;
        }
        open(context, link);
        return true;
    }

    /**
     * {@code LaunchActivity.handleIntent} for VIEW intents (from the system or
     * {@code Browser.openAsInternalIntent}): true when handled, so Telegram's link parser never runs.
     */
    public static boolean handleViewIntent(Context context, Uri data) {
        if (!RelayConfig.ENABLED || data == null) {
            return false;
        }
        final String scheme = lower(data.getScheme());
        if (interceptOpenUrl(context, data)) {
            return true;
        }
        if ("tg".equals(scheme)) {
            // A tg: link Relay handles (TG_HOSTS_FOR_RELAY): Telegram's parser takes it.
            return false;
        }
        if (("http".equals(scheme) || "https".equals(scheme)) && data.getHost() != null) {
            // Any other web link that reached the app: the browser, never a Telegram flow.
            openInBrowser(context, data);
            return true;
        }
        return false;
    }

    // MARK: - routing

    private static boolean isTgLinkForRelay(Uri uri) {
        final String host = lower(uri.getHost());
        for (String allowed : TG_HOSTS_FOR_RELAY) {
            if (allowed.equals(host)) {
                return true;
            }
        }
        RelayConfig.log(TAG, "ignored tg link: " + host);
        return false;
    }

    private static void open(Context context, Link link) {
        RelayConfig.log(TAG, "open " + link);
        final Handler h = handler;
        if (h != null && h.open(context, link)) {
            return;
        }
        // TODO(A7): the bridge resolves the post (GET /posts/{id}) to its dialog and message id and
        // opens ChatActivity at it (a CRT reply in its thread); channels by team + name (join prompt
        // when not a member), DMs by username, group messages by channel name, threads by root id.
        RelayConfig.log(TAG, "no handler for " + link.route + " link yet (A7)");
    }

    private static void openInBrowser(Context context, Uri uri) {
        RelayConfig.log(TAG, "browser: " + uri.getHost());
        final Context ctx = context != null ? context : org.telegram.messenger.ApplicationLoader.applicationContext;
        try {
            final android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_VIEW, uri);
            intent.putExtra(android.provider.Browser.EXTRA_CREATE_NEW_TAB, true);
            intent.putExtra(android.provider.Browser.EXTRA_APPLICATION_ID, ctx.getPackageName());
            if (!(ctx instanceof android.app.Activity)) {
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            }
            ctx.startActivity(intent);
        } catch (Exception e) {
            RelayConfig.logError(TAG, "no browser for " + uri.getHost(), e);
        }
    }

    public static boolean isTelegramHost(String host) {
        host = lower(host);
        if (host.startsWith("www.")) {
            host = host.substring(4);
        }
        return host.equals("t.me") || host.equals("telegram.me") || host.equals("telegram.dog") || T_ME_SUBDOMAIN.matcher(host).matches();
    }

    // MARK: - parsing (port of iOS MattermostLink.parse)

    /** Parses {@code url} against the servers of the signed-in accounts; null when it is not a Mattermost link. */
    public static Link parse(String url) {
        final String[] servers = new String[UserConfig.MAX_ACCOUNT_COUNT];
        for (int a = 0; a < servers.length; a++) {
            final RelaySession session = RelaySessionStore.get(a);
            servers[a] = session != null && session.isValid() ? session.serverUrl : null;
        }
        return parse(url, servers);
    }

    /** {@code servers[i]} is the base URL of account {@code i} (null for an empty slot). */
    public static Link parse(String url, String[] servers) {
        if (url == null) {
            return null;
        }
        final Uri uri;
        try {
            uri = Uri.parse(url.trim());
        } catch (Exception e) {
            return null;
        }
        final String scheme = lower(uri.getScheme());
        final String host = lower(uri.getHost());
        if (host.isEmpty()) {
            return null;
        }
        final boolean isWeb = scheme.equals("http") || scheme.equals("https");
        final boolean isApp = scheme.equals(SCHEME) || scheme.equals(MATTERMOST_SCHEME);
        if (!isWeb && !isApp) {
            return null;
        }
        final String linkPath = uri.getEncodedPath() == null ? "" : uri.getEncodedPath();
        int best = -1;
        int bestPrefix = -1;
        boolean bestExactPort = false;
        for (int i = 0; i < servers.length; i++) {
            final Base base = Base.of(servers[i]);
            if (base == null || !base.host.equals(host)) {
                continue;
            }
            boolean exactPort = true;
            if (uri.getPort() != -1) {
                if (uri.getPort() != base.port) {
                    continue;
                }
            } else if (isWeb) {
                if (base.port != defaultPort(scheme)) {
                    continue;
                }
            } else {
                // An app link without a port: the server on its own scheme's default port wins.
                exactPort = base.port == defaultPort(base.scheme);
            }
            if (!(base.path.isEmpty() || linkPath.equals(base.path) || linkPath.startsWith(base.path + "/"))) {
                continue;
            }
            if (best < 0 || (exactPort && !bestExactPort) || (exactPort == bestExactPort && base.path.length() > bestPrefix)) {
                best = i;
                bestPrefix = base.path.length();
                bestExactPort = exactPort;
            }
        }
        if (best < 0) {
            return null;
        }
        final String rest = linkPath.substring(bestPrefix);
        final java.util.ArrayList<String> parts = new java.util.ArrayList<>();
        for (String part : rest.split("/")) {
            if (!part.isEmpty()) {
                parts.add(Uri.decode(part));
            }
        }
        if (parts.size() < 3) {
            return null;
        }
        final String team = parts.get(0);
        final String teamName = "_redirect".equals(team) ? null : team;
        if (teamName != null && !isTeamName(teamName)) {
            return null;
        }
        final String value = parts.get(2);
        Route route = null;
        String routeValue = value;
        switch (parts.get(1)) {
            case "pl":
                route = isId(value) ? Route.POST : null;
                break;
            case "threads":
                route = isId(value) ? Route.THREAD : null;
                break;
            case "channels":
                route = isChannelName(value) ? Route.CHANNEL : null;
                break;
            case "messages":
                if (value.startsWith("@")) {
                    routeValue = value.substring(1).toLowerCase(Locale.US);
                    route = routeValue.isEmpty() ? null : Route.DIRECT_MESSAGE;
                } else {
                    route = isChannelName(value) ? Route.GROUP_MESSAGE : null;
                }
                break;
        }
        if (route == null) {
            return null;
        }
        return new Link(best, servers[best], route, teamName, routeValue, url);
    }

    /** {@code relay://} form of a web link (same path, the server named by host and port). */
    public static String appLink(String webLink) {
        if (webLink == null) {
            return null;
        }
        final Uri uri = Uri.parse(webLink);
        final String scheme = lower(uri.getScheme());
        if (!scheme.equals("http") && !scheme.equals("https") || uri.getHost() == null) {
            return null;
        }
        final int port = uri.getPort();
        final String authority = uri.getHost() + (port != -1 && port != defaultPort(scheme) ? ":" + port : "");
        return uri.buildUpon().scheme(SCHEME).encodedAuthority(authority).build().toString();
    }

    /** Mattermost ids: 26 characters of [a-z0-9]. */
    static boolean isId(String value) {
        if (value.length() != 26) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            final char c = value.charAt(i);
            if (!(c >= 'a' && c <= 'z' || c >= '0' && c <= '9')) {
                return false;
            }
        }
        return true;
    }

    static boolean isTeamName(String value) {
        if (value.isEmpty() || value.length() > 64) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            final char c = value.charAt(i);
            if (!(c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '-' || c == '_')) {
                return false;
            }
        }
        return true;
    }

    static boolean isChannelName(String value) {
        if (value.isEmpty() || value.length() > 64) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            final char c = value.charAt(i);
            if (!(c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '-' || c == '_')) {
                return false;
            }
        }
        return true;
    }

    static int defaultPort(String scheme) {
        return "https".equals(scheme) ? 443 : 80;
    }

    private static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.US);
    }

    private static final class Base {
        final String scheme;
        final String host;
        final int port;
        /** Encoded path without a trailing slash ("" at the root). */
        final String path;

        private Base(String scheme, String host, int port, String path) {
            this.scheme = scheme;
            this.host = host;
            this.port = port;
            this.path = path;
        }

        static Base of(String serverUrl) {
            if (TextUtils.isEmpty(serverUrl)) {
                return null;
            }
            String trimmed = serverUrl.trim();
            while (trimmed.endsWith("/")) {
                trimmed = trimmed.substring(0, trimmed.length() - 1);
            }
            final Uri uri = Uri.parse(trimmed);
            final String scheme = lower(uri.getScheme());
            final String host = lower(uri.getHost());
            if (!(scheme.equals("http") || scheme.equals("https")) || host.isEmpty()) {
                return null;
            }
            String path = uri.getEncodedPath() == null ? "" : uri.getEncodedPath();
            while (path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }
            return new Base(scheme, host, uri.getPort() != -1 ? uri.getPort() : defaultPort(scheme), path);
        }
    }
}
