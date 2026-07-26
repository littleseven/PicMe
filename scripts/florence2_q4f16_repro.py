#!/usr/bin/env python3
"""Florence-2 q4f16 ONNX 权威复现（PC 端）。

目标：
  1. 用 HuggingFace PyTorch processor 作为「正确输入」的 ground truth（pixel_values + task input_ids），
     打印每个 task 的真实 token ids（解决 Kotlin 硬编码 token ids 的待导出问题）。
  2. 跑 PyTorch model.generate() 作为参考输出。
  3. 跑 q4f16 ONNX 双 decoder 管道（prefill decoder_model_q4f16 + merged decoder_model_merged_q4）。
  4. 对比，确认 q4f16 管道正确。

模型目录：/Users/guoshuai/code/florence-2-onnx
  - vision_encoder_quantized.onnx   (INT8，q4f16 vision encoder 在 Mac ORT 有图优化 bug，用 INT8)
  - encoder_model_q4f16.onnx
  - decoder_model_q4f16.onnx        (独立 prefill)
  - decoder_model_merged_q4.onnx    (merged decode, use_cache_branch=True)
  - embed_tokens_q4f16.onnx
"""
import sys, json
import numpy as np
import onnxruntime as ort
from PIL import Image

DIR = "/Users/guoshuai/code/florence-2-onnx"
NL = 6            # BART decoder 层数
EOS = 2
BOS = 0
DEC_START = 2     # decoder_start_token_id
MAXT = 256
HIDDEN = 768

# ── vocab（解码用）──────────────────────────────────────────
vocab = json.load(open(f"{DIR}/vocab.json"))
added = json.load(open(f"{DIR}/added_tokens.json"))
id2tok = {v: k for k, v in vocab.items()}
for k, v in added.items():
    id2tok[v] = k


def decode(ids):
    out = []
    for t in ids:
        tok = id2tok.get(int(t), "")
        if tok.startswith("<") and tok.endswith(">"):
            out.append(tok)
        else:
            out.append(tok.replace("Ġ", " ").replace("Ċ", "\n"))
    return "".join(out).strip()


def load_sessions(vision_q4f16=True):
    # q4f16 模型在 ORT_ENABLE_ALL 的 LayerNorm fusion 下加载失败（Mac ORT 1.23），
    # 降到 EXTENDED 即可（含大部分优化，避开坏掉的 SimplifiedLayerNormFusion）。
    opts = ort.SessionOptions()
    opts.intra_op_num_threads = 4
    opts.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_EXTENDED
    kw = dict(sess_options=opts, providers=["CPUExecutionProvider"])
    vis_file = "vision_encoder_q4f16.onnx" if vision_q4f16 else "vision_encoder_quantized.onnx"
    s = {
        "vis": ort.InferenceSession(f"{DIR}/{vis_file}", **kw),
        "enc": ort.InferenceSession(f"{DIR}/encoder_model_q4f16.onnx", **kw),
        "emb": ort.InferenceSession(f"{DIR}/embed_tokens_q4f16.onnx", **kw),
        "prefill": ort.InferenceSession(f"{DIR}/decoder_model_q4f16.onnx", **kw),
        "decode": ort.InferenceSession(f"{DIR}/decoder_model_merged_q4.onnx", **kw),
    }
    return s


def io(session, name):
    print(f"\n[{name}]")
    for i in session.get_inputs():
        print(f"  IN  {i.name:40s} {i.type:12s} {i.shape}")
    for o in session.get_outputs():
        print(f"  OUT {o.name:40s} {o.type:12s} {o.shape}")


def inspect_all(s):
    for k, sess in s.items():
        io(sess, k)


def processor_inputs(task_text, img_path):
    """用 HF processor 生成 ground-truth pixel_values + input_ids + attention_mask。"""
    import torch
    from transformers import AutoProcessor
    proc = AutoProcessor.from_pretrained("microsoft/Florence-2-base", trust_remote_code=True)
    img = Image.open(img_path).convert("RGB")
    inputs = proc(text=task_text, images=img, return_tensors="pt")
    pv = inputs["pixel_values"].numpy()              # [1,3,768,768]
    ids = inputs["input_ids"].numpy().astype(np.int64)  # [1,L]
    am = inputs.get("attention_mask")
    am = am.numpy().astype(np.int64) if am is not None else np.ones_like(ids)
    return pv, ids, am, proc


