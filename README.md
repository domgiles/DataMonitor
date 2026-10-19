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
datamonitor24022026.zip
└── datamonitor/
    ├── bin/
    │   ├── datamonitor          ← Unix/macOS launcher
    │   ├── datamonitor.bat      ← Windows launcher
    │   └── datamonitor.yaml     ← configuration (edit this)
    ├── lib/                     ← JARs (do not modify)
    └── log/                     ← log output
```

On Unix/macOS make sure the launcher is executable (it should already be, but if not):

```sh
chmod +x bin/datamonitor
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
| `title`        | –       | Window title bar text                                |
| `theme`        | `dark`  | `dark` or `light`                                    |
| `refreshRate`  | `3`     | Global query interval in seconds (overridable per QModule) |
| `windowSize`   | `120`   | Seconds of history shown on LINE and AREA charts     |
| `columns`      | `3`     | Dashboard grid columns (1–3)                         |
| `border`       | `6`     | Pixel gap between QModule panels                     |
| `flashOnChange`| `false` | Flash TEXT tiles when their value changes            |

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

## Running

**Unix/macOS**
```sh
./bin/datamonitor
```

**Windows**
```bat
bin\datamonitor.bat
```

The launcher passes `bin/datamonitor.yaml` automatically. To use a different config file:

```sh
./bin/datamonitor -c /path/to/other.yaml
```

