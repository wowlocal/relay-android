package org.telegram.relay.mattermost.ws;

/** A completed WebSocket handshake. */
public final class ConnectionInfo {
    public final String connectionId;
    /** From the last {@code hello}, e.g. {@code 11.7.14.39757492507.<hash>.false}; may be null. */
    public final String serverVersion;
    /**
     * True when the server resumed the previous connection and replays the events missed in between.
     * When false after a reconnect, events may have been lost: the sync layer must catch up over REST.
     *
     * <p>Mattermost sends no {@code hello} on a resumed connection, so the client sends a {@code ping}
     * right after connecting: a reply without a preceding {@code hello} means "resumed". A {@code hello}
     * with the previous connection id also counts as resumed.
     */
    public final boolean resumed;

    public ConnectionInfo(String connectionId, String serverVersion, boolean resumed) {
        this.connectionId = connectionId;
        this.serverVersion = serverVersion;
        this.resumed = resumed;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof ConnectionInfo)) {
            return false;
        }
        ConnectionInfo other = (ConnectionInfo) o;
        return eq(connectionId, other.connectionId) && eq(serverVersion, other.serverVersion) && resumed == other.resumed;
    }

    private static boolean eq(Object a, Object b) {
        return a == null ? b == null : a.equals(b);
    }

    @Override
    public int hashCode() {
        return (connectionId != null ? connectionId.hashCode() : 0) * 31 + (resumed ? 1 : 0);
    }

    @Override
    public String toString() {
        return "ConnectionInfo(" + connectionId + (resumed ? " resumed" : " new") + ")";
    }
}
