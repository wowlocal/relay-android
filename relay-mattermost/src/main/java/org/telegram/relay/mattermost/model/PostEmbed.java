package org.telegram.relay.mattermost.model;

import com.google.gson.JsonElement;
import com.google.gson.annotations.SerializedName;

/** {@code metadata.embeds[]}: {@code image}, {@code message_attachment}, {@code opengraph}, {@code link}, {@code permalink}. */
public class PostEmbed {
    @SerializedName("type") public String type = "";
    @SerializedName("url") public String url = "";
    /** Type-specific payload (OpenGraph data, permalink preview…); may be null. */
    @SerializedName("data") public JsonElement data;
}
