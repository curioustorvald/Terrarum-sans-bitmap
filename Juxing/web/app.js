'use strict';
/* Juxing workbench: status, drawing sheets, pixel editor, families, worklist, text preview.
   Talks to webapp.py; no dependencies. */

const CELL = 16;
const TIERS = ['everyday', 'common', 'URO rest', 'Ext. A rest'];
const SERIES = ['--series-1', '--series-2', '--series-3', '--series-4'];

// ---------------------------------------------------------------------------
// small helpers

function h(tag, attrs, ...children) {
  const el = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs || {})) {
    if (v === undefined || v === null || v === false) continue;
    if (k === 'class') el.className = v;
    else if (k === 'text') el.textContent = v;
    else if (k.startsWith('on')) el.addEventListener(k.slice(2), v);
    else if (k === 'style' && typeof v === 'object') Object.assign(el.style, v);
    else el.setAttribute(k, v === true ? '' : v);
  }
  for (const c of children.flat()) {
    if (c === null || c === undefined || c === false) continue;
    el.append(c instanceof Node ? c : document.createTextNode(String(c)));
  }
  return el;
}
const svgNS = 'http://www.w3.org/2000/svg';
function s(tag, attrs, ...children) {
  const el = document.createElementNS(svgNS, tag);
  for (const [k, v] of Object.entries(attrs || {})) if (v !== undefined && v !== null) el.setAttribute(k, v);
  for (const c of children.flat()) if (c) el.append(c instanceof Node ? c : document.createTextNode(String(c)));
  return el;
}
const token = name => getComputedStyle(document.documentElement).getPropertyValue(name).trim();
const fmt = n => n.toLocaleString('en-GB');
const pct = (a, b) => (b ? (100 * a / b) : 0);
const pctText = (a, b) => `${pct(a, b) < 10 && a ? pct(a, b).toFixed(1) : Math.round(pct(a, b))}%`;
const sizeOf = k => `${k.w}×${k.h}${k.role || ''}`;
const debounce = (fn, ms) => { let t; return (...a) => { clearTimeout(t); t = setTimeout(() => fn(...a), ms); }; };

let busyCount = 0;
function busy(d) {
  busyCount += d;
  document.getElementById('busy').hidden = busyCount <= 0;
}

async function api(path, body) {
  busy(1);
  try {
    const opts = body === undefined ? {} : {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body),
    };
    const r = await fetch(path, opts);
    const j = await r.json();
    if (!r.ok) throw new Error(j.error || r.statusText);
    return j;
  } finally {
    busy(-1);
  }
}

function toast(msg, error) {
  const t = h('div', { class: 'toast' + (error ? ' error' : ''), role: error ? 'alert' : 'status' }, msg);
  document.getElementById('toasts').append(t);
  setTimeout(() => t.remove(), error ? 8000 : 3500);
}

const tip = {
  el: null,
  show(html, x, y) {
    this.el = this.el || document.getElementById('tooltip');
    this.el.innerHTML = '';
    this.el.append(html);
    this.el.hidden = false;
    const r = this.el.getBoundingClientRect();
    let left = x + 14, top = y + 14;
    if (left + r.width > window.innerWidth - 8) left = x - r.width - 14;
    if (top + r.height > window.innerHeight - 8) top = y - r.height - 14;
    this.el.style.left = `${Math.max(4, left)}px`;
    this.el.style.top = `${Math.max(4, top)}px`;
  },
  hide() { if (this.el) this.el.hidden = true; },
};

// Bitmaps: {w, h, bits: '0101...'}
function maskAt(m, x, y) { return m.bits.charCodeAt(y * m.w + x) === 49; }

// A bitmap on its dark pixel surface. It is framed by a margin of that surface (unless
// opts.frame is false), so ink on its edge still shows against a light page.
function bitmapCanvas(m, scale, opts = {}) {
  const c = h('canvas', { class: opts.frame === false ? 'pixels' : 'pixels framed', width: m.w * scale, height: m.h * scale });
  const bg = opts.bg || token('--pix-bg');
  if (opts.frame !== false) c.style.setProperty('--frame-bg', bg);
  const g = c.getContext('2d');
  g.fillStyle = bg;
  g.fillRect(0, 0, c.width, c.height);
  for (const [x, y, w, hh] of opts.missing || []) {
    g.fillStyle = token('--pix-missing');
    g.fillRect(x * scale, y * scale, w * scale, hh * scale);
  }
  g.fillStyle = opts.ink || token('--pix-ink');
  for (let y = 0; y < m.h; y++) {
    for (let x = 0; x < m.w; x++) if (maskAt(m, x, y)) g.fillRect(x * scale, y * scale, scale, scale);
  }
  return c;
}

// Cached registry entries (refreshed when something changes)
const app = { entries: null, status: null, leaving: null };
async function getEntries(force) {
  if (force || !app.entries) app.entries = await api('/api/entries');
  return app.entries;
}
function invalidate() { app.entries = null; app.status = null; }

// ---------------------------------------------------------------------------
// routing

const views = {};
let current = { tab: null, arg: null };
let suppressHash = false;

