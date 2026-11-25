package org.telegram.relay.bridge;

import com.google.gson.JsonObject;

import org.telegram.relay.RelayConfig;
import org.telegram.relay.account.RelayAccount;
import org.telegram.relay.account.RelayDatabase;
import org.telegram.relay.mattermost.MattermostDirectory;
import org.telegram.relay.mattermost.MattermostException;
import org.telegram.relay.mattermost.model.Channel;
import org.telegram.relay.mattermost.model.ChannelMember;
import org.telegram.relay.mattermost.model.ChannelStats;
import org.telegram.relay.mattermost.model.TeamMember;
import org.telegram.relay.mattermost.model.User;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_update;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Phase A7b, membership: member lists with roles, what the user may do in a channel (Mattermost roles and
 * permissions → Telegram admin rights), add/remove members, leave, join, edit name/purpose/header,
 * archive/unarchive.
 *
 * <p><b>Rights.</b> Mattermost lets every member of a channel (by default) add and remove members, edit the
 * channel and archive it; channel admins can also change roles. Telegram expresses "may do X" through the
 * chat's {@code admin_rights}, so {@link #applyRights} gives the user's own {@code TL_channel} the admin
 * rights the user's permissions allow. The admin badge in member lists comes from the participant type
 * ({@code channelParticipantAdmin} for channel admins), which is independent. Archived channels are
 * read-only: no admin rights and every default right banned.
 */
final class RelayMembers {
    private static final String TAG = RelayConfig.TAG_BRIDGE;

    private static final WeakHashMap<RelayAccount, RelayMembers> instances = new WeakHashMap<>();

    static RelayMembers of(RelayAccount account) {
        synchronized (instances) {
            RelayMembers members = instances.get(account);
            if (members == null) {
                instances.put(account, members = new RelayMembers(account));
            }
            return members;
        }
    }

    static void register(Map<Class<?>, RelayHandlers.Handler> map) {
        map.put(TLRPC.TL_channels_getParticipants.class, (bridge, account, req) -> of(account).getParticipants((TLRPC.TL_channels_getParticipants) req));
        map.put(TLRPC.TL_channels_getParticipant.class, (bridge, account, req) -> of(account).getParticipant((TLRPC.TL_channels_getParticipant) req));
        map.put(TLRPC.TL_channels_inviteToChannel.class, (bridge, account, req) -> of(account).invite(bridge, (TLRPC.TL_channels_inviteToChannel) req));
        map.put(TLRPC.TL_channels_editBanned.class, (bridge, account, req) -> of(account).editBanned(bridge, (TLRPC.TL_channels_editBanned) req));
        map.put(TLRPC.TL_channels_editAdmin.class, (bridge, account, req) -> of(account).editAdmin(bridge, (TLRPC.TL_channels_editAdmin) req));
        map.put(TLRPC.TL_channels_leaveChannel.class, (bridge, account, req) -> of(account).leave(bridge, (TLRPC.TL_channels_leaveChannel) req));
        map.put(TLRPC.TL_channels_joinChannel.class, (bridge, account, req) -> of(account).join(bridge, (TLRPC.TL_channels_joinChannel) req));
        map.put(TLRPC.TL_channels_deleteChannel.class, (bridge, account, req) -> of(account).archive(bridge, (TLRPC.TL_channels_deleteChannel) req));
        map.put(TLRPC.TL_channels_editTitle.class, (bridge, account, req) -> of(account).editTitle(bridge, (TLRPC.TL_channels_editTitle) req));
        map.put(TLRPC.TL_messages_editChatAbout.class, (bridge, account, req) -> of(account).editAbout(bridge, (TLRPC.TL_messages_editChatAbout) req));
        // Info screens of channels ask for these; Mattermost has no invite links or channel recommendations.
        map.put(TLRPC.TL_messages_getExportedChatInvites.class, (bridge, account, req) -> new TLRPC.TL_messages_exportedChatInvites());
        map.put(TLRPC.TL_channels_getChannelRecommendations.class, (bridge, account, req) -> new TLRPC.TL_messages_chats());
    }

    final RelayAccount account;
    final RelayReadPath path;

