package org.telegram.relay.mattermost.ids;

import org.junit.Test;
import org.telegram.relay.mattermost.ids.TestSupport.FakeServer;
import org.telegram.relay.mattermost.ids.TestSupport.Page;
import org.telegram.relay.mattermost.ids.TestSupport.SeededGenerator;
import org.telegram.relay.mattermost.ids.TestSupport.Times;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.telegram.relay.mattermost.ids.TestSupport.randomPostId;

/**
 * Port of the iOS MessageIdPropertyTests: random interleavings of every allocator operation against
 * a simulated server. After each step: ids are unique, in range and strictly increasing in key order;
 * every renumber plan describes exactly the id changes that happened; islands are contiguous on the
 * server; holes contain no mapped ids; the start/latest flags are true; posts with several parts keep
 * exactly their current parts.
 */
public class MessageIdPropertyTest {
    private static final class Simulation {
        final String channel = "c";
        final MessageIdAllocator allocator;
        final SeededGenerator rng;
        final FakeServer server;
        final InMemoryMessageIdStore store = new InMemoryMessageIdStore();
        /** The ids the store should hold, maintained only from operation results. */
        final Map<String, Integer> shadow = new HashMap<String, Integer>();
        boolean connected = true;
        /** Posts created while disconnected; delivered by catch-up on reconnect. */
        List<PostOrderKey> undelivered = new ArrayList<PostOrderKey>();
        long clock;
        final Map<String, Integer> stats = new HashMap<String, Integer>();
        /** Current part count of posts with more than one part. */
        final Map<String, Integer> partCounts = new HashMap<String, Integer>();
        /** Posts whose part count shrank (removed parts stay tombstoned, so they never grow again). */
        final Set<String> shrunk = new HashSet<String>();
        final List<String> failures = new ArrayList<String>();

        Simulation(long seed, MessageIdAllocator.Configuration configuration, int initialPosts) {
            allocator = new MessageIdAllocator(configuration);
            rng = new SeededGenerator(seed);
            List<PostOrderKey> posts = new ArrayList<PostOrderKey>();
            // Imported 2013 history, then a gap, then regular traffic with bursts.
            long time = Times.YEAR_2013;
            for (int index = 0; index < initialPosts; index++) {
                if (index == initialPosts / 4) {
                    time = Times.YEAR_2025;
                }
                long roll = rng.nextMod(10);
                if (roll == 0) {
                    // same millisecond
                } else if (roll == 1) {
                    time += rng.nextMod(1000);
                } else {
                    time += rng.nextMod(6 * 3_600_000L);
                }
                PostOrderKey post = new PostOrderKey(time, randomPostId(rng));
                posts.add(post);
                if (rng.nextMod(8) == 0) {
                    partCounts.put(post.postId, 2 + (int) rng.nextMod(4));
                }
            }
            server = new FakeServer(posts);
            clock = time + 60_000;
        }

        int random(int bound) {
            return rng.random(bound);
        }

        int partCount(String postId) {
            Integer count = partCounts.get(postId);
            return count != null ? count : 1;
        }

        /** A page as the sync engine ingests it: every post followed by its parts. */
        List<PostOrderKey> expand(List<PostOrderKey> posts) {
            List<PostOrderKey> result = new ArrayList<PostOrderKey>();
            for (PostOrderKey post : posts) {
                result.addAll(Arrays.asList(post.parts(partCount(post.postId))));
            }
            return result;
        }

        PostOrderKey newPost(long createAt) {
            PostOrderKey post = new PostOrderKey(createAt, randomPostId(rng));
            if (random(8) == 0) {
                partCounts.put(post.postId, 2 + random(4));
            }
            return post;
        }

        void check(boolean condition, String message) {
            if (!condition) {
                failures.add(message);
            }
        }

        void checkAnchor(String anchor) {
            check(server.key(anchor) != null, "cursor " + anchor + " is not a server post");
        }

        void count(String name) {
            Integer value = stats.get(name);
            stats.put(name, value == null ? 1 : value + 1);
        }

        void add(String name, int amount) {
            Integer value = stats.get(name);
            stats.put(name, (value == null ? 0 : value) + amount);
        }

        // MARK: Operations

        void step() throws MessageIdAllocationException {
            int roll = random(100);
            if (roll < 10) {
                openLatest();
            } else if (roll < 42) {
                fillRandomHole();
            } else if (roll < 52) {
                permalinkJump();
            } else if (roll < 64) {
                livePost();
            } else if (roll < 72) {
                latePost();
            } else if (roll < 78) {
                burst();
            } else if (roll < 83) {
                deleteRandomPost();
            } else if (roll < 85) {
                editParts();
            } else if (roll < 90) {
                toggleConnection();
            } else {
                replayKnownPage();
            }
        }

