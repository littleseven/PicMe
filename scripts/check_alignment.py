#!/usr/bin/env python3
import struct
import os

def check_elf_alignment(filepath):
    with open(filepath, 'rb') as f:
        # Read ELF header
        e_ident = f.read(16)
        if e_ident[:4] != b'\x7fELF':
            return False
        
        # 64-bit ELF
        e_type, e_machine, e_version = struct.unpack('<HHI', f.read(8))
        e_entry, e_phoff, e_shoff = struct.unpack('<QQQ', f.read(24))
        e_flags, e_ehsize, e_phentsize, e_phnum = struct.unpack('<IHHH', f.read(10))
        
        print(f"Program headers: {e_phnum}, offset: {e_phoff:#x}, entry size: {e_phentsize}")
        
        # Read program headers
        f.seek(e_phoff)
        has_issue = False
        for i in range(e_phnum):
            ph_data = f.read(e_phentsize)
            p_type, p_flags = struct.unpack('<II', ph_data[0:8])
            p_offset, p_vaddr, p_paddr = struct.unpack('<QQQ', ph_data[8:32])
            p_filesz, p_memsz = struct.unpack('<QQ', ph_data[32:48])
            p_align = struct.unpack('<Q', ph_data[48:56])[0]
            
            # PT_LOAD = 1
            if p_type == 1:
                alignment_ok = (p_offset % 16384) == 0
                status = "OK" if alignment_ok else "MISALIGNED"
                print(f"  LOAD segment {i}: offset={p_offset:#x}, align={p_align:#x} [{status}]")
                if not alignment_ok:
                    has_issue = True
        
        return not has_issue

# Check all arm64-v8a libraries
lib_dir = "androidApp/build/intermediates/merged_native_libs/debug/mergeDebugNativeLibs/out/lib/arm64-v8a"
all_ok = True
for filename in sorted(os.listdir(lib_dir)):
    if filename.endswith('.so'):
        filepath = os.path.join(lib_dir, filename)
        print(f"\n=== {filename} ===")
        ok = check_elf_alignment(filepath)
        if not ok:
            all_ok = False

print(f"\n{'✓ All libraries are 16KB aligned!' if all_ok else '✗ Some libraries have alignment issues'}")
