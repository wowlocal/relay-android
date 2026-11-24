package org.telegram.relay.mattermost.ws;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** The connection state machine against a scripted transport: handshake, seq tracking, resume, auth, backoff. */
public class MattermostWebSocketTest {
    private static final String HELLO = "{\"event\":\"hello\",\"data\":{\"connection_id\":\"conn-1\",\"server_version\":\"11.7.14.1.abc.false\"},\"broadcast\":{},\"seq\":0}";

    // MARK: Fakes

    static final class FakeConnection implements WebSocketTransport.Connection {
        final String url;
        final Map<String, String> headers;
        final WebSocketTransport.Callback callback;
        final List<String> sent = new CopyOnWriteArrayList<String>();
        final BlockingQueue<JsonObject> sentQueue = new LinkedBlockingQueue<JsonObject>();
        final AtomicBoolean closed = new AtomicBoolean();
        volatile boolean open;

        FakeConnection(String url, Map<String, String> headers, WebSocketTransport.Callback callback) {
            this.url = url;
            this.headers = headers;
            this.callback = callback;
        }

        @Override
        public boolean send(String text) {
            if (!open || closed.get()) {
                return false;
            }
            sent.add(text);
            sentQueue.add(JsonParser.parseString(text).getAsJsonObject());
            return true;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                callback.onClosed(this, null, 0);
            }
        }

        void serverOpen() {
            open = true;
            callback.onOpen(this);
        }

        void serverSend(String text) {
            callback.onText(this, text);
        }

        void serverClose(int status) {
            if (closed.compareAndSet(false, true)) {
                callback.onClosed(this, new java.io.IOException("closed by test"), status);
            }
        }

