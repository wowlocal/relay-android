package org.telegram.relay.bridge;

import org.telegram.relay.RelayConfig;
import org.telegram.relay.account.RelayAccount;
import org.telegram.relay.account.RelayDatabase;
import org.telegram.relay.account.RelayUsers;
import org.telegram.relay.mattermost.FileDownload;
import org.telegram.relay.mattermost.MattermostException;
import org.telegram.relay.mattermost.model.FileInfo;
import org.telegram.relay.mattermost.model.User;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

import java.io.IOException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Mattermost files as Telegram media (internals doc §8.5) and the {@code upload.getFile} loop (§8.2).
 *
 * <ul>
 *   <li>Every file gets a stable TL id from the id map ({@code KIND_FILE}); {@code access_hash} is a hash
 *       of the Mattermost id, {@code dc_id} {@link RelayUsers#DC_ID}, {@code file_reference = {1}}.</li>
 *   <li>Photos (JPEG/PNG/WebP/BMP with dimensions): size {@code 's'} = {@code /thumbnail} (whole, size 0),
 *       size {@code 'y'} = the original with its exact size, served with ranged GETs (dimensions scaled to
 *       fit 1280 so the client picks it for the bubble); originals over {@link #MAX_PHOTO_ORIGINAL} use
 *       {@code /preview} as {@code 'x'} instead.</li>
 *   <li>Other files: {@code TL_document} with mime type, exact size, file name (and image size for
 *       images); a {@code 'm'} thumbnail from {@code /thumbnail} when the server has a preview.</li>
 *   <li>Avatars: {@code inputPeerPhotoFileLocation} (and profile {@code TL_photo}s, which reuse the avatar
 *       photo id) → {@code /users/{id}/image?_=last_picture_update}, fetched whole.</li>
 * </ul>
 *
 * Whole resources (thumbnails, previews, avatars) sit in a small LRU because the client asks for them in
 * sequential 32 KB chunks when the size is unknown.
 */
final class RelayFiles {
    private static final String TAG = RelayConfig.TAG_BRIDGE;
    static final long MAX_PHOTO_ORIGINAL = 10L * 1024 * 1024;
    static final byte[] FILE_REFERENCE = new byte[]{1};
    private static final long CACHE_BUDGET = 24L * 1024 * 1024;

    private final RelayAccount account;
    private final LinkedHashMap<String, byte[]> cache = new LinkedHashMap<>(64, 0.75f, true);
    private long cacheBytes;

    RelayFiles(RelayAccount account) {
        this.account = account;
    }

    // MARK: - Media

    /** Shown as a photo (album item) rather than a file. */
    static boolean isPhoto(FileInfo file) {
        if (file == null || file.width <= 0 || file.height <= 0) {
            return false;
        }
        String mime = file.mimeType == null ? "" : file.mimeType.toLowerCase(Locale.ROOT);
        return mime.equals("image/jpeg") || mime.equals("image/jpg") || mime.equals("image/png") || mime.equals("image/webp") || mime.equals("image/bmp");
    }

    long tlId(FileInfo file) {
        return account.db().tlId(RelayDatabase.KIND_FILE, file.id);
    }

    TLRPC.MessageMedia media(FileInfo file, int date) {
        if (isPhoto(file)) {
            TLRPC.TL_messageMediaPhoto media = new TLRPC.TL_messageMediaPhoto();
            media.photo = photo(file, date);
            media.flags |= TLObject.FLAG_0;
            return media;
        }
        TLRPC.TL_messageMediaDocument media = new TLRPC.TL_messageMediaDocument();
        media.document = document(file, date);
        media.flags |= TLObject.FLAG_0;
        return media;
    }

    TLRPC.TL_photo photo(FileInfo file, int date) {
        TLRPC.TL_photo photo = new TLRPC.TL_photo();
        photo.id = tlId(file);
        photo.access_hash = RelayUsers.accessHash(file.id);
        photo.file_reference = FILE_REFERENCE;
        photo.date = date;
        photo.dc_id = RelayUsers.DC_ID;
        int[] thumb = thumbnailSize(file.width, file.height);
        photo.sizes.add(size("s", thumb[0], thumb[1], 0));
        int[] big = fit(file.width, file.height, 1280);
        if (file.size > 0 && file.size <= MAX_PHOTO_ORIGINAL) {
            photo.sizes.add(size("y", big[0], big[1], (int) file.size));
        } else {
            photo.sizes.add(size("x", big[0], big[1], 0));
        }
        return photo;
    }

    TLRPC.TL_document document(FileInfo file, int date) {
        TLRPC.TL_document doc = new TLRPC.TL_document();
        doc.id = tlId(file);
        doc.access_hash = RelayUsers.accessHash(file.id);
        doc.file_reference = FILE_REFERENCE;
        doc.date = date;
        String mime = file.mimeType == null ? "" : file.mimeType.trim();
        int semicolon = mime.indexOf(';');
        if (semicolon > 0) {
            mime = mime.substring(0, semicolon).trim();
        }
        doc.mime_type = mime.isEmpty() ? "application/octet-stream" : mime;
        doc.size = file.size;
        doc.dc_id = RelayUsers.DC_ID;
        TLRPC.TL_documentAttributeFilename name = new TLRPC.TL_documentAttributeFilename();
        name.file_name = file.name == null || file.name.isEmpty() ? file.id : file.name;
        doc.attributes.add(name);
        if (file.width > 0 && file.height > 0) {
            TLRPC.TL_documentAttributeImageSize imageSize = new TLRPC.TL_documentAttributeImageSize();
            imageSize.w = file.width;
            imageSize.h = file.height;
            doc.attributes.add(imageSize);
        }
        if (doc.mime_type.startsWith("video/")) {
            // Shown as a video (inline player). Mattermost has no video dimensions/duration: the sender's
            // are kept for files this app uploaded (kv video:<id>), else the bubble's default size.
            TLRPC.TL_documentAttributeVideo video = new TLRPC.TL_documentAttributeVideo();
            video.supports_streaming = true;
            String known = account.db().kv("video:" + file.id);
            if (known != null) {
                String[] parts = known.split(",");
                try {
                    video.w = Integer.parseInt(parts[0]);
                    video.h = Integer.parseInt(parts[1]);
                    video.duration = Integer.parseInt(parts[2]);
                } catch (Exception ignore) {
                }
            } else if (file.width > 0 && file.height > 0) {
                video.w = file.width;
                video.h = file.height;
            }
            doc.attributes.add(video);
        }
        if (file.hasPreviewImage && file.width > 0 && file.height > 0) {
            int[] thumb = thumbnailSize(file.width, file.height);
            doc.thumbs.add(size("m", thumb[0], thumb[1], 0));
            doc.flags |= TLObject.FLAG_0;
        }
        return doc;
    }

    /** A user's profile picture as a {@code TL_photo} (profile screen, avatar viewer); null without one. */
    TLRPC.TL_photo profilePhoto(User user) {
        long photoId = account.avatarPhotoId(user);
        if (photoId == 0) {
            return null;
        }
        TLRPC.TL_photo photo = new TLRPC.TL_photo();
        photo.id = photoId;
        photo.access_hash = RelayUsers.accessHash(user.id);
        photo.file_reference = FILE_REFERENCE;
        photo.date = (int) (user.lastPictureUpdate / 1000);
        photo.dc_id = RelayUsers.DC_ID;
        photo.sizes.add(size("a", 160, 160, 0));
        photo.sizes.add(size("c", 640, 640, 0));
        return photo;
    }

    private static TLRPC.TL_photoSize size(String type, int w, int h, int bytes) {
        TLRPC.TL_photoSize size = new TLRPC.TL_photoSize();
        size.type = type;
        size.w = Math.max(1, w);
        size.h = Math.max(1, h);
        size.size = bytes;
        return size;
    }

    /** Mattermost's thumbnail box (120 × 100): wide images are scaled to height 100, others to width 120. */
    static int[] thumbnailSize(int w, int h) {
        if (w <= 120 && h <= 100) {
            return new int[]{w, h};
        }
        if ((double) h / w < 100.0 / 120.0) {
            return new int[]{Math.max(1, (int) Math.round(100.0 * w / h)), 100};
        }
        return new int[]{120, Math.max(1, (int) Math.round(120.0 * h / w))};
    }

    static int[] fit(int w, int h, int side) {
        int max = Math.max(w, h);
        if (max <= side) {
            return new int[]{w, h};
        }
        double scale = (double) side / max;
        return new int[]{Math.max(1, (int) Math.round(w * scale)), Math.max(1, (int) Math.round(h * scale))};
    }

    // MARK: - upload.getFile

    /** Answers one chunk. Runs on the transfer pool. */
    TLObject getFile(TLRPC.TL_upload_getFile request) throws Exception {
        TLRPC.InputFileLocation location = request.location;
        long offset = request.offset;
        int limit = request.limit;
        byte[] chunk;
        if (location instanceof TLRPC.TL_inputPeerPhotoFileLocation) {
            chunk = avatarChunk(((TLRPC.TL_inputPeerPhotoFileLocation) location).photo_id, offset, limit);
        } else if (location instanceof TLRPC.TL_inputPhotoFileLocation) {
            String fileId = account.db().mmId(RelayDatabase.KIND_FILE, location.id);
            if (fileId == null) {
                // Profile photos reuse the avatar photo id.
                chunk = avatarChunk(location.id, offset, limit);
            } else if ("y".equals(location.thumb_size) || "w".equals(location.thumb_size)) {
                chunk = rangeChunk(fileId, offset, limit);
            } else if ("x".equals(location.thumb_size)) {
                chunk = wholeChunk("p:" + fileId, offset, limit, () -> account.client.filePreview(fileId));
            } else {
                chunk = wholeChunk("t:" + fileId, offset, limit, () -> account.client.fileThumbnail(fileId));
            }
        } else if (location instanceof TLRPC.TL_inputDocumentFileLocation) {
            String fileId = account.db().mmId(RelayDatabase.KIND_FILE, location.id);
            if (fileId == null && RelayEmoji.isEmojiDocumentId(location.id)) {
                // A7c: emoji documents (reactions, emoji statuses, custom emoji in text).
                byte[] emoji = RelayEmoji.of(account).chunk(location.id, offset, limit);
                return emoji != null ? uploadFile(emoji) : RelayTl.error(400, "FILE_ID_INVALID");
            }
            if (fileId == null) {
                return RelayTl.error(400, "FILE_ID_INVALID");
            }
            if (location.thumb_size == null || location.thumb_size.isEmpty()) {
                chunk = rangeChunk(fileId, offset, limit);
            } else {
                chunk = wholeChunk("t:" + fileId, offset, limit, () -> account.client.fileThumbnail(fileId));
            }
        } else {
            RelayConfig.log(TAG, "upload.getFile: unsupported location " + location.getClass().getSimpleName());
            return RelayTl.error(400, "LOCATION_INVALID");
        }
        if (chunk == null) {
            return RelayTl.error(400, "FILE_ID_INVALID");
        }
        return uploadFile(chunk);
    }

    /** {@code upload.file} with the bytes in a NativeByteBuffer (delivered without a wire round trip). */
    static TLRPC.TL_upload_file uploadFile(byte[] chunk) throws Exception {
        TLRPC.TL_upload_file file = new TLRPC.TL_upload_file();
        file.type = new TLRPC.TL_storage_filePartial();
        file.mtime = 0;
        if (chunk.length > 0) {
            NativeByteBuffer buffer = new NativeByteBuffer(chunk.length);
            buffer.buffer.put(chunk);
            buffer.buffer.position(0);
            file.bytes = buffer;
        }
        return file;
    }

    private byte[] avatarChunk(long photoId, long offset, int limit) throws Exception {
        String userId = account.avatarUserId(photoId);
        if (userId == null) {
            return null;
        }
        User user = account.cachedUser(userId);
        long version = user != null ? user.lastPictureUpdate : 0;
        return wholeChunk("a:" + userId + ":" + version, offset, limit, () -> account.client.profileImage(userId, version));
    }

    private interface Fetch {
        FileDownload open() throws MattermostException;
    }

    private byte[] wholeChunk(String key, long offset, int limit, Fetch fetch) throws Exception {
        byte[] whole = cached(key);
        if (whole == null) {
            try (FileDownload download = fetch.open()) {
                whole = download.readAll();
            } catch (MattermostException e) {
                if (e.isNotFound()) {
                    return null;
                }
                throw e;
            }
            remember(key, whole);
        }
        return slice(whole, offset, limit);
    }

    private byte[] rangeChunk(String fileId, long offset, int limit) throws Exception {
        byte[] whole = cached("f:" + fileId);
        if (whole != null) {
            return slice(whole, offset, limit);
        }
        try (FileDownload download = account.client.downloadFile(fileId, offset, offset + limit - 1)) {
            byte[] bytes = download.readAll();
            if (download.isPartial()) {
                return bytes.length > limit ? slice(bytes, 0, limit) : bytes;
            }
            if (download.getStatusCode() == 416) {
                return new byte[0];
            }
            // The server ignored Range and sent the whole file: keep it and slice.
            if (bytes.length <= 8 * 1024 * 1024) {
                remember("f:" + fileId, bytes);
            }
            return slice(bytes, offset, limit);
        } catch (MattermostException e) {
            if (e.getStatusCode() == 416) {
                return new byte[0];
            }
            if (e.isNotFound()) {
                return null;
            }
            throw e;
        } catch (IOException e) {
            throw e;
        }
    }

    private static byte[] slice(byte[] whole, long offset, int limit) {
        if (offset >= whole.length) {
            return new byte[0];
        }
        int start = (int) offset;
        int length = Math.min(limit, whole.length - start);
        byte[] out = new byte[length];
        System.arraycopy(whole, start, out, 0, length);
        return out;
    }

    private synchronized byte[] cached(String key) {
        return cache.get(key);
    }

    private synchronized void remember(String key, byte[] bytes) {
        byte[] old = cache.put(key, bytes);
        if (old != null) {
            cacheBytes -= old.length;
        }
        cacheBytes += bytes.length;
        Iterator<Map.Entry<String, byte[]>> it = cache.entrySet().iterator();
        while (cacheBytes > CACHE_BUDGET && it.hasNext()) {
            Map.Entry<String, byte[]> eldest = it.next();
            if (eldest.getKey().equals(key)) {
                continue;
            }
            cacheBytes -= eldest.getValue().length;
            it.remove();
        }
    }
}
