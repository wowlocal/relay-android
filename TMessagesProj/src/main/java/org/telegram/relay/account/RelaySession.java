package org.telegram.relay.account;

/** A Mattermost session of one Telegram account slot. Immutable. */
public final class RelaySession {
    /** Normalised site URL, e.g. {@code http://localhost:8065} (no trailing slash, no {@code /api/v4}). */
    public final String serverUrl;
    /** Session token from {@code POST /users/login}. */
    public final String token;
    /** Mattermost user id of the logged-in user. */
    public final String userId;
    /** Login id the user typed (username or email); shown when the session expires. */
    public final String loginId;
    /** Server version at login, e.g. {@code 11.7.14} (may be empty). */
    public final String serverVersion;
    /** {@code System.currentTimeMillis()} at login. */
    public final long createdAt;

    public RelaySession(String serverUrl, String token, String userId, String loginId, String serverVersion, long createdAt) {
        this.serverUrl = serverUrl;
        this.token = token;
        this.userId = userId;
        this.loginId = loginId == null ? "" : loginId;
        this.serverVersion = serverVersion == null ? "" : serverVersion;
        this.createdAt = createdAt;
    }

    public boolean isValid() {
        return serverUrl != null && !serverUrl.isEmpty() && token != null && !token.isEmpty() && userId != null && !userId.isEmpty();
    }

    @Override
    public String toString() {
        return "RelaySession(" + serverUrl + " user=" + userId + ")";
    }
}
