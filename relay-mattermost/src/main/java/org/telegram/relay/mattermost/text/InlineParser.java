package org.telegram.relay.mattermost.text;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.telegram.relay.mattermost.text.CharClass.NONE;
import static org.telegram.relay.mattermost.text.CharClass.isASCIIAlphanumeric;
import static org.telegram.relay.mattermost.text.CharClass.isASCIILetter;
import static org.telegram.relay.mattermost.text.CharClass.isASCIIPunctuation;
import static org.telegram.relay.mattermost.text.CharClass.isAlphabetic;
import static org.telegram.relay.mattermost.text.CharClass.isControl;
import static org.telegram.relay.mattermost.text.CharClass.isNonspacingMark;
import static org.telegram.relay.mattermost.text.CharClass.isNumeric;
import static org.telegram.relay.mattermost.text.CharClass.isPunctuation;
import static org.telegram.relay.mattermost.text.CharClass.isWhitespace;

/**
 * Parses the inline content of one block (paragraph, heading, list item text, table cell). Port of
 * the iOS {@code InlineParser}.
 *
 * <p>Follows CommonMark's inline algorithm: code spans, autolinks and Mattermost tokens (bare URLs,
 * emails, {@code @mentions}, {@code ~channels}, {@code #hashtags}, {@code :emoji:}, emoticons,
 * {@code $inline latex$}) are atomic; {@code *}, {@code _} and {@code ~~} runs are paired with the
 * delimiter-stack algorithm (including the {@code _} intraword and "multiple of 3" rules);
 * {@code [text](url)} links use the bracket stack. Works on code points; entity ranges come out in
 * UTF-16 units.
 */
final class InlineParser {
    private static final class Open {
        final int id;
        final TextEntity entity;

        Open(int id, TextEntity entity) {
            this.id = id;
            this.entity = entity;
        }
    }

    private static final class Node {
        String text = "";
        /** Entity covering {@code text} (atoms); a template, its range is set at output. */
        TextEntity entity;
        /** URL / email / mention / channel / hashtag entities, dropped inside link text. */
        boolean isAutoEntity;
        /** For delimiter runs: the run character and the number of characters left unmatched. */
        int delimiterChar = NONE;
        int delimiterCount;
        /** Entities starting right after this node's text. */
        List<Open> opens;
        /** Entities ending right before this node's text. */
        List<Integer> closes;

        void open(int id, TextEntity entity) {
            if (opens == null) {
                opens = new ArrayList<Open>(2);
            }
            opens.add(new Open(id, entity));
        }

        void close(int id) {
            if (closes == null) {
                closes = new ArrayList<Integer>(2);
            }
            closes.add(id);
        }
    }

    private static final class Delimiter {
        final int nodeIndex;
        final int ch;
        final int originalCount;
        final boolean canOpen;
        final boolean canClose;
        boolean active = true;

        Delimiter(int nodeIndex, int ch, int originalCount, boolean canOpen, boolean canClose) {
            this.nodeIndex = nodeIndex;
            this.ch = ch;
            this.originalCount = originalCount;
            this.canOpen = canOpen;
            this.canClose = canClose;
        }
    }

    private static final class Bracket {
        final int nodeIndex;
        final int delimiterBottom;
        final boolean isImage;
        boolean active = true;

        Bracket(int nodeIndex, int delimiterBottom, boolean isImage) {
            this.nodeIndex = nodeIndex;
            this.delimiterBottom = delimiterBottom;
            this.isImage = isImage;
        }
    }

    private final int[] scalars;
    private final MarkdownToEntities.Options options;
    private int position;
    private final List<Node> nodes = new ArrayList<Node>();
    private final List<Delimiter> delimiters = new ArrayList<Delimiter>();
    private final List<Bracket> brackets = new ArrayList<Bracket>();
    private final StringBuilder pendingText = new StringBuilder();
    private int nextMarkerId;
    /** Start of the last backtick run of each length, so an unmatched opener fails in O(1). */
    private final Map<Integer, Integer> lastBacktickRunStart = new HashMap<Integer, Integer>();

    static FormattedText parse(String text, MarkdownToEntities.Options options) {
        return new InlineParser(CharClass.codePoints(text), options).run();
    }

    private InlineParser(int[] scalars, MarkdownToEntities.Options options) {
        this.scalars = scalars;
        this.options = options;
        int index = 0;
        while (index < scalars.length) {
            if (scalars[index] == '`') {
                int start = index;
                while (index < scalars.length && scalars[index] == '`') {
                    index++;
                }
                lastBacktickRunStart.put(index - start, start);
            } else {
                index++;
            }
        }
    }

    // MARK: Main loop

