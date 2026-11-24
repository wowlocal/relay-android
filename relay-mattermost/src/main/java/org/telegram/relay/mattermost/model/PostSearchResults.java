package org.telegram.relay.mattermost.model;

import com.google.gson.annotations.SerializedName;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** {@code POST /teams/{team_id}/posts/search}: a {@link PostList} plus matched terms per post. */
public class PostSearchResults extends PostList {
    @SerializedName("matches") public Map<String, List<String>> matches = new HashMap<String, List<String>>();
}
