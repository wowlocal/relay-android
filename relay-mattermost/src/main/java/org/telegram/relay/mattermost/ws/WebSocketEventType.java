package org.telegram.relay.mattermost.ws;

import java.util.HashMap;
import java.util.Map;

/** Known WebSocket event names. Unknown names map to {@link #UNKNOWN}; {@link WebSocketEvent#event} keeps the raw name. */
public enum WebSocketEventType {
    HELLO("hello"),
    POSTED("posted"),
    POST_EDITED("post_edited"),
    POST_DELETED("post_deleted"),
    POST_UNREAD("post_unread"),
    REACTION_ADDED("reaction_added"),
    REACTION_REMOVED("reaction_removed"),
    CHANNEL_VIEWED("channel_viewed"),
    MULTIPLE_CHANNELS_VIEWED("multiple_channels_viewed"),
    TYPING("typing"),
    STATUS_CHANGE("status_change"),
    USER_UPDATED("user_updated"),
    NEW_USER("new_user"),
    CHANNEL_CREATED("channel_created"),
    CHANNEL_UPDATED("channel_updated"),
    CHANNEL_CONVERTED("channel_converted"),
    CHANNEL_DELETED("channel_deleted"),
    CHANNEL_RESTORED("channel_restored"),
    CHANNEL_MEMBER_UPDATED("channel_member_updated"),
    USER_ADDED("user_added"),
    USER_REMOVED("user_removed"),
    ADDED_TO_TEAM("added_to_team"),
    LEAVE_TEAM("leave_team"),
    UPDATE_TEAM("update_team"),
    DIRECT_ADDED("direct_added"),
    GROUP_ADDED("group_added"),
    PREFERENCE_CHANGED("preference_changed"),
    PREFERENCES_CHANGED("preferences_changed"),
    PREFERENCES_DELETED("preferences_deleted"),
    SIDEBAR_CATEGORY_CREATED("sidebar_category_created"),
    SIDEBAR_CATEGORY_UPDATED("sidebar_category_updated"),
    SIDEBAR_CATEGORY_DELETED("sidebar_category_deleted"),
    SIDEBAR_CATEGORY_ORDER_UPDATED("sidebar_category_order_updated"),
    THREAD_UPDATED("thread_updated"),
    THREAD_READ_CHANGED("thread_read_changed"),
    THREAD_FOLLOW_CHANGED("thread_follow_changed"),
    EMOJI_ADDED("emoji_added"),
    EPHEMERAL_MESSAGE("ephemeral_message"),
    OPEN_DIALOG("open_dialog"),
    CONFIG_CHANGED("config_changed"),
    LICENSE_CHANGED("license_changed"),
    ROLE_UPDATED("role_updated"),
    MEMBER_ROLE_UPDATED("memberrole_updated"),
    CHANNEL_SCHEME_UPDATED("channel_scheme_updated"),
    UNKNOWN("");

    public final String rawValue;

    WebSocketEventType(String rawValue) {
        this.rawValue = rawValue;
    }

    private static final Map<String, WebSocketEventType> BY_NAME = new HashMap<String, WebSocketEventType>();

    static {
        for (WebSocketEventType type : values()) {
            if (type != UNKNOWN) {
                BY_NAME.put(type.rawValue, type);
            }
        }
    }

    public static WebSocketEventType from(String name) {
        WebSocketEventType type = name != null ? BY_NAME.get(name) : null;
        return type != null ? type : UNKNOWN;
    }
}
