package org.telegram.relay.mattermost;

import org.telegram.relay.mattermost.model.AppError;

import java.io.IOException;

/**
 * Every failure of a {@link MattermostClient} call.
 *
 * <ul>
 *   <li>{@link Kind#HTTP}: the server answered with a non-2xx status. {@link #getStatusCode()} is the
 *       status and {@link #getErrorId()} the Mattermost error id (e.g.
 *       {@code api.user.login.invalid_credentials_email_username}) when the body was an AppError.</li>
 *   <li>{@link Kind#TRANSPORT}: no usable response (offline, DNS, TLS, timeout).
 *       {@link #getStatusCode()} is 0.</li>
 *   <li>{@link Kind#DECODING}: a 2xx response whose body could not be interpreted.</li>
 * </ul>
 */
public class MattermostException extends IOException {
    public enum Kind {
        HTTP,
        TRANSPORT,
        DECODING
    }

    private final Kind kind;
    private final int statusCode;
    private final AppError appError;
    /** Seconds from {@code Retry-After} / {@code X-RateLimit-Reset} on a 429, or -1. */
    private final long retryAfterSeconds;

    public MattermostException(Kind kind, int statusCode, AppError appError, String message, Throwable cause, long retryAfterSeconds) {
        super(message, cause);
        this.kind = kind;
        this.statusCode = statusCode;
        this.appError = appError;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public static MattermostException http(int statusCode, AppError appError, long retryAfterSeconds) {
        String message;
        if (appError != null && appError.message != null && !appError.message.isEmpty()) {
            message = "HTTP " + statusCode + ": " + appError.message + (appError.id.isEmpty() ? "" : " (" + appError.id + ")");
        } else {
            message = "HTTP " + statusCode;
        }
        return new MattermostException(Kind.HTTP, statusCode, appError, message, null, retryAfterSeconds);
    }

    public static MattermostException transport(String message, Throwable cause) {
        return new MattermostException(Kind.TRANSPORT, 0, null, message, cause, -1);
    }

    public static MattermostException decoding(String message, Throwable cause) {
        return new MattermostException(Kind.DECODING, 0, null, message, cause, -1);
    }

    public Kind getKind() {
        return kind;
    }

    /** The HTTP status, or 0 when there was no HTTP response. */
    public int getStatusCode() {
        return statusCode;
    }

    /** The server's error body, when there was one. */
    public AppError getAppError() {
        return appError;
    }

    /** The Mattermost error id ({@code ""} when unknown). */
    public String getErrorId() {
        return appError != null && appError.id != null ? appError.id : "";
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }

    public boolean isUnauthorized() {
        return kind == Kind.HTTP && statusCode == 401;
    }

    public boolean isForbidden() {
        return kind == Kind.HTTP && statusCode == 403;
    }

    public boolean isNotFound() {
        return kind == Kind.HTTP && statusCode == 404;
    }

    public boolean isRateLimited() {
        return kind == Kind.HTTP && statusCode == 429;
    }

    /** Retrying the same request later may succeed: network errors, 408, 429 and 5xx. */
    public boolean isTransient() {
        if (kind == Kind.TRANSPORT) {
            return true;
        }
        return kind == Kind.HTTP && (statusCode >= 500 || statusCode == 408 || statusCode == 429);
    }

    /** The login needs an MFA token (401 {@code mfa.validate_token.authenticate.app_error}). */
    public boolean isMfaRequired() {
        String id = getErrorId();
        return id.equals("mfa.validate_token.authenticate.app_error")
                || id.equals("ent.mfa.validate_token.authenticate.app_error");
    }

    /** The login sent an MFA token and it was wrong or expired. */
    public boolean isInvalidMfaToken() {
        return getErrorId().equals("api.user.check_user_mfa.bad_code.app_error");
    }
}
