package org.telegram.relay.mattermost.ws;

/** A WebSocket action that did not succeed. */
public class WebSocketActionException extends Exception {
    public enum Reason {
        /** Not connected, or the handshake has not finished. */
        NOT_CONNECTED,
        /** The connection closed before the server replied. */
        CONNECTION_CLOSED,
        /** No reply in time. */
        TIMEOUT,
        /** The server replied with a non-OK status; see {@link #getResponse()}. */
        ACTION_FAILED
    }

    private final Reason reason;
    private final WebSocketResponse response;

    public WebSocketActionException(Reason reason, WebSocketResponse response) {
        super(reason.name().toLowerCase() + (response != null && response.error != null ? ": " + response.error : ""));
        this.reason = reason;
        this.response = response;
    }

    public Reason getReason() {
        return reason;
    }

    public WebSocketResponse getResponse() {
        return response;
    }
}
