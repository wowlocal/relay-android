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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.telegram.relay.mattermost.ids.TestSupport.ids;
import static org.telegram.relay.mattermost.ids.TestSupport.keys;
import static org.telegram.relay.mattermost.ids.TestSupport.localIds;
import static org.telegram.relay.mattermost.ids.TestSupport.makePosts;
import static org.telegram.relay.mattermost.ids.TestSupport.randomPostId;

/** Port of the iOS MessageIdAllocatorTests (Spike C scenarios). */
public class MessageIdAllocatorTest {
    private static final String CHANNEL = "channel-1";
    private static final int FIRST = 1 << 30;

    private static void assertValid(InMemoryMessageIdStore store) {
        assertValid(store, Integer.MAX_VALUE);
    }

    private static void assertValid(InMemoryMessageIdStore store, int maxId) {
        assertEquals(Collections.emptyList(), store.validate(CHANNEL, maxId));
    }

    private static List<IdRange> ranges(List<HistoryHole> holes) {
        List<IdRange> result = new ArrayList<IdRange>();
        for (HistoryHole hole : holes) {
            result.add(hole.range);
        }
        return result;
    }

    private static int localId(InMemoryMessageIdStore store, String postId) {
        MessageIdEntry entry = store.entries(CHANNEL, Collections.singletonList(postId)).get(postId);
        assertNotNull(entry);
        return entry.localId;
    }

    // MARK: First post / initial page

    @Test
    public void firstPostGetsTwoToTheThirtieth() throws Exception {
        SeededGenerator rng = new SeededGenerator(1);
        InMemoryMessageIdStore store = new InMemoryMessageIdStore();
        MessageIdAllocator allocator = new MessageIdAllocator();
        PostOrderKey post = new PostOrderKey(Times.YEAR_2026, randomPostId(rng));
        IngestResult result = allocator.insertPost(post, CHANNEL, store);
        assertEquals(Collections.singletonList(new PostAllocation(post.postId, FIRST, PostAllocation.Status.INSERTED)), result.allocations);
        // A single post with no context: holes on both sides.
        assertEquals(Arrays.asList(new IdRange(1, FIRST - 1), new IdRange(FIRST + 1, Integer.MAX_VALUE)), ranges(result.holes));
        assertValid(store);
    }

    @Test
    public void initialPage() throws Exception {
        SeededGenerator rng = new SeededGenerator(2);
        InMemoryMessageIdStore store = new InMemoryMessageIdStore();
        MessageIdAllocator allocator = new MessageIdAllocator();
        List<PostOrderKey> posts = makePosts(60, Times.YEAR_2026, 1000, rng);
        IngestResult result = allocator.ingestLatestPage(rng.shuffled(posts), false, CHANNEL, store);

        assertEquals(60, result.insertedCount());
        assertNull(result.renumberPlan);
        List<String> allocated = new ArrayList<String>();
        List<Integer> allocatedIds = new ArrayList<Integer>();
        for (PostAllocation allocation : result.allocations) {
            allocated.add(allocation.postId);
            allocatedIds.add(allocation.localId);
        }
        assertEquals("allocations are reported in key order", ids(posts), allocated);
        List<Integer> expected = new ArrayList<Integer>();
        for (int i = 0; i < 60; i++) {
            expected.add(FIRST - (59 - i) * 16);
        }
        assertEquals(expected, allocatedIds);
        assertEquals(new IdRange(expected.get(0), expected.get(59)), result.islandRange);
        // Live at the top, unloaded history below.
        assertEquals(Collections.singletonList(new HistoryHole(new IdRange(1, expected.get(0) - 1), null, posts.get(0))), result.holes);
        assertEquals(HistoryCursor.before(posts.get(0).postId), result.holes.get(0).cursor(HistoryDirection.OLDER));
        assertValid(store);

        // Replaying the same page is a no-op.
        IngestResult replay = allocator.ingestLatestPage(posts, false, CHANNEL, store);
        assertEquals(0, replay.insertedCount());
        for (PostAllocation allocation : replay.allocations) {
            assertEquals(PostAllocation.Status.EXISTING, allocation.status);
        }
        assertEquals(expected, localIds(store.allEntries(CHANNEL)));
    }

    @Test
    public void emptyChannelHasOneHole() {
        InMemoryMessageIdStore store = new InMemoryMessageIdStore();
        MessageIdAllocator allocator = new MessageIdAllocator();
        assertEquals(Collections.singletonList(new HistoryHole(new IdRange(1, Integer.MAX_VALUE), null, null)), allocator.holes(CHANNEL, store));
        assertEquals(HistoryCursor.LATEST, allocator.fillCursor(HistoryDirection.OLDER, Integer.MAX_VALUE, CHANNEL, store));
        assertNull(allocator.fillCursor(HistoryDirection.NEWER, 1, CHANNEL, store));
    }

