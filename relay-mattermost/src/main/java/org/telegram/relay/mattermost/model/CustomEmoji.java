package org.telegram.relay.mattermost.model;

import com.google.gson.annotations.SerializedName;

/** {@code GET /emoji}; the image is {@code GET /emoji/{id}/image}. */
public class CustomEmoji {
    @SerializedName("id") public String id = "";
    @SerializedName("creator_id") public String creatorId = "";
    @SerializedName("name") public String name = "";
    @SerializedName("create_at") public long createAt;
    @SerializedName("update_at") public long updateAt;
    @SerializedName("delete_at") public long deleteAt;
}
