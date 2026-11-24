package org.telegram.relay.mattermost.ids;

/**
 * A post shown as several messages: a post with N files becomes N messages (an album or file group),
 * so it needs N local ids. Part 0 is the post's own id-map entry; parts 1…N-1 are keyed by synthetic
 * ids {@code <post id>#01}, {@code <post id>#02}, …
 *
 * <p>Part keys share the post's {@code create_at} and sort right after the post and before any other
 * post: {@code #} (0x23) sorts before every character of a Mattermost id ({@code [a-z0-9]}), and the
 * two-digit suffix keeps parts in index order. Only cursors (which name server posts) and deletions
 * (which remove a post with its parts) know about parts.
 */
public final class PostParts {
    /** The largest part count. */
    public static final int MAXIMUM_COUNT = 100;
    static final char MARKER = '#';
    /** Sorts after every part suffix of the same post ({@code $} is 0x24). */
    static final String UPPER_SENTINEL = "$";

    private PostParts() {
    }

    /** The id-map id of part {@code index} ({@code 0} is the post id itself). */
    public static String partId(String postId, int index) {
        if (index < 0 || index >= MAXIMUM_COUNT) {
            throw new IllegalArgumentException("part index " + index);
        }
        if (index == 0) {
            return postId;
        }
        return postId + MARKER + (index < 10 ? "0" : "") + index;
    }

    /** The server post id of an id-map id. */
    public static String postIdOfPartId(String id) {
        int index = id.indexOf(MARKER);
        return index < 0 ? id : id.substring(0, index);
    }

    /** The part index of an id-map id (0 for the post itself). */
    public static int indexOfPartId(String id) {
        int index = id.indexOf(MARKER);
        if (index < 0) {
            return 0;
        }
        try {
            return Integer.parseInt(id.substring(index + 1));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public static boolean isPartId(String id) {
        return id.indexOf(MARKER) >= 0;
    }
}
