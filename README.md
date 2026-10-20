# Data Monitor

A lightweight Oracle Database dashboard that displays live metrics as configurable panels.
Each panel (QModule) runs a SQL query on a configurable schedule and renders the result
as one of six chart types arranged on a grid.

## Requirements

- Java 17 or later
- Oracle Database (any edition; tested against 19c and 23ai)
- Network access to the database from the machine running the dashboard
- For Autonomous Database (ADB): a wallet credentials file

## Installation

Unzip the distribution archive and edit the configuration file:

```
datamonitor20102026.zip
└── DataMonitor/
    ├── bin/
    │   ├── datamonitor          ← desktop dashboard (macOS / Linux)
    │   ├── datamonitorweb       ← web dashboard (macOS / Linux)
    │   └── datamonitor.yaml     ← configuration (edit this)
    ├── winbin/
    │   ├── datamonitor.bat      ← desktop dashboard (Windows)
    │   └── datamonitorweb.bat   ← web dashboard (Windows)
    ├── lib/
    │   ├── datamonitor.jar      ← the application and all its libraries
    │   └── launcher/            ← used by the launcher (do not modify)
    └── launcher/                ← Apache Commons Launcher: launcher.xml holds the JVM settings
```

On macOS / Linux the commands should already be executable; if not:

```sh
chmod +x bin/datamonitor bin/datamonitorweb
```

## Configuration

All configuration lives in `bin/datamonitor.yaml`.

### Connection

```yaml
settings:
  connection:
    username: system
    password: welcome1               # plain text, or enc:<base64> for encrypted
    connectString: //localhost/XEPDB1  # TNS alias or //host:port/service
    # credentialsFile: /path/to/wallet  # ADB mTLS wallet directory
```

### Dashboard settings

| Key            | Default | Description                                          |
|----------------|---------|------------------------------------------------------|
| `title`        | `Data Monitor` | Window title bar text                         |
| `theme`        | `dark`  | `dark` or `light`                                    |
| `refreshRate`  | `5`     | Global query interval in seconds (overridable per QModule) |
| `windowSize`   | `120`   | Seconds of history shown on LINE and AREA charts     |
| `columns`      | `3`     | Dashboard grid columns (1–3)                         |
| `border`       | `8`     | Pixel gap between QModule panels                     |
| `flashOnChange`| `true`  | Flash TEXT tiles when their value changes            |

### QModules

Each entry under `qmodules:` defines one panel:

```yaml
qmodules:
  - title: "Active Sessions"
    grid: "R1,C2"          # position on the grid
    chartType: TEXT        # TEXT | LINE | AREA | BAR | PIE | TABLE
    processingMode: NONE   # NONE | DIFFERENCE | CHANGE_PER_MIN | CHANGE_PER_HOUR | CUMULATIVE_SINCE_START
    refreshRate: 2         # seconds (overrides global)
    windowSize: 120        # seconds of history (LINE/AREA only)
    divideBy: 1048576      # optional divisor applied to all values
    sql: |
      SELECT 'Active Sessions', count(*)
      FROM v$session
      WHERE status = 'ACTIVE' AND type = 'USER'
```

#### Grid spec

| Format       | Example     | Meaning                              |
|--------------|-------------|--------------------------------------|
| `R<r>,C<c>`  | `R1,C2`     | Row 1, column 2                      |
| `R<r>,C<a-b>`| `R2,C1-2`   | Row 2, spanning columns 1 and 2      |
| `R<r>,C<c>*` | `R1,C1*`    | Row 1, spanning all columns          |
| `R<a-b>,C…`  | `R3-4,C1-2` | Rows 3–4 and columns 1–2 (a 2 × 2 block); the row range works with every column form |

A module that spans rows is as tall as those rows plus the gaps between them. Leaving the row
part as a single number (`R1`) keeps a module one row tall, so existing files are unchanged.

#### Chart types

| Type    | Best for                                      |
|---------|-----------------------------------------------|
| `TEXT`  | Single KPI value displayed large              |
| `LINE`  | One or more time-series lines                 |
| `AREA`  | Stacked or overlapping filled time-series     |
| `BAR`   | Comparative bar chart, refreshed each cycle   |
| `PIE`   | Proportional distribution                     |
| `TABLE` | Tabular data (label / value rows)             |

#### Processing modes

| Mode                     | Description                                                                        |
|--------------------------|------------------------------------------------------------------------------------|
| `NONE`                   | Display the raw value returned by the query                                        |
| `DIFFERENCE`             | Per-second rate of change between successive results                               |
| `CHANGE_PER_MIN`         | Per-minute rate of change between successive results                               |
| `CHANGE_PER_HOUR`        | Per-hour rate of change between successive results                                 |
| `CUMULATIVE_SINCE_START` | Total accumulated change from the value first observed when the dashboard launched |

All rate-based modes (`DIFFERENCE`, `CHANGE_PER_MIN`, `CHANGE_PER_HOUR`) emit 0 on the
first execution to avoid spurious spikes when the dashboard starts. Negative deltas are
clamped to 0 (counter resets are ignored).

`CUMULATIVE_SINCE_START` is useful for slow-moving counters where a rate would show 0
most of the time. The baseline is the raw value at startup; the display shows how much
the counter has grown since then.

#### SQL result format

Each row returned by the SQL query represents one series or data point.

**Duplet** – two columns:
```sql
SELECT 'label', value FROM ...
```

