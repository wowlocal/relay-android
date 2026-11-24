package org.telegram.relay.mattermost.ws;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;

import org.telegram.relay.mattermost.Json;
import org.telegram.relay.mattermost.model.Channel;
import org.telegram.relay.mattermost.model.ChannelMember;
import org.telegram.relay.mattermost.model.CustomEmoji;
import org.telegram.relay.mattermost.model.Post;
import org.telegram.relay.mattermost.model.Preference;
import org.telegram.relay.mattermost.model.Reaction;
import org.telegram.relay.mattermost.model.User;
import org.telegram.relay.mattermost.model.UserThread;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A server-initiated WebSocket event. {@link #raw} keeps the frame's JSON; the typed accessors
 * ({@link #getPosted()}, {@link #getReaction()}, …) return null when the event is of another type or
 * its payload can't be read. Mattermost sends several payloads ({@code post}, {@code reaction},
 * {@code channel}, {@code thread}) as JSON-encoded strings; the accessors decode both forms.
 */
public final class WebSocketEvent {
    /** The raw event name. */
    public final String event;
    public final WebSocketEventType type;
    public final JsonObject data;
    public final WebSocketBroadcast broadcast;
    /** Per-connection sequence number, or null. */
    public final Long seq;
    /** The whole frame as received. */
    public final String raw;

    public WebSocketEvent(String event, JsonObject data, WebSocketBroadcast broadcast, Long seq, String raw) {
        this.event = event;
        this.type = WebSocketEventType.from(event);
        this.data = data != null ? data : new JsonObject();
        this.broadcast = broadcast != null ? broadcast : new WebSocketBroadcast();
        this.seq = seq;
        this.raw = raw;
    }

    static WebSocketEvent fromJson(JsonObject object, String raw) {
        String event = Json.string(object, "event");
        JsonObject data = object.has("data") && object.get("data").isJsonObject() ? object.getAsJsonObject("data") : new JsonObject();
        WebSocketBroadcast broadcast = Json.decodeEmbedded(object.get("broadcast"), WebSocketBroadcast.class);
        Long seq = null;
        JsonElement seqElement = object.get("seq");
        if (seqElement != null && seqElement.isJsonPrimitive()) {
            long value = Json.longValue(object, "seq", Long.MIN_VALUE);
            if (value != Long.MIN_VALUE) {
                seq = value;
            }
        }
        return new WebSocketEvent(event, data, broadcast, seq, raw);
    }

    /** Parses a text frame: a {@link WebSocketEvent}, a {@link WebSocketResponse}, or null for anything else. */
    public static Object parseFrame(String text) {
        JsonObject object = Json.parseObjectOrNull(text);
        if (object == null) {
            return null;
        }
        if (object.has("seq_reply")) {
            return WebSocketResponse.fromJson(object, text);
        }
        JsonElement event = object.get("event");
        if (event != null && event.isJsonPrimitive() && event.getAsJsonPrimitive().isString()) {
            return fromJson(object, text);
        }
        return null;
    }

    /** Parses an event frame, or returns null. */
    public static WebSocketEvent parse(String text) {
        Object frame = parseFrame(text);
        return frame instanceof WebSocketEvent ? (WebSocketEvent) frame : null;
    }

    public String dataString(String key) {
        return Json.string(data, key);
    }

    /** {@code data.channel_id}, else {@code broadcast.channel_id}; null when both are empty. */
    public String getChannelId() {
        String id = dataString("channel_id");
        if (id == null || id.isEmpty()) {
            id = broadcast.channelId;
        }
        return id == null || id.isEmpty() ? null : id;
    }

    public <T> T decode(String key, Class<T> type) {
        return Json.decodeEmbedded(data.get(key), type);
    }

    // MARK: - Typed payloads

    public static final class Hello {
        public final String connectionId;
        public final String serverVersion;

        Hello(String connectionId, String serverVersion) {
            this.connectionId = connectionId;
            this.serverVersion = serverVersion;
        }
    }

    public static final class Posted {
        public final Post post;
        /** {@code O}, {@code P}, {@code D}, {@code G}; may be null. */
        public final String channelType;
        public final String channelName;
        public final String channelDisplayName;
        public final String teamId;
        /** e.g. {@code "@alice"}. */
        public final String senderName;
        /** Ids of users mentioned by the post. */
        public final List<String> mentions;
        /** Ids of users following the thread (collapsed reply threads). */
        public final List<String> followers;
        public final Boolean setOnline;

        Posted(Post post, String channelType, String channelName, String channelDisplayName, String teamId, String senderName, List<String> mentions, List<String> followers, Boolean setOnline) {
            this.post = post;
            this.channelType = channelType;
            this.channelName = channelName;
            this.channelDisplayName = channelDisplayName;
            this.teamId = teamId;
            this.senderName = senderName;
            this.mentions = mentions;
            this.followers = followers;
            this.setOnline = setOnline;
        }
    }

    public static final class Typing {
        public final String userId;
        public final String channelId;
        /** Thread root id when typing in a thread; empty otherwise. */
        public final String parentId;

        Typing(String userId, String channelId, String parentId) {
            this.userId = userId;
            this.channelId = channelId;
            this.parentId = parentId;
        }
    }

    public static final class StatusChange {
        public final String userId;
        /** {@code online}, {@code away}, {@code dnd}, {@code offline}. */
        public final String status;

        StatusChange(String userId, String status) {
            this.userId = userId;
            this.status = status;
        }
    }

    /** Channels the current user marked read (usually on another device). */
    public static final class ChannelsViewed {
        /** Sorted. */
        public final List<String> channelIds;
        /** Channel id to the new {@code last_viewed_at} (ms); empty for {@code channel_viewed}. */
        public final Map<String, Long> lastViewedAt;

        ChannelsViewed(List<String> channelIds, Map<String, Long> lastViewedAt) {
            this.channelIds = channelIds;
            this.lastViewedAt = lastViewedAt;
        }
    }

    /** {@code user_added} / {@code user_removed} / {@code added_to_team} / {@code leave_team}. */
    public static final class Membership {
        public final String userId;
        /** Null for team events. */
        public final String channelId;
        public final String teamId;
        /** {@code user_removed} only. */
        public final String removerId;

        Membership(String userId, String channelId, String teamId, String removerId) {
            this.userId = userId;
            this.channelId = channelId;
            this.teamId = teamId;
            this.removerId = removerId;
        }
    }

    /** {@code channel_created}, {@code channel_deleted}, {@code channel_restored}, {@code channel_converted}. */
    public static final class ChannelChange {
        public final String channelId;
        public final String teamId;
        /** {@code channel_deleted} only (ms), else 0. */
        public final long deleteAt;

        ChannelChange(String channelId, String teamId, long deleteAt) {
            this.channelId = channelId;
            this.teamId = teamId;
            this.deleteAt = deleteAt;
        }
    }

    /** {@code direct_added} / {@code group_added}: a DM or GM the user is now part of. */
    public static final class DirectAdded {
        public final String channelId;
        public final String creatorId;
        /** The other users (one for a DM). */
        public final List<String> teammateIds;

        DirectAdded(String channelId, String creatorId, List<String> teammateIds) {
            this.channelId = channelId;
            this.creatorId = creatorId;
            this.teammateIds = teammateIds;
        }
    }

    /** {@code thread_read_changed}. */
    public static final class ThreadReadChanged {
        /** Null when every thread of the team was marked read. */
        public final String threadId;
        public final String channelId;
        public final String teamId;
        public final long timestamp;
        public final long unreadReplies;
        public final long unreadMentions;

        ThreadReadChanged(String threadId, String channelId, String teamId, long timestamp, long unreadReplies, long unreadMentions) {
            this.threadId = threadId;
            this.channelId = channelId;
            this.teamId = teamId;
            this.timestamp = timestamp;
            this.unreadReplies = unreadReplies;
            this.unreadMentions = unreadMentions;
        }
    }

    /** {@code post_unread}: the channel was marked unread from a post (on another device). */
    public static final class PostUnread {
        public final String channelId;
        public final String postId;
        public final long lastViewedAt;
        public final long msgCount;
        public final long msgCountRoot;
        public final long mentionCount;
        public final long mentionCountRoot;

        PostUnread(String channelId, String postId, long lastViewedAt, long msgCount, long msgCountRoot, long mentionCount, long mentionCountRoot) {
            this.channelId = channelId;
            this.postId = postId;
            this.lastViewedAt = lastViewedAt;
            this.msgCount = msgCount;
            this.msgCountRoot = msgCountRoot;
            this.mentionCount = mentionCount;
            this.mentionCountRoot = mentionCountRoot;
        }
    }

    // MARK: - Accessors

    public Hello getHello() {
        if (type != WebSocketEventType.HELLO) {
            return null;
        }
        String connectionId = dataString("connection_id");
        if (connectionId == null) {
            return null;
        }
        return new Hello(connectionId, dataString("server_version"));
    }

    private List<String> stringList(String key) {
        List<String> list = Json.decodeEmbedded(data.get(key), new TypeToken<List<String>>() { }.getType());
        return list != null ? list : new ArrayList<String>();
    }

    public Posted getPosted() {
        if (type != WebSocketEventType.POSTED) {
            return null;
        }
        Post post = decode("post", Post.class);
        if (post == null) {
            return null;
        }
        return new Posted(post, dataString("channel_type"), dataString("channel_name"), dataString("channel_display_name"),
                dataString("team_id"), dataString("sender_name"), stringList("mentions"), stringList("followers"),
                Json.booleanValue(data, "set_online"));
    }

    /** The post of {@code posted}, {@code post_edited}, {@code post_deleted} and {@code ephemeral_message}. */
    public Post getPost() {
        switch (type) {
            case POSTED:
            case POST_EDITED:
            case POST_DELETED:
            case EPHEMERAL_MESSAGE:
                return decode("post", Post.class);
            default:
                return null;
        }
    }

    public Post getEditedPost() {
        return type == WebSocketEventType.POST_EDITED ? decode("post", Post.class) : null;
    }

    /** The deleted post ({@code delete_at} may be 0 in the payload). */
    public Post getDeletedPost() {
        return type == WebSocketEventType.POST_DELETED ? decode("post", Post.class) : null;
    }

    /** The reaction of {@code reaction_added} / {@code reaction_removed}. */
    public Reaction getReaction() {
        if (type != WebSocketEventType.REACTION_ADDED && type != WebSocketEventType.REACTION_REMOVED) {
            return null;
        }
        return decode("reaction", Reaction.class);
    }

    public Typing getTyping() {
        if (type != WebSocketEventType.TYPING) {
            return null;
        }
        String userId = dataString("user_id");
        if (userId == null) {
            return null;
        }
        String parentId = dataString("parent_id");
        return new Typing(userId, broadcast.channelId, parentId != null ? parentId : "");
    }

    public StatusChange getStatusChange() {
        if (type != WebSocketEventType.STATUS_CHANGE) {
            return null;
        }
        String userId = dataString("user_id");
        String status = dataString("status");
        if (userId == null || status == null) {
            return null;
        }
        return new StatusChange(userId, status);
    }

    /**
     * Read-on-another-device for every server version: {@code channel_viewed} (older servers) and
     * {@code multiple_channels_viewed} (11.x sends only this one, also for a single channel).
     */
    public ChannelsViewed getChannelsViewed() {
        if (type == WebSocketEventType.CHANNEL_VIEWED) {
            String channelId = dataString("channel_id");
            if (channelId == null) {
                return null;
            }
            return new ChannelsViewed(Collections.singletonList(channelId), new LinkedHashMap<String, Long>());
        }
        if (type == WebSocketEventType.MULTIPLE_CHANNELS_VIEWED) {
            JsonObject times = Json.decodeEmbedded(data.get("channel_times"), JsonObject.class);
            if (times == null) {
                return null;
            }
            List<String> ids = new ArrayList<String>(times.keySet());
            Collections.sort(ids);
            Map<String, Long> lastViewedAt = new LinkedHashMap<String, Long>();
            for (String id : ids) {
                lastViewedAt.put(id, Json.longValue(times, id, 0));
            }
            return new ChannelsViewed(ids, lastViewedAt);
        }
        return null;
    }

    /** {@code user_updated}. */
    public User getUser() {
        return type == WebSocketEventType.USER_UPDATED ? decode("user", User.class) : null;
    }

    /** The channel of {@code channel_updated} (and {@code channel_converted} on servers that include it). */
    public Channel getChannel() {
        if (type != WebSocketEventType.CHANNEL_UPDATED && type != WebSocketEventType.CHANNEL_CONVERTED) {
            return null;
        }
        return decode("channel", Channel.class);
    }

    public ChannelChange getChannelChange() {
        switch (type) {
            case CHANNEL_CREATED:
            case CHANNEL_DELETED:
            case CHANNEL_RESTORED:
            case CHANNEL_CONVERTED: {
                String channelId = getChannelId();
                if (channelId == null) {
                    return null;
                }
                String teamId = dataString("team_id");
                if (teamId == null || teamId.isEmpty()) {
                    teamId = broadcast.teamId;
                }
                return new ChannelChange(channelId, teamId, Json.longValue(data, "delete_at", 0));
            }
            default:
                return null;
        }
    }

    /** {@code channel_member_updated}. */
    public ChannelMember getChannelMember() {
        return type == WebSocketEventType.CHANNEL_MEMBER_UPDATED ? decode("channelMember", ChannelMember.class) : null;
    }

    public Membership getMembership() {
        switch (type) {
            case USER_ADDED:
            case USER_REMOVED:
            case ADDED_TO_TEAM:
            case LEAVE_TEAM: {
                String userId = dataString("user_id");
                if (userId == null || userId.isEmpty()) {
                    userId = broadcast.userId;
                }
                boolean team = type == WebSocketEventType.ADDED_TO_TEAM || type == WebSocketEventType.LEAVE_TEAM;
                String teamId = dataString("team_id");
                if (teamId == null || teamId.isEmpty()) {
                    teamId = broadcast.teamId;
                }
                return new Membership(userId, team ? null : getChannelId(), teamId, dataString("remover_id"));
            }
            default:
                return null;
        }
    }

    public DirectAdded getDirectAdded() {
        if (type == WebSocketEventType.DIRECT_ADDED) {
            String teammate = dataString("teammate_id");
            List<String> teammates = teammate != null ? Collections.singletonList(teammate) : new ArrayList<String>();
            return new DirectAdded(getChannelId(), dataString("creator_id"), teammates);
        }
        if (type == WebSocketEventType.GROUP_ADDED) {
            return new DirectAdded(getChannelId(), dataString("creator_id"), stringList("teammate_ids"));
        }
        return null;
    }

    /** {@code thread_updated}. */
    public UserThread getThread() {
        return type == WebSocketEventType.THREAD_UPDATED ? decode("thread", UserThread.class) : null;
    }

    public ThreadReadChanged getThreadReadChanged() {
        if (type != WebSocketEventType.THREAD_READ_CHANGED) {
            return null;
        }
        String threadId = dataString("thread_id");
        return new ThreadReadChanged(threadId == null || threadId.isEmpty() ? null : threadId, getChannelId(), broadcast.teamId,
                Json.longValue(data, "timestamp", 0), Json.longValue(data, "unread_replies", 0), Json.longValue(data, "unread_mentions", 0));
    }

    public PostUnread getPostUnread() {
        if (type != WebSocketEventType.POST_UNREAD) {
            return null;
        }
        return new PostUnread(getChannelId(), dataString("post_id"), Json.longValue(data, "last_viewed_at", 0),
                Json.longValue(data, "msg_count", 0), Json.longValue(data, "msg_count_root", 0),
                Json.longValue(data, "mention_count", 0), Json.longValue(data, "mention_count_root", 0));
    }

    /** {@code emoji_added}. */
    public CustomEmoji getEmoji() {
        return type == WebSocketEventType.EMOJI_ADDED ? decode("emoji", CustomEmoji.class) : null;
    }

    /** {@code preference_changed} (one) / {@code preferences_changed} / {@code preferences_deleted}. */
    public List<Preference> getPreferences() {
        if (type == WebSocketEventType.PREFERENCE_CHANGED) {
            Preference preference = decode("preference", Preference.class);
            List<Preference> list = new ArrayList<Preference>();
            if (preference != null) {
                list.add(preference);
            }
            return list;
        }
        if (type == WebSocketEventType.PREFERENCES_CHANGED || type == WebSocketEventType.PREFERENCES_DELETED) {
            List<Preference> list = Json.decodeEmbedded(data.get("preferences"), new TypeToken<List<Preference>>() { }.getType());
            return list != null ? list : new ArrayList<Preference>();
        }
        return null;
    }

    @Override
    public String toString() {
        return "WebSocketEvent(" + event + (seq != null ? " seq=" + seq : "") + ")";
    }
}
