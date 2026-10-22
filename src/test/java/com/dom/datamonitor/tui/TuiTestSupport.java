package com.dom.datamonitor.tui;

import com.dom.datamonitor.charts.Theme;
import com.dom.datamonitor.config.ConfigException;
import com.dom.datamonitor.config.QModuleConfig;
import com.dom.datamonitor.engine.MonitorEngine.ModuleView;
import com.dom.datamonitor.engine.MonitorEngine.Sample;
import com.dom.datamonitor.model.GridLocation;
import com.dom.datamonitor.model.QueryRow;
import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Rect;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Builds modules and reads a rendered buffer back as text, so panels can be tested without a terminal. */
final class TuiTestSupport {

    static final TuiTheme THEME = new TuiTheme(Theme.DARK, new TermColors(true));

    private TuiTestSupport() {}

    static ModuleView module(String title, String type, String grid, String mode, List<Sample> history) {
        QModuleConfig c = new QModuleConfig();
        c.setTitle(title);
        c.setChartType(type);
        c.setGrid(grid);
        c.setProcessingMode(mode);
        c.setSql("SELECT 'x', 1 FROM dual");
        try {
            return new ModuleView("m1", c, GridLocation.parse(grid), 2, 120, history);
        } catch (ConfigException e) {
            throw new IllegalArgumentException(e);
        }
    }

    static Sample sample(long time, QueryRow... rows) {
        return new Sample(time, List.of(rows), null);
    }

    static Sample error(long time, String message) {
        return new Sample(time, List.of(), message);
    }

    /** Renders {@code m} into a {@code width} x {@code height} buffer and returns its lines. */
    static List<String> render(ModuleView m, int width, int height, long now) {
        Locale saved = Locale.getDefault();
        Locale.setDefault(Locale.US);              // "8,192", whatever the machine's locale
        try {
            Buffer buf = Buffer.empty(Rect.of(width, height));
            Panels.render(m, new Rect(0, 0, width, height), buf, new Panels.Context(THEME, true, now));
            return lines(buf);
        } finally {
            Locale.setDefault(saved);
        }
    }

    static List<String> lines(Buffer buf) {
        List<String> out = new ArrayList<>();
        for (int y = 0; y < buf.height(); y++) {
            StringBuilder line = new StringBuilder();
            for (int x = 0; x < buf.width(); x++) line.append(buf.get(x, y).symbol());
            out.add(line.toString());
        }
        return out;
    }

    static String text(List<String> lines) {
        return String.join("\n", lines);
    }
}
