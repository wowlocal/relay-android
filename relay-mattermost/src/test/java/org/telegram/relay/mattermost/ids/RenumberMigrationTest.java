package org.telegram.relay.mattermost.ids;

import org.junit.Test;
import org.telegram.relay.mattermost.ids.TestSupport.SeededGenerator;
import org.telegram.relay.mattermost.ids.TestSupport.Times;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.telegram.relay.mattermost.ids.TestSupport.makePosts;

/**
 * Polish 1: the bridge's renumber migration (Android {@code RelayRenumber}). A forced renumber (placeholder late
 * inserts between the two newest posts, as the debug hook does) is applied to a client-side id → post map the way the
 * bridge tells the client: every {@code from} id deleted first, then every moved post inserted at its {@code to} id.
 * Afterwards the client agrees with the id map for every real post, in order, with no duplicates.
 */
public class RenumberMigrationTest {
    private static final String CHANNEL = "channel-r";

    @Test
    public void forcedRenumberMigratesTheClientMap() throws Exception {
        SeededGenerator rng = new SeededGenerator(42);
        List<PostOrderKey> posts = makePosts(60, Times.YEAR_2026, 60_000, rng);
        InMemoryMessageIdStore store = new InMemoryMessageIdStore();
        MessageIdAllocator allocator = new MessageIdAllocator();
        allocator.ingestLatestPage(posts, true, CHANNEL, store);

        // The client's cache before: local id → post id.
        TreeMap<Integer, String> client = new TreeMap<Integer, String>();
        for (MessageIdEntry entry : store.allEntries(CHANNEL)) {
            client.put(entry.localId, entry.postId());
        }

        PostOrderKey lower = posts.get(posts.size() - 2);
        List<String> placeholders = new ArrayList<String>();
        RenumberPlan plan = null;
        for (int i = 0; i < 40 && plan == null; i++) {
            String id = lower.postId + "z" + (9999 - i);
            placeholders.add(id);
            plan = allocator.insertPost(new PostOrderKey(lower.createAt, id), 1, CHANNEL, store).renumberPlan;
        }
        assertNotNull("placeholder inserts force a renumber", plan);
        allocator.markDeleted(placeholders, CHANNEL, store);

        int realMoves = 0;
        for (RenumberMove move : plan.moves) {
            client.remove(move.from);
        }
        for (RenumberMove move : plan.moves) {
            if (!placeholders.contains(move.postId)) {
                client.put(move.to, move.postId);
                realMoves++;
            }
        }
        assertTrue("real posts moved", realMoves > 0);

        Map<String, MessageIdEntry> now = store.entries(CHANNEL, new ArrayList<String>(client.values()));
        for (Map.Entry<Integer, String> e : client.entrySet()) {
            assertFalse("no placeholder reaches the client", placeholders.contains(e.getValue()));
            assertEquals("client id == map id for " + e.getValue(), now.get(e.getValue()).localId, (int) e.getKey());
        }
        assertEquals("every post once", posts.size(), client.size());
        List<String> order = new ArrayList<String>(client.values());
        List<String> expected = new ArrayList<String>();
        for (PostOrderKey key : posts) {
            expected.add(key.postId);
        }
        Collections.sort(expected, (a, b) -> Integer.compare(now.get(a).localId, now.get(b).localId));
        assertEquals("client order == post order", expected, order);
        List<String> byKey = new ArrayList<String>();
        for (PostOrderKey key : posts) {
            byKey.add(key.postId);
        }
        assertEquals(byKey, order);
    }
}
