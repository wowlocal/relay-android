package org.telegram.relay.account;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.ApplicationLoader;

/**
 * Per-account Relay session ({@code relay_account<n>} preferences).
 *
 * <p>The token is stored in app-private SharedPreferences (mode private, excluded from backups by the
 * app's backup rules like Telegram's own {@code userconfig}). {@code androidx.security:security-crypto}
 * ({@code EncryptedSharedPreferences}) is not among the app's dependencies; switching to it (or to a
 * Keystore-wrapped key) is a later hardening step.
 */
public final class RelaySessionStore {
    private RelaySessionStore() {
    }

    private static final Object lock = new Object();
    private static final RelaySession[] cache = new RelaySession[8];
    private static final boolean[] loaded = new boolean[8];

    private static SharedPreferences prefs(int account) {
        return ApplicationLoader.applicationContext.getSharedPreferences("relay_account" + account, Context.MODE_PRIVATE);
    }

    /** The account's session, or null when the slot is not logged in to Mattermost. */
    public static RelaySession get(int account) {
        synchronized (lock) {
            if (!loaded[account]) {
                SharedPreferences p = prefs(account);
                RelaySession session = new RelaySession(
                        p.getString("server", null),
                        p.getString("token", null),
                        p.getString("user_id", null),
                        p.getString("login_id", ""),
                        p.getString("server_version", ""),
                        p.getLong("created_at", 0));
                cache[account] = session.isValid() ? session : null;
                loaded[account] = true;
            }
            return cache[account];
        }
    }

    public static void put(int account, RelaySession session) {
        synchronized (lock) {
            prefs(account).edit()
                    .clear()
                    .putString("server", session.serverUrl)
                    .putString("token", session.token)
                    .putString("user_id", session.userId)
                    .putString("login_id", session.loginId)
                    .putString("server_version", session.serverVersion)
                    .putLong("created_at", session.createdAt)
                    .commit();
            cache[account] = session;
            loaded[account] = true;
        }
    }

    public static void clear(int account) {
        synchronized (lock) {
            prefs(account).edit().clear().commit();
            cache[account] = null;
            loaded[account] = true;
        }
    }

    /** The last server URL used on this device (prefills the server step). */
    public static String lastServerUrl() {
        return ApplicationLoader.applicationContext.getSharedPreferences("relay_global", Context.MODE_PRIVATE).getString("last_server", "");
    }

    public static void setLastServerUrl(String url) {
        ApplicationLoader.applicationContext.getSharedPreferences("relay_global", Context.MODE_PRIVATE).edit().putString("last_server", url).apply();
    }
}
