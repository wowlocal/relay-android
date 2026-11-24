package org.telegram.relay.mattermost.ids;

/** An allocator operation that could not be applied; nothing was written. */
public class MessageIdAllocationException extends Exception {
    public enum Kind {
        /** {@code ingestPage} was called with an anchor that isn't mapped. */
        UNKNOWN_ANCHOR,
        /** The channel has more posts than the id space can hold. */
        CAPACITY_EXHAUSTED
    }

    public final Kind kind;
    /** The anchor post id or the channel id. */
    public final String id;

    public MessageIdAllocationException(Kind kind, String id) {
        super(kind.name().toLowerCase() + ": " + id);
        this.kind = kind;
        this.id = id;
    }
}
