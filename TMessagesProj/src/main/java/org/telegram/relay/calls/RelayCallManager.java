package org.telegram.relay.calls;

import android.util.Log;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.voip.VoIPService;
import org.telegram.relay.account.RelayAccount;
import org.telegram.relay.account.RelayDatabase;
import org.telegram.relay.account.RelayUsers;
import org.telegram.relay.bridge.RelayHooks;
import org.telegram.relay.mattermost.calls.RelayCall;
import org.telegram.relay.mattermost.calls.RelayCallsClient;
import org.telegram.relay.mattermost.calls.RelayCallsEvent;
import org.telegram.relay.mattermost.calls.RelayCallsException;
import org.telegram.relay.mattermost.calls.RelayCallsLog;
import org.telegram.relay.mattermost.calls.RelayDirectCall;
import org.telegram.relay.mattermost.ws.WebSocketEvent;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_phone;
import org.telegram.tgnet.tl.TL_update;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Relay calls of one account (phase A8): the calls plugin ({@code org.relaychat.calls}, contract version 2, the
 * same as iOS and macOS) behind Telegram's own call flow. Telegram's {@code VoIPService} keeps doing what it does
 * for a Telegram call — {@code phone.requestCall}, {@code acceptCall}, {@code confirmCall}, {@code discardCall},
 * {@code updatePhoneCall} — and the bridge answers those requests from a {@link RelayDirectCall} (port of the iOS
 * core) instead of Telegram's servers:
 *
 * <ul>
 *   <li>outgoing: {@code requestCall} rings the DM; a callee device that reports {@code received} becomes
 *       {@code phoneCallWaiting.receive_date} ("Ringing", ringback); the answer becomes {@code phoneCallAccepted};
 *       the client's {@code confirmCall} gets the {@code phoneCall} and creates its "tgcalls instance", which is
 *       {@link RelayPrivateInstance} over the call's LiveKit room;</li>
 *   <li>incoming: the plugin's {@code incoming} event becomes {@code updatePhoneCall(phoneCallRequested)}, so
 *       Telegram rings with its own incoming call notification / screen; {@code acceptCall} accepts on the
 *       plugin;</li>
 *   <li>every end ({@code declined}, {@code busy}, {@code missed}, {@code cancelled}, hang up, answered elsewhere)
 *       becomes {@code phoneCallDiscarded} with Telegram's reason.</li>
 * </ul>
 *
 * The Diffie-Hellman values Telegram exchanges are played by the bridge (the "other side") so Telegram's checks pass;
 * the media is not end-to-end encrypted with them (LiveKit uses its own transport encryption), and the emoji key is
 * hidden ({@code VoIPFragment}). One call at a time for the whole app, as in Telegram.
 */
public final class RelayCallManager {
    static final String TAG = "RelayCalls";

    /** Telegram's well-known 2048-bit DH prime (tgnet {@code Handshake::isGoodPrime} fast path), g = 3. */
    private static final String DH_PRIME_HEX = "c71caeb9c6b1c9048e6c522f70f13f73980d40238e3e21c14934d037563d930f48198a0aa7c14058229493d22530f4dbfa336f6e0ac925139543aed44cce7c3720fd51f69458705ac68cd4fe6b6b13abdc9746512969328454f18faf8c595f642477fe96bb2a941d5bcd1d4ac8cc49880708fa9b378e3c4f3a9060bee67cf9a4a4a695811051907e162753b56b0f6b410dba74d8a84b2a14b3144e0ef1284754fd17ed950d5965b4b9dd46582db1178d169c6bc465b0d6ff9ca3928fef5b9ae4e418fc15e83ebea0f87fa9ff5eed70050ded2849f47bf959d956850ce929851f0d8115f635b105ee2e4e15d04b2454bf6f4fadf034b10403119cd8e3b92fcc5b";
    static final BigInteger DH_P = new BigInteger(DH_PRIME_HEX, 16);
    static final int DH_G = 3;
    /** The "library version" of Relay calls in Telegram's protocol objects. */
    static final String LIBRARY_VERSION = "9.0.0";