    private RelayMembers(RelayAccount account) {
        this.account = account;
        this.path = RelayReadPath.of(account);
    }

    // MARK: - Permissions

    private final Map<String, Set<String>> rolePermissions = new HashMap<>();
    private volatile Map<String, TeamMember> teamMembers;
    private volatile long teamMembersAt;
    private volatile long rolesAt;

    private synchronized Map<String, TeamMember> teamMembers() {
        Map<String, TeamMember> current = teamMembers;
        if (current != null && System.currentTimeMillis() - teamMembersAt < 10 * 60_000) {
            return current;
        }
        Map<String, TeamMember> map = new HashMap<>();
        try {
            for (TeamMember member : account.client.teamMembers()) {
                map.put(member.teamId, member);
            }
            teamMembers = map;
            teamMembersAt = System.currentTimeMillis();
        } catch (MattermostException e) {
            RelayConfig.logError(TAG, "team members", e);
            return current != null ? current : map;
        }
        return map;
    }

    private static void addRoles(Set<String> into, String roles) {
        if (roles == null) {
            return;
        }
        for (String role : roles.split(" ")) {
            if (!role.trim().isEmpty()) {
                into.add(role.trim());
            }
        }
    }

    /** Role names of the user in a channel (system, team and channel roles). */
    Set<String> roleNames(Channel channel, ChannelMember member) {
        Set<String> names = new LinkedHashSet<>();
        User me = account.cachedUser(account.session.userId);
        if (me != null) {
            addRoles(names, me.roles);
        } else {
            names.add("system_user");
        }
        if (channel.teamId != null && !channel.teamId.isEmpty()) {
            TeamMember teamMember = teamMembers().get(channel.teamId);
            if (teamMember != null) {
                addRoles(names, teamMember.roles);
                if (teamMember.schemeUser) names.add("team_user");
                if (teamMember.schemeAdmin) names.add("team_admin");
            }
        }
        if (member != null) {
            addRoles(names, member.roles);
            if (member.schemeUser) names.add("channel_user");
            if (member.schemeAdmin) names.add("channel_admin");
        }
        return names;
    }

    /** The user's permissions in a channel; null when the roles cannot be loaded (callers then use defaults). */
    synchronized Set<String> permissions(Channel channel, ChannelMember member) {
        Set<String> names = roleNames(channel, member);
        if (System.currentTimeMillis() - rolesAt > 10 * 60_000) {
            rolePermissions.clear();
        }
        List<String> missing = new ArrayList<>();
        for (String name : names) {
            if (!rolePermissions.containsKey(name)) {
                missing.add(name);
            }
        }
        if (!missing.isEmpty()) {
            try {
                for (MattermostDirectory.Role role : MattermostDirectory.rolesByNames(account.client, missing)) {
                    rolePermissions.put(role.name, new HashSet<>(role.permissions));
                }
                for (String name : missing) {
                    if (!rolePermissions.containsKey(name)) {
                        rolePermissions.put(name, Collections.<String>emptySet());
                    }
                }
                rolesAt = System.currentTimeMillis();
            } catch (MattermostException e) {
                RelayConfig.logError(TAG, "roles", e);
                return null;
            }
        }
        Set<String> permissions = new HashSet<>();
        for (String name : names) {
            Set<String> p = rolePermissions.get(name);
            if (p != null) {
                permissions.addAll(p);
            }
        }
        if (names.contains("system_admin")) {
            permissions.add("manage_system");
        }
        return permissions;
    }

    /** What the user may do in one channel. */
    static final class Rights {
        boolean member;
        boolean archived;
        boolean channelAdmin;
        boolean changeInfo;
        boolean manageMembers;
        boolean manageRoles;
        boolean deleteOthers;
        boolean archive;
        boolean unarchive;
        boolean pin = true;
    }

