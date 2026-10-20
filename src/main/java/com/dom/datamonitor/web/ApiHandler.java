package com.dom.datamonitor.web;

import com.dom.datamonitor.charts.ColorPalette;
import com.dom.datamonitor.config.AppConfig;
import com.dom.datamonitor.config.ConfigException;
import com.dom.datamonitor.config.ConnectionConfig;
import com.dom.datamonitor.config.QModuleConfig;
import com.dom.datamonitor.db.SqlValidator;
import com.dom.datamonitor.engine.MonitorEngine;
import com.dom.datamonitor.model.ChartType;
import com.dom.datamonitor.model.GridOccupancy;
import com.dom.datamonitor.model.ProcessingMode;
import com.dom.datamonitor.model.QueryRow;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;

import java.awt.Color;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * REST API under {@code /api}. All bodies are JSON.
 *
 * <pre>
 *   GET    /api/dashboard        settings + modules (with recent history)
 *   POST   /api/modules          add a module            → 201 {"id": …}
 *   PUT    /api/modules/{id}     replace a module        → 200 {"id": …}
 *   DELETE /api/modules/{id}     delete a module         → 204
 *   POST   /api/validate         run SQL once + preview  → {"ok", "message", "warnings", "preview"}
 * </pre>
 *
 * <p>Errors are {@code {"error": "…"}} with 400 (invalid input), 404 (unknown module) or
 * 500 (e.g. the change was applied but saving the YAML failed).
 *
 * <p>Passwords are never sent to the browser: a module connection is returned with
 * {@code hasPassword} instead. When a module is saved or validated with a blank password,
 * the module's existing password is kept.
 */
class ApiHandler {

    private static final Logger log = Logger.getLogger(ApiHandler.class.getName());

    private static final int MAX_BODY_BYTES = 1 << 20;

    private final MonitorEngine engine;
    private final String        version;
    private final String        theme;

    /** @param theme initial browser theme; may differ from the config when set with {@code --theme} */
    ApiHandler(MonitorEngine engine, String version, String theme) {
        this.engine  = engine;
        this.version = version;
        this.theme   = theme;
    }

    void handle(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod();
        String path   = ex.getRequestURI().getPath().substring("/api/".length());

        try {
            if (path.equals("dashboard") && method.equals("GET")) {
                WebServer.sendJson(ex, 200, dashboardJson());

            } else if (path.equals("modules") && method.equals("POST")) {
                String id = engine.add(moduleFromJson(readJson(ex), null));
                WebServer.sendJson(ex, 201, idJson(id));

            } else if (path.startsWith("modules/") && method.equals("PUT")) {
                String id = path.substring("modules/".length());
                MonitorEngine.ModuleView existing = requireModule(id);
                engine.update(id, moduleFromJson(readJson(ex), existing.config()));
                WebServer.sendJson(ex, 200, idJson(id));

            } else if (path.startsWith("modules/") && method.equals("DELETE")) {
                engine.delete(requireModule(path.substring("modules/".length())).id());
                ex.sendResponseHeaders(204, -1);

            } else if (path.equals("validate") && method.equals("POST")) {
                WebServer.sendJson(ex, 200, validate(readJson(ex)));

            } else {
                WebServer.sendJson(ex, 404, errorJson("Unknown API call: " + method + " /api/" + path));
            }
        } catch (NotFound e) {
            WebServer.sendJson(ex, 404, errorJson(e.getMessage()));
        } catch (ConfigException | JsonParseException | IllegalStateException | ClassCastException e) {
            WebServer.sendJson(ex, 400, errorJson(e.getMessage()));
        } catch (IOException e) {
            // The engine applies a change before saving, so the dashboard has changed
            log.log(Level.WARNING, "Saving the configuration failed", e);
            WebServer.sendJson(ex, 500, errorJson(
                "The dashboard was updated, but saving the configuration failed: " + e.getMessage()));
        }
    }

    private static final class NotFound extends Exception {
        NotFound(String message) { super(message); }
    }

    private MonitorEngine.ModuleView requireModule(String id) throws NotFound {
        MonitorEngine.ModuleView m = engine.module(id);
        if (m == null) throw new NotFound("No QModule with id " + id);
        return m;
    }

