// Web front end entry point: loads the dashboard, keeps it live over Server-Sent Events,
// lays modules out on the configured grid, and wires the add / edit / delete actions.

import { getDashboard, deleteModule, ApiError } from './api.js';
import { Card, themeTokens } from './charts.js';
import { openEditor } from './editor.js';

const $ = (sel) => document.querySelector(sel);
const grid = $('#grid');
const menu = $('#module-menu');

const cards = new Map();      // module id -> Card
let dashboard = null;
let tokens = null;
let menuTarget = null;        // Card the menu was opened for (null: empty space)

// ── Theme ────────────────────────────────────────────────────────────────────

const THEME_KEY = 'dm-theme';

function storedTheme() {
  try { return localStorage.getItem(THEME_KEY); } catch { return null; }
}

function applyTheme(theme, remember) {
  document.documentElement.dataset.theme = theme === 'light' ? 'light' : 'dark';
  if (remember) {
    try { localStorage.setItem(THEME_KEY, theme); } catch { /* private mode */ }
  }
  tokens = themeTokens();
  cards.forEach((c) => c.setTheme(tokens));
}

$('#theme-btn').addEventListener('click', () => {
  applyTheme(document.documentElement.dataset.theme === 'light' ? 'dark' : 'light', true);
});

// ── Toasts ───────────────────────────────────────────────────────────────────

function toast(message, kind) {
  const t = document.createElement('div');
  t.className = `toast ${kind || ''}`;
  t.textContent = message;
  $('#toasts').append(t);
  setTimeout(() => {
    t.classList.add('leaving');
    setTimeout(() => t.remove(), 300);
  }, kind === 'error' ? 7000 : 3000);
}

// ── Layout ───────────────────────────────────────────────────────────────────

/** Everything that defines a module except its data; a change means a new card. */
const signature = (m) => JSON.stringify({ ...m, history: undefined });

function applyDashboard(next) {
  const first = dashboard === null;
  dashboard = next;
  document.title = next.title;
  $('#title').textContent = next.title;
  $('#connection').textContent = next.connection ? `⬡ ${next.connection}` : '';
  if (first && !storedTheme()) applyTheme(next.theme, false);

  // Keep cards whose definition is unchanged (and their chart state); rebuild the rest
  const ids = new Set(next.modules.map((m) => m.id));
  for (const [id, card] of cards) {
    if (!ids.has(id)) { card.dispose(); cards.delete(id); }
  }
  next.modules.forEach((m) => {
    const existing = cards.get(m.id);
    if (existing && existing.signature === signature(m)) {
      existing.setHistory(m.history);   // catch up, e.g. after a reconnect
      return;
    }
    existing?.dispose();
    const card = new Card(m, next, tokens);
    card.signature = signature(m);
    cards.set(m.id, card);
  });

  layout();
}

function layout() {
  grid.style.setProperty('--columns', dashboard.columns);
  const items = [];
  const taken = new Set();
  let maxRow = 0;

  dashboard.modules.forEach((m, i) => {
    const card = cards.get(m.id);
    card.el.style.gridRow = `${m.row} / ${m.rowEnd + 1}`;
    card.el.style.gridColumn = `${m.colStart} / ${m.colEnd + 1}`;
    card.el.style.setProperty('--i', i);
    items.push(card.el);
    for (let r = m.row; r <= m.rowEnd; r++) {
      for (let c = m.colStart; c <= m.colEnd; c++) taken.add(`${r},${c}`);
    }
    maxRow = Math.max(maxRow, m.rowEnd);
  });

  // Empty cells inside the used rows become quiet "add here" targets
  for (let r = 1; r <= maxRow; r++) {
    for (let c = 1; c <= dashboard.columns; c++) {
      if (taken.has(`${r},${c}`)) continue;
      const slot = document.createElement('button');
      slot.type = 'button';
      slot.className = 'slot';
      slot.style.gridRow = String(r);
      slot.style.gridColumn = String(c);
      slot.innerHTML = '<span>+ Add QModule here</span>';
      slot.addEventListener('click', () => edit(null, { row: r, rowEnd: r, colStart: c, colEnd: c }));
      items.push(slot);
    }
  }

  if (!dashboard.modules.length) {
    const empty = document.createElement('div');
    empty.className = 'empty-state';
    const h = document.createElement('h2');
    h.textContent = 'No QModules yet';
    const p = document.createElement('p');
    p.textContent = 'Add one to start monitoring. Each QModule runs a SQL query on a schedule and charts the result.';
    const b = document.createElement('button');
    b.type = 'button';
    b.className = 'btn btn-primary';
    b.textContent = 'Add QModule';
    b.addEventListener('click', () => edit(null));
    empty.append(h, p, b);
    items.push(empty);
  }

  grid.replaceChildren(...items);
}

