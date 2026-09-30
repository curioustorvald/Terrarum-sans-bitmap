"""
Juxing web app: the drawing status at a glance, and a pixel editor for the drawing sheets.

    python3 juxing.py serve [--port 8765] [--host 127.0.0.1]

Serves web/ and a JSON API over the same Context as the command line (Python standard
library only). The editor saves straight into glyphlettes/sheet_NN.tga and regenerates the
guides of that sheet, as `refresh` does. The registry and the sheets are re-read whenever
they change on disk, so the app and an external editor (Krita) can be used side by side,
as long as the same sheet isn't edited in both at once: a save rewrites the whole sheet.

There is no authentication: keep the default host (127.0.0.1) unless the network is trusted.
"""

import json
import os
import threading
import traceback
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from io import BytesIO
from urllib.parse import parse_qs, urlparse

import numpy as np
from PIL import Image

import assembler as ASM
import geometry as GEO
import glyphlettes as GL
import model as M
import planner as P
from geometry import SlotKey

HERE = os.path.dirname(os.path.abspath(__file__))
WEB_DIR = os.path.join(HERE, 'web')
TIER_NAMES = {1: 'everyday', 2: 'common', 3: 'URO rest', 4: 'Ext. A rest'}
STATIC_TYPES = {'.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
                '.css': 'text/css; charset=utf-8', '.svg': 'image/svg+xml'}


def bits(mask) -> dict:
    """A boolean mask as JSON: width, height and a string of 0/1, row by row."""
    mask = np.asarray(mask, dtype=bool)
    return {'w': int(mask.shape[1]), 'h': int(mask.shape[0]),
            'bits': ''.join('1' if v else '0' for v in mask.ravel())}


def unbits(s: str, h: int = GEO.CELL_H, w: int = GEO.CELL_W) -> np.ndarray:
    if len(s) != h * w or set(s) - {'0', '1'}:
        raise ValueError(f"expected {h * w} bits")
    return np.array([c == '1' for c in s], dtype=bool).reshape(h, w)


def key_json(k: SlotKey) -> dict:
    return {'key': str(k), 'comp': k.comp, 'w': k.w, 'h': k.h, 'role': k.role}


class PeekCache:
    """The derived-size cache for previews: reads the real one, keeps what it adds to itself
    (a preview's ink is gone after the next stroke, so it isn't worth storing)."""

    def __init__(self, cache):
        self.cache, self.local = cache, {}

    def key(self, *args):
        return self.cache.key(*args)

    def get(self, k):
        hit = self.local.get(k, False)
        return self.cache.get(k) if hit is False else hit

    def put(self, k, v):
        self.local[k] = v


