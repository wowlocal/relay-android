package org.telegram.relay.calls;

import android.util.Log;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.voip.Instance;
import org.telegram.messenger.voip.NativeInstance;
import org.telegram.messenger.voip.RelayVoIPAccess;
import org.telegram.messenger.voip.VideoCapturerDevice;
import org.telegram.relay.livekit.RelayRoom;
import org.telegram.relay.mattermost.calls.RelayDirectCall;
import org.telegram.relay.mattermost.calls.RelayMedia;
import org.webrtc.VideoSink;

/**
 * Telegram's 1:1 "tgcalls instance" over a {@link RelayDirectCall}: connection state (Connecting → established,
 * reconnecting, failed), signal bars from LiveKit's connection quality, the remote microphone/camera state, the
 * remote video (camera or screen, whichever is live) into Telegram's renderer, audio levels, and mute / camera /
 * screen share / camera switch back to the controller. Main thread.
 */
final class RelayPrivateInstance extends RelayInstanceBase {
    private static final String TAG = RelayCallManager.TAG;

    private final RelayCallManager.DirectSession session;
    private final RelayFrames remoteFrames;
    private final NativeInstance.AudioLevelsCallback audioLevels;
    private final Runnable engineObserver = this::refresh;
    private final Runnable levelsTick = new Runnable() {
        @Override
        public void run() {
            if (stopped) {
                return;
            }
            sendLevels();
            AndroidUtilities.runOnUIThread(this, 150);
        }
    };

    private long capturerId;
    /** 0/1 camera, 2 screen; -1 none. */
    private int outgoingType = -1;
    private int lastState = -1;
    private int lastBars = -1;
    private int lastAudio = -1;
    private int lastVideo = -1;
    private String attachedTrack;

    RelayPrivateInstance(RelayCallManager.DirectSession session, VideoSink remoteSink, NativeInstance.AudioLevelsCallback audioLevels) {
        this.session = session;
        this.remoteFrames = new RelayFrames(remoteSink);
        this.audioLevels = audioLevels;
        if (session != null) {
            session.engine.addSnapshotObserver(engineObserver);
            AndroidUtilities.runOnUIThread(this::refresh);
            AndroidUtilities.runOnUIThread(levelsTick, 150);
        } else {
            AndroidUtilities.runOnUIThread(() -> report(Instance.STATE_FAILED));
        }
    }

    boolean usesCapturer(long id) {
        return capturerId == id && id != 0;
    }

    void onCallChanged(RelayDirectCall.Snapshot s) {
        refresh();
    }

    private void refresh() {
        if (stopped || session == null) {
            return;
        }
        RelayDirectCall.Snapshot s = session.call.snapshot();
        switch (s.state) {
            case ACTIVE:
                report(Instance.STATE_ESTABLISHED);
                break;
            case RECONNECTING:
                report(Instance.STATE_RECONNECTING);
                break;
            case TERMINATED:
                if (s.endReason == RelayDirectCall.EndReason.FAILED) {
                    report(Instance.STATE_FAILED);
                }
                break;
            default:
                report(Instance.STATE_WAIT_INIT_ACK);
                break;
        }
        RelayMedia.Participant remote = s.remote;
        int bars = remote == null ? 4 : quality(remote.connectionQuality);
        if (bars != lastBars && signalBarsListener != null) {
            lastBars = bars;
            signalBarsListener.onSignalBarsUpdated(bars);
        }
        int audio = remote == null || remote.isMicrophoneEnabled() ? Instance.AUDIO_STATE_ACTIVE : Instance.AUDIO_STATE_MUTED;
        int video = remote != null && (remote.isCameraEnabled() || remote.isScreenSharing()) ? Instance.VIDEO_STATE_ACTIVE : Instance.VIDEO_STATE_INACTIVE;
        if ((audio != lastAudio || video != lastVideo) && remoteMediaListener != null) {
            lastAudio = audio;
            lastVideo = video;
            remoteMediaListener.onMediaStateUpdated(audio, video);
        }
        attachRemoteVideo(remote);
    }

    private static int quality(int q) {
        switch (q) {
            case 1: return 1;
            case 2: return 2;
            case 3: return 3;
            default: return 4;
        }
    }

    private void report(int state) {
        if (state == lastState) {
            return;
        }
        lastState = state;
        Log.i(TAG, "instance state " + state);
        if (stateListener != null) {
            stateListener.onStateUpdated(state, false);
        }
    }

