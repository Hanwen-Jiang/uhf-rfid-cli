"""Precise payload-layout extraction for rfid_main_pannel.exe.

Abstract interpretation over each command method's entry state (SysV x86-64
member function):

    rcx      = this
    edx      = arg1, r8d = arg2, r9d = arg3
    [rbp+0x30], [rbp+0x38] ... = further arguments

Locals live at [rbp-0x8], [rbp-0x10] ...  Cells are byte-addressable so
byte/word/dword partial writes are modelled individually.

Every store into the tx buffer (this+0x270 for LibWinUart, this+0x268 for
tcp_client) is recorded together with the symbolic origin of the byte, which
lets us label each frame byte with the C++ parameter it comes from.
"""
import os
import sys
import collections

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import symaddr as S
import pedwarf as P
from capstone import Cs, CS_ARCH_X86, CS_MODE_64
from capstone.x86 import X86_OP_IMM, X86_OP_REG, X86_OP_MEM

PE = P.PE()
IMAGE = PE.data
md = Cs(CS_ARCH_X86, CS_MODE_64)
md.detail = True

# ---------------------------------------------------------------- register model
RW = {}       # name -> (byte offset in the 64-bit reg, width)
ALIAS = {}    # name -> canonical 64-bit reg


def _reg(canon, *subs):
    RW[canon] = (0, 8)
    order = [('b', 0, 1), ('h', 1, 1), ('w', 0, 2), ('d', 0, 4)]
    for (suf, off, sz), name in zip(order, subs):
        if name is None:
            continue
        RW[name] = (off, sz)
        ALIAS[name] = canon
    ALIAS[canon] = canon


_reg('rax', 'al', 'ah', 'ax', 'eax')
_reg('rcx', 'cl', 'ch', 'cx', 'ecx')
_reg('rdx', 'dl', 'dh', 'dx', 'edx')
_reg('rbx', 'bl', 'bh', 'bx', 'ebx')
_reg('rsi', 'sil', None, 'si', 'esi')
_reg('rdi', 'dil', None, 'di', 'edi')
_reg('rbp', 'bpl', None, 'bp', 'ebp')
_reg('rsp', 'spl', None, 'sp', 'esp')
for _i in range(8, 16):
    _r = 'r%d' % _i
    RW[_r] = (0, 8)
    RW[_r + 'b'] = (0, 1)
    RW[_r + 'w'] = (0, 2)
    RW[_r + 'd'] = (0, 4)
    for _s in (_r, _r + 'b', _r + 'w', _r + 'd'):
        ALIAS[_s] = _r


def canon(n):
    return ALIAS.get(n, n)


def root(v):
    """Normalize a compound byte-tuple back to its symbolic root.

    '{this|0,this|1,...}' -> 'this' ;  '{arg5|0,...}|0' -> 'arg5|0'
    """
    if not isinstance(v, str):
        return v
    if '|' in v and not v.startswith('{'):
        return v
    if v.startswith('{') and v.endswith('}'):
        parts = [p for p in v[1:-1].split(',')]
        roots = set(p.split('|')[0] for p in parts)
        if len(roots) == 1:
            r = roots.pop()
            if len(parts) == 8:
                return r
            return '%s|%s' % (r, parts[0].split('|')[1] if '|' in parts[0] else '0')
    return v


# ---------------------------------------------------------------- PE helpers
def va_to_off(va):
    rva = va - S.IMAGE_BASE
    for s in PE.sections:
        if s['vma'] <= rva < s['vma'] + max(s['vsize'], s['rawsize']):
            return s['rawptr'] + (rva - s['vma'])
    return None


def _load_funcs():
    syms = sorted(set((va, n) for n, va in S.SYMBOLS.items()))
    out = []
    for i, (va, n) in enumerate(syms):
        end = syms[i + 1][0] if i + 1 < len(syms) else va + 0x4000
        end = min(end, va + 0x4000)
        o, e = va_to_off(va), va_to_off(end)
        if o is None or e is None or e <= o:
            continue
        out.append((va, end, n, IMAGE[o:e]))
    return out


FUNCS = _load_funcs()


