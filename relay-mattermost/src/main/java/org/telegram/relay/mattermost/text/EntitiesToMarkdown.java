package org.telegram.relay.mattermost.text;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;

/**
 * Composer output (plain text + Telegram-style entities) → Mattermost markdown. Port of the iOS
 * {@code MattermostMarkdown.markdown(from:)} and {@code OutgoingText}, extended with block quotes,
 * headings and an exact-literal mode.
 *
 * <ul>
 *   <li>{@link TextEntity.Type#BOLD} → {@code **…**}, {@link TextEntity.Type#ITALIC} → {@code _…_}
 *       ({@code *…*} next to letters or digits), {@link TextEntity.Type#STRIKETHROUGH} → {@code ~~…~~},
 *       {@link TextEntity.Type#CODE} → a code span with a fence longer than any backtick run inside,
 *       {@link TextEntity.Type#PRE} → a fenced block on its own lines (with the language),
 *       {@link TextEntity.Type#TEXT_URL} → {@code [text](url)} ({@code <url>} when it needs it).
 *       Markdown only nests, so crossing entities are split; whitespace stays outside delimiters, and
 *       emphasis is closed at line ends.</li>
 *   <li>{@link TextEntity.Type#MENTION}: the covered text becomes {@code @username} (the entity's username,
 *       or the {@link UsernameResolver}'s for a mention by user id); {@link TextEntity.Type#CUSTOM_EMOJI}
 *       with a name becomes {@code :name:}.</li>
 *   <li>{@link TextEntity.Type#BLOCKQUOTE} → {@code > } lines; {@link TextEntity.Type#HEADING} over a
 *       whole line → {@code ## } (the bold over the same range is implied).</li>
 *   <li>Other entities (URL, email, hashtag, underline, …) are dropped; their text stays.</li>
 * </ul>
 *
 * <p>Plain text: by default it is passed through unescaped, as iOS does, because Mattermost users type
 * markdown directly ({@code # heading}, {@code - lists}, tables). With
 * {@link Options#setEscapeLiterals(boolean)} every character the parser would read as markdown is
 * backslash-escaped instead (only where it would be read so), while URLs, emails, mentions, channel
 * links, hashtags and custom emoji stay live; then {@code MarkdownToEntities.convert(convert(text,
 * entities))} gives back the same text and composer entities. Use that for text that is not the
 * user's own typing (an edit of a rendered post, a forward).
 *
 * <p>Thread-safe; every call is independent.
 */
public final class EntitiesToMarkdown {
    private EntitiesToMarkdown() {
    }

    /** Resolves the username of a Mattermost user id (for mentions by id). */
    public interface UsernameResolver {
        /** The username without {@code @}, or null when unknown (the text is then kept). */
        String usernameForUserId(String userId);
    }

    public static final class Options {
        boolean escapeLiterals;
        boolean inlineLatex;
        UsernameResolver usernames;

        /** Escape literal markdown in plain text so it round-trips exactly. Default off (pass-through, as iOS). */
        public Options setEscapeLiterals(boolean escapeLiterals) {
            this.escapeLiterals = escapeLiterals;
            return this;
        }

        /** The server renders {@code $…$} as inline LaTeX: escape literal dollars (with {@link #setEscapeLiterals}). */
        public Options setInlineLatex(boolean inlineLatex) {
            this.inlineLatex = inlineLatex;
            return this;
        }

        public Options setUsernameResolver(UsernameResolver usernames) {
            this.usernames = usernames;
            return this;
        }
    }

    private static final Options DEFAULT = new Options();

    public static String convert(FormattedText formatted) {
        return convert(formatted.getText(), formatted.getEntities(), DEFAULT);
    }

    public static String convert(FormattedText formatted, Options options) {
        return convert(formatted.getText(), formatted.getEntities(), options);
    }

    public static String convert(String text, List<TextEntity> entities) {
        return convert(text, entities, DEFAULT);
    }

    public static String convert(String text, List<TextEntity> entities, Options options) {
        if (text == null) {
            text = "";
        }
        if (entities == null) {
            entities = Collections.emptyList();
        }
        if (options == null) {
            options = DEFAULT;
        }
        FormattedText replaced = replaceTokens(text, entities, options);
        return new Serializer(replaced.getText(), replaced.getEntities(), options).serialize();
    }