    Rights rights(Channel channel) {
        Rights r = new Rights();
        ChannelMember member;
        try {
            member = path.directory.any().members.get(channel.id);
        } catch (MattermostException e) {
            member = null;
        }
        r.member = member != null;
        r.archived = channel.isArchived();
        r.channelAdmin = member != null && (member.schemeAdmin || (member.roles != null && member.roles.contains("channel_admin")));
        Set<String> p = permissions(channel, member);
        boolean all = p != null && p.contains("manage_system");
        boolean open = channel.isOpen();
        if (p == null) {
            // Mattermost's defaults when the roles are unknown.
            r.changeInfo = r.manageMembers = r.archive = r.member;
            r.manageRoles = r.channelAdmin;
        } else {
            r.changeInfo = all || p.contains(open ? "manage_public_channel_properties" : "manage_private_channel_properties");
            r.manageMembers = all || p.contains(open ? "manage_public_channel_members" : "manage_private_channel_members");
            r.manageRoles = all || p.contains("manage_channel_roles");
            r.deleteOthers = all || p.contains("delete_others_posts");
            r.archive = all || p.contains(open ? "delete_public_channel" : "delete_private_channel");
            r.unarchive = all || p.contains("manage_team") || p.contains("manage_system");
        }
        if (channel.isGroup()) {
            // GMs: no name, no members to add or remove, nothing to archive.
            r.changeInfo = r.manageMembers = r.manageRoles = r.archive = r.unarchive = false;
        }
        if (!r.member) {
            r.changeInfo = r.manageMembers = r.manageRoles = r.deleteOthers = r.archive = r.pin = false;
        }
        return r;
    }

    /** Gives the user's {@code TL_channel} its rights (called by {@link RelayDirectory#tlChannel}). */
    void applyRights(Channel channel, TLRPC.TL_channel c) {
        if (channel.isDirect()) {
            return;
        }
        Rights r = rights(channel);
        c.creator = false;
        c.left = !r.member;
        TLRPC.TL_chatBannedRights defaults = new TLRPC.TL_chatBannedRights();
        defaults.until_date = Integer.MAX_VALUE;
        defaults.edit_rank = true; // Mattermost has no member tags ("Add Tag")
        if (r.archived) {
            c.admin_rights = null;
            c.flags &= ~TLObject.FLAG_14;
            defaults.send_messages = defaults.send_media = defaults.send_stickers = defaults.send_gifs = defaults.send_games = true;
            defaults.send_inline = defaults.embed_links = defaults.send_polls = defaults.change_info = defaults.invite_users = true;
            defaults.pin_messages = defaults.send_photos = defaults.send_videos = defaults.send_roundvideos = defaults.send_audios = true;
            defaults.send_voices = defaults.send_docs = defaults.send_plain = defaults.send_reactions = true;
            TLRPC.TL_chatBannedRights own = new TLRPC.TL_chatBannedRights();
            own.until_date = Integer.MAX_VALUE;
            own.send_messages = own.send_media = own.send_plain = own.send_reactions = own.pin_messages = own.change_info = own.invite_users = true;
            own.send_photos = own.send_videos = own.send_docs = own.send_audios = own.send_voices = own.send_roundvideos = true;
            own.send_stickers = own.send_gifs = own.send_games = own.send_inline = own.embed_links = own.send_polls = true;
            own.edit_rank = true;
            c.banned_rights = own;
            c.flags |= TLObject.FLAG_15;
        } else if (r.member) {
            TLRPC.TL_chatAdminRights admin = new TLRPC.TL_chatAdminRights();
            admin.change_info = r.changeInfo;
            admin.invite_users = r.manageMembers;
            admin.ban_users = r.manageMembers;
            admin.add_admins = r.manageRoles;
            admin.delete_messages = r.deleteOthers;
            admin.pin_messages = r.pin;
            admin.other = true;
            c.admin_rights = admin;
            c.flags |= TLObject.FLAG_14;
            defaults.invite_users = !r.manageMembers;
            defaults.change_info = !r.changeInfo;
        }
        c.default_banned_rights = defaults;
        c.flags |= TLObject.FLAG_18;
        rightsByChat.put(c.id, r);
    }

    /** Rights of the chats handed to the client, by TL chat id (for the UI hooks, see {@link RelayFinding}). */
    static final Map<Long, Rights> rightsByChat = Collections.synchronizedMap(new HashMap<Long, Rights>());

