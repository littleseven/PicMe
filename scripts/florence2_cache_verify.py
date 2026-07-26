#!/usr/bin/env python3
"""验证修复后的 merged decoder 缓存分支：正确性（与 no-cache 逐 token 对比）+ 速度对比。

no-cache 管道已被验证与 PyTorch model.generate() 一致（见 florence2_q4f16_repro.py），
这里以它为 ground truth，证明 cache 分支修复后输出逐 token 相同，并量化加速比。

用法: python3 scripts/florence2_cache_verify.py [image]
"""
import sys, json, time
import numpy as np
import onnxruntime as ort
from PIL import Image

DIR = "/Users/guoshuai/code/florence-2-onnx"
NL = 6; EOS = 2; DEC_START = 2; MAXT = 256

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

# 三条链路：vision/encoder/embed 沿用各自已验证组合，decoder 用修复后的 merged（prefill+decode 一体）
# （2026-07-26 起本地/ModelScope 的 merged decoder 均为图手术修复版，标准文件名）
CHAINS = {
    "fp32": dict(vis="vision_encoder.onnx", enc="encoder_model.onnx", emb="embed_tokens.onnx",
                 dec="decoder_model_merged.onnx"),
    "int8": dict(vis="vision_encoder_quantized.onnx", enc="encoder_model_quantized.onnx", emb="embed_tokens_int8.onnx",
                 dec="decoder_model_merged_quantized.onnx"),
    "q4":   dict(vis="vision_encoder_quantized.onnx", enc="encoder_model_q4f16.onnx", emb="embed_tokens_q4f16.onnx",
                 dec="decoder_model_merged_q4.onnx"),
}

def prepare(s, pv, task_ids):
    img_feats = s["vis"].run(None, {"pixel_values": pv})[0]
    task_embeds = s["emb"].run(None, {"input_ids": task_ids})[0]
    ie = np.concatenate([img_feats, task_embeds], axis=1)
    T = ie.shape[1]; enc_mask = np.ones((1, T), dtype=np.int64)
    enc_hs = s["enc"].run(None, {"inputs_embeds": ie, "attention_mask": enc_mask})[0]
    return enc_hs, enc_mask, T

def dummy_past(di, T):
    dd = np.zeros((1, 12, 1, 64), dtype=np.float32)
    de = np.zeros((1, 12, T, 64), dtype=np.float32)
    for L in range(NL):
        di[f"past_key_values.{L}.decoder.key"] = dd
        di[f"past_key_values.{L}.decoder.value"] = dd
        di[f"past_key_values.{L}.encoder.key"] = de
        di[f"past_key_values.{L}.encoder.value"] = de

def run_no_cache(s, enc_hs, enc_mask, T):
    seq = [DEC_START]; gen = []
    for _ in range(MAXT):
        ie_dec = s["emb"].run(None, {"input_ids": np.array([seq], dtype=np.int64)})[0]
        di = {"use_cache_branch": np.array([False]), "inputs_embeds": ie_dec,
              "encoder_hidden_states": enc_hs, "encoder_attention_mask": enc_mask}
        dummy_past(di, T)
        outs = s["dec"].run(None, di)
        nt = int(np.argmax(outs[0][0, -1]))
        if nt == EOS: break
        gen.append(nt); seq.append(nt)
    return gen

def run_cache(s, enc_hs, enc_mask, T):
    def emb1(t):
        return s["emb"].run(None, {"input_ids": np.array([[t]], dtype=np.int64)})[0]
    # prefill: False 分支 + dummy past
    di = {"use_cache_branch": np.array([False]), "inputs_embeds": emb1(DEC_START),
          "encoder_hidden_states": enc_hs, "encoder_attention_mask": enc_mask}
    dummy_past(di, T)
    po = s["dec"].run(None, di)
    nt = int(np.argmax(po[0][0, -1])); kv = po[1:]; gen = []
    if nt == EOS: return gen
    gen.append(nt)
    # decode: True 分支（本次修复对象）
    for _ in range(1, MAXT):
        di = {"use_cache_branch": np.array([True]), "inputs_embeds": emb1(nt),
              "encoder_hidden_states": enc_hs, "encoder_attention_mask": enc_mask}
        for L in range(NL):
            b = L * 4
            di[f"past_key_values.{L}.decoder.key"] = kv[b]
            di[f"past_key_values.{L}.decoder.value"] = kv[b + 1]
            di[f"past_key_values.{L}.encoder.key"] = kv[b + 2]
            di[f"past_key_values.{L}.encoder.value"] = kv[b + 3]
        out = s["dec"].run(None, di)
        nt = int(np.argmax(out[0][0, -1])); kv = out[1:]
        if nt == EOS: break
        gen.append(nt)
    return gen

def main():
    img = sys.argv[1] if len(sys.argv) > 1 else "input_images/face.jpg"
    from transformers import AutoProcessor
    proc = AutoProcessor.from_pretrained("microsoft/Florence-2-base", trust_remote_code=True)
    pil = Image.open(img).convert("RGB")

    for task in ["<OD>", "<MORE_DETAILED_CAPTION>"]:
        inp = proc(text=task, images=pil, return_tensors="pt")
        pv = inp["pixel_values"].numpy(); ids = inp["input_ids"].numpy().astype(np.int64)
        print(f"\n================ {task}  image={img} ================")
        for name, cfg in CHAINS.items():
            s = {k: sess(v) for k, v in cfg.items()}
            enc_hs, enc_mask, T = prepare(s, pv, ids)
            t0 = time.perf_counter(); g_nc = run_no_cache(s, enc_hs, enc_mask, T); t_nc = time.perf_counter() - t0
            t0 = time.perf_counter(); g_c = run_cache(s, enc_hs, enc_mask, T); t_c = time.perf_counter() - t0
            match = "✅ 逐 token 一致" if g_nc == g_c else f"❌ 不一致\n  no-cache: {decode(g_nc)[:200]}\n  cache   : {decode(g_c)[:200]}"
            print(f"[{name:4s}] {len(g_nc)} toks | no-cache {t_nc:6.2f}s | cache {t_c:6.2f}s | 加速 {t_nc/max(t_c,1e-9):4.1f}x | {match}")

if __name__ == "__main__":
    main()
