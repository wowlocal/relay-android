package org.telegram.relay.mattermost.text;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.telegram.relay.mattermost.text.TextTestSupport.quote;

/** Port of the iOS composer → markdown tests, plus quotes, headings, mentions and literal mode. */
public class EntitiesToMarkdownTest {
    private static TextEntity e(TextEntity.Type type, int start, int end) {
        return TextEntity.of(type, start, end - start);
    }

    private static FormattedText f(String text, TextEntity... entities) {
        return new FormattedText(text, Arrays.asList(entities));
    }

    private static final TextEntity.Type BOLD = TextEntity.Type.BOLD;
    private static final TextEntity.Type ITALIC = TextEntity.Type.ITALIC;
    private static final TextEntity.Type STRIKE = TextEntity.Type.STRIKETHROUGH;
    private static final TextEntity.Type CODE = TextEntity.Type.CODE;
    private static final TextEntity.Type UNDERLINE = TextEntity.Type.UNDERLINE;
    private static final TextEntity.Type QUOTE = TextEntity.Type.BLOCKQUOTE;

    private static final EntitiesToMarkdown.Options LITERAL = new EntitiesToMarkdown.Options().setEscapeLiterals(true);

    @Test
    public void composerEntitiesToMarkdown() {
        Object[][] cases = {
            {f("hello bold world", e(BOLD, 6, 10)), "hello **bold** world"},
            {f("snakecase", e(ITALIC, 5, 9)), "snake*case*"},
            {f("an italic word", e(ITALIC, 3, 9)), "an _italic_ word"},
            {f("a b c", e(BOLD, 0, 5), e(ITALIC, 2, 3)), "**a _b_ c**"},
            {f("gone", e(STRIKE, 0, 4)), "~~gone~~"},
            {f("run code now", e(CODE, 4, 8)), "run `code` now"},
            {f("tick`in", e(CODE, 0, 7)), "``tick`in``"},
            {f("`x`", e(CODE, 0, 3)), "`` `x` ``"},
            {f("intro\nlet x = 1\nouter", TextEntity.pre(6, 9, "swift")), "intro\n```swift\nlet x = 1\n```\nouter"},
            {f("inline pre here", TextEntity.pre(7, 3, null)), "inline \n```\npre\n```\n here"},
            {f("has ``` fence", TextEntity.pre(0, 13, null)), "````\nhas ``` fence\n````"},
            {f("click here please", TextEntity.textUrl(6, 4, "https://x.com/a_(b)"), e(BOLD, 6, 10)), "click [**here**](https://x.com/a_(b)) please"},
            {f("odd link", TextEntity.textUrl(0, 3, "https://x.com/a b)")), "[odd](<https://x.com/a b)>) link"},
            {f("[x] link", TextEntity.textUrl(0, 8, "https://x.com")), "[\\[x\\] link](https://x.com)"},
            {f("bold \nnext", e(BOLD, 0, 10)), "**bold** \n**next**"},
            {f("👍🏽 hi", e(BOLD, 5, 7)), "👍🏽 **hi**"},
            {f("plain **typed** markdown"), "plain **typed** markdown"},
            {f("under @bob", e(UNDERLINE, 0, 5), TextEntity.mention(6, 4, "bob")), "under @bob"},
            {f("overlap here", e(BOLD, 0, 7), e(ITALIC, 3, 12)), "**ove*rlap*** _here_"},
            {f("split", e(BOLD, 0, 1), e(ITALIC, 1, 5)), "**s***plit*"},
        };
        for (Object[] testCase : cases) {
            FormattedText formatted = (FormattedText) testCase[0];
            assertEquals(formatted.toString(), testCase[1], EntitiesToMarkdown.convert(formatted));
        }
    }

    @Test
    public void entityRangesAreClampedAndSnapped() {
        // Out of range, empty and surrogate-splitting ranges.
        assertEquals("**ab**", EntitiesToMarkdown.convert(f("ab", e(BOLD, 0, 99))));
        assertEquals("ab", EntitiesToMarkdown.convert(f("ab", e(BOLD, 1, 1))));
        assertEquals("**😀**x", EntitiesToMarkdown.convert(f("😀x", e(BOLD, 1, 2))));
        assertEquals("", EntitiesToMarkdown.convert((String) null, null));
    }

