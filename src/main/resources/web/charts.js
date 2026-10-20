// Chart cards: one per QModule. Renders LINE / AREA / BAR / PIE with ECharts and
// TEXT / TABLE as plain DOM, from the module's history plus live results.
//
// Series colours: a colour named in the SQL (resolved server-side exactly as the Swing
// charts do) always wins. Otherwise the validated categorical palette below is used, in
// fixed order, with each series keeping its slot for the card's lifetime (colour follows
// the entity, never its rank). Past 8 series, extra series are drawn in the muted grey.

/* global echarts */

// Validated with the dataviz palette checker against this app's surfaces
// (#121821 dark, #ffffff light): all adjacent CVD / normal-vision checks pass.
const PALETTE = {
  dark:  ['#3987e5', '#d95926', '#199e70', '#c98500', '#d55181', '#008300', '#9085e9', '#e66767'],
  light: ['#2a78d6', '#eb6834', '#1baf7a', '#eda100', '#e87ba4', '#008300', '#6250d6', '#e34948'],
};

const MODE_CHIPS = {
  DIFFERENCE:             ['Δ/s',   'Per-second rate of change'],
  CHANGE_PER_MIN:         ['Δ/min', 'Per-minute rate of change'],
  CHANGE_PER_HOUR:        ['Δ/h',   'Per-hour rate of change'],
  CUMULATIVE_SINCE_START: ['Σ',     'Total change since the dashboard started'],
};

// ── Formatting ───────────────────────────────────────────────────────────────

const compactFmt = new Intl.NumberFormat(undefined, { notation: 'compact', maximumFractionDigits: 1 });
const smallFmt   = new Intl.NumberFormat(undefined, { maximumSignificantDigits: 3 });

/** Full value for tiles, tables and tooltips: 1,284 · 12.5 · 0.00412 · 3.4M */
export function formatValue(v) {
  if (v === null || v === undefined || Number.isNaN(v)) return '–';
  const abs = Math.abs(v);
  if (abs >= 1e6) return compactFmt.format(v);
  if (abs > 0 && abs < 1) return smallFmt.format(v);     // keep small rates readable
  const digits = abs === 0 || abs >= 100 ? 0 : abs >= 10 ? 1 : 2;
  return v.toLocaleString(undefined, { maximumFractionDigits: digits });
}

/** Short value for axes and labels: 0 · 0.002 · 2.5 · 12K · 3.4M */
export function formatCompact(v) {
  if (v === null || v === undefined || Number.isNaN(v)) return '–';
  const abs = Math.abs(v);
  if (abs >= 1000) return compactFmt.format(v);
  return abs > 0 && abs < 10 ? smallFmt.format(v) : v.toLocaleString(undefined, { maximumFractionDigits: 0 });
}

/** {@code #rrggbb} moved {@code amount} (0–1) of the way towards black. */
function darken(hex, amount) {
  const m = /^#?([0-9a-f]{6})$/i.exec(hex || '');
  if (!m) return hex;
  const n = parseInt(m[1], 16);
  const ch = (shift) => Math.round(((n >> shift) & 255) * (1 - amount)).toString(16).padStart(2, '0');
  return `#${ch(16)}${ch(8)}${ch(0)}`;
}

