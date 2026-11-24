package org.telegram.relay.account;

import org.telegram.messenger.UserConfig;
import org.telegram.relay.RelayConfig;
import org.telegram.relay.mattermost.Json;
import org.telegram.relay.mattermost.MattermostClient;
import org.telegram.relay.mattermost.MattermostException;
import org.telegram.relay.mattermost.ids.MessageIdAllocator;
import org.telegram.relay.mattermost.model.User;
import org.telegram.relay.mattermost.model.UserStatus;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Everything the bridge knows about one account slot: the Mattermost session, a REST client with the
 * session token, the id-map database and the message id allocator. Created lazily, dropped on logout.
 */
public final class RelayAccount {
    public final int account;
    public final RelaySession session;
    public final MattermostClient client;
    private RelayDatabase database;
    private SqliteMessageIdStore messageIdStore;
    public final MessageIdAllocator messageIds = new MessageIdAllocator();

    private static final Object lock = new Object();
    private static final RelayAccount[] instances = new RelayAccount[UserConfig.MAX_ACCOUNT_COUNT];

    /** The account's Relay state, or null when the slot has no Mattermost session. */
    public static RelayAccount get(int account) {
        synchronized (lock) {
            RelaySession session = RelaySessionStore.get(account);
            RelayAccount current = instances[account];
            if (session == null) {
                return null;
            }
            if (current == null || current.session != session) {
                current = new RelayAccount(account, session);
                instances[account] = current;
            }
            return current;
        }
    }

    /** Forgets the cached state of a slot (logout); the database files are deleted by the caller. */
    public static void drop(int account) {
        synchronized (lock) {
            RelayAccount current = instances[account];
            instances[account] = null;
            if (current != null) {
                current.closeDatabase();
            }
        }
    }

    private RelayAccount(int account, RelaySession session) {
        this.account = account;
        this.session = session;
        this.client = newClient(session.serverUrl, session.token);
    }

    public static MattermostClient newClient(String serverUrl, String token) {
        MattermostClient client = new MattermostClient(serverUrl, token);
        client.setUserAgent("Relay-Android/0.1");
        return client;
    }

    public synchronized RelayDatabase db() {
        if (database == null) {
            database = new RelayDatabase(account);
        }
        return database;
    }

    public synchronized SqliteMessageIdStore messageIdStore() {
        if (messageIdStore == null) {
            messageIdStore = new SqliteMessageIdStore(db());
        }
        return messageIdStore;
    }

    private synchronized void closeDatabase() {
        if (database != null) {
            try {
                database.close();
            } catch (Exception ignore) {
            }
            database = null;
            messageIdStore = null;
        }
    }

    // MARK: - Users

    public long userTlId(String mmUserId) {
        return db().tlId(RelayDatabase.KIND_USER, mmUserId);
    }

    public long selfTlId() {
        return userTlId(session.userId);
    }

    /** Caches a Mattermost user and returns its TL id. */
    public long rememberUser(User user) {
        db().putUserJson(user.id, Json.toJson(user));
        return userTlId(user.id);
    }

    /** The cached Mattermost user, or null. */
    public User cachedUser(String mmUserId) {
        String json = db().userJson(mmUserId);
        if (json == null) {
            return null;
        }
        try {
            return Json.fromJson(json, User.class);
        } catch (MattermostException e) {
            RelayConfig.logError(RelayConfig.TAG_BRIDGE, "bad cached user " + mmUserId, e);
            return null;
        }
    }

    /** Prefix of synthetic users that stand for a webhook/bot {@code override_username} (never sent to Mattermost). */
    public static final String OVERRIDE_PREFIX = "override:";

    /** The synthetic user id for posts of {@code userId} shown as {@code name}; cached as a user so it maps like one. */
    public String overrideUser(String userId, String name) {
        String key = OVERRIDE_PREFIX + userId + ":" + name;
        if (cachedUser(key) == null) {
            User user = new User();
            user.id = key;
            user.username = name.replaceAll("[^A-Za-z0-9_.\\-]", "");
            user.firstName = name;
            user.isBot = true;
            db().putUserJson(key, Json.toJson(user));
        }
        return key;
    }

