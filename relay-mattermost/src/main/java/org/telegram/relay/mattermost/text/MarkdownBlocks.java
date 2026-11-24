package org.telegram.relay.mattermost.text;

import java.util.ArrayList;
import java.util.List;

/** Block structure of Mattermost markdown (port of the iOS {@code BlockParser}). */
final class MarkdownBlocks {
    private MarkdownBlocks() {
    }

    enum Kind {
        PARAGRAPH,
        HEADING,
        CODE,
        QUOTE,
        LIST,
        TABLE,
        RULE
    }

    enum Alignment {
        LEFT,
        CENTER,
        RIGHT
    }

    static final class Block {
        final Kind kind;
        /** Paragraph / heading text, code content. */
        String text;
        int level;
        String language;
        /** Quote content. */
        List<Item> children;
        boolean ordered;
        int start;
        List<ListItem> items;
        List<List<String>> rows;
        List<Alignment> alignments;

        Block(Kind kind) {
            this.kind = kind;
        }
    }

    static final class Item {
        final Block block;
        final boolean blankBefore;

        Item(Block block, boolean blankBefore) {
            this.block = block;
            this.blankBefore = blankBefore;
        }
    }

    static final class ListItem {
        /** null: not a task; false: {@code [ ]}; true: {@code [x]}. */
        final Boolean task;
        final List<Item> blocks;

        ListItem(Boolean task, List<Item> blocks) {
            this.task = task;
            this.blocks = blocks;
        }
    }

    private static final class Fence {
        int ch;
        int count;
        int indent;
        String language;
    }

    private static final class ListMarker {
        boolean ordered;
        int marker;
        int number;
        int contentIndent;
        String content;

        ListMarker(boolean ordered, int marker, int number, int contentIndent, String content) {
            this.ordered = ordered;
            this.marker = marker;
            this.number = number;
            this.contentIndent = contentIndent;
            this.content = content;
        }
    }

    /** Quotes and lists nested deeper than this are kept as plain paragraphs (bounded recursion). */
    static final int MAXIMUM_NESTING = 32;

    // MARK: Lines

    /** Splits on {@code \n}, {@code \r} and {@code \r\n}; expands leading tabs. */
    static List<String> splitLines(String text) {
        List<String> lines = new ArrayList<String>();
        int start = 0;
        int length = text.length();
        for (int i = 0; i < length; i++) {
            char c = text.charAt(i);
            if (c == '\n' || c == '\r') {
                lines.add(expandLeadingTabs(text.substring(start, i)));
                if (c == '\r' && i + 1 < length && text.charAt(i + 1) == '\n') {
                    i++;
                }
                start = i + 1;
            }
        }
        lines.add(expandLeadingTabs(text.substring(start)));
        return lines;
    }

    static String expandLeadingTabs(String line) {
        if (line.isEmpty() || !(line.charAt(0) == '\t' || (line.charAt(0) == ' ' && line.indexOf('\t') >= 0))) {
            return line;
        }
        StringBuilder result = new StringBuilder();
        int column = 0;
        int index = 0;
        while (index < line.length()) {
            char c = line.charAt(index);
            if (c == ' ') {
                result.append(' ');
                column++;
            } else if (c == '\t') {
                int width = 4 - column % 4;
                for (int k = 0; k < width; k++) {
                    result.append(' ');
                }
                column += width;
            } else {
                break;
            }
            index++;
        }
        result.append(line, index, line.length());
        return result.toString();
    }

    // MARK: Parsing

