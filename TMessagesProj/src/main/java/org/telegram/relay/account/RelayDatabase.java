package org.telegram.relay.account;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.telegram.messenger.ApplicationLoader;

import java.io.File;

/**
 * The bridge's per-account database {@code relay-<account>.db}, next to Telegram's own
 * {@code cache4.db} ({@code files/} for account 0, {@code files/account<n>/} for the others).
 *
 * <ul>
 *   <li>{@code id_map}: Mattermost string ids ↔ TL long ids, per kind ({@link #KIND_USER},
 *       {@link #KIND_CHANNEL}, {@link #KIND_TEAM}, {@link #KIND_FILE}).</li>
 *   <li>{@code id_seq}: the next TL id per kind.</li>
 *   <li>{@code users}: the latest Mattermost user JSON per user (A2: the profile cache behind {@code TL_user}).</li>
 *   <li>{@code msg_ids}, {@code msg_islands}, {@code msg_tombstones}: the post ↔ message id map per
 *       dialog, written by {@link SqliteMessageIdStore} for {@code MessageIdAllocator}.</li>
 *   <li>{@code pts}, {@code update_log}: the pts counters (box {@code common} for DMs, {@code c<id>} per
 *       channel) and the update log behind {@code getDifference}.</li>
 * </ul>
 */
public final class RelayDatabase extends SQLiteOpenHelper {
    public static final String KIND_USER = "user";
    public static final String KIND_CHANNEL = "channel";
    public static final String KIND_TEAM = "team";
    public static final String KIND_FILE = "file";
    /** A user's profile picture version, keyed {@code <userId>:<last_picture_update>}: the TL photo id of the avatar. */
    public static final String KIND_AVATAR = "avatar";
    /** A8: a calls-plugin call id: the TL id of Telegram's phone call / group call. */
    public static final String KIND_CALL = "call";

    private static final int VERSION = 2;

    /**
     * First TL id per kind. User and channel ids stay far below 2^48 and above Telegram's special
     * service ids (333000, 777000, 42777, 489000, 2666000, 708513). Team ids are dialog filter ids (≥ 2).
     */
    private static long firstId(String kind) {
        switch (kind) {
            case KIND_USER:
                return 100_000_001L;
            case KIND_CHANNEL:
                return 2_000_000_001L;
            case KIND_TEAM:
                return 2L;
            case KIND_FILE:
                return 5_000_000_001L;
            case KIND_AVATAR:
                return 7_000_000_001L;
            case KIND_CALL:
                return 8_000_000_001L;
            default:
                return 1L;
        }
    }

    public final int account;
    private final File file;

    public static File databaseFile(int account) {
        File dir = ApplicationLoader.getFilesDirFixed();
        if (account != 0) {
            dir = new File(dir, "account" + account);
            dir.mkdirs();
        }
        return new File(dir, "relay-" + account + ".db");
    }