class State:
    """The Context and what is derived from it, rebuilt when the files change."""

    def __init__(self, juxing):
        self.J = juxing
        self.lock = threading.RLock()
        self.ctx = None
        self.stamp = None
        self.load()

    # -- loading -------------------------------------------------------------

    def disk_stamp(self):
        paths = [GL.REGISTRY_PATH] + [GL.sheet_path(n) for n in range(64)]
        return tuple(os.path.getmtime(p) if os.path.exists(p) else 0 for p in paths)

    def load(self):
        self.ctx = self.J.Context()
        self.stamp = self.disk_stamp()
        self.plan_stale = False
        self.scorer = None
        self.glyphs = {}
        self.status_cache = None
        self.drafts = {}

    def fresh(self):
        """The Context, reloaded if the registry or a sheet changed on disk."""
        if self.disk_stamp() != self.stamp:
            self.load()
        return self.ctx

    def planned(self):
        """The Context with an up-to-date plan (a save leaves it stale until needed)."""
        ctx = self.fresh()
        if self.plan_stale:
            ctx.plan = P.make_plan(ctx.layout, ctx.model.targets, set(ctx.registry.by_key),
                                   free=lambda k: self.J.GN.can_generate(k, ctx.layout), unit=self.J.GN.is_unit,
                                   derive_ok=ctx.drawn.derive_ok, verbose=False,
                                   blank=set(ctx.registry.by_key) - set(ctx.hand))
            self.plan_stale = False
            self.status_cache = None
        return ctx

    def get_scorer(self):
        if self.scorer is None:
            self.scorer = ASM.font_scorer(self.ctx.layout, self.ctx.drawn)
        return self.scorer

    def drawings_changed(self):
        ctx = self.ctx
        ctx.hand = ctx.sheets.drawn()
        ctx.drawn = GL.Library(ctx.hand, ctx.layout, ctx.drawn.cache)
        self.plan_stale = True
        self.scorer = None
        self.glyphs = {}
        self.status_cache = None
        self.drafts = {}

    # -- glyphs --------------------------------------------------------------

    def glyph(self, cp: int):
        """The assembled cell of a character (evenness pass included), or None."""
        if cp not in self.glyphs:
            if cp in ASM.HEXAGRAMS:
                self.glyphs[cp] = ASM.hexagram(cp)
            elif not self.ctx.model.get(chr(cp)) or not self.ctx.model[chr(cp)].is_target:
                self.glyphs[cp] = None
            else:
                r = ASM.assemble_one(self.ctx.layout, cp, self.ctx.drawn, self.get_scorer())
                self.glyphs[cp] = None if r is None else r[0]
        return self.glyphs[cp]

    def sketch(self, cp: int, lib, scorer):
        """A character as it would be assembled with `lib`; parts that are missing are
        returned as boxes to draw in grey."""
        ctx = self.ctx
        r = ASM.assemble_one(ctx.layout, cp, lib, scorer)
        if r is not None:
            return r[0], []
        cell = np.zeros((GEO.CELL_H, GEO.CELL_W), dtype=bool)
        missing = []
        for k, x, y in GL.cut_placements(ctx.layout, cp, lambda k: k in ctx.plan.leaves or k in lib):
            if k in lib:
                lib[k].blit(cell, x, y)
            else:
                missing.append([x, y, k.w, k.h])
        return cell & GL.BODY_MASK, missing

    # -- views ---------------------------------------------------------------

    def status(self):
        ctx = self.planned()
        if self.status_cache:
            return self.status_cache
        plan, reg = ctx.plan, ctx.registry
        fams = plan.families()
        per = sorted(len(f.derived) for f in fams.values()) or [0]
        q = lambda p: per[round(p * (len(per) - 1))]
        needed = plan.glyphlettes()
        issued = set(reg.by_key)
        ready = set()
        for cp in ctx.model.targets:
            if ASM.resolve(ctx.layout, cp, ctx.drawn) is not None:
                ready.add(cp)
        totals, done = {}, {}
        for cp in ctx.model.targets:
            t = ctx.model[chr(cp)].tier
            totals[t] = totals.get(t, 0) + 1
            done[t] = done.get(t, 0) + (cp in ready)
        derived_now = sum(1 for k in plan.cover if k not in ctx.hand and ctx.drawn.kind(k) == 'derived')
        cover = np.zeros(ASM.SHEET_END - ASM.SHEET_FIRST, dtype=np.uint8)
        for cp in ctx.model.targets:
            cover[cp - ASM.SHEET_FIRST] = 2 if cp in ready else 1
        for cp in ASM.HEXAGRAMS:
            cover[cp - ASM.SHEET_FIRST] = 3
        self.status_cache = {
            'summary': plan.summary(),
            'bases': len(fams),
            'derived': sum(per),
            'per_base': {'min': per[0], 'q1': q(.25), 'median': q(.5), 'q3': q(.75), 'max': per[-1]},
            'overrides': sum(len(f.drawings) - 1 for f in fams.values()),
            'glyphlettes': len(needed),
            'issued': len(reg.entries),
            'drawn': len(ctx.hand),
            'blank': sum(1 for e in reg.entries if e.key not in ctx.hand),
            'not_issued': len(needed - issued),
            'orphans': sum(1 for e in reg.entries if e.key not in needed),
            'derived_now': derived_now,
            'declined': len(plan.declined),
            'generated': len(plan.generated),
            'sheets': reg.sheet_count(),
            'tiers': [{'tier': t, 'name': TIER_NAMES[t], 'done': done.get(t, 0), 'total': totals.get(t, 0)}
                      for t in (1, 2, 3, 4)],
            'ready': len(ready),
            'targets': len(ctx.model.targets),
            'coverage': {'first': ASM.SHEET_FIRST, 'cols': ASM.SHEET_COLS, 'states': ''.join(map(str, cover))},
            'progress': self.progress(plan),
        }
        return self.status_cache

    def progress(self, plan):
        """Characters complete per tier after the next N glyphlettes, for the chart."""
        out = []
        n = len(plan.progress)
        marks = sorted({min(n, m) for m in (0, 50, 100, 250, 500, 1000, 1500, 2000, 2500, 3000, 3500, 4000,
                                            5000, 6000, 7500, 10000) if m <= n} | {n})
        for m in marks:
            pr = plan.progress[m - 1] if m else {}
            out.append({'n': m, 'tiers': [pr.get(t, 0) for t in (1, 2, 3, 4)]})
        return out

    def entry_info(self, e, fams=None, draft=False):
        """What the app shows of a registry entry; a draft is a size not issued yet, opened
        for overriding (it is issued when it is first saved)."""
        ctx = self.ctx
        fams = fams if fams is not None else ctx.plan.families()
        fam = fams.get(GL.family(e.key))
        kind = 'orphan'
        if draft:
            kind = 'override' if fam and fam.base != e.key else 'base'
        elif fam and e.key in fam.drawings:
            kind = 'base' if fam.base == e.key else 'override'
        return dict(key_json(e.key), id=None if draft else e.id, sheet=e.sheet, col=e.col, row=e.row,
                    bx=e.bx, by=e.by, exemplar=e.exemplar, drawn=not draft and e.key in ctx.hand, kind=kind,
                    sizes=len(fam.derived) if fam else 0, draft=draft, would_be=e.id if draft else None,
                    base=str(fam.base) if fam and kind == 'override' else None)

    def entries(self):
        ctx = self.planned()
        fams = ctx.plan.families()
        return {'sheets': ctx.registry.sheet_count(), 'per_sheet': GL.PER_SHEET, 'cols': GL.SHEET_COLS,
                'entries': [self.entry_info(e, fams) for e in ctx.registry.entries]}

    def cell(self, eid: int):
        ctx = self.fresh()          # the editor doesn't wait for a new plan after a save
        e = ctx.registry.by_id[eid]
        return self.cell_json(e, GL.ink_mask(ctx.sheets.cell(e)))

    def draft_entry(self, k: SlotKey):
        """A size not issued yet, as it would be issued now: (entry, starting ink). The entry
        is not in the registry; its id is the cell it would get."""
        if k not in self.drafts:
            ctx = self.planned()
            ex, bx, by, ink = self.J.draft(ctx, k)
            self.drafts[k] = (GL.Entry(ctx.registry.next_id(), k, bx, by, ex), ink)
        return self.drafts[k]

    def draft(self, key: str):
        """The editor's cell for overriding a size: its registry cell if it is issued, else a
        draft that starts from the resizer's version and is issued on its first save."""
        ctx = self.planned()
        k = SlotKey.parse(key)
        if k in ctx.registry.by_key:
            return self.cell(ctx.registry.by_key[k].id)
        if k.w > GEO.BODY_W or k.h > GEO.BODY_H:
            raise ValueError(f"{k} does not fit the body box")
        e, ink = self.draft_entry(k)
        return self.cell_json(e, ink, draft=True)

    def cell_json(self, e, ink, draft=False):
        ctx = self.ctx
        g = GL.cell_guides(e, ctx.layout, ctx.is_leaf, ctx.drawn)
        users = self.users_of(e.key)
        src = None
        if draft:
            s, d, _ = ctx.drawn.derivation(e.key)
            if d is not None:
                src = {'key': str(s), 'state': 'derived' if d.ok else 'declined', 'cost': round(d.cost, 1)}
        return dict(self.entry_info(e, draft=draft), ink=bits(ink), box=list(g.box), derived=src,
                    keep_empty=list(g.keep_empty) if g.keep_empty else None,
                    context=[{'key': str(k), 'rect': list(r), 'hole': list(h) if h else None}
                             for k, r, h in g.context],
                    context_ink=bits(g.context_ink),
                    users=[chr(cp) for cp in users[:40]], user_count=len(users),
                    note=(ctx.model.get(e.key.comp).note if ctx.model.get(e.key.comp) else ''))

    def users_of(self, key):
        """Characters that use a drawing (or the size it serves), most common first."""
        ctx = self.ctx
        d = key if key in ctx.plan.uses else ctx.plan.cover.get(key, key)
        users = [cp for cp, parts in ctx.plan.chars.items() if d in parts]
        return sorted(users, key=lambda cp: (ctx.model[chr(cp)].tier, len(ctx.plan.chars[cp]), cp))

    def preview(self, eid, ink: np.ndarray, key: str = None):
        """Derived sizes and characters as they would be with this ink in the cell (of an
        entry, or of a draft by its key)."""
        ctx = self.fresh()
        if eid is not None:
            e = ctx.registry.by_id[eid]
        else:
            self.planned()
            e = self.draft_entry(SlotKey.parse(key))[0]
        hand = dict(ctx.hand)
        if ink.any():
            hand[e.key] = GL.Drawing(ink, e.key, e.bx, e.by)
        else:
            hand.pop(e.key, None)
        lib = GL.Library(hand, ctx.layout, PeekCache(ctx.drawn.cache))
        fam = GL.family(e.key)
        keys = {k for k in ctx.plan.cover if GL.family(k) == fam} | {k for k in hand if GL.family(k) == fam}
        sizes = []
        for k in sorted(keys, key=lambda k: (-k.w * k.h, k)):
            entry = ctx.registry.by_key[k].id if k in ctx.registry.by_key else None
            if k in hand:
                d = hand[k]
                sizes.append(dict(key_json(k), state='drawn', mask=bits(d.mask[d.by:d.by + k.h, d.bx:d.bx + k.w]),
                                  current=k == e.key, entry=entry))
                continue
            src, d, _ = lib.derivation(k)
            if d is None:
                sizes.append(dict(key_json(k), state='none', current=k == e.key, entry=entry))
                continue
            sizes.append(dict(key_json(k), state='derived' if d.ok else 'declined', cost=round(d.cost, 1),
                              source=str(src), from_here=src == e.key, current=k == e.key, entry=entry,
                              mask=bits(d.mask[d.by:d.by + k.h, d.bx:d.bx + k.w])))
        chars = []
        scorer = self.get_scorer()
        for cp in ([ord(e.exemplar)] if e.exemplar else []) + self.users_of(e.key)[:23]:
            if any(c['cp'] == cp for c in chars):
                continue
            cell, missing = self.sketch(cp, lib, scorer)
            chars.append({'cp': cp, 'ch': chr(cp), 'cell': bits(cell), 'missing': missing})
        return {'sizes': sizes, 'chars': chars}

    def save_draft(self, key: str, ink: np.ndarray):
        """Save a draft: issue its size (where the draft showed it: the same exemplar and box,
        whatever the plan says by now) and save the ink into the new cell."""
        ctx = self.planned()
        k = SlotKey.parse(key)
        if k in ctx.registry.by_key:
            return self.save(ctx.registry.by_key[k].id, ink)
        d, _ = self.draft_entry(k)
        fam = ctx.plan.families().get(GL.family(k))
        e = ctx.registry.issue(k, d.bx, d.by, d.exemplar)
        ctx.registry.save()
        info = self.save(e.id, ink)
        # the plan catches up with the new cell lazily; until then it is what the draft was
        info.update(kind='override' if fam and fam.base != k else 'base',
                    base=str(fam.base) if fam and fam.base != k else None)
        return info

    def save(self, eid: int, ink: np.ndarray):
        ctx = self.fresh()
        e = ctx.registry.by_id[eid]
        n = e.sheet
        ctx.sheets._images.pop(n, None)          # start from the sheet as it is on disk
        cell = ctx.sheets.cell(e)
        cell[GL.ink_mask(cell)] = (0, 0, 0, 0)
        cell[ink] = GL.COLOUR_INK
        self.drawings_changed()
        ctx.sheets.render_guides(n, ctx.layout, ctx.is_leaf, ctx.drawn)
        ctx.sheets.save(n)
        self.stamp = self.disk_stamp()
        return self.entry_info(e, ctx.plan.families())

    def retire(self, eid: int):
        """Retire an orphan cell (juxing.retire); drawn ones only when asked, as that erases them."""
        ctx = self.planned()
        e = ctx.registry.by_id[eid]
        if e.key in ctx.plan.glyphlettes():
            raise ValueError(f"#{eid} {e.key} is in the plan; only orphans can be retired")
        self.J.retire(ctx, [e], verbose=False)
        self.load()
        return {'retired': eid, 'key': str(e.key)}

    def refresh(self):
        ctx = self.planned()
        for n in range(ctx.registry.sheet_count()):
            self.J._write_sheet(ctx, n, verbose=False)
        self.stamp = self.disk_stamp()

    def issue(self, count=0, keys=(), whole=()):
        ctx = self.planned()
        new = [ctx.layout.root(ord(ch)) for ch in whole] + [SlotKey.parse(k) for k in keys]
        new += ctx.plan.order[:count]
        issued = self.J.issue(ctx, new, verbose=False)
        self.load()
        return [e.id for e in issued]

    def worklist(self, limit: int, offset: int):
        ctx = self.planned()
        plan = ctx.plan
        fams = plan.families()
        serves = {}
        for k, d in plan.cover.items():
            serves.setdefault(d, []).append(k)
        users = ctx.users()
        rows = []
        for i, k in enumerate(plan.order[offset:offset + limit], start=offset):
            sizes = sorted(serves.get(k, []), key=lambda s: (-s.w * s.h, s))
            pr = plan.progress[i]
            rows.append(dict(key_json(k), rank=i + 1, uses=plan.uses[k], effort=P.cost(ctx.layout, k),
                             kind='base' if fams[GL.family(k)].base == k else 'override',
                             sizes=[f"{s.w}x{s.h}" for s in sizes if s != k],
                             examples=''.join(map(chr, ctx.examples(users[k], 8))),
                             everyday=pr.get(1, 0)))
        return {'total': len(plan.order), 'rows': rows, 'everyday_total': self.status()['tiers'][0]['total']}

    def families(self, limit: int, comps=None):
        ctx = self.planned()
        rows = self.J.derived_review(ctx, comps)
        out = []
        for worst, (comp, role, aspect), items in rows[:limit]:
            out.append({'comp': comp, 'role': role, 'aspect': aspect,
                        'worst': None if worst == float('inf') else round(worst, 1),
                        'items': [dict(key_json(k), state=state, cost=round(cost, 1), source=str(src),
                                       entry=ctx.registry.by_key[k].id if k in ctx.registry.by_key else None,
                                       mask=bits(box))
                                  for k, box, state, cost, src in items]})
        return {'families': out, 'total': len(rows),
                'declined': sum(1 for _, _, its in rows for i in its if i[2] == 'declined')}

    def text(self, text: str):
        self.planned()
        out = []
        for ch in text:
            if ch == '\n':
                out.append({'ch': '\n'})
                continue
            cp = ord(ch)
            if ASM.SHEET_FIRST <= cp < ASM.SHEET_END:
                g = self.glyph(cp)
                out.append({'ch': ch, 'cell': bits(g) if g is not None else None})
            else:
                out.append({'ch': ch})
        return {'glyphs': out}

    def char(self, ch: str):
        ctx = self.planned()
        cp = ord(ch)
        comp = ctx.model.get(ch)
        if comp is None or not comp.is_target:
            return {'ch': ch, 'covered': False}
        placed = ASM.resolve(ctx.layout, cp, ctx.drawn)
        planned = GL.cut_placements(ctx.layout, cp, lambda k: k in ctx.plan.leaves)
        parts = []
        for k, x, y in (placed or planned):
            kind = ctx.drawn.kind(k)
            src = ctx.drawn.source(k) if kind == 'derived' else None
            if kind is None and k in ctx.plan.generated:
                kind = 'generated'
            d = ctx.plan.cover.get(k)
            target = k if k in ctx.registry.by_key else src if src in ctx.registry.by_key else \
                d if d in ctx.registry.by_key else None
            parts.append(dict(key_json(k), x=x, y=y, kind=kind or 'missing',
                              source=str(src) if src else None,
                              drawing=str(d) if d and d != k else None,
                              entry=ctx.registry.by_key[target].id if target else None))
        g = self.glyph(cp)
        return {'ch': ch, 'cp': cp, 'covered': True, 'ids': M.IDS.to_string(comp.decomp),
                'strokes': comp.strokes, 'tier': comp.tier, 'tier_name': TIER_NAMES.get(comp.tier, '-'),
                'assembled': placed is not None, 'cell': bits(g) if g is not None else None, 'parts': parts}

    def sample(self) -> str:
        path = os.path.join(HERE, 'sample_text.txt')
        if not os.path.exists(path):
            return ''
        with open(path, encoding='utf-8') as f:
            return '\n'.join(l.rstrip('\n') for l in f if not l.startswith('#'))

    def sheet_png(self, n: int) -> bytes:
        ctx = self.fresh()
        buf = BytesIO()
        Image.fromarray(ctx.sheets.image(n), 'RGBA').save(buf, 'PNG')
        return buf.getvalue()


