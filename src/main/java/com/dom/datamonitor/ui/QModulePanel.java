package com.dom.datamonitor.ui;

import com.dom.datamonitor.charts.*;
import com.dom.datamonitor.config.AppConfig;
import com.dom.datamonitor.config.ConnectionConfig;
import com.dom.datamonitor.config.QModuleConfig;
import com.dom.datamonitor.db.SQLExecutor;
import com.dom.datamonitor.model.ChartType;
import com.dom.datamonitor.model.GridLocation;
import com.dom.datamonitor.model.ProcessingMode;
import com.dom.datamonitor.model.QueryResult;

import javax.swing.*;
import java.awt.*;
import java.util.logging.Logger;

/**
 * Container panel for a single QModule: title strip + chart body.
 *
 * <p>Owns a {@link SQLExecutor} that runs on a daemon thread and pushes results
 * to the embedded chart via {@link SwingUtilities#invokeLater}.
 */
public class QModulePanel extends JPanel {

    private static final Logger log = Logger.getLogger(QModulePanel.class.getName());

    private final QModuleConfig      config;
    private final AppConfig.Settings settings;
    private       Theme              theme;
    private final GridLocation       gridLocation;
    private final BaseChart          chart;
    private       SQLExecutor        executor;
    private       TitleStrip         titleStrip;

    // ─────────────────────────────────────────────────────────────────────────

    public QModulePanel(QModuleConfig config, AppConfig.Settings settings, Theme theme)
            throws Exception {
        this.config      = config;
        this.settings    = settings;
        this.theme       = theme;
        this.gridLocation = GridLocation.parse(config.getGrid());
        this.chart       = createChart();

        buildLayout();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    /** Starts the background SQL executor for this module. */
    public void startExecutor() {
        int refreshRate = config.getRefreshRate() != null
            ? config.getRefreshRate() : settings.getRefreshRate();

        ConnectionConfig connConfig = config.getConnection() != null
            ? config.getConnection() : settings.getEffectiveConnection();

        ProcessingMode mode;
        try {
            mode = ProcessingMode.fromString(config.getProcessingMode());
        } catch (Exception e) {
            mode = ProcessingMode.NONE;
        }

        executor = new SQLExecutor(
            config.getSql().trim(),
            mode,
            config.getDivideBy(),
            connConfig,
            refreshRate
        );

        executor.onResult(result -> SwingUtilities.invokeLater(() -> chart.updateData(result)));
        executor.onError(msg -> {
            QueryResult err = QueryResult.error(msg);
            SwingUtilities.invokeLater(() -> chart.updateData(err));
        });

        executor.start();
    }

    /** Stops the SQL executor (e.g. on application shutdown). */
    public void stopExecutor() {
        if (executor != null) executor.stop();
    }

    public GridLocation  getGridLocation() { return gridLocation;      }
    public QModuleConfig getConfig()       { return config;            }
    public String        getTitle()        { return config.getTitle(); }

    // ─────────────────────────────────────────────────────────────────────────
    // Layout
    // ─────────────────────────────────────────────────────────────────────────

    private void buildLayout() {
        setLayout(new BorderLayout(0, 0));
        setBackground(theme.background);
        setBorder(BorderFactory.createLineBorder(theme.border, 1));

        // Title strip – stored so it can be re-themed later
        titleStrip = new TitleStrip(config.getTitle(), theme);
        add(titleStrip, BorderLayout.NORTH);

        // Chart body
        add(chart, BorderLayout.CENTER);

        // Right-click anywhere on the module shows the menu set on this panel
        titleStrip.setInheritsPopupMenu(true);
        chart.setInheritsPopupMenu(true);
    }

    /** Propagates a theme change to the border, title strip and embedded chart. */
    public void setTheme(Theme newTheme) {
        this.theme = newTheme;
        setBackground(newTheme.background);
        setBorder(BorderFactory.createLineBorder(newTheme.border, 1));
        titleStrip.setTheme(newTheme);
        chart.setTheme(newTheme);
        repaint();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Chart factory
    // ─────────────────────────────────────────────────────────────────────────

    private BaseChart createChart() throws Exception {
        ChartType type;
        try {
            type = ChartType.fromString(config.getChartType());
        } catch (Exception e) {
            log.warning("Unknown chart type for module '" + config.getTitle() + "': "
                + config.getChartType() + " – defaulting to LINE");
            type = ChartType.LINE;
        }

        int refreshRate  = config.getRefreshRate()  != null ? config.getRefreshRate()  : settings.getRefreshRate();
        int windowSize   = config.getWindowSize()   != null ? config.getWindowSize()   : settings.getWindowSize();

        return switch (type) {
            case LINE  -> new LineChart(theme, windowSize, refreshRate);
            case AREA  -> new AreaChart(theme, windowSize, refreshRate);
            case BAR   -> new BarChart(theme);
            case PIE   -> new PieChart(theme);
            case TEXT  -> new TextDisplay(theme, settings.isFlashOnChange(), config.getTitle(), windowSize);
            case TABLE -> new TableDisplay(theme);
        };
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Inner: title strip
    // ─────────────────────────────────────────────────────────────────────────

    private static final class TitleStrip extends JPanel {

        private final String title;
        private       Theme  theme;

        TitleStrip(String title, Theme theme) {
            this.title = title;
            this.theme = theme;
            setOpaque(false);
            setPreferredSize(new Dimension(0, 24));
        }

        void setTheme(Theme newTheme) {
            this.theme = newTheme;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g;
            BaseChart.applyRenderingHints(g2);
            int w = getWidth(), h = getHeight();

            // Subtle gradient header band
            LinearGradientPaint grad = new LinearGradientPaint(
                0, 0, 0, h,
                new float[]{0f, 1f},
                new Color[]{new Color(theme.accent.getRed(),
                                      theme.accent.getGreen(),
                                      theme.accent.getBlue(), 50),
                             new Color(0, 0, 0, 0)}
            );
            g2.setPaint(grad);
            g2.fillRect(0, 0, w, h);

            // Bottom border line
            g2.setColor(theme.accent);
            g2.setStroke(new BasicStroke(1f));
            g2.drawLine(0, h - 1, w, h - 1);

            // Title text: shrinks (down to 9pt) to fit a narrow module, then ends in an ellipsis
            int   room = w - 12;
            Font  font = theme.titleFont;
            float size = font.getSize2D();
            while (size > 9f && g2.getFontMetrics(font.deriveFont(size)).stringWidth(title) > room) size -= 0.5f;
            g2.setFont(font.deriveFont(size));
            g2.setColor(theme.foreground);
            FontMetrics fm = g2.getFontMetrics();
            String text = title;
            while (text.length() > 1 && fm.stringWidth(text) > room) {
                text = text.substring(0, text.length() - 2) + "…";
            }
            g2.drawString(text, (w - fm.stringWidth(text)) / 2,
                (h + fm.getAscent() - fm.getDescent()) / 2);
        }
    }
}
