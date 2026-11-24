package org.telegram.relay.mattermost.ws;

import com.neovisionaries.ws.client.OpeningHandshakeException;
import com.neovisionaries.ws.client.WebSocket;
import com.neovisionaries.ws.client.WebSocketAdapter;
import com.neovisionaries.ws.client.WebSocketException;
import com.neovisionaries.ws.client.WebSocketFactory;
import com.neovisionaries.ws.client.WebSocketFrame;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** {@link WebSocketTransport} over nv-websocket-client. */
public final class NvWebSocketTransport implements WebSocketTransport {
    private final WebSocketFactory factory;

    public NvWebSocketTransport() {
        this(new WebSocketFactory());
    }

    /** Pass a factory configured with a proxy, SSL context or server names. */
    public NvWebSocketTransport(WebSocketFactory factory) {
        this.factory = factory;
    }

    @Override
    public Connection connect(String url, Map<String, String> headers, int connectTimeoutMillis, final Callback callback) {
        final NvConnection connection = new NvConnection();
        WebSocket socket;
        try {
            socket = factory.createSocket(url, connectTimeoutMillis);
        } catch (IOException e) {
            connection.closed(callback, e, 0);
            return connection;
        }
        connection.socket = socket;
        for (Map.Entry<String, String> header : headers.entrySet()) {
            socket.addHeader(header.getKey(), header.getValue());
        }
        socket.setMissingCloseFrameAllowed(true);
        socket.addListener(new WebSocketAdapter() {
            @Override
            public void onConnected(WebSocket websocket, Map<String, List<String>> responseHeaders) {
                if (connection.closeRequested) {
                    // Closed while the handshake was in flight.
                    websocket.disconnect();
                    return;
                }
                callback.onOpen(connection);
            }

            @Override
            public void onTextMessage(WebSocket websocket, String text) {
                callback.onText(connection, text);
            }

            @Override
            public void onConnectError(WebSocket websocket, WebSocketException exception) {
                int status = 0;
                if (exception instanceof OpeningHandshakeException) {
                    OpeningHandshakeException handshake = (OpeningHandshakeException) exception;
                    if (handshake.getStatusLine() != null) {
                        status = handshake.getStatusLine().getStatusCode();
                    }
                }
                connection.closed(callback, exception, status);
            }

            @Override
            public void onDisconnected(WebSocket websocket, WebSocketFrame serverCloseFrame, WebSocketFrame clientCloseFrame, boolean closedByServer) {
                connection.closed(callback, null, 0);
            }
        });
        socket.connectAsynchronously();
        return connection;
    }

    private static final class NvConnection implements Connection {
        volatile WebSocket socket;
        volatile boolean closeRequested;
        private final AtomicBoolean closed = new AtomicBoolean(false);

        void closed(Callback callback, Throwable error, int status) {
            if (closed.compareAndSet(false, true)) {
                callback.onClosed(this, error, status);
            }
        }

        @Override
        public boolean send(String text) {
            WebSocket current = socket;
            if (current == null || closed.get() || !current.isOpen()) {
                return false;
            }
            current.sendText(text);
            return true;
        }

        @Override
        public void close() {
            closeRequested = true;
            WebSocket current = socket;
            if (current != null) {
                current.disconnect();
            }
        }
    }
}
