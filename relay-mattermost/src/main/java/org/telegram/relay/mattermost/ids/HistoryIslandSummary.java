package org.telegram.relay.mattermost.ids;

/** An island with the local ids of its boundary posts resolved. */
public final class HistoryIslandSummary {
    public final HistoryIsland island;
    public final IdRange range;

    public HistoryIslandSummary(HistoryIsland island, IdRange range) {
        this.island = island;
        this.range = range;
    }

    @Override
    public String toString() {
        return island + "=" + range;
    }
}