function parseHash() {
  const [tab, ...rest] = location.hash.replace(/^#/, '').split('/');
  return { tab: views[tab] ? tab : 'status', arg: rest.length ? decodeURIComponent(rest.join('/')) : null };
}

async function route() {
  if (suppressHash) { suppressHash = false; return; }
  const next = parseHash();
  if (app.leaving && (next.tab !== current.tab || next.arg !== current.arg)) {
    if (!app.leaving()) {
      suppressHash = true;
      location.hash = `#${current.tab}${current.arg !== null ? '/' + encodeURIComponent(current.arg) : ''}`;
      return;
    }
  }
  app.leaving = null;
  app.unsaved = null;
  if (app.editorKeys) { document.removeEventListener('keydown', app.editorKeys); app.editorKeys = null; }
  current = next;
  for (const a of document.querySelectorAll('.tabs a')) a.classList.toggle('active', a.dataset.tab === next.tab);
  const root = document.getElementById('view');
  const fresh = h('div');
  root.replaceChildren(fresh);
  tip.hide();
  try {
    await views[next.tab](fresh, next.arg);
  } catch (e) {
    fresh.append(h('div', { class: 'card empty' }, `Could not load this view: ${e.message}`));
    console.error(e);
  }
}

window.addEventListener('hashchange', route);
// asks before a reload or closing the tab only if there is unsaved work
window.addEventListener('beforeunload', e => { if (app.unsaved && app.unsaved()) { e.preventDefault(); e.returnValue = ''; } });
document.getElementById('reload').addEventListener('click', async () => {
  if (app.leaving && !app.leaving()) return;
  app.leaving = null;
  await api('/api/reload', {});
  invalidate();
  toast('Reloaded from disk');
  route();
});

// ---------------------------------------------------------------------------
// Status

views.status = async root => {
  const st = await api('/api/status');
  app.status = st;
  const pb = st.per_base;
  root.append(
    h('section', { class: 'card' },
      h('div', { class: 'hero' },
        h('div', {}, h('div', { class: 'hero-number' }, fmt(st.bases)), h('div', { class: 'hero-label' }, 'base glyphlettes')),
        h('div', { class: 'hero-side' },
          stat(fmt(st.derived), 'glyphlettes derived'),
          stat(`${pb.min} · ${pb.q1} · ${pb.median} · ${pb.q3} · ${pb.max}`, 'derived per base: min · 25% · 50% · 75% · max'),
          stat(fmt(st.overrides), 'glyphlettes overridden'))),
      h('div', { class: 'summary-line' }, st.summary)),
    h('section', { class: 'card' },
      h('div', { class: 'tiles' },
        tile(st.issued, 'issued on ' + st.sheets + ' sheet' + (st.sheets === 1 ? '' : 's')),
        tile(st.drawn, 'drawn'),
        tile(st.blank, 'issued, still blank', st.blank ? '◌' : null),
        tile(st.not_issued, 'planned, not issued yet'),
        tile(st.derived_now, 'sizes derived now'),
        tile(st.declined, 'declined sizes (need an override)', st.declined ? '▲' : null),
        tile(st.generated, 'generated sizes'),
        tile(st.orphans, 'orphans (issued, no longer planned)'))),
    h('div', { class: 'grid-2' }, glyphletteStack(st), tierBars(st)),
    progressChart(st),
    coverageCard(st));
};

function stat(v, l) { return h('div', {}, h('div', { class: 'v' }, v), h('div', { class: 'l' }, l)); }
function tile(v, l, icon) {
  return h('div', { class: 'tile' }, h('div', { class: 'v' }, fmt(v)),
    h('div', { class: 'l' }, icon ? h('span', { class: 'icon', 'aria-hidden': 'true' }, icon) : null, l));
}

function glyphletteStack(st) {
  const parts = [
    { label: 'drawn', v: st.drawn, c: '--series-1' },
    { label: 'issued, blank', v: st.blank, c: '--series-2' },
    { label: 'not issued', v: st.not_issued, c: '--track' },
  ];
  const total = parts.reduce((a, p) => a + p.v, 0) || 1;
  return h('section', { class: 'card' },
    h('div', { class: 'card-head' }, h('h2', {}, 'Glyphlettes to draw'),
      h('span', { class: 'muted num' }, `${fmt(st.drawn)} of ${fmt(st.glyphlettes + st.orphans)} drawn`)),
    h('div', { class: 'stack', role: 'img', 'aria-label': parts.map(p => `${p.label} ${p.v}`).join(', ') },
      parts.filter(p => p.v > 0).map(p => h('div', {
        style: { flex: `${p.v} 0 0`, background: `var(${p.c})` },
        onmousemove: e => tip.show(h('div', {}, `${p.label}: ${fmt(p.v)} (${pctText(p.v, total)})`), e.clientX, e.clientY),
        onmouseleave: () => tip.hide(),
      }))),
    h('div', { class: 'legend' }, parts.map(p =>
      h('span', {}, h('span', { class: 'sw', style: { background: `var(${p.c})` } }), `${p.label} ${fmt(p.v)}`))),
    h('p', { class: 'muted', style: { margin: '10px 0 0', fontSize: '12.5px' } },
      'Bases and planned overrides. Derived and generated sizes are not drawn.'));
}

function tierBars(st) {
  return h('section', { class: 'card' },
    h('div', { class: 'card-head' }, h('h2', {}, 'Characters that can be assembled'),
      h('span', { class: 'muted num' }, `${fmt(st.ready)} of ${fmt(st.targets)}`)),
    h('div', { class: 'bars' }, st.tiers.flatMap(t => [
      h('div', {}, t.name),
      h('div', { class: 'track', role: 'img', 'aria-label': `${t.name}: ${t.done} of ${t.total}` },
        h('div', { class: 'fill', style: { width: `${pct(t.done, t.total)}%` } })),
      h('div', { class: 'val' }, `${fmt(t.done)} / ${fmt(t.total)} · ${pctText(t.done, t.total)}`),
    ])));
}

function progressChart(st) {
  const data = st.progress;
  const totals = st.tiers.map(t => t.total);
  const holder = h('div');
  const table = h('table', { class: 'data' },
    h('thead', {}, h('tr', {}, h('th', { class: 'n' }, 'next N'), TIERS.map(t => h('th', { class: 'n' }, t)))),
    h('tbody', {}, data.map(d => h('tr', {}, h('td', { class: 'n' }, fmt(d.n)),
      d.tiers.map((v, i) => h('td', { class: 'n' }, `${fmt(v)} (${pctText(v, totals[i])})`))))));
  // drawn at the width it gets, so text stays at its CSS size
  let lastW = 0;
  new ResizeObserver(entries => {
    const W = Math.floor(entries[0].contentRect.width);
    if (W > 0 && Math.abs(W - lastW) > 4) { lastW = W; holder.replaceChildren(drawProgress(data, totals, W)); }
  }).observe(holder);
  return h('section', { class: 'card chart' },
    h('div', { class: 'card-head' }, h('h2', {}, 'Plan: characters complete as the worklist is drawn'),
      h('span', { class: 'muted' }, 'share of each tier; issued glyphlettes count as drawn')),
    h('div', { class: 'legend', style: { marginBottom: '8px' } }, TIERS.map((t, i) =>
      h('span', {}, h('span', { class: 'sw', style: { background: `var(${SERIES[i]})` } }), t))),
    holder,
    h('details', {}, h('summary', {}, 'Show as a table'), table));
}

function drawProgress(data, totals, W) {
  const H = 260, m = { l: 44, r: 24, t: 12, b: 38 };
  const maxN = Math.max(1, data[data.length - 1].n);
  const x = n => m.l + (W - m.l - m.r) * n / maxN;
  const y = p => m.t + (H - m.t - m.b) * (1 - p / 100);
  const val = (d, i) => pct(d.tiers[i], totals[i]);
  const svg = s('svg', { viewBox: `0 0 ${W} ${H}`, width: W, height: H, role: 'img',
    'aria-label': 'Share of each tier complete after the next N glyphlettes are drawn' });
  const axis = s('g', { class: 'axis' });
  for (const p of [0, 25, 50, 75, 100]) {
    axis.append(s('line', { class: 'gridline', x1: m.l, x2: W - m.r, y1: y(p), y2: y(p) }));
    axis.append(s('text', { x: m.l - 8, y: y(p) + 4, 'text-anchor': 'end' }, `${p}%`));
  }
  const step = niceStep(maxN / Math.max(2, Math.floor((W - m.l - m.r) / 110)));
  for (let n = 0; n <= maxN; n += step) {
    axis.append(s('text', { x: x(n), y: H - m.b + 18, 'text-anchor': 'middle' }, fmt(n)));
  }
  axis.append(s('text', { x: (m.l + W - m.r) / 2, y: H - 3, 'text-anchor': 'middle' }, 'next N glyphlettes drawn'));
  svg.append(axis);
  TIERS.forEach((name, i) => {
    const pts = data.map(d => `${x(d.n).toFixed(1)},${y(val(d, i)).toFixed(1)}`).join(' ');
    svg.append(s('polyline', { points: pts, fill: 'none', stroke: `var(${SERIES[i]})`, 'stroke-width': 2,
      'stroke-linejoin': 'round', 'stroke-linecap': 'round' }));
  });
  // direct labels where the lines are furthest apart, stacked in their order there
  let at = data[0], spread = -1;
  for (const d of data) {
    const vs = TIERS.map((_, i) => val(d, i));
    const sp = Math.max(...vs) - Math.min(...vs);
    if (d.n > 0 && sp > spread) { spread = sp; at = d; }
  }
  const labels = TIERS.map((name, i) => ({ name, i, y: y(val(at, i)) })).sort((a, b) => a.y - b.y);
  for (let k = 1; k < labels.length; k++) labels[k].y = Math.max(labels[k].y, labels[k - 1].y + 14);
  for (const l of labels) {
    svg.append(s('text', { class: 'direct', x: x(at.n) + 8, y: l.y + 4 }, l.name));
  }
  // crosshair and tooltip
  const cross = s('line', { class: 'crosshair', y1: m.t, y2: H - m.b, visibility: 'hidden' });
  const dots = TIERS.map((_, i) => s('circle', { r: 4, fill: `var(${SERIES[i]})`, stroke: 'var(--surface-1)', 'stroke-width': 2, visibility: 'hidden' }));
  svg.append(cross, ...dots);
  const hit = s('rect', { x: m.l - 10, y: 0, width: W - m.l - m.r + 20, height: H - m.b, fill: 'transparent' });
  hit.addEventListener('mousemove', e => {
    const r = svg.getBoundingClientRect();
    const px = (e.clientX - r.left) * W / r.width;
    let best = data[0];
    for (const d of data) if (Math.abs(x(d.n) - px) < Math.abs(x(best.n) - px)) best = d;
    cross.setAttribute('x1', x(best.n)); cross.setAttribute('x2', x(best.n)); cross.setAttribute('visibility', 'visible');
    dots.forEach((c, i) => { c.setAttribute('cx', x(best.n)); c.setAttribute('cy', y(val(best, i))); c.setAttribute('visibility', 'visible'); });
    tip.show(h('div', {}, h('div', { class: 't-row' }, h('b', {}, `after ${fmt(best.n)} more glyphlettes`)),
      TIERS.map((name, i) => h('div', { class: 't-row' },
        h('span', { class: 'sw', style: { background: `var(${SERIES[i]})` } }),
        `${name}: ${fmt(best.tiers[i])} / ${fmt(totals[i])} (${pctText(best.tiers[i], totals[i])})`))), e.clientX, e.clientY);
  });
  hit.addEventListener('mouseleave', () => { tip.hide(); cross.setAttribute('visibility', 'hidden'); dots.forEach(c => c.setAttribute('visibility', 'hidden')); });
  svg.append(hit);
  return svg;
}

function niceStep(raw) {
  const p = Math.pow(10, Math.floor(Math.log10(Math.max(raw, 1))));
  for (const f of [1, 2, 2.5, 5, 10]) if (raw <= f * p) return f * p;
  return 10 * p;
}

function coverageCard(st) {
  const cov = st.coverage;
  const cols = cov.cols, rows = Math.ceil(cov.states.length / cols), scale = 3;
  const c = h('canvas', { class: 'pixels', width: cols * scale, height: rows * scale,
    role: 'img', 'aria-label': `Coverage map: ${st.ready} of ${st.targets} characters can be assembled` });
  const g = c.getContext('2d');
  const colour = ['--surface-2', '--track', '--series-1', '--text-muted'].map(token);
  g.fillStyle = token('--surface-1');
  g.fillRect(0, 0, c.width, c.height);
  for (let i = 0; i < cov.states.length; i++) {
    const v = cov.states.charCodeAt(i) - 48;
    g.fillStyle = colour[v];
    g.fillRect((i % cols) * scale, Math.floor(i / cols) * scale, scale, scale);
  }
  const names = ['not a Han target', 'not assembled yet', 'assembled', 'hexagram (procedural)'];
  const at = e => {
    const r = c.getBoundingClientRect();
    const cx = Math.floor((e.clientX - r.left) * c.width / r.width / scale);
    const cy = Math.floor((e.clientY - r.top) * c.height / r.height / scale);
    const i = cy * cols + cx;
    return i >= 0 && i < cov.states.length ? i : -1;
  };
  c.addEventListener('mousemove', e => {
    const i = at(e);
    if (i < 0) return tip.hide();
    const cp = cov.first + i;
    tip.show(h('div', {}, h('span', { style: { fontSize: '20px', marginRight: '8px' } }, String.fromCodePoint(cp)),
      `U+${cp.toString(16).toUpperCase()} · ${names[cov.states.charCodeAt(i) - 48]}`), e.clientX, e.clientY);
  });
  c.addEventListener('mouseleave', () => tip.hide());
  c.addEventListener('click', e => {
    const i = at(e);
    if (i >= 0) location.hash = `#text/${encodeURIComponent(String.fromCodePoint(cov.first + i))}`;
  });
  c.style.cursor = 'pointer';
  return h('section', { class: 'card' },
    h('div', { class: 'card-head' }, h('h2', {}, 'Coverage'),
      h('span', { class: 'muted' }, 'U+3400–U+9FFF, 256 code points a row; click one to inspect it')),
    h('div', { class: 'legend', style: { marginBottom: '8px' } }, [1, 2, 3].map(v =>
      h('span', {}, h('span', { class: 'sw', style: { background: colour[v] } }), names[v]))),
    h('div', { class: 'coverage-wrap' }, c));
}

// ---------------------------------------------------------------------------
// Sheets

views.sheets = async (root, arg) => {
  const data = await getEntries(true);
  if (!data.sheets) {
    root.append(h('div', { class: 'card empty' }, 'Nothing is issued yet. Issue glyphlettes from the ',
      h('a', { href: '#worklist' }, 'Worklist'), '.'));
    return;
  }
  const n = Math.min(Math.max(0, parseInt(arg || '0', 10) || 0), data.sheets - 1);
  let scale = parseInt(localStorageGet('sheetScale') || '2', 10);
  const onSheet = data.entries.filter(e => e.sheet === n);
  const blank = onSheet.filter(e => !e.drawn).length;
  const canvas = h('canvas', { class: 'pixels' });
  const img = new Image();
  const draw = () => {
    const S = CELL * data.cols;
    canvas.width = S * scale; canvas.height = Math.ceil(data.per_sheet / data.cols) * CELL * scale;
    const g = canvas.getContext('2d');
    g.imageSmoothingEnabled = false;
    g.fillStyle = token('--pix-bg');
    g.fillRect(0, 0, canvas.width, canvas.height);
    if (img.complete && img.naturalWidth) g.drawImage(img, 0, 0, img.naturalWidth * scale, img.naturalHeight * scale);
    g.strokeStyle = 'rgba(255,255,255,0.06)';
    g.lineWidth = 1;
    for (let i = 0; i <= data.cols; i++) {
      g.beginPath(); g.moveTo(i * CELL * scale + .5, 0); g.lineTo(i * CELL * scale + .5, canvas.height); g.stroke();
      g.beginPath(); g.moveTo(0, i * CELL * scale + .5); g.lineTo(canvas.width, i * CELL * scale + .5); g.stroke();
    }
    g.lineWidth = 2;
    for (const e of onSheet) {
      const col = !e.drawn ? '--state-blank' : e.kind === 'override' ? '--state-override' : e.kind === 'orphan' ? '--text-muted' : null;
      if (!col) continue;
      g.strokeStyle = token(col);
      g.strokeRect(e.col * CELL * scale + 1, e.row * CELL * scale + 1, CELL * scale - 2, CELL * scale - 2);
    }
  };
  img.onload = draw;
  img.src = `/sheet/${n}.png?t=${Date.now()}`;
  const entryAt = ev => {
    const r = canvas.getBoundingClientRect();
    const col = Math.floor((ev.clientX - r.left) * canvas.width / r.width / (CELL * scale));
    const row = Math.floor((ev.clientY - r.top) * canvas.height / r.height / (CELL * scale));
    return onSheet.find(e => e.col === col && e.row === row);
  };
  canvas.addEventListener('mousemove', ev => {
    const e = entryAt(ev);
    if (!e) return tip.hide();
    tip.show(h('div', {},
      h('div', { class: 't-row' }, h('span', { style: { fontSize: '20px' } }, e.comp), h('b', {}, sizeOf(e)), `#${e.id}`),
      h('div', {}, `${e.kind} · ${e.drawn ? 'drawn' : 'blank'}${e.sizes ? ` · serves ${e.sizes} more size${e.sizes === 1 ? '' : 's'}` : ''}`),
      e.exemplar ? h('div', { class: 'muted' }, `shown in ${e.exemplar}`) : null), ev.clientX, ev.clientY);
  });
  canvas.addEventListener('mouseleave', () => tip.hide());
  canvas.addEventListener('click', ev => { const e = entryAt(ev); if (e) location.hash = `#edit/${e.id}`; });
  const zoom = z => h('button', { class: 'small' + (scale === z ? ' on' : ''), onclick: () => {
    scale = z; localStorageSet('sheetScale', String(z)); draw();
    for (const b of zoomRow.querySelectorAll('button')) b.classList.toggle('on', b.textContent === `${z}×`);
  } }, `${z}×`);
  const zoomRow = h('span', { class: 'row' }, [1, 2, 3, 4].map(zoom));
  root.append(h('section', { class: 'card' },
    h('div', { class: 'card-head' },
      h('h2', {}, `Sheet ${String(n).padStart(2, '0')}`),
      h('span', { class: 'muted num' }, `${onSheet.length} glyphlettes, ${blank} blank`),
      h('span', { class: 'spacer' }),
      h('span', { class: 'row' }, Array.from({ length: data.sheets }, (_, i) =>
        h('a', { href: `#sheets/${i}`, class: 'pill', style: i === n ? { fontWeight: 700, color: 'var(--text-primary)' } : null },
          String(i).padStart(2, '0')))),
      zoomRow),
    h('div', { class: 'legend', style: { marginBottom: '10px' } },
      h('span', {}, h('span', { class: 'sw', style: { background: 'var(--state-blank)' } }), 'blank (not drawn yet)'),
      h('span', {}, h('span', { class: 'sw', style: { background: 'var(--state-override)' } }), 'override'),
      h('span', {}, h('span', { class: 'sw', style: { background: 'var(--text-muted)' } }), 'orphan'),
      h('span', {}, 'click a cell to edit it')),
    h('div', { class: 'sheet-wrap' }, canvas)));
};

function localStorageGet(k) { try { return localStorage.getItem('juxing.' + k); } catch (e) { return null; } }
function localStorageSet(k, v) { try { localStorage.setItem('juxing.' + k, v); } catch (e) { /* private mode */ } }

// ---------------------------------------------------------------------------
// Editor

views.edit = async (root, arg) => {
  const data = await getEntries(true);
  if (!data.entries.length) {
    root.append(h('div', { class: 'card empty' }, 'Nothing is issued yet. Issue glyphlettes from the ',
      h('a', { href: '#worklist' }, 'Worklist'), ', then draw them here.'));
    return;
  }
  let cell;
  if (arg !== null && !/^\d+$/.test(arg)) {
    // a size by its key: its cell if it is issued, else a draft to override it with
    cell = await api(`/api/draft?key=${encodeURIComponent(arg)}`);
    if (cell.id !== null) { history.replaceState(null, '', `#edit/${cell.id}`); current.arg = String(cell.id); }
  } else {
    let id = arg !== null ? parseInt(arg, 10) : (data.entries.find(e => !e.drawn) || data.entries[0]).id;
    if (!(id >= 0 && id < data.entries.length)) id = 0;
    if (arg === null) { history.replaceState(null, '', `#edit/${id}`); current.arg = String(id); }
    cell = await api(`/api/cell/${id}`);
  }
  const ed = new Editor(cell, data);
  root.append(ed.dom);
  ed.start();
  app.leaving = () => !ed.dirty || confirm('Discard the unsaved changes to this glyphlette?');
  app.unsaved = () => ed.dirty;
};

class Editor {
  constructor(cell, data) {
    this.cell = cell;
    this.data = data;
    this.ink = Uint8Array.from(cell.ink.bits, ch => ch === '1' ? 1 : 0);
    this.saved = this.ink.slice();
    this.undo = []; this.redo = [];
    this.tool = 'pencil';
    this.guides = localStorageGet('guides') !== '0';
    this.gridOn = localStorageGet('grid') !== '0';
    this.hover = null;
    this.stroke = null;
    this.sel = null;       // selected rectangle {x, y, w, h}, cell coordinates (may reach outside the cell)
    this.float = null;     // ink lifted out of it, moving with it: {x, y, w, h, bits}
    this.drag = null;      // selecting or moving with the pointer
    this.previewSeq = 0;
    this.px = Math.max(14, Math.min(34, Math.floor((Math.min(window.innerWidth, 1480) - 80) / 16)));
    if (window.innerWidth > 1150) this.px = Math.min(this.px, 32);
    this.build();
  }

  get dirty() { const ink = this.composite(); return ink.some((v, i) => v !== this.saved[i]); }

  build() {
    const c = this.cell;
    this.canvas = h('canvas', { class: 'pixel-editor pixels', width: CELL * this.px, height: CELL * this.px,
      'aria-label': `Pixel editor for ${c.key}` });
    this.dirtyEl = h('span', { class: 'dirty' });
    this.saveBtn = h('button', { class: 'primary', onclick: () => this.save(), title: 'Ctrl+S' }, 'Save');
    const toolBtn = (tool, label, key) => h('button', { class: 'small', 'data-tool': tool, title: `${label} (${key})`,
      onclick: () => this.setTool(tool) }, label);
    this.toolbar = h('div', { class: 'toolbar' },
      toolBtn('pencil', 'Pencil', 'P'), toolBtn('eraser', 'Eraser', 'E'), toolBtn('line', 'Line', 'L'),
      toolBtn('select', 'Select', 'M'),
      h('span', { class: 'sep' }),
      h('button', { class: 'small', title: 'Undo (Ctrl+Z)', onclick: () => this.doUndo() }, 'Undo'),
      h('button', { class: 'small', title: 'Redo (Ctrl+Shift+Z)', onclick: () => this.doRedo() }, 'Redo'),
      h('button', { class: 'small', title: 'Clear all ink', onclick: () => { this.settle(); this.snapshot(); this.ink.fill(0); this.changed(); } }, 'Clear'),
      h('span', { class: 'sep' }),
      this.guideBtn = h('button', { class: 'small', title: 'Guides (G)', onclick: () => this.toggle('guides') }, 'Guides'),
      this.gridBtn = h('button', { class: 'small', title: 'Grid (#)', onclick: () => this.toggle('gridOn') }, 'Grid'));
    const found = this.data.entries.findIndex(e => e.id === c.id);
    const i = this.idx = c.draft ? this.data.entries.length : found;
    const nextBlank = this.data.entries.slice(i + 1).concat(this.data.entries.slice(0, i)).find(e => !e.drawn);
    const nav = h('div', { class: 'row' },
      h('button', { class: 'small', disabled: i <= 0, title: '[', onclick: () => this.go(this.data.entries[i - 1]) }, '◀ Prev'),
      h('button', { class: 'small', disabled: i >= this.data.entries.length - 1, title: ']', onclick: () => this.go(this.data.entries[i + 1]) }, 'Next ▶'),
      h('button', { class: 'small', disabled: !nextBlank, title: 'N', onclick: () => this.go(nextBlank) }, 'Next blank'));
    this.nextBlank = nextBlank;
    this.kindEl = h('span');
    this.renderKind();
    const info = h('section', { class: 'card info' },
      h('div', { class: 'big-comp' }, c.comp),
      h('div', { class: 'row' }, h('b', {}, c.key), this.kindEl),
      h('dl', {},
        h('dt', {}, 'cell'), h('dd', {}, c.draft ? `not issued yet (it becomes #${c.would_be} when saved)`
          : `#${c.id} · sheet ${String(c.sheet).padStart(2, '0')}, row ${c.row}, col ${c.col}`),
        h('dt', {}, 'box'), h('dd', {}, `${c.w}×${c.h} at (${c.bx}, ${c.by})${c.role ? ` · frame ${c.role}` : ''}`),
        h('dt', {}, 'shown in'), h('dd', {}, c.exemplar || '—'),
        h('dt', {}, 'serves'), h('dd', {}, c.sizes ? `${c.sizes} derived size${c.sizes === 1 ? '' : 's'}` : 'its own size only'),
        c.note ? h('dt', {}, 'note') : null, c.note ? h('dd', {}, c.note) : null),
      nav,
      h('h3', { style: { marginTop: '14px' } }, `Used in ${fmt(c.user_count)} character${c.user_count === 1 ? '' : 's'}`),
      h('div', { class: 'users' }, c.users.map(ch => h('a', { href: `#text/${encodeURIComponent(ch)}`, title: 'inspect' }, ch))),
      h('div', { class: 'keys' },
        h('div', {}, h('kbd', {}, 'left'), ' draw · ', h('kbd', {}, 'right'), ' erase · ', h('kbd', {}, 'shift'), '+click line'),
        h('div', {}, h('kbd', {}, '←↑→↓'), ' move the ink · ', h('kbd', {}, 'Ctrl+Z'), ' undo · ', h('kbd', {}, 'Ctrl+S'), ' save'),
        h('div', {}, h('kbd', {}, 'M'), ' select, then drag or ', h('kbd', {}, '←↑→↓'), ' to move it (its blank pixels don\'t erase) · ',
          h('kbd', {}, 'Ctrl'), '/', h('kbd', {}, 'Alt'), '+drag copies · ', h('kbd', {}, 'Del'), ' clears · ', h('kbd', {}, 'Enter'), ' drops'),
        h('div', {}, h('kbd', {}, '['), h('kbd', {}, ']'), ' prev/next · ', h('kbd', {}, 'N'), ' next blank · ', h('kbd', {}, 'G'), ' guides')));
    // guide colours are translucent over the dark pixel surface, so their swatches are too
    const sw = v => h('span', { class: 'sw', style: { background: `linear-gradient(var(${v}), var(${v})), var(--pix-bg)` } });
    const legend = h('div', { class: 'legend' },
      h('span', {}, sw('--pix-box'), 'box'),
      c.keep_empty ? h('span', {}, sw('--pix-keep'), 'keep empty') : null,
      h('span', {}, sw('--pix-context'), 'not drawn yet'),
      h('span', {}, sw('--pix-context-ink'), 'drawn context'),
      h('span', { class: 'muted' }, 'dashed: the 15×15 body'));
    this.noticeEl = h('div', { class: 'notice', role: 'status' });
    this.renderNotice();
    const canvasCard = h('section', { class: 'card canvas-card' },
      this.noticeEl,
      h('div', { class: 'row', style: { width: '100%' } }, this.saveBtn,
        h('button', { onclick: () => this.revert() }, 'Revert'), this.dirtyEl),
      this.toolbar, this.canvas, legend);
    this.sizesEl = h('div', { class: 'thumbs' });
    this.charsEl = h('div', { class: 'thumbs' });
    this.selfEl = h('div', { class: 'row', style: { alignItems: 'flex-end', gap: '14px' } });
    const preview = h('section', { class: 'card' },
      h('div', { class: 'card-head' }, h('h2', {}, 'Live preview'), h('span', { class: 'muted' }, 'unsaved ink included')),
      this.selfEl,
      h('h3', { style: { margin: '14px 0 8px' } }, 'Sizes of this family'),
      this.sizesEl,
      h('h3', { style: { margin: '14px 0 8px' } }, 'In characters'),
      this.charsEl);
    this.dom = h('div', { class: 'editor' }, info, canvasCard, preview);
  }

  renderKind() {
    const c = this.cell;
    this.kindEl.replaceChildren(h('span', { class: `pill ${c.kind}` }, c.kind),
      h('span', { class: `pill ${c.drawn ? 'drawn' : 'blank'}` }, c.draft ? 'not issued' : c.drawn ? 'drawn' : 'blank'));
  }

  // One line over the canvas saying what this drawing does: an override replaces the size
  // derived from the family's base, and a draft is issued when it is first saved.
  renderNotice() {
    const c = this.cell;
    let main = null, detail = '';
    if (c.draft) {
      main = c.kind === 'override' ? 'You are overriding this size' : `You are drawing a new base for ${c.comp}`;
      const from = c.derived ? `the resizer's version (${c.derived.state === 'declined' ? 'declined' : `cost ${c.derived.cost}`}, from ${c.derived.key})`
        : 'an empty cell';
      detail = `. It starts from ${from} and is issued to the sheets when you save.`;
    } else if (c.kind === 'override') {
      main = 'You are overriding this size';
      detail = c.base ? `: your drawing replaces the version derived from the family's base, ${c.base}.` : '.';
    } else if (c.kind === 'orphan') {
      main = 'This cell is an orphan';
      detail = `: no character's layout uses ${c.key} any more. ` + (c.drawn
        ? 'Its drawing still serves as a source for resizing its family. '
        : 'Retiring it frees the cell for the next glyphlette issued. ');
    }
    this.noticeEl.hidden = !main;
    this.noticeEl.replaceChildren(main ? h('b', {}, main) : '', detail, c.kind === 'orphan' ? this.retireButton() : '');
  }

  // Retiring keeps the cell's number (nothing moves) and gives the cell to the next glyphlette
  // issued. A drawn cell takes a second click, as its drawing is erased.
  retireButton() {
    const c = this.cell;
    let armed = !c.drawn;
    const b = h('button', { class: 'small', onclick: async () => {
      if (!armed) { armed = true; b.textContent = 'Click again to erase the drawing'; b.classList.add('danger'); return; }
      try {
        await api('/api/retire', { id: c.id });
        invalidate();
        toast(`Retired #${c.id} (${c.key}); the next glyphlette issued takes the cell`);
        app.leaving = null; app.unsaved = null;
        const next = this.data.entries.find(e => e.id > c.id) || this.data.entries.find(e => e.id !== c.id);
        location.hash = next ? `#edit/${next.id}` : '#sheets';
      } catch (err) { toast(`Could not retire: ${err.message}`, true); }
    } }, c.drawn ? 'Retire and erase this drawing' : 'Retire this cell');
    return b;
  }

  start() {
    this.syncButtons();
    this.draw();
    this.bind();
    this.preview();
  }

  go(e) { if (e) location.hash = `#edit/${e.id}`; }

  setTool(t) { if (t !== this.tool) this.commit(); this.tool = t; this.stroke = null; this.syncButtons(); this.draw(); }
  toggle(k) {
    this[k] = !this[k];
    localStorageSet(k === 'guides' ? 'guides' : 'grid', this[k] ? '1' : '0');
    this.syncButtons(); this.draw();
  }
  syncButtons() {
    for (const b of this.toolbar.querySelectorAll('[data-tool]')) b.classList.toggle('on', b.dataset.tool === this.tool);
    this.guideBtn.classList.toggle('on', this.guides);
    this.gridBtn.classList.toggle('on', this.gridOn);
    const d = this.dirty;
    this.dirtyEl.textContent = d ? '● unsaved' : '';
    this.saveBtn.disabled = !d;
  }

  snapshot() { this.undo.push(this.ink.slice()); if (this.undo.length > 200) this.undo.shift(); this.redo = []; }
  doUndo() { this.settle(); if (!this.undo.length) return this.changed(); this.redo.push(this.ink.slice()); this.ink = this.undo.pop(); this.changed(); }
  doRedo() { this.settle(); if (!this.redo.length) return this.changed(); this.undo.push(this.ink.slice()); this.ink = this.redo.pop(); this.changed(); }
  changed() { this.syncButtons(); this.draw(); this.preview(); }

  shift(dx, dy) {
    this.snapshot();
    const out = new Uint8Array(CELL * CELL);
    for (let y = 0; y < CELL; y++) for (let x = 0; x < CELL; x++) {
      const nx = x + dx, ny = y + dy;
      if (this.ink[y * CELL + x] && nx >= 0 && ny >= 0 && nx < CELL && ny < CELL) out[ny * CELL + nx] = 1;
    }
    this.ink = out;
    this.changed();
  }

  // -- selection (the marquee tool) --------------------------------------------
  // Dragging a selection lifts the ink inside it into a floating layer that moves with it.
  // The layer is porous: its blank pixels are holes, so it shows the ink under it and, when
  // dropped, only adds ink, never erasing what it lands on. The
  // layer is dropped by clicking elsewhere, Enter or Escape, another tool, or saving.
  // Ctrl+drag (or Alt+drag) lifts a copy, leaving the original in place.

  composite() {
    const f = this.float;
    if (!f) return this.ink;
    const out = this.ink.slice();
    for (let y = 0; y < f.h; y++) for (let x = 0; x < f.w; x++) {
      const cx = f.x + x, cy = f.y + y;
      if (f.bits[y * f.w + x] && cx >= 0 && cy >= 0 && cx < CELL && cy < CELL) out[cy * CELL + cx] = 1;
    }
    return out;
  }

  inSel(at) {
    const r = this.sel;
    return !!(r && at && at[0] >= r.x && at[1] >= r.y && at[0] < r.x + r.w && at[1] < r.y + r.h);
  }

  lift(copy) {
    if (this.float || !this.sel) return;
    this.snapshot();
    const r = this.sel, bits = new Uint8Array(r.w * r.h);
    for (let y = 0; y < r.h; y++) for (let x = 0; x < r.w; x++) {
      const cx = r.x + x, cy = r.y + y;
      if (cx < 0 || cy < 0 || cx >= CELL || cy >= CELL) continue;
      if (this.ink[cy * CELL + cx]) {
        bits[y * r.w + x] = 1;
        if (!copy) this.ink[cy * CELL + cx] = 0;
      }
    }
    this.float = { x: r.x, y: r.y, w: r.w, h: r.h, bits };
  }

  // drop the floating ink where it is (ink that left the cell is lost) and forget the selection
  settle() {
    if (this.float) this.ink = this.composite();
    this.float = null; this.sel = null; this.drag = null;
  }

  commit() { if (this.sel || this.float) { this.settle(); this.changed(); } }

  moveSel(dx, dy) {
    this.lift(false);
    const f = this.float;
    f.x += dx; f.y += dy;
    this.sel = { x: f.x, y: f.y, w: f.w, h: f.h };
    this.changed();
  }

  clearSel() {
    if (this.float) { this.float = null; this.sel = null; this.changed(); return; }   // the lifted ink is gone
    if (!this.sel) return;
    this.snapshot();
    const r = this.sel;
    for (let y = Math.max(0, r.y); y < Math.min(CELL, r.y + r.h); y++)
      for (let x = Math.max(0, r.x); x < Math.min(CELL, r.x + r.w); x++) this.ink[y * CELL + x] = 0;
    this.sel = null;
    this.changed();
  }

  selectAll() { this.setTool('select'); this.commit(); this.sel = { x: 0, y: 0, w: CELL, h: CELL }; this.draw(); }

  selectDown(e, at) {
    if (this.inSel(at)) {
      this.lift(e.altKey || e.ctrlKey);        // Ctrl as well: desktops often take Alt+drag
      this.drag = { mode: 'move', from: at, x0: this.float.x, y0: this.float.y };
    } else {
      this.commit();
      const c = clampCell(at);
      this.drag = { mode: 'select', from: c, moved: false };
      this.sel = { x: c[0], y: c[1], w: 1, h: 1 };
    }
    this.draw();
  }

  selectMove(at) {
    const d = this.drag;
    if (d.mode === 'select') {
      const c = clampCell(at);
      if (c[0] !== d.from[0] || c[1] !== d.from[1]) d.moved = true;
      const x = Math.min(c[0], d.from[0]), y = Math.min(c[1], d.from[1]);
      this.sel = { x, y, w: Math.abs(c[0] - d.from[0]) + 1, h: Math.abs(c[1] - d.from[1]) + 1 };
    } else {
      const f = this.float;
      f.x = d.x0 + at[0] - d.from[0];
      f.y = d.y0 + at[1] - d.from[1];
      this.sel = { x: f.x, y: f.y, w: f.w, h: f.h };
    }
    this.draw();
  }

  selectUp() {
    const d = this.drag;
    this.drag = null;
    if (!d) return;
    if (d.mode === 'select' && !d.moved) this.sel = null;     // a click without a drag deselects
    this.changed();
  }

  // -- drawing ---------------------------------------------------------------

  draw() {
    const g = this.canvas.getContext('2d');
    const p = this.px, c = this.cell;
    g.fillStyle = token('--pix-bg');
    g.fillRect(0, 0, CELL * p, CELL * p);
    if (this.guides) {
      g.fillStyle = token('--pix-context');
      for (const part of c.context) {
        const [x, y, w, hh] = part.rect;
        g.fillRect(x * p, y * p, w * p, hh * p);
        if (part.hole) {
          g.fillStyle = token('--pix-bg');
          g.fillRect(part.hole[0] * p, part.hole[1] * p, part.hole[2] * p, part.hole[3] * p);
          g.fillStyle = token('--pix-context');
        }
      }
      g.fillStyle = token('--pix-box');
      g.fillRect(c.box[0] * p, c.box[1] * p, c.box[2] * p, c.box[3] * p);
      if (c.keep_empty) {
        g.fillStyle = token('--pix-keep');
        g.fillRect(c.keep_empty[0] * p, c.keep_empty[1] * p, c.keep_empty[2] * p, c.keep_empty[3] * p);
      }
      g.fillStyle = token('--pix-context-ink');
      for (let y = 0; y < CELL; y++) for (let x = 0; x < CELL; x++) if (maskAt(c.context_ink, x, y)) g.fillRect(x * p, y * p, p, p);
    }
    if (this.gridOn) {
      g.strokeStyle = token('--pix-grid');
      g.lineWidth = 1;
      g.beginPath();
      for (let i = 1; i < CELL; i++) { g.moveTo(i * p + .5, 0); g.lineTo(i * p + .5, CELL * p); g.moveTo(0, i * p + .5); g.lineTo(CELL * p, i * p + .5); }
      g.stroke();
    }
    // the body: 15x15 at (0, 1); column 15 and row 0 are spacing
    g.setLineDash([4, 4]);
    g.strokeStyle = 'rgba(255,255,255,0.35)';
    g.strokeRect(0.5, p + .5, 15 * p - 1, 15 * p - 1);
    g.setLineDash([]);
    g.fillStyle = token('--pix-ink');
    const inset = this.gridOn ? 1 : 0;
    for (let y = 0; y < CELL; y++) for (let x = 0; x < CELL; x++) {
      if (this.ink[y * CELL + x]) g.fillRect(x * p + inset, y * p + inset, p - inset, p - inset);
    }
    const f = this.float;
    if (f) {
      g.fillStyle = token('--pix-float');
      for (let y = 0; y < f.h; y++) for (let x = 0; x < f.w; x++) {
        const cx = f.x + x, cy = f.y + y;
        if (f.bits[y * f.w + x] && cx >= 0 && cy >= 0 && cx < CELL && cy < CELL) {
          g.fillRect(cx * p + inset, cy * p + inset, p - inset, p - inset);
        }
      }
    }
    if (this.sel) {
      const r = this.sel;
      g.save();
      g.lineWidth = 2;
      g.strokeStyle = 'rgba(0,0,0,0.8)';
      g.strokeRect(r.x * p + 1, r.y * p + 1, r.w * p - 2, r.h * p - 2);
      g.setLineDash([5, 4]);
      g.strokeStyle = token('--accent');
      g.strokeRect(r.x * p + 1, r.y * p + 1, r.w * p - 2, r.h * p - 2);
      g.restore();
    }
    // line preview
    if (this.stroke && this.stroke.line && this.hover) {
      g.fillStyle = this.stroke.value ? 'rgba(255,255,255,0.6)' : 'rgba(208,59,59,0.7)';
      for (const [x, y] of lineCells(this.stroke.from, this.hover)) g.fillRect(x * p + 2, y * p + 2, p - 4, p - 4);
    }
    if (this.hover) {
      g.strokeStyle = token('--accent');
      g.lineWidth = 2;
      g.strokeRect(this.hover[0] * p + 1, this.hover[1] * p + 1, p - 2, p - 2);
    }
  }

  cellAt(ev) {
    const [x, y] = this.cellAtFree(ev);
    return x >= 0 && y >= 0 && x < CELL && y < CELL ? [x, y] : null;
  }

  // the cell under the pointer, outside the canvas too (a selection may be dragged past its edge)
  cellAtFree(ev) {
    const r = this.canvas.getBoundingClientRect();
    return [Math.floor((ev.clientX - r.left) * CELL / r.width), Math.floor((ev.clientY - r.top) * CELL / r.height)];
  }

  paint(cells, v) {
    let any = false;
    for (const [x, y] of cells) {
      const i = y * CELL + x;
      if (this.ink[i] !== v) { this.ink[i] = v; any = true; }
    }
    return any;
  }

  bind() {
    const cv = this.canvas;
    cv.addEventListener('contextmenu', e => e.preventDefault());
    cv.addEventListener('pointerdown', e => {
      if (this.tool === 'select') {
        if (e.button !== 0) return;
        cv.setPointerCapture(e.pointerId);
        this.selectDown(e, this.cellAtFree(e));
        return;
      }
      const at = this.cellAt(e);
      if (!at) return;
      cv.setPointerCapture(e.pointerId);
      const value = e.button === 2 || this.tool === 'eraser' ? 0 : 1;
      this.snapshot();
      if (this.tool === 'line' || (e.shiftKey && this.last)) {
        this.stroke = { line: true, from: e.shiftKey && this.last ? this.last : at, value };
        if (e.shiftKey && this.last) { this.paint(lineCells(this.last, at), value); this.stroke = null; this.last = at; this.changed(); return; }
      } else {
        this.stroke = { line: false, prev: at, value };
        this.paint([at], value);
      }
      this.hover = at;
      this.draw();
    });
    cv.addEventListener('pointermove', e => {
      const at = this.cellAt(e);
      this.hover = at;
      if (this.tool === 'select') {
        cv.style.cursor = this.drag && this.drag.mode === 'move' || (!this.drag && this.inSel(at)) ? 'move' : 'crosshair';
        if (this.drag) this.selectMove(this.cellAtFree(e)); else this.draw();
        return;
      }
      cv.style.cursor = '';
      if (this.stroke && !this.stroke.line && at) {
        this.paint(lineCells(this.stroke.prev, at), this.stroke.value);
        this.stroke.prev = at;
      }
      this.draw();
    });
    const end = e => {
      if (this.tool === 'select') { this.selectUp(); return; }
      if (!this.stroke) return;
      const at = this.cellAt(e) || this.hover;
      if (this.stroke.line && at) this.paint(lineCells(this.stroke.from, at), this.stroke.value);
      this.last = at || this.last;
      this.stroke = null;
      if (this.undo.length && this.undo[this.undo.length - 1].every((v, i) => v === this.ink[i])) this.undo.pop();
      this.changed();
    };
    cv.addEventListener('pointerup', end);
    cv.addEventListener('pointercancel', end);
    cv.addEventListener('pointerleave', () => { if (!this.stroke) { this.hover = null; this.draw(); } });

    this.keyHandler = e => {
      if (e.target.matches('input, textarea, select')) return;
      const mod = e.ctrlKey || e.metaKey;
      if (mod && e.key.toLowerCase() === 's') { e.preventDefault(); this.save(); return; }
      if (mod && e.key.toLowerCase() === 'z') { e.preventDefault(); e.shiftKey ? this.doRedo() : this.doUndo(); return; }
      if (mod && e.key.toLowerCase() === 'y') { e.preventDefault(); this.doRedo(); return; }
      if (mod && e.key.toLowerCase() === 'a') { e.preventDefault(); this.selectAll(); return; }
      if (mod || e.altKey) return;
      const k = e.key;
      const moves = { ArrowLeft: [-1, 0], ArrowRight: [1, 0], ArrowUp: [0, -1], ArrowDown: [0, 1] };
      if (moves[k]) { e.preventDefault(); this.sel ? this.moveSel(...moves[k]) : this.shift(...moves[k]); }
      else if (k === 'Enter' && this.sel) { e.preventDefault(); this.commit(); }
      else if ((k === 'Delete' || k === 'Backspace') && this.sel) { e.preventDefault(); this.clearSel(); }
      else if (k === 'm') this.setTool('select');
      else if (k === 'p' || k === 'b') this.setTool('pencil');
      else if (k === 'e') this.setTool('eraser');
      else if (k === 'l') this.setTool('line');
      else if (k === 'g') this.toggle('guides');
      else if (k === '#') this.toggle('gridOn');
      else if (k === '[') this.go(this.data.entries[this.idx - 1]);
      else if (k === ']') this.go(this.data.entries[this.idx + 1]);
      else if (k === 'n') this.go(this.nextBlank);
      else if (k === 'Escape' && this.stroke) { this.stroke = null; this.draw(); }
      else if (k === 'Escape' && this.sel) this.commit();
    };
    if (app.editorKeys) document.removeEventListener('keydown', app.editorKeys);
    app.editorKeys = this.keyHandler;
    document.addEventListener('keydown', this.keyHandler);
  }

  bitsString() { return Array.from(this.composite(), v => (v ? '1' : '0')).join(''); }

  // -- server ----------------------------------------------------------------

  preview = debounce(async () => {
    const seq = ++this.previewSeq;
    let r;
    try {
      r = await api('/api/preview', { id: this.cell.id, key: this.cell.draft ? this.cell.key : undefined, ink: this.bitsString() });
    } catch (e) {
      toast(`Preview failed: ${e.message}`, true);
      return;
    }
    if (seq !== this.previewSeq) return;
    const self = { w: CELL, h: CELL, bits: this.bitsString() };
    this.selfEl.replaceChildren(
      h('div', { class: 'thumb' }, bitmapCanvas(self, 1), h('span', {}, '1×')),
      h('div', { class: 'thumb' }, bitmapCanvas(self, 2), h('span', {}, '2×')),
      h('div', { class: 'thumb' }, bitmapCanvas(self, 3), h('span', {}, '3×')));
    this.sizesEl.replaceChildren(...(r.sizes.length ? r.sizes.map(sz => this.sizeThumb(sz)) : [h('span', { class: 'muted' }, 'no other sizes')]));
    this.charsEl.replaceChildren(...(r.chars.length ? r.chars.map(ch => {
      const cv = bitmapCanvas(ch.cell, 3, { missing: ch.missing });
      return h('a', { class: 'thumb', href: `#text/${encodeURIComponent(ch.ch)}`, style: { textDecoration: 'none' },
        title: ch.missing.length ? `${ch.ch}: grey parts are not drawn yet` : ch.ch }, cv, h('span', {}, ch.ch));
    }) : [h('span', { class: 'muted' }, 'no characters use it')]));
  }, 180);

  sizeThumb(sz) {
    const cap = sz.state === 'drawn' ? (sz.current ? 'this drawing' : 'drawn')
      : sz.state === 'declined' ? 'declined' : sz.state === 'none' ? 'cannot map'
        : `${sz.cost}${sz.from_here ? '' : ` ← ${sz.source.split('@')[1]}`}`;
    const bg = sz.state === 'drawn' ? token('--pix-drawn-bg') : sz.state === 'declined' ? token('--pix-declined-bg') : undefined;
    const cv = sz.mask ? bitmapCanvas(sz.mask, 4, { bg }) : h('span', { class: 'muted' }, '—');
    const what = sz.state === 'derived' ? `derived from ${sz.source}, cost ${sz.cost}` : sz.state;
    if (sz.current) {
      return h('div', { class: `thumb ${sz.state} current`, title: 'the drawing you are editing' },
        cv, h('span', {}, sizeOf(sz)), h('span', { class: 'cap' }, cap));
    }
    const href = sz.entry !== null && sz.entry !== undefined ? `#edit/${sz.entry}` : `#edit/${encodeURIComponent(sz.key)}`;
    return h('a', { class: `thumb link ${sz.state}`, href,
      title: `${what} · ${sz.state === 'drawn' ? 'edit this drawing' : 'override this size'}` },
      cv, h('span', {}, sizeOf(sz)), h('span', { class: 'cap' }, cap));
  }

  async save() {
    this.commit();
    if (!this.dirty) return;
    if (this.cell.draft) return this.saveDraft();
    try {
      const info = await api('/api/save', { id: this.cell.id, ink: this.bitsString() });
      this.saved = this.ink.slice();
      Object.assign(this.cell, info);
      const e = this.data.entries.find(e => e.id === this.cell.id);
      if (e) Object.assign(e, info);
      invalidate();
      this.renderKind();
      this.syncButtons();
      this.preview();
      toast(`Saved ${this.cell.key} to sheet_${String(this.cell.sheet).padStart(2, '0')}.tga`);
    } catch (err) {
      toast(`Could not save: ${err.message}`, true);
    }
  }

  async saveDraft() {
    try {
      const info = await api('/api/save', { key: this.cell.key, ink: this.bitsString() });
      this.saved = this.ink.slice();
      invalidate();
      toast(`Issued ${this.cell.key} as #${info.id} and saved it to sheet_${String(info.sheet).padStart(2, '0')}.tga`);
      app.leaving = null;
      app.unsaved = null;
      location.hash = `#edit/${info.id}`;
    } catch (err) {
      toast(`Could not save: ${err.message}`, true);
    }
  }

  revert() {
    this.settle();
    if (!this.dirty) return this.changed();
    this.snapshot();
    this.ink = this.saved.slice();
    this.changed();
  }
}

function clampCell(at) {
  return [Math.max(0, Math.min(CELL - 1, at[0])), Math.max(0, Math.min(CELL - 1, at[1]))];
}

function lineCells(a, b) {
  // Bresenham between two cells, both included
  const out = [];
  let [x0, y0] = a; const [x1, y1] = b;
  const dx = Math.abs(x1 - x0), dy = -Math.abs(y1 - y0), sx = x0 < x1 ? 1 : -1, sy = y0 < y1 ? 1 : -1;
  let err = dx + dy;
  for (;;) {
    out.push([x0, y0]);
    if (x0 === x1 && y0 === y1) break;
    const e2 = 2 * err;
    if (e2 >= dy) { err += dy; x0 += sx; }
    if (e2 <= dx) { err += dx; y0 += sy; }
  }
  return out;
}

// ---------------------------------------------------------------------------
// Families: every derived size next to its drawing, worst first

views.families = async (root, arg) => {
  const filter = h('input', { type: 'text', placeholder: 'components, e.g. 木氵', value: arg || '', size: 16 });
  const list = h('div');
  const head = h('span', { class: 'muted' });
  const load = async () => {
    const q = filter.value.trim();
    const r = await api(`/api/families?limit=80${q ? '&comps=' + encodeURIComponent(q) : ''}`);
    head.textContent = r.total ? `${r.total} famil${r.total === 1 ? 'y' : 'ies'} with drawings · ${r.declined} declined size${r.declined === 1 ? '' : 's'}` : '';
    if (!r.families.length) {
      list.replaceChildren(h('div', { class: 'empty' }, q ? 'None of these components is drawn yet.' :
        'Nothing is drawn yet, so nothing is derived. Draw some base glyphlettes first.'));
      return;
    }
    list.replaceChildren(...r.families.map(f => h('div', { class: 'fam' },
      h('div', { class: 'label' }, h('div', { class: 'c' }, f.comp),
        h('div', { class: 'muted', style: { fontSize: '12px' } }, f.aspect + (f.role ? ` ${f.role}` : '')),
        f.worst === null ? h('span', { class: 'pill declined' }, 'has declined') : null),
      h('div', { class: 'items' }, f.items.map(it => famItem(it))))));
  };
  filter.addEventListener('keydown', e => { if (e.key === 'Enter') { location.hash = `#families/${encodeURIComponent(filter.value.trim())}`; } });
  root.append(h('section', { class: 'card' },
    h('div', { class: 'card-head' }, h('h2', {}, 'Families'), head, h('span', { class: 'spacer' }),
      filter, h('button', { class: 'small', onclick: () => { location.hash = `#families/${encodeURIComponent(filter.value.trim())}`; } }, 'Filter')),
    h('p', { class: 'muted', style: { margin: '0 0 6px', fontSize: '12.5px' } },
      'Drawings on blue, derived sizes with their resize cost (lower is closer to the drawing), declined sizes on red. ',
      'Click a drawing to edit it, or a derived size to override it with a drawing of its own.'),
    list));
  await load();
};

function famItem(it) {
  const bg = it.state === 'drawn' ? token('--pix-drawn-bg') : it.state === 'declined' ? token('--pix-declined-bg') : undefined;
  const cap = it.state === 'drawn' ? 'drawn' : it.state === 'declined' ? 'declined' : `${it.cost}`;
  return h('button', { class: `item ${it.state}`, title: it.state === 'drawn' ? `edit ${it.key}` : `override ${it.key}`,
    onclick: () => { location.hash = it.entry !== null ? `#edit/${it.entry}` : `#edit/${encodeURIComponent(it.key)}`; } },
    bitmapCanvas(it.mask, 4, { bg }), h('span', {}, sizeOf(it)), h('span', { class: 'cap' }, cap));
}

// ---------------------------------------------------------------------------
// Worklist

views.worklist = async (root, arg) => {
  const offset = Math.max(0, parseInt(arg || '0', 10) || 0);
  const r = await api(`/api/worklist?limit=100&offset=${offset}`);
  const count = h('input', { type: 'number', min: 1, max: 1024, value: 64 });
  const issueBtn = h('button', { class: 'primary', onclick: async () => {
    const n = parseInt(count.value, 10);
    if (!(n > 0)) return;
    if (!confirm(`Issue the next ${n} glyphlettes to the drawing sheets?`)) return;
    try {
      const res = await api('/api/issue', { count: n });
      invalidate();
      if (!res.issued.length) return toast('Nothing to issue');
      toast(`Issued ${res.issued.length} glyphlettes: #${res.issued[0]}–#${res.issued[res.issued.length - 1]}`);
      location.hash = `#edit/${res.issued[0]}`;
    } catch (e) { toast(`Could not issue: ${e.message}`, true); }
  } }, 'Issue');
  const pager = h('div', { class: 'row' },
    h('a', { href: `#worklist/${Math.max(0, offset - 100)}`, class: 'pill', style: offset ? null : { visibility: 'hidden' } }, '◀ previous 100'),
    h('span', { class: 'muted num' }, r.total ? `${offset + 1}–${Math.min(offset + 100, r.total)} of ${fmt(r.total)}` : ''),
    h('a', { href: `#worklist/${offset + 100}`, class: 'pill', style: offset + 100 < r.total ? null : { visibility: 'hidden' } }, 'next 100 ▶'));
  root.append(h('section', { class: 'card' },
    h('div', { class: 'card-head' }, h('h2', {}, 'Worklist'),
      h('span', { class: 'muted' }, 'what to draw next, most useful first'), h('span', { class: 'spacer' }),
      h('label', { class: 'row' }, 'Issue the next', count, 'glyphlettes', issueBtn)),
    r.rows.length ? h('div', { style: { overflowX: 'auto' } }, h('table', { class: 'data' },
      h('thead', {}, h('tr', {}, h('th', { class: 'n' }, '#'), h('th', {}, 'glyphlette'), h('th', {}, 'kind'),
        h('th', { class: 'n' }, 'uses'), h('th', {}, 'also serves'), h('th', {}, 'examples'),
        h('th', { class: 'n' }, 'everyday complete'))),
      h('tbody', {}, r.rows.map(w => h('tr', {},
        h('td', { class: 'n' }, w.rank),
        h('td', {}, h('span', { style: { fontSize: '20px', marginRight: '6px' } }, w.comp), h('span', { class: 'mono' }, sizeOf(w))),
        h('td', {}, h('span', { class: `pill ${w.kind}` }, w.kind)),
        h('td', { class: 'n' }, fmt(w.uses)),
        h('td', { class: 'mono muted', title: w.sizes.join(' ') }, w.sizes.length ? `${w.sizes.length}: ${w.sizes.slice(0, 5).join(' ')}${w.sizes.length > 5 ? ' …' : ''}` : '—'),
        h('td', { style: { fontSize: '16px' } }, w.examples),
        h('td', { class: 'n' }, `${fmt(w.everyday)} / ${fmt(r.everyday_total)}`)))))) :
      h('div', { class: 'empty' }, 'Everything planned is issued.'),
    pager));
};

// ---------------------------------------------------------------------------
// Text: render text with the glyphs as they are now, and inspect characters

views.text = async (root, arg) => {
  let saved = localStorageGet('text');
  if (saved === null) saved = (await api('/api/sample')).text;
  const area = h('textarea', { class: 'sample', spellcheck: 'false' });
  area.value = saved;
  let scale = parseInt(localStorageGet('textScale') || '2', 10);
  const out = h('div', { class: 'rendered' });
  const inspector = h('section', { class: 'card inspector' }, h('div', { class: 'muted' }, 'Click a character to inspect it.'));
  const render = async () => {
    localStorageSet('text', area.value);
    const r = await api(`/api/text?t=${encodeURIComponent(area.value.slice(0, 2000))}`);
    const lines = [[]];
    for (const g of r.glyphs) g.ch === '\n' ? lines.push([]) : lines[lines.length - 1].push(g);
    out.replaceChildren(...lines.map(line => h('div', { class: 'line' }, line.map(g => {
      if (g.cell) {
        const cv = bitmapCanvas(g.cell, scale, { frame: false });   // text: glyphs abut, on a dark surface
        cv.classList.add('g');
        cv.title = g.ch;
        cv.addEventListener('click', () => inspect(g.ch));
        return cv;
      }
      return h('span', { class: 'g miss', title: `${g.ch}: not assembled yet`,
        style: { width: `${CELL * scale}px`, height: `${CELL * scale}px`, fontSize: `${12 * scale}px` },
        onclick: () => inspect(g.ch) }, g.ch);
    }))));
  };
  const inspect = async ch => {
    const c = await api(`/api/char?c=${encodeURIComponent(ch)}`);
    if (!c.covered) {
      inspector.replaceChildren(h('div', { class: 'ch' }, ch), h('p', { class: 'muted' }, 'Not a character Juxing covers.'));
      return;
    }
    inspector.replaceChildren(
      h('div', { class: 'row', style: { gap: '16px' } },
        h('div', { class: 'ch' }, ch),
        c.cell ? bitmapCanvas(c.cell, 4) : h('span', { class: 'pill missing' }, 'not assembled yet')),
      h('div', { class: 'secondary' }, `U+${c.cp.toString(16).toUpperCase()} · ${c.ids} · ${c.strokes} strokes · ${c.tier_name}`),
      h('h3', { style: { marginTop: '12px' } }, c.assembled ? 'Assembled from' : 'Planned parts'),
      h('ul', { class: 'parts' }, c.parts.map(p => h('li', {},
        h('span', { style: { fontSize: '20px' } }, p.comp), h('span', { class: 'mono' }, sizeOf(p)),
        h('span', { class: `pill ${p.kind}` }, p.kind),
        p.source ? h('span', { class: 'muted' }, `from ${p.source}`) : null,
        !p.source && p.drawing ? h('span', { class: 'muted' }, `from ${p.drawing}`) : null,
        h('span', { style: { flex: 1 } }),
        p.entry !== null ? h('a', { href: `#edit/${p.entry}`, class: 'pill' }, 'edit') :
          p.kind === 'missing' ? h('button', { class: 'small', onclick: () => issueFor(p) }, 'issue') : null))));
  };
  const issueFor = async p => {
    const key = p.drawing || p.key;
    if (!confirm(`Issue ${key} to the drawing sheets?`)) return;
    try {
      const r = await api('/api/issue', { keys: [key] });
      invalidate();
      if (r.issued.length) location.hash = `#edit/${r.issued[0]}`;
    } catch (e) { toast(`Could not issue: ${e.message}`, true); }
  };
  area.addEventListener('input', debounce(render, 400));
  const zoom = z => h('button', { class: 'small' + (scale === z ? ' on' : ''), onclick: e => {
    scale = z; localStorageSet('textScale', String(z));
    for (const b of e.target.parentNode.children) b.classList.toggle('on', b === e.target);
    render();
  } }, `${z}×`);
  root.append(h('div', { class: 'text-layout' },
    h('section', { class: 'card' },
      h('div', { class: 'card-head' }, h('h2', {}, 'Text'), h('span', { class: 'muted' }, 'as the font would draw it now; grey characters are not assembled yet'),
        h('span', { class: 'spacer' }), h('span', { class: 'row' }, [1, 2, 3, 4].map(zoom))),
      area, h('div', { style: { height: '12px' } }), out),
    inspector));
  await render();
  if (arg) inspect(arg);
};

// ---------------------------------------------------------------------------
// About: what Juxing is, for everyone who draws with it (web/about.html)

views.about = async root => {
  const html = await fetch('about.html', { cache: 'no-store' }).then(r => r.text());
  const holder = h('div');
  holder.innerHTML = html;
  for (const el of holder.querySelectorAll('[data-px]')) {
    const rows = el.dataset.px.split('/');
    const m = { w: rows[0].length, h: rows.length, bits: rows.join('').replace(/#/g, '1').replace(/[^1]/g, '0') };
    el.replaceWith(bitmapCanvas(m, 14));
  }
  root.append(holder);
  // where things stand, when the status is at hand (it may take a moment after a save)
  const live = holder.querySelector('[data-live=progress]');
  if (live) {
    try {
      const st = app.status || await api('/api/status');
      const every = st.tiers[0];
      live.textContent = `So far: ${fmt(st.drawn)} of ${fmt(st.glyphlettes)} glyphlettes drawn; ` +
        `${fmt(st.ready)} of ${fmt(st.targets)} characters can be assembled, ` +
        `${fmt(every.done)} of ${fmt(every.total)} everyday ones.`;
    } catch (e) { live.remove(); }
  }
};

route();
