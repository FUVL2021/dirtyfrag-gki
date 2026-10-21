#!/usr/bin/env python3
"""Remove Termux's library path from an ELF's RPATH/RUNPATH.

Termux's clang driver injects -rpath /data/data/com.termux/files/usr/lib on
every link and no flag suppresses it. That directory is mode 0700 on another
app's data, so init — the context bootstrap is exec'd in — cannot read it. The
dynamic loader then fails to find libc.so and the process dies with nothing but
an exit status to show for it.

The tag is found through the program headers (PT_DYNAMIC), not the section
table: the binaries are run through strip, and section headers are exactly what
that removes.

The replacement is written in place. "/system/lib64" is shorter than the string
it replaces, so everything stays at its original offset.
"""
import sys

TERMUX = b"/data/data/com.termux/files/usr"
REPLACEMENT = b"/system/lib64"

PT_DYNAMIC = 2
DT_NULL, DT_STRTAB, DT_RPATH, DT_RUNPATH = 0, 5, 15, 29


def u64(data, off):
    return int.from_bytes(data[off:off + 8], 'little')


def patch(path):
    with open(path, 'r+b') as f:
        data = bytearray(f.read())
        if data[:4] != b'\x7fELF':
            return "skip (not ELF)"
        if data[4] != 2 or data[5] != 1:
            return "skip (not ELF64 LE)"

        e_phoff = u64(data, 0x20)
        e_phentsize = int.from_bytes(data[0x36:0x38], 'little')
        e_phnum = int.from_bytes(data[0x38:0x3A], 'little')
        if e_phoff == 0 or e_phnum == 0:
            return "skip (no program headers)"

        # Locate PT_DYNAMIC, then the virtual-address-to-file-offset mapping
        # from the PT_LOAD segments.
        dyn_off = dyn_size = None
        loads = []
        for i in range(e_phnum):
            base = e_phoff + i * e_phentsize
            p_type = int.from_bytes(data[base:base + 4], 'little')
            if p_type == PT_DYNAMIC:
                dyn_off = u64(data, base + 0x08)
                dyn_size = u64(data, base + 0x20)
            elif p_type == 1:  # PT_LOAD
                loads.append((u64(data, base + 0x08),      # p_offset
                              u64(data, base + 0x10),      # p_vaddr
                              u64(data, base + 0x20)))     # p_filesz
        if dyn_off is None:
            return "skip (no PT_DYNAMIC)"

        def vaddr_to_off(vaddr):
            for p_offset, p_vaddr, p_filesz in loads:
                if p_vaddr <= vaddr < p_vaddr + p_filesz:
                    return p_offset + (vaddr - p_vaddr)
            return None

        # Two passes: the string table address comes from the same array we are
        # walking, so resolve it first.
        strtab_vaddr = None
        for j in range(dyn_size // 16):
            ent = dyn_off + j * 16
            tag = u64(data, ent)
            if tag == DT_NULL:
                break
            if tag == DT_STRTAB:
                strtab_vaddr = u64(data, ent + 8)
        if strtab_vaddr is None:
            return "skip (no DT_STRTAB)"
        strtab_off = vaddr_to_off(strtab_vaddr)
        if strtab_off is None:
            return "skip (strtab not mapped)"

        patched = 0
        for j in range(dyn_size // 16):
            ent = dyn_off + j * 16
            tag = u64(data, ent)
            if tag == DT_NULL:
                break
            if tag not in (DT_RPATH, DT_RUNPATH):
                continue
            off = strtab_off + u64(data, ent + 8)
            end = data.index(b'\x00', off)
            old = bytes(data[off:end])
            if TERMUX not in old:
                continue
            if len(old) < len(REPLACEMENT):
                return "skip (string too short: %r)" % old
            data[off:off + len(REPLACEMENT)] = REPLACEMENT
            data[off + len(REPLACEMENT)] = 0
            patched += 1

        if patched:
            f.seek(0)
            f.write(data)
            return "patched %d tag(s)" % patched
        return "no termux rpath"


if __name__ == '__main__':
    for p in sys.argv[1:]:
        print("%s: %s" % (p, patch(p)))