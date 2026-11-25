package org.telegram.relay.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import org.telegram.relay.RelayConfig;
import org.telegram.relay.account.RelayAccount;
import org.telegram.relay.mattermost.Json;
import org.telegram.relay.mattermost.model.Preference;
import org.telegram.relay.mattermost.text.EmojiTable;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reactions without Telegram's animated reaction set (A7c).
 *
 * <ul>
 *   <li>{@code messages.getAvailableReactions}: a fixed list of common Unicode emoji (Mattermost has no per-server
 *       reaction set). Their animation documents are {@code documentEmpty}: in Relay mode the reaction bar, the
 *       bubble and the effect overlay draw the plain emoji glyph instead (marked hooks in
 *       {@code ReactionsContainerLayout}, {@code ReactionsLayoutInBubble}, {@code ReactionsEffectOverlay}).</li>
 *   <li>{@code messages.getTopReactions} / {@code getRecentReactions}: the user's Mattermost "recent emoji"
 *       preference ({@code recent_emojis}), most used first, which the web and mobile apps share; adding a
 *       reaction bumps it like those clients do.</li>
 *   <li>A Mattermost custom emoji is a {@code reactionCustomEmoji} with a Relay emoji document ({@link RelayEmoji}).</li>
 * </ul>
 */
public final class RelayReactions {
    private static final String TAG = RelayConfig.TAG_BRIDGE;
    static final String PREF_RECENT_EMOJIS = "recent_emojis";

    /** The quick list (Mattermost names), like the web app's defaults plus Telegram's usual set. */
    static final String[] DEFAULT_NAMES = {
            "+1", "heart", "joy", "open_mouth", "cry", "pray", "tada", "fire", "eyes", "white_check_mark",
            "100", "smile", "raised_hands", "clap", "thinking_face", "heart_eyes", "rocket", "star", "sparkles",
            "sunglasses", "handshake", "muscle", "-1", "heavy_plus_sign", "slightly_smiling_face", "laughing",
            "ok_hand", "wave", "grinning", "thumbsup"
    };

    private RelayReactions() {
    }

    static void register(Map<Class<?>, RelayHandlers.Handler> map) {
        map.put(TLRPC.TL_messages_getAvailableReactions.class, (bridge, account, req) -> availableReactions());
        map.put(TLRPC.TL_messages_getTopReactions.class, (bridge, account, req) -> recentReactions(account, ((TLRPC.TL_messages_getTopReactions) req).limit));
        // Recent = Mattermost's recent emoji, then the server's custom emoji: the quick bar shows "recent" ones without
        // Telegram's Premium filter, so custom emoji are one tap away there (the full picker lists Unicode emoji first).
        map.put(TLRPC.TL_messages_getRecentReactions.class, (bridge, account, req) -> recentWithCustom(account, ((TLRPC.TL_messages_getRecentReactions) req).limit));
        map.put(TLRPC.TL_messages_clearRecentReactions.class, (bridge, account, req) -> new TLRPC.TL_boolTrue());
    }

    // MARK: - Mapping

    /** A Mattermost reaction name → Telegram reaction: Unicode emoji, or a custom-emoji document. */
    static TLRPC.Reaction tlReaction(RelayAccount account, String name) {
        String unicode = drawable(EmojiTable.unicode(name));
        if (unicode != null && !unicode.isEmpty()) {
            TLRPC.TL_reactionEmoji emoji = new TLRPC.TL_reactionEmoji();
            emoji.emoticon = unicode;
            return emoji;
        }
        TLRPC.TL_reactionCustomEmoji custom = new TLRPC.TL_reactionCustomEmoji();
        custom.document_id = RelayEmoji.of(account).customDocumentId(name);
        return custom;
    }

    /** A Telegram reaction → Mattermost emoji name, or null. */
    static String emojiName(RelayAccount account, TLRPC.Reaction reaction) {
        if (reaction instanceof TLRPC.TL_reactionCustomEmoji) {
            return RelayEmoji.of(account).nameOf(((TLRPC.TL_reactionCustomEmoji) reaction).document_id);
        }
        return RelayWrite.emojiName(reaction);
    }

    /**
     * The spelling of a Unicode emoji that Telegram's emoji sprites know: Mattermost's table has the
     * variation selector ({@code ❤️}), Telegram's reactions and sprites often not ({@code ❤}).
     */
    static String drawable(String unicode) {
        if (unicode == null || unicode.indexOf('\uFE0F') < 0) {
            return unicode;
        }
        try {
            if (org.telegram.messenger.Emoji.getEmojiDrawable(unicode) == null) {
                String plain = unicode.replace("\uFE0F", "");
                if (org.telegram.messenger.Emoji.getEmojiDrawable(plain) != null) {
                    return plain;
                }
            }
        } catch (Throwable ignore) {
        }
        return unicode;
    }

