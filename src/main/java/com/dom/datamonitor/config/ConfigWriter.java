package com.dom.datamonitor.config;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.Mark;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;
import org.yaml.snakeyaml.nodes.Tag;
import org.yaml.snakeyaml.representer.Representer;

import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes an {@link AppConfig} back to its YAML file after the user edits the dashboard.
 *
 * <ul>
 *   <li>Before the first save, the original file is copied to {@code <name>.bak}.
 *       SnakeYAML cannot preserve comments, so the backup keeps the hand-written original.</li>
 *   <li>Keys are written in a fixed, readable order; unset optional values are omitted.</li>
 *   <li>Multi-line SQL is written as a literal block ({@code sql: |}).</li>
 *   <li>The file is written to a temp file and moved into place, so a failed save never
 *       leaves a half-written config.</li>
 * </ul>
 */
public class ConfigWriter {

    private final Path    configPath;
    private       boolean backedUp;

    public ConfigWriter(File configFile) {
        this.configPath = configFile.toPath().toAbsolutePath();
    }

    /**
     * Encrypts, in the file itself, every {@code password:} value that is plain text or in the
     * old {@code enc:<base64>} form, so passwords are never left readable on disk. Only those
     * values change, to {@code enc(...)}; the rest of the file, comments included, is kept as
     * written. The values are found with SnakeYAML, so text that merely looks like
     * {@code password:} (inside SQL, say) is never touched.
     *
     * @return how many passwords were encrypted; 0 leaves the file untouched
     */
    public static int encryptPlainPasswords(File configFile) throws IOException {
        Path path = configFile.toPath().toAbsolutePath();
        String text = Files.readString(path, StandardCharsets.UTF_8);
        Node root = new Yaml().compose(new StringReader(text));

        List<ScalarNode> found = new ArrayList<>();
        collectPasswords(root, found);
        String[] lines = text.split("\n", -1);        // a \r stays at the end of its line
        int changed = 0;
        // Last first, so earlier positions on the same line stay valid
        for (int i = found.size() - 1; i >= 0; i--) {
            ScalarNode v = found.get(i);
            if (v.getValue().isEmpty() || PasswordCipher.isEncrypted(v.getValue())) continue;
            Mark from = v.getStartMark(), to = v.getEndMark();
            if (from.getLine() != to.getLine()) continue; // multi-line scalar: leave it alone
            String line = lines[from.getLine()];
            int a = line.offsetByCodePoints(0, from.getColumn());
            int b = line.offsetByCodePoints(0, to.getColumn());
            lines[from.getLine()] = line.substring(0, a) + PasswordCipher.protect(v.getValue()) + line.substring(b);
            changed++;
        }
        if (changed == 0) return 0;

        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        Files.writeString(tmp, String.join("\n", lines), StandardCharsets.UTF_8);
        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return changed;
    }

    /** Adds every scalar value whose key is {@code password}, in file order. */
    private static void collectPasswords(Node node, List<ScalarNode> found) {
        if (node instanceof MappingNode map) {
            for (NodeTuple t : map.getValue()) {
                if (t.getKeyNode() instanceof ScalarNode k && k.getValue().equals("password")
                        && t.getValueNode() instanceof ScalarNode v) {
                    found.add(v);
                } else {
                    collectPasswords(t.getValueNode(), found);
                }
            }
        } else if (node instanceof SequenceNode seq) {
            for (Node n : seq.getValue()) collectPasswords(n, found);
        }
    }

    /** Path of the backup created on the first {@link #save}. */
    public Path backupPath() {
        return configPath.resolveSibling(configPath.getFileName() + ".bak");
    }

    public void save(AppConfig config) throws IOException {
        if (!backedUp && Files.exists(configPath)) {
            Files.copy(configPath, backupPath(), StandardCopyOption.REPLACE_EXISTING);
            backedUp = true;
        }

        String header = "# Saved by Data Monitor on "
            + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) + "\n"
            + "# The file as it was before the first save (with its comments) is "
            + backupPath().getFileName() + "\n\n";

