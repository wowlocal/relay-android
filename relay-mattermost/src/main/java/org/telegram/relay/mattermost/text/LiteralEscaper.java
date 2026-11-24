package org.telegram.relay.mattermost.text;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

import static org.telegram.relay.mattermost.text.CharClass.NONE;
import static org.telegram.relay.mattermost.text.CharClass.isASCIIAlphanumeric;
import static org.telegram.relay.mattermost.text.CharClass.isASCIILetter;
import static org.telegram.relay.mattermost.text.CharClass.isAlphabetic;
import static org.telegram.relay.mattermost.text.CharClass.isNonspacingMark;
import static org.telegram.relay.mattermost.text.CharClass.isNumeric;
import static org.telegram.relay.mattermost.text.CharClass.isPunctuation;
import static org.telegram.relay.mattermost.text.CharClass.isWhitespace;

/**
 * Decides which characters of plain text {@link EntitiesToMarkdown} backslash-escapes in literal
 * mode: those {@link MarkdownToEntities} would read as markdown, by the parser's own rules. URLs,
 * emails, mentions, channel links, hashtags and custom emoji are found as the parser finds them
 * (with the delimiters the serializer puts around them) and kept live and unescaped.
 */
final class LiteralEscaper {
    private final String units;
    private final int count;
    private final EntitiesToMarkdown.Options options;
    private final int[] cps;
    /** UTF-16 index → code point index (-1 on a low surrogate). */
    private final int[] cpIndex;
    /** Code point index → UTF-16 index (one past the end included). */
    private final int[] starts;
    /** Line-level escapes: heading, quote, list and rule markers at line starts; table pipes. */
    private final boolean[] lineEscape;

    /** Tokens of one inline segment. */
    static final class Tokens {
        final boolean[] protectedChar;
        final boolean[] forceEscape;
        /**
         * Bare URLs: {start, end, tailEnd, wrappable, delimiterAfter} in UTF-16 units. The tail is the run of characters
         * right after the URL that the URL scanner strips ({@code .,:*_~…}, an unbalanced {@code )}) or
         * stops at ({@code <}): a backslash before one would become part of the URL. A wrappable URL
         * (with a scheme, no {@code >}) can go in {@code <…>} instead.
         */
        final List<int[]> urlTokens = new ArrayList<int[]>();

        Tokens(int count) {
            protectedChar = new boolean[count];
            forceEscape = new boolean[count];
        }
    }

    LiteralEscaper(String text, EntitiesToMarkdown.Options options, List<Integer> blockEdges) {
        this.units = text;
        this.count = text.length();
        this.options = options;
        this.cps = CharClass.codePoints(text);
        this.cpIndex = new int[count];
        Arrays.fill(cpIndex, -1);
        this.starts = new int[cps.length + 1];
        int offset = 0;
        for (int k = 0; k < cps.length; k++) {
            starts[k] = offset;
            cpIndex[offset] = k;
            offset += Character.charCount(cps[k]);
        }
        starts[cps.length] = offset;
        lineEscape = new boolean[count];
        scanLines(lineStarts(blockEdges));
        scanTables();
    }

    // MARK: Tokens

    /**
     * Finds the tokens of the inline segment {@code [start, end)}. {@code delimiters}: what the
     * serializer emits at each boundary, which the parser sees between the text characters.
     */
    Tokens scanSegment(int start, int end, Map<Integer, String> delimiters) {
        Tokens tokens = new Tokens(count);
        // The segment as the parser sees it: text code points with the delimiters in between.
        int firstCp = start < count ? cpIndex[start] : cps.length;
        int lastCp = end < count ? cpIndex[end] : cps.length;
        int capacity = lastCp - firstCp + 1;
        for (String delimiter : delimiters.values()) {
            capacity += delimiter.length();
        }
        int[] virtual = new int[capacity];
        int[] origin = new int[capacity];
        int n = 0;
        for (int k = firstCp; k <= lastCp; k++) {
            String delimiter = delimiters.get(starts[k]);
            if (delimiter != null) {
                for (int d = 0; d < delimiter.length(); d++) {
                    virtual[n] = delimiter.charAt(d);
                    origin[n] = -1;
                    n++;
                }
            }
            if (k < lastCp) {
                virtual[n] = cps[k];
                origin[n] = k;
                n++;
            }
        }
        virtual = Arrays.copyOf(virtual, n);
        origin = Arrays.copyOf(origin, n);
        scanTokens(virtual, origin, tokens);
        return tokens;
    }

