package org.telegram.relay.mattermost.ids;

/** A {@code GET /channels/{id}/posts} cursor. */
public final class HistoryCursor {
    public enum Kind {
        /** No cursor: the newest page ({@code page=0}). */
        LATEST,
        BEFORE,
        AFTER,
        /** {@code GET /posts/{id}} + {@code before=id} + {@code after=id}. */
        AROUND
    }

    public static final HistoryCursor LATEST = new HistoryCursor(Kind.LATEST, null);

    public final Kind kind;
    /** Null for {@link Kind#LATEST}. */
    public final String postId;

    private HistoryCursor(Kind kind, String postId) {
        this.kind = kind;
        this.postId = postId;
    }

    public static HistoryCursor before(String postId) {
        return new HistoryCursor(Kind.BEFORE, postId);
    }

    public static HistoryCursor after(String postId) {
        return new HistoryCursor(Kind.AFTER, postId);
    }

    public static HistoryCursor around(String postId) {
        return new HistoryCursor(Kind.AROUND, postId);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof HistoryCursor)) {
            return false;
        }
        HistoryCursor other = (HistoryCursor) o;
        return kind == other.kind && (postId == null ? other.postId == null : postId.equals(other.postId));
    }

    @Override
    public int hashCode() {
        return kind.hashCode() * 31 + (postId != null ? postId.hashCode() : 0);
    }

    @Override
    public String toString() {
        return kind == Kind.LATEST ? "latest" : kind.name().toLowerCase() + "(" + postId + ")";
    }
}