    @Test
    public void mentionsAndCustomEmoji() {
        EntitiesToMarkdown.UsernameResolver resolver = new EntitiesToMarkdown.UsernameResolver() {
            @Override
            public String usernameForUserId(String userId) {
                return userId.equals("uid-bob") ? "bob" : null;
            }
        };
        EntitiesToMarkdown.Options options = new EntitiesToMarkdown.Options().setUsernameResolver(resolver);
        // Telegram's mention by id over a display name: the name becomes @username, later ranges shift.
        FormattedText formatted = f("hi Bob Smith, look here", TextEntity.mentionUser(3, 9, "uid-bob"), e(BOLD, 0, 12), e(ITALIC, 19, 23));
        assertEquals("**hi @bob**, look _here_", EntitiesToMarkdown.convert(formatted, options));
        // Unknown id: the text stays.
        assertEquals("hi Someone", EntitiesToMarkdown.convert(f("hi Someone", TextEntity.mentionUser(3, 7, "uid-x")), options));
        // A mention with a username over other text.
        assertEquals("cc @alice!", EntitiesToMarkdown.convert(f("cc Alice!", TextEntity.mention(3, 5, "alice"))));
        // A custom emoji over its fallback character.
        assertEquals("nice :party_parrot: **ok**", EntitiesToMarkdown.convert(f("nice 🦜 ok", TextEntity.customEmoji(5, 2, "party_parrot"), e(BOLD, 8, 10))));
        // Already the right text: unchanged.
        assertEquals(":party_parrot:", EntitiesToMarkdown.convert(f(":party_parrot:", TextEntity.customEmoji(0, 14, "party_parrot"))));
    }

    @Test
    public void blockQuotes() {
        Object[][] cases = {
            {f("quoted\nlines", e(QUOTE, 0, 12)), "> quoted\n> lines"},
            {f("before\nquote\nafter", e(QUOTE, 7, 12)), "before\n> quote\n>\nafter"},
            {f("before\nquote\n\nafter", e(QUOTE, 7, 12)), "before\n> quote\n\nafter"},
            {f("x quote y", e(QUOTE, 2, 7)), "x \n> quote\n>\n y"},
            {f("a\nb", e(QUOTE, 0, 1), e(QUOTE, 2, 3)), "> a\n> b"},
            {f("q\n\nr", e(QUOTE, 0, 4)), "> q\n>\n> r"},
            {f("bold quote", e(QUOTE, 0, 10), e(BOLD, 0, 4)), "> **bold** quote"},
            {f("code\nnext", e(QUOTE, 0, 9), TextEntity.pre(0, 4, "js")), "> ```js\n> code\n> ```\n> next"},
        };
        for (Object[] testCase : cases) {
            FormattedText formatted = (FormattedText) testCase[0];
            String markdown = EntitiesToMarkdown.convert(formatted);
            assertEquals(formatted.toString(), testCase[1], markdown);
        }
        // Quotes that start and end on line boundaries come back exactly.
        FormattedText[] exact = {
            f("quoted\nlines", e(QUOTE, 0, 12)),
            f("before\nquote\nafter", e(QUOTE, 7, 12)),
            f("before\nquote\n\nafter", e(QUOTE, 7, 12)),
            f("bold quote", e(QUOTE, 0, 10), e(BOLD, 0, 4)),
            f("a\nb", e(QUOTE, 0, 3)),
        };
        for (FormattedText formatted : exact) {
            FormattedText reparsed = MarkdownToEntities.convert(EntitiesToMarkdown.convert(formatted, LITERAL));
            assertEquals(formatted.toString(), formatted, reparsed);
        }
    }

