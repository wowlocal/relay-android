package org.telegram.relay.mattermost.ids;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Port of the iOS MattermostSyncCore test support (SplitMix64, FakeServer, post factories). */
final class TestSupport {
    private TestSupport() {
    }

    /** SplitMix64: small, fast, deterministic. */
    static final class SeededGenerator {
        long state;

        SeededGenerator(long seed) {
            state = seed;
        }

        long next() {
            state += 0x9E3779B97F4A7C15L;
            long z = state;
            z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
            z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
            return z ^ (z >>> 31);
        }

        /** {@code next() % bound} with unsigned semantics, like Swift's UInt64. */
        long nextMod(long bound) {
            return Long.remainderUnsigned(next(), bound);
        }

        int random(int bound) {
            return (int) nextMod(bound);
        }

        <T> void shuffle(List<T> list) {
            for (int i = list.size() - 1; i > 0; i--) {
                int j = (int) nextMod(i + 1);
                Collections.swap(list, i, j);
            }
        }

        <T> List<T> shuffled(List<T> list) {
            List<T> copy = new ArrayList<T>(list);
            shuffle(copy);
            return copy;
        }
    }

    static final class Times {
        /** 2013-01-01T00:00:00Z */
        static final long YEAR_2013 = 1_356_998_400_000L;
        /** 2025-01-01T00:00:00Z */
        static final long YEAR_2025 = 1_735_689_600_000L;
        /** 2026-01-01T00:00:00Z */
        static final long YEAR_2026 = 1_767_225_600_000L;
        static final long DAY = 86_400_000L;
    }

    private static final char[] ID_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789".toCharArray();

    static String randomPostId(SeededGenerator rng) {
        char[] id = new char[26];
        for (int i = 0; i < 26; i++) {
            id[i] = ID_ALPHABET[(int) rng.nextMod(ID_ALPHABET.length)];
        }
        return new String(id);
    }

    /** Posts with increasing timestamps: {@code count} posts from {@code start}, {@code spacing} ms apart. */
    static List<PostOrderKey> makePosts(int count, long start, long spacing, SeededGenerator rng) {
        List<PostOrderKey> posts = new ArrayList<PostOrderKey>(count);
        for (int i = 0; i < count; i++) {
            posts.add(new PostOrderKey(start + i * spacing, randomPostId(rng)));
        }
        Collections.sort(posts);
        return posts;
    }

    static final class Page {
        final List<PostOrderKey> posts;
        final boolean reachedEnd;

        Page(List<PostOrderKey> posts, boolean reachedEnd) {
            this.posts = posts;
            this.reachedEnd = reachedEnd;
        }
    }

    /** An in-memory Mattermost channel answering the history queries the sync engine makes. */
    static final class FakeServer {
        /** All posts ever created, sorted; deleted ones stay (as in Mattermost's DB). */
        final List<PostOrderKey> posts = new ArrayList<PostOrderKey>();
        final Set<String> deleted = new HashSet<String>();
        private final Map<String, PostOrderKey> keyById = new HashMap<String, PostOrderKey>();

        FakeServer(List<PostOrderKey> initial) {
            for (PostOrderKey post : initial) {
                add(post);
            }
        }

        List<PostOrderKey> livePosts() {
            List<PostOrderKey> result = new ArrayList<PostOrderKey>();
            for (PostOrderKey post : posts) {
                if (!deleted.contains(post.postId)) {
                    result.add(post);
                }
            }
            return result;
        }

        PostOrderKey key(String postId) {
            return keyById.get(postId);
        }

        void add(PostOrderKey post) {
            posts.add(lowerIndex(post), post);
            keyById.put(post.postId, post);
        }

        void delete(String postId) {
            deleted.add(postId);
        }

        boolean isDeleted(String postId) {
            return deleted.contains(postId);
        }