    static List<Item> parseBlocks(List<String> lines, int depth) {
        List<Item> items = new ArrayList<Item>();
        if (depth > MAXIMUM_NESTING) {
            StringBuilder text = new StringBuilder();
            for (String line : lines) {
                String trimmed = CharClass.trimInlineWhitespace(line);
                if (!trimmed.isEmpty()) {
                    if (text.length() > 0) {
                        text.append('\n');
                    }
                    text.append(trimmed);
                }
            }
            if (text.length() > 0) {
                items.add(new Item(paragraph(text.toString()), false));
            }
            return items;
        }
        int i = 0;
        boolean blankBefore = false;
        int count = lines.size();
        while (i < count) {
            String line = lines.get(i);
            if (isBlank(line)) {
                blankBefore = !items.isEmpty();
                i++;
                continue;
            }
            int indent = leadingSpaces(line);

            // Indented code.
            if (indent >= 4) {
                List<String> codeLines = new ArrayList<String>();
                while (i < count && (isBlank(lines.get(i)) || leadingSpaces(lines.get(i)) >= 4)) {
                    codeLines.add(isBlank(lines.get(i)) ? "" : dropLeadingSpaces(lines.get(i), 4));
                    i++;
                }
                boolean trailingBlank = false;
                while (!codeLines.isEmpty() && codeLines.get(codeLines.size() - 1).isEmpty()) {
                    codeLines.remove(codeLines.size() - 1);
                    trailingBlank = true;
                }
                Block code = new Block(Kind.CODE);
                code.text = join(codeLines);
                items.add(new Item(code, blankBefore));
                blankBefore = trailingBlank;
                continue;
            }

            Fence fence = fenceOpen(line);
            if (fence != null) {
                i++;
                List<String> content = new ArrayList<String>();
                while (i < count && !isFenceClose(lines.get(i), fence)) {
                    content.add(dropLeadingSpaces(lines.get(i), fence.indent));
                    i++;
                }
                if (i < count) {
                    i++;
                }
                Block code = new Block(Kind.CODE);
                code.text = join(content);
                code.language = fence.language;
                items.add(new Item(code, blankBefore));
                blankBefore = false;
                continue;
            }

            Object[] heading = atxHeading(line);
            if (heading != null) {
                Block block = new Block(Kind.HEADING);
                block.level = (Integer) heading[0];
                block.text = (String) heading[1];
                items.add(new Item(block, blankBefore));
                blankBefore = false;
                i++;
                continue;
            }

            if (isThematicBreak(line)) {
                items.add(new Item(new Block(Kind.RULE), blankBefore));
                blankBefore = false;
                i++;
                continue;
            }

            if (stripQuote(line) != null) {
                List<String> quoteLines = new ArrayList<String>();
                boolean lastWasText = false;
                while (i < count) {
                    String inner = stripQuote(lines.get(i));
                    if (inner != null) {
                        quoteLines.add(inner);
                        lastWasText = !isBlank(inner);
                        i++;
                        continue;
                    }
                    if (lastWasText && !isBlank(lines.get(i)) && !interruptsParagraph(lines, i)) {
                        // Lazy continuation of a quoted paragraph.
                        quoteLines.add(lines.get(i));
                        i++;
                        continue;
                    }
                    break;
                }
                Block quote = new Block(Kind.QUOTE);
                quote.children = parseBlocks(quoteLines, depth + 1);
                items.add(new Item(quote, blankBefore));
                blankBefore = false;
                continue;
            }

            // An empty first item ("*" or "-" alone) only starts a list when content follows under
            // it; a lone "*" in a chat message is meant literally.
            ListMarker first = listMarker(line);
            if (first != null && (!first.content.isEmpty()
                || (i + 1 < count && !isBlank(lines.get(i + 1)) && leadingSpaces(lines.get(i + 1)) >= first.contentIndent))) {
                List<List<String>> itemLines = new ArrayList<List<String>>();
                List<String> currentItem = new ArrayList<String>();
                currentItem.add(first.content);
                itemLines.add(currentItem);
                int contentIndent = first.contentIndent;
                int trailingBlanks = 0;
                i++;
                while (i < count) {
                    String next = lines.get(i);
                    if (isBlank(next)) {
                        currentItem.add("");
                        trailingBlanks++;
                        i++;
                        continue;
                    }
                    if (leadingSpaces(next) >= contentIndent) {
                        currentItem.add(dropLeadingSpaces(next, contentIndent));
                        trailingBlanks = 0;
                        i++;
                        continue;
                    }
                    ListMarker marker = isThematicBreak(next) ? null : listMarker(next);
                    if (marker != null && marker.ordered == first.ordered && marker.marker == first.marker) {
                        currentItem = new ArrayList<String>();
                        currentItem.add(marker.content);
                        itemLines.add(currentItem);
                        contentIndent = marker.contentIndent;
                        trailingBlanks = 0;
                        i++;
                        continue;
                    }
                    if (trailingBlanks == 0 && !interruptsParagraph(lines, i)) {
                        currentItem.add(dropLeadingSpaces(next, Integer.MAX_VALUE));
                        i++;
                        continue;
                    }
                    break;
                }
                while (!currentItem.isEmpty() && currentItem.get(currentItem.size() - 1).isEmpty()) {
                    currentItem.remove(currentItem.size() - 1);
                }
                List<ListItem> listItems = new ArrayList<ListItem>();
                for (List<String> itemContent : itemLines) {
                    List<String> contentLines = new ArrayList<String>(itemContent);
                    Boolean task = null;
                    if (!contentLines.isEmpty()) {
                        String firstLine = contentLines.get(0);
                        String[][] prefixes = {{"[ ]", "false"}, {"[x]", "true"}, {"[X]", "true"}};
                        for (String[] prefix : prefixes) {
                            if (firstLine.equals(prefix[0]) || firstLine.startsWith(prefix[0] + " ")) {
                                task = Boolean.valueOf(prefix[1]);
                                contentLines.set(0, dropLeadingSpaces(firstLine.substring(prefix[0].length()), Integer.MAX_VALUE));
                                break;
                            }
                        }
                    }
                    listItems.add(new ListItem(task, parseBlocks(contentLines, depth + 1)));
                }
                Block list = new Block(Kind.LIST);
                list.ordered = first.ordered;
                list.start = first.number;
                list.items = listItems;
                items.add(new Item(list, blankBefore));
                blankBefore = trailingBlanks > 0;
                continue;
            }

            if (isTableStart(lines, i)) {
                List<String> header = splitTableRow(lines.get(i));
                List<Alignment> alignments = new ArrayList<Alignment>();
                for (String cell : splitTableRow(lines.get(i + 1))) {
                    if (cell.startsWith(":") && cell.endsWith(":") && cell.length() > 1) {
                        alignments.add(Alignment.CENTER);
                    } else {
                        alignments.add(cell.endsWith(":") ? Alignment.RIGHT : Alignment.LEFT);
                    }
                }
                List<List<String>> rows = new ArrayList<List<String>>();
                rows.add(header);
                i += 2;
                while (i < count && !isBlank(lines.get(i)) && lines.get(i).indexOf('|') >= 0 && !startsOtherBlock(lines.get(i))) {
                    List<String> cells = splitTableRow(lines.get(i));
                    if (cells.size() < header.size()) {
                        while (cells.size() < header.size()) {
                            cells.add("");
                        }
                    } else if (cells.size() > header.size()) {
                        cells = new ArrayList<String>(cells.subList(0, header.size()));
                    }
                    rows.add(cells);
                    i++;
                }
                Block table = new Block(Kind.TABLE);
                table.rows = rows;
                table.alignments = alignments;
                items.add(new Item(table, blankBefore));
                blankBefore = false;
                continue;
            }

            // Paragraph (or setext heading).
            List<String> paragraphLines = new ArrayList<String>();
            paragraphLines.add(line);
            int headingLevel = 0;
            i++;
            while (i < count) {
                String next = lines.get(i);
                if (isBlank(next)) {
                    break;
                }
                if (isSetextUnderline(next)) {
                    headingLevel = CharClass.trimInlineWhitespace(next).charAt(0) == '=' ? 1 : 2;
                    i++;
                    break;
                }
                if (interruptsParagraph(lines, i)) {
                    break;
                }
                paragraphLines.add(next);
                i++;
            }
            StringBuilder text = new StringBuilder();
            for (int k = 0; k < paragraphLines.size(); k++) {
                if (k > 0) {
                    text.append('\n');
                }
                text.append(CharClass.trimInlineWhitespace(paragraphLines.get(k)));
            }
            Block block;
            if (headingLevel > 0) {
                block = new Block(Kind.HEADING);
                block.level = headingLevel;
                block.text = text.toString();
            } else {
                block = paragraph(text.toString());
            }
            items.add(new Item(block, blankBefore));
            blankBefore = false;
        }
        return items;
    }