    private FormattedText run() {
        while (position < scalars.length) {
            int c = scalars[position];
            switch (c) {
                case '\\':
                    handleBackslash();
                    break;
                case '`':
                    handleBackticks();
                    break;
                case '*':
                case '_':
                    handleDelimiterRun(c);
                    break;
                case '~':
                    handleTilde();
                    break;
                case '[':
                    flushText();
                    nodes.add(textNode("["));
                    brackets.add(new Bracket(nodes.size() - 1, delimiters.size(), false));
                    position++;
                    break;
                case '!':
                    if (peek(1) == '[') {
                        flushText();
                        nodes.add(textNode("!["));
                        brackets.add(new Bracket(nodes.size() - 1, delimiters.size(), true));
                        position += 2;
                    } else {
                        appendText(c);
                    }
                    break;
                case ']':
                    handleCloseBracket();
                    break;
                case '<':
                    if (!handleEmoticon() && !handleAngleAutolink()) {
                        appendText(c);
                    }
                    break;
                case '@':
                    if (!handleMention()) {
                        appendText(c);
                    }
                    break;
                case '#':
                    if (!handleHashtag()) {
                        appendText(c);
                    }
                    break;
                case ':':
                    if (!handleEmoji() && !handleEmoticon()) {
                        appendText(c);
                    }
                    break;
                case ';':
                    if (!handleEmoticon()) {
                        appendText(c);
                    }
                    break;
                case '$':
                    if (!handleInlineLatex()) {
                        appendText(c);
                    }
                    break;
                default:
                    if (isASCIIAlphanumeric(c) && !isASCIIAlphanumeric(peek(-1))) {
                        if ((c == 'x' || c == 'X') && handleEmoticon()) {
                            continue;
                        }
                        if (tryBareURL() || tryEmail()) {
                            continue;
                        }
                    }
                    appendText(c);
            }
        }
        flushText();
        processEmphasis(0);
        return emit();
    }

    // MARK: Character helpers

    private int peek(int offset) {
        return scalar(position + offset);
    }

    private int scalar(int index) {
        if (index < 0 || index >= scalars.length) {
            return NONE;
        }
        return scalars[index];
    }

    private static Node textNode(String text) {
        Node node = new Node();
        node.text = text;
        return node;
    }

    private void appendText(int c) {
        pendingText.appendCodePoint(c);
        position++;
    }

    private void flushText() {
        if (pendingText.length() > 0) {
            nodes.add(textNode(pendingText.toString()));
            pendingText.setLength(0);
        }
    }

    private void appendAtom(String text, TextEntity entity, boolean isAutoEntity) {
        flushText();
        Node node = textNode(text);
        node.entity = entity;
        node.isAutoEntity = isAutoEntity;
        nodes.add(node);
    }

    private String string(int start, int end) {
        return CharClass.string(scalars, start, end);
    }

    // MARK: Escapes and code spans

    private void handleBackslash() {
        int next = peek(1);
        if (next != NONE) {
            if (next == '\n') {
                // Hard line break: Mattermost keeps every newline anyway.
                appendText('\n');
                position++;
                return;
            }
            if (isASCIIPunctuation(next)) {
                pendingText.appendCodePoint(next);
                position += 2;
                return;
            }
        }
        appendText('\\');
    }

    private void handleBackticks() {
        int start = position;
        int count = 0;
        while (scalar(start + count) == '`') {
            count++;
        }
        // Find a closing run of exactly `count` backticks.
        int index = start + count;
        Integer last = lastBacktickRunStart.get(count);
        int lastCandidate = last != null ? last : -1;
        while (lastCandidate > start && index < scalars.length) {
            if (scalars[index] == '`') {
                int run = 0;
                while (scalar(index + run) == '`') {
                    run++;
                }
                if (run == count) {
                    int from = start + count;
                    int to = index;
                    boolean hasNonSpace = false;
                    for (int k = from; k < to; k++) {
                        int s = scalars[k];
                        if (s != ' ' && s != '\n') {
                            hasNonSpace = true;
                            break;
                        }
                    }
                    // Newlines become spaces; one space is stripped from each side when both have one.
                    if (to - from >= 2 && isSpaceOrNewline(scalars[from]) && isSpaceOrNewline(scalars[to - 1]) && hasNonSpace) {
                        from++;
                        to--;
                    }
                    StringBuilder content = new StringBuilder();
                    for (int k = from; k < to; k++) {
                        content.appendCodePoint(scalars[k] == '\n' ? ' ' : scalars[k]);
                    }
                    appendAtom(content.toString(), TextEntity.of(TextEntity.Type.CODE, 0, 0), false);
                    position = index + run;
                    return;
                }
                index += run;
            } else {
                index++;
            }
        }
        for (int k = 0; k < count; k++) {
            pendingText.append('`');
        }
        position += count;
    }

    private static boolean isSpaceOrNewline(int c) {
        return c == ' ' || c == '\n';
    }

