package org.telegram.relay.calls;

import android.util.Log;

import org.telegram.messenger.voip.NativeInstance;
import org.telegram.messenger.voip.VideoCapturerDevice;
import org.telegram.relay.account.RelayDatabase;
import org.telegram.relay.account.RelayUsers;
import org.telegram.relay.bridge.RelayHooks;
import org.telegram.relay.mattermost.calls.RelayCall;
import org.telegram.relay.mattermost.calls.RelayCallsException;
import org.telegram.relay.mattermost.calls.RelayGroupCall;
import org.telegram.relay.mattermost.calls.RelayMedia;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_phone;
import org.telegram.tgnet.tl.TL_update;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A channel or group-message call (plugin kinds {@code channel} and {@code group}) presented with Telegram's own
 * video chat UI (GroupCallActivity, the chat's join panel), the Android side of iOS's
 * {@code RelayPresentationGroupCall}. The bridge answers Telegram's group-call methods
 * ({@code createGroupCall}, {@code joinGroupCall}, {@code getGroupCall}, {@code getGroupParticipants},
 * {@code editGroupCallParticipant}, {@code leaveGroupCall}, {@code discardGroupCall}, presentations) from a
 * {@link RelayGroupCall} and pushes {@code updateGroupCall} / {@code updateGroupCallParticipants}:
 *
 * <ul>
 *   <li>one Telegram participant per Mattermost user (devices merged), synthetic SSRCs and video endpoints
 *       {@code relay:camera:<user>} / {@code relay:screen:<user>} (as iOS), mute, camera, screen share, speaking
 *       through audio levels;</li>
 *   <li>Telegram's admin features (titles, recording, join-muted, invite links, raise hand, end for everyone)
 *       stay hidden: {@code canManageCall} is false in Relay.</li>
 * </ul>
 * Main thread unless noted.
 */
public final class RelayGroupSession implements RelayGroupCall.Observer {
    private static final String TAG = RelayCallManager.TAG;
    static final int VIDEO_LIMIT = 25;

    /** The video chat the app is in (one call at a time). */
    static RelayGroupSession current;
    /** Telegram creates its group instance before it joins: the session adopts it. */
    private static RelayGroupInstance pendingInstance;
    private static RelayGroupInstance pendingScreenInstance;

    final RelayCallManager manager;
    final String channelId;
    final long chatTlId;
    final RelayGroupCall call;
    final RelayLiveKitEngine engine;
    String callId;
    long callTlId;
    long accessHash;
    private volatile int version = 1;
    /** What Telegram was told, per Mattermost user (read by the bridge executor too). */
    private final Map<String, TLRPC.TL_groupCallParticipant> published = Collections.synchronizedMap(new LinkedHashMap<String, TLRPC.TL_groupCallParticipant>());
    private final Set<String> knownUsers = new HashSet<>();
    private final List<RelayCallManager.Answer> waitingForCall = new ArrayList<>();
    private final List<RelayCallManager.Answer> waitingForJoin = new ArrayList<>();
    private boolean selfJoined;
    private boolean ended;
    RelayGroupInstance instance;
    RelayGroupInstance screenInstance;

    private RelayGroupSession(RelayCallManager manager, String channelId, long chatTlId, RelayGroupCall call, RelayLiveKitEngine engine) {
        this.manager = manager;
        this.channelId = channelId;
        this.chatTlId = chatTlId;
        this.call = call;
        this.engine = engine;
    }

    // MARK: - Ids

    /** A stable SSRC of a Mattermost user (audio); +1 camera, +2 screen. */
    static int ssrc(String userId) {
        int h = userId.hashCode() & 0x0ffffff0;
        return h == 0 ? 0x10 : h;
    }

    static String endpoint(String userId, boolean screen) {
        return (screen ? "relay:screen:" : "relay:camera:") + userId;
    }

    static TLRPC.TL_inputGroupCall inputCall(long tlId, long accessHash) {
        TLRPC.TL_inputGroupCall input = new TLRPC.TL_inputGroupCall();
        input.id = tlId;
        input.access_hash = accessHash;
        return input;
    }

    static long accessHash(long callTlId) {
        return RelayUsers.accessHash("groupcall:" + callTlId);
    }

    // MARK: - Starting and joining (TL handlers; bridge executor)

    /** {@code phone.createGroupCall}: starts the channel's call (rings a group message's members). */
    public static void create(RelayCallManager manager, TL_phone.createGroupCall req, RelayCallManager.Answer answer) {
        long chatTlId = req.peer.channel_id != 0 ? req.peer.channel_id : req.peer.chat_id;
        String channelId = manager.relay.db().mmId(RelayDatabase.KIND_CHANNEL, chatTlId);
        if (channelId == null) {
            answer.answer(RelayCallManager.error(400, "PEER_ID_INVALID"));
            return;
        }
        boolean ring = isGroupMessage(manager, channelId);
        manager.scheduler.post(() -> {
            if (RelayCallManager.hasActiveCall()) {
                answer.answer(RelayCallManager.error(400, "GROUPCALL_ALREADY_STARTED"));
                return;
            }
            RelayLiveKitEngine engine = RelayVoip.engine();
            RelayGroupCall controller = ring
                    ? RelayGroupCall.ring(manager.client, engine, manager.scheduler, manager.ownUserId, manager.deviceId, channelId, false)
                    : RelayGroupCall.join(manager.client, engine, manager.scheduler, manager.ownUserId, manager.deviceId, channelId, null, RelayCall.Kind.CHANNEL);
            RelayGroupSession session = new RelayGroupSession(manager, channelId, chatTlId, controller, engine);
            session.waitingForCall.add(answer);
            session.begin();
            Log.i(TAG, "createGroupCall: " + (ring ? "ringing" : "starting") + " in " + channelId);
        });
    }

    /** {@code phone.joinGroupCall}: joins the call (the one created above, or a running one from the join panel). */
    public static void join(RelayCallManager manager, TL_phone.joinGroupCall req, RelayCallManager.Answer answer) {
        long callTlId = req.call.id;
        String callId = manager.relay.db().mmId(RelayDatabase.KIND_CALL, callTlId);
        if (callId == null) {
            answer.answer(RelayCallManager.error(400, "GROUPCALL_INVALID"));
            return;
        }
        RelayCall known = manager.live.get(callId);
        String channelId = known != null ? known.channelId : null;
        if (channelId == null) {
            try {
                RelayCall fetched = manager.client.call(callId);
                channelId = fetched != null && fetched.isLive() ? fetched.channelId : null;
            } catch (RelayCallsException e) {
                Log.w(TAG, "joinGroupCall " + callId + ": " + e.getMessage());
            }
        }
        RelayGroupSession existing = current;
        if (channelId == null && (existing == null || !callId.equals(existing.callId))) {
            answer.answer(RelayCallManager.error(400, "GROUPCALL_INVALID"));
            return;
        }
        final String channel = channelId;
        long chatTlId = channel != null ? manager.relay.db().tlId(RelayDatabase.KIND_CHANNEL, channel) : 0;
        manager.scheduler.post(() -> {
            RelayGroupSession session = current;
            if (session != null && callId.equals(session.callId)) {
                session.answerJoin(answer, req.muted);
                return;
            }
            if (RelayCallManager.hasActiveCall()) {
                answer.answer(RelayCallManager.error(400, "GROUPCALL_ALREADY_STARTED"));
                return;
            }
            RelayLiveKitEngine engine = RelayVoip.engine();
            RelayCall.Kind kind = known != null ? known.kind : RelayCall.Kind.CHANNEL;
            // Polish 2a: a group-message call still ringing for this user is accepted (the plugin stops the ring and
            // records the answer), as iOS's RelayGroupCall.incoming(…).accept(); otherwise a plain join.
            boolean ringing = known != null && known.kind == RelayCall.Kind.GROUP && known.isRinging(manager.ownUserId);
            RelayGroupCall controller = ringing
                    ? RelayGroupCall.incoming(manager.client, engine, manager.scheduler, manager.ownUserId, manager.deviceId, known)
                    : RelayGroupCall.join(manager.client, engine, manager.scheduler, manager.ownUserId, manager.deviceId, channel, callId, kind);
            session = new RelayGroupSession(manager, channel, chatTlId, controller, engine);
            session.callId = callId;
            session.callTlId = callTlId;
            session.accessHash = accessHash(callTlId);
            if (req.muted) {
                controller.setMuted(true);
            }
            session.begin();
            if (ringing) {
                controller.accept();
            }
            session.answerJoin(answer, req.muted);
            Log.i(TAG, "joinGroupCall: " + callId + " in " + channel + (ringing ? " (ring accepted)" : ""));
        });
    }

    private static boolean isGroupMessage(RelayCallManager manager, String channelId) {
        try {
            org.telegram.relay.mattermost.model.Channel channel = manager.relay.client.channel(channelId);
            return channel != null && channel.isGroup();
        } catch (Exception e) {
            return false;
        }
    }

    private void begin() {
        current = this;
        call.observe(this);
        if (pendingInstance != null) {
            adopt(pendingInstance);
            pendingInstance = null;
        }
        if (pendingScreenInstance != null) {
            adopt(pendingScreenInstance);
            pendingScreenInstance = null;
        }
        call.start();
    }

    private void adopt(RelayGroupInstance i) {
        if (i.screencast) {
            screenInstance = i;
        } else {
            instance = i;
        }
        i.bind(this);
    }

    private void answerJoin(RelayCallManager.Answer answer, boolean muted) {
        if (callId == null || call.state() == RelayGroupCall.State.CONNECTING && call.callId() == null) {
            waitingForJoin.add(answer);
            return;
        }
        selfJoined = true;
        TLRPC.TL_updates updates = new TLRPC.TL_updates();
        updates.date = now();
        TL_update.TL_updateGroupCallParticipants participants = new TL_update.TL_updateGroupCallParticipants();
        participants.call = inputCall(callTlId, accessHash);
        participants.version = version;
        TLRPC.TL_groupCallParticipant self = selfParticipant(muted);
        participants.participants.add(self);
        published.put(manager.ownUserId, self);
        updates.updates.add(participants);
        TL_update.TL_updateGroupCallConnection connection = new TL_update.TL_updateGroupCallConnection();
        connection.params = new TLRPC.TL_dataJSON();
        connection.params.data = "{\"relay\":true}";
        updates.updates.add(connection);
        TL_update.TL_updateGroupCall groupCall = new TL_update.TL_updateGroupCall();
        groupCall.flags |= TLObject.FLAG_1;
        groupCall.peer = peer();
        groupCall.call = tlCall(Math.max(1, call.participants().size()));
        updates.updates.add(groupCall);
        List<TLRPC.User> users = RelayHooks.tlUsers(manager.account, Collections.singletonList(manager.ownUserId));
        updates.users.addAll(users);
        answer.answer(updates);
    }

    // MARK: - Controller

    @Override
    public void onChange(RelayGroupCall.Snapshot s) {
        if (s.callId != null && callId == null) {
            callId = s.callId;
            String id = s.callId;
            manager.scheduler.io(() -> {
                long tl = manager.relay.db().tlId(RelayDatabase.KIND_CALL, id);
                manager.scheduler.post(() -> {
                    callTlId = tl;
                    accessHash = accessHash(tl);
                    Log.i(TAG, "group call " + id + ": tl " + tl);
                    for (RelayCallManager.Answer a : waitingForCall) {
                        a.answer(createdUpdates());
                    }
                    waitingForCall.clear();
                    for (RelayCallManager.Answer a : new ArrayList<>(waitingForJoin)) {
                        waitingForJoin.remove(a);
                        answerJoin(a, call.snapshot().isMuted);
                    }
                    onChange(call.snapshot());
                });
            });
            return;
        }
        if (callTlId == 0) {
            if (s.state == RelayGroupCall.State.ENDED) {
                for (RelayCallManager.Answer a : waitingForCall) {
                    a.answer(RelayCallManager.error(400, "GROUPCALL_CREATE_FAILED"));
                }
                for (RelayCallManager.Answer a : waitingForJoin) {
                    a.answer(RelayCallManager.error(400, "GROUPCALL_INVALID"));
                }
                waitingForCall.clear();
                waitingForJoin.clear();
                finish();
            }
            return;
        }
        if (!waitingForJoin.isEmpty() && s.callId != null && s.state != RelayGroupCall.State.ENDED) {
            // The plugin's join answered: Telegram's joinGroupCall gets its participant and connection.
            for (RelayCallManager.Answer a : new ArrayList<>(waitingForJoin)) {
                waitingForJoin.remove(a);
                answerJoin(a, s.isMuted);
            }
        }
        if (instance != null) {
            instance.refresh();
        }
        if (s.state == RelayGroupCall.State.ENDED) {
            for (RelayCallManager.Answer a : waitingForJoin) {
                a.answer(RelayCallManager.error(400, "GROUPCALL_INVALID"));
            }
            waitingForJoin.clear();
            Log.i(TAG, "group call " + callId + ": ended " + s.endReason + (s.failure != null ? " (" + s.failure + ")" : ""));
            if (s.endReason == RelayGroupCall.EndReason.ENDED || s.endReason == RelayGroupCall.EndReason.REMOVED || s.endReason == RelayGroupCall.EndReason.FAILED) {
                // Ended for everyone (or this device was removed): Telegram leaves the video chat.
                emitCall(true);
            } else {
                publishParticipants(Collections.<RelayGroupCall.Participant>emptyList(), true);
            }
            finish();
            return;
        }
        if (selfJoined) {
            publishParticipants(s.participants, false);
        }
    }

    private void finish() {
        if (ended) {
            return;
        }
        ended = true;
        if (current == this) {
            current = null;
        }
        manager.scheduler.schedule(1000, RelayVoip::releaseIfIdle);
    }

    /** Diffs the merged participants against what Telegram has and pushes the changes. */
    private void publishParticipants(List<RelayGroupCall.Participant> list, boolean allLeft) {
        List<TLRPC.GroupCallParticipant> changed = new ArrayList<>();
        Set<String> present = new HashSet<>();
        List<String> newUsers = new ArrayList<>();
        if (!allLeft) {
            for (RelayGroupCall.Participant p : list) {
                present.add(p.userId);
                TLRPC.TL_groupCallParticipant tl = participant(p);
                TLRPC.TL_groupCallParticipant old = published.get(p.userId);
                if (old == null || !same(old, tl)) {
                    if (old == null) {
                        tl.just_joined = true;
                    }
                    published.put(p.userId, tl);
                    changed.add(tl);
                    if (knownUsers.add(p.userId)) {
                        newUsers.add(p.userId);
                    }
                }
            }
        }
        for (String userId : new ArrayList<>(published.keySet())) {
            if (!present.contains(userId) && !(userId.equals(manager.ownUserId) && !allLeft)) {
                TLRPC.TL_groupCallParticipant gone = published.remove(userId);
                gone.left = true;
                gone.just_joined = false;
                changed.add(gone);
            }
        }
        if (changed.isEmpty()) {
            return;
        }
        int countBefore = version;
        TL_update.TL_updateGroupCallParticipants update = new TL_update.TL_updateGroupCallParticipants();
        update.call = inputCall(callTlId, accessHash);
        update.participants.addAll(changed);
        update.version = ++version;
        List<TLRPC.Update> updates = new ArrayList<>();
        updates.add(update);
        TL_update.TL_updateGroupCall groupCall = new TL_update.TL_updateGroupCall();
        groupCall.flags |= TLObject.FLAG_1;
        groupCall.peer = peer();
        groupCall.call = tlCall(Math.max(published.size(), 0));
        updates.add(groupCall);
        RelayHooks.deliverUpdates(manager.account, updates, newUsers, null);
        if (countBefore == 1) {
            Log.i(TAG, "group call " + callId + ": participants " + published.keySet());
        }
    }

    private static boolean same(TLRPC.TL_groupCallParticipant a, TLRPC.TL_groupCallParticipant b) {
        return a.muted == b.muted && a.source == b.source && videoKey(a.video).equals(videoKey(b.video))
                && videoKey(a.presentation).equals(videoKey(b.presentation)) && (a.active_date != 0) == (b.active_date != 0);
    }

    private static String videoKey(TLRPC.TL_groupCallParticipantVideo v) {
        return v == null ? "" : v.endpoint + (v.paused ? "/p" : "");
    }

    private TLRPC.TL_groupCallParticipant selfParticipant(boolean muted) {
        TLRPC.TL_groupCallParticipant p = new TLRPC.TL_groupCallParticipant();
        p.peer = peerUser(manager.relay.selfTlId());
        p.date = now();
        p.source = ssrc(manager.ownUserId);
        p.muted = muted;
        p.can_self_unmute = true;
        p.self = true;
        p.volume = 10000;
        p.flags |= TLObject.FLAG_7;
        return p;
    }

    TLRPC.TL_groupCallParticipant participant(RelayGroupCall.Participant p) {
        TLRPC.TL_groupCallParticipant tl = new TLRPC.TL_groupCallParticipant();
        tl.peer = peerUser(manager.relay.userTlId(p.userId));
        tl.date = p.joinedAt > 0 ? (int) (p.joinedAt / 1000) : now();
        int source = ssrc(p.userId);
        tl.source = source;
        tl.muted = !p.isMicrophoneEnabled;
        tl.can_self_unmute = true;
        tl.self = p.isSelf;
        tl.volume = 10000;
        tl.flags |= TLObject.FLAG_7;
        if (p.isSpeaking) {
            tl.active_date = now();
            tl.flags |= TLObject.FLAG_3;
        }
        if (p.camera != null && p.camera.track.isLive()) {
            tl.video = video(endpoint(p.userId, false), source + 1);
            tl.flags |= TLObject.FLAG_6;
        }
        if (p.screenShare != null && p.screenShare.track.isLive()) {
            tl.presentation = video(endpoint(p.userId, true), source + 2);
            tl.flags |= TLObject.FLAG_14;
        }
        return tl;
    }

    private static TLRPC.TL_groupCallParticipantVideo video(String endpoint, int source) {
        TLRPC.TL_groupCallParticipantVideo v = new TLRPC.TL_groupCallParticipantVideo();
        v.endpoint = endpoint;
        TLRPC.TL_groupCallParticipantVideoSourceGroup group = new TLRPC.TL_groupCallParticipantVideoSourceGroup();
        group.semantics = "SIM";
        group.sources.add(source);
        v.source_groups.add(group);
        return v;
    }

    private static TLRPC.TL_peerUser peerUser(long id) {
        TLRPC.TL_peerUser peer = new TLRPC.TL_peerUser();
        peer.user_id = id;
        return peer;
    }

    TLRPC.Peer peer() {
        TLRPC.TL_peerChannel peer = new TLRPC.TL_peerChannel();
        peer.channel_id = chatTlId;
        return peer;
    }

    TLRPC.TL_groupCall tlCall(int participants) {
        return tlCall(callTlId, accessHash, participants, version);
    }

    static TLRPC.TL_groupCall tlCall(long tlId, long accessHash, int participants, int version) {
        TLRPC.TL_groupCall c = new TLRPC.TL_groupCall();
        c.id = tlId;
        c.access_hash = accessHash;
        c.participants_count = participants;
        c.can_start_video = true;
        c.join_date_asc = true;
        c.unmuted_video_limit = VIDEO_LIMIT;
        c.version = version;
        return c;
    }

    private TLObject createdUpdates() {
        TLRPC.TL_updates updates = new TLRPC.TL_updates();
        updates.date = now();
        TL_update.TL_updateGroupCall update = new TL_update.TL_updateGroupCall();
        update.flags |= TLObject.FLAG_1;
        update.peer = peer();
        update.call = tlCall(0);
        updates.updates.add(update);
        return updates;
    }

    private void emitCall(boolean discarded) {
        TL_update.TL_updateGroupCall update = new TL_update.TL_updateGroupCall();
        update.flags |= TLObject.FLAG_1;
        update.peer = peer();
        if (discarded) {
            TLRPC.TL_groupCallDiscarded d = new TLRPC.TL_groupCallDiscarded();
            d.id = callTlId;
            d.access_hash = accessHash;
            update.call = d;
        } else {
            update.call = tlCall(published.size());
        }
        List<TLRPC.Update> list = new ArrayList<>();
        list.add(update);
        RelayHooks.deliverUpdates(manager.account, list, null, null);
    }

    int now() {
        return org.telegram.tgnet.ConnectionsManager.getInstance(manager.account).getCurrentTime();
    }

    // MARK: - Telegram → call (main thread)

    void setVideo(boolean screen, boolean on) {
        if (screen) {
            engine.setScreenCapturePermission(VideoCapturerDevice.mediaProjectionPermissionResultData);
            call.setScreenShare(on);
        } else {
            call.setCameraEnabled(on);
        }
    }

    /** The user id of a participant SSRC (audio, camera or screen), or null. */
    String userOfSsrc(int source) {
        for (String userId : new ArrayList<>(published.keySet())) {
            int base = ssrc(userId);
            if (source == base || source == base + 1 || source == base + 2) {
                return userId;
            }
        }
        return null;
    }

    // MARK: - Instances

    static NativeInstance makeInstance(long capturer, boolean screencast, NativeInstance.PayloadCallback payload, NativeInstance.AudioLevelsCallback audioLevels) {
        RelayGroupInstance i = new RelayGroupInstance(screencast, payload, audioLevels);
        RelayGroupSession s = current;
        if (s != null) {
            s.adopt(i);
        } else if (screencast) {
            pendingScreenInstance = i;
        } else {
            pendingInstance = i;
        }
        Log.i(TAG, "group instance (" + (screencast ? "screen" : "camera") + ") for " + (s != null ? s.callId : "a call being joined"));
        return i;
    }

    static void instanceStopped(RelayGroupInstance i) {
        if (pendingInstance == i) {
            pendingInstance = null;
        }
        if (pendingScreenInstance == i) {
            pendingScreenInstance = null;
        }
    }

    // MARK: - Telegram's queries (bridge executor)

    /** {@code phone.getGroupCall} / {@code getGroupParticipants}: from the joined session, else from the plugin's call. */
    public static TLObject getGroupCall(RelayCallManager manager, TLRPC.InputGroupCall input, boolean participantsOnly) {
        String callId = manager.relay.db().mmId(RelayDatabase.KIND_CALL, input.id);
        RelayCall live = callId != null ? manager.live.get(callId) : null;
        if (callId != null && live == null) {
            try {
                live = manager.client.call(callId);
            } catch (RelayCallsException e) {
                Log.w(TAG, "getGroupCall " + callId + ": " + e.getMessage());
            }
        }
        List<TLRPC.GroupCallParticipant> participants = new ArrayList<>();
        List<String> userIds = new ArrayList<>();
        int version = 1;
        long accessHash = accessHash(input.id);
        RelayGroupSession s = current;
        boolean ended = live == null || !live.isLive();
        if (s != null && callId != null && callId.equals(s.callId)) {
            List<TLRPC.TL_groupCallParticipant> snapshot = s.publishedSnapshot();
            participants.addAll(snapshot);
            for (TLRPC.TL_groupCallParticipant p : snapshot) {
                String mm = manager.relay.db().mmId(RelayDatabase.KIND_USER, p.peer.user_id);
                if (mm != null) {
                    userIds.add(mm);
                }
            }
            version = s.versionSnapshot();
            ended = false;
        } else if (live != null) {
            for (String userId : live.participantIds) {
                TLRPC.TL_groupCallParticipant p = new TLRPC.TL_groupCallParticipant();
                p.peer = peerUser(manager.relay.userTlId(userId));
                p.date = (int) (live.startedAt / 1000);
                p.source = ssrc(userId);
                p.can_self_unmute = true;
                p.self = userId.equals(manager.ownUserId);
                participants.add(p);
                userIds.add(userId);
            }
        }
        List<TLRPC.User> users = RelayHooks.tlUsers(manager.account, userIds);
        if (participantsOnly) {
            TL_phone.groupParticipants res = new TL_phone.groupParticipants();
            res.count = participants.size();
            res.participants.addAll(participants);
            res.next_offset = "";
            res.users.addAll(users);
            res.version = version;
            return res;
        }
        TL_phone.groupCall res = new TL_phone.groupCall();
        if (ended) {
            TLRPC.TL_groupCallDiscarded d = new TLRPC.TL_groupCallDiscarded();
            d.id = input.id;
            d.access_hash = accessHash;
            res.call = d;
        } else {
            res.call = tlCall(input.id, accessHash, participants.size(), version);
        }
        res.participants.addAll(participants);
        res.participants_next_offset = "";
        res.users.addAll(users);
        TLRPC.TL_channel chat = live != null ? RelayHooks.tlChannel(manager.account, live.channelId) : null;
        if (chat != null) {
            res.chats.add(chat);
        }
        return res;
    }

    List<TLRPC.TL_groupCallParticipant> publishedSnapshot() {
        synchronized (published) {
            return new ArrayList<>(published.values());
        }
    }

    int versionSnapshot() {
        return version;
    }

    /** The live call of a channel for its full info (join panel): {@code chatFull.call}, {@code call_active}. Bridge executor. */
    public static void decorateFullChannel(int account, String channelId, TLRPC.ChatFull full, TLRPC.Chat chat) {
        if (!org.telegram.relay.RelayFeatures.RELAY_GROUP_CALLS) {
            return;
        }
        RelayCallManager manager = RelayCallManager.of(account);
        if (manager == null) {
            return;
        }
        RelayCall live = null;
        for (RelayCall c : manager.live.values()) {
            if (channelId.equals(c.channelId) && c.kind != RelayCall.Kind.DIRECT && c.isLive()) {
                live = c;
            }
        }
        if (live == null && !manager.liveSynced) {
            try {
                live = manager.client.channelCall(channelId);
            } catch (RelayCallsException e) {
                Log.i(TAG, "channel call " + channelId + ": " + e.getMessage());
            }
            if (live != null && (live.kind == RelayCall.Kind.DIRECT || !live.isLive())) {
                live = null;
            }
        }
        if (live == null) {
            return;
        }
        long tl = manager.relay.db().tlId(RelayDatabase.KIND_CALL, live.id);
        full.call = inputCall(tl, accessHash(tl));
        full.flags |= TLObject.FLAG_21;
        if (chat != null) {
            chat.call_active = true;
            chat.call_not_empty = live.participants > 0 || !live.participantIds.isEmpty();
        }
    }

    /** A live channel call changed (plugin event): Telegram's join panel follows with {@code updateGroupCall}. */
    static void liveCallChanged(RelayCallManager manager, RelayCall call) {
        if (!org.telegram.relay.RelayFeatures.RELAY_GROUP_CALLS || call.channelId == null || call.channelId.isEmpty()) {
            return;
        }
        RelayGroupSession s = current;
        if (s != null && call.id.equals(s.callId)) {
            return;
        }
        manager.scheduler.io(() -> {
            long tl = manager.relay.db().tlId(RelayDatabase.KIND_CALL, call.id);
            long chatTl = manager.relay.db().findTlId(RelayDatabase.KIND_CHANNEL, call.channelId);
            if (chatTl == 0) {
                return;
            }
            TL_update.TL_updateGroupCall update = new TL_update.TL_updateGroupCall();
            update.flags |= TLObject.FLAG_1;
            TLRPC.TL_peerChannel peer = new TLRPC.TL_peerChannel();
            peer.channel_id = chatTl;
            update.peer = peer;
            if (call.isLive()) {
                update.call = tlCall(tl, accessHash(tl), Math.max(call.participantIds.size(), call.participants), 1);
            } else {
                TLRPC.TL_groupCallDiscarded d = new TLRPC.TL_groupCallDiscarded();
                d.id = tl;
                d.access_hash = accessHash(tl);
                d.duration = call.durationMs > 0 ? (int) (call.durationMs / 1000) : 0;
                update.call = d;
            }
            List<TLRPC.Update> list = new ArrayList<>();
            list.add(update);
            TLRPC.TL_channel chat = RelayHooks.tlChannel(manager.account, call.channelId);
            if (chat != null) {
                chat.call_active = call.isLive();
                chat.call_not_empty = call.isLive() && !call.participantIds.isEmpty();
            }
            RelayHooks.deliverUpdates(manager.account, list, call.participantIds, chat != null ? Collections.<TLRPC.Chat>singletonList(chat) : null);
        });
    }

    // MARK: - Edits (bridge executor → main)

    public static void edit(RelayCallManager manager, TL_phone.editGroupCallParticipant req, RelayCallManager.Answer answer) {
        long selfTl = manager.relay.selfTlId();
        long target = req.participant != null ? (req.participant.user_id != 0 ? req.participant.user_id : (req.participant instanceof TLRPC.TL_inputPeerSelf ? selfTl : 0)) : 0;
        String targetUser = target == selfTl ? manager.ownUserId : manager.relay.db().mmId(RelayDatabase.KIND_USER, target);
        manager.scheduler.post(() -> {
            RelayGroupSession s = current;
            TLRPC.TL_updates updates = new TLRPC.TL_updates();
            updates.date = s != null ? s.now() : 0;
            if (s == null || s.callTlId != req.call.id) {
                answer.answer(updates);
                return;
            }
            if (target == selfTl) {
                if ((req.flags & 1) != 0) {
                    s.call.setMuted(req.muted);
                }
            } else if ((req.flags & 2) != 0 && targetUser != null) {
                s.engine.room().setVolume(targetUser, Math.max(0, Math.min(2, req.volume / 10000.0)));
            } else if ((req.flags & 1) != 0 && targetUser != null) {
                // "Mute for me".
                s.engine.room().setVolume(targetUser, req.muted ? 0 : 1);
            }
            answer.answer(updates);
        });
    }

    public static void leave(RelayCallManager manager, TLRPC.InputGroupCall input, boolean endForEveryone, RelayCallManager.Answer answer) {
        manager.scheduler.post(() -> {
            RelayGroupSession s = current;
            TLRPC.TL_updates updates = new TLRPC.TL_updates();
            if (s == null || s.callTlId != input.id) {
                answer.answer(updates);
                return;
            }
            updates.date = s.now();
            if (endForEveryone && s.call.snapshot().canEnd) {
                s.call.endForEveryone(error -> {
                    if (error != null) {
                        Log.w(TAG, "end for everyone failed: " + error);
                        s.call.leave();
                    }
                });
            } else {
                s.call.leave();
            }
            TL_update.TL_updateGroupCallParticipants left = new TL_update.TL_updateGroupCallParticipants();
            left.call = inputCall(s.callTlId, s.accessHash);
            TLRPC.TL_groupCallParticipant self = s.selfParticipant(true);
            self.left = true;
            left.participants.add(self);
            left.version = s.version;
            updates.updates.add(left);
            answer.answer(updates);
        });
    }

    public static void presentation(RelayCallManager manager, TLRPC.InputGroupCall input, boolean on, RelayCallManager.Answer answer) {
        manager.scheduler.post(() -> {
            RelayGroupSession s = current;
            TLRPC.TL_updates updates = new TLRPC.TL_updates();
            if (s == null || s.callTlId != input.id) {
                answer.answer(on ? RelayCallManager.error(400, "GROUPCALL_INVALID") : updates);
                return;
            }
            updates.date = s.now();
            s.setVideo(true, on);
            if (on) {
                TL_update.TL_updateGroupCallConnection connection = new TL_update.TL_updateGroupCallConnection();
                connection.presentation = true;
                connection.params = new TLRPC.TL_dataJSON();
                connection.params.data = "{\"relay\":true}";
                updates.updates.add(connection);
            }
            answer.answer(updates);
        });
    }
}
