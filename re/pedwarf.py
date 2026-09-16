"""Minimal PE + DWARF4 reader for the MinGW-built rfid_main_pannel.exe.

Provides section access, abbreviation/DIEs iteration, and a line-number lookup.
"""
import struct
import bisect
import os

PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'rfid_main_pannel.exe')

# ---------------------------------------------------------------- PE parsing
class PE:
    def __init__(self, path=PATH):
        self.path = path
        with open(path, 'rb') as f:
            self.data = f.read()
        d = self.data
        pe_off = struct.unpack_from('<I', d, 0x3C)[0]
        assert d[pe_off:pe_off + 4] == b'PE\0\0', 'not a PE'
        coff = pe_off + 4
        nsec, = struct.unpack_from('<H', d, coff + 2)
        opt_size, = struct.unpack_from('<H', d, coff + 16)
        opt = coff + 20
        assert struct.unpack_from('<H', d, opt)[0] == 0x20b, 'not PE32+'
        self.image_base, = struct.unpack_from('<Q', d, opt + 24)
        sym_ptr, nsym, = struct.unpack_from('<II', d, coff + 8)
        strtab = sym_ptr + nsym * 18
        strtab_size, = struct.unpack_from('<I', d, strtab)
        strtab_data = d[strtab:strtab + strtab_size]

        def longname(off):
            e = strtab_data.index(b'\0', off)
            return strtab_data[off:e].decode('latin1')

        sec_off = opt + opt_size
        self.sections = []
        for i in range(nsec):
            o = sec_off + i * 40
            raw = d[o:o + 8].rstrip(b'\0').decode('latin1')
            if raw.startswith('/') and raw[1:].isdigit():
                raw = longname(int(raw[1:]))
            vsize, vma, rawsize, rawptr = struct.unpack_from('<IIII', d, o + 8)
            self.sections.append(dict(name=raw, vma=vma, vsize=vsize,
                                      rawptr=rawptr, rawsize=rawsize))
        self.by_name = {s['name']: s for s in self.sections}

    def sec(self, name):
        s = self.by_name[name]
        return self.data[s['rawptr']:s['rawptr'] + s['rawsize']]

    def read_va(self, va, n):
        for s in self.sections:
            if s['vma'] <= va < s['vma'] + max(s['vsize'], s['rawsize']):
                off = s['rawptr'] + (va - s['vma'])
                return self.data[off:off + n]
        raise KeyError(hex(va))

    def cstr_at_va(self, va):
        out = bytearray()
        while True:
            b = self.read_va(va, 1)
            if not b or b[0] == 0:
                return out.decode('utf-8', 'replace')
            out += b
            va += 1


