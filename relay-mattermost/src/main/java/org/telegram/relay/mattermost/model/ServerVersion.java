package org.telegram.relay.mattermost.model;

/**
 * {@code major.minor.patch} from a Mattermost version string ({@code "11.7.14"}, the {@code Version}
 * client config value, or the {@code X-Version-Id} header {@code "11.7.14.<build>.<hash>.<ee>"}).
 */
public final class ServerVersion implements Comparable<ServerVersion> {
    /** The oldest server Relay supports (collapsed threads, {@code multiple_channels_viewed}, post metadata). */
    public static final ServerVersion MINIMUM_SUPPORTED = new ServerVersion(9, 0, 0);

    public final int major;
    public final int minor;
    public final int patch;

    public ServerVersion(int major, int minor, int patch) {
        this.major = major;
        this.minor = minor;
        this.patch = patch;
    }

    /** Null unless {@code text} starts with at least {@code major.minor}. */
    public static ServerVersion parse(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        if (trimmed.startsWith("v") || trimmed.startsWith("V")) {
            trimmed = trimmed.substring(1);
        }
        String[] parts = trimmed.split("[.\\-+]");
        if (parts.length < 2) {
            return null;
        }
        try {
            int major = Integer.parseInt(parts[0]);
            int minor = Integer.parseInt(parts[1]);
            int patch = 0;
            if (parts.length > 2) {
                try {
                    patch = Integer.parseInt(parts[2]);
                } catch (NumberFormatException ignored) {
                    patch = 0;
                }
            }
            if (major < 0 || minor < 0 || patch < 0) {
                return null;
            }
            return new ServerVersion(major, minor, patch);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public boolean isAtLeast(ServerVersion other) {
        return compareTo(other) >= 0;
    }

    public boolean isAtLeast(int major, int minor, int patch) {
        return isAtLeast(new ServerVersion(major, minor, patch));
    }

    public boolean isSupported() {
        return isAtLeast(MINIMUM_SUPPORTED);
    }

    @Override
    public int compareTo(ServerVersion other) {
        if (major != other.major) {
            return major < other.major ? -1 : 1;
        }
        if (minor != other.minor) {
            return minor < other.minor ? -1 : 1;
        }
        if (patch != other.patch) {
            return patch < other.patch ? -1 : 1;
        }
        return 0;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof ServerVersion)) {
            return false;
        }
        ServerVersion other = (ServerVersion) o;
        return major == other.major && minor == other.minor && patch == other.patch;
    }

    @Override
    public int hashCode() {
        return (major * 1000 + minor) * 1000 + patch;
    }

    @Override
    public String toString() {
        return major + "." + minor + "." + patch;
    }
}