    // MARK: Paging

    @Test
    public void backwardPagingOverTenThousandPosts() throws Exception {
        SeededGenerator rng = new SeededGenerator(3);
        // Irregular timestamps, including bursts in the same millisecond.
        long time = Times.YEAR_2025;
        List<PostOrderKey> posts = new ArrayList<PostOrderKey>();
        for (int i = 0; i < 10_000; i++) {
            if (rng.nextMod(10) != 0) {
                time += rng.nextMod(120_000);
            }
            posts.add(new PostOrderKey(time, randomPostId(rng)));
        }
        FakeServer server = new FakeServer(posts);
        InMemoryMessageIdStore store = new InMemoryMessageIdStore();
        MessageIdAllocator allocator = new MessageIdAllocator();

        Page first = server.latest(60);
        allocator.ingestLatestPage(first.posts, first.reachedEnd, CHANNEL, store);
        int pages = 1;
        while (true) {
            List<HistoryHole> holes = allocator.holes(CHANNEL, store);
            if (holes.isEmpty()) {
                break;
            }
            HistoryCursor cursor = allocator.fillCursor(HistoryDirection.OLDER, holes.get(0).range.upper, CHANNEL, store);
            assertNotNull(cursor);
            assertEquals("expected a before cursor", HistoryCursor.Kind.BEFORE, cursor.kind);
            Page page = server.before(cursor.postId, 60);
            IngestResult result = allocator.ingestPage(page.posts, cursor.postId, HistoryDirection.OLDER, page.reachedEnd, CHANNEL, store);
            assertNull(result.renumberPlan);
            assertEquals(page.posts.size(), result.insertedCount());
            pages++;
            assertTrue(pages < 200);
        }

        List<MessageIdEntry> entries = store.allEntries(CHANNEL);
        assertEquals(server.posts, keys(entries));
        assertEquals(FIRST, entries.get(entries.size() - 1).localId);
        for (int i = 1; i < entries.size(); i++) {
            assertEquals(16, entries.get(i).localId - entries.get(i - 1).localId);
        }
        assertEquals(1, allocator.islandSummaries(CHANNEL, store).size());
        assertTrue(allocator.holes(CHANNEL, store).isEmpty());
        assertValid(store);
    }

    @Test
    public void permalinkJumpThenPagingUntilIslandsJoin() throws Exception {
        SeededGenerator rng = new SeededGenerator(4);
        List<PostOrderKey> posts = makePosts(3000, Times.YEAR_2025, 10 * 60 * 1000, rng);
        FakeServer server = new FakeServer(posts);
        InMemoryMessageIdStore store = new InMemoryMessageIdStore();
        MessageIdAllocator allocator = new MessageIdAllocator();

        Page latest = server.latest(60);
        allocator.ingestLatestPage(latest.posts, false, CHANNEL, store);
        PostOrderKey latestBottom = latest.posts.get(0);
        int latestBottomId = localId(store, latestBottom.postId);

        // Jump to a post deep in history.
        PostOrderKey target = posts.get(1000);
        Page older = server.before(target.postId, 30);
        Page newer = server.after(target.postId, 30);
        IngestResult jump = allocator.ingestAround(target, older.posts, newer.posts, older.reachedEnd, false, CHANNEL, store);
        assertEquals(61, jump.insertedCount());
        assertFalse(jump.didCloseHole());
        assertNull(jump.renumberPlan);
        IdRange islandRange = jump.islandRange;
        assertNotNull(islandRange);
        assertEquals("holes below the new island and between the islands", 2, jump.holes.size());
        assertEquals(new IdRange(1, islandRange.lower - 1), jump.holes.get(0).range);
        assertEquals(new IdRange(islandRange.upper + 1, latestBottomId - 1), jump.holes.get(1).range);
        assertEquals(newer.posts.get(newer.posts.size() - 1), jump.holes.get(1).lowerBoundary);
        assertEquals(latestBottom, jump.holes.get(1).upperBoundary);
        Set<Integer> spacings = new HashSet<Integer>();
        for (int i = 1; i < jump.allocations.size(); i++) {
            spacings.add(jump.allocations.get(i).localId - jump.allocations.get(i - 1).localId);
        }
        assertEquals(Collections.singleton(16), spacings);

        // Proportional placement: the island sits in the gap roughly where its time sits.
        long floor = allocator.configuration.historyFloorTime;
        double timeFraction = (double) (target.createAt - floor) / (latestBottom.createAt - floor);
        double idFraction = (double) islandRange.lower / latestBottomId;
        assertEquals(timeFraction, idFraction, 0.1);

        // Page newer from the island until it reaches the latest island.
        boolean closed = false;
        int pages = 0;
        while (!closed) {
            HistoryHole middle = null;
            for (HistoryHole hole : allocator.holes(CHANNEL, store)) {
                if (hole.lowerBoundary != null && hole.upperBoundary != null) {
                    middle = hole;
                    break;
                }
            }
            assertNotNull(middle);
            HistoryCursor cursor = allocator.fillCursor(HistoryDirection.NEWER, middle.range.lower, CHANNEL, store);
            assertNotNull(cursor);
            assertEquals("expected an after cursor", HistoryCursor.Kind.AFTER, cursor.kind);
            assertEquals(HistoryCursor.after(cursor.postId), middle.cursor(HistoryDirection.NEWER));
            Page page = server.after(cursor.postId, 60);
            IngestResult result = allocator.ingestPage(page.posts, cursor.postId, HistoryDirection.NEWER, page.reachedEnd, CHANNEL, store);
            assertNull(result.renumberPlan);
            closed = result.didCloseHole();
            pages++;
            assertTrue(pages < 100);
        }
        assertEquals(1, allocator.islandSummaries(CHANNEL, store).size());
        List<HistoryHole> holes = allocator.holes(CHANNEL, store);
        assertEquals("only the hole below the merged island is left", 1, holes.size());
        assertNull(holes.get(0).lowerBoundary);
        // Everything from the jump target to the latest post is loaded and ordered.
        assertEquals(posts.subList(1000 - 30, posts.size()), keys(store.allEntries(CHANNEL)));
        assertValid(store);
    }

