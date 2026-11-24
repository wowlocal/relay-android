package org.telegram.relay.mattermost.ws;

/** The WebSocket's connection state. */
public final class ConnectionState {
    public enum Kind {
        /** Not connected and not trying (initial state, or after {@code disconnect()}). */
        DISCONNECTED,
        /** Opening a connection; {@link #attempt} counts up while retrying. */
        CONNECTING,
        /** The server sent {@code hello}, or accepted the resumed connection; see {@link #info}. */
        CONNECTED,
        /** Waiting {@link #delayMillis} before attempt {@link #attempt}. */
        WAITING_TO_RECONNECT,
        /**
         * The server rejected the token. Reconnecting stops until {@code connect()} is called again
         * (normally after a new login). Mattermost accepts the upgrade even with a bad token and then
         * stays silent, so this is reached via a 401 on upgrade, a {@code not_authenticated} reply, or
         * the token verifier after a connection never completed its handshake.
         */
        UNAUTHORIZED
    }

    public static final ConnectionState DISCONNECTED = new ConnectionState(Kind.DISCONNECTED, 0, 0, null);
    public static final ConnectionState UNAUTHORIZED = new ConnectionState(Kind.UNAUTHORIZED, 0, 0, null);

    public final Kind kind;
    public final int attempt;
    public final long delayMillis;
    /** Non-null for {@link Kind#CONNECTED}. */
    public final ConnectionInfo info;

    private ConnectionState(Kind kind, int attempt, long delayMillis, ConnectionInfo info) {
        this.kind = kind;
        this.attempt = attempt;
        this.delayMillis = delayMillis;
        this.info = info;
    }

    public static ConnectionState connecting(int attempt) {
        return new ConnectionState(Kind.CONNECTING, attempt, 0, null);
    }

    public static ConnectionState connected(ConnectionInfo info) {
        return new ConnectionState(Kind.CONNECTED, 0, 0, info);
    }

    public static ConnectionState waitingToReconnect(int attempt, long delayMillis) {
        return new ConnectionState(Kind.WAITING_TO_RECONNECT, attempt, delayMillis, null);
    }

    public boolean isConnected() {
        return kind == Kind.CONNECTED;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof ConnectionState)) {
            return false;
        }
        ConnectionState other = (ConnectionState) o;
        return kind == other.kind && attempt == other.attempt && delayMillis == other.delayMillis
                && (info == null ? other.info == null : info.equals(other.info));
    }

    @Override
    public int hashCode() {
        return kind.hashCode() * 31 + attempt;
    }

    @Override
    public String toString() {
        switch (kind) {
            case CONNECTING:
                return "connecting(" + attempt + ")";
            case CONNECTED:
                return "connected(" + info + ")";
            case WAITING_TO_RECONNECT:
                return "waiting(" + attempt + ", " + delayMillis + "ms)";
            default:
                return kind.name().toLowerCase();
        }
    }
}