        int perPage() {
            return new int[]{1, 5, 30, 60, 200}[random(5)];
        }

        void openLatest() throws MessageIdAllocationException {
            count("openLatest");
            Page page = server.latest(perPage());
            absorb(allocator.ingestLatestPage(expand(page.posts), page.reachedEnd, channel, store));
            if (!connected) {
                allocator.invalidateLatest(channel, store);
            }
        }

        void fillRandomHole() throws MessageIdAllocationException {
            List<HistoryHole> holes = allocator.holes(channel, store);
            if (holes.isEmpty()) {
                count("fillHole.none");
                return;
            }
            HistoryHole hole = holes.get(random(holes.size()));
            HistoryDirection direction = random(2) == 0 ? HistoryDirection.OLDER : HistoryDirection.NEWER;
            int boundary = direction == HistoryDirection.OLDER ? hole.range.upper : hole.range.lower;
            HistoryCursor cursor = allocator.fillCursor(direction, boundary, channel, store);
            int limit = perPage();
            if (cursor == null) {
                count("fillHole.nil");
                return;
            }
            switch (cursor.kind) {
                case LATEST: {
                    count("fillHole.latest");
                    Page page = server.latest(limit);
                    absorb(allocator.ingestLatestPage(expand(page.posts), page.reachedEnd, channel, store));
                    if (!connected) {
                        allocator.invalidateLatest(channel, store);
                    }
                    break;
                }
                case BEFORE: {
                    count("fillHole.before");
                    checkAnchor(cursor.postId);
                    Page page = server.before(cursor.postId, limit);
                    absorb(allocator.ingestPage(expand(page.posts), cursor.postId, HistoryDirection.OLDER, page.reachedEnd, channel, store));
                    break;
                }
                case AFTER: {
                    count("fillHole.after");
                    checkAnchor(cursor.postId);
                    Page page = server.after(cursor.postId, limit);
                    // Only a connected client may treat the newest page as live.
                    absorb(allocator.ingestPage(expand(page.posts), cursor.postId, HistoryDirection.NEWER, page.reachedEnd && connected, channel, store));
                    break;
                }
                default:
                    count("fillHole.nil");
            }
        }

        void permalinkJump() throws MessageIdAllocationException {
            List<PostOrderKey> live = server.livePosts();
            if (live.isEmpty()) {
                return;
            }
            count("permalink");
            PostOrderKey target = live.get(random(live.size()));
            int limit = perPage();
            Page older = server.before(target.postId, limit);
            Page newer = server.after(target.postId, limit);
            // The target's parts follow it, so they belong to the newer side.
            List<PostOrderKey> newerKeys = new ArrayList<PostOrderKey>();
            PostOrderKey[] targetParts = target.parts(partCount(target.postId));
            newerKeys.addAll(Arrays.asList(targetParts).subList(1, targetParts.length));
            newerKeys.addAll(expand(newer.posts));
            absorb(allocator.ingestAround(target, expand(older.posts), newerKeys, older.reachedEnd, newer.reachedEnd && connected, channel, store));
        }

        void livePost() throws MessageIdAllocationException {
            count("live");
            clock += random(5) == 0 ? 0 : random(120_000);
            PostOrderKey post = newPost(clock);
            server.add(post);
            if (connected) {
                absorb(allocator.insertPost(post, partCount(post.postId), channel, store));
            } else {
                undelivered.add(post);
            }
        }

        /** A backdated post (import, delayed bot) delivered by an event; anywhere in time. */
        void latePost() throws MessageIdAllocationException {
            count("late");
            List<PostOrderKey> live = server.livePosts();
            long time;
            if (!live.isEmpty() && random(3) != 0) {
                PostOrderKey neighbour = live.get(random(live.size()));
                time = neighbour.createAt + (random(3) == 0 ? 0 : random(2000));
            } else {
                time = Times.YEAR_2013 + rng.nextMod(clock - Times.YEAR_2013);
            }
            PostOrderKey post = newPost(time);
            server.add(post);
            absorb(allocator.insertPost(post, partCount(post.postId), channel, store));
        }

