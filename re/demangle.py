"""Minimal Itanium C++ demangler for the symbol shapes used in this binary."""
import os
import re

TYPES = {
    'v': 'void', 'b': 'bool', 'c': 'char', 'a': 'signed char', 'h': 'unsigned char',
    's': 'short', 't': 'unsigned short', 'i': 'int', 'j': 'unsigned int',
    'l': 'long', 'm': 'unsigned long', 'x': 'long long', 'y': 'unsigned long long',
    'f': 'float', 'd': 'double', 'e': 'long double', 'w': 'wchar_t',
    'z': '...', 'Pv': 'void*', 'Kc': 'const char',
}


def _parse_type(s, i):
    """Return (typename, next_index)."""
    if i >= len(s):
        return ('?', i)
    c = s[i]
    if c in TYPES:
        return (TYPES[c], i + 1)
    if c == 'P':
        t, j = _parse_type(s, i + 1)
        return (t + '*', j)
    if c == 'R':
        t, j = _parse_type(s, i + 1)
        return (t + '&', j)
    if c == 'K':
        t, j = _parse_type(s, i + 1)
        return (t + ' const', j)
    if c in 'Sij':
        # substitution / template-param placeholders: keep it short
        return ('T', i + 1)
    if c == 'N':
        # nested name N ... E
        depth, j, parts = 1, i + 1, []
        while j < len(s) and depth:
            if s[j] == 'N':
                depth += 1
                j += 1
            elif s[j] == 'E':
                depth -= 1
                j += 1
            else:
                ln = 0
                k = j
                while k < len(s) and s[k].isdigit():
                    ln = ln * 10 + int(s[k])
                    k += 1
                if ln:
                    parts.append(s[k:k + ln])
                    j = k + ln
                else:
                    j += 1
        return ('::'.join(parts), j)
    ln = 0
    j = i
    while j < len(s) and s[j].isdigit():
        ln = ln * 10 + int(s[j])
        j += 1
    if ln:
        return (s[j:j + ln], j + ln)
    return ('?', i + 1)


def demangle(mangled):
    """Return a readable signature like 'LibWinUart::RFID_Read(unsigned char, unsigned int, unsigned short, unsigned int)'."""
    if not mangled.startswith('_Z'):
        return mangled
    s = mangled[2:]
    if s.startswith('N'):
        s = s[1:]
        parts, i, depth = [], 0, 1
        while i < len(s) and depth:
            if s[i] == 'E':
                depth -= 1
                i += 1
                break
            if s[i] in 'CI':
                i += 1
                continue
            ln = 0
            j = i
            while j < len(s) and s[j].isdigit():
                ln = ln * 10 + int(s[j])
                j += 1
            if not ln:
                i += 1
                continue
            parts.append(s[j:j + ln])
            i = j + ln
        name = '::'.join(parts)
    else:
        ln = 0
        i = 0
        while i < len(s) and s[i].isdigit():
            ln = ln * 10 + int(s[i])
            i += 1
        name = s[i:i + ln]
        i += ln
    args = []
    while i < len(s):
        t, i = _parse_type(s, i)
        args.append(t)
    return '%s(%s)' % (name, ', '.join(args))


if __name__ == '__main__':
    import sys
    for a in sys.argv[1:]:
        print(demangle(a))
