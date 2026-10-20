// Add / edit dialog: settings form, location picker and SQL editor with validation.
// Same rules as the Swing QModuleDialog: Save is enabled only once the current SQL (and
// connection) has validated; when editing, the saved SQL counts as validated until changed.

/* global CodeMirror */

import { addModule, updateModule, validateSql } from './api.js';
import { formatValue } from './charts.js';

const $ = (sel) => document.querySelector(sel);

const dialog   = $('#editor');
const form     = $('#editor-form');
const picker   = $('#picker');
const statusEl = $('#validate-status');
const preview  = $('#preview');
const saveBtn  = $('#save-btn');
const errorEl  = $('#form-error');

let cm;          // CodeMirror instance (created once)
let state;       // per-open state, see openEditor

function sqlEditor() {
  if (!cm) {
    cm = CodeMirror.fromTextArea($('#sql'), {
      mode: 'text/x-plsql',
      lineNumbers: true,
      matchBrackets: true,
      indentUnit: 4,
      tabSize: 4,
      indentWithTabs: false,     // tabs would end up in the YAML
      lineWrapping: false,
      extraKeys: {
        'Ctrl-Enter': () => runValidation(),
        'Cmd-Enter': () => runValidation(),
        Tab: (ed) => ed.replaceSelection('    '),
      },
    });
    cm.on('change', refresh);
    $('#kbd-mod').textContent = /Mac|iPhone|iPad/.test(navigator.platform) ? '⌘' : 'Ctrl';
  }
  return cm;
}

// ── Grid helpers ─────────────────────────────────────────────────────────────
// A location is {row, rowEnd, colStart, colEnd}: a rectangle of grid cells (1-based, inclusive).

const spec = (loc) => {
  const rows = loc.rowEnd === loc.row ? `R${loc.row}` : `R${loc.row}-${loc.rowEnd}`;
  return loc.colStart === loc.colEnd ? `${rows},C${loc.colStart}` : `${rows},C${loc.colStart}-${loc.colEnd}`;
};

const sameLoc = (a, b) => a && b && a.row === b.row && a.rowEnd === b.rowEnd
  && a.colStart === b.colStart && a.colEnd === b.colEnd;

const inside = (loc, r, c) => loc && r >= loc.row && r <= loc.rowEnd && c >= loc.colStart && c <= loc.colEnd;

/** Rectangle with corners a and b, each {row, col}. */
const rect = (a, b) => ({
  row: Math.min(a.row, b.row), rowEnd: Math.max(a.row, b.row),
  colStart: Math.min(a.col, b.col), colEnd: Math.max(a.col, b.col),
});

/** Every module except the one being edited, plus their cells: "row,col" -> title. */
function occupancy(modules, excludeId) {
  const blocks = modules.filter((m) => m.id !== excludeId);
  const taken = new Map();
  for (const m of blocks) {
    for (let r = m.row; r <= m.rowEnd; r++) {
      for (let c = m.colStart; c <= m.colEnd; c++) taken.set(`${r},${c}`, m.title);
    }
  }
  return { blocks, taken };
}

function firstFree(taken, columns) {
  for (let r = 1; ; r++) {
    for (let c = 1; c <= columns; c++) {
      if (!taken.has(`${r},${c}`)) return { row: r, rowEnd: r, colStart: c, colEnd: c };
    }
  }
}

function fits(loc) {
  if (loc.colEnd > state.columns) return false;
  for (let r = loc.row; r <= loc.rowEnd; r++) {
    for (let c = loc.colStart; c <= loc.colEnd; c++) {
      if (state.taken.has(`${r},${c}`)) return false;
    }
  }
  return true;
}

/**
 * The selection for a drag from anchor to target: the largest free rectangle that has the
 * anchor as one corner and stays within the anchor-target box (closest to the target on a tie).
 * So dragging into a taken cell stops the selection at its edge instead of overlapping.
 */
function bestRect(anchor, target) {
  const dr = target.row >= anchor.row ? 1 : -1;
  const dc = target.col >= anchor.col ? 1 : -1;
  let best = null, bestArea = 0, bestDist = Infinity;
  for (let r = anchor.row; dr > 0 ? r <= target.row : r >= target.row; r += dr) {
    for (let c = anchor.col; dc > 0 ? c <= target.col : c >= target.col; c += dc) {
      const loc = rect(anchor, { row: r, col: c });
      if (!fits(loc)) continue;
      const area = (loc.rowEnd - loc.row + 1) * (loc.colEnd - loc.colStart + 1);
      const dist = Math.abs(target.row - r) + Math.abs(target.col - c);
      if (area > bestArea || (area === bestArea && dist < bestDist)) {
        best = loc; bestArea = area; bestDist = dist;
      }
    }
  }
  return best;
}

