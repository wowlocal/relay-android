package org.telegram.relay.mattermost.text;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.telegram.relay.mattermost.text.TextTestSupport.at;
import static org.telegram.relay.mattermost.text.TextTestSupport.bold;
import static org.telegram.relay.mattermost.text.TextTestSupport.c;
import static org.telegram.relay.mattermost.text.TextTestSupport.channel;
import static org.telegram.relay.mattermost.text.TextTestSupport.check;
import static org.telegram.relay.mattermost.text.TextTestSupport.code;
import static org.telegram.relay.mattermost.text.TextTestSupport.customEmoji;
import static org.telegram.relay.mattermost.text.TextTestSupport.email;
import static org.telegram.relay.mattermost.text.TextTestSupport.hashtag;
import static org.telegram.relay.mattermost.text.TextTestSupport.heading;
import static org.telegram.relay.mattermost.text.TextTestSupport.italic;
import static org.telegram.relay.mattermost.text.TextTestSupport.mention;
import static org.telegram.relay.mattermost.text.TextTestSupport.on;
import static org.telegram.relay.mattermost.text.TextTestSupport.pre;
import static org.telegram.relay.mattermost.text.TextTestSupport.quote;
import static org.telegram.relay.mattermost.text.TextTestSupport.special;
import static org.telegram.relay.mattermost.text.TextTestSupport.strike;
import static org.telegram.relay.mattermost.text.TextTestSupport.textUrl;
import static org.telegram.relay.mattermost.text.TextTestSupport.url;

/** Port of the iOS {@code MarkdownTests} (parse direction), plus the context options. */
public class MarkdownToEntitiesTest {

    // MARK: Inline formatting

    @Test
    public void emphasis() {
        check(
            c("**bold**", "bold", on(bold(), "bold")),
            c("__bold__", "bold", on(bold(), "bold")),
            c("*italic*", "italic", on(italic(), "italic")),
            c("_italic_", "italic", on(italic(), "italic")),
            c("~~strike~~", "strike", on(strike(), "strike")),
            c("***both***", "both", on(bold(), "both"), on(italic(), "both")),
            c("*italic **bold** italic*", "italic bold italic", on(italic(), "italic bold italic"), on(bold(), "bold")),
            c("**bold *italic***", "bold italic", on(bold(), "bold italic"), on(italic(), "italic")),
            c("x ~~a **b** c~~ y", "x a b c y", on(strike(), "a b c"), on(bold(), "b")),
            c("**bold**text and 2*3*4", "boldtext and 234", on(bold(), "bold"), on(italic(), "3")),
            c("**bold\nacross lines**", "bold\nacross lines", on(bold(), "bold\nacross lines")),
            c("file_name.txt *star*", "file_name.txt star", on(italic(), "star"))
        );
    }

    @Test
    public void intrawordUnderscores() {
        check(
            c("snake_case_name", "snake_case_name"),
            c("snake_case_name and __init__", "snake_case_name and init", on(bold(), "init")),
            c("call my_func_name_here() now", "call my_func_name_here() now"),
            c("_emphasis_ but not_this_one", "emphasis but not_this_one", on(italic(), "emphasis"))
        );
    }

    @Test
    public void unclosedAndStrayMarkers() {
        check(
            c("unclosed **bold", "unclosed **bold"),
            c("**", "**"),
            c("*", "*"),
            c("** not bold **", "** not bold **"),
            c("a * b * c", "a * b * c"),
            c("~~ not strike ~~", "~~ not strike ~~"),
            c("x ~~~tilde~~~ y", "x ~~~tilde~~~ y"),
            c("-", "-"),
            c("unclosed `code", "unclosed `code"),
            c("[not a link]", "[not a link]"),
            c("[text](unclosed", "[text](unclosed")
        );
    }

