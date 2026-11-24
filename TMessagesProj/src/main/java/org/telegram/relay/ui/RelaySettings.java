package org.telegram.relay.ui;

import org.telegram.relay.RelayConfig;
import org.telegram.relay.account.RelayAccount;
import org.telegram.relay.account.RelaySession;
import org.telegram.relay.account.RelaySessionStore;
import org.telegram.relay.mattermost.model.User;

import java.util.HashMap;

/** Mattermost profile facts that Telegram's settings screen shows (TL users have no email). */
public final class RelaySettings {
    private RelaySettings() {
    }

    private static final HashMap<String, String> emailCache = new HashMap<>();

    /**
     * Email of the logged-in Mattermost user of {@code account} from the bridge's user cache, or
     * the login id when it was an email, or null.
     */
    public static String selfEmail(int account) {
        final RelaySession session = RelaySessionStore.get(account);
        if (session == null || !session.isValid()) {
            return null;
        }
        final String key = account + ":" + session.userId;
        synchronized (emailCache) {
            final String cached = emailCache.get(key);
            if (cached != null) {
                return cached.isEmpty() ? null : cached;
            }
        }
        String email = null;
        try {
            final RelayAccount relayAccount = RelayAccount.get(account);
            final User user = relayAccount != null ? relayAccount.cachedUser(session.userId) : null;
            if (user != null && user.email != null && !user.email.isEmpty()) {
                email = user.email;
            }
        } catch (Exception e) {
            RelayConfig.logError(RelayConfig.TAG_AUTH, "self email", e);
        }
        if (email == null && session.loginId.contains("@")) {
            email = session.loginId;
        }
        if (email != null) {
            synchronized (emailCache) {
                emailCache.put(key, email);
            }
        }
        return email;
    }
}
