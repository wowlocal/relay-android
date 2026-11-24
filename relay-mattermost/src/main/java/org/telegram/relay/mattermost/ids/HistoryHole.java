package org.telegram.relay.mattermost.ids;

/** A gap in the local id space where posts may exist on the server but are not loaded. */
public final class HistoryHole {
    public final IdRange range;
    /** The loaded post just below the hole (null: the hole reaches the start of the id space). */
    public final PostOrderKey lowerBoundary;
    /** The loaded post just above the hole (null: the hole reaches the top of the id space). */
    public final PostOrderKey upperBoundary;

    public HistoryHole(IdRange range, PostOrderKey lowerBoundary, PostOrderKey upperBoundary) {
        this.range = range;
        this.lowerBoundary = lowerBoundary;
        this.upperBoundary = upperBoundary;
    }

    /**
     * The cursor that loads posts into this hole moving in {@code direction}: {@code OLDER} pages
     * {@code before=} the post above (or the latest page when nothing is above); {@code NEWER} pages
     * {@code after=} the post below, or null when nothing is below (Mattermost can't page forward from
     * the channel start).
     */
    public HistoryCursor cursor(HistoryDirection direction) {
        if (direction == HistoryDirection.OLDER) {
            return upperBoundary != null ? HistoryCursor.before(upperBoundary.serverPostId()) : HistoryCursor.LATEST;
        }
        return lowerBoundary != null ? HistoryCursor.after(lowerBoundary.serverPostId()) : null;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof HistoryHole)) {
            return false;
        }
        HistoryHole other = (HistoryHole) o;
        return range.equals(other.range) && eq(lowerBoundary, other.lowerBoundary) && eq(upperBoundary, other.upperBoundary);
    }

    private static boolean eq(Object a, Object b) {
        return a == null ? b == null : a.equals(b);
    }

    @Override
    public int hashCode() {
        return range.hashCode();
    }

    @Override
    public String toString() {
        return "hole(" + range + ")";
    }
}