    // MARK: Mentions and custom emoji

    /**
     * Mentions become {@code @username} and named custom emoji {@code :name:}; the other entities'
     * ranges are shifted. Replacements go back to front so earlier ranges stay valid.
     */
    static FormattedText replaceTokens(String text, List<TextEntity> entities, Options options) {
        List<TextEntity> candidates = new ArrayList<TextEntity>();
        for (TextEntity entity : entities) {
            if (entity.getType() == TextEntity.Type.MENTION || (entity.getType() == TextEntity.Type.CUSTOM_EMOJI && entity.getName() != null)) {
                candidates.add(entity);
            }
        }
        if (candidates.isEmpty()) {
            return new FormattedText(text, entities);
        }
        Collections.sort(candidates, new Comparator<TextEntity>() {
            @Override
            public int compare(TextEntity lhs, TextEntity rhs) {
                return lhs.getOffset() != rhs.getOffset() ? (lhs.getOffset() > rhs.getOffset() ? -1 : 1) : 0;
            }
        });
        StringBuilder units = new StringBuilder(text);
        List<int[]> shifts = new ArrayList<int[]>();
        int lowestReplaced = Integer.MAX_VALUE;
        for (TextEntity entity : candidates) {
            int lower = snap(text, entity.getOffset());
            int upper = snap(text, entity.getEnd());
            if (upper <= lower || upper > lowestReplaced) {
                continue;
            }
            String replacement;
            if (entity.getType() == TextEntity.Type.MENTION) {
                String name = entity.getUsername();
                if (name == null && entity.getUserId() != null && options.usernames != null) {
                    name = options.usernames.usernameForUserId(entity.getUserId());
                }
                if (name == null || name.isEmpty()) {
                    continue;
                }
                replacement = "@" + (name.startsWith("@") ? name.substring(1) : name);
            } else {
                replacement = ":" + entity.getName() + ":";
            }
            if (text.substring(lower, upper).equalsIgnoreCase(replacement)) {
                continue;
            }
            units.replace(lower, upper, replacement);
            shifts.add(new int[] {upper, replacement.length() - (upper - lower)});
            lowestReplaced = lower;
        }
        if (shifts.isEmpty()) {
            return new FormattedText(text, entities);
        }
        List<TextEntity> shifted = new ArrayList<TextEntity>();
        for (TextEntity entity : entities) {
            int lower = shift(shifts, entity.getOffset());
            int upper = shift(shifts, entity.getEnd());
            if (upper > lower) {
                shifted.add(entity.withRange(lower, upper - lower));
            }
        }
        return new FormattedText(units.toString(), shifted);
    }

    private static int shift(List<int[]> shifts, int offset) {
        int result = offset;
        for (int[] shift : shifts) {
            if (offset >= shift[0]) {
                result += shift[1];
            }
        }
        return result;
    }

    /** Clamps to the text and moves an offset inside a surrogate pair to its start. */
    static int snap(String text, int offset) {
        int value = Math.max(0, Math.min(text.length(), offset));
        if (value > 0 && value < text.length() && Character.isLowSurrogate(text.charAt(value)) && Character.isHighSurrogate(text.charAt(value - 1))) {
            value--;
        }
        return value;
    }

    // MARK: Serializer

    private static final int TEXT_URL = 0;
    private static final int BOLD = 1;
    private static final int ITALIC = 2;
    private static final int STRIKE = 3;
    private static final int CODE = 4;

    private static final class Span {
        int start;
        int end;
        final int kind;
        final String url;

        Span(int start, int end, int kind, String url) {
            this.start = start;
            this.end = end;
            this.kind = kind;
            this.url = url;
        }

        boolean sameKind(Span other) {
            return kind == other.kind && (url == null ? other.url == null : url.equals(other.url));
        }

        Span with(int newStart, int newEnd) {
            return new Span(newStart, newEnd, kind, url);
        }
    }

    private static final class Range {
        int start;
        int end;
        final String language;

        Range(int start, int end, String language) {
            this.start = start;
            this.end = end;
            this.language = language;
        }
    }