    @Test
    public void codeSpansAndBlocks() {
        check(
            c("`code **not bold**`", "code **not bold**", on(code(), "code **not bold**")),
            c("`` a`b ``", "a`b", on(code(), "a`b")),
            c("`@nobody :smile: #nohash https://x.com`", "@nobody :smile: #nohash https://x.com", on(code(), "@nobody :smile: #nohash https://x.com")),
            c("```swift\nlet x = 1\n```", "let x = 1", on(pre("swift"), "let x = 1")),
            c("~~~\ntilde code\n~~~", "tilde code", on(pre(null), "tilde code")),
            c("```js title\nfoo()\n```", "foo()", on(pre("js"), "foo()")),
            c("```\n**not bold** _x_ @nobody :smile:\n```", "**not bold** _x_ @nobody :smile:", on(pre(null), "**not bold** _x_ @nobody :smile:")),
            c("```\nunclosed fence\nstill code", "unclosed fence\nstill code", on(pre(null), "unclosed fence\nstill code")),
            c("    indented code\n    more", "indented code\nmore", on(pre(null), "indented code\nmore")),
            c("text\n```\ncode\n```\nafter", "text\ncode\nafter", on(pre(null), "code")),
            c("```\n  keep\n    indentation\n```", "  keep\n    indentation", on(pre(null), "  keep\n    indentation"))
        );
    }

    // MARK: Links

    @Test
    public void links() {
        check(
            c("[text](https://example.com)", "text", on(textUrl("https://example.com"), "text")),
            c("[**bold link**](https://example.com)", "bold link", on(textUrl("https://example.com"), "bold link"), on(bold(), "bold link")),
            c("**bold [link](https://x.com) more**", "bold link more", on(bold(), "bold link more"), on(textUrl("https://x.com"), "link")),
            c("[a](http://x.com \"title\")", "a", on(textUrl("http://x.com"), "a")),
            c("[w](https://en.wikipedia.org/wiki/Foo_(bar))", "w", on(textUrl("https://en.wikipedia.org/wiki/Foo_(bar)"), "w")),
            c("[x](<https://x.com/a b>)", "x", on(textUrl("https://x.com/a b"), "x")),
            c("[](https://empty.com)", "https://empty.com", on(textUrl("https://empty.com"), "https://empty.com")),
            c("[https://a.com](https://b.com)", "https://a.com", on(textUrl("https://b.com"), "https://a.com")),
            c("[@user #tag](https://b.com)", "@user #tag", on(textUrl("https://b.com"), "@user #tag")),
            c("![alt text](https://x.com/img.png)", "alt text", on(textUrl("https://x.com/img.png"), "alt text")),
            c("[x](javascript:alert(1))", "x"),
            c("[outer [inner](https://in.com)](https://out.com)", "[outer inner](https://out.com)", on(textUrl("https://in.com"), "inner"), on(url(), "https://out.com"))
        );
    }

    @Test
    public void autolinks() {
        check(
            c("<https://auto.link> and <me@x.com>", "https://auto.link and me@x.com", on(url(), "https://auto.link"), on(email(), "me@x.com")),
            c("see https://example.com/a_b_c?x=1. ok", "see https://example.com/a_b_c?x=1. ok", on(url(), "https://example.com/a_b_c?x=1")),
            c("go to https://x.com/a_b_c/*d*/e now", "go to https://x.com/a_b_c/*d*/e now", on(url(), "https://x.com/a_b_c/*d*/e")),
            c("**https://x.com/a_b**", "https://x.com/a_b", on(bold(), "https://x.com/a_b"), on(url(), "https://x.com/a_b")),
            c("_https://x.com/path_", "https://x.com/path", on(italic(), "https://x.com/path"), on(url(), "https://x.com/path")),
            c("visit www.example.com, now", "visit www.example.com, now", on(url(), "www.example.com")),
            c("(https://x.com/path)", "(https://x.com/path)", on(url(), "https://x.com/path")),
            c("http://localhost:8065/team/pl/abc", "http://localhost:8065/team/pl/abc", on(url(), "http://localhost:8065/team/pl/abc")),
            c("mail me at foo.bar@example.com.", "mail me at foo.bar@example.com.", on(email(), "foo.bar@example.com")),
            c("first_last@example.co.uk", "first_last@example.co.uk", on(email(), "first_last@example.co.uk")),
            c("_foo@bar.com_", "foo@bar.com", on(italic(), "foo@bar.com"), on(email(), "foo@bar.com")),
            c("foo@bar is not an email", "foo@bar is not an email"),
            c("www.nodot and http://", "www.nodot and http://"),
            // URLs with underscores, asterisks and tildes stay whole.
            c("https://x.com/~user/a__b__c", "https://x.com/~user/a__b__c", on(url(), "https://x.com/~user/a__b__c")),
            c("HTTPS://X.COM/Path", "HTTPS://X.COM/Path", on(url(), "HTTPS://X.COM/Path"))
        );
    }

