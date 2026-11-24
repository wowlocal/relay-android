package org.telegram.relay.mattermost.model;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * {@code GET /config/client?format=old}: a flat map of strings ({@code "true"}/{@code "false"} for
 * flags). Logged-out callers get a limited subset.
 */
public class ClientConfig {
    private final Map<String, String> values;

    public ClientConfig(Map<String, String> values) {
        this.values = values != null ? new HashMap<String, String>(values) : new HashMap<String, String>();
    }

    public Map<String, String> getValues() {
        return Collections.unmodifiableMap(values);
    }

    public String get(String key) {
        return values.get(key);
    }

    /** Null when missing or not {@code true}/{@code false}. */
    public Boolean getBoolean(String key) {
        String value = values.get(key);
        if ("true".equalsIgnoreCase(value)) {
            return Boolean.TRUE;
        }
        if ("false".equalsIgnoreCase(value)) {
            return Boolean.FALSE;
        }
        return null;
    }

    public boolean getBoolean(String key, boolean fallback) {
        Boolean value = getBoolean(key);
        return value != null ? value : fallback;
    }

    public long getLong(String key, long fallback) {
        String value = values.get(key);
        if (value == null) {
            return fallback;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public String getVersion() {
        return values.get("Version");
    }

    public ServerVersion getServerVersion() {
        return ServerVersion.parse(getVersion());
    }

    public String getBuildNumber() {
        return values.get("BuildNumber");
    }

    public String getSiteName() {
        return values.get("SiteName");
    }

    public String getSiteUrl() {
        return values.get("SiteURL");
    }

    public String getDiagnosticId() {
        return values.get("DiagnosticId");
    }

    public boolean isCustomEmojiEnabled() {
        return getBoolean("EnableCustomEmoji", false);
    }

    public boolean isLinkPreviewsEnabled() {
        return getBoolean("EnableLinkPreviews", false);
    }

    public boolean hasImageProxy() {
        return getBoolean("HasImageProxy", false);
    }

    /** {@code MaxFileSize} in bytes, or -1. */
    public long getMaxFileSize() {
        return getLong("MaxFileSize", -1);
    }

    /** {@code disabled}, {@code default_on}, {@code default_off}, {@code always_on}. */
    public String getCollapsedThreads() {
        String value = values.get("CollapsedThreads");
        return value != null ? value : "disabled";
    }

    public boolean isCollapsedThreadsAvailable() {
        String value = getCollapsedThreads();
        return !value.isEmpty() && !"disabled".equals(value);
    }

    /** {@code username}, {@code nickname_full_name} or {@code full_name}. */
    public String getTeammateNameDisplay() {
        return values.get("TeammateNameDisplay");
    }

    public boolean isSignInWithEmailEnabled() {
        return getBoolean("EnableSignInWithEmail", false);
    }

    public boolean isSignInWithUsernameEnabled() {
        return getBoolean("EnableSignInWithUsername", false);
    }

    public boolean isMfaEnabled() {
        return getBoolean("EnableMultifactorAuthentication", false);
    }

    public boolean isMfaEnforced() {
        return getBoolean("EnforceMultifactorAuthentication", false);
    }
}
