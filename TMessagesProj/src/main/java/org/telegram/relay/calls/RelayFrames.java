package org.telegram.relay.calls;

import org.telegram.relay.livekit.RelayRoom;
import org.webrtc.JavaI420Buffer;
import org.webrtc.VideoFrame;
import org.webrtc.VideoSink;

/**
 * LiveKit's video frames (prefixed WebRTC, {@code livekit.org.webrtc}) into Telegram's renderers (Telegram's own
 * {@code org.webrtc}): the I420 planes are wrapped, not copied, and released when Telegram's frame is.
 */
final class RelayFrames implements RelayRoom.FrameSink {
    private volatile VideoSink target;
    private volatile Runnable onFirstFrame;
    private volatile int frames;

    RelayFrames(VideoSink target) {
        this.target = target;
    }

    void setTarget(VideoSink target) {
        this.target = target;
    }

    void setOnFirstFrame(Runnable r) {
        onFirstFrame = r;
    }

    int frames() {
        return frames;
    }

    @Override
    public void onFrame(RelayRoom.I420Frame frame) {
        VideoSink sink = target;
        if (sink == null) {
            frame.release();
            return;
        }
        JavaI420Buffer buffer = JavaI420Buffer.wrap(frame.width, frame.height, frame.dataY, frame.strideY, frame.dataU, frame.strideU,
                frame.dataV, frame.strideV, frame::release);
        VideoFrame tgFrame = new VideoFrame(buffer, frame.rotation, frame.timestampNs);
        try {
            sink.onFrame(tgFrame);
        } finally {
            tgFrame.release();
        }
        if (frames++ == 0) {
            Runnable r = onFirstFrame;
            if (r != null) {
                org.telegram.messenger.AndroidUtilities.runOnUIThread(r);
            }
        }
    }
}
