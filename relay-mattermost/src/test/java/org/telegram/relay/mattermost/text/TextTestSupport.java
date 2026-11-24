package org.telegram.relay.mattermost.text;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Expectation helpers shared by the text tests (port of the iOS MarkdownTests helpers). */
final class TextTestSupport {
    private TextTestSupport() {
    }

    /** An expected entity, at an explicit UTF-16 range or over the n-th occurrence of a substring. */
    static final class Expected {
        final TextEntity template;
        final int start;
        final int end;
        final String substring;
        final int occurrence;

        Expected(TextEntity template, int start, int end, String substring, int occurrence) {
            this.template = template;
            this.start = start;
            this.end = end;
            this.substring = substring;
            this.occurrence = occurrence;
        }

        TextEntity resolve(String text) {
            if (substring == null) {
                return template.withRange(start, end - start);
            }
            int searchFrom = 0;
            int found = -1;
            for (int k = 0; k <= occurrence; k++) {
                found = text.indexOf(substring, searchFrom);
                if (found < 0) {
                    return null;
                }
                searchFrom = found + Math.max(1, substring.length());
            }
            return template.withRange(found, substring.length());
        }

        @Override
        public String toString() {
            return template + (substring != null ? " on " + substring : " at " + start + ".." + end);
        }
    }

    static Expected on(TextEntity template, String substring) {
        return new Expected(template, 0, 0, substring, 0);
    }

    static Expected on(TextEntity template, String substring, int occurrence) {
        return new Expected(template, 0, 0, substring, occurrence);
    }

    static Expected at(TextEntity template, int start, int end) {
        return new Expected(template, start, end, null, 0);
    }

    static final class Case {
        final String markdown;
        final String text;
        final Expected[] entities;

        Case(String markdown, String text, Expected... entities) {
            this.markdown = markdown;
            this.text = text;
            this.entities = entities;
        }
    }

    static Case c(String markdown, String text, Expected... entities) {
        return new Case(markdown, text, entities);
    }

    static void check(Case... cases) {
        check(null, cases);
    }

    static void check(MarkdownToEntities.Options options, Case... cases) {
        for (Case testCase : cases) {
            FormattedText result = options == null ? MarkdownToEntities.convert(testCase.markdown) : MarkdownToEntities.convert(testCase.markdown, options);
            String label = "markdown: " + quote(testCase.markdown);
            assertEquals(label, testCase.text, result.getText());
            List<TextEntity> expected = new ArrayList<TextEntity>();
            for (Expected entity : testCase.entities) {
                TextEntity resolved = entity.resolve(testCase.text);
                if (resolved == null) {
                    fail("bad expectation " + entity + " for " + label);
                }
                expected.add(resolved);
            }
            assertEquals(label, TextEntity.sorted(expected), result.getEntities());
            assertValid(testCase.markdown, result);
        }
    }

    /** Ranges in bounds, non-empty, sorted, and not splitting a surrogate pair. */
    static void assertValid(String input, FormattedText result) {
        String text = result.getText();
        int count = text.length();
        for (TextEntity entity : result.getEntities()) {
            String label = quote(input) + " -> " + result;
            assertTrue(label, entity.getOffset() >= 0 && entity.getEnd() <= count && entity.getLength() > 0);
            assertTrue("splits a surrogate pair: " + label, entity.getOffset() == 0 || !Character.isLowSurrogate(text.charAt(entity.getOffset())));
            assertTrue("splits a surrogate pair: " + label, entity.getEnd() == count || !Character.isLowSurrogate(text.charAt(entity.getEnd())));
        }
        assertEquals(TextEntity.sorted(result.getEntities()), result.getEntities());
    }

    static String quote(String s) {
        StringBuilder builder = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\n':
                    builder.append("\\n");
                    break;
                case '\r':
                    builder.append("\\r");
                    break;
                case '\t':
                    builder.append("\\t");
                    break;
                case '"':
                case '\\':
                    builder.append('\\').append(c);
                    break;
                default:
                    builder.append(c);
            }
        }
        return builder.append('"').toString();
    }

    // MARK: Entity templates

    static TextEntity bold() {
        return TextEntity.of(TextEntity.Type.BOLD, 0, 0);
    }

    static TextEntity italic() {
        return TextEntity.of(TextEntity.Type.ITALIC, 0, 0);
    }

    static TextEntity strike() {
        return TextEntity.of(TextEntity.Type.STRIKETHROUGH, 0, 0);
    }

    static TextEntity underline() {
        return TextEntity.of(TextEntity.Type.UNDERLINE, 0, 0);
    }

    static TextEntity code() {
        return TextEntity.of(TextEntity.Type.CODE, 0, 0);
    }

    static TextEntity pre(String language) {
        return TextEntity.pre(0, 0, language);
    }

    static TextEntity textUrl(String url) {
        return TextEntity.textUrl(0, 0, url);
    }

    static TextEntity url() {
        return TextEntity.of(TextEntity.Type.URL, 0, 0);
    }

    static TextEntity email() {
        return TextEntity.of(TextEntity.Type.EMAIL, 0, 0);
    }

    static TextEntity mention(String username) {
        return TextEntity.mention(0, 0, username);
    }

    static TextEntity channel(String name) {
        return TextEntity.channelMention(0, 0, name);
    }

    static TextEntity special(String kind) {
        return TextEntity.specialMention(0, 0, kind);
    }

    static TextEntity quote() {
        return TextEntity.of(TextEntity.Type.BLOCKQUOTE, 0, 0);
    }

    static TextEntity hashtag() {
        return TextEntity.of(TextEntity.Type.HASHTAG, 0, 0);
    }

    static TextEntity customEmoji(String name) {
        return TextEntity.customEmoji(0, 0, name);
    }

    static TextEntity heading(int level) {
        return TextEntity.heading(0, 0, level);
    }

    /** SplitMix64, as in the iOS tests. */
    static final class SeededGenerator {
        long state;

        SeededGenerator(long seed) {
            state = seed;
        }

        long next() {
            state += 0x9E3779B97F4A7C15L;
            long z = state;
            z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
            z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
            return z ^ (z >>> 31);
        }

        int random(int bound) {
            return (int) Long.remainderUnsigned(next(), bound);
        }
    }
}
