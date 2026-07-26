#!/usr/bin/env python3
"""Florence-2 ONNX 配置穷举测试：找出哪种 vision/encoder/embed/decoder 组合在 Mac ORT 能跑通 + 出正确标签。

预处理与 task token ids 全部用 HF processor ground truth（正确）。
"""
import sys, json, itertools
import numpy as np
import onnxruntime as ort
from PIL import Image

DIR = "/Users/guoshuai/code/florence-2-onnx"
NL = 6; EOS = 2; DEC_START = 2; MAXT = 128; HIDDEN = 768

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

def processor_inputs(task, img_path):
    from transformers import AutoProcessor
    proc = AutoProcessor.from_pretrained("microsoft/Florence-2-base", trust_remote_code=True)
    img = Image.open(img_path).convert("RGB")
    inp = proc(text=task, images=img, return_tensors="pt")
    return inp["pixel_values"].numpy(), inp["input_ids"].numpy().astype(np.int64)

def embed(s_emb, ids):
    return s_emb.run(None, {"input_ids": ids})[0]

def run(cfg, pv, task_ids):
    vis = sess(cfg["vis"]); enc = sess(cfg["enc"]); emb = sess(cfg["emb"])
    pref = sess(cfg["prefill"]); dec = sess(cfg["decode"])
    img_feats = vis.run(None, {"pixel_values": pv})[0]
    task_embeds = embed(emb, task_ids)
    ie = np.concatenate([img_feats, task_embeds], axis=1)
    T = ie.shape[1]; mask = np.ones((1, T), dtype=np.int64)
    enc_hs = enc.run(None, {"inputs_embeds": ie, "attention_mask": mask})[0]

    # prefill
    se = embed(emb, np.array([[DEC_START]], dtype=np.int64))
    po = pref.run(None, {"inputs_embeds": se, "encoder_hidden_states": enc_hs, "encoder_attention_mask": mask})
    nt = int(np.argmax(po[0][0, -1])); kv = po[1:]; gen = [nt]
    for step in range(1, MAXT):
        if nt == EOS: break
        ne = embed(emb, np.array([[nt]], dtype=np.int64))
        di = {"use_cache_branch": np.array([True]), "inputs_embeds": ne,
              "encoder_hidden_states": enc_hs, "encoder_attention_mask": mask}
        for L in range(NL):
            b = L * 4
            di[f"past_key_values.{L}.decoder.key"] = kv[b]
            di[f"past_key_values.{L}.decoder.value"] = kv[b+1]
            di[f"past_key_values.{L}.encoder.key"] = kv[b+2]
            di[f"past_key_values.{L}.encoder.value"] = kv[b+3]
        out = dec.run(None, di)
        nt = int(np.argmax(out[0][0, -1])); gen.append(nt); kv = out[1:]
    return gen

CONFIGS = [
    {"name":"A: q4 prefill + q4 merged", "vis":"vision_encoder_quantized.onnx","enc":"encoder_model_q4f16.onnx","emb":"embed_tokens_q4f16.onnx","prefill":"decoder_model_q4f16.onnx","decode":"decoder_model_merged_q4.onnx"},
    {"name":"B: q4 prefill + INT8 merged","vis":"vision_encoder_quantized.onnx","enc":"encoder_model_q4f16.onnx","emb":"embed_tokens_q4f16.onnx","prefill":"decoder_model_q4f16.onnx","decode":"decoder_model_merged_quantized.onnx"},
    {"name":"C: INT8 prefill + INT8 merged","vis":"vision_encoder_quantized.onnx","enc":"encoder_model_quantized.onnx","emb":"embed_tokens_int8.onnx","prefill":"decoder_model_quantized.onnx","decode":"decoder_model_merged_quantized.onnx"},
    {"name":"D: INT8 prefill + q4 merged","vis":"vision_encoder_quantized.onnx","enc":"encoder_model_quantized.onnx","emb":"embed_tokens_int8.onnx","prefill":"decoder_model_quantized.onnx","decode":"decoder_model_merged_q4.onnx"},
    {"name":"E: all-q4 (vision q4f16)","vis":"vision_encoder_q4f16.onnx","enc":"encoder_model_q4f16.onnx","emb":"embed_tokens_q4f16.onnx","prefill":"decoder_model_q4f16.onnx","decode":"decoder_model_merged_q4.onnx"},
]

def main():
    img = sys.argv[1] if len(sys.argv) > 1 else "input_images/face.jpg"
    pv, ids = processor_inputs("<OD>", img)
    print(f"image={img}  task token ids={ids[0].tolist()}\n")
    for cfg in CONFIGS:
        try:
            gen = run(cfg, pv, ids)
            txt = decode(gen)
            print(f"[OK]   {cfg['name']}\n        ({len(gen)} toks) {txt[:200]}\n")
        except Exception as e:
            msg = str(e)
            # extract the useful bit
            if "Status Message:" in msg:
                msg = msg.split("Status Message:")[-1].strip().split("\n")[0]
            print(f"[FAIL] {cfg['name']}\n        {msg[:160]}\n")

if __name__ == "__main__":
    main()
