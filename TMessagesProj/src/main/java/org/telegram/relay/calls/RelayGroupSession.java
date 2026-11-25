package org.telegram.relay.calls;

import org.telegram.messenger.voip.NativeInstance;
import org.telegram.relay.mattermost.calls.RelayCall;
import org.telegram.relay.mattermost.calls.RelayGroupCall;

/** A group/channel call presented with Telegram's video chat UI (filled in by the group-call part of A8). */
final class RelayGroupSession {
    static RelayGroupSession current;

    RelayCallManager manager;
    RelayGroupCall call;

    static NativeInstance makeInstance(long capturer, boolean screencast, NativeInstance.PayloadCallback payload, NativeInstance.AudioLevelsCallback audioLevels) {
        return new RelayInstanceBase() {
            @Override
            protected void onStop() {
            }
        };
    }

    static void liveCallChanged(RelayCallManager manager, RelayCall call) {
    }

    void setVideo(boolean screen, boolean on) {
    }
}
