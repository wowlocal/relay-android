package org.telegram.relay.mattermost.ids;

/** What happened to one post of an ingested batch. */
public final class PostAllocation {
    public enum Status {
        /** The post was new and got {@link #localId}. */
        INSERTED,
        /** Already mapped (idempotent replay); {@link #localId} is its current id. */
        EXISTING,
        /** Tombstoned: don't (re)insert the message. {@link #localId} is null when it was never mapped. */
        DELETED
    }

    public final String postId;
    public final Integer localId;
    public final Status status;

    public PostAllocation(String postId, Integer localId, Status status) {
        this.postId = postId;
        this.localId = localId;
        this.status = status;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof PostAllocation)) {
            return false;
        }
        PostAllocation other = (PostAllocation) o;
        return postId.equals(other.postId) && status == other.status && (localId == null ? other.localId == null : localId.equals(other.localId));
    }

    @Override
    public int hashCode() {
        return postId.hashCode();
    }

    @Override
    public String toString() {
        return postId + "=" + localId + "(" + status.name().toLowerCase() + ")";
    }
}