    private static int at(int[] v, int index) {
        return index >= 0 && index < v.length ? v[index] : NONE;
    }

    /** Protects the text characters of {@code [from, to)} of the virtual segment; false when a delimiter falls inside. */
    private boolean protect(int[] origin, int from, int to, Tokens tokens) {
        for (int k = from; k < to; k++) {
            if (origin[k] < 0) {
                return false;
            }
        }
        for (int k = from; k < to; k++) {
            int unit = starts[origin[k]];
            int width = Character.charCount(cps[origin[k]]);
            for (int w = 0; w < width; w++) {
                tokens.protectedChar[unit + w] = true;
            }
        }
        return true;
    }

    private void force(int[] origin, int index, Tokens tokens) {
        if (index >= 0 && index < origin.length && origin[index] >= 0) {
            tokens.forceEscape[starts[origin[index]]] = true;
        }
    }

    private int unitOf(int[] origin, int index) {
        // The UTF-16 index of the text character at or after a virtual index.
        for (int k = index; k < origin.length; k++) {
            if (origin[k] >= 0) {
                return starts[origin[k]];
            }
        }
        return origin.length == 0 ? 0 : starts[lastOrigin(origin) + 1];
    }

    private static int lastOrigin(int[] origin) {
        for (int k = origin.length - 1; k >= 0; k--) {
            if (origin[k] >= 0) {
                return origin[k];
            }
        }
        return -1;
    }