    private static final Comparator<Range> BY_START = new Comparator<Range>() {
        @Override
        public int compare(Range lhs, Range rhs) {
            return lhs.start < rhs.start ? -1 : (lhs.start == rhs.start ? 0 : 1);
        }
    };

    private static final class Serializer {
        final String units;
        final int count;
        final Options options;
        final List<Span> spans = new ArrayList<Span>();
        final List<Range> pres = new ArrayList<Range>();
        final List<Range> quotes = new ArrayList<Range>();
        /** Heading level by start offset (0: none). */
        final int[] headingAt;
        final LiteralEscaper escaper;

        Serializer(String text, List<TextEntity> entities, Options options) {
            this.units = text;
            this.count = text.length();
            this.options = options;
            this.headingAt = new int[count + 1];
            List<Range> headings = new ArrayList<Range>();
            for (TextEntity entity : entities) {
                int lower = snap(text, entity.getOffset());
                int upper = snap(text, entity.getEnd());
                if (upper <= lower) {
                    continue;
                }
                switch (entity.getType()) {
                    case BOLD:
                        spans.add(new Span(lower, upper, BOLD, null));
                        break;
                    case ITALIC:
                        spans.add(new Span(lower, upper, ITALIC, null));
                        break;
                    case STRIKETHROUGH:
                        spans.add(new Span(lower, upper, STRIKE, null));
                        break;
                    case CODE:
                        spans.add(new Span(lower, upper, CODE, null));
                        break;
                    case TEXT_URL:
                        if (entity.getUrl() != null && !entity.getUrl().isEmpty()) {
                            spans.add(new Span(lower, upper, TEXT_URL, entity.getUrl()));
                        }
                        break;
                    case PRE:
                        pres.add(new Range(lower, upper, entity.getLanguage()));
                        break;
                    case BLOCKQUOTE:
                        quotes.add(new Range(lower, upper, null));
                        break;
                    case HEADING:
                        headings.add(new Range(lower, upper, String.valueOf(entity.getLevel())));
                        break;
                    default:
                        // Mentions were replaced already; links, hashtags and the rest are plain text in markdown.
                        break;
                }
            }
            mergePres();
            mergeQuotes();
            for (Range heading : headings) {
                addHeading(heading);
            }
            if (options.escapeLiterals) {
                List<Integer> blockEdges = new ArrayList<Integer>();
                for (Range pre : pres) {
                    blockEdges.add(pre.end);
                }
                for (Range quote : quotes) {
                    blockEdges.add(quote.start);
                    blockEdges.add(quote.end);
                }
                escaper = new LiteralEscaper(text, options, blockEdges);
            } else {
                escaper = null;
            }
        }

        private void mergePres() {
            Collections.sort(pres, BY_START);
            List<Range> merged = new ArrayList<Range>();
            for (Range pre : pres) {
                Range last = merged.isEmpty() ? null : merged.get(merged.size() - 1);
                if (last != null && pre.start <= last.end) {
                    last.end = Math.max(last.end, pre.end);
                } else {
                    merged.add(new Range(pre.start, pre.end, pre.language));
                }
            }
            pres.clear();
            pres.addAll(merged);
        }

        /**
         * Quotes without their leading and trailing newlines; quotes separated by a single newline (or
         * overlapping) become one: markdown can't put two quotes on consecutive lines.
         */
        private void mergeQuotes() {
            List<Range> trimmed = new ArrayList<Range>();
            for (Range quote : quotes) {
                int start = quote.start;
                int end = quote.end;
                while (start < end && units.charAt(start) == '\n') {
                    start++;
                }
                while (end > start && units.charAt(end - 1) == '\n') {
                    end--;
                }
                if (end > start) {
                    trimmed.add(new Range(start, end, null));
                }
            }
            Collections.sort(trimmed, BY_START);
            List<Range> merged = new ArrayList<Range>();
            for (Range quote : trimmed) {
                Range last = merged.isEmpty() ? null : merged.get(merged.size() - 1);
                if (last != null && (quote.start <= last.end || (quote.start == last.end + 1 && units.charAt(last.end) == '\n'))) {
                    last.end = Math.max(last.end, quote.end);
                } else {
                    merged.add(quote);
                }
            }
            quotes.clear();
            quotes.addAll(merged);
        }