# ---------------------------------------------------------------- symbolics
# A symbolic value is a fixed-length little-endian byte vector.  Every element
# is a label string describing where that byte came from, or None when the byte
# is unknown.  The length is preserved by every operation so slicing can never
# lose positional information.
SYM_LEN = 8


class Sym:
    __slots__ = ('b',)

    def __init__(self, b=None, fill=None):
        self.b = list(b) if b is not None else [fill] * SYM_LEN
        while len(self.b) < SYM_LEN:
            self.b.append(None)

    # -- constructors -------------------------------------------------
    @classmethod
    def root(cls, name):
        """A whole-width symbolic value, e.g. a pointer or an argument."""
        return cls([name] * SYM_LEN)

    @classmethod
    def imm(cls, val):
        """A constant; every byte shares the same literal value."""
        return cls(['%d' % (val & 0xFF)] * SYM_LEN)

    # -- byte access --------------------------------------------------
    def byte(self, i):
        return self.b[i] if 0 <= i < len(self.b) else None

    def slice(self, off, size):
        """Bytes [off, off+size) as a new Sym, padded with unknowns.

        A zero-size read is the `movsxd`/`cdqe` idiom: the low bytes are
        promoted, so the value is returned unchanged.
        """
        if size == 0:
            return self
        return Sym([self.byte(off + i) for i in range(size)])

    def all_known(self):
        return all(x is not None for x in self.b)

    def __getitem__(self, sl):
        if isinstance(sl, int):
            sl = slice(sl, sl + 1)
        start = sl.start or 0
        stop = sl.stop if sl.stop is not None else len(self.b)
        return Sym([self.byte(i) for i in range(start, stop)])

    # -- formatting ---------------------------------------------------
    def __str__(self):
        uniq = set(x for x in self.b if x is not None)
        if not uniq:
            return '<unknown>'
        if len(uniq) == 1 and self.all_known():
            return next(iter(uniq))
        return '{%s}' % ','.join('?' if x is None else x for x in self.b)

    def __repr__(self):
        return 'Sym(%s)' % self

    def __eq__(self, other):
        return isinstance(other, Sym) and self.b == other.b

    def __hash__(self):
        return hash(tuple(self.b))

    def label(self):
        """Compact human-facing description of the whole value."""
        known = [x for x in self.b if x is not None]
        if not known:
            return '?'
        uniq = []
        for x in known:
            if x not in uniq:
                uniq.append(x)
        if len(uniq) == 1:
            return uniq[0]
        return '+'.join(uniq[:3]) + ('..' if len(uniq) > 3 else '')


def byte_label(v, i):
    """Label for byte `i` of a value described by label `v`.

    'arg2[3]' -> 'arg2[3]'   (already a specific byte)
    'arg2'    -> 'arg2[3]'   (a whole value, take its byte 3)
    'a|3'     -> 'a|3'
    'this'    -> 'this'
    """
    if v is None:
        return None
    s = str(v)
    if s.endswith(']') or '|' in s:
        return s
    if s in ('this', 'rbp', 'rsp') or s.startswith('this+'):
        return s
    return '%s[%d]' % (s, i)


def merge_bytes(cur, off, size, val):
    """Overwrite `size` bytes at `off` of `cur` (Sym/None) with `val` (Sym/None)."""
    base = list(cur.b) if isinstance(cur, Sym) else [None] * SYM_LEN
    while len(base) < off + size:
        base.append(None)
    for i in range(size):
        if val is None:
            base[off + i] = None
        else:
            v = val if isinstance(val, Sym) else norm(val)
            base[off + i] = v.byte(i) if v is not None else None
    return Sym(base)


def norm(v):
    """Normalize a value produced by the interpreter into a Sym or None."""
    if v is None:
        return None
    if isinstance(v, Sym):
        return v if v.v else None
    s = str(v)
    if s.startswith('imm:'):
        return Sym.imm(int(s.split('0x')[1], 16))
    if s == 'this':
        return Sym.root('this')
    if s.startswith('this+'):
        return Sym.root(s)
    if s.startswith('arg'):
        return Sym.root(s)
    return Sym.root(s)