    private void scanTokens(int[] v, int[] origin, Tokens tokens) {
        int n = v.length;
        int i = 0;
        int brackets = 0;
        while (i < n) {
            int c = v[i];
            if (origin[i] < 0) {
                // A link's brackets: a URL in its text ends at the "]".
                if (c == '[') {
                    brackets++;
                } else if (c == ']' && brackets > 0) {
                    brackets--;
                }
                i++;
                continue;
            }
            int previous = at(v, i - 1);
            int next = at(v, i + 1);
            if (c == '@' && !(isASCIIAlphanumeric(previous) || previous == '@') && isASCIILetter(next)) {
                int end = i + 2;
                while (end < n && (isASCIIAlphanumeric(v[end]) || v[end] == '.' || v[end] == '_' || v[end] == '-')) {
                    end++;
                }
                while (end > i + 2 && (v[end - 1] == '.' || v[end - 1] == '_' || v[end - 1] == '-')) {
                    end--;
                }
                if (protect(origin, i, end, tokens)) {
                    i = end;
                    continue;
                }
            }
            if (c == '~' && previous != '~' && next != '~' && !isASCIIAlphanumeric(previous) && isASCIIAlphanumeric(next)) {
                int end = i + 1;
                while (end < n && (isASCIIAlphanumeric(v[end]) || v[end] == '-' || v[end] == '_' || v[end] == '.')) {
                    end++;
                }
                while (end > i + 1 && (v[end - 1] == '-' || v[end - 1] == '_' || v[end - 1] == '.')) {
                    end--;
                }
                if (protect(origin, i, end, tokens)) {
                    i = end;
                    continue;
                }
            }
            if (c == '#' && !(isASCIIAlphanumeric(previous) || previous == '&' || previous == '#' || isAlphabetic(previous)) && isAlphabetic(next)) {
                int end = i + 2;
                while (end < n && (isAlphabetic(v[end]) || isNumeric(v[end]) || isNonspacingMark(v[end]) || v[end] == '_' || v[end] == '-' || v[end] == '.')) {
                    end++;
                }
                while (end > i + 2 && (v[end - 1] == '.' || v[end - 1] == '_' || v[end - 1] == '-')) {
                    end--;
                }
                if (end - (i + 1) >= 3 && protect(origin, i, end, tokens)) {
                    i = end;
                    continue;
                }
            }
            if (c == ':') {
                int end = InlineParser.matchEmojiShortcode(v, i);
                if (end > 0) {
                    String name = CharClass.string(v, i + 1, end - 1);
                    if (EmojiTable.unicode(name.toLowerCase(Locale.ROOT)) != null) {
                        // A built-in shortcode would become the emoji: keep it literal.
                        force(origin, i, tokens);
                        i++;
                        continue;
                    }
                    if (InlineParser.isEmojiName(name) && protect(origin, i, end, tokens)) {
                        i = end;
                        continue;
                    }
                }
            }
            if (c == ':' || c == ';') {
                int length = InlineParser.matchEmoticon(v, i, null, true);
                if (length > 0) {
                    force(origin, i, tokens);
                    i += length;
                    continue;
                }
            }
            if (isASCIIAlphanumeric(c) && !isASCIIAlphanumeric(previous)) {
                if (c == 'x' || c == 'X') {
                    int length = InlineParser.matchEmoticon(v, i, null, true);
                    if (length > 0) {
                        // "x-d": escape the hyphen.
                        force(origin, i + 1, tokens);
                        i += length;
                        continue;
                    }
                }
                if (InlineParser.allowsBareURLAfter(previous)) {
                    int end = InlineParser.matchBareURL(v, i, brackets > 0);
                    if (end > 0 && protect(origin, i, end, tokens)) {
                        boolean scheme = InlineParser.urlPrefixLength(v, i) != 4;
                        boolean hasAngle = false;
                        for (int k = i; k < end; k++) {
                            if (v[k] == '>') {
                                hasAngle = true;
                                break;
                            }
                        }
                        int tailEnd = end;
                        while (tailEnd < n && (origin[tailEnd] < 0 || "?!.,:*_~'\";)".indexOf(v[tailEnd]) >= 0)) {
                            tailEnd++;
                        }
                        if (tailEnd < n && v[tailEnd] == '<') {
                            tailEnd++;
                        }
                        // A delimiter right after the URL that the URL scanner would not strip (a code span's or
                        // link's opening) has to be kept out of it.
                        boolean delimiterAfter = end < n && origin[end] < 0 && "*_~".indexOf(v[end]) < 0;
                        tokens.urlTokens.add(new int[] {unitOf(origin, i), unitOf(origin, end), unitOf(origin, tailEnd), scheme && !hasAngle ? 1 : 0, delimiterAfter ? 1 : 0});
                        i = end;
                        continue;
                    }
                }
                if (InlineParser.allowsEmailAfter(previous)) {
                    int length = InlineParser.matchEmail(v, i);
                    if (length > 0 && protect(origin, i, i + length, tokens)) {
                        i += length;
                        continue;
                    }
                }
            }
            i++;
        }
    }

    // MARK: Line starts

    /** Line starts in markdown: after newlines, and where a code block or quote begins or ends ({@code blockEdges}). */
    private TreeSet<Integer> lineStarts(List<Integer> blockEdges) {
        TreeSet<Integer> result = new TreeSet<Integer>(blockEdges);
        result.add(0);
        for (int k = 0; k < count; k++) {
            if (units.charAt(k) == '\n') {
                result.add(k + 1);
            }
        }
        return result;
    }

    private boolean isSpaceOrTab(int index, int lineEnd) {
        return index < lineEnd && (units.charAt(index) == ' ' || units.charAt(index) == '\t');
    }

