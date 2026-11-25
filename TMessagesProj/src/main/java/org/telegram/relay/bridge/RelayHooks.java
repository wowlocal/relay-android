package org.telegram.relay.bridge;

import org.telegram.relay.RelayConfig;
import org.telegram.relay.account.RelayAccount;
import org.telegram.tgnet.TLObject;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Small hooks called from Telegram's controllers in Relay mode.
 *
 * <p><b>Peer-aware message lookups.</b> Telegram keys private-chat messages by an account-wide id,
 * but Relay allocates message ids per dialog (one {@code MessageIdAllocator} space per chat, DMs
 * included; storage is keyed {@code (mid, uid)} anyway). A few client paths send ids without a peer
 * ({@code messages.getMessages} for reply previews and pinned messages). Those call sites tag the
 * request with its dialog via {@link #tagDialog}; the bridge reads the tag back with
 * {@link #taggedDialog}. Untagged requests get {@code messageEmpty}.
 */
public final class RelayHooks {
    private RelayHooks() {
    }

    private static final Map<TLObject, Long> dialogTags = Collections.synchronizedMap(new WeakHashMap<>());

    /** Remembers which dialog a peer-less request ({@code messages.getMessages}) is about. Returns the request. */
    public static <T extends TLObject> T tagDialog(T request, long dialogId) {
        if (RelayConfig.ENABLED && request != null && dialogId != 0) {
            dialogTags.put(request, dialogId);
        }
        return request;
    }

    static long taggedDialog(TLObject request) {
        Long dialogId = dialogTags.get(request);
        return dialogId != null ? dialogId : 0;
    }

    /**
     * Private-chat deletes: Telegram's {@code updateDeleteMessages} carries bare ids (one account-wide id
     * space), Relay's ids are per dialog. The bridge notes the dialog of every DM delete it hands out by
     * its (unique) common pts; {@code MessagesController.processUpdateArray} reads it back so storage and
     * the open chat delete in that dialog only.
     */
    private static final Map<Long, Long> dmDeletes = Collections.synchronizedMap(new java.util.LinkedHashMap<Long, Long>(64, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Long> eldest) {
            return size() > 4096;
        }
    });

    static void noteDmDelete(int account, int pts, long dialogId) {
        dmDeletes.put(((long) account << 32) | (pts & 0xffffffffL), dialogId);
    }

    /** The private chat of a bridge {@code updateDeleteMessages} (by its pts), or 0 (Telegram's peer-less behaviour). */
    public static long dmDeleteDialog(int account, int pts) {
        if (!RelayConfig.ENABLED) {
            return 0;
        }
        Long dialogId = dmDeletes.get(((long) account << 32) | (pts & 0xffffffffL));
        return dialogId != null ? dialogId : 0;
    }
    /**
     * Polish 1: an own message the server answered. Telegram keeps the text it parsed locally ({@code _italic_}
     * literal) and only copies the server's entities; {@code SendMessagesHelper} now copies the server's text too
     * (Mattermost markdown rendered by {@code MarkdownToEntities}), and the open chat rebuilds the bubble from it
     * here ({@code ChatActivity} messageReceivedByServer). True when the cell must be laid out again.
     */
    public static boolean reapplySentText(org.telegram.messenger.MessageObject obj) {
        if (!RelayConfig.ENABLED || obj == null || obj.messageOwner == null || android.text.TextUtils.isEmpty(obj.messageOwner.message)) {
            return false;
        }
        if (obj.type == org.telegram.messenger.MessageObject.TYPE_TEXT && obj.messageOwner.media == null
                || obj.messageOwner.media instanceof org.telegram.tgnet.TLRPC.TL_messageMediaEmpty
                || obj.messageOwner.media instanceof org.telegram.tgnet.TLRPC.TL_messageMediaWebPage) {
            obj.applyNewText();
            return true;
        }
        if (obj.caption != null) {
            obj.caption = null;
            obj.generateCaption();
            return true;
        }
        return false;
    }

    /**
     * Polish 1: messages the bridge re-inserted after an id renumber ({@link RelayRenumber}) are old posts at new
     * ids; they must not notify. Keyed {@code account:dialog:id}, bounded.
     */
    private static final Map<String, Boolean> renumbered = Collections.synchronizedMap(new java.util.LinkedHashMap<String, Boolean>(64, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
            return size() > 4096;
        }
    });

    static void noteRenumbered(int account, long dialogId, int id) {
        renumbered.put(account + ":" + dialogId + ":" + id, Boolean.TRUE);
    }

    /** True for a message the bridge re-inserted after a renumber (no notification). */
    public static boolean isRenumbered(int account, long dialogId, int id) {
        return RelayConfig.ENABLED && renumbered.containsKey(account + ":" + dialogId + ":" + id);
    }

    /** The newest mapped message id of a dialog in the bridge's id map, or 0 (polish 1, CRT read marks). */
    public static int newestMessageId(int account, long dialogId) {
        RelayAccount relayAccount = RelayAccount.get(account);
        if (!RelayConfig.ENABLED || relayAccount == null) {
            return 0;
        }
        try {
            String channelId = RelayReadPath.of(relayAccount).directory.channelIdOf(dialogId);
            if (channelId == null) {
                return 0;
            }
            for (org.telegram.relay.mattermost.ids.MessageIdEntry entry : relayAccount.messageIdStore().entriesFromLocalId(channelId, Integer.MAX_VALUE, false, 64)) {
                if (!entry.isDeleted) {
                    return entry.localId;
                }
            }
        } catch (Exception e) {
            RelayConfig.logError(RelayConfig.TAG_BRIDGE, "newest message of " + dialogId, e);
        }
        return 0;
    }

    /** Debug dump of the bridge's id map for one dialog (see {@code RelayDebugHooks}). */
    public static String dumpDialog(int account, long dialogId) {
        RelayAccount relayAccount = RelayAccount.get(account);
        if (relayAccount == null) {
            return "no session";
        }
        try {
            return RelayReadPath.of(relayAccount).dumpDialog(dialogId);
        } catch (Exception e) {
            return "dump failed: " + e;
        }
    }

    /** Debug: drops the account's WebSocket, resumed or not (a non-resumed reconnect forces a catch-up). */
    public static void debugReconnect(int account, boolean resume) {
        RelayLive live = RelayBridge.get(account).live();
        if (live != null) {
            live.debugReconnect(resume);
        }
    }

    /** Debug (polish 1): forces an id renumber in a chat and migrates it ({@link RelayRenumber#debugForce}); result on the callback. */
    public static void debugRenumber(int account, String channelId, org.telegram.messenger.Utilities.Callback<String> done) {
        RelayBridge bridge = RelayBridge.get(account);
        RelayLive live = bridge.live();
        if (live == null) {
            done.run("no live path");
            return;
        }
        bridge.post(() -> {
            String result;
            try {
                result = RelayRenumber.debugForce(live, channelId);
            } catch (Throwable t) {
                result = "failed: " + t;
            }
            done.run(result);
        });
    }

    /** Debug: the live path's state in one line. */
    public static String debugLiveState(int account) {
        RelayAccount relayAccount = RelayAccount.get(account);
        RelayLive live = RelayBridge.get(account).live();
        if (relayAccount == null || live == null) {
            return "live=none";
        }
        return "online=" + live.isOnline() + " network=" + live.networkAvailable + " catchUpNeeded=" + live.catchUpNeeded
                + " pts=" + relayAccount.db().pts(org.telegram.relay.account.RelayDatabase.BOX_COMMON)
                + " sync_at=" + relayAccount.db().kv(RelayLive.KV_SYNC_AT);
    }

    /** The bridge's dialog id for a Mattermost channel id (debug), or 0. */
    public static long dialogIdOfChannel(int account, String channelId) {
        RelayAccount relayAccount = RelayAccount.get(account);
        if (relayAccount == null) {
            return 0;
        }
        try {
            RelayReadPath path = RelayReadPath.of(relayAccount);
            return path.directory.dialogId(path.directory.channel(channelId));
        } catch (Exception e) {
            return 0;
        }
    }

    /** The Mattermost channel of a dialog (debug), or null. */
    public static String channelIdOfDialog(int account, long dialogId) {
        RelayAccount relayAccount = RelayAccount.get(account);
        if (relayAccount == null) {
            return null;
        }
        try {
            return RelayReadPath.of(relayAccount).directory.channelIdOf(dialogId);
        } catch (Exception e) {
            return null;
        }
    }
}
