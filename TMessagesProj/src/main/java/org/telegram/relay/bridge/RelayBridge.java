package org.telegram.relay.bridge;

import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.relay.RelayConfig;
import org.telegram.relay.account.RelayAccount;
import org.telegram.relay.account.RelayDatabase;
import org.telegram.relay.mattermost.MattermostException;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.QuickAckDelegate;
import org.telegram.tgnet.RequestDelegate;
import org.telegram.tgnet.RequestDelegateTimestamp;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The in-process "server" of one account slot. {@code ConnectionsManager.sendRequestInternal} hands
 * every TL request here instead of to native tgnet (see {@code docs/relay/telegram-internals.md} §1).
 *
 * <ul>
 *   <li>Requests run on a serial executor per account (downloads/uploads on a small pool), so
 *       {@code InvokeAfter} order is kept.</li>
 *   <li>Handlers are looked up by request class: real handlers ({@link RelayHandlers}), stubs
 *       ({@link RelayStubs}), else an automatic stub (empty Vector / boolTrue / error) chosen by
 *       probing the request's own {@code deserializeResponse}. Every method that is not a real handler
 *       is logged once with tag {@code RelayBridge}; debug builds keep a summary (logcat and
 *       {@code files/relay-unhandled-<account>.txt}).</li>
 *   <li>Responses are serialised and parsed back through {@code deserializeResponse} and delivered
 *       on {@code Utilities.stageQueue}, exactly like the network path.</li>
 *   <li>While the account is logged in, the bridge drives {@code updateTimerProc()} at 1 Hz, reports
 *       "connected" and pushes a Relay {@code TL_config}.</li>
 * </ul>
 */
public final class RelayBridge {
    private static final String TAG = RelayConfig.TAG_BRIDGE;

    // MARK: - Registry (shared by all accounts)

    private static final Map<Class<?>, RelayHandlers.Handler> handlers = new HashMap<>();
    private static final Map<Class<?>, RelayStubs.Stub> stubs = new HashMap<>();

    static {
        RelayHandlers.register(handlers);
        RelayStubs.register(stubs);
    }

    private static final int AUTO_UNKNOWN = 0;
    private static final int AUTO_VECTOR = 1;
    private static final int AUTO_BOOL = 2;
    private static final int AUTO_ERROR = 3;
    /** Automatic stub kind per request class, probed once. */
    private static final ConcurrentHashMap<Class<?>, Integer> autoKinds = new ConcurrentHashMap<>();
    /** Methods already logged as UNHANDLED (once per process, whatever the account). */
    private static final ConcurrentHashMap<String, Boolean> loggedUnhandled = new ConcurrentHashMap<>();

    // MARK: - Instances

    private static final RelayBridge[] instances = new RelayBridge[UserConfig.MAX_ACCOUNT_COUNT];

    public static boolean enabled(int account) {
        return RelayConfig.ENABLED;
    }

    public static RelayBridge get(int account) {
        RelayBridge bridge = instances[account];
        if (bridge == null) {
            synchronized (RelayBridge.class) {
                bridge = instances[account];
                if (bridge == null) {
                    instances[account] = bridge = new RelayBridge(account);
                }
            }
        }
        return bridge;
    }

    public final int account;
    private final ScheduledThreadPoolExecutor executor;
    private final ExecutorService transferExecutor;

    private static final class Pending {
        final int token;
        final TLObject request;
        final RequestDelegate onComplete;
        final RequestDelegateTimestamp onCompleteTimestamp;
        final QuickAckDelegate onQuickAck;
        final int flags;
        final int connectionType;
        volatile boolean cancelled;
        int attempt;

        Pending(int token, TLObject request, RequestDelegate onComplete, RequestDelegateTimestamp onCompleteTimestamp, QuickAckDelegate onQuickAck, int flags, int connectionType) {
            this.token = token;
            this.request = request;
            this.onComplete = onComplete;
            this.onCompleteTimestamp = onCompleteTimestamp;
            this.onQuickAck = onQuickAck;
            this.flags = flags;
            this.connectionType = connectionType;
        }
    }

    private final ConcurrentHashMap<Integer, Pending> pending = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, Set<Integer>> guidTokens = new ConcurrentHashMap<>();

    /** Method name → [calls, kind]; kind: "stub", "auto-vector", "auto-bool", "error". */
    private final ConcurrentHashMap<String, String> unhandledKinds = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> unhandledCounts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> handledCounts = new ConcurrentHashMap<>();
    private volatile boolean summaryScheduled;

    private volatile boolean started;
    private Runnable timerRunnable;

