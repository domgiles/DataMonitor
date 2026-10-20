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

const spec = (loc) => (loc.colStart === loc.colEnd
  ? `R${loc.row},C${loc.colStart}`
  : `R${loc.row},C${loc.colStart}-${loc.colEnd}`);

const sameLoc = (a, b) => a && b && a.row === b.row && a.colStart === b.colStart && a.colEnd === b.colEnd;

/** Cells taken by every module except the one being edited: "row,col" -> title. */
function occupancy(modules, excludeId) {
  const taken = new Map();
  for (const m of modules) {
    if (m.id === excludeId) continue;
    for (let c = m.colStart; c <= m.colEnd; c++) taken.set(`${m.row},${c}`, m.title);
  }
  return taken;
}

function firstFree(taken, columns) {
  for (let r = 1; ; r++) {
    for (let c = 1; c <= columns; c++) if (!taken.has(`${r},${c}`)) return { row: r, colStart: c, colEnd: c };
  }
}

function fits(loc) {
  for (let c = loc.colStart; c <= loc.colEnd; c++) {
    if (c > state.columns || state.taken.has(`${loc.row},${c}`)) return false;
  }
  return true;
}

// ── Picker ───────────────────────────────────────────────────────────────────

function renderPicker() {
  const { taken, columns, selection } = state;
  let maxRow = 0;
  for (const key of taken.keys()) maxRow = Math.max(maxRow, Number(key.split(',')[0]));
  const rows = Math.max(maxRow, selection?.row ?? 0) + 1;     // always one empty row at the bottom

  picker.style.setProperty('--cols', columns);
  const frag = document.createDocumentFragment();
  frag.append(cell('axis', ''));
  for (let c = 1; c <= columns; c++) {
    const h = cell('axis', `C${c}`);
    h.dataset.colHeader = c;
    frag.append(h);
  }
  for (let r = 1; r <= rows; r++) {
    frag.append(cell('axis', `R${r}`));
    let c = 1;
    while (c <= columns) {
      const owner = taken.get(`${r},${c}`);
      if (owner !== undefined) {
        let end = c;
        while (end + 1 <= columns && taken.get(`${r},${end + 1}`) === owner) end++;
        const e = cell('cell taken', owner, r, c, end);
        e.title = owner;
        frag.append(e);
        c = end + 1;
      } else if (selection && selection.row === r && c === selection.colStart) {
        const e = cell('cell selected', spec(selection), r, c, selection.colEnd);
        e.setAttribute('aria-selected', 'true');
        frag.append(e);
        c = selection.colEnd + 1;
      } else {
        const e = cell('cell free', '', r, c, c);
        e.title = 'Free: click to place here, drag along the row to span';
        frag.append(e);
        c++;
      }
    }
  }
  picker.replaceChildren(frag);
}

function cell(className, text, row, colStart, colEnd) {
  const e = document.createElement('div');
  e.className = className;
  e.textContent = text;
  if (row) {
    e.dataset.row = row;
    e.dataset.col = colStart;
    e.style.gridRow = String(row + 1);
    e.style.gridColumn = `${colStart + 1} / ${colEnd + 2}`;
  }
  return e;
}

/** Column under the pointer, from the column headers' positions. */
function colAt(clientX) {
  for (const h of picker.querySelectorAll('[data-col-header]')) {
    const r = h.getBoundingClientRect();
    if (clientX >= r.left - 3 && clientX <= r.right + 3) return Number(h.dataset.colHeader);
  }
  return null;
}

picker.addEventListener('pointerdown', (e) => {
  const target = e.target.closest('.free, .selected');
  if (!target) return;
  const row = Number(target.dataset.row);
  const col = target.classList.contains('selected') ? (colAt(e.clientX) ?? Number(target.dataset.col)) : Number(target.dataset.col);
  state.drag = { row, anchor: col };
  state.selection = { row, colStart: col, colEnd: col };
  picker.setPointerCapture(e.pointerId);
  renderPicker();
  refresh();
});

picker.addEventListener('pointermove', (e) => {
  if (!state?.drag) return;
  const target = colAt(e.clientX);
  if (target === null) return;
  const { row, anchor } = state.drag;
  const step = target >= anchor ? 1 : -1;
  let reach = anchor;
  for (let c = anchor + step; step > 0 ? c <= target : c >= target; c += step) {
    if (state.taken.has(`${row},${c}`)) break;
    reach = c;
  }
  const next = { row, colStart: Math.min(anchor, reach), colEnd: Math.max(anchor, reach) };
  if (!sameLoc(next, state.selection)) {
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
 * @param {object} [opts.location] initial {row, colStart, colEnd} when adding
 * @returns {Promise<boolean>} true if saved
 */
export function openEditor({ dashboard, def, location }) {
  const editor = sqlEditor();
  const taken = occupancy(dashboard.modules, def?.id);
  const originalLoc = def ? { row: def.row, colStart: def.colStart, colEnd: def.colEnd } : null;

  state = {
    mode: def ? 'edit' : 'add',
    def, taken, originalLoc,
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