    // MARK: Mattermost tokens

    @Test
    public void mentions() {
        check(
            c("@john", "@john", on(mention("john"), "@john")),
            c("hi @john.doe, and @jane_doe. ok", "hi @john.doe, and @jane_doe. ok", on(mention("john.doe"), "@john.doe"), on(mention("jane_doe"), "@jane_doe")),
            c("@John-Smith- said", "@John-Smith- said", on(mention("john-smith"), "@John-Smith")),
            c("@user's thing", "@user's thing", on(mention("user"), "@user")),
            c("(@user)", "(@user)", on(mention("user"), "@user")),
            c("@all @Here @channel", "@all @Here @channel", on(special("all"), "@all"), on(special("here"), "@Here"), on(special("channel"), "@channel")),
            c("@channels is a user", "@channels is a user", on(mention("channels"), "@channels")),
            c("_@user_", "@user", on(italic(), "@user"), on(mention("user"), "@user")),
            c("**@user**", "@user", on(bold(), "@user"), on(mention("user"), "@user")),
            c("@123 and @_x", "@123 and @_x"),
            c("email@example.com", "email@example.com", on(email(), "email@example.com"))
        );
    }

    @Test
    public void channelMentions() {
        check(
            c("join ~town-square please", "join ~town-square please", on(channel("town-square"), "~town-square")),
            c("~~strike~~ vs ~off-topic.", "strike vs ~off-topic.", on(strike(), "strike"), on(channel("off-topic"), "~off-topic")),
            c("~Dev_Ops", "~Dev_Ops", on(channel("dev_ops"), "~Dev_Ops")),
            c("a~b and ~ alone", "a~b and ~ alone")
        );
    }

    @Test
    public void hashtags() {
        check(
            c("#hashtag #ab #a1b2 #привет #123", "#hashtag #ab #a1b2 #привет #123", on(hashtag(), "#hashtag"), on(hashtag(), "#a1b2"), on(hashtag(), "#привет")),
            c("C# and issue#123 and &#39;", "C# and issue#123 and &#39;"),
            c("#release-1.2.", "#release-1.2.", on(hashtag(), "#release-1.2")),
            c("https://x.com/#anchor", "https://x.com/#anchor", on(url(), "https://x.com/#anchor"))
        );
    }

    @Test
    public void emoji() {
        check(
            c(":smile: :+1: :custom_thing: 10:30:45", "😄 👍 :custom_thing: 10:30:45", at(customEmoji("custom_thing"), 6, 20)),
            c(":heart: and :white_check_mark:", "❤\uFE0F and ✅"),
            c(":thumbsup::-1:", "👍👎"),
            c("a:smile: and :smile:b", "a:smile: and :smile:b"),
            c("`:smile:`", ":smile:", on(code(), ":smile:")),
            c(":100: :1234: :12345:", "💯 🔢 :12345:"),
            c(":floppy_disk: :thinking: :thinking_face:", "💾 🤔 🤔"),
            c(":+1_light_skin_tone: :wave_medium_dark_skin_tone:", "👍🏻 👋🏾"),
            c(":flag-de: :man-woman-boy:", "🇩🇪 👨\u200D👩\u200D👦"),
            c(":Smile:", "😄"),
            c("**:fire: hot**", "🔥 hot", on(bold(), "🔥 hot"))
        );
        assertTrue(EmojiTable.allNames().size() >= 3000);
        assertTrue(EmojiTable.allEmoji().size() >= 1800);
        for (String name : EmojiTable.allNames()) {
            String emoji = EmojiTable.unicode(name);
            assertNotNull(name, emoji);
            assertFalse(name, emoji.isEmpty());
            boolean allASCII = true;
            for (int k = 0; k < emoji.length(); k++) {
                if (emoji.charAt(k) >= 0x80) {
                    allASCII = false;
                }
            }
            assertFalse(name, allASCII);
        }
    }

