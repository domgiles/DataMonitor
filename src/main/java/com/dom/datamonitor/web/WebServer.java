package com.dom.datamonitor.web;

import com.dom.datamonitor.engine.MonitorEngine;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Embedded HTTP server for the web front end, built on the JDK's {@code com.sun.net.httpserver}.
 *
 * <ul>
 *   <li>{@code /}            – the single-page app ({@code web/index.html} on the classpath)</li>
 *   <li>{@code /app/*}       – the app's own JS/CSS ({@code web/*})</li>
 *   <li>{@code /vendor/<lib>/*} – files from the ECharts / CodeMirror WebJars</li>
 *   <li>{@code /api/*}       – REST API and live event stream; see {@link ApiHandler}</li>
 * </ul>
 *
 * <h3>Access control</h3>
 * The API can run SQL with the dashboard's credentials and rewrite the config, so every
 * {@code /api} request needs the access token generated at startup. Opening
 * {@code /?token=…} (the link printed on the console) stores it in an HttpOnly,
 * SameSite=Strict cookie and redirects to {@code /}. Requests that change state are also
 * refused when their {@code Origin} is a different site.
 */
public class WebServer {

    private static final Logger log = Logger.getLogger(WebServer.class.getName());

    static final String COOKIE = "dm_token";

    private static final Map<String, String> CONTENT_TYPES = Map.of(
        "html", "text/html; charset=utf-8",
        "js",   "text/javascript; charset=utf-8",
        "css",  "text/css; charset=utf-8",
        "json", "application/json; charset=utf-8",
        "svg",  "image/svg+xml",
        "png",  "image/png",
        "ico",  "image/x-icon",
        "woff2", "font/woff2");

    /** WebJars served under {@code /vendor/<name>/}; the version is read from the jar itself. */
    private static final String[] WEBJARS = {"echarts", "codemirror"};

    private final HttpServer      server;
    private final ExecutorService threads;
    private final String          token;
    private final EventStream     events;

    /**
     * @param theme initial browser theme ({@code dark} / {@code light})
     * @param bind  address to listen on, e.g. {@code 127.0.0.1} (this machine only) or {@code 0.0.0.0}
     * @param port  TCP port; 0 picks a free one
     * @param token access token; {@code null} generates a random one
     */
    public WebServer(MonitorEngine engine, String version, String theme, String bind, int port, String token)
            throws IOException {
        this.token   = (token != null) ? token : randomToken();
        this.server  = HttpServer.create(new InetSocketAddress(bind, port), 0);
        // Cached pool: each open event stream holds a thread while it is connected
        this.threads = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "dm-web");
            t.setDaemon(true);
            return t;
        });
        server.setExecutor(threads);

        ApiHandler api = new ApiHandler(engine, version, theme);
        this.events = new EventStream(engine, api::dashboardJson);

        server.createContext("/api/events", ex -> guarded(ex, () -> events.handle(ex)));
        server.createContext("/api/",       ex -> guarded(ex, () -> api.handle(ex)));
        server.createContext("/vendor/",    ex -> handled(ex, () -> serveVendor(ex)));
        server.createContext("/app/",       ex -> handled(ex, () -> serveApp(ex)));
        server.createContext("/",           ex -> handled(ex, () -> serveRoot(ex)));
    }

    public void start() {
        server.start();
        events.start();
    }

    public void stop() {
        events.stop();
        server.stop(0);
        threads.shutdownNow();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public String token() {
        return token;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Access control
    // ─────────────────────────────────────────────────────────────────────────

    private interface Action { void run() throws IOException; }

    /** Runs {@code action} only for authorised, same-site requests. */
    private void guarded(HttpExchange ex, Action action) {
        handled(ex, () -> {
            if (!hasToken(ex)) {
                sendJson(ex, 401, "{\"error\":\"Missing or invalid access token. "
                    + "Open the link printed on the console when the server started.\"}");
                return;
            }
            if (!"GET".equals(ex.getRequestMethod()) && isCrossSite(ex)) {
                sendJson(ex, 403, "{\"error\":\"Cross-site request refused\"}");
                return;
            }
            action.run();
        });
    }

    boolean hasToken(HttpExchange ex) {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        if (auth != null && auth.startsWith("Bearer ") && matches(auth.substring(7).trim())) return true;
        String cookies = ex.getRequestHeaders().getFirst("Cookie");
        if (cookies != null) {
            for (String c : cookies.split(";")) {
                String[] kv = c.trim().split("=", 2);
                if (kv.length == 2 && kv[0].equals(COOKIE) && matches(kv[1])) return true;
            }
        }
        return false;
    }

    /** Constant-time comparison, so response timing reveals nothing about the token. */
    private boolean matches(String candidate) {
        return MessageDigest.isEqual(candidate.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8));
    }

    /** True when an {@code Origin} header names a different host than the one requested. */
    private static boolean isCrossSite(HttpExchange ex) {
        String origin = ex.getRequestHeaders().getFirst("Origin");
        String host   = ex.getRequestHeaders().getFirst("Host");
        if (origin == null || host == null) return false;
        try {
            URI o = URI.create(origin);
            String originHost = o.getHost() + (o.getPort() > 0 ? ":" + o.getPort() : "");
            return !originHost.equalsIgnoreCase(host);
        } catch (IllegalArgumentException e) {
            return true;
        }
    }

    private static String randomToken() {
        byte[] bytes = new byte[18];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Static files
    // ─────────────────────────────────────────────────────────────────────────

    /** {@code /?token=…} sets the cookie and redirects to a clean URL; {@code /} serves the app. */
    private void serveRoot(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        if (!path.equals("/") && !path.equals("/index.html")) {
            sendJson(ex, 404, "{\"error\":\"Not found\"}");
            return;
        }
        String query = ex.getRequestURI().getRawQuery();
        if (query != null) {
            for (String p : query.split("&")) {
                if (p.startsWith("token=") && matches(p.substring(6))) {
                    ex.getResponseHeaders().add("Set-Cookie",
                        COOKIE + "=" + token + "; Path=/; HttpOnly; SameSite=Strict");
                    ex.getResponseHeaders().add("Location", "/");
                    ex.sendResponseHeaders(303, -1);
                    return;
                }
            }
        }
        serveResource(ex, "web/index.html", false);
    }

    private void serveApp(HttpExchange ex) throws IOException {
        String rest = ex.getRequestURI().getPath().substring("/app/".length());
        serveResource(ex, "web/" + rest, false);
    }

    private void serveVendor(HttpExchange ex) throws IOException {
        String rest = ex.getRequestURI().getPath().substring("/vendor/".length());
        int slash = rest.indexOf('/');
        String lib = (slash > 0) ? rest.substring(0, slash) : rest;
        String version = webjarVersion(lib);
        if (version == null) {
            sendJson(ex, 404, "{\"error\":\"Not found\"}");
            return;
        }
        serveResource(ex, "META-INF/resources/webjars/" + lib + "/" + version + rest.substring(slash), true);
    }

    private static String webjarVersion(String lib) {
        for (String known : WEBJARS) {
            if (!known.equals(lib)) continue;
            try (InputStream in = WebServer.class.getClassLoader().getResourceAsStream(
                    "META-INF/maven/org.webjars.npm/" + lib + "/pom.properties")) {
                if (in == null) return null;
                Properties p = new Properties();
                p.load(in);
                return p.getProperty("version");
            } catch (IOException e) {
                return null;
            }
        }
        return null;
    }

    private static void serveResource(HttpExchange ex, String resource, boolean immutable) throws IOException {
        if (resource.contains("..") || resource.contains("\\") || resource.endsWith("/")) {
            sendJson(ex, 404, "{\"error\":\"Not found\"}");
            return;
        }
        try (InputStream in = WebServer.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                sendJson(ex, 404, "{\"error\":\"Not found\"}");
                return;
            }
            byte[] body = in.readAllBytes();
            String ext  = resource.substring(resource.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
            ex.getResponseHeaders().set("Content-Type", CONTENT_TYPES.getOrDefault(ext, "application/octet-stream"));
            ex.getResponseHeaders().set("Cache-Control", immutable ? "public, max-age=86400" : "no-cache");
            ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            if (ext.equals("html")) {
                ex.getResponseHeaders().set("Content-Security-Policy",
                    "default-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; frame-ancestors 'none'");
            }
            send(ex, 200, body);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /** Runs {@code action}; any failure becomes a 500 and the exchange is always closed. */
    private static void handled(HttpExchange ex, Action action) {
        try {
            action.run();
        } catch (Exception e) {
            log.log(Level.WARNING, "Request failed: " + ex.getRequestURI(), e);
            try {
                sendJson(ex, 500, "{\"error\":\"Internal error\"}");
            } catch (IOException ignored) {
                // response already started
            }
        } finally {
            ex.close();
        }
    }

    static void sendJson(HttpExchange ex, int status, String json) throws IOException {
        ex.getResponseHeaders().set("Content-Type", CONTENT_TYPES.get("json"));
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        send(ex, status, json.getBytes(StandardCharsets.UTF_8));
    }

    private static void send(HttpExchange ex, int status, byte[] body) throws IOException {
        ex.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            try (OutputStream out = ex.getResponseBody()) {
                out.write(body);
            }
        }
    }
}
