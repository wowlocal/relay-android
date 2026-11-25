package org.telegram.relay.bridge;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.relay.RelayConfig;
import org.telegram.relay.account.RelayAccount;
import org.telegram.relay.mattermost.Json;
import org.telegram.relay.mattermost.model.Channel;
import org.telegram.relay.mattermost.model.ChannelMember;
import org.telegram.relay.mattermost.model.User;
import org.telegram.relay.mattermost.model.UserStatus;
import org.telegram.relay.mattermost.ws.WebSocketEvent;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_account;
import org.telegram.tgnet.tl.TL_update;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Notifications in Relay mode (A7c).
 *
 * <ul>
 *   <li><b>Which posts notify</b> ({@link #shouldNotify}, called from {@code NotificationsController.processNewMessages}):
 *       the Mattermost rules: nothing while the own status is Do Not Disturb; DMs and GMs unless the push level is
 *       "never"; channels by the channel's {@code notify_props.push} (or the user's when "default"): all / mentions
 *       only / never; a muted channel ({@code mark_unread=mention}) only for mentions. Telegram's own per-chat mute
 *       still applies on top (the bridge keeps it equal to Mattermost's mute).</li>
 *   <li><b>Settings</b>: {@code account.updateNotifySettings} for one chat → the channel's {@code mark_unread}
 *       (mute/unmute, as Mattermost's clients do); for "Private chats"/"Groups" → the user's
 *       {@code notify_props.push} (all / mention / none). {@code account.getNotifySettings} answers from the same
 *       props, and a mute changed on another device arrives as {@code updateNotifySettings}.</li>
 *   <li><b>Background</b>: {@link RelayKeepAlive} keeps the process and its WebSocket alive with a foreground
 *       service when "Keep connected in background" is on. Real push (FCM through a Mattermost push proxy) is
 *       prepared behind {@link #FCM_PUSH} (off; see {@code docs/relay/PUSH.md}).</li>
 * </ul>
 */
public final class RelayNotifications {
    private static final String TAG = RelayConfig.TAG_BRIDGE;

    /**
     * Real FCM push through a Mattermost push proxy. Off: the build's Firebase project is a dummy, so a token
     * request would only open Google sockets. Turn on with a real {@code google-services.json} and a push proxy
     * (docs/relay/PUSH.md).
     */
    public static final boolean FCM_PUSH = false;
    /** Mattermost's device id prefix for Android apps behind push proxy v2 (as the official mobile app). */
    public static final String DEVICE_PREFIX = "android_rn-v2:";

    /** Channel notify props changed by this app or seen live, newer than the directory snapshot. */
    private static final ConcurrentHashMap<String, Map<String, String>> channelProps = new ConcurrentHashMap<>();

    private RelayNotifications() {
    }

    static void register(Map<Class<?>, RelayHandlers.Handler> map) {
        map.put(TL_account.updateNotifySettings.class, (bridge, account, req) -> updateNotifySettings(account, (TL_account.updateNotifySettings) req));
        map.put(TL_account.getNotifySettings.class, (bridge, account, req) -> getNotifySettings(account, (TL_account.getNotifySettings) req));
        map.put(TL_account.registerDevice.class, (bridge, account, req) -> {
            TL_account.registerDevice r = (TL_account.registerDevice) req;
            if (FCM_PUSH && r.token != null && !r.token.isEmpty()) {
                account.client.attachDevice(DEVICE_PREFIX + r.token);
                RelayConfig.log(TAG, "push device attached (" + DEVICE_PREFIX + "…)");
            }
            return new TLRPC.TL_boolTrue();
        });
        map.put(TL_account.unregisterDevice.class, (bridge, account, req) -> new TLRPC.TL_boolTrue());
    }

    // MARK: - Props

    static Map<String, String> memberProps(RelayAccount account, String channelId) {
        Map<String, String> props = channelProps.get(account.account + ":" + channelId);
        if (props != null) {
            return props;
        }
        try {
            ChannelMember member = RelayReadPath.of(account).directory.any().members.get(channelId);
            if (member != null && member.notifyProps != null) {
                return member.notifyProps;
            }
        } catch (Throwable ignore) {
        }
        return new HashMap<>();
    }

    static String userPushLevel(RelayAccount account) {
        User me = account.cachedUser(account.session.userId);
        String push = me != null && me.notifyProps != null ? me.notifyProps.get("push") : null;
        return push != null && !push.isEmpty() ? push : "mention";
    }

    // MARK: - Decision

    /** Whether a new message may notify (Mattermost's rules); true when the bridge cannot tell. */
    public static boolean shouldNotify(int accountNum, MessageObject message) {
        if (!RelayConfig.ENABLED || message == null || message.messageOwner == null || message.isOutOwner()) {
            return true;
        }
        if (RelayHooks.isRenumbered(accountNum, message.getDialogId(), message.getId())) {
            return false; // Polish 1: an old post re-inserted at a new id (RelayRenumber)
        }
        RelayAccount account = RelayAccount.get(accountNum);
        if (account == null) {
            return true;
        }
        try {
            UserStatus status = account.status(account.session.userId);
            if (status != null && UserStatus.DND.equals(status.status)) {
                RelayConfig.log(TAG, "notification skipped: Do Not Disturb");
                return false;
            }
            RelayReadPath path = RelayReadPath.of(account);
            String channelId = path.directory.channelIdOf(message.getDialogId());
            if (channelId == null) {
                return true;
            }
            Map<String, String> props = memberProps(account, channelId);
            String level = props.get("push");
            if (level == null || level.isEmpty() || "default".equals(level)) {
                level = userPushLevel(account);
            }
            boolean mentioned = message.messageOwner.mentioned;
            boolean muted = "mention".equals(props.get("mark_unread"));
            Channel channel = path.directory.any().channels.get(channelId);
            boolean direct = channel != null && (channel.isDirect() || channel.isGroup());
            boolean result;
            if ("none".equals(level)) {
                result = false;
            } else if (muted) {
                result = mentioned;
            } else if (direct || "all".equals(level)) {
                result = true;
            } else {
                result = mentioned;
            }
            if (!result) {
                RelayConfig.log(TAG, "notification skipped: " + channelId + " push=" + level + " muted=" + muted + " mentioned=" + mentioned);
            }
            return result;
        } catch (Throwable t) {
            RelayConfig.logError(TAG, "shouldNotify", t);
            return true;
        }
    }

    // MARK: - Settings

    private static TLObject updateNotifySettings(RelayAccount account, TL_account.updateNotifySettings r) throws Exception {
        int now = RelayStubs.now(account.account);
        boolean muted = r.settings != null && (r.settings.flags & TLObject.FLAG_2) != 0 && r.settings.mute_until > now;
        if (r.peer instanceof TLRPC.TL_inputNotifyPeer) {
            RelayReadPath path = RelayReadPath.of(account);
            String channelId = path.directory.channelIdOf(((TLRPC.TL_inputNotifyPeer) r.peer).peer);
            if (channelId == null) {
                return new TLRPC.TL_boolTrue();
            }
            Map<String, String> props = new HashMap<>(memberProps(account, channelId));
            String markUnread = muted ? "mention" : "all";
            if (markUnread.equals(props.get("mark_unread"))) {
                return new TLRPC.TL_boolTrue();
            }
            Map<String, String> patch = new HashMap<>();
            patch.put("mark_unread", markUnread);
            account.client.updateChannelNotifyProps(channelId, patch);
            props.put("mark_unread", markUnread);
            channelProps.put(account.account + ":" + channelId, props);
            path.directory.invalidate();
            RelayConfig.log(TAG, "channel " + channelId + " " + (muted ? "muted" : "unmuted"));
            return new TLRPC.TL_boolTrue();
        }
        if (r.peer instanceof TLRPC.TL_inputNotifyUsers || r.peer instanceof TLRPC.TL_inputNotifyChats) {
            // Telegram's "Private chats"/"Groups" switches ↔ the user's push level: off = never; on = the level the
            // user had (Mattermost's default "mentions" when it was never). Telegram has no "mentions only" switch:
            // that level stays as set on the server, and shouldNotify applies it.
            User me = account.client.me();
            Map<String, String> notify = new HashMap<>(me.notifyProps != null ? me.notifyProps : new HashMap<String, String>());
            String current = notify.get("push");
            String push = muted ? "none" : ("none".equals(current) || current == null || current.isEmpty() ? "mention" : current);
            if (!push.equals(current)) {
                notify.put("push", push);
                com.google.gson.JsonObject patch = new com.google.gson.JsonObject();
                patch.add("notify_props", Json.GSON.toJsonTree(notify));
                me = account.client.patchMe(patch);
                RelayConfig.log(TAG, "user push level: " + push);
            }
            account.rememberUser(me);
        }
        return new TLRPC.TL_boolTrue();
    }

    private static TLObject getNotifySettings(RelayAccount account, TL_account.getNotifySettings r) throws Exception {
        TLRPC.TL_peerNotifySettings s = new TLRPC.TL_peerNotifySettings();
        s.flags |= TLObject.FLAG_0;
        s.show_previews = true;
        boolean muted = false;
        if (r.peer instanceof TLRPC.TL_inputNotifyPeer) {
            String channelId = RelayReadPath.of(account).directory.channelIdOf(((TLRPC.TL_inputNotifyPeer) r.peer).peer);
            muted = channelId != null && "mention".equals(memberProps(account, channelId).get("mark_unread"));
        } else if (r.peer instanceof TLRPC.TL_inputNotifyUsers || r.peer instanceof TLRPC.TL_inputNotifyChats) {
            User me = account.cachedUser(account.session.userId);
            if (me == null) {
                account.rememberUser(me = account.client.me());
            }
            muted = "none".equals(userPushLevel(account));
        }
        if (muted) {
            s.mute_until = Integer.MAX_VALUE;
            s.flags |= TLObject.FLAG_2;
        } else {
            s.mute_until = 0;
            s.flags |= TLObject.FLAG_2;
        }
        return s;
    }

    /** {@code channel_member_updated}: notify props changed (also by another device): mute state to the client. */
    static void onChannelMemberUpdated(RelayLive live, RelayAccount account, WebSocketEvent event) {
        com.google.gson.JsonElement raw = event.data != null ? event.data.get("channelMember") : null;
        ChannelMember member = raw != null ? Json.decodeEmbedded(raw, ChannelMember.class) : null;
        if (member == null || !account.session.userId.equals(member.userId)) {
            return;
        }
        String key = account.account + ":" + member.channelId;
        Map<String, String> before = memberProps(account, member.channelId);
        channelProps.put(key, member.notifyProps != null ? member.notifyProps : new HashMap<String, String>());
        boolean wasMuted = "mention".equals(before.get("mark_unread"));
        boolean muted = member.isMuted();
        if (wasMuted == muted) {
            return;
        }
        try {
            RelayReadPath path = RelayReadPath.of(account);
            Channel channel = path.directory.channel(member.channelId);
            path.directory.invalidate();
            TL_update.TL_updateNotifySettings u = new TL_update.TL_updateNotifySettings();
            TLRPC.TL_notifyPeer peer = new TLRPC.TL_notifyPeer();
            peer.peer = path.directory.peer(channel);
            u.peer = peer;
            TLRPC.TL_peerNotifySettings s = new TLRPC.TL_peerNotifySettings();
            s.flags |= TLObject.FLAG_0 | TLObject.FLAG_2;
            s.show_previews = true;
            s.mute_until = muted ? Integer.MAX_VALUE : 0;
            u.notify_settings = s;
            ArrayList<TLRPC.Update> list = new ArrayList<>();
            list.add(u);
            RelayUpdates.Refs refs = new RelayUpdates.Refs();
            if (!channel.isDirect()) {
                refs.chat(path.directory.tlChannel(channel));
            }
            live.updates.deliver(list, refs);
            RelayConfig.log(TAG, "live: channel " + member.channelId + (muted ? " muted" : " unmuted") + " elsewhere");
        } catch (Throwable t) {
            RelayConfig.logError(TAG, "channel_member_updated", t);
        }
    }

    // MARK: - Push (FCM through a Mattermost push proxy; off, see FCM_PUSH)

    /**
     * A push from the Mattermost push proxy ({@code type} message / clear / update_badge / session; {@code channel_id},
     * {@code post_id}, {@code root_id}, {@code sender_name}, {@code channel_name}, {@code message}, {@code badge},
     * {@code server_id}, {@code ack_id}). The bridge wakes (or stays) up and catches up over REST/WebSocket; the
     * notification itself then comes from Telegram's NotificationsController like any live message.
     */
    public static void onPushMessage(Map<String, String> data, long sentTime) {
        String type = data != null ? data.get("type") : null;
        RelayConfig.log(TAG, "push " + type + " channel=" + (data != null ? data.get("channel_id") : null));
        AndroidUtilities.runOnUIThread(() -> {
            ApplicationLoader.postInitApplication();
            for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                if (RelayAccount.get(a) == null) {
                    continue;
                }
                final int account = a;
                RelayBridge.get(account).start();
                if ("clear".equals(type) || "message".equals(type)) {
                    Utilities.stageQueue.postRunnable(() -> MessagesController.getInstance(account).getDifference());
                }
            }
        });
    }

    // MARK: - Keep connected (foreground service)

    /** "Keep connected in background": Telegram's {@code pushService} preference, which Relay reuses. */
    public static boolean keepConnected() {
        return MessagesController.getGlobalNotificationsSettings().getBoolean("pushService", false);
    }

    public static void setKeepConnected(boolean on) {
        MessagesController.getGlobalNotificationsSettings().edit().putBoolean("pushService", on).apply();
        applyKeepConnected();
    }

    /** Starts or stops {@link RelayKeepAlive} (from {@code ApplicationLoader.startPushService}). */
    public static void applyKeepConnected() {
        Context context = ApplicationLoader.applicationContext;
        Intent intent = new Intent(context, RelayKeepAlive.class);
        boolean loggedIn = false;
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (RelayAccount.get(a) != null) {
                loggedIn = true;
                break;
            }
        }
        try {
            if (keepConnected() && loggedIn) {
                if (Build.VERSION.SDK_INT >= 26) {
                    context.startForegroundService(intent);
                } else {
                    context.startService(intent);
                }
            } else {
                context.stopService(intent);
            }
        } catch (Throwable t) {
            RelayConfig.logError(TAG, "keep connected service", t);
        }
    }
}
