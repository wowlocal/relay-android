package org.telegram.relay.mattermost;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;

/**
 * An open binary response ({@code GET /files/{id}}, thumbnails, previews, profile images, emoji).
 * Read {@link #getInputStream()} and always {@link #close()}.
 *
 * <p>For a ranged request the server answers 206 with {@code Content-Range: bytes start-end/total};
 * a server (or proxy) that ignores {@code Range} answers 200 with the whole file, which
 * {@link #isPartial()} reports.
 */
public final class FileDownload implements Closeable {
    private final HttpURLConnection connection;
    private final InputStream stream;
    private final int statusCode;
    private final String contentType;
    private final long contentLength;
    private final long rangeStart;
    private final long rangeEnd;
    private final long totalLength;
    private final String etag;

    FileDownload(HttpURLConnection connection, InputStream stream, int statusCode) {
        this.connection = connection;
        this.stream = stream;
        this.statusCode = statusCode;
        this.contentType = connection.getContentType();
        this.contentLength = parseLong(connection.getHeaderField("Content-Length"), -1);
        this.etag = connection.getHeaderField("ETag");
        long start = -1;
        long end = -1;
        long total = -1;
        String range = connection.getHeaderField("Content-Range");
        if (statusCode == 206 && range != null) {
            // bytes 0-99/1234 (total may be "*")
            String value = range.trim();
            if (value.startsWith("bytes")) {
                value = value.substring(5).trim();
            }
            int dash = value.indexOf('-');
            int slash = value.indexOf('/');
            if (dash > 0 && slash > dash) {
                start = parseLong(value.substring(0, dash), -1);
                end = parseLong(value.substring(dash + 1, slash), -1);
                total = parseLong(value.substring(slash + 1), -1);
            }
        } else if (statusCode == 200) {
            start = 0;
            total = contentLength;
            end = contentLength > 0 ? contentLength - 1 : -1;
        }
        this.rangeStart = start;
        this.rangeEnd = end;
        this.totalLength = total;
    }

    private static long parseLong(String value, long fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public InputStream getInputStream() {
        return stream;
    }

    /** 200 or 206. */
    public int getStatusCode() {
        return statusCode;
    }

    /** True for a 206 response carrying only the requested range. */
    public boolean isPartial() {
        return statusCode == 206;
    }

    public String getContentType() {
        return contentType;
    }

    /** Bytes in this response, or -1 when unknown (chunked). */
    public long getContentLength() {
        return contentLength;
    }

    /** First byte offset of this response within the file (0 for a 200), or -1 when unknown. */
    public long getRangeStart() {
        return rangeStart;
    }

    /** Last byte offset (inclusive), or -1 when unknown. */
    public long getRangeEnd() {
        return rangeEnd;
    }

    /** The whole file's size from {@code Content-Range} (or {@code Content-Length} for a 200), or -1. */
    public long getTotalLength() {
        return totalLength;
    }

    public String getEtag() {
        return etag;
    }

    public String getHeader(String name) {
        return connection.getHeaderField(name);
    }

    /** Reads the rest of the stream into memory and closes the download. */
    public byte[] readAll() throws IOException {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(contentLength > 0 && contentLength < Integer.MAX_VALUE ? (int) contentLength : 8192);
            byte[] buffer = new byte[16384];
            int read;
            while ((read = stream.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        } finally {
            close();
        }
    }

    @Override
    public void close() {
        try {
            stream.close();
        } catch (IOException ignored) {
            // closing anyway
        }
    }
}
