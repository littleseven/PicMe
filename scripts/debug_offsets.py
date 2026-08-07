#!/usr/bin/env python3
"""Debug NCNN .bin weight offsets"""
import struct

with open('androidApp/src/main/assets/models/ncnn/det_500m.bin', 'rb') as f:
    data = f.read()

with open('androidApp/src/main/assets/models/ncnn/det_500m.param', 'r') as f:
    lines = [l.rstrip('\n\r') for l in f.readlines()]

header = lines[1].strip().split()
orig_layer_count = int(header[0])
print(f'Layers: {orig_layer_count}, Bin size: {len(data)} bytes')

offset = 0
for idx, line in enumerate(lines[2:]):
    if not line.strip():
        continue
    parts = line.strip().split()
    type_name = parts[0]
    layer_name = parts[1]
    
    if type_name == 'Input':
        print(f'  Layer {idx:3d}: {type_name:25s} {layer_name:20s} offset={offset:8d} size=0 (no weight)')
        continue
    
    # Parse params
    params = {}
    for p in parts[4:]:
        if '=' in p:
            k, v = p.split('=', 1)
            try:
                params[int(k)] = int(v)
            except:
                params[k] = v
    
    if type_name in ('Convolution', 'ConvolutionDepthWise', 'InnerProduct'):
        ws = params.get(6, 0)  # weight_data_size
        no = params.get(0, 0)  # num_output
        bt = params.get(5, 0)  # bias_term
        
        # weight: 4-byte flag + ws * 4 bytes (float32)
        w_size = 4 + ws * 4
        # bias: direct no * 4 bytes (type=1, NO flag)
        b_size = no * 4 if bt == 1 else 0
        total = w_size + b_size
        
        # Read flag
        if offset + 4 <= len(data):
            flag = struct.unpack('I', data[offset:offset+4])[0]
            flag_hex = f'0x{flag:08X}'
        else:
            flag_hex = 'OUT_OF_BOUNDS'
        
        print(f'  Layer {idx:3d}: {type_name:25s} {layer_name:20s} ws={ws:5d} no={no:3d} bt={bt} '
              f'flag={flag_hex} w={w_size:6d} b={b_size:5d} total={total:6d} '
              f'offset={offset:8d} -> {offset+total:8d}')
        offset += total
    else:
        print(f'  Layer {idx:3d}: {type_name:25s} {layer_name:20s} offset={offset:8d} size=0 (no weight)')

print(f'\nFinal offset: {offset}')
print(f'Bin size:     {len(data)}')
print(f'Difference:   {offset - len(data)} bytes')