    public RelayDatabase(int account) {
        super(ApplicationLoader.applicationContext, databaseFile(account).getAbsolutePath(), null, VERSION);
        this.account = account;
        this.file = databaseFile(account);
        setWriteAheadLoggingEnabled(true);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE id_map (kind TEXT NOT NULL, mm_id TEXT NOT NULL, tl_id INTEGER NOT NULL, PRIMARY KEY (kind, mm_id))");
        db.execSQL("CREATE UNIQUE INDEX id_map_tl ON id_map (kind, tl_id)");
        db.execSQL("CREATE TABLE id_seq (kind TEXT PRIMARY KEY, next INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE users (mm_id TEXT PRIMARY KEY, json TEXT NOT NULL, updated_at INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE msg_ids (channel TEXT NOT NULL, post_id TEXT NOT NULL, create_at INTEGER NOT NULL, local_id INTEGER NOT NULL, deleted INTEGER NOT NULL DEFAULT 0, PRIMARY KEY (channel, post_id))");
        db.execSQL("CREATE INDEX msg_ids_key ON msg_ids (channel, create_at, post_id)");
        db.execSQL("CREATE UNIQUE INDEX msg_ids_local ON msg_ids (channel, local_id)");
        db.execSQL("CREATE TABLE msg_islands (channel TEXT NOT NULL, idx INTEGER NOT NULL, lower_at INTEGER NOT NULL, lower_id TEXT NOT NULL, upper_at INTEGER NOT NULL, upper_id TEXT NOT NULL, reaches_start INTEGER NOT NULL, reaches_latest INTEGER NOT NULL, PRIMARY KEY (channel, idx))");
        db.execSQL("CREATE TABLE msg_tombstones (channel TEXT NOT NULL, post_id TEXT NOT NULL, PRIMARY KEY (channel, post_id))");
        db.execSQL("CREATE TABLE pts (box TEXT PRIMARY KEY, pts INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE update_log (box TEXT NOT NULL, pts INTEGER NOT NULL, date INTEGER NOT NULL, data BLOB NOT NULL, PRIMARY KEY (box, pts))");
        createV2(db);
    }