def local_key(disp):
    """Memory-map key for a stack operand at [rbp+disp].

    The model keeps one byte per distinct stack address, so [rbp-4] and
    [rbp-1] are separate bytes of the same 4-byte local.  That matches how GCC
    emits code: it stores the dword at [rbp-4] and later reads its bytes at
    [rbp-4], [rbp-3], [rbp-2], [rbp-1].
    """
    return disp


def local_off(disp):
    """Byte offset inside the addressed location (always 0 byte-addressed)."""
    return 0


class Cell:
    """Byte-addressable symbolic storage holding a label or None per byte."""
    __slots__ = ('b',)

    def __init__(self, b=None, fill=None):
        if b is None:
            b = [fill] * SYM_LEN
        self.b = list(b)
        while len(self.b) < SYM_LEN:
            self.b.append(None)

    def write(self, off, size, val):
        """Write `size` bytes of `val` (a Sym or None) at byte offset `off`.

        A whole-width pointer value written to a cell replaces the cell
        entirely, so `mov [rbp+0x10], rcx` followed by `mov rax, [rbp+0x10]`
        round-trips as the symbolic pointer 'this'.
        """
        while len(self.b) < off + size:
            self.b.append(None)
        if val is None:
            for i in range(size):
                self.b[off + i] = None
            return
        if not isinstance(val, Sym):
            val = norm(val)
        if val is None:
            for i in range(size):
                self.b[off + i] = None
            return
        # A pointer/argument marker (every known byte carries the same label)
        # written at byte 0 replaces the whole cell, so a later `mov rax,[..]`
        # round-trips as that symbolic pointer.
        uniq = set(x for x in val.b if x is not None)
        if off == 0 and size >= SYM_LEN and len(uniq) == 1:
            self.b = [next(iter(uniq))] * SYM_LEN
            return
        for i in range(size):
            # The written value is little-endian starting at its own byte 0,
            # which lands at cell byte `off + i`.
            self.b[off + i] = val.byte(i)

    def read(self, off, size):
        """Read `size` bytes as a Sym (or None when any byte is unknown)."""
        while len(self.b) < off + size:
            self.b.append(None)
        return Sym(list(self.b[off:off + size]))


def _expand(s):
    """Expand a symbolic value into a list of byte labels, or None if unknown.

    Handles arbitrary nesting of '{a,b,c}' tuples and 'X|n' byte selections,
    e.g. '{{arg2|0,arg2|1}|0}' -> ['arg2|0'].
    """
    s = s.strip()
    if s.startswith('{') and s.endswith('}'):
        inner = s[1:-1]
        parts, depth, cur = [], 0, ''
        for ch in inner:
            if ch == '{':
                depth += 1
            elif ch == '}':
                depth -= 1
            if ch == ',' and depth == 0:
                parts.append(cur)
                cur = ''
            else:
                cur += ch
        if cur:
            parts.append(cur)
        out = []
        for p in parts:
            e = _expand(p)
            if e is None:
                return None
            out.extend(e)
        return out
    if '|' in s:
        base, idx = s.rsplit('|', 1)
        if not idx.isdigit():
            return None
        e = _expand(base)
        if e is None:
            return None
        n = int(idx)
        if n < len(e):
            return [e[n]]
        return None
    # A bare symbolic root denotes a full 8-byte value (register / argument).
    # All eight bytes share the same label; explicit '|n' suffixes are only
    # introduced when a genuine byte slice is taken.
    return [s] * 8


def pick(val, off, size):
    """Select `size` bytes at `off`; returns 'root|n' (1 byte) or '{...}'."""
    if val is None:
        return None
    v = str(val)
    # Fast path: a plain single-byte label.
    if '|' in v and not v.startswith('{'):
        base, idx = v.rsplit('|', 1)
        if idx.isdigit() and '{' not in base:
            if size == 1:
                return '%s|%d' % (base, int(idx) + off)
            return '{%s}' % ','.join('%s|%d' % (base, int(idx) + off + i) for i in range(size))
    e = _expand(v)
    if e is None or len(e) < off + size:
        return None
    sel = e[off:off + size]
    if size == 1:
        return sel[0]
    return '{%s}' % ','.join(sel)