    // MARK: Late inserts

    @Test
    public void lateArrivingPostBetweenAdjacentPosts() throws Exception {
        SeededGenerator rng = new SeededGenerator(5);
        List<PostOrderKey> posts = makePosts(60, Times.YEAR_2026, 60_000, rng);
        InMemoryMessageIdStore store = new InMemoryMessageIdStore();
        MessageIdAllocator allocator = new MessageIdAllocator();
        allocator.ingestLatestPage(posts, true, CHANNEL, store);
        List<MessageIdEntry> before = store.allEntries(CHANNEL);

        PostOrderKey late = new PostOrderKey(posts.get(10).createAt + 30_000, randomPostId(rng));
        IngestResult result = allocator.insertPost(late, CHANNEL, store);
        int a = before.get(10).localId;
        int b = before.get(11).localId;
        assertEquals(Integer.valueOf(a + (b - a) / 2), result.localId(late.postId));
        assertNull(result.renumberPlan);
        assertEquals(1, result.joinedIslandCount);
        assertTrue("the island still covers the whole channel", result.holes.isEmpty());
        assertValid(store);

        // Idempotent.
        IngestResult again = allocator.insertPost(late, CHANNEL, store);
        assertEquals(Collections.singletonList(new PostAllocation(late.postId, a + (b - a) / 2, PostAllocation.Status.EXISTING)), again.allocations);
    }

    @Test
    public void livePostExtendsLatestIsland() throws Exception {
        SeededGenerator rng = new SeededGenerator(6);
        List<PostOrderKey> posts = makePosts(10, Times.YEAR_2026, 60_000, rng);
        InMemoryMessageIdStore store = new InMemoryMessageIdStore();
        MessageIdAllocator allocator = new MessageIdAllocator();
        allocator.ingestLatestPage(posts, false, CHANNEL, store);
        PostOrderKey live = new PostOrderKey(posts.get(posts.size() - 1).createAt + 5000, randomPostId(rng));
        IngestResult result = allocator.insertPost(live, CHANNEL, store);
        assertEquals(Integer.valueOf(FIRST + 16), result.localId(live.postId));
        assertEquals(1, allocator.islandSummaries(CHANNEL, store).size());

        // After a disconnect the top is no longer known to be live: a new post becomes its own island.
        allocator.invalidateLatest(CHANNEL, store);
        List<HistoryHole> holes = allocator.holes(CHANNEL, store);
        assertNull(holes.get(holes.size() - 1).upperBoundary);
        assertEquals(Integer.MAX_VALUE, holes.get(holes.size() - 1).range.upper);
        PostOrderKey later = new PostOrderKey(live.createAt + 3_600_000, randomPostId(rng));
        IngestResult isolated = allocator.insertPost(later, CHANNEL, store);
        assertEquals(0, isolated.joinedIslandCount);
        assertEquals(2, allocator.islandSummaries(CHANNEL, store).size());
        assertEquals("below, between, above", 3, isolated.holes.size());
        assertValid(store);
    }

    // MARK: Same millisecond / second