**Triplet** – three columns (colour is the middle column):
```sql
SELECT 'label', 'colour', value FROM ...
```

Use `UNION ALL` to return multiple series from a single query:
```sql
SELECT 'Reads',  'green', sum(phyrds)  FROM v$filestat UNION ALL
SELECT 'Writes', 'red',   sum(phywrts) FROM v$filestat
```

Named colours: `red`, `blue`, `green`, `orange`, `yellow`, `cyan`, `pink`,
`light green`, `light gray`, `dark red`, `gray brown`, etc.

### Editing the dashboard while it runs

You can add, edit and delete QModules without touching the YAML file:

- **Right-click a module** and choose **Edit…** or **Delete…**. Delete asks you to confirm first.
- **Add a module** with the **+** button in the header, or right-click an empty part of the dashboard and choose **Add QModule…**.

The Add/Edit dialog contains:

- **Module:** title, chart type, processing mode, refresh rate, window size and divide-by. Tick **Default** to use the dashboard-wide value. You can also give a module its own database connection.
- **Location:** a miniature of the dashboard. Grey cells are taken by other modules, green cells are free, and your selection is solid green. Click a free cell, or drag to span a rectangle of rows and columns; the selection stops at grey cells instead of overlapping them. There is always an empty row at the bottom (and below your selection), so you can drag down onto new rows.
- **SQL:** a colour-coded editor. Press **Validate SQL** (or Cmd/Ctrl+Enter) to run the query once. The dialog checks the result has a layout the charts understand and shows a preview of the rows. **Add**/**Save** stays disabled until the SQL validates. Only `SELECT` / `WITH` queries are accepted, so validating can never change data.

Every change is saved to the configuration file straight away. The first save also keeps a copy of your original file as `datamonitor.yaml.bak`. That copy keeps your comments, which the saved file does not.

## Running

**macOS / Linux**
```sh
./bin/datamonitor
```

**Windows**
```bat
winbin\datamonitor.bat
```

The commands run from the `bin` directory, so they use `bin/datamonitor.yaml` unless you name another file. A relative `-c` path is therefore read from `bin`; an absolute path is safest:

```sh
./bin/datamonitor -c /path/to/other.yaml
```

The commands start the app through Apache Commons Launcher; JVM settings such as the memory limit (`-Xmx512m`) are in `launcher/launcher.xml`. `lib/datamonitor.jar` holds the application and every library it needs, so it can also be started directly:

```sh
java -jar lib/datamonitor.jar -c bin/datamonitor.yaml
```

Other options: `--theme dark|light` overrides the configured theme, `--version` prints the version, `-h` shows help.

### Web front end

Start the dashboard as a web application instead of a desktop window:

```sh
./bin/datamonitorweb                       # http://127.0.0.1:8080
./bin/datamonitorweb --port 9090
winbin\datamonitorweb.bat                  # Windows
```

(`datamonitorweb` is `datamonitor --web`; either works.)

From a source checkout, the same with Ant:

```sh
ant run-web
ant run-web -Dargs="--port 9090"
```

On startup it tests the database connection, then prints a link such as:

```
http://127.0.0.1:8080/?token=Xy3…
```

Open that link in a browser. The `token` is a random access key created for each run. The browser remembers it for the session, and the dashboard and its API refuse requests without it. Each time you restart the server, use the new link it prints.

The web dashboard shows the same QModules as the desktop app, live and with the same dark/light themes. It also adds:

- hover tooltips on bar and pie charts too (both versions show them on line and area charts)
- a **Show as table** view for any module
- the same **Add / Edit / Delete** as the desktop app: right-click a module, use its **⋯** menu, use **+ Add** in the header, or click an empty space on the grid
- live updates in every open browser: an edit made in one browser appears in all of them

Edits are saved to the configuration file exactly as in the desktop app (with a `.bak` of the original on the first save).

| Option | Meaning |
|---|---|
| `--web` | Run the web front end instead of the desktop window |
| `--port <n>` | Port to listen on (default `8080`) |
| `--bind <address>` | Address to listen on. The default `127.0.0.1` means only this machine can connect. Use `0.0.0.0` to allow other machines. |

> **Security:** anyone with the link can view the dashboard, edit QModules and run `SELECT` queries using the dashboard's database account. Keep the default `--bind 127.0.0.1` unless you need remote access. The server uses plain HTTP, so for remote use put it behind an HTTPS reverse proxy or an SSH tunnel. Passwords are never sent to the browser, and only `SELECT` / `WITH` statements are accepted.

### Database connection on the command line

The username, password and connect string can be given on the command line instead of, or as well as, `settings.connection`:

```sh
./bin/datamonitor -u system -p welcome1 --connect-string //dbhost:1521/orclpdb
./bin/datamonitor -p "$DB_PASSWORD"      # username and connect string still come from the YAML
```

| Option | Overrides |
|---|---|
| `-u`, `--username <user>` | `settings.connection.username` |
| `-p`, `--password <password>` | `settings.connection.password` (plain text or `enc:<base64>`) |
| `--connect-string <connect>` | `settings.connection.connectString` |

Each option is independent. A value given on the command line wins over the file for that run, and it is **never written to the configuration file**, even when you edit QModules. If all three are given, the YAML doesn't need a `connection` section at all. Modules with their own `connection:` are not affected.

> A password passed on the command line can be seen by other users of the machine (for example with `ps`) and may be saved in your shell history. Passing it from an environment variable, as in the second example, keeps it out of the history but not out of `ps`.