        /** A heading over one whole line, outside code blocks; its bold over the same range is implied. */
        private void addHeading(Range heading) {
            boolean startsLine = heading.start == 0 || units.charAt(heading.start - 1) == '\n' || isQuoteStart(heading.start);
            boolean endsLine = heading.end == count || units.charAt(heading.end) == '\n';
            if (!startsLine || !endsLine || units.substring(heading.start, heading.end).indexOf('\n') >= 0) {
                return;
            }
            for (Range pre : pres) {
                if (pre.start < heading.end && heading.start < pre.end) {
                    return;
                }
            }
            int level = Integer.parseInt(heading.language);
            headingAt[heading.start] = Math.max(1, Math.min(6, level));
            for (int k = spans.size() - 1; k >= 0; k--) {
                Span span = spans.get(k);
                if (span.kind == BOLD && span.start == heading.start && span.end == heading.end) {
                    spans.remove(k);
                }
            }
        }

        private boolean isQuoteStart(int offset) {
            for (Range quote : quotes) {
                if (quote.start == offset) {
                    return true;
                }
            }
            return false;
        }

        String serialize() {
            StringBuilder output = new StringBuilder(count + 32);
            int position = 0;
            for (Range quote : quotes) {
                if (position < quote.start) {
                    serializeBlocks(position, quote.start, output);
                }
                if (output.length() > 0 && output.charAt(output.length() - 1) != '\n') {
                    output.append('\n');
                }
                StringBuilder inner = new StringBuilder();
                serializeBlocks(quote.start, quote.end, inner);
                appendQuoted(inner, output);
                position = quote.end;
                if (position < count) {
                    if (units.charAt(position) != '\n') {
                        // The quote ends inside a line: the rest goes on a line of its own, after an empty
                        // quote line so it is not a lazy continuation of the quote.
                        output.append("\n>\n");
                    } else if (position + 1 < count && units.charAt(position + 1) != '\n') {
                        output.append("\n>");
                    }
                }
            }
            if (position < count) {
                serializeBlocks(position, count, output);
            }
            return output.toString();
        }

        private static void appendQuoted(CharSequence inner, StringBuilder output) {
            int start = 0;
            int length = inner.length();
            while (true) {
                int newline = start;
                while (newline < length && inner.charAt(newline) != '\n') {
                    newline++;
                }
                if (newline == start) {
                    output.append('>');
                } else {
                    output.append("> ").append(inner, start, newline);
                }
                if (newline >= length) {
                    break;
                }
                output.append('\n');
                start = newline + 1;
            }
        }

        /** Code blocks on their own lines, inline markdown between them (iOS {@code serialize}). */
        private void serializeBlocks(int start, int end, StringBuilder output) {
            int position = start;
            for (Range pre : pres) {
                int preStart = Math.max(start, pre.start);
                int preEnd = Math.min(end, pre.end);
                if (preEnd <= preStart) {
                    continue;
                }
                if (position < preStart) {
                    renderInline(position, preStart, output);
                }
                if (output.length() > 0 && output.charAt(output.length() - 1) != '\n') {
                    output.append('\n');
                }
                int contentEnd = preEnd;
                while (contentEnd > preStart && units.charAt(contentEnd - 1) == '\n') {
                    contentEnd--;
                }
                String content = units.substring(preStart, contentEnd);
                String fence = repeat('`', Math.max(3, longestRun('`', content) + 1));
                output.append(fence);
                if (pre.language != null && !pre.language.isEmpty()) {
                    output.append(pre.language);
                }
                output.append('\n').append(content).append('\n').append(fence);
                if (preEnd < end && units.charAt(preEnd) != '\n') {
                    output.append('\n');
                }
                position = preEnd;
            }
            if (position < end) {
                renderInline(position, end, output);
            }
        }

        private static int longestRun(char c, CharSequence s) {
            int longest = 0;
            int current = 0;
            for (int k = 0; k < s.length(); k++) {
                if (s.charAt(k) == c) {
                    current++;
                    longest = Math.max(longest, current);
                } else {
                    current = 0;
                }
            }
            return longest;
        }

        private static boolean isWhitespaceUnit(char unit) {
            return unit == ' ' || unit == '\n' || unit == '\t' || unit == '\r';
        }