# ---------------------------------------------------------------- interpreter
SENDER = {}
for _nm in ('SendUartCmd', 'SendClientCmd'):
    for _va, _n in S.find(_nm):
        SENDER[_va] = _n


def simulate(code, trace=False, base=0):
    """Abstract interpretation of one command method.

    Returns (cmd, length, assigns, txbase) where `assigns` maps payload byte
    index -> symbolic label of that byte.

    Entry state (SysV x86-64 member function, `this` in rcx):
        rcx = pointer to the object
        edx/r8d/r9d = arg1/arg2/arg3 (low 4 bytes known, high 4 unknown)
        [rbp+0x30], [rbp+0x38] ... = arg4, arg5, ...
    Locals live at [rbp-8], [rbp-16] ... and are byte-addressable.
    """
    regs = {
        'rcx': Sym.root('this'),
        'rbp': 'rbp',
        'rsp': 'rsp',
    }
    for i, r in enumerate(('rdx', 'r8', 'r9')):
        regs[r] = Sym(['arg%d[%d]' % (i + 1, b) for b in range(4)] + [None] * 4)

    # Byte-addressed stack memory: key = displacement from rbp, value = label.
    # Argument slots above rbp are prefilled with the incoming parameter bytes.
    stack = {}
    for i in range(8):
        for b in range(8):
            stack[0x10 + i * 8 + b] = 'arg%d[%d]' % (i + 1, b)

    assigns = {}
    cmd = ln = None
    txbase = None
    step = 0

    for ins in md.disasm(code, base):
        step += 1
        ops = ins.operands
        m = ins.mnemonic

        if trace:
            print('%3d %-38s | rax=%-34s rdx=%-30s' % (
                step, m + ' ' + ins.op_str, regs.get('rax'), regs.get('rdx')))

        # ---------------------------------------------------------- helpers
        def root_of(v):
            """Pointer name carried by a register value, or None."""
            if isinstance(v, str):
                return v
            if isinstance(v, Sym):
                known = set(x for x in v.b if x is not None)
                if len(known) == 1 and v.all_known():
                    return next(iter(known))
            return None

        def mem_root(mem):
            if not mem.base:
                return None
            return root_of(regs.get(canon(md.reg_name(mem.base))))

        def stack_read(disp, size):
            """Read `size` bytes starting at [rbp+disp]."""
            return Sym([stack.get(disp + i) for i in range(size)])

        def stack_write(disp, size, sval):
            """Write `size` bytes of `sval` starting at [rbp+disp]."""
            for i in range(size):
                stack[disp + i] = None if sval is None else sval.byte(i)

        def mem_cell(mem):
            """True when the operand addresses byte-addressable stack memory."""
            if not mem.base:
                return False
            return regs.get(canon(md.reg_name(mem.base))) == 'rbp'

        # ---------------------------------------------------------- mov family
        if m in ('mov', 'movzx', 'movsx', 'movsxd') and len(ops) == 2:
            dst, src = ops

            if dst.type == X86_OP_REG and src.type == X86_OP_REG \
                    and canon(md.reg_name(dst.reg)) == 'rbp' \
                    and canon(md.reg_name(src.reg)) == 'rsp':
                regs['rbp'] = 'rbp'          # standard prologue
                continue

            # ---- source operand -> Sym ----------------------------------
            sval = None
            if src.type == X86_OP_IMM:
                sval = Sym.imm(src.imm)
            elif src.type == X86_OP_REG:
                sname = canon(md.reg_name(src.reg))
                soff, ssize = RW.get(md.reg_name(src.reg), (0, 8))
                raw = regs.get(sname)
                if isinstance(raw, Sym):
                    sval = raw.slice(soff, ssize)
            elif src.type == X86_OP_MEM:
                mroot = mem_root(src.mem)
                if mroot == 'this':
                    sval = Sym.root('this+%#x' % src.mem.disp)
                elif mroot and mroot.startswith('this+'):
                    sval = Sym.root('this+%#x' % (int(mroot[5:], 16) + src.mem.disp))
                else:
                    if mem_cell(src.mem):
                        size = 8 if m == 'mov' else max(1, min(src.size, 8))
                        sval = stack_read(src.mem.disp, size)
                        if trace:
                            print('        [LOAD] [rbp%+#x] size=%d -> %s' % (
                                src.mem.disp, size, sval))

            # ---- destination -------------------------------------------
            if dst.type == X86_OP_MEM:
                droot = mem_root(dst.mem)
                if droot == 'this' and dst.mem.disp >= 0x200:
                    if txbase is None:
                        txbase = dst.mem.disp
                    k = dst.mem.disp - txbase
                    for i in range(dst.size):
                        assigns[k + i] = byte_label(sval.byte(i), i) if sval else None
                    if trace:
                        print('        [TXSTORE] body[%d..%d] <- %s' % (
                            k, k + dst.size - 1, sval))
                    continue
                if mem_cell(dst.mem):
                    if trace:
                        print('        [STORE] [rbp%+#x] size=%d <- %s' % (
                            dst.mem.disp, dst.size, sval))
                    stack_write(dst.mem.disp, dst.size, sval)
                continue

            if dst.type == X86_OP_REG:
                d = canon(md.reg_name(dst.reg))
                doff, dsize = RW.get(md.reg_name(dst.reg), (0, 8))
                if trace:
                    print('        [REG] %s <- %s' % (md.reg_name(dst.reg), sval))
                if sval is None:
                    regs[d] = None
                elif dsize >= 8:
                    regs[d] = sval
                elif doff == 0:
                    regs[d] = sval.slice(0, dsize)
                else:
                    regs[d] = merge_bytes(regs.get(d), 1, 1, sval)
                continue
            continue

        # ---------------------------------------------------------- lea
        if m == 'lea' and len(ops) == 2 and ops[0].type == X86_OP_REG \
                and ops[1].type == X86_OP_MEM:
            d = canon(md.reg_name(ops[0].reg))
            mroot = mem_root(ops[1].mem)
            if mroot == 'this':
                regs[d] = Sym.root('this+%#x' % ops[1].mem.disp)
            elif isinstance(mroot, str) and mroot.startswith('this+'):
                regs[d] = Sym.root('this+%#x' % (int(mroot[5:], 16) + ops[1].mem.disp))
            else:
                regs[d] = None
            continue

        # ---------------------------------------------------------- call
        if m == 'call' and len(ops) == 1 and ops[0].type == X86_OP_IMM \
                and ops[0].imm in SENDER:
            cmd = regs.get('rdx')
            ln = regs.get('r8')
            break

        # ---------------------------------------------------------- default
        if ops and ops[0].type == X86_OP_REG:
            d = canon(md.reg_name(ops[0].reg))
            if d not in ('rsp',):
                regs[d] = None
        elif ops and ops[0].type == X86_OP_MEM:
            if mem_cell(ops[0].mem):
                for i in range(ops[0].size):
                    stack[ops[0].mem.disp + i] = None

    return cmd, ln, assigns, txbase


