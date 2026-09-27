"""
Upstream data sources for the Han model.

  - Unihan database (Unicode, Inc.; Unicode License v3)
      stroke counts, frequency/commonness indicators, readings, definitions
  - BabelStone IDS.TXT (Andrew West; copyright waived, free for any use)
      ideographic description sequences, used as the seed of the decomposition
  - EquivalentUnifiedIdeograph.txt, CJKRadicals.txt (Unicode, Inc.; Unicode License v3)
      maps radical/stroke-block characters to unified ideographs; the Kangxi radicals
  - CNS 11643 attribute data and Unicode mapping tables (Ministry of Digital Affairs,
    Taiwan; Open Government Data License v1.0, compatible with CC BY 4.0)
      stroke-type sequences (1 horizontal, 2 vertical, 3 left-falling, 4 dot, 5 turning),
      used to estimate the shape of components the IDS does not decompose.
      Only the attribute tables are used, never the CNS fonts.

These files are downloaded into sources/ by `make fetch` and are not committed;
the model derived from them (model/han_model.tsv) is.

The GPL-licensed CHISE/cjkvi IDS data is deliberately NOT used.
"""

import os
import re
import urllib.request
import zipfile
from dataclasses import dataclass, field
from typing import Dict, List, Optional, Tuple

HERE = os.path.dirname(os.path.abspath(__file__))
SOURCES_DIR = os.path.join(HERE, 'sources')

URLS = {
    'Unihan.zip': 'https://www.unicode.org/Public/UCD/latest/ucd/Unihan.zip',
    'IDS.TXT': 'https://www.babelstone.co.uk/CJK/IDS.TXT',
    'EquivalentUnifiedIdeograph.txt': 'https://www.unicode.org/Public/UCD/latest/ucd/EquivalentUnifiedIdeograph.txt',
    'CJKRadicals.txt': 'https://www.unicode.org/Public/UCD/latest/ucd/CJKRadicals.txt',
    'CNS_Properties.zip': 'https://www.cns11643.gov.tw/opendata/Properties.zip',
    'CNS_MapingTables.zip': 'https://www.cns11643.gov.tw/opendata/MapingTables.zip',
}

CNS_ATTRIBUTION = (
    "Ministry of Digital Affairs (Taiwan) 2026, CNS11643中文標準交換碼全字庫 (CNS 11643 attribute "
    "data and mapping tables, version 20260805), made available to the public under the Open "
    "Government Data License, version 1.0, https://data.gov.tw/license")

# Ranges the Juxing model covers
RANGE_EXT_A = range(0x3400, 0x4DC0)
RANGE_URO = range(0x4E00, 0xA000)


def is_target(cp: int) -> bool:
    return cp in RANGE_EXT_A or cp in RANGE_URO


def target_codepoints() -> List[int]:
    return list(RANGE_EXT_A) + list(RANGE_URO)


def fetch(force=False):
    os.makedirs(SOURCES_DIR, exist_ok=True)
    for name, url in URLS.items():
        path = os.path.join(SOURCES_DIR, name)
        if os.path.exists(path) and not force:
            print(f"  {name}: present")
            continue
        print(f"  {name}: downloading {url}")
        req = urllib.request.Request(url, headers={'User-Agent': 'Juxing/1.0'})
        with urllib.request.urlopen(req) as r, open(path + '.part', 'wb') as f:
            f.write(r.read())
        os.replace(path + '.part', path)


def _source_path(name):
    path = os.path.join(SOURCES_DIR, name)
    if not os.path.exists(path):
        raise FileNotFoundError(f"{path} not found; run `make fetch` first")
    return path


# ---------------------------------------------------------------------------
# Unihan

UNIHAN_FIELDS = frozenset([
    'kTotalStrokes', 'kRSUnicode',
    'kGB0', 'kGB1', 'kBigFive', 'kHanyuPinlu', 'kUnihanCore2020', 'kIICore',
    'kGradeLevel', 'kJoyoKanji', 'kJinmeiyoKanji', 'kKoreanEducationHanja', 'kJis0',
    'kMandarin', 'kDefinition',
])


def read_unihan() -> Dict[int, Dict[str, str]]:
    """Returns {codepoint: {field: value}} for the fields Juxing uses."""
    out: Dict[int, Dict[str, str]] = {}
    with zipfile.ZipFile(_source_path('Unihan.zip')) as z:
        for name in z.namelist():
            if not name.endswith('.txt'):
                continue
            with z.open(name) as f:
                for raw in f:
                    line = raw.decode('utf-8')
                    if line.startswith('#') or not line.strip():
                        continue
                    cp, key, value = line.rstrip('\n').split('\t', 2)
                    if key in UNIHAN_FIELDS:
                        out.setdefault(int(cp[2:], 16), {})[key] = value
    return out


def unihan_strokes(rec: Dict[str, str]) -> Optional[int]:
    v = rec.get('kTotalStrokes')
    if not v:
        return None
    # first value is the zh-Hans (G) preference
    return int(v.split()[0])


