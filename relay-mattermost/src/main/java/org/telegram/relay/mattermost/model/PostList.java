package org.telegram.relay.mattermost.model;

import com.google.gson.annotations.SerializedName;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A page of posts. {@link #order} lists post ids newest first; {@link #posts} may also contain thread
 * roots and replies that are not in {@code order}.
 */
public class PostList {
    @SerializedName("order") public List<String> order = new ArrayList<String>();
    @SerializedName("posts") public Map<String, Post> posts = new HashMap<String, Post>();
    @SerializedName("next_post_id") public String nextPostId = "";
    @SerializedName("prev_post_id") public String prevPostId = "";
    /** Only sent by newer servers; may be null. */
    @SerializedName("has_next") public Boolean hasNext;
    @SerializedName("first_inaccessible_post_time") public long firstInaccessiblePostTime;

    /** Posts in {@link #order} (newest first). Ids without a post are skipped. */
    public List<Post> getOrderedPosts() {
        List<Post> result = new ArrayList<Post>(order != null ? order.size() : 0);
        if (order == null || posts == null) {
            return result;
        }
        for (String id : order) {
            Post post = posts.get(id);
            if (post != null) {
                result.add(post);
            }
        }
        return result;
    }

    public int size() {
        return order == null ? 0 : order.size();
    }

    public boolean isEmpty() {
        return size() == 0;
    }
}
