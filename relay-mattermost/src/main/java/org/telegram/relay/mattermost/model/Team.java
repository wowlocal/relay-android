package org.telegram.relay.mattermost.model;

import com.google.gson.annotations.SerializedName;

/** {@code GET /users/{id}/teams}. */
public class Team {
    @SerializedName("id") public String id = "";
    @SerializedName("create_at") public long createAt;
    @SerializedName("update_at") public long updateAt;
    @SerializedName("delete_at") public long deleteAt;
    @SerializedName("display_name") public String displayName = "";
    /** URL name. */
    @SerializedName("name") public String name = "";
    @SerializedName("description") public String description = "";
    @SerializedName("email") public String email = "";
    /** {@code O} (open) or {@code I} (invite only). */
    @SerializedName("type") public String type = "";
    @SerializedName("company_name") public String companyName = "";
    @SerializedName("allowed_domains") public String allowedDomains = "";
    @SerializedName("invite_id") public String inviteId = "";
    @SerializedName("allow_open_invite") public boolean allowOpenInvite;
    @SerializedName("last_team_icon_update") public long lastTeamIconUpdate;
    @SerializedName("scheme_id") public String schemeId = "";
    @SerializedName("group_constrained") public Boolean groupConstrained;
    @SerializedName("policy_id") public String policyId = "";

    @Override
    public String toString() {
        return "Team(" + id + " " + name + ")";
    }
}
