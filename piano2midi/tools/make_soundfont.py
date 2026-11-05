#!/usr/bin/env python3
"""Cut a small SoundFont (.sf2) out of a General MIDI SoundFont.

Keeps only the selected bank-0 presets together with the instruments and
samples they reference, and writes a valid SF2 2.01 file. Used to build the
app's built-in instrument set from FluidR3_GM.sf2 (MIT license, Frank Wen):

  python3 make_soundfont.py /usr/share/sounds/sf2/FluidR3_GM.sf2 \
      ../android/app/src/main/assets/instruments.sf2 0 4 24 25 32 40 48 73
"""
import struct
import sys

PHDR, BAG, MOD, GEN, INST, SHDR = 38, 4, 10, 4, 22, 46
GEN_INSTRUMENT, GEN_SAMPLE = 41, 53


def chunks(data, off, end):
    while off < end:
        cid = data[off:off + 4]
        size = struct.unpack_from('<I', data, off + 4)[0]
        yield cid, off + 8, size
        off += 8 + size + (size & 1)


def parse(path):
    data = open(path, 'rb').read()
    assert data[:4] == b'RIFF' and data[8:12] == b'sfbk'
    out = {}
    for cid, off, size in chunks(data, 12, len(data)):
        if cid != b'LIST':
            continue
        kind = data[off:off + 4]
        for sid, soff, ssize in chunks(data, off + 4, off + size):
            out[(kind, sid)] = data[soff:soff + ssize]
    return out


def records(buf, size):
    return [buf[i:i + size] for i in range(0, len(buf), size)]