# ---------------------------------------------------------------- reporting
HINTS = [
    ('RFID_GbSeWrite', 'GB secure write'), ('RFID_GbSeRead', 'GB secure read'),
    ('RFID_GbSeLock', 'GB secure lock'), ('RFID_GbSeKill', 'GB secure kill'),
    ('RFID_GbWrite', 'GB/T29768 write'), ('RFID_GbRead', 'GB/T29768 read'),
    ('RFID_GbLock', 'GB/T29768 lock'), ('RFID_GbKill', 'GB/T29768 kill'),
    ('RFID_Write', 'ISO18000-6C write'), ('RFID_Read', 'ISO18000-6C read'),
    ('RFID_Lock', 'ISO18000-6C lock'), ('RFID_Kill', 'ISO18000-6C kill'),
    ('RFID_GbDoubleIdentify', 'GB double identify'),
    ('Set_Frequece_Standard', 'set freq standard'), ('Set_Frequece', 'set frequency'),
    ('Set_Multi_Ant_power', 'set 4-ant power'), ('Set_Eight_Ant_power', 'set 8-ant power'),
    ('Set_Epc_Match', 'set EPC match'), ('Switch_Ant_Inventory', 'switch-ant inventory'),
    ('Custom_Session_Inventory', 'custom session inv'), ('Custom_Inventory', 'custom inventory'),
    ('RealTime_Inventory', 'realtime inventory'), ('Start_Buff_Inventory', 'buffered inventory'),
    ('Reader_App_Upgrade', 'app fw upgrade'), ('Baseband_Firmware_Upgrade', 'baseband upgrade'),
    ('Set_Uart_BoundRate', 'set uart baudrate'), ('Set_RS485_Addr', 'set RS485 addr'),
    ('Set_Beep_status', 'set beeper'), ('Set_Work_Ant', 'set work antenna'),
    ('Get_Work_Ant', 'get work antenna'), ('Set_Fast_Epc', 'set fast-EPC'),
    ('Get_Fast_Epc', 'get fast-EPC'), ('Set_Ant_power', 'set ant power'),
    ('Get_Ant_Power', 'get ant power'), ('Set_RF_Link', 'set RF link param'),
    ('Get_RF_Link', 'get RF link param'), ('Set_Gpio_Value', 'set GPIO'),
    ('Read_Gpio_Value', 'read GPIO'), ('Get_Tag_Buff', 'get tag buffer'),
    ('Get_and_Clear_Buff', 'get+clear tag buffer'), ('Get_Tag_Num', 'get tag count'),
    ('Clear_Tag_Buff', 'clear tag buffer'), ('Get_Epc_Match', 'get EPC match'),
    ('Read_Frameware_version', 'read firmware version'), ('Read_Work_Temp', 'read temp'),
    ('Restart_Reader', 'restart reader'), ('Stop_Inventory', 'stop inventory'),
    ('SaveParam', 'save params'), ('Save_Param', 'save params'), ('Reset_Para', 'factory reset'),
    ('Get_Frequence', 'get frequency'), ('Uart_Moniter_Send', 'uart monitor passthrough'),
]