    private static Block paragraph(String text) {
        Block block = new Block(Kind.PARAGRAPH);
        block.text = text;
        return block;
    }

    private static String join(List<String> lines) {
        StringBuilder builder = new StringBuilder();
        for (int k = 0; k < lines.size(); k++) {
            if (k > 0) {
                builder.append('\n');
            }
            builder.append(lines.get(k));
        }
        return builder.toString();
    }

    // MARK: Line classification

    static boolean isBlank(String line) {
        for (int k = 0; k < line.length(); k++) {
            char c = line.charAt(k);
            if (c != ' ' && c != '\t') {
                return false;
            }
        }
        return true;
    }

    static int leadingSpaces(String line) {
        int count = 0;
        while (count < line.length() && line.charAt(count) == ' ') {
            count++;
        }
        return count;
    }

    static String dropLeadingSpaces(String line, int count) {
        int index = 0;
        while (index < count && index < line.length() && line.charAt(index) == ' ') {
            index++;
        }
        return line.substring(index);
    }

    private static Fence fenceOpen(String line) {
        int indent = leadingSpaces(line);
        if (indent > 3 || indent >= line.length()) {
            return null;
        }
        char ch = line.charAt(indent);
        if (ch != '`' && ch != '~') {
            return null;
        }
        int count = 0;
        while (indent + count < line.length() && line.charAt(indent + count) == ch) {
            count++;
        }
        if (count < 3) {
            return null;
        }
        String info = CharClass.trimInlineWhitespace(line.substring(indent + count));
        if (ch == '`' && info.indexOf('`') >= 0) {
            return null;
        }
        Fence fence = new Fence();
        fence.ch = ch;
        fence.count = count;
        fence.indent = indent;
        String language = null;
        for (String part : info.split(" ")) {
            if (!part.isEmpty()) {
                language = part;
                break;
            }
        }
        fence.language = language;
        return fence;
    }