    @Test
    public void emojiNamesForUnicode() {
        assertEquals("+1", EmojiTable.name("👍"));
        assertEquals("heart", EmojiTable.name("❤\uFE0F"));
        assertEquals("heart", EmojiTable.name("❤"));
        // The names Mattermost clients send: `thinking_face`, not the alias.
        assertEquals("thinking_face", EmojiTable.name("🤔"));
        assertEquals("floppy_disk", EmojiTable.name("💾"));
        assertEquals("laughing", EmojiTable.name("😆"));
        assertEquals("+1_medium_skin_tone", EmojiTable.name("👍🏽"));
        for (EmojiTable.Entry entry : EmojiTable.allEmoji()) {
            assertFalse("skin tones resolve but are not listed", entry.emoji.equals("👍🏽"));
        }
        assertNull(EmojiTable.name("x"));
        Set<String> distinct = new HashSet<String>();
        for (EmojiTable.Entry entry : EmojiTable.allEmoji()) {
            assertEquals(entry.name, EmojiTable.name(entry.emoji));
            assertEquals(entry.emoji, EmojiTable.unicode(entry.name));
            distinct.add(entry.emoji);
        }
        assertEquals(EmojiTable.allEmoji().size(), distinct.size());
    }

    @Test
    public void emojiSearch() {
        List<EmojiTable.Entry> results = EmojiTable.search("smile");
        assertEquals("smile", results.get(0).name);
        assertEquals("😄", results.get(0).emoji);
        List<String> names = new ArrayList<String>();
        for (EmojiTable.Entry entry : results) {
            names.add(entry.name);
        }
        // `smiley` starts with the query; `sweat_smile` only contains it.
        assertTrue(names.indexOf("smiley") >= 0 && names.indexOf("sweat_smile") >= 0);
        assertTrue(names.indexOf("smiley") < names.indexOf("sweat_smile"));

        List<EmojiTable.Entry> thumbs = EmojiTable.search("thumbs");
        Set<String> emoji = new HashSet<String>();
        boolean hasThumbsUp = false;
        for (EmojiTable.Entry entry : thumbs) {
            emoji.add(entry.emoji);
            assertFalse(entry.name.contains("skin_tone"));
            if (entry.name.equals("thumbsup") && entry.emoji.equals("👍")) {
                hasThumbsUp = true;
            }
        }
        assertEquals(thumbs.size(), emoji.size());
        assertTrue(hasThumbsUp);

        assertEquals(5, EmojiTable.search("a", 5).size());
        assertEquals("smile", EmojiTable.search("SMILE").get(0).name);
        assertTrue(EmojiTable.search("").isEmpty());
        assertTrue(EmojiTable.search("zzzzqqq").isEmpty());
    }

    // MARK: Blocks

    @Test
    public void blockQuotes() {
        check(
            c("> quoted\n> lines", "quoted\nlines", on(quote(), "quoted\nlines")),
            c("> **bold** quote", "bold quote", on(quote(), "bold quote"), on(bold(), "bold")),
            c("> outer\n>> inner", "outer\ninner", on(quote(), "outer\ninner")),
            c("before\n> quote\n\nafter", "before\nquote\n\nafter", on(quote(), "quote")),
            c("> quote\ncontinued lazily", "quote\ncontinued lazily", on(quote(), "quote\ncontinued lazily")),
            c(">no space", "no space", on(quote(), "no space"))
        );
    }

    @Test
    public void escaping() {
        check(
            c("\\*not italic\\*", "*not italic*"),
            c("\\@user \\#tag \\:smile:", "@user #tag :smile:"),
            c("\\[not\\](a link)", "[not](a link)"),
            c("a \\\\ b", "a \\ b"),
            c("\\a stays", "\\a stays"),
            c("\\# not a heading", "# not a heading"),
            c("\\_x\\_ and \\~\\~y\\~\\~ and \\`z\\`", "_x_ and ~~y~~ and `z`"),
            // One escaped tilde leaves "~y": a channel mention.
            c("\\~~y", "~~y", on(channel("y"), "~y")),
            c("1\\. not a list", "1. not a list"),
            c("\\- not a list", "- not a list"),
            c("\\> not a quote", "> not a quote")
        );
    }