    @Test
    public void manyPostsInTheSameMillisecond() throws Exception {
        SeededGenerator rng = new SeededGenerator(7);
        long instant = Times.YEAR_2026 + 123;
        List<PostOrderKey> posts = new ArrayList<PostOrderKey>();
        for (int i = 0; i < 1000; i++) {
            posts.add(new PostOrderKey(instant, randomPostId(rng)));
        }
        Collections.sort(posts);
        FakeServer server = new FakeServer(posts);
        InMemoryMessageIdStore store = new InMemoryMessageIdStore();
        MessageIdAllocator allocator = new MessageIdAllocator();

        Page latest = server.latest(100);
        allocator.ingestLatestPage(latest.posts, false, CHANNEL, store);
        while (true) {
            List<HistoryHole> holes = allocator.holes(CHANNEL, store);
            if (holes.isEmpty()) {
                break;
            }
            HistoryCursor cursor = allocator.fillCursor(HistoryDirection.OLDER, holes.get(0).range.upper, CHANNEL, store);
            assertEquals(HistoryCursor.Kind.BEFORE, cursor.kind);
            Page page = server.before(cursor.postId, 100);
            allocator.ingestPage(rng.shuffled(page.posts), cursor.postId, HistoryDirection.OLDER, page.reachedEnd, CHANNEL, store);
        }
        List<String> stored = new ArrayList<String>();
        for (MessageIdEntry entry : store.allEntries(CHANNEL)) {
            stored.add(entry.postId());
        }
        assertEquals("ordered by post id within the millisecond", ids(posts), stored);
        assertValid(store);
    }

    @Test
    public void manyPostsInTheSameSecondArrivingLive() throws Exception {
        SeededGenerator rng = new SeededGenerator(8);
        long second = Times.YEAR_2026;
        InMemoryMessageIdStore store = new InMemoryMessageIdStore();
        MessageIdAllocator allocator = new MessageIdAllocator();
        List<PostOrderKey> seed = makePosts(5, second - 10_000, 1000, rng);
        allocator.ingestLatestPage(seed, true, CHANNEL, store);

        // A bot posts 400 messages within one second; WebSocket events arrive out of order.
        List<PostOrderKey> burst = new ArrayList<PostOrderKey>();
        for (int i = 0; i < 400; i++) {
            burst.add(new PostOrderKey(second + rng.nextMod(1000), randomPostId(rng)));
        }
        rng.shuffle(burst);
        Map<String, Integer> shadow = new HashMap<String, Integer>();
        for (MessageIdEntry entry : store.allEntries(CHANNEL)) {
            shadow.put(entry.postId(), entry.localId);
        }
        int renumbers = 0;
        for (PostOrderKey post : burst) {
            IngestResult result = allocator.insertPost(post, CHANNEL, store);
            if (result.renumberPlan != null) {
                renumbers++;
                for (RenumberMove move : result.renumberPlan.moves) {
                    assertEquals(Integer.valueOf(move.from), shadow.get(move.postId));
                    shadow.put(move.postId, move.to);
                }
            }
            shadow.put(post.postId, result.localId(post.postId));
        }
        List<PostOrderKey> all = new ArrayList<PostOrderKey>(seed);
        all.addAll(burst);
        Collections.sort(all);
        List<MessageIdEntry> entries = store.allEntries(CHANNEL);
        assertEquals(all, keys(entries));
        for (MessageIdEntry entry : entries) {
            assertEquals(Integer.valueOf(entry.localId), shadow.get(entry.postId()));
        }
        assertTrue("out-of-order arrivals exhaust midpoints", renumbers > 0);
        assertEquals(1, allocator.islandSummaries(CHANNEL, store).size());
        assertValid(store);
    }

    // MARK: Imported history

