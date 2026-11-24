package org.telegram.relay.mattermost;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

/** One file for {@link MattermostClient#uploadFiles}. */
public final class FileUpload {
    /** Opens the content; called once per upload attempt. */
    public interface Source {
        InputStream open() throws IOException;

        /** Size in bytes, or -1 when unknown (the upload is then chunked). */
        long length();
    }

    /** Client-generated id, echoed back in {@code FileUploadResponse.clientIds}. */
    public final String clientId;
    public final String fileName;
    public final String mimeType;
    public final Source source;

    public FileUpload(String clientId, String fileName, String mimeType, Source source) {
        this.clientId = clientId != null ? clientId : UUID.randomUUID().toString().toLowerCase();
        this.fileName = fileName;
        this.mimeType = mimeType != null ? mimeType : "application/octet-stream";
        this.source = source;
    }

    public static FileUpload ofBytes(String fileName, String mimeType, final byte[] data) {
        return new FileUpload(null, fileName, mimeType, new Source() {
            @Override
            public InputStream open() {
                return new ByteArrayInputStream(data);
            }

            @Override
            public long length() {
                return data.length;
            }
        });
    }

    public static FileUpload ofFile(String fileName, String mimeType, final File file) {
        return new FileUpload(null, fileName != null ? fileName : file.getName(), mimeType, new Source() {
            @Override
            public InputStream open() throws IOException {
                return new FileInputStream(file);
            }

            @Override
            public long length() {
                return file.length();
            }
        });
    }
}
