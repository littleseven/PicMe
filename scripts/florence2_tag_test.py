#!/usr/bin/env python3
"""Florence-2-base 打标测试：<OD> 物体检测 → tags, <CAPTION> → summary。
对比 SmolVLM：看 Florence-2 能否稳定出结构化标签。"""
import numpy
for _a,_t in (("long",int),("ulong",int),("int",int),("float",float),("complex",complex),("bool",bool),("object",object),("str",str)):
    if not hasattr(numpy,_a): setattr(numpy,_a,_t)
import torch
from transformers import AutoProcessor, AutoModelForCausalLM
from PIL import Image

MODEL = "microsoft/Florence-2-base"
IMAGES = [
    "input_images/face.jpg",
    "input_images/volcano_img.png",
    "input_images/img_lyf.png",
    "input_images/new_portrait.png",
    "docs/assets/winxin.jpg",
    "input_images/img_MLKit.png",
]


def run_task(model, proc, img, task_prompt):
    inputs = proc(text=task_prompt, images=img, return_tensors="pt")
    with torch.no_grad():
        gen = model.generate(**inputs, max_new_tokens=512, num_beams=3, do_sample=False)
    return proc.batch_decode(gen, skip_special_tokens=True)[0].strip()


def main():
    print(f"loading {MODEL} ...")
    proc = AutoProcessor.from_pretrained(MODEL, trust_remote_code=True)
    model = AutoModelForCausalLM.from_pretrained(MODEL, trust_remote_code=True, torch_dtype="float32").eval()
    n = sum(p.numel() for p in model.parameters()) / 1e6
    print(f"loaded. {n:.0f}M params\n")

    for p in IMAGES:
        try: img = Image.open(p).convert("RGB")
        except Exception as e: print(f"[{p}] {e}\n"); continue
        od = run_task(model, proc, img, "<OD>")
        cap = run_task(model, proc, img, "<CAPTION>")
        # 从 OD 结果提取标签（Florence-2 输出格式：label1<loc...>label2<loc...>）
        import re
        labels = re.findall(r'[A-Za-z ]+?(?=<loc)', od)
        labels = [l.strip() for l in labels if l.strip()]
        print(f"===== {p} =====")
        print(f"  <OD> tags: {labels if labels else od[:200]}")
        print(f"  <CAPTION>: {cap[:200]}")
        print()


if __name__ == "__main__":
    main()
