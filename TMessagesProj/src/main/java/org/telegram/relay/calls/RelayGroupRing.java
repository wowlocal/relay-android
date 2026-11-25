package org.telegram.relay.calls;

import android.util.Log;

import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.voip.VoIPGroupNotification;
import org.telegram.messenger.voip.VoIPService;
import org.telegram.relay.account.RelayDatabase;
import org.telegram.relay.bridge.RelayHooks;
import org.telegram.relay.mattermost.calls.RelayCall;
import org.telegram.relay.mattermost.calls.RelayCallsException;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Components.voip.VoIPHelper;
import org.telegram.ui.LaunchActivity;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Polish 2a: an incoming group-message ring ({@code kind: group}) rings with Telegram's own incoming group call
 * (conference) notification and screen ({@link VoIPGroupNotification}: full-screen intent, Answer / Decline, ringtone),
 * as iOS rings them. Answer joins the GM's video chat (Telegram's join path, {@code joinGroupCall} → the plugin's
 * {@code accept}); Decline declines on the plugin; an unanswered ring is hidden when the plugin stops ringing (missed,
 * answered on another device, caller gone). Main thread unless noted.
 */
public final class RelayGroupRing {
    private static final String TAG = RelayCallManager.TAG;

    private static final class Ring {
        final int account;
        final String callId;
        final long callTlId;
        final long chatTlId;

        Ring(int account, String callId, long callTlId, long chatTlId) {
            this.account = account;
            this.callId = callId;
            this.callTlId = callTlId;
            this.chatTlId = chatTlId;
        }
    }

    /** Telegram's invite "message id" (the notification's key) → the ring. */
    private static final Map<Integer, Ring> rings = new ConcurrentHashMap<>();

    private RelayGroupRing() {
    }

    static int msgId(long callTlId) {
        int id = (int) (callTlId ^ (callTlId >>> 32)) & 0x3fffffff;
        return id == 0 ? 1 : id;
    }

    /** A group ring for this user arrived (event or {@code GET /calls/active}). */
    static void maybeRing(RelayCallManager manager, RelayCall call) {
        if (call == null || call.kind != RelayCall.Kind.GROUP || !call.isRinging(manager.ownUserId) || manager.ownUserId.equals(call.startedBy)) {
            return;
        }
        RelayGroupSession s = RelayGroupSession.current;
        if (s != null && call.id.equals(s.callId)) {
            return;
        }
        if (RelayCallManager.hasActiveCall() || VoIPService.getSharedInstance() != null) {
            Log.i(TAG, "group ring " + call.id + ": busy (another call)");
            return;
        }
        manager.scheduler.io(() -> {
            long callTlId = manager.relay.db().tlId(RelayDatabase.KIND_CALL, call.id);
            long chatTlId = manager.relay.db().findTlId(RelayDatabase.KIND_CHANNEL, call.channelId);
            long callerTlId = manager.relay.userTlId(call.startedBy);
            List<TLRPC.User> users = RelayHooks.tlUsers(manager.account, Collections.singletonList(call.startedBy));
            TLRPC.TL_channel chat = RelayHooks.tlChannel(manager.account, call.channelId);
            manager.scheduler.post(() -> {
                if (chatTlId == 0 || !call.isRinging(manager.ownUserId) || RelayCallManager.hasActiveCall()) {
                    return;
                }
                MessagesController mc = MessagesController.getInstance(manager.account);
                mc.putUsers(new java.util.ArrayList<>(users), false);
                if (chat != null && mc.getChat(chat.id) == null) {
                    mc.putChat(chat, false);
                }
                int msgId = msgId(callTlId);
                if (rings.containsKey(msgId)) {
                    return;
                }
                rings.put(msgId, new Ring(manager.account, call.id, callTlId, chatTlId));
                TLRPC.Chat gm = mc.getChat(chatTlId);
                String names = gm != null && gm.title != null ? gm.title : null;
                Log.i(TAG, "group ring " + call.id + ": incoming from " + call.startedBy + " in " + call.channelId);
                VoIPGroupNotification.requestRelay(RelayCallManager.context(), manager.account, callerTlId, names, callTlId, msgId, call.video);
            });
        });
    }

