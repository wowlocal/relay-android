package org.telegram.relay.bridge;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import org.telegram.relay.account.RelayAccount;
import org.telegram.relay.mattermost.Json;
import org.telegram.relay.mattermost.model.Post;
import org.telegram.relay.mattermost.model.PostEmbed;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

import java.util.Collection;
import java.util.regex.Pattern;

/**
 * A7a: incoming forwards. Mattermost's forward (web app, Relay iOS and Relay Android) is a post whose
 * message is the forwarded post's permalink; with {@code EnablePermalinkPreviews} the server adds a
 * {@code permalink} embed carrying that post. A <em>plain</em> forward (the message is only the permalink,
 * and the embed is there) is shown as Telegram's forwarded message: "Forwarded from &lt;author&gt;" with
 * the forwarded post's date and text (iOS {@code ForwardedPost}). A forward with a comment, or without the
 * embed (no access, deleted, previews off), stays the post as written.
 */
final class RelayForwards {
    private RelayForwards() {
    }

    /** {@code http(s)://host[:port][/prefix]/<team>/pl/<26-char id>}. */
    private static final Pattern PERMALINK = Pattern.compile("^https?://\\S+/[a-z0-9_-]+/pl/[a-z0-9]{26}/?$");

    /** The forwarded post of a plain forward, else null. */
    static Post forwardedPost(Post post) {
        if (post == null || post.metadata == null || post.metadata.embeds == null || post.metadata.embeds.isEmpty()) {
            return null;
        }
        String message = post.message != null ? post.message.trim() : "";
        if (message.isEmpty() || !PERMALINK.matcher(message).matches()) {
            return null;
        }
        for (PostEmbed embed : post.metadata.embeds) {
            if (!"permalink".equals(embed.type) || embed.data == null || !embed.data.isJsonObject()) {
                continue;
            }
            JsonObject data = embed.data.getAsJsonObject();
            JsonElement raw = data.get("post");
            if (raw == null || !raw.isJsonObject()) {
                continue;
            }
            String id = Json.string(data, "post_id");
            if (id == null || !message.contains("/pl/" + id)) {
                continue;
            }
            try {
                Post forwarded = Json.GSON.fromJson(raw, Post.class);
                if (forwarded != null && forwarded.userId != null && !forwarded.userId.isEmpty() && !forwarded.isDeleted()) {
                    return forwarded;
                }
            } catch (Exception ignore) {
            }
        }
        return null;
    }

    /** Telegram's forward header: the forwarded post's author and date. */
    static void setHeader(TLRPC.Message m, Post forwarded, RelayAccount account, Collection<String> usersOut) {
        TLRPC.TL_messageFwdHeader header = new TLRPC.TL_messageFwdHeader();
        TLRPC.TL_peerUser from = new TLRPC.TL_peerUser();
        from.user_id = account.userTlId(forwarded.userId);
        header.from_id = from;
        header.flags |= TLObject.FLAG_0;
        header.date = (int) (forwarded.createAt / 1000);
        m.fwd_from = header;
        m.flags |= TLObject.FLAG_2;
        usersOut.add(forwarded.userId);
    }
}
