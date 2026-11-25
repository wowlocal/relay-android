package org.telegram.relay.bridge;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.NotificationCenter;
import org.telegram.relay.RelayConfig;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Upload progress in Relay mode (A5 known issue, fixed in A7c). Telegram's own upload goes to the bridge's local
 * buffer and is instant; the real transfer is the {@code POST /files} to Mattermost when the message is sent. The
 * bubble's progress shows both: Telegram's part as the first {@link #LOCAL_SHARE} of the ring, the Mattermost upload
 * as the rest, posted as {@code fileUploadProgressChanged} for the same local path the message cell observes.
 */
public final class RelayUploadProgress {
    static final float LOCAL_SHARE = 0.1f;

    /** Telegram's upload file id → the local path it uploads (the key the message cell observes). */
    private static final Map<Long, String> paths = Collections.synchronizedMap(new LinkedHashMap<Long, String>(32, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, String> eldest) {
            return size() > 256;
        }
    });

    private RelayUploadProgress() {
    }

    /** From ImageLoader's upload delegate: remembers the path and scales the local progress to its share. */
    public static long localProgress(long fileId, String location, long uploaded, long total) {
        if (!RelayConfig.ENABLED || fileId == 0 || location == null) {
            return uploaded;
        }
        paths.put(fileId, location);
        return (long) (uploaded * (double) LOCAL_SHARE);
    }

    /** From the bridge's {@code POST /files}: progress of the upload of Telegram file {@code fileId}. */
    static void serverProgress(int account, long fileId, long sent, long total) {
        String location = paths.get(fileId);
        if (location == null || total <= 0) {
            return;
        }
        long shown = (long) (total * (LOCAL_SHARE + (1 - LOCAL_SHARE) * Math.min(1.0, sent / (double) total)));
        AndroidUtilities.runOnUIThread(() -> NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.fileUploadProgressChanged, location, shown, total, false));
    }

    static void done(long fileId) {
        paths.remove(fileId);
    }
}
