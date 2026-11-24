package org.telegram.relay.mattermost.model;

import com.google.gson.annotations.SerializedName;

/** {@code metadata.images[url]}. */
public class PostImage {
    @SerializedName("width") public int width;
    @SerializedName("height") public int height;
    @SerializedName("format") public String format = "";
    @SerializedName("frame_count") public int frameCount;
}
