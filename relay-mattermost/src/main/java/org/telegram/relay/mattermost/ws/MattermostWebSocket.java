package org.telegram.relay.mattermost.ws;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import org.telegram.relay.mattermost.Json;
import org.telegram.relay.mattermost.UrlCodec;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Mattermost WebSocket client ({@code ws(s)://<server>/api/v4/websocket}): connects and
 * authenticates, parses events, reconnects with exponential backoff and jitter, tracks {@code seq}
 * and resumes the previous connection ({@code connection_id} + {@code sequence_number}) so the server
 * replays missed events.
 *
 * <p>A port of the iOS {@code MattermostWebSocket}. All state lives on one internal thread, which
 * also calls the {@link WebSocketListener}. After a reconnect that was not resumed
 * ({@link ConnectionInfo#resumed} false) events may have been missed and the caller must catch up
 * over REST. A gap in {@code seq} makes the socket reconnect and ask the server to replay.
 */
public final class MattermostWebSocket {
    public enum Authentication {
        /** {@code Authorization: Bearer} on the upgrade request (the server only resumes these). */
        HEADER,
        /** An {@code authentication_challenge} action after connecting. */
        CHALLENGE,
        HEADER_AND_CHALLENGE
    }

    /** Confirms a token after a connection never completed its handshake (Mattermost stays silent for bad tokens). */
    public interface TokenVerifier {
        /** False only when the token is definitely rejected (e.g. REST answered 401). Called off the socket thread. */
        boolean isTokenAccepted();
    }

    public interface Logger {
        void log(String message);
    }

    public static final class Configuration {
        public Authentication authentication = Authentication.HEADER;
        public BackoffPolicy backoff = new BackoffPolicy();
        /** Interval of {@code ping} actions that detect dead connections; 0 disables. */
        public long pingIntervalMillis = 30_000;
        /** How long to wait for an action reply (pings included). */
        public long actionTimeoutMillis = 10_000;
        /** How long a new connection may take to complete its handshake; 0 waits forever. */
        public long handshakeTimeoutMillis = 15_000;
        public int connectTimeoutMillis = 15_000;
        /** Send {@code connection_id} and {@code sequence_number} when reconnecting. */
        public boolean resumesConnections = true;
        public String userAgent;
        public TokenVerifier tokenVerifier;
        public WebSocketTransport transport;
        /** Jitter source; inject for deterministic tests. */
        public Random random;
        public Logger logger;
    }

    private final String serverUrl;
    private final Configuration configuration;
    private final WebSocketTransport transport;
    private final WebSocketListener listener;
    private final Random random;
    private final ScheduledExecutorService executor;
    private volatile Thread executorThread;

    private volatile String token;
    private volatile ConnectionState state = ConnectionState.DISCONNECTED;

    // Confined to the executor thread.
    private boolean running;
    private int generation;
    private ScheduledFuture<?> pendingAttempt;
    private ScheduledFuture<?> handshakeTimer;
    private ScheduledFuture<?> pingTimer;
    private WebSocketTransport.Connection connection;
    private boolean opened;
    private boolean handshakeComplete;
    private boolean isResumeAttempt;
    private long handshakeProbeSequence = -1;
    private int consecutiveFailures;
    private String connectionId;
    private long nextSequence;
    private String serverVersion;
    private String lastUrl;

    /** Set while the handshake is complete; read by callers of {@link #sendAction}. */
    private volatile WebSocketTransport.Connection readyConnection;
    private final AtomicLong nextActionSequence = new AtomicLong(1);
    private final Map<Long, PendingReply> pendingReplies = new ConcurrentHashMap<Long, PendingReply>();

    public MattermostWebSocket(String serverUrl, String token, Configuration configuration, WebSocketListener listener) {
        this.serverUrl = serverUrl;
        this.token = token;
        this.configuration = configuration != null ? configuration : new Configuration();
        this.transport = this.configuration.transport != null ? this.configuration.transport : new NvWebSocketTransport();
        this.random = this.configuration.random != null ? this.configuration.random : new Random();
        this.listener = listener;
        this.executor = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "mattermost-ws");
                thread.setDaemon(true);
                executorThread = thread;
                return thread;
            }
        });
    }

    // MARK: - Public API

    public ConnectionState getState() {
        return state;
    }

    public boolean isConnected() {
        return state.isConnected();
    }

    /** The URL of the last connection attempt (with resume parameters), for diagnostics. */
    public String getLastUrl() {
        return lastUrl;
    }

    /** The token for the next connection attempt. */
    public void setToken(String token) {
        this.token = token;
    }

    /** Starts connecting; afterwards reconnects automatically until {@link #disconnect()}. No-op while running. */
    public void connect() {
        post(new Runnable() {
            @Override
            public void run() {
                if (running) {
                    return;
                }
                running = true;
                generation++;
                consecutiveFailures = 0;
                attempt();
            }
        });
    }

    /** Closes the connection and stops reconnecting. Resume state is kept, so a later {@link #connect()} asks to resume. */
    public void disconnect() {
        post(new Runnable() {
            @Override
            public void run() {
                running = false;
                generation++;
                cancelPendingAttempt();
                tearDown();
                setState(ConnectionState.DISCONNECTED);
            }
        });
    }

    /** Drops the current connection or backoff and connects again now (foreground, network change). */
    public void reconnectNow() {
        post(new Runnable() {
            @Override
            public void run() {
                running = true;
                generation++;
                cancelPendingAttempt();
                tearDown();
                consecutiveFailures = 0;
                attempt();
            }
        });
    }

    /** Forgets the connection id and sequence number; the next connection starts fresh (not resumed). */
    public void resetResumeState() {
        post(new Runnable() {
            @Override
            public void run() {
                connectionId = null;
                nextSequence = 0;
                serverVersion = null;
            }
        });
    }

    /** Disconnects and stops the internal thread. The instance can't be used afterwards. */
    public void shutdown() {
        disconnect();
        executor.shutdown();
    }

    /**
     * Sends an action and waits for its {@code seq_reply}. Blocks; never call it from the listener.
     *
     * @throws WebSocketActionException when not connected, on timeout, close, or a non-OK reply.
     */
    public WebSocketResponse sendAction(String action, JsonObject data, long timeoutMillis) throws WebSocketActionException, InterruptedException {
        if (Thread.currentThread() == executorThread) {
            throw new IllegalStateException("sendAction would block the WebSocket thread");
        }
        WebSocketTransport.Connection target = readyConnection;
        if (target == null) {
            throw new WebSocketActionException(WebSocketActionException.Reason.NOT_CONNECTED, null);
        }
        long sequence = nextActionSequence.getAndIncrement();
        PendingReply pending = new PendingReply();
        pendingReplies.put(sequence, pending);
        try {
            if (!target.send(encodeAction(action, sequence, data))) {
                throw new WebSocketActionException(WebSocketActionException.Reason.CONNECTION_CLOSED, null);
            }
            if (!pending.latch.await(timeoutMillis, TimeUnit.MILLISECONDS)) {
                throw new WebSocketActionException(WebSocketActionException.Reason.TIMEOUT, null);
            }
            if (pending.response == null) {
                throw new WebSocketActionException(WebSocketActionException.Reason.CONNECTION_CLOSED, null);
            }
            if (!pending.response.isOk()) {
                throw new WebSocketActionException(WebSocketActionException.Reason.ACTION_FAILED, pending.response);
            }
            return pending.response;
        } finally {
            pendingReplies.remove(sequence);
        }
    }

    public WebSocketResponse sendAction(String action, JsonObject data) throws WebSocketActionException, InterruptedException {
        return sendAction(action, data, configuration.actionTimeoutMillis);
    }

    /** {@code ping}; the reply's data includes {@code server_time}. */
    public WebSocketResponse ping() throws WebSocketActionException, InterruptedException {
        return sendAction("ping", new JsonObject());
    }

    /** {@code get_statuses}: user id to status for users the server tracks. */
    public Map<String, String> getStatuses() throws WebSocketActionException, InterruptedException {
        WebSocketResponse response = sendAction("get_statuses", new JsonObject());
        Map<String, String> result = new LinkedHashMap<String, String>();
        for (Map.Entry<String, JsonElement> entry : response.data.entrySet()) {
            if (entry.getValue().isJsonPrimitive()) {
                result.put(entry.getKey(), entry.getValue().getAsString());
            }
        }
        return result;
    }

    public void updateActiveStatus(boolean active) throws WebSocketActionException, InterruptedException {
        sendAction("user_update_active_status", Json.object("user_is_active", active, "manual", false));
    }

    /**
     * Sends {@code user_typing} without waiting for the reply. Throttle calls (the official clients
     * send at most one every few seconds).
     *
     * @return false when not connected.
     */
    public boolean userTyping(String channelId, String parentId) {
        WebSocketTransport.Connection target = readyConnection;
        if (target == null) {
            return false;
        }
        JsonObject data = Json.object("channel_id", channelId, "parent_id", parentId != null ? parentId : "");
        return target.send(encodeAction("user_typing", nextActionSequence.getAndIncrement(), data));
    }

    // MARK: - URL

    /** {@code ws(s)://<host>/<subpath>/api/v4/websocket} for a site URL; null for other schemes. */
    public static String webSocketUrl(String serverUrl) {
        String url = serverUrl.trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        int fragment = url.indexOf('#');
        if (fragment >= 0) {
            url = url.substring(0, fragment);
        }
        int query = url.indexOf('?');
        if (query >= 0) {
            url = url.substring(0, query);
        }
        String lower = url.toLowerCase();
        String rest;
        String scheme;
        if (lower.startsWith("https://")) {
            scheme = "wss://";
            rest = url.substring(8);
        } else if (lower.startsWith("wss://")) {
            scheme = "wss://";
            rest = url.substring(6);
        } else if (lower.startsWith("http://")) {
            scheme = "ws://";
            rest = url.substring(7);
        } else if (lower.startsWith("ws://")) {
            scheme = "ws://";
            rest = url.substring(5);
        } else {
            return null;
        }
        while (rest.endsWith("/")) {
            rest = rest.substring(0, rest.length() - 1);
        }
        return scheme + rest + "/api/v4/websocket";
    }

    // MARK: - Connection loop (executor thread)

    private void post(Runnable runnable) {
        try {
            executor.execute(runnable);
        } catch (RuntimeException e) {
            log("websocket executor rejected task: " + e);
        }
    }

    private void log(String message) {
        Logger logger = configuration.logger;
        if (logger != null) {
            logger.log(message);
        }
    }

    private void cancelPendingAttempt() {
        if (pendingAttempt != null) {
            pendingAttempt.cancel(false);
            pendingAttempt = null;
        }
    }

    private void attempt() {
        pendingAttempt = null;
        if (!running) {
            return;
        }
        setState(ConnectionState.connecting(consecutiveFailures + 1));
        String base = webSocketUrl(serverUrl);
        if (base == null) {
            log("websocket: unsupported server URL " + serverUrl);
            running = false;
            setState(ConnectionState.DISCONNECTED);
            return;
        }
        String url = base;
        if (configuration.resumesConnections && connectionId != null) {
            url += "?connection_id=" + UrlCodec.encodeQueryComponent(connectionId) + "&sequence_number=" + nextSequence;
        }
        lastUrl = url;
        Map<String, String> headers = new LinkedHashMap<String, String>();
        String currentToken = token;
        if (configuration.authentication != Authentication.CHALLENGE && currentToken != null && !currentToken.isEmpty()) {
            headers.put("Authorization", "Bearer " + currentToken);
        }
        if (configuration.userAgent != null) {
            headers.put("User-Agent", configuration.userAgent);
        }
        opened = false;
        handshakeComplete = false;
        isResumeAttempt = configuration.resumesConnections && connectionId != null;
        handshakeProbeSequence = -1;
        final Callbacks callbacks = new Callbacks();
        connection = transport.connect(url, headers, configuration.connectTimeoutMillis, callbacks);
    }

    /** Transport callbacks, re-posted onto the executor and checked against the current connection. */
    private final class Callbacks implements WebSocketTransport.Callback {
        @Override
        public void onOpen(final WebSocketTransport.Connection opened) {
            post(new Runnable() {
                @Override
                public void run() {
                    handleOpen(opened);
                }
            });
        }

        @Override
        public void onText(final WebSocketTransport.Connection source, final String text) {
            post(new Runnable() {
                @Override
                public void run() {
                    handleText(source, text);
                }
            });
        }

        @Override
        public void onClosed(final WebSocketTransport.Connection source, final Throwable error, final int httpStatus) {
            post(new Runnable() {
                @Override
                public void run() {
                    if (source != connection) {
                        return;
                    }
                    log("websocket closed" + (error != null ? ": " + error : "") + (httpStatus != 0 ? " status=" + httpStatus : ""));
                    handleFailure(httpStatus == 401);
                }
            });
        }
    }

    private void handleOpen(final WebSocketTransport.Connection source) {
        if (source != connection) {
            source.close();
            return;
        }
        opened = true;
        startHandshakeTimer(source);
        String currentToken = token;
        if (configuration.authentication != Authentication.HEADER && currentToken != null && !currentToken.isEmpty()) {
            source.send(encodeAction("authentication_challenge", nextActionSequence.getAndIncrement(), Json.object("token", currentToken)));
        }
        // A new connection always queues hello before any reply, so a reply that arrives first means a
        // resumed connection, or (with not_authenticated) a rejected token.
        handshakeProbeSequence = nextActionSequence.getAndIncrement();
        source.send(encodeAction("ping", handshakeProbeSequence, new JsonObject()));
    }

    private void handleText(WebSocketTransport.Connection source, String text) {
        if (source != connection) {
            return;
        }
        Object frame = WebSocketEvent.parseFrame(text);
        if (frame instanceof WebSocketResponse) {
            WebSocketResponse response = (WebSocketResponse) frame;
            if (handshakeProbeSequence >= 0 && response.seqReply == handshakeProbeSequence) {
                handshakeProbeSequence = -1;
                if (!handshakeComplete) {
                    if (response.isNotAuthenticated()) {
                        log("websocket authentication rejected");
                        handleFailure(true);
                        return;
                    }
                    if (isResumeAttempt && connectionId != null) {
                        completeHandshake(new ConnectionInfo(connectionId, serverVersion, true), source);
                    }
                }
                return;
            }
            PendingReply pending = pendingReplies.get(response.seqReply);
            if (pending != null) {
                pending.resolve(response);
            }
        } else if (frame instanceof WebSocketEvent) {
            handleEvent((WebSocketEvent) frame, source);
        }
    }

    private void handleEvent(WebSocketEvent event, WebSocketTransport.Connection source) {
        if (event.type == WebSocketEventType.HELLO) {
            WebSocketEvent.Hello hello = event.getHello();
            String newConnectionId = hello != null ? hello.connectionId : null;
            boolean resumed = newConnectionId != null && newConnectionId.equals(connectionId);
            if (newConnectionId != null) {
                connectionId = newConnectionId;
            }
            if (hello != null && hello.serverVersion != null) {
                serverVersion = hello.serverVersion;
            }
            if (event.seq != null) {
                nextSequence = event.seq + 1;
            } else if (!resumed) {
                nextSequence = 0;
            }
            completeHandshake(new ConnectionInfo(newConnectionId != null ? newConnectionId : "", serverVersion, resumed), source);
        } else if (event.seq != null) {
            long seq = event.seq;
            // Events replayed on a resumed connection arrive before the probe reply: check them too.
            if (handshakeComplete || isResumeAttempt) {
                if (seq < nextSequence) {
                    // Already delivered (replayed after a resume).
                    return;
                }
                if (seq > nextSequence) {
                    // Missed events: reconnect so the server replays them, or reports a new connection
                    // (then the sync layer catches up).
                    log("websocket sequence gap: expected " + nextSequence + ", got " + seq);
                    handleFailure(false);
                    return;
                }
            }
            nextSequence = seq + 1;
        }
        deliver(event);
    }

    private void completeHandshake(ConnectionInfo info, WebSocketTransport.Connection source) {
        handshakeComplete = true;
        if (handshakeTimer != null) {
            handshakeTimer.cancel(false);
            handshakeTimer = null;
        }
        consecutiveFailures = 0;
        readyConnection = source;
        setState(ConnectionState.connected(info));
        startPinging(source);
    }

    private void handleFailure(boolean unauthorized) {
        boolean openedWithoutHandshake = opened && !handshakeComplete;
        tearDown();
        if (!running) {
            return;
        }
        final TokenVerifier verifier = configuration.tokenVerifier;
        if (!unauthorized && openedWithoutHandshake && verifier != null) {
            final int expectedGeneration = generation;
            Thread thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    boolean accepted;
                    try {
                        accepted = verifier.isTokenAccepted();
                    } catch (RuntimeException e) {
                        accepted = true;
                    }
                    final boolean result = accepted;
                    post(new Runnable() {
                        @Override
                        public void run() {
                            if (expectedGeneration != generation || !running) {
                                return;
                            }
                            if (!result) {
                                becomeUnauthorized();
                            } else {
                                scheduleRetry();
                            }
                        }
                    });
                }
            }, "mattermost-ws-verify");
            thread.setDaemon(true);
            thread.start();
            return;
        }
        if (unauthorized) {
            becomeUnauthorized();
            return;
        }
        scheduleRetry();
    }

    private void becomeUnauthorized() {
        running = false;
        cancelPendingAttempt();
        setState(ConnectionState.UNAUTHORIZED);
    }

    private void scheduleRetry() {
        consecutiveFailures++;
        long delay = configuration.backoff.delayMillis(consecutiveFailures, random.nextDouble());
        setState(ConnectionState.waitingToReconnect(consecutiveFailures + 1, delay));
        final int expectedGeneration = generation;
        cancelPendingAttempt();
        pendingAttempt = executor.schedule(new Runnable() {
            @Override
            public void run() {
                if (expectedGeneration == generation) {
                    attempt();
                }
            }
        }, delay, TimeUnit.MILLISECONDS);
    }

    private void startHandshakeTimer(final WebSocketTransport.Connection source) {
        if (handshakeTimer != null) {
            handshakeTimer.cancel(false);
            handshakeTimer = null;
        }
        long timeout = configuration.handshakeTimeoutMillis;
        if (timeout <= 0) {
            return;
        }
        handshakeTimer = executor.schedule(new Runnable() {
            @Override
            public void run() {
                if (source != connection || handshakeComplete) {
                    return;
                }
                log("websocket handshake timeout");
                handleFailure(false);
            }
        }, timeout, TimeUnit.MILLISECONDS);
    }

    private void startPinging(final WebSocketTransport.Connection source) {
        if (pingTimer != null) {
            pingTimer.cancel(false);
            pingTimer = null;
        }
        final long interval = configuration.pingIntervalMillis;
        if (interval <= 0) {
            return;
        }
        pingTimer = executor.schedule(new Runnable() {
            @Override
            public void run() {
                sendKeepAlivePing(source, interval);
            }
        }, interval, TimeUnit.MILLISECONDS);
    }

    private void sendKeepAlivePing(final WebSocketTransport.Connection source, final long interval) {
        if (source != connection || !handshakeComplete) {
            return;
        }
        final long sequence = nextActionSequence.getAndIncrement();
        final PendingReply pending = new PendingReply();
        pendingReplies.put(sequence, pending);
        source.send(encodeAction("ping", sequence, new JsonObject()));
        pingTimer = executor.schedule(new Runnable() {
            @Override
            public void run() {
                pendingReplies.remove(sequence);
                if (source != connection) {
                    return;
                }
                if (pending.response == null) {
                    // No pong in time: assume a dead connection.
                    log("websocket ping timeout");
                    handleFailure(false);
                    return;
                }
                // Any reply proves the connection is alive; schedule the next ping.
                pingTimer = executor.schedule(new Runnable() {
                    @Override
                    public void run() {
                        sendKeepAlivePing(source, interval);
                    }
                }, Math.max(0, interval - configuration.actionTimeoutMillis), TimeUnit.MILLISECONDS);
            }
        }, configuration.actionTimeoutMillis, TimeUnit.MILLISECONDS);
    }

    private void tearDown() {
        if (pingTimer != null) {
            pingTimer.cancel(false);
            pingTimer = null;
        }
        if (handshakeTimer != null) {
            handshakeTimer.cancel(false);
            handshakeTimer = null;
        }
        readyConnection = null;
        if (connection != null) {
            WebSocketTransport.Connection old = connection;
            connection = null;
            old.close();
        }
        opened = false;
        handshakeComplete = false;
        isResumeAttempt = false;
        handshakeProbeSequence = -1;
        List<PendingReply> pending = new ArrayList<PendingReply>(pendingReplies.values());
        pendingReplies.clear();
        for (PendingReply reply : pending) {
            reply.resolve(null);
        }
    }

    private void setState(ConnectionState newState) {
        if (newState.equals(state)) {
            return;
        }
        log("websocket state " + state + " -> " + newState);
        state = newState;
        if (listener != null) {
            try {
                listener.onStateChanged(this, newState);
            } catch (RuntimeException e) {
                log("websocket listener failed: " + e);
            }
        }
    }

    private void deliver(WebSocketEvent event) {
        if (listener != null) {
            try {
                listener.onEvent(this, event);
            } catch (RuntimeException e) {
                log("websocket listener failed: " + e);
            }
        }
    }

    /** {@code {"action":…,"seq":…,"data":{…}}}. */
    public static String encodeAction(String action, long sequence, JsonObject data) {
        JsonObject object = new JsonObject();
        object.addProperty("action", action);
        object.addProperty("seq", sequence);
        object.add("data", data != null ? data : new JsonObject());
        return object.toString();
    }

    private static final class PendingReply {
        final CountDownLatch latch = new CountDownLatch(1);
        volatile WebSocketResponse response;

        void resolve(WebSocketResponse value) {
            if (latch.getCount() > 0) {
                response = value;
                latch.countDown();
            }
        }
    }

    /** For tests: the resume state as {@code {connection_id, next_sequence}}. */
    Map<String, Object> debugResumeState() throws InterruptedException {
        final Map<String, Object> result = new HashMap<String, Object>();
        final CountDownLatch done = new CountDownLatch(1);
        post(new Runnable() {
            @Override
            public void run() {
                result.put("connection_id", connectionId);
                result.put("next_sequence", nextSequence);
                done.countDown();
            }
        });
        done.await(5, TimeUnit.SECONDS);
        return result;
    }
}
