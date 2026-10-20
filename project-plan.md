# New Data Monitor — Project Reference

## Overview

A Java 17+ desktop application that renders a live, configurable dashboard of Oracle Database metrics. SQL queries are executed on a schedule and results are displayed as time-series charts, bar charts, pie charts, text KPI tiles, or tables. The dashboard is fully driven by a single YAML configuration file — no code changes are needed to add or reconfigure panels.

---

## Goals

- Replace the older MonitorDB application (held in `Old Version of the Application/`) with a modern, maintainable rewrite
- Config-file-driven: all queries, layout, and appearance defined in `datamonitor.yaml`
- Dark and light themes with runtime toggle
- Oracle 26ai JDBC Thin driver (ojdbc17)
- All chart rendering via `java.awt.Graphics2D` — no third-party chart libraries
- Build with Apache Ant + Apache Ivy (no Maven/Gradle)

---

## Build System

### Prerequisites

| Requirement | Version |
|---|---|
| JDK | 17 or later |
| Apache Ant | 1.10+ |
| Internet access | Required on first build (Ivy downloads JARs) |

### Files

| File | Purpose |
|---|---|
| `build.xml` | Ant build script |
| `ivy.xml` | Dependency declarations |
| `ivy-settings.xml` | Maven Central resolver configuration |

### Common Ant targets

```
ant resolve     # Download dependencies via Ivy (auto-run by compile)
ant compile     # Compile all sources to build/classes/
ant compile.test  # Compile src/test/java to build/test-classes/
ant test        # Run all JUnit 5 tests (reports in build/test-reports/); fails the build on any failure
ant jar         # Package build/dist/datamonitor/lib/new-data-monitor.jar
ant dist        # clean + jar, then copy deps, launchers, sample YAML and README into build/dist/datamonitor/
ant zip         # dist + dated archive datamonitorDDMMYYYY.zip in the project root
ant run         # Compile and launch from the build classpath (-Dconfig=<file> to pick a config)
ant run-web     # Same, but starts the web front end (--web); prints the access link
                #   extra flags for either: -Dargs="--port 9090 --theme light"
ant docs        # Generate Javadoc in docs/
ant clean       # Delete build/ and docs/
ant all         # clean → compile → jar
```

### Distribution layout (`ant dist`)

```
build/dist/datamonitor/
├── bin/
│   ├── datamonitor          Unix/macOS launcher (passes -c bin/datamonitor.yaml)
│   ├── datamonitor.bat      Windows launcher
│   └── datamonitor.yaml     Sample config
├── lib/                     new-data-monitor.jar + all dependency JARs
├── log/
└── README.md
```

The launchers use `-cp lib/*`. The JAR manifest also carries a `Class-Path` listing every runtime JAR by file name (generated from `lib/runtime/` at build time), so `java -jar lib/new-data-monitor.jar` works too, as long as the dependency JARs sit beside it.

### Running

```bash
ant run                                  # uses ./datamonitor.yaml
ant run -Dconfig=/path/to/other.yaml
ant run-web                              # web front end on http://127.0.0.1:8080
ant run-web -Dargs="--port 9090"
build/dist/datamonitor/bin/datamonitor   # after ant dist
build/dist/datamonitor/bin/datamonitor -c /path/to/other.yaml
java -jar build/dist/datamonitor/lib/new-data-monitor.jar -c datamonitor.yaml
```

CLI flags: `-c|--config <file>`, `--theme dark|light`, `-u|--username <user>`, `-p|--password <password>`, `--connect-string <connect>`, `--web`, `--port <n>`, `--bind <address>`, `--version`, `-h|--help`

`-u`, `-p` and `--connect-string` each override one field of `settings.connection` for the current run. They are stored separately (`Settings.overrideConnection`), and runtime code uses `Settings.getEffectiveConnection()`, which is the file's connection with the overrides on top. `ConfigWriter` saves `getConnection()` only, so command-line values never reach the YAML. `ConfigValidator` checks the effective connection, so a config without a `connection` section is valid when all three flags are given. Per-module connections are not overridden.

### Versioning

The version is defined **once**, as `project.version` in `build.xml`. The `compile` target writes it to `com/dom/datamonitor/version.properties` in the classes directory, and `DataMonitor` reads it at startup (falling back to `dev` if the file is missing). It also goes into the JAR manifest as `Implementation-Version`. `ivy.xml` deliberately has no `revision`.