def run_onnx_pipeline(s, pv, task_ids, max_new_tokens=MAXT):
    """q4f16 管道（已验证可跑通的正确路径）。

    关键：merged decoder 的 use_cache_branch=True 缓存分支在 ORT 上有 cross-attn
    MatMul dim0 bug（所有量化版本都坏）。**正确做法 = use_cache_branch=False 每步全量重算**
    （无 KV cache，每步喂入不断增长的 decoder 序列，取最后一位 logits）。
    解码慢一点（O(n²)），但 231M 小模型 + 几十个 token 完全可接受，且结果与 PyTorch 一致。
    """
    # 1. vision encoder (INT8) → img_feats [1,577,768]
    img_feats = s["vis"].run(None, {"pixel_values": pv})[0]
    # 2. embed task ids (q4f16) → task_embeds [1,L,768]
    task_embeds = s["emb"].run(None, {"input_ids": task_ids})[0]
    # 3. concat [img ⊕ task]
    inputs_embeds = np.concatenate([img_feats, task_embeds], axis=1)  # [1, 577+L, 768]
    T = inputs_embeds.shape[1]
    enc_mask = np.ones((1, T), dtype=np.int64)
    # 4. encoder (q4f16) → enc_hs
    enc_hs = s["enc"].run(None, {"inputs_embeds": inputs_embeds, "attention_mask": enc_mask})[0]

    def emb_ids(x):
        return s["emb"].run(None, {"input_ids": x})[0]

    # dummy past KV（use_cache_branch=False 分支不用，但 ORT 要求 28 个输入齐全）
    dummy_dec = np.zeros((1, 12, 1, 64), dtype=np.float32)
    dummy_enc = np.zeros((1, 12, T, 64), dtype=np.float32)

    # 5. 无 cache 自回归：每步喂入 [DEC_START, *gen_so_far] 的 embedding，取最后一位 logits
    seq = [DEC_START]
    gen = []
    for _ in range(max_new_tokens):
        ie_dec = emb_ids(np.array([seq], dtype=np.int64))  # [1, len, 768]
        di = {
            "use_cache_branch": np.array([False]),
            "inputs_embeds": ie_dec,
            "encoder_hidden_states": enc_hs,
            "encoder_attention_mask": enc_mask,
        }
        for layer in range(NL):
            di[f"past_key_values.{layer}.decoder.key"] = dummy_dec
            di[f"past_key_values.{layer}.decoder.value"] = dummy_dec
            di[f"past_key_values.{layer}.encoder.key"] = dummy_enc
            di[f"past_key_values.{layer}.encoder.value"] = dummy_enc
        outs = s["decode"].run(None, di)
        nt = int(np.argmax(outs[0][0, -1]))
        if nt == EOS:
            break
        gen.append(nt)
        seq.append(nt)
    return gen


def pytorch_reference(task_text, img_path):
    """PyTorch model.generate() 参考输出。"""
    import torch
    from transformers import AutoProcessor, AutoModelForCausalLM
    proc = AutoProcessor.from_pretrained("microsoft/Florence-2-base", trust_remote_code=True)
    model = AutoModelForCausalLM.from_pretrained(
        "microsoft/Florence-2-base", trust_remote_code=True, torch_dtype=torch.float32
    ).eval()
    img = Image.open(img_path).convert("RGB")
    inputs = proc(text=task_text, images=img, return_tensors="pt")
    with torch.no_grad():
        out_ids = model.generate(
            **inputs, max_new_tokens=MAXT, num_beams=3, do_sample=False
        )
    text = proc.batch_decode(out_ids, skip_special_tokens=False)[0]
    return text


def main():
    s = load_sessions()
    if "--io" in sys.argv:
        inspect_all(s)
        return

    img = sys.argv[1] if len(sys.argv) > 1 and not sys.argv[1].startswith("--") else "input_images/face.jpg"
    tasks = ["<OD>", "<MORE_DETAILED_CAPTION>"]

    print(f"\n########## IMAGE: {img} ##########")
    # PyTorch 参考只对第一个 task 跑一次（慢）
    do_ref = "--ref" in sys.argv

    for task in tasks:
        print(f"\n========== TASK {task} ==========")
        pv, ids, am, proc = processor_inputs(task, img)
        print(f"pixel_values: {pv.shape}  input_ids: {ids.shape}")
        print(f"task token ids: {ids[0].tolist()}")
        print(f"task decoded  : {decode(ids[0].tolist())!r}")

        gen = run_onnx_pipeline(s, pv, ids)
        onnx_text = decode(gen)
        print(f"ONNX q4f16 ({len(gen)} toks): {onnx_text[:400]}")

        if do_ref:
            ref = pytorch_reference(task, img)
            print(f"PyTorch ref       : {ref[:400]}")


if __name__ == "__main__":
    main()