    private void attachRemoteVideo(RelayMedia.Participant remote) {
        RelayRoom room = session.engine.room();
        if (remote == null) {
            if (attachedTrack != null) {
                room.detachRemoteSink(remoteFrames);
                attachedTrack = null;
            }
            return;
        }
        int source;
        RelayMedia.Track track;
        if (remote.isScreenSharing()) {
            source = RelayRoom.SOURCE_SCREEN;
            track = remote.screenShare;
        } else if (remote.camera != null) {
            source = RelayRoom.SOURCE_CAMERA;
            track = remote.camera;
        } else {
            if (attachedTrack != null) {
                room.detachRemoteSink(remoteFrames);
                attachedTrack = null;
            }
            return;
        }
        if (track.sid.equals(attachedTrack)) {
            return;
        }
        if (room.attachRemoteSink(remote.identity, source, remoteFrames)) {
            attachedTrack = track.sid;
            Log.i(TAG, "remote video " + track.sid + " attached");
        }
    }

    private void sendLevels() {
        if (audioLevels == null || session == null) {
            return;
        }
        RelayDirectCall.Snapshot s = session.call.snapshot();
        float mic = s.local != null && !s.isMuted ? s.local.audioLevel : 0;
        float speaker = s.remote != null ? s.remote.audioLevel : 0;
        audioLevels.run(new int[]{0, 1}, new float[]{mic, speaker}, new boolean[]{mic > 0.05f, speaker > 0.05f});
    }

    // MARK: - VoIPService → call

    @Override
    public void setMuteMicrophone(boolean mute) {
        if (session != null) {
            session.call.setMuted(mute);
        }
    }

    @Override
    public void setupOutgoingVideo(VideoSink localSink, int type) {
        outgoingType = type;
        capturerId = 0;
        Log.i(TAG, "outgoing video type " + type);
        if (session != null && type != 2) {
            RelayRoom room = session.engine.room();
            room.setCameraFront(type == 1);
            RelayFrames preview = new RelayFrames(localSink);
            preview.setOnFirstFrame(RelayVoIPAccess::cameraFirstFrame);
            room.setLocalPreviewSink(RelayRoom.SOURCE_CAMERA, preview);
        }
    }

    @Override
    public void setupOutgoingVideoCreated(long videoCapturer) {
        RelayVoip.Capturer c = RelayVoip.capturer(videoCapturer);
        if (c == null) {
            return;
        }
        capturerId = videoCapturer;
        outgoingType = c.type;
        capturerChanged(c, c.state == Instance.VIDEO_STATE_ACTIVE);
    }

    @Override
    public void activateVideoCapturer(long videoCapturer) {
        setupOutgoingVideoCreated(videoCapturer);
    }

    @Override
    public void clearVideoCapturer() {
        if (session != null) {
            session.call.setCameraEnabled(false);
        }
        capturerId = 0;
        outgoingType = -1;
    }

    @Override
    public boolean hasVideoCapturer() {
        return outgoingType >= 0;
    }

    @Override
    public void setVideoState(int videoState) {
        boolean on = videoState == Instance.VIDEO_STATE_ACTIVE;
        Log.i(TAG, "video state " + videoState + " (type " + outgoingType + ")");
        if (session == null) {
            return;
        }
        if (outgoingType == 2) {
            setScreen(on);
        } else {
            session.call.setCameraEnabled(on);
        }
    }

    void capturerChanged(RelayVoip.Capturer c, boolean on) {
        if (session == null) {
            return;
        }
        if (c.isScreen()) {
            setScreen(on);
        } else {
            session.call.setCameraEnabled(on);
        }
    }

    private void setScreen(boolean on) {
        if (on) {
            session.engine.setScreenCapturePermission(VideoCapturerDevice.mediaProjectionPermissionResultData);
            // Telegram's 1:1 screencast replaces the camera.
            session.call.setCameraEnabled(false);
        }
        session.call.setScreenShare(on);
    }

    @Override
    public void switchCamera(boolean front) {
        if (session != null) {
            session.engine.room().setCameraFront(front);
            RelayVoIPAccess.cameraSwitched(front);
        }
    }

    @Override
    protected void onStop() {
        AndroidUtilities.cancelRunOnUIThread(levelsTick);
        Runnable cleanup = () -> {
            if (session != null) {
                session.engine.removeSnapshotObserver(engineObserver);
                session.engine.room().detachRemoteSink(remoteFrames);
                if (session.instance == this) {
                    session.instance = null;
                }
            }
        };
        AndroidUtilities.runOnUIThread(cleanup);
    }
}
