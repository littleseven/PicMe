#!/usr/bin/env python3
"""Florence-2 用独立 decoder（非 merged）跑 ORT，验证正确性。"""
import numpy as np, onnxruntime as ort, json, os
from PIL import Image

DIR = "/Users/guoshuai/code/florence-2-onnx"
HS = 768; NL = 6; DSTART = 2; EOS = 2; MAXT = 256
TASK_OD = np.array([0,41552,7111,15698,2], dtype=np.int64)
TASK_CAP = np.array([0,41552,38543,1215,495,19739,3063,1691,1215,28494,10263,15698,2], dtype=np.int64)
vocab = json.load(open(f"{DIR}/vocab.json")); added = json.load(open(f"{DIR}/added_tokens.json"))
id2tok = {v:k for k,v in vocab.items()}
for k,v in added.items(): id2tok[v] = k
def decode(ids):
    return "".join(id2tok.get(t,"").replace("Ġ"," ").replace("Ċ","\n") if not (id2tok.get(t,"").startswith("<") and id2tok.get(t,"").endswith(">")) else id2tok.get(t,"") for t in ids).strip()

opts = ort.SessionOptions(); opts.intra_op_num_threads=4
vis=ort.InferenceSession(f"{DIR}/vision_encoder_quantized.onnx",opts,providers=["CPUExecutionProvider"])
enc=ort.InferenceSession(f"{DIR}/encoder_model_quantized.onnx",opts,providers=["CPUExecutionProvider"])
emb=ort.InferenceSession(f"{DIR}/embed_tokens_int8.onnx",opts,providers=["CPUExecutionProvider"])
dec0=ort.InferenceSession(f"{DIR}/decoder_model_quantized.onnx",opts,providers=["CPUExecutionProvider"])
dec1=ort.InferenceSession(f"{DIR}/decoder_with_past_model_quantized.onnx",opts,providers=["CPUExecutionProvider"])
print("5 sessions loaded (separate decoders)\n")

def preprocess(p):
    img=Image.open(p).convert("RGB"); w,h=img.size; cs=min(w,h)
    img=img.crop(((w-cs)//2,(h-cs)//2,(w+cs)//2,(h+cs)//2)).resize((768,768))
    px=(np.array(img,dtype=np.float32)/255-0.5)/0.5
    return np.transpose(px,(2,0,1))[np.newaxis]

def run_task(pv, tids, name):
    ivf=vis.run(None,{"pixel_values":pv})[0]
    te=emb.run(None,{"input_ids":tids[np.newaxis]})[0]
    ie=np.concatenate([ivf,te],axis=1); T=ie.shape[1]
    am=np.ones((1,T),dtype=np.int64)
    eo=enc.run(None,{"inputs_embeds":ie,"attention_mask":am})[0]
    print(f"  [{name}] vis={ivf.shape[1]} enc_out={eo.shape}")

    gen=[]; cur=DSTART; kv={}
    for step in range(MAXT):
        te=emb.run(None,{"input_ids":np.array([[cur]],dtype=np.int64)})[0]
        if step==0:
            outs=dec0.run(None,{"encoder_hidden_states":eo,"encoder_attention_mask":am,"inputs_embeds":te})
            # outputs[0]=logits, [1:25]=KV (24 tensors, 6 layers × 4)
        else:
            di={"encoder_hidden_states":eo,"encoder_attention_mask":am,"inputs_embeds":te}
            for layer in range(NL):
                for j,kind in enumerate(["decoder.key","decoder.value","encoder.key","encoder.value"]):
                    di[f"past_key_values.{layer}.{kind}"]=kv[f"past_key_values.{layer}.{kind}"]
            outs=dec1.run(None,di)

        nid=int(np.argmax(outs[0][0,-1]))
        # save KV
        kv={}
        for layer in range(NL):
            base=1+layer*4
            for j,kind in enumerate(["decoder.key","decoder.value","encoder.key","encoder.value"]):
                kv[f"past_key_values.{layer}.{kind}"]=outs[base+j]
        if nid==EOS: print(f"  [{name}] EOS@{step}"); break
        gen.append(nid); cur=nid
    txt=decode(gen)
    print(f"  [{name}] {len(gen)} tokens: {txt[:250]}")
    return txt

for p in ["input_images/face.jpg","docs/assets/winxin.jpg","input_images/img_lyf.png"]:
    print(f"===== {p} =====")
    try:
        pv=preprocess(p)
        od=run_task(pv,TASK_OD,"OD"); cap=run_task(pv,TASK_CAP,"CAP")
        print(f"  → OD: {od[:200]}")
        print(f"  → Caption: {cap[:200]}\n")
    except Exception as e: print(f"  ERROR: {e}\n")
