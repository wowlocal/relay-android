package org.telegram.relay.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MediaDataController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.relay.RelayConfig;
import org.telegram.relay.account.RelayAccount;
import org.telegram.relay.mattermost.Json;
import org.telegram.relay.mattermost.MattermostException;
import org.telegram.relay.mattermost.model.Channel;
import org.telegram.relay.mattermost.model.CustomEmoji;
import org.telegram.relay.mattermost.model.Post;
import org.telegram.relay.mattermost.model.Team;
import org.telegram.relay.mattermost.model.User;
import org.telegram.relay.mattermost.text.EmojiTable;
import org.telegram.relay.mattermost.text.FormattedText;
import org.telegram.relay.mattermost.text.MarkdownToEntities;
import org.telegram.relay.mattermost.ws.WebSocketEvent;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The composer in Relay mode (A7c): autocomplete for {@code @mentions} (channel members, {@code @channel},
 * {@code @here}, {@code @all}), {@code ~channels}, {@code :emoji:} (Unicode and custom) and slash commands
 * ({@code /teams/{id}/commands/autocomplete_suggestions}); running slash commands ({@code POST /commands/execute})
 * instead of posting them; the {@code @channel}/{@code @here} confirmation for large channels; ephemeral posts
 * (shown in the open chat only, like Mattermost's clients, until a reload).
 *
 * <p>The UI side is {@code org.telegram.relay.ui.RelayComposerUi} (MentionsAdapter / ChatActivity hooks).
 */
public final class RelayComposer {
    private static final String TAG = RelayConfig.TAG_BRIDGE;
    static final String EPHEMERAL_FOOTER = "(Only visible to you)";
    /** The server's error id for an unknown trigger. */
    public static final String COMMAND_NOT_FOUND = "api.command.execute_command.not_found.app_error";

    private static final ExecutorService io = Executors.newFixedThreadPool(2, r -> new Thread(r, "RelayComposer"));
    private static final AtomicInteger generation = new AtomicInteger();

    private RelayComposer() {
    }

    static void register(Map<Class<?>, RelayHandlers.Handler> map) {
        // Nothing goes through TL: autocomplete and commands are direct calls from the composer.
    }

    /** One autocomplete row that is neither a user nor an emoji: a special mention, a channel or a command. */
    public static final class Suggestion {
        public final String title;
        public final String description;
        /** What replaces the typed token (a trailing space is added by the composer). */
        public final String insert;

        Suggestion(String title, String description, String insert) {
            this.title = title;
            this.description = description;
            this.insert = insert;
        }
    }

    public interface Callback<T> {
        void done(T result);
    }

    // MARK: - Slash command parsing (iOS SlashCommand)

    /** The trigger of a slash command, or null when the text is posted as is ({@code //…}, paths, lone slash). */
    public static String commandTrigger(String text) {
        if (text == null || !text.startsWith("/") || text.startsWith("//")) {
            return null;
        }
        int end = 1;
        while (end < text.length() && !Character.isWhitespace(text.charAt(end))) {
            end++;
        }
        String word = text.substring(1, end);
        if (word.isEmpty() || word.contains("/")) {
            return null;
        }
        return word.toLowerCase(Locale.ROOT);
    }

    // MARK: - Autocomplete

    /**
     * Suggestions for the token before the cursor. {@code trigger} is {@code '@'}, {@code '~'}, {@code ':'} or
     * {@code '/'}; {@code query} the text after it ({@code '/'}: the whole input up to the cursor). The callback
     * runs on the UI thread with users ({@code TLRPC.User}, already put into MessagesController),
     * {@link Suggestion}s and {@code MediaDataController.KeywordResult}s, or null when a newer query replaced it.
     */
    public static void suggest(int accountNum, long dialogId, char trigger, String query, Callback<List<Object>> callback) {
        RelayAccount account = RelayAccount.get(accountNum);
        if (account == null) {
            return;
        }
        int gen = generation.incrementAndGet();
        io.execute(() -> {
            List<Object> result = new ArrayList<>();
            ArrayList<TLRPC.User> users = new ArrayList<>();
            try {
                RelayReadPath path = RelayReadPath.of(account);
                String channelId = path.directory.channelIdOf(dialogId);
                Channel channel = channelId != null ? path.directory.channel(channelId) : null;
                String teamId = teamOf(account, channel);
                switch (trigger) {
                    case '@':
                        mentions(account, channel, teamId, query, result, users);
                        break;
                    case '~':
                        if (teamId != null) {
                            for (Channel c : account.client.autocompleteChannels(teamId, query)) {
                                if (c.deleteAt != 0 || !("O".equals(c.type) || "P".equals(c.type))) {
                                    continue;
                                }
                                result.add(new Suggestion("~" + c.name, c.displayName, "~" + c.name));
                                if (result.size() >= 25) {
                                    break;
                                }
                            }
                        }
                        break;
                    case ':':
                        emoji(account, query, result);
                        break;
                    case '/':
                        if (teamId != null && channelId != null) {
                            commands(account, teamId, channelId, query, result);
                        }
                        break;
                    default:
                        break;
                }
            } catch (Throwable t) {
                RelayConfig.log(TAG, "autocomplete " + trigger + query + " failed: " + t);
            }
            AndroidUtilities.runOnUIThread(() -> {
                if (gen != generation.get()) {
                    return;
                }
                if (!users.isEmpty()) {
                    MessagesController.getInstance(accountNum).putUsers(users, false);
                }
                callback.done(result);
            });
        });
    }

    /** Cancels pending suggestions (the token went away). */
    public static void cancelSuggestions() {
        generation.incrementAndGet();
    }

    private static String teamOf(RelayAccount account, Channel channel) throws MattermostException {
        if (channel != null && channel.teamId != null && !channel.teamId.isEmpty()) {
            return channel.teamId;
        }
        List<Team> teams = RelayReadPath.of(account).directory.any().teams;
        return teams != null && !teams.isEmpty() ? teams.get(0).id : null;
    }

    private static void mentions(RelayAccount account, Channel channel, String teamId, String query, List<Object> out, ArrayList<TLRPC.User> users) throws Exception {
        String q = query.toLowerCase(Locale.ROOT);
        boolean direct = channel == null || channel.isDirect();
        JsonObject res = account.client.autocompleteUsers(teamId, channel != null ? channel.id : null, q, 25);
        HashSet<String> seen = new HashSet<>();
        for (String key : new String[]{"users", "out_of_channel"}) {
            JsonElement list = res.get(key);
            if (list == null || !list.isJsonArray()) {
                continue;
            }
            for (JsonElement item : list.getAsJsonArray()) {
                User user = Json.fromJson(item, User.class);
                if (user == null || user.deleteAt != 0 || !seen.add(user.id)) {
                    continue;
                }
                account.rememberUser(user);
                TLRPC.TL_user tl = account.tlUser(user.id);
                if (tl != null && tl.username != null) {
                    users.add(tl);
                    out.add(tl);
                }
            }
        }
        if (!direct) {
            String[][] specials = {
                    {"all", "Notifies everyone in this channel"},
                    {"channel", "Notifies everyone in this channel"},
                    {"here", "Notifies everyone online in this channel"},
            };
            for (String[] s : specials) {
                if (s[0].startsWith(q)) {
                    out.add(new Suggestion("@" + s[0], s[1], "@" + s[0]));
                }
            }
        }
    }

    private static void emoji(RelayAccount account, String query, List<Object> out) {
        if (query.length() < 2) {
            return;
        }
        String q = query.toLowerCase(Locale.ROOT);
        for (EmojiTable.Entry e : EmojiTable.search(q, 20)) {
            out.add(new MediaDataController.KeywordResult(e.emoji, e.name));
        }
        RelayEmoji emoji = RelayEmoji.of(account);
        int custom = 0;
        for (CustomEmoji e : emoji.customEmoji().values()) {
            if (e.name.toLowerCase(Locale.ROOT).contains(q)) {
                out.add(custom, new MediaDataController.KeywordResult("animated_" + emoji.customDocumentId(e.name), e.name));
                if (++custom >= 15) {
                    break;
                }
            }
        }
    }

    private static void commands(RelayAccount account, String teamId, String channelId, String input, List<Object> out) throws Exception {
        JsonArray list = account.client.commandSuggestions(teamId, input, channelId, null);
        for (JsonElement item : list) {
            if (!item.isJsonObject()) {
                continue;
            }
            JsonObject o = item.getAsJsonObject();
            String complete = string(o, "Complete");
            String suggestion = string(o, "Suggestion");
            String hint = string(o, "Hint");
            String description = string(o, "Description");
            if (complete.isEmpty()) {
                continue;
            }
            String insert = complete.startsWith("/") ? complete : "/" + complete;
            String title = suggestion.isEmpty() ? insert : (suggestion.startsWith("/") || !input.trim().contains(" ") ? (suggestion.startsWith("/") ? suggestion : "/" + suggestion) : suggestion);
            String desc = (hint.isEmpty() ? "" : hint + "  ") + description;
            out.add(new Suggestion(title, desc.trim(), insert));
            if (out.size() >= 30) {
                break;
            }
        }
    }

    private static String string(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : "";
    }

    // MARK: - Commands

    /** Result of a slash command: ok, unknown trigger (offer to post as text), or another error. */
    public static final class CommandResult {
        public final boolean ok;
        public final boolean notFound;
        public final String error;
        public final String gotoLocation;

        CommandResult(boolean ok, boolean notFound, String error, String gotoLocation) {
            this.ok = ok;
            this.notFound = notFound;
            this.error = error;
            this.gotoLocation = gotoLocation;
        }
    }

    /**
     * {@code POST /commands/execute} in the chat ({@code replyToMsgId}: a thread's root or reply, for {@code root_id}).
     * The command's own answer arrives as a post or an ephemeral post over the WebSocket.
     */
    public static void executeCommand(int accountNum, long dialogId, int replyToMsgId, String text, Callback<CommandResult> callback) {
        RelayAccount account = RelayAccount.get(accountNum);
        if (account == null) {
            return;
        }
        RelayBridge.get(accountNum).post(() -> {
            CommandResult result;
            try {
                RelayReadPath path = RelayReadPath.of(account);
                String channelId = path.directory.channelIdOf(dialogId);
                Channel channel = channelId != null ? path.directory.channel(channelId) : null;
                String rootId = null;
                if (channelId != null && replyToMsgId != 0) {
                    RelayHistory.Unit unit = path.history.unitAt(channelId, replyToMsgId);
                    if (unit != null) {
                        Post post = unit.layout.post;
                        rootId = post.isReply() ? post.rootId : post.id;
                    }
                }
                JsonObject res = account.client.executeCommand(channelId, teamOf(account, channel), text, rootId, rootId);
                RelayConfig.log(TAG, "command " + commandTrigger(text) + " executed: " + res.get("response_type"));
                String goTo = string(res, "goto_location");
                result = new CommandResult(true, false, null, goTo.isEmpty() ? null : goTo);
            } catch (MattermostException e) {
                boolean notFound = COMMAND_NOT_FOUND.equals(e.getErrorId());
                RelayConfig.log(TAG, "command " + commandTrigger(text) + " failed: " + e.getErrorId() + " " + e.getMessage());
                result = new CommandResult(false, notFound, e.getMessage(), null);
            } catch (Throwable t) {
                RelayConfig.logError(TAG, "command failed", t);
                result = new CommandResult(false, false, String.valueOf(t.getMessage()), null);
            }
            CommandResult r = result;
            AndroidUtilities.runOnUIThread(() -> callback.done(r));
        });
    }

    // MARK: - @channel / @here confirmation

    /** {@code [memberCount (without the sender), timezoneCount, hereOnly ? 1 : 0]}, or null to send at once. */
    public static void channelMentionConfirmation(int accountNum, long dialogId, String text, Callback<int[]> callback) {
        boolean all = false;
        boolean here = false;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?:^|[^\\w@.\\-])@(all|channel|here)(?![\\w\\-])", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(text);
        while (m.find()) {
            String which = m.group(1).toLowerCase(Locale.ROOT);
            if (which.equals("here")) {
                here = true;
            } else {
                all = true;
            }
        }
        RelayAccount account = RelayAccount.get(accountNum);
        if ((!all && !here) || account == null) {
            callback.done(null);
            return;
        }
        boolean hereOnly = here && !all;
        RelayBridge.get(accountNum).post(() -> {
            int[] result = null;
            try {
                RelayReadPath path = RelayReadPath.of(account);
                String channelId = path.directory.channelIdOf(dialogId);
                Channel channel = channelId != null ? path.directory.channel(channelId) : null;
                boolean enabled = confirmEnabled(account);
                if (channel != null && !channel.isDirect() && !channel.isGroup() && enabled) {
                    long members = account.client.channelStats(channelId).memberCount;
                    if (members > 5) {
                        int timezones = 0;
                        try {
                            timezones = account.client.channelTimezones(channelId).size();
                        } catch (MattermostException ignore) {
                        }
                        result = new int[]{(int) (members - 1), timezones, hereOnly ? 1 : 0};
                        RelayConfig.log(TAG, "channel mention to " + members + " members in " + timezones + " time zones asks first");
                    }
                }
            } catch (Throwable t) {
                RelayConfig.log(TAG, "channel mention check failed: " + t);
            }
            int[] r = result;
            AndroidUtilities.runOnUIThread(() -> callback.done(r));
        });
    }

    private static volatile Boolean confirmNotifications;

    private static boolean confirmEnabled(RelayAccount account) {
        Boolean cached = confirmNotifications;
        if (cached == null) {
            try {
                cached = account.client.clientConfig().getBoolean("EnableConfirmNotificationsToChannel", true);
            } catch (Throwable t) {
                cached = true;
            }
            confirmNotifications = cached;
        }
        return cached;
    }

    // MARK: - Ephemeral posts

    /** {@code ephemeral_message}: shows the post in its chat, for this session only (never stored). */
    static void onEphemeral(RelayLive live, RelayAccount account, WebSocketEvent event) throws Exception {
        JsonElement raw = event.data != null ? event.data.get("post") : null;
        Post post = raw != null ? Json.decodeEmbedded(raw, Post.class) : null;
        if (post == null || post.channelId == null) {
            return;
        }
        RelayReadPath path = RelayReadPath.of(account);
        Channel channel = path.directory.channel(post.channelId);
        if (channel == null) {
            return;
        }
        long dialogId = path.directory.dialogId(channel);
        boolean system = post.type != null && post.type.startsWith("system_") || post.userId == null || post.userId.isEmpty() || account.session.userId.equals(post.userId);
        String authorId;
        String overrideName = post.props != null && post.props.has("override_username") && post.props.get("override_username").isJsonPrimitive() ? post.props.get("override_username").getAsString() : null;
        if (system) {
            authorId = account.overrideUser("system", "System");
        } else if (overrideName != null && !overrideName.isEmpty()) {
            authorId = account.overrideUser(post.userId, overrideName);
        } else {
            account.ensureUsers(java.util.Collections.singletonList(post.userId));
            authorId = post.userId;
        }
        TLRPC.TL_user author = account.tlUser(authorId);
        String markdown = post.message != null ? post.message : "";
        FormattedText text;
        try {
            text = MarkdownToEntities.convert(markdown, new MarkdownToEntities.Options());
        } catch (Throwable t) {
            text = new FormattedText(markdown);
        }
        String body = text.getText();
        ArrayList<TLRPC.MessageEntity> entities = RelayConvert.entities(text);
        int footerStart = body.length() + (body.isEmpty() ? 0 : 2);
        body = body + (body.isEmpty() ? "" : "\n\n") + EPHEMERAL_FOOTER;
        TLRPC.TL_messageEntityItalic italic = new TLRPC.TL_messageEntityItalic();
        italic.offset = footerStart;
        italic.length = EPHEMERAL_FOOTER.length();
        entities.add(italic);

        int accountNum = account.account;
        TLRPC.TL_message m = new TLRPC.TL_message();
        m.message = body;
        m.entities = entities;
        m.flags |= TLObject.FLAG_7;
        m.date = (int) (post.createAt > 0 ? post.createAt / 1000 : System.currentTimeMillis() / 1000);
        m.peer_id = path.directory.peer(channel);
        m.dialog_id = dialogId;
        TLRPC.TL_peerUser from = new TLRPC.TL_peerUser();
        from.user_id = author != null ? author.id : 0;
        m.from_id = from;
        m.flags |= TLObject.FLAG_8;
        m.out = false;
        m.unread = false;
        m.media = new TLRPC.TL_messageMediaEmpty();
        m.post = false;
        TLRPC.TL_channel chat = channel.isDirect() ? null : path.directory.tlChannel(channel);
        RelayConfig.log(TAG, "live: ephemeral post in " + post.channelId + " from " + (system ? "System" : authorId));
        AndroidUtilities.runOnUIThread(() -> {
            MessagesController mc = MessagesController.getInstance(accountNum);
            if (author != null) {
                mc.putUser(author, false);
            }
            if (chat != null && mc.getChat(chat.id) == null) {
                mc.putChat(chat, false);
            }
            m.id = UserConfig.getInstance(accountNum).getNewMessageId();
            UserConfig.getInstance(accountNum).saveConfig(false);
            m.local_id = m.id;
            MessageObject object = new MessageObject(accountNum, m, true, true);
            ArrayList<MessageObject> list = new ArrayList<>();
            list.add(object);
            // Only the open chat shows it (Mattermost drops ephemeral posts on reload); the chat list is untouched.
            NotificationCenter.getInstance(accountNum).postNotificationName(NotificationCenter.didReceiveNewMessages, dialogId, list, false, 0);
        });
    }
}
