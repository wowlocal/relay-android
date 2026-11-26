package org.telegram.relay.bridge;

import org.telegram.relay.RelayConfig;
import org.telegram.relay.account.RelayAccount;
import org.telegram.relay.mattermost.MattermostException;
import org.telegram.relay.mattermost.ThreadQuery;
import org.telegram.relay.mattermost.model.Post;
import org.telegram.relay.mattermost.model.PostList;
import org.telegram.relay.mattermost.model.User;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * DM threads + replier avatars: the latest repliers of thread roots (Telegram's {@code messageReplies.recent_repliers},
 * the avatars in front of "N Replies"), newest first, at most three, as iOS keeps them
 * ({@code MattermostSync.updateThreadRootSummary}).
 *
 * <p>Mattermost's plain channel pages (the bridge pages every post, A7a) carry no thread participants, so the
 * repliers come from:
 * <ul>
 *   <li>a loaded thread ({@code GET /posts/{root}/thread}): the replies' authors, newest first (exact);</li>
 *   <li>live replies (A4 {@code posted}, own replies included): the author goes in front of the known ones;</li>
 *   <li>the followed-threads list and {@code thread_updated}: the thread's participants, latest first;</li>
 *   <li>otherwise one small request per root, once: {@code GET /posts/{root}/thread?perPage=1&collapsedThreads=true
 *       &collapsedThreadsExtended=true}, whose root carries the participants (latest first = reversed);</li>
 *   <li>as a last resort, the authors of the root's replies in the bridge's post cache.</li>
 * </ul>
 */
final class RelayRepliers {
    private static final String TAG = RelayConfig.TAG_BRIDGE;
    static final int MAX = 3;
    private static final int CACHE = 2000;
    private static final long FETCH_TIMEOUT_MS = 4000;

    /** Where the repliers come from; LIVE is partial (live replies only, the server's older participants not merged yet). */
    private enum Source { LIVE, CACHE, PARTICIPANTS, THREAD }

    private static final class Known {
        final List<String> users;
        final Source source;
        /** {@code create_at} of the newest reply accounted for (0: unknown). */
        final long newestAt;

        Known(List<String> users, Source source, long newestAt) {
            this.users = users;
            this.source = source;
            this.newestAt = newestAt;
        }
    }