    @Test
    public void importedPostsFrom2013MixedWith2026() throws Exception {
        SeededGenerator rng = new SeededGenerator(9);
        List<PostOrderKey> imported = makePosts(500, Times.YEAR_2013, Times.DAY / 3, rng);
        List<PostOrderKey> recent = makePosts(500, Times.YEAR_2026, 15 * 60 * 1000, rng);
        List<PostOrderKey> all = new ArrayList<PostOrderKey>(imported);
        all.addAll(recent);
        FakeServer server = new FakeServer(all);
        InMemoryMessageIdStore store = new InMemoryMessageIdStore();
        MessageIdAllocator allocator = new MessageIdAllocator();

        Page latest = server.latest(60);
        allocator.ingestLatestPage(latest.posts, false, CHANNEL, store);
        // Permalink to a 2013 post.
        PostOrderKey target = imported.get(200);
        Page older = server.before(target.postId, 30);
        Page newer = server.after(target.postId, 30);
        IngestResult jump = allocator.ingestAround(target, older.posts, newer.posts, false, false, CHANNEL, store);
        IdRange jumpRange = jump.islandRange;
        assertNotNull(jumpRange);
        assertTrue("a 2013 island sits far below a 2026 island", jumpRange.upper < (1 << 29));
        assertTrue("but leaves room for the older 2013 history below it", jumpRange.lower > (1 << 24));

        // A post imported later with a 2013 timestamp in the unloaded gap becomes its own island.
        PostOrderKey lateImport = new PostOrderKey(imported.get(400).createAt + 1, randomPostId(rng));
        server.add(lateImport);
        IngestResult single = allocator.insertPost(lateImport, CHANNEL, store);
        assertEquals(0, single.joinedIslandCount);
        assertEquals(3, allocator.islandSummaries(CHANNEL, store).size());

        // Load everything until no hole is left.
        int iterations = 0;
        while (true) {
            List<HistoryHole> holes = allocator.holes(CHANNEL, store);
            if (holes.isEmpty()) {
                break;
            }
            iterations++;
            assertTrue(iterations < 100);
            HistoryCursor cursor = allocator.fillCursor(HistoryDirection.OLDER, holes.get(0).range.upper, CHANNEL, store);
            assertEquals(HistoryCursor.Kind.BEFORE, cursor.kind);
            Page page = server.before(cursor.postId, 60);
            IngestResult result = allocator.ingestPage(page.posts, cursor.postId, HistoryDirection.OLDER, page.reachedEnd, CHANNEL, store);
            assertNull(result.renumberPlan);
        }
        assertEquals(server.posts, keys(store.allEntries(CHANNEL)));
        assertValid(store);
    }

    // MARK: Gap exhaustion

    @Test
    public void exhaustedGapInsideIslandProducesMinimalRenumberPlan() throws Exception {
        SeededGenerator rng = new SeededGenerator(10);
        List<PostOrderKey> posts = makePosts(200, Times.YEAR_2026, 60_000, rng);
        InMemoryMessageIdStore store = new InMemoryMessageIdStore();
        MessageIdAllocator allocator = new MessageIdAllocator();
        allocator.ingestLatestPage(posts, true, CHANNEL, store);
        Map<String, Integer> shadow = new HashMap<String, Integer>();
        for (MessageIdEntry entry : store.allEntries(CHANNEL)) {
            shadow.put(entry.postId(), entry.localId);
        }

        // Keep inserting right after posts[100]: 16 → 8 → 4 → 2 → 1, then the gap is exhausted.
        PostOrderKey anchor = posts.get(100);
        RenumberPlan plan = null;
        int inserted = 0;
        for (int offset = 1000; offset > 0; offset -= 100) {
            PostOrderKey late = new PostOrderKey(anchor.createAt + offset, randomPostId(rng));
            IngestResult result = allocator.insertPost(late, CHANNEL, store);
            inserted++;
            if (result.renumberPlan != null) {
                plan = result.renumberPlan;
                for (RenumberMove move : plan.moves) {
                    assertEquals("the plan starts from the stored ids", Integer.valueOf(move.from), shadow.get(move.postId));
                    assertTrue(plan.affectedRange.contains(move.from) && plan.affectedRange.contains(move.to));
                    shadow.put(move.postId, move.to);
                }
            }
            shadow.put(late.postId, result.localId(late.postId));
            if (plan != null) {
                break;
            }
        }
        assertNotNull(plan);
        assertEquals(5, inserted);
        assertFalse(plan.moves.isEmpty());
        assertTrue("only a small window around the insertion moves", plan.moves.size() < 20);
        for (MessageIdEntry entry : store.allEntries(CHANNEL)) {
            assertEquals(Integer.valueOf(entry.localId), shadow.get(entry.postId()));
        }
        assertValid(store);
    }

