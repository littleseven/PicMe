#!/usr/bin/env python3
"""测 VQA 风格 prompt 能否从 SmolVLM-500M 拿到可用标签（替代结构化 JSON）。"""
import numpy
for _a,_t in (("long",int),("ulong",int),("int",int),("float",float),("longfloat",float),
              ("complex",complex),("longcomplex",complex),("cfloat",complex),("bool",bool),
              ("object",object),("str",str),("unicode",str)):
    if not hasattr(numpy,_a): setattr(numpy,_a,_t)
import torch
from transformers import AutoProcessor, AutoModelForImageTextToText
from PIL import Image

MODEL = "HuggingFaceTB/SmolVLM-500M-Instruct"
IMAGES = ["input_images/face.jpg", "docs/assets/winxin.jpg"]

# VQA 风格：像问问题一样提取标签（开放式，不给格式约束/示例）
VQA_PROMPTS = {
    "V1_关键词": "I'm building a photo gallery. What keywords describe this image? Just list them separated by commas.",
    "V2_问答式": "What is the main subject of this photo? What objects are visible? Where was this taken? Answer briefly.",
    "V3_一句话+词": "Describe this photo in one sentence. Then write 5 keywords separated by commas.",
}


def run(model, proc, img, prompt):
    msgs = [{"role":"user","content":[{"type":"image"},{"type":"text","text":prompt}]}]
    text = proc.apply_chat_template(msgs, add_generation_prompt=True)
    inputs = proc(text=[text], images=[img], return_tensors="pt")
    n = inputs.input_ids.shape[1]
    with torch.no_grad():
        gen = model.generate(**inputs, max_new_tokens=200, do_sample=False)
    return proc.decode(gen[0][n:], skip_special_tokens=True).strip()


def main():
    print(f"loading {MODEL} ...")
    proc = AutoProcessor.from_pretrained(MODEL)
    model = AutoModelForImageTextToText.from_pretrained(MODEL, torch_dtype=torch.float32).eval()
    print("loaded.\n")
    for p in IMAGES:
        try: img = Image.open(p).convert("RGB")
        except Exception as e: print(f"[{p}] {e}\n"); continue
        print(f"========== {p} ==========")
        for name, pr in VQA_PROMPTS.items():
            print(f"--- {name} ---"); print(run(model, proc, img, pr)[:350])
        print()


if __name__ == "__main__":
    main()
