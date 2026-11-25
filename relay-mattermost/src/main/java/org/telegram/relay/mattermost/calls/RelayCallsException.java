package org.telegram.relay.mattermost.calls;

/**
 * A failed plugin request. {@link #status} is the HTTP status (0 for transport/decoding problems);
 * {@link #call} is the conflicting call of a {@code 409} when the plugin sent one
 * ({@link #isCallInProgress()} for ring).
 */
public final class RelayCallsException extends Exception {
    public static final int INVALID_CHANNEL = -1;
    public static final int INVALID_SERVER = -2;
    public static final int INVALID_RESPONSE = -3;

    public final int status;
    public final RelayCall call;

    public RelayCallsException(int status, String message, RelayCall call) {
        super(message);
        this.status = status;
        this.call = call;
    }

    public RelayCallsException(String message, Throwable cause) {
        super(message, cause);
        this.status = 0;
        this.call = null;
    }

    public boolean isCallInProgress() {
        return status == 409 && call != null;
    }
}
