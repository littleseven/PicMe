#!/usr/bin/env python3
"""用 onnxruntime 在 Mac 上跑 Florence-2 量化版 ONNX，验证推理正确性。
对比 PyTorch 版结果（之前 6/6 caption 正确）。"""
import numpy as np
import onnxruntime as ort
from PIL import Image
import json, os, re

DIR = "/Users/guoshuai/code/florence-2-onnx"
IMAGE_SIZE = 768
HIDDEN = 768
NUM_LAYERS = 6
DECODER_START = 2
EOS = 2
MAX_TOKENS = 256

# Task prompt token ids (precomputed)
TASK_OD = np.array([0, 41552, 7111, 15698, 2], dtype=np.int64)
TASK_CAP = np.array([0, 41552, 38543, 1215, 495, 19739, 3063, 1691, 1215, 28494, 10263, 15698, 2], dtype=np.int64)

# Load vocab for decoding
vocab = json.load(open(f"{DIR}/vocab.json"))
added = json.load(open(f"{DIR}/added_tokens.json"))
id2tok = {}
for tok, idx in vocab.items(): id2tok[idx] = tok
for tok, idx in added.items(): id2tok[idx] = tok

def decode(token_ids):
    parts = []
    for tid in token_ids:
        tok = id2tok.get(tid, "")
        if tok.startswith("<") and tok.endswith(">"): parts.append(tok)
        else: parts.append(tok.replace("Ġ", " ").replace("Ċ", "\n"))
    return "".join(parts).strip()

def preprocess(img_path):
    img = Image.open(img_path).convert("RGB")
    w, h = img.size
    cs = min(w, h)
    img = img.crop(((w-cs)//2, (h-cs)//2, (w+cs)//2, (h+cs)//2))
    img = img.resize((IMAGE_SIZE, IMAGE_SIZE))
    px = np.array(img, dtype=np.float32) / 255.0
    px = (px - 0.5) / 0.5  # normalize
    # HWC -> CHW
    return np.transpose(px, (2, 0, 1))[np.newaxis]  # [1, 3, 768, 768]

print("Loading 4 ONNX sessions...")
opts = ort.SessionOptions()
opts.intra_op_num_threads = 4
opts.inter_op_num_threads = 1

vis_enc = ort.InferenceSession(f"{DIR}/vision_encoder_quantized.onnx", opts, providers=["CPUExecutionProvider"])
txt_enc = ort.InferenceSession(f"{DIR}/encoder_model_quantized.onnx", opts, providers=["CPUExecutionProvider"])
emb     = ort.InferenceSession(f"{DIR}/embed_tokens_int8.onnx", opts, providers=["CPUExecutionProvider"])
dec     = ort.InferenceSession(f"{DIR}/decoder_model_merged_quantized.onnx", opts, providers=["CPUExecutionProvider"])
print("All sessions loaded.\n")

def run_task(pixel_values, task_ids, task_name):
    # 1. Vision encoder
    img_feats = vis_enc.run(None, {"pixel_values": pixel_values})[0]  # [1, N, 768]
    n_vis = img_feats.shape[1]
    print(f"  [{task_name}] vision tokens: {n_vis}")

    # 2. Embed task tokens
    text_embeds = emb.run(None, {"input_ids": task_ids[np.newaxis]})[0]  # [1, L, 768]
    n_txt = text_embeds.shape[1]

    # 3. Concat + encoder
    inputs_embeds = np.concatenate([img_feats, text_embeds], axis=1)  # [1, N+L, 768]
    total_len = n_vis + n_txt
    attn_mask = np.ones((1, total_len), dtype=np.int64)

    enc_out = txt_enc.run(None, {
        "inputs_embeds": inputs_embeds,
        "attention_mask": attn_mask
    })[0]  # [1, total_len, 768]
    print(f"  [{task_name}] encoder out: {enc_out.shape}")

    # 4. Decoder loop (merged model with use_cache_branch)
    generated = []
    kv_cache = {}
    has_past = False
    cur_token = DECODER_START

    for step in range(MAX_TOKENS):
        # embed current token
        tok_emb = emb.run(None, {"input_ids": np.array([[cur_token]], dtype=np.int64)})[0]  # [1,1,768]

        dec_inputs = {
            "encoder_hidden_states": enc_out,
            "encoder_attention_mask": attn_mask,
            "inputs_embeds": tok_emb,
            "use_cache_branch": np.array([step > 0], dtype=bool),
        }

        if has_past:
            for layer in range(NUM_LAYERS):
                for kind in ["decoder.key", "decoder.value", "encoder.key", "encoder.value"]:
                    key = f"past_key_values.{layer}.{kind}"
                    if key in kv_cache:
                        dec_inputs[key] = kv_cache[key]
        else:
            # step 0: merged decoder 需要零长度 KV cache 占位
            for layer in range(NUM_LAYERS):
                for kind in ["decoder.key", "decoder.value", "encoder.key", "encoder.value"]:
                    dec_inputs[f"past_key_values.{layer}.{kind}"] = np.zeros((1, 12, 0, 64), dtype=np.float32)

        try:
            outputs = dec.run(None, dec_inputs)
        except Exception as e:
            print(f"  [{task_name}] step {step} ORT error: {e}")
            break

        logits = outputs[0]  # [1, 1, vocab]
        next_id = int(np.argmax(logits[0, -1]))

        # extract KV cache (outputs[1:] = present.0.decoder.key ...)
        kv_cache.clear()
        for layer in range(NUM_LAYERS):
            base = 1 + layer * 4
            for j, kind in enumerate(["decoder.key", "decoder.value", "encoder.key", "encoder.value"]):
                idx = base + j
                if idx < len(outputs):
                    kv_cache[f"present.{layer}.{kind}"] = outputs[idx]
        # rename present.* -> past_key_values.* for next step
        new_kv = {}
        for k, v in kv_cache.items():
            new_kv[k.replace("present.", "past_key_values.")] = v
        kv_cache = new_kv
        has_past = True

        if next_id == EOS:
            print(f"  [{task_name}] EOS at step {step}")
            break
        generated.append(next_id)
        cur_token = next_id

    text = decode(generated)
    print(f"  [{task_name}] output ({len(generated)} tokens): {text[:300]}")
    return text


# Test on images
for img_path in ["input_images/face.jpg", "docs/assets/winxin.jpg", "input_images/img_lyf.png"]:
    print(f"\n===== {img_path} =====")
    try:
        pv = preprocess(img_path)
        od = run_task(pv, TASK_OD, "OD")
        cap = run_task(pv, TASK_CAP, "DETAILED_CAP")
        print(f"\n  OD result: {od[:200]}")
        print(f"  Caption:   {cap[:200]}")
    except Exception as e:
        print(f"  ERROR: {e}")