### Tests

- Sources in `src/test/java` (mirroring the main packages), fixtures in `src/test/resources`.
- JUnit 5 via `org.junit.platform:junit-platform-console-standalone` (Ivy `test` conf). `ant test` runs the console launcher headless, with the project root as the working directory.
- No database is needed: `QueryResultParser` is tested against a dynamic-proxy `FakeResultSet`, and processing-mode arithmetic is tested through the package-private `SQLExecutor.applyProcessingMode`.
- `ConfigLoaderTest` loads and validates the shipped `datamonitor.yaml`, so a broken sample config fails the build.

| Test class | Covers |
|---|---|
| `model.GridLocationTest` | Grid spec parsing, spans, invalid specs |
| `model.EnumParsingTest` | `ChartType` / `ProcessingMode` parsing |
| `charts.ColorPaletteTest` | Named, hex, `rgb()`, AWT field colours; fallback; default palette |
| `db.QueryResultParserTest` | Duplet, both triplet orders, multi-series rows, `divideBy`, null/non-numeric values |
| `db.SQLExecutorProcessingTest` | All five processing modes, first-run zero, clamping, per-key tracking |
| `config.ConfigLoaderTest` | Defaults, sample config, missing/empty/invalid files, duplicate keys |
| `config.ConfigValidatorTest` | All-errors-in-one-pass, connections, overlaps, column bounds |

### Version control

Git repository, branch `main`. `.gitignore` excludes `Old Version of the Application/` (reference only, never committed), `build/`, `docs/`, `lib/`, `ivy/`, `.idea/`, `datamonitor*.zip` and local settings.

---

## Dependencies (`ivy.xml`)

| Artifact | Version | Scope | Purpose |
|---|---|---|---|
| `com.oracle.database.jdbc:ojdbc17` | 23.26.0.0.0 | compile | Oracle JDBC Thin driver (JDK 17 build) |
| `com.oracle.database.jdbc:ucp17` | 23.26.0.0.0 | compile | Oracle Universal Connection Pool |
| `com.oracle.database.security:oraclepki` | 23.26.0.0.0 | runtime | Wallet / mTLS for Autonomous DB connections |
| `org.yaml:snakeyaml` | 2.3 | compile | YAML config parsing (load and save) |
| `com.fifesoft:rsyntaxtextarea` | 3.5.4 | compile | Syntax-highlighted SQL editor in the QModule dialog (BSD licence) |
| `com.google.code.gson:gson` | 2.11.0 | compile | JSON for the web REST API |
| `org.webjars.npm:echarts` | 5.5.1 | compile | Apache ECharts for the web charts (served from the jar, works offline) |
| `org.webjars.npm:codemirror` | 5.65.16 | compile | CodeMirror 5 SQL editor in the web dialog |
| `org.junit.platform:junit-platform-console-standalone` | 1.11.4 | test | JUnit 5 (Jupiter API + engine + console launcher) |

> **Note:** `osdt_core` and `osdt_cert` are **not** separate dependencies — they are bundled inside `ojdbc17` from Oracle 23ai onwards. Do not add them; they do not exist at 23.x on Maven Central.

---

## Source Layout

