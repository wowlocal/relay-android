package org.telegram.relay.bridge;

import org.telegram.relay.RelayConfig;
import org.telegram.relay.account.RelayAccount;
import org.telegram.relay.mattermost.MattermostException;
import org.telegram.relay.mattermost.ids.IngestResult;
import org.telegram.relay.mattermost.ids.MessageIdEntry;
import org.telegram.relay.mattermost.ids.PostOrderKey;
import org.telegram.relay.mattermost.ids.PostParts;
import org.telegram.relay.mattermost.ids.RenumberMove;
import org.telegram.relay.mattermost.ids.RenumberPlan;
import org.telegram.relay.mattermost.model.Channel;
import org.telegram.relay.mattermost.model.ChannelMember;
import org.telegram.relay.mattermost.model.Post;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * Polish 1: an id renumber ({@code MessageIdAllocator}'s {@link RenumberPlan}) migrated into the client. When a
 * channel's id space runs out between two mapped posts, the allocator moves a window of existing posts to new local
 * ids. The client still has those messages at their old ids (storage, the open chat, the dialog's top message), so
 * the bridge tells it, through the normal update path (pts-stamped and logged, so a catch-up replays it too):
 *
 * <ol>
 *   <li>delete every message at a {@code from} id ({@code updateDeleteChannelMessages}; DMs peer-aware), in one batch
 *       first, since ids may be swapped between moved posts;</li>
 *   <li>re-insert each moved post part at its {@code to} id ({@code updateNewChannelMessage} / {@code updateNewMessage}),
 *       marked in {@link RelayHooks#isRenumbered} so it doesn't notify;</li>
 *   <li>re-send the read state when the chat is read on the server (re-inserted ids above the client's old read mark
 *       would otherwise count as unread).</li>
 * </ol>
 *
 * A chat the client has no dialog for has nothing to migrate. Runs on the bridge executor, right after the ingest
 * that renumbered (the id map already holds the new ids).
 */
final class RelayRenumber {
    private static final String TAG = RelayConfig.TAG_BRIDGE;
    private static final Pattern POST_ID = Pattern.compile("^[a-z0-9]{26}$");

    /** Renumbers migrated into the client (debug / tests). */
    static final AtomicInteger migrated = new AtomicInteger();

    private RelayRenumber() {
    }

    /** Called for every ingest result; does nothing without a plan. */
    static void onResult(RelayAccount account, String channelId, IngestResult result, String what) {
        if (result == null || result.renumberPlan == null || result.renumberPlan.moves.isEmpty()) {
            return;
        }
        RelayConfig.log(TAG, "RENUMBER in " + channelId + " (" + what + "): " + result.renumberPlan + " — migrating client messages");
        RelayLive live = RelayLive.peek(account);
        if (live == null) {
            RelayConfig.log(TAG, "RENUMBER: no live path, the client reloads the chat when it next asks");
            return;
        }
        try {
            apply(live, channelId, result.renumberPlan);
        } catch (Throwable t) {
            RelayConfig.logError(TAG, "RENUMBER migration in " + channelId + " failed", t);
        }
    }

    static int apply(RelayLive live, String channelId, RenumberPlan plan) throws Exception {
        RelayAccount account = live.account;
        RelayReadPath path = live.path;
        Channel channel = path.directory.channel(channelId);
        if (channel == null || !channel.isDirect() && !path.isKnown(channel.id)) {
            return 0;
        }
        long dialogId = path.directory.dialogId(channel);

        // 1. Old ids out (one batch: a later batch may reuse them).
        ArrayList<Integer> fromIds = new ArrayList<>();
        Map<String, Integer> newIdOfPart = new LinkedHashMap<>();
        for (RenumberMove move : plan.moves) {
            fromIds.add(move.from);
            newIdOfPart.put(move.postId, move.to);
        }
        ArrayList<TLRPC.Update> deletes = new ArrayList<>();
        deletes.add(live.deleteUpdate(channel, fromIds));
        live.updates.emit(deletes, live.refs(channel, Collections.<String>emptyList()), channel.isDirect() ? dialogId : 0);

        // 2. Moved posts back in at their new ids.
        Set<String> postIds = new HashSet<>();
        for (String partId : newIdOfPart.keySet()) {
            String postId = PostParts.postIdOfPartId(partId);
            if (POST_ID.matcher(postId).matches()) {
                postIds.add(postId);
            }
        }
        List<RelayHistory.Unit> units = new ArrayList<>();
        for (String postId : postIds) {
            Post post;
            try {
                post = path.history.post(postId);
            } catch (MattermostException e) {
                RelayConfig.logError(TAG, "RENUMBER: post " + postId, e);
                continue;
            }
            if (post == null || post.isDeleted()) {
                continue;
            }
            for (RelayHistory.Unit unit : live.mappedUnits(channel.id, post)) {
                Integer to = newIdOfPart.get(unit.partId());
                if (to != null && to == unit.localId) {
                    units.add(unit);
                }
            }
        }
        Collections.sort(units, (a, b) -> Integer.compare(a.localId, b.localId));
        int reinserted = 0;
        if (!units.isEmpty()) {
            List<String> users = new ArrayList<>();
            ArrayList<TLRPC.Message> messages = path.messages(channel, units, users);
            ArrayList<TLRPC.Update> list = new ArrayList<>();
            for (TLRPC.Message message : messages) {
                RelayHooks.noteRenumbered(account.account, dialogId, message.id);
                list.add(RelayLive.newMessageUpdate(channel, message));
            }
            live.updates.emit(list, live.refs(channel, users), 0);
            reinserted = messages.size();
        }

        // 3. Read state: re-inserted ids may sit above the client's old read mark.
        try {
            ChannelMember member = account.client.channelMember(channel.id);
            Channel fresh = account.client.channel(channel.id);
            if (member != null && fresh != null && member.lastViewedAt >= fresh.lastPostAt) {
                live.emitRead(channel);
            }
        } catch (MattermostException e) {
            RelayConfig.logError(TAG, "RENUMBER: read state of " + channel.id, e);
        }
        migrated.incrementAndGet();
        RelayConfig.log(TAG, "RENUMBER migrated in " + channel.id + ": " + fromIds.size() + " old ids deleted, " + reinserted + " messages re-inserted");
        return reinserted;
    }

    // MARK: - Debug (polish 1 e2e): a forced renumber

    /**
     * Forces a renumber in a chat: late-inserts placeholder keys between its two newest posts (each halves the free
     * gap) until the allocator has to renumber, then tombstones the placeholders (they never reach the client) and
     * migrates the plan like a real one. Returns a summary line. Debug builds call it from {@code DEBUG_POLISH1}.
     */
    static String debugForce(RelayLive live, String channelId) throws Exception {
        RelayAccount account = live.account;
        List<MessageIdEntry> newest = new ArrayList<>();
        for (MessageIdEntry entry : account.messageIdStore().entriesFromLocalId(channelId, Integer.MAX_VALUE, false, 64)) {
            if (!entry.isDeleted) {
                newest.add(entry);
            }
        }
        MessageIdEntry lower = null;
        MessageIdEntry upper = null;
        for (int i = 0; i + 1 < newest.size(); i++) {
            if (newest.get(i).createAt() > newest.get(i + 1).createAt()) {
                upper = newest.get(i);
                lower = newest.get(i + 1);
                break;
            }
        }
        if (lower == null) {
            return "no two mapped posts";
        }
        List<String> placeholders = new ArrayList<>();
        RenumberPlan plan = null;
        for (int i = 0; i < 40 && plan == null; i++) {
            // Sorts after `lower` and its parts ('z' > '$'), before the previous placeholder, before `upper`.
            String id = lower.postId() + "z" + (9999 - i);
            placeholders.add(id);
            IngestResult result = account.messageIds.insertPost(new PostOrderKey(lower.createAt(), id), 1, channelId, account.messageIdStore());
            plan = result.renumberPlan;
        }
        account.messageIds.markDeleted(placeholders, channelId, account.messageIdStore());
        if (plan == null) {
            return "no renumber after " + placeholders.size() + " inserts";
        }
        int moved = 0;
        for (RenumberMove move : plan.moves) {
            if (POST_ID.matcher(PostParts.postIdOfPartId(move.postId)).matches()) {
                moved++;
            }
        }
        RelayConfig.log(TAG, "RENUMBER (debug) in " + channelId + " after " + placeholders.size() + " inserts: " + plan);
        int reinserted = apply(live, channelId, plan);
        return "inserts=" + placeholders.size() + " moves=" + plan.moves.size() + " real=" + moved + " reinserted=" + reinserted;
    }
}
