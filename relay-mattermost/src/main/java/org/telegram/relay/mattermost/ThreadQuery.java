package org.telegram.relay.mattermost;

/** Options for {@code GET /posts/{id}/thread}. */
public final class ThreadQuery {
    Integer perPage;
    String fromPost;
    Long fromCreateAt;
    /** {@code up} or {@code down}. */
    String direction;
    boolean skipFetchThreads;
    boolean collapsedThreads;
    boolean collapsedThreadsExtended;

    public ThreadQuery perPage(int value) {
        perPage = value;
        return this;
    }

    /** Page from a reply ({@code fromPost} + {@code fromCreateAt}). */
    public ThreadQuery from(String postId, long createAt) {
        fromPost = postId;
        fromCreateAt = createAt;
        return this;
    }

    /** {@code "up"} (older) or {@code "down"} (newer). */
    public ThreadQuery direction(String value) {
        direction = value;
        return this;
    }

    public ThreadQuery skipFetchThreads(boolean value) {
        skipFetchThreads = value;
        return this;
    }

    public ThreadQuery collapsedThreads(boolean value) {
        collapsedThreads = value;
        return this;
    }

    public ThreadQuery collapsedThreadsExtended(boolean value) {
        collapsedThreadsExtended = value;
        return this;
    }

    QueryBuilder toQuery() {
        QueryBuilder query = new QueryBuilder();
        query.add("perPage", perPage);
        query.add("fromPost", fromPost);
        query.add("fromCreateAt", fromCreateAt);
        query.add("direction", direction);
        query.flag("skipFetchThreads", skipFetchThreads);
        query.flag("collapsedThreads", collapsedThreads);
        query.flag("collapsedThreadsExtended", collapsedThreadsExtended);
        return query;
    }
}
