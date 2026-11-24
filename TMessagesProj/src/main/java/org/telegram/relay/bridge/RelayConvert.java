package org.telegram.relay.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import org.telegram.relay.RelayConfig;
import org.telegram.relay.account.RelayAccount;
import org.telegram.relay.mattermost.model.FileInfo;
import org.telegram.relay.mattermost.model.Post;
import org.telegram.relay.mattermost.model.Reaction;
import org.telegram.relay.mattermost.text.EmojiTable;
import org.telegram.relay.mattermost.text.FormattedText;
import org.telegram.relay.mattermost.text.MarkdownToEntities;
import org.telegram.relay.mattermost.text.TextEntity;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Mattermost posts → {@code TL_message} / {@code TL_messageService} (PLAN "Domain mapping", iOS
 * {@code PostConverter}).
 *
 * <ul>
 *   <li>A post with N files is N messages ({@link Layout}): images first (one album, {@code grouped_id}),
 *       then the other files (a second group); the text goes on the last file, or on the first image of
 *       an images-only post. Reactions, the reply counter and the pinned flag sit on part 0.</li>
 *   <li>Text: Mattermost markdown → plain text + entities ({@code org.telegram.relay.mattermost.text});
 *       plain text when the converter fails.</li>
 *   <li>System posts ({@code system_*}) → service messages with the server's text
 *       ({@code messageActionCustomAction}).</li>
 *   <li>Webhook/bot posts with {@code override_username} are sent by a synthetic user of that name.</li>
 * </ul>
 */
final class RelayConvert {
    private static final String TAG = RelayConfig.TAG_BRIDGE;

    /** How a post is split into messages. */
    static final class Layout {
        final Post post;
        /** Files in part order (images first); empty for text-only posts. */
        final List<FileInfo> files;
        final int imageCount;
        final int parts;
        final int textPart;
        final boolean service;

        Layout(Post post) {
            this.post = post;
            List<FileInfo> images = new ArrayList<>();
            List<FileInfo> others = new ArrayList<>();
            service = post.isSystemMessage();
            if (!service) {
                for (FileInfo file : orderedFiles(post)) {
                    if (RelayFiles.isPhoto(file)) {
                        images.add(file);
                    } else {
                        others.add(file);
                    }
                }
            }
            files = new ArrayList<>(images);
            files.addAll(others);
            imageCount = images.size();
            parts = Math.max(1, Math.min(files.size(), 100));
            textPart = others.isEmpty() ? 0 : parts - 1;
        }

        FileInfo file(int part) {
            return part < files.size() ? files.get(part) : null;
        }

        long groupedId(int part) {
            boolean image = part < imageCount;
            int groupSize = image ? imageCount : files.size() - imageCount;
            if (groupSize < 2) {
                return 0;
            }
            long hash = org.telegram.relay.account.RelayUsers.accessHash(post.id + (image ? "#img" : "#file"));
            return hash == 0 ? 1 : hash & Long.MAX_VALUE;
        }
    }

    /** {@code metadata.files} in {@code file_ids} order. */
    private static List<FileInfo> orderedFiles(Post post) {
        List<FileInfo> files = post.getFiles();
        if (post.fileIds == null || post.fileIds.size() < 2 || files.size() < 2) {
            return files;
        }
        Map<String, FileInfo> byId = new LinkedHashMap<>();
        for (FileInfo file : files) {
            byId.put(file.id, file);
        }
        List<FileInfo> ordered = new ArrayList<>();
        for (String id : post.fileIds) {
            FileInfo file = byId.remove(id);
            if (file != null) {
                ordered.add(file);
            }
        }
        ordered.addAll(byId.values());
        return ordered;
    }

    private final RelayAccount account;
    private final RelayFiles files;

    RelayConvert(RelayAccount account, RelayFiles files) {
        this.account = account;
        this.files = files;
    }

    /** The Mattermost user a post is shown from (a synthetic one for overridden names). */
    String senderOf(Post post, boolean directMessage) {
        String override = post.prop("override_username");
        if (!directMessage && override != null && !override.trim().isEmpty() && (post.isFromWebhook() || post.isFromBot())) {
            return account.overrideUser(post.userId, override.trim());
        }
        return post.userId;
    }

