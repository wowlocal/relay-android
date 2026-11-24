package org.telegram.relay.mattermost;

import java.nio.charset.Charset;

/** Percent-encoding the way Mattermost's Go router expects it. */
public final class UrlCodec {
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private UrlCodec() {
    }

    private static boolean isUnreserved(int c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                || c == '-' || c == '.' || c == '_' || c == '~';
    }

    /**
     * One path segment: RFC 3986 {@code pchar} minus {@code /}. {@code +} stays literal so emoji names
     * like {@code +1} match the server's routes.
     */
    public static String encodePathSegment(String segment) {
        return encode(segment, true);
    }

    /** A query name or value: everything but unreserved characters is escaped (Go decodes {@code +} as a space). */
    public static String encodeQueryComponent(String value) {
        return encode(value, false);
    }

    private static String encode(String value, boolean pathSegment) {
        byte[] bytes = value.getBytes(UTF8);
        StringBuilder builder = new StringBuilder(bytes.length + 8);
        for (byte b : bytes) {
            int c = b & 0xff;
            if (isUnreserved(c) || (pathSegment && "!$&'()*+,;=:@".indexOf(c) >= 0)) {
                builder.append((char) c);
            } else {
                builder.append('%').append(HEX[c >> 4]).append(HEX[c & 0xf]);
            }
        }
        return builder.toString();
    }

    /** {@code /a/b/c} from raw segments, each percent-encoded. */
    public static String path(String... segments) {
        StringBuilder builder = new StringBuilder();
        for (String segment : segments) {
            builder.append('/').append(encodePathSegment(segment));
        }
        return builder.toString();
    }
}