    @Test
    public void headings() {
        FormattedText formatted = MarkdownToEntities.convert("## Title **x**\nbody\n> # Quoted");
        assertEquals("## Title x\nbody\n> # Quoted", EntitiesToMarkdown.convert(formatted));
        assertEquals(formatted, MarkdownToEntities.convert(EntitiesToMarkdown.convert(formatted)));
        // Not a whole line: only the bold stays.
        assertEquals("x **head**", EntitiesToMarkdown.convert(f("x head", TextEntity.heading(2, 4, 1), e(BOLD, 2, 6))));
    }

    @Test
    public void roundTripThroughMarkdown() {
        String[] inputs = {
            "hello **bold** world",
            "*italic* text and _more_",
            "~~gone~~ for good",
            "run `code` now",
            "``a`b``",
            "```swift\nlet x = 1\n```",
            "before\n```\ncode\n```\nafter",
            "[link](https://example.com) and [other](https://x.com/a_(b))",
            "**bold _italic_ bold**",
            "[**bold link**](https://x.com/path)",
            "**[bold link](https://x.com/path)**",
            "snake*case* and snake_case_name",
            "Привет **мир** 👍🏽 *ok*",
            "multi\nline **bold**\n_end_",
            "***both***",
            "**a** *b* ~~c~~ `d`",
            "mention @bob and #tag and https://plain.url stay",
            "> quoted **text**\n> more\n\nafter",
            "# Heading\nbody",
        };
        for (EntitiesToMarkdown.Options options : new EntitiesToMarkdown.Options[] {new EntitiesToMarkdown.Options(), LITERAL}) {
            for (String input : inputs) {
                FormattedText parsed = MarkdownToEntities.convert(input);
                String markdown = EntitiesToMarkdown.convert(parsed, options);
                FormattedText reparsed = MarkdownToEntities.convert(markdown);
                assertEquals(quote(input) + " -> " + quote(markdown), parsed, reparsed);
            }
        }
    }

    @Test
    public void literalModeEscapesOnlyWhatMarkdownWouldRead() {
        Object[][] cases = {
            {"plain text, nothing to do.", "plain text, nothing to do."},
            {"snake_case_name and a_b", "snake_case_name and a_b"},
            {"2*3*4 and **x**", "2\\*3\\*4 and \\*\\*x\\*\\*"},
            {"_x_ and __init__", "\\_x\\_ and \\_\\_init\\_\\_"},
            {"~~no~~ ~ alone", "\\~\\~no\\~\\~ ~ alone"},
            {"`tick` [x](y) a\\b", "\\`tick\\` \\[x\\](y) a\\\\b"},
            {"# not a heading\n- not a list\n+ nor this\n1. nor this\n> nor a quote", "\\# not a heading\n\\- not a list\n\\+ nor this\n1\\. nor this\n\\> nor a quote"},
            {"#hashtag and C# stay", "#hashtag and C# stay"},
            {"title\n===\nrule\n---", "title\n\\===\nrule\n\\---"},
            {"a | b\n--|--", "a | b\n\\--\\|--"},
            {"x\n|-|", "x\n\\|-\\|"},
            {"a | b alone", "a | b alone"},
            {"smile :) <3 x-D :smile: :custom_one:", "smile \\:) \\<3 x\\-D \\:smile: :custom_one:"},
            {"10:30 and a < b", "10:30 and a < b"},
            {"see https://x.com/a_b_c/*d*/~e and www.x.com/_y_", "see https://x.com/a_b_c/*d*/~e and www.x.com/_y_"},
            {"(https://x.com/a_b), a@b.co_ @jane_doe ~town_square #tag_x", "(https://x.com/a_b), a@b.co\\_ @jane_doe ~town_square #tag_x"},
            {"https://x.com*", "<https://x.com>\\*"},
            {"$5 and $x$", "$5 and $x$"},
        };
        for (Object[] testCase : cases) {
            String text = (String) testCase[0];
            String markdown = EntitiesToMarkdown.convert(text, null, LITERAL);
            assertEquals(quote(text), testCase[1], markdown);
            assertEquals(quote(text) + " -> " + quote(markdown), text, MarkdownToEntities.convert(markdown).getText());
        }
        EntitiesToMarkdown.Options latex = new EntitiesToMarkdown.Options().setEscapeLiterals(true).setInlineLatex(true);
        assertEquals("\\$x\\$", EntitiesToMarkdown.convert("$x$", null, latex));
        assertEquals("$x$", MarkdownToEntities.convert(EntitiesToMarkdown.convert("$x$", null, latex), new MarkdownToEntities.Options().setInlineLatex(true)).getText());
        // Pass-through mode leaves typed markdown alone.
        assertEquals("**typed** # x", EntitiesToMarkdown.convert("**typed** # x", null));
    }

