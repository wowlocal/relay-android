package org.telegram.relay.mattermost.model;

import com.google.gson.annotations.SerializedName;

/** {@code GET /files/{id}/info}, {@code Post.metadata.files}. */
public class FileInfo {
    @SerializedName("id") public String id = "";
    @SerializedName("user_id") public String userId = "";
    /** Empty until the file is attached to a post. */
    @SerializedName("post_id") public String postId = "";
    @SerializedName("channel_id") public String channelId = "";
    @SerializedName("create_at") public long createAt;
    @SerializedName("update_at") public long updateAt;
    @SerializedName("delete_at") public long deleteAt;
    @SerializedName("name") public String name = "";
    @SerializedName("extension") public String extension = "";
    @SerializedName("size") public long size;
    @SerializedName("mime_type") public String mimeType = "";
    @SerializedName("width") public int width;
    @SerializedName("height") public int height;
    @SerializedName("has_preview_image") public boolean hasPreviewImage;
    /** Base64 of a tiny JPEG thumbnail (images only), or empty. */
    @SerializedName("mini_preview") public String miniPreview = "";
    @SerializedName("remote_id") public String remoteId = "";
    @SerializedName("archived") public boolean archived;

    public boolean isImage() {
        return mimeType != null && mimeType.startsWith("image/");
    }

    public boolean isVideo() {
        return mimeType != null && mimeType.startsWith("video/");
    }

    public boolean isAudio() {
        return mimeType != null && mimeType.startsWith("audio/");
    }
}