    /**
     * One message of a post.
     *
     * @param peer        the chat ({@code peerUser} for DMs, {@code peerChannel} otherwise)
     * @param replyToId   local id of the thread root (part 0), or 0
     * @param replyCount  replies of a root post (0: none / unknown)
     */
    TLRPC.Message message(Layout layout, int part, int localId, TLRPC.Peer peer, boolean directMessage, int replyToId, int replyCount, Collection<String> usersOut) {
        Post post = layout.post;
        String sender = senderOf(post, directMessage);
        usersOut.add(sender);
        boolean out = sender.equals(account.session.userId);
        int date = (int) (post.createAt / 1000);

        TLRPC.TL_peerUser from = new TLRPC.TL_peerUser();
        from.user_id = account.userTlId(sender);

        if (layout.service) {
            TLRPC.TL_messageService service = new TLRPC.TL_messageService();
            service.id = localId;
            service.peer_id = peer;
            service.from_id = from;
            service.flags |= TLObject.FLAG_8;
            service.date = date;
            service.out = out;
            TLRPC.TL_messageActionCustomAction action = new TLRPC.TL_messageActionCustomAction();
            action.message = serviceText(post);
            service.action = action;
            return service;
        }

        TLRPC.TL_message m = new TLRPC.TL_message();
        m.id = localId;
        m.peer_id = peer;
        m.from_id = from;
        m.flags |= TLObject.FLAG_8;
        m.date = date;
        m.out = out;

        if (part == layout.textPart) {
            FormattedText text = format(messageText(post));
            m.message = text.getText();
            ArrayList<TLRPC.MessageEntity> entities = entities(text);
            if (!entities.isEmpty()) {
                m.entities = entities;
                m.flags |= TLObject.FLAG_7;
            }
            if (!out && mentionsMe(text, directMessage)) {
                m.mentioned = true;
            }
        } else {
            m.message = "";
        }

        FileInfo file = layout.file(part);
        if (file != null) {
            m.media = files.media(file, date);
            m.flags |= TLObject.FLAG_9;
            long grouped = layout.groupedId(part);
            if (grouped != 0) {
                m.grouped_id = grouped;
                m.flags |= TLObject.FLAG_17;
            }
        }

        if (post.isEdited()) {
            m.edit_date = (int) (post.editAt / 1000);
            m.flags |= TLObject.FLAG_15;
        }
        if (replyToId != 0) {
            TLRPC.TL_messageReplyHeader reply = new TLRPC.TL_messageReplyHeader();
            reply.reply_to_msg_id = replyToId;
            reply.flags |= TLObject.FLAG_4;
            m.reply_to = reply;
            m.flags |= TLObject.FLAG_3;
        }
        if (part == 0) {
            m.pinned = post.isPinned;
            if (replyCount > 0 && !directMessage) {
                TLRPC.TL_messageReplies replies = new TLRPC.TL_messageReplies();
                replies.replies = replyCount;
                replies.replies_pts = 0;
                replies.comments = false;
                m.replies = replies;
                m.flags |= TLObject.FLAG_23;
            }
            TLRPC.TL_messageReactions reactions = reactions(post);
            if (reactions != null) {
                m.reactions = reactions;
                m.flags |= TLObject.FLAG_20;
            }
        }
        return m;
    }

    // MARK: - Text

    private String serviceText(Post post) {
        String text = post.message != null ? post.message.trim() : "";
        if (!text.isEmpty()) {
            return text;
        }
        return post.type.replace("system_", "").replace('_', ' ');
    }

    /** The post text, or the text of its message attachments (bots, webhooks) when the post has none. */
    static String messageText(Post post) {
        String text = post.message != null ? post.message : "";
        String attachments = attachmentsText(post);
        if (attachments.isEmpty()) {
            return text;
        }
        return text.trim().isEmpty() ? attachments : text + "\n\n" + attachments;
    }

    private static String attachmentsText(Post post) {
        if (post.props == null) {
            return "";
        }
        JsonElement raw = post.props.get("attachments");
        if (raw == null || !raw.isJsonArray()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (JsonElement element : (JsonArray) raw) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject a = element.getAsJsonObject();
            StringBuilder block = new StringBuilder();
            appendLine(block, string(a, "pretext"));
            String title = string(a, "title");
            if (!title.isEmpty()) {
                appendLine(block, "**" + title + "**");
            }
            appendLine(block, string(a, "text"));
            JsonElement fields = a.get("fields");
            if (fields != null && fields.isJsonArray()) {
                for (JsonElement f : (JsonArray) fields) {
                    if (f.isJsonObject()) {
                        String name = string(f.getAsJsonObject(), "title");
                        String value = string(f.getAsJsonObject(), "value");
                        appendLine(block, name.isEmpty() ? value : "**" + name + ":** " + value);
                    }
                }
            }
            if (block.length() == 0) {
                appendLine(block, string(a, "fallback"));
            }
            if (block.length() > 0) {
                if (out.length() > 0) {
                    out.append("\n\n");
                }
                out.append(block);
            }
        }
        return out.toString();
    }

    private static void appendLine(StringBuilder block, String line) {
        if (line == null || line.trim().isEmpty()) {
            return;
        }
        if (block.length() > 0) {
            block.append('\n');
        }
        block.append(line);
    }

    private static String string(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : "";
    }

    private FormattedText format(String markdown) {
        if (markdown == null || markdown.isEmpty()) {
            return new FormattedText("");
        }
        try {
            MarkdownToEntities.Options options = new MarkdownToEntities.Options();
            org.telegram.relay.mattermost.model.User me = account.cachedUser(account.session.userId);
            if (me != null && me.username != null) {
                options.setCurrentUsername(me.username);
            }
            return MarkdownToEntities.convert(markdown, options);
        } catch (Throwable t) {
            RelayConfig.logError(TAG, "markdown conversion failed, using plain text", t);
            return new FormattedText(markdown);
        }
    }

