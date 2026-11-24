package org.telegram.relay.mattermost.ws;

/**
 * Receives WebSocket events and state changes, on the WebSocket's own thread, in order. Don't block
 * it (hand work to another thread); never call the blocking {@code sendAction} from it.
 */
public interface WebSocketListener {
    void onStateChanged(MattermostWebSocket socket, ConnectionState state);

    /** Every server event, {@code hello} included, after duplicate replays have been dropped. */
    void onEvent(MattermostWebSocket socket, WebSocketEvent event);
}