// ── Picker ───────────────────────────────────────────────────────────────────

function renderPicker() {
  const { blocks, taken, columns, selection } = state;
  let maxRow = 0;
  for (const b of blocks) maxRow = Math.max(maxRow, b.rowEnd);
  const rows = Math.max(maxRow, selection?.rowEnd ?? 0) + 1;     // always one empty row at the bottom

  picker.style.setProperty('--cols', columns);
  const frag = document.createDocumentFragment();
  for (let c = 1; c <= columns; c++) {
    const h = cell('axis', `C${c}`, { row: 0, rowEnd: 0, colStart: c, colEnd: c });
    h.dataset.colHeader = c;
    frag.append(h);
  }
  for (let r = 1; r <= rows; r++) {
    const h = cell('axis', `R${r}`, { row: r, rowEnd: r, colStart: 0, colEnd: 0 });
    h.dataset.rowHeader = r;
    frag.append(h);
  }

  // Every element is placed explicitly, so a module spanning rows is one rounded block
  for (const b of blocks) {
    const e = cell('cell taken', b.title, { ...b, colEnd: Math.min(b.colEnd, columns) });
    e.title = `${b.title} (${spec(b)})`;
    frag.append(e);
  }
  if (selection) {
    const e = cell('cell selected', spec(selection), selection);
    e.setAttribute('aria-selected', 'true');
    e.title = `${spec(selection)}: drag to resize`;
    frag.append(e);
  }
  for (let r = 1; r <= rows; r++) {
    for (let c = 1; c <= columns; c++) {
      if (taken.has(`${r},${c}`) || inside(selection, r, c)) continue;
      const e = cell('cell free', '', { row: r, rowEnd: r, colStart: c, colEnd: c });
      e.title = `Free (R${r},C${c}): click to place here, drag to span rows and columns`;
      frag.append(e);
    }
  }
  picker.replaceChildren(frag);
}

/** A picker element covering loc; row 0 / column 0 are the axis labels. */
function cell(className, text, loc) {
  const e = document.createElement('div');
  e.className = className;
  const label = document.createElement('span');
  label.textContent = text;
  e.append(label);
  e.dataset.row = loc.row;
  e.dataset.col = loc.colStart;
  e.style.gridRow = `${loc.row + 1} / ${loc.rowEnd + 2}`;
  e.style.gridColumn = `${loc.colStart + 1} / ${loc.colEnd + 2}`;
  return e;
}

/**
 * Index of the header under the pointer along one axis. Between the headers and the picker's
 * edge it clamps to the first / last one; outside the picker it is null (no change), so holding
 * the pointer below the picker does not keep adding rows.
 */
function headerAt(selector, attr, pos, edge) {
  const headers = [...picker.querySelectorAll(selector)];
  if (!headers.length) return null;
  for (const h of headers) {
    const r = h.getBoundingClientRect();
    if (pos >= r[edge[0]] - 3 && pos <= r[edge[1]] + 3) return Number(h.dataset[attr]);
  }
  const box = picker.getBoundingClientRect();
  if (pos < box[edge[0]] || pos > box[edge[1]]) return null;
  const first = headers[0].getBoundingClientRect();
  return Number((pos < first[edge[0]] ? headers[0] : headers[headers.length - 1]).dataset[attr]);
}

const cellAt = (x, y) => ({
  row: headerAt('[data-row-header]', 'rowHeader', y, ['top', 'bottom']),
  col: headerAt('[data-col-header]', 'colHeader', x, ['left', 'right']),
});

picker.addEventListener('pointerdown', (e) => {
  const target = e.target.closest('.free, .selected');
  if (!target) return;
  const at = target.classList.contains('selected')
    ? cellAt(e.clientX, e.clientY)
    : { row: Number(target.dataset.row), col: Number(target.dataset.col) };
  state.drag = { anchor: at };
  state.selection = rect(at, at);
  picker.setPointerCapture(e.pointerId);
  renderPicker();
  refresh();
});

picker.addEventListener('pointermove', (e) => {
  if (!state?.drag) return;
  const target = cellAt(e.clientX, e.clientY);
  if (target.row === null || target.col === null) return;
  const next = bestRect(state.drag.anchor, target);
  if (next && !sameLoc(next, state.selection)) {
    state.selection = next;
    renderPicker();
    refresh();
  }
});

const endDrag = () => { if (state) state.drag = null; };
picker.addEventListener('pointerup', endDrag);
picker.addEventListener('pointercancel', endDrag);

// ── Form ─────────────────────────────────────────────────────────────────────

const f = (name) => form.elements[name];