    private boolean mentionsMe(FormattedText text, boolean directMessage) {
        if (directMessage) {
            return false;
        }
        try {
            org.telegram.relay.mattermost.model.User me = account.cachedUser(account.session.userId);
            return me != null && MarkdownToEntities.mentions(text, me.username, true);
        } catch (Throwable t) {
            return false;
        }
    }

    static ArrayList<TLRPC.MessageEntity> entities(FormattedText text) {
        ArrayList<TLRPC.MessageEntity> result = new ArrayList<>();
        for (TextEntity e : text.getEntities()) {
            TLRPC.MessageEntity entity;
            switch (e.getType()) {
                case BOLD:
                    entity = new TLRPC.TL_messageEntityBold();
                    break;
                case ITALIC:
                    entity = new TLRPC.TL_messageEntityItalic();
                    break;
                case STRIKETHROUGH:
                    entity = new TLRPC.TL_messageEntityStrike();
                    break;
                case UNDERLINE:
                    entity = new TLRPC.TL_messageEntityUnderline();
                    break;
                case CODE:
                    entity = new TLRPC.TL_messageEntityCode();
                    break;
                case PRE: {
                    TLRPC.TL_messageEntityPre pre = new TLRPC.TL_messageEntityPre();
                    pre.language = e.getLanguage() != null ? e.getLanguage() : "";
                    entity = pre;
                    break;
                }
                case TEXT_URL: {
                    TLRPC.TL_messageEntityTextUrl url = new TLRPC.TL_messageEntityTextUrl();
                    url.url = e.getUrl() != null ? e.getUrl() : "";
                    entity = url;
                    break;
                }
                case URL:
                    entity = new TLRPC.TL_messageEntityUrl();
                    break;
                case EMAIL:
                    entity = new TLRPC.TL_messageEntityEmail();
                    break;
                case MENTION:
                    entity = new TLRPC.TL_messageEntityMention();
                    break;
                case SPECIAL_MENTION:
                    entity = new TLRPC.TL_messageEntityBold();
                    break;
                case BLOCKQUOTE:
                    entity = new TLRPC.TL_messageEntityBlockquote();
                    break;
                case HASHTAG:
                    entity = new TLRPC.TL_messageEntityHashtag();
                    break;
                default:
                    // CHANNEL_MENTION, CUSTOM_EMOJI, HEADING (paired with BOLD): plain text for now.
                    continue;
            }
            entity.offset = e.getOffset();
            entity.length = e.getLength();
            if (entity.length > 0) {
                result.add(entity);
            }
        }
        return result;
    }

    // MARK: - Reactions

    /** Mattermost reactions → {@code messageReactions} (counts; {@code chosen_order} for the user's own). Read-only in A3. */
    TLRPC.TL_messageReactions reactions(Post post) {
        List<Reaction> list = post.getReactions();
        if (list.isEmpty()) {
            return null;
        }
        LinkedHashMap<String, int[]> counts = new LinkedHashMap<>();
        Map<String, Boolean> mine = new HashMap<>();
        List<Reaction> sorted = new ArrayList<>(list);
        java.util.Collections.sort(sorted, (a, b) -> Long.compare(a.createAt, b.createAt));
        for (Reaction r : sorted) {
            if (r.deleteAt > 0 || r.emojiName == null || r.emojiName.isEmpty()) {
                continue;
            }
            int[] count = counts.get(r.emojiName);
            if (count == null) {
                counts.put(r.emojiName, count = new int[1]);
            }
            count[0]++;
            if (r.userId.equals(account.session.userId)) {
                mine.put(r.emojiName, Boolean.TRUE);
            }
        }
        if (counts.isEmpty()) {
            return null;
        }
        TLRPC.TL_messageReactions reactions = new TLRPC.TL_messageReactions();
        int order = 0;
        for (Map.Entry<String, int[]> entry : counts.entrySet()) {
            TLRPC.TL_reactionCount count = new TLRPC.TL_reactionCount();
            TLRPC.TL_reactionEmoji emoji = new TLRPC.TL_reactionEmoji();
            emoji.emoticon = emoticon(entry.getKey());
            count.reaction = emoji;
            count.count = entry.getValue()[0];
            if (mine.containsKey(entry.getKey())) {
                count.chosen_order = ++order;
                count.flags |= TLObject.FLAG_0;
            }
            reactions.results.add(count);
        }
        return reactions;
    }

    /** A Mattermost emoji name as Unicode, or {@code :name:} for custom emoji. */
    static String emoticon(String name) {
        try {
            String unicode = EmojiTable.unicode(name);
            if (unicode != null && !unicode.isEmpty()) {
                return unicode;
            }
        } catch (Throwable ignore) {
        }
        return ":" + name + ":";
    }
}