// ── Live updates ─────────────────────────────────────────────────────────────

function setLive(state, text) {
  const live = $('#live');
  live.dataset.state = state;
  live.querySelector('span').textContent = text;
}

function connectEvents() {
  const es = new EventSource('/api/events');
  es.addEventListener('open', () => setLive('live', 'Live'));
  es.addEventListener('error', () => setLive('offline', 'Reconnecting'));   // EventSource retries itself
  es.addEventListener('dashboard', (e) => applyDashboard(JSON.parse(e.data)));
  es.addEventListener('result', (e) => {
    const { id, sample } = JSON.parse(e.data);
    cards.get(id)?.push(sample);
  });
}

// ── Actions ──────────────────────────────────────────────────────────────────

async function edit(card, location) {
  const saved = await openEditor({ dashboard, def: card?.def, location });
  // The server pushes the new layout to every open browser; just confirm here
  if (saved) toast(card ? `Saved “${card.def.title}”` : 'QModule added');
}

async function remove(card) {
  const dlg = $('#confirm');
  const text = $('#confirm-text');
  const name = document.createElement('strong');
  name.textContent = card.def.title;
  text.replaceChildren('Delete the QModule ', name,
    '? Its query stops and it is removed from the configuration file.');
  dlg.returnValue = '';
  dlg.showModal();
  const choice = await new Promise((resolve) => dlg.addEventListener('close', () => resolve(dlg.returnValue), { once: true }));
  if (choice !== 'delete') return;
  try {
    await deleteModule(card.def.id);
    toast(`Deleted “${card.def.title}”`);
  } catch (err) {
    toast(err.message, 'error');
  }
}

// ── Context menu ─────────────────────────────────────────────────────────────

function openMenu(x, y, card) {
  menuTarget = card;
  menu.dataset.mode = card ? 'module' : 'empty';
  if (card) {
    $('#menu-heading').textContent = card.def.title;
    menu.querySelector('[data-action="table"]').textContent = card.tableView ? 'Show as chart' : 'Show as table';
    card.menuBtn.setAttribute('aria-expanded', 'true');
  }
  menu.hidden = false;
  const r = menu.getBoundingClientRect();
  menu.style.left = `${Math.min(x, innerWidth - r.width - 8)}px`;
  menu.style.top = `${Math.min(y, innerHeight - r.height - 8)}px`;
  menu.querySelector(card ? '[data-action="edit"]' : '[data-action="add"]').focus();
}

function closeMenu() {
  if (menu.hidden) return;
  menu.hidden = true;
  menuTarget?.menuBtn.setAttribute('aria-expanded', 'false');
}

grid.addEventListener('contextmenu', (e) => {
  const cardEl = e.target.closest('.card');
  e.preventDefault();
  openMenu(e.clientX, e.clientY, cardEl ? cards.get(cardEl.dataset.id) : null);
});

grid.addEventListener('click', (e) => {
  const btn = e.target.closest('.card-menu-btn');
  if (!btn) return;
  const r = btn.getBoundingClientRect();
  openMenu(r.right - 190, r.bottom + 4, cards.get(btn.closest('.card').dataset.id));
});

menu.addEventListener('click', (e) => {
  const action = e.target.closest('[data-action]')?.dataset.action;
  if (!action) return;
  const card = menuTarget;
  closeMenu();
  if (action === 'edit') edit(card);
  if (action === 'delete') remove(card);
  if (action === 'add') edit(null);
  if (action === 'table') card.toggleTable();
});

document.addEventListener('pointerdown', (e) => {
  if (!menu.contains(e.target) && !e.target.closest('.card-menu-btn')) closeMenu();
});
document.addEventListener('keydown', (e) => { if (e.key === 'Escape') closeMenu(); });
window.addEventListener('scroll', closeMenu, { passive: true });
window.addEventListener('blur', closeMenu);

$('#add-btn').addEventListener('click', () => edit(null));

// ── Start ────────────────────────────────────────────────────────────────────

async function start() {
  const saved = storedTheme();
  applyTheme(saved || document.documentElement.dataset.theme, false);
  try {
    applyDashboard(await getDashboard());
  } catch (err) {
    if (err instanceof ApiError && err.status === 401) {
      $('#gate').hidden = false;
      return;
    }
    toast(`Could not load the dashboard: ${err.message}`, 'error');
    setLive('offline', 'Offline');
  }
  connectEvents();
}

start();
