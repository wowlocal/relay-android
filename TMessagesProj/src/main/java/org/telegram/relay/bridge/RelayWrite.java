package org.telegram.relay.bridge;

import android.text.TextUtils;
import android.util.Base64;

import com.google.gson.JsonObject;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.NotificationCenter;
import org.telegram.relay.RelayConfig;
import org.telegram.relay.account.RelayAccount;
import org.telegram.relay.account.RelayDatabase;
import org.telegram.relay.mattermost.FileDownload;
import org.telegram.relay.mattermost.FileUpload;
import org.telegram.relay.mattermost.MattermostException;
import org.telegram.relay.mattermost.ids.MessageIdEntry;
import org.telegram.relay.mattermost.ids.PostOrderKey;
import org.telegram.relay.mattermost.model.Channel;
import org.telegram.relay.mattermost.model.FileInfo;
import org.telegram.relay.mattermost.model.FileUploadResponse;
import org.telegram.relay.mattermost.model.Post;
import org.telegram.relay.mattermost.model.PostDraft;
import org.telegram.relay.mattermost.model.Reaction;
import org.telegram.relay.mattermost.model.Team;
import org.telegram.relay.mattermost.text.EmojiTable;
import org.telegram.relay.mattermost.text.EntitiesToMarkdown;
import org.telegram.relay.mattermost.text.TextEntity;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_update;
import org.telegram.ui.Components.Bulletin;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Phase A5, the write path (internals doc §7): send (text, media, albums, voice), reply (thread reply
 * with {@code root_id}), edit, delete, reactions, forward, mark unread.
 *
 * <ul>
 *   <li>Every write is a job in one FIFO per account, run on the bridge executor. A job that fails
 *       transiently (offline, 5xx, 429) stays at the head and is retried with backoff, or at once when
 *       the WebSocket reconnects / the network returns; later jobs wait behind it, so messages go out
 *       in order. Without a network nothing is tried (Telegram shows the clock).</li>
 *   <li>Posts carry {@code pending_post_id = <user id>:<random_id>}; the post id is remembered per
 *       {@code random_id} ({@code kv sent:<random_id>}), so a retried or re-sent message is never
 *       posted twice, and the WebSocket echo of a post being created is ignored.</li>
 *   <li>Responses are {@code TL_updates} with {@code updateMessageID(random_id → id)} and the new message
 *       stamped with the next pts of its box ({@link RelayUpdates#stamp}).</li>
 * </ul>
 */
final class RelayWrite {
    private static final String TAG = RelayConfig.TAG_BRIDGE;
    private static final WeakHashMap<RelayAccount, RelayWrite> instances = new WeakHashMap<>();

    static RelayWrite of(RelayBridge bridge, RelayAccount account) {
        synchronized (instances) {
            RelayWrite write = instances.get(account);
            if (write == null) {
                instances.put(account, write = new RelayWrite(bridge, account));
            }
            return write;
        }
    }

    final RelayBridge bridge;
    final RelayAccount account;
    final RelayReadPath path;
    final RelayLive live;

    private RelayWrite(RelayBridge bridge, RelayAccount account) {
        this.bridge = bridge;
        this.account = account;
        this.path = RelayReadPath.of(account);
        this.live = RelayLive.of(bridge, account);
        live.addOnlineListener(() -> bridge.post(this::drain));
        RelayUploads.prune(account.account);
    }

    // MARK: - Queue (bridge executor)

    interface Op {
        TLObject run(Job job) throws Exception;
    }

    static final class Job {
        final String label;
        final RelayBridge.Deferred deferred;
        final Op op;
        int attempt;

        Job(String label, RelayBridge.Deferred deferred, Op op) {
            this.label = label;
            this.deferred = deferred;
            this.op = op;
        }
    }

    private final ArrayDeque<Job> queue = new ArrayDeque<>();
    private long retryAt;

    /** Queues a write; the handler returns the result of this ({@link RelayBridge#DEFERRED}). */
    TLObject enqueue(String label, Op op) {
        queue.add(new Job(label, bridge.defer(), op));
        drain();
        return RelayBridge.DEFERRED;
    }

    private void drain() {
        while (!queue.isEmpty()) {
            Job job = queue.peek();
            if (job.deferred.isCancelled()) {
                queue.poll();
                RelayConfig.log(TAG, "write: " + job.label + " cancelled");
                continue;
            }
            if (!live.networkAvailable) {
                RelayConfig.log(TAG, "write: offline, " + queue.size() + " job(s) wait for the network");
                return;
            }
            long now = System.currentTimeMillis();
            if (retryAt > now && job.attempt > 0) {
                return;
            }
            TLObject response;
            long started = now;
            try {
                response = job.op.run(job);
            } catch (MattermostException e) {
                if (e.isTransient() || e.getKind() == MattermostException.Kind.TRANSPORT) {
                    job.attempt++;
                    long delay = Math.min(30_000, 1000L << Math.min(job.attempt, 5));
                    retryAt = System.currentTimeMillis() + delay;
                    RelayConfig.log(TAG, "write: " + job.label + " failed (" + e.getMessage() + "), retry " + job.attempt + " in " + delay + " ms");
                    bridge.postDelayed(this::drain, delay);
                    return;
                }
                RelayConfig.logError(TAG, "write: " + job.label + " rejected", e);
                if (e.isUnauthorized()) {
                    bridge.sessionRejected();
                }
                response = RelayTl.error(e.getStatusCode() >= 500 ? 500 : 400, e.getStatusCode() == 403 ? "CHAT_WRITE_FORBIDDEN" : "RELAY_REJECTED");
            } catch (Throwable t) {
                RelayConfig.logError(TAG, "write: " + job.label + " crashed", t);
                response = RelayTl.error(500, "RELAY_INTERNAL");
            }
            queue.poll();
            retryAt = 0;
            RelayConfig.log(TAG, "write: " + job.label + (response instanceof TLRPC.TL_error ? " failed: " + ((TLRPC.TL_error) response).text : " done")
                    + " in " + (System.currentTimeMillis() - started) + " ms" + (job.attempt > 0 ? " after " + job.attempt + " retries" : ""));
            job.deferred.complete(response);
        }
    }

    // MARK: - Helpers

    private Channel channelOf(TLRPC.InputPeer peer) throws MattermostException {
        String channelId = path.directory.channelIdOf(peer);
        return channelId != null ? path.directory.channel(channelId) : null;
    }

    private Channel channelOfDialog(long dialogId) throws MattermostException {
        String channelId = path.directory.channelIdOf(dialogId);
        return channelId != null ? path.directory.channel(channelId) : null;
    }

    private static TLObject peerInvalid() {
        return RelayTl.error(400, "PEER_ID_INVALID");
    }

    /** Telegram text + entities → Mattermost markdown (pass-through of what the user typed, as iOS). */
    String markdown(String text, List<TLRPC.MessageEntity> entities) {
        if (text == null) {
            return "";
        }
        List<TextEntity> list = new ArrayList<>();
        if (entities != null) {
            for (TLRPC.MessageEntity e : entities) {
                TextEntity t = textEntity(e);
                if (t != null) {
                    list.add(t);
                }
            }
        }
        EntitiesToMarkdown.Options options = new EntitiesToMarkdown.Options().setUsernameResolver(userId -> {
            org.telegram.relay.mattermost.model.User user = account.cachedUser(userId);
            return user != null ? user.username : null;
        });
        try {
            return EntitiesToMarkdown.convert(text, list, options);
        } catch (Throwable t) {
            RelayConfig.logError(TAG, "entities → markdown failed, sending plain text", t);
            return text;
        }
    }

    private TextEntity textEntity(TLRPC.MessageEntity e) {
        int o = e.offset, l = e.length;
        if (e instanceof TLRPC.TL_messageEntityBold) return TextEntity.of(TextEntity.Type.BOLD, o, l);
        if (e instanceof TLRPC.TL_messageEntityItalic) return TextEntity.of(TextEntity.Type.ITALIC, o, l);
        if (e instanceof TLRPC.TL_messageEntityStrike) return TextEntity.of(TextEntity.Type.STRIKETHROUGH, o, l);
        if (e instanceof TLRPC.TL_messageEntityUnderline) return TextEntity.of(TextEntity.Type.UNDERLINE, o, l);
        if (e instanceof TLRPC.TL_messageEntityCode) return TextEntity.of(TextEntity.Type.CODE, o, l);
        if (e instanceof TLRPC.TL_messageEntityPre) return TextEntity.pre(o, l, TextUtils.isEmpty(e.language) ? null : e.language);
        if (e instanceof TLRPC.TL_messageEntityTextUrl) return TextEntity.textUrl(o, l, e.url);
        if (e instanceof TLRPC.TL_messageEntityUrl) return TextEntity.of(TextEntity.Type.URL, o, l);
        if (e instanceof TLRPC.TL_messageEntityEmail) return TextEntity.of(TextEntity.Type.EMAIL, o, l);
        if (e instanceof TLRPC.TL_messageEntityBlockquote) return TextEntity.of(TextEntity.Type.BLOCKQUOTE, o, l);
        if (e instanceof TLRPC.TL_messageEntityHashtag) return TextEntity.of(TextEntity.Type.HASHTAG, o, l);
        if (e instanceof TLRPC.TL_messageEntityMentionName) {
            String mm = account.db().mmId(RelayDatabase.KIND_USER, ((TLRPC.TL_messageEntityMentionName) e).user_id);
            return mm != null ? TextEntity.mentionUser(o, l, mm) : null;
        }
        if (e instanceof TLRPC.TL_inputMessageEntityMentionName) {
            TLRPC.InputUser input = ((TLRPC.TL_inputMessageEntityMentionName) e).user_id;
            String mm = input != null ? account.db().mmId(RelayDatabase.KIND_USER, input.user_id) : null;
            return mm != null ? TextEntity.mentionUser(o, l, mm) : null;
        }
        return null; // mentions, cashtags, spoilers…: the text stays
    }

    /** The thread root a reply goes to (CRT, as iOS: replying to a reply continues its thread); "" for none. */
    private String rootFor(String channelId, TLRPC.InputReplyTo replyTo) throws MattermostException {
        if (!(replyTo instanceof TLRPC.TL_inputReplyToMessage)) {
            return "";
        }
        int id = replyTo.reply_to_msg_id != 0 ? replyTo.reply_to_msg_id : replyTo.top_msg_id;
        if (id == 0) {
            return "";
        }
        RelayHistory.Unit unit = path.history.unitAt(channelId, id);
        if (unit == null) {
            return "";
        }
        Post target = unit.layout.post;
        if (target.isSystemMessage()) {
            return "";
        }
        return target.isReply() ? target.rootId : target.id;
    }

    private String pendingPostId(long randomId) {
        return account.session.userId + ":" + Long.toHexString(randomId);
    }

    /**
     * {@code POST /posts}, at most once per {@code random_id}: a retry finds the post it created before
     * (kv {@code sent:<random_id>}; Mattermost also deduplicates {@code pending_post_id} for a while).
     */
    private Post createPost(String channelId, String message, String rootId, List<String> fileIds, JsonObject props, long randomId) throws MattermostException {
        String key = "sent:" + randomId;
        String existing = randomId != 0 ? account.db().kv(key) : null;
        if (existing != null) {
            try {
                return account.client.post(existing);
            } catch (MattermostException e) {
                if (!e.isNotFound()) {
                    throw e;
                }
            }
        }
        PostDraft draft = new PostDraft(channelId, message).rootId(rootId != null ? rootId : "").fileIds(fileIds != null ? fileIds : Collections.<String>emptyList());
        draft.pendingPostId(pendingPostId(randomId));
        draft.props = props;
        live.pendingPostIds.add(draft.pendingPostId);
        try {
            Post post = account.client.createPost(draft);
            if (randomId != 0) {
                account.db().putKv(key, post.id);
            }
            return post;
        } finally {
            live.pendingPostIds.remove(draft.pendingPostId);
        }
    }

    /**
     * The answer to a send: {@code updateMessageID(random_id → id)} + the new message of each part, stamped
     * with pts. {@code randomIdOfPart} gives each part's random id (0: none).
     */
    private TLRPC.TL_updates sent(Channel channel, Post post, long[] randomIdOfPart) throws Exception {
        path.history.rememberPost(post);
        RelayConvert.Layout layout = new RelayConvert.Layout(post);
        account.messageIds.insertPost(new PostOrderKey(post.createAt, post.id), layout.parts, channel.id, account.messageIdStore());
        List<RelayHistory.Unit> units = live.mappedUnits(channel.id, post);
        List<String> users = new ArrayList<>();
        ArrayList<TLRPC.Message> messages = path.messages(channel, units, users);
        TLRPC.TL_updates res = new TLRPC.TL_updates();
        for (int i = 0; i < messages.size(); i++) {
            TLRPC.Message message = messages.get(i);
            int part = units.get(i).part;
            long randomId = part < randomIdOfPart.length ? randomIdOfPart[part] : 0;
            if (randomId != 0) {
                TL_update.TL_updateMessageID id = new TL_update.TL_updateMessageID();
                id.id = message.id;
                id.random_id = randomId;
                res.updates.add(id);
            }
            TLRPC.Update u = RelayLive.newMessageUpdate(channel, message);
            live.updates.stamp(u, 0);
            res.updates.add(u);
        }
        fill(res, live.refs(channel, users));
        return res;
    }

    private void fill(TLRPC.TL_updates res, RelayUpdates.Refs refs) throws MattermostException {
        res.users.addAll(path.users(refs.users));
        res.chats.addAll(refs.chats.values());
        res.date = RelayStubs.now(account.account);
        res.seq = 0;
    }

    private static long[] single(long randomId) {
        return new long[]{randomId};
    }

    private void bulletin(String text) {
        AndroidUtilities.runOnUIThread(() -> NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.showBulletin, Bulletin.TYPE_ERROR, text));
    }

    // MARK: - Text

    TLObject sendMessage(TLRPC.TL_messages_sendMessage r) {
        return enqueue("sendMessage", job -> {
            Channel channel = channelOf(r.peer);
            if (channel == null) {
                return peerInvalid();
            }
            String root = rootFor(channel.id, r.reply_to);
            Post post = createPost(channel.id, markdown(r.message, r.entities), root, null, null, r.random_id);
            RelayConfig.log(TAG, "sent " + post.id + " to " + channel.name + (root.isEmpty() ? "" : " in thread " + root));
            return sent(channel, post, single(r.random_id));
        });
    }

    TLObject editMessage(TLRPC.TL_messages_editMessage r) {
        return enqueue("editMessage", job -> {
            Channel channel = channelOf(r.peer);
            if (channel == null) {
                return peerInvalid();
            }
            RelayHistory.Unit unit = path.history.unitAt(channel.id, r.id);
            if (unit == null) {
                return RelayTl.error(400, "MESSAGE_ID_INVALID");
            }
            if (r.media != null && !(r.media instanceof TLRPC.TL_inputMediaEmpty)) {
                return RelayTl.error(400, "MEDIA_EDIT_UNSUPPORTED");
            }
            if (r.message == null) {
                return RelayTl.error(400, "MESSAGE_NOT_MODIFIED");
            }
            JsonObject patch = new JsonObject();
            patch.addProperty("message", markdown(r.message, r.entities));
            Post post = account.client.patchPost(unit.layout.post.id, patch);
            path.history.rememberPost(post);
            List<RelayHistory.Unit> units = live.mappedUnits(channel.id, post);
            List<String> users = new ArrayList<>();
            TLRPC.TL_updates res = new TLRPC.TL_updates();
            for (TLRPC.Message message : path.messages(channel, units, users)) {
                TLRPC.Update u = RelayLive.editUpdate(channel, message);
                live.updates.stamp(u, 0);
                res.updates.add(u);
            }
            fill(res, live.refs(channel, users));
            return res;
        });
    }

    // MARK: - Delete

    TLObject deleteMessages(TLRPC.TL_messages_deleteMessages r) {
        long dialogId = RelayHooks.taggedDialog(r);
        return enqueue("deleteMessages", job -> {
            String channelId = dialogId != 0 ? path.directory.channelIdOf(dialogId) : null;
            Channel channel = channelId != null ? path.directory.channel(channelId) : null;
            if (channel == null) {
                // An untagged (peer-less) delete: nothing to do on the server.
                TLRPC.TL_messages_affectedMessages res = new TLRPC.TL_messages_affectedMessages();
                res.pts = account.db().pts(RelayDatabase.BOX_COMMON);
                res.pts_count = 0;
                return res;
            }
            return delete(channel, r.id);
        });
    }

    TLObject deleteChannelMessages(TLRPC.TL_channels_deleteMessages r) {
        return enqueue("channels.deleteMessages", job -> {
            String channelId = account.db().mmId(RelayDatabase.KIND_CHANNEL, r.channel.channel_id);
            Channel channel = channelId != null ? path.directory.channel(channelId) : null;
            if (channel == null) {
                return RelayTl.error(400, "CHANNEL_INVALID");
            }
            return delete(channel, r.id);
        });
    }

    /**
     * Mattermost delete (for everyone; Mattermost has no "delete for me"). A post with several files is
     * several messages: deleting one deletes the post, the other parts follow as a live delete. Posts the
     * user may not delete (403) come back as messages, with a notice.
     */
    private TLObject delete(Channel channel, List<Integer> ids) throws Exception {
        Map<String, List<Integer>> byPost = new LinkedHashMap<>();
        for (Integer id : ids) {
            MessageIdEntry entry = account.messageIdStore().entryByLocalId(channel.id, id);
            if (entry == null || entry.isDeleted) {
                continue;
            }
            String postId = entry.serverPostId();
            List<Integer> list = byPost.get(postId);
            if (list == null) {
                byPost.put(postId, list = new ArrayList<>());
            }
            list.add(id);
        }
        List<String> deleted = new ArrayList<>();
        List<String> forbidden = new ArrayList<>();
        for (String postId : byPost.keySet()) {
            try {
                account.client.deletePost(postId);
                deleted.add(postId);
            } catch (MattermostException e) {
                if (e.isNotFound()) {
                    deleted.add(postId);
                } else if (e.getStatusCode() == 403) {
                    forbidden.add(postId);
                } else {
                    throw e;
                }
            }
        }
        List<MessageIdEntry> entries = account.messageIds.markDeleted(deleted, channel.id, account.messageIdStore());
        Set<Integer> requested = new LinkedHashSet<>(ids);
        ArrayList<Integer> answered = new ArrayList<>();
        ArrayList<Integer> extra = new ArrayList<>();
        for (MessageIdEntry entry : entries) {
            (requested.contains(entry.localId) ? answered : extra).add(entry.localId);
        }
        TLRPC.TL_messages_affectedMessages res = new TLRPC.TL_messages_affectedMessages();
        if (!answered.isEmpty()) {
            TLRPC.Update u = live.deleteUpdate(channel, answered);
            live.updates.stamp(u, channel.isDirect() ? path.directory.dialogId(channel) : 0);
            res.pts = RelayUpdates.ptsOf(u);
            res.pts_count = answered.size();
        } else {
            res.pts = account.db().pts(channel.isDirect() ? RelayDatabase.BOX_COMMON : RelayDatabase.channelBox(-path.directory.dialogId(channel)));
            res.pts_count = 0;
        }
        if (!extra.isEmpty()) {
            ArrayList<TLRPC.Update> list = new ArrayList<>();
            list.add(live.deleteUpdate(channel, extra));
            live.updates.emit(list, live.refs(channel, Collections.<String>emptyList()), channel.isDirect() ? path.directory.dialogId(channel) : 0);
        }
        // Thread roots of deleted replies: their counters drop.
        for (String postId : deleted) {
            Post post = path.history.cachedPost(postId);
            if (post != null && post.isReply()) {
                live.refreshRoot(channel, post.rootId);
            }
        }
        if (!forbidden.isEmpty()) {
            for (String postId : forbidden) {
                Post post = path.history.post(postId);
                if (post != null) {
                    List<RelayHistory.Unit> units = live.mappedUnits(channel.id, post);
                    List<String> users = new ArrayList<>();
                    ArrayList<TLRPC.Update> list = new ArrayList<>();
                    for (TLRPC.Message message : path.messages(channel, units, users)) {
                        list.add(RelayLive.newMessageUpdate(channel, message));
                    }
                    live.updates.emit(list, live.refs(channel, users), 0);
                }
            }
            bulletin("You can't delete this message.");
        }
        RelayConfig.log(TAG, "deleted " + deleted.size() + " post(s) in " + channel.name + (forbidden.isEmpty() ? "" : ", " + forbidden.size() + " forbidden"));
        return res;
    }

    // MARK: - Reactions

    TLObject sendReaction(TLRPC.TL_messages_sendReaction r) {
        return enqueue("sendReaction", job -> {
            Channel channel = channelOf(r.peer);
            if (channel == null) {
                return peerInvalid();
            }
            RelayHistory.Unit unit = path.history.unitAt(channel.id, r.msg_id);
            if (unit == null) {
                return RelayTl.error(400, "MESSAGE_ID_INVALID");
            }
            String postId = unit.layout.post.id;
            Set<String> wanted = new LinkedHashSet<>();
            for (TLRPC.Reaction reaction : r.reaction) {
                String name = RelayReactions.emojiName(account, reaction); // A7c: custom emoji documents too
                if (name != null) {
                    wanted.add(name);
                }
            }
            Set<String> mine = new LinkedHashSet<>();
            for (Reaction reaction : account.client.reactions(postId)) {
                if (account.session.userId.equals(reaction.userId)) {
                    mine.add(reaction.emojiName);
                }
            }
            for (String name : mine) {
                if (!wanted.contains(name)) {
                    account.client.removeReaction(postId, name);
                }
            }
            java.util.List<String> added = new java.util.ArrayList<>();
            for (String name : wanted) {
                if (!mine.contains(name)) {
                    account.client.addReaction(account.session.userId, postId, name);
                    added.add(name);
                }
            }
            RelayReactions.noteUsed(account, added); // A7c: Mattermost's recent emoji
            Post post = account.client.post(postId);
            path.history.rememberPost(post);
            int part0 = path.history.localIdOf(channel.id, postId);
            TLRPC.TL_updates res = new TLRPC.TL_updates();
            res.updates.add(live.reactionsUpdate(channel, post, part0 != 0 ? part0 : r.msg_id));
            fill(res, live.refs(channel, Collections.<String>emptyList()));
            RelayConfig.log(TAG, "reactions on " + postId + ": " + wanted);
            return res;
        });
    }

    /** A Telegram reaction → Mattermost emoji name ({@code :name:} for custom emoji), or null. */
    static String emojiName(TLRPC.Reaction reaction) {
        if (!(reaction instanceof TLRPC.TL_reactionEmoji)) {
            return null;
        }
        String emoticon = ((TLRPC.TL_reactionEmoji) reaction).emoticon;
        if (emoticon == null || emoticon.isEmpty()) {
            return null;
        }
        if (emoticon.length() > 2 && emoticon.startsWith(":") && emoticon.endsWith(":")) {
            return emoticon.substring(1, emoticon.length() - 1);
        }
        String name = EmojiTable.name(emoticon);
        if (name == null) {
            name = EmojiTable.name(emoticon.replace("️", ""));
        }
        return name;
    }

    // MARK: - Media

    /** Max file size from the server's client config ({@code MaxFileSize}), 0 when unknown. */
    private volatile long maxFileSize = -1;

    private long maxFileSize() {
        if (maxFileSize < 0) {
            try {
                String value = account.client.clientConfig().get("MaxFileSize");
                maxFileSize = value != null ? Long.parseLong(value.trim()) : 0;
            } catch (Exception e) {
                return 0;
            }
        }
        return maxFileSize;
    }

    /** What to upload for an InputMedia: file, name, mime type, and post props (voice marker). */
    private static final class Upload {
        File file;
        long uploadId;
        String name;
        String mime;
        JsonObject props;
        int[] video; // w, h, duration
        String existingFileId;
    }

    private Upload prepare(TLRPC.InputMedia media) throws Exception {
        Upload upload = new Upload();
        if (media instanceof TLRPC.TL_inputMediaUploadedPhoto) {
            upload.file = RelayUploads.assemble(account.account, media.file);
            upload.uploadId = media.file.id;
            upload.name = "Photo " + stamp() + ".jpg";
            upload.mime = "image/jpeg";
        } else if (media instanceof TLRPC.TL_inputMediaUploadedDocument) {
            upload.file = RelayUploads.assemble(account.account, media.file);
            upload.uploadId = media.file.id;
            upload.mime = TextUtils.isEmpty(media.mime_type) ? "application/octet-stream" : media.mime_type;
            String name = null;
            TLRPC.TL_documentAttributeAudio voice = null;
            for (TLRPC.DocumentAttribute attribute : media.attributes) {
                if (attribute instanceof TLRPC.TL_documentAttributeFilename && !TextUtils.isEmpty(attribute.file_name)) {
                    name = attribute.file_name;
                } else if (attribute instanceof TLRPC.TL_documentAttributeAudio && attribute.voice) {
                    voice = (TLRPC.TL_documentAttributeAudio) attribute;
                } else if (attribute instanceof TLRPC.TL_documentAttributeVideo && !attribute.round_message) {
                    upload.video = new int[]{attribute.w, attribute.h, (int) Math.round(attribute.duration)};
                }
            }
            if (voice != null) {
                // Relay voice message (iOS wire format): one audio file, no text, props.relay_voice.
                String ext = upload.mime.contains("ogg") || upload.mime.contains("opus") ? "ogg" : "m4a";
                upload.name = "Voice message " + stamp() + "." + ext;
                JsonObject marker = new JsonObject();
                marker.addProperty("v", 1);
                marker.addProperty("duration", (int) Math.round(voice.duration));
                if (voice.waveform != null && voice.waveform.length > 0) {
                    marker.addProperty("waveform", Base64.encodeToString(voice.waveform, Base64.NO_WRAP));
                }
                upload.props = new JsonObject();
                upload.props.add("relay_voice", marker);
            } else {
                upload.name = name != null ? name : (media.file.name != null && !media.file.name.isEmpty() ? media.file.name : "file");
            }
        } else if (media instanceof TLRPC.TL_inputMediaPhoto && ((TLRPC.TL_inputMediaPhoto) media).id != null) {
            upload.existingFileId = account.db().mmId(RelayDatabase.KIND_FILE, ((TLRPC.TL_inputMediaPhoto) media).id.id);
        } else if (media instanceof TLRPC.TL_inputMediaDocument && ((TLRPC.TL_inputMediaDocument) media).id != null) {
            upload.existingFileId = account.db().mmId(RelayDatabase.KIND_FILE, ((TLRPC.TL_inputMediaDocument) media).id.id);
        } else {
            return null;
        }
        return upload;
    }

    private static String stamp() {
        return new SimpleDateFormat("yyyy-MM-dd HH.mm.ss", Locale.US).format(new Date());
    }

    /** Uploads a prepared file to the channel ({@code POST /files}); null when it is too big (notice shown). */
    private FileInfo upload(String channelId, Upload upload, Job job) throws Exception {
        if (upload.existingFileId != null) {
            // A file this app just uploaded (album item, uploadMedia) is used as it is; a file that already
            // belongs to a post (Telegram reuses the photo of an earlier send, a copy-forward) is re-uploaded:
            // Mattermost attaches a file to one post only and silently drops it otherwise.
            FileInfo info = account.client.fileInfo(upload.existingFileId);
            if ((info.postId == null || info.postId.isEmpty()) && channelId.equals(info.channelId)) {
                return info;
            }
            return copyFile(channelId, upload.existingFileId, job);
        }
        if (upload.file == null) {
            throw new IllegalStateException("upload parts missing");
        }
        long max = maxFileSize();
        if (max > 0 && upload.file.length() > max) {
            bulletin(String.format(Locale.US, "The file is too large (the server allows %d MB).", max / (1024 * 1024)));
            RelayUploads.discard(account.account, upload.uploadId);
            return null;
        }
        String md5 = RelayUploads.md5(upload.file);
        long started = System.currentTimeMillis();
        final long[] lastLog = {0, 0};
        FileUploadResponse response = account.client.uploadFile(channelId, FileUpload.ofFile(upload.name, upload.mime, upload.file), (sent, total) -> {
            if (job.deferred.isCancelled()) {
                throw new RuntimeException("upload cancelled");
            }
            long now = System.currentTimeMillis();
            if (now - lastLog[0] > 500) {
                lastLog[0] = now;
                RelayConfig.log(TAG, "upload " + upload.name + ": " + sent + "/" + total);
            }
            if (now - lastLog[1] > 100 || sent >= total) {
                lastLog[1] = now;
                RelayUploadProgress.serverProgress(account.account, upload.uploadId, sent, total); // A7c: bubble progress
            }
        });
        if (response.fileInfos.isEmpty()) {
            throw new IllegalStateException("no file info");
        }
        FileInfo info = response.fileInfos.get(0);
        RelayConfig.log(TAG, "uploaded " + upload.name + " (" + info.size + " bytes, md5 " + md5 + ") as " + info.id + " in " + (System.currentTimeMillis() - started) + " ms");
        if (upload.video != null) {
            account.db().putKv("video:" + info.id, upload.video[0] + "," + upload.video[1] + "," + upload.video[2]);
        }
        RelayUploads.discard(account.account, upload.uploadId);
        return info;
    }

    /** A file of another post, re-uploaded into {@code channelId} (Mattermost attaches a file to one post only). */
    private FileInfo copyFile(String channelId, String fileId, Job job) throws Exception {
        FileInfo source = account.client.fileInfo(fileId);
        File tmp = new File(RelayUploads.root(account.account), "copy-" + fileId);
        try (FileDownload download = account.client.downloadFile(fileId); OutputStream out = new FileOutputStream(tmp)) {
            InputStream in = download.getInputStream();
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        }
        try {
            FileUploadResponse response = account.client.uploadFile(channelId, FileUpload.ofFile(source.name, source.mimeType, tmp), null);
            return response.fileInfos.get(0);
        } finally {
            tmp.delete();
        }
    }

    TLObject sendMedia(TLRPC.TL_messages_sendMedia r) {
        return enqueue("sendMedia", job -> {
            Channel channel = channelOf(r.peer);
            if (channel == null) {
                return peerInvalid();
            }
            String existing = account.db().kv("sent:" + r.random_id);
            Upload upload = prepare(r.media);
            if (upload == null) {
                return RelayTl.error(400, "MEDIA_INVALID");
            }
            String root = rootFor(channel.id, r.reply_to);
            List<String> fileIds = new ArrayList<>();
            if (existing == null) {
                FileInfo info = upload(channel.id, upload, job);
                if (info == null) {
                    return RelayTl.error(400, "MEDIA_INVALID");
                }
                fileIds.add(info.id);
            }
            Post post = createPost(channel.id, markdown(r.message, r.entities), root, fileIds, upload.props, r.random_id);
            RelayConfig.log(TAG, "sent media post " + post.id + " to " + channel.name);
            return sent(channel, post, single(r.random_id));
        });
    }

    /** Album item: the file goes to Mattermost now; {@code sendMultiMedia} then posts all of them as one post. */
    TLObject uploadMedia(TLRPC.TL_messages_uploadMedia r) {
        return enqueue("uploadMedia", job -> {
            Channel channel = channelOf(r.peer);
            if (channel == null) {
                return peerInvalid();
            }
            Upload upload = prepare(r.media);
            if (upload == null) {
                return RelayTl.error(400, "MEDIA_INVALID");
            }
            FileInfo info = upload(channel.id, upload, job);
            if (info == null) {
                return RelayTl.error(400, "MEDIA_INVALID");
            }
            return path.files.media(info, RelayStubs.now(account.account));
        });
    }

    TLObject sendMultiMedia(TLRPC.TL_messages_sendMultiMedia r) {
        return enqueue("sendMultiMedia", job -> {
            Channel channel = channelOf(r.peer);
            if (channel == null) {
                return peerInvalid();
            }
            List<String> fileIds = new ArrayList<>();
            Map<String, Long> randomByFile = new HashMap<>();
            List<String> captions = new ArrayList<>();
            for (TLRPC.TL_inputSingleMedia single : r.multi_media) {
                Upload upload = prepare(single.media);
                String fileId = null;
                if (upload != null) {
                    FileInfo info = upload(channel.id, upload, job);
                    fileId = info != null ? info.id : null;
                }
                if (fileId == null) {
                    continue;
                }
                fileIds.add(fileId);
                randomByFile.put(fileId, single.random_id);
                if (!TextUtils.isEmpty(single.message)) {
                    captions.add(markdown(single.message, single.entities));
                }
            }
            if (fileIds.isEmpty()) {
                return RelayTl.error(400, "MEDIA_INVALID");
            }
            String root = rootFor(channel.id, r.reply_to);
            long key = r.multi_media.get(0).random_id;
            Post post = createPost(channel.id, TextUtils.join("\n", captions), root, fileIds.subList(0, Math.min(10, fileIds.size())), null, key);
            RelayConvert.Layout layout = new RelayConvert.Layout(post);
            long[] randoms = new long[layout.parts];
            for (int part = 0; part < layout.parts; part++) {
                FileInfo file = layout.file(part);
                Long random = file != null ? randomByFile.get(file.id) : null;
                randoms[part] = random != null ? random : 0;
            }
            RelayConfig.log(TAG, "sent album post " + post.id + " (" + fileIds.size() + " files) to " + channel.name);
            return sent(channel, post, randoms);
        });
    }

    // MARK: - Forward

    /**
     * Forward as Mattermost does (web app {@code forwardPost}): a new post whose text is the forwarded
     * post's permalink (the server adds a preview). "Hide sender name" ({@code drop_author}) copies the
     * text and files instead.
     */
    TLObject forwardMessages(TLRPC.TL_messages_forwardMessages r) {
        long taggedFrom = RelayHooks.taggedDialog(r);
        return enqueue("forwardMessages", job -> {
            // Private-chat sources come as inputPeerEmpty (Telegram's account-wide ids): the caller tags the dialog.
            Channel from = r.from_peer instanceof TLRPC.TL_inputPeerEmpty && taggedFrom != 0
                    ? channelOfDialog(taggedFrom) : channelOf(r.from_peer);
            Channel to = channelOf(r.to_peer);
            if (from == null || to == null) {
                RelayConfig.log(TAG, "forward: unknown chat from=" + r.from_peer + " to=" + r.to_peer);
                return peerInvalid();
            }
            String root = rootFor(to.id, r.reply_to);
            TLRPC.TL_updates res = new TLRPC.TL_updates();
            List<String> users = new ArrayList<>();
            Set<String> done = new LinkedHashSet<>();
            for (int i = 0; i < r.id.size(); i++) {
                RelayHistory.Unit unit = path.history.unitAt(from.id, r.id.get(i));
                long randomId = i < r.random_id.size() ? r.random_id.get(i) : 0;
                if (unit == null || !done.add(unit.layout.post.id)) {
                    continue;
                }
                Post source = unit.layout.post;
                Post post;
                if (r.drop_author) {
                    List<String> fileIds = new ArrayList<>();
                    for (FileInfo file : unit.layout.files) {
                        fileIds.add(copyFile(to.id, file.id, job).id);
                    }
                    post = createPost(to.id, source.message, root, fileIds, null, randomId);
                } else {
                    post = createPost(to.id, permalink(from, source), root, null, null, randomId);
                }
                TLRPC.TL_updates one = sent(to, post, new long[]{randomId});
                res.updates.addAll(one.updates);
                for (TLRPC.User user : one.users) {
                    String mm = account.db().mmId(RelayDatabase.KIND_USER, user.id);
                    if (mm != null) {
                        users.add(mm);
                    }
                }
            }
            fill(res, live.refs(to, users));
            return res;
        });
    }

    private String permalink(Channel channel, Post post) throws MattermostException {
        String teamName = null;
        RelayDirectory.Snapshot snapshot = path.directory.any();
        for (Team team : snapshot.teams) {
            if (team.id.equals(channel.teamId)) {
                teamName = team.name;
            }
        }
        if (teamName == null && !snapshot.teams.isEmpty()) {
            teamName = snapshot.teams.get(0).name;
        }
        String server = account.session.serverUrl;
        while (server.endsWith("/")) {
            server = server.substring(0, server.length() - 1);
        }
        return server + "/" + (teamName != null ? teamName : "_redirect") + "/pl/" + post.id;
    }

    // MARK: - Mark unread

    TLObject markDialogUnread(TLRPC.TL_messages_markDialogUnread r) {
        return enqueue("markDialogUnread", job -> {
            TLRPC.InputPeer peer = r.peer instanceof TLRPC.TL_inputDialogPeer ? ((TLRPC.TL_inputDialogPeer) r.peer).peer : null;
            Channel channel = peer != null ? channelOf(peer) : null;
            if (channel == null) {
                return new TLRPC.TL_boolTrue();
            }
            if (r.unread) {
                int top = live.topLocalId(channel.id);
                RelayHistory.Unit unit = top != 0 ? path.history.unitAt(channel.id, top) : null;
                if (unit != null) {
                    account.client.markPostUnread(unit.layout.post.id, path.directory.any().collapsedThreads);
                }
            } else {
                account.client.viewChannel(channel.id);
            }
            path.directory.invalidate();
            return new TLRPC.TL_boolTrue();
        });
    }
}