    // MARK: - Participants

    private Channel channelOf(TLRPC.InputChannel input) throws MattermostException {
        String channelId = input != null ? account.db().mmId(RelayDatabase.KIND_CHANNEL, input.channel_id) : null;
        return channelId != null ? path.directory.channel(channelId) : null;
    }

    private static boolean isAdmin(ChannelMember member) {
        return member != null && (member.schemeAdmin || (member.roles != null && member.roles.contains("channel_admin")));
    }

    private TLRPC.ChannelParticipant participant(Channel channel, String userId, ChannelMember member, boolean canEdit) {
        long tlId = account.userTlId(userId);
        int date = (int) (channel.createAt / 1000);
        boolean self = userId.equals(account.session.userId);
        if (isAdmin(member)) {
            TLRPC.TL_channelParticipantAdmin p = new TLRPC.TL_channelParticipantAdmin();
            p.peer = new TLRPC.TL_peerUser();
            p.peer.user_id = tlId;
            p.user_id = tlId;
            p.self = self;
            p.inviter_id = self ? tlId : 0;
            p.can_edit = canEdit && !self;
            p.promoted_by = tlId;
            p.date = date;
            TLRPC.TL_chatAdminRights rights = new TLRPC.TL_chatAdminRights();
            rights.change_info = rights.invite_users = rights.ban_users = rights.pin_messages = rights.add_admins = true;
            rights.delete_messages = true;
            rights.other = true;
            p.admin_rights = rights;
            return p;
        }
        if (self) {
            TLRPC.TL_channelParticipantSelf p = new TLRPC.TL_channelParticipantSelf();
            p.user_id = tlId;
            p.peer = new TLRPC.TL_peerUser();
            p.peer.user_id = tlId;
            p.inviter_id = tlId;
            p.date = date;
            return p;
        }
        TLRPC.TL_channelParticipant p = new TLRPC.TL_channelParticipant();
        p.peer = new TLRPC.TL_peerUser();
        p.peer.user_id = tlId;
        p.user_id = tlId;
        p.date = date;
        return p;
    }

    /** Users of a channel in Mattermost's order, {@code [offset, offset+limit)}. */
    private List<User> usersInChannel(String channelId, int offset, int limit) throws MattermostException {
        List<User> result = new ArrayList<>();
        int perPage = 200;
        int page = offset / perPage;
        int skip = offset % perPage;
        while (result.size() < limit) {
            List<User> users = account.client.usersInChannel(channelId, page, perPage);
            for (int i = skip; i < users.size() && result.size() < limit; i++) {
                result.add(users.get(i));
            }
            if (users.size() < perPage) {
                break;
            }
            page++;
            skip = 0;
        }
        return result;
    }