        /** The next frame the client sent with this action. */
        JsonObject awaitAction(String action) throws InterruptedException {
            long deadline = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < deadline) {
                JsonObject frame = sentQueue.poll(100, TimeUnit.MILLISECONDS);
                if (frame != null && frame.get("action").getAsString().equals(action)) {
                    return frame;
                }
            }
            fail("client never sent " + action);
            return null;
        }
    }

    static final class FakeTransport implements WebSocketTransport {
        final BlockingQueue<FakeConnection> connections = new LinkedBlockingQueue<FakeConnection>();
        final AtomicInteger count = new AtomicInteger();

        @Override
        public Connection connect(String url, Map<String, String> headers, int connectTimeoutMillis, Callback callback) {
            FakeConnection connection = new FakeConnection(url, headers, callback);
            count.incrementAndGet();
            connections.add(connection);
            return connection;
        }

        FakeConnection next() throws InterruptedException {
            FakeConnection connection = connections.poll(5, TimeUnit.SECONDS);
            assertNotNull("expected a connection attempt", connection);
            return connection;
        }
    }

    static final class Recorder implements WebSocketListener {
        final BlockingQueue<ConnectionState> states = new LinkedBlockingQueue<ConnectionState>();
        final BlockingQueue<WebSocketEvent> events = new LinkedBlockingQueue<WebSocketEvent>();

        @Override
        public void onStateChanged(MattermostWebSocket socket, ConnectionState state) {
            states.add(state);
        }

        @Override
        public void onEvent(MattermostWebSocket socket, WebSocketEvent event) {
            events.add(event);
        }

        ConnectionState awaitState(ConnectionState.Kind kind) throws InterruptedException {
            long deadline = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < deadline) {
                ConnectionState state = states.poll(100, TimeUnit.MILLISECONDS);
                if (state != null && state.kind == kind) {
                    return state;
                }
            }
            fail("never reached " + kind);
            return null;
        }

        WebSocketEvent awaitEvent() throws InterruptedException {
            WebSocketEvent event = events.poll(5, TimeUnit.SECONDS);
            assertNotNull("expected an event", event);
            return event;
        }

        void assertNoEvent() throws InterruptedException {
            assertNull(events.poll(200, TimeUnit.MILLISECONDS));
        }
    }

    private final List<MattermostWebSocket> sockets = new ArrayList<MattermostWebSocket>();

    @After
    public void tearDown() {
        for (MattermostWebSocket socket : sockets) {
            socket.shutdown();
        }
    }

    private MattermostWebSocket socket(FakeTransport transport, Recorder recorder, MattermostWebSocket.TokenVerifier verifier) {
        MattermostWebSocket.Configuration configuration = new MattermostWebSocket.Configuration();
        configuration.transport = transport;
        configuration.backoff = new BackoffPolicy(20, 2, 200, 0);
        configuration.random = new Random(1);
        configuration.pingIntervalMillis = 0;
        configuration.handshakeTimeoutMillis = 300;
        configuration.actionTimeoutMillis = 500;
        configuration.tokenVerifier = verifier;
        MattermostWebSocket socket = new MattermostWebSocket("http://localhost:8065/", "tok", configuration, recorder);
        sockets.add(socket);
        return socket;
    }

    private static String event(String name, long seq) {
        return "{\"event\":\"" + name + "\",\"data\":{\"user_id\":\"u\"},\"broadcast\":{\"channel_id\":\"c\"},\"seq\":" + seq + "}";
    }

    private static String reply(long seq) {
        return "{\"status\":\"OK\",\"seq_reply\":" + seq + ",\"data\":{}}";
    }

    /** Opens a fresh connection and completes it with hello. */
    private FakeConnection handshake(FakeTransport transport, Recorder recorder) throws InterruptedException {
        FakeConnection connection = transport.next();
        connection.serverOpen();
        connection.awaitAction("ping");
        connection.serverSend(HELLO);
        ConnectionState connected = recorder.awaitState(ConnectionState.Kind.CONNECTED);
        assertEquals("conn-1", connected.info.connectionId);
        assertFalse(connected.info.resumed);
        assertEquals(WebSocketEventType.HELLO, recorder.awaitEvent().type);
        return connection;
    }

    // MARK: Tests

    @Test
    public void urlForServer() {
        assertEquals("ws://localhost:8065/api/v4/websocket", MattermostWebSocket.webSocketUrl("http://localhost:8065/"));
        assertEquals("wss://chat.example.com/mm/api/v4/websocket", MattermostWebSocket.webSocketUrl("https://chat.example.com/mm?x=1#y"));
        assertNull(MattermostWebSocket.webSocketUrl("ftp://example.com"));
    }

    @Test
    public void backoffGrowsAndCaps() {
        BackoffPolicy policy = new BackoffPolicy(1000, 2, 60_000, 0.5);
        assertEquals(500, policy.delayMillis(1, 0));
        assertEquals(1000, policy.delayMillis(1, 1));
        assertEquals(2000, policy.delayMillis(2, 1));
        assertEquals(8000, policy.delayMillis(4, 1));
        assertEquals(60_000, policy.delayMillis(20, 1));
        assertEquals(30_000, policy.delayMillis(20, 0));
    }

    @Test
    public void handshakeDeliversEventsAndTracksSeq() throws Exception {
        FakeTransport transport = new FakeTransport();
        Recorder recorder = new Recorder();
        MattermostWebSocket socket = socket(transport, recorder, null);
        socket.connect();
        FakeConnection connection = transport.next();
        assertEquals("ws://localhost:8065/api/v4/websocket", connection.url);
        assertEquals("Bearer tok", connection.headers.get("Authorization"));
        connection.serverOpen();
        JsonObject probe = connection.awaitAction("ping");
        connection.serverSend(HELLO);
        ConnectionState connected = recorder.awaitState(ConnectionState.Kind.CONNECTED);
        assertEquals("11.7.14.1.abc.false", connected.info.serverVersion);
        assertEquals(WebSocketEventType.HELLO, recorder.awaitEvent().type);
        // The probe reply after hello is swallowed.
        connection.serverSend(reply(probe.get("seq").getAsLong()));

        connection.serverSend(event("typing", 1));
        connection.serverSend(event("typing", 2));
        connection.serverSend(event("typing", 2));  // duplicate: dropped
        assertEquals(Long.valueOf(1), recorder.awaitEvent().seq);
        assertEquals(Long.valueOf(2), recorder.awaitEvent().seq);
        recorder.assertNoEvent();
        assertEquals(3L, socket.debugResumeState().get("next_sequence"));
        assertTrue(socket.isConnected());
    }

    @Test
    public void sequenceGapReconnectsAndResumes() throws Exception {
        FakeTransport transport = new FakeTransport();
        Recorder recorder = new Recorder();
        MattermostWebSocket socket = socket(transport, recorder, null);
        socket.connect();
        FakeConnection first = handshake(transport, recorder);
        first.serverSend(event("posted", 1));
        assertEquals(Long.valueOf(1), recorder.awaitEvent().seq);

        // seq 2 and 3 are lost: the gap at 4 forces a reconnect asking to resume from 2.
        first.serverSend(event("posted", 4));
        recorder.awaitState(ConnectionState.Kind.WAITING_TO_RECONNECT);
        assertTrue(first.closed.get());
        FakeConnection second = transport.next();
        assertEquals("ws://localhost:8065/api/v4/websocket?connection_id=conn-1&sequence_number=2", second.url);
        second.serverOpen();
        JsonObject probe = second.awaitAction("ping");
        // The server replays the missed events (one of them twice) without hello, then answers the probe.
        second.serverSend(event("posted", 1));
        second.serverSend(event("posted", 2));
        second.serverSend(event("posted", 3));
        second.serverSend(reply(probe.get("seq").getAsLong()));
        ConnectionState resumed = recorder.awaitState(ConnectionState.Kind.CONNECTED);
        assertTrue(resumed.info.resumed);
        assertEquals("conn-1", resumed.info.connectionId);
        assertEquals(Long.valueOf(2), recorder.awaitEvent().seq);
        assertEquals(Long.valueOf(3), recorder.awaitEvent().seq);
        second.serverSend(event("posted", 4));
        assertEquals(Long.valueOf(4), recorder.awaitEvent().seq);
        recorder.assertNoEvent();
    }

    @Test
    public void failedResumeReportsNewConnection() throws Exception {
        FakeTransport transport = new FakeTransport();
        Recorder recorder = new Recorder();
        MattermostWebSocket socket = socket(transport, recorder, null);
        socket.connect();
        FakeConnection first = handshake(transport, recorder);
        first.serverSend(event("posted", 1));
        recorder.awaitEvent();
        first.serverClose(0);
        recorder.awaitState(ConnectionState.Kind.WAITING_TO_RECONNECT);

        FakeConnection second = transport.next();
        assertTrue(second.url.contains("connection_id=conn-1&sequence_number=2"));
        second.serverOpen();
        second.awaitAction("ping");
        // The server no longer has the connection: a new hello with a new id.
        second.serverSend(HELLO.replace("conn-1", "conn-2"));
        ConnectionState connected = recorder.awaitState(ConnectionState.Kind.CONNECTED);
        assertFalse("events may be lost: catch up over REST", connected.info.resumed);
        assertEquals("conn-2", connected.info.connectionId);
        assertEquals(WebSocketEventType.HELLO, recorder.awaitEvent().type);
        second.serverSend(event("posted", 1));
        assertEquals(Long.valueOf(1), recorder.awaitEvent().seq);
    }

    @Test
    public void unauthorizedUpgradeStopsReconnecting() throws Exception {
        FakeTransport transport = new FakeTransport();
        Recorder recorder = new Recorder();
        MattermostWebSocket socket = socket(transport, recorder, null);
        socket.connect();
        transport.next().serverClose(401);
        recorder.awaitState(ConnectionState.Kind.UNAUTHORIZED);
        Thread.sleep(150);
        assertEquals(1, transport.count.get());
        assertEquals(ConnectionState.Kind.UNAUTHORIZED, socket.getState().kind);
    }

    @Test
    public void notAuthenticatedProbeReplyIsUnauthorized() throws Exception {
        FakeTransport transport = new FakeTransport();
        Recorder recorder = new Recorder();
        MattermostWebSocket socket = socket(transport, recorder, null);
        socket.connect();
        FakeConnection connection = transport.next();
        connection.serverOpen();
        JsonObject probe = connection.awaitAction("ping");
        connection.serverSend("{\"status\":\"FAIL\",\"seq_reply\":" + probe.get("seq").getAsLong() + ",\"error\":{\"id\":\"api.web_socket_router.not_authenticated.app_error\",\"status_code\":401}}");
        recorder.awaitState(ConnectionState.Kind.UNAUTHORIZED);
    }

    @Test
    public void silentServerIsCheckedWithTheTokenVerifier() throws Exception {
        FakeTransport transport = new FakeTransport();
        Recorder recorder = new Recorder();
        final AtomicInteger checks = new AtomicInteger();
        final AtomicBoolean accepted = new AtomicBoolean(true);
        MattermostWebSocket socket = socket(transport, recorder, new MattermostWebSocket.TokenVerifier() {
            @Override
            public boolean isTokenAccepted() {
                checks.incrementAndGet();
                return accepted.get();
            }
        });
        socket.connect();
        // Opens, never says hello: handshake timeout → verifier says the token is fine → retry.
        transport.next().serverOpen();
        recorder.awaitState(ConnectionState.Kind.WAITING_TO_RECONNECT);
        assertEquals(1, checks.get());
        accepted.set(false);
        transport.next().serverOpen();
        recorder.awaitState(ConnectionState.Kind.UNAUTHORIZED);
        assertEquals(2, checks.get());
    }

    @Test
    public void actionsGetReplies() throws Exception {
        FakeTransport transport = new FakeTransport();
        Recorder recorder = new Recorder();
        final MattermostWebSocket socket = socket(transport, recorder, null);
        try {
            socket.ping();
            fail("not connected yet");
        } catch (WebSocketActionException e) {
            assertEquals(WebSocketActionException.Reason.NOT_CONNECTED, e.getReason());
        }
        socket.connect();
        final FakeConnection connection = handshake(transport, recorder);

        assertTrue(socket.userTyping("chan", null));
        JsonObject typing = connection.awaitAction("user_typing");
        assertEquals("chan", typing.getAsJsonObject("data").get("channel_id").getAsString());
        assertEquals("", typing.getAsJsonObject("data").get("parent_id").getAsString());

        Thread server = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    JsonObject request = connection.awaitAction("get_statuses");
                    connection.serverSend("{\"status\":\"OK\",\"seq_reply\":" + request.get("seq").getAsLong() + ",\"data\":{\"u1\":\"online\"}}");
                } catch (InterruptedException ignored) {
                    // test ends
                }
            }
        });
        server.start();
        Map<String, String> statuses = socket.getStatuses();
        assertEquals(Collections.singletonMap("u1", "online"), statuses);

        try {
            socket.sendAction("ping", new JsonObject(), 200);
            fail("no reply");
        } catch (WebSocketActionException e) {
            assertEquals(WebSocketActionException.Reason.TIMEOUT, e.getReason());
        }
    }

    @Test
    public void disconnectStopsAndConnectResumes() throws Exception {
        FakeTransport transport = new FakeTransport();
        Recorder recorder = new Recorder();
        MattermostWebSocket socket = socket(transport, recorder, null);
        socket.connect();
        FakeConnection first = handshake(transport, recorder);
        first.serverSend(event("posted", 1));
        recorder.awaitEvent();
        socket.disconnect();
        recorder.awaitState(ConnectionState.Kind.DISCONNECTED);
        assertTrue(first.closed.get());
        Thread.sleep(100);
        assertEquals(1, transport.count.get());

        socket.connect();
        FakeConnection second = transport.next();
        assertTrue("resume state is kept across disconnect()", second.url.endsWith("connection_id=conn-1&sequence_number=2"));
        socket.resetResumeState();
        socket.reconnectNow();
        FakeConnection third = transport.next();
        assertEquals("ws://localhost:8065/api/v4/websocket", third.url);
    }
}
