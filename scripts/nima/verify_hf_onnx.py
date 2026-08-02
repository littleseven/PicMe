#!/usr/bin/env python3
"""校验 HF cromsc/nima-mobilenet-aesthetic.onnx：探明预处理口径 + 输出打分公式。

判定标准（不靠猜，靠质量梯度）：
  - 正确预处理 → 分数合理铺开（不塌缩在 5.5±0.1），且 清晰 > 中模糊 > 重模糊。
  - 错误预处理 → 网络off-distribution，分数挤在窄区间、梯度无序。
模型已内置 Softmax（输出 dense[1,10] 求和=1），score = Σ p_i·(i+1) ∈ [1,10]。
"""
import sys
from pathlib import Path
import numpy as np
from PIL import Image, ImageFilter
import onnxruntime as ort

MODEL = "/tmp/nima_verify/nima_mobilenet_aesthetic.onnx"
BASE_DIR = Path("input_images")
# 基图 + 模糊梯度（用细节丰富的图，模糊才好区分）
GRADIENT_SRC = BASE_DIR / "volcano_img.png"
# 多样性真实照（看分数铺开）
REAL_IMAGES = [
    BASE_DIR / "volcano_img.png",
    BASE_DIR / "new_portrait.png",
    BASE_DIR / "face.jpg",
    BASE_DIR / "img_lyf.png",
]
SIZE = 224


def load_rgb(p):
    return Image.open(p).convert("RGB")


def to_224(img):
    return img.resize((SIZE, SIZE), Image.BILINEAR)


# ---- 预处理假设（输出均 NHWC float32 [1,224,224,3]，RGB）----
def prep_rescale(img):
    x = np.asarray(to_224(img), dtype=np.float32) / 255.0
    return x[None]                                  # [0,1]


def prep_mobilenetv2(img):
    x = np.asarray(to_224(img), dtype=np.float32) / 127.5 - 1.0
    return x[None]                                  # [-1,1]


def prep_imagenet(img):
    x = np.asarray(to_224(img), dtype=np.float32) / 255.0
    mean = np.array([0.485, 0.456, 0.406], dtype=np.float32)
    std = np.array([0.229, 0.224, 0.225], dtype=np.float32)
    x = (x - mean) / std
    return x[None]


def prep_raw(img):
    x = np.asarray(to_224(img), dtype=np.float32)
    return x[None]                                  # [0,255]


HYPOTHESES = {
    "H1 rescale[0,1]": prep_rescale,
    "H2 mobilenetv2[-1,1]": prep_mobilenetv2,
    "H3 imagenet m/std": prep_imagenet,
    "H4 raw[0,255]": prep_raw,
}


def score(session, inp):
    out = session.run(None, {"input": inp})[0][0]   # (10,)
    s_sum = float(out.sum())
    rating = float(np.sum(out * np.arange(1, 11)))  # Σ p_i·(i+1)
    return rating, s_sum, out


def main():
    sess = ort.InferenceSession(MODEL, providers=["CPUExecutionProvider"])

    # 质量梯度：清晰 / σ3 / σ8
    base = load_rgb(GRADIENT_SRC)
    gradient = {
        "sharp": base,
        "blur σ=3": base.filter(ImageFilter.GaussianBlur(3)),
        "blur σ=8": base.filter(ImageFilter.GaussianBlur(8)),
    }

    for name, fn in HYPOTHESES.items():
        print(f"\n===== {name} =====")
        print(f"  {'image':<14} {'score':>7} {'Σp':>7} {'argmax':>7}")
        # 梯度（关键判定）
        for label, img in gradient.items():
            r, psum, dist = score(sess, fn(img))
            print(f"  {label:<14} {r:7.3f} {psum:7.3f} {int(np.argmax(dist))+1:>7}")
        # 真实照铺开
        print("  --- real photos ---")
        for p in REAL_IMAGES:
            try:
                r, psum, dist = score(sess, fn(load_rgb(p)))
                print(f"  {p.name:<14} {r:7.3f} {psum:7.3f} {int(np.argmax(dist))+1:>7}")
            except Exception as e:
                print(f"  {p.name}: ERR {e}")

    # 判定提示
    print("\n===== 判定 =====")
    print("看 H1~H4 哪个：① sharp > blurσ3 > blurσ8（单调）；② real 分数有铺开（极差>0.5）；③ Σp≈1.0。")
    print("满足的就是正确口径。若都不单调/都塌缩 → 该现成模型不可信，走自转兜底。")


if __name__ == "__main__":
    main()