    TLObject getParticipants(TLRPC.TL_channels_getParticipants req) throws Exception {
        TLRPC.TL_channels_channelParticipants res = new TLRPC.TL_channels_channelParticipants();
        Channel channel = channelOf(req.channel);
        if (channel == null) {
            return RelayTl.error(400, "CHANNEL_INVALID");
        }
        if (req.filter instanceof TLRPC.TL_channelParticipantsKicked || req.filter instanceof TLRPC.TL_channelParticipantsBanned) {
            return res;
        }
        int limit = req.limit > 0 ? Math.min(req.limit, 500) : 200;
        String q = req.filter != null && req.filter.q != null ? req.filter.q.trim() : "";
        List<User> users;
        boolean counted = false;
        if (!q.isEmpty() && (req.filter instanceof TLRPC.TL_channelParticipantsSearch || req.filter instanceof TLRPC.TL_channelParticipantsContacts
                || req.filter instanceof TLRPC.TL_channelParticipantsMentions)) {
            users = req.offset > 0 ? new ArrayList<User>() : MattermostDirectory.searchUsers(account.client, q, null, channel.id, null, limit);
        } else if (req.filter instanceof TLRPC.TL_channelParticipantsAdmins || req.filter instanceof TLRPC.TL_channelParticipantsBots) {
            users = new ArrayList<>();
            if (req.offset == 0) {
                for (User user : usersInChannel(channel.id, 0, 2000)) {
                    users.add(user);
                }
            }
        } else {
            users = usersInChannel(channel.id, Math.max(0, req.offset), limit);
            counted = true;
        }
        Map<String, ChannelMember> members = new HashMap<>();
        List<String> ids = new ArrayList<>();
        for (User user : users) {
            account.rememberUser(user);
            ids.add(user.id);
        }
        for (int start = 0; start < ids.size(); start += 200) {
            for (ChannelMember member : MattermostDirectory.channelMembersByIds(account.client, channel.id, ids.subList(start, Math.min(ids.size(), start + 200)))) {
                members.put(member.userId, member);
            }
        }
        boolean canEdit = rights(channel).manageRoles;
        List<String> listed = new ArrayList<>();
        for (User user : users) {
            ChannelMember member = members.get(user.id);
            if (req.filter instanceof TLRPC.TL_channelParticipantsAdmins && !isAdmin(member)) {
                continue;
            }
            if (req.filter instanceof TLRPC.TL_channelParticipantsBots && !user.isBot) {
                continue;
            }
            res.participants.add(participant(channel, user.id, member, canEdit));
            listed.add(user.id);
        }
        res.users.addAll(path.users(listed));
        res.count = res.participants.size();
        if (counted) {
            try {
                ChannelStats stats = account.client.channelStats(channel.id);
                res.count = (int) Math.max(res.participants.size(), stats.memberCount);
            } catch (MattermostException e) {
                RelayConfig.logError(TAG, "channel stats", e);
            }
        }
        RelayConfig.log(TAG, "participants " + channel.name + " filter=" + (req.filter != null ? req.filter.getClass().getSimpleName() : "null")
                + " offset=" + req.offset + " -> " + res.participants.size() + "/" + res.count);
        return res;
    }

    TLObject getParticipant(TLRPC.TL_channels_getParticipant req) throws Exception {
        Channel channel = channelOf(req.channel);
        if (channel == null) {
            return RelayTl.error(400, "CHANNEL_INVALID");
        }
        String userId;
        if (req.participant instanceof TLRPC.TL_inputPeerSelf || (req.participant != null && req.participant.user_id == account.selfTlId())) {
            userId = account.session.userId;
        } else if (req.participant != null && req.participant.user_id != 0) {
            userId = account.db().mmId(RelayDatabase.KIND_USER, req.participant.user_id);
        } else {
            return RelayTl.error(400, "PARTICIPANT_ID_INVALID");
        }
        if (userId == null) {
            return RelayTl.error(400, "USER_ID_INVALID");
        }
        ChannelMember member;
        try {
            member = account.client.channelMember(channel.id, userId);
        } catch (MattermostException e) {
            if (e.isNotFound() || e.getStatusCode() == 403) {
                return RelayTl.error(400, "USER_NOT_PARTICIPANT");
            }
            throw e;
        }
        TLRPC.TL_channels_channelParticipant res = new TLRPC.TL_channels_channelParticipant();
        res.participant = participant(channel, userId, member, rights(channel).manageRoles);
        res.users.addAll(path.users(Collections.singletonList(userId)));
        res.chats.add(path.directory.tlChannel(channel));
        return res;
    }

    // MARK: - Membership changes

    /** A {@code TL_updates} with {@code updateChannel} for one chat (the client reloads the chat and its info). */
    TLRPC.TL_updates channelUpdate(Channel channel) {
        TLRPC.TL_updates res = new TLRPC.TL_updates();
        TL_update.TL_updateChannel u = new TL_update.TL_updateChannel();
        u.channel_id = -path.directory.dialogId(channel);
        res.updates.add(u);
        res.chats.add(path.directory.tlChannel(channel));
        res.date = RelayStubs.now(account.account);
        return res;
    }

    private String userIdOf(TLRPC.InputUser input) {
        if (input instanceof TLRPC.TL_inputUserSelf) {
            return account.session.userId;
        }
        return input != null ? account.db().mmId(RelayDatabase.KIND_USER, input.user_id) : null;
    }

