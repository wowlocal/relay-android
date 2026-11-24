package org.telegram.relay.mattermost.model;

import com.google.gson.annotations.SerializedName;

/** {@code GET /users/{id}/teams/members}. */
public class TeamMember {
    @SerializedName("team_id") public String teamId = "";
    @SerializedName("user_id") public String userId = "";
    @SerializedName("roles") public String roles = "";
    @SerializedName("delete_at") public long deleteAt;
    @SerializedName("scheme_guest") public boolean schemeGuest;
    @SerializedName("scheme_user") public boolean schemeUser;
    @SerializedName("scheme_admin") public boolean schemeAdmin;
    @SerializedName("explicit_roles") public String explicitRoles = "";
}
