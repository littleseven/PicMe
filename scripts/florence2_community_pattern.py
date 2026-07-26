#!/usr/bin/env python3
"""Florence-2 ONNX 社区 demo 模式：decoder_model(prefill) + decoder_merged(decode, use_cache_branch=True)"""
import numpy as np, onnxruntime as ort, json
from PIL import Image

DIR = "/Users/guoshuai/code/florence-2-onnx"
NL = 6; EOS = 2; MAXT = 256
vocab = json.load(open(f"{DIR}/vocab.json")); added = json.load(open(f"{DIR}/added_tokens.json"))
id2tok = {v:k for k,v in vocab.items()}
for k,v in added.items(): id2tok[v] = k
def decode(ids):
    return ''.join(id2tok.get(t,'').replace("Ġ"," ").replace("Ċ","\n") if not(id2tok.get(t,"").startswith("<")and id2tok.get(t,"").endswith(">"))else id2tok.get(t,"")for t in ids).strip()

TASK_OD = np.array([0,41552,7111,15698,2], dtype=np.int64)
opts = ort.SessionOptions(); opts.intra_op_num_threads = 4
vis = ort.InferenceSession(f"{DIR}/vision_encoder_quantized.onnx", opts, providers=["CPUExecutionProvider"])
enc = ort.InferenceSession(f"{DIR}/encoder_model_quantized.onnx", opts, providers=["CPUExecutionProvider"])
emb = ort.InferenceSession(f"{DIR}/embed_tokens_int8.onnx", opts, providers=["CPUExecutionProvider"])
# step 0: 用非 merged decoder_model（检查它的 I/O）
dec_prefill = ort.InferenceSession(f"{DIR}/decoder_model_quantized.onnx", opts, providers=["CPUExecutionProvider"])
# step 1+: merged decoder（use_cache_branch 始终 True）
dec_decode = ort.InferenceSession(f"{DIR}/decoder_model_merged_quantized.onnx", opts, providers=["CPUExecutionProvider"])

print(f"prefill inputs: {[(i.name, str(i.shape)) for i in dec_prefill.get_inputs()]}")
print(f"prefill outputs: {len(dec_prefill.get_outputs())}")
print(f"decode inputs: {len(dec_decode.get_inputs())}")
print("sessions loaded\n")

def prep(p):
    img = Image.open(p).convert("RGB"); w,h = img.size; cs = min(w,h)
    img = img.crop(((w-cs)//2,(h-cs)//2,(w+cs)//2,(h+cs)//2)).resize((768,768))
    px = (np.array(img,dtype=np.float32)/255-0.5)/0.5
    return np.transpose(px,(2,0,1))[np.newaxis]

for p in ["input_images/face.jpg", "docs/assets/winxin.jpg", "input_images/img_lyf.png"]:
    print(f"===== {p} =====")
    try:
        pv = prep(p)
        # 1. vision encoder
        img_feats = vis.run(None, {"pixel_values": pv})[0]  # [1, N, 768]
        # 2. embed task tokens
        task_embeds = emb.run(None, {"input_ids": TASK_OD[np.newaxis]})[0]  # [1, L, 768]
        # 3. concat [img + task]
        inputs_embeds = np.concatenate([img_feats, task_embeds], axis=1)  # [1, N+L, 768]
        T = inputs_embeds.shape[1]
        attn_mask = np.ones((1, T), dtype=np.int64)
        # 4. encoder
        enc_hs = enc.run(None, {"inputs_embeds": inputs_embeds, "attention_mask": attn_mask})[0]

        # 5. prefill (step 0): inputs_embeds = 最后一个 token 的 embedding
        last_token_embeds = inputs_embeds[:, -1:, :]  # [1, 1, 768]
        prefill_outs = dec_prefill.run(None, {
            "inputs_embeds": last_token_embeds,
            "encoder_hidden_states": enc_hs,
            "encoder_attention_mask": attn_mask
        })
        # prefill_outs[0] = logits, [1:25] = 24 KV tensors
        logits = prefill_outs[0]
        next_token = int(np.argmax(logits[0, -1]))
        encoder_kv = prefill_outs[1:]  # 保存 prefill 的全部 KV（encoder KV 不变）

        gen = [next_token]
        print(f"  prefill → token {next_token} ({id2tok.get(next_token,'?')})")

        # 6. decode loop (step 1+): merged decoder with use_cache_branch=True
        decoder_kv = prefill_outs[1:]  # 当前步 KV
        for step in range(1, MAXT):
            if next_token == EOS:
                print(f"  EOS@{step}")
                break

            next_embeds = emb.run(None, {"input_ids": np.array([[next_token]], dtype=np.int64)})[0]

            # 构建 decode 输入：encoder KV 从 prefill（不变），decoder KV 从上一步
            di = {
                "use_cache_branch": np.array([True], dtype=np.bool_),
                "inputs_embeds": next_embeds,
                "encoder_hidden_states": enc_hs,
                "encoder_attention_mask": attn_mask,
            }
            # KV: decoder.key/value 从 decoder_kv（上一步输出），encoder.key/value 从 encoder_kv（prefill，不变）
            for layer in range(NL):
                dk = layer * 4  # decoder_kv 偏移
                ek = layer * 4  # encoder_kv 偏移（同一个 outs 数组里交替）
                di[f"past_key_values.{layer}.decoder.key"] = decoder_kv[dk]
                di[f"past_key_values.{layer}.decoder.value"] = decoder_kv[dk+1]
                di[f"past_key_values.{layer}.encoder.key"] = encoder_kv[ek+2]
                di[f"past_key_values.{layer}.encoder.value"] = encoder_kv[ek+3]

            decode_outs = dec_decode.run(None, di)
            logits = decode_outs[0]
            next_token = int(np.argmax(logits[0, -1]))
            gen.append(next_token)
            decoder_kv = decode_outs[1:]  # 更新 decoder KV

        text = decode(gen)
        print(f"  OD ({len(gen)} tokens): {text[:300]}\n")
    except Exception as e:
        import traceback; traceback.print_exc()
        print(f"  ERROR: {e}\n")
