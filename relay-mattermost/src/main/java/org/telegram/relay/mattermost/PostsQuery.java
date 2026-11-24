package org.telegram.relay.mattermost;

/**
 * Paging for {@code GET /channels/{id}/posts}. Use one style per call: {@code page}/{@code perPage},
 * a {@code before}/{@code after} post-id cursor (with {@code perPage}), or {@code since} (ms; posts
 * created, edited or deleted since then, ignoring the other arguments).
 */
public final class PostsQuery {
    Integer page;
    Integer perPage;
    String before;
    String after;
    Long since;
    boolean includeDeleted;
    boolean collapsedThreads;
    boolean collapsedThreadsExtended;

    /** The newest page ({@code page=0}). */
    public static PostsQuery latest(int perPage) {
        PostsQuery query = new PostsQuery();
        query.page = 0;
        query.perPage = perPage;
        return query;
    }

    public static PostsQuery page(int page, int perPage) {
        PostsQuery query = new PostsQuery();
        query.page = page;
        query.perPage = perPage;
        return query;
    }

    /** The {@code perPage} posts just older than {@code postId}. */
    public static PostsQuery before(String postId, int perPage) {
        PostsQuery query = new PostsQuery();
        query.before = postId;
        query.perPage = perPage;
        return query;
    }

    /** The {@code perPage} posts just newer than {@code postId}. */
    public static PostsQuery after(String postId, int perPage) {
        PostsQuery query = new PostsQuery();
        query.after = postId;
        query.perPage = perPage;
        return query;
    }

    /** Posts created, edited or deleted after {@code millis} (catch-up). */
    public static PostsQuery since(long millis) {
        PostsQuery query = new PostsQuery();
        query.since = millis;
        return query;
    }

    public PostsQuery includeDeleted(boolean value) {
        includeDeleted = value;
        return this;
    }

    /** Only root posts in {@code order} (collapsed reply threads). */
    public PostsQuery collapsedThreads(boolean value) {
        collapsedThreads = value;
        return this;
    }

    public PostsQuery collapsedThreadsExtended(boolean value) {
        collapsedThreadsExtended = value;
        return this;
    }

    QueryBuilder toQuery() {
        QueryBuilder query = new QueryBuilder();
        query.add("page", page);
        query.add("per_page", perPage);
        query.add("before", before);
        query.add("after", after);
        query.add("since", since);
        query.flag("include_deleted", includeDeleted);
        query.flag("collapsedThreads", collapsedThreads);
        query.flag("collapsedThreadsExtended", collapsedThreadsExtended);
        return query;
    }
}