class Handler(BaseHTTPRequestHandler):
    state: State = None

    def log_message(self, fmt, *args):
        pass

    def send(self, code, body: bytes, ctype: str):
        self.send_response(code)
        self.send_header('Content-Type', ctype)
        self.send_header('Content-Length', str(len(body)))
        self.send_header('Cache-Control', 'no-store')
        self.end_headers()
        self.wfile.write(body)

    def json(self, obj, code=200):
        self.send(code, json.dumps(obj, ensure_ascii=False).encode('utf-8'), 'application/json; charset=utf-8')

    def body(self):
        n = int(self.headers.get('Content-Length') or 0)
        return json.loads(self.rfile.read(n) or b'{}')

    def do_GET(self):
        self.route('GET')

    def do_POST(self):
        self.route('POST')

    def route(self, method):
        url = urlparse(self.path)
        q = {k: v[-1] for k, v in parse_qs(url.query).items()}
        path = url.path
        st = self.state
        try:
            if method == 'GET' and not path.startswith('/api/') and not path.startswith('/sheet/'):
                return self.static(path)
            with st.lock:
                if path == '/api/status':
                    return self.json(st.status())
                if path == '/api/entries':
                    return self.json(st.entries())
                if path.startswith('/api/cell/'):
                    return self.json(st.cell(int(path.rsplit('/', 1)[1])))
                if path == '/api/draft':
                    return self.json(st.draft(q['key']))
                if path.startswith('/sheet/') and path.endswith('.png'):
                    return self.send(200, st.sheet_png(int(path[7:-4])), 'image/png')
                if path == '/api/worklist':
                    return self.json(st.worklist(int(q.get('limit', 100)), int(q.get('offset', 0))))
                if path == '/api/families':
                    comps = set(q['comps']) if q.get('comps') else None
                    return self.json(st.families(int(q.get('limit', 60)), comps))
                if path == '/api/text':
                    return self.json(st.text(q.get('t', '')[:2000]))
                if path == '/api/char':
                    return self.json(st.char(q.get('c', '')[:1] or '一'))
                if path == '/api/sample':
                    return self.json({'text': st.sample()})
                if method == 'POST':
                    b = self.body()
                    if path == '/api/preview':
                        eid = b.get('id')
                        return self.json(st.preview(None if eid is None else int(eid), unbits(b['ink']), b.get('key')))
                    if path == '/api/save':
                        if b.get('id') is None:
                            return self.json(st.save_draft(b['key'], unbits(b['ink'])))
                        return self.json(st.save(int(b['id']), unbits(b['ink'])))
                    if path == '/api/issue':
                        return self.json({'issued': st.issue(int(b.get('count', 0)), b.get('keys', []),
                                                             b.get('whole', ''))})
                    if path == '/api/retire':
                        return self.json(st.retire(int(b['id'])))
                    if path == '/api/refresh':
                        st.refresh()
                        return self.json({'ok': True})
                    if path == '/api/reload':
                        st.load()
                        return self.json({'ok': True})
            self.json({'error': f"no such endpoint: {method} {path}"}, 404)
        except (ValueError, KeyError, IndexError) as e:
            self.json({'error': str(e)}, 400)
        except Exception as e:
            traceback.print_exc()
            self.json({'error': f"{type(e).__name__}: {e}"}, 500)

    def static(self, path):
        if path in ('', '/'):
            path = '/index.html'
        full = os.path.normpath(os.path.join(WEB_DIR, path.lstrip('/')))
        if not full.startswith(WEB_DIR + os.sep) or not os.path.isfile(full):
            return self.json({'error': 'not found'}, 404)
        with open(full, 'rb') as f:
            self.send(200, f.read(), STATIC_TYPES.get(os.path.splitext(full)[1], 'application/octet-stream'))


def serve(juxing, host='127.0.0.1', port=8765):
    print("Loading the model and the plan...")
    Handler.state = State(juxing)
    httpd = ThreadingHTTPServer((host, port), Handler)
    print(f"Juxing web app on http://{host}:{port}/  (Ctrl+C to stop)")
    if host not in ('127.0.0.1', 'localhost', '::1'):
        print("  warning: no authentication, and the app writes the drawing sheets")
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        httpd.server_close()