def hint(n):
    for k, v in HINTS:
        if k in n:
            return v
    return ''


def num(v):
    """Command/length values are modelled as Sym immediates."""
    if isinstance(v, int):
        return v & 0xFF
    if isinstance(v, Sym):
        known = [x for x in v.b if x is not None]
        if known and len(set(known)) == 1:
            try:
                return int(known[0]) & 0xFF
            except ValueError:
                return None
    if isinstance(v, str):
        try:
            return int(v) & 0xFF
        except ValueError:
            return None
    return None


def fmt_body(label):
    """Render a payload byte label."""
    if label is None:
        return '-- (uninitialised)'
    try:
        return 'const 0x%02X' % int(label)
    except (TypeError, ValueError):
        return label


def main():
    lines = []
    for filt, title in (('LibWinUart', 'LibWinUart  (UART / HID transport)'),
                        ('tcp_client', 'tcp_client (TCP transport)')):
        lines.append('=' * 90)
        lines.append(title)
        lines.append('=' * 90)
        rows = []
        for (va, end, name, code) in FUNCS:
            if filt not in name:
                continue
            cmd, ln, assigns, txbase = simulate(code, base=va)
            if not assigns and cmd is None:
                continue
            rows.append((name, va, cmd, ln, assigns))
        rows.sort(key=lambda r: (num(r[2]) if num(r[2]) is not None else 9999,
                                 num(r[3]) if num(r[3]) is not None else 0))
        for (name, va, cmd, ln, assigns) in rows:
            cv, lv = num(cmd), num(ln)
            lines.append('')
            lines.append('  cmd=%-6s frame_len=%-5s %-24s %s' % (
                '0x%02X' % cv if cv is not None else '??',
                lv if lv is not None else '?', hint(name), name))
            if not assigns:
                continue
            for k in range(max(assigns) + 1):
                lines.append('        payload[%2d] -> frame[%2d] = %s' % (
                    k, k + 4, fmt_body(assigns.get(k))))
        lines.append('')
    txt = '\n'.join(lines)
    with open(os.path.join(HERE, 'protocol_dump.txt'), 'w', encoding='utf-8') as f:
        f.write(txt)
    print(txt)


if __name__ == '__main__':
    main()