    private String userIdOf(TLRPC.InputPeer peer) {
        if (peer instanceof TLRPC.TL_inputPeerSelf) {
            return account.session.userId;
        }
        return peer != null && peer.user_id != 0 ? account.db().mmId(RelayDatabase.KIND_USER, peer.user_id) : null;
    }

    TLObject invite(RelayBridge bridge, TLRPC.TL_channels_inviteToChannel req) throws Exception {
        Channel channel = channelOf(req.channel);
        if (channel == null) {
            return RelayTl.error(400, "CHANNEL_INVALID");
        }
        TLRPC.TL_messages_invitedUsers res = new TLRPC.TL_messages_invitedUsers();
        int added = 0;
        for (TLRPC.InputUser input : req.users) {
            String userId = userIdOf(input);
            if (userId == null) {
                continue;
            }
            try {
                account.client.addChannelMember(channel.id, userId);
                added++;
            } catch (MattermostException e) {
                RelayConfig.logError(TAG, "add member " + userId + " to " + channel.name, e);
                if (req.users.size() == 1) {
                    return RelayTl.error(e.getStatusCode() == 403 ? 403 : 400, e.getStatusCode() == 403 ? "CHAT_ADMIN_REQUIRED" : "USER_NOT_MUTUAL_CONTACT");
                }
                TLRPC.TL_missingInvitee missing = new TLRPC.TL_missingInvitee();
                missing.user_id = input.user_id;
                res.missing_invitees.add(missing);
            }
        }
        RelayConfig.log(TAG, "added " + added + " member(s) to " + channel.name);
        path.directory.invalidate();
        res.updates = channelUpdate(channel);
        return res;
    }

    TLObject editBanned(RelayBridge bridge, TLRPC.TL_channels_editBanned req) throws Exception {
        Channel channel = channelOf(req.channel);
        String userId = userIdOf(req.participant);
        if (channel == null || userId == null) {
            return RelayTl.error(400, "PARTICIPANT_ID_INVALID");
        }
        if (req.banned_rights != null && req.banned_rights.view_messages) {
            try {
                account.client.removeChannelMember(channel.id, userId);
            } catch (MattermostException e) {
                if (e.getStatusCode() == 403) {
                    return RelayTl.error(403, "CHAT_ADMIN_REQUIRED");
                }
                throw e;
            }
            RelayConfig.log(TAG, "removed " + userId + " from " + channel.name);
        }
        path.directory.invalidate();
        return channelUpdate(channel);
    }

    /** Promote/demote: Mattermost channel admin (scheme role). */
    TLObject editAdmin(RelayBridge bridge, TLRPC.TL_channels_editAdmin req) throws Exception {
        Channel channel = channelOf(req.channel);
        String userId = userIdOf(req.user_id);
        if (channel == null || userId == null) {
            return RelayTl.error(400, "USER_ID_INVALID");
        }
        TLRPC.TL_chatAdminRights rights = req.admin_rights;
        boolean admin = rights != null && (rights.change_info || rights.ban_users || rights.invite_users || rights.add_admins || rights.pin_messages || rights.delete_messages || rights.other);
        try {
            MattermostDirectory.setChannelAdmin(account.client, channel.id, userId, admin);
        } catch (MattermostException e) {
            if (e.getStatusCode() == 403) {
                return RelayTl.error(403, "CHAT_ADMIN_REQUIRED");
            }
            throw e;
        }
        RelayConfig.log(TAG, (admin ? "made admin " : "made member ") + userId + " in " + channel.name);
        path.directory.invalidate();
        return channelUpdate(channel);
    }

    TLObject leave(RelayBridge bridge, TLRPC.TL_channels_leaveChannel req) throws Exception {
        Channel channel = channelOf(req.channel);
        if (channel == null) {
            return RelayTl.error(400, "CHANNEL_INVALID");
        }
        account.client.removeChannelMember(channel.id, account.session.userId);
        RelayConfig.log(TAG, "left " + channel.name);
        path.directory.invalidate();
        RelayLive.of(bridge, account).forget(channel.id, -path.directory.dialogId(channel));
        TLRPC.TL_updates res = new TLRPC.TL_updates();
        res.date = RelayStubs.now(account.account);
        return res;
    }