    @Test
    public void headings() {
        check(
            c("# Heading\ntext", "Heading\ntext", on(bold(), "Heading"), on(heading(1), "Heading")),
            c("## **Big** title ##", "Big title", on(bold(), "Big title"), on(heading(2), "Big title")),
            c("### Heading with [link](https://x.com)", "Heading with link", on(bold(), "Heading with link"), on(heading(3), "Heading with link"), on(textUrl("https://x.com"), "link")),
            c("###### Six", "Six", on(bold(), "Six"), on(heading(6), "Six")),
            c("#hashtag at start", "#hashtag at start", on(hashtag(), "#hashtag")),
            c("Title\n===\nbody", "Title\nbody", on(bold(), "Title"), on(heading(1), "Title")),
            c("Subtitle\n---", "Subtitle", on(bold(), "Subtitle"), on(heading(2), "Subtitle")),
            c("####### seven", "####### seven"),
            c("> # Quoted", "Quoted", on(quote(), "Quoted"), on(bold(), "Quoted"), on(heading(1), "Quoted"))
        );
    }

    @Test
    public void lists() {
        // Continuation lines and nested blocks are indented by whitespace about as wide as the prefix:
        // "• " → en space + space, "1. " → en space (narrow digit) + punctuation space + space.
        String bulletIndent = "\u2002 ";
        String oneIndent = "\u2002\u2008 ";
        check(
            c("- one\n- two\n  - nested\n- three", "• one\n• two\n" + bulletIndent + "◦ nested\n• three"),
            c("* **bold** item\n+ other", "• bold item\n• other", on(bold(), "bold")),
            c("1. a\n2. b\n3. c", "1. a\n2. b\n3. c"),
            c("3. a\n4. b", "3. a\n4. b"),
            c("1) paren\n1) again", "1. paren\n2. again"),
            c("- [ ] todo\n- [x] done", "☐ todo\n☑ done"),
            c("- item with\n  continuation", "• item with\n" + bulletIndent + "continuation"),
            c("Steps:\n1. first\n2. second", "Steps:\n1. first\n2. second"),
            c("1. one\n   - sub\n2. two", "1. one\n" + oneIndent + "◦ sub\n2. two"),
            c("- a\n  - b\n    - c\n      - d", "• a\n" + bulletIndent + "◦ b\n" + bulletIndent + bulletIndent + "‣ c\n" + bulletIndent + bulletIndent + bulletIndent + "‣ d"),
            c("10. ten\n    more", "10. ten\n\u2002\u2007\u2008 more"),
            c("- [ ] task\n  details", "☐ task\n\u2002\u2009\u2009\u2009 details"),
            c("- `code` item", "• code item", on(code(), "code")),
            c("- **x**\n  **y**", "• x\n" + bulletIndent + "y", at(bold(), 2, 3), at(bold(), 6, 7)),
            c("-not a list", "-not a list"),
            c("2020. A good year", "2020. A good year")
        );
    }

    @Test
    public void tables() {
        check(
            c("| a | b |\n|---|:-:|\n| 1 | 22 |\n| 333 | 4 |", "a   | b\n----+---\n1   | 22\n333 | 4", on(pre("table"), "a   | b\n----+---\n1   | 22\n333 | 4")),
            c("| **a** | `b` |\n|--|--|\n| c | d |", "a | b\n--+--\nc | d", on(pre("table"), "a | b\n--+--\nc | d")),
            c("Name | Value\n--- | ---\nx | 1\n\nafter", "Name | Value\n-----+------\nx    | 1\n\nafter", on(pre("table"), "Name | Value\n-----+------\nx    | 1")),
            c("a | b\nnot a table", "a | b\nnot a table"),
            // Alignment from the delimiter row.
            c("| Item | Qty |\n|:--|--:|\n| tea | 2 |\n| coffee | 10 |", "Item   | Qty\n-------+----\ntea    |   2\ncoffee |  10", on(pre("table"), "Item   | Qty\n-------+----\ntea    |   2\ncoffee |  10")),
            c("| x | mid | y |\n|---|:---:|---|\n| 1 | a | 2 |", "x | mid | y\n--+-----+--\n1 |  a  | 2", on(pre("table"), "x | mid | y\n--+-----+--\n1 |  a  | 2")),
            // Escaped pipes stay in the cell.
            c("| a \\| b | c |\n|---|---|\n| 1 | 2 |", "a | b | c\n------+--\n1     | 2", on(pre("table"), "a | b | c\n------+--\n1     | 2"))
        );
    }

