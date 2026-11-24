package org.telegram.relay.mattermost.model;

import com.google.gson.annotations.SerializedName;

/** {@code GET /users/{id}/preferences}: favourites ({@code favorite_channel}), saved posts ({@code flagged_post}), DM visibility… */
public class Preference {
    @SerializedName("user_id") public String userId = "";
    @SerializedName("category") public String category = "";
    @SerializedName("name") public String name = "";
    @SerializedName("value") public String value = "";

    public Preference() {
    }

    public Preference(String userId, String category, String name, String value) {
        this.userId = userId;
        this.category = category;
        this.name = name;
        this.value = value;
    }
}