    /** v2 (A3): small key/value state (dialog fingerprint, CRT flag). */
    private static void createV2(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS kv (k TEXT PRIMARY KEY, v TEXT NOT NULL)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            createV2(db);
        }
    }

    // MARK: - Key/value

    public String kv(String key) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT v FROM kv WHERE k = ?", new String[]{key})) {
            return c.moveToFirst() ? c.getString(0) : null;
        }
    }

    public void putKv(String key, String value) {
        ContentValues row = new ContentValues();
        row.put("k", key);
        row.put("v", value);
        getWritableDatabase().insertWithOnConflict("kv", null, row, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public void deleteKv(String key) {
        getWritableDatabase().delete("kv", "k = ?", new String[]{key});
    }

    // MARK: - Id map

    /** The TL id of a Mattermost id, allocating the next one of its kind when it has none. */
    public long tlId(String kind, String mmId) {
        long existing = findTlId(kind, mmId);
        if (existing != 0) {
            return existing;
        }
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            long again = findTlId(kind, mmId);
            if (again != 0) {
                db.setTransactionSuccessful();
                return again;
            }
            long next = firstId(kind);
            try (Cursor c = db.rawQuery("SELECT next FROM id_seq WHERE kind = ?", new String[]{kind})) {
                if (c.moveToFirst()) {
                    next = c.getLong(0);
                }
            }
            ContentValues seq = new ContentValues();
            seq.put("kind", kind);
            seq.put("next", next + 1);
            db.insertWithOnConflict("id_seq", null, seq, SQLiteDatabase.CONFLICT_REPLACE);
            ContentValues row = new ContentValues();
            row.put("kind", kind);
            row.put("mm_id", mmId);
            row.put("tl_id", next);
            db.insertOrThrow("id_map", null, row);
            db.setTransactionSuccessful();
            return next;
        } finally {
            db.endTransaction();
        }
    }

    /** The TL id of a Mattermost id, or 0 when it has none yet. */
    public long findTlId(String kind, String mmId) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT tl_id FROM id_map WHERE kind = ? AND mm_id = ?", new String[]{kind, mmId})) {
            return c.moveToFirst() ? c.getLong(0) : 0;
        }
    }

    /** The Mattermost id of a TL id, or null. */
    public String mmId(String kind, long tlId) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT mm_id FROM id_map WHERE kind = ? AND tl_id = ?", new String[]{kind, Long.toString(tlId)})) {
            return c.moveToFirst() ? c.getString(0) : null;
        }
    }

    // MARK: - Users

    public void putUserJson(String mmUserId, String json) {
        ContentValues row = new ContentValues();
        row.put("mm_id", mmUserId);
        row.put("json", json);
        row.put("updated_at", System.currentTimeMillis());
        getWritableDatabase().insertWithOnConflict("users", null, row, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public String userJson(String mmUserId) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT json FROM users WHERE mm_id = ?", new String[]{mmUserId})) {
            return c.moveToFirst() ? c.getString(0) : null;
        }
    }

    // MARK: - pts

    public static final String BOX_COMMON = "common";

    public static String channelBox(long channelId) {
        return "c" + channelId;
    }

    /** The current pts of a box; a box starts at 1 so the client never sees pts 0 ("no state"). */
    public int pts(String box) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT pts FROM pts WHERE box = ?", new String[]{box})) {
            return c.moveToFirst() ? c.getInt(0) : 1;
        }
    }

    /** Adds {@code count} to a box and returns the new pts. Call from the account's update executor only. */
    public int advancePts(String box, int count) {
        int next = pts(box) + count;
        ContentValues row = new ContentValues();
        row.put("box", box);
        row.put("pts", next);
        getWritableDatabase().insertWithOnConflict("pts", null, row, SQLiteDatabase.CONFLICT_REPLACE);
        return next;
    }

    public void appendUpdateLog(String box, int pts, int date, byte[] data) {
        ContentValues row = new ContentValues();
        row.put("box", box);
        row.put("pts", pts);
        row.put("date", date);
        row.put("data", data);
        getWritableDatabase().insertWithOnConflict("update_log", null, row, SQLiteDatabase.CONFLICT_REPLACE);
    }

    /** One logged update: its pts and its TL bytes. */
    public static final class LoggedUpdate {
        public final int pts;
        public final int date;
        public final byte[] data;

        LoggedUpdate(int pts, int date, byte[] data) {
            this.pts = pts;
            this.date = date;
            this.data = data;
        }
    }

    /** Logged updates of a box with {@code pts > afterPts}, in pts order (at most {@code limit}). */
    public java.util.List<LoggedUpdate> updateLogAfter(String box, int afterPts, int limit) {
        java.util.List<LoggedUpdate> list = new java.util.ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT pts, date, data FROM update_log WHERE box = ? AND pts > ? ORDER BY pts LIMIT " + limit,
                new String[]{box, Integer.toString(afterPts)})) {
            while (c.moveToNext()) {
                list.add(new LoggedUpdate(c.getInt(0), c.getInt(1), c.getBlob(2)));
            }
        }
        return list;
    }

    /** The lowest logged pts of a box, or 0 when its log is empty. */
    public int updateLogFirstPts(String box) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT MIN(pts) FROM update_log WHERE box = ?", new String[]{box})) {
            return c.moveToFirst() && !c.isNull(0) ? c.getInt(0) : 0;
        }
    }

    /** Channel boxes ({@code c<id>}) with a logged update dated {@code >= date}. */
    public java.util.List<String> channelBoxesSince(int date) {
        java.util.List<String> list = new java.util.ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT DISTINCT box FROM update_log WHERE box != ? AND date >= ?", new String[]{BOX_COMMON, Integer.toString(date)})) {
            while (c.moveToNext()) {
                list.add(c.getString(0));
            }
        }
        return list;
    }

    /** Drops log entries older than {@code minDate}, keeping at most {@code keep} per box. */
    public void pruneUpdateLog(int minDate, int keep) {
        SQLiteDatabase db = getWritableDatabase();
        db.execSQL("DELETE FROM update_log WHERE date < ?", new Object[]{minDate});
        db.execSQL("DELETE FROM update_log WHERE rowid IN (SELECT l.rowid FROM update_log l WHERE (SELECT COUNT(*) FROM update_log n WHERE n.box = l.box AND n.pts > l.pts) >= ?)", new Object[]{keep});
    }

    // MARK: - Lifecycle

    /** Closes and deletes the database files (logout). */
    public synchronized void wipe() {
        try {
            close();
        } catch (Exception ignore) {
        }
        deleteFiles(account);
    }

    public static void deleteFiles(int account) {
        File f = databaseFile(account);
        SQLiteDatabase.deleteDatabase(f);
    }
}