    // MARK: Emphasis

    private void handleDelimiterRun(int c) {
        int start = position;
        int count = 0;
        while (scalar(start + count) == c) {
            count++;
        }
        int before = scalar(start - 1);
        int after = scalar(start + count);
        boolean left = leftFlanking(before, after);
        boolean right = rightFlanking(before, after);
        boolean canOpen;
        boolean canClose;
        if (c == '_') {
            canOpen = left && (!right || isPunctuation(before));
            canClose = right && (!left || isPunctuation(after));
        } else {
            canOpen = left;
            canClose = right;
        }
        flushText();
        Node node = new Node();
        node.delimiterChar = c;
        node.delimiterCount = count;
        nodes.add(node);
        delimiters.add(new Delimiter(nodes.size() - 1, c, count, canOpen, canClose));
        position += count;
    }

    private void handleTilde() {
        int start = position;
        int count = 0;
        while (scalar(start + count) == '~') {
            count++;
        }
        if (count == 2) {
            int before = scalar(start - 1);
            int after = scalar(start + 2);
            flushText();
            Node node = new Node();
            node.delimiterChar = '~';
            node.delimiterCount = 2;
            nodes.add(node);
            delimiters.add(new Delimiter(nodes.size() - 1, '~', 2, leftFlanking(before, after), rightFlanking(before, after)));
            position += 2;
            return;
        }
        if (count == 1 && !isASCIIAlphanumeric(peek(-1)) && isASCIIAlphanumeric(peek(1))) {
            int fullEnd = position + 1;
            int s;
            while ((s = scalar(fullEnd)) != NONE && (isASCIIAlphanumeric(s) || s == '-' || s == '_' || s == '.')) {
                fullEnd++;
            }
            int end = fullEnd;
            while (end > position + 1 && ((s = scalar(end - 1)) == '-' || s == '_' || s == '.')) {
                end--;
            }
            Map<String, String> channels = options.channels;
            if (channels != null && end != fullEnd && channels.containsKey(string(position + 1, fullEnd).toLowerCase(Locale.ROOT))) {
                end = fullEnd;
            }
            String name = string(position + 1, end);
            String lowercased = name.toLowerCase(Locale.ROOT);
            TextEntity entity = null;
            if (channels == null) {
                entity = TextEntity.channelMention(0, 0, lowercased);
            } else if (channels.containsKey(lowercased)) {
                entity = TextEntity.channelMention(0, 0, lowercased).withTargetId(channels.get(lowercased));
            }
            // An unknown channel stays text (as in Mattermost's clients), still atomic.
            appendAtom("~" + name, entity, true);
            position = end;
            return;
        }
        for (int k = 0; k < count; k++) {
            pendingText.append('~');
        }
        position += count;
    }

    private static boolean leftFlanking(int before, int after) {
        return !isWhitespace(after) && (!isPunctuation(after) || isWhitespace(before) || isPunctuation(before));
    }

    private static boolean rightFlanking(int before, int after) {
        return !isWhitespace(before) && (!isPunctuation(before) || isWhitespace(after) || isPunctuation(after));
    }

    private void processEmphasis(int stackBottom) {
        Map<Integer, Integer> openersBottom = new HashMap<Integer, Integer>();
        int current = stackBottom;
        while (current < delimiters.size()) {
            Delimiter closer = delimiters.get(current);
            if (!closer.active || !closer.canClose || nodes.get(closer.nodeIndex).delimiterCount == 0) {
                current++;
                continue;
            }
            int key = closer.ch * 8 + (closer.originalCount % 3) * 2 + (closer.canOpen ? 1 : 0);
            Integer storedBottom = openersBottom.get(key);
            int bottom = Math.max(stackBottom, storedBottom != null ? storedBottom : stackBottom);
            int openerIndex = -1;
            for (int j = current - 1; j >= bottom; j--) {
                Delimiter opener = delimiters.get(j);
                if (opener.active && opener.ch == closer.ch && opener.canOpen && nodes.get(opener.nodeIndex).delimiterCount > 0) {
                    if (closer.ch == '~') {
                        openerIndex = j;
                        break;
                    }
                    boolean oddMatch = (opener.canClose || closer.canOpen)
                        && (opener.originalCount + closer.originalCount) % 3 == 0
                        && !(opener.originalCount % 3 == 0 && closer.originalCount % 3 == 0);
                    if (!oddMatch) {
                        openerIndex = j;
                        break;
                    }
                }
            }

            if (openerIndex < 0) {
                openersBottom.put(key, current);
                if (!closer.canOpen) {
                    closer.active = false;
                }
                current++;
                continue;
            }

            Node openerNode = nodes.get(delimiters.get(openerIndex).nodeIndex);
            Node closerNode = nodes.get(closer.nodeIndex);
            int used;
            TextEntity.Type type;
            if (closer.ch == '~') {
                used = 2;
                type = TextEntity.Type.STRIKETHROUGH;
            } else if (openerNode.delimiterCount >= 2 && closerNode.delimiterCount >= 2) {
                used = 2;
                type = TextEntity.Type.BOLD;
            } else {
                used = 1;
                type = TextEntity.Type.ITALIC;
            }
            int id = nextMarkerId++;
            openerNode.delimiterCount -= used;
            openerNode.open(id, TextEntity.of(type, 0, 0));
            closerNode.delimiterCount -= used;
            closerNode.close(id);
            for (int k = openerIndex + 1; k < current; k++) {
                delimiters.get(k).active = false;
            }
            if (openerNode.delimiterCount == 0) {
                delimiters.get(openerIndex).active = false;
            }
            if (closerNode.delimiterCount == 0) {
                closer.active = false;
                current++;
            }
        }
    }

