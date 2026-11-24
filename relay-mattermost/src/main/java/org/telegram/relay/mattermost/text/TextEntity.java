package org.telegram.relay.mattermost.text;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * A formatting entity over plain text, neutral between Mattermost and Telegram (the TL bridge maps
 * it onto {@code TLRPC.MessageEntity}). {@link #getOffset()} and {@link #getLength()} are in UTF-16
 * code units, as Telegram's entities are (and as Java strings index).
 *
 * <p>Port of the iOS {@code MattermostSyncCore.TextEntity}. Immutable.
 */
public final class TextEntity {
    /** Entity types: Telegram's, plus the Mattermost ones the bridge maps onto links or custom entities. */
    public enum Type {
        BOLD,
        ITALIC,
        STRIKETHROUGH,
        /** Not produced by Mattermost markdown (it has no underline syntax); kept for the outgoing direction. */
        UNDERLINE,
        CODE,
        /** A code block; {@link #getLanguage()} is the highlighter's name (see {@link CodeLanguage}) or null. */
        PRE,
        /** A link whose text differs from its target ({@code [text](url)}); see {@link #getUrl()}. */
        TEXT_URL,
        /** A bare or {@code <…>} URL; the target is the covered text. */
        URL,
        /** An email address; the target is the covered text. */
        EMAIL,
        /**
         * {@code @username}: {@link #getUsername()} is lowercased, without {@code @}. {@link #getUserId()} is
         * set when the user is known (incoming: from the context; outgoing: Telegram's mention-by-id, whose
         * username the serializer resolves).
         */
        MENTION,
        /** {@code ~channel-name}: {@link #getName()} is lowercased, without {@code ~}; {@link #getTargetId()} the channel id when known. */
        CHANNEL_MENTION,
        /** {@code @channel}, {@code @here}, {@code @all}: {@link #getName()} is the lowercased keyword. */
        SPECIAL_MENTION,
        BLOCKQUOTE,
        /** {@code #hashtag}; the tag is the covered text. */
        HASHTAG,
        /**
         * {@code :name:} with no built-in Unicode emoji; the text keeps {@code :name:}. {@link #getName()} is
         * lowercased; {@link #getTargetId()} is the custom emoji's id when the context knows it.
         */
        CUSTOM_EMOJI,
        /**
         * A heading ({@code #}…{@code ######}, setext), {@link #getLevel()} 1–6. Always paired with
         * {@link #BOLD} over the same range, so renderers without heading sizes show bold text.
         */
        HEADING
    }

    /** {@code @channel}, {@code @here} and {@code @all}, as {@link #getName()} of a {@link Type#SPECIAL_MENTION}. */
    public static final String SPECIAL_CHANNEL = "channel";
    public static final String SPECIAL_HERE = "here";
    public static final String SPECIAL_ALL = "all";

    private final Type type;
    private final int offset;
    private final int length;
    private final String url;
    private final String userId;
    private final String username;
    private final String language;
    private final String name;
    private final String targetId;
    private final int level;
    private final boolean highlight;

    private TextEntity(Type type, int offset, int length, String url, String userId, String username, String language,
                       String name, String targetId, int level, boolean highlight) {
        if (type == null) {
            throw new IllegalArgumentException("type");
        }
        if (offset < 0 || length < 0) {
            throw new IllegalArgumentException("negative range " + offset + "+" + length);
        }
        this.type = type;
        this.offset = offset;
        this.length = length;
        this.url = url;
        this.userId = userId;
        this.username = username;
        this.language = language;
        this.name = name;
        this.targetId = targetId;
        this.level = level;
        this.highlight = highlight;
    }

    /** An entity without attributes (bold, italic, strikethrough, underline, code, url, email, blockquote, hashtag). */
    public static TextEntity of(Type type, int offset, int length) {
        return new TextEntity(type, offset, length, null, null, null, null, null, null, 0, false);
    }

    public static TextEntity pre(int offset, int length, String language) {
        return new TextEntity(Type.PRE, offset, length, null, null, null, language, null, null, 0, false);
    }

    public static TextEntity textUrl(int offset, int length, String url) {
        return new TextEntity(Type.TEXT_URL, offset, length, url, null, null, null, null, null, 0, false);
    }

    /** {@code @username} (stored lowercased). */
    public static TextEntity mention(int offset, int length, String username) {
        return new TextEntity(Type.MENTION, offset, length, null, null, lower(username), null, null, null, 0, false);
    }

    /** A mention of a user by id, without a known username (Telegram's {@code messageEntityMentionName}). */
    public static TextEntity mentionUser(int offset, int length, String userId) {
        return new TextEntity(Type.MENTION, offset, length, null, userId, null, null, null, null, 0, false);
    }

    public static TextEntity channelMention(int offset, int length, String channelName) {
        return new TextEntity(Type.CHANNEL_MENTION, offset, length, null, null, null, null, lower(channelName), null, 0, false);
    }

    /** {@code kind}: {@link #SPECIAL_CHANNEL}, {@link #SPECIAL_HERE} or {@link #SPECIAL_ALL}. */
    public static TextEntity specialMention(int offset, int length, String kind) {
        return new TextEntity(Type.SPECIAL_MENTION, offset, length, null, null, null, null, lower(kind), null, 0, false);
    }

    public static TextEntity customEmoji(int offset, int length, String name) {
        return new TextEntity(Type.CUSTOM_EMOJI, offset, length, null, null, null, null, lower(name), null, 0, false);
    }

    public static TextEntity heading(int offset, int length, int level) {
        return new TextEntity(Type.HEADING, offset, length, null, null, null, null, null, null, Math.max(1, Math.min(6, level)), false);
    }

    private static String lower(String value) {
        return value == null ? null : value.toLowerCase(java.util.Locale.ROOT);
    }

    // MARK: Accessors

    public Type getType() {
        return type;
    }

    /** Start in UTF-16 code units. */
    public int getOffset() {
        return offset;
    }

    /** Length in UTF-16 code units. */
    public int getLength() {
        return length;
    }

    /** {@code offset + length}. */
    public int getEnd() {
        return offset + length;
    }

    /** {@link Type#TEXT_URL}: the target. */
    public String getUrl() {
        return url;
    }

    /** {@link Type#MENTION}: the Mattermost user id when known. */
    public String getUserId() {
        return userId;
    }

    /** {@link Type#MENTION}: the lowercased username (without {@code @}), or null for a mention by id. */
    public String getUsername() {
        return username;
    }

    /** {@link Type#PRE}: the code block's language, or null. */
    public String getLanguage() {
        return language;
    }

    /** Channel name ({@link Type#CHANNEL_MENTION}), keyword ({@link Type#SPECIAL_MENTION}) or emoji name ({@link Type#CUSTOM_EMOJI}). */
    public String getName() {
        return name;
    }

    /** The channel id ({@link Type#CHANNEL_MENTION}) or custom emoji id ({@link Type#CUSTOM_EMOJI}) when known. */
    public String getTargetId() {
        return targetId;
    }

    /** {@link Type#HEADING}: 1–6. */
    public int getLevel() {
        return level;
    }

    /**
     * A mention that concerns the current user: their own {@code @username}, or {@code @channel}/{@code @here}/{@code @all}.
     * Set by {@link MarkdownToEntities} when the options name the current user.
     */
    public boolean isHighlight() {
        return highlight;
    }

    // MARK: Copies

    public TextEntity withRange(int newOffset, int newLength) {
        return new TextEntity(type, newOffset, newLength, url, userId, username, language, name, targetId, level, highlight);
    }

    public TextEntity withUserId(String newUserId) {
        return new TextEntity(type, offset, length, url, newUserId, username, language, name, targetId, level, highlight);
    }

    public TextEntity withUsername(String newUsername) {
        return new TextEntity(type, offset, length, url, userId, lower(newUsername), language, name, targetId, level, highlight);
    }

    public TextEntity withTargetId(String newTargetId) {
        return new TextEntity(type, offset, length, url, userId, username, language, name, newTargetId, level, highlight);
    }

    public TextEntity withHighlight(boolean newHighlight) {
        return new TextEntity(type, offset, length, url, userId, username, language, name, targetId, level, newHighlight);
    }

    /** Same type and attributes (the range and the highlight flag aside). */
    public boolean sameKind(TextEntity other) {
        return type == other.type
            && level == other.level
            && eq(url, other.url)
            && eq(userId, other.userId)
            && eq(username, other.username)
            && eq(language, other.language)
            && eq(name, other.name)
            && eq(targetId, other.targetId);
    }

    private static boolean eq(Object a, Object b) {
        return a == null ? b == null : a.equals(b);
    }

    /** Order among entities sharing a range (outer first); same as iOS. */
    int sortRank() {
        switch (type) {
            case BLOCKQUOTE: return 0;
            case HEADING: return 1;
            case PRE: return 2;
            case TEXT_URL: return 3;
            case BOLD: return 4;
            case ITALIC: return 5;
            case UNDERLINE: return 6;
            case STRIKETHROUGH: return 7;
            case CODE: return 8;
            case URL: return 9;
            case EMAIL: return 10;
            case MENTION: return 11;
            case CHANNEL_MENTION: return 12;
            case SPECIAL_MENTION: return 13;
            case HASHTAG: return 14;
            case CUSTOM_EMOJI: return 15;
            default: return 16;
        }
    }

    /** Sorted by start, then longer first, then a fixed type order (outer first). */
    public static final Comparator<TextEntity> ORDER = new Comparator<TextEntity>() {
        @Override
        public int compare(TextEntity lhs, TextEntity rhs) {
            if (lhs.offset != rhs.offset) {
                return lhs.offset < rhs.offset ? -1 : 1;
            }
            if (lhs.getEnd() != rhs.getEnd()) {
                return lhs.getEnd() > rhs.getEnd() ? -1 : 1;
            }
            if (lhs.sortRank() != rhs.sortRank()) {
                return lhs.sortRank() < rhs.sortRank() ? -1 : 1;
            }
            return lhs.attributesString().compareTo(rhs.attributesString());
        }
    };

    /** A sorted copy (see {@link #ORDER}). */
    public static List<TextEntity> sorted(List<TextEntity> entities) {
        List<TextEntity> copy = new ArrayList<TextEntity>(entities);
        Collections.sort(copy, ORDER);
        return copy;
    }

    private String attributesString() {
        StringBuilder builder = new StringBuilder();
        if (url != null) {
            builder.append(" url=").append(url);
        }
        if (userId != null) {
            builder.append(" userId=").append(userId);
        }
        if (username != null) {
            builder.append(" username=").append(username);
        }
        if (language != null) {
            builder.append(" language=").append(language);
        }
        if (name != null) {
            builder.append(" name=").append(name);
        }
        if (targetId != null) {
            builder.append(" targetId=").append(targetId);
        }
        if (level != 0) {
            builder.append(" level=").append(level);
        }
        if (highlight) {
            builder.append(" highlight");
        }
        return builder.toString();
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }
        if (!(object instanceof TextEntity)) {
            return false;
        }
        TextEntity other = (TextEntity) object;
        return offset == other.offset && length == other.length && highlight == other.highlight && sameKind(other);
    }

    @Override
    public int hashCode() {
        int result = type.hashCode();
        result = 31 * result + offset;
        result = 31 * result + length;
        result = 31 * result + level;
        result = 31 * result + (highlight ? 1 : 0);
        result = 31 * result + (url != null ? url.hashCode() : 0);
        result = 31 * result + (userId != null ? userId.hashCode() : 0);
        result = 31 * result + (username != null ? username.hashCode() : 0);
        result = 31 * result + (language != null ? language.hashCode() : 0);
        result = 31 * result + (name != null ? name.hashCode() : 0);
        result = 31 * result + (targetId != null ? targetId.hashCode() : 0);
        return result;
    }

    @Override
    public String toString() {
        return type + "@" + offset + ".." + getEnd() + attributesString();
    }
}