```
src/main/java/com/dom/datamonitor/
├── DataMonitor.java                    Entry point; CLI parsing; bootstrap
│
├── config/
│   ├── AppConfig.java                  Root YAML bean (settings + qmodules list)
│   ├── ConnectionConfig.java           username / password / connectString / credentialsFile
│   ├── QModuleConfig.java              Per-module config bean
│   ├── ConfigLoader.java               SnakeYAML loader (JavaBeans constructor)
│   ├── ConfigWriter.java               Saves edits back to YAML; .bak of original on first save; atomic write
│   ├── ConfigValidator.java            Full validation; collects all errors before throwing
│   └── ConfigException.java           Checked exception for config errors
│
├── model/
│   ├── ChartType.java                  Enum: LINE, AREA, BAR, PIE, TEXT, TABLE
│   ├── ProcessingMode.java             Enum: NONE, DIFFERENCE, CHANGE_PER_MIN, CHANGE_PER_HOUR, CUMULATIVE_SINCE_START
│   ├── GridLocation.java               Record: parses "R2,C1-3" / "R1-2,C3" grid strings; toSpec() formats them
│   ├── GridOccupancy.java              Which cells are taken and by whom; shared by validator and location picker
│   ├── QueryRow.java                   Record: (key, value, color)
│   └── QueryResult.java                Immutable result; factory methods success/error
│
├── engine/
│   └── MonitorEngine.java              UI-independent runtime for the web mode: executors, rolling history, add/update/delete + save
│
├── web/
│   ├── WebServer.java                  JDK HttpServer: static files, /vendor WebJars, token auth, same-site check, routing
│   ├── ApiHandler.java                 REST API (JSON via Gson); never sends passwords
│   └── EventStream.java                Server-Sent Events: "dashboard" snapshots + "result" updates, per-client queues
│
├── db/
│   ├── ConnectionManager.java          Singleton UCP pool manager (one pool per user/connect string/password); readable ORA- errors
│   ├── QueryResultParser.java          JDBC ResultSet → QueryResult; auto-detects duplet/triplet format
│   ├── SQLExecutor.java                ScheduledExecutorService; processing modes; rate normalisation; divideBy
│   └── SqlValidator.java               Editor "Validate SQL": read-only guard, column-layout check, one-off run + preview
│
├── charts/
│   ├── Theme.java                      Immutable colour+font palette; Theme.DARK / Theme.LIGHT
│   ├── ColorPalette.java               Named colour map + hex + rgb() resolution; default rotating palette
│   ├── BaseChart.java                  Abstract JPanel; double-buffered off-screen render; error/connecting state
│   ├── LineChart.java                  Rolling time-series line chart with glow pass
│   ├── AreaChart.java                  Stacked area chart with per-band gradient fill
│   ├── BarChart.java                   Horizontal bar chart with RoundRectangle2D gradient bars
│   ├── PieChart.java                   Donut-style pie chart with RadialGradientPaint
│   ├── TextDisplay.java                KPI tile: small label + large focal-point number; optional flash
│   └── TableDisplay.java               Custom-painted table with alternating rows and colour dots
│
└── ui/
    ├── DashboardFrame.java             JFrame; scroll pane; theme toggle; add/edit/delete actions + auto-save
    ├── DashboardPanel.java             GridBagLayout container; add/replace/remove modules at runtime; right-click menus
    ├── HeaderBar.java                  Title / connection / status / add (+) and theme-toggle buttons
    ├── QModulePanel.java               Per-module container: TitleStrip (inner class) + BaseChart
    ├── QModuleDialog.java              Add/Edit dialog: settings form, location picker, SQL editor, validation
    └── GridPicker.java                 Miniature grid: grey = taken, green = free, solid green = selected

(util/ exists but is currently empty.)

src/main/resources/web/                 Web front end (no build step; plain ES modules)
├── index.html                          Shell, menus, dialogs
├── app.css                             Both themes as CSS variables; operations-console look
├── app.js                              Bootstrap, SSE, grid layout, context menu, delete confirm, theme toggle
├── charts.js                           Card class: ECharts LINE/AREA/BAR/PIE, DOM TEXT/TABLE, table view
├── editor.js                           Add/Edit dialog: form, location picker, CodeMirror, validation
├── api.js                              fetch wrappers
└── favicon.svg
```

---

## Configuration File (`datamonitor.yaml`)

### Full settings reference

```yaml
settings:
  title: "Oracle DB Monitor"     # Window title and header label         (default: "Data Monitor")
  theme: dark                    # dark | light                          (default: dark)
  refreshRate: 2                 # Global default poll interval, seconds (default: 5)
  windowSize: 120                # X-axis window for LINE/AREA, seconds  (default: 120)
  border: 6                      # Gap in pixels between QModule panels  (default: 8)
  columns: 3                     # Dashboard columns: 1, 2, or 3         (default: 3)
  flashOnChange: true            # Flash glow ring on TEXT tiles on change (default: true)
  connection:
    username: system
    password: welcome1           # Plain text, or enc:<base64> for encoded passwords
    connectString: //localhost/soe   # EZConnect: //host:port/service, or TNS alias
    # credentialsFile: /path/to/wallet   # For Oracle Autonomous Database (mTLS wallet)
```

### QModule reference