    @Test
    public void literalModeKeepsEntitiesNextToMarkdownCharacters() {
        FormattedText[] cases = {
            f("~ x ~", e(STRIKE, 2, 3)),
            f("a ~town b", e(STRIKE, 2, 7)),
            f("x * y", e(BOLD, 2, 3)),
            f("x ` y", e(CODE, 2, 3)),
            f("say :) now", e(ITALIC, 4, 6)),
            f("[link] text", TextEntity.textUrl(0, 6, "https://x.com")),
            f("open https://x.com/a_b now", e(BOLD, 5, 22)),
            f("open https://x.com/a_b now", e(ITALIC, 5, 22)),
            f("see :smile: here", e(BOLD, 4, 11)),
            f("- item", e(BOLD, 0, 1)),
            f("# heading", e(ITALIC, 0, 1)),
            f("go https://x.com", TextEntity.textUrl(3, 13, "https://y.com")),
        };
        for (FormattedText formatted : cases) {
            String markdown = EntitiesToMarkdown.convert(formatted, LITERAL);
            FormattedText reparsed = MarkdownToEntities.convert(markdown);
            assertEquals(formatted + " -> " + quote(markdown), formatted.getText(), reparsed.getText());
            assertEquals(formatted + " -> " + quote(markdown), formatted.getEntities(), composerEntities(reparsed));
        }
    }

    // MARK: Property tests

    static List<TextEntity> composerEntities(FormattedText formatted) {
        List<TextEntity> result = new ArrayList<TextEntity>();
        for (TextEntity entity : formatted.getEntities()) {
            switch (entity.getType()) {
                case BOLD:
                case ITALIC:
                case STRIKETHROUGH:
                case CODE:
                case TEXT_URL:
                    result.add(entity);
                    break;
                default:
                    break;
            }
        }
        return result;
    }

    private static final TextEntity[] KINDS = {
        TextEntity.of(BOLD, 0, 0), TextEntity.of(ITALIC, 0, 0), TextEntity.of(STRIKE, 0, 0), TextEntity.of(CODE, 0, 0), TextEntity.textUrl(0, 0, "https://example.com/p"),
    };

    /** Random composer output over words: word-aligned entities, possibly two kinds on the same words. */
    private static FormattedText randomComposerText(TextTestSupport.SeededGenerator rng, String[] words, boolean lines) {
        int count = 1 + rng.random(7);
        StringBuilder text = new StringBuilder();
        List<int[]> wordRanges = new ArrayList<int[]>();
        List<Boolean> lineBreakBefore = new ArrayList<Boolean>();
        for (int index = 0; index < count; index++) {
            boolean lineBreak = false;
            if (index > 0) {
                lineBreak = lines && rng.random(4) == 0;
                text.append(lineBreak ? "\n" : " ");
            }
            lineBreakBefore.add(lineBreak);
            String word = words[rng.random(words.length)];
            int start = text.length();
            text.append(word);
            wordRanges.add(new int[] {start, text.length()});
        }
        List<TextEntity> entities = new ArrayList<TextEntity>();
        int index = 0;
        while (index < wordRanges.size()) {
            int length = 1 + rng.random(2);
            int end = Math.min(wordRanges.size(), index + length);
            // Entities stay on one line.
            for (int k = index + 1; k < end; k++) {
                if (lineBreakBefore.get(k)) {
                    end = k;
                    break;
                }
            }
            if (rng.random(2) == 0) {
                int start = wordRanges.get(index)[0];
                int stop = wordRanges.get(end - 1)[1];
                TextEntity kind = KINDS[rng.random(KINDS.length)];
                entities.add(kind.withRange(start, stop - start));
                if (kind.getType() != CODE && rng.random(3) == 0) {
                    List<TextEntity.Type> inner = new ArrayList<TextEntity.Type>(Arrays.asList(BOLD, ITALIC, STRIKE));
                    inner.remove(kind.getType());
                    entities.add(TextEntity.of(inner.get(rng.random(inner.size())), start, stop - start));
                }
            }
            index = end;
        }
        return new FormattedText(text.toString(), TextEntity.sorted(entities));
    }