    /** The plugin no longer rings this user for the call (answered elsewhere, ended, expired): hide the ring. */
    static void onCallChanged(RelayCallManager manager, RelayCall call) {
        if (call == null || call.kind != RelayCall.Kind.GROUP) {
            return;
        }
        for (Map.Entry<Integer, Ring> e : rings.entrySet()) {
            Ring ring = e.getValue();
            if (ring.account == manager.account && ring.callId.equals(call.id) && !call.isRinging(manager.ownUserId)) {
                rings.remove(e.getKey());
                VoIPGroupNotification.hideByCallId(RelayCallManager.context(), manager.account, ring.callTlId);
                Log.i(TAG, "group ring " + call.id + ": no longer ringing");
            }
        }
    }

    /** VoIPGroupNotification: is this one of Relay's rings? */
    public static boolean isRelayRing(int msgId) {
        return rings.containsKey(msgId);
    }

    /** Answer: join the group message's video chat (Telegram's join panel path). */
    public static void answer(int account, int msgId) {
        Ring ring = rings.remove(msgId);
        if (ring == null) {
            return;
        }
        TLRPC.Chat chat = MessagesController.getInstance(account).getChat(ring.chatTlId);
        LaunchActivity activity = LaunchActivity.instance;
        if (chat == null || activity == null) {
            Log.w(TAG, "group ring " + ring.callId + ": answer without chat or activity");
            return;
        }
        Log.i(TAG, "group ring " + ring.callId + ": answered");
        MessagesController mc = MessagesController.getInstance(account);
        long chatId = ring.chatTlId;
        Runnable join = () -> {
            LaunchActivity a = LaunchActivity.instance;
            if (a != null) {
                VoIPHelper.startCall(mc.getChat(chatId), null, null, false, true, a, LaunchActivity.getLastFragment(), AccountInstance.getInstance(account));
            }
        };
        // VoIPService joins the chat's known call (chatFull.call → ChatObject.Call): load them first when the GM was
        // never opened in this session.
        if (mc.getGroupCall(chatId, false) != null) {
            join.run();
            return;
        }
        TLRPC.ChatFull full = mc.getChatFull(chatId);
        if (full != null && full.call != null) {
            mc.getGroupCall(chatId, true, join);
            return;
        }
        NotificationCenter center = NotificationCenter.getInstance(account);
        NotificationCenter.NotificationCenterDelegate[] observer = new NotificationCenter.NotificationCenterDelegate[1];
        observer[0] = (id, acc, args) -> {
            if (args.length == 0 || !(args[0] instanceof TLRPC.ChatFull) || ((TLRPC.ChatFull) args[0]).id != chatId) {
                return;
            }
            center.removeObserver(observer[0], NotificationCenter.chatInfoDidLoad);
            if (mc.getGroupCall(chatId, false) != null) {
                join.run();
            } else {
                mc.getGroupCall(chatId, true, join);
            }
        };
        center.addObserver(observer[0], NotificationCenter.chatInfoDidLoad);
        mc.loadFullChat(chatId, 0, true);
    }

    /** Decline (Telegram's Decline button or notification action) on the plugin. */
    public static void decline(int account, int msgId) {
        Ring ring = rings.remove(msgId);
        if (ring == null) {
            return;
        }
        RelayCallManager manager = RelayCallManager.of(account);
        if (manager == null) {
            return;
        }
        Log.i(TAG, "group ring " + ring.callId + ": declined");
        manager.scheduler.io(() -> {
            try {
                manager.client.decline(ring.callId, manager.deviceId, "declined");
            } catch (RelayCallsException e) {
                Log.w(TAG, "group ring " + ring.callId + ": decline failed: " + e.getMessage());
            }
        });
    }

    /** The ring timed out locally (Telegram's ring timeout): hide only; the plugin reports it as missed. */
    public static void expired(int msgId) {
        rings.remove(msgId);
    }
}
