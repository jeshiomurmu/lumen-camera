#!/usr/bin/env python3
"""Prints 'width height orientation' of a JPEG (no third-party modules)."""
import struct, sys

def info(path):
    d = open(path, 'rb').read()
    w = h = None
    orient = 1
    i = 2
    while i < len(d) - 9:
        if d[i] != 0xFF:
            i += 1
            continue
        m = d[i + 1]
        if m in (0xD8, 0x01) or 0xD0 <= m <= 0xD7:
            i += 2
            continue
        ln = struct.unpack('>H', d[i + 2:i + 4])[0]
        seg = d[i + 4:i + 2 + ln]
        if m == 0xE1 and seg[:6] == b'Exif\x00\x00':
            t = seg[6:]
            e = '<' if t[:2] == b'II' else '>'
            off = struct.unpack(e + 'I', t[4:8])[0]
            n = struct.unpack(e + 'H', t[off:off + 2])[0]
            for k in range(n):
                ent = t[off + 2 + 12 * k: off + 14 + 12 * k]
                tag = struct.unpack(e + 'H', ent[:2])[0]
                if tag == 0x0112:
                    orient = struct.unpack(e + 'H', ent[8:10])[0]
        if m in (0xC0, 0xC1, 0xC2):
            h, w = struct.unpack('>HH', seg[1:5])
            break
        i += 2 + ln
    return w, h, orient

w, h, o = info(sys.argv[1])
print(w, h, o)