    private void scanLines(TreeSet<Integer> lineStarts) {
        for (int start : lineStarts) {
            if (start >= count) {
                continue;
            }
            int lineEnd = units.indexOf('\n', start);
            if (lineEnd < 0) {
                lineEnd = count;
            }
            int q = start;
            while (q < lineEnd && q - start < 4 && units.charAt(q) == ' ') {
                q++;
            }
            if (q >= lineEnd || q - start > 3) {
                continue;
            }
            char c = units.charAt(q);
            boolean endsOrSpace = q + 1 == lineEnd || isSpaceOrTab(q + 1, lineEnd);
            if (c == '#') {
                int run = 0;
                while (q + run < lineEnd && units.charAt(q + run) == '#') {
                    run++;
                }
                if (run <= 6 && (q + run == lineEnd || isSpaceOrTab(q + run, lineEnd))) {
                    lineEscape[q] = true;
                }
            } else if (c == '>') {
                lineEscape[q] = true;
            } else if ((c == '-' || c == '+' || c == '*') && endsOrSpace) {
                lineEscape[q] = true;
            } else if (c >= '0' && c <= '9') {
                int digits = 0;
                while (q + digits < lineEnd && digits < 10 && CharClass.isASCIIDigit(units.charAt(q + digits))) {
                    digits++;
                }
                int marker = q + digits;
                if (digits <= 9 && marker < lineEnd && (units.charAt(marker) == '.' || units.charAt(marker) == ')')
                    && (marker + 1 == lineEnd || isSpaceOrTab(marker + 1, lineEnd))) {
                    lineEscape[marker] = true;
                }
            }
            String line = units.substring(start, lineEnd);
            if (MarkdownBlocks.isSetextUnderline(line) || MarkdownBlocks.isThematicBreak(line)) {
                lineEscape[q] = true;
            }
        }
    }

    /**
     * A line that could be a table's delimiter row (only {@code |}, {@code -}, {@code :} and spaces, with
     * a {@code -}): its first character and its pipes are escaped, so it can't make the line above a
     * table header whatever that line becomes in markdown (pipes in code spans or between delimiters).
     */
    private void scanTables() {
        int start = 0;
        for (int k = 0; k <= count; k++) {
            if (k < count && units.charAt(k) != '\n') {
                continue;
            }
            boolean dash = false;
            boolean delimiterRow = true;
            int first = -1;
            for (int index = start; index < k; index++) {
                char c = units.charAt(index);
                if (c == '-') {
                    dash = true;
                } else if (c != '|' && c != ':' && c != ' ' && c != '\t') {
                    delimiterRow = false;
                    break;
                }
                if (first < 0 && c != ' ' && c != '\t') {
                    first = index;
                }
            }
            if (delimiterRow && dash && start > 0) {
                lineEscape[first] = true;
                for (int index = start; index < k; index++) {
                    if (units.charAt(index) == '|') {
                        lineEscape[index] = true;
                    }
                }
            }
            start = k + 1;
        }
    }

    // MARK: Decision

    private int cp(int index) {
        return index >= 0 && index < cps.length ? cps[index] : NONE;
    }

    private static boolean adjacent(int index, int width, TreeSet<Integer> boundaries) {
        return boundaries.contains(index) || boundaries.contains(index + width);
    }

    /** Whether the character at UTF-16 {@code index} (plain text, outside code) gets a backslash. */
    boolean needsEscape(int index, TreeSet<Integer> boundaries, Tokens tokens) {
        int k = cpIndex[index];
        if (k < 0) {
            return false;
        }
        int c = cps[k];
        int width = Character.charCount(c);
        if (tokens.protectedChar[index]) {
            return false;
        }
        if (tokens.forceEscape[index] || lineEscape[index]) {
            return true;
        }
        switch (c) {
            case '\\':
            case '*':
            case '`':
            case '[':
            case ']':
                return true;
            case '_': {
                int previous = cp(k - 1);
                int next = cp(k + 1);
                boolean inert = !isWhitespace(previous) && !isPunctuation(previous) && !isWhitespace(next) && !isPunctuation(next);
                return !inert || adjacent(index, width, boundaries);
            }
            case '~':
                return cp(k - 1) == '~' || cp(k + 1) == '~' || adjacent(index, width, boundaries);
            case '<': {
                int next = cp(k + 1);
                return next != NONE && !isWhitespace(next);
            }
            case ':':
            case ';':
                return adjacent(index, width, boundaries);
            case '$':
                return options.inlineLatex;
            default:
                return false;
        }
    }
}