        /** A bot posting many messages in one millisecond, delivered out of order. */
        void burst() throws MessageIdAllocationException {
            count("burst");
            clock += 1;
            List<PostOrderKey> posts = new ArrayList<PostOrderKey>();
            int n = 1 + random(40);
            for (int i = 0; i < n; i++) {
                posts.add(newPost(clock));
            }
            for (PostOrderKey post : posts) {
                server.add(post);
            }
            if (!connected) {
                undelivered.addAll(posts);
                return;
            }
            rng.shuffle(posts);
            for (PostOrderKey post : posts) {
                absorb(allocator.insertPost(post, partCount(post.postId), channel, store));
            }
        }

        void deleteRandomPost() {
            List<PostOrderKey> live = server.livePosts();
            if (live.isEmpty()) {
                return;
            }
            count("delete");
            PostOrderKey post = live.get(random(live.size()));
            server.delete(post.postId);
            // Deletions are always delivered (event or catch-up).
            List<MessageIdEntry> deleted = allocator.markDeleted(Collections.singletonList(post.postId), channel, store);
            for (MessageIdEntry entry : deleted) {
                check(Integer.valueOf(entry.localId).equals(shadow.get(entry.postId())), "deleted entry id mismatch " + entry);
                check(entry.serverPostId().equals(post.postId), "only the post and its parts are deleted");
            }
            if (shadow.containsKey(post.postId)) {
                for (MessageIdEntry part : store.entriesInRange(channel, post.partsRangeLower(), post.partsRangeUpper())) {
                    check(part.isDeleted, "a deleted post keeps no live parts");
                }
            }
        }

        /** An edit that adds files (more parts) or removes some. */
        void editParts() throws MessageIdAllocationException {
            List<PostOrderKey> live = server.livePosts();
            if (live.isEmpty()) {
                return;
            }
            List<PostOrderKey> multi = new ArrayList<PostOrderKey>();
            for (PostOrderKey post : live) {
                if (partCount(post.postId) > 1) {
                    multi.add(post);
                }
            }
            PostOrderKey post = !multi.isEmpty() && random(2) == 0 ? multi.get(random(multi.size())) : live.get(random(live.size()));
            int current = partCount(post.postId);
            int updated = shrunk.contains(post.postId) ? Math.max(1, current - random(2)) : 1 + random(5);
            if (updated == current) {
                return;
            }
            count(updated > current ? "parts.grow" : "parts.shrink");
            partCounts.put(post.postId, updated);
            if (updated < current) {
                shrunk.add(post.postId);
            }
            if (!shadow.containsKey(post.postId)) {
                // Not loaded: the post comes with its current parts when its page loads.
                return;
            }
            if (updated > current) {
                absorb(allocator.insertPost(post, updated, channel, store));
            } else {
                for (MessageIdEntry entry : allocator.removeParts(post.postId, updated, channel, store)) {
                    check(Integer.valueOf(entry.localId).equals(shadow.get(entry.postId())), "removed part id mismatch " + entry);
                    check(entry.partIndex() >= updated, "removed a part that should stay " + entry);
                }
            }
        }

        void toggleConnection() throws MessageIdAllocationException {
            if (connected) {
                count("disconnect");
                connected = false;
                allocator.invalidateLatest(channel, store);
            } else {
                reconnect();
            }
        }

        /** Catch-up: posts created while offline are applied one by one, oldest first. */
        void reconnect() throws MessageIdAllocationException {
            count("reconnect");
            connected = true;
            List<PostOrderKey> missed = new ArrayList<PostOrderKey>(undelivered);
            Collections.sort(missed);
            undelivered = new ArrayList<PostOrderKey>();
            for (PostOrderKey post : missed) {
                absorb(allocator.insertPost(post, partCount(post.postId), channel, store));
            }
        }

        /** Catch-up and WebSocket resume replay data that is already known. */
        void replayKnownPage() throws MessageIdAllocationException {
            List<MessageIdEntry> entries = store.allEntries(channel);
            if (entries.isEmpty()) {
                return;
            }
            count("replay");
            MessageIdEntry anchor = entries.get(random(entries.size()));
            Page page = server.before(anchor.serverPostId(), perPage());
            absorb(allocator.ingestPage(expand(page.posts), anchor.serverPostId(), HistoryDirection.OLDER, page.reachedEnd, channel, store));
        }

        // MARK: Checking

