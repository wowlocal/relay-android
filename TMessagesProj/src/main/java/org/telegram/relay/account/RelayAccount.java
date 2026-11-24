package org.telegram.relay.account;

import org.telegram.messenger.UserConfig;
import org.telegram.relay.RelayConfig;
import org.telegram.relay.mattermost.Json;
import org.telegram.relay.mattermost.MattermostClient;
import org.telegram.relay.mattermost.MattermostException;
import org.telegram.relay.mattermost.ids.MessageIdAllocator;
import org.telegram.relay.mattermost.model.User;
import org.telegram.tgnet.TLRPC;

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

    /** The TL user of a cached Mattermost user id, or null. */
    public TLRPC.TL_user tlUser(String mmUserId) {
        User user = cachedUser(mmUserId);
        if (user == null) {
            return null;
        }
        return RelayUsers.toTlUser(user, userTlId(mmUserId), mmUserId.equals(session.userId));
    }

    /** The TL user for a TL user id that the bridge allocated, or null. */
    public TLRPC.TL_user tlUserById(long tlId) {
        String mmId = db().mmId(RelayDatabase.KIND_USER, tlId);
        return mmId == null ? null : tlUser(mmId);
    }
}
