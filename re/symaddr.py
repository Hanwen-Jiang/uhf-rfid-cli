"""Address helpers: resolve symbol addresses from the PE symbol table.

objdump prints COFF symbol values as section-relative offsets.  For PE images
loaded at the default base, `objdump -d` reports VA = section-relative value
+ 0x1000 + 0x400000 (i.e. RVA + image base).  We therefore add 0x401000.
"""
import re
import os
import subprocess

HERE = os.path.dirname(os.path.abspath(__file__))
EXE = os.path.join(HERE, '..', 'rfid_main_pannel.exe')
SYMS = os.path.join(HERE, 'symbols.txt')
TEXT_BASE = 0x401000
IMAGE_BASE = 0x400000


def load_symbols(path=SYMS):
    """Return {mangled_name: va} for section 1 (.text) function symbols."""
    if not os.path.exists(path):
        with open(path, 'w', encoding='utf-8') as f:
            subprocess.run(['objdump', '-t', EXE], stdout=f, stderr=subprocess.DEVNULL)
    out = {}
    pat = re.compile(r'\(sec\s+(-?\d+)\).*?0x([0-9a-fA-F]+)\s+(\S+)\s*$')
    with open(path, encoding='utf-8', errors='replace') as f:
        for line in f:
            m = pat.search(line)
            if not m:
                continue
            sec, val, name = m.group(1), int(m.group(2), 16), m.group(3)
            if sec == '1':
                out[name] = val + TEXT_BASE
    return out


SYMBOLS = load_symbols()
BY_VA = {}


def _build_by_va():
    global BY_VA
    for n, va in SYMBOLS.items():
        BY_VA.setdefault(va, n)


_build_by_va()


def find(substr, exclude_dupes=True):
    """Find symbols whose mangled name contains substr. Returns [(va, name)]."""
    res = [(va, n) for n, va in SYMBOLS.items() if substr in n]
    seen, out = set(), []
    for va, n in sorted(res):
        if exclude_dupes and va in seen:
            continue
        seen.add(va)
        out.append((va, n))
    return out