    private static boolean isFenceClose(String line, Fence fence) {
        int indent = leadingSpaces(line);
        if (indent > 3) {
            return false;
        }
        int count = 0;
        while (indent + count < line.length() && line.charAt(indent + count) == fence.ch) {
            count++;
        }
        if (count < fence.count) {
            return false;
        }
        for (int k = indent + count; k < line.length(); k++) {
            char c = line.charAt(k);
            if (c != ' ' && c != '\t') {
                return false;
            }
        }
        return true;
    }

    /** {level, text} or null. */
    static Object[] atxHeading(String line) {
        int indent = leadingSpaces(line);
        if (indent > 3) {
            return null;
        }
        String rest = line.substring(indent);
        int level = 0;
        while (level < rest.length() && rest.charAt(level) == '#') {
            level++;
        }
        if (level < 1 || level > 6) {
            return null;
        }
        String afterHashes = rest.substring(level);
        if (!(afterHashes.isEmpty() || afterHashes.charAt(0) == ' ' || afterHashes.charAt(0) == '\t')) {
            return null;
        }
        String content = CharClass.trimInlineWhitespace(afterHashes);
        // Optional closing sequence.
        int end = content.length();
        while (end > 0 && content.charAt(end - 1) == '#') {
            end--;
        }
        if (end < content.length() && (end == 0 || content.charAt(end - 1) == ' ')) {
            content = CharClass.trimInlineWhitespace(content.substring(0, end));
        }
        return new Object[] {level, content};
    }

    static boolean isThematicBreak(String line) {
        if (leadingSpaces(line) > 3) {
            return false;
        }
        int first = 0;
        int count = 0;
        for (int k = 0; k < line.length(); k++) {
            char c = line.charAt(k);
            if (c == ' ' || c == '\t') {
                continue;
            }
            if (count == 0) {
                if (c != '-' && c != '*' && c != '_') {
                    return false;
                }
                first = c;
            } else if (c != first) {
                return false;
            }
            count++;
        }
        return count >= 3;
    }

    static boolean isSetextUnderline(String line) {
        if (leadingSpaces(line) > 3) {
            return false;
        }
        String trimmed = CharClass.trimInlineWhitespace(line);
        if (trimmed.isEmpty()) {
            return false;
        }
        char first = trimmed.charAt(0);
        if (first != '=' && first != '-') {
            return false;
        }
        for (int k = 1; k < trimmed.length(); k++) {
            if (trimmed.charAt(k) != first) {
                return false;
            }
        }
        return true;
    }

    static String stripQuote(String line) {
        int indent = leadingSpaces(line);
        if (indent > 3 || indent >= line.length() || line.charAt(indent) != '>') {
            return null;
        }
        int start = indent + 1;
        if (start < line.length() && line.charAt(start) == ' ') {
            start++;
        }
        return line.substring(start);
    }