    public interface Answer {
        void answer(TLObject response);
    }

    private static final RelayCallManager[] instances = new RelayCallManager[UserConfig.MAX_ACCOUNT_COUNT];

    /** The account's call manager, or null without a Relay session. */
    public static RelayCallManager of(int account) {
        RelayAccount relay = RelayAccount.get(account);
        if (relay == null) {
            return null;
        }
        synchronized (instances) {
            RelayCallManager current = instances[account];
            if (current == null || current.relay != relay) {
                current = new RelayCallManager(account, relay);
                instances[account] = current;
            }
            return current;
        }
    }

    static {
        RelayCallsLog.handler = text -> Log.i(TAG, text);
    }

    final int account;
    final RelayAccount relay;
    final RelayCallsClient client;
    final String ownUserId;
    final String deviceId;
    final RelayCallScheduler scheduler = RelayCallScheduler.INSTANCE;
    /** Ringing and active calls in this account's channels (join panel), by call id. */
    final Map<String, RelayCall> live = new ConcurrentHashMap<>();
    /** {@link #live} was filled from {@code GET /calls/active} at least once. */
    volatile boolean liveSynced;

    /** The 1:1 call the app presents (one call at a time for all accounts), main thread. */
    static DirectSession direct;

    private RelayCallManager(int account, RelayAccount relay) {
        this.account = account;
        this.relay = relay;
        this.client = new RelayCallsClient(relay.client);
        this.ownUserId = relay.session.userId;
        this.deviceId = deviceId(relay);
    }

    /** A UUID per install and account (kept in the account's database), as {@code relayCallsDeviceId} on iOS. */
    private static String deviceId(RelayAccount relay) {
        RelayDatabase db = relay.db();
        String id = db.kv("calls_device_id");
        if (id == null || id.isEmpty()) {
            id = UUID.randomUUID().toString();
            db.putKv("calls_device_id", id);
        }
        return id;
    }

    static boolean hasActiveCall() {
        return direct != null || RelayGroupSession.current != null;
    }

    // MARK: - Plugin events (RelayLive hook, socket thread)

    /** Every {@code custom_org.relaychat.calls_*} event of the account's WebSocket. */
    public void onSocketEvent(WebSocketEvent event) {
        RelayCallsEvent decoded = RelayCallsEvent.decode(event.event, event.data);
        if (decoded == null) {
            return;
        }
        Log.i(TAG, "event " + decoded);
        scheduler.post(() -> handle(decoded));
    }

    /** After every WebSocket (re)connect: events are not replayed, so the active calls are fetched. */
    public void onSocketConnected() {
        scheduler.io(() -> {
            try {
                List<RelayCall> calls = client.activeCalls();
                scheduler.post(() -> handle(RelayCallsEvent.activeCalls(calls)));
            } catch (RelayCallsException e) {
                Log.i(TAG, "active calls: " + e.status + " " + e.getMessage());
            }
        });
    }

    private void handle(RelayCallsEvent event) {
        trackLive(event);
        DirectSession d = direct;
        if (d != null && d.manager == this) {
            d.call.handle(event);
        }
        RelayGroupSession g = RelayGroupSession.current;
        if (g != null && g.manager == this) {
            g.call.handle(event);
        }
        if (event.type == RelayCallsEvent.Type.INCOMING) {
            maybeRing(event.call);
            RelayGroupRing.maybeRing(this, event.call); // polish 2a: group-message rings
        } else if (event.type == RelayCallsEvent.Type.ACTIVE_CALLS) {
            for (RelayCall call : event.calls) {
                maybeRing(call);
                RelayGroupRing.maybeRing(this, call);
                RelayGroupRing.onCallChanged(this, call);
            }
        } else if (event.call != null) {
            RelayGroupRing.onCallChanged(this, event.call);
        }
    }