    @Test
    public void exhaustedHoleRenumbersTheSmallerIsland() throws Exception {
        SeededGenerator rng = new SeededGenerator(11);
        MessageIdAllocator.Configuration configuration = MessageIdAllocator.Configuration.withIdSpace(16, 50_000, 100_000);
        MessageIdAllocator allocator = new MessageIdAllocator(configuration);
        // An old cluster, a dense stretch of 800 posts, then 1500 recent posts.
        List<PostOrderKey> old = makePosts(20, Times.YEAR_2025, 60_000, rng);
        List<PostOrderKey> dense = makePosts(800, Times.YEAR_2026 - 800 * 1000, 1000, rng);
        List<PostOrderKey> recent = makePosts(1500, Times.YEAR_2026, 60_000, rng);
        List<PostOrderKey> all = new ArrayList<PostOrderKey>(old);
        all.addAll(dense);
        all.addAll(recent);
        FakeServer server = new FakeServer(all);
        InMemoryMessageIdStore store = new InMemoryMessageIdStore();

        Page latest = server.latest(1500);
        allocator.ingestLatestPage(latest.posts, false, CHANNEL, store);
        // Jump to the start of the dense stretch: a small island just below the large latest one.
        PostOrderKey target = dense.get(0);
        IngestResult jump = allocator.ingestAround(target, server.before(target.postId, 5).posts, server.after(target.postId, 5).posts, false, false, CHANNEL, store);
        assertNull(jump.renumberPlan);

        // Page forward through the dense stretch: the hole between the islands runs out.
        Map<String, Integer> shadow = new HashMap<String, Integer>();
        for (MessageIdEntry entry : store.allEntries(CHANNEL)) {
            shadow.put(entry.postId(), entry.localId);
        }
        Set<String> recentIds = new HashSet<String>(ids(recent));
        int plans = 0;
        boolean closed = false;
        while (!closed) {
            HistoryHole middle = null;
            for (HistoryHole hole : allocator.holes(CHANNEL, store)) {
                if (hole.lowerBoundary != null && hole.upperBoundary != null) {
                    middle = hole;
                    break;
                }
            }
            assertNotNull(middle);
            HistoryCursor cursor = middle.cursor(HistoryDirection.NEWER);
            assertEquals(HistoryCursor.Kind.AFTER, cursor.kind);
            int smallIslandSize = 0;
            for (MessageIdEntry entry : store.allEntries(CHANNEL)) {
                if (!recentIds.contains(entry.postId())) {
                    smallIslandSize++;
                }
            }
            Page page = server.after(cursor.postId, 100);
            IngestResult result = allocator.ingestPage(page.posts, cursor.postId, HistoryDirection.NEWER, page.reachedEnd, CHANNEL, store);
            if (result.renumberPlan != null) {
                plans++;
                for (RenumberMove move : result.renumberPlan.moves) {
                    assertFalse("the larger latest island is not moved", recentIds.contains(move.postId));
                }
                assertTrue(result.renumberPlan.moves.size() <= smallIslandSize);
                for (RenumberMove move : result.renumberPlan.moves) {
                    assertEquals(Integer.valueOf(move.from), shadow.get(move.postId));
                    shadow.put(move.postId, move.to);
                }
            }
            for (PostAllocation allocation : result.allocations) {
                if (allocation.status == PostAllocation.Status.INSERTED) {
                    shadow.put(allocation.postId, allocation.localId);
                }
            }
            closed = result.didCloseHole();
            assertValid(store, configuration.maxId);
        }
        assertTrue(plans > 0);
        List<MessageIdEntry> entries = store.allEntries(CHANNEL);
        assertEquals(server.posts.subList(old.size() - 5, server.posts.size()), keys(entries));
        for (MessageIdEntry entry : entries) {
            assertEquals(Integer.valueOf(entry.localId), shadow.get(entry.postId()));
        }
        // The renumbered island got its STEP spacing back.
        Set<Integer> spacings = new HashSet<Integer>();
        Integer previous = null;
        for (PostOrderKey key : dense.subList(0, 100)) {
            Integer id = shadow.get(key.postId);
            if (id == null) {
                continue;
            }
            if (previous != null) {
                spacings.add(id - previous);
            }
            previous = id;
        }
        assertEquals(Collections.singleton(16), spacings);
    }

    // MARK: Cursors and tombstones

    @Test
    public void holeFillCursorTranslation() throws Exception {
        SeededGenerator rng = new SeededGenerator(12);
        List<PostOrderKey> posts = makePosts(500, Times.YEAR_2026, 60_000, rng);
        FakeServer server = new FakeServer(posts);
        InMemoryMessageIdStore store = new InMemoryMessageIdStore();
        MessageIdAllocator allocator = new MessageIdAllocator();
        allocator.ingestLatestPage(server.latest(50).posts, false, CHANNEL, store);
        PostOrderKey target = posts.get(100);
        allocator.ingestAround(target, server.before(target.postId, 10).posts, server.after(target.postId, 10).posts, false, false, CHANNEL, store);

        List<HistoryHole> holes = allocator.holes(CHANNEL, store);
        assertEquals(2, holes.size());
        HistoryHole middle = holes.get(1);
        assertEquals(HistoryCursor.before(posts.get(450).postId), allocator.fillCursor(HistoryDirection.OLDER, middle.range.upper, CHANNEL, store));
        assertEquals(HistoryCursor.after(posts.get(110).postId), allocator.fillCursor(HistoryDirection.NEWER, middle.range.lower, CHANNEL, store));
        // A boundary that is itself a mapped id.
        int boundaryId = middle.range.upper + 1;
        assertEquals(HistoryCursor.before(posts.get(450).postId), allocator.fillCursor(HistoryDirection.OLDER, boundaryId, CHANNEL, store));
        assertEquals(HistoryCursor.around(posts.get(450).postId), allocator.aroundCursor(boundaryId, CHANNEL, store));
        assertNull(allocator.aroundCursor(middle.range.lower, CHANNEL, store));
        // Nothing below the lowest island: can't page forward from the channel start.
        assertNull(allocator.fillCursor(HistoryDirection.NEWER, 1, CHANNEL, store));
        assertEquals(HistoryCursor.before(posts.get(90).postId), holes.get(0).cursor(HistoryDirection.OLDER));

        // A tombstoned boundary post is skipped in favour of a live one in the same island.
        allocator.markDeleted(Collections.singletonList(posts.get(450).postId), CHANNEL, store);
        assertEquals(HistoryCursor.before(posts.get(451).postId), allocator.fillCursor(HistoryDirection.OLDER, middle.range.upper, CHANNEL, store));
    }

