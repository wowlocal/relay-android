package org.telegram.relay.bridge;

import org.telegram.messenger.MessagesController;
import org.telegram.messenger.Utilities;
import org.telegram.relay.RelayConfig;
import org.telegram.relay.account.RelayAccount;
import org.telegram.relay.account.RelayDatabase;
import org.telegram.relay.mattermost.model.Channel;
import org.telegram.tgnet.SerializedData;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_update;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The bridge's update stream (internals doc §6.4): pts assignment, the update log behind
 * {@code getDifference}/{@code getChannelDifference}, and delivery to {@code MessagesController.processUpdates}.
 *
 * <ul>
 *   <li>Box {@code common} for private-chat updates (type 0), one box per megagroup ({@code c<id>}) for
 *       channel updates (type 2); every other update carries no pts.</li>
 *   <li>Every pts-bearing update is logged as {@code [int64 dialog][TL bytes]} (the dialog makes a DM
 *       {@code updateDeleteMessages} peer-aware on replay, see {@link RelayHooks#dmDeleteDialog}).</li>
 *   <li>Runs on the account's serial bridge executor, like the request handlers that also hand out pts
 *       (send, edit, delete), so pts order is delivery order.</li>
 * </ul>
 */
final class RelayUpdates {
    private static final String TAG = RelayConfig.TAG_BRIDGE;
    static final int LOG_KEEP = 2000;
    static final int LOG_SECONDS = 3 * 24 * 3600;
    /** Entries answered per difference; a longer gap is answered "too long". */
    static final int DIFFERENCE_LIMIT = 1000;

    final RelayBridge bridge;
    final RelayAccount account;
    final RelayReadPath path;

    RelayUpdates(RelayBridge bridge, RelayAccount account, RelayReadPath path) {
        this.bridge = bridge;
        this.account = account;
        this.path = path;
    }

    private RelayDatabase db() {
        return account.db();
    }

    // MARK: - pts

    /** The pts box of an update, or null when it carries no pts. */
    static String box(TLRPC.Update u) {
        if (u instanceof TL_update.TL_updateNewMessage || u instanceof TL_update.TL_updateEditMessage
                || u instanceof TL_update.TL_updateDeleteMessages || u instanceof TL_update.TL_updateReadHistoryInbox
                || u instanceof TL_update.TL_updateReadHistoryOutbox || u instanceof TL_update.TL_updatePinnedMessages) {
            return RelayDatabase.BOX_COMMON;
        }
        long channelId = channelOf(u);
        return channelId != 0 ? RelayDatabase.channelBox(channelId) : null;
    }

    /** The channel of a type-2 update, or 0. */
    static long channelOf(TLRPC.Update u) {
        if (u instanceof TL_update.TL_updateNewChannelMessage) {
            return ((TL_update.TL_updateNewChannelMessage) u).message.peer_id.channel_id;
        }
        if (u instanceof TL_update.TL_updateEditChannelMessage) {
            return ((TL_update.TL_updateEditChannelMessage) u).message.peer_id.channel_id;
        }
        if (u instanceof TL_update.TL_updateDeleteChannelMessages) {
            return ((TL_update.TL_updateDeleteChannelMessages) u).channel_id;
        }
        if (u instanceof TL_update.TL_updatePinnedChannelMessages) { // A7b: pin/unpin
            return ((TL_update.TL_updatePinnedChannelMessages) u).channel_id;
        }
        return 0;
    }

    private static int ptsCount(TLRPC.Update u) {
        if (u instanceof TL_update.TL_updateDeleteMessages) {
            return Math.max(1, ((TL_update.TL_updateDeleteMessages) u).messages.size());
        }
        if (u instanceof TL_update.TL_updateDeleteChannelMessages) {
            return Math.max(1, ((TL_update.TL_updateDeleteChannelMessages) u).messages.size());
        }
        return 1;
    }

    private static void setPts(TLRPC.Update u, int pts, int count) {
        if (u instanceof TL_update.TL_updateNewMessage) {
            ((TL_update.TL_updateNewMessage) u).pts = pts;
            ((TL_update.TL_updateNewMessage) u).pts_count = count;
        } else if (u instanceof TL_update.TL_updateEditMessage) {
            ((TL_update.TL_updateEditMessage) u).pts = pts;
            ((TL_update.TL_updateEditMessage) u).pts_count = count;
        } else if (u instanceof TL_update.TL_updateDeleteMessages) {
            ((TL_update.TL_updateDeleteMessages) u).pts = pts;
            ((TL_update.TL_updateDeleteMessages) u).pts_count = count;
        } else if (u instanceof TL_update.TL_updateReadHistoryInbox) {
            ((TL_update.TL_updateReadHistoryInbox) u).pts = pts;
            ((TL_update.TL_updateReadHistoryInbox) u).pts_count = count;
        } else if (u instanceof TL_update.TL_updateReadHistoryOutbox) {
            ((TL_update.TL_updateReadHistoryOutbox) u).pts = pts;
            ((TL_update.TL_updateReadHistoryOutbox) u).pts_count = count;
        } else if (u instanceof TL_update.TL_updateNewChannelMessage) {
            ((TL_update.TL_updateNewChannelMessage) u).pts = pts;
            ((TL_update.TL_updateNewChannelMessage) u).pts_count = count;
        } else if (u instanceof TL_update.TL_updateEditChannelMessage) {
            ((TL_update.TL_updateEditChannelMessage) u).pts = pts;
            ((TL_update.TL_updateEditChannelMessage) u).pts_count = count;
        } else if (u instanceof TL_update.TL_updateDeleteChannelMessages) {
            ((TL_update.TL_updateDeleteChannelMessages) u).pts = pts;
            ((TL_update.TL_updateDeleteChannelMessages) u).pts_count = count;
        } else if (u instanceof TL_update.TL_updatePinnedMessages) { // A7b
            ((TL_update.TL_updatePinnedMessages) u).pts = pts;
            ((TL_update.TL_updatePinnedMessages) u).pts_count = count;
        } else if (u instanceof TL_update.TL_updatePinnedChannelMessages) {
            ((TL_update.TL_updatePinnedChannelMessages) u).pts = pts;
            ((TL_update.TL_updatePinnedChannelMessages) u).pts_count = count;
        }
    }

    static int ptsOf(TLRPC.Update u) {
        if (u instanceof TL_update.TL_updateNewMessage) return ((TL_update.TL_updateNewMessage) u).pts;
        if (u instanceof TL_update.TL_updateEditMessage) return ((TL_update.TL_updateEditMessage) u).pts;
        if (u instanceof TL_update.TL_updateDeleteMessages) return ((TL_update.TL_updateDeleteMessages) u).pts;
        if (u instanceof TL_update.TL_updateReadHistoryInbox) return ((TL_update.TL_updateReadHistoryInbox) u).pts;
        if (u instanceof TL_update.TL_updateReadHistoryOutbox) return ((TL_update.TL_updateReadHistoryOutbox) u).pts;
        if (u instanceof TL_update.TL_updateNewChannelMessage) return ((TL_update.TL_updateNewChannelMessage) u).pts;
        if (u instanceof TL_update.TL_updateEditChannelMessage) return ((TL_update.TL_updateEditChannelMessage) u).pts;
        if (u instanceof TL_update.TL_updateDeleteChannelMessages) return ((TL_update.TL_updateDeleteChannelMessages) u).pts;
        if (u instanceof TL_update.TL_updatePinnedMessages) return ((TL_update.TL_updatePinnedMessages) u).pts;
        if (u instanceof TL_update.TL_updatePinnedChannelMessages) return ((TL_update.TL_updatePinnedChannelMessages) u).pts;
        return 0;
    }

    /**
     * Gives a pts-bearing update its pts (the next of its box) and logs it. {@code dmDialog} is the
     * private chat of an {@code updateDeleteMessages} (Relay message ids are per dialog).
     */
    void stamp(TLRPC.Update u, long dmDialog) {
        String box = box(u);
        if (box == null) {
            return;
        }
        int count = ptsCount(u);
        int pts = db().advancePts(box, count);
        setPts(u, pts, count);
        db().appendUpdateLog(box, pts, RelayStubs.now(account.account), encode(u, dmDialog));
        if (u instanceof TL_update.TL_updateDeleteMessages && dmDialog != 0) {
            RelayHooks.noteDmDelete(account.account, pts, dmDialog);
        }
    }

    private static byte[] encode(TLRPC.Update u, long dialog) {
        SerializedData out = new RelayTl.WireData();
        out.writeInt64(dialog);
        u.serializeToStream(out);
        byte[] bytes = out.toByteArray();
        out.cleanup();
        return bytes;
    }

    /** A logged update and its dialog. */
    static final class Decoded {
        final TLRPC.Update update;
        final long dialog;

        Decoded(TLRPC.Update update, long dialog) {
            this.update = update;
            this.dialog = dialog;
        }
    }

    private static Decoded decode(byte[] bytes) {
        SerializedData in = new SerializedData(bytes);
        try {
            long dialog = in.readInt64(true);
            TLRPC.Update update = TLRPC.Update.TLdeserialize(in, in.readInt32(true), true);
            return update != null ? new Decoded(update, dialog) : null;
        } catch (Throwable t) {
            RelayConfig.logError(TAG, "bad update log entry", t);
            return null;
        } finally {
            in.cleanup();
        }
    }

    // MARK: - Delivery

    /** Who an update batch references: Mattermost user ids and channels (megagroups). */
    static final class Refs {
        final Set<String> users = new LinkedHashSet<>();
        final Map<String, TLRPC.Chat> chats = new LinkedHashMap<>();

        Refs user(String mmUserId) {
            if (mmUserId != null && !mmUserId.isEmpty()) {
                users.add(mmUserId);
            }
            return this;
        }

        Refs users(Collection<String> mmUserIds) {
            for (String id : mmUserIds) {
                user(id);
            }
            return this;
        }

        Refs chat(TLRPC.Chat chat) {
            if (chat != null) {
                chats.put(Long.toString(chat.id), chat);
            }
            return this;
        }
    }

    /**
     * Stamps (pts + log) and delivers updates as one {@code TL_updates}, parsed back from the wire like a
     * server push. Call on the bridge executor.
     */
    void emit(List<TLRPC.Update> updates, Refs refs, long dmDialog) {
        if (updates.isEmpty()) {
            return;
        }
        for (TLRPC.Update u : updates) {
            stamp(u, dmDialog);
        }
        deliver(updates, refs);
    }

    /** Delivers already-stamped updates (or pts-less ones) as one {@code TL_updates}. */
    void deliver(List<TLRPC.Update> updates, Refs refs) {
        TLRPC.TL_updates batch = new TLRPC.TL_updates();
        batch.updates.addAll(updates);
        try {
            batch.users.addAll(path.users(refs.users));
        } catch (Exception e) {
            RelayConfig.logError(TAG, "users for updates", e);
        }
        batch.chats.addAll(refs.chats.values());
        batch.date = RelayStubs.now(account.account);
        batch.seq = 0;
        TLRPC.Updates parsed;
        byte[] bytes = RelayTl.serialize(batch);
        SerializedData in = new SerializedData(bytes);
        try {
            parsed = TLRPC.Updates.TLdeserialize(in, in.readInt32(true), true);
        } catch (Throwable t) {
            RelayConfig.logError(TAG, "updates do not parse", t);
            return;
        } finally {
            in.cleanup();
        }
        if (parsed == null) {
            return;
        }
        int accountNum = account.account;
        Utilities.stageQueue.postRunnable(() -> MessagesController.getInstance(accountNum).processUpdates(parsed, false));
    }

    // MARK: - Differences

    /** Users and chats referenced by updates/messages, for difference answers. */
    void collect(TLRPC.Update u, Refs refs) {
        TLRPC.Message message = null;
        if (u instanceof TL_update.TL_updateNewMessage) message = ((TL_update.TL_updateNewMessage) u).message;
        else if (u instanceof TL_update.TL_updateEditMessage) message = ((TL_update.TL_updateEditMessage) u).message;
        else if (u instanceof TL_update.TL_updateNewChannelMessage) message = ((TL_update.TL_updateNewChannelMessage) u).message;
        else if (u instanceof TL_update.TL_updateEditChannelMessage) message = ((TL_update.TL_updateEditChannelMessage) u).message;
        if (message != null) {
            collect(message, refs);
        }
        long channelId = channelOf(u);
        if (channelId != 0) {
            refs.chat(chatById(channelId));
        }
        if (u instanceof TL_update.TL_updateReadHistoryInbox) {
            TLRPC.Peer peer = ((TL_update.TL_updateReadHistoryInbox) u).peer;
            if (peer != null && peer.user_id != 0) {
                refs.user(db().mmId(RelayDatabase.KIND_USER, peer.user_id));
            }
        }
    }

    void collect(TLRPC.Message message, Refs refs) {
        if (message.from_id != null && message.from_id.user_id != 0) {
            refs.user(db().mmId(RelayDatabase.KIND_USER, message.from_id.user_id));
        }
        if (message.peer_id != null) {
            if (message.peer_id.user_id != 0) {
                refs.user(db().mmId(RelayDatabase.KIND_USER, message.peer_id.user_id));
            } else if (message.peer_id.channel_id != 0) {
                refs.chat(chatById(message.peer_id.channel_id));
            }
        }
    }

    TLRPC.Chat chatById(long channelTlId) {
        String mmId = db().mmId(RelayDatabase.KIND_CHANNEL, channelTlId);
        if (mmId == null) {
            return null;
        }
        try {
            Channel channel = path.directory.channel(mmId);
            return channel != null ? path.directory.tlChannel(channel) : null;
        } catch (Exception e) {
            RelayConfig.logError(TAG, "channel " + mmId, e);
            return null;
        }
    }

    /**
     * {@code updates.getDifference}: the common box from the log, plus {@code updateChannelTooLong} for
     * every megagroup whose box moved since {@code date} (the client then asks for that channel's
     * difference; it skips channels it is already current with). Null when the log does not cover the gap.
     */
    TLObject difference(int clientPts, int clientDate) {
        int commonPts = db().pts(RelayDatabase.BOX_COMMON);
        List<TLRPC.Update> channelHints = new ArrayList<>();
        Refs refs = new Refs();
        for (String box : db().channelBoxesSince(clientDate - 120)) {
            long channelId;
            try {
                channelId = Long.parseLong(box.substring(1));
            } catch (NumberFormatException e) {
                continue;
            }
            TLRPC.Chat chat = chatById(channelId);
            if (chat == null) {
                continue;
            }
            TL_update.TL_updateChannelTooLong hint = new TL_update.TL_updateChannelTooLong();
            hint.channel_id = channelId;
            hint.pts = db().pts(box);
            hint.flags |= TLObject.FLAG_0;
            channelHints.add(hint);
            refs.chat(chat);
        }
        if (clientPts > commonPts) {
            // The client is ahead (reinstalled bridge state): re-list.
            RelayConfig.log(TAG, "getDifference: client pts " + clientPts + " > bridge " + commonPts + ", too long");
            return tooLong(commonPts);
        }
        if (clientPts == commonPts && channelHints.isEmpty()) {
            TLRPC.TL_updates_differenceEmpty empty = new TLRPC.TL_updates_differenceEmpty();
            empty.date = RelayStubs.now(account.account);
            empty.seq = 0;
            return empty;
        }
        TLRPC.TL_updates_difference res = new TLRPC.TL_updates_difference();
        if (clientPts < commonPts) {
            List<RelayDatabase.LoggedUpdate> log = db().updateLogAfter(RelayDatabase.BOX_COMMON, clientPts, DIFFERENCE_LIMIT + 1);
            if (!covers(log, clientPts, commonPts)) {
                RelayConfig.log(TAG, "getDifference: log does not cover " + clientPts + ".." + commonPts + ", too long");
                return tooLong(commonPts);
            }
            int expected = clientPts;
            for (RelayDatabase.LoggedUpdate entry : log) {
                Decoded decoded = decode(entry.data);
                if (decoded == null || ptsOf(decoded.update) - ptsCount(decoded.update) != expected) {
                    RelayConfig.log(TAG, "getDifference: log gap after pts " + expected + ", too long");
                    return tooLong(commonPts);
                }
                TLRPC.Update u = decoded.update;
                expected = ptsOf(u);
                collect(u, refs);
                if (u instanceof TL_update.TL_updateNewMessage) {
                    res.new_messages.add(((TL_update.TL_updateNewMessage) u).message);
                } else {
                    if (u instanceof TL_update.TL_updateDeleteMessages && decoded.dialog != 0) {
                        RelayHooks.noteDmDelete(account.account, ((TL_update.TL_updateDeleteMessages) u).pts, decoded.dialog);
                    }
                    res.other_updates.add(u);
                }
            }
        }
        res.other_updates.addAll(channelHints);
        try {
            res.users.addAll(path.users(refs.users));
        } catch (Exception e) {
            RelayConfig.logError(TAG, "users for difference", e);
        }
        res.chats.addAll(refs.chats.values());
        res.state = bridge.currentState(account);
        RelayConfig.log(TAG, "getDifference " + clientPts + " -> " + commonPts + ": " + res.new_messages.size() + " messages, "
                + res.other_updates.size() + " other updates (" + channelHints.size() + " channel hints)");
        return res;
    }

    private static boolean covers(List<RelayDatabase.LoggedUpdate> log, int from, int to) {
        if (log.isEmpty() || log.size() > DIFFERENCE_LIMIT) {
            return false;
        }
        // Contiguity from `from` is checked entry by entry while decoding.
        return log.get(log.size() - 1).pts == to;
    }

    private TLObject tooLong(int commonPts) {
        TLRPC.TL_updates_differenceTooLong tooLong = new TLRPC.TL_updates_differenceTooLong();
        tooLong.pts = commonPts;
        return tooLong;
    }

    /** {@code updates.channelDifference} from a channel's log, or null when the log does not cover the gap. */
    TLRPC.TL_updates_channelDifference channelDifference(long channelTlId, int clientPts) {
        String box = RelayDatabase.channelBox(channelTlId);
        int channelPts = db().pts(box);
        List<RelayDatabase.LoggedUpdate> log = db().updateLogAfter(box, clientPts, DIFFERENCE_LIMIT + 1);
        if (!covers(log, clientPts, channelPts)) {
            return null;
        }
        TLRPC.TL_updates_channelDifference res = new TLRPC.TL_updates_channelDifference();
        Refs refs = new Refs();
        int expected = clientPts;
        for (RelayDatabase.LoggedUpdate entry : log) {
            Decoded decoded = decode(entry.data);
            if (decoded == null || ptsOf(decoded.update) - ptsCount(decoded.update) != expected) {
                return null;
            }
            TLRPC.Update u = decoded.update;
            expected = ptsOf(u);
            collect(u, refs);
            if (u instanceof TL_update.TL_updateNewChannelMessage) {
                res.new_messages.add(((TL_update.TL_updateNewChannelMessage) u).message);
            } else {
                res.other_updates.add(u);
            }
        }
        refs.chat(chatById(channelTlId));
        try {
            res.users.addAll(path.users(refs.users));
        } catch (Exception e) {
            RelayConfig.logError(TAG, "users for channel difference", e);
        }
        res.chats.addAll(refs.chats.values());
        res.pts = channelPts;
        res.isFinal = true;
        res.timeout = 30;
        res.flags |= TLObject.FLAG_1;
        RelayConfig.log(TAG, "getChannelDifference " + channelTlId + " " + clientPts + " -> " + channelPts + ": "
                + res.new_messages.size() + " messages, " + res.other_updates.size() + " other updates");
        return res;
    }

    void prune() {
        try {
            db().pruneUpdateLog(RelayStubs.now(account.account) - LOG_SECONDS, LOG_KEEP);
        } catch (Exception e) {
            RelayConfig.logError(TAG, "prune update log", e);
        }
    }
}
