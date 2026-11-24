package org.telegram.relay.mattermost.ids;

/** One id change in a {@link RenumberPlan}. */
public final class RenumberMove {
    public final String postId;
    public final int from;
    public final int to;

    public RenumberMove(String postId, int from, int to) {
        this.postId = postId;
        this.from = from;
        this.to = to;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof RenumberMove)) {
            return false;
        }
        RenumberMove other = (RenumberMove) o;
        return from == other.from && to == other.to && postId.equals(other.postId);
    }

    @Override
    public int hashCode() {
        return postId.hashCode() * 31 + from;
    }

    @Override
    public String toString() {
        return postId + ": " + from + "→" + to;
    }
}