    @Test
    public void randomComposerOutputRoundTrips() {
        // The iOS property: plain words, pass-through mode.
        TextTestSupport.SeededGenerator rng = new TextTestSupport.SeededGenerator(7);
        String[] words = {"alpha", "beta", "гамма", "😀", "delta", "ok", "x1", "snake_case"};
        for (int round = 0; round < 1000; round++) {
            FormattedText formatted = randomComposerText(rng, words, false);
            String markdown = EntitiesToMarkdown.convert(formatted);
            FormattedText reparsed = MarkdownToEntities.convert(markdown);
            assertEquals(quote(markdown), formatted.getText(), reparsed.getText());
            assertEquals(quote(markdown), formatted.getEntities(), composerEntities(reparsed));
        }
    }

    @Test
    public void randomLiteralTextRoundTripsExactly() {
        // Words full of markdown syntax, on several lines: literal mode gives back text and entities.
        String[] words = {
            "alpha", "beta", "гамма", "😀", "x1", "snake_case", "*", "_", "__init__", "~~", "~", "`", "``code``",
            "[a](b)", "\\", "<3", ":)", ";-)", "x-D", ":smile:", ":custom_x:", "@bob", "@jane_doe.", "~town", "#tag", "C#",
            "https://x.com/a_b", "http://y.org/(z)", "www.w.com/_q_", "a@b.co", "1.", "2)", "-", "+", "#", "##", ">",
            "=", "---", "***", "|", "2*3*4", "$5", "!", "(paren)", "[x]", "**", "a_b_", "_a", "b_", "~~x~~", "<b>",
            "&amp;", "10:30", "👍🏽", "!*x*", "\\*", "a\\", "]", "(", ")",
        };
        TextTestSupport.SeededGenerator rng = new TextTestSupport.SeededGenerator(2026);
        for (int round = 0; round < 20000; round++) {
            FormattedText formatted = randomComposerText(rng, words, true);
            String markdown = EntitiesToMarkdown.convert(formatted, LITERAL);
            FormattedText reparsed = MarkdownToEntities.convert(markdown);
            String label = formatted + " -> " + quote(markdown) + " -> " + reparsed;
            assertEquals(label, formatted.getText(), reparsed.getText());
            assertEquals(label, formatted.getEntities(), composerEntities(reparsed));
        }
    }

