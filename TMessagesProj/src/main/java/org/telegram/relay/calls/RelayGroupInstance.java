package org.telegram.relay.calls;

import android.util.Log;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.voip.NativeInstance;
import org.telegram.relay.livekit.RelayRoom;
import org.telegram.relay.mattermost.calls.RelayGroupCall;
import org.telegram.relay.mattermost.calls.RelayMedia;
import org.webrtc.VideoSink;

import java.util.HashMap;
import java.util.Map;

/**
 * Telegram's group-call "tgcalls instance" over a {@link RelayGroupSession}: the join payload Telegram sends with
 * {@code phone.joinGroupCall} (only the SSRC matters), the connection state (connected = 1), audio levels per SSRC
 * (speaking indicators), incoming video outputs by endpoint ({@code relay:camera:<user>} / {@code relay:screen:<user>})
 * into Telegram's renderers, mute, per-user volume. The screencast instance ({@link #screencast}) stands for the
 * screen share's own join ({@code joinGroupCallPresentation}). Main thread.
 */
final class RelayGroupInstance extends RelayInstanceBase {
    private static final String TAG = RelayCallManager.TAG;

    final boolean screencast;
    private final NativeInstance.PayloadCallback payload;
    private final NativeInstance.AudioLevelsCallback audioLevels;
    private RelayGroupSession session;
    private int lastState = -1;
    private long nextOutput = 1;

    private static final class Output {
        final String endpoint;
        final RelayFrames frames;
        String attachedSid;

        Output(String endpoint, RelayFrames frames) {
            this.endpoint = endpoint;
            this.frames = frames;
        }
    }

    private final Map<Long, Output> outputs = new HashMap<>();
    /** Remote videos attached to Telegram's renderers right now (debug state). */
    static volatile int attachedVideos;
    private final Runnable engineObserver = this::refresh;
    private final Runnable levelsTick = new Runnable() {
        @Override
        public void run() {
            if (stopped) {
                return;
            }
            sendLevels();
            AndroidUtilities.runOnUIThread(this, 120);
        }
    };

    RelayGroupInstance(boolean screencast, NativeInstance.PayloadCallback payload, NativeInstance.AudioLevelsCallback audioLevels) {
        this.screencast = screencast;
        this.payload = payload;
        this.audioLevels = audioLevels;
    }

    void bind(RelayGroupSession s) {
        session = s;
        if (!screencast) {
            s.engine.addSnapshotObserver(engineObserver);
            AndroidUtilities.runOnUIThread(levelsTick, 120);
        }
        refresh();
    }

    @Override
    public boolean isGroup() {
        return true;
    }

    /** Telegram (re)starts the instance: emit the join payload, as tgcalls does. */
    @Override
    public void resetGroupInstance(boolean set, boolean disconnect) {
        String self = RelayCallManager.of(org.telegram.messenger.UserConfig.selectedAccount) != null
                ? RelayCallManager.of(org.telegram.messenger.UserConfig.selectedAccount).ownUserId : "self";
        RelayGroupSession s = session != null ? session : RelayGroupSession.current;
        if (s != null) {
            self = s.manager.ownUserId;
        }
        final int ssrc = RelayGroupSession.ssrc(self) + (screencast ? 2 : 0);
        final String json = "{\"ufrag\":\"relay\",\"pwd\":\"relay\",\"fingerprints\":[],\"ssrc\":" + ssrc + "}";
        AndroidUtilities.runOnUIThread(() -> {
            if (!stopped && payload != null) {
                payload.run(ssrc, json);
            }
        });
    }

    void refresh() {
        if (stopped || session == null || screencast) {
            return;
        }
        RelayGroupCall.Snapshot s = session.call.snapshot();
        int state = s.state == RelayGroupCall.State.CONNECTED ? 1 : 0;
        if (state != lastState) {
            lastState = state;
            Log.i(TAG, "group instance state " + state);
            if (stateListener != null) {
                stateListener.onStateUpdated(state, false);
            }
        }
        int attached = 0;
        for (Output o : outputs.values()) {
            attach(o, s);
            if (o.attachedSid != null) {
                attached++;
            }
        }
        attachedVideos = attached;
    }

