package org.telegram.relay.mattermost.model;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.annotations.SerializedName;

import org.telegram.relay.mattermost.Json;

import java.util.ArrayList;
import java.util.List;

/** A Mattermost post ({@code GET /posts/{id}}, entries of {@link PostList}). */
public class Post {
    @SerializedName("id") public String id = "";
    @SerializedName("create_at") public long createAt;
    @SerializedName("update_at") public long updateAt;
    /** Non-zero once the message has been edited. */
    @SerializedName("edit_at") public long editAt;
    @SerializedName("delete_at") public long deleteAt;
    @SerializedName("is_pinned") public boolean isPinned;
    @SerializedName("user_id") public String userId = "";
    @SerializedName("channel_id") public String channelId = "";
    /** Thread root post id; empty for root posts. */
    @SerializedName("root_id") public String rootId = "";
    @SerializedName("original_id") public String originalId = "";
    @SerializedName("message") public String message = "";
    /** Empty for regular posts; {@code system_*} for system messages, {@code system_ephemeral} for ephemeral posts. */
    @SerializedName("type") public String type = "";
    /** Free-form: {@code attachments}, {@code from_webhook}, {@code override_username}, plugin data… May be null. */
    @JsonAdapter(Json.NullableJsonObject.class) @SerializedName("props") public JsonObject props;
    @SerializedName("hashtags") public String hashtags = "";
    @SerializedName("file_ids") public List<String> fileIds = new ArrayList<String>();
    @SerializedName("pending_post_id") public String pendingPostId = "";
    @SerializedName("has_reactions") public boolean hasReactions;
    @SerializedName("remote_id") public String remoteId = "";
    @SerializedName("reply_count") public long replyCount;
    @SerializedName("last_reply_at") public long lastReplyAt;
    /** Thread participants (collapsed reply threads, extended responses only); empty otherwise. */
    @SerializedName("participants") public List<User> participants = new ArrayList<User>();
    /** Only set for thread roots when collapsed reply threads are on. */
    @SerializedName("is_following") public Boolean isFollowing;
    /** May be null (e.g. in WebSocket {@code post_deleted}). */
    @SerializedName("metadata") public PostMetadata metadata;

    public boolean isDeleted() {
        return deleteAt > 0;
    }

    public boolean isEdited() {
        return editAt > 0;
    }

    public boolean isReply() {
        return rootId != null && !rootId.isEmpty();
    }

    public boolean isSystemMessage() {
        return type != null && type.startsWith("system_");
    }

    public boolean isEphemeral() {
        return "system_ephemeral".equals(type);
    }

    public boolean isFromWebhook() {
        return propBoolean("from_webhook");
    }

    public boolean isFromBot() {
        return propBoolean("from_bot");
    }

    /** A string prop ({@code override_username}, …), or null. */
    public String prop(String key) {
        if (props == null) {
            return null;
        }
        JsonElement value = props.get(key);
        if (value == null || value.isJsonNull()) {
            return null;
        }
        return value.isJsonPrimitive() ? value.getAsString() : value.toString();
    }

    private boolean propBoolean(String key) {
        if (props == null) {
            return false;
        }
        JsonElement value = props.get(key);
        if (value == null || !value.isJsonPrimitive()) {
            return false;
        }
        return value.getAsJsonPrimitive().isBoolean() ? value.getAsBoolean() : "true".equalsIgnoreCase(value.getAsString());
    }

    /** {@code metadata.files}, or an empty list. */
    public List<FileInfo> getFiles() {
        return metadata != null && metadata.files != null ? metadata.files : new ArrayList<FileInfo>();
    }

    /** {@code metadata.reactions}, or an empty list. */
    public List<Reaction> getReactions() {
        return metadata != null && metadata.reactions != null ? metadata.reactions : new ArrayList<Reaction>();
    }

    /** {@code pending_post_id} in the format the official clients use: {@code <userId>:<ms>}. */
    public static String makePendingPostId(String userId, long nowMillis) {
        return userId + ":" + nowMillis;
    }

    @Override
    public String toString() {
        return "Post(" + id + " in " + channelId + " at " + createAt + ")";
    }
}
