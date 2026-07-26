#!/usr/bin/env python3
"""决定性测试：未量化 decoder_model_merged.onnx 的 use_cache_branch=True 是否也崩。

spec（docs/superpowers/specs/2026-07-26-florence2-use-cache-branch-bug.md）只测过
INT8 / q4 量化版 merged decoder（都崩）。本脚本用全 fp32 链路验证：
  - 若 fp32 merged cache 分支正常 → bug 在量化器的 If 子图处理 → 重导/图手术修复量化版
  - 若 fp32 也崩 → bug 在 optimum merged 导出本身 → 走 decoder_with_past 路线

用法: python3 scripts/florence2_fp32_merged_cache_test.py [image]
"""
import sys, json
import numpy as np
import onnxruntime as ort
from PIL import Image

DIR = "/Users/guoshuai/code/florence-2-onnx"
NL = 6; EOS = 2; DEC_START = 2; MAXT = 64

vocab = json.load(open(f"{DIR}/vocab.json")); added = json.load(open(f"{DIR}/added_tokens.json"))
id2tok = {v: k for k, v in vocab.items()}
for k, v in added.items(): id2tok[v] = k

def decode(ids):
    out = []
    for t in ids:
        tok = id2tok.get(int(t), "")
        out.append(tok if (tok.startswith("<") and tok.endswith(">")) else tok.replace("Ġ", " ").replace("Ċ", "\n"))
    return "".join(out).strip()

def sess(path):
    o = ort.SessionOptions(); o.intra_op_num_threads = 4
    o.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_EXTENDED
    return ort.InferenceSession(f"{DIR}/{path}", o, providers=["CPUExecutionProvider"])

def main():
    img = sys.argv[1] if len(sys.argv) > 1 else "input_images/face.jpg"
    dec_name = sys.argv[2] if len(sys.argv) > 2 else "decoder_model_merged.onnx"
    from transformers import AutoProcessor
    proc = AutoProcessor.from_pretrained("microsoft/Florence-2-base", trust_remote_code=True)
    pv, ids = None, None
    inp = proc(text="<OD>", images=Image.open(img).convert("RGB"), return_tensors="pt")
    pv = inp["pixel_values"].numpy(); ids = inp["input_ids"].numpy().astype(np.int64)
    print(f"image={img}  task ids={ids[0].tolist()}")

    vis = sess("vision_encoder.onnx")
    enc = sess("encoder_model.onnx")
    emb = sess("embed_tokens.onnx")
    dec = sess(dec_name)   # merged decoder，prefill+decode 同一个

    img_feats = vis.run(None, {"pixel_values": pv})[0]
    task_embeds = emb.run(None, {"input_ids": ids})[0]
    ie = np.concatenate([img_feats, task_embeds], axis=1)
    T = ie.shape[1]; enc_mask = np.ones((1, T), dtype=np.int64)
    enc_hs = enc.run(None, {"inputs_embeds": ie, "attention_mask": enc_mask})[0]

    def kv_inputs(kv):
        d = {}
        for L in range(NL):
            b = L * 4
            d[f"past_key_values.{L}.decoder.key"] = kv[b]
            d[f"past_key_values.{L}.decoder.value"] = kv[b + 1]
            d[f"past_key_values.{L}.encoder.key"] = kv[b + 2]
            d[f"past_key_values.{L}.encoder.value"] = kv[b + 3]
        return d

    # ── prefill: use_cache_branch=False + 全零 dummy past ──
    se = emb.run(None, {"input_ids": np.array([[DEC_START]], dtype=np.int64)})[0]
    dummy_dec = np.zeros((1, 12, 1, 64), dtype=np.float32)
    dummy_enc = np.zeros((1, 12, T, 64), dtype=np.float32)
    di = {"use_cache_branch": np.array([False]), "inputs_embeds": se,
          "encoder_hidden_states": enc_hs, "encoder_attention_mask": enc_mask}
    for L in range(NL):
        di[f"past_key_values.{L}.decoder.key"] = dummy_dec
        di[f"past_key_values.{L}.decoder.value"] = dummy_dec
        di[f"past_key_values.{L}.encoder.key"] = dummy_enc
        di[f"past_key_values.{L}.encoder.value"] = dummy_enc
    po = dec.run(None, di)
    nt = int(np.argmax(po[0][0, -1])); kv = po[1:]; gen = [nt]
    print(f"prefill ok, first token={nt} ({id2tok.get(nt)!r})")
    for i, o in enumerate(dec.get_outputs()[1:5]):
        print(f"  present[{i}] {o.name}: {kv[i].shape}")

    # ── decode: use_cache_branch=True（spec 中量化版在这里崩）──
    for step in range(1, MAXT):
        if nt == EOS: break
        ne = emb.run(None, {"input_ids": np.array([[nt]], dtype=np.int64)})[0]
        di = {"use_cache_branch": np.array([True]), "inputs_embeds": ne,
              "encoder_hidden_states": enc_hs, "encoder_attention_mask": enc_mask}
        di.update(kv_inputs(kv))
        out = dec.run(None, di)
        nt = int(np.argmax(out[0][0, -1])); gen.append(nt); kv = out[1:]
        if step <= 2:
            print(f"decode step {step} ok, token={nt} ({id2tok.get(nt)!r})")

    print(f"\n[RESULT] fp32 merged use_cache_branch=True 跑通，{len(gen)} toks:")
    print(decode(gen)[:300])

if __name__ == "__main__":
    main()
