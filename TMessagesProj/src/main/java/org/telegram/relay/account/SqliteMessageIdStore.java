package org.telegram.relay.account;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import org.telegram.relay.mattermost.ids.HistoryIsland;
import org.telegram.relay.mattermost.ids.MessageIdChangeSet;
import org.telegram.relay.mattermost.ids.MessageIdEntry;
import org.telegram.relay.mattermost.ids.MessageIdStore;
import org.telegram.relay.mattermost.ids.PostOrderKey;
import org.telegram.relay.mattermost.ids.RenumberMove;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@link MessageIdStore} on {@link RelayDatabase} ({@code msg_ids}, {@code msg_islands},
 * {@code msg_tombstones}). The "channel" key is the Mattermost channel id. Key order is
 * {@code (create_at, post_id)} with SQLite's BINARY collation, which equals {@link PostOrderKey}'s
 * code-point order. Each {@link #apply} runs in one transaction.
 */
public final class SqliteMessageIdStore implements MessageIdStore {
    private static final String COLS = "post_id, create_at, local_id, deleted";
    private final RelayDatabase database;

    public SqliteMessageIdStore(RelayDatabase database) {
        this.database = database;
    }

    private static MessageIdEntry entry(Cursor c) {
        return new MessageIdEntry(new PostOrderKey(c.getLong(1), c.getString(0)), c.getInt(2), c.getInt(3) != 0);
    }

    private static List<MessageIdEntry> read(Cursor c) {
        List<MessageIdEntry> result = new ArrayList<>();
        try {
            while (c.moveToNext()) {
                result.add(entry(c));
            }
        } finally {
            c.close();
        }
        return result;
    }

    @Override
    public Map<String, MessageIdEntry> entries(String channelId, List<String> postIds) {
        Map<String, MessageIdEntry> result = new HashMap<>();
        SQLiteDatabase db = database.getReadableDatabase();
        for (String postId : postIds) {
            try (Cursor c = db.rawQuery("SELECT " + COLS + " FROM msg_ids WHERE channel = ? AND post_id = ?", new String[]{channelId, postId})) {
                if (c.moveToFirst()) {
                    result.put(postId, entry(c));
                }
            }
        }
        return result;
    }

    @Override
    public List<MessageIdEntry> entriesInRange(String channelId, PostOrderKey lower, PostOrderKey upper) {
        return read(database.getReadableDatabase().rawQuery(
                "SELECT " + COLS + " FROM msg_ids WHERE channel = ? AND (create_at > ? OR (create_at = ? AND post_id >= ?)) AND (create_at < ? OR (create_at = ? AND post_id <= ?)) ORDER BY create_at, post_id",
                new String[]{channelId, Long.toString(lower.createAt), Long.toString(lower.createAt), lower.postId, Long.toString(upper.createAt), Long.toString(upper.createAt), upper.postId}));
    }

    @Override
    public List<MessageIdEntry> entriesOlderThan(String channelId, PostOrderKey key, int limit) {
        if (limit <= 0) {
            return new ArrayList<>();
        }
        if (key == null) {
            return read(database.getReadableDatabase().rawQuery(
                    "SELECT " + COLS + " FROM msg_ids WHERE channel = ? ORDER BY create_at DESC, post_id DESC LIMIT " + limit, new String[]{channelId}));
        }
        return read(database.getReadableDatabase().rawQuery(
                "SELECT " + COLS + " FROM msg_ids WHERE channel = ? AND (create_at < ? OR (create_at = ? AND post_id < ?)) ORDER BY create_at DESC, post_id DESC LIMIT " + limit,
                new String[]{channelId, Long.toString(key.createAt), Long.toString(key.createAt), key.postId}));
    }

    @Override
    public List<MessageIdEntry> entriesNewerThan(String channelId, PostOrderKey key, int limit) {
        if (limit <= 0) {
            return new ArrayList<>();
        }
        if (key == null) {
            return read(database.getReadableDatabase().rawQuery(
                    "SELECT " + COLS + " FROM msg_ids WHERE channel = ? ORDER BY create_at, post_id LIMIT " + limit, new String[]{channelId}));
        }
        return read(database.getReadableDatabase().rawQuery(
                "SELECT " + COLS + " FROM msg_ids WHERE channel = ? AND (create_at > ? OR (create_at = ? AND post_id > ?)) ORDER BY create_at, post_id LIMIT " + limit,
                new String[]{channelId, Long.toString(key.createAt), Long.toString(key.createAt), key.postId}));
    }

    @Override
    public List<MessageIdEntry> entriesFromLocalId(String channelId, int localId, boolean ascending, int limit) {
        if (limit <= 0) {
            return new ArrayList<>();
        }
        String sql = ascending
                ? "SELECT " + COLS + " FROM msg_ids WHERE channel = ? AND local_id >= ? ORDER BY local_id LIMIT " + limit
                : "SELECT " + COLS + " FROM msg_ids WHERE channel = ? AND local_id <= ? ORDER BY local_id DESC LIMIT " + limit;
        return read(database.getReadableDatabase().rawQuery(sql, new String[]{channelId, Integer.toString(localId)}));
    }

    @Override
    public List<HistoryIsland> islands(String channelId) {
        List<HistoryIsland> result = new ArrayList<>();
        try (Cursor c = database.getReadableDatabase().rawQuery(
                "SELECT lower_at, lower_id, upper_at, upper_id, reaches_start, reaches_latest FROM msg_islands WHERE channel = ? ORDER BY idx", new String[]{channelId})) {
            while (c.moveToNext()) {
                result.add(new HistoryIsland(new PostOrderKey(c.getLong(0), c.getString(1)), new PostOrderKey(c.getLong(2), c.getString(3)), c.getInt(4) != 0, c.getInt(5) != 0));
            }
        }
        return result;
    }

    @Override
    public Set<String> unmappedTombstones(String channelId, List<String> postIds) {
        Set<String> result = new HashSet<>();
        SQLiteDatabase db = database.getReadableDatabase();
        for (String postId : postIds) {
            try (Cursor c = db.rawQuery("SELECT 1 FROM msg_tombstones WHERE channel = ? AND post_id = ?", new String[]{channelId, postId})) {
                if (c.moveToFirst()) {
                    result.add(postId);
                }
            }
        }
        return result;
    }

    @Override
    public void apply(MessageIdChangeSet changes) {
        if (changes.isEmpty()) {
            return;
        }
        SQLiteDatabase db = database.getWritableDatabase();
        db.beginTransaction();
        try {
            String channel = changes.channelId;
            if (!changes.renumbered.isEmpty()) {
                // Two passes through negative ids keep the unique (channel, local_id) index satisfied
                // while ids move past each other.
                for (RenumberMove move : changes.renumbered) {
                    db.execSQL("UPDATE msg_ids SET local_id = ? WHERE channel = ? AND post_id = ?", new Object[]{-(long) move.to - 1, channel, move.postId});
                }
                db.execSQL("UPDATE msg_ids SET local_id = -local_id - 1 WHERE channel = ? AND local_id < 0", new Object[]{channel});
            }
            for (MessageIdEntry entry : changes.inserted) {
                ContentValues row = new ContentValues();
                row.put("channel", channel);
                row.put("post_id", entry.postId());
                row.put("create_at", entry.createAt());
                row.put("local_id", entry.localId);
                row.put("deleted", entry.isDeleted ? 1 : 0);
                db.insertWithOnConflict("msg_ids", null, row, SQLiteDatabase.CONFLICT_IGNORE);
            }
            for (String postId : changes.deletedPostIds) {
                db.execSQL("UPDATE msg_ids SET deleted = 1 WHERE channel = ? AND post_id = ?", new Object[]{channel, postId});
            }
            for (String postId : changes.unmappedTombstones) {
                db.execSQL("INSERT OR IGNORE INTO msg_tombstones (channel, post_id) SELECT ?, ? WHERE NOT EXISTS (SELECT 1 FROM msg_ids WHERE channel = ? AND post_id = ?)",
                        new Object[]{channel, postId, channel, postId});
            }
            if (changes.islands != null) {
                db.execSQL("DELETE FROM msg_islands WHERE channel = ?", new Object[]{channel});
                int idx = 0;
                for (HistoryIsland island : changes.islands) {
                    ContentValues row = new ContentValues();
                    row.put("channel", channel);
                    row.put("idx", idx++);
                    row.put("lower_at", island.lower.createAt);
                    row.put("lower_id", island.lower.postId);
                    row.put("upper_at", island.upper.createAt);
                    row.put("upper_id", island.upper.postId);
                    row.put("reaches_start", island.reachesStart ? 1 : 0);
                    row.put("reaches_latest", island.reachesLatest ? 1 : 0);
                    db.insertOrThrow("msg_islands", null, row);
                }
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    // MARK: - Bridge queries (not part of MessageIdStore)

    /** The entry with exactly this local id (tombstones included), or null. */
    public MessageIdEntry entryByLocalId(String channelId, int localId) {
        try (Cursor c = database.getReadableDatabase().rawQuery("SELECT " + COLS + " FROM msg_ids WHERE channel = ? AND local_id = ?", new String[]{channelId, Integer.toString(localId)})) {
            return c.moveToFirst() ? entry(c) : null;
        }
    }

    /** Every entry of a channel in local id order (debug dumps). */
    public List<MessageIdEntry> allEntries(String channelId) {
        return read(database.getReadableDatabase().rawQuery("SELECT " + COLS + " FROM msg_ids WHERE channel = ? ORDER BY local_id", new String[]{channelId}));
    }
}
