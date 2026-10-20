package com.dom.datamonitor.web;

import com.dom.datamonitor.config.AppConfig;
import com.dom.datamonitor.config.ConfigLoader;
import com.dom.datamonitor.config.ConfigWriter;
import com.dom.datamonitor.config.ConnectionConfig;
import com.dom.datamonitor.config.QModuleConfig;
import com.dom.datamonitor.db.SQLExecutor;
import com.dom.datamonitor.engine.MonitorEngine;
import com.dom.datamonitor.model.ProcessingMode;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** The server over real HTTP on a free port; executors are inert, so no database is needed. */
class WebServerTest {

    private static final String TOKEN = "test-token";

    private WebServer server;
    private MonitorEngine engine;
    private Path file;
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void setUp(@TempDir Path dir) throws Exception {
        file = dir.resolve("dm.yaml");
        Files.copy(Path.of("src/test/resources/sample-datamonitor.yaml"), file);
        AppConfig config = ConfigLoader.load(file.toFile());

        // Give one module its own connection, to check the password never reaches the browser
        ConnectionConfig secret = new ConnectionConfig();
        secret.setUsername("reporter");
        secret.setPassword("s3cret-pw");
        secret.setConnectString("//other/db");
        config.getQmodules().get(0).setConnection(secret);

        engine = new MonitorEngine(config, new ConfigWriter(file.toFile()), (m, c, mode, rate) ->
            new SQLExecutor("SELECT 1 FROM dual", ProcessingMode.NONE, 1.0, null, 1) {
                @Override public void start() {}
                @Override public void stop()  {}
            });
        server = new WebServer(engine, "9.9.9", "light", "127.0.0.1", 0, TOKEN);
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop();
    }