```yaml
qmodules:
  - title: "My Chart"            # Displayed in the title strip
    # comment: "Optional note"   # Not rendered; for config documentation only
    grid: "R1,C1"                # Grid position (see Grid Spec below)
    chartType: LINE              # LINE | AREA | BAR | PIE | TEXT | TABLE
    processingMode: DIFFERENCE   # NONE | DIFFERENCE | CHANGE_PER_MIN | CHANGE_PER_HOUR | CUMULATIVE_SINCE_START (default: NONE)
    refreshRate: 2               # Overrides global default (seconds)
    windowSize: 120              # Overrides global default for LINE/AREA (seconds)
    divideBy: 1048576            # Divide raw SQL values by this factor (e.g. bytes → MB; default: 1.0)
    sql: |
      SELECT 'label', value FROM ...
    # connection:                # Per-module connection override (same fields as settings.connection)
    #   username: other_user
    #   password: other_pass
    #   connectString: //other-host/service
```

### Grid spec format

| Syntax | Meaning |
|---|---|
| `R1,C1` | Row 1, column 1 |
| `R2,C1-2` | Row 2, spanning columns 1 and 2 |
| `R3,C1*` | Row 3, spanning all configured columns |
| `R4-5,C2` | Rows 4 and 5, column 2 (a tall module) |
| `R6-7,C1-2` | A 2 × 2 block; the row range combines with every column form |

Rows and columns are 1-indexed. Rows scroll vertically; columns do not scroll. A single row number
(`R1`) means one row, and `toSpec()` only writes a row range when a module spans more than one row,
so files without multi-row modules save exactly as before.

### SQL result formats

The parser auto-detects the column count and types:

| Format | Columns | Example |
|---|---|---|
| Duplet | 2 | `SELECT 'label', value FROM ...` |
| Triplet | 3 | `SELECT 'label', 'colour', value FROM ...` |
| Multi-series (single row) | 3N | `SELECT 'k1','c1',v1, 'k2','c2',v2 FROM ...` |
| Multi-series (UNION ALL) | 3 | `SELECT 'k1','c1',v1 ... UNION ALL SELECT 'k2','c2',v2 ...` |

### Processing modes

| Mode | Behaviour |
|---|---|
| `NONE` | Raw SQL value is passed directly to the chart |
| `DIFFERENCE` | `(current − previous) / refreshRateSeconds` — per-second rate |
| `CHANGE_PER_MIN` | `(current − previous) × 60 / refreshRateSeconds` — per-minute rate |
| `CHANGE_PER_HOUR` | `(current − previous) × 3600 / refreshRateSeconds` — per-hour rate |
| `CUMULATIVE_SINCE_START` | `current − initial`, where `initial` is the first raw value seen for that series key. Useful for slow counters where a rate would be 0 most of the time. |

All three rate modes emit 0 on the first execution to avoid a startup spike, and clamp negative deltas to 0 (counter wrap or instance restart). Processing is tracked per series key.

### Supported colour names (for SQL triplets)

`red`, `dark red`, `green`, `light green`, `blue`, `light blue`, `cyan`, `orange`, `yellow`, `pink`, `purple`, `violet`, `white`, `black`, `gray`/`grey`, `light gray`, `dark gray`, `brown`, `teal`

Also accepts: HTML hex codes (`#FF5733`), RGB notation (`rgb(255,87,51)`), or `java.awt.Color` field names (`MAGENTA`).

---

## Architecture Decisions

### Rendering

- All charts extend `BaseChart` (`JPanel`). `paintComponent` renders to an off-screen `BufferedImage` (double-buffering) then blits it — prevents flicker on EDT repaints.
- `applyRenderingHints` is `public static` so it can be called from `QModulePanel.TitleStrip` (an inner class, not a `BaseChart` subclass).
- Anti-aliasing and fractional text metrics are always enabled.

### Threading

- Each `SQLExecutor` owns a `ScheduledExecutorService` with a single daemon thread.
- Results are dispatched via `SwingUtilities.invokeLater` before updating chart state — all chart mutations happen on the EDT.
- `ScheduledExecutorService.scheduleAtFixedRate` is used (not `scheduleWithFixedDelay`) so polling stays on a regular wall-clock cadence even if a query runs slow.

### Connection pooling

- `ConnectionManager` is a singleton holding a `ConcurrentHashMap<String, PoolDataSource>` keyed by `username@connectString#sha256(password, wallet)`, so changed credentials get a fresh pool.
- Connection failures are re-thrown with the underlying `ORA-` error and a short hint instead of UCP's generic `UCP-0` wrapper (`ConnectionManager.describe`).
- Pools are created lazily on first use.
- On shutdown, pools are destroyed via `UniversalConnectionPoolManagerImpl.getUniversalConnectionPoolManager().destroyConnectionPool(name)` — **not** via reflection. The deprecated `setConnectionWaitTimeout(int)` API is replaced with `setConnectionWaitDuration(Duration)`.
- Passwords can be plain-text or Base64-encoded with an `enc:` prefix.

