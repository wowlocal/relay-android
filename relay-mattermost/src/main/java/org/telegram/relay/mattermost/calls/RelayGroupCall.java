package org.telegram.relay.mattermost.calls;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A {@code group} or {@code channel} call, port of the iOS {@code RelayGroupCall}: join (or start) the channel's
 * call, ring a group message's members, or answer a group ring. The room stays up while anyone is connected;
 * leaving never ends it for others ({@link #endForEveryone} does, for who may). Participants are merged per
 * Mattermost user ({@link #mergeParticipants}): Telegram's group-call UI keys participants by peer.
 */
public final class RelayGroupCall {
    public enum State { RINGING, CONNECTING, CONNECTED, RECONNECTING, ENDED }

    public enum EndReason { LEFT, ENDED, DECLINED, MISSED, ANSWERED_ELSEWHERE, REMOVED, FAILED }

    public static final class Video {
        public final String identity;
        public final RelayMedia.Track track;
        public final boolean isLocal;

        Video(String identity, RelayMedia.Track track, boolean isLocal) {
            this.identity = identity;
            this.track = track;
            this.isLocal = isLocal;
        }
    }

    public static final class Participant {
        public final String userId;
        public final String name;
        public final boolean isSelf;
        /** This device first, then by join time. */
        public final List<RelayMedia.Participant> devices;
        public final boolean isSpeaking;
        public final float audioLevel;
        public final boolean isMicrophoneEnabled;
        public final Video camera;
        public final Video screenShare;
        public final int connectionQuality;
        /** Unix ms, 0 when unknown. */
        public final long joinedAt;

        Participant(String userId, String name, boolean isSelf, List<RelayMedia.Participant> devices, boolean isSpeaking, float audioLevel,
                    boolean isMicrophoneEnabled, Video camera, Video screenShare, int connectionQuality, long joinedAt) {
            this.userId = userId;
            this.name = name;
            this.isSelf = isSelf;
            this.devices = Collections.unmodifiableList(devices);
            this.isSpeaking = isSpeaking;
            this.audioLevel = audioLevel;
            this.isMicrophoneEnabled = isMicrophoneEnabled;
            this.camera = camera;
            this.screenShare = screenShare;
            this.connectionQuality = connectionQuality;
            this.joinedAt = joinedAt;
        }

        @Override
        public String toString() {
            return userId + (isSelf ? " (self)" : "") + (isMicrophoneEnabled ? " mic" : "") + (camera != null && camera.track.isLive() ? " camera" : "")
                    + (screenShare != null && screenShare.track.isLive() ? " screen" : "") + (isSpeaking ? " speaking" : "");
        }
    }

    public static final class Snapshot {
        public final State state;
        public final EndReason endReason;
        public final String failure;
        public final RelayCall call;
        public final String callId;
        public final String channelId;
        public final RelayCall.Kind kind;
        public final List<Participant> participants;
        public final Map<String, RelayCall.Invitee> invitees;
        public final boolean isMuted;
        public final boolean isCameraEnabled;
        public final boolean isScreenSharing;
        public final RelayMedia.ConnectionState mediaState;
        public final boolean canEnd;

        Snapshot(RelayGroupCall c) {
            state = c.state;
            endReason = c.endReason;
            failure = c.failure;
            call = c.call;
            callId = c.callId;
            channelId = c.channelId;
            kind = c.kind;
            participants = c.participants;
            invitees = c.call != null ? Collections.unmodifiableMap(new LinkedHashMap<>(c.call.invitees)) : Collections.<String, RelayCall.Invitee>emptyMap();
            isMuted = !c.microphone.wanted();
            isCameraEnabled = c.camera.wanted();
            isScreenSharing = c.isScreenSharing;
            mediaState = c.media.connectionState;
            canEnd = c.call != null && c.call.canEnd;
        }

        @Override
        public String toString() {
            return state + (endReason != null ? " " + endReason : "") + " call " + callId + " participants " + participants;
        }
    }

    public interface Observer {
        void onChange(Snapshot snapshot);
    }

    private enum Mode { JOIN, RING, INCOMING }

    public final String ownUserId;
    public final String deviceId;
    public final String channelId;
    private final RelayCallsApi api;
    private final RelayMedia.Engine engine;
    private final RelayCallScheduler scheduler;
    private final Mode mode;
    private final String joinCallId;
    private final boolean ringVideo;
    private final List<Observer> observers = new CopyOnWriteArrayList<>();

    private State state;
    private EndReason endReason;
    private String failure;
    private RelayCall call;
    private String callId;
    private List<Participant> participants = Collections.emptyList();
    private RelayCall.Kind kind;
    private boolean started;
    private boolean answered;
    private boolean mediaRequested;
    private boolean mediaConnected;
    private RelayMedia.Snapshot media = RelayMedia.Snapshot.EMPTY;
    private final RelayLocalSource microphone;
    private final RelayLocalSource camera;
    private boolean isScreenSharing;
    private RelayCallScheduler.Cancellable ringTimer;

    private RelayGroupCall(RelayCallsApi api, RelayMedia.Engine engine, RelayCallScheduler scheduler, String ownUserId, String deviceId, String channelId,
                           RelayCall.Kind kind, Mode mode, String joinCallId, RelayCall call, boolean video) {
        this.api = api;
        this.engine = engine;
        this.scheduler = scheduler;
        this.ownUserId = ownUserId;
        this.deviceId = deviceId;
        this.channelId = channelId;
        this.kind = kind;
        this.mode = mode;
        this.joinCallId = joinCallId;
        this.ringVideo = video;
        this.call = call;
        this.callId = call != null ? call.id : null;
        this.microphone = new RelayLocalSource("microphone", true, null, scheduler, RelayLocalSource.MICROPHONE_RETRY_DELAYS,
                RelayLocalSource.MICROPHONE_REQUEST_TIMEOUT, engine::setMicrophone);
        // Nothing to turn off before the camera was ever published.
        this.camera = new RelayLocalSource("camera", video, false, scheduler, RelayLocalSource.CAMERA_RETRY_DELAYS,
                RelayLocalSource.CAMERA_REQUEST_TIMEOUT, engine::setCamera);
        this.state = mode == Mode.INCOMING ? State.RINGING : State.CONNECTING;
        this.microphone.describe = this::logName;
        this.camera.describe = this::logName;
        this.camera.onRetriesExhausted = () -> {
            if (state == State.ENDED) {
                return;
            }
            camera.setWanted(false);
            notifyObservers();
        };
        engine.setListener(new RelayMedia.Listener() {
            @Override
            public void onChange(RelayMedia.Snapshot snapshot) {
                mediaChanged(snapshot);
            }

            @Override
            public void onDisconnect(RelayMedia.DisconnectReason reason, String message) {
                mediaDisconnected(reason, message);
            }
        });
    }

    /** Joins the channel's call ([callId] from the join panel) or starts a channel call. */
    public static RelayGroupCall join(RelayCallsApi api, RelayMedia.Engine engine, RelayCallScheduler scheduler, String ownUserId, String deviceId,
                                      String channelId, String callId, RelayCall.Kind kind) {
        return new RelayGroupCall(api, engine, scheduler, ownUserId, deviceId, channelId, kind == null ? RelayCall.Kind.CHANNEL : kind, Mode.JOIN, callId, null, false);
    }

    /** A group message: rings the other members; joins the running call if there is one. */
    public static RelayGroupCall ring(RelayCallsApi api, RelayMedia.Engine engine, RelayCallScheduler scheduler, String ownUserId, String deviceId,
                                      String channelId, boolean video) {
        return new RelayGroupCall(api, engine, scheduler, ownUserId, deviceId, channelId, RelayCall.Kind.GROUP, Mode.RING, null, null, video);
    }

    /** A group ring for this user. */
    public static RelayGroupCall incoming(RelayCallsApi api, RelayMedia.Engine engine, RelayCallScheduler scheduler, String ownUserId, String deviceId, RelayCall call) {
        return new RelayGroupCall(api, engine, scheduler, ownUserId, deviceId, call.channelId, call.kind, Mode.INCOMING, null, call, false);
    }

    public State state() {
        return state;
    }

    public String callId() {
        return callId;
    }

    public RelayCall call() {
        return call;
    }

    public List<Participant> participants() {
        return participants;
    }

    public Snapshot snapshot() {
        return new Snapshot(this);
    }

    public void observe(Observer observer) {
        observers.add(observer);
    }

    public void removeObserver(Observer observer) {
        observers.remove(observer);
    }

    // MARK: Actions

    public void start() {
        if (started || state == State.ENDED) {
            return;
        }
        started = true;
        switch (mode) {
            case JOIN:
                run(() -> api.join(channelId, joinCallId, deviceId));
                break;
            case RING:
                run(() -> {
                    try {
                        return api.ring(channelId, ringVideo, deviceId);
                    } catch (RelayCallsException e) {
                        if (e.isCallInProgress()) {
                            // A call is already running in the group: join it.
                            return api.join(channelId, e.call.id, deviceId);
                        }
                        throw e;
                    }
                });
                break;
            case INCOMING:
                scheduleRingExpiry();
                reportReceived();
                break;
        }
        notifyObservers();
    }

    public void accept() {
        if (mode != Mode.INCOMING || state != State.RINGING || callId == null) {
            return;
        }
        answered = true;
        cancelRingTimer();
        state = State.CONNECTING;
        notifyObservers();
        final String id = callId;
        run(() -> {
            try {
                return api.accept(id, deviceId);
            } catch (RelayCallsException e) {
                if (e.status == 409) {
                    // No longer ringing for this user; the call may still be running: join it.
                    return api.join(channelId, id, deviceId);
                }
                throw e;
            }
        });
    }

    public void decline(String reason) {
        if (mode != Mode.INCOMING || state != State.RINGING) {
            return;
        }
        end(EndReason.DECLINED, null);
        if (callId != null) {
            final String id = callId;
            final String why = reason == null ? "declined" : reason;
            scheduler.io(() -> {
                try {
                    api.decline(id, deviceId, why);
                } catch (RelayCallsException e) {
                    RelayCallsLog.log(logName() + ": decline failed: " + e.getMessage());
                }
            });
        }
    }

    public void leave() {
        if (state == State.RINGING) {
            decline("declined");
            return;
        }
        if (state == State.ENDED) {
            return;
        }
        end(EndReason.LEFT, null);
        if (callId != null) {
            sendHangup(callId);
        }
    }

    /** Ends the call for everyone ({@link Snapshot#canEnd}); [done] gets null or the failure. */
    public void endForEveryone(RelayMedia.Result done) {
        if (callId == null) {
            done.done(null);
            return;
        }
        final String id = callId;
        RelayCalls.request(scheduler, () -> {
            api.end(channelId, id);
            return Boolean.TRUE;
        }, ok -> {
            end(EndReason.ENDED, null);
            done.done(null);
        }, done::done);
    }

    public void setMuted(boolean muted) {
        microphone.setWanted(!muted);
        notifyObservers();
    }

    public void audioBecameAvailable() {
        if (state != State.ENDED) {
            microphone.retry("audio became available");
        }
    }

    public void setCameraEnabled(boolean enabled) {
        camera.setWanted(enabled);
        notifyObservers();
    }

    public void setScreenShare(boolean enabled) {
        if (!isMediaUp()) {
            return;
        }
        isScreenSharing = enabled;
        notifyObservers();
        engine.setScreenShare(enabled, error -> scheduler.post(() -> {
            if (error != null) {
                RelayCallsLog.log(logName() + ": screen share " + (enabled ? "on" : "off") + " failed: " + error);
                isScreenSharing = false;
                notifyObservers();
            }
        }));
    }

    // MARK: Events

    public void handle(RelayCallsEvent event) {
        if (state == State.ENDED) {
            return;
        }
        switch (event.type) {
            case INCOMING:
            case CALL_UPDATED:
                apply(event.call);
                break;
            case CALL_ENDED:
                if (event.call.id.equals(callId)) {
                    call = event.call;
                    end(EndReason.ENDED, null);
                }
                break;
            case CALL_STATE:
                if (!event.call.id.equals(callId)) {
                    return;
                }
                apply(event.call);
                if (state == State.RINGING && ownUserId.equals(event.userId) && !deviceId.equals(event.deviceId)) {
                    switch (event.action) {
                        case ACCEPTED:
                        case JOINED:
                            end(EndReason.ANSWERED_ELSEWHERE, null);
                            break;
                        case DECLINED:
                        case BUSY:
                            end(EndReason.DECLINED, null);
                            break;
                        default:
                            break;
                    }
                }
                break;
            case ACTIVE_CALLS:
                if (callId == null) {
                    return;
                }
                RelayCall found = null;
                for (RelayCall c : event.calls) {
                    if (c.id.equals(callId)) {
                        found = c;
                    }
                }
                if (found != null) {
                    apply(found);
                } else {
                    refreshFromServer();
                }
                break;
        }
    }

    // MARK: Flow

    private void run(RelayDirectCall.Call<RelayCallJoin> request) {
        RelayCalls.request(scheduler, request, join -> {
            callId = join.call.id;
            if (state == State.ENDED) {
                sendHangup(join.call.id);
                return;
            }
            apply(join.call);
            if (state == State.ENDED) {
                return;
            }
            mediaRequested = true;
            engine.connect(join.url, join.token, error -> scheduler.post(() -> {
                if (error != null) {
                    if (state != State.ENDED) {
                        RelayCallsLog.log(logName() + ": media connect failed: " + error);
                        end(EndReason.FAILED, String.valueOf(error.getMessage()));
                        sendHangup(join.call.id);
                    }
                    return;
                }
                if (state == State.ENDED) {
                    engine.disconnect();
                    return;
                }
                RelayCallsLog.log(logName() + ": media connected (microphone " + (microphone.wanted() ? "on" : "off") + ", camera " + (camera.wanted() ? "on" : "off") + ")");
                mediaConnected = true;
                mediaChanged(engine.snapshot());
                updatePublishing();
                evaluate();
                notifyObservers();
            }));
        }, error -> {
            if (state != State.ENDED) {
                RelayCallsLog.log(logName() + ": join failed: " + error.getMessage());
                end(EndReason.FAILED, error.getMessage());
            }
        });
    }

    private void updatePublishing() {
        boolean ready = mediaConnected && state != State.ENDED && isMediaUp();
        microphone.setReady(ready);
        camera.setReady(ready);
    }

    private void sendHangup(String id) {
        scheduler.io(() -> {
            try {
                api.hangup(id, deviceId);
            } catch (RelayCallsException e) {
                RelayCallsLog.log(logName() + ": hangup failed: " + e.getMessage());
            }
        });
    }

    private String logName() {
        return (kind == RelayCall.Kind.CHANNEL ? "channel" : "group") + " call " + (callId != null ? callId : "(channel " + channelId + ")");
    }

    private void reportReceived() {
        if (callId == null) {
            return;
        }
        final String id = callId;
        scheduler.io(() -> {
            try {
                api.received(id, deviceId);
            } catch (RelayCallsException e) {
                RelayCallsLog.log(logName() + ": received failed: " + e.getMessage());
            }
        });
    }

    private void refreshFromServer() {
        if (callId == null || state == State.ENDED) {
            return;
        }
        final String id = callId;
        RelayCalls.request(scheduler, () -> api.call(id), current -> {
            if (current != null) {
                apply(current);
            } else if (media.connectionState != RelayMedia.ConnectionState.CONNECTED) {
                end(EndReason.ENDED, null);
            }
        }, e -> {
            if (media.connectionState != RelayMedia.ConnectionState.CONNECTED) {
                end(EndReason.ENDED, null);
            }
        });
    }

    // MARK: State

    private boolean isMediaUp() {
        return media.connectionState == RelayMedia.ConnectionState.CONNECTED || media.connectionState == RelayMedia.ConnectionState.RECONNECTING;
    }

    private void apply(RelayCall c) {
        if (c == null || !c.id.equals(callId)) {
            return;
        }
        call = c;
        kind = c.kind;
        if (c.state == RelayCall.State.ENDED) {
            end(EndReason.ENDED, null);
            return;
        }
        if (state == State.RINGING && !answered) {
            RelayCall.Invitee mine = c.invitee(ownUserId);
            if (mine == RelayCall.Invitee.ACCEPTED) {
                end(EndReason.ANSWERED_ELSEWHERE, null);
                return;
            }
            if (mine == RelayCall.Invitee.DECLINED || mine == RelayCall.Invitee.BUSY) {
                end(EndReason.DECLINED, null);
                return;
            }
            if (mine == RelayCall.Invitee.MISSED) {
                end(EndReason.MISSED, null);
                return;
            }
        }
        notifyObservers();
    }

    private void evaluate() {
        if (state == State.ENDED || state == State.RINGING) {
            return;
        }
        State previous = state;
        switch (media.connectionState) {
            case CONNECTED:
                state = State.CONNECTED;
                break;
            case RECONNECTING:
                state = State.RECONNECTING;
                break;
            default:
                state = State.CONNECTING;
                break;
        }
        if (previous != state) {
            notifyObservers();
        }
    }

    private void mediaChanged(RelayMedia.Snapshot snapshot) {
        RelayMedia.Snapshot previous = media;
        media = snapshot;
        if (state == State.ENDED) {
            return;
        }
        updatePublishing();
        if (previous.connectionState == RelayMedia.ConnectionState.RECONNECTING && snapshot.connectionState == RelayMedia.ConnectionState.CONNECTED) {
            microphone.retry("reconnected");
            camera.retry("reconnected");
        }
        participants = mergeParticipants(snapshot.participants, ownUserId);
        RelayMedia.Participant local = snapshot.local();
        if (local != null) {
            isScreenSharing = local.isScreenSharing();
        }
        State before = state;
        evaluate();
        if (before == state) {
            notifyObservers();
        }
    }

    private void mediaDisconnected(RelayMedia.DisconnectReason reason, String message) {
        if (state == State.ENDED) {
            return;
        }
        final String id = callId;
        Runnable decide = () -> {
            if (state == State.ENDED) {
                return;
            }
            switch (reason) {
                case REMOVED:
                case DUPLICATE_IDENTITY:
                    end(EndReason.REMOVED, null);
                    break;
                default:
                    RelayCallsLog.log(logName() + ": media disconnected: " + message);
                    end(EndReason.FAILED, message);
                    if (callId != null) {
                        sendHangup(callId);
                    }
                    break;
            }
        };
        if (id == null) {
            decide.run();
            return;
        }
        RelayCalls.request(scheduler, () -> api.call(id), current -> {
            if (current != null && current.state == RelayCall.State.ENDED) {
                call = current;
                end(EndReason.ENDED, null);
                return;
            }
            decide.run();
        }, e -> decide.run());
    }

    private void scheduleRingExpiry() {
        cancelRingTimer();
        long expiry = call != null && call.ringExpiresAt > 0 ? call.ringExpiresAt : scheduler.now() + RelayDirectCall.DEFAULT_RING_TIMEOUT_MS;
        ringTimer = scheduler.schedule(Math.max(0, expiry - scheduler.now()), () -> {
            if (state == State.RINGING) {
                end(EndReason.MISSED, null);
            }
        });
    }

    private void cancelRingTimer() {
        if (ringTimer != null) {
            ringTimer.cancel();
            ringTimer = null;
        }
    }

    private void end(EndReason reason, String failureMessage) {
        if (state == State.ENDED) {
            return;
        }
        cancelRingTimer();
        state = State.ENDED;
        endReason = reason;
        failure = failureMessage;
        microphone.stop();
        camera.stop();
        if (mediaRequested) {
            engine.disconnect();
        }
        RelayCallsLog.log(logName() + ": ended " + reason + (failureMessage != null ? " (" + failureMessage + ")" : ""));
        notifyObservers();
    }

    private void notifyObservers() {
        Snapshot snapshot = snapshot();
        for (Observer o : new ArrayList<>(observers)) {
            o.onChange(snapshot);
        }
    }

    // MARK: Merging

    /** One participant per Mattermost user: the own user first, then by join time. */
    public static List<Participant> mergeParticipants(List<RelayMedia.Participant> devices, String ownUserId) {
        List<String> order = new ArrayList<>();
        Map<String, List<RelayMedia.Participant>> byUser = new LinkedHashMap<>();
        for (RelayMedia.Participant device : devices) {
            // A device without a user part (not issued by the plugin) stands for itself.
            String userId = device.userId() != null ? device.userId() : device.identity;
            List<RelayMedia.Participant> list = byUser.get(userId);
            if (list == null) {
                list = new ArrayList<>();
                byUser.put(userId, list);
                order.add(userId);
            }
            list.add(device);
        }
        List<Participant> result = new ArrayList<>();
        for (String userId : order) {
            List<RelayMedia.Participant> userDevices = new ArrayList<>(byUser.get(userId));
            Collections.sort(userDevices, (a, b) -> {
                if (a.isLocal != b.isLocal) {
                    return a.isLocal ? -1 : 1;
                }
                return Long.compare(a.joinedAt == 0 ? Long.MAX_VALUE : a.joinedAt, b.joinedAt == 0 ? Long.MAX_VALUE : b.joinedAt);
            });
            boolean isSelf = userId.equals(ownUserId);
            boolean speaking = false;
            boolean mic = false;
            float level = 0;
            int quality = 0;
            long joined = 0;
            String name = null;
            for (RelayMedia.Participant d : userDevices) {
                isSelf |= d.isLocal;
                speaking |= d.isSpeaking;
                mic |= d.isMicrophoneEnabled();
                level = Math.max(level, d.audioLevel);
                quality = Math.max(quality, d.connectionQuality);
                if (d.joinedAt != 0 && (joined == 0 || d.joinedAt < joined)) {
                    joined = d.joinedAt;
                }
                if (name == null && d.name != null && !d.name.isEmpty()) {
                    name = d.name;
                }
            }
            result.add(new Participant(userId, name, isSelf, userDevices, speaking, level, mic, video(userDevices, false), video(userDevices, true), quality, joined));
        }
        List<Participant> sorted = new ArrayList<>(result);
        Collections.sort(sorted, (a, b) -> {
            if (a.isSelf != b.isSelf) {
                return a.isSelf ? -1 : 1;
            }
            long left = a.joinedAt == 0 ? Long.MAX_VALUE : a.joinedAt;
            long right = b.joinedAt == 0 ? Long.MAX_VALUE : b.joinedAt;
            if (left != right) {
                return Long.compare(left, right);
            }
            return Integer.compare(result.indexOf(a), result.indexOf(b));
        });
        return Collections.unmodifiableList(sorted);
    }

    /** The live track of the first device that has one (this device first), else a muted one. */
    private static Video video(List<RelayMedia.Participant> devices, boolean screen) {
        RelayMedia.Participant chosen = null;
        for (RelayMedia.Participant d : devices) {
            RelayMedia.Track t = screen ? d.screenShare : d.camera;
            if (t != null && t.isLive()) {
                chosen = d;
                break;
            }
        }
        if (chosen == null) {
            for (RelayMedia.Participant d : devices) {
                if ((screen ? d.screenShare : d.camera) != null) {
                    chosen = d;
                    break;
                }
            }
        }
        if (chosen == null) {
            return null;
        }
        return new Video(chosen.identity, screen ? chosen.screenShare : chosen.camera, chosen.isLocal);
    }
}
