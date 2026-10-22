package com.dom.datamonitor.tui;

import com.dom.datamonitor.charts.Theme;
import com.dom.datamonitor.engine.MonitorEngine;
import com.dom.datamonitor.engine.MonitorEngine.ModuleView;
import com.dom.datamonitor.engine.MonitorEngine.Sample;
import com.dom.datamonitor.model.GridLocation;
import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Rect;
import dev.tamboui.terminal.Frame;
import dev.tamboui.tui.TuiConfig;
import dev.tamboui.tui.TuiRunner;
import dev.tamboui.tui.event.Event;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.TickEvent;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The dashboard in a terminal ({@code --cl}): the same modules and grid as the desktop and
 * web versions, drawn with characters, read-only.
 *
 * <p>Keys: {@code q} / Esc quits, {@code p} / space pauses the display (queries keep running),
 * {@code t} swaps the dark and light colours, and ↑ ↓ / PgUp PgDn scroll when the terminal is
 * too short to show every row.
 *
 * <p>Results arrive on the engine's threads; they only mark the screen as changed. Drawing
 * happens on the terminal thread, on the next tick, from a snapshot of the engine's modules.
 */
public final class TuiDashboard {

    private static final Duration TICK = Duration.ofMillis(250);

    private final MonitorEngine engine;
    private final String        title;
    private final String        connection;
    private final boolean       flashOnChange;
    private final int           columns;

    private final AtomicBoolean changed = new AtomicBoolean(true);
    private TuiTheme          theme;
    private boolean           paused;
    private List<ModuleView>  frozen;          // what is shown while paused
    private int               scroll;
    private GridLayout        lastLayout;

    public TuiDashboard(MonitorEngine engine, String themeName, String connection) {
        this.engine        = engine;
        this.title         = engine.settings().getTitle();
        this.connection    = connection;
        this.flashOnChange = engine.settings().isFlashOnChange();
        this.columns       = engine.settings().getColumns();
        this.theme         = new TuiTheme(Theme.forName(themeName), TermColors.detect());
    }

    /** Runs until the user quits. The terminal is restored afterwards, even on errors. */
    public void run() throws Exception {
        MonitorEngine.Listener listener = new MonitorEngine.Listener() {
            @Override public void onResult(String moduleId, Sample sample) { changed.set(true); }
            @Override public void onLayoutChanged()                        { changed.set(true); }
        };
        engine.addListener(listener);
        try (TuiRunner tui = TuiRunner.create(TuiConfig.builder().tickRate(TICK).build())) {
            tui.setWindowTitle(title);
            tui.run(this::handle, this::render);
        } finally {
            engine.removeListener(listener);
        }
    }

    /** Returns true when the screen should be redrawn. */
    boolean handle(Event event, TuiRunner runner) {
        if (event instanceof TickEvent) {
            // Redraw for new results, and once more as a value's change colour wears off
            return changed.getAndSet(false) || flashing();
        }
        if (!(event instanceof KeyEvent key)) return false;

        if (key.isCtrlC() || key.isKey(KeyCode.ESCAPE) || key.isCharIgnoreCase('q')) {
            runner.quit();
            return false;
        }
        if (key.isCharIgnoreCase('p') || key.isChar(' ')) {
            paused = !paused;
            frozen = paused ? engine.modules() : null;
            return true;
        }
        if (key.isCharIgnoreCase('t')) {
            theme = theme.other();
            return true;
        }
        int max = lastLayout == null ? 0 : lastLayout.maxScroll();
        int page = lastLayout == null ? 1 : Math.max(1, lastLayout.visibleRows);
        if (key.isUp())       { scroll = Math.max(0, scroll - 1);       return true; }
        if (key.isDown())     { scroll = Math.min(max, scroll + 1);     return true; }
        if (key.isPageUp())   { scroll = Math.max(0, scroll - page);    return true; }
        if (key.isPageDown()) { scroll = Math.min(max, scroll + page);  return true; }
        if (key.isHome())     { scroll = 0;                             return true; }
        if (key.isEnd())      { scroll = max;                           return true; }
        return false;
    }

    private long lastFlashCheck;

    /** True while some TEXT value is still in its change colour (and once just after). */
    private boolean flashing() {
        if (!flashOnChange || paused) return false;
        long now = System.currentTimeMillis();
        boolean any = false;
        for (ModuleView m : engine.modules()) {
            List<Sample> h = m.history();
            if (!h.isEmpty() && now - h.get(h.size() - 1).time() < Panels.FLASH_MILLIS + TICK.toMillis()) any = true;
        }
        boolean redraw = any || now - lastFlashCheck < TICK.toMillis();
        if (any) lastFlashCheck = now;
        return redraw;
    }

    void render(Frame frame) {
        render(frame.buffer(), new Rect(0, 0, frame.width(), frame.height()));
    }

    /** Draws the whole screen into {@code buf}; separate from {@link #render(Frame)} for tests. */
    void render(Buffer buf, Rect area) {
        List<ModuleView> modules = paused ? frozen : engine.modules();

        if (area.height() < 3 || area.width() < 20) {
            buf.setString(0, 0, Panels.fit("Terminal too small", area.width()), theme.muted);
            return;
        }

        Rect grid = new Rect(area.x(), area.y() + 1, area.width(), area.height() - 1);
        List<GridLocation> locations = new ArrayList<>();
        for (ModuleView m : modules) locations.add(m.location());
        GridLayout layout = GridLayout.place(locations, columns, grid, scroll);
        lastLayout = layout;
        scroll = layout.firstRow;

        header(buf, area, layout, modules.isEmpty());

        if (modules.isEmpty()) {
            String msg = "No modules yet: add some in the config file, the desktop app or the web version";
            buf.setString(area.x() + 1, grid.y() + 1, Panels.fit(msg, area.width() - 2), theme.muted);
            return;
        }
        Panels.Context ctx = new Panels.Context(theme, flashOnChange && !paused, System.currentTimeMillis());
        for (GridLayout.Placement p : layout.placements) {
            Panels.render(modules.get(p.index()), p.rect(), buf, ctx);
        }
    }

    /** One line: title and connection on the left; status, scroll position and keys on the right. */
    private void header(Buffer buf, Rect area, GridLayout layout, boolean empty) {
        int x = area.x() + 1;
        x = buf.setString(x, area.y(), "▌", theme.accent);
        x = buf.setString(x + 1, area.y(), title, theme.title);
        int leftEnd = x + 2;

        String status = paused ? "❚❚ PAUSED" : "● LIVE";
        String rows = layout.scrollable() && !empty
            ? "rows " + (layout.firstRow + 1) + "-" + (layout.firstRow + layout.visibleRows) + "/" + layout.totalRows + " ↑↓  "
            : "";
        String keys = "q quit  p pause  t theme";
        String right = rows + keys + "   " + status + " ";

        int rx = area.right() - right.length();
        if (rx > leftEnd + connection.length() + 2) {
            buf.setString(leftEnd, area.y(), connection, theme.muted);
        }
        if (rx > leftEnd) {
            int kx = buf.setString(rx, area.y(), rows + keys, theme.muted);
            buf.setString(kx + 3, area.y(), status, paused ? theme.paused : theme.accent);
        } else {
            buf.setString(area.right() - status.length() - 1, area.y(), status, paused ? theme.paused : theme.accent);
        }
    }
}