    /** Keeps the live calls of the account's channels (the join panel of channels and group messages). */
    private void trackLive(RelayCallsEvent event) {
        List<RelayCall> changed = new ArrayList<>();
        if (event.type == RelayCallsEvent.Type.ACTIVE_CALLS) {
            liveSynced = true;
            java.util.Set<String> ids = new java.util.HashSet<>();
            for (RelayCall call : event.calls) {
                ids.add(call.id);
                live.put(call.id, call);
                changed.add(call);
            }
            for (RelayCall old : new ArrayList<>(live.values())) {
                if (!ids.contains(old.id)) {
                    live.remove(old.id);
                    RelayCall ended = old.copy();
                    ended.state = RelayCall.State.ENDED;
                    changed.add(ended);
                }
            }
        } else if (event.call != null) {
            if (event.call.state == RelayCall.State.ENDED) {
                live.remove(event.call.id);
            } else {
                live.put(event.call.id, event.call);
            }
            changed.add(event.call);
        }
        for (RelayCall call : changed) {
            if (call.kind != RelayCall.Kind.DIRECT) {
                RelayGroupSession.liveCallChanged(this, call);
            }
        }
    }

    /** A direct ring for this user becomes Telegram's incoming call. */
    private void maybeRing(RelayCall call) {
        if (call == null || call.kind != RelayCall.Kind.DIRECT || !call.isRinging(ownUserId) || ownUserId.equals(call.startedBy)) {
            return;
        }
        DirectSession d = direct;
        if (d != null && call.id.equals(d.call.callId())) {
            return;
        }
        if (hasActiveCall() || VoIPService.getSharedInstance() != null) {
            Log.i(TAG, "call " + call.id + ": busy (another call), declining");
            RelayDirectCall.declineBusy(client, scheduler, call.id, deviceId);
            return;
        }
        scheduler.io(() -> {
            long callTlId = relay.db().tlId(RelayDatabase.KIND_CALL, call.id);
            long callerTlId = relay.userTlId(call.startedBy);
            List<TLRPC.User> users = RelayHooks.tlUsers(account, Collections.singletonList(call.startedBy));
            scheduler.post(() -> startIncoming(call, callTlId, callerTlId, users));
        });
    }

    private void startIncoming(RelayCall call, long callTlId, long callerTlId, List<TLRPC.User> users) {
        if (hasActiveCall() || VoIPService.getSharedInstance() != null || !call.isRinging(ownUserId)) {
            if (hasActiveCall() || VoIPService.getSharedInstance() != null) {
                RelayDirectCall.declineBusy(client, scheduler, call.id, deviceId);
            }
            return;
        }
        RelayLiveKitEngine engine = RelayVoip.engine();
        RelayDirectCall controller = RelayDirectCall.incoming(client, engine, scheduler, ownUserId, deviceId, call);
        DirectSession session = new DirectSession(this, controller, engine, callTlId, callerTlId, false, call.video);
        direct = session;
        // Bridge plays the caller of Telegram's key exchange: a, g_a; the client checks sha256(g_a).
        session.secret = new BigInteger(1, randomBytes(256));
        session.gOurs = pad256(BigInteger.valueOf(DH_G).modPow(session.secret, DH_P).toByteArray());
        controller.observe(session);
        controller.start();
        TL_phone.phoneCallRequested requested = new TL_phone.phoneCallRequested();
        requested.id = callTlId;
        requested.access_hash = session.accessHash;
        requested.date = org.telegram.tgnet.ConnectionsManager.getInstance(account).getCurrentTime();
        session.date = requested.date;
        requested.admin_id = callerTlId;
        requested.participant_id = relay.selfTlId();
        requested.g_a_hash = Utilities.computeSHA256(session.gOurs, 0, session.gOurs.length);
        requested.protocol = protocol(session.library);
        requested.video = call.video;
        Log.i(TAG, "call " + call.id + ": incoming from " + call.startedBy + " (tl " + callTlId + ")");
        emit(requested, Collections.singletonList(call.startedBy));
    }

    // MARK: - TL requests (bridge executor)

    /** {@code messages.getDhConfig}: Telegram's prime, g = 3. */
    public static TLObject dhConfig(TLRPC.TL_messages_getDhConfig req) {
        TLRPC.TL_messages_dhConfig config = new TLRPC.TL_messages_dhConfig();
        config.g = DH_G;
        config.p = pad256(DH_P.toByteArray());
        config.version = 1;
        config.random = randomBytes(Math.max(0, Math.min(req.random_length, 4096)));
        return config;
    }

