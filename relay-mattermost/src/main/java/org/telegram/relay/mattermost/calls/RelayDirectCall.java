package org.telegram.relay.mattermost.calls;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A 1:1 call ({@code kind: direct}), port of the iOS {@code RelayDirectCall} (submodules/RelayCalls), so that
 * Android, iOS and macOS behave the same against the plugin:
 * <ul>
 *   <li>Outgoing: {@link #start()} rings ({@code POST /channels/<dm>/ring}) and connects the media right away, but
 *       publishes the microphone only once the callee answered (Telegram sends nothing before the answer); a video
 *       call's camera publishes right away (local preview).</li>
 *   <li>Incoming: {@link #start()} reports {@code received} and starts the local ring expiry; {@link #accept()},
 *       {@link #decline(String)}.</li>
 *   <li>{@link #hangup()} declines an unanswered incoming ring, cancels an unanswered outgoing one, ends an answered
 *       call for both.</li>
 *   <li>An answered call that stays {@link State#CONNECTING} for 30 s or {@link State#RECONNECTING} for 60 s ends as
 *       failed and hangs up; the remaining client hangs up when its peer's device is gone.</li>
 * </ul>
 * {@link State} mirrors Telegram's call states. Everything runs on the scheduler's thread; feed every plugin event of
 * the account to {@link #handle} (other calls' events are ignored).
 */
public final class RelayDirectCall {
    public enum State {
        /** Outgoing, before the answer ({@link Snapshot#calleeRinging}: a callee device reported {@code received}). */
        REQUESTING,
        /** Incoming ring. */
        RINGING,
        /** Answered, the other device not in the room yet. */
        CONNECTING,
        ACTIVE,
        /** Media reconnecting, or the other device dropped out after the call was up. */
        RECONNECTING,
        TERMINATED
    }

    public enum EndReason {
        /** This device hung up (an unanswered outgoing call was cancelled). */
        HANGUP,
        REMOTE_HANGUP,
        /** The callee declined. */
        DECLINED,
        DECLINED_LOCALLY,
        BUSY,
        MISSED,
        /** Incoming: the caller hung up first. */
        CANCELLED,
        ANSWERED_ELSEWHERE,
        DECLINED_ELSEWHERE,
        ENDED,
        FAILED
    }

    public static final class Snapshot {
        public final State state;
        public final boolean calleeRinging;
        public final EndReason endReason;
        public final String failure;
        /** Seconds from the answer (the plugin's {@code duration_ms} when it sent one); -1 when unknown. */
        public final double duration;
        /** Unix ms of the answer (ACTIVE / RECONNECTING), 0 before. */
        public final long activeSince;
        public final RelayCall call;
        public final String callId;
        public final String channelId;
        public final String peerUserId;
        public final boolean isOutgoing;
        public final boolean isVideo;
        public final boolean isMuted;
        public final boolean isCameraEnabled;
        public final boolean isScreenSharing;
        public final RelayMedia.ConnectionState mediaState;
        public final RelayMedia.Participant local;
        public final RelayMedia.Participant remote;

        Snapshot(RelayDirectCall c) {
            state = c.state;
            calleeRinging = c.calleeReceived;
            endReason = c.endReason;
            failure = c.failure;
            duration = c.duration;
            activeSince = c.activeSince;
            call = c.call;
            callId = c.callId;
            channelId = c.channelId;
            peerUserId = c.peerUserId;
            isOutgoing = c.isOutgoing;
            isVideo = c.isVideo;
            isMuted = !c.microphone.wanted();
            isCameraEnabled = c.camera.wanted();
            isScreenSharing = c.isScreenSharing;
            mediaState = c.media.connectionState;
            local = c.media.local();
            remote = c.remoteParticipant();
        }

        @Override
        public String toString() {
            return state + (state == State.REQUESTING && calleeRinging ? " (ringing)" : "") + (endReason != null ? " " + endReason : "")
                    + " call " + callId + " media " + mediaState + (isMuted ? " muted" : "") + (isCameraEnabled ? " camera" : "");
        }
    }

    public interface Observer {
        void onChange(Snapshot snapshot);
    }

    public static final long RING_EXPIRY_GRACE_MS = 3000;
    static final long DEFAULT_RING_TIMEOUT_MS = 45000;
    public static final long CONNECT_TIMEOUT_MS = 30000;
    public static final long RECONNECT_TIMEOUT_MS = 60000;

    public final String ownUserId;
    public final String deviceId;
    public final String channelId;
    public final String peerUserId;
    public final boolean isOutgoing;
    public final boolean isVideo;

    private final RelayCallsApi api;
    private final RelayMedia.Engine engine;
    private final RelayCallScheduler scheduler;
    private final List<Observer> observers = new CopyOnWriteArrayList<>();

    private State state;
    private EndReason endReason;
    private String failure;
    private double duration = -1;
    private RelayCall call;
    private String callId;
    private boolean started;
    private boolean answered;
    private boolean acceptedLocally;
    private boolean calleeReceived;
    private boolean notifiedRinging;
    private long activeSince;
    private boolean mediaRequested;
    private boolean mediaConnected;
    private RelayMedia.Snapshot media = RelayMedia.Snapshot.EMPTY;
    private final RelayLocalSource microphone;
    private final RelayLocalSource camera;
    private boolean isScreenSharing;
    private RelayCallScheduler.Cancellable ringTimer;
    private RelayCallScheduler.Cancellable mediaTimer;
    /** What {@link #mediaTimer} waits for: CONNECTING or RECONNECTING. */
    private State mediaWait;

    private RelayDirectCall(RelayCallsApi api, RelayMedia.Engine engine, RelayCallScheduler scheduler, String ownUserId, String deviceId,
                            String channelId, String peerUserId, boolean isOutgoing, boolean isVideo, RelayCall call) {
        this.api = api;
        this.engine = engine;
        this.scheduler = scheduler;
        this.ownUserId = ownUserId;
        this.deviceId = deviceId;
        this.channelId = channelId;
        this.peerUserId = peerUserId;
        this.isOutgoing = isOutgoing;
        this.isVideo = isVideo;
        this.call = call;
        this.callId = call != null ? call.id : null;
        this.microphone = new RelayLocalSource("microphone", true, null, scheduler, RelayLocalSource.MICROPHONE_RETRY_DELAYS,
                RelayLocalSource.MICROPHONE_REQUEST_TIMEOUT, engine::setMicrophone);
        // Nothing to turn off before the camera was ever published.
        this.camera = new RelayLocalSource("camera", isVideo && isOutgoing, false, scheduler, RelayLocalSource.CAMERA_RETRY_DELAYS,
                RelayLocalSource.CAMERA_REQUEST_TIMEOUT, engine::setCamera);
        this.state = isOutgoing ? State.REQUESTING : State.RINGING;
        this.microphone.describe = this::logName;
        this.camera.describe = this::logName;
        this.camera.onRetriesExhausted = () -> {
            // A camera that cannot publish shows as off (the user can turn it on again).
            if (state == State.TERMINATED) {
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

    public static RelayDirectCall outgoing(RelayCallsApi api, RelayMedia.Engine engine, RelayCallScheduler scheduler, String ownUserId, String deviceId,
                                           String channelId, String peerUserId, boolean video) {
        return new RelayDirectCall(api, engine, scheduler, ownUserId, deviceId, channelId, peerUserId, true, video, null);
    }

    /** From the {@code incoming} event or an own ring in the active calls. */
    public static RelayDirectCall incoming(RelayCallsApi api, RelayMedia.Engine engine, RelayCallScheduler scheduler, String ownUserId, String deviceId, RelayCall call) {
        return new RelayDirectCall(api, engine, scheduler, ownUserId, deviceId, call.channelId, call.startedBy, false, call.video, call);
    }

    /** Declines a ring this device cannot take, without a controller. */
    public static void declineBusy(RelayCallsApi api, RelayCallScheduler scheduler, String callId, String deviceId) {
        scheduler.io(() -> {
            try {
                api.decline(callId, deviceId, "busy");
            } catch (RelayCallsException e) {
                RelayCallsLog.log("call " + callId + ": busy decline failed: " + e.getMessage());
            }
        });
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
        if (started || state == State.TERMINATED) {
            return;
        }
        started = true;
        if (isOutgoing) {
            ring();
        } else {
            scheduleRingExpiry();
            reportReceived();
        }
        notifyObservers();
    }

    public void accept() {
        if (isOutgoing || state != State.RINGING || callId == null) {
            return;
        }
        acceptedLocally = true;
        answered = true;
        cancelRingTimer();
        state = State.CONNECTING;
        updateMediaTimer();
        notifyObservers();
        final String id = callId;
        request(() -> api.accept(id, deviceId), join -> {
            if (state == State.TERMINATED) {
                sendHangup(id);
                return;
            }
            apply(join.call);
            connectMedia(join);
        }, this::acceptFailed);
    }

    /** [reason]: {@code declined} or {@code busy}. */
    public void decline(String reason) {
        if (isOutgoing || state != State.RINGING) {
            return;
        }
        terminate("busy".equals(reason) ? EndReason.BUSY : EndReason.DECLINED_LOCALLY, null, -1);
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

    public void hangup() {
        if (!isOutgoing && state == State.RINGING) {
            decline("declined");
            return;
        }
        if (state == State.TERMINATED) {
            return;
        }
        terminate(EndReason.HANGUP, null, -1);
        if (callId != null) {
            sendHangup(callId);
        }
    }

    public void setMuted(boolean muted) {
        microphone.setWanted(!muted);
        notifyObservers();
    }

    public void audioBecameAvailable() {
        if (state == State.TERMINATED) {
            return;
        }
        microphone.retry("audio became available");
    }

    public void setCameraEnabled(boolean enabled) {
        camera.setWanted(enabled);
        notifyObservers();
    }

    public void setScreenShare(boolean enabled) {
        if (media.connectionState != RelayMedia.ConnectionState.CONNECTED) {
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
        if (state == State.TERMINATED) {
            return;
        }
        switch (event.type) {
            case INCOMING:
            case CALL_UPDATED:
                apply(event.call);
                break;
            case CALL_ENDED:
                if (event.call.id.equals(callId)) {
                    ended(event.call);
                }
                break;
            case CALL_STATE:
                if (!event.call.id.equals(callId)) {
                    return;
                }
                apply(event.call);
                if (state != State.TERMINATED) {
                    stateAction(event.userId, event.deviceId, event.action);
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
                    // Missing from the active calls: it ended while the socket was away.
                    refreshFromServer();
                }
                break;
        }
    }

    private void stateAction(String userId, String device, RelayCallsEvent.Action action) {
        if (ownUserId.equals(userId)) {
            if (deviceId.equals(device) || isOutgoing || acceptedLocally) {
                return;
            }
            switch (action) {
                case ACCEPTED:
                case JOINED:
                    terminate(EndReason.ANSWERED_ELSEWHERE, null, -1);
                    break;
                case DECLINED:
                case BUSY:
                    terminate(EndReason.DECLINED_ELSEWHERE, null, -1);
                    break;
                default:
                    break;
            }
            return;
        }
        if (!peerUserId.equals(userId)) {
            return;
        }
        switch (action) {
            case RECEIVED:
                if (isOutgoing && !calleeReceived) {
                    calleeReceived = true;
                    evaluate();
                }
                break;
            case ACCEPTED:
            case JOINED:
                // Only the callee answers. The caller's device joins the room while it still rings: on an incoming
                // call that is not an answer.
                if (isOutgoing && !answered) {
                    answered = true;
                    cancelRingTimer();
                    evaluate();
                }
                break;
            case DECLINED:
                terminate(isOutgoing ? EndReason.DECLINED : EndReason.CANCELLED, null, -1);
                break;
            case BUSY:
                terminate(isOutgoing ? EndReason.BUSY : EndReason.CANCELLED, null, -1);
                break;
            case MISSED:
                terminate(EndReason.MISSED, null, -1);
                break;
            case CANCELLED:
                terminate(answered ? EndReason.REMOTE_HANGUP : EndReason.CANCELLED, null, -1);
                break;
            case HANGUP:
            case LEFT:
                if (answered) {
                    terminate(EndReason.REMOTE_HANGUP, null, -1);
                } else if (isOutgoing) {
                    terminate(EndReason.DECLINED, null, -1);
                } else if (action == RelayCallsEvent.Action.HANGUP) {
                    terminate(EndReason.CANCELLED, null, -1);
                }
                // A ringing caller's device leaving the room (a media reconnect) does not end the ring.
                break;
            default:
                break;
        }
    }

    // MARK: Flow

    private void ring() {
        request(() -> api.ring(channelId, isVideo, deviceId), this::rang, error -> {
            if (error.isCallInProgress()) {
                joinExisting(error.call);
            } else {
                terminate(EndReason.FAILED, error.getMessage(), -1);
            }
        });
    }

    private void rang(RelayCallJoin join) {
        callId = join.call.id;
        if (state == State.TERMINATED) {
            // Hung up while the ring request was in flight.
            sendHangup(join.call.id);
            return;
        }
        apply(join.call);
        if (state == State.TERMINATED) {
            // The ring answered with a call that already ended (the callee is busy): nothing to connect.
            return;
        }
        scheduleRingExpiry();
        connectMedia(join);
    }

    private void joinExisting(RelayCall existing) {
        if (existing.isRinging(ownUserId)) {
            answered = true;
            acceptedLocally = true;
            callId = existing.id;
            request(() -> api.accept(existing.id, deviceId), this::rang, e -> terminate(EndReason.FAILED, e.getMessage(), -1));
            return;
        }
        if (existing.isLive()) {
            answered = existing.state == RelayCall.State.ACTIVE;
            callId = existing.id;
            request(() -> api.join(channelId, existing.id, deviceId), this::rang, e -> terminate(EndReason.FAILED, e.getMessage(), -1));
            return;
        }
        terminate(EndReason.FAILED, "A call is already in progress in this conversation.", -1);
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

    private void acceptFailed(RelayCallsException error) {
        if (state == State.TERMINATED) {
            return;
        }
        if (error.status == 409 && callId != null) {
            // No longer ringing for this user: answered elsewhere, cancelled, or expired.
            final String id = callId;
            request(() -> api.call(id), current -> {
                if (state == State.TERMINATED) {
                    return;
                }
                if (current != null) {
                    call = current;
                    terminate(reasonForLostRing(current), null, -1);
                } else {
                    terminate(EndReason.ENDED, null, -1);
                }
            }, e -> {
                if (state != State.TERMINATED) {
                    terminate(EndReason.ENDED, null, -1);
                }
            });
            return;
        }
        terminate(EndReason.FAILED, error.getMessage(), -1);
    }

    private EndReason reasonForLostRing(RelayCall c) {
        if (c.state == RelayCall.State.ENDED) {
            return endReasonFor(c);
        }
        RelayCall.Invitee mine = c.invitee(ownUserId);
        if (mine == RelayCall.Invitee.ACCEPTED) {
            return EndReason.ANSWERED_ELSEWHERE;
        }
        if (mine == RelayCall.Invitee.DECLINED || mine == RelayCall.Invitee.BUSY) {
            return EndReason.DECLINED_ELSEWHERE;
        }
        if (mine == RelayCall.Invitee.MISSED) {
            return EndReason.MISSED;
        }
        return c.state == RelayCall.State.ACTIVE ? EndReason.ANSWERED_ELSEWHERE : EndReason.ENDED;
    }

    private void connectMedia(RelayCallJoin join) {
        if (mediaRequested) {
            return;
        }
        mediaRequested = true;
        engine.connect(join.url, join.token, error -> scheduler.post(() -> {
            if (error != null) {
                if (state == State.TERMINATED) {
                    return;
                }
                RelayCallsLog.log(logName() + ": media connect failed: " + error);
                terminate(EndReason.FAILED, String.valueOf(error.getMessage()), -1);
                if (callId != null) {
                    sendHangup(callId);
                }
                return;
            }
            if (state == State.TERMINATED) {
                engine.disconnect();
                return;
            }
            RelayCallsLog.log(logName() + ": media connected (microphone " + (microphone.wanted() ? "on" : "off") + ", camera " + (camera.wanted() ? "on" : "off") + ")");
            mediaConnected = true;
            // Publishes the camera now and the microphone once answered, retrying failures.
            media = engine.snapshot();
            updatePublishing();
            evaluate();
        }));
    }

    private void updatePublishing() {
        boolean isUp = media.connectionState == RelayMedia.ConnectionState.CONNECTED || media.connectionState == RelayMedia.ConnectionState.RECONNECTING;
        boolean ready = mediaConnected && state != State.TERMINATED && isUp;
        microphone.setReady(ready && (answered || !isOutgoing));
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
        return "direct call " + (callId != null ? callId : "(ringing " + peerUserId + ")");
    }

    private void refreshFromServer() {
        if (callId == null || state == State.TERMINATED) {
            return;
        }
        final String id = callId;
        request(() -> api.call(id), current -> {
            if (current == null) {
                // Forgotten (404): ended unless the media is still up.
                if (media.connectionState != RelayMedia.ConnectionState.CONNECTED) {
                    terminate(EndReason.ENDED, null, -1);
                }
                return;
            }
            apply(current);
        }, e -> {
            if (media.connectionState != RelayMedia.ConnectionState.CONNECTED) {
                terminate(EndReason.ENDED, null, -1);
            }
        });
    }

    // MARK: State

    private void apply(RelayCall c) {
        if (c == null || !c.id.equals(callId)) {
            return;
        }
        call = c;
        if (c.state == RelayCall.State.ENDED) {
            ended(c);
            return;
        }
        RelayCall.Invitee peer = c.invitee(peerUserId);
        if (peer == RelayCall.Invitee.RECEIVED) {
            calleeReceived = true;
        }
        if (peer == RelayCall.Invitee.ACCEPTED) {
            answered = true;
        }
        if (c.state == RelayCall.State.ACTIVE && isOutgoing) {
            answered = true;
        }
        if (!isOutgoing && !acceptedLocally && state == State.RINGING) {
            RelayCall.Invitee mine = c.invitee(ownUserId);
            if (mine == RelayCall.Invitee.ACCEPTED) {
                terminate(EndReason.ANSWERED_ELSEWHERE, null, -1);
                return;
            }
            if (mine == RelayCall.Invitee.DECLINED || mine == RelayCall.Invitee.BUSY) {
                terminate(EndReason.DECLINED_ELSEWHERE, null, -1);
                return;
            }
            if (mine == RelayCall.Invitee.MISSED) {
                terminate(EndReason.MISSED, null, -1);
                return;
            }
        }
        if (answered) {
            cancelRingTimer();
        }
        evaluate();
    }

    private void ended(RelayCall c) {
        if (state == State.TERMINATED) {
            return;
        }
        call = c;
        double seconds = c.durationMs >= 0 && (answered || acceptedLocally) ? c.durationMs / 1000.0 : -1;
        terminate(endReasonFor(c), c.endReason == RelayCall.EndReason.FAILED ? "The call failed." : null, seconds);
    }

    private EndReason endReasonFor(RelayCall c) {
        RelayCall.EndReason reason = c.endReason != null ? c.endReason : RelayCall.EndReason.ENDED;
        switch (reason) {
            case MISSED:
                return EndReason.MISSED;
            case CANCELLED:
                return isOutgoing ? EndReason.HANGUP : EndReason.CANCELLED;
            case DECLINED:
                if (isOutgoing) {
                    return EndReason.DECLINED;
                }
                return acceptedLocally ? EndReason.REMOTE_HANGUP : EndReason.DECLINED_ELSEWHERE;
            case BUSY:
                return EndReason.BUSY;
            case HANGUP:
                if (answered || acceptedLocally) {
                    return EndReason.REMOTE_HANGUP;
                }
                return isOutgoing ? EndReason.DECLINED : EndReason.CANCELLED;
            case FAILED:
                return EndReason.FAILED;
            case ENDED:
            default:
                return EndReason.ENDED;
        }
    }

    private void evaluate() {
        if (state == State.TERMINATED) {
            return;
        }
        State previous = state;
        boolean remotePresent = remoteParticipant() != null;
        if (!answered && isOutgoing && remotePresent) {
            // The callee's device joined before its `accepted` arrived.
            answered = true;
            cancelRingTimer();
        }
        if (!answered) {
            state = isOutgoing ? State.REQUESTING : State.RINGING;
        } else if (media.connectionState == RelayMedia.ConnectionState.RECONNECTING && activeSince != 0) {
            state = State.RECONNECTING;
        } else if (media.connectionState == RelayMedia.ConnectionState.CONNECTED && remotePresent) {
            if (activeSince == 0) {
                activeSince = scheduler.now();
            }
            state = State.ACTIVE;
        } else if (activeSince != 0) {
            // The other side's device left the room after the call was up.
            state = State.RECONNECTING;
        } else {
            state = State.CONNECTING;
        }
        updateMediaTimer();
        updatePublishing();
        // `requesting(ringing:)` is one state per ringing flag on iOS.
        boolean ringingChanged = state == State.REQUESTING && notifiedRinging != calleeReceived;
        notifiedRinging = calleeReceived;
        if (state != previous || ringingChanged) {
            notifyObservers();
        }
    }

    private void updateMediaTimer() {
        State wait;
        if (state == State.CONNECTING) {
            wait = State.CONNECTING;
        } else if (state == State.RECONNECTING) {
            wait = State.RECONNECTING;
        } else {
            if (mediaTimer != null) {
                mediaTimer.cancel();
            }
            mediaTimer = null;
            mediaWait = null;
            return;
        }
        if (mediaWait == wait) {
            return;
        }
        if (mediaTimer != null) {
            mediaTimer.cancel();
        }
        final State waitingFor = wait;
        mediaWait = wait;
        mediaTimer = scheduler.schedule(wait == State.CONNECTING ? CONNECT_TIMEOUT_MS : RECONNECT_TIMEOUT_MS, () -> {
            if (mediaWait != waitingFor || state == State.TERMINATED) {
                return;
            }
            mediaTimer = null;
            mediaWait = null;
            terminate(EndReason.FAILED, waitingFor == State.CONNECTING ? "The call could not connect." : "The connection was lost.", -1);
            if (callId != null) {
                sendHangup(callId);
            }
        });
    }

    private RelayMedia.Participant remoteParticipant() {
        for (RelayMedia.Participant p : media.remote()) {
            if (peerUserId.equals(p.userId())) {
                return p;
            }
        }
        return null;
    }

    private void mediaChanged(RelayMedia.Snapshot snapshot) {
        RelayMedia.Snapshot previous = media;
        media = snapshot;
        if (state == State.TERMINATED) {
            return;
        }
        RelayMedia.Participant local = snapshot.local();
        if (local != null) {
            isScreenSharing = local.isScreenSharing();
        }
        updatePublishing();
        if (previous.connectionState == RelayMedia.ConnectionState.RECONNECTING && snapshot.connectionState == RelayMedia.ConnectionState.CONNECTED) {
            microphone.retry("reconnected");
            camera.retry("reconnected");
        }
        State before = state;
        evaluate();
        if (state == before && !previous.equals(snapshot)) {
            notifyObservers();
        }
    }

    private void mediaDisconnected(RelayMedia.DisconnectReason reason, String message) {
        if (state == State.TERMINATED) {
            return;
        }
        // The room went away: usually the call ended (the `call_ended` event may be on its way).
        final String id = callId;
        Runnable decide = () -> {
            if (state == State.TERMINATED) {
                return;
            }
            switch (reason) {
                case REMOVED:
                    terminate(EndReason.ENDED, null, -1);
                    break;
                case DUPLICATE_IDENTITY:
                    terminate(EndReason.ANSWERED_ELSEWHERE, null, -1);
                    break;
                default:
                    RelayCallsLog.log(logName() + ": media disconnected: " + message);
                    terminate(EndReason.FAILED, message, -1);
                    break;
            }
            if (callId != null) {
                sendHangup(callId);
            }
        };
        if (id == null) {
            decide.run();
            return;
        }
        request(() -> api.call(id), current -> {
            if (current != null && current.state == RelayCall.State.ENDED) {
                ended(current);
                return;
            }
            decide.run();
        }, e -> decide.run());
    }

    private void scheduleRingExpiry() {
        cancelRingTimer();
        if (answered) {
            return;
        }
        // Without an expiry (an older plugin), the plugin's default ring timeout.
        long expiry = call != null && call.ringExpiresAt > 0 ? call.ringExpiresAt : scheduler.now() + DEFAULT_RING_TIMEOUT_MS;
        long grace = isOutgoing ? RING_EXPIRY_GRACE_MS : 0;
        ringTimer = scheduler.schedule(Math.max(0, expiry + grace - scheduler.now()), this::ringExpired);
    }

    private void ringExpired() {
        if (answered || state == State.TERMINATED) {
            return;
        }
        terminate(EndReason.MISSED, null, -1);
        if (isOutgoing && callId != null) {
            // The plugin's sweeper normally ended it as missed already.
            sendHangup(callId);
        }
    }

    private void cancelRingTimer() {
        if (ringTimer != null) {
            ringTimer.cancel();
            ringTimer = null;
        }
    }

    private void terminate(EndReason reason, String failureMessage, double seconds) {
        if (state == State.TERMINATED) {
            return;
        }
        cancelRingTimer();
        if (mediaTimer != null) {
            mediaTimer.cancel();
            mediaTimer = null;
        }
        mediaWait = null;
        double elapsed = activeSince != 0 ? (scheduler.now() - activeSince) / 1000.0 : -1;
        state = State.TERMINATED;
        endReason = reason;
        failure = failureMessage;
        duration = seconds >= 0 ? seconds : elapsed;
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

    // MARK: Requests

    interface Call<T> {
        T run() throws RelayCallsException;
    }

    interface Ok<T> {
        void done(T value);
    }

    interface Failed {
        void failed(RelayCallsException error);
    }

    private <T> void request(Call<T> call, Ok<T> ok, Failed failed) {
        RelayCalls.request(scheduler, call, ok, failed);
    }
}