    // MARK: Links

    private void handleCloseBracket() {
        flushText();
        if (brackets.isEmpty()) {
            appendText(']');
            return;
        }
        Bracket bracket = brackets.get(brackets.size() - 1);
        if (!bracket.active) {
            brackets.remove(brackets.size() - 1);
            appendText(']');
            return;
        }
        Object[] target = parseLinkTarget(position + 1);
        if (target == null) {
            brackets.remove(brackets.size() - 1);
            appendText(']');
            return;
        }
        String destination = (String) target[0];
        int end = (Integer) target[1];

        processEmphasis(bracket.delimiterBottom);
        while (delimiters.size() > bracket.delimiterBottom) {
            delimiters.remove(delimiters.size() - 1);
        }
        boolean hasText = false;
        for (int k = bracket.nodeIndex + 1; k < nodes.size(); k++) {
            Node node = nodes.get(k);
            if (node.isAutoEntity) {
                node.entity = null;
            }
            if (!node.text.isEmpty() || node.delimiterCount > 0) {
                hasText = true;
            }
        }
        String url = sanitizedURL(destination);
        int id = nextMarkerId++;
        Node openNode = nodes.get(bracket.nodeIndex);
        openNode.text = "";
        if (url != null) {
            openNode.open(id, TextEntity.textUrl(0, 0, url));
        }
        if (!hasText) {
            nodes.add(textNode(destination));
        }
        Node closeNode = new Node();
        if (url != null) {
            closeNode.close(id);
        }
        nodes.add(closeNode);
        brackets.remove(brackets.size() - 1);
        if (!bracket.isImage) {
            // Links can't contain links.
            for (Bracket other : brackets) {
                if (!other.isImage) {
                    other.active = false;
                }
            }
        }
        position = end;
    }

    /**
     * Parses {@code (destination "title")} starting at {@code index}. Returns the destination and
     * the index after the closing parenthesis, or null.
     */
    private Object[] parseLinkTarget(int index) {
        if (scalar(index) != '(') {
            return null;
        }
        int i = skipWhitespace(index + 1);
        StringBuilder destination = new StringBuilder();
        int s;
        if (scalar(i) == '<') {
            i++;
            while ((s = scalar(i)) != NONE && s != '>') {
                if (s == '\n' || s == '<') {
                    return null;
                }
                int next = scalar(i + 1);
                if (s == '\\' && next != NONE && isASCIIPunctuation(next)) {
                    destination.appendCodePoint(next);
                    i += 2;
                    continue;
                }
                destination.appendCodePoint(s);
                i++;
            }
            if (scalar(i) != '>') {
                return null;
            }
            i++;
        } else {
            int depth = 0;
            while ((s = scalar(i)) != NONE) {
                if (s == ' ' || s == '\t' || s == '\n' || isControl(s)) {
                    break;
                }
                int next = scalar(i + 1);
                if (s == '\\' && next != NONE && isASCIIPunctuation(next)) {
                    destination.appendCodePoint(next);
                    i += 2;
                    continue;
                }
                if (s == '(') {
                    depth++;
                } else if (s == ')') {
                    if (depth == 0) {
                        break;
                    }
                    depth--;
                }
                destination.appendCodePoint(s);
                i++;
            }
            if (depth != 0) {
                return null;
            }
        }
        int afterDestination = i;
        i = skipWhitespace(i);
        int open = scalar(i);
        if (i > afterDestination && (open == '"' || open == '\'' || open == '(')) {
            int close = open == '(' ? ')' : open;
            i++;
            while ((s = scalar(i)) != NONE && s != close) {
                if (s == '\\') {
                    i++;
                }
                i++;
            }
            if (scalar(i) != close) {
                return null;
            }
            i++;
            i = skipWhitespace(i);
        }
        if (scalar(i) != ')') {
            return null;
        }
        return new Object[] {destination.toString(), i + 1};
    }

