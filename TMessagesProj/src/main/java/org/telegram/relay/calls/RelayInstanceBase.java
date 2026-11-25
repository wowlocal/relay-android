package org.telegram.relay.calls;

import org.telegram.messenger.voip.Instance;
import org.telegram.messenger.voip.NativeInstance;
import org.telegram.messenger.voip.VoIPService;
import org.webrtc.VideoSink;

import java.nio.ByteBuffer;

/**
 * A {@link NativeInstance} without tgcalls: every native method is overridden (no native pointer exists), so
 * Telegram's VoIPService can drive a Relay call through the instance API it knows. Subclasses map the calls they
 * need onto the LiveKit-backed controllers.
 */
abstract class RelayInstanceBase extends NativeInstance {
    protected Instance.OnStateUpdatedListener stateListener;
    protected Instance.OnSignalBarsUpdatedListener signalBarsListener;
    protected Instance.OnRemoteMediaStateUpdatedListener remoteMediaListener;
    protected volatile boolean stopped;

    @Override
    public void setOnStateUpdatedListener(Instance.OnStateUpdatedListener listener) {
        stateListener = listener;
    }

    @Override
    public void setOnSignalBarsUpdatedListener(Instance.OnSignalBarsUpdatedListener listener) {
        signalBarsListener = listener;
    }

    @Override
    public void setOnSignalDataListener(Instance.OnSignalingDataListener listener) {
    }

    @Override
    public void setOnRemoteMediaStateUpdatedListener(Instance.OnRemoteMediaStateUpdatedListener listener) {
        remoteMediaListener = listener;
    }

    @Override
    public Instance.FinalState stop() {
        stopped = true;
        onStop();
        return new Instance.FinalState(new byte[0], "", new Instance.TrafficStats(0, 0, 0, 0), false);
    }

    @Override
    public void stopGroup() {
        stopped = true;
        onStop();
    }

    /** The instance is done (Telegram ended or left the call). */
    protected abstract void onStop();

    @Override public void setJoinResponsePayload(String payload) { }
    @Override public void prepareForStream(boolean isRtpStream) { }
    @Override public void resetGroupInstance(boolean set, boolean disconnect) { }
    @Override public void onMediaDescriptionAvailable(long taskPtr, VoIPService.RequestedParticipant[] ssrcs) { }
    @Override public void setNoiseSuppressionEnabled(boolean value) { }
    @Override public void activateVideoCapturer(long videoCapturer) { }
    @Override public void clearVideoCapturer() { }
    @Override public long addIncomingVideoOutput(int quality, String endpointId, SsrcGroup[] ssrcGroups, VideoSink remoteSink, long userId) { return 0; }
    @Override public void removeIncomingVideoOutput(long nativeRemoteSink) { }
    @Override public void setVideoEndpointQuality(String endpointId, int quality) { }
    @Override public void setGlobalServerConfig(String serverConfigJson) { }
    @Override public void setBufferSize(int size) { }
    @Override public void setNetworkType(int networkType) { }
    @Override public void setMuteMicrophone(boolean muteMicrophone) { }
    @Override public void setVolume(int ssrc, double volume) { }
    @Override public void setAudioOutputGainControlEnabled(boolean enabled) { }
    @Override public void setEchoCancellationStrength(int strength) { }
    @Override public String getLastError() { return "ERROR_UNKNOWN"; }
    @Override public String getDebugInfo() { return ""; }
    @Override public long getPreferredRelayId() { return 0; }
    @Override public Instance.TrafficStats getTrafficStats() { return new Instance.TrafficStats(0, 0, 0, 0); }
    @Override public byte[] getPersistentState() { return new byte[0]; }
    @Override public void setupOutgoingVideo(VideoSink localSink, int type) { }
    @Override public void setupOutgoingVideoCreated(long videoCapturer) { }
    @Override public void switchCamera(boolean front) { }
    @Override public void setVideoState(int videoState) { }
    @Override public void onSignalingDataReceive(byte[] data) { }
    @Override public void onStreamPartAvailable(long ts, ByteBuffer buffer, int size, long timestamp, int videoChannel, int quality) { }
    @Override public boolean hasVideoCapturer() { return false; }
    @Override public void onRequestTimeComplete(long taskPtr, long time) { }
    @Override public void setConferenceCallId(long call_id) { }
}
