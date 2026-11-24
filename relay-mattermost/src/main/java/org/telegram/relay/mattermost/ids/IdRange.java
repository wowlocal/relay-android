package org.telegram.relay.mattermost.ids;

/** A closed range of local message ids, {@code lower...upper}. */
public final class IdRange {
    public final int lower;
    public final int upper;

    public IdRange(int lower, int upper) {
        if (upper < lower) {
            throw new IllegalArgumentException("empty range " + lower + "..." + upper);
        }
        this.lower = lower;
        this.upper = upper;
    }

    public boolean contains(int id) {
        return id >= lower && id <= upper;
    }

    public long count() {
        return (long) upper - lower + 1;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof IdRange)) {
            return false;
        }
        IdRange other = (IdRange) o;
        return lower == other.lower && upper == other.upper;
    }

    @Override
    public int hashCode() {
        return lower * 31 + upper;
    }

    @Override
    public String toString() {
        return lower + "..." + upper;
    }
}