    @Test
    public void wideTablesBecomeRecords() {
        String markdown = "| Service | Owner | Status | Notes |\n"
            + "|---|---|---|---|\n"
            + "| **api** | @alice | :white_check_mark: up | see [runbook](https://x.com/rb) |\n"
            + "| worker | bob | down |  |";
        check(
            c(markdown, "api\nOwner: @alice\nStatus: ✅ up\nNotes: see runbook\n\nworker\nOwner: bob\nStatus: down",
                on(bold(), "api"),
                on(italic(), "Owner:"), on(mention("alice"), "@alice"),
                on(italic(), "Status:"),
                on(italic(), "Notes:"), on(textUrl("https://x.com/rb"), "runbook"),
                on(bold(), "worker"),
                on(italic(), "Owner:", 1),
                on(italic(), "Status:", 1)),
            // A wide table with an empty first cell, and one with a header only.
            c("| Key | A rather long column header |\n|---|---|\n|  | value |", "A rather long column header: value", on(italic(), "A rather long column header:")),
            c("| First wide header | Second wide header |\n|---|---|", "First wide header\nSecond wide header", on(bold(), "First wide header"), on(bold(), "Second wide header"))
        );
    }

    @Test
    public void codeBlockLanguages() {
        check(
            c("```golang\nfunc main() {}\n```", "func main() {}", on(pre("go"), "func main() {}")),
            c("```C++\nint x;\n```", "int x;", on(pre("cpp"), "int x;")),
            c("```Swift\nlet x = 1\n```", "let x = 1", on(pre("swift"), "let x = 1")),
            c("```zsh\necho hi\n```", "echo hi", on(pre("bash"), "echo hi")),
            c("```text\nplain\n```", "plain", on(pre("text"), "plain"))
        );
        assertEquals("js", CodeLanguage.normalize("js"));
        assertEquals("sql", CodeLanguage.normalize(" PgSQL "));
        assertNull(CodeLanguage.normalize(" "));
        assertNull(CodeLanguage.normalize(null));
    }

    @Test
    public void emoticons() {
        check(
            c("Nice :) thanks", "Nice 🙂 thanks"),
            c(":-) ;) ;-) :D :-d :P :o :-O :] :( :/ :s :| :$ :-x :@ x-D <3 </3 :'(", "🙂 😉 😉 😄 😄 😛 😮 😱 😏 🙁 😕 😖 😐 😳 😷 😡 😝 ❤\uFE0F 💔 😢"),
            c("**great :)**", "great 🙂", on(bold(), "great 🙂")),
            // Not between word characters, not in URLs, code, times or emoji names.
            c("http://example.com/a:)b 10:30 Note:D a:(b", "http://example.com/a:)b 10:30 Note:D a:(b", on(url(), "http://example.com/a:)b")),
            c("`:)` and :smile: and :Done", ":) and 😄 and :Done", on(code(), ":)")),
            c("(see :/path) :pizza:", "(see :/path) 🍕")
        );
        FormattedText off = MarkdownToEntities.convert("hi :) <3", new MarkdownToEntities.Options().setEmoticons(false));
        assertEquals("hi :) <3", off.getText());
    }

    @Test
    public void inlineLatex() {
        MarkdownToEntities.Options options = new MarkdownToEntities.Options().setInlineLatex(true);
        assertEquals("Energy E = mc^2 here", MarkdownToEntities.convert("Energy $E = mc^2$ here", options).getText());
        assertEquals(Collections.singletonList(TextEntity.of(TextEntity.Type.CODE, 7, 8)), MarkdownToEntities.convert("Energy $E = mc^2$ here", options).getEntities());
        // Prices and spaced dollars stay text.
        assertEquals("costs $5 and $10", MarkdownToEntities.convert("costs $5 and $10", options).getText());
        assertEquals("$ a $", MarkdownToEntities.convert("$ a $", options).getText());
        assertEquals("US$5x$", MarkdownToEntities.convert("US$5x$", options).getText());
        assertEquals("a $b\nc$", MarkdownToEntities.convert("a $b\nc$", options).getText());
        assertEquals(Collections.singletonList(TextEntity.of(TextEntity.Type.CODE, 0, 3)), MarkdownToEntities.convert("`$x$`", options).getEntities());
        // Off unless the server enables it.
        assertEquals("$x$", MarkdownToEntities.convert("$x$").getText());
    }