        void absorb(IngestResult result) {
            RenumberPlan plan = result.renumberPlan;
            if (plan != null) {
                count("renumber");
                add("renumberedPosts", plan.moves.size());
                Set<String> seen = new HashSet<String>();
                for (RenumberMove move : plan.moves) {
                    check(seen.add(move.postId), "post moved twice in one plan");
                    check(Integer.valueOf(move.from).equals(shadow.get(move.postId)), "plan must start from the stored id: " + move);
                    check(move.from != move.to, "no-op move " + move);
                    check(plan.affectedRange.contains(move.from) && plan.affectedRange.contains(move.to), "move outside affected range " + move);
                }
                for (RenumberMove move : plan.moves) {
                    shadow.put(move.postId, move.to);
                }
            }
            for (PostAllocation allocation : result.allocations) {
                switch (allocation.status) {
                    case INSERTED:
                        check(!shadow.containsKey(allocation.postId), "inserted twice " + allocation);
                        shadow.put(allocation.postId, allocation.localId);
                        break;
                    case EXISTING:
                        check(allocation.localId != null && allocation.localId.equals(shadow.get(allocation.postId)), "existing id mismatch " + allocation);
                        break;
                    case DELETED:
                        if (allocation.localId != null) {
                            check(allocation.localId.equals(shadow.get(allocation.postId)), "deleted id mismatch " + allocation);
                        }
                        break;
                    default:
                        break;
                }
            }
        }

        List<String> check(int step) {
            List<String> problems = new ArrayList<String>(store.validate(channel, allocator.configuration.maxId));
            problems.addAll(failures);
            failures.clear();
            List<MessageIdEntry> entries = store.allEntries(channel);
            if (entries.size() != shadow.size()) {
                problems.add("store has " + entries.size() + " entries, shadow " + shadow.size());
            }
            for (MessageIdEntry entry : entries) {
                if (!Integer.valueOf(entry.localId).equals(shadow.get(entry.postId()))) {
                    problems.add(entry + " but shadow says " + shadow.get(entry.postId()));
                }
            }
            List<HistoryIsland> islands = store.islands(channel);
            Set<String> pending = new HashSet<String>();
            for (PostOrderKey post : undelivered) {
                pending.add(post.postId);
            }
            for (HistoryIsland island : islands) {
                for (PostOrderKey post : server.livePosts(island.lower, island.upper)) {
                    if (pending.contains(post.postId)) {
                        continue;
                    }
                    if (!shadow.containsKey(post.postId)) {
                        problems.add("island " + island + " misses server post " + post);
                        continue;
                    }
                    PostOrderKey[] parts = post.parts(partCount(post.postId));
                    for (int i = 1; i < parts.length; i++) {
                        if (!shadow.containsKey(parts[i].postId)) {
                            problems.add("post " + post + " misses part " + parts[i]);
                        }
                    }
                }
            }
            // Mapped parts: consecutive with their post, and live exactly while the post has them.
            MessageIdEntry previous = null;
            for (MessageIdEntry entry : entries) {
                if (entry.partIndex() > 0) {
                    if (previous != null && (!previous.serverPostId().equals(entry.serverPostId()) || previous.partIndex() != entry.partIndex() - 1)) {
                        problems.add("part " + entry + " does not follow " + entry.serverPostId() + " directly (after " + previous + ")");
                    }
                    boolean isLive = !server.isDeleted(entry.serverPostId()) && entry.partIndex() < partCount(entry.serverPostId());
                    if (isLive == entry.isDeleted) {
                        problems.add("part " + entry + " is " + (entry.isDeleted ? "deleted" : "live") + " but the post has " + partCount(entry.serverPostId()) + " parts");
                    }
                }
                previous = entry;
            }
            if (!islands.isEmpty()) {
                HistoryIsland first = islands.get(0);
                if (first.reachesStart && server.hasLivePostOlderThan(first.lower)) {
                    problems.add("lowest island claims the channel start but older posts exist");
                }
                HistoryIsland last = islands.get(islands.size() - 1);
                if (last.reachesLatest && server.hasLivePostNewerThan(last.upper)) {
                    problems.add("highest island claims to be live but newer posts exist");
                }
            }
            for (HistoryHole hole : allocator.holes(channel, store)) {
                for (MessageIdEntry entry : store.entriesFromLocalId(channel, hole.range.lower, true, 1)) {
                    if (hole.range.contains(entry.localId)) {
                        problems.add("hole " + hole + " contains a mapped id");
                    }
                }
            }
            List<String> result = new ArrayList<String>();
            for (String problem : problems) {
                result.add("step " + step + ": " + problem);
            }
            return result;
        }
    }

