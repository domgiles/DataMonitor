package com.dom.datamonitor.config;

/**
 * Configuration for a single QModule (query + visualisation unit).
 *
 * <p>Loaded directly by SnakeYAML from the {@code qmodules} list in the YAML file.
 *
 * <h3>Grid specification</h3>
 * <pre>
 *   R1,C1     – row 1, column 1 only
 *   R1,C1-2   – row 1, spanning columns 1 and 2
 *   R1,C1*    – row 1, spanning all columns from 1 to maxColumns
 * </pre>
 *
 * <h3>SQL result format</h3>
 * The SQL must return rows of:
 * <ul>
 *   <li>Duplet:  (key VARCHAR, value NUMERIC)</li>
 *   <li>Triplet: (key VARCHAR, colour VARCHAR, value NUMERIC) – colour before value</li>
 * </ul>
 * Multiple series are expressed as multiple rows (UNION) or multiple triplet/duplet
 * column groups in a single row.
 */
public class QModuleConfig {

    /** Display title shown above the chart. */
    private String title;

    /** Optional developer note – never rendered in the UI. */
    private String comment;

    /**
     * Grid placement, e.g. {@code R2,C1-2}.
     * Required.
     */
    private String grid;

    /**
     * Chart type: LINE, AREA, BAR, PIE, TEXT, TABLE.
     * Required.
     */
    private String chartType;

    /**
     * SQL query to execute. See class-level JavaDoc for result format.
     * Required.
     */
    private String sql;

    /**
     * How values are processed between executions.
     * DIFFERENCE (default for counters) or NONE.
     * Defaults to NONE if not specified.
     */
    private String processingMode;

    /**
     * Refresh interval in seconds for this module.
     * Overrides the global {@code settings.refreshRate} when set.
     */
    private Integer refreshRate;

    /**
     * Time-series window in seconds (LINE / AREA only).
     * Overrides the global {@code settings.windowSize} when set.
     */
    private Integer windowSize;

    /**
     * Divisor applied to all numeric values before rendering.
     * Useful for unit conversion (e.g. {@code 1048576} to convert bytes to MB).
     * Defaults to 1.
     */
    private double divideBy = 1.0;

    /**
     * Optional connection override for this module.
     * Uses the global connection from {@code settings.connection} when null.
     */
    private ConnectionConfig connection;

    // --- JavaBeans accessors ---

    public String getTitle()                    { return title;          }
    public void   setTitle(String v)            { this.title = v;        }

    public String getComment()                  { return comment;        }
    public void   setComment(String v)          { this.comment = v;      }

    public String getGrid()                     { return grid;           }
    public void   setGrid(String v)             { this.grid = v;         }

    public String getChartType()                { return chartType;      }
    public void   setChartType(String v)        { this.chartType = v;    }

    public String getSql()                      { return sql;            }
    public void   setSql(String v)              { this.sql = v;          }

    public String getProcessingMode()           { return processingMode; }
    public void   setProcessingMode(String v)   { this.processingMode = v; }

    public Integer getRefreshRate()             { return refreshRate;    }
    public void    setRefreshRate(Integer v)    { this.refreshRate = v;  }

    public Integer getWindowSize()              { return windowSize;     }
    public void    setWindowSize(Integer v)     { this.windowSize = v;   }

    public double getDivideBy()                 { return divideBy;       }
    public void   setDivideBy(double v)         { this.divideBy = v;     }

    public ConnectionConfig getConnection()           { return connection;   }
    public void             setConnection(ConnectionConfig v) { this.connection = v; }
}
