package org.telegram.relay.mattermost.ids;

/**
 * The ordering key of a post inside one channel: {@code (create_at ms, post id)}.
 *
 * <p>Local message ids are strictly increasing in this order within a channel. Post ids compare by
 * code point, which equals UTF-8 byte order (SQLite {@code BINARY} collation), so a SQL store can
 * use {@code ORDER BY create_at, post_id} and agree with this type.
 */
public final class PostOrderKey implements Comparable<PostOrderKey> {
    /** Sorts after every character of a real id. */
    static final String MAX_ID_SUFFIX = new String(Character.toChars(0x10FFFF));

    public final long createAt;
    public final String postId;

    public PostOrderKey(long createAt, String postId) {
        this.createAt = createAt;
        this.postId = postId;
    }

    /** A key after every real post created at {@code createAt}. */
    public static PostOrderKey upperBound(long createAt) {
        return new PostOrderKey(createAt, MAX_ID_SUFFIX);
    }

    /** A key before every real post created at {@code createAt}. */
    public static PostOrderKey lowerBound(long createAt) {
        return new PostOrderKey(createAt, "");
    }

    /** Code-point (= UTF-8 byte) order. */
    static int compareIds(String a, String b) {
        int i = 0;
        int j = 0;
        int lengthA = a.length();
        int lengthB = b.length();
        while (i < lengthA && j < lengthB) {
            char ca = a.charAt(i);
            char cb = b.charAt(j);
            if (ca == cb) {
                i++;
                j++;
                continue;
            }
            if (!Character.isSurrogate(ca) && !Character.isSurrogate(cb)) {
                return ca < cb ? -1 : 1;
            }
            int pa = a.codePointAt(i);
            int pb = b.codePointAt(j);
            if (pa != pb) {
                return pa < pb ? -1 : 1;
            }
            i += Character.charCount(pa);
            j += Character.charCount(pb);
        }
        int remainingA = lengthA - i;
        int remainingB = lengthB - j;
        return remainingA == remainingB ? 0 : (remainingA < remainingB ? -1 : 1);
    }

    @Override
    public int compareTo(PostOrderKey other) {
        if (createAt != other.createAt) {
            return createAt < other.createAt ? -1 : 1;
        }
        return compareIds(postId, other.postId);
    }

    public boolean lessThan(PostOrderKey other) {
        return compareTo(other) < 0;
    }

    // MARK: - Parts (PostParts)

    /** The key of part {@code index} of this post ({@code this} must be a post key). */
    public PostOrderKey part(int index) {
        return new PostOrderKey(createAt, PostParts.partId(postId, index));
    }

    /** The post key followed by the keys of parts 1…count-1. */
    public PostOrderKey[] parts(int count) {
        int n = Math.max(1, Math.min(count, PostParts.MAXIMUM_COUNT));
        PostOrderKey[] result = new PostOrderKey[n];
        for (int i = 0; i < n; i++) {
            result[i] = part(i);
        }
        return result;
    }

    /** The server post this key belongs to. */
    public String serverPostId() {
        return PostParts.postIdOfPartId(postId);
    }

    public int partIndex() {
        return PostParts.indexOfPartId(postId);
    }

    /** The lower bound of the range covering this post and its parts. */
    public PostOrderKey partsRangeLower() {
        return new PostOrderKey(createAt, serverPostId());
    }

    /** The upper bound of the range covering this post and its parts. */
    public PostOrderKey partsRangeUpper() {
        return new PostOrderKey(createAt, serverPostId() + PostParts.UPPER_SENTINEL);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof PostOrderKey)) {
            return false;
        }
        PostOrderKey other = (PostOrderKey) o;
        return createAt == other.createAt && postId.equals(other.postId);
    }

    @Override
    public int hashCode() {
        return (int) (createAt ^ (createAt >>> 32)) * 31 + postId.hashCode();
    }

    @Override
    public String toString() {
        return createAt + "/" + postId;
    }
}