    private HttpResponse<String> request(String method, String path, String body, boolean withToken, String origin)
            throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
            .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (withToken) b.header("Cookie", WebServer.COOKIE + "=" + TOKEN);
        if (body != null) b.header("Content-Type", "application/json");
        if (origin != null) b.header("Origin", origin);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path) throws Exception {
        return request("GET", path, null, true, null);
    }

    private static JsonObject json(HttpResponse<String> r) {
        return JsonParser.parseString(r.body()).getAsJsonObject();
    }

    @Test
    void apiNeedsTheToken() throws Exception {
        assertEquals(401, request("GET", "/api/dashboard", null, false, null).statusCode());
        assertEquals(200, get("/api/dashboard").statusCode());

        HttpResponse<String> bearer = http.send(HttpRequest.newBuilder(
            URI.create("http://127.0.0.1:" + server.port() + "/api/dashboard"))
            .header("Authorization", "Bearer " + TOKEN).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, bearer.statusCode());
    }

    @Test
    void tokenLinkSetsAStrictHttpOnlyCookieAndRedirects() throws Exception {
        HttpResponse<String> r = request("GET", "/?token=" + TOKEN, null, false, null);
        assertEquals(303, r.statusCode());
        assertEquals("/", r.headers().firstValue("Location").orElse(""));
        String cookie = r.headers().firstValue("Set-Cookie").orElse("");
        assertTrue(cookie.contains("HttpOnly") && cookie.contains("SameSite=Strict"), cookie);

        assertNotEquals(303, request("GET", "/?token=wrong", null, false, null).statusCode());
    }

    @Test
    void servesTheAppAndVendorFilesButNothingElse() throws Exception {
        HttpResponse<String> index = request("GET", "/", null, false, null);
        assertEquals(200, index.statusCode());
        assertTrue(index.headers().firstValue("Content-Security-Policy").isPresent());
        assertEquals(200, get("/app/app.js").statusCode());
        assertEquals(200, get("/vendor/echarts/dist/echarts.min.js").statusCode());
        assertEquals(200, get("/vendor/codemirror/mode/sql/sql.js").statusCode());
        assertEquals(404, get("/vendor/unknown/x.js").statusCode());
        assertEquals(404, get("/app/../../../etc/passwd").statusCode());
        assertEquals(404, get("/vendor/echarts/../../../../META-INF/MANIFEST.MF").statusCode());
    }

    @Test
    void dashboardDescribesModulesWithoutPasswords() throws Exception {
        HttpResponse<String> r = get("/api/dashboard");
        JsonObject d = json(r);
        assertEquals("9.9.9", d.get("version").getAsString());
        assertEquals("light", d.get("theme").getAsString());
        assertEquals("system@//localhost/soe", d.get("connection").getAsString());

        JsonObject first = d.getAsJsonArray("modules").get(0).getAsJsonObject();
        JsonObject conn = first.getAsJsonObject("connection");
        assertTrue(conn.get("hasPassword").getAsBoolean());
        assertFalse(conn.has("password"));
        assertFalse(r.body().contains("s3cret-pw"));
        assertFalse(r.body().contains("welcome1"));

        // Span-all grids are sent with an explicit end column
        JsonObject io = d.getAsJsonArray("modules").asList().stream().map(e -> e.getAsJsonObject())
            .filter(m -> m.get("grid").getAsString().equals("R2,C1*")).findFirst().orElseThrow();
        assertEquals(3, io.get("colEnd").getAsInt());
        assertEquals(io.get("row").getAsInt(), io.get("rowEnd").getAsInt());
    }

    @Test
    void addEditDeleteRoundTrip() throws Exception {
        String body = "{\"title\":\"Web added\",\"chartType\":\"BAR\",\"processingMode\":\"NONE\",\"divideBy\":1,"
            + "\"grid\":\"R9-10,C1-2\",\"sql\":\"SELECT 'a', 1 FROM dual\",\"connection\":null}";
        HttpResponse<String> added = request("POST", "/api/modules", body, true, null);
        assertEquals(201, added.statusCode(), added.body());
        String id = json(added).get("id").getAsString();
        assertTrue(Files.readString(file).contains("title: Web added"));
        assertTrue(Files.readString(file).contains("grid: R9-10,C1-2"));

        JsonObject multi = json(get("/api/dashboard")).getAsJsonArray("modules").asList().stream()
            .map(e -> e.getAsJsonObject()).filter(m -> m.get("id").getAsString().equals(id)).findFirst().orElseThrow();
        assertEquals(9, multi.get("row").getAsInt());
        assertEquals(10, multi.get("rowEnd").getAsInt());

        HttpResponse<String> edited = request("PUT", "/api/modules/" + id, body.replace("Web added", "Web edited"), true, null);
        assertEquals(200, edited.statusCode(), edited.body());
        assertEquals("Web edited", engine.module(id).config().getTitle());

        assertEquals(204, request("DELETE", "/api/modules/" + id, null, true, null).statusCode());
        assertNull(engine.module(id));
        assertFalse(Files.readString(file).contains("Web edited"));
        assertEquals(404, request("DELETE", "/api/modules/" + id, null, true, null).statusCode());
    }

    @Test
    void editingWithABlankPasswordKeepsTheExistingOne() throws Exception {
        String id = engine.modules().get(0).id();
        QModuleConfig before = engine.module(id).config();
        String body = "{\"title\":\"" + before.getTitle() + "\",\"chartType\":\"" + before.getChartType() + "\","
            + "\"grid\":\"" + before.getGrid() + "\",\"sql\":\"SELECT 'a', 1 FROM dual\",\"divideBy\":1,"
            + "\"connection\":{\"username\":\"reporter\",\"password\":\"\",\"connectString\":\"//other/db\"}}";
        assertEquals(200, request("PUT", "/api/modules/" + id, body, true, null).statusCode());
        assertEquals("s3cret-pw", engine.module(id).config().getConnection().getPassword());
    }

    @Test
    void refusesBadInputCrossSiteRequestsAndDml() throws Exception {
        String dml = "{\"title\":\"x\",\"chartType\":\"TEXT\",\"grid\":\"R9,C1\",\"sql\":\"DELETE FROM t\"}";
        HttpResponse<String> r = request("POST", "/api/modules", dml, true, null);
        assertEquals(400, r.statusCode());
        assertTrue(json(r).get("error").getAsString().contains("SELECT"));

        assertEquals(400, request("POST", "/api/modules", "not json", true, null).statusCode());
        assertEquals(400, request("POST", "/api/modules", "{\"title\":\"x\",\"chartType\":\"NOPE\"}", true, null).statusCode());

        String ok = "{\"title\":\"x\",\"chartType\":\"TEXT\",\"grid\":\"R9,C1\",\"sql\":\"SELECT 'a', 1 FROM dual\"}";
        assertEquals(403, request("POST", "/api/modules", ok, true, "http://evil.example").statusCode());
        assertEquals(201, request("POST", "/api/modules", ok, true, "http://127.0.0.1:" + server.port()).statusCode());
    }

    @Test
    void validateRefusesNonQueriesWithoutTouchingTheDatabase() throws Exception {
        HttpResponse<String> r = request("POST", "/api/validate", "{\"sql\":\"DROP TABLE t\",\"chartType\":\"LINE\"}", true, null);
        assertEquals(200, r.statusCode());
        assertFalse(json(r).get("ok").getAsBoolean());
    }

    @Test
    void colourNamesResolveLikeTheSwingCharts() {
        assertEquals("#d2321e", ApiHandler.colourHex("red"));
        assertEquals("#ff5733", ApiHandler.colourHex("#FF5733"));
        assertNull(ApiHandler.colourHex("not-a-colour"));
        assertNull(ApiHandler.colourHex(null));
    }
}
