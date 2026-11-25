package org.telegram.messenger.voip;

/**
 * Relay (A8): the few VoIPService callbacks tgcalls' Java capturer used (package-private there), for the LiveKit
 * capturer in {@code org.telegram.relay.calls}.
 */
public final class RelayVoIPAccess {
    private RelayVoIPAccess() {
    }

    public static void cameraFirstFrame() {
        VoIPService service = VoIPService.getSharedInstance();
        if (service != null) {
            service.onCameraFirstFrameAvailable();
        }
    }

    public static void cameraSwitched(boolean front) {
        VoIPService service = VoIPService.getSharedInstance();
        if (service != null) {
            service.setSwitchingCamera(false, front);
        }
    }
}
