package org.telegram.relay.bridge;

import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.relay.RelayConfig;
import org.telegram.relay.account.RelayAccount;
import org.telegram.relay.mattermost.model.FileInfo;
import org.telegram.relay.mattermost.model.Post;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Polish 1: videos from other clients. Mattermost stores no video dimensions, duration or preview, so Telegram showed
 * such a video as a default-size bubble without a frame or a time. The bridge probes each unknown video once with
 * {@link MediaMetadataRetriever} over HTTP ({@code GET /files/{id}} with the session token; the retriever reads the
 * container header with range requests, not the whole file): width/height (rotation applied), duration and a frame
 * (JPEG, at most 320 px) under {@code files/relay-video/<account>/}. The result is kept like the sender's own values
 * ({@code kv video:<id>} = {@code w,h,duration}, as the write path stores them) and the post is re-sent as an edit, so
 * the open chat redraws the bubble with its size, time and thumbnail ({@code 'm'} thumb served from the local frame).
 */
final class RelayVideoProbe {
    private static final String TAG = RelayConfig.TAG_BRIDGE;
    private static final int THUMB_SIDE = 320;
    private static final long REEMIT_DELAY_MS = 1500;

    private static final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "RelayVideoProbe");
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    private static final Set<String> scheduled = Collections.synchronizedSet(new HashSet<String>());

    private RelayVideoProbe() {
    }

    static boolean isVideo(FileInfo file) {
        String mime = file == null || file.mimeType == null ? "" : file.mimeType.trim().toLowerCase(java.util.Locale.ROOT);
        return mime.startsWith("video/");
    }

    /** Probes {@code file} of {@code post} once when the bridge knows nothing about it yet. */
    static void schedule(RelayAccount account, Post post, FileInfo file) {
        if (account == null || post == null || !isVideo(file) || account.db().kv("video:" + file.id) != null) {
            return;
        }
        String key = account.account + ":" + file.id;
        if (!scheduled.add(key)) {
            return;
        }
        final String channelId = post.channelId;
        final String postId = post.id;
        executor.execute(() -> {
            long started = System.currentTimeMillis();
            String result = probe(account, file);
            RelayConfig.log(TAG, "video probe " + file.id + " (" + file.name + "): " + result + " in " + (System.currentTimeMillis() - started) + " ms");
            if (result.startsWith("ok")) {
                RelayLive live = RelayLive.peek(account);
                if (live != null) {
                    live.bridge.postDelayed(() -> live.reemitPost(channelId, postId), REEMIT_DELAY_MS);
                }
            }
        });
    }

    private static String probe(RelayAccount account, FileInfo file) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            Map<String, String> headers = new HashMap<>();
            headers.put("Authorization", "Bearer " + account.session.token);
            retriever.setDataSource(account.session.serverUrl + "/api/v4/files/" + file.id, headers);
            int w = parse(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
            int h = parse(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
            int rotation = parse(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION));
            long durationMs = parse(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
            if (rotation == 90 || rotation == 270) {
                int t = w;
                w = h;
                h = t;
            }
            if (w <= 0 || h <= 0) {
                account.db().putKv("video:" + file.id, "0,0," + Math.round(durationMs / 1000.0)); // not probed again
                return "no video track";
            }
            Bitmap frame = retriever.getFrameAtTime(Math.min(1_000_000L, durationMs * 500L), MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (frame != null) {
                int[] size = RelayFiles.fit(frame.getWidth(), frame.getHeight(), THUMB_SIDE);
                Bitmap scaled = Bitmap.createScaledBitmap(frame, size[0], size[1], true);
                ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
                scaled.compress(Bitmap.CompressFormat.JPEG, 80, jpeg);
                File out = thumbnailFile(account, file.id);
                out.getParentFile().mkdirs();
                try (FileOutputStream stream = new FileOutputStream(out)) {
                    stream.write(jpeg.toByteArray());
                }
                if (scaled != frame) {
                    scaled.recycle();
                }
                frame.recycle();
            }
            int seconds = (int) Math.max(1, Math.round(durationMs / 1000.0));
            account.db().putKv("video:" + file.id, w + "," + h + "," + seconds);
            return "ok " + w + "x" + h + " " + seconds + " s" + (frame != null ? " + thumbnail" : "");
        } catch (Throwable t) {
            RelayConfig.logError(TAG, "video probe " + file.id, t);
            return "failed: " + t;
        } finally {
            try {
                retriever.release();
            } catch (Throwable ignore) {
            }
        }
    }

    private static int parse(String value) {
        try {
            return value == null ? 0 : Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static File thumbnailFile(RelayAccount account, String fileId) {
        return new File(new File(ApplicationLoader.getFilesDirFixed(), "relay-video/" + account.account), fileId + ".jpg");
    }

    /** The probed frame of a video file (JPEG), or null. */
    static byte[] thumbnail(RelayAccount account, String fileId) {
        File file = thumbnailFile(account, fileId);
        if (!file.isFile()) {
            return null;
        }
        try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream((int) file.length());
            byte[] buffer = new byte[16 * 1024];
            int n;
            while ((n = in.read(buffer)) > 0) {
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    /** Dimensions of the probed frame, or null (for the document's {@code 'm'} thumb size). */
    static int[] thumbnailSize(RelayAccount account, String fileId, int w, int h) {
        if (!thumbnailFile(account, fileId).isFile() || w <= 0 || h <= 0) {
            return null;
        }
        return RelayFiles.fit(w, h, THUMB_SIDE);
    }
}
