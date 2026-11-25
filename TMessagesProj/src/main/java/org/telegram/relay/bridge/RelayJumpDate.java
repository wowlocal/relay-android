package org.telegram.relay.bridge;

import org.telegram.relay.RelayConfig;
import org.telegram.relay.mattermost.MattermostException;
import org.telegram.relay.mattermost.PostsQuery;
import org.telegram.relay.mattermost.model.Channel;
import org.telegram.relay.mattermost.model.Post;
import org.telegram.relay.mattermost.model.PostList;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A7a: jump to date ({@code getHistory} with {@code offset_date}, load type 4; internals doc §5.3: p is the
 * first message with {@code date < offset_date}, the window {@code L[p+add_offset, p+add_offset+limit)}).
 *
 * <p>Mattermost has no "posts before a time" query, so the bridge binary-searches the channel's posts by
 * offset ({@code GET /channels/{id}/posts?page=n&per_page=1}, newest first: about 14 requests for 11,000
 * posts) for the newest post created before the date, then answers the usual window around it. The
 * device's local midnight that Telegram's calendar sends is used as it is, so there is no time zone
 * conversion (as there would be with search's {@code on:}/{@code before:}).
 */
final class RelayJumpDate {
    private RelayJumpDate() {
    }

    private static final String TAG = RelayConfig.TAG_BRIDGE;

    static List<RelayHistory.Unit> window(RelayReadPath path, Channel channel, int offsetDate, int addOffset, int limit, int maxId, int minId, boolean rootsOnly) throws Exception {
        long started = System.currentTimeMillis();
        long target = offsetDate * 1000L;
        Search search = new Search(path, channel.id);
        // Upper bound: an index whose post is before the date (or past the end).
        int hi = (int) Math.max(1, Math.min(Integer.MAX_VALUE / 4, channel.totalMsgCount + 1));
        for (int i = 0; i < 20 && !search.before(hi, target); i++) {
            hi *= 2;
        }
        int lo = 0;
        while (lo < hi) {
            int mid = lo + (hi - lo) / 2;
            if (search.before(mid, target)) {
                hi = mid;
            } else {
                lo = mid + 1;
            }
        }
        Post anchor = search.at(lo);
        Post oldest = anchor == null && lo > 0 ? search.at(lo - 1) : null;
        List<RelayHistory.Unit> units = path.history.windowAtPost(channel.id, anchor, oldest, addOffset, limit, rootsOnly);
        if (maxId != 0 || minId != 0) {
            java.util.Iterator<RelayHistory.Unit> it = units.iterator();
            while (it.hasNext()) {
                RelayHistory.Unit unit = it.next();
                if ((maxId != 0 && unit.localId >= maxId) || (minId != 0 && unit.localId <= minId)) {
                    it.remove();
                }
            }
        }
        RelayConfig.log(TAG, "jump to date " + offsetDate + " in " + channel.name + ": index " + lo + " anchor "
                + (anchor != null ? anchor.id + "@" + anchor.createAt : "none (oldest " + (oldest != null ? oldest.id : "-") + ")")
                + " after " + search.requests + " requests, " + units.size() + " msgs in " + (System.currentTimeMillis() - started) + " ms");
        return units;
    }

    /** Posts of a channel by newest-first index, fetched one by one and cached for one search. */
    private static final class Search {
        final RelayReadPath path;
        final String channelId;
        final Map<Integer, Post> posts = new HashMap<>();
        final Map<Integer, Boolean> missing = new HashMap<>();
        int requests;

        Search(RelayReadPath path, String channelId) {
            this.path = path;
            this.channelId = channelId;
        }

        Post at(int index) throws MattermostException {
            if (index < 0) {
                return null;
            }
            Post cached = posts.get(index);
            if (cached != null || missing.containsKey(index)) {
                return cached;
            }
            requests++;
            PostList list = path.account.client.posts(channelId, PostsQuery.page(index, 1));
            List<Post> ordered = list.getOrderedPosts();
            if (ordered.isEmpty()) {
                missing.put(index, Boolean.TRUE);
                return null;
            }
            Post post = ordered.get(0);
            path.history.rememberPost(post);
            posts.put(index, post);
            return post;
        }

        /** True when the post at {@code index} was created before {@code target}, or the index is past the end. */
        boolean before(int index, long target) throws MattermostException {
            Post post = at(index);
            return post == null || post.createAt < target;
        }
    }
}