    TLObject join(RelayBridge bridge, TLRPC.TL_channels_joinChannel req) throws Exception {
        Channel channel = channelOf(req.channel);
        if (channel == null) {
            return RelayTl.error(400, "CHANNEL_INVALID");
        }
        account.client.addChannelMember(channel.id, account.session.userId);
        RelayConfig.log(TAG, "joined " + channel.name);
        path.directory.invalidate();
        path.directory.get(0);
        TLRPC.TL_chatInviteJoinResultOk res = new TLRPC.TL_chatInviteJoinResultOk();
        res.updates = channelUpdate(channel);
        path.forgetKnown(channel.id);
        return res;
    }

    TLObject archive(RelayBridge bridge, TLRPC.TL_channels_deleteChannel req) throws Exception {
        Channel channel = channelOf(req.channel);
        if (channel == null) {
            return RelayTl.error(400, "CHANNEL_INVALID");
        }
        try {
            MattermostDirectory.archiveChannel(account.client, channel.id);
        } catch (MattermostException e) {
            if (e.getStatusCode() == 403) {
                return RelayTl.error(403, "CHAT_ADMIN_REQUIRED");
            }
            throw e;
        }
        RelayConfig.log(TAG, "archived " + channel.name);
        channel.deleteAt = System.currentTimeMillis();
        path.directory.invalidate();
        return channelUpdate(channel);
    }

    Channel unarchive(String channelId) throws Exception {
        Channel channel = MattermostDirectory.restoreChannel(account.client, channelId);
        RelayConfig.log(TAG, "unarchived " + channel.name);
        path.directory.invalidate();
        return channel;
    }

    TLObject editTitle(RelayBridge bridge, TLRPC.TL_channels_editTitle req) throws Exception {
        Channel channel = channelOf(req.channel);
        if (channel == null) {
            return RelayTl.error(400, "CHANNEL_INVALID");
        }
        JsonObject patch = new JsonObject();
        patch.addProperty("display_name", req.title != null ? req.title.trim() : "");
        Channel patched = patch(channel, patch);
        if (patched == null) {
            return RelayTl.error(403, "CHAT_ADMIN_REQUIRED");
        }
        return channelUpdate(patched);
    }

    TLObject editAbout(RelayBridge bridge, TLRPC.TL_messages_editChatAbout req) throws Exception {
        String channelId = path.directory.channelIdOf(req.peer);
        Channel channel = channelId != null ? path.directory.channel(channelId) : null;
        if (channel == null || channel.isDirect()) {
            return RelayTl.error(400, "PEER_ID_INVALID");
        }
        JsonObject patch = new JsonObject();
        patch.addProperty("purpose", req.about != null ? req.about.trim() : "");
        return patch(channel, patch) != null ? new TLRPC.TL_boolTrue() : RelayTl.error(403, "CHAT_ADMIN_REQUIRED");
    }

    /** {@code PUT /channels/{id}/patch}; null when refused. */
    Channel patch(Channel channel, JsonObject patch) throws MattermostException {
        try {
            Channel patched = MattermostDirectory.patchChannel(account.client, channel.id, patch);
            RelayConfig.log(TAG, "patched " + channel.name + " " + patch.keySet());
            path.directory.invalidate();
            return patched;
        } catch (MattermostException e) {
            if (e.getStatusCode() == 403) {
                RelayConfig.logError(TAG, "patch refused", e);
                return null;
            }
            throw e;
        }
    }

    // MARK: - Live (RelayLive)

    /** Someone else joined or left a chat of the user: {@code updateChannel} makes open info screens reload. */
    void onMembersChanged(RelayLive live, String channelId) {
        try {
            Channel channel = path.directory.any().channels.get(channelId);
            if (channel == null || channel.isDirect() || !path.isKnown(channelId)) {
                return;
            }
            TLRPC.TL_updates updates = channelUpdate(channel);
            live.updates.deliver(updates.updates, new RelayUpdates.Refs().chat(updates.chats.get(0)));
            RelayConfig.log(TAG, "live: members of " + channel.name + " changed");
        } catch (Exception e) {
            RelayConfig.logError(TAG, "members changed", e);
        }
    }

