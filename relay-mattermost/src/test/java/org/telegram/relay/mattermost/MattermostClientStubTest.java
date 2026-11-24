package org.telegram.relay.mattermost;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.telegram.relay.mattermost.model.LoginResult;
import org.telegram.relay.mattermost.model.PostList;
import org.telegram.relay.mattermost.model.FileUploadResponse;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Request formation and response handling against a local stub HTTP server serving the recorded fixtures. */
public class MattermostClientStubTest {
    private static final Charset UTF8 = Charset.forName("UTF-8");

    static final class Recorded {
        String method;
        String uri;
        String rawQuery;
        String authorization;
        String requestedWith;
        String contentType;
        String range;
        byte[] body;
    }

    private HttpServer server;
    private final List<Recorded> requests = new ArrayList<Recorded>();
    private MattermostClient client;

    @Before
    public void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                Recorded recorded = new Recorded();
                recorded.method = exchange.getRequestMethod();
                recorded.uri = exchange.getRequestURI().getRawPath();
                recorded.rawQuery = exchange.getRequestURI().getRawQuery();
                recorded.authorization = exchange.getRequestHeaders().getFirst("Authorization");
                recorded.requestedWith = exchange.getRequestHeaders().getFirst("X-Requested-With");
                recorded.contentType = exchange.getRequestHeaders().getFirst("Content-Type");
                recorded.range = exchange.getRequestHeaders().getFirst("Range");
                recorded.body = MattermostClient.readFully(exchange.getRequestBody());
                synchronized (requests) {
                    requests.add(recorded);
                }
                route(exchange, recorded);
            }
        });
        server.start();
        client = new MattermostClient("http://127.0.0.1:" + server.getAddress().getPort() + "/");
    }

    @After
    public void tearDown() {
        server.stop(0);
    }

    private static void respond(HttpExchange exchange, int status, String contentType, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        OutputStream out = exchange.getResponseBody();
        out.write(body);
        out.close();
    }

    private static final byte[] FILE = new byte[1000];

    static {
        for (int i = 0; i < FILE.length; i++) {
            FILE[i] = (byte) i;
        }
    }

    private void route(HttpExchange exchange, Recorded request) throws IOException {
        String path = request.uri;
        if (path.equals("/api/v4/users/login")) {
            exchange.getResponseHeaders().set("Token", "session-token");
            respond(exchange, 200, "application/json", Fixtures.rest("users_login").getBytes(UTF8));
        } else if (path.equals("/api/v4/system/ping")) {
            exchange.getResponseHeaders().set("X-Version-Id", "11.7.14.1.abc.false");
            respond(exchange, 200, "application/json", Fixtures.rest("system_ping").getBytes(UTF8));
        } else if (path.startsWith("/api/v4/channels/") && path.endsWith("/posts")) {
            respond(exchange, 200, "application/json", Fixtures.rest("posts_page_firehose").getBytes(UTF8));
        } else if (path.equals("/api/v4/posts/zzzzzzzzzzzzzzzzzzzzzzzzzz")) {
            respond(exchange, 404, "application/json", Fixtures.rest("error_post_not_found").getBytes(UTF8));
        } else if (path.equals("/api/v4/users/me") && "Bearer expired".equals(request.authorization)) {
            respond(exchange, 401, "application/json", "{\"id\":\"api.context.session_expired.app_error\",\"message\":\"expired\",\"status_code\":401}".getBytes(UTF8));
        } else if (path.equals("/api/v4/files/f1")) {
            String range = request.range;
            if (range != null && range.startsWith("bytes=")) {
                String[] parts = range.substring(6).split("-", -1);
                int start = Integer.parseInt(parts[0]);
                int end = parts[1].isEmpty() ? FILE.length - 1 : Integer.parseInt(parts[1]);
                byte[] slice = new byte[end - start + 1];
                System.arraycopy(FILE, start, slice, 0, slice.length);
                exchange.getResponseHeaders().set("Content-Range", "bytes " + start + "-" + end + "/" + FILE.length);
                respond(exchange, 206, "application/octet-stream", slice);
            } else {
                respond(exchange, 200, "application/octet-stream", FILE);
            }
        } else if (path.equals("/api/v4/files")) {
            respond(exchange, 201, "application/json", "{\"file_infos\":[{\"id\":\"f1\",\"name\":\"a.bin\",\"size\":3}],\"client_ids\":[\"cid\"]}".getBytes(UTF8));
        } else {
            respond(exchange, 200, "application/json", "{\"status\":\"OK\"}".getBytes(UTF8));
        }
    }

    private Recorded last() {
        synchronized (requests) {
            return requests.get(requests.size() - 1);
        }
    }

    @Test
    public void loginAdoptsTokenAndAuthenticatesLaterRequests() throws Exception {
        LoginResult result = client.login("alice", "secret");
        assertEquals("session-token", result.token);
        assertEquals("alice", result.user.username);
        Recorded login = last();
        assertEquals("POST", login.method);
        assertNull("login is sent without a token", login.authorization);
        assertEquals("XMLHttpRequest", login.requestedWith);
        assertTrue(login.contentType.startsWith("application/json"));
        String body = new String(login.body, UTF8);
        assertTrue(body.contains("\"login_id\":\"alice\""));
        assertTrue("no MFA token key unless given", !body.contains("\"token\""));

        client.posts("chan", PostsQuery.before("p1", 60).collapsedThreads(true));
        Recorded posts = last();
        assertEquals("Bearer session-token", posts.authorization);
        assertEquals("/api/v4/channels/chan/posts", posts.uri);
        assertEquals("per_page=60&before=p1&collapsedThreads=true", posts.rawQuery);

        client.logout();
        assertNull(client.getToken());
    }

    @Test
    public void pathAndQueryEncoding() throws Exception {
        client.setToken("t");
        client.removeReaction("post1", "+1");
        assertEquals("DELETE", last().method);
        assertEquals("/api/v4/users/me/posts/post1/reactions/+1", last().uri);
        client.profileImage("u1", 1234).close();
        assertEquals("_=1234", last().rawQuery);
        client.searchPosts("team", "from:bob hello", false, 0, 20);
        assertEquals("/api/v4/teams/team/posts/search", last().uri);
        assertTrue(new String(last().body, UTF8).contains("\"terms\":\"from:bob hello\""));
        assertEquals("a%20b%2Bc%2F%C3%A9", UrlCodec.encodeQueryComponent("a b+c/é"));
        assertEquals("/emoji/name/+1%2Fx", UrlCodec.path("emoji", "name", "+1/x"));
        client.viewChannel("c1");
        assertEquals("/api/v4/channels/members/me/view", last().uri);
        assertTrue(new String(last().body, UTF8).contains("\"channel_id\":\"c1\""));
        client.pinPost("p1");
        assertEquals("POST", last().method);
    }

    @Test
    public void createPostSendsProps() throws Exception {
        client.setToken("t");
        org.telegram.relay.mattermost.model.PostDraft draft = new org.telegram.relay.mattermost.model.PostDraft("c1", "");
        draft.pendingPostId("u1:abc");
        com.google.gson.JsonObject voice = new com.google.gson.JsonObject();
        voice.addProperty("v", 1);
        voice.addProperty("duration", 3);
        draft.props = new com.google.gson.JsonObject();
        draft.props.add("relay_voice", voice);
        client.createPost(draft);
        String body = new String(last().body, UTF8);
        assertTrue(body, body.contains("\"props\":{\"relay_voice\":{\"v\":1,\"duration\":3}}"));
        assertTrue(body, body.contains("\"pending_post_id\":\"u1:abc\""));
        client.createPost(new org.telegram.relay.mattermost.model.PostDraft("c1", "plain"));
        assertTrue(!new String(last().body, UTF8).contains("\"props\":{"));
    }

    @Test
    public void pingReadsVersionHeader() throws Exception {
        assertEquals("11.7.14", client.ping(true).getServerVersion().toString());
        assertEquals("get_server_status=true", last().rawQuery);
        assertNull(last().authorization);
    }

    @Test
    public void errorsCarryStatusAndId() {
        client.setToken("t");
        try {
            client.post("zzzzzzzzzzzzzzzzzzzzzzzzzz");
            fail("expected 404");
        } catch (MattermostException e) {
            assertTrue(e.isNotFound());
            assertEquals("app.post.get.app_error", e.getErrorId());
        }
        final AtomicReference<MattermostException> unauthorized = new AtomicReference<MattermostException>();
        client.setListener(new MattermostClient.Listener() {
            @Override
            public void onUnauthorized(MattermostClient source, MattermostException error) {
                unauthorized.set(error);
            }
        });
        client.setToken("expired");
        assertTrue(!client.isTokenAccepted());
        assertEquals("api.context.session_expired.app_error", unauthorized.get().getErrorId());

        MattermostClient offline = new MattermostClient("http://127.0.0.1:1");
        try {
            offline.ping(false);
            fail("expected a transport error");
        } catch (MattermostException e) {
            assertEquals(MattermostException.Kind.TRANSPORT, e.getKind());
            assertTrue(e.isTransient());
        }
    }

    @Test
    public void rangedDownloads() throws Exception {
        client.setToken("t");
        FileDownload whole = client.downloadFile("f1");
        assertEquals(200, whole.getStatusCode());
        assertEquals(1000, whole.getTotalLength());
        assertArrayEquals(FILE, whole.readAll());

        FileDownload part = client.downloadFile("f1", 100, 199);
        assertEquals("bytes=100-199", last().range);
        assertTrue(part.isPartial());
        assertEquals(100, part.getRangeStart());
        assertEquals(199, part.getRangeEnd());
        assertEquals(1000, part.getTotalLength());
        byte[] bytes = part.readAll();
        assertEquals(100, bytes.length);
        assertEquals((byte) 100, bytes[0]);

        FileDownload tail = client.downloadFile("f1", 900, -1);
        assertEquals("bytes=900-", last().range);
        assertEquals(100, tail.readAll().length);
    }

    @Test
    public void multipartUpload() throws Exception {
        client.setToken("t");
        final List<long[]> progress = new ArrayList<long[]>();
        FileUploadResponse response = client.uploadFile("chan", new FileUpload("cid", "a \"b\".bin", "application/octet-stream", FileUpload.ofBytes("x", null, new byte[]{1, 2, 3}).source), new UploadProgressListener() {
            @Override
            public void onProgress(long sent, long total) {
                progress.add(new long[]{sent, total});
            }
        });
        assertEquals("f1", response.fileInfos.get(0).id);
        assertEquals("cid", response.clientIds.get(0));
        Recorded upload = last();
        assertTrue(upload.contentType.startsWith("multipart/form-data; boundary="));
        String body = new String(upload.body, UTF8);
        int channel = body.indexOf("name=\"channel_id\"");
        int clientId = body.indexOf("name=\"client_ids\"");
        int file = body.indexOf("name=\"files\"; filename=\"a \\\"b\\\".bin\"");
        assertTrue(channel >= 0 && clientId > channel && file > clientId);
        long[] lastProgress = progress.get(progress.size() - 1);
        assertEquals(upload.body.length, lastProgress[0]);
        assertEquals(upload.body.length, lastProgress[1]);
    }

    @Test
    public void postListFromStub() throws Exception {
        client.setToken("t");
        PostList list = client.posts("chan", PostsQuery.latest(60));
        assertEquals(60, list.size());
        assertEquals("page=0&per_page=60", last().rawQuery);
    }
}