# ---------------------------------------------------------------- DWARF consts
DW_TAG = {
    0x01: 'array_type', 0x02: 'class_type', 0x03: 'entry_point', 0x04: 'enumeration_type',
    0x05: 'formal_parameter', 0x08: 'imported_declaration', 0x0a: 'label', 0x0b: 'lexical_block',
    0x0d: 'member', 0x0f: 'pointer_type', 0x11: 'compile_unit', 0x12: 'string_type',
    0x13: 'structure_type', 0x15: 'subroutine_type', 0x16: 'typedef', 0x17: 'union_type',
    0x18: 'unspecified_parameters', 0x19: 'variant', 0x1a: 'variable', 0x1b: 'volatile_type',
    0x1d: 'inlined_subroutine', 0x21: 'subrange_type', 0x22: 'base_type', 0x23: 'const_type',
    0x24: 'enumerator', 0x25: 'file_type', 0x26: 'packed_type', 0x27: 'namespace',
    0x28: 'imported_module', 0x29: 'unspecified_type', 0x2a: 'partial_unit', 0x2b: 'imported_unit',
    0x2e: 'subprogram', 0x2f: 'variable', 0x30: 'volatile_type', 0x31: 'subprogram',
    0x32: 'lexical_block', 0x33: 'unspecified_type', 0x34: 'partial_unit', 0x35: 'imported_unit',
    0x36: 'imported_unit', 0x37: 'unspecified_type', 0x38: 'imported_module',
    0x39: 'type_unit', 0x3a: 'rvalue_reference_type',
    0x3b: 'template_alias', 0x3f: 'template_type_parameter', 0x40: 'template_value_parameter',
    0x41: 'gnu_template_template_parameter', 0x42: 'gnu_template_parameter_pack',
    0x43: 'gnu_formal_parameter_pack', 0x44: 'call_site', 0x45: 'call_site_parameter',
    0x46: 'gnu_call_site', 0x47: 'gnu_call_site_parameter', 0x48: 'skeleton_unit',
    0x49: 'immutable_type', 0x4106: 'gnu_vector_type', 0x4107: 'gnu_template_template_param',
    0x4108: 'gnu_call_site', 0x4109: 'gnu_call_site_parameter', 0x410a: 'gnu_string_type',
}
DW_AT = {
    0x01: 'location', 0x02: 'name', 0x03: 'ordering', 0x09: 'ordering', 0x0b: 'byte_size',
    0x0c: 'bit_offset', 0x0d: 'bit_size', 0x10: 'stmt_list', 0x11: 'low_pc', 0x12: 'high_pc',
    0x13: 'language', 0x15: 'discr', 0x16: 'discr_value', 0x17: 'visibility', 0x18: 'import',
    0x19: 'string_length', 0x1a: 'common_reference', 0x1b: 'comp_dir', 0x1c: 'const_value',
    0x1d: 'containing_type', 0x1e: 'default_value', 0x20: 'inline', 0x21: 'is_optional',
    0x22: 'lower_bound', 0x23: 'producer', 0x24: 'prototyped', 0x25: 'return_addr',
    0x26: 'start_scope', 0x27: 'bit_stride', 0x28: 'upper_bound', 0x2a: 'abstract_origin',
    0x2b: 'accessibility', 0x2c: 'address_class', 0x2d: 'artificial', 0x2e: 'base_types',
    0x2f: 'calling_convention', 0x30: 'count', 0x31: 'data_member_location',
    0x32: 'decl_column', 0x33: 'decl_file', 0x34: 'decl_line', 0x35: 'declaration',
    0x3a: 'friend', 0x3b: 'identifier_case', 0x3c: 'macro_info', 0x3d: 'namelist_item',
    0x3e: 'priority', 0x3f: 'segment', 0x40: 'specification', 0x41: 'static_link',
    0x42: 'type', 0x43: 'use_location', 0x44: 'variable_parameter', 0x45: 'virtuality',
    0x46: 'vtable_elem_location', 0x47: 'allocated', 0x48: 'associated', 0x49: 'data_location',
    0x4a: 'byte_stride', 0x4b: 'entry_pc', 0x4c: 'use_UTF8', 0x4d: 'extension',
    0x4e: 'ranges', 0x4f: 'trampoline', 0x50: 'call_column', 0x51: 'call_file',
    0x52: 'call_line', 0x53: 'call_origin', 0x54: 'bit_offset', 0x55: 'call_frame_cfa',
    0x56: 'macro_info', 0x57: 'call_origin', 0x58: 'call_pc', 0x59: 'call_value',
    0x5a: 'call_data_location', 0x5b: 'call_data_value', 0x5c: 'call_target',
    0x5d: 'call_origin', 0x5e: 'call_origin', 0x5f: 'call_origin', 0x60: 'call_origin',
    0x64: 'signature', 0x65: 'main_subprogram', 0x66: 'data_bit_offset', 0x67: 'const_expr',
    0x68: 'enum_class', 0x69: 'linkage_name', 0x6a: 'string_length_bit_size',
    0x6b: 'string_length_byte_size', 0x6c: 'rank', 0x6d: 'str_offsets_base',
    0x6e: 'addr_base', 0x6f: 'rnglists_base', 0x70: 'dwo_name', 0x71: 'reference',
    0x72: 'rvalue_reference', 0x73: 'macros', 0x74: 'call_all_calls', 0x75: 'call_all_source_calls',
    0x76: 'call_all_tail_calls', 0x77: 'call_return_pc', 0x78: 'call_value',
    0x79: 'call_origin', 0x7a: 'call_parameter', 0x7b: 'call_pc', 0x7c: 'call_origin',
    0x7d: 'call_origin', 0x7e: 'call_origin', 0x7f: 'call_origin', 0x80: 'call_origin',
    0x81: 'call_origin', 0x82: 'call_origin', 0x83: 'call_origin',
    0x2000: 'MIPS_loop', 0x2001: 'MIPS_stride', 0x2002: 'MIPS_stride_byte',
    0x2003: 'MIPS_stride_elem', 0x2004: 'MIPS_ptr_dopetype', 0x2005: 'MIPS_allocatable_dopetype',
    0x2006: 'MIPS_assumed_shape_dopetype', 0x2007: 'MIPS_assumed_size',
    0x2101: 'GNU_all_call_sites', 0x2102: 'GNU_all_tail_call_sites',
    0x2103: 'GNU_all_typeunits', 0x2104: 'GNU_macros', 0x2105: 'GNU_deleted',
    0x2106: 'GNU_dwo_name', 0x2107: 'GNU_dwo_id', 0x2108: 'GNU_ranges_base',
    0x2109: 'GNU_addr_base', 0x210a: 'GNU_pubnames', 0x210b: 'GNU_pubtypes',
    0x2111: 'GNU_numerator', 0x2112: 'GNU_denominator', 0x2113: 'GNU_MIPS_loop_begin',
    0x2114: 'GNU_MIPS_tail_loop_begin', 0x2115: 'GNU_MIPS_epilog_begin',
    0x2116: 'GNU_MIPS_loop_unroll_factor', 0x2117: 'GNU_MIPS_software_pipeline_depth',
    0x2118: 'GNU_MIPS_linkage_name', 0x2119: 'GNU_MIPS_stride',
    0x211a: 'GNU_MIPS_stride_byte', 0x211b: 'GNU_MIPS_stride_elem',
    0x211c: 'GNU_MIPS_ptr_dopetype', 0x211d: 'GNU_MIPS_allocatable_dopetype',
    0x211e: 'GNU_MIPS_assumed_shape_dopetype', 0x211f: 'GNU_MIPS_assumed_size',
    0x2120: 'GNU_ft_entries', 0x2121: 'GNU_MIPS_fp_register',
    0x2130: 'GNU_locviews', 0x2131: 'GNU_entry_view',
    0x2133: 'GNU_ranges_base', 0x2134: 'GNU_pubnames', 0x2135: 'GNU_pubtypes',
    0x2136: 'GNU_odr_signature', 0x2137: 'GNU_template_parameter_pack',
    0x2138: 'GNU_formal_parameter_pack', 0x2139: 'GNU_call_site_value',
    0x213a: 'GNU_call_site_data_value', 0x213b: 'GNU_call_site_target',
    0x213c: 'GNU_call_site_target_clobbered', 0x213d: 'GNU_tail_call',
    0x213e: 'GNU_all_tail_call_sites', 0x213f: 'GNU_all_call_sites',
    0x2140: 'GNU_all_source_call_sites', 0x2141: 'GNU_macros', 0x2142: 'GNU_deleted',
    0x2143: 'GNU_loclists', 0x2144: 'GNU_macros', 0x2145: 'GNU_dwo_name',
    0x2146: 'GNU_dwo_id', 0x2147: 'GNU_ranges_base', 0x2148: 'GNU_addr_base',
}
DW_FORM = {
    0x01: 'addr', 0x03: 'block2', 0x04: 'block4', 0x05: 'data2', 0x06: 'data4',
    0x07: 'data8', 0x08: 'string', 0x09: 'block', 0x0a: 'block', 0x0b: 'data1',
    0x0c: 'flag', 0x0d: 'sdata', 0x0e: 'strp', 0x0f: 'udata', 0x10: 'ref_addr',
    0x11: 'ref1', 0x12: 'ref2', 0x13: 'ref4', 0x14: 'ref8', 0x15: 'ref_udata',
    0x16: 'indirect', 0x17: 'sec_offset', 0x18: 'exprloc', 0x19: 'flag_present',
    0x1a: 'strx', 0x1b: 'addrx', 0x1c: 'ref_sup4', 0x1d: 'strp_sup',
    0x1e: 'data16', 0x1f: 'line_strp', 0x20: 'ref_sig8', 0x21: 'implicit_const',
    0x22: 'loclistx', 0x23: 'rnglistx', 0x24: 'ref_sup8', 0x25: 'strx1',
    0x26: 'strx2', 0x27: 'strx3', 0x28: 'strx4', 0x29: 'addrx1', 0x2a: 'addrx2',
    0x2b: 'addrx3', 0x2c: 'addrx4',
}
DW_LANG = {0x01: 'C89', 0x02: 'C', 0x04: 'C++', 0x0d: 'C++11'}