    @Test
    public void randomBlocksRoundTripExactly() {
        // Lines of literal text with inline entities, whole-line code blocks and quotes (quotes kept
        // apart by an unquoted line: markdown merges quotes on consecutive lines).
        String[] words = {"alpha", "*", "_x_", "`", "> q", "- item", "1.", "#", "@bob", "https://x.com/a_b", ":)", "~~", "|", "---", "[a](b)", "😀"};
        String[] code = {"let x = 1", "``` fence", "  indented", "**raw** _x_", "a\\b"};
        TextTestSupport.SeededGenerator rng = new TextTestSupport.SeededGenerator(31);
        for (int round = 0; round < 5000; round++) {
            int lineCount = 1 + rng.random(6);
            StringBuilder text = new StringBuilder();
            List<TextEntity> entities = new ArrayList<TextEntity>();
            boolean previousQuoted = false;
            int quoteStart = -1;
            for (int line = 0; line < lineCount; line++) {
                if (line > 0) {
                    text.append('\n');
                }
                int lineStart = text.length();
                int kind = rng.random(4);
                boolean quoted = kind == 3 && !previousQuoted;
                if (kind == 2) {
                    text.append(code[rng.random(code.length)]);
                    entities.add(TextEntity.pre(lineStart, text.length() - lineStart, rng.random(2) == 0 ? null : "js"));
                } else {
                    FormattedText inline = randomComposerText(rng, words, false);
                    text.append(inline.getText());
                    for (TextEntity entity : inline.getEntities()) {
                        entities.add(entity.withRange(entity.getOffset() + lineStart, entity.getLength()));
                    }
                }
                if (quoted) {
                    quoteStart = lineStart;
                    entities.add(TextEntity.of(QUOTE, lineStart, text.length() - lineStart));
                }
                previousQuoted = quoted;
            }
            FormattedText formatted = new FormattedText(text.toString(), TextEntity.sorted(entities));
            String markdown = EntitiesToMarkdown.convert(formatted, LITERAL);
            FormattedText reparsed = MarkdownToEntities.convert(markdown);
            List<TextEntity> kept = new ArrayList<TextEntity>();
            for (TextEntity entity : reparsed.getEntities()) {
                if (entity.getType() == QUOTE || entity.getType() == TextEntity.Type.PRE || composerEntities(new FormattedText("", Collections.singletonList(entity))).size() == 1) {
                    kept.add(entity);
                }
            }
            String label = formatted + " -> " + quote(markdown) + " -> " + reparsed;
            assertEquals(label, formatted.getText(), reparsed.getText());
            assertEquals(label, formatted.getEntities(), kept);
        }
    }

    @Test
    public void serializedParsedMarkdownIsAFixpoint() {
        // parse → literal markdown → parse is stable from the second round on, for random markdown.
        TextTestSupport.SeededGenerator rng = new TextTestSupport.SeededGenerator(99);
        String[] alphabet = {"a", "b", " ", "_", "*", "~", "`", "[", "]", "(", ")", "<", ">", "@", "#", ":", "!", "\\", "\n", "-", "|", "1", ".", "😀", "й", "h", "t", "p", "s", "/", "x", "=", "+", "'", "https://x.co/", "**", "`` ", "> ", "- ", "1. ", "# ", "\n\n", "```\n"};
        for (int round = 0; round < 5000; round++) {
            int length = rng.random(40);
            StringBuilder input = new StringBuilder();
            for (int k = 0; k < length; k++) {
                input.append(alphabet[rng.random(alphabet.length)]);
            }
            FormattedText first = MarkdownToEntities.convert(input.toString());
            FormattedText second = MarkdownToEntities.convert(EntitiesToMarkdown.convert(first, LITERAL));
            String markdown = EntitiesToMarkdown.convert(second, LITERAL);
            FormattedText third = MarkdownToEntities.convert(markdown);
            String label = quote(input.toString()) + " -> " + second + " -> " + quote(markdown);
            assertEquals(label, second.getText(), third.getText());
            assertEquals(label, composerEntities(second), composerEntities(third));
        }
    }

    @Test
    public void unicodeTextRoundTrips() {
        Map<String, FormattedText> cases = new HashMap<String, FormattedText>();
        cases.put("emoji", f("👨\u200D👩\u200D👧 **x** 🇺🇦", e(BOLD, 9, 14)));
        cases.put("cyrillic", f("Привет мир", e(ITALIC, 7, 10)));
        cases.put("astral", f("𝒳 code", e(CODE, 3, 7)));
        for (Map.Entry<String, FormattedText> entry : cases.entrySet()) {
            FormattedText formatted = entry.getValue();
            FormattedText reparsed = MarkdownToEntities.convert(EntitiesToMarkdown.convert(formatted, LITERAL));
            assertEquals(entry.getKey(), formatted.getText(), reparsed.getText());
            assertEquals(entry.getKey(), formatted.getEntities(), composerEntities(reparsed));
        }
        assertEquals(Collections.<TextEntity>emptyList(), composerEntities(MarkdownToEntities.convert("plain")));
    }
}
