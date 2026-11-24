package org.telegram.relay.mattermost.model;

import com.google.gson.annotations.SerializedName;

/** {@code POST /reactions}, {@code GET /posts/{id}/reactions}, WebSocket {@code reaction_added/removed}. */
public class Reaction {
    @SerializedName("user_id") public String userId = "";
    @SerializedName("post_id") public String postId = "";
    /** Without colons, e.g. {@code +1}, {@code smile}, or a custom emoji name. */
    @SerializedName("emoji_name") public String emojiName = "";
    @SerializedName("create_at") public long createAt;
    @SerializedName("update_at") public long updateAt;
    @SerializedName("delete_at") public long deleteAt;
    @SerializedName("remote_id") public String remoteId = "";
    @SerializedName("channel_id") public String channelId = "";

    public Reaction() {
    }

    public Reaction(String userId, String postId, String emojiName) {
        this.userId = userId;
        this.postId = postId;
        this.emojiName = emojiName;
    }
}