    private Map<String, Integer> run(long seed, int steps, MessageIdAllocator.Configuration configuration, int initialPosts) throws MessageIdAllocationException {
        Simulation simulation = new Simulation(seed, configuration, initialPosts);
        for (int step = 0; step < steps; step++) {
            simulation.step();
            // Plan consistency is checked on every operation (absorb); the full scan every few steps.
            if (step % 3 != 2 && step != steps - 1) {
                continue;
            }
            List<String> problems = simulation.check(step);
            if (!problems.isEmpty()) {
                fail("seed " + seed + ": " + problems.subList(0, Math.min(5, problems.size())));
            }
        }
        // Finally reconnect, load everything and compare with the server.
        if (!simulation.connected) {
            simulation.reconnect();
        }
        int guard = 0;
        while (true) {
            List<HistoryHole> holes = simulation.allocator.holes(simulation.channel, simulation.store);
            if (holes.isEmpty()) {
                break;
            }
            guard++;
            if (guard > 10_000) {
                fail("seed " + seed + ": holes never close");
            }
            HistoryCursor cursor = simulation.allocator.fillCursor(HistoryDirection.OLDER, holes.get(0).range.upper, simulation.channel, simulation.store);
            if (cursor != null && cursor.kind == HistoryCursor.Kind.BEFORE) {
                simulation.checkAnchor(cursor.postId);
                Page page = simulation.server.before(cursor.postId, 200);
                simulation.absorb(simulation.allocator.ingestPage(simulation.expand(page.posts), cursor.postId, HistoryDirection.OLDER, page.reachedEnd, simulation.channel, simulation.store));
            } else {
                Page page = simulation.server.latest(200);
                simulation.absorb(simulation.allocator.ingestLatestPage(simulation.expand(page.posts), page.reachedEnd, simulation.channel, simulation.store));
            }
        }
        assertEquals("seed " + seed, Collections.<String>emptyList(), simulation.check(steps));
        Set<String> mapped = new HashSet<String>();
        for (MessageIdEntry entry : simulation.store.allEntries(simulation.channel)) {
            if (!entry.isDeleted) {
                mapped.add(entry.postId());
            }
        }
        for (PostOrderKey post : simulation.server.livePosts()) {
            for (PostOrderKey part : post.parts(simulation.partCount(post.postId))) {
                assertTrue("seed " + seed + ": " + part + " never loaded", mapped.contains(part.postId));
            }
        }
        if (System.getenv("SIM_STATS") != null) {
            System.out.println("STATS seed " + seed + ": " + simulation.stats + " entries=" + simulation.store.allEntries(simulation.channel).size());
        }
        return simulation.stats;
    }

    private static void merge(Map<String, Integer> totals, Map<String, Integer> stats) {
        for (Map.Entry<String, Integer> entry : stats.entrySet()) {
            Integer value = totals.get(entry.getKey());
            totals.put(entry.getKey(), (value == null ? 0 : value) + entry.getValue());
        }
    }

    private static int stat(Map<String, Integer> totals, String key) {
        Integer value = totals.get(key);
        return value == null ? 0 : value;
    }

    @Test
    public void randomInterleavingsWithDefaultConfiguration() throws Exception {
        Map<String, Integer> totals = new HashMap<String, Integer>();
        for (long seed = 1; seed <= 4; seed++) {
            merge(totals, run(seed, 400, MessageIdAllocator.Configuration.DEFAULT, 1000));
        }
        assertTrue(stat(totals, "permalink") > 0);
        assertTrue(stat(totals, "burst") > 0);
        assertTrue("same-millisecond bursts exhaust midpoints", stat(totals, "renumber") > 0);
        assertTrue(stat(totals, "parts.grow") > 0);
        assertTrue(stat(totals, "parts.shrink") > 0);
    }

    /** A small id space so holes run out constantly and every renumber path is exercised. */
    @Test
    public void randomInterleavingsInATightIdSpace() throws Exception {
        MessageIdAllocator.Configuration configuration = MessageIdAllocator.Configuration.withIdSpace(16, 1 << 13, 1 << 14);
        Map<String, Integer> totals = new HashMap<String, Integer>();
        for (long seed = 100; seed <= 105; seed++) {
            merge(totals, run(seed, 300, configuration, 800));
        }
        assertTrue(stat(totals, "renumber") > 20);
    }

    /** Nearly full: forces whole-channel relayouts. */
    @Test
    public void randomInterleavingsInANearlyFullIdSpace() throws Exception {
        MessageIdAllocator.Configuration configuration = MessageIdAllocator.Configuration.withIdSpace(16, 1500, 3000);
        for (long seed = 200; seed <= 202; seed++) {
            run(seed, 250, configuration, 600);
        }
    }
}