    private static ListMarker listMarker(String line) {
        int indent = leadingSpaces(line);
        if (indent > 3) {
            return null;
        }
        int[] rest = CharClass.codePoints(line.substring(indent));
        if (rest.length == 0) {
            return null;
        }
        int first = rest[0];
        boolean ordered;
        int marker = first;
        int number = 1;
        int markerWidth = 1;
        if (first == '-' || first == '+' || first == '*') {
            ordered = false;
        } else {
            int digits = 0;
            while (digits < rest.length && digits < 9 && CharClass.isASCIIDigit(rest[digits])) {
                digits++;
            }
            if (digits < 1 || digits >= rest.length || (rest[digits] != '.' && rest[digits] != ')')) {
                return null;
            }
            ordered = true;
            number = Integer.parseInt(CharClass.string(rest, 0, digits));
            marker = rest[digits];
            markerWidth = digits + 1;
        }
        if (rest.length == markerWidth) {
            return new ListMarker(ordered, marker, number, indent + markerWidth + 1, "");
        }
        if (rest[markerWidth] != ' ') {
            return null;
        }
        int spaces = 0;
        while (markerWidth + spaces < rest.length && rest[markerWidth + spaces] == ' ') {
            spaces++;
        }
        if (markerWidth + spaces == rest.length) {
            return new ListMarker(ordered, marker, number, indent + markerWidth + 1, "");
        }
        if (spaces > 4) {
            spaces = 1;
        }
        String content = CharClass.string(rest, markerWidth + spaces, rest.length);
        return new ListMarker(ordered, marker, number, indent + markerWidth + spaces, content);
    }

    /**
     * Block starts that can interrupt a paragraph (CommonMark: an ordered list only when it starts
     * at 1, and no empty list items).
     */
    static boolean startsOtherBlock(String line) {
        if (fenceOpen(line) != null || atxHeading(line) != null || isThematicBreak(line) || stripQuote(line) != null) {
            return true;
        }
        ListMarker marker = listMarker(line);
        return marker != null && !marker.content.isEmpty() && (!marker.ordered || marker.number == 1);
    }

    static boolean interruptsParagraph(List<String> lines, int index) {
        return startsOtherBlock(lines.get(index)) || isTableStart(lines, index);
    }

    // MARK: Tables

    static boolean isTableStart(List<String> lines, int index) {
        if (index + 1 >= lines.size()) {
            return false;
        }
        String line = lines.get(index);
        String next = lines.get(index + 1);
        if (line.indexOf('|') < 0 || !(next.indexOf('|') >= 0 || line.startsWith("|"))) {
            return false;
        }
        List<String> header = splitTableRow(line);
        List<String> delimiter = splitTableRow(next);
        if (header.isEmpty() || header.size() != delimiter.size()) {
            return false;
        }
        for (String cell : delimiter) {
            int start = 0;
            int end = cell.length();
            if (start < end && cell.charAt(start) == ':') {
                start++;
            }
            if (end > start && cell.charAt(end - 1) == ':') {
                end--;
            }
            if (start >= end) {
                return false;
            }
            for (int k = start; k < end; k++) {
                if (cell.charAt(k) != '-') {
                    return false;
                }
            }
        }
        return true;
    }

    static List<String> splitTableRow(String line) {
        String trimmed = CharClass.trimInlineWhitespace(line);
        if (trimmed.startsWith("|")) {
            trimmed = trimmed.substring(1);
        }
        if (trimmed.endsWith("|") && !trimmed.endsWith("\\|")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        List<String> cells = new ArrayList<String>();
        StringBuilder current = new StringBuilder();
        boolean inCode = false;
        int previous = CharClass.NONE;
        for (int k = 0; k < trimmed.length(); ) {
            int c = trimmed.codePointAt(k);
            k += Character.charCount(c);
            if (c == '`') {
                inCode = !inCode;
            }
            if (c == '|' && previous != '\\' && !inCode) {
                cells.add(current.toString());
                current.setLength(0);
            } else if (c == '|' && previous == '\\') {
                current.setLength(current.length() - 1);
                current.append('|');
            } else {
                current.appendCodePoint(c);
            }
            previous = c;
        }
        cells.add(current.toString());
        List<String> result = new ArrayList<String>(cells.size());
        for (String cell : cells) {
            result.add(CharClass.trimInlineWhitespace(cell));
        }
        return result;
    }
}
