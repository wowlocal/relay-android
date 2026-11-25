package org.telegram.relay.calls;

import android.util.Log;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.relay.account.RelayAccount;
import org.telegram.relay.mattermost.calls.RelayCallJoin;
import org.telegram.relay.mattermost.calls.RelayCallsClient;
import org.telegram.relay.mattermost.calls.RelayCallsException;

/** Debug-build helpers behind {@code org.relaychat.debug.RelayA8DebugHooks}. */
public final class RelayCallsDebug {
    private static final String TAG = "RelayDebug";

    private RelayCallsDebug() {
    }

    /**
     * A8 step 1: joins (or starts) the channel's call over the plugin, connects a LiveKit room with this
     * app's WebRTC (liblkjingle) beside Telegram's, publishes the microphone, logs snapshots, leaves after
     * [seconds].
     */
    public static void probe(int account, String channelId, int seconds) {
        RelayAccount relay = RelayAccount.get(account);
        if (relay == null) {
            Log.w(TAG, "DEBUG_CALL_PROBE: no Relay account");
            return;
        }
        RelayCallsClient client = new RelayCallsClient(relay.client);
        String deviceId = java.util.UUID.randomUUID().toString();
        RelayCallScheduler.INSTANCE.io(() -> {
            try {
                RelayCallJoin join = client.join(channelId, null, deviceId);
                Log.i(TAG, "DEBUG_CALL_PROBE joined " + join.call + " url=" + join.url);
                RelayCallScheduler.INSTANCE.post(() -> connect(client, join, deviceId, seconds));
            } catch (RelayCallsException e) {
                Log.w(TAG, "DEBUG_CALL_PROBE join failed: " + e.status + " " + e.getMessage());
            }
        });
    }

    private static void connect(RelayCallsClient client, RelayCallJoin join, String deviceId, int seconds) {
        RelayLiveKitEngine engine = new RelayLiveKitEngine(ApplicationLoader.applicationContext);
        engine.setListener(new org.telegram.relay.mattermost.calls.RelayMedia.Listener() {
            @Override
            public void onChange(org.telegram.relay.mattermost.calls.RelayMedia.Snapshot snapshot) {
                Log.i(TAG, "DEBUG_CALL_PROBE snapshot " + snapshot);
            }

            @Override
            public void onDisconnect(org.telegram.relay.mattermost.calls.RelayMedia.DisconnectReason reason, String message) {
                Log.i(TAG, "DEBUG_CALL_PROBE disconnected " + reason + " " + message);
            }
        });
        engine.connect(join.url, join.token, error -> {
            Log.i(TAG, "DEBUG_CALL_PROBE connect " + (error == null ? "ok" : "failed: " + error));
            if (error != null) {
                return;
            }
            engine.setMicrophone(true, e -> Log.i(TAG, "DEBUG_CALL_PROBE microphone " + (e == null ? "on" : "failed: " + e)));
            RelayCallScheduler.INSTANCE.schedule(seconds * 1000L, () -> {
                Log.i(TAG, "DEBUG_CALL_PROBE leaving, last " + engine.snapshot());
                engine.release();
                RelayCallScheduler.INSTANCE.io(() -> {
                    try {
                        client.hangup(join.call.id, deviceId);
                        Log.i(TAG, "DEBUG_CALL_PROBE done");
                    } catch (RelayCallsException e) {
                        Log.w(TAG, "DEBUG_CALL_PROBE hangup failed: " + e.getMessage());
                    }
                });
            });
        });
    }

    /** Telegram's call button path for a DM user (main thread). */
    public static void call(int account, String username, boolean video) {
        org.telegram.messenger.MessagesController mc = org.telegram.messenger.MessagesController.getInstance(account);
        org.telegram.tgnet.TLRPC.User user = mc.getUser(username);
        android.app.Activity activity = org.telegram.ui.LaunchActivity.instance;
        if (user == null || activity == null) {
            Log.w(TAG, "DEBUG_CALL: user " + username + " found=" + (user != null) + " activity=" + (activity != null));
            return;
        }
        org.telegram.tgnet.TLRPC.UserFull full = mc.getUserFull(user.id);
        Log.i(TAG, "DEBUG_CALL " + username + " video=" + video + " full=" + (full != null));
        org.telegram.ui.Components.voip.VoIPHelper.startCall(user, video, true, activity, full, org.telegram.messenger.AccountInstance.getInstance(account));
    }

    /** {@code DEBUG_CALL_STATE state=<VoIPService state> …} (main thread). */
    public static void logState() {
        org.telegram.messenger.voip.VoIPService s = org.telegram.messenger.voip.VoIPService.getSharedInstance();
        StringBuilder out = new StringBuilder("DEBUG_CALL_STATE");
        if (s == null) {
            out.append(" service=none");
        } else {
            out.append(" state=").append(s.getCallState()).append(" mic_muted=").append(s.isMicMute())
                    .append(" video=").append(s.getVideoState(false)).append(" screen=").append(s.getVideoState(true))
                    .append(" group=").append(s.groupCall != null);
        }
        RelayCallManager.DirectSession d = RelayCallManager.direct;
        if (d != null) {
            out.append(" relay=").append(d.call.snapshot());
        }
        RelayGroupSession g = RelayGroupSession.current;
        if (g != null && g.call != null) {
            out.append(" relay_group=").append(g.call.snapshot());
        }
        RelayLiveKitEngine e = RelayVoip.currentEngine();
        if (e != null) {
            out.append(" media=").append(e.snapshot());
        }
        Log.i(TAG, out.toString());
    }
}