def main():
    src, dst = sys.argv[1], sys.argv[2]
    programs = [int(p) for p in sys.argv[3:]]
    c = parse(src)
    smpl = c[(b'sdta', b'smpl')]
    phdr = records(c[(b'pdta', b'phdr')], PHDR)
    pbag = records(c[(b'pdta', b'pbag')], BAG)
    pmod = records(c[(b'pdta', b'pmod')], MOD)
    pgen = records(c[(b'pdta', b'pgen')], GEN)
    inst = records(c[(b'pdta', b'inst')], INST)
    ibag = records(c[(b'pdta', b'ibag')], BAG)
    imod = records(c[(b'pdta', b'imod')], MOD)
    igen = records(c[(b'pdta', b'igen')], GEN)
    shdr = records(c[(b'pdta', b'shdr')], SHDR)

    def bag_range(hdrs, i, size_off):
        a = struct.unpack_from('<H', hdrs[i], size_off)[0]
        b = struct.unpack_from('<H', hdrs[i + 1], size_off)[0]
        return a, b

    def bag_items(bags, j):
        g0, m0 = struct.unpack('<HH', bags[j])
        g1, m1 = struct.unpack('<HH', bags[j + 1])
        return (g0, g1), (m0, m1)

    chosen = []
    for i in range(len(phdr) - 1):
        name, prog, bank = struct.unpack_from('<20sHH', phdr[i])
        if bank == 0 and prog in programs:
            chosen.append(i)
    chosen.sort(key=lambda i: struct.unpack_from('<H', phdr[i], 20)[0])

    inst_map, sample_map = {}, {}
    sample_order = []

    def want_sample(s):
        if s in sample_map:
            return
        sample_map[s] = len(sample_order)
        sample_order.append(s)
        stype = struct.unpack_from('<H', shdr[s], 44)[0]
        link = struct.unpack_from('<H', shdr[s], 42)[0]
        if stype in (2, 4, 8) and link < len(shdr) - 1:
            want_sample(link)

    # collect instruments and samples
    for p in chosen:
        b0, b1 = bag_range(phdr, p, 24)
        for j in range(b0, b1):
            (g0, g1), _ = bag_items(pbag, j)
            for g in range(g0, g1):
                op, amt = struct.unpack('<HH', pgen[g])
                if op == GEN_INSTRUMENT and amt not in inst_map:
                    inst_map[amt] = len(inst_map)
    inst_order = sorted(inst_map, key=inst_map.get)
    for ins in inst_order:
        b0, b1 = bag_range(inst, ins, 20)
        for j in range(b0, b1):
            (g0, g1), _ = bag_items(ibag, j)
            for g in range(g0, g1):
                op, amt = struct.unpack('<HH', igen[g])
                if op == GEN_SAMPLE:
                    want_sample(amt)

    # sample data
    new_smpl = bytearray()
    new_shdr = []
    for s in sample_order:
        name, start, end, ls, le, rate, pitch, corr, link, stype = struct.unpack('<20sIIIIIBbHH', shdr[s])
        ns = len(new_smpl) // 2
        new_smpl += smpl[start * 2:end * 2] + b'\0' * 92  # 46 zero points after each sample
        off = ns - start
        nlink = sample_map.get(link, 0)
        new_shdr.append(struct.pack('<20sIIIIIBbHH', name, ns, ns + (end - start), ls + off, le + off,
                                    rate, pitch, corr, nlink, stype))
    new_shdr.append(struct.pack('<20sIIIIIBbHH', b'EOS', 0, 0, 0, 0, 0, 0, 0, 0, 0))

    # instruments
    n_inst, n_ibag, n_imod, n_igen = [], [], [], []
    for ins in inst_order:
        n_inst.append(struct.pack('<20sH', inst[ins][:20], len(n_ibag)))
        b0, b1 = bag_range(inst, ins, 20)
        for j in range(b0, b1):
            (g0, g1), (m0, m1) = bag_items(ibag, j)
            n_ibag.append(struct.pack('<HH', len(n_igen), len(n_imod)))
            n_imod += imod[m0:m1]
            for g in range(g0, g1):
                op, amt = struct.unpack('<HH', igen[g])
                if op == GEN_SAMPLE:
                    amt = sample_map[amt]
                n_igen.append(struct.pack('<HH', op, amt))
    n_inst.append(struct.pack('<20sH', b'EOI', len(n_ibag)))
    n_ibag.append(struct.pack('<HH', len(n_igen), len(n_imod)))
    n_imod.append(b'\0' * MOD)
    n_igen.append(b'\0' * GEN)

    # presets
    n_phdr, n_pbag, n_pmod, n_pgen = [], [], [], []
    for p in chosen:
        n_phdr.append(phdr[p][:24] + struct.pack('<H', len(n_pbag)) + phdr[p][26:])
        b0, b1 = bag_range(phdr, p, 24)
        for j in range(b0, b1):
            (g0, g1), (m0, m1) = bag_items(pbag, j)
            n_pbag.append(struct.pack('<HH', len(n_pgen), len(n_pmod)))
            n_pmod += pmod[m0:m1]
            for g in range(g0, g1):
                op, amt = struct.unpack('<HH', pgen[g])
                if op == GEN_INSTRUMENT:
                    amt = inst_map[amt]
                n_pgen.append(struct.pack('<HH', op, amt))
    n_phdr.append(struct.pack('<20sHHHIII', b'EOP', 0, 0, len(n_pbag), 0, 0, 0))
    n_pbag.append(struct.pack('<HH', len(n_pgen), len(n_pmod)))
    n_pmod.append(b'\0' * MOD)
    n_pgen.append(b'\0' * GEN)

    def chunk(cid, body):
        body = bytes(body)
        pad = b'\0' if len(body) & 1 else b''
        return cid + struct.pack('<I', len(body)) + body + pad

    def lst(kind, body):
        return chunk(b'LIST', kind + body)

    def zstr(s):
        b = s.encode() + b'\0'
        return b + (b'\0' if len(b) & 1 else b'')

    info = (chunk(b'ifil', struct.pack('<HH', 2, 1)) + chunk(b'isng', zstr('EMU8000')) +
            chunk(b'INAM', zstr('Piano2MIDI instruments (from FluidR3_GM)')) +
            chunk(b'ICOP', zstr('FluidR3_GM (c) 2000-2002, 2008 Frank Wen, MIT license')))
    pdta = b''.join([chunk(b'phdr', b''.join(n_phdr)), chunk(b'pbag', b''.join(n_pbag)),
                     chunk(b'pmod', b''.join(n_pmod)), chunk(b'pgen', b''.join(n_pgen)),
                     chunk(b'inst', b''.join(n_inst)), chunk(b'ibag', b''.join(n_ibag)),
                     chunk(b'imod', b''.join(n_imod)), chunk(b'igen', b''.join(n_igen)),
                     chunk(b'shdr', b''.join(new_shdr))])
    body = b'sfbk' + lst(b'INFO', info) + lst(b'sdta', chunk(b'smpl', new_smpl)) + lst(b'pdta', pdta)
    with open(dst, 'wb') as f:
        f.write(b'RIFF' + struct.pack('<I', len(body)) + body)
    for p in chosen:
        name, prog = struct.unpack_from('<20sH', phdr[p])
        print(f'  {prog:3d} {name.split(b"\0")[0].decode()}')
    print(f'{len(chosen)} presets, {len(inst_order)} instruments, {len(sample_order)} samples, '
          f'{len(new_smpl) / 1e6:.1f} MB sample data -> {dst}')


if __name__ == '__main__':
    main()
