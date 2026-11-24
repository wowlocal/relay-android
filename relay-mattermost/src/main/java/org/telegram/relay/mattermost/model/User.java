package org.telegram.relay.mattermost.model;

import com.google.gson.annotations.SerializedName;

import org.telegram.relay.mattermost.Json;

import java.util.HashMap;
import java.util.Map;

/** {@code GET /users/{id}}. Sanitised for other users (no email, notify props, etc.). */
public class User {
    @SerializedName("id") public String id = "";
    @SerializedName("create_at") public long createAt;
    @SerializedName("update_at") public long updateAt;
    /** Non-zero for deactivated users. */
    @SerializedName("delete_at") public long deleteAt;
    @SerializedName("username") public String username = "";
    @SerializedName("auth_service") public String authService = "";
    @SerializedName("email") public String email = "";
    @SerializedName("email_verified") public boolean emailVerified;
    @SerializedName("nickname") public String nickname = "";
    @SerializedName("first_name") public String firstName = "";
    @SerializedName("last_name") public String lastName = "";
    @SerializedName("position") public String position = "";
    /** Space-separated, e.g. {@code "system_user system_admin"}. */
    @SerializedName("roles") public String roles = "";
    @SerializedName("locale") public String locale = "";
    /** Includes {@code customStatus} (a JSON string), see {@link #getCustomStatus()}. */
    @SerializedName("props") public Map<String, String> props = new HashMap<String, String>();
    @SerializedName("notify_props") public Map<String, String> notifyProps = new HashMap<String, String>();
    /** {@code useAutomaticTimezone}, {@code automaticTimezone}, {@code manualTimezone}. */
    @SerializedName("timezone") public Map<String, String> timezone = new HashMap<String, String>();
    /** Pass as {@code _=} when loading the profile image so caches refresh; negative/0 means no custom picture. */
    @SerializedName("last_picture_update") public long lastPictureUpdate;
    @SerializedName("last_activity_at") public long lastActivityAt;
    @SerializedName("is_bot") public boolean isBot;
    @SerializedName("bot_description") public String botDescription = "";
    @SerializedName("bot_last_icon_update") public long botLastIconUpdate;
    @SerializedName("mfa_active") public boolean mfaActive;
    @SerializedName("remote_id") public String remoteId = "";

    public boolean isDeactivated() {
        return deleteAt > 0;
    }

    public boolean hasCustomPicture() {
        return lastPictureUpdate > 0;
    }

    public boolean hasRole(String role) {
        if (roles == null) {
            return false;
        }
        for (String part : roles.split(" ")) {
            if (part.equals(role)) {
                return true;
            }
        }
        return false;
    }

    public boolean isSystemAdmin() {
        return hasRole("system_admin");
    }

    public boolean isGuest() {
        return hasRole("system_guest");
    }

    /** {@code "First Last"}, trimmed; empty when neither is set. */
    public String getFullName() {
        String first = firstName == null ? "" : firstName.trim();
        String last = lastName == null ? "" : lastName.trim();
        if (first.isEmpty()) {
            return last;
        }
        if (last.isEmpty()) {
            return first;
        }
        return first + " " + last;
    }

    /** The custom status from {@code props.customStatus}, or null. */
    public CustomStatus getCustomStatus() {
        if (props == null) {
            return null;
        }
        String raw = props.get("customStatus");
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        CustomStatus status = Json.decodeEmbedded(new com.google.gson.JsonPrimitive(raw), CustomStatus.class);
        return status == null || status.isEmpty() ? null : status;
    }

    @Override
    public String toString() {
        return "User(" + id + " @" + username + ")";
    }
}
