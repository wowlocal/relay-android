package org.telegram.relay.mattermost.ids;

/** One row of the persistent id map: a post (or post part) and its local message id. */
public final class MessageIdEntry {
    public final PostOrderKey key;
    public final int localId;
    /** Tombstone: a deleted post keeps its id, so the slot is never reused and a late event can't resurrect it. */
    public final boolean isDeleted;

    public MessageIdEntry(PostOrderKey key, int localId, boolean isDeleted) {
        this.key = key;
        this.localId = localId;
        this.isDeleted = isDeleted;
    }

    public MessageIdEntry(PostOrderKey key, int localId) {
        this(key, localId, false);
    }

    public String postId() {
        return key.postId;
    }

    public long createAt() {
        return key.createAt;
    }

    public String serverPostId() {
        return key.serverPostId();
    }

    public int partIndex() {
        return key.partIndex();
    }

    public MessageIdEntry withLocalId(int id) {
        return new MessageIdEntry(key, id, isDeleted);
    }

    public MessageIdEntry deleted() {
        return new MessageIdEntry(key, localId, true);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof MessageIdEntry)) {
            return false;
        }
        MessageIdEntry other = (MessageIdEntry) o;
        return localId == other.localId && isDeleted == other.isDeleted && key.equals(other.key);
    }

    @Override
    public int hashCode() {
        return key.hashCode() * 31 + localId;
    }

    @Override
    public String toString() {
        return localId + "=" + key + (isDeleted ? "(deleted)" : "");
    }
}