    // MARK: - Lists

    private static TLRPC.TL_messages_availableReactions availableReactions() {
        TLRPC.TL_messages_availableReactions res = new TLRPC.TL_messages_availableReactions();
        res.hash = 0x52656c61 + DEFAULT_NAMES.length; // stable: the list only changes with the app
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        for (String name : DEFAULT_NAMES) {
            String unicode = drawable(EmojiTable.unicode(name));
            if (unicode == null || !seen.add(unicode)) {
                continue;
            }
            TLRPC.TL_availableReaction r = new TLRPC.TL_availableReaction();
            r.reaction = unicode;
            r.title = name;
            r.static_icon = new TLRPC.TL_documentEmpty();
            r.appear_animation = new TLRPC.TL_documentEmpty();
            r.select_animation = new TLRPC.TL_documentEmpty();
            r.activate_animation = new TLRPC.TL_documentEmpty();
            r.effect_animation = new TLRPC.TL_documentEmpty();
            res.reactions.add(r);
        }
        return res;
    }

    /** The user's recent emoji (Mattermost preference), most used first. */
    static TLObject recentReactions(RelayAccount account, int limit) throws Exception {
        List<String> names = recentNames(account);
        TLRPC.TL_messages_reactions res = new TLRPC.TL_messages_reactions();
        res.hash = names.hashCode();
        for (String name : names) {
            if (limit > 0 && res.reactions.size() >= limit) {
                break;
            }
            res.reactions.add(tlReaction(account, name));
        }
        return res;
    }

    static TLObject recentWithCustom(RelayAccount account, int limit) throws Exception {
        List<String> names = recentNames(account);
        java.util.LinkedHashSet<String> all = new java.util.LinkedHashSet<>(names);
        for (org.telegram.relay.mattermost.model.CustomEmoji e : RelayEmoji.of(account).customEmoji().values()) {
            all.add(e.name);
        }
        TLRPC.TL_messages_reactions res = new TLRPC.TL_messages_reactions();
        res.hash = all.hashCode();
        for (String name : all) {
            if (limit > 0 && res.reactions.size() >= limit) {
                break;
            }
            res.reactions.add(tlReaction(account, name));
        }
        return res;
    }

    static List<String> recentNames(RelayAccount account) throws Exception {
        LinkedHashMap<String, Integer> usage = readRecent(account);
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(usage.entrySet());
        Collections.sort(entries, (a, b) -> Integer.compare(b.getValue(), a.getValue()));
        List<String> names = new ArrayList<>();
        for (Map.Entry<String, Integer> e : entries) {
            names.add(e.getKey());
        }
        return names;
    }

    private static LinkedHashMap<String, Integer> readRecent(RelayAccount account) throws Exception {
        LinkedHashMap<String, Integer> usage = new LinkedHashMap<>();
        for (Preference p : account.client.preferences()) {
            if (!PREF_RECENT_EMOJIS.equals(p.category)) {
                continue;
            }
            try {
                JsonElement value = Json.parse(p.value);
                if (value != null && value.isJsonArray()) {
                    for (JsonElement item : value.getAsJsonArray()) {
                        if (item.isJsonObject() && item.getAsJsonObject().has("name")) {
                            JsonObject o = item.getAsJsonObject();
                            int count = o.has("usageCount") ? o.get("usageCount").getAsInt() : 1;
                            usage.put(o.get("name").getAsString(), count);
                        } else if (item.isJsonPrimitive()) {
                            usage.put(item.getAsString(), 1);
                        }
                    }
                }
            } catch (Throwable t) {
                RelayConfig.log(TAG, "recent_emojis unreadable: " + t);
            }
        }
        return usage;
    }

    /** Bumps names in the {@code recent_emojis} preference, as the web and mobile apps do after a reaction. */
    static void noteUsed(RelayAccount account, List<String> names) {
        if (names.isEmpty()) {
            return;
        }
        try {
            LinkedHashMap<String, Integer> usage = readRecent(account);
            for (String name : names) {
                Integer count = usage.get(name);
                usage.put(name, count == null ? 1 : count + 1);
            }
            JsonArray array = new JsonArray();
            for (Map.Entry<String, Integer> e : usage.entrySet()) {
                JsonObject o = new JsonObject();
                o.addProperty("name", e.getKey());
                o.addProperty("usageCount", e.getValue());
                array.add(o);
            }
            List<Preference> prefs = new ArrayList<>();
            prefs.add(new Preference(account.session.userId, PREF_RECENT_EMOJIS, account.session.userId, Json.GSON.toJson(array)));
            account.client.savePreferences(prefs);
        } catch (Throwable t) {
            RelayConfig.log(TAG, "recent_emojis update failed: " + t);
        }
    }
}