### Theme system

- `Theme` is an immutable value object holding all colours and fonts.
- Two instances: `Theme.DARK` (near-black / bright green) and `Theme.LIGHT` (off-white / dark slate).
- Theme changes propagate: `DashboardFrame → DashboardPanel → QModulePanel → (TitleStrip + BaseChart)`. Each layer calls `setTheme(Theme)` on its children.
- The theme-toggle button uses `BasicButtonUI` to bypass macOS Aqua native painting, ensuring `setBackground()` and `setBorder()` take effect.

### Editing QModules at runtime

- **Entry points:** right-click a module (Edit… / Delete… / Add QModule…), right-click empty dashboard space, or the **+** button in the header. Menus are set with `setComponentPopupMenu`; the title strip and chart use `setInheritsPopupMenu(true)` so a right-click anywhere on a module works.
- **Delete** asks for confirmation with Cancel as the default button.
- **Add / Edit** open `QModuleDialog`. It works on `QModuleConfig.copy()`, so Cancel changes nothing. Fields that are left at the dashboard default are saved as unset, which keeps the YAML minimal.
- **Location:** `GridPicker` is built from `DashboardPanel.occupancyExcluding(module)`, so the module being edited can stay where it is. An extra empty row is always offered below the last used row and the selection. Dragging selects a rectangle: `GridOccupancy.largestFreeRectangle` picks the largest free rectangle from the anchor towards the pointer, so the selection stops at taken cells (the web picker uses the same rule). If the location is unchanged, the original grid text (e.g. `R2,C1*`) is kept.
- **Multi-row layout:** `GridOccupancy` records each placement so pickers draw a module as one block even when two modules share a title. In Swing, `DashboardPanel.applyRowSizes` gives every covered row the same minimum height and weight on the `GridBagLayout`; left to itself, GridBagLayout sizes rows from the single-row components in them, so a row covered only by multi-row modules collapsed or came out uneven. Rows that no module covers stay collapsed, as before. The web dashboard uses CSS grid row spans. TEXT values grow with the module: the web tile sizes its value from the height of its row (then shrinks it to fit the width), and Swing's `TextDisplay` now uses the same layout as the web tile: label (hidden when it repeats the title), a value sized from the height left for it and shrunk to fit the width, and a footer with a sparkline over the chart window and the change since the previous reading (▲/▼ plus colour). Swing title strips shrink (to 9pt) and then truncate with an ellipsis when a module is too narrow for its title. Swing number formatting follows the web: `BaseChart.formatValue` (values) and `formatCompact` (axis labels) use the same rules as `charts.js`, and `niceTicks` gives line and area charts round axis steps (1, 2, 2.5 or 5 × 10ⁿ). Hovering a Swing line or area chart shows a guide line, a dot per series and a readout with the time, each value and (stacked) the total, like the web tooltip. Their legend gets its own band above the plot (wrapping onto more rows when narrow) instead of being drawn over the data, and single-series charts have no legend, as on the web.
- **SQL validation:** OK stays disabled until the current SQL and connection have validated. Any edit to either disables it again; changing the chart type only recomputes warnings. When editing, the saved SQL counts as validated until it is changed. `SqlValidator` refuses anything that is not `SELECT`/`WITH` (so Validate can never modify data), rejects a trailing `;`, runs with a 15 s timeout and a 20-row limit, rolls back afterwards, and checks the column layout matches what `QueryResultParser` supports. Cmd/Ctrl+Enter validates from the editor.
- **Runtime changes:** `DashboardPanel.addModule / replaceModule / removeModule` keep the panel list and `config.getQmodules()` in step, re-run the GridBag layout, and start/stop executors. Untouched modules keep their panels and chart history. `replaceModule` builds the new panel before stopping the old one, so a failure changes nothing.
- **Saving:** after every change `DashboardFrame` calls `ConfigWriter.save()`. The first save in a session copies the original to `<config>.bak`, because SnakeYAML cannot keep comments. Writes go through a temp file and an atomic move. If saving fails, the live change stays and the user is told it will be lost on exit.
- **Command-line overrides never leak into the saved file:** `--theme` is passed straight to `DashboardFrame`, and the connection flags live in `Settings.connectionOverride`, which `ConfigWriter` does not write.
- **Empty dashboards** are valid. `ConfigValidator` no longer requires at least one module, because modules can be added from the UI.

