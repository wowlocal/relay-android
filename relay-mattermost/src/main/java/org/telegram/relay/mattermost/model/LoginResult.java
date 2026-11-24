package org.telegram.relay.mattermost.model;

/** {@code POST /users/login}: the user and the session token from the {@code Token} header. */
public class LoginResult {
    public final User user;
    public final String token;

    public LoginResult(User user, String token) {
        this.user = user;
        this.token = token;
    }
}