    public static boolean isSynthetic(String mmUserId) {
        return mmUserId != null && mmUserId.startsWith(OVERRIDE_PREFIX);
    }

    /** Makes sure every user id is cached, loading the missing ones in one {@code POST /users/ids}. */
    public void ensureUsers(Collection<String> mmUserIds) throws MattermostException {
        List<String> missing = new ArrayList<>();
        for (String id : new LinkedHashSet<>(mmUserIds)) {
            if (id != null && !id.isEmpty() && !isSynthetic(id) && cachedUser(id) == null) {
                missing.add(id);
            }
        }
        if (missing.isEmpty()) {
            return;
        }
        for (User user : client.users(missing)) {
            rememberUser(user);
        }
    }

    /** Re-reads users from the server (profile changes) when their cache entry is older than {@code maxAgeMs}. */
    public void refreshUsers(Collection<String> mmUserIds, long maxAgeMs) throws MattermostException {
        List<String> stale = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (String id : new LinkedHashSet<>(mmUserIds)) {
            if (id == null || id.isEmpty() || isSynthetic(id)) {
                continue;
            }
            Long at = userRefreshedAt.get(id);
            if (at == null || now - at > maxAgeMs) {
                stale.add(id);
            }
        }
        if (stale.isEmpty()) {
            return;
        }
        for (User user : client.users(stale)) {
            rememberUser(user);
            userRefreshedAt.put(user.id, now);
        }
    }

    private final ConcurrentHashMap<String, Long> userRefreshedAt = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, UserStatus> statuses = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> statusFetchedAt = new ConcurrentHashMap<>();

    /** Loads presence for users whose status is older than {@code maxAgeMs} ({@code POST /users/status/ids}). Errors are ignored. */
    public void refreshStatuses(Collection<String> mmUserIds, long maxAgeMs) {
        List<String> stale = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (String id : new LinkedHashSet<>(mmUserIds)) {
            if (id == null || id.isEmpty() || isSynthetic(id)) {
                continue;
            }
            Long at = statusFetchedAt.get(id);
            if (at == null || now - at > maxAgeMs) {
                stale.add(id);
            }
        }
        if (stale.isEmpty()) {
            return;
        }
        try {
            for (UserStatus status : client.userStatuses(stale)) {
                statuses.put(status.userId, status);
            }
            for (String id : stale) {
                statusFetchedAt.put(id, now);
            }
        } catch (MattermostException e) {
            RelayConfig.logError(RelayConfig.TAG_BRIDGE, "statuses", e);
        }
    }

    public UserStatus status(String mmUserId) {
        return statuses.get(mmUserId);
    }

    /** The TL photo id of a user's current picture, or 0 when the user has none (letter avatar). */
    public long avatarPhotoId(User user) {
        if (user == null || isSynthetic(user.id) || user.lastPictureUpdate <= 0) {
            return 0;
        }
        return db().tlId(RelayDatabase.KIND_AVATAR, user.id + ":" + user.lastPictureUpdate);
    }

    /** The Mattermost user id behind an avatar photo id, or null. */
    public String avatarUserId(long photoId) {
        String key = db().mmId(RelayDatabase.KIND_AVATAR, photoId);
        if (key == null) {
            return null;
        }
        int colon = key.lastIndexOf(':');
        return colon > 0 ? key.substring(0, colon) : key;
    }

    /** The TL user of a cached Mattermost user id, or null. */
    public TLRPC.TL_user tlUser(String mmUserId) {
        User user = cachedUser(mmUserId);
        if (user == null) {
            return null;
        }
        boolean self = mmUserId.equals(session.userId);
        return RelayUsers.toTlUser(user, userTlId(mmUserId), self, avatarPhotoId(user), status(mmUserId));
    }

    /** The TL user for a TL user id that the bridge allocated, or null. */
    public TLRPC.TL_user tlUserById(long tlId) {
        String mmId = db().mmId(RelayDatabase.KIND_USER, tlId);
        return mmId == null ? null : tlUser(mmId);
    }
}