def unihan_tier(rec: Dict[str, str], cp: int) -> int:
    """
    Commonness tier, 1 (most common) .. 4 (rarest). Used to prioritise drawing.

    1: everyday characters of any CJK locale
       (GB 2312 level 1, Big5 level 1, Jōyō, Korean education hanja, HK grade list, Hanyu Pinlu)
    2: GB 2312 level 2, Big5 level 2, IICore, JIS X 0208, Jinmeiyō
    3: remaining URO characters, and Ext. A characters in UnihanCore2020
    4: remaining Ext. A characters
    """
    gb0 = rec.get('kGB0')
    big5 = rec.get('kBigFive')
    # values may carry a trailing apostrophe or several codes; the first code counts
    big5v = int(re.match(r'[0-9A-F]+', big5).group(0), 16) if big5 else None
    if (gb0 and int(gb0[:2]) <= 55) or (big5v and 0xA440 <= big5v <= 0xC67E) \
            or 'kJoyoKanji' in rec or 'kKoreanEducationHanja' in rec \
            or 'kGradeLevel' in rec or 'kHanyuPinlu' in rec:
        return 1
    if gb0 or (big5v and 0xC940 <= big5v <= 0xF9D5) or 'kIICore' in rec \
            or 'kJis0' in rec or 'kJinmeiyoKanji' in rec:
        return 2
    if cp in RANGE_URO or 'kUnihanCore2020' in rec:
        return 3
    return 4


# ---------------------------------------------------------------------------
# BabelStone IDS

@dataclass
class IdsEntry:
    cp: int
    char: str
    # list of (ids string without ^$ and without IVI, source letters, has IVI)
    sequences: List[Tuple[str, str, bool]] = field(default_factory=list)


_SEQ_RE = re.compile(r'\^(.*)\$\(([^)]*)\)')


def read_babelstone_ids() -> Tuple[Dict[str, IdsEntry], Dict[str, Tuple[str, str]]]:
    """
    Returns
      entries: {char: IdsEntry} for every character in the file
      unencoded: {'{n}': (description, fallback fragment)} from the file header
    """
    entries: Dict[str, IdsEntry] = {}
    unencoded: Dict[str, Tuple[str, str]] = {}
    unenc_re = re.compile(r'^#\t(\{\d+\})\t([^\t]*)\t?([^\t]*)$')
    pua_re = re.compile(r'\s*\([0-9A-F]{4}[^)]*\)\s*$')
    with open(_source_path('IDS.TXT'), encoding='utf-8-sig') as f:
        for line in f:
            line = line.rstrip('\r\n')
            if line.startswith('#'):
                m = unenc_re.match(line)
                if m:
                    desc = pua_re.sub('', m.group(2)).strip()
                    unencoded[m.group(1)] = (desc, m.group(3).strip())
                continue
            if not line.strip():
                continue
            cols = line.split('\t')
            if not cols[0].startswith('U+'):
                continue
            cp = int(cols[0][2:], 16)
            e = IdsEntry(cp, cols[1])
            for c in cols[2:]:
                m = _SEQ_RE.match(c)
                if not m:
                    continue
                ids = m.group(1)
                ivi = ids.startswith('〾')
                if ivi:
                    ids = ids[1:]
                e.sequences.append((ids, m.group(2), ivi))
            entries[e.char] = e
    return entries, unencoded


def pick_sequence(e: IdsEntry, region_pref: str) -> Optional[Tuple[str, str, bool]]:
    """
    Choose the IDS for the preferred region. Source letters in square brackets are
    'virtual' glyph forms and count as a match too. Returns (ids, region letter, ivi).
    """
    best = None
    best_rank = len(region_pref) + 1
    for ids, srcs, ivi in e.sequences:
        letters = srcs.replace('[', '').replace(']', '')
        # multi-letter designations such as UCS2003 are ignored
        letters = re.sub(r'UCS2003', '', letters)
        for rank, letter in enumerate(region_pref):
            if letter in letters and rank < best_rank:
                best, best_rank = (ids, letter, ivi), rank
    return best


# ---------------------------------------------------------------------------
# EquivalentUnifiedIdeograph

def read_equivalent_ideographs() -> Dict[str, str]:
    out = {}
    with open(_source_path('EquivalentUnifiedIdeograph.txt'), encoding='utf-8') as f:
        for line in f:
            line = line.split('#', 1)[0].strip()
            if not line:
                continue
            lhs, rhs = [s.strip() for s in line.split(';')]
            target = chr(int(rhs, 16))
            if '..' in lhs:
                a, b = lhs.split('..')
                for cp in range(int(a, 16), int(b, 16) + 1):
                    out[chr(cp)] = target
            else:
                out[chr(int(lhs, 16))] = target
    return out


# ---------------------------------------------------------------------------
# CJKRadicals

def read_radicals() -> Dict[str, str]:
    """Returns {character: radical number} for the unified ideograph and the radical
    character of each Kangxi radical (including the simplified forms, e.g. 149')."""
    out = {}
    with open(_source_path('CJKRadicals.txt'), encoding='utf-8') as f:
        for line in f:
            line = line.split('#', 1)[0].strip()
            if not line:
                continue
            num, radical, unified = [s.strip() for s in line.split(';')]
            for code in (radical, unified):
                if code:
                    out[chr(int(code, 16))] = num
    return out


# ---------------------------------------------------------------------------
# CNS 11643

def read_cns_stroke_sequences() -> Dict[str, str]:
    """Returns {character: stroke-type sequence}, e.g. 口: '251'."""
    with zipfile.ZipFile(_source_path('CNS_Properties.zip')) as z:
        seq = dict(l.split('\t')[:2] for l in
                   z.read('CNS_strokes_sequence.txt').decode('utf-8-sig').splitlines() if '\t' in l)
    out: Dict[str, str] = {}
    with zipfile.ZipFile(_source_path('CNS_MapingTables.zip')) as z:
        for name in ('Unicode/CNS2UNICODE_Unicode BMP.txt', 'Unicode/CNS2UNICODE_Unicode 2.txt',
                     'Unicode/CNS2UNICODE_Unicode 3.txt'):
            for line in z.read(name).decode('utf-8-sig').splitlines():
                if '\t' not in line:
                    continue
                cns, uni = line.split('\t')[:2]
                ch = chr(int(uni, 16))
                if cns in seq and ch not in out:
                    out[ch] = seq[cns]
    return out