function ownConnection() {
  if (!f('ownConnection').checked) return null;
  return {
    username: f('connUser').value.trim(),
    password: f('connPassword').value,
    connectString: f('connString').value.trim(),
    credentialsFile: f('connWallet').value.trim() || null,
  };
}

function divideBy() {
  const n = Number(f('divideBy').value.replace(/,/g, '').trim());
  return Number.isFinite(n) && n > 0 ? n : null;
}

/** What validation depends on: changing any of it requires validating again. */
function fingerprint() {
  return JSON.stringify([sqlEditor().getValue().trim(), ownConnection()]);
}

function formProblem() {
  if (!f('title').value.trim()) return 'Enter a title.';
  if (divideBy() === null) return 'Divide by must be a positive number.';
  if (!state.selection) return 'Pick a location.';
  const conn = ownConnection();
  if (conn) {
    const keepsPassword = state.def?.connection?.hasPassword;
    if (!conn.username || !conn.connectString || (!conn.password && !keepsPassword)) {
      return 'Fill in username, password and connect string for the connection.';
    }
  }
  return null;
}

function setStatus(text, kind) {
  statusEl.textContent = text;
  statusEl.dataset.kind = kind || '';
}

function refresh() {
  if (!state) return;
  const timeSeries = ['LINE', 'AREA'].includes(f('chartType').value);
  f('refreshRate').disabled = f('refreshDefault').checked;
  f('windowDefault').disabled = !timeSeries;
  f('windowSize').disabled = !timeSeries || f('windowDefault').checked;
  $('#window-field').style.opacity = timeSeries ? '' : '.5';
  $('#conn-fields').disabled = !f('ownConnection').checked;
  f('divideBy').setAttribute('aria-invalid', divideBy() === null ? 'true' : 'false');

  const fp = fingerprint();
  const validated = state.validatedFp === fp;
  const problem = formProblem();

  if (state.validating) {
    setStatus('Validating…', 'busy');
  } else if (validated && state.result) {
    const warnings = warningsFor(f('chartType').value, state.result.preview);
    if (warnings.length) setStatus(`${state.result.message}. ${warnings.join(' ')}`, 'warn');
    else setStatus(state.result.message, 'ok');
  } else if (validated) {
    setStatus('SQL unchanged since it was saved.', 'ok');
  } else if (state.result && !state.result.ok && state.failedFp === fp) {
    setStatus(state.result.message, 'error');
  } else {
    setStatus(`Validate the SQL before ${state.mode === 'add' ? 'adding' : 'saving'} this module.`, '');
  }

  saveBtn.disabled = !validated || state.validating || problem !== null;
  saveBtn.title = problem || (validated ? '' : 'Validate the SQL first');
}

/** Mirrors SqlValidator.warningsFor, so changing the chart type updates advice without re-running. */
function warningsFor(chartType, rows) {
  if (!rows.length) return ['The query returned no rows, so the chart will be empty until it does.'];
  if (chartType === 'TEXT' && rows.length > 1) return [`TEXT shows only the first row; ${rows.length - 1} more will be ignored.`];
  return [];
}

function showPreview(rows) {
  const body = preview.querySelector('tbody');
  body.replaceChildren();
  for (const r of rows) {
    const tr = document.createElement('tr');
    const key = document.createElement('td');
    key.textContent = r.key ?? '';
    const value = document.createElement('td');
    value.className = 'num';
    value.textContent = formatValue(r.value);
    const colour = document.createElement('td');
    if (r.color) {
      const sw = document.createElement('span');
      sw.className = 'swatch';
      sw.style.background = r.color;
      colour.append(sw, document.createTextNode(r.color));
    }
    tr.append(key, value, colour);
    body.append(tr);
  }
  preview.hidden = rows.length === 0;
}

async function runValidation() {
  if (!state || state.validating) return;
  const fp = fingerprint();
  state.validating = true;
  preview.hidden = true;
  refresh();
  try {
    const result = await validateSql({
      sql: sqlEditor().getValue().trim(),
      chartType: f('chartType').value,
      divideBy: divideBy() ?? 1,
      connection: ownConnection(),
      moduleId: state.def?.id ?? null,
    });
    state.result = result;
    if (result.ok) {
      state.validatedFp = fp;
      state.failedFp = null;
      showPreview(result.preview);
    } else {
      state.validatedFp = null;
      state.failedFp = fp;
    }
  } catch (err) {
    state.result = { ok: false, message: err.message, preview: [], warnings: [] };
    state.validatedFp = null;
    state.failedFp = fp;
  } finally {
    state.validating = false;
    refresh();
  }
}

