package org.telegram.relay.bridge;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.relay.RelayConfig;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;

/**
 * {@code upload.saveFilePart} / {@code saveBigFilePart} (internals doc §8.4): Telegram's uploader sends
 * a file in parts (8 at once, out of order, part 0 possibly last), keyed by a random {@code file_id}. The
 * bridge buffers each part as a file under {@code cache/relay-upload/<account>/<file_id>/<part>} and
 * concatenates them when {@code messages.sendMedia} / {@code uploadMedia} names the {@code InputFile};
 * the multipart {@code POST /files} to Mattermost happens then. Parts are kept for a day (Telegram
 * resumes an upload with the same id after a restart).
 */
final class RelayUploads {
    private static final String TAG = RelayConfig.TAG_BRIDGE;
    private static final long KEEP_MS = 24L * 3600 * 1000;

    private RelayUploads() {
    }

    static File root(int account) {
        File dir = new File(ApplicationLoader.applicationContext.getCacheDir(), "relay-upload/" + account);
        dir.mkdirs();
        return dir;
    }

    private static File dir(int account, long fileId) {
        return new File(root(account), Long.toHexString(fileId));
    }

    /** Stores one part (called on the transfer pool); returns false when the bytes are missing. */
    static boolean savePart(int account, long fileId, int part, NativeByteBuffer bytes) {
        if (bytes == null || bytes.buffer == null) {
            return false;
        }
        File dir = dir(account, fileId);
        dir.mkdirs();
        int length = bytes.limit();
        byte[] data = new byte[length];
        java.nio.ByteBuffer buffer = bytes.buffer.duplicate();
        buffer.position(0);
        buffer.limit(length);
        buffer.get(data);
        File tmp = new File(dir, part + ".tmp");
        try (OutputStream out = new FileOutputStream(tmp)) {
            out.write(data);
        } catch (IOException e) {
            RelayConfig.logError(TAG, "upload part " + part + " of " + Long.toHexString(fileId), e);
            return false;
        }
        return tmp.renameTo(new File(dir, Integer.toString(part)));
    }

    /** The whole uploaded file (parts concatenated in order), or null when a part is missing. */
    static File assemble(int account, TLRPC.InputFile file) throws IOException {
        File dir = dir(account, file.id);
        File whole = new File(dir, "whole");
        if (whole.exists() && file.parts > 0 && !new File(dir, "0").exists()) {
            return whole;
        }
        int parts = file.parts;
        for (int i = 0; i < parts; i++) {
            if (!new File(dir, Integer.toString(i)).exists()) {
                RelayConfig.log(TAG, "upload " + Long.toHexString(file.id) + ": part " + i + " of " + parts + " missing");
                return null;
            }
        }
        byte[] buf = new byte[64 * 1024];
        try (OutputStream out = new FileOutputStream(whole)) {
            for (int i = 0; i < parts; i++) {
                File part = new File(dir, Integer.toString(i));
                try (InputStream in = new FileInputStream(part)) {
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                    }
                }
            }
        }
        for (int i = 0; i < parts; i++) {
            new File(dir, Integer.toString(i)).delete();
        }
        return whole;
    }

    /** Removes an upload's buffer after Mattermost has the file. */
    static void discard(int account, long fileId) {
        deleteRecursively(dir(account, fileId));
    }

    /** Drops buffers older than a day. */
    static void prune(int account) {
        File[] dirs = root(account).listFiles();
        if (dirs == null) {
            return;
        }
        long now = System.currentTimeMillis();
        for (File dir : dirs) {
            if (now - dir.lastModified() > KEEP_MS) {
                deleteRecursively(dir);
            }
        }
    }

    private static void deleteRecursively(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        file.delete();
    }

    static String md5(File file) {
        try (InputStream in = new FileInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
            StringBuilder hex = new StringBuilder();
            for (byte b : md.digest()) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
