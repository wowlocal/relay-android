package org.telegram.relay.mattermost.live;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import org.junit.After;
import org.junit.Test;
import org.telegram.relay.mattermost.MattermostClient;
import org.telegram.relay.mattermost.MattermostException;
import org.telegram.relay.mattermost.PostsQuery;
import org.telegram.relay.mattermost.model.Channel;
import org.telegram.relay.mattermost.model.CustomEmoji;
import org.telegram.relay.mattermost.model.Post;
import org.telegram.relay.mattermost.model.PostList;
import org.telegram.relay.mattermost.model.User;
import org.telegram.relay.mattermost.text.EntitiesToMarkdown;
import org.telegram.relay.mattermost.text.FormattedText;
import org.telegram.relay.mattermost.text.MarkdownToEntities;
import org.telegram.relay.mattermost.text.TextEntity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Converts the seeded posts of the local dev server (the latest page of every channel alice is in,
 * attachment texts, channel headers): every text converts without exceptions, entity ranges are in
 * bounds and on code point boundaries, and literal markdown re-converts to a fixpoint. Skipped when
 * the server is down.
 */
public class MarkdownLiveTest {
    private MattermostClient client;

    @After
    public void tearDown() {
        LiveServer.logoutQuietly(client);
    }

    private static final EntitiesToMarkdown.Options LITERAL = new EntitiesToMarkdown.Options().setEscapeLiterals(true);

    @Test
    public void everySeededPostConverts() throws MattermostException {
        LiveServer.assumeAvailable();
        client = LiveServer.login("alice");
        User me = client.me();

        Map<String, String> channelIds = new HashMap<String, String>();
        List<Channel> channels = client.allChannels(false, 0);
        List<String> texts = new ArrayList<String>();
        for (Channel channel : channels) {
            channelIds.put(channel.name, channel.id);
            texts.add(channel.header);
            texts.add(channel.purpose);
        }
        Map<String, String> emojiIds = new HashMap<String, String>();
        for (CustomEmoji emoji : client.customEmojis(0, 200, "name")) {
            emojiIds.put(emoji.name, emoji.id);
        }
        Set<String> authorIds = new HashSet<String>();
        int posts = 0;
        for (Channel channel : channels) {
            // Every post of the channel (the firehose has about 12k), at most 100 pages.
            for (int pageIndex = 0; pageIndex < 100; pageIndex++) {
                PostList page = client.posts(channel.id, PostsQuery.page(pageIndex, 200));
                for (Post post : page.getOrderedPosts()) {
                    posts++;
                    authorIds.add(post.userId);
                    texts.add(post.message);
                    collectAttachmentTexts(post.props, texts);
                }
                if (page.size() < 200) {
                    break;
                }
            }
        }
        Map<String, String> userIds = new HashMap<String, String>();
        for (User user : client.users(new ArrayList<String>(authorIds))) {
            userIds.put(user.username, user.id);
        }
        assertTrue("no seeded posts", posts > 100);

        MarkdownToEntities.Options context = new MarkdownToEntities.Options()
            .setUsers(userIds)
            .setChannels(channelIds)
            .setCustomEmoji(emojiIds)
            .setCurrentUsername(me.username);
        MarkdownToEntities.Options latex = new MarkdownToEntities.Options().setInlineLatex(true).setEmoticons(false);

        Map<TextEntity.Type, Integer> counts = new TreeMap<TextEntity.Type, Integer>();
        int converted = 0;
        int resolvedMentions = 0;
        int highlighted = 0;
        int exact = 0;
        List<String> problems = new ArrayList<String>();
        List<String> inexact = new ArrayList<String>();
        for (String markdown : texts) {
            if (markdown == null || markdown.isEmpty()) {
                continue;
            }
            converted++;
            FormattedText withContext;
            try {
                withContext = MarkdownToEntities.convert(markdown, context);
                assertValid(markdown, withContext);
                assertValid(markdown, MarkdownToEntities.convert(markdown, latex));
            } catch (RuntimeException e) {
                throw new AssertionError("converting " + markdown, e);
            }
            for (TextEntity entity : withContext.getEntities()) {
                Integer count = counts.get(entity.getType());
                counts.put(entity.getType(), count == null ? 1 : count + 1);
                if (entity.getType() == TextEntity.Type.MENTION && entity.getUserId() != null) {
                    resolvedMentions++;
                }
            }
            if (withContext.hasHighlight()) {
                highlighted++;
            }

            // Back to markdown, both ways; literal markdown re-converts to the same text from the second round on.
            FormattedText first = MarkdownToEntities.convert(markdown);
            EntitiesToMarkdown.convert(first);
            FormattedText second = MarkdownToEntities.convert(EntitiesToMarkdown.convert(first, LITERAL));
            FormattedText third = MarkdownToEntities.convert(EntitiesToMarkdown.convert(second, LITERAL));
            if (second.getText().equals(first.getText()) && composer(second).equals(composer(first))) {
                exact++;
            } else if (inexact.size() < 5) {
                inexact.add(markdown + "\n  -> " + first + "\n  -> " + second);
            }
            if (!second.getText().equals(third.getText()) || !composer(second).equals(composer(third))) {
                problems.add(markdown + "\n  -> " + second + "\n  -> " + third);
            }
        }
        System.out.println("markdown live: " + posts + " posts, " + converted + " texts, entities " + counts
            + ", " + resolvedMentions + " mentions with user ids, " + highlighted + " highlighted for @" + me.username
            + ", " + exact + " exact literal round trips");
        for (String example : inexact) {
            System.out.println("not exact (whitespace normalization): " + example);
        }
        if (!problems.isEmpty()) {
            fail(problems.size() + " texts are not a literal fixpoint, e.g.\n" + problems.get(0));
        }
        assertTrue(counts.containsKey(TextEntity.Type.BOLD));
        assertTrue(counts.containsKey(TextEntity.Type.MENTION));
    }