$('#validate-btn').addEventListener('click', runValidation);
form.addEventListener('input', refresh);
form.addEventListener('change', refresh);
dialog.querySelectorAll('[data-close]').forEach((b) => b.addEventListener('click', () => dialog.close()));

form.addEventListener('submit', async (e) => {
  e.preventDefault();
  if (saveBtn.disabled) return;
  const chartType = f('chartType').value;
  const timeSeries = ['LINE', 'AREA'].includes(chartType);
  const loc = state.selection;
  const payload = {
    title: f('title').value.trim(),
    chartType,
    processingMode: f('processingMode').value,
    refreshRate: f('refreshDefault').checked ? null : Number(f('refreshRate').value),
    windowSize: !timeSeries || f('windowDefault').checked ? null : Number(f('windowSize').value),
    divideBy: divideBy(),
    // Keep the original grid text (e.g. "R2,C1*") when the location was not changed
    grid: state.def && sameLoc(loc, state.originalLoc) ? state.def.grid : spec(loc),
    sql: sqlEditor().getValue().trim(),
    connection: ownConnection(),
  };
  saveBtn.disabled = true;
  errorEl.textContent = '';
  try {
    if (state.mode === 'add') await addModule(payload);
    else await updateModule(state.def.id, payload);
    state.saved = true;
    dialog.close();
  } catch (err) {
    errorEl.textContent = err.message;
    refresh();
  }
});

dialog.addEventListener('close', () => {
  const done = state?.resolve;
  const saved = Boolean(state?.saved);
  state = null;
  done?.(saved);
});

// ── Open ─────────────────────────────────────────────────────────────────────

/**
 * Opens the dialog.
 * @param {object} opts
 * @param {object} opts.dashboard  current dashboard (settings + modules)
 * @param {object} [opts.def]      module to edit; omit to add
 * @param {object} [opts.location] initial {row, rowEnd, colStart, colEnd} when adding
 * @returns {Promise<boolean>} true if saved
 */
export function openEditor({ dashboard, def, location }) {
  const editor = sqlEditor();
  const { blocks, taken } = occupancy(dashboard.modules, def?.id);
  const originalLoc = def ? { row: def.row, rowEnd: def.rowEnd, colStart: def.colStart, colEnd: def.colEnd } : null;

  state = {
    mode: def ? 'edit' : 'add',
    def, blocks, taken, originalLoc,
    columns: dashboard.columns,
    selection: null,
    validatedFp: null, failedFp: null, result: null, validating: false,
  };
  state.selection = [originalLoc, location].find((l) => l && fits(l)) || firstFree(taken, dashboard.columns);

  $('#editor-title').textContent = def ? `Edit QModule: ${def.title}` : 'Add QModule';
  saveBtn.textContent = def ? 'Save' : 'Add';
  errorEl.textContent = '';

  fillSelect(f('chartType'), dashboard.chartTypes);
  fillSelect(f('processingMode'), dashboard.processingModes);
  $('#refresh-default-label').textContent = `Default (${dashboard.refreshRate}s)`;
  $('#window-default-label').textContent = `Default (${dashboard.windowSize}s)`;

  f('title').value = def?.title ?? 'New QModule';
  f('chartType').value = def?.chartType ?? 'LINE';
  f('processingMode').value = def?.processingMode ?? 'NONE';
  f('refreshDefault').checked = def ? def.refreshRateOverride === null : true;
  f('refreshRate').value = def?.refreshRateOverride ?? dashboard.refreshRate;
  f('windowDefault').checked = def ? def.windowSizeOverride === null : true;
  f('windowSize').value = def?.windowSizeOverride ?? dashboard.windowSize;
  f('divideBy').value = def ? String(def.divideBy) : '1';

  const conn = def?.connection;
  f('ownConnection').checked = Boolean(conn);
  f('connUser').value = conn?.username ?? '';
  f('connPassword').value = '';
  f('connPassword').placeholder = conn?.hasPassword ? 'unchanged' : '';
  f('connString').value = conn?.connectString ?? '';
  f('connWallet').value = conn?.credentialsFile ?? '';

  editor.setValue(def?.sql ?? "SELECT 'Active Sessions', count(*)\nFROM v$session\nWHERE status = 'ACTIVE' AND type = 'USER'");
  editor.clearHistory();
  preview.hidden = true;
  // The saved SQL is accepted as-is until it is changed
  if (def) state.validatedFp = fingerprint();

  renderPicker();
  refresh();
  dialog.showModal();
  editor.refresh();
  f('title').focus();
  f('title').select();

  return new Promise((resolve) => { state.resolve = resolve; });
}

function fillSelect(select, values) {
  if (select.options.length === values.length) return;
  select.replaceChildren(...values.map((v) => new Option(v, v)));
}