        private static boolean isAlphanumericUnit(int index, String units) {
            if (index < 0 || index >= units.length()) {
                return false;
            }
            char unit = units.charAt(index);
            if (unit < 128) {
                return CharClass.isASCIIAlphanumeric(unit);
            }
            if (Character.isSurrogate(unit)) {
                return false;
            }
            return Character.isAlphabetic(unit) || CharClass.isNumeric(unit);
        }

        private void renderInline(int rangeStart, int rangeEnd, StringBuilder output) {
            // Clip to this segment.
            List<Span> clipped = new ArrayList<Span>();
            for (Span span : spans) {
                int lower = Math.max(span.start, rangeStart);
                int upper = Math.min(span.end, rangeEnd);
                if (upper > lower) {
                    clipped.add(span.with(lower, upper));
                }
            }

            // Code is atomic: merge overlapping code spans, drop formatting inside them and widen
            // formatting that partially overlaps them.
            List<Span> codes = new ArrayList<Span>();
            for (Span span : clipped) {
                if (span.kind == CODE) {
                    codes.add(span);
                }
            }
            Collections.sort(codes, SPAN_BY_START);
            List<Span> mergedCodes = new ArrayList<Span>();
            for (Span code : codes) {
                Span last = mergedCodes.isEmpty() ? null : mergedCodes.get(mergedCodes.size() - 1);
                if (last != null && code.start <= last.end) {
                    last.end = Math.max(last.end, code.end);
                } else {
                    mergedCodes.add(code.with(code.start, code.end));
                }
            }
            List<Span> adjusted = new ArrayList<Span>(mergedCodes);
            for (Span span : clipped) {
                if (span.kind == CODE) {
                    continue;
                }
                int lower = span.start;
                int upper = span.end;
                boolean insideCode = false;
                for (Span code : mergedCodes) {
                    // Formatting over exactly the code's range wraps it (`**`x`**`); inside it, it's dropped.
                    if (code.start <= lower && upper <= code.end && !(code.start == lower && code.end == upper)) {
                        insideCode = true;
                        break;
                    }
                    if (code.start <= lower && lower < code.end) {
                        lower = code.start;
                    }
                    if (upper > code.start && upper < code.end) {
                        upper = code.end;
                    }
                }
                if (!insideCode) {
                    adjusted.add(span.with(lower, upper));
                }
            }

            // Merge overlapping spans of the same kind.
            Collections.sort(adjusted, SPAN_BY_START);
            List<Span> byKind = new ArrayList<Span>();
            for (Span span : adjusted) {
                int index = -1;
                for (int k = byKind.size() - 1; k >= 0; k--) {
                    Span other = byKind.get(k);
                    if (other.sameKind(span) && other.end >= span.start) {
                        index = k;
                        break;
                    }
                }
                if (index >= 0) {
                    Span other = byKind.get(index);
                    byKind.set(index, other.with(other.start, Math.max(other.end, span.end)));
                } else {
                    byKind.add(span);
                }
            }

            // Split at newlines (markdown emphasis can't span blocks). In literal mode nothing at a line
            // start can begin a block, so only blank lines (paragraph ends) split.
            List<Span> current = new ArrayList<Span>();
            for (Span span : byKind) {
                if (span.kind == CODE) {
                    current.add(span);
                    continue;
                }
                int start = span.start;
                for (int index = span.start; index < span.end; index++) {
                    if (units.charAt(index) == '\n' && (escaper == null || (index + 1 < count && units.charAt(index + 1) == '\n')
                        || (index > 0 && units.charAt(index - 1) == '\n'))) {
                        current.add(span.with(start, index));
                        start = index + 1;
                    }
                }
                current.add(span.with(start, span.end));
            }

            // Markdown can only nest, so split crossing spans, and keep whitespace outside the
            // delimiters (`**bold **` is not bold). Repeat until stable: trimming can create a crossing.
            boolean changed = true;
            int rounds = 0;
            while (changed && rounds < 64) {
                changed = false;
                rounds++;
                List<Span> trimmed = new ArrayList<Span>();
                for (Span span : current) {
                    int lower = span.start;
                    int upper = span.end;
                    if (span.kind != CODE) {
                        while (lower < upper && isWhitespaceUnit(units.charAt(lower))) {
                            lower++;
                        }
                        while (upper > lower && isWhitespaceUnit(units.charAt(upper - 1))) {
                            upper--;
                        }
                    }
                    if (lower != span.start || upper != span.end) {
                        changed = true;
                    }
                    if (upper > lower) {
                        trimmed.add(span.with(lower, upper));
                    }
                }
                current = trimmed;
                crossing:
                for (int i = 0; i < current.size(); i++) {
                    for (int j = 0; j < current.size(); j++) {
                        if (i == j) {
                            continue;
                        }
                        Span a = current.get(i);
                        Span b = current.get(j);
                        if (a.start < b.start && b.start < a.end && a.end < b.end) {
                            current.set(j, b.with(b.start, a.end));
                            current.add(b.with(a.end, b.end));
                            changed = true;
                            break crossing;
                        }
                    }
                }
            }

            Collections.sort(current, new Comparator<Span>() {
                @Override
                public int compare(Span lhs, Span rhs) {
                    if (lhs.start != rhs.start) {
                        return lhs.start < rhs.start ? -1 : 1;
                    }
                    if (lhs.end != rhs.end) {
                        return lhs.end > rhs.end ? -1 : 1;
                    }
                    return lhs.kind < rhs.kind ? -1 : (lhs.kind == rhs.kind ? 0 : 1);
                }
            });

            TreeSet<Integer> boundaries = new TreeSet<Integer>();
            for (Span span : current) {
                boundaries.add(span.start);
                boundaries.add(span.end);
            }
            for (int k = rangeStart; k < rangeEnd; k++) {
                if (headingAt[k] > 0) {
                    boundaries.add(k);
                }
            }
            LiteralEscaper.Tokens tokens = null;
            delimiterOverrides.clear();
            if (escaper != null) {
                tokens = escaper.scanSegment(rangeStart, rangeEnd, emittedDelimiters(current, boundaries));
                // Use the other emphasis character when a URL's tail inside the span has this one, then
                // look at the tokens again with the delimiters that are really emitted.
                java.util.Set<Integer> tails = new java.util.HashSet<Integer>();
                urlLayout(tokens, boundaries, null, tails);
                if (!tails.isEmpty()) {
                    for (Span span : current) {
                        String[] alternative = alternativeDelimiters(span, tails);
                        if (alternative != null) {
                            delimiterOverrides.put(span, alternative);
                        }
                    }
                    if (!delimiterOverrides.isEmpty()) {
                        tokens = escaper.scanSegment(rangeStart, rangeEnd, emittedDelimiters(current, boundaries));
                    }
                }
            }
            Emitter emitter = new Emitter(rangeStart, output, boundaries, tokens);
            int nextSpan = 0;
            for (int boundary : boundaries) {
                emitter.emitText(boundary);
                // Spans are properly nested, so the ones ending here are on top of the stack.
                while (!emitter.stack.isEmpty() && emitter.top().span.end == boundary) {
                    emitter.close();
                }
                if (boundary < count && headingAt[boundary] > 0) {
                    output.append(repeat('#', headingAt[boundary])).append(' ');
                }
                while (nextSpan < current.size() && current.get(nextSpan).start == boundary) {
                    emitter.open(current.get(nextSpan));
                    nextSpan++;
                }
            }
            emitter.emitText(rangeEnd);
            while (!emitter.stack.isEmpty()) {
                emitter.close();
            }
        }

