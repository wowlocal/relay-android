package org.telegram.relay.bridge;

import org.telegram.messenger.MediaDataController;
import org.telegram.relay.RelayConfig;
import org.telegram.relay.account.RelayAccount;
import org.telegram.relay.account.RelayDatabase;
import org.telegram.relay.mattermost.MattermostDirectory;
import org.telegram.relay.mattermost.MattermostException;
import org.telegram.relay.mattermost.PostsQuery;
import org.telegram.relay.mattermost.model.Channel;
import org.telegram.relay.mattermost.model.FileInfo;
import org.telegram.relay.mattermost.model.Post;
import org.telegram.relay.mattermost.model.PostList;
import org.telegram.relay.mattermost.model.Team;
import org.telegram.relay.mattermost.model.User;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_update;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.WeakHashMap;

/**
 * Phase A7b, finding things: Telegram's search UI on Mattermost.
 *
 * <ul>
 *   <li>{@code messages.search} with a peer: text ({@code POST /teams/{id}/posts/search} with
 *       {@code in:<channel id>}, {@code from:<username>}, {@code after:/before:}; the user's own modifiers pass
 *       through), pinned ({@code /channels/{id}/pinned}), media tabs (the channel's files from
 *       {@code /teams/{id}/files/search in:<channel id>}, classified with Telegram's own
 *       {@link MediaDataController#getMediaType}), links (posts with URLs in the recent history).</li>
 *   <li>{@code messages.searchGlobal}: posts in every team (DMs/GMs come back per team, merged by id).</li>
 *   <li>{@code contacts.search}: people ({@code /users/search}), the user's chats, joinable public channels.</li>
 *   <li>Counters, result positions (shared media scroll), pin/unpin.</li>
 * </ul>
 *
 * Found posts that are not mapped yet are mapped as single-post islands (like reply roots in A3), so a
 * result opens the chat at the post and the history around it loads as usual. Result lists are cached for
 * a minute per query so the client's paging ({@code offset_id}, {@code next_rate}) is stable.
 */
final class RelaySearch {
    private static final String TAG = RelayConfig.TAG_BRIDGE;
    /** The database search backend answers page 0 only, with at most 100 posts. */
    static final int SEARCH_PAGE = 100;
    static final int MAX_FILE_PAGES = 5;
    /** Recent posts scanned for links (the server cannot search for URLs). */
    static final int LINK_SCAN_PAGES = 5;
    static final long CACHE_MS = 60_000;

    private static final WeakHashMap<RelayAccount, RelaySearch> instances = new WeakHashMap<>();

    static RelaySearch of(RelayAccount account) {
        synchronized (instances) {
            RelaySearch search = instances.get(account);
            if (search == null) {
                instances.put(account, search = new RelaySearch(account));
            }
            return search;
        }
    }

    static void register(Map<Class<?>, RelayHandlers.Handler> map) {
        map.put(TLRPC.TL_messages_search.class, (bridge, account, req) -> of(account).search((TLRPC.TL_messages_search) req));
        map.put(TLRPC.TL_messages_searchGlobal.class, (bridge, account, req) -> of(account).searchGlobal((TLRPC.TL_messages_searchGlobal) req));
        map.put(TLRPC.TL_contacts_search.class, (bridge, account, req) -> of(account).searchPeers((TLRPC.TL_contacts_search) req));
        map.put(TLRPC.TL_messages_getSearchCounters.class, (bridge, account, req) -> of(account).counters((TLRPC.TL_messages_getSearchCounters) req));
        map.put(TLRPC.TL_messages_getSearchResultsPositions.class, (bridge, account, req) -> of(account).positions((TLRPC.TL_messages_getSearchResultsPositions) req));
        map.put(TLRPC.TL_messages_getSearchResultsCalendar.class, (bridge, account, req) -> {
            TLRPC.TL_messages_searchResultsCalendar res = new TLRPC.TL_messages_searchResultsCalendar();
            res.inexact = true;
            return res;
        });
        map.put(TLRPC.TL_messages_updatePinnedMessage.class, (bridge, account, req) -> of(account).updatePinned(bridge, (TLRPC.TL_messages_updatePinnedMessage) req));
        map.put(TLRPC.TL_messages_unpinAllMessages.class, (bridge, account, req) -> of(account).unpinAll(bridge, (TLRPC.TL_messages_unpinAllMessages) req));
        map.put(TLRPC.TL_contacts_getContacts.class, (bridge, account, req) -> of(account).contacts());
    }

    final RelayAccount account;
    final RelayReadPath path;

    /** One cached result list: messages newest first (by id within a chat, by date across chats). */
    static final class Found {
        final long at = System.currentTimeMillis();
        final ArrayList<TLRPC.Message> messages = new ArrayList<>();
        final Set<String> users = new LinkedHashSet<>();
        final Map<String, Channel> channels = new LinkedHashMap<>();
        /** True when the server had more than was fetched. */
        boolean more;
    }