    private static JsonObject readJson(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            byte[] body = in.readNBytes(MAX_BODY_BYTES + 1);
            if (body.length > MAX_BODY_BYTES) throw new JsonParseException("Request body too large");
            JsonElement e = JsonParser.parseString(new String(body, StandardCharsets.UTF_8));
            if (!e.isJsonObject()) throw new JsonParseException("Expected a JSON object");
            return e.getAsJsonObject();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Responses
    // ─────────────────────────────────────────────────────────────────────────

    String dashboardJson() {
        AppConfig.Settings s = engine.settings();
        JsonObject j = new JsonObject();
        j.addProperty("version",       version);
        j.addProperty("title",         s.getTitle());
        j.addProperty("theme",         theme);
        j.addProperty("columns",       s.getColumns());
        j.addProperty("border",        s.getBorder());
        j.addProperty("refreshRate",   s.getRefreshRate());
        j.addProperty("windowSize",    s.getWindowSize());
        j.addProperty("flashOnChange", s.isFlashOnChange());
        ConnectionConfig c = s.getEffectiveConnection();
        j.addProperty("connection", c == null ? null : c.getUsername() + "@" + c.getConnectString());
        j.add("chartTypes",      names(ChartType.values()));
        j.add("processingModes", names(ProcessingMode.values()));

        JsonArray modules = new JsonArray();
        for (MonitorEngine.ModuleView m : engine.modules()) modules.add(moduleJson(m, s.getColumns()));
        j.add("modules", modules);
        return j.toString();
    }

    private static JsonObject moduleJson(MonitorEngine.ModuleView m, int columns) {
        QModuleConfig q = m.config();
        JsonObject j = new JsonObject();
        j.addProperty("id",             m.id());
        j.addProperty("title",          q.getTitle());
        j.addProperty("chartType",      q.getChartType().toUpperCase());
        j.addProperty("processingMode", q.getProcessingMode() == null ? "NONE" : q.getProcessingMode().toUpperCase());
        j.addProperty("refreshRate",    m.refreshRate());
        j.addProperty("windowSize",     m.windowSize());
        j.addProperty("refreshRateOverride", q.getRefreshRate());
        j.addProperty("windowSizeOverride",  q.getWindowSize());
        j.addProperty("divideBy",       q.getDivideBy());
        j.addProperty("grid",           q.getGrid());
        j.addProperty("row",            m.location().row());
        j.addProperty("colStart",       m.location().colStart());
        // Explicit end column: the browser never sees the "span all" sentinel (C2* → C2-3)
        j.addProperty("colEnd",         GridOccupancy.lastColumn(m.location(), columns));
        j.addProperty("sql",            q.getSql() == null ? "" : q.getSql().strip());

        ConnectionConfig c = q.getConnection();
        if (c == null) {
            j.add("connection", JsonNull.INSTANCE);
        } else {
            JsonObject cj = new JsonObject();
            cj.addProperty("username",        c.getUsername());
            cj.addProperty("connectString",   c.getConnectString());
            cj.addProperty("credentialsFile", c.getCredentialsFile());
            cj.addProperty("hasPassword",     c.getPassword() != null && !c.getPassword().isEmpty());
            j.add("connection", cj);
        }

        JsonArray history = new JsonArray();
        for (MonitorEngine.Sample s : m.history()) history.add(sampleJson(s));
        j.add("history", history);
        return j;
    }

    static String resultJson(String moduleId, MonitorEngine.Sample sample) {
        JsonObject j = new JsonObject();
        j.addProperty("id", moduleId);
        j.add("sample", sampleJson(sample));
        return j.toString();
    }

    private static JsonObject sampleJson(MonitorEngine.Sample s) {
        JsonObject j = new JsonObject();
        j.addProperty("time", s.time());
        j.add("rows", rowsJson(s.rows()));
        j.addProperty("error", s.error());
        return j;
    }

    private static JsonArray rowsJson(List<QueryRow> rows) {
        JsonArray a = new JsonArray();
        for (QueryRow r : rows) {
            JsonObject j = new JsonObject();
            j.addProperty("key", r.key());
            // JSON has no NaN/Infinity
            j.addProperty("value", Double.isFinite(r.value()) ? r.value() : null);
            j.addProperty("color", colourHex(r.color()));
            a.add(j);
        }
        return a;
    }

    /**
     * Resolves a SQL colour (name, hex, rgb(), AWT field) exactly as the Swing charts do, so a
     * module looks the same in both; {@code null} lets the browser use its default palette.
     */
    static String colourHex(String colour) {
        Color c = ColorPalette.resolve(colour, null);
        return (c == null) ? null : String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
    }

    private static JsonArray names(Enum<?>[] values) {
        JsonArray a = new JsonArray();
        for (Enum<?> v : values) a.add(v.name());
        return a;
    }

    private static String idJson(String id) {
        JsonObject j = new JsonObject();
        j.addProperty("id", id);
        return j.toString();
    }

    static String errorJson(String message) {
        JsonObject j = new JsonObject();
        j.addProperty("error", message);
        return j.toString();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Requests
    // ─────────────────────────────────────────────────────────────────────────

    private String validate(JsonObject body) throws ConfigException {
        ChartType type = ChartType.fromString(str(body, "chartType", "LINE"));
        double divideBy = num(body, "divideBy", 1.0);

        QModuleConfig existing = null;
        String moduleId = str(body, "moduleId", null);
        if (moduleId != null && engine.module(moduleId) != null) existing = engine.module(moduleId).config();

        ConnectionConfig conn = connectionFromJson(body.get("connection"), existing);
        SqlValidator.Result r = engine.validateSql(str(body, "sql", ""), conn, type, divideBy > 0 ? divideBy : 1.0);

        JsonObject j = new JsonObject();
        j.addProperty("ok",      r.ok());
        j.addProperty("message", r.message());
        JsonArray warnings = new JsonArray();
        r.warnings().forEach(warnings::add);
        j.add("warnings", warnings);
        j.add("preview",  rowsJson(r.preview()));
        return j.toString();
    }

    /**
     * Builds a module from the request, starting from a copy of {@code base} (when editing)
     * so fields the browser does not edit, such as {@code comment}, are kept.
     */
    static QModuleConfig moduleFromJson(JsonObject j, QModuleConfig base) throws ConfigException {
        QModuleConfig m = (base != null) ? base.copy() : new QModuleConfig();

        m.setTitle(str(j, "title", "").strip());
        ChartType type = ChartType.fromString(str(j, "chartType", null));
        m.setChartType(type.name());

        ProcessingMode mode = ProcessingMode.fromString(str(j, "processingMode", null));
        // NONE is the default: leave it unset unless the module already set it explicitly
        boolean hadMode = base != null && base.getProcessingMode() != null;
        m.setProcessingMode(mode == ProcessingMode.NONE && !hadMode ? null : mode.name());

        m.setRefreshRate(integer(j, "refreshRate"));
        m.setWindowSize(type.isTimeSeries() ? integer(j, "windowSize") : null);
        m.setDivideBy(num(j, "divideBy", 1.0));
        m.setGrid(str(j, "grid", null));
        m.setSql(str(j, "sql", "").strip());
        m.setConnection(connectionFromJson(j.get("connection"), base));
        return m;
    }

    /** {@code null}/absent means "use the dashboard connection". A blank password keeps {@code base}'s. */
    private static ConnectionConfig connectionFromJson(JsonElement e, QModuleConfig base) {
        if (e == null || e.isJsonNull()) return null;
        JsonObject j = e.getAsJsonObject();
        ConnectionConfig c = new ConnectionConfig();
        c.setUsername(str(j, "username", "").strip());
        c.setConnectString(str(j, "connectString", "").strip());
        String wallet = str(j, "credentialsFile", "").strip();
        c.setCredentialsFile(wallet.isEmpty() ? null : wallet);

        String password = str(j, "password", "");
        if (password.isEmpty() && base != null && base.getConnection() != null) {
            password = base.getConnection().getPassword();
        }
        c.setPassword(password);
        return c;
    }

    private static String str(JsonObject j, String key, String fallback) {
        JsonElement e = j.get(key);
        return (e == null || e.isJsonNull()) ? fallback : e.getAsString();
    }

    private static double num(JsonObject j, String key, double fallback) {
        JsonElement e = j.get(key);
        return (e == null || e.isJsonNull()) ? fallback : e.getAsDouble();
    }

    private static Integer integer(JsonObject j, String key) {
        JsonElement e = j.get(key);
        return (e == null || e.isJsonNull()) ? null : e.getAsInt();
    }
}