    /** {@code phone.requestCall}: rings the DM with the user; answered with {@code phoneCallWaiting} once the plugin has the call. */
    public void requestCall(TL_phone.requestCall req, Answer answer) {
        long peerTlId = req.user_id.user_id;
        String peerUserId = relay.db().mmId(RelayDatabase.KIND_USER, peerTlId);
        String channelId = peerUserId == null ? null : RelayHooks.channelIdOfDialog(account, peerTlId);
        if (channelId == null) {
            try {
                channelId = relay.client.createDirectChannel(ownUserId, peerUserId).id;
            } catch (Exception e) {
                Log.w(TAG, "requestCall: no DM with " + peerUserId + ": " + e);
            }
        }
        if (peerUserId == null || channelId == null) {
            answer.answer(error(400, "USER_ID_INVALID"));
            return;
        }
        final String dm = channelId;
        scheduler.post(() -> {
            if (hasActiveCall()) {
                answer.answer(error(400, "CALL_ALREADY_ACTIVE"));
                return;
            }
            RelayLiveKitEngine engine = RelayVoip.engine();
            RelayDirectCall controller = RelayDirectCall.outgoing(client, engine, scheduler, ownUserId, deviceId, dm, peerUserId, req.video);
            DirectSession session = new DirectSession(this, controller, engine, 0, peerTlId, true, req.video);
            session.gAHash = req.g_a_hash;
            // Bridge plays the callee: b, g_b.
            session.secret = new BigInteger(1, randomBytes(256));
            session.gOurs = pad256(BigInteger.valueOf(DH_G).modPow(session.secret, DH_P).toByteArray());
            session.pendingRequest = answer;
            direct = session;
            controller.observe(session);
            Log.i(TAG, "requestCall: ringing " + peerUserId + " in " + dm + (req.video ? " (video)" : ""));
            controller.start();
        });
    }

    /** {@code phone.acceptCall}: accepts the ring on the plugin; the {@code phoneCall} follows as an update. */
    public void acceptCall(TL_phone.acceptCall req, Answer answer) {
        scheduler.post(() -> {
            DirectSession d = direct;
            if (d == null || d.tlId != req.peer.id || d.outgoing) {
                answer.answer(error(400, "CALL_PEER_INVALID"));
                return;
            }
            d.clientG = req.g_b;
            d.call.accept();
            TL_phone.TL_phoneCallWaiting waiting = d.waiting(false);
            answer.answer(phoneCall(waiting, d));
            if (d.call.state() != RelayDirectCall.State.TERMINATED) {
                // The keys are "exchanged" at once: Telegram creates its call instance, which shows Connecting
                // until the caller's device is in the room.
                scheduler.post(d::sendConnected);
            }
        });
    }

    /** {@code phone.confirmCall} (outgoing, after {@code phoneCallAccepted}): the {@code phoneCall}. */
    public void confirmCall(TL_phone.confirmCall req, Answer answer) {
        scheduler.post(() -> {
            DirectSession d = direct;
            if (d == null || d.tlId != req.peer.id || !d.outgoing) {
                answer.answer(error(400, "CALL_PEER_INVALID"));
                return;
            }
            if (d.gAHash != null && !Arrays.equals(d.gAHash, Utilities.computeSHA256(req.g_a, 0, req.g_a.length))) {
                Log.w(TAG, "confirmCall: g_a does not match its hash");
            }
            TL_phone.TL_phoneCall call = d.phoneCall(d.gOurs, req.key_fingerprint);
            d.connectedSent = true;
            answer.answer(phoneCall(call, d));
        });
    }

