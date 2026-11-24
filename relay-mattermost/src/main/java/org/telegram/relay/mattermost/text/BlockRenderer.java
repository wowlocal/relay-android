package org.telegram.relay.mattermost.text;

import java.util.ArrayList;
import java.util.List;

/** Renders parsed blocks as plain text and entities (port of the iOS {@code BlockRenderer}). */
final class BlockRenderer {
    /**
     * Tables up to this many characters per line are drawn as a monospaced grid (a {@code pre}
     * block); wider ones as one record per row. A chat bubble fits about 30 monospaced characters
     * on a phone; a wrapped grid line is unreadable.
     */
    static final int TABLE_GRID_MAX_WIDTH = 30;

    /**
     * The grid's code block language: Telegram labels a code block with its language (a header row
     * that also holds the copy button); no highlighter grammar has this name.
     */
    static final String TABLE_LANGUAGE = "table";

    private final MarkdownToEntities.Options options;

    BlockRenderer(MarkdownToEntities.Options options) {
        this.options = options;
    }

    /** Text plus entities, appended piecewise (UTF-16 offsets). */
    static final class TextBuilder {
        final StringBuilder text = new StringBuilder();
        final List<TextEntity> entities = new ArrayList<TextEntity>();

        int length() {
            return text.length();
        }

        void append(String string) {
            text.append(string);
        }

        void append(FormattedText formatted) {
            int base = text.length();
            text.append(formatted.getText());
            for (TextEntity entity : formatted.getEntities()) {
                entities.add(entity.withRange(entity.getOffset() + base, entity.getLength()));
            }
        }

        void addEntity(int start, TextEntity template) {
            if (text.length() > start) {
                entities.add(template.withRange(start, text.length() - start));
            }
        }

        FormattedText formatted() {
            return new FormattedText(text.toString(), entities);
        }
    }

    private FormattedText inline(String text) {
        return InlineParser.parse(text, options);
    }

    /**
     * Renders blocks separated by a newline, or a blank line where the markdown had one. Blocks that
     * render to nothing (an empty heading or quote, a code block of blank lines) are left out with
     * their separators.
     */
    FormattedText render(List<MarkdownBlocks.Item> items, int depth) {
        TextBuilder builder = new TextBuilder();
        boolean pendingBlank = false;
        for (MarkdownBlocks.Item item : items) {
            FormattedText piece = renderBlock(item.block, depth);
            boolean blank = pendingBlank || item.blankBefore;
            if (piece.getText().isEmpty()) {
                pendingBlank = blank;
                continue;
            }
            if (builder.length() > 0) {
                builder.append(blank ? "\n\n" : "\n");
            }
            builder.append(piece);
            pendingBlank = false;
        }
        return builder.formatted();
    }

    private FormattedText renderBlock(MarkdownBlocks.Block block, int depth) {
        TextBuilder builder = new TextBuilder();
        {
            switch (block.kind) {
                case PARAGRAPH:
                    builder.append(inline(block.text));
                    break;
                case HEADING: {
                    int start = builder.length();
                    builder.append(inline(block.text));
                    builder.addEntity(start, TextEntity.of(TextEntity.Type.BOLD, 0, 0));
                    builder.addEntity(start, TextEntity.heading(0, 0, block.level));
                    break;
                }
                case CODE: {
                    if (MarkdownBlocks.isBlank(block.text.replace('\n', ' '))) {
                        break;
                    }
                    int start = builder.length();
                    builder.append(block.text);
                    builder.addEntity(start, TextEntity.pre(0, 0, CodeLanguage.normalize(block.language)));
                    break;
                }
                case QUOTE: {
                    int start = builder.length();
                    builder.append(render(block.children, depth));
                    builder.addEntity(start, TextEntity.of(TextEntity.Type.BLOCKQUOTE, 0, 0));
                    break;
                }
                case LIST:
                    for (int k = 0; k < block.items.size(); k++) {
                        MarkdownBlocks.ListItem listItem = block.items.get(k);
                        if (k > 0) {
                            builder.append("\n");
                        }
                        String prefix;
                        if (block.ordered) {
                            prefix = (block.start + k) + ". ";
                            if (listItem.task != null) {
                                prefix += listItem.task ? "☑ " : "☐ ";
                            }
                        } else if (listItem.task != null) {
                            prefix = listItem.task ? "☑ " : "☐ ";
                        } else {
                            prefix = bullet(depth) + " ";
                        }
                        FormattedText content = render(listItem.blocks, depth + 1);
                        if (content.getText().isEmpty()) {
                            builder.append(prefix.substring(0, prefix.length() - 1));
                        } else {
                            builder.append(prefix);
                            builder.append(indentContinuationLines(content, indent(prefix)));
                        }
                    }
                    break;
                case TABLE:
                    renderTable(block.rows, block.alignments, builder);
                    break;
                case RULE:
                    builder.append("———");
                    break;
                default:
                    break;
            }
        }
        return builder.formatted();
    }

