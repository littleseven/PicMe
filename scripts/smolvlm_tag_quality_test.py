#!/usr/bin/env python3
"""找 SmolVLM-500M 能 follow 的打标 prompt（不给填好的示例，避免照抄）。"""
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

# 候选 prompt（都不给填好的 JSON 示例）
PROMPTS = {
    "P1_键值对": "Describe this photo. Output exactly these lines:\nscene: <one word place>\nactivity: <one phrase>\ntags: <8 English nouns, comma-separated>\nsummary: <one sentence>",
    "P2_只要tags+summary": "Give 8 English nouns describing this photo (comma-separated), then a one-sentence summary. Output only:\ntags: ...\nsummary: ...",
    "P3_先描述再提炼": "First describe this photo in one sentence. Then list 8 English nouns describing it.",
}


def run(model, proc, img, prompt):
    msgs = [{"role":"user","content":[{"type":"image"},{"type":"text","text":prompt}]}]
    text = proc.apply_chat_template(msgs, add_generation_prompt=True)
    inputs = proc(text=[text], images=[img], return_tensors="pt")
    n = inputs.input_ids.shape[1]
    with torch.no_grad():
        gen = model.generate(**inputs, max_new_tokens=180, do_sample=False)
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
        for name, pr in PROMPTS.items():
            print(f"--- {name} ---")
            print(run(model, proc, img, pr)[:350])
        print()


if __name__ == "__main__":
    main()
