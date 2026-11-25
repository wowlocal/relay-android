package org.telegram.relay.bridge;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;

import org.telegram.messenger.Emoji;
import org.telegram.relay.RelayConfig;
import org.telegram.relay.account.RelayAccount;
import org.telegram.relay.account.RelayUsers;
import org.telegram.relay.mattermost.FileDownload;
import org.telegram.relay.mattermost.MattermostException;
import org.telegram.relay.mattermost.model.CustomEmoji;
import org.telegram.relay.mattermost.text.EmojiTable;
import org.telegram.relay.mattermost.text.FormattedText;
import org.telegram.relay.mattermost.text.TextEntity;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Emoji as Telegram custom-emoji documents (A7c).
 *
 * <p>Telegram draws reactions, emoji statuses and inline custom emoji from {@code TL_document}s with a
 * {@code documentAttributeCustomEmoji}. Relay makes one document per emoji, keyed {@code c:<name>} for a
 * Mattermost custom emoji (image from {@code GET /emoji/{id}/image}) and {@code u:<name>} for a Unicode emoji
 * (rendered here from Telegram's own emoji sprites, for emoji statuses, which can only be documents). The
 * documents are static images: AnimatedEmojiDrawable loads them through their {@code 'm'} thumbnail, which
 * {@link RelayFiles#getFile} hands to {@link #chunk}. Every document is {@code free}, so a non-Premium account
 * may use them, and the custom ones form one emoji pack ("Custom Emoji") in the emoji keyboard.
 *
 * <p>Ids: {@link #DOCUMENT_BASE} + the id map's {@code emoji} sequence, far from file ids (5·10⁹) and avatar
 * ids (7·10⁹).
 */
public final class RelayEmoji {
    private static final String TAG = RelayConfig.TAG_BRIDGE;
    static final String KIND_EMOJI = "emoji";
    static final long DOCUMENT_BASE = 9_000_000_000L;
    /** The Relay custom-emoji pack. */
    public static final long SET_ID = 0x52656c6179437573L;
    static final long SET_ACCESS_HASH = 0x5265;
    public static final String SET_SHORT_NAME = "relay_custom_emoji";
    private static final int IMAGE_SIZE = 128;

    private static final WeakHashMap<RelayAccount, RelayEmoji> instances = new WeakHashMap<>();

    public static RelayEmoji of(RelayAccount account) {
        synchronized (instances) {
            RelayEmoji emoji = instances.get(account);
            if (emoji == null) {
                instances.put(account, emoji = new RelayEmoji(account));
            }
            return emoji;
        }
    }

    private final RelayAccount account;
    /** Custom emoji by lowercased name (whole server list, refreshed every 10 min). */
    private volatile Map<String, CustomEmoji> custom;
    private volatile long customLoadedAt;
    private final LinkedHashMap<String, byte[]> images = new LinkedHashMap<>(32, 0.75f, true);

    private RelayEmoji(RelayAccount account) {
        this.account = account;
    }

    static void register(Map<Class<?>, RelayHandlers.Handler> map) {
        map.put(TLRPC.TL_messages_getCustomEmojiDocuments.class, (bridge, account, req) ->
                of(account).getCustomEmojiDocuments((TLRPC.TL_messages_getCustomEmojiDocuments) req));
        map.put(TLRPC.TL_messages_getEmojiStickers.class, (bridge, account, req) -> of(account).getEmojiStickers());
        map.put(TLRPC.TL_messages_getStickerSet.class, (bridge, account, req) -> {
            TLRPC.TL_messages_getStickerSet r = (TLRPC.TL_messages_getStickerSet) req;
            TLObject ours = of(account).getStickerSet(r.stickerset);
            if (ours != null) {
                return ours;
            }
            // As the A2 stub: the animated-emoji set must be a stickerSet with a non-zero id, or MDC retries every second.
            if (r.stickerset instanceof TLRPC.TL_inputStickerSetAnimatedEmoji) {
                TLRPC.TL_messages_stickerSet set = new TLRPC.TL_messages_stickerSet();
                TLRPC.TL_stickerSet s = new TLRPC.TL_stickerSet();
                s.id = 0x52656c6179L;
                s.access_hash = 1;
                s.title = "Relay";
                s.short_name = "relay_emoji";
                s.emojis = true;
                set.set = s;
                return set;
            }
            return RelayTl.error(400, "STICKERSET_INVALID");
        });
    }

    // MARK: - Custom emoji list

    /** The server's custom emoji by name (cached; loads the full list once per 10 minutes). */
    public Map<String, CustomEmoji> customEmoji() {
        Map<String, CustomEmoji> current = custom;
        if (current != null && System.currentTimeMillis() - customLoadedAt < 10 * 60_000) {
            return current;
        }
        LinkedHashMap<String, CustomEmoji> loaded = new LinkedHashMap<>();
        try {
            for (int page = 0; page < 50; page++) {
                List<CustomEmoji> list = account.client.customEmojis(page, 200, "name");
                for (CustomEmoji e : list) {
                    if (e.deleteAt == 0 && e.name != null) {
                        loaded.put(e.name.toLowerCase(Locale.ROOT), e);
                    }
                }
                if (list.size() < 200) {
                    break;
                }
            }
            custom = loaded;
            customLoadedAt = System.currentTimeMillis();
            return loaded;
        } catch (MattermostException e) {
            RelayConfig.log(TAG, "custom emoji list failed: " + e.getMessage());
            return current != null ? current : Collections.<String, CustomEmoji>emptyMap();
        }
    }

    /** Cached list only (never blocks on the network); empty before the first load. */
    public Map<String, CustomEmoji> customEmojiCached() {
        Map<String, CustomEmoji> current = custom;
        return current != null ? current : Collections.<String, CustomEmoji>emptyMap();
    }

    /** A new custom emoji from the WebSocket ({@code emoji_added}). */
    void onEmojiAdded(CustomEmoji emoji) {
        Map<String, CustomEmoji> current = custom;
        if (current != null && emoji != null && emoji.name != null) {
            LinkedHashMap<String, CustomEmoji> copy = new LinkedHashMap<>(current);
            copy.put(emoji.name.toLowerCase(Locale.ROOT), emoji);
            custom = copy;
        }
    }

    public boolean isCustom(String name) {
        return name != null && customEmoji().containsKey(name.toLowerCase(Locale.ROOT));
    }

    // MARK: - Documents

    /** The document id of a Mattermost emoji name: Unicode names get a rendered document, others the custom one. */
    public long documentId(String name) {
        String key = EmojiTable.unicode(name) != null ? "u:" + name : "c:" + name.toLowerCase(Locale.ROOT);
        return DOCUMENT_BASE + account.db().tlId(KIND_EMOJI, key);
    }

    /** The document id of a custom emoji name (no Unicode check). */
    public long customDocumentId(String name) {
        return DOCUMENT_BASE + account.db().tlId(KIND_EMOJI, "c:" + name.toLowerCase(Locale.ROOT));
    }

    /** The document id for a Unicode emoji (rendered); 0 when Mattermost has no name for it. */
    public long unicodeDocumentId(String unicode) {
        String name = nameOfUnicode(unicode);
        return name != null ? DOCUMENT_BASE + account.db().tlId(KIND_EMOJI, "u:" + name) : 0;
    }

    static String nameOfUnicode(String unicode) {
        if (unicode == null) {
            return null;
        }
        String name = EmojiTable.name(unicode);
        if (name == null) {
            name = EmojiTable.name(unicode.replace("️", ""));
        }
        return name;
    }

    /** {@code c:name} / {@code u:name} of a document id, or null when it is not an emoji document. */
    String keyOf(long documentId) {
        if (documentId <= DOCUMENT_BASE || documentId > DOCUMENT_BASE + 1_000_000_000L) {
            return null;
        }
        return account.db().mmId(KIND_EMOJI, documentId - DOCUMENT_BASE);
    }

    /** The Mattermost emoji name of a document id, or null. */
    public String nameOf(long documentId) {
        String key = keyOf(documentId);
        return key != null && key.length() > 2 ? key.substring(2) : null;
    }

    public static boolean isEmojiDocumentId(long documentId) {
        return documentId > DOCUMENT_BASE && documentId <= DOCUMENT_BASE + 1_000_000_000L;
    }

    TLRPC.TL_document document(long documentId) {
        String key = keyOf(documentId);
        if (key == null) {
            return null;
        }
        boolean customEmoji = key.startsWith("c:");
        String name = key.substring(2);
        TLRPC.TL_document doc = new TLRPC.TL_document();
        doc.id = documentId;
        doc.access_hash = RelayUsers.accessHash(key);
        doc.file_reference = new byte[0];
        doc.date = 1;
        doc.mime_type = "image/png";
        doc.size = 0;
        doc.dc_id = RelayUsers.DC_ID;
        TLRPC.TL_photoSize thumb = new TLRPC.TL_photoSize();
        thumb.type = "m";
        thumb.w = IMAGE_SIZE;
        thumb.h = IMAGE_SIZE;
        thumb.size = 0;
        thumb.location = new TLRPC.TL_fileLocationToBeDeprecated();
        doc.thumbs.add(thumb);
        doc.flags |= TLObject.FLAG_0;
        TLRPC.TL_documentAttributeCustomEmoji attr = new TLRPC.TL_documentAttributeCustomEmoji();
        attr.free = true;
        attr.text_color = false;
        String unicode = customEmoji ? null : EmojiTable.unicode(name);
        attr.alt = unicode != null ? unicode : ":" + name + ":";
        if (customEmoji) {
            TLRPC.TL_inputStickerSetID set = new TLRPC.TL_inputStickerSetID();
            set.id = SET_ID;
            set.access_hash = SET_ACCESS_HASH;
            attr.stickerset = set;
        } else {
            attr.stickerset = new TLRPC.TL_inputStickerSetEmpty();
        }
        doc.attributes.add(attr);
        TLRPC.TL_documentAttributeImageSize size = new TLRPC.TL_documentAttributeImageSize();
        size.w = IMAGE_SIZE;
        size.h = IMAGE_SIZE;
        doc.attributes.add(size);
        return doc;
    }

    /** {@code messages.getCustomEmojiDocuments}. */
    TLObject getCustomEmojiDocuments(TLRPC.TL_messages_getCustomEmojiDocuments request) {
        ArrayList<TLObject> docs = new ArrayList<>();
        for (Long id : request.document_id) {
            TLRPC.TL_document doc = id != null ? document(id) : null;
            if (doc != null) {
                docs.add(doc);
            }
        }
        return new RelayTl.RawVector(docs);
    }

    // MARK: - Emoji pack (emoji keyboard)

    private TLRPC.TL_stickerSet stickerSet(int count) {
        TLRPC.TL_stickerSet s = new TLRPC.TL_stickerSet();
        s.id = SET_ID;
        s.access_hash = SET_ACCESS_HASH;
        s.title = "Custom Emoji";
        s.short_name = SET_SHORT_NAME;
        s.emojis = true;
        s.installed_date = 1;
        s.flags |= TLObject.FLAG_0;
        s.count = count;
        s.hash = count;
        return s;
    }

    /** {@code messages.getEmojiStickers}: the server's custom emoji as one installed pack (none when there are none). */
    TLObject getEmojiStickers() {
        Map<String, CustomEmoji> all = customEmoji();
        TLRPC.TL_messages_allStickers res = new TLRPC.TL_messages_allStickers();
        res.hash = Integer.toString(all.size() + 1);
        if (!all.isEmpty()) {
            res.sets.add(stickerSet(all.size()));
        }
        return res;
    }

    /** {@code messages.getStickerSet} for the Relay pack, or null for another set. */
    TLObject getStickerSet(TLRPC.InputStickerSet input) {
        boolean ours = input instanceof TLRPC.TL_inputStickerSetID && input.id == SET_ID
                || input instanceof TLRPC.TL_inputStickerSetShortName && SET_SHORT_NAME.equals(input.short_name);
        if (!ours) {
            return null;
        }
        Map<String, CustomEmoji> all = customEmoji();
        TLRPC.TL_messages_stickerSet res = new TLRPC.TL_messages_stickerSet();
        res.set = stickerSet(all.size());
        for (CustomEmoji e : all.values()) {
            long id = customDocumentId(e.name);
            TLRPC.TL_document doc = document(id);
            if (doc == null) {
                continue;
            }
            res.documents.add(doc);
            TLRPC.TL_stickerPack pack = new TLRPC.TL_stickerPack();
            pack.emoticon = ":" + e.name + ":";
            pack.documents.add(id);
            res.packs.add(pack);
        }
        return res;
    }

    // MARK: - Text

    /** Adds {@code messageEntityCustomEmoji} for every {@code :name:} that is one of the server's custom emoji. */
    void addCustomEmojiEntities(FormattedText text, ArrayList<TLRPC.MessageEntity> out) {
        Map<String, CustomEmoji> known = null;
        for (TextEntity e : text.getEntities()) {
            if (e.getType() != TextEntity.Type.CUSTOM_EMOJI || e.getName() == null || e.getLength() <= 0) {
                continue;
            }
            if (known == null) {
                known = customEmoji();
            }
            if (!known.containsKey(e.getName())) {
                continue;
            }
            TLRPC.TL_messageEntityCustomEmoji entity = new TLRPC.TL_messageEntityCustomEmoji();
            entity.offset = e.getOffset();
            entity.length = e.getLength();
            entity.document_id = customDocumentId(e.getName());
            out.add(entity);
        }
        if (known != null) {
            Collections.sort(out, (a, b) -> Integer.compare(a.offset, b.offset));
        }
    }

    // MARK: - Images (upload.getFile)

    /** The bytes of an emoji document's image, sliced like a file part; null when the id is not an emoji. */
    byte[] chunk(long documentId, long offset, int limit) throws Exception {
        String key = keyOf(documentId);
        if (key == null) {
            return null;
        }
        byte[] whole;
        synchronized (images) {
            whole = images.get(key);
        }
        if (whole == null) {
            whole = key.startsWith("c:") ? customImage(key.substring(2)) : renderUnicode(EmojiTable.unicode(key.substring(2)));
            if (whole == null) {
                whole = renderUnicode("❓");
            }
            synchronized (images) {
                images.put(key, whole);
                while (images.size() > 200) {
                    images.remove(images.keySet().iterator().next());
                }
            }
        }
        if (offset >= whole.length) {
            return new byte[0];
        }
        int length = (int) Math.min(limit, whole.length - offset);
        byte[] out = new byte[length];
        System.arraycopy(whole, (int) offset, out, 0, length);
        return out;
    }

    private byte[] customImage(String name) throws Exception {
        CustomEmoji emoji = customEmoji().get(name);
        if (emoji == null) {
            try {
                emoji = account.client.customEmojiByName(name);
            } catch (MattermostException e) {
                if (e.isNotFound()) {
                    return null;
                }
                throw e;
            }
        }
        try (FileDownload download = account.client.customEmojiImage(emoji.id)) {
            return download.readAll();
        } catch (MattermostException e) {
            if (e.isNotFound()) {
                return null;
            }
            throw e;
        }
    }

    /** Renders a Unicode emoji with Telegram's emoji sprites (system font as a fallback) to a PNG. */
    static byte[] renderUnicode(String unicode) {
        if (unicode == null) {
            return null;
        }
        Bitmap bitmap = Bitmap.createBitmap(IMAGE_SIZE, IMAGE_SIZE, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        boolean drawn = false;
        try {
            Emoji.EmojiDrawable drawable = Emoji.getEmojiDrawable(unicode);
            if (drawable != null) {
                Emoji.preloadEmoji(unicode);
                for (int i = 0; i < 40 && !drawable.isLoaded(); i++) {
                    Thread.sleep(50);
                }
                if (drawable.isLoaded()) {
                    drawable.fullSize = false;
                    ((Drawable) drawable).setBounds(0, 0, IMAGE_SIZE, IMAGE_SIZE);
                    drawable.draw(canvas);
                    drawn = true;
                }
            }
        } catch (Throwable t) {
            RelayConfig.logError(TAG, "emoji render", t);
        }
        if (!drawn) {
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setTextSize(IMAGE_SIZE * 0.8f);
            paint.setTextAlign(Paint.Align.CENTER);
            Paint.FontMetrics fm = paint.getFontMetrics();
            canvas.drawText(unicode, IMAGE_SIZE / 2f, IMAGE_SIZE / 2f - (fm.ascent + fm.descent) / 2f, paint);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        bitmap.recycle();
        return out.toByteArray();
    }
}