    private int skipWhitespace(int i) {
        int s;
        while ((s = scalar(i)) == ' ' || s == '\t' || s == '\n') {
            i++;
        }
        return i;
    }

    /** The URL, trimmed, unless it is empty or uses a dangerous scheme ({@code javascript:}, …). */
    static String sanitizedURL(String url) {
        String trimmed = CharClass.trimInlineWhitespace(url);
        if (trimmed.isEmpty()) {
            return null;
        }
        int colon = trimmed.indexOf(':');
        if (colon >= 0) {
            String scheme = trimmed.substring(0, colon).toLowerCase(Locale.ROOT);
            if (scheme.equals("javascript") || scheme.equals("vbscript") || scheme.equals("data") || scheme.equals("file")) {
                return null;
            }
        }
        return trimmed;
    }

    // MARK: Autolinks

    private boolean handleAngleAutolink() {
        int i = position + 1;
        // URI autolink: scheme ":" then no spaces, "<" or ">".
        int schemeLength = 0;
        int s;
        while ((s = scalar(i + schemeLength)) != NONE && (isASCIIAlphanumeric(s) || s == '+' || s == '.' || s == '-')) {
            schemeLength++;
        }
        if (schemeLength >= 2 && schemeLength <= 32 && isASCIILetter(scalar(i)) && scalar(i + schemeLength) == ':') {
            int end = i + schemeLength + 1;
            while ((s = scalar(end)) != NONE && s != '>' && s != '<' && !isWhitespace(s) && !isControl(s)) {
                end++;
            }
            if (scalar(end) == '>') {
                String url = string(i, end);
                if (sanitizedURL(url) != null) {
                    appendAtom(url, TextEntity.of(TextEntity.Type.URL, 0, 0), true);
                } else {
                    appendAtom(url, null, false);
                }
                position = end + 1;
                return true;
            }
        }
        // Email autolink.
        i = position + 1;
        int length = matchEmail(i);
        if (length > 0 && scalar(i + length) == '>') {
            appendAtom(string(i, i + length), TextEntity.of(TextEntity.Type.EMAIL, 0, 0), true);
            position = i + length + 1;
            return true;
        }
        return false;
    }

    private static final String[] URL_PREFIXES = {"https://", "http://", "ftp://", "www."};

    /** Length of the URL prefix ({@code https://}, …, {@code www.}, ASCII case-insensitive) at {@code start}, or 0. */
    static int urlPrefixLength(int[] scalars, int start) {
        for (String prefix : URL_PREFIXES) {
            boolean matches = true;
            for (int k = 0; k < prefix.length(); k++) {
                int index = start + k;
                if (index >= scalars.length) {
                    matches = false;
                    break;
                }
                int s = scalars[index];
                if (s >= 'A' && s <= 'Z') {
                    s += 32;
                }
                if (s != prefix.charAt(k)) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                return prefix.length();
            }
        }
        return 0;
    }

    /** Characters that may precede a bare URL. */
    static boolean allowsBareURLAfter(int previous) {
        return previous == NONE || isWhitespace(previous) || previous == '*' || previous == '_' || previous == '~'
            || previous == '(' || previous == '[' || previous == '"' || previous == '\'';
    }

    /**
     * End (exclusive) of a bare URL starting at {@code start}, or -1. {@code insideBrackets}: a
     * {@code ]} ends the URL (link text).
     */
    static int matchBareURL(int[] scalars, int start, boolean insideBrackets) {
        int prefixLength = urlPrefixLength(scalars, start);
        if (prefixLength == 0) {
            return -1;
        }
        int end = start + prefixLength;
        while (end < scalars.length) {
            int s = scalars[end];
            if (isWhitespace(s) || s == '<') {
                break;
            }
            if (s == ']' && insideBrackets) {
                break;
            }
            end++;
        }
        // Trailing punctuation is not part of the URL; nor is an unbalanced ")".
        while (end > start + prefixLength) {
            int last = scalars[end - 1];
            if ("?!.,:*_~'\";".indexOf(last) >= 0) {
                end--;
                continue;
            }
            if (last == ')') {
                int open = 0;
                int close = 0;
                for (int k = start; k < end; k++) {
                    if (scalars[k] == '(') {
                        open++;
                    } else if (scalars[k] == ')') {
                        close++;
                    }
                }
                if (close > open) {
                    end--;
                    continue;
                }
            }
            break;
        }
        int hostStart = start + prefixLength;
        if (hostStart >= end) {
            return -1;
        }
        int firstHost = scalars[hostStart];
        if (!(isASCIIAlphanumeric(firstHost) || firstHost >= 0x80)) {
            return -1;
        }
        if (prefixLength == 4) {
            // "www." needs a real domain.
            boolean hasDot = false;
            for (int k = hostStart; k < end; k++) {
                if (scalars[k] == '.') {
                    hasDot = true;
                    break;
                }
            }
            if (!hasDot) {
                return -1;
            }
        }
        return end;
    }