    @Test
    public void tombstonesPreventResurrection() throws Exception {
        SeededGenerator rng = new SeededGenerator(13);
        List<PostOrderKey> posts = makePosts(20, Times.YEAR_2026, 60_000, rng);
        InMemoryMessageIdStore store = new InMemoryMessageIdStore();
        MessageIdAllocator allocator = new MessageIdAllocator();
        allocator.ingestLatestPage(posts.subList(0, 10), true, CHANNEL, store);
        int mappedId = localId(store, posts.get(3).postId);

        List<MessageIdEntry> deleted = allocator.markDeleted(Arrays.asList(posts.get(3).postId, posts.get(15).postId), CHANNEL, store);
        assertEquals(1, deleted.size());
        assertEquals(posts.get(3).postId, deleted.get(0).postId());
        assertEquals("a deleted post keeps its id", mappedId, deleted.get(0).localId);

        // A replayed posted event for either post doesn't bring it back.
        IngestResult replayMapped = allocator.insertPost(posts.get(3), CHANNEL, store);
        assertEquals(Collections.singletonList(new PostAllocation(posts.get(3).postId, mappedId, PostAllocation.Status.DELETED)), replayMapped.allocations);
        IngestResult replayUnmapped = allocator.insertPost(posts.get(15), CHANNEL, store);
        assertEquals(Collections.singletonList(new PostAllocation(posts.get(15).postId, null, PostAllocation.Status.DELETED)), replayUnmapped.allocations);
        IngestResult page = allocator.ingestLatestPage(posts.subList(10, 20), false, CHANNEL, store);
        PostAllocation fifteen = null;
        for (PostAllocation allocation : page.allocations) {
            if (allocation.postId.equals(posts.get(15).postId)) {
                fifteen = allocation;
            }
        }
        assertNotNull(fifteen);
        assertEquals(PostAllocation.Status.DELETED, fifteen.status);
        assertEquals(9, page.insertedCount());
        assertNull(store.entries(CHANNEL, Collections.singletonList(posts.get(15).postId)).get(posts.get(15).postId));
        assertValid(store);
    }

    @Test
    public void unknownAnchorThrows() {
        InMemoryMessageIdStore store = new InMemoryMessageIdStore();
        MessageIdAllocator allocator = new MessageIdAllocator();
        try {
            allocator.ingestPage(new ArrayList<PostOrderKey>(), "missing", HistoryDirection.OLDER, false, CHANNEL, store);
            fail("expected unknownAnchor");
        } catch (MessageIdAllocationException e) {
            assertEquals(MessageIdAllocationException.Kind.UNKNOWN_ANCHOR, e.kind);
            assertEquals("missing", e.id);
        }
    }

    @Test
    public void capacityExhaustedThrows() {
        SeededGenerator rng = new SeededGenerator(14);
        MessageIdAllocator allocator = new MessageIdAllocator(MessageIdAllocator.Configuration.withIdSpace(16, 32, 64));
        InMemoryMessageIdStore store = new InMemoryMessageIdStore();
        List<PostOrderKey> posts = makePosts(70, Times.YEAR_2026, 1000, rng);
        try {
            allocator.ingestLatestPage(posts, true, CHANNEL, store);
            fail("expected capacityExhausted");
        } catch (MessageIdAllocationException e) {
            assertEquals(MessageIdAllocationException.Kind.CAPACITY_EXHAUSTED, e.kind);
            assertEquals(CHANNEL, e.id);
        }
        assertTrue("nothing is written on failure", store.allEntries(CHANNEL).isEmpty());
    }