class DwarfError(Exception):
    pass


class Dwarf:
    def __init__(self, pe):
        self.pe = pe
        self.info = pe.sec('.debug_info')
        self.abbrev = pe.sec('.debug_abbrev')
        self.str = pe.sec('.debug_str')
        self.line = pe.sec('.debug_line')
        self.ranges = pe.sec('.debug_ranges')
        self._abbrev_cache = {}
        self.cus = []           # list of dicts: offset, version, abbrev_off, addr_size, dies
        self.dies = []          # flat list of DIE dicts
        self._parse_units()
        self._line_cache = {}

    # -------------------------------------------------- abbrev tables
    def abbrev_table(self, offset):
        if offset in self._abbrev_cache:
            return self._abbrev_cache[offset]
        tbl, p = {}, offset
        d = self.abbrev

        def uleb(p):
            r = s = 0
            while True:
                b = d[p]; p += 1
                r |= (b & 0x7f) << s
                if not b & 0x80:
                    return r, p
                s += 7

        while True:
            code, p = uleb(p)
            if code == 0:
                break
            tag, p = uleb(p)
            has_children = d[p]; p += 1
            specs = []
            while True:
                at, p = uleb(p)
                form, p = uleb(p)
                if form == 0x21:  # implicit_const
                    val, p = sleb(d, p)
                    specs.append((at, form, val))
                    continue
                if at == 0 and form == 0:
                    break
                specs.append((at, form, None))
            tbl[code] = dict(tag=tag, has_children=has_children, specs=specs)
        self._abbrev_cache[offset] = tbl
        return tbl

    # -------------------------------------------------- unit walk
    def _parse_units(self):
        d = self.info
        pos = 0
        n = len(d)
        while pos + 11 < n:
            cu_start = pos
            length, = struct.unpack_from('<I', d, pos)
            if length == 0:
                pos = cu_start + 4
                continue
            if length == 0xffffffff:
                break  # DWARF64 not expected
            cu_end = pos + 4 + length
            version, = struct.unpack_from('<H', d, pos + 4)
            aboff, = struct.unpack_from('<I', d, pos + 6)
            addr_size = d[pos + 10]
            p = pos + 11
            tbl = self.abbrev_table(aboff)
            cu = dict(offset=cu_start, version=version, abbrev=aboff,
                      addr_size=addr_size, dies=[])
            self.cus.append(cu)
            # depth-first, children tracked by has_children
            stack = [cu]
            while p < cu_end:
                die_off = p
                code = d[p]; p += 1
                if code >= 0x80:  # multi-byte ULEB (rare)
                    p -= 1
                    code, p = uleb(d, p)
                if code == 0:
                    if len(stack) > 1:
                        stack.pop()
                    continue
                spec = tbl.get(code)
                if spec is None:
                    raise DwarfError('bad abbrev code %d @%x' % (code, die_off))
                die = dict(offset=die_off, tag=spec['tag'], code=code,
                           has_children=spec['has_children'], attrs={},
                           parent=stack[-1] if len(stack) > 1 else None,
                           cu=cu, depth=len(stack) - 1)
                for (at, form, ic) in spec['specs']:
                    val, p = self._read_form(form, p, cu, ic)
                    die['attrs'][at] = val
                cu['dies'].append(die)
                self.dies.append(die)
                if spec['has_children']:
                    stack.append(die)
            pos = cu_end

    def _read_form(self, form, p, cu, ic):
        d = self.info
        asz = cu['addr_size']
        if form == 0x01:
            v = int.from_bytes(d[p:p + asz], 'little'); return v, p + asz
        if form == 0x0b:
            return d[p], p + 1
        if form == 0x05:
            return struct.unpack_from('<H', d, p)[0], p + 2
        if form == 0x06:
            return struct.unpack_from('<I', d, p)[0], p + 4
        if form == 0x07:
            return struct.unpack_from('<Q', d, p)[0], p + 8
        if form == 0x0d:
            return struct.unpack_from('<b', d, p)[0], p + 1
        if form == 0x0c:
            return d[p], p + 1
        if form == 0x0f:
            v, p = uleb(d, p); return v, p
        if form == 0x08:
            e = d.index(b'\0', p)
            return d[p:e].decode('utf-8', 'replace'), e + 1
        if form == 0x0e:
            off, = struct.unpack_from('<I', d, p)
            if off >= len(self.str):
                return '<badstrp %x>' % off, p + 4
            e = self.str.index(b'\0', off)
            return self.str[off:e].decode('utf-8', 'replace'), p + 4
        if form == 0x0a or form == 0x09:
            ln, = struct.unpack_from('<H' if form == 0x0a else '<I', d, p)
            p += 2 if form == 0x0a else 4
            return d[p:p + ln], p + ln
        if form == 0x18:
            ln, p = uleb(d, p)
            return d[p:p + ln], p + ln
        if form == 0x17:
            return struct.unpack_from('<I', d, p)[0], p + 4
        if form == 0x13:
            return struct.unpack_from('<I', d, p)[0], p + 4
        if form == 0x10:
            return struct.unpack_from('<Q', d, p)[0], p + 8
        if form == 0x11:
            return d[p], p + 1
        if form == 0x12:
            return struct.unpack_from('<H', d, p)[0], p + 2
        if form == 0x15:
            v, p = uleb(d, p); return v, p
        if form == 0x16:
            f2 = d[p]; p += 1
            return self._read_form(f2, p, cu, ic)
        if form == 0x19:
            return True, p
        if form == 0x1e:
            return d[p:p + 16], p + 16
        if form == 0x20:
            return d[p:p + 8], p + 8
        if form == 0x21:
            return ic, p
        raise DwarfError('unhandled form %#x' % form)

    # -------------------------------------------------- helpers
    def name(self, die):
        return die['attrs'].get(0x02, '')

    def type_name(self, die, depth=0):
        if die is None or depth > 12:
            return '?'
        t = die['tag']
        if t == 0x16:
            return self.type_name(self.ref(die, 0x42), depth + 1)
        if t in (0x26, 0x30, 0x1b, 0x38, 0x49):
            return self.type_name(self.ref(die, 0x42), depth + 1) + (
                ' const' if t == 0x26 else ' volatile' if t in (0x30, 0x1b) else '')
        if t == 0x0f:
            return self.type_name(self.ref(die, 0x42), depth + 1) + '*'
        if t in (0x3a, 0x42):
            return self.type_name(self.ref(die, 0x42), depth + 1) + ('&&' if t == 0x3a else '&')
        if t == 0x01:
            et = self.ref(die, 0x42)
            cnt = None
            for c in self.children(die):
                if c['tag'] == 0x21:
                    cnt = c['attrs'].get(0x28) or c['attrs'].get(0x2f)
            return '%s[%s]' % (self.type_name(et, depth + 1), '' if cnt is None else cnt)
        if t in (0x13, 0x02, 0x17):
            nm = self.name(die)
            return nm or '<anon %s>' % DW_TAG.get(t, t)
        if t == 0x04:
            return 'enum ' + (self.name(die) or '?')
        if t == 0x22:
            return self.name(die) or ('base%#x' % die['attrs'].get(0x37, 0))
        if t == 0x15:
            return 'func'
        if t == 0x31:
            return 'void'
        return self.name(die) or DW_TAG.get(t, str(t))

    def ref(self, die, at=0x42):
        off = die['attrs'].get(at)
        if off is None:
            return None
        return self.by_offset.get(off) if hasattr(self, 'by_offset') else None

    def children(self, die):
        for c in die['cu']['dies']:
            if c.get('parent') is die:
                yield c
        return

    def build_index(self):
        self.by_offset = {d['offset']: d for d in self.dies}
        return self


def sleb(d, p):
    r = s = 0
    while True:
        b = d[p]; p += 1
        r |= (b & 0x7f) << s
        s += 7
        if not b & 0x80:
            if b & 0x40:
                r -= (1 << s)
            return r, p


def uleb(d, p):
    r = s = 0
    while True:
        b = d[p]; p += 1
        r |= (b & 0x7f) << s
        if not b & 0x80:
            return r, p
        s += 7


if __name__ == '__main__':
    pe = PE()
    print('image base', hex(pe.image_base))
    for s in pe.sections:
        print('  %-16s vma=%08x size=%08x' % (s['name'], s['vma'], s['vsize']))
    dw = Dwarf(pe)
    print('CUs:', len(dw.cus), 'DIEs:', len(dw.dies))
    for cu in dw.cus[:400]:
        if not cu['dies']:
            continue
        top = cu['dies'][0]
        nm = dw.name(top)
        if 'wx' in nm or 'gcc' in nm or 'libstdc' in nm:
            continue
        print(' CU %-60s prod=%s' % (nm[:60], top['attrs'].get(0x23, '')[:40]))