### Web front end (`--web`)

- **Runtime:** `MonitorEngine` owns one `SQLExecutor` per module (ids `m1`, `m2`… for the life of the process), keeps a rolling history (window/refresh + 1 samples for LINE/AREA/TEXT, the latest for others), and applies add/update/delete. Each change is validated against the whole dashboard with `ConfigValidator`, checked as SELECT/WITH-only, applied, then saved with `ConfigWriter`. Late results from a replaced executor are dropped. The Swing UI still uses its own panels; moving it onto the engine is an open item.
- **Server:** the JDK's `com.sun.net.httpserver`, so no web framework. It serves `/` (the app), `/app/*` (classpath `web/`), `/vendor/<lib>/*` (WebJar files; the version is read from the WebJar's `pom.properties`, so HTML never hard-codes it), `/api/*` and `/api/events`.
- **API:** `GET /api/dashboard`, `POST /api/modules`, `PUT|DELETE /api/modules/{id}`, `POST /api/validate`. Errors are `{"error": …}` with status 400/404/500. Module connections are returned with `hasPassword` and never the password. A blank password on save or validate keeps the module's existing one.
- **Live updates:** Server-Sent Events. A `dashboard` snapshot is sent on connect and after every change, so all browsers stay in sync, and `result` events are sent per module. Each client has a bounded queue drained by its own thread, so a slow browser never blocks the executors. Heartbeats every 15 s.
- **Access control:** a random token per run, printed in the startup link. `/?token=…` sets an `HttpOnly; SameSite=Strict` cookie and redirects. `/api` accepts that cookie or `Authorization: Bearer`, compared in constant time. Non-GET requests with a foreign `Origin` get 403. The default bind is `127.0.0.1`, and binding elsewhere prints a warning. HTML is sent with a strict CSP (`default-src 'self'`), and every DB/config string is inserted as text (chart tooltips escape it).
- **Charts:** ECharts for LINE (2px lines, 10% wash for a single series, end labels for 1–2 series), AREA (stacked, each band edged in a 40%-darker shade of its own colour, no edge for all-zero series, total in the tooltip), BAR (horizontal, ≤24px, rounded ends, value at the tip, one colour unless the SQL names colours) and PIE (donut, touching slices edged in a darker shade of their own colour, no edge on zero slices, total in the centre). TEXT tiles are DOM: a hero value, a signed ▲/▼ delta, a sparkline and the optional flash. TABLE is DOM. Every card has a table view.
- **Colours:** SQL colour names are resolved server-side by the same `ColorPalette` the Swing charts use. Unnamed series use an 8-slot categorical palette in fixed order that was validated for colour-vision deficiency against both card surfaces. The Swing default palette failed that check, so the web version doesn't reuse it. A series keeps its slot for the card's lifetime.
- **Browser theme:** the initial theme comes from the config (or `--theme`). The toggle is remembered per browser in `localStorage` and is never saved to the YAML.

### Area chart colour fidelity

- The `LinearGradientPaint` for each series band is scoped to that band's actual vertical extent (computed by scanning all time points), not the full plot height. This ensures every series gets the full alpha range regardless of its stack position.
- Fill alpha values are `(220, 150)` top-to-bottom. Lower values cause colours to blend into the dark background and appear desaturated (e.g. yellow → brown).

---

## Known Issues Fixed