    private boolean tryBareURL() {
        if (!allowsBareURLAfter(peek(-1))) {
            return false;
        }
        int end = matchBareURL(scalars, position, !brackets.isEmpty());
        if (end < 0) {
            return false;
        }
        appendAtom(string(position, end), TextEntity.of(TextEntity.Type.URL, 0, 0), true);
        position = end;
        return true;
    }

    /** Characters that may precede a bare email address. */
    static boolean allowsEmailAfter(int previous) {
        return !(isASCIIAlphanumeric(previous) || previous == '.' || previous == '+' || previous == '-' || previous == '@');
    }

    private boolean tryEmail() {
        if (!allowsEmailAfter(peek(-1))) {
            return false;
        }
        int length = matchEmail(position);
        if (length <= 0) {
            return false;
        }
        appendAtom(string(position, position + length), TextEntity.of(TextEntity.Type.EMAIL, 0, 0), true);
        position += length;
        return true;
    }

    private int matchEmail(int start) {
        return matchEmail(scalars, start);
    }

    /** Length of an email address starting at {@code start}, or 0. */
    static int matchEmail(int[] scalars, int start) {
        int i = start;
        int s;
        while (i < scalars.length && (isASCIIAlphanumeric(s = scalars[i]) || s == '.' || s == '_' || s == '+' || s == '-')) {
            i++;
        }
        if (i <= start || i >= scalars.length || scalars[i] != '@' || scalars[i - 1] == '.') {
            return 0;
        }
        i++;
        int domainStart = i;
        while (i < scalars.length && (isASCIIAlphanumeric(s = scalars[i]) || s == '-' || s == '.')) {
            i++;
        }
        // Trailing "." and "-" are not part of the domain.
        while (i > domainStart && ((s = scalars[i - 1]) == '.' || s == '-')) {
            i--;
        }
        if (i <= domainStart || !isASCIIAlphanumeric(scalars[domainStart])) {
            return 0;
        }
        boolean hasDot = false;
        for (int k = domainStart; k < i; k++) {
            if (scalars[k] == '.') {
                hasDot = true;
                if (k + 1 < i && scalars[k + 1] == '.') {
                    return 0;
                }
            }
        }
        if (!hasDot) {
            return 0;
        }
        if (i < scalars.length && isASCIIAlphanumeric(scalars[i])) {
            return 0;
        }
        return i - start;
    }

    // MARK: Mattermost tokens

    /** Name characters of a mention or channel mention. */
    private static boolean isNameCharacter(int s) {
        return isASCIIAlphanumeric(s) || s == '.' || s == '_' || s == '-';
    }

    private boolean handleMention() {
        int previous = peek(-1);
        if (isASCIIAlphanumeric(previous) || previous == '@') {
            return false;
        }
        if (!isASCIILetter(peek(1))) {
            return false;
        }
        int fullEnd = position + 2;
        while (fullEnd < scalars.length && isNameCharacter(scalars[fullEnd])) {
            fullEnd++;
        }
        int end = fullEnd;
        int s;
        while (end > position + 2 && ((s = scalars[end - 1]) == '.' || s == '_' || s == '-')) {
            end--;
        }
        Map<String, String> users = options.users;
        if (users != null && end != fullEnd && users.containsKey(string(position + 1, fullEnd).toLowerCase(Locale.ROOT))) {
            // A known username that ends in punctuation (Mattermost allows it).
            end = fullEnd;
        }
        String name = string(position + 1, end);
        String lowercased = name.toLowerCase(Locale.ROOT);
        TextEntity entity;
        if (lowercased.equals(TextEntity.SPECIAL_CHANNEL) || lowercased.equals(TextEntity.SPECIAL_HERE) || lowercased.equals(TextEntity.SPECIAL_ALL)) {
            entity = TextEntity.specialMention(0, 0, lowercased).withHighlight(options.currentUsername != null);
        } else {
            entity = TextEntity.mention(0, 0, lowercased);
            if (users != null && users.get(lowercased) != null) {
                entity = entity.withUserId(users.get(lowercased));
            }
            if (options.currentUsername != null && options.currentUsername.equals(lowercased)) {
                entity = entity.withHighlight(true);
            }
        }
        appendAtom("@" + name, entity, true);
        position = end;
        return true;
    }