    private static void collectAttachmentTexts(JsonObject props, List<String> into) {
        if (props == null || !props.has("attachments") || !props.get("attachments").isJsonArray()) {
            return;
        }
        JsonArray attachments = props.getAsJsonArray("attachments");
        for (JsonElement element : attachments) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject attachment = element.getAsJsonObject();
            for (String key : new String[] {"pretext", "text", "title", "footer"}) {
                addString(attachment, key, into);
            }
            if (attachment.has("fields") && attachment.get("fields").isJsonArray()) {
                for (JsonElement field : attachment.getAsJsonArray("fields")) {
                    if (field.isJsonObject()) {
                        addString(field.getAsJsonObject(), "title", into);
                        addString(field.getAsJsonObject(), "value", into);
                    }
                }
            }
        }
    }

    private static void addString(JsonObject object, String key, List<String> into) {
        JsonElement value = object.get(key);
        if (value != null && value.isJsonPrimitive()) {
            into.add(value.getAsString());
        }
    }

    private static List<TextEntity> composer(FormattedText formatted) {
        List<TextEntity> result = new ArrayList<TextEntity>();
        for (TextEntity entity : formatted.getEntities()) {
            switch (entity.getType()) {
                case BOLD:
                case ITALIC:
                case STRIKETHROUGH:
                case CODE:
                case PRE:
                case TEXT_URL:
                case BLOCKQUOTE:
                    result.add(entity);
                    break;
                default:
                    break;
            }
        }
        return result;
    }

    private static void assertValid(String markdown, FormattedText result) {
        String text = result.getText();
        for (TextEntity entity : result.getEntities()) {
            String label = markdown + " -> " + result;
            assertTrue(label, entity.getOffset() >= 0 && entity.getLength() > 0 && entity.getEnd() <= text.length());
            assertTrue(label, entity.getOffset() == 0 || !Character.isLowSurrogate(text.charAt(entity.getOffset())));
            assertTrue(label, entity.getEnd() == text.length() || !Character.isLowSurrogate(text.charAt(entity.getEnd())));
        }
        assertEquals(TextEntity.sorted(result.getEntities()), result.getEntities());
    }
}