        private int lowerIndex(PostOrderKey key) {
            int low = 0;
            int high = posts.size();
            while (low < high) {
                int mid = (low + high) >>> 1;
                if (posts.get(mid).lessThan(key)) {
                    low = mid + 1;
                } else {
                    high = mid;
                }
            }
            return low;
        }

        /** {@code page=0&per_page=limit}, returned oldest first. */
        Page latest(int limit) {
            List<PostOrderKey> result = new ArrayList<PostOrderKey>();
            int index = posts.size() - 1;
            while (index >= 0 && result.size() < limit) {
                if (!deleted.contains(posts.get(index).postId)) {
                    result.add(posts.get(index));
                }
                index--;
            }
            boolean reachedEnd = true;
            for (int i = 0; i <= index; i++) {
                if (!deleted.contains(posts.get(i).postId)) {
                    reachedEnd = false;
                    break;
                }
            }
            Collections.reverse(result);
            return new Page(result, reachedEnd);
        }

        /** {@code before=postId}: the {@code limit} newest live posts older than the cursor. */
        Page before(String postId, int limit) {
            PostOrderKey key = keyById.get(postId);
            if (key == null) {
                return new Page(new ArrayList<PostOrderKey>(), true);
            }
            List<PostOrderKey> result = new ArrayList<PostOrderKey>();
            int index = lowerIndex(key) - 1;
            while (index >= 0 && result.size() < limit) {
                if (!deleted.contains(posts.get(index).postId)) {
                    result.add(posts.get(index));
                }
                index--;
            }
            Collections.reverse(result);
            return new Page(result, result.size() < limit);
        }

        /** {@code after=postId}: the {@code limit} oldest live posts newer than the cursor. */
        Page after(String postId, int limit) {
            PostOrderKey key = keyById.get(postId);
            if (key == null) {
                return new Page(new ArrayList<PostOrderKey>(), true);
            }
            List<PostOrderKey> result = new ArrayList<PostOrderKey>();
            int index = lowerIndex(key) + 1;
            while (index < posts.size() && result.size() < limit) {
                if (!deleted.contains(posts.get(index).postId)) {
                    result.add(posts.get(index));
                }
                index++;
            }
            return new Page(result, result.size() < limit);
        }

        /** Live posts with {@code lower <= key <= upper}. */
        List<PostOrderKey> livePosts(PostOrderKey lower, PostOrderKey upper) {
            List<PostOrderKey> result = new ArrayList<PostOrderKey>();
            int index = lowerIndex(lower);
            while (index < posts.size() && !upper.lessThan(posts.get(index))) {
                if (!deleted.contains(posts.get(index).postId)) {
                    result.add(posts.get(index));
                }
                index++;
            }
            return result;
        }

        boolean hasLivePostOlderThan(PostOrderKey key) {
            int end = lowerIndex(key);
            for (int i = 0; i < end; i++) {
                if (!deleted.contains(posts.get(i).postId)) {
                    return true;
                }
            }
            return false;
        }

        boolean hasLivePostNewerThan(PostOrderKey key) {
            int index = lowerIndex(key);
            if (index < posts.size() && posts.get(index).equals(key)) {
                index++;
            }
            for (int i = index; i < posts.size(); i++) {
                if (!deleted.contains(posts.get(i).postId)) {
                    return true;
                }
            }
            return false;
        }
    }

    static List<String> ids(List<PostOrderKey> keys) {
        List<String> result = new ArrayList<String>();
        for (PostOrderKey key : keys) {
            result.add(key.postId);
        }
        return result;
    }

    static List<Integer> localIds(List<MessageIdEntry> entries) {
        List<Integer> result = new ArrayList<Integer>();
        for (MessageIdEntry entry : entries) {
            result.add(entry.localId);
        }
        return result;
    }

    static List<PostOrderKey> keys(List<MessageIdEntry> entries) {
        List<PostOrderKey> result = new ArrayList<PostOrderKey>();
        for (MessageIdEntry entry : entries) {
            result.add(entry.key);
        }
        return result;
    }
}
