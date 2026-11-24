package org.telegram.relay.mattermost.text;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Mattermost markdown → Telegram-style plain text and entities (UTF-16 ranges). Port of the iOS
 * {@code MattermostMarkdown.parse}; the output is identical for the same input and options.
 *
 * <p>Blocks: paragraphs (every newline is kept, as Mattermost renders soft breaks as line breaks),
 * fenced and indented code ({@link TextEntity.Type#PRE} with the highlighter's language name), {@code >}
 * quotes ({@link TextEntity.Type#BLOCKQUOTE}; nested quotes flatten), headings ({@link TextEntity.Type#HEADING}
 * plus {@link TextEntity.Type#BOLD}), lists ({@code • }/{@code ◦ }/{@code ‣ } by depth, {@code 1. }, {@code ☐}/{@code ☑}
 * for tasks, continuation lines indented by whitespace as wide as the prefix), tables (an aligned
 * monospaced {@code pre} block labelled {@code table} when at most 30 characters wide, else one record
 * per row) and horizontal rules ({@code ———}).
 *
 * <p>Inline: {@code **bold**}/{@code __bold__}, {@code *italic*}/{@code _italic_} (CommonMark flanking
 * rules, so {@code snake_case} stays text), {@code ~~strike~~}, code spans, {@code [text](url)} and
 * {@code ![alt](url)}, {@code <autolinks>}, bare URLs ({@code https://}, {@code http://}, {@code ftp://},
 * {@code www.}) and emails, {@code @mentions}, {@code @channel}/{@code @here}/{@code @all}, {@code ~channels},
 * {@code #hashtags} (3+ characters), {@code :emoji:} shortcodes (built-in → Unicode, else a custom emoji
 * entity over the kept {@code :name:}), emoticons ({@code :)} → 🙂), backslash escapes, and optional
 * {@code $inline latex$} (as code). Mattermost has no spoiler or underline syntax.
 *
 * <p>Thread-safe; every call is independent.
 */
public final class MarkdownToEntities {
    private MarkdownToEntities() {
    }

    /**
     * What the parser renders besides CommonMark, and what it knows about the server. Mutable
     * builder-style; don't change an instance while a parse uses it.
     */
    public static final class Options {
        boolean emoticons = true;
        boolean inlineLatex;
        Map<String, String> users;
        Map<String, String> channels;
        Map<String, String> customEmoji;
        String currentUsername;

        /**
         * Emoticons ({@code :)}, {@code ;-)}, {@code <3}, …) become emoji, as Mattermost's clients render
         * them unless the user's "Render emoticons as emoji" setting ({@code render_emoticons_as_emoji}) is off.
         * Default on.
         */
        public Options setEmoticons(boolean emoticons) {
            this.emoticons = emoticons;
            return this;
        }

        /** {@code $…$} inline LaTeX, shown as inline code (the server's {@code EnableLatex} and {@code EnableInlineLatex}). Default off. */
        public Options setInlineLatex(boolean inlineLatex) {
            this.inlineLatex = inlineLatex;
            return this;
        }

        /**
         * Known users: username → user id (keys are matched lowercased). Mentions of known users get
         * {@link TextEntity#getUserId()}; a known username ending in {@code .}, {@code _} or {@code -} keeps
         * that character. Null (default): every {@code @name} is a mention without a user id.
         */
        public Options setUsers(Map<String, String> usernameToUserId) {
            this.users = lowercasedKeys(usernameToUserId);
            return this;
        }

        /**
         * Known channels of the team: name → channel id. With a map, {@code ~name} of a channel not in
         * it stays plain text (as in Mattermost's clients). Null (default): every {@code ~name} is a channel mention.
         */
        public Options setChannels(Map<String, String> channelNameToId) {
            this.channels = lowercasedKeys(channelNameToId);
            return this;
        }

        /** Custom emoji of the server: name → emoji id, set as {@link TextEntity#getTargetId()} of custom emoji entities. */
        public Options setCustomEmoji(Map<String, String> nameToEmojiId) {
            this.customEmoji = lowercasedKeys(nameToEmojiId);
            return this;
        }

        /**
         * The reading user's username: their own mentions, and {@code @channel}/{@code @here}/{@code @all},
         * are marked {@link TextEntity#isHighlight()}. Null (default): nothing is highlighted.
         */
        public Options setCurrentUsername(String username) {
            this.currentUsername = username == null || username.isEmpty() ? null : username.toLowerCase(Locale.ROOT);
            return this;
        }

        private static Map<String, String> lowercasedKeys(Map<String, String> map) {
            if (map == null) {
                return null;
            }
            Map<String, String> result = new HashMap<String, String>(map.size() * 2);
            for (Map.Entry<String, String> entry : map.entrySet()) {
                if (entry.getKey() != null) {
                    result.put(entry.getKey().toLowerCase(Locale.ROOT), entry.getValue());
                }
            }
            return Collections.unmodifiableMap(result);
        }
    }

    private static final Options DEFAULT = new Options();

    /** Parses with the default options (emoticons on, no inline LaTeX, no server context). */
    public static FormattedText convert(String markdown) {
        return convert(markdown, DEFAULT);
    }

    /** Parses Mattermost markdown into plain text (syntax removed) and sorted entities with UTF-16 ranges. */
    public static FormattedText convert(String markdown, Options options) {
        if (markdown == null || markdown.isEmpty()) {
            return new FormattedText("");
        }
        if (options == null) {
            options = DEFAULT;
        }
        List<String> lines = MarkdownBlocks.splitLines(markdown);
        List<MarkdownBlocks.Item> blocks = MarkdownBlocks.parseBlocks(lines, 0);
        FormattedText rendered = new BlockRenderer(options).render(blocks, 0);
        return finish(rendered);
    }

    /** Whether the text mentions {@code username} (case-insensitive), or has {@code @channel}/{@code @here}/{@code @all} when {@code includeSpecial}. */
    public static boolean mentions(FormattedText formatted, String username, boolean includeSpecial) {
        String lowered = username == null ? null : username.toLowerCase(Locale.ROOT);
        for (TextEntity entity : formatted.getEntities()) {
            if (entity.getType() == TextEntity.Type.MENTION && lowered != null && lowered.equals(entity.getUsername())) {
                return true;
            }
            if (entity.getType() == TextEntity.Type.SPECIAL_MENTION && includeSpecial) {
                return true;
            }
        }
        return false;
    }

    private static FormattedText finish(FormattedText formatted) {
        String text = formatted.getText();
        int length = text.length();
        while (length > 0) {
            int c = text.codePointBefore(length);
            if (!CharClass.isWhiteSpaceProperty(c)) {
                break;
            }
            length -= Character.charCount(c);
        }
        text = text.substring(0, length);
        List<TextEntity> entities = new ArrayList<TextEntity>();
        for (TextEntity entity : formatted.getEntities()) {
            int lower = Math.max(0, entity.getOffset());
            int upper = Math.min(length, entity.getEnd());
            if (upper > lower) {
                entities.add(entity.withRange(lower, upper - lower));
            }
        }
        // Drop entities nested in another entity of the same type (a bold span inside a heading, a
        // quote inside a quote: Telegram can't nest block quotes).
        List<TextEntity> outerFirst = TextEntity.sorted(entities);
        List<TextEntity> kept = new ArrayList<TextEntity>();
        for (TextEntity entity : outerFirst) {
            boolean redundant = false;
            for (TextEntity other : kept) {
                if (other.sameKind(entity) && other.getOffset() <= entity.getOffset() && entity.getEnd() <= other.getEnd()) {
                    redundant = true;
                    break;
                }
            }
            if (!redundant) {
                kept.add(entity);
            }
        }
        return new FormattedText(text, kept);
    }
}