    @Test
    public void horizontalRules() {
        check(
            c("above\n\n---\n\nbelow", "above\n\n———\n\nbelow"),
            c("***", "———"),
            c("- - -", "———"),
            c("___", "———")
        );
    }

    @Test
    public void whitespaceAndLineBreaks() {
        check(
            c("Line one\nLine two", "Line one\nLine two"),
            c("a\r\nb\r\n\r\n\r\nc", "a\nb\n\nc"),
            c("a\rb", "a\nb"),
            c("  hi  ", "hi"),
            c("a  \nb", "a\nb"),
            c("a\\\nb", "a\nb"),
            c("\n\nleading blank lines", "leading blank lines"),
            c("tab\tinside", "tab\tinside"),
            c("", "")
        );
        assertEquals("", MarkdownToEntities.convert(null).getText());
    }

    // MARK: UTF-16 offsets

    @Test
    public void utf16OffsetsWithNonBMPCharacters() {
        check(
            // 👍🏽 is two code points of two UTF-16 units each.
            c("👍🏽 **hi**", "👍🏽 hi", at(bold(), 5, 7)),
            c("Привет, **мир**!", "Привет, мир!", at(bold(), 8, 11)),
            c("𝒳 *a* 😀 `b`", "𝒳 a 😀 b", at(italic(), 3, 4), at(code(), 8, 9)),
            // A ZWJ family is 8 UTF-16 units.
            c("**bold** 👨\u200D👩\u200D👧 [link](https://x.com)", "bold 👨\u200D👩\u200D👧 link", at(bold(), 0, 4), at(textUrl("https://x.com"), 14, 18)),
            c(":smile: @bob", "😄 @bob", at(mention("bob"), 3, 7)),
            c("> 🇺🇦 **так**", "🇺🇦 так", at(quote(), 0, 8), at(bold(), 5, 8)),
            c("- 😀 item\n- **b**", "• 😀 item\n• b", at(bold(), 12, 13))
        );
        // Cross-check every offset with substring.
        FormattedText result = MarkdownToEntities.convert("🎉 **Привет** _мир_ 👍🏽 [ссылка](https://x.com) @user");
        List<String> covered = new ArrayList<String>();
        for (TextEntity entity : result.getEntities()) {
            covered.add(result.substring(entity));
        }
        assertEquals(Arrays.asList("Привет", "мир", "ссылка", "@user"), covered);
    }

    // MARK: Context

    @Test
    public void knownUsersGetIdsAndKeepTrailingPunctuation() {
        Map<String, String> users = new HashMap<String, String>();
        users.put("alice", "uid-alice");
        users.put("Bob.", "uid-bob-dot");
        MarkdownToEntities.Options options = new MarkdownToEntities.Options().setUsers(users);
        FormattedText result = MarkdownToEntities.convert("hi @Alice, @bob. and @carol.", options);
        assertEquals(Arrays.asList(
            TextEntity.mention(3, 6, "alice").withUserId("uid-alice"),
            TextEntity.mention(11, 5, "bob.").withUserId("uid-bob-dot"),
            TextEntity.mention(21, 6, "carol")
        ), result.getEntities());
    }

    @Test
    public void knownChannelsGetIdsAndUnknownOnesStayText() {
        Map<String, String> channels = new HashMap<String, String>();
        channels.put("town-square", "cid-ts");
        MarkdownToEntities.Options options = new MarkdownToEntities.Options().setChannels(channels);
        FormattedText result = MarkdownToEntities.convert("see ~town-square and ~nowhere_x_ now", options);
        assertEquals("see ~town-square and ~nowhere_x_ now", result.getText());
        assertEquals(Collections.singletonList(TextEntity.channelMention(4, 12, "town-square").withTargetId("cid-ts")), result.getEntities());
    }