const escapeHtml = (s) => String(s ?? '').replace(/[&<>"']/g,
  (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

function el(tag, className, text) {
  const e = document.createElement(tag);
  if (className) e.className = className;
  if (text !== undefined) e.textContent = text;
  return e;
}

/** Theme tokens read from CSS, so charts follow the same variables as the page. */
export function themeTokens() {
  const css = getComputedStyle(document.documentElement);
  const v = (name) => css.getPropertyValue(name).trim();
  const theme = document.documentElement.dataset.theme === 'light' ? 'light' : 'dark';
  return {
    theme,
    palette: PALETTE[theme],
    surface: v('--surface'), line: v('--line'), lineStrong: v('--line-strong'),
    text: v('--text'), text2: v('--text-2'), muted: v('--muted'),
    accent: v('--accent'), surface2: v('--surface-2'),
    mono: v('--font-mono'), ui: v('--font-ui'),
  };
}

// ── Card ─────────────────────────────────────────────────────────────────────

export class Card {
  /**
   * @param def      module definition from /api/dashboard (includes history)
   * @param settings dashboard settings (flashOnChange, …)
   * @param tokens   theme tokens from themeTokens()
   */
  constructor(def, settings, tokens) {
    this.def = def;
    this.settings = settings;
    this.tokens = tokens;
    this.history = [];
    this.slots = new Map();          // series key -> palette slot
    this.tableView = false;
    this.chart = null;
    this.spark = null;
    this.capacity = this.computeCapacity();

    this.el = el('article', 'card');
    this.el.dataset.id = def.id;
    this.el.tabIndex = -1;

    const head = el('header', 'card-head');
    this.titleEl = el('h2', 'card-title', def.title);
    this.titleEl.title = def.title;
    head.append(this.titleEl);
    const chip = MODE_CHIPS[def.processingMode];
    if (chip) {
      const c = el('span', 'chip', chip[0]);
      c.title = chip[1];
      head.append(c);
    }
    const every = el('span', 'chip', `${def.refreshRate}s`);
    every.title = `Refreshes every ${def.refreshRate} seconds`;
    head.append(every);

    this.menuBtn = el('button', 'card-menu-btn');
    this.menuBtn.type = 'button';
    this.menuBtn.setAttribute('aria-label', `Options for ${def.title}`);
    this.menuBtn.setAttribute('aria-haspopup', 'menu');
    this.menuBtn.innerHTML = '<svg viewBox="0 0 16 16" aria-hidden="true"><circle cx="3.5" cy="8" r="1.2" class="fill"/><circle cx="8" cy="8" r="1.2" class="fill"/><circle cx="12.5" cy="8" r="1.2" class="fill"/></svg>';
    head.append(this.menuBtn);

    this.beat = el('div', 'beat');
    this.beat.style.setProperty('--beat', `${def.refreshRate}s`);
    this.body = el('div', 'card-body');
    this.el.append(head, this.beat, this.body);

    this.resizeObserver = new ResizeObserver(() => {
      this.chart?.resize();
      this.spark?.resize();
    });
    this.resizeObserver.observe(this.body);

    this.setHistory(def.history || []);
  }

  /** Same windowing as MonitorEngine: time series and TEXT keep a window, others the latest. */
  computeCapacity() {
    const windowed = ['LINE', 'AREA', 'TEXT'].includes(this.def.chartType);
    return windowed ? Math.max(2, Math.floor(this.def.windowSize / Math.max(1, this.def.refreshRate)) + 1) : 1;
  }

  setHistory(history) {
    this.history = history.slice(-this.capacity);
    this.history.forEach((s) => this.assignSlots(s.rows));
    this.render();
  }

  push(sample) {
    const previous = this.latestGood();
    this.history.push(sample);
    if (this.history.length > this.capacity) this.history.splice(0, this.history.length - this.capacity);
    this.assignSlots(sample.rows);
    this.restartBeat();
    this.render(previous);
  }

  setTheme(tokens) {
    this.tokens = tokens;
    this.disposeCharts();
    this.body.replaceChildren();
    this.render();
  }

  toggleTable() {
    this.tableView = !this.tableView;
    this.disposeCharts();
    this.body.replaceChildren();
    this.render();
    return this.tableView;
  }

  dispose() {
    this.resizeObserver.disconnect();
    this.disposeCharts();
    this.el.remove();
  }

  // ── Helpers ───────────────────────────────────────────────────────────────

  disposeCharts() {
    this.chart?.dispose();
    this.spark?.dispose();
    this.chart = null;
    this.spark = null;
  }

  restartBeat() {
    this.beat.classList.remove('run');
    void this.beat.offsetWidth;   // reflow so the animation restarts
    this.beat.classList.add('run');
  }

  latestGood() {
    for (let i = this.history.length - 1; i >= 0; i--) {
      if (!this.history[i].error) return this.history[i];
    }
    return null;
  }

  assignSlots(rows) {
    for (const r of rows || []) {
      if (!this.slots.has(r.key)) this.slots.set(r.key, this.slots.size);
    }
  }

  colour(key, sqlColour) {
    if (sqlColour) return sqlColour;
    const slot = this.slots.get(key) ?? 0;
    return slot < this.tokens.palette.length ? this.tokens.palette[slot] : this.tokens.muted;
  }

  /** Series keys in first-seen order. */
  keys() {
    return [...this.slots.keys()];
  }

  sqlColours() {
    const colours = new Map();
    for (const s of this.history) for (const r of s.rows) if (r.color) colours.set(r.key, r.color);
    return colours;
  }

  // ── Rendering ─────────────────────────────────────────────────────────────

  render(previous) {
    const latest = this.history[this.history.length - 1];
    const good = this.latestGood();

    this.el.classList.toggle('is-error', Boolean(latest?.error));
    this.el.querySelector('.card-error')?.remove();
    if (latest?.error) {
      const box = el('div', 'card-error');
      box.append(el('strong', null, '⚠'), el('span', null, latest.error));
      this.el.append(box);
    }

    if (!good) {
      if (!this.body.querySelector('.waiting')) {
        this.disposeCharts();
        this.body.replaceChildren(el('p', 'waiting', latest?.error ? 'No data' : 'Waiting for first result…'));
      }
      return;
    }
    this.body.querySelector('.waiting')?.remove();

    if (this.tableView) return this.renderTable(good.rows);

    switch (this.def.chartType) {
      case 'TEXT':  return this.renderText(good, previous);
      case 'TABLE': return this.renderTable(good.rows);
      default:      return this.renderEChart();
    }
  }

  ensureChart() {
    if (!this.chart) {
      const host = el('div', 'chart');
      this.body.replaceChildren(host);
      this.chart = echarts.init(host, null, { renderer: 'canvas' });
    }
    return this.chart;
  }

  renderEChart() {
    const t = this.tokens;
    const base = {
      animationDuration: 500,
      animationDurationUpdate: 350,
      textStyle: { fontFamily: t.ui, color: t.text2 },
      tooltip: {
        backgroundColor: t.surface2,
        borderColor: t.lineStrong,
        borderWidth: 1,
        padding: [8, 10],
        textStyle: { color: t.text, fontSize: 12 },
        extraCssText: 'box-shadow: 0 8px 24px rgba(0,0,0,.35); border-radius: 8px;',
      },
    };
    const option = {
      LINE: () => this.timeSeriesOption(base, false),
      AREA: () => this.timeSeriesOption(base, true),
      BAR:  () => this.barOption(base),
      PIE:  () => this.pieOption(base),
    }[this.def.chartType]?.();
    if (option) this.ensureChart().setOption(option, { notMerge: true, lazyUpdate: true });
  }

  axisStyle() {
    const t = this.tokens;
    return {
      axisLine: { show: false },
      axisTick: { show: false },
      axisLabel: { color: t.muted, fontFamily: t.mono, fontSize: 10.5 },
      splitLine: { lineStyle: { color: t.line, width: 1, type: 'solid' } },
    };
  }

  timeSeriesOption(base, stacked) {
    const t = this.tokens;
    const keys = this.keys();
    const colours = this.sqlColours();
    const samples = this.history.filter((s) => !s.error);
    const lastTime = samples.length ? samples[samples.length - 1].time : Date.now();
    const many = keys.length > 1;

    const series = keys.map((key) => {
      const colour = this.colour(key, colours.get(key));
      const data = samples.map((s) => {
        const row = s.rows.find((r) => r.key === key);
        return [s.time, row ? row.value : null];
      });
      return stacked ? {
        name: key, type: 'line', stack: 'total', data, color: colour,
        showSymbol: false, symbolSize: 8,
        // Edge each band in a darker shade of its own colour (dark red on red …). A series
        // that has been zero all window gets no edge: it would only overdraw the band below.
        lineStyle: { width: data.some(([, v]) => v) ? 1.5 : 0, color: darken(colour, 0.4) },
        areaStyle: { color: colour, opacity: t.theme === 'dark' ? 0.82 : 0.75 },
        emphasis: { focus: 'series' },
      } : {
        name: key, type: 'line', data, color: colour,
        showSymbol: false, symbolSize: 8, connectNulls: false,
        lineStyle: { width: 2, cap: 'round', join: 'round' },
        areaStyle: many ? undefined : { color: colour, opacity: 0.1 },
        endLabel: keys.length <= 2 ? {
          show: true, formatter: (p) => formatCompact(p.value[1]),
          color: t.text2, fontFamily: t.mono, fontSize: 11, distance: 6,
        } : undefined,
        emphasis: { focus: 'series' },
      };
    });

    return {
      ...base,
      grid: { top: many ? 34 : 14, left: 10, right: keys.length <= 2 && !stacked ? 46 : 14, bottom: 6, containLabel: true },
      legend: many ? {
        top: 6, left: 10, type: 'scroll', itemGap: 14,
        icon: stacked ? 'roundRect' : 'path://M0,0H16V3H0Z', itemWidth: stacked ? 10 : 14, itemHeight: stacked ? 10 : 3,
        textStyle: { color: t.text2, fontSize: 11 },
        pageIconColor: t.text2, pageTextStyle: { color: t.muted },
      } : undefined,
      tooltip: {
        ...base.tooltip,
        trigger: 'axis',
        axisPointer: { type: 'line', lineStyle: { color: t.lineStrong, width: 1 } },
        formatter: (params) => {
          if (!params.length) return '';
          const time = new Date(params[0].value[0]).toLocaleTimeString();
          let total = 0;
          const rows = params.map((p) => {
            const v = p.value[1];
            if (v !== null) total += v;
            return `<div style="display:flex;align-items:center;gap:8px;line-height:1.7">
              <span style="width:12px;height:3px;border-radius:2px;background:${p.color}"></span>
              <b style="font-family:${t.mono};min-width:60px">${escapeHtml(formatValue(v))}</b>
              <span style="color:${t.text2}">${escapeHtml(p.seriesName)}</span></div>`;
          }).join('');
          const totalRow = stacked && params.length > 1
            ? `<div style="margin-top:4px;padding-top:4px;border-top:1px solid ${t.line};color:${t.text2}">Total <b style="color:${t.text};font-family:${t.mono}">${escapeHtml(formatValue(total))}</b></div>` : '';
          return `<div style="color:${t.muted};font-size:11px;margin-bottom:3px">${time}</div>${rows}${totalRow}`;
        },
      },
      xAxis: {
        type: 'time',
        min: lastTime - this.def.windowSize * 1000,
        max: lastTime,
        ...this.axisStyle(),
        splitLine: { show: false },
        axisLabel: { ...this.axisStyle().axisLabel, hideOverlap: true,
          formatter: (v) => new Date(v).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' }) },
      },
      yAxis: {
        type: 'value',
        min: 0,
        ...this.axisStyle(),
        axisLabel: { ...this.axisStyle().axisLabel, formatter: (v) => formatCompact(v) },
      },
      series,
    };
  }

  barOption(base) {
    const t = this.tokens;
    const rows = this.latestGood().rows;
    return {
      ...base,
      grid: { top: 10, left: 10, right: 52, bottom: 6, containLabel: true },
      tooltip: {
        ...base.tooltip, trigger: 'item',
        formatter: (p) => `<b style="font-family:${t.mono}">${escapeHtml(formatValue(p.value))}</b>
          <span style="color:${t.text2};margin-left:6px">${escapeHtml(p.name)}</span>`,
      },
      xAxis: { type: 'value', min: 0, ...this.axisStyle(),
        axisLabel: { ...this.axisStyle().axisLabel, formatter: (v) => formatCompact(v) } },
      yAxis: {
        type: 'category', inverse: true, data: rows.map((r) => r.key),
        ...this.axisStyle(),
        axisLabel: { color: t.text2, fontSize: 11.5, width: 120, overflow: 'truncate' },
      },
      series: [{
        type: 'bar',
        barMaxWidth: 24,
        barCategoryGap: '30%',
        // One series: one colour (slot 1) unless the SQL names colours per row
        data: rows.map((r) => ({
          value: r.value,
          itemStyle: { color: r.color || t.palette[0], borderRadius: [0, 4, 4, 0] },
        })),
        label: {
          show: true, position: 'right', distance: 6,
          color: t.text2, fontFamily: t.mono, fontSize: 11,
          formatter: (p) => formatCompact(p.value),
        },
        emphasis: { itemStyle: { opacity: 0.85 } },
      }],
    };
  }

  pieOption(base) {
    const t = this.tokens;
    const rows = this.latestGood().rows.filter((r) => r.value !== null && r.value >= 0);
    const total = rows.reduce((a, r) => a + r.value, 0);
    const pct = (v) => (total > 0 ? (100 * v) / total : 0);
    const byName = new Map(rows.map((r) => [r.key, r.value]));
    return {
      ...base,
      tooltip: {
        ...base.tooltip, trigger: 'item',
        formatter: (p) => `<b style="font-family:${t.mono}">${escapeHtml(formatValue(p.value))}</b>
          <span style="color:${t.text2};margin:0 6px">${escapeHtml(p.name)}</span>
          <span style="color:${t.muted}">${p.percent.toFixed(1)}%</span>`,
      },
      legend: {
        type: 'scroll', orient: 'vertical', right: 10, top: 'middle',
        icon: 'roundRect', itemWidth: 10, itemHeight: 10, itemGap: 9,
        textStyle: { color: t.text2, fontSize: 11.5 },
        formatter: (name) => `${name}  ${pct(byName.get(name) ?? 0).toFixed(1)}%`,
        pageIconColor: t.text2, pageTextStyle: { color: t.muted },
      },
      title: {
        text: formatCompact(total), subtext: 'total',
        left: '29%', top: 'center', textAlign: 'center',
        textStyle: { color: t.text, fontSize: 18, fontWeight: 600, fontFamily: t.ui },
        subtextStyle: { color: t.muted, fontSize: 10.5 },
        itemGap: 2,
      },
      series: [{
        type: 'pie',
        radius: ['50%', '76%'],
        center: ['30%', '52%'],
        padAngle: 1,
        avoidLabelOverlap: true,
        label: { show: false },
        itemStyle: { borderColor: t.surface, borderWidth: 2, borderRadius: 4 },
        emphasis: { scale: true, scaleSize: 4 },
        data: rows.map((r) => ({ name: r.key, value: r.value, itemStyle: { color: this.colour(r.key, r.color) } })),
      }],
    };
  }

  renderText(good, previous) {
    const row = good.rows[0];
    let tile = this.body.querySelector('.tile');
    if (!tile) {
      this.disposeCharts();
      tile = el('div', 'tile');
      tile.append(el('div', 'tile-label'), el('div', 'tile-value'));
      const foot = el('div', 'tile-foot');
      foot.append(el('div', 'tile-spark'), el('span', 'delta'));
      tile.append(foot);
      this.body.replaceChildren(tile);
    }
    // The card title usually repeats the row label; only show the label when it adds something
    const label = row?.key ?? '';
    tile.querySelector('.tile-label').textContent =
      label.trim().toLowerCase() === this.def.title.trim().toLowerCase() ? '' : label;
    tile.querySelector('.tile-value').textContent = row ? formatValue(row.value) : '–';

    // Change vs the previous good sample: signed and arrowed, never colour alone
    const prevRow = previous?.rows?.[0];
    const delta = tile.querySelector('.delta');
    if (row && prevRow && row.value !== null && prevRow.value !== null) {
      const d = row.value - prevRow.value;
      delta.className = `delta ${d > 0 ? 'up' : d < 0 ? 'down' : ''}`;
      delta.textContent = d === 0 ? '± 0' : `${d > 0 ? '▲ +' : '▼ '}${formatValue(d)}`;
      if (d !== 0 && this.settings.flashOnChange) this.flash(d > 0 ? 'is-flash-up' : 'is-flash-down');
    }

    // Sparkline of the first row's value over the window
    const points = this.history.filter((s) => !s.error && s.rows[0]).map((s) => [s.time, s.rows[0].value]);
    if (points.length > 1) {
      const t = this.tokens;
      if (!this.spark) this.spark = echarts.init(tile.querySelector('.tile-spark'), null, { renderer: 'canvas' });
      this.spark.setOption({
        animation: false,
        grid: { top: 3, bottom: 3, left: 2, right: 2 },
        xAxis: { type: 'time', show: false },
        yAxis: { type: 'value', show: false, scale: true },
        series: [{
          type: 'line', data: points, showSymbol: false,
          lineStyle: { width: 1.5, color: t.muted },
          areaStyle: { color: t.muted, opacity: 0.08 },
          markPoint: {
            symbol: 'circle', symbolSize: 7, data: [{ coord: points[points.length - 1] }],
            itemStyle: { color: t.accent, borderColor: t.surface, borderWidth: 2 }, label: { show: false },
          },
        }],
      }, { notMerge: true });
    }
  }

  flash(cls) {
    this.el.classList.remove('is-flash-up', 'is-flash-down');
    void this.el.offsetWidth;
    this.el.classList.add(cls);
    clearTimeout(this.flashTimer);
    this.flashTimer = setTimeout(() => this.el.classList.remove(cls), 900);
  }

  renderTable(rows) {
    this.disposeCharts();
    const wrap = el('div', 'table-wrap');
    const table = el('table', 'data-table');
    const head = el('thead');
    const hr = el('tr');
    hr.append(el('th', null, 'Metric'), el('th', 'num', 'Value'));
    head.append(hr);
    const body = el('tbody');
    for (const r of rows) {
      const tr = el('tr');
      const name = el('td');
      const sw = el('span', 'swatch');
      sw.style.background = this.colour(r.key, r.color);
      name.append(sw, document.createTextNode(r.key ?? ''));
      tr.append(name, el('td', 'num', formatValue(r.value)));
      body.append(tr);
    }
    table.append(head, body);
    wrap.append(table);
    this.body.replaceChildren(wrap);
  }
}