        /**
         * What the serializer emits at each boundary, as the parser sees it next to the text there:
         * closing delimiters innermost first, a heading prefix, opening delimiters outermost first. A
         * link's closing is {@code "])"}: the parser stops at its {@code ]}, then the text follows a {@code )}.
         */
        private java.util.Map<Integer, String> emittedDelimiters(List<Span> sorted, TreeSet<Integer> boundaries) {
            java.util.Map<Integer, String> result = new java.util.HashMap<Integer, String>();
            for (int boundary : boundaries) {
                StringBuilder emitted = new StringBuilder();
                for (int k = sorted.size() - 1; k >= 0; k--) {
                    Span span = sorted.get(k);
                    if (span.end == boundary && span.start < boundary) {
                        emitted.append(span.kind == TEXT_URL ? "])" : delimiters(span)[1]);
                    }
                }
                if (boundary < count && headingAt[boundary] > 0) {
                    emitted.append(repeat('#', headingAt[boundary])).append(' ');
                }
                for (Span span : sorted) {
                    if (span.start == boundary) {
                        emitted.append(delimiters(span)[0]);
                    }
                }
                if (emitted.length() > 0) {
                    result.put(boundary, emitted.toString());
                }
            }
            return result;
        }

        private final class Active {
            final Span span;
            final String closing;