    private void attach(Output o, RelayGroupCall.Snapshot s) {
        boolean screen = o.endpoint.startsWith("relay:screen:");
        String userId = o.endpoint.substring(screen ? "relay:screen:".length() : "relay:camera:".length());
        RelayGroupCall.Video video = null;
        for (RelayGroupCall.Participant p : s.participants) {
            if (p.userId.equals(userId)) {
                video = screen ? p.screenShare : p.camera;
            }
        }
        RelayRoom room = session.engine.room();
        if (video == null || !video.track.isLive()) {
            if (o.attachedSid != null) {
                room.detachRemoteSink(o.frames);
                o.attachedSid = null;
            }
            return;
        }
        if (video.track.sid.equals(o.attachedSid)) {
            return;
        }
        if (video.isLocal) {
            // Own video: Telegram renders it from the local sink (capturer preview).
            return;
        }
        if (room.attachRemoteSink(video.identity, screen ? RelayRoom.SOURCE_SCREEN : RelayRoom.SOURCE_CAMERA, o.frames)) {
            o.attachedSid = video.track.sid;
            Log.i(TAG, "group video " + o.endpoint + " attached (" + video.track.sid + ")");
        }
    }

    private void sendLevels() {
        if (audioLevels == null || session == null) {
            return;
        }
        RelayGroupCall.Snapshot s = session.call.snapshot();
        int n = s.participants.size();
        if (n == 0) {
            return;
        }
        int[] uids = new int[n];
        float[] levels = new float[n];
        boolean[] voice = new boolean[n];
        for (int i = 0; i < n; i++) {
            RelayGroupCall.Participant p = s.participants.get(i);
            uids[i] = p.isSelf ? 0 : RelayGroupSession.ssrc(p.userId);
            boolean muted = p.isSelf ? s.isMuted : !p.isMicrophoneEnabled;
            levels[i] = muted ? 0 : Math.min(1f, p.audioLevel);
            voice[i] = !muted && (p.isSpeaking || p.audioLevel > 0.05f);
        }
        audioLevels.run(uids, levels, voice);
    }

    // MARK: - VoIPService → call

    @Override
    public long addIncomingVideoOutput(int quality, String endpointId, SsrcGroup[] ssrcGroups, VideoSink remoteSink, long userId) {
        long handle = nextOutput++;
        Output o = new Output(endpointId, new RelayFrames(remoteSink));
        outputs.put(handle, o);
        Log.i(TAG, "group video output " + endpointId);
        if (session != null) {
            attach(o, session.call.snapshot());
        }
        return handle;
    }

    @Override
    public void removeIncomingVideoOutput(long handle) {
        Output o = outputs.remove(handle);
        if (o != null && session != null) {
            session.engine.room().detachRemoteSink(o.frames);
        }
    }

    @Override
    public void setMuteMicrophone(boolean mute) {
        if (session != null && !screencast) {
            session.call.setMuted(mute);
        }
    }

    @Override
    public void setVolume(int ssrc, double volume) {
        if (session == null) {
            return;
        }
        String userId = session.userOfSsrc(ssrc);
        if (userId != null) {
            session.engine.room().setVolume(userId, volume);
        }
    }

    @Override
    public void setVideoState(int videoState) {
        if (session != null) {
            session.setVideo(screencast, videoState == org.telegram.messenger.voip.Instance.VIDEO_STATE_ACTIVE);
        }
    }

    @Override
    public void setupOutgoingVideoCreated(long videoCapturer) {
        RelayVoip.Capturer c = RelayVoip.capturer(videoCapturer);
        if (c != null && session != null) {
            session.setVideo(c.isScreen(), c.state == org.telegram.messenger.voip.Instance.VIDEO_STATE_ACTIVE);
        }
    }

    @Override
    public void activateVideoCapturer(long videoCapturer) {
        setupOutgoingVideoCreated(videoCapturer);
    }

    @Override
    public void clearVideoCapturer() {
        if (session != null) {
            session.setVideo(screencast, false);
        }
    }

    @Override
    public boolean hasVideoCapturer() {
        return session != null && session.call.snapshot().isCameraEnabled;
    }

    @Override
    public void switchCamera(boolean front) {
        if (session != null) {
            session.engine.room().setCameraFront(front);
            org.telegram.messenger.voip.RelayVoIPAccess.cameraSwitched(front);
        }
    }

    @Override
    protected void onStop() {
        AndroidUtilities.runOnUIThread(() -> {
            AndroidUtilities.cancelRunOnUIThread(levelsTick);
            RelayGroupSession.instanceStopped(this);
            if (session != null) {
                session.engine.removeSnapshotObserver(engineObserver);
                for (Output o : outputs.values()) {
                    session.engine.room().detachRemoteSink(o.frames);
                }
                if (screencast) {
                    session.setVideo(true, false);
                    if (session.screenInstance == this) {
                        session.screenInstance = null;
                    }
                } else if (session.instance == this) {
                    session.instance = null;
                }
            }
            outputs.clear();
        });
    }
}