    private final RelayAccount account;
    private final LinkedHashMap<String, Known> entries = new LinkedHashMap<String, Known>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Known> eldest) {
            return size() > CACHE;
        }
    };

    RelayRepliers(RelayAccount account) {
        this.account = account;
    }

    private static List<String> distinct(Collection<String> newestFirst) {
        List<String> result = new ArrayList<>(MAX);
        for (String id : newestFirst) {
            if (id != null && !id.isEmpty() && !result.contains(id)) {
                result.add(id);
                if (result.size() == MAX) {
                    break;
                }
            }
        }
        return result;
    }

    private static List<String> reversedIds(List<User> oldestFirst) {
        List<String> ids = new ArrayList<>();
        if (oldestFirst != null) {
            for (int i = oldestFirst.size() - 1; i >= 0; i--) {
                User user = oldestFirst.get(i);
                if (user != null && user.id != null && !user.id.isEmpty()) {
                    ids.add(user.id);
                }
            }
        }
        return ids;
    }

    /** The known latest repliers of a root (newest first), or null when unknown. */
    synchronized List<String> get(String rootId) {
        Known entry = entries.get(rootId);
        return entry != null ? entry.users : null;
    }

    /** True when the root's repliers are complete enough to answer without asking the server. */
    private synchronized boolean complete(String rootId) {
        Known entry = entries.get(rootId);
        return entry != null && entry.source != Source.LIVE;
    }

    /** A loaded thread: the authors of its replies (oldest first, as {@code RelayThreads} keeps them). True when it changed. */
    synchronized boolean fromThread(String rootId, List<Post> repliesOldestFirst) {
        List<String> authors = new ArrayList<>();
        for (int i = repliesOldestFirst.size() - 1; i >= 0 && authors.size() < 16; i--) {
            authors.add(repliesOldestFirst.get(i).userId);
        }
        long newestAt = repliesOldestFirst.isEmpty() ? 0 : repliesOldestFirst.get(repliesOldestFirst.size() - 1).createAt;
        return put(rootId, distinct(authors), Source.THREAD, newestAt);
    }

    /**
     * The thread's participants (oldest first, Mattermost's order: latest first is reversed), e.g. the followed-threads
     * list. Completes live-only repliers (they stay in front) and never replaces a loaded thread's exact order.
     */
    synchronized boolean fromParticipants(String rootId, List<User> participantsOldestFirst, boolean override) {
        List<String> ids = reversedIds(participantsOldestFirst);
        if (ids.isEmpty()) {
            return false;
        }
        Known known = entries.get(rootId);
        if (known != null && !override && known.source != Source.CACHE && known.source != Source.LIVE) {
            return false;
        }
        List<String> users = new ArrayList<>();
        if (known != null && known.source == Source.LIVE) {
            users.addAll(known.users);
        }
        users.addAll(ids);
        return put(rootId, distinct(users), Source.PARTICIPANTS, known != null ? known.newestAt : 0);
    }

    /**
     * A new reply (live, caught up, or sent here): its author goes in front of the known repliers. Live events of
     * replies posted at nearly the same time can arrive out of order: an older reply than the newest one accounted for
     * only joins at the back.
     */
    synchronized void onReply(String rootId, String userId, long createAt) {
        Known known = entries.get(rootId);
        List<String> users = new ArrayList<>();
        long newestAt = createAt;
        if (known != null && createAt < known.newestAt) {
            users.addAll(known.users);
            users.add(userId);
            newestAt = known.newestAt;
        } else {
            users.add(userId);
            if (known != null) {
                users.addAll(known.users);
            }
        }
        put(rootId, distinct(users), known != null ? known.source : Source.LIVE, newestAt);
    }

    /** A reply of the root was deleted: the next answer asks the server again. */
    synchronized void forget(String rootId) {
        entries.remove(rootId);
    }

    synchronized void clear() {
        entries.clear();
    }

    private boolean put(String rootId, List<String> users, Source source, long newestAt) {
        Known old = entries.get(rootId);
        entries.put(rootId, new Known(Collections.unmodifiableList(users), source, newestAt));
        boolean changed = old == null || !old.users.equals(users);
        if (changed) {
            RelayConfig.log(TAG, "repliers " + rootId + " = " + users + " (" + source + ")");
        }
        return changed;
    }

    /**
     * The latest repliers of {@code roots} (root post ids with replies), fetching the unknown ones in parallel on
     * {@code pool} (bounded wait); {@code cachedPosts} gives the fallback from the post cache.
     */
    Map<String, List<String>> resolve(Collection<String> roots, ExecutorService pool, RelayHistory history) {
        Map<String, List<String>> result = new HashMap<>();
        Map<String, Future<List<User>>> pending = new HashMap<>();
        for (String rootId : roots) {
            if (complete(rootId)) {
                result.put(rootId, get(rootId));
            } else {
                pending.put(rootId, pool.submit(() -> fetchParticipants(rootId)));
            }
        }
        long deadline = System.currentTimeMillis() + FETCH_TIMEOUT_MS;
        for (Map.Entry<String, Future<List<User>>> e : pending.entrySet()) {
            List<User> participants = null;
            try {
                participants = e.getValue().get(Math.max(1, deadline - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
            } catch (Exception ex) {
                RelayConfig.log(TAG, "repliers of " + e.getKey() + ": " + ex);
            }
            List<String> users;
            if (participants != null && !participants.isEmpty()) {
                fromParticipants(e.getKey(), participants, false);
                users = get(e.getKey());
            } else {
                users = get(e.getKey()); // live-only repliers, else the post cache's reply authors
                if (users == null || users.isEmpty()) {
                    users = history.cachedReplyAuthors(e.getKey(), MAX);
                    if (!users.isEmpty()) {
                        synchronized (this) {
                            if (!entries.containsKey(e.getKey())) {
                                put(e.getKey(), users, Source.CACHE, 0);
                            }
                        }
                    }
                }
            }
            if (users != null && !users.isEmpty()) {
                result.put(e.getKey(), users);
            }
        }
        return result;
    }

    /** The root's thread participants (oldest first) from one small thread page with the extended root. */
    private List<User> fetchParticipants(String rootId) throws MattermostException {
        PostList list = account.client.postThread(rootId, new ThreadQuery().perPage(1).direction("down")
                .collapsedThreads(true).collapsedThreadsExtended(true));
        Post root = list != null && list.posts != null ? list.posts.get(rootId) : null;
        return root != null ? root.participants : null;
    }
}
