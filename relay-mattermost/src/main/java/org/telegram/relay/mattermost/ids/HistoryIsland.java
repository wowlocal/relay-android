package org.telegram.relay.mattermost.ids;

/**
 * A contiguous loaded stretch of a channel's history: every post with a key in {@code lower...upper}
 * is in the id map. Islands are stored by post keys, so renumbering never invalidates them.
 */
public final class HistoryIsland {
    public PostOrderKey lower;
    public PostOrderKey upper;
    /** Nothing older than {@code lower} exists on the server (only meaningful for the lowest island). */
    public boolean reachesStart;
    /** Nothing newer than {@code upper} exists and the island is kept live (only for the highest island). */
    public boolean reachesLatest;

    public HistoryIsland(PostOrderKey lower, PostOrderKey upper, boolean reachesStart, boolean reachesLatest) {
        this.lower = lower;
        this.upper = upper;
        this.reachesStart = reachesStart;
        this.reachesLatest = reachesLatest;
    }

    public HistoryIsland(PostOrderKey lower, PostOrderKey upper) {
        this(lower, upper, false, false);
    }

    public HistoryIsland copy() {
        return new HistoryIsland(lower, upper, reachesStart, reachesLatest);
    }

    public boolean contains(PostOrderKey key) {
        return !key.lessThan(lower) && !upper.lessThan(key);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof HistoryIsland)) {
            return false;
        }
        HistoryIsland other = (HistoryIsland) o;
        return reachesStart == other.reachesStart && reachesLatest == other.reachesLatest && lower.equals(other.lower) && upper.equals(other.upper);
    }

    @Override
    public int hashCode() {
        return lower.hashCode() * 31 + upper.hashCode();
    }

    @Override
    public String toString() {
        return "[" + lower + " … " + upper + (reachesStart ? " start" : "") + (reachesLatest ? " latest" : "") + "]";
    }
}
