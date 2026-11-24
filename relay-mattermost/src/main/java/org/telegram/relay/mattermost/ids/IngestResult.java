package org.telegram.relay.mattermost.ids;

import java.util.ArrayList;
import java.util.List;

/** The outcome of one allocator operation. */
public final class IngestResult {
    /** One entry per distinct input post, sorted by {@link PostOrderKey}. */
    public List<PostAllocation> allocations;
    /** Non-null when existing posts had to move. Apply it before inserting the new messages. */
    public final RenumberPlan renumberPlan;
    /** Local id range of the island that now contains the batch; the caller can drop it from its hole index. */
    public final IdRange islandRange;
    /** How many previously separate islands the batch touched; {@code >= 2} means a hole was closed. */
    public final int joinedIslandCount;
    /** All holes of the channel after the operation. */
    public final List<HistoryHole> holes;

    public IngestResult(List<PostAllocation> allocations, RenumberPlan renumberPlan, IdRange islandRange, int joinedIslandCount, List<HistoryHole> holes) {
        this.allocations = allocations != null ? allocations : new ArrayList<PostAllocation>();
        this.renumberPlan = renumberPlan;
        this.islandRange = islandRange;
        this.joinedIslandCount = joinedIslandCount;
        this.holes = holes != null ? holes : new ArrayList<HistoryHole>();
    }

    /** The batch reached an existing island (the hole between them is closed). */
    public boolean didCloseHole() {
        return joinedIslandCount >= 2;
    }

    public int insertedCount() {
        int count = 0;
        for (PostAllocation allocation : allocations) {
            if (allocation.status == PostAllocation.Status.INSERTED) {
                count++;
            }
        }
        return count;
    }

    public Integer localId(String postId) {
        for (PostAllocation allocation : allocations) {
            if (allocation.postId.equals(postId)) {
                return allocation.localId;
            }
        }
        return null;
    }
}