    /**
     * A chat of the user was archived: it stays, read-only (Mattermost keeps the membership). False when the
     * user is not a member any more (the caller drops the chat).
     */
    boolean onArchived(RelayLive live, String channelId) {
        try {
            Channel channel = account.client.channel(channelId);
            if (channel == null || !channel.isArchived()) {
                return false;
            }
            try {
                account.client.channelMember(channelId);
            } catch (MattermostException e) {
                return false;
            }
            TLRPC.TL_updates updates = channelUpdate(channel);
            live.updates.deliver(updates.updates, new RelayUpdates.Refs().chat(updates.chats.get(0)));
            RelayConfig.log(TAG, "live: " + channel.name + " archived, read-only");
            return true;
        } catch (Exception e) {
            RelayConfig.logError(TAG, "archived", e);
            return false;
        }
    }

    // MARK: - Creating chats (RelayFinding)

    /** A URL name for a display name: lowercase letters, digits, '-' and '_', starting and ending with a letter or digit. */
    static String slug(String displayName) {
        String s = displayName == null ? "" : java.text.Normalizer.normalize(displayName, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder();
        for (char ch : s.toCharArray()) {
            if ((ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9')) {
                out.append(ch);
            } else if ((ch == '-' || ch == '_' || ch == ' ' || ch == '.') && out.length() > 0 && out.charAt(out.length() - 1) != '-') {
                out.append('-');
            }
        }
        while (out.length() > 0 && out.charAt(out.length() - 1) == '-') {
            out.setLength(out.length() - 1);
        }
        if (out.length() > 60) {
            out.setLength(60);
        }
        return out.toString();
    }

    /** Whether a URL name is valid for Mattermost (2–64 characters, as {@link #slug} makes them). */
    static boolean validName(String name) {
        return name != null && name.matches("[a-z0-9]([a-z0-9_-]{0,62}[a-z0-9])?") && name.length() >= 2;
    }

    Channel createChannel(String teamId, String displayName, String name, boolean isPrivate, String purpose, String header) throws MattermostException {
        String urlName = name != null && !name.trim().isEmpty() ? name.trim() : slug(displayName);
        if (urlName.length() < 2) {
            urlName = "channel-" + Long.toString(System.currentTimeMillis() % 1_000_000L, 36);
        }
        Channel channel = account.client.createChannel(teamId, urlName, displayName.trim(), isPrivate ? Channel.TYPE_PRIVATE : Channel.TYPE_OPEN, purpose, header);
        RelayConfig.log(TAG, "created " + (isPrivate ? "private" : "public") + " channel " + channel.name);
        path.directory.invalidate();
        return channel;
    }

    Channel createGroup(List<Long> userTlIds) throws MattermostException {
        List<String> ids = new ArrayList<>();
        ids.add(account.session.userId);
        for (Long tlId : userTlIds) {
            String mmId = account.db().mmId(RelayDatabase.KIND_USER, tlId);
            if (mmId != null && !ids.contains(mmId)) {
                ids.add(mmId);
            }
        }
        Channel channel = ids.size() == 2 ? account.client.createDirectChannel(ids.get(0), ids.get(1)) : account.client.createGroupChannel(ids);
        RelayConfig.log(TAG, "created " + (channel.isDirect() ? "DM " : "GM ") + channel.id + " with " + (ids.size() - 1) + " user(s)");
        path.directory.invalidate();
        return channel;
    }

    /** The order of the user's team list for the channel screens. */
    LinkedHashMap<String, String> teams() throws MattermostException {
        LinkedHashMap<String, String> teams = new LinkedHashMap<>();
        List<org.telegram.relay.mattermost.model.Team> list = new ArrayList<>(path.directory.any().teams);
        Collections.sort(list, (a, b) -> a.displayName.compareToIgnoreCase(b.displayName));
        for (org.telegram.relay.mattermost.model.Team team : list) {
            teams.put(team.id, team.displayName != null && !team.displayName.isEmpty() ? team.displayName : team.name);
        }
        return teams;
    }
}
