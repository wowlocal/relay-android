package org.telegram.relay.calls;

import android.util.Log;

import org.telegram.messenger.voip.Instance;
import org.telegram.messenger.voip.NativeInstance;
import org.telegram.messenger.voip.RelayVoIPAccess;
import org.telegram.messenger.voip.VideoCapturerDevice;
import org.telegram.relay.livekit.RelayRoom;
import org.webrtc.VideoSink;

import java.util.HashMap;
import java.util.Map;

/**
 * Relay's replacement of tgcalls behind {@code NativeInstance} (A8): VoIPService's instances and video capturers
 * become LiveKit through the call being presented ({@link RelayCallManager}). Main thread.
 *
 * <p>Media session: one {@link RelayLiveKitEngine} (one LiveKit room) at a time, shared by the call controller
 * and the capturers (Telegram's camera preview starts before a video call has a room; the preview runs on the
 * room's camera track, which the call publishes once connected).
 *
 * <p>Capturers: Telegram's handles ({@code captureDevice[]}) are Relay ids; type 0/1 = back/front camera, 2 = the
 * screen (MediaProjection data from {@link VideoCapturerDevice#mediaProjectionPermissionResultData}).
 */
public final class RelayVoip {
    private static final String TAG = RelayCallManager.TAG;

    private RelayVoip() {
    }

    private static RelayLiveKitEngine engine;

    /** The media session of the call being set up or presented. */
    static RelayLiveKitEngine engine() {
        if (engine == null) {
            engine = new RelayLiveKitEngine(RelayCallManager.context());
        }
        return engine;
    }

    static RelayLiveKitEngine currentEngine() {
        return engine;
    }

    /** Releases the media session once no call and no capturer use it. */
    static void releaseIfIdle() {
        if (!RelayCallManager.hasActiveCall()) {
            // A screen share ends with its call; with VoIPService gone every capturer is stale (Telegram does not
            // always destroy them).
            if (org.telegram.messenger.voip.VoIPService.getSharedInstance() == null) {
                capturers.clear();
            } else {
                capturers.values().removeIf(Capturer::isScreen);
            }
        }
        if (engine == null || RelayCallManager.hasActiveCall() || !capturers.isEmpty()) {
            return;
        }
        Log.i(TAG, "media session released");
        RelayLiveKitEngine e = engine;
        engine = null;
        e.release();
    }

    // MARK: - Instances

    public static NativeInstance makePrivate(VideoSink remoteSink, long capturer, NativeInstance.AudioLevelsCallback audioLevels) {
        RelayCallManager.DirectSession session = RelayCallManager.direct;
        RelayPrivateInstance instance = new RelayPrivateInstance(session, remoteSink, audioLevels);
        if (session != null) {
            session.instance = instance;
        }
        Capturer c = capturers.get(capturer);
        if (c != null) {
            instance.setupOutgoingVideoCreated(capturer);
        }
        Log.i(TAG, "private instance for " + (session != null ? session.call.callId() : "no call"));
        return instance;
    }

    public static NativeInstance makeGroup(long capturer, boolean screencast, NativeInstance.PayloadCallback payload, NativeInstance.AudioLevelsCallback audioLevels,
                                           NativeInstance.VideoSourcesCallback unknownParticipants) {
        return RelayGroupSession.makeInstance(capturer, screencast, payload, audioLevels);
    }

    // MARK: - Capturers

    static final class Capturer {
        final long id;
        final int type;
        VideoSink localSink;
        RelayFrames preview;
        int state = Instance.VIDEO_STATE_ACTIVE;

        Capturer(long id, int type, VideoSink localSink) {
            this.id = id;
            this.type = type;
            this.localSink = localSink;
        }

        boolean isScreen() {
            return type == 2;
        }
    }

    private static final Map<Long, Capturer> capturers = new HashMap<>();
    private static long nextCapturer = 0x5e1a70000L;

    static Capturer capturer(long id) {
        return capturers.get(id);
    }

    public static long createCapturer(VideoSink localSink, int type) {
        long id = ++nextCapturer;
        Capturer c = new Capturer(id, type, localSink);
        capturers.put(id, c);
        RelayRoom room = engine().room();
        if (c.isScreen()) {
            engine().setScreenCapturePermission(VideoCapturerDevice.mediaProjectionPermissionResultData);
            c.preview = new RelayFrames(localSink);
            room.setLocalPreviewSink(RelayRoom.SOURCE_SCREEN, c.preview);
        } else {
            room.setCameraFront(type == 1);
            c.preview = new RelayFrames(localSink);
            c.preview.setOnFirstFrame(RelayVoIPAccess::cameraFirstFrame);
            room.setLocalPreviewSink(RelayRoom.SOURCE_CAMERA, c.preview);
        }
        Log.i(TAG, "capturer " + id + " created (type " + type + ")");
        if (!c.isScreen()) {
            // Telegram's preview (video preview sheets, the call screen's own video): captured, published only once
            // Telegram turns the video on (setVideoState / setupOutgoingVideoCreated) or the call wants it.
            Throwable failure = room.setCameraCapture(true);
            if (failure != null) {
                Log.w(TAG, "camera preview failed: " + failure);
            }
        }
        return id;
    }

    public static void setCapturerState(long id, int state) {
        Capturer c = capturers.get(id);
        if (c == null) {
            return;
        }
        c.state = state;
        apply(c, state == Instance.VIDEO_STATE_ACTIVE);
    }

    public static void switchCapturer(long id, boolean front) {
        Capturer c = capturers.get(id);
        if (c == null || c.isScreen() || engine == null) {
            return;
        }
        engine.room().setCameraFront(front);
        RelayVoIPAccess.cameraSwitched(front);
    }

    public static void destroyCapturer(long id) {
        Capturer c = capturers.remove(id);
        if (c == null) {
            return;
        }
        Log.i(TAG, "capturer " + id + " destroyed");
        if (engine != null) {
            engine.room().setLocalPreviewSink(c.isScreen() ? RelayRoom.SOURCE_SCREEN : RelayRoom.SOURCE_CAMERA, null);
        }
        apply(c, false);
        if (engine != null && !c.isScreen()) {
            engine.room().setCameraCapture(false);
        }
        releaseIfIdle();
    }

    /** Turns the capturer's source on or off: through the call (published, retried) or, before a call, the preview only. */
    static void apply(Capturer c, boolean on) {
        if (RelayGroupSession.current != null) {
            RelayGroupSession.current.setVideo(c.isScreen(), on);
            return;
        }
        RelayCallManager.DirectSession d = RelayCallManager.direct;
        if (d != null && d.instance != null && d.instance.usesCapturer(c.id)) {
            d.instance.capturerChanged(c, on);
            return;
        }
        if (c.isScreen() || engine == null) {
            return;
        }
        if (d != null) {
            d.call.setCameraEnabled(on);
        } else {
            // Before the call: preview only (the call publishes it once connected when it is a video call).
            engine.room().setCameraCapture(on);
        }
    }
}