            Active(Span span, String closing) {
                this.span = span;
                this.closing = closing;
            }
        }

        private final class Emitter {
            final StringBuilder output;
            final List<Active> stack = new ArrayList<Active>();
            final TreeSet<Integer> boundaries;
            /** URL tokens wrapped in {@code <…>}: start → end. */
            final java.util.Map<Integer, Integer> angleUrls = new java.util.HashMap<Integer, Integer>();
            /** Characters right after an unwrapped URL: escaped only when an open delimiter uses them. */
            final java.util.Set<Integer> urlTails = new java.util.HashSet<Integer>();
            final LiteralEscaper.Tokens tokens;
            int position;
            int linkDepth;
            int angleEnd = -1;

            Emitter(int position, StringBuilder output, TreeSet<Integer> boundaries, LiteralEscaper.Tokens tokens) {
                this.position = position;
                this.output = output;
                this.boundaries = boundaries;
                this.tokens = tokens;
                if (tokens != null) {
                    urlLayout(tokens, boundaries, angleUrls, urlTails);
                }
            }

            /** Whether an open span's delimiter uses {@code unit}. */
            boolean openDelimiterUses(char unit) {
                for (Active active : stack) {
                    if (active.span.kind != TEXT_URL && active.closing.indexOf(unit) >= 0) {
                        return true;
                    }
                }
                return false;
            }

            Active top() {
                return stack.get(stack.size() - 1);
            }

            void open(Span span) {
                String[] delimiters = delimiters(span);
                output.append(delimiters[0]);
                stack.add(new Active(span, delimiters[1]));
                if (span.kind == TEXT_URL) {
                    linkDepth++;
                }
            }

            void close() {
                Active active = stack.remove(stack.size() - 1);
                output.append(active.closing);
                if (active.span.kind == TEXT_URL) {
                    linkDepth--;
                }
            }

            void emitText(int upTo) {
                while (position < upTo) {
                    char unit = units.charAt(position);
                    boolean insideCode = !stack.isEmpty() && top().span.kind == CODE;
                    if (!insideCode) {
                        if (tokens != null) {
                            Integer urlEnd = angleUrls.get(position);
                            if (urlEnd != null) {
                                output.append('<');
                                angleEnd = urlEnd;
                            }
                            boolean escape = escaper.needsEscape(position, boundaries, tokens);
                            if (escape && urlTails.contains(position)) {
                                escape = openDelimiterUses(unit);
                            }
                            if (escape) {
                                output.append('\\');
                            }
                        } else if (linkDepth > 0 && (unit == '[' || unit == ']')) {
                            output.append('\\');
                        }
                    }
                    output.append(unit);
                    position++;
                    if (position == angleEnd) {
                        output.append('>');
                        angleEnd = -1;
                    }
                }
            }
        }

        /** Delimiters chosen for spans of the current segment instead of the usual ones. */
        private final java.util.IdentityHashMap<Span, String[]> delimiterOverrides = new java.util.IdentityHashMap<Span, String[]>();

        /**
         * Which URLs go in {@code <…>} (when what follows would otherwise become part of the URL), and
         * the tails of the others: characters right after a URL that get no backslash unless an open
         * delimiter uses them.
         */
        private void urlLayout(LiteralEscaper.Tokens tokens, TreeSet<Integer> boundaries, java.util.Map<Integer, Integer> angleUrls, java.util.Set<Integer> tails) {
            for (int[] token : tokens.urlTokens) {
                boolean escapedTail = false;
                for (int k = token[1]; k < token[2] && !escapedTail; k++) {
                    escapedTail = units.charAt(k) != '<' && escaper.needsEscape(k, boundaries, tokens);
                }
                if (token[2] < count && !escapedTail) {
                    int after = units.codePointAt(token[2]);
                    escapedTail = !CharClass.isWhitespace(after) && escaper.needsEscape(token[2], boundaries, tokens);
                }
                boolean wrap = token[3] == 1 && (token[4] == 1 || escapedTail);
                if (wrap) {
                    if (angleUrls != null) {
                        angleUrls.put(token[0], token[1]);
                    }
                } else {
                    for (int k = token[1]; k < token[2]; k++) {
                        tails.add(k);
                    }
                }
            }
        }