    /** {@code phone.discardCall}: hang up / decline (busy for a second ring). Answered with the discarded call. */
    public void discardCall(TL_phone.discardCall req, Answer answer) {
        scheduler.post(() -> {
            DirectSession d = direct;
            TLRPC.TL_updates updates = new TLRPC.TL_updates();
            updates.date = org.telegram.tgnet.ConnectionsManager.getInstance(account).getCurrentTime();
            if (d == null || d.tlId != req.peer.id) {
                // A ring Telegram declined itself (busy): decline it on the plugin.
                scheduler.io(() -> {
                    String callId = relay.db().mmId(RelayDatabase.KIND_CALL, req.peer.id);
                    if (callId != null) {
                        try {
                            client.decline(callId, deviceId, req.reason instanceof TLRPC.TL_phoneCallDiscardReasonBusy ? "busy" : "declined");
                        } catch (RelayCallsException e) {
                            Log.i(TAG, "discardCall " + callId + ": " + e.getMessage());
                        }
                    }
                });
                answer.answer(updates);
                return;
            }
            d.discardedSent = true;
            RelayDirectCall.State state = d.call.state();
            if (!d.outgoing && state == RelayDirectCall.State.RINGING) {
                // Telegram declines a ring with reason "busy" (its servers show "declined" for that): the user
                // declined. A ring this device cannot take never gets this far (it is declined busy by the manager).
                d.call.decline("declined");
            } else {
                d.call.hangup();
            }
            TL_update.TL_updatePhoneCall update = new TL_update.TL_updatePhoneCall();
            update.phone_call = d.discarded(req.reason, Math.max(0, req.duration));
            updates.updates.add(update);
            answer.answer(updates);
        });
    }

    // MARK: - Updates

    void emit(TL_phone.PhoneCall call, List<String> mmUserIds) {
        TL_update.TL_updatePhoneCall update = new TL_update.TL_updatePhoneCall();
        update.phone_call = call;
        List<TLRPC.Update> list = new ArrayList<>();
        list.add(update);
        RelayHooks.deliverUpdates(account, list, mmUserIds, null);
    }

    private TLObject phoneCall(TL_phone.PhoneCall call, DirectSession d) {
        TL_phone.TL_phone_phoneCall result = new TL_phone.TL_phone_phoneCall();
        result.phone_call = call;
        result.users.addAll(d.users);
        return result;
    }

    // MARK: - Helpers

    static TL_phone.TL_phoneCallProtocol protocol(String library) {
        TL_phone.TL_phoneCallProtocol protocol = new TL_phone.TL_phoneCallProtocol();
        protocol.udp_p2p = true;
        protocol.udp_reflector = true;
        protocol.min_layer = 65;
        protocol.max_layer = org.telegram.messenger.voip.Instance.getConnectionMaxLayer();
        protocol.library_versions.add(library == null ? LIBRARY_VERSION : library);
        return protocol;
    }

    static TLRPC.TL_error error(int code, String text) {
        TLRPC.TL_error e = new TLRPC.TL_error();
        e.code = code;
        e.text = text;
        return e;
    }