    static String bullet(int depth) {
        switch (depth) {
            case 0:
                return "•";
            case 1:
                return "◦";
            default:
                return "‣";
        }
    }

    /**
     * Whitespace about as wide as {@code prefix} in a proportional UI font, so continuation lines
     * and nested blocks line up with the text after a bullet or number: Telegram's text layout has
     * no hanging indents, and plain spaces are much narrower than "•" or digits.
     */
    static String indent(String prefix) {
        StringBuilder result = new StringBuilder();
        for (int k = 0; k < prefix.length(); ) {
            int c = prefix.codePointAt(k);
            k += Character.charCount(c);
            if (c == '•' || c == '◦' || c == '‣') {
                result.append('\u2002'); // en space ≈ the bullet
            } else if (c == '☐' || c == '☑') {
                result.append("\u2002\u2009\u2009\u2009"); // en + 3 thin ≈ the box
            } else if (c == '1') {
                result.append('\u2002'); // a narrow digit
            } else if (c >= '0' && c <= '9') {
                result.append('\u2007'); // figure space = digit width
            } else if (c == '.') {
                result.append('\u2008'); // punctuation space = period width
            } else {
                result.append(' ');
            }
        }
        return result.toString();
    }

    /** Indents every non-empty line after the first by {@code indent}, remapping entity ranges. */
    static FormattedText indentContinuationLines(FormattedText formatted, String indent) {
        String text = formatted.getText();
        int width = indent.length();
        List<Integer> insertionPoints = new ArrayList<Integer>();
        for (int index = 0; index < text.length(); index++) {
            if (text.charAt(index) == '\n' && index + 1 < text.length() && text.charAt(index + 1) != '\n') {
                insertionPoints.add(index + 1);
            }
        }
        if (insertionPoints.isEmpty() || width == 0) {
            return formatted;
        }
        StringBuilder result = new StringBuilder(text.length() + insertionPoints.size() * width);
        int next = 0;
        for (int index = 0; index < text.length(); index++) {
            if (next < insertionPoints.size() && insertionPoints.get(next) == index) {
                result.append(indent);
                next++;
            }
            result.append(text.charAt(index));
        }
        List<TextEntity> entities = new ArrayList<TextEntity>();
        for (TextEntity entity : formatted.getEntities()) {
            int before = 0;
            int inside = 0;
            for (int point : insertionPoints) {
                if (point <= entity.getOffset()) {
                    before++;
                }
                if (point < entity.getEnd()) {
                    inside++;
                }
            }
            int start = entity.getOffset() + width * before;
            int end = entity.getEnd() + width * inside;
            entities.add(entity.withRange(start, Math.max(0, end - start)));
        }
        return new FormattedText(result.toString(), entities);
    }

    // MARK: Tables