    /** Regression: the in-memory store computed start + limit and overflowed. */
    @Test
    public void storeQueriesAcceptAnyLimit() throws Exception {
        SeededGenerator rng = new SeededGenerator(16);
        InMemoryMessageIdStore store = new InMemoryMessageIdStore();
        List<PostOrderKey> posts = makePosts(10, Times.YEAR_2026, 60_000, rng);
        new MessageIdAllocator().ingestLatestPage(posts, true, CHANNEL, store);
        List<MessageIdEntry> all = store.allEntries(CHANNEL);
        assertEquals(all.subList(4, 10), store.entriesNewerThan(CHANNEL, posts.get(3), Integer.MAX_VALUE));
        assertEquals(all, store.entriesNewerThan(CHANNEL, null, Integer.MAX_VALUE));
        List<MessageIdEntry> olderReversed = new ArrayList<MessageIdEntry>(all.subList(0, 3));
        Collections.reverse(olderReversed);
        assertEquals(olderReversed, store.entriesOlderThan(CHANNEL, posts.get(3), Integer.MAX_VALUE));
        assertEquals(all.subList(2, 10), store.entriesFromLocalId(CHANNEL, all.get(2).localId, true, Integer.MAX_VALUE));
        List<MessageIdEntry> downReversed = new ArrayList<MessageIdEntry>(all.subList(0, 3));
        Collections.reverse(downReversed);
        assertEquals(downReversed, store.entriesFromLocalId(CHANNEL, all.get(2).localId, false, Integer.MAX_VALUE));
        assertTrue(store.entriesNewerThan(CHANNEL, null, 0).isEmpty());
        assertTrue(store.entriesOlderThan(CHANNEL, null, -1).isEmpty());
    }

    // MARK: Parts

    @Test
    public void postPartsGetConsecutiveIdsAndDeleteTogether() throws Exception {
        SeededGenerator rng = new SeededGenerator(17);
        List<PostOrderKey> posts = makePosts(10, Times.YEAR_2026, 60_000, rng);
        InMemoryMessageIdStore store = new InMemoryMessageIdStore();
        MessageIdAllocator allocator = new MessageIdAllocator();
        allocator.ingestLatestPage(posts, true, CHANNEL, store);
        PostOrderKey album = new PostOrderKey(posts.get(4).createAt + 10, randomPostId(rng));
        IngestResult result = allocator.insertPost(album, 3, CHANNEL, store);
        assertEquals(3, result.insertedCount());
        List<MessageIdEntry> parts = allocator.partEntries(album.postId, CHANNEL, store);
        assertEquals(3, parts.size());
        assertEquals(album.postId, parts.get(0).postId());
        assertEquals(PostParts.partId(album.postId, 1), parts.get(1).postId());
        assertEquals(PostParts.partId(album.postId, 2), parts.get(2).postId());
        assertTrue(parts.get(0).localId < parts.get(1).localId && parts.get(1).localId < parts.get(2).localId);
        assertEquals(album.postId, parts.get(2).serverPostId());

        // An edit that adds a file appends a part; one that removes files tombstones them.
        IngestResult grown = allocator.insertPost(album, 4, CHANNEL, store);
        assertEquals(1, grown.insertedCount());
        assertEquals(4, allocator.partEntries(album.postId, CHANNEL, store).size());
        List<MessageIdEntry> removed = allocator.removeParts(album.postId, 2, CHANNEL, store);
        assertEquals(2, removed.size());
        assertValid(store);

        List<MessageIdEntry> deleted = allocator.markDeleted(Collections.singletonList(album.postId), CHANNEL, store);
        assertEquals("the post and its remaining live part", 2, deleted.size());
        for (MessageIdEntry entry : allocator.partEntries(album.postId, CHANNEL, store)) {
            assertTrue(entry.isDeleted);
        }
    }

    @Test
    public void postOrderKeyComparesByCodePoint() {
        assertTrue(new PostOrderKey(1, "a").lessThan(new PostOrderKey(2, "0")));
        assertTrue(new PostOrderKey(1, "a").lessThan(new PostOrderKey(1, "b")));
        assertTrue(new PostOrderKey(1, "abc").lessThan(new PostOrderKey(1, "abc#01")));
        assertTrue(new PostOrderKey(1, "abc#99").lessThan(new PostOrderKey(1, "abc$")));
        assertTrue(new PostOrderKey(1, "abc$").lessThan(new PostOrderKey(1, "abd")));
        // Supplementary characters sort after U+FFFF like in UTF-8 (String.compareTo would disagree).
        assertTrue(new PostOrderKey(1, "￿").lessThan(PostOrderKey.upperBound(1)));
        assertTrue(PostOrderKey.lowerBound(5).lessThan(new PostOrderKey(5, "0")));
    }
}