    private RelayBridge(int account) {
        this.account = account;
        executor = new ScheduledThreadPoolExecutor(1, r -> {
            Thread t = new Thread(r, "RelayBridge-" + account);
            t.setPriority(Thread.NORM_PRIORITY);
            return t;
        });
        executor.setRemoveOnCancelPolicy(true);
        transferExecutor = new ThreadPoolExecutor(4, 4, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), r -> new Thread(r, "RelayTransfer-" + account));
        ((ThreadPoolExecutor) transferExecutor).allowCoreThreadTimeOut(true);
    }

    // MARK: - Request entry (ConnectionsManager.sendRequestInternal)

    public void handle(TLObject request, RequestDelegate onComplete, RequestDelegateTimestamp onCompleteTimestamp, QuickAckDelegate onQuickAck,
                       int flags, int datacenterId, int connectionType, int token) {
        Pending p = new Pending(token, request, onComplete, onCompleteTimestamp, onQuickAck, flags, connectionType);
        pending.put(token, p);
        boolean transfer = (connectionType & (ConnectionsManager.ConnectionTypeDownload | ConnectionsManager.ConnectionTypeUpload)) != 0;
        if (transfer) {
            transferExecutor.execute(() -> run(p));
        } else {
            executor.execute(() -> run(p));
        }
    }

    private void run(Pending p) {
        if (p.cancelled) {
            return;
        }
        Class<?> type = p.request.getClass();
        TLObject response;
        try {
            response = answer(p.request, type);
        } catch (MattermostException e) {
            boolean failFast = (p.flags & (ConnectionsManager.RequestFlagFailOnServerErrors | ConnectionsManager.RequestFlagFailOnServerErrorsExceptFloodWait)) != 0;
            if (e.isTransient() && !failFast && p.attempt < 5 && !p.cancelled) {
                long delay = Math.min(15_000, 1000L << p.attempt);
                p.attempt++;
                RelayConfig.log(TAG, RelayTl.methodName(type) + " transient failure (" + e.getMessage() + "), retry " + p.attempt + " in " + delay + " ms");
                executor.schedule(() -> run(p), delay, TimeUnit.MILLISECONDS);
                return;
            }
            RelayConfig.logError(TAG, RelayTl.methodName(type) + " failed", e);
            if (e.isUnauthorized()) {
                onSessionRejected();
                response = RelayTl.error(401, "AUTH_KEY_UNREGISTERED");
            } else if (e.getKind() == MattermostException.Kind.TRANSPORT) {
                response = RelayTl.error(-1000, "RELAY_UNREACHABLE");
            } else {
                response = RelayTl.error(500, "INTERNAL");
            }
        } catch (Throwable t) {
            RelayConfig.logError(TAG, RelayTl.methodName(type) + " handler crashed", t);
            response = RelayTl.error(500, "RELAY_INTERNAL");
        }
        try {
            p.request.freeResources();
        } catch (Throwable ignore) {
        }
        deliver(p, response);
    }

    private TLObject answer(TLObject request, Class<?> type) throws Exception {
        RelayHandlers.Handler handler = handlers.get(type);
        if (handler != null) {
            count(handledCounts, RelayTl.methodName(type));
            RelayAccount relayAccount = RelayAccount.get(account);
            if (relayAccount == null && !RelayHandlers.WITHOUT_SESSION.contains(type)) {
                // Not logged in: 401 stops the client's retry loops (updates.getState, MC:16288).
                return RelayTl.error(401, "RELAY_NOT_LOGGED_IN");
            }
            return handler.handle(this, relayAccount, request);
        }
        RelayStubs.Stub stub = stubs.get(type);
        if (stub != null) {
            noteUnhandled(type, "stub");
            return stub.answer(request, account);
        }
        int kind = autoKind(request, type);
        switch (kind) {
            case AUTO_VECTOR:
                noteUnhandled(type, "auto-vector");
                return RelayTl.emptyVector();
            case AUTO_BOOL:
                noteUnhandled(type, "auto-bool");
                return new TLRPC.TL_boolTrue();
            default:
                noteUnhandled(type, "error");
                return RelayTl.error(400, "RELAY_UNSUPPORTED");
        }
    }

    /** Probes (once per class) whether a method returns a Vector or a Bool, by parsing an empty answer. */
    private static int autoKind(TLObject request, Class<?> type) {
        Integer known = autoKinds.get(type);
        if (known != null) {
            return known;
        }
        int kind = AUTO_ERROR;
        if (probe(request, RelayTl.emptyVector())) {
            kind = AUTO_VECTOR;
        } else if (probe(request, new TLRPC.TL_boolTrue())) {
            kind = AUTO_BOOL;
        }
        autoKinds.put(type, kind);
        return kind;
    }

    private static boolean probe(TLObject request, TLObject candidate) {
        try {
            return RelayTl.roundTrip(request, candidate) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    private void deliver(Pending p, TLObject response) {
        TLObject parsed = null;
        TLRPC.TL_error error = null;
        if (response instanceof TLRPC.TL_error) {
            error = (TLRPC.TL_error) response;
        } else if (response instanceof TLRPC.TL_upload_file) {
            // Bytes live in a NativeByteBuffer, which SerializedData cannot carry; built fresh per chunk, never aliased.
            parsed = response;
        } else if (response != null) {
            try {
                parsed = RelayTl.roundTrip(p.request, response);
            } catch (Throwable t) {
                RelayConfig.logError(TAG, "response for " + RelayTl.methodName(p.request.getClass()) + " does not parse as its result type", t);
            }
            if (parsed == null) {
                error = RelayTl.error(500, "RELAY_BAD_RESPONSE");
            }
        } else {
            error = RelayTl.error(500, "RELAY_NO_RESPONSE");
        }
        if (parsed != null && p.onQuickAck != null && (p.flags & ConnectionsManager.RequestFlagNeedQuickAck) != 0) {
            Utilities.stageQueue.postRunnable(p.onQuickAck::run);
        }
        final TLObject finalResponse = parsed;
        final TLRPC.TL_error finalError = error;
        Utilities.stageQueue.postRunnable(() -> {
            pending.remove(p.token);
            if (p.cancelled && (p.flags & ConnectionsManager.RequestFlagListenAfterCancel) == 0) {
                return;
            }
            if (p.onComplete != null) {
                p.onComplete.run(finalResponse, finalError);
            } else if (p.onCompleteTimestamp != null) {
                p.onCompleteTimestamp.run(finalResponse, finalError, System.currentTimeMillis());
            } else if (finalResponse instanceof TLRPC.Updates) {
                AccountInstance.getInstance(account).getMessagesController().processUpdates((TLRPC.Updates) finalResponse, false);
            }
        });
    }

    // MARK: - Cancel / guid binding (ConnectionsManager)

    public void cancel(int token, Runnable onCancelled) {
        Pending p = pending.remove(token);
        if (p != null) {
            p.cancelled = true;
        }
        if (onCancelled != null) {
            Utilities.stageQueue.postRunnable(onCancelled);
        }
    }

    public void bindToGuid(int token, int guid) {
        Set<Integer> set = guidTokens.get(guid);
        if (set == null) {
            set = Collections.newSetFromMap(new ConcurrentHashMap<>());
            Set<Integer> existing = guidTokens.putIfAbsent(guid, set);
            if (existing != null) {
                set = existing;
            }
        }
        set.add(token);
    }

    public void cancelForGuid(int guid) {
        Set<Integer> set = guidTokens.remove(guid);
        if (set != null) {
            for (Integer token : set) {
                cancel(token, null);
            }
        }
    }

    /** Native failNotRunningRequest: delivers -2000 CANCELLED_REQUEST if the request has not run yet. */
    public void failNotRunning(int token) {
        Pending p = pending.get(token);
        if (p != null && p.attempt == 0) {
            pending.remove(token);
            p.cancelled = true;
            Utilities.stageQueue.postRunnable(() -> {
                if (p.onComplete != null) {
                    p.onComplete.run(null, RelayTl.error(-2000, "CANCELLED_REQUEST"));
                } else if (p.onCompleteTimestamp != null) {
                    p.onCompleteTimestamp.run(null, RelayTl.error(-2000, "CANCELLED_REQUEST"), System.currentTimeMillis());
                }
            });
        }
    }

    /** Fails every pending request with -1000 (logout), as native does for non-WithoutLogin requests. */
    public void failAllPending() {
        for (Pending p : new ArrayList<>(pending.values())) {
            pending.remove(p.token);
            p.cancelled = true;
            Utilities.stageQueue.postRunnable(() -> {
                if (p.onComplete != null) {
                    p.onComplete.run(null, RelayTl.error(-1000, ""));
                }
            });
        }
    }

    // MARK: - State

    /** The bridge's {@code updates.state}: common pts, qts 0, seq 0, now. */
    TLRPC.TL_updates_state currentState(RelayAccount relayAccount) {
        TLRPC.TL_updates_state state = new TLRPC.TL_updates_state();
        state.pts = relayAccount != null ? relayAccount.db().pts(RelayDatabase.BOX_COMMON) : 1;
        state.qts = 0;
        state.seq = 0;
        state.date = RelayStubs.now(account);
        state.unread_count = 0;
        return state;
    }

    // MARK: - Lifecycle

    /** Called once per ConnectionsManager (boot): starts the bridge if the slot is logged in. */
    public static void onConnectionsManagerCreated(int account) {
        org.telegram.messenger.AndroidUtilities.runOnUIThread(() -> {
            if (UserConfig.getInstance(account).isClientActivated() && RelayAccount.get(account) != null) {
                get(account).start();
            }
        }, 100);
    }

    /** Logged in (boot or fresh login): connected state, Relay config, timer. Idempotent. */
    public synchronized void start() {
        if (started) {
            return;
        }
        started = true;
        RelayConfig.log(TAG, "start account=" + account);
        ConnectionsManager.onConnectionStateChanged(ConnectionsManager.ConnectionStateConnected, account);
        Utilities.stageQueue.postRunnable(() -> MessagesController.getInstance(account).updateConfig(RelayServerConfig.build(account)));
        timerRunnable = new Runnable() {
            @Override
            public void run() {
                if (!started) {
                    return;
                }
                ConnectionsManager cm = ConnectionsManager.getInstance(account);
                long pausedAt = cm.getPauseTime();
                if (pausedAt == 0 || System.currentTimeMillis() - pausedAt < 30_000) {
                    try {
                        MessagesController.getInstance(account).updateTimerProc();
                    } catch (Throwable t) {
                        RelayConfig.logError(TAG, "updateTimerProc", t);
                    }
                }
                Utilities.stageQueue.postRunnable(this, 1000);
            }
        };
        Utilities.stageQueue.postRunnable(timerRunnable, 1000);
        scheduleSummary(20_000);
    }

    /** Logged out: stop the timer and fail what is still pending. */
    public synchronized void stop() {
        if (!started) {
            return;
        }
        started = false;
        RelayConfig.log(TAG, "stop account=" + account);
        if (timerRunnable != null) {
            Utilities.stageQueue.cancelRunnable(timerRunnable);
            timerRunnable = null;
        }
        failAllPending();
    }

    public boolean isStarted() {
        return started;
    }

    private volatile boolean sessionRejected;

    /** Mattermost answered 401 with our token: the session is gone (revoked or expired). */
    private void onSessionRejected() {
        if (sessionRejected) {
            return;
        }
        sessionRejected = true;
        RelayConfig.log(TAG, "session rejected by the server, logging out account=" + account);
        ConnectionsManager.onLogout(account);
    }

    void resetSessionRejected() {
        sessionRejected = false;
    }

    // MARK: - Unhandled methods (drives A3)

    private static void count(ConcurrentHashMap<String, AtomicInteger> map, String name) {
        AtomicInteger counter = map.get(name);
        if (counter == null) {
            counter = new AtomicInteger();
            AtomicInteger existing = map.putIfAbsent(name, counter);
            if (existing != null) {
                counter = existing;
            }
        }
        counter.incrementAndGet();
    }

    private void noteUnhandled(Class<?> type, String kind) {
        String name = RelayTl.methodName(type);
        count(unhandledCounts, name);
        if (unhandledKinds.putIfAbsent(name, kind) == null) {
            if (loggedUnhandled.putIfAbsent(name, Boolean.TRUE) == null) {
                RelayConfig.log(TAG, String.format("UNHANDLED %s (0x%08x) -> %s [account %d]", name, RelayTl.constructorOf(type), kind, account));
            }
            if (RelayConfig.isDebug() && started) {
                scheduleSummary(5_000);
            }
        }
    }

    private void scheduleSummary(long delayMs) {
        if (!RelayConfig.isDebug() || summaryScheduled) {
            return;
        }
        summaryScheduled = true;
        executor.schedule(() -> {
            summaryScheduled = false;
            logSummary();
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    /** Logs the unhandled-method table and writes it to {@code files/relay-unhandled-<account>.txt}. */
    public String logSummary() {
        TreeMap<String, String> sorted = new TreeMap<>(unhandledKinds);
        StringBuilder text = new StringBuilder();
        text.append("account=").append(account).append(" unhandled=").append(sorted.size()).append('\n');
        List<String> byCount = new ArrayList<>(sorted.keySet());
        Collections.sort(byCount, (a, b) -> Integer.compare(countOf(unhandledCounts, b), countOf(unhandledCounts, a)));
        for (String name : byCount) {
            text.append(name).append('\t').append(sorted.get(name)).append('\t').append(countOf(unhandledCounts, name)).append('\n');
        }
        text.append("handled=").append(handledCounts.size()).append('\n');
        for (String name : new TreeMap<>(handledCounts).keySet()) {
            text.append(name).append('\t').append("handler").append('\t').append(countOf(handledCounts, name)).append('\n');
        }
        String summary = text.toString();
        for (String line : summary.split("\n")) {
            RelayConfig.log(TAG, "SUMMARY " + line);
        }
        try {
            File file = new File(ApplicationLoader.getFilesDirFixed(), "relay-unhandled-" + account + ".txt");
            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write(summary.getBytes(Charset.forName("UTF-8")));
            }
        } catch (Throwable t) {
            RelayConfig.logError(TAG, "summary file", t);
        }
        return summary;
    }

    private static int countOf(ConcurrentHashMap<String, AtomicInteger> map, String name) {
        AtomicInteger counter = map.get(name);
        return counter == null ? 0 : counter.get();
    }
}