    private final LinkedHashMap<String, Found> cache = new LinkedHashMap<String, Found>(32, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Found> eldest) {
            return size() > 24;
        }
    };

    private RelaySearch(RelayAccount account) {
        this.account = account;
        this.path = RelayReadPath.of(account);
    }

    private synchronized Found cached(String key) {
        Found found = cache.get(key);
        if (found != null && System.currentTimeMillis() - found.at > CACHE_MS) {
            cache.remove(key);
            return null;
        }
        return found;
    }

    /** Drops one cached list (the Mentions tab reloads live). */
    synchronized void forget(String key) {
        cache.remove(key);
    }

    private synchronized void remember(String key, Found found) {
        cache.put(key, found);
    }

    /** Drops cached lists of a channel (pin, flag or a new post changed them). */
    synchronized void invalidate(String channelId) {
        synchronized (filePostsCache) {
            filePostsCache.clear();
        }
        if (channelId == null) {
            cache.clear();
            return;
        }
        for (java.util.Iterator<String> it = cache.keySet().iterator(); it.hasNext(); ) {
            String key = it.next();
            if (key.contains(channelId) || key.startsWith("global|") || key.startsWith("saved") || key.startsWith("mentions")) {
                it.remove();
            }
        }
    }

    static int timeZoneOffset() {
        return TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 1000;
    }

    /** {@code YYYY-MM-DD} of a Unix time in the device's time zone. */
    static String day(long unixSeconds) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        format.setTimeZone(TimeZone.getDefault());
        return format.format(new Date(unixSeconds * 1000L));
    }

    /**
     * The server terms: the query as typed (Mattermost modifiers included) plus the scope. Telegram's date
     * range is inclusive, Mattermost's {@code after:}/{@code before:} exclusive, so the neighbouring days are used.
     */
    static String terms(String query, String inChannel, String fromUsername, int minDate, int maxDate) {
        StringBuilder terms = new StringBuilder(query != null ? query.trim() : "");
        if (inChannel != null) {
            append(terms, "in:" + inChannel);
        }
        if (fromUsername != null) {
            append(terms, "from:" + fromUsername);
        }
        if (minDate > 0) {
            append(terms, "after:" + day(minDate - 86400L));
        }
        if (maxDate > 0 && maxDate < Integer.MAX_VALUE) {
            append(terms, "before:" + day(maxDate + 86400L));
        }
        return terms.toString();
    }

    private static void append(StringBuilder terms, String part) {
        if (terms.length() > 0) {
            terms.append(' ');
        }
        terms.append(part);
    }

    private String teamOf(Channel channel) throws MattermostException {
        if (channel.teamId != null && !channel.teamId.isEmpty()) {
            return channel.teamId;
        }
        List<Team> teams = path.directory.any().teams;
        return teams.isEmpty() ? null : teams.get(0).id;
    }

    private String username(TLRPC.InputPeer from) throws MattermostException {
        if (from == null || from instanceof TLRPC.TL_inputPeerEmpty) {
            return null;
        }
        String mmId;
        if (from instanceof TLRPC.TL_inputPeerSelf || from.user_id == account.selfTlId()) {
            mmId = account.session.userId;
        } else if (from.user_id != 0) {
            mmId = account.db().mmId(RelayDatabase.KIND_USER, from.user_id);
        } else {
            return null;
        }
        if (mmId == null) {
            return null;
        }
        account.ensureUsers(Collections.singletonList(mmId));
        User user = account.cachedUser(mmId);
        return user != null ? user.username : null;
    }

    // MARK: - Kinds

    /** What a result list holds: the message with a post's text, pinned posts, or one media tab. */
    static final int KIND_TEXT = -1;
    static final int KIND_PINNED = -2;
    static final int KIND_PHOTOS_ONLY = -3;
    static final int KIND_VIDEOS_ONLY = -4;
    static final int KIND_NONE = -100;

    /** The kind of a Telegram filter ({@link MediaDataController} media types, or a KIND_ constant). */
    static int kindOf(TLRPC.MessagesFilter filter) {
        if (filter == null || filter instanceof TLRPC.TL_inputMessagesFilterEmpty) return KIND_TEXT;
        if (filter instanceof TLRPC.TL_inputMessagesFilterPinned) return KIND_PINNED;
        if (filter instanceof TLRPC.TL_inputMessagesFilterPhotoVideo) return MediaDataController.MEDIA_PHOTOVIDEO;
        if (filter instanceof TLRPC.TL_inputMessagesFilterPhotos) return KIND_PHOTOS_ONLY;
        if (filter instanceof TLRPC.TL_inputMessagesFilterVideo) return KIND_VIDEOS_ONLY;
        if (filter instanceof TLRPC.TL_inputMessagesFilterDocument) return MediaDataController.MEDIA_FILE;
        if (filter instanceof TLRPC.TL_inputMessagesFilterRoundVoice || filter instanceof TLRPC.TL_inputMessagesFilterVoice
                || filter instanceof TLRPC.TL_inputMessagesFilterRoundVideo) return MediaDataController.MEDIA_AUDIO;
        if (filter instanceof TLRPC.TL_inputMessagesFilterMusic) return MediaDataController.MEDIA_MUSIC;
        if (filter instanceof TLRPC.TL_inputMessagesFilterGif) return MediaDataController.MEDIA_GIF;
        if (filter instanceof TLRPC.TL_inputMessagesFilterUrl) return MediaDataController.MEDIA_URL;
        return KIND_NONE;
    }

    static boolean isMediaKind(int kind) {
        return (kind >= 0 && kind != MediaDataController.MEDIA_URL) || kind == KIND_PHOTOS_ONLY || kind == KIND_VIDEOS_ONLY;
    }

    static boolean matches(TLRPC.Message message, int kind) {
        int type = MediaDataController.getMediaType(message);
        switch (kind) {
            case KIND_PHOTOS_ONLY:
                return MessageMedia.isPhoto(message);
            case KIND_VIDEOS_ONLY:
                return type == MediaDataController.MEDIA_PHOTOVIDEO && !MessageMedia.isPhoto(message);
            case MediaDataController.MEDIA_URL:
                return type == MediaDataController.MEDIA_URL || hasUrl(message.message);
            default:
                return type == kind;
        }
    }

    private static final class MessageMedia {
        static boolean isPhoto(TLRPC.Message message) {
            return message.media instanceof TLRPC.TL_messageMediaPhoto;
        }
    }

    static boolean hasUrl(String text) {
        return text != null && (text.contains("http://") || text.contains("https://"));
    }

    // MARK: - Posts → messages

    /**
     * The messages of {@code posts} (mapping unmapped ones as single-post islands): the message with each
     * post's text, or for a media kind every part whose media is of that kind.
     */
    Found messagesOf(List<Post> posts, int kind, boolean byDate) throws Exception {
        Found found = new Found();
        Map<String, List<Post>> byChannel = new LinkedHashMap<>();
        for (Post post : posts) {
            if (post == null || post.isDeleted() || post.isEphemeral()) {
                continue;
            }
            List<Post> list = byChannel.get(post.channelId);
            if (list == null) {
                byChannel.put(post.channelId, list = new ArrayList<>());
            }
            list.add(post);
        }
        for (Map.Entry<String, List<Post>> entry : byChannel.entrySet()) {
            Channel channel;
            try {
                channel = path.directory.channel(entry.getKey());
            } catch (MattermostException e) {
                RelayConfig.logError(TAG, "search: channel " + entry.getKey(), e);
                continue;
            }
            if (channel == null) {
                continue;
            }
            List<RelayHistory.Unit> wanted = new ArrayList<>();
            List<Post> channelPosts = entry.getValue();
            if (kind == KIND_TEXT && RelayThreads.collapses(account, channel, path.directory.any().collapsedThreads)) {
                // With collapsed reply threads a main history shows thread roots only (A7a; DMs too since the DM
                // threads change): a reply that matches is listed as its thread's root, which the chat can jump to.
                List<Post> roots = new ArrayList<>();
                Set<String> seen = new HashSet<>();
                for (Post post : channelPosts) {
                    Post shown = post;
                    if (post.isReply()) {
                        path.history.rememberPost(post);
                        Post root = path.history.post(post.rootId);
                        if (root == null || root.isDeleted()) {
                            continue;
                        }
                        shown = root;
                    }
                    if (seen.add(shown.id)) {
                        roots.add(shown);
                    }
                }
                channelPosts = roots;
            }
            for (Post post : channelPosts) {
                path.history.rememberPost(post);
                if (path.history.localIdOf(channel.id, post.id) == 0) {
                    path.history.mapSingle(channel.id, post);
                }
                List<RelayHistory.Unit> units = path.history.assignIds(channel.id, RelayHistory.newestFirst(Collections.singletonList(post)));
                for (RelayHistory.Unit unit : units) {
                    if (kind == KIND_TEXT || kind == KIND_PINNED || kind == MediaDataController.MEDIA_URL) {
                        if (unit.part == unit.layout.textPart) {
                            wanted.add(unit);
                        }
                    } else if (unit.layout.file(unit.part) != null) {
                        wanted.add(unit);
                    }
                }
            }
            if (wanted.isEmpty()) {
                continue;
            }
            ArrayList<TLRPC.Message> messages = path.messages(channel, wanted, found.users);
            boolean any = false;
            for (TLRPC.Message message : messages) {
                if (isMediaKind(kind) || kind == MediaDataController.MEDIA_URL) {
                    if (!matches(message, kind)) {
                        continue;
                    }
                }
                found.messages.add(message);
                any = true;
            }
            if (any) {
                found.channels.put(channel.id, channel);
                String dmUser = path.directory.dmUser(channel);
                if (dmUser != null) {
                    found.users.add(dmUser);
                }
            }
        }
        Collections.sort(found.messages, (a, b) -> {
            if (byDate && a.date != b.date) {
                return Integer.compare(b.date, a.date);
            }
            return Integer.compare(b.id, a.id);
        });
        return found;
    }

    /** Posts found by a post search in the given teams (merged by id, newest first). */
    List<Post> searchPosts(Collection<String> teamIds, String terms, boolean isOr, Found into) throws MattermostException {
        return searchPosts(teamIds, terms, isOr, false, into);
    }

    /** {@code includeArchived}: a search inside an archived channel (other searches leave archived channels out, as iOS). */
    List<Post> searchPosts(Collection<String> teamIds, String terms, boolean isOr, boolean includeArchived, Found into) throws MattermostException {
        Map<String, Post> byId = new LinkedHashMap<>();
        for (String teamId : teamIds) {
            PostList list = MattermostDirectory.searchPosts(account.client, teamId, terms, isOr, includeArchived, timeZoneOffset(), 0, SEARCH_PAGE);
            List<Post> page = list.getOrderedPosts();
            if (page.size() >= SEARCH_PAGE && into != null) {
                into.more = true;
            }
            for (Post post : page) {
                byId.put(post.id, post);
            }
        }
        List<Post> posts = new ArrayList<>(byId.values());
        Collections.sort(posts, (a, b) -> Long.compare(b.createAt, a.createAt));
        return posts;
    }

    /** The posts of the files a file search finds (newest first); {@code fileIds} receives the files. */
    List<Post> searchFilePosts(String teamId, String terms, Set<String> fileIds) throws MattermostException {
        List<String> postIds = new ArrayList<>();
        for (int page = 0; page < MAX_FILE_PAGES; page++) {
            MattermostDirectory.FileInfoList list = MattermostDirectory.searchFiles(account.client, teamId, terms, timeZoneOffset(), page, SEARCH_PAGE);
            List<FileInfo> files = list.ordered();
            for (FileInfo file : files) {
                if (file.deleteAt != 0 || file.postId == null || file.postId.isEmpty()) {
                    continue;
                }
                fileIds.add(file.id);
                if (!postIds.contains(file.postId)) {
                    postIds.add(file.postId);
                }
            }
            if (files.size() < SEARCH_PAGE) {
                break;
            }
        }
        List<Post> posts = new ArrayList<>();
        for (int start = 0; start < postIds.size(); start += 100) {
            List<String> chunk = postIds.subList(start, Math.min(postIds.size(), start + 100));
            List<String> missing = new ArrayList<>();
            for (String id : chunk) {
                Post cached = path.history.cachedPost(id);
                if (cached != null && cached.metadata != null) {
                    posts.add(cached);
                } else {
                    missing.add(id);
                }
            }
            posts.addAll(MattermostDirectory.postsByIds(account.client, missing));
        }
        Collections.sort(posts, (a, b) -> Long.compare(b.createAt, a.createAt));
        return posts;
    }

    private final Map<String, Object[]> filePostsCache = new HashMap<>();

    /** {@link #searchFilePosts} cached for {@link #CACHE_MS} (every media tab and counter asks for the same files). */
    private List<Post> cachedFilePosts(String teamId, String terms) throws MattermostException {
        String key = teamId + "|" + terms;
        synchronized (filePostsCache) {
            Object[] hit = filePostsCache.get(key);
            if (hit != null && System.currentTimeMillis() - (Long) hit[0] < CACHE_MS) {
                @SuppressWarnings("unchecked") List<Post> posts = (List<Post>) hit[1];
                return posts;
            }
        }
        List<Post> posts = searchFilePosts(teamId, terms, new HashSet<String>());
        synchronized (filePostsCache) {
            if (filePostsCache.size() > 16) {
                filePostsCache.clear();
            }
            filePostsCache.put(key, new Object[]{System.currentTimeMillis(), posts});
        }
        return posts;
    }

    /** Recent posts of a channel that contain a URL (newest first). */
    List<Post> linkPosts(String channelId) throws MattermostException {
        List<Post> result = new ArrayList<>();
        String before = null;
        for (int page = 0; page < LINK_SCAN_PAGES; page++) {
            PostsQuery query = before == null ? PostsQuery.latest(200) : PostsQuery.before(before, 200);
            PostList list = account.client.posts(channelId, query);
            List<Post> posts = list.getOrderedPosts();
            for (Post post : posts) {
                if (!post.isDeleted() && !post.isSystemMessage() && hasUrl(post.message)) {
                    result.add(post);
                }
            }
            if (posts.size() < 200) {
                break;
            }
            before = posts.get(posts.size() - 1).id;
        }
        return result;
    }

    // MARK: - messages.search (one chat)

    /** The result list of a chat search (cached for {@link #CACHE_MS}). */
    Found chatResults(Channel channel, int kind, String query, String fromUsername, int minDate, int maxDate, int topMsgId) throws Exception {
        String key = "chat|" + channel.id + "|" + kind + "|" + query + "|" + fromUsername + "|" + minDate + "|" + maxDate + "|" + topMsgId;
        Found found = cached(key);
        if (found != null) {
            return found;
        }
        long started = System.currentTimeMillis();
        String q = query != null ? query.trim() : "";
        List<Post> posts;
        switch (kind) {
            case KIND_PINNED:
                posts = account.client.pinnedPosts(channel.id).getOrderedPosts();
                break;
            case KIND_TEXT: {
                if (q.isEmpty() && fromUsername == null) {
                    posts = new ArrayList<>();
                    break;
                }
                String teamId = teamOf(channel);
                posts = teamId == null ? new ArrayList<Post>() : searchPosts(Collections.singletonList(teamId), terms(q, channel.id, fromUsername, minDate, maxDate), false, channel.isArchived(), null);
                break;
            }
            case MediaDataController.MEDIA_URL: {
                posts = new ArrayList<>();
                for (Post post : linkPosts(channel.id)) {
                    if (q.isEmpty() || post.message.toLowerCase(Locale.ROOT).contains(q.toLowerCase(Locale.ROOT))) {
                        posts.add(post);
                    }
                }
                break;
            }
            default: {
                if (!isMediaKind(kind)) {
                    posts = new ArrayList<>();
                    break;
                }
                String teamId = teamOf(channel);
                String fileTerms = terms(q, channel.id, fromUsername, minDate, maxDate);
                posts = teamId == null ? new ArrayList<Post>() : cachedFilePosts(teamId, fileTerms);
                break;
            }
        }
        if (topMsgId != 0) {
            RelayHistory.Unit root = path.history.unitAt(channel.id, topMsgId);
            String rootId = root != null ? (root.layout.post.isReply() ? root.layout.post.rootId : root.layout.post.id) : null;
            List<Post> inThread = new ArrayList<>();
            for (Post post : posts) {
                if (rootId != null && (rootId.equals(post.id) || rootId.equals(post.rootId))) {
                    inThread.add(post);
                }
            }
            posts = inThread;
        }
        found = messagesOf(posts, kind, false);
        remember(key, found);
        RelayConfig.log(TAG, String.format(Locale.US, "search %s kind=%d q=%d chars from=%s -> %d posts, %d msgs in %d ms",
                channel.name, kind, q.length(), fromUsername, posts.size(), found.messages.size(), System.currentTimeMillis() - started));
        return found;
    }

    TLObject search(TLRPC.TL_messages_search req) throws Exception {
        String channelId = path.directory.channelIdOf(req.peer);
        int kind = kindOf(req.filter);
        if (channelId == null || kind == KIND_NONE || !req.saved_reaction.isEmpty()) {
            return emptyFor(req.peer);
        }
        Channel channel = path.directory.channel(channelId);
        String from = username(req.from_id);
        if (req.from_id != null && !(req.from_id instanceof TLRPC.TL_inputPeerEmpty) && from == null) {
            return emptyFor(req.peer);
        }
        Found found = chatResults(channel, kind, req.q, from, req.min_date, req.max_date, req.top_msg_id);
        List<TLRPC.Message> window = window(found.messages, req.offset_id, req.add_offset, req.limit, req.max_id, req.min_id);
        return response(channel, window, found);
    }

    /** {@code L[max(0, p+add_offset) … p+add_offset+limit)} of a list sorted by id desc, p = first id below offset_id. */
    static List<TLRPC.Message> window(List<TLRPC.Message> all, int offsetId, int addOffset, int limit, int maxId, int minId) {
        List<TLRPC.Message> list = new ArrayList<>();
        for (TLRPC.Message m : all) {
            if (maxId > 0 && m.id >= maxId) continue;
            if (minId > 0 && m.id <= minId) continue;
            list.add(m);
        }
        int p = 0;
        if (offsetId > 0) {
            p = list.size();
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i).id < offsetId) {
                    p = i;
                    break;
                }
            }
        }
        int from = p + addOffset;
        int to = from + Math.max(1, limit);
        from = Math.max(0, from);
        to = Math.min(list.size(), to);
        if (from >= to) {
            return new ArrayList<>();
        }
        return new ArrayList<>(list.subList(from, to));
    }

    private TLObject emptyFor(TLRPC.InputPeer peer) {
        if (peer instanceof TLRPC.TL_inputPeerChannel) {
            TLRPC.TL_messages_channelMessages res = new TLRPC.TL_messages_channelMessages();
            res.pts = account.db().pts(RelayDatabase.channelBox(peer.channel_id));
            return res;
        }
        return new TLRPC.TL_messages_messages();
    }

    private TLRPC.messages_Messages response(Channel channel, List<TLRPC.Message> window, Found found) throws MattermostException {
        TLRPC.messages_Messages res;
        if (channel.isDirect()) {
            TLRPC.TL_messages_messagesSlice slice = new TLRPC.TL_messages_messagesSlice();
            slice.count = found.messages.size();
            res = slice;
        } else {
            TLRPC.TL_messages_channelMessages cm = new TLRPC.TL_messages_channelMessages();
            cm.pts = account.db().pts(RelayDatabase.channelBox(-path.directory.dialogId(channel)));
            cm.count = found.messages.size();
            cm.chats.add(path.directory.tlChannel(channel));
            res = cm;
        }
        res.messages.addAll(window);
        Set<String> users = new LinkedHashSet<>(found.users);
        String dmUser = path.directory.dmUser(channel);
        if (dmUser != null) {
            users.add(dmUser);
        }
        res.users.addAll(path.users(users));
        return res;
    }

    // MARK: - messages.searchGlobal

    TLObject searchGlobal(TLRPC.TL_messages_searchGlobal req) throws Exception {
        int kind = kindOf(req.filter);
        String q = req.q != null ? req.q.trim() : "";
        TLRPC.TL_messages_messagesSlice res = new TLRPC.TL_messages_messagesSlice();
        if (kind == KIND_NONE || kind == KIND_PINNED || (q.isEmpty() && !isMediaKind(kind))) {
            return res;
        }
        String key = "global|" + kind + "|" + q + "|" + req.min_date + "|" + req.max_date + "|" + req.users_only + req.groups_only + req.broadcasts_only;
        Found found = cached(key);
        if (found == null) {
            long started = System.currentTimeMillis();
            List<String> teams = new ArrayList<>();
            for (Team team : path.directory.any().teams) {
                teams.add(team.id);
            }
            String terms = terms(q, null, null, req.min_date, req.max_date);
            List<Post> posts;
            if (isMediaKind(kind)) {
                posts = new ArrayList<>();
                Set<String> seen = new HashSet<>();
                String fileTerms = terms.isEmpty() ? extensionTerms(kind) : terms;
                for (String teamId : teams) {
                    for (Post post : searchFilePosts(teamId, fileTerms, new HashSet<String>())) {
                        if (seen.add(post.id)) {
                            posts.add(post);
                        }
                    }
                }
            } else {
                found = new Found();
                posts = searchPosts(teams, terms, false, found);
                if (kind == MediaDataController.MEDIA_URL) {
                    List<Post> withLinks = new ArrayList<>();
                    for (Post post : posts) {
                        if (hasUrl(post.message)) {
                            withLinks.add(post);
                        }
                    }
                    posts = withLinks;
                }
            }
            if (req.users_only || req.groups_only || req.broadcasts_only) {
                RelayDirectory.Snapshot snapshot = path.directory.any();
                List<Post> kept = new ArrayList<>();
                for (Post post : posts) {
                    Channel channel = snapshot.channels.get(post.channelId);
                    boolean dm = channel != null && channel.isDirect();
                    if (req.users_only ? dm : !dm && !req.broadcasts_only) {
                        kept.add(post);
                    }
                }
                posts = kept;
            }
            boolean more = found != null && found.more;
            found = messagesOf(posts, kind, true);
            found.more = more;
            remember(key, found);
            RelayConfig.log(TAG, String.format(Locale.US, "searchGlobal kind=%d q=%d chars -> %d msgs in %d ms", kind, q.length(), found.messages.size(), System.currentTimeMillis() - started));
        }
        int from = Math.max(0, req.offset_rate);
        int to = Math.min(found.messages.size(), from + Math.max(1, req.limit));
        res.count = found.messages.size();
        if (from < to) {
            res.messages.addAll(found.messages.subList(from, to));
        }
        if (to < found.messages.size()) {
            res.next_rate = to;
            res.flags |= TLObject.FLAG_0;
        }
        fillPeers(res, found);
        return res;
    }

    private static String extensionTerms(int kind) {
        String[] ext;
        switch (kind) {
            case MediaDataController.MEDIA_PHOTOVIDEO:
            case KIND_PHOTOS_ONLY:
            case KIND_VIDEOS_ONLY:
            case MediaDataController.MEDIA_GIF:
                ext = new String[]{"jpg", "jpeg", "png", "gif", "heic", "webp", "mp4", "mov", "m4v", "webm"};
                break;
            case MediaDataController.MEDIA_MUSIC:
            case MediaDataController.MEDIA_AUDIO:
                ext = new String[]{"mp3", "m4a", "aac", "flac", "wav", "ogg", "oga", "opus"};
                break;
            default:
                ext = new String[]{"pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "rtf", "md", "csv", "json", "xml", "log", "zip", "gz", "tar", "7z", "rar", "apk"};
        }
        StringBuilder terms = new StringBuilder();
        for (String e : ext) {
            append(terms, "ext:" + e);
        }
        return terms.toString();
    }

    /** Users and chats of the found messages. */
    void fillPeers(TLRPC.messages_Messages res, Found found) throws MattermostException {
        for (Channel channel : found.channels.values()) {
            if (!channel.isDirect()) {
                res.chats.add(path.directory.tlChannel(channel));
            }
        }
        res.users.addAll(path.users(found.users));
    }

    // MARK: - Counters and positions (shared media)

    TLObject counters(TLRPC.TL_messages_getSearchCounters req) throws Exception {
        String channelId = path.directory.channelIdOf(req.peer);
        Channel channel = channelId != null ? path.directory.channel(channelId) : null;
        ArrayList<TLObject> counters = new ArrayList<>();
        for (TLRPC.MessagesFilter filter : req.filters) {
            TLRPC.TL_messages_searchCounter counter = new TLRPC.TL_messages_searchCounter();
            counter.filter = filter;
            int kind = kindOf(filter);
            if (channel != null && kind != KIND_NONE && kind != KIND_TEXT) {
                try {
                    counter.count = chatResults(channel, kind, "", null, 0, 0, req.top_msg_id).messages.size();
                } catch (MattermostException e) {
                    RelayConfig.logError(TAG, "search counter", e);
                }
            }
            counters.add(counter);
        }
        return new RelayTl.RawVector(counters);
    }

    TLObject positions(TLRPC.TL_messages_getSearchResultsPositions req) throws Exception {
        TLRPC.TL_messages_searchResultsPositions res = new TLRPC.TL_messages_searchResultsPositions();
        String channelId = path.directory.channelIdOf(req.peer);
        int kind = kindOf(req.filter);
        if (channelId == null || kind == KIND_NONE || kind == KIND_TEXT) {
            return res;
        }
        Found found = chatResults(path.directory.channel(channelId), kind, "", null, 0, 0, 0);
        res.count = found.messages.size();
        int offset = 0;
        for (TLRPC.Message message : found.messages) {
            if (req.offset_id == 0 || message.id < req.offset_id) {
                TLRPC.TL_searchResultPosition position = new TLRPC.TL_searchResultPosition();
                position.msg_id = message.id;
                position.date = message.date;
                position.offset = offset;
                res.positions.add(position);
                if (req.limit > 0 && res.positions.size() >= req.limit) {
                    break;
                }
            }
            offset++;
        }
        return res;
    }

    // MARK: - contacts.search

    TLObject searchPeers(TLRPC.TL_contacts_search req) throws Exception {
        TLRPC.TL_contacts_found res = new TLRPC.TL_contacts_found();
        String q = req.q != null ? req.q.trim() : "";
        while (q.startsWith("@") || q.startsWith("~")) {
            q = q.substring(1);
        }
        if (q.isEmpty()) {
            return res;
        }
        String lower = q.toLowerCase(Locale.ROOT);
        int limit = req.limit > 0 ? req.limit : 20;
        RelayDirectory.Snapshot snapshot = path.directory.get(30_000);
        // Chats the user is in (channels and GMs; DMs are found as people).
        for (Channel channel : RelayDirectory.sortedChannels(snapshot)) {
            if (channel.isDirect()) {
                continue;
            }
            String title = path.directory.tlChannel(channel).title;
            if ((title != null && title.toLowerCase(Locale.ROOT).contains(lower)) || channel.name.toLowerCase(Locale.ROOT).contains(lower)) {
                TLRPC.TL_channel chat = path.directory.tlChannel(channel);
                res.chats.add(chat);
                TLRPC.TL_peerChannel peer = new TLRPC.TL_peerChannel();
                peer.channel_id = chat.id;
                res.my_results.add(peer);
            }
        }
        // People.
        List<String> userIds = new ArrayList<>();
        for (User user : MattermostDirectory.searchUsers(account.client, q, null, null, null, limit)) {
            if (user.isDeactivated()) {
                continue;
            }
            account.rememberUser(user);
            userIds.add(user.id);
        }
        for (TLRPC.User user : path.users(userIds)) {
            if (user.self && !userIds.contains(account.session.userId)) {
                continue;
            }
            res.users.add(user);
            TLRPC.TL_peerUser peer = new TLRPC.TL_peerUser();
            peer.user_id = user.id;
            res.my_results.add(peer);
        }
        // Public channels the user can join.
        Set<String> seen = new HashSet<>();
        for (Team team : snapshot.teams) {
            try {
                for (Channel channel : MattermostDirectory.searchTeamChannels(account.client, team.id, q, false)) {
                    if (snapshot.channels.containsKey(channel.id) || !seen.add(channel.id) || channel.isArchived()) {
                        continue;
                    }
                    TLRPC.TL_channel chat = path.directory.tlChannel(channel);
                    res.chats.add(chat);
                    TLRPC.TL_peerChannel peer = new TLRPC.TL_peerChannel();
                    peer.channel_id = chat.id;
                    res.results.add(peer);
                }
            } catch (MattermostException e) {
                RelayConfig.logError(TAG, "channel search in " + team.name, e);
            }
        }
        return res;
    }

    // MARK: - contacts.getContacts: the user directory

    /** Everyone in the user's teams (the "contacts" of New Message, Add Members and the Contacts tab). */
    TLObject contacts() throws Exception {
        TLRPC.TL_contacts_contacts res = new TLRPC.TL_contacts_contacts();
        Set<String> ids = new LinkedHashSet<>();
        for (Team team : path.directory.get(30_000).teams) {
            for (int page = 0; page < 10; page++) {
                List<User> users = MattermostDirectory.usersInTeam(account.client, team.id, page, 200);
                for (User user : users) {
                    if (user.isDeactivated() || user.id.equals(account.session.userId)) {
                        continue;
                    }
                    account.rememberUser(user);
                    ids.add(user.id);
                }
                if (users.size() < 200) {
                    break;
                }
            }
        }
        // DM partners outside the teams.
        ids.addAll(path.directory.any().dmByUser.keySet());
        ids.remove(account.session.userId);
        for (TLRPC.User user : path.users(ids)) {
            if (user.self) {
                continue;
            }
            TLRPC.TL_contact contact = new TLRPC.TL_contact();
            contact.user_id = user.id;
            contact.mutual = true;
            res.contacts.add(contact);
            res.users.add(user);
        }
        res.saved_count = 0;
        RelayConfig.log(TAG, "contacts: " + res.contacts.size() + " users");
        try {
            flaggedIds(false);
        } catch (MattermostException e) {
            RelayConfig.logError(TAG, "saved ids", e);
        }
        return res;
    }

    // MARK: - Pinning

    TLObject updatePinned(RelayBridge bridge, TLRPC.TL_messages_updatePinnedMessage req) throws Exception {
        String channelId = path.directory.channelIdOf(req.peer);
        RelayHistory.Unit unit = channelId != null ? path.history.unitAt(channelId, req.id) : null;
        if (unit == null) {
            return RelayTl.error(400, "MESSAGE_ID_INVALID");
        }
        Post post = unit.layout.post;
        if (req.unpin) {
            account.client.unpinPost(post.id);
        } else {
            account.client.pinPost(post.id);
        }
        post.isPinned = !req.unpin;
        path.history.rememberPost(post);
        invalidate(channelId);
        Channel channel = path.directory.channel(channelId);
        RelayConfig.log(TAG, (req.unpin ? "unpinned " : "pinned ") + post.id);
        return pinnedUpdates(bridge, channel, Collections.singletonList(textPartId(channel.id, post)), !req.unpin);
    }

    TLObject unpinAll(RelayBridge bridge, TLRPC.TL_messages_unpinAllMessages req) throws Exception {
        String channelId = path.directory.channelIdOf(req.peer);
        TLRPC.TL_messages_affectedHistory res = new TLRPC.TL_messages_affectedHistory();
        if (channelId == null) {
            return res;
        }
        Channel channel = path.directory.channel(channelId);
        List<Integer> ids = new ArrayList<>();
        for (Post post : account.client.pinnedPosts(channelId).getOrderedPosts()) {
            account.client.unpinPost(post.id);
            post.isPinned = false;
            path.history.rememberPost(post);
            int id = textPartId(channelId, post);
            if (id != 0) {
                ids.add(id);
            }
        }
        invalidate(channelId);
        if (!ids.isEmpty()) {
            TLRPC.Updates updates = pinnedUpdates(bridge, channel, ids, false);
            RelayLive.of(bridge, account).updates.deliver(((TLRPC.TL_updates) updates).updates, new RelayUpdates.Refs().chat(channel.isDirect() ? null : path.directory.tlChannel(channel)));
        }
        res.pts = account.db().pts(channel.isDirect() ? RelayDatabase.BOX_COMMON : RelayDatabase.channelBox(-path.directory.dialogId(channel)));
        res.pts_count = 0;
        return res;
    }

    /** The local id of the message with a post's text (mapping the post if needed), or 0. */
    int textPartId(String channelId, Post post) throws Exception {
        if (path.history.localIdOf(channelId, post.id) == 0) {
            path.history.mapSingle(channelId, post);
        }
        RelayConvert.Layout layout = new RelayConvert.Layout(post);
        String partId = org.telegram.relay.mattermost.ids.PostParts.partId(post.id, layout.textPart);
        org.telegram.relay.mattermost.ids.MessageIdEntry entry = account.messageIdStore().entries(channelId, Collections.singletonList(partId)).get(partId);
        return entry != null && !entry.isDeleted ? entry.localId : 0;
    }

    /** {@code updatePinnedMessages} / {@code updatePinnedChannelMessages}, stamped, as one {@code TL_updates}. */
    TLRPC.Updates pinnedUpdates(RelayBridge bridge, Channel channel, List<Integer> ids, boolean pinned) throws Exception {
        TLRPC.Update u;
        if (channel.isDirect()) {
            TL_update.TL_updatePinnedMessages p = new TL_update.TL_updatePinnedMessages();
            p.peer = path.directory.peer(channel);
            p.messages.addAll(ids);
            p.pinned = pinned;
            u = p;
        } else {
            TL_update.TL_updatePinnedChannelMessages p = new TL_update.TL_updatePinnedChannelMessages();
            p.channel_id = -path.directory.dialogId(channel);
            p.messages.addAll(ids);
            p.pinned = pinned;
            u = p;
        }
        RelayLive.of(bridge, account).updates.stamp(u, channel.isDirect() ? path.directory.dialogId(channel) : 0);
        TLRPC.TL_updates res = new TLRPC.TL_updates();
        res.updates.add(u);
        if (!channel.isDirect()) {
            res.chats.add(path.directory.tlChannel(channel));
        } else {
            res.users.addAll(path.users(Collections.singletonList(path.directory.dmUser(channel))));
        }
        res.date = RelayStubs.now(account.account);
        return res;
    }

    /** The newest pinned message of a chat (for {@code channelFull.pinned_msg_id}), or 0. */
    int newestPinnedId(Channel channel) {
        try {
            Found found = chatResults(channel, KIND_PINNED, "", null, 0, 0, 0);
            return found.messages.isEmpty() ? 0 : found.messages.get(0).id;
        } catch (Exception e) {
            RelayConfig.logError(TAG, "pinned of " + channel.id, e);
            return 0;
        }
    }

    /** A post edited elsewhere: pinned or unpinned → pinned update; cached lists of the chat are dropped. */
    void onPostEdited(RelayLive live, Channel channel, Post before, Post after) {
        invalidate(channel.id);
        if (before == null || before.isPinned == after.isPinned) {
            return;
        }
        try {
            int id = textPartId(channel.id, after);
            if (id == 0) {
                return;
            }
            TLRPC.Updates updates = pinnedUpdates(live.bridge, channel, Collections.singletonList(id), after.isPinned);
            TLRPC.TL_updates batch = (TLRPC.TL_updates) updates;
            RelayUpdates.Refs refs = new RelayUpdates.Refs();
            if (!channel.isDirect()) {
                refs.chat(path.directory.tlChannel(channel));
            } else {
                refs.user(path.directory.dmUser(channel));
            }
            live.updates.deliver(batch.updates, refs);
            RelayConfig.log(TAG, "live: " + (after.isPinned ? "pinned " : "unpinned ") + after.id);
        } catch (Exception e) {
            RelayConfig.logError(TAG, "pinned update", e);
        }
    }

    // MARK: - Saved posts and recent mentions (RelayFinding)

    static final String FLAGGED = "flagged_post";
    private volatile Set<String> flagged;
    private volatile long flaggedAt;

    /** The user's saved (flagged) post ids, from the preferences (cached a minute). */
    Set<String> flaggedIds(boolean refresh) throws MattermostException {
        Set<String> current = flagged;
        if (current != null && !refresh && System.currentTimeMillis() - flaggedAt < 60_000) {
            return current;
        }
        Set<String> ids = java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<String, Boolean>());
        for (org.telegram.relay.mattermost.model.Preference p : account.client.preferences()) {
            if (FLAGGED.equals(p.category) && "true".equals(p.value)) {
                ids.add(p.name);
            }
        }
        flagged = ids;
        flaggedAt = System.currentTimeMillis();
        return ids;
    }

    boolean flaggedLoaded() {
        return flagged != null;
    }

    /** Cached flag state (no I/O; false until the first load). */
    boolean isFlaggedCached(String postId) {
        Set<String> current = flagged;
        return current != null && current.contains(postId);
    }

    void setFlagged(String postId, boolean flag) throws MattermostException {
        org.telegram.relay.mattermost.model.Preference p = new org.telegram.relay.mattermost.model.Preference(account.session.userId, FLAGGED, postId, "true");
        if (flag) {
            MattermostDirectory.savePreferences(account.client, account.session.userId, Collections.singletonList(p));
        } else {
            MattermostDirectory.deletePreferences(account.client, account.session.userId, Collections.singletonList(p));
        }
        Set<String> current = flaggedIds(false);
        if (flag) {
            current.add(postId);
        } else {
            current.remove(postId);
        }
        invalidate(null);
        RelayConfig.log(TAG, (flag ? "saved post " : "unsaved post ") + postId);
    }

    Found saved() throws Exception {
        Found found = cached("saved");
        if (found != null) {
            return found;
        }
        PostList list = MattermostDirectory.flaggedPosts(account.client, null, 0, 200);
        List<Post> posts = list.getOrderedPosts();
        Set<String> ids = new HashSet<>();
        for (Post post : posts) {
            ids.add(post.id);
        }
        Set<String> current = flaggedIds(false);
        current.addAll(ids);
        found = messagesOf(posts, KIND_TEXT, true);
        remember("saved", found);
        return found;
    }

    /** Mattermost's "Recent Mentions": an OR search for the user's mention keys, in every team. */
    Found mentions() throws Exception {
        Found found = cached("mentions");
        if (found != null) {
            return found;
        }
        User me = account.cachedUser(account.session.userId);
        if (me == null) {
            me = account.client.me();
            account.rememberUser(me);
        }
        List<String> keys = new ArrayList<>();
        keys.add("@" + me.username);
        if (me.notifyProps != null) {
            if ("true".equals(me.notifyProps.get("first_name")) && me.firstName != null && !me.firstName.isEmpty()) {
                keys.add(me.firstName);
            }
            String custom = me.notifyProps.get("mention_keys");
            if (custom != null) {
                for (String key : custom.split(",")) {
                    String k = key.trim();
                    if (!k.isEmpty() && !keys.contains(k) && !k.equalsIgnoreCase("@channel") && !k.equalsIgnoreCase("@all") && !k.equalsIgnoreCase("@here")) {
                        keys.add(k.contains(" ") ? "\"" + k + "\"" : k);
                    }
                }
            }
        }
        StringBuilder terms = new StringBuilder();
        for (String key : keys) {
            append(terms, key);
        }
        List<String> teams = new ArrayList<>();
        for (Team team : path.directory.any().teams) {
            teams.add(team.id);
        }
        List<Post> posts = searchPosts(teams, terms.toString(), true, null);
        // Polish 2a (QA-17): the user's own posts are not mentions (as Mattermost's Recent Mentions); an own reply
        // that names the user also listed its thread root.
        List<Post> others = new ArrayList<>();
        for (Post post : posts) {
            if (!account.session.userId.equals(post.userId)) {
                others.add(post);
            }
        }
        found = messagesOf(others, KIND_TEXT, true);
        remember("mentions", found);
        return found;
    }
}
