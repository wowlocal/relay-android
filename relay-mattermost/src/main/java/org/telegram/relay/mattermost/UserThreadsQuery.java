package org.telegram.relay.mattermost;

/** Options for {@code GET /users/{id}/teams/{team_id}/threads} (collapsed reply threads). */
public final class UserThreadsQuery {
    Long since;
    String before;
    String after;
    Integer perPage;
    boolean extended;
    boolean includeDeleted;
    boolean unreadOnly;
    boolean totalsOnly;
    boolean threadsOnly;

    public UserThreadsQuery since(long millis) {
        since = millis;
        return this;
    }

    /** Thread (root post) id cursor. */
    public UserThreadsQuery before(String threadId) {
        before = threadId;
        return this;
    }

    public UserThreadsQuery after(String threadId) {
        after = threadId;
        return this;
    }

    public UserThreadsQuery perPage(int value) {
        perPage = value;
        return this;
    }

    /** Full participant users instead of ids. */
    public UserThreadsQuery extended(boolean value) {
        extended = value;
        return this;
    }

    public UserThreadsQuery includeDeleted(boolean value) {
        includeDeleted = value;
        return this;
    }

    public UserThreadsQuery unreadOnly(boolean value) {
        unreadOnly = value;
        return this;
    }

    public UserThreadsQuery totalsOnly(boolean value) {
        totalsOnly = value;
        return this;
    }

    public UserThreadsQuery threadsOnly(boolean value) {
        threadsOnly = value;
        return this;
    }

    QueryBuilder toQuery() {
        QueryBuilder query = new QueryBuilder();
        query.add("since", since);
        query.add("before", before);
        query.add("after", after);
        query.add("per_page", perPage);
        query.flag("extended", extended);
        query.flag("deleted", includeDeleted);
        query.flag("unread", unreadOnly);
        query.flag("totalsOnly", totalsOnly);
        query.flag("threadsOnly", threadsOnly);
        return query;
    }
}