    /**
     * A narrow table as an aligned monospaced grid; a wider one as records (the first column's value
     * in bold, then "Column: value" lines with the cells' inline formatting), separated by blank lines.
     */
    private void renderTable(List<List<String>> rows, List<MarkdownBlocks.Alignment> alignments, TextBuilder builder) {
        List<List<FormattedText>> cells = new ArrayList<List<FormattedText>>();
        for (List<String> row : rows) {
            List<FormattedText> parsedRow = new ArrayList<FormattedText>();
            for (String cell : row) {
                FormattedText parsed = inline(cell);
                parsedRow.add(new FormattedText(parsed.getText().replace('\n', ' '), parsed.getEntities()));
            }
            cells.add(parsedRow);
        }
        if (cells.isEmpty() || cells.get(0).isEmpty()) {
            return;
        }
        List<FormattedText> header = cells.get(0);
        List<List<String>> plain = new ArrayList<List<String>>();
        for (List<FormattedText> row : cells) {
            List<String> texts = new ArrayList<String>();
            for (FormattedText cell : row) {
                texts.add(cell.getText());
            }
            plain.add(texts);
        }
        String grid = tableGrid(plain, alignments);
        if (grid != null) {
            int start = builder.length();
            builder.append(grid);
            builder.addEntity(start, TextEntity.pre(0, 0, TABLE_LANGUAGE));
            return;
        }
        if (cells.size() == 1) {
            // Only a header row: its cells, one per line.
            for (int index = 0; index < header.size(); index++) {
                if (index > 0) {
                    builder.append("\n");
                }
                int start = builder.length();
                builder.append(header.get(index));
                builder.addEntity(start, TextEntity.of(TextEntity.Type.BOLD, 0, 0));
            }
            return;
        }
        for (int rowIndex = 1; rowIndex < cells.size(); rowIndex++) {
            List<FormattedText> row = cells.get(rowIndex);
            if (rowIndex > 1) {
                builder.append("\n\n");
            }
            boolean wroteLine = false;
            for (int column = 0; column < row.size() && column < header.size(); column++) {
                FormattedText cell = row.get(column);
                if (column == 0) {
                    if (!cell.getText().isEmpty()) {
                        int start = builder.length();
                        builder.append(cell);
                        builder.addEntity(start, TextEntity.of(TextEntity.Type.BOLD, 0, 0));
                        wroteLine = true;
                    }
                    continue;
                }
                if (cell.getText().isEmpty()) {
                    continue;
                }
                if (wroteLine) {
                    builder.append("\n");
                }
                String label = header.get(column).getText();
                if (!label.isEmpty()) {
                    int start = builder.length();
                    builder.append(label + ":");
                    builder.addEntity(start, TextEntity.of(TextEntity.Type.ITALIC, 0, 0));
                    builder.append(" ");
                }
                builder.append(cell);
                wroteLine = true;
            }
            if (!wroteLine) {
                builder.append("—");
            }
        }
    }

    /** The grid text, or null when a line would be wider than {@link #TABLE_GRID_MAX_WIDTH}. */
    static String tableGrid(List<List<String>> rows, List<MarkdownBlocks.Alignment> alignments) {
        if (rows.isEmpty() || rows.get(0).isEmpty()) {
            return null;
        }
        int columnCount = rows.get(0).size();
        int[] widths = new int[columnCount];
        for (List<String> row : rows) {
            for (int column = 0; column < row.size() && column < columnCount; column++) {
                widths[column] = Math.max(widths[column], CharClass.displayLength(row.get(column)));
            }
        }
        int lineWidth = 3 * (columnCount - 1);
        for (int column = 0; column < columnCount; column++) {
            widths[column] = Math.max(1, widths[column]);
            lineWidth += widths[column];
        }
        if (lineWidth > TABLE_GRID_MAX_WIDTH) {
            return null;
        }
        StringBuilder grid = new StringBuilder();
        grid.append(gridLine(rows.get(0), widths, alignments));
        grid.append('\n');
        for (int column = 0; column < columnCount; column++) {
            if (column > 0) {
                grid.append("-+-");
            }
            repeat(grid, '-', widths[column]);
        }
        for (int index = 1; index < rows.size(); index++) {
            grid.append('\n');
            grid.append(gridLine(rows.get(index), widths, alignments));
        }
        return grid.toString();
    }

    private static String gridLine(List<String> row, int[] widths, List<MarkdownBlocks.Alignment> alignments) {
        StringBuilder line = new StringBuilder();
        for (int column = 0; column < widths.length; column++) {
            if (column > 0) {
                line.append(" | ");
            }
            String cell = column < row.size() ? row.get(column) : "";
            MarkdownBlocks.Alignment alignment = column < alignments.size() ? alignments.get(column) : MarkdownBlocks.Alignment.LEFT;
            int missing = Math.max(0, widths[column] - CharClass.displayLength(cell));
            switch (alignment) {
                case RIGHT:
                    repeat(line, ' ', missing);
                    line.append(cell);
                    break;
                case CENTER:
                    repeat(line, ' ', missing / 2);
                    line.append(cell);
                    repeat(line, ' ', missing - missing / 2);
                    break;
                default:
                    line.append(cell);
                    repeat(line, ' ', missing);
                    break;
            }
        }
        int end = line.length();
        while (end > 0 && line.charAt(end - 1) == ' ') {
            end--;
        }
        line.setLength(end);
        return line.toString();
    }

    private static void repeat(StringBuilder builder, char c, int count) {
        for (int k = 0; k < count; k++) {
            builder.append(c);
        }
    }
}
