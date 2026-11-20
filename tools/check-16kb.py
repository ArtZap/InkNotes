#!/usr/bin/env python3
"""Checks an APK for 16 KB memory pages (Google Play requires it for apps targeting Android 15+).

Every native library for a 64-bit ABI must have its LOAD segments aligned to at least 16 KB, and
an uncompressed library must start at a 16 KB boundary inside the APK. Prints each library and
exits with 1 when one fails.

    tools/check-16kb.py app/build/outputs/apk/release/app-release.apk
"""
import struct
import sys
import zipfile

PAGE = 16 * 1024
ABIS = ("arm64-v8a", "x86_64")


def load_alignments(data: bytes):
    if data[:4] != b"\x7fELF" or data[4] != 2:
        return None  # not a 64-bit ELF
    little = data[5] == 1
    e = "<" if little else ">"
    phoff = struct.unpack_from(e + "Q", data, 0x20)[0]
    phentsize, phnum = struct.unpack_from(e + "HH", data, 0x36)
    out = []
    for i in range(phnum):
        off = phoff + i * phentsize
        p_type = struct.unpack_from(e + "I", data, off)[0]
        if p_type == 1:  # PT_LOAD
            out.append(struct.unpack_from(e + "Q", data, off + 0x30)[0])
    return out


def main(path: str) -> int:
    bad = 0
    seen = 0
    with zipfile.ZipFile(path) as z:
        for info in z.infolist():
            name = info.filename
            if not name.startswith("lib/") or not name.endswith(".so"):
                continue
            abi = name.split("/")[1]
            if abi not in ABIS:
                continue
            seen += 1
            aligns = load_alignments(z.read(name)) or []
            ok_elf = bool(aligns) and min(aligns) >= PAGE
            # Where the file's bytes start in the APK: after its local header.
            with open(path, "rb") as f:
                f.seek(info.header_offset)
                head = f.read(30)
                n, m = struct.unpack_from("<HH", head, 26)
                start = info.header_offset + 30 + n + m
            stored = info.compress_type == zipfile.ZIP_STORED
            ok_zip = not stored or start % PAGE == 0
            status = "ok" if ok_elf and ok_zip else "FAIL"
            if status == "FAIL":
                bad += 1
            print(f"{status:4}  {name}  LOAD align {min(aligns) if aligns else '?'}"
                  f"  {'stored at ' + str(start) if stored else 'compressed'}")
    print(f"{seen} native libraries for {', '.join(ABIS)}, {bad} not ready for 16 KB pages")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1]))