    private boolean handleHashtag() {
        int previous = peek(-1);
        if (isASCIIAlphanumeric(previous) || previous == '&' || previous == '#' || isAlphabetic(previous)) {
            return false;
        }
        if (!isAlphabetic(peek(1))) {
            return false;
        }
        int end = position + 2;
        int s;
        while ((s = scalar(end)) != NONE && (isAlphabetic(s) || isNumeric(s) || isNonspacingMark(s) || s == '_' || s == '-' || s == '.')) {
            end++;
        }
        while (end > position + 2 && ((s = scalar(end - 1)) == '.' || s == '_' || s == '-')) {
            end--;
        }
        if (end - (position + 1) < 3) {
            return false;
        }
        appendAtom(string(position, end), TextEntity.of(TextEntity.Type.HASHTAG, 0, 0), true);
        position = end;
        return true;
    }

    /**
     * End (exclusive, after the closing colon) of an emoji shortcode {@code :name:} at {@code start}, or -1.
     * Only the syntax; the caller checks the name.
     */
    static int matchEmojiShortcode(int[] scalars, int start) {
        if (start > 0 && isASCIIAlphanumeric(scalars[start - 1])) {
            return -1;
        }
        int end = start + 1;
        int s;
        while (end < scalars.length && (isASCIIAlphanumeric(s = scalars[end]) || s == '_' || s == '+' || s == '-')) {
            end++;
            if (end - start > 65) {
                return -1;
            }
        }
        if (end <= start + 1 || end >= scalars.length || scalars[end] != ':') {
            return -1;
        }
        if (end + 1 < scalars.length && isASCIIAlphanumeric(scalars[end + 1])) {
            return -1;
        }
        return end + 1;
    }

    /** Whether {@code name} (as written) is an emoji name: built-in, or with a letter (custom). */
    static boolean isEmojiName(String name) {
        if (EmojiTable.unicode(name.toLowerCase(Locale.ROOT)) != null) {
            return true;
        }
        for (int k = 0; k < name.length(); k++) {
            if (isASCIILetter(name.charAt(k))) {
                return true;
            }
        }
        return false;
    }

    private boolean handleEmoji() {
        int end = matchEmojiShortcode(scalars, position);
        if (end < 0) {
            return false;
        }
        String name = string(position + 1, end - 1);
        String lowercased = name.toLowerCase(Locale.ROOT);
        String emoji = EmojiTable.unicode(lowercased);
        if (emoji != null) {
            appendAtom(emoji, null, false);
        } else {
            if (!isEmojiName(name)) {
                return false;
            }
            TextEntity entity = TextEntity.customEmoji(0, 0, lowercased);
            Map<String, String> custom = options.customEmoji;
            if (custom != null && custom.get(lowercased) != null) {
                entity = entity.withTargetId(custom.get(lowercased));
            }
            appendAtom(":" + name + ":", entity, false);
        }
        position = end;
        return true;
    }

    // MARK: Emoticons and inline LaTeX

    private static final class Emoticon {
        final int[] text;
        final String name;
        final boolean ignoresCase;

        Emoticon(String text, String name, boolean ignoresCase) {
            this.text = CharClass.codePoints(text);
            this.name = name;
            this.ignoresCase = ignoresCase;
        }
    }

    /**
     * Emoticons Mattermost's clients render as emoji (webapp {@code utils/emoticons}), longest first;
     * {@code ignoresCase}: letters match in either case.
     */
    private static final Emoticon[] EMOTICONS = {
        new Emoticon(":-)", "slightly_smiling_face", false), new Emoticon(":)", "slightly_smiling_face", false),
        new Emoticon(";-)", "wink", false), new Emoticon(";)", "wink", false),
        new Emoticon(":-o", "scream", true), new Emoticon(":o", "open_mouth", true),
        new Emoticon(":-]", "smirk", false), new Emoticon(":]", "smirk", false),
        new Emoticon(":-d", "smile", true), new Emoticon(":d", "smile", true),
        new Emoticon("x-d", "stuck_out_tongue_closed_eyes", true),
        new Emoticon(":-p", "stuck_out_tongue", true), new Emoticon(":p", "stuck_out_tongue", true),
        new Emoticon(":-@", "rage", false), new Emoticon(":@", "rage", false), new Emoticon(":-[", "rage", false), new Emoticon(":[", "rage", false),
        new Emoticon(":-(", "slightly_frowning_face", false), new Emoticon(":(", "slightly_frowning_face", false),
        new Emoticon(":`-(", "cry", false), new Emoticon(":'-(", "cry", false), new Emoticon(":’-(", "cry", false),
        new Emoticon(":`(", "cry", false), new Emoticon(":'(", "cry", false), new Emoticon(":’(", "cry", false),
        new Emoticon(":-/", "confused", false), new Emoticon(":/", "confused", false),
        new Emoticon(":-s", "confounded", true), new Emoticon(":s", "confounded", true),
        new Emoticon(":-|", "neutral_face", false), new Emoticon(":|", "neutral_face", false),
        new Emoticon(":-$", "flushed", false), new Emoticon(":$", "flushed", false),
        new Emoticon(":-x", "mask", true),
        new Emoticon("</3", "broken_heart", false), new Emoticon("<3", "heart", false),
    };

