package org.telegram.relay.mattermost;

/** Upload progress, called on the uploading thread. */
public interface UploadProgressListener {
    /** @param totalBytes the request body size, or -1 when unknown. */
    void onProgress(long bytesSent, long totalBytes);
}