        /** The other emphasis delimiters for a span with a URL tail of its own character inside, or null. */
        private String[] alternativeDelimiters(Span span, java.util.Set<Integer> tails) {
            String[] usual = delimiters(span);
            boolean wordNeighbour = isAlphanumericUnit(span.start - 1, units) || isAlphanumericUnit(span.end, units);
            if (span.kind == ITALIC && usual[0].equals("_") && tailInside(span, '_', tails)) {
                return new String[] {"*", "*"};
            }
            if (span.kind == ITALIC && usual[0].equals("*") && tailInside(span, '*', tails) && !wordNeighbour) {
                return new String[] {"_", "_"};
            }
            if (span.kind == BOLD && tailInside(span, '*', tails) && !wordNeighbour) {
                return new String[] {"__", "__"};
            }
            return null;
        }

        private boolean tailInside(Span span, char unit, java.util.Set<Integer> tails) {
            for (int k = span.start; k < span.end; k++) {
                if (units.charAt(k) == unit && tails.contains(k)) {
                    return true;
                }
            }
            return false;
        }

        private String[] delimiters(Span span) {
            String[] override = delimiterOverrides.get(span);
            if (override != null) {
                return override;
            }
            switch (span.kind) {
                case BOLD:
                    return new String[] {"**", "**"};
                case ITALIC: {
                    // `_` reads better and can't merge with a neighbouring `**`, but doesn't work inside words.
                    String marker = (isAlphanumericUnit(span.start - 1, units) || isAlphanumericUnit(span.end, units)) ? "*" : "_";
                    return new String[] {marker, marker};
                }
                case STRIKE:
                    return new String[] {"~~", "~~"};
                case CODE: {
                    String content = units.substring(span.start, span.end);
                    String fence = repeat('`', longestRun('`', content) + 1);
                    boolean hasNonSpace = false;
                    for (int k = 0; k < content.length(); k++) {
                        if (content.charAt(k) != ' ') {
                            hasNonSpace = true;
                            break;
                        }
                    }
                    boolean needsPadding = content.charAt(0) == '`' || content.charAt(content.length() - 1) == '`'
                        || (content.charAt(0) == ' ' && content.charAt(content.length() - 1) == ' ' && hasNonSpace);
                    return new String[] {fence + (needsPadding ? " " : ""), (needsPadding ? " " : "") + fence};
                }
                default:
                    return new String[] {"[", "](" + formatDestination(span.url) + ")"};
            }
        }
    }

    private static final Comparator<Span> SPAN_BY_START = new Comparator<Span>() {
        @Override
        public int compare(Span lhs, Span rhs) {
            return lhs.start < rhs.start ? -1 : (lhs.start == rhs.start ? 0 : 1);
        }
    };

    /** A link destination, in {@code <…>} when it has unbalanced parentheses, spaces or angle brackets. */
    static String formatDestination(String url) {
        int depth = 0;
        boolean balanced = true;
        boolean special = false;
        for (int k = 0; k < url.length(); k++) {
            char c = url.charAt(k);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth < 0) {
                    balanced = false;
                }
            } else if (c == ' ' || c == '<' || c == '>' || c == '\n' || c == '\r' || c == '\u000B' || c == '\u000C'
                || c == '\u0085' || c == '\u2028' || c == '\u2029') {
                special = true;
            }
        }
        if (depth != 0) {
            balanced = false;
        }
        if (balanced && !special) {
            return url;
        }
        return "<" + url.replace("<", "%3C").replace(">", "%3E").replace("\n", "%0A") + ">";
    }

    static String repeat(char c, int count) {
        StringBuilder builder = new StringBuilder(count);
        for (int k = 0; k < count; k++) {
            builder.append(c);
        }
        return builder.toString();
    }
}
