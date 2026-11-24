package org.telegram.relay.mattermost.ws;

import java.util.Map;

/** The socket under {@link MattermostWebSocket}; tests inject a fake. */
public interface WebSocketTransport {
    interface Connection {
        /** Queues a text frame; false when the connection is not open. */
        boolean send(String text);

        void close();
    }

    /** Called from transport threads; each connection reports {@link #onClosed} at most once. */
    interface Callback {
        void onOpen(Connection connection);

        void onText(Connection connection, String text);

        /**
         * The connection failed to open or closed.
         *
         * @param httpStatus the HTTP status of a rejected upgrade, or 0.
         */
        void onClosed(Connection connection, Throwable error, int httpStatus);
    }

    /** Starts connecting without blocking and returns the connection handle. */
    Connection connect(String url, Map<String, String> headers, int connectTimeoutMillis, Callback callback);
}