    @Test
    public void customEmojiIds() {
        Map<String, String> emoji = new HashMap<String, String>();
        emoji.put("party_parrot", "eid-1");
        MarkdownToEntities.Options options = new MarkdownToEntities.Options().setCustomEmoji(emoji);
        FormattedText result = MarkdownToEntities.convert(":party_parrot: :unknown_thing: :smile:", options);
        assertEquals(":party_parrot: :unknown_thing: 😄", result.getText());
        assertEquals(Arrays.asList(
            TextEntity.customEmoji(0, 14, "party_parrot").withTargetId("eid-1"),
            TextEntity.customEmoji(15, 15, "unknown_thing")
        ), result.getEntities());
    }

    @Test
    public void highlightsOwnMentionsAndSpecialMentions() {
        MarkdownToEntities.Options options = new MarkdownToEntities.Options().setCurrentUsername("Alice");
        FormattedText result = MarkdownToEntities.convert("@alice @bob @here", options);
        assertEquals(Arrays.asList(
            TextEntity.mention(0, 6, "alice").withHighlight(true),
            TextEntity.mention(7, 4, "bob"),
            TextEntity.specialMention(12, 5, "here").withHighlight(true)
        ), result.getEntities());
        assertTrue(result.hasHighlight());
        assertFalse(MarkdownToEntities.convert("@bob", options).hasHighlight());
        assertFalse(MarkdownToEntities.convert("`@alice`", options).hasHighlight());
        // Without a current user nothing is highlighted.
        assertFalse(MarkdownToEntities.convert("@alice @all").hasHighlight());
        assertTrue(MarkdownToEntities.mentions(MarkdownToEntities.convert("hey @ALICE"), "alice", false));
        assertTrue(MarkdownToEntities.mentions(MarkdownToEntities.convert("hey @all"), "alice", true));
        assertFalse(MarkdownToEntities.mentions(MarkdownToEntities.convert("hey @all"), "alice", false));
    }

    // MARK: Robustness

    @Test(timeout = 20000)
    public void pathologicalInputsStayFast() {
        String[] inputs = {
            repeat("**a ", 3000),
            repeat("`a ``b ", 2000),
            repeat("[", 3000) + repeat("]", 3000),
            repeat("`` x ", 3000) + "`",
            repeat(">", 5000) + " deep",
            repeat("- ", 2000) + "x",
            repeat("_a ", 3000),
            repeat("~~a ", 3000),
            repeat("[a](", 2000),
            repeat("| a ", 500) + "\n" + repeat("|---", 500),
        };
        long start = System.nanoTime();
        for (String input : inputs) {
            FormattedText result = MarkdownToEntities.convert(input);
            assertFalse(result.getText().isEmpty());
            TextTestSupport.assertValid(input.substring(0, 20), result);
        }
        assertTrue("took " + (System.nanoTime() - start) / 1000000 + " ms", System.nanoTime() - start < 10000L * 1000000L);
        // Nested quotes below the nesting cap flatten to one block quote.
        String deepQuotes = repeat(">", 20) + " deep";
        assertEquals(new FormattedText("deep", Collections.singletonList(TextEntity.of(TextEntity.Type.BLOCKQUOTE, 0, 4))), MarkdownToEntities.convert(deepQuotes));
    }

    @Test
    public void entitiesAreValidForRandomInput() {
        TextTestSupport.SeededGenerator rng = new TextTestSupport.SeededGenerator(42);
        String[] alphabet = {"a", "b", " ", "_", "*", "~", "`", "[", "]", "(", ")", "<", ">", "@", "#", ":", "!", "\\", "\n", ">", "-", "|", "1", ".", "😀", "й", "h", "t", "p", "s", "/", "$", "\t", "\r", "x", "=", "+", "'"};
        for (int round = 0; round < 5000; round++) {
            int length = rng.random(60);
            StringBuilder input = new StringBuilder();
            for (int k = 0; k < length; k++) {
                input.append(alphabet[rng.random(alphabet.length)]);
            }
            String markdown = input.toString();
            TextTestSupport.assertValid(markdown, MarkdownToEntities.convert(markdown));
            TextTestSupport.assertValid(markdown, MarkdownToEntities.convert(markdown, new MarkdownToEntities.Options().setInlineLatex(true).setCurrentUsername("a")));
        }
    }

    static String repeat(String s, int count) {
        StringBuilder builder = new StringBuilder(s.length() * count);
        for (int k = 0; k < count; k++) {
            builder.append(s);
        }
        return builder.toString();
    }
}