    static byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        Utilities.random.nextBytes(b);
        return b;
    }

    /** A big-endian number as exactly 256 bytes (Telegram's DH values). */
    static byte[] pad256(byte[] value) {
        if (value.length == 256) {
            return value;
        }
        byte[] out = new byte[256];
        if (value.length > 256) {
            System.arraycopy(value, value.length - 256, out, 0, 256);
        } else {
            System.arraycopy(value, 0, out, 256 - value.length, value.length);
        }
        return out;
    }

    /** Telegram's key fingerprint: the low 8 bytes of sha1(key). */
    static long fingerprint(byte[] authKey) {
        byte[] hash = Utilities.computeSHA1(authKey);
        byte[] id = new byte[8];
        System.arraycopy(hash, hash.length - 8, id, 0, 8);
        return Utilities.bytesToLong(id);
    }

    static android.content.Context context() {
        return ApplicationLoader.applicationContext;
    }

    // MARK: - The 1:1 session

    /** A {@link RelayDirectCall} and the Telegram call it stands for. Main thread. */
    static final class DirectSession implements RelayDirectCall.Observer {
        final RelayCallManager manager;
        final RelayDirectCall call;
        final RelayLiveKitEngine engine;
        long tlId;
        long accessHash;
        final long peerTlId;
        final boolean outgoing;
        final boolean video;
        int date;
        /** Telegram compares versions as strings ("2.7.7" or later offers video), so not "11.x". */
        String library = LIBRARY_VERSION;
        byte[] gAHash;
        BigInteger secret;
        byte[] gOurs;
        byte[] clientG;
        final List<TLRPC.User> users = new ArrayList<>();
        Answer pendingRequest;
        boolean receivedSent;
        boolean acceptedSent;
        boolean connectedSent;
        boolean discardedSent;
        boolean released;
        RelayPrivateInstance instance;

        DirectSession(RelayCallManager manager, RelayDirectCall call, RelayLiveKitEngine engine, long tlId, long peerTlId, boolean outgoing, boolean video) {
            this.manager = manager;
            this.call = call;
            this.engine = engine;
            this.tlId = tlId;
            this.peerTlId = peerTlId;
            this.outgoing = outgoing;
            this.video = video;
            if (tlId != 0) {
                accessHash = RelayUsers.accessHash("call:" + tlId);
            }
        }

        long adminId() {
            return outgoing ? manager.relay.selfTlId() : peerTlId;
        }

        long participantId() {
            return outgoing ? peerTlId : manager.relay.selfTlId();
        }

        TL_phone.TL_phoneCallWaiting waiting(boolean received) {
            TL_phone.TL_phoneCallWaiting w = new TL_phone.TL_phoneCallWaiting();
            w.id = tlId;
            w.access_hash = accessHash;
            w.date = date;
            w.admin_id = adminId();
            w.participant_id = participantId();
            w.protocol = protocol(library);
            w.video = video;
            if (received) {
                w.flags |= 1;
                w.receive_date = org.telegram.tgnet.ConnectionsManager.getInstance(manager.account).getCurrentTime();
            }
            return w;
        }

        TL_phone.TL_phoneCall phoneCall(byte[] gAOrB, long keyFingerprint) {
            TL_phone.TL_phoneCall c = new TL_phone.TL_phoneCall();
            c.id = tlId;
            c.access_hash = accessHash;
            c.date = date;
            c.admin_id = adminId();
            c.participant_id = participantId();
            c.g_a_or_b = gAOrB;
            c.key_fingerprint = keyFingerprint;
            c.protocol = protocol(library);
            c.video = video;
            c.p2p_allowed = false;
            c.start_date = org.telegram.tgnet.ConnectionsManager.getInstance(manager.account).getCurrentTime();
            // Telegram builds its endpoints from these; Relay's media goes to the plugin's LiveKit room instead.
            TLRPC.TL_phoneConnection connection = new TLRPC.TL_phoneConnection();
            connection.id = 1;
            connection.ip = "127.0.0.1";
            connection.ipv6 = "";
            connection.port = 7880;
            connection.peer_tag = new byte[16];
            c.connections.add(connection);
            return c;
        }

        TL_phone.TL_phoneCallDiscarded discarded(TLRPC.PhoneCallDiscardReason reason, int duration) {
            TL_phone.TL_phoneCallDiscarded d = new TL_phone.TL_phoneCallDiscarded();
            d.id = tlId;
            d.video = video;
            d.flags |= 1;
            d.reason = reason != null ? reason : new TLRPC.TL_phoneCallDiscardReasonHangup();
            if (duration > 0) {
                d.flags |= 2;
                d.duration = duration;
            }
            return d;
        }

        /** Incoming, after the accept: Telegram's {@code phoneCall} with the caller's g_a and the fingerprint. */
        void sendConnected() {
            if (connectedSent || discardedSent || clientG == null) {
                return;
            }
            connectedSent = true;
            byte[] key = pad256(new BigInteger(1, clientG).modPow(secret, DH_P).toByteArray());
            manager.emit(phoneCall(gOurs, fingerprint(key)), Collections.emptyList());
        }

        @Override
        public void onChange(RelayDirectCall.Snapshot s) {
            if (pendingRequest != null && (s.callId != null || s.state == RelayDirectCall.State.TERMINATED)) {
                answerRequest(s);
                return;
            }
            if (tlId == 0) {
                return;
            }
            if (instance != null) {
                instance.onCallChanged(s);
            }
            if (outgoing && !receivedSent && s.state == RelayDirectCall.State.REQUESTING && s.calleeRinging) {
                receivedSent = true;
                manager.emit(waiting(true), Collections.emptyList());
            }
            boolean answered = s.state == RelayDirectCall.State.CONNECTING || s.state == RelayDirectCall.State.ACTIVE || s.state == RelayDirectCall.State.RECONNECTING;
            if (outgoing && answered && !acceptedSent) {
                acceptedSent = true;
                TL_phone.TL_phoneCallAccepted accepted = new TL_phone.TL_phoneCallAccepted();
                accepted.id = tlId;
                accepted.access_hash = accessHash;
                accepted.date = date;
                accepted.admin_id = adminId();
                accepted.participant_id = participantId();
                accepted.g_b = gOurs;
                accepted.protocol = protocol(library);
                accepted.video = video;
                manager.emit(accepted, Collections.emptyList());
            }
            if (s.state == RelayDirectCall.State.TERMINATED) {
                ended(s);
            }
        }

        private void answerRequest(RelayDirectCall.Snapshot s) {
            Answer answer = pendingRequest;
            pendingRequest = null;
            if (s.callId == null) {
                // The ring failed before the plugin created a call.
                answer.answer(error(s.endReason == RelayDirectCall.EndReason.BUSY ? 400 : 500, s.failure != null ? "CALL_FAILED" : "CALL_FAILED"));
                end();
                return;
            }
            String callId = s.callId;
            manager.scheduler.io(() -> {
                long id = manager.relay.db().tlId(RelayDatabase.KIND_CALL, callId);
                List<TLRPC.User> u = RelayHooks.tlUsers(manager.account, Collections.singletonList(call.peerUserId));
                manager.scheduler.post(() -> {
                    tlId = id;
                    accessHash = RelayUsers.accessHash("call:" + id);
                    date = org.telegram.tgnet.ConnectionsManager.getInstance(manager.account).getCurrentTime();
                    users.addAll(u);
                    Log.i(TAG, "call " + callId + ": outgoing, tl " + id);
                    TL_phone.TL_phone_phoneCall result = new TL_phone.TL_phone_phoneCall();
                    result.phone_call = waiting(false);
                    result.users.addAll(users);
                    answer.answer(result);
                    // Catch up on what happened meanwhile (ringing, answer, end).
                    manager.scheduler.post(() -> onChange(call.snapshot()));
                });
            });
        }

        private void ended(RelayDirectCall.Snapshot s) {
            if (!discardedSent) {
                discardedSent = true;
                TLRPC.PhoneCallDiscardReason reason;
                switch (s.endReason) {
                    case BUSY:
                    case DECLINED:
                        reason = outgoing ? new TLRPC.TL_phoneCallDiscardReasonBusy() : new TLRPC.TL_phoneCallDiscardReasonHangup();
                        break;
                    case MISSED:
                    case CANCELLED:
                        reason = new TLRPC.TL_phoneCallDiscardReasonMissed();
                        break;
                    case FAILED:
                        reason = new TLRPC.TL_phoneCallDiscardReasonDisconnect();
                        break;
                    default:
                        reason = new TLRPC.TL_phoneCallDiscardReasonHangup();
                        break;
                }
                Log.i(TAG, "call " + s.callId + ": ended " + s.endReason + (s.failure != null ? " (" + s.failure + ")" : "") + ", duration " + s.duration);
                manager.emit(discarded(reason, s.duration > 0 ? (int) Math.round(s.duration) : 0), Collections.emptyList());
            }
            end();
        }

        private void end() {
            if (released) {
                return;
            }
            released = true;
            if (direct == this) {
                direct = null;
            }
            if (instance != null) {
                instance.onCallChanged(call.snapshot());
            }
            // Let the disconnect finish first.
            manager.scheduler.schedule(1000, RelayVoip::releaseIfIdle);
        }
    }
}