| Issue | Root Cause | Fix Applied |
|---|---|---|
| `osdt_core`/`osdt_cert` not found | Only exist up to 21.x on Maven Central; bundled in ojdbc17 from 23ai | Removed from ivy.xml |
| `Timer` symbol not found in `TextDisplay` | `java.awt.*` does not cover `javax.swing.Timer` | Added explicit import |
| `applyRenderingHints` protected access error | Called from inner class `TitleStrip` (not a `BaseChart` subclass) | Changed to `public static` |
| `setConnectionWaitTimeout` deprecated | Replaced by duration-based API in UCP 23.x | Replaced with `setConnectionWaitDuration(Duration.ofSeconds(15))` |
| `destroyConnectionPool` NoSuchMethodException on close | Reflection was calling the method on the wrong object (`PoolDataSourceImpl` instead of the pool manager) | Use `UniversalConnectionPoolManagerImpl.getUniversalConnectionPoolManager().destroyConnectionPool(name)` |
| Theme toggle changes background but charts stay black | `DashboardPanel.setTheme()` did not propagate to child modules | Added full propagation chain through `QModulePanel` and `TitleStrip` |
| DIFFERENCE values doubled at `refreshRate: 2` | Delta was not normalised; raw `(current − prev)` was emitted | Divide by `refreshRateSeconds` to produce a per-second rate |
| X-axis label wrong at `refreshRate > 1` | `maxPoints` was used as seconds; `maxPoints = windowSize / refreshRate` | Store and use `windowSeconds` directly |
| Area chart colours appear brown/desaturated | Gradient spanned full plot height; lower-stack series got very low alpha | Per-band gradient + raised alpha to `(220, 150)` |
| Theme-toggle button renders white on dark background (macOS) | Aqua L&F overrides `setBackground()` on `JButton` | Set `BasicButtonUI`; `setOpaque(true)`; `LineBorder(accent, 1)` |
| Wrong password reported as `UCP-0: Unable to start the Universal Connection Pool` | UCP wraps every startup failure; the real `ORA-01017` was two causes down | `ConnectionManager.getConnection` re-throws with the first `ORA-` error in the chain (minus the `CONNECTION_ID`) plus a hint, e.g. "check the username and password". This applies to the startup dialog, module errors and the editor's Validate |
| Correcting a password kept using the failed pool | Pools were keyed by `username@connectString` only | Key also includes a SHA-256 of password + wallet, so new credentials get a new pool; the key never contains the password |
| Malformed `enc:` password gave an obscure pool-creation error | Base64 decoding only happened when the pool was created | `ConfigValidator` rejects `enc:` values that aren't valid Base64; the pool-creation error now includes the cause |
| Grid `C4*` on a 3-column dashboard passed validation | For span-all specs the end column is clamped to `columns`, so it fell below the start and the bounds check never fired | `ConfigValidator` checks `max(colStart, effectiveEnd)` against `columns` (found by `ConfigValidatorTest`) |

---

## Open Issues (as of 2026-10-20)

| Item | Detail |
|---|---|
| Swing UI not on `MonitorEngine` | Desktop and web share config, validation and saving, but the Swing panels still own their executors. Moving Swing onto the engine would remove the duplicated add/edit/delete logic. |
| Web server is HTTP only | No TLS; use the default localhost bind, an SSH tunnel or an HTTPS reverse proxy for remote access. |
| Web token changes every run | Bookmarks stop working after a restart; a `--token` option or a stored token could be added. |
| No browser tests in the build | The web UI was verified by driving headless Chrome by hand; `WebServerTest` covers the HTTP API only. |
| Limited UI tests | `DashboardPanelTest` covers runtime add/replace/remove, but the dialogs and chart rendering are only checked by hand. |
| Saving drops YAML comments | SnakeYAML cannot round-trip comments; the original is kept as `<config>.bak` on the first save of each session. |
| No DB integration tests | `ConnectionManager` and `SQLExecutor.execute()` are only exercised against a real database. |
| `ant run` ignores a non-zero exit | The `run` target has no `failonerror`, so a config error still reports BUILD SUCCESSFUL. |
| `loadFromResource` differs from `load` | Only `load(File)` rejects duplicate YAML keys. |
| Empty `util/` package | Present in the source tree with no classes. |

---

## Extending the Application

### Adding a new chart type

1. Add a constant to `ChartType.java` and update `fromString()`
2. Create a class extending `BaseChart`; implement `onNewData(QueryResult)` and `paintChart(Graphics2D, int, int)`
3. Add a `case` to the `switch` in `QModulePanel.createChart()`

### Adding a new config option

1. Add a field + getter/setter to `AppConfig.Settings` (or `QModuleConfig` for per-module options)
2. Add validation in `ConfigValidator` if needed
3. Document in `datamonitor.yaml`

### Autonomous Database (ADB) connections

Set `credentialsFile` to the path of your wallet ZIP or directory. The application sets `oracle.net.wallet_location` and `oracle.net.tns_admin` automatically. Only `oraclepki` is needed as an additional runtime dependency — it is already declared in `ivy.xml`.