        Path tmp = configPath.resolveSibling(configPath.getFileName() + ".tmp");
        Files.writeString(tmp, header + toYaml(config), StandardCharsets.UTF_8);
        Files.move(tmp, configPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /** Renders {@code config} as YAML (without the header comment). */
    public static String toYaml(AppConfig config) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("settings", settingsMap(config.getSettings()));

        List<Map<String, Object>> modules = new ArrayList<>();
        for (QModuleConfig m : config.getQmodules()) modules.add(moduleMap(m));
        root.put("qmodules", modules);

        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setIndent(2);
        options.setIndicatorIndent(2);
        options.setIndentWithIndicator(true);
        options.setWidth(120);
        return new Yaml(new LiteralBlockRepresenter(options), options).dump(root);
    }

    // ─────────────────────────────────────────────────────────────────────────

    private static Map<String, Object> settingsMap(AppConfig.Settings s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("title",         s.getTitle());
        m.put("theme",         s.getTheme());
        m.put("refreshRate",   s.getRefreshRate());
        m.put("windowSize",    s.getWindowSize());
        m.put("border",        s.getBorder());
        m.put("columns",       s.getColumns());
        m.put("flashOnChange", s.isFlashOnChange());
        putIfSet(m, "connection", connectionMap(s.getConnection()));
        return m;
    }

    private static Map<String, Object> moduleMap(QModuleConfig q) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("title", q.getTitle());
        putIfSet(m, "comment",        q.getComment());
        m.put("grid",      q.getGrid());
        m.put("chartType", q.getChartType());
        putIfSet(m, "processingMode", q.getProcessingMode());
        putIfSet(m, "refreshRate",    q.getRefreshRate());
        putIfSet(m, "windowSize",     q.getWindowSize());
        if (q.getDivideBy() != 1.0) m.put("divideBy", wholeIfPossible(q.getDivideBy()));
        putIfSet(m, "connection",     connectionMap(q.getConnection()));
        m.put("sql", normaliseSql(q.getSql()));
        return m;
    }

    private static Map<String, Object> connectionMap(ConnectionConfig c) {
        if (c == null) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("username",      c.getUsername());
        m.put("password",      PasswordCipher.protect(c.getPassword()));
        m.put("connectString", c.getConnectString());
        putIfSet(m, "credentialsFile", c.getCredentialsFile());
        return m;
    }

    private static void putIfSet(Map<String, Object> m, String key, Object value) {
        if (value == null) return;
        if (value instanceof String s && s.isBlank()) return;
        m.put(key, value);
    }

    /** {@code 1048576.0} is written as {@code 1048576}; fractional values are kept. */
    private static Object wholeIfPossible(double d) {
        return (d == Math.rint(d) && Math.abs(d) < Long.MAX_VALUE) ? (Object) (long) d : (Object) d;
    }

    /**
     * Strips trailing whitespace from each line and ends with a newline. SnakeYAML falls back
     * to a quoted one-line string if any line has trailing spaces, so this keeps {@code sql: |}.
     */
    static String normaliseSql(String sql) {
        if (sql == null) return null;
        String stripped = sql.strip().lines().map(String::stripTrailing)
            .reduce((a, b) -> a + "\n" + b).orElse("");
        return stripped + "\n";
    }

    /** Emits strings that contain a newline in literal block style ({@code |}). */
    private static final class LiteralBlockRepresenter extends Representer {
        LiteralBlockRepresenter(DumperOptions options) {
            super(options);
        }

        @Override
        protected Node representScalar(Tag tag, String value, DumperOptions.ScalarStyle style) {
            if (Tag.STR.equals(tag) && value.contains("\n")) {
                style = DumperOptions.ScalarStyle.LITERAL;
            }
            return super.representScalar(tag, value, style);
        }
    }
}
