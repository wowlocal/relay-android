package org.telegram.relay.mattermost.ids;

import java.util.Collections;
import java.util.List;

/**
 * Existing posts whose local ids change (the "renumber" signal). Apply in one transaction: delete every
 * message at a {@code from} id first, then re-insert each at its {@code to} id (ids may be swapped
 * between moved posts, so interleaving delete/insert per move is not safe). Every other stored local id
 * (read state, reply references) must be remapped too. Holes inside {@link #affectedRange} should be
 * recomputed from {@link IngestResult#holes}.
 */
public final class RenumberPlan {
    public final List<RenumberMove> moves;
    /** Contains every {@code from} and {@code to} id of the plan. */
    public final IdRange affectedRange;

    public RenumberPlan(List<RenumberMove> moves, IdRange affectedRange) {
        this.moves = Collections.unmodifiableList(moves);
        this.affectedRange = affectedRange;
    }

    /** The new id of the message at {@code oldId}, or null when it doesn't move. */
    public Integer newId(int oldId) {
        for (RenumberMove move : moves) {
            if (move.from == oldId) {
                return move.to;
            }
        }
        return null;
    }

    @Override
    public String toString() {
        return "RenumberPlan(" + moves.size() + " moves in " + affectedRange + ")";
    }
}