    private static boolean isWordCharacter(int s) {
        return isASCIIAlphanumeric(s) || s == '_';
    }

    /**
     * Length of the emoticon at {@code start} (only between non-word characters, the webapp's
     * {@code (^|\B)…($|\B)} patterns, so {@code http://}, {@code 10:30} or {@code Note:D} stay text), or 0.
     */
    static int matchEmoticon(int[] scalars, int start, String[] emojiOut) {
        return matchEmoticon(scalars, start, emojiOut, false);
    }

    /** {@code underscoreAfterIsBreak}: an {@code _} after the emoticon counts as a non-word character (it may get escaped). */
    static int matchEmoticon(int[] scalars, int start, String[] emojiOut, boolean underscoreAfterIsBreak) {
        if (start > 0 && isWordCharacter(scalars[start - 1])) {
            return 0;
        }
        for (Emoticon emoticon : EMOTICONS) {
            int[] candidate = emoticon.text;
            if (start + candidate.length > scalars.length) {
                continue;
            }
            boolean matches = true;
            for (int offset = 0; offset < candidate.length; offset++) {
                int actual = scalars[start + offset];
                if (emoticon.ignoresCase && actual >= 'A' && actual <= 'Z') {
                    actual += 32;
                }
                if (actual != candidate[offset]) {
                    matches = false;
                    break;
                }
            }
            int after = start + candidate.length < scalars.length ? scalars[start + candidate.length] : NONE;
            if (!matches || (isWordCharacter(after) && !(underscoreAfterIsBreak && after == '_'))) {
                continue;
            }
            String emoji = EmojiTable.unicode(emoticon.name);
            if (emoji == null) {
                continue;
            }
            if (emojiOut != null) {
                emojiOut[0] = emoji;
            }
            return candidate.length;
        }
        return 0;
    }

    private boolean handleEmoticon() {
        if (!options.emoticons) {
            return false;
        }
        String[] emoji = new String[1];
        int length = matchEmoticon(scalars, position, emoji);
        if (length == 0) {
            return false;
        }
        appendAtom(emoji[0], null, false);
        position += length;
        return true;
    }

    /**
     * {@code $x^2$} (with the server's inline LaTeX on) as inline code without the dollars: the app
     * doesn't typeset TeX. Like Mattermost: no space just inside the dollars, one line, and no digit
     * right after the closing one, so "$5 and $10" stays text.
     */
    private boolean handleInlineLatex() {
        if (!options.inlineLatex || isASCIIAlphanumeric(peek(-1)) || peek(-1) == '\\') {
            return false;
        }
        int first = peek(1);
        if (first == NONE || first == '$' || CharClass.isWhiteSpaceProperty(first)) {
            return false;
        }
        int end = position + 1;
        int s;
        while ((s = scalar(end)) != NONE && s != '$') {
            if (s == '\n') {
                return false;
            }
            end++;
        }
        if (end >= scalars.length) {
            return false;
        }
        int last = scalar(end - 1);
        if (last == NONE || CharClass.isWhiteSpaceProperty(last) || last == '\\') {
            return false;
        }
        if (isNumeric(scalar(end + 1))) {
            return false;
        }
        appendAtom(string(position + 1, end), TextEntity.of(TextEntity.Type.CODE, 0, 0), false);
        position = end + 1;
        return true;
    }

    // MARK: Output

    private FormattedText emit() {
        StringBuilder output = new StringBuilder();
        Map<Integer, Open> starts = new HashMap<Integer, Open>();
        Map<Integer, Integer> startOffsets = new HashMap<Integer, Integer>();
        List<TextEntity> entities = new ArrayList<TextEntity>();
        for (Node node : nodes) {
            if (node.closes != null) {
                for (Integer id : node.closes) {
                    Open open = starts.remove(id);
                    Integer start = startOffsets.remove(id);
                    if (open != null && output.length() > start) {
                        entities.add(open.entity.withRange(start, output.length() - start));
                    }
                }
            }
            int start = output.length();
            if (node.delimiterChar != NONE) {
                for (int k = 0; k < node.delimiterCount; k++) {
                    output.appendCodePoint(node.delimiterChar);
                }
            } else {
                output.append(node.text);
            }
            if (node.entity != null && output.length() > start) {
                entities.add(node.entity.withRange(start, output.length() - start));
            }
            if (node.opens != null) {
                for (Open open : node.opens) {
                    starts.put(open.id, open);
                    startOffsets.put(open.id, output.length());
                }
            }
        }
        return new FormattedText(output.toString(), entities);
    }
}
