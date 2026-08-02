#!/usr/bin/env python3
"""v2：用退化输入 + 分布尖度锁定 HF NIMA 的预处理口径。

退化集（纯噪声/极重模糊/细节摧毁）应明显低于真实照；正确口径下：
  - 真实照 vs 退化 分离度（separation）最大；
  - 真实照分布更尖（peakiness/max-prob 更高）；
  - 排序 sane（真实 > 退化）。
另测 proper crop（短边256→中心裁224）是否比 squash-resize224 分离更好。
"""
from pathlib import Path
import numpy as np
from PIL import Image, ImageFilter
import onnxruntime as ort

MODEL = "/tmp/nima_verify/nima_mobilenet_aesthetic.onnx"
BASE = Path("input_images")
REAL = [BASE / "volcano_img.png", BASE / "new_portrait.png", BASE / "face.jpg", BASE / "img_lyf.png"]


def load(p):
    return Image.open(p).convert("RGB")


def crop224(img):
    """短边缩到256，中心裁224（idealo 标准）。"""
    w, h = img.size
    s = min(w, h)
    new_w, new_h = int(w * 256 / s), int(h * 256 / s)
    img = img.resize((new_w, new_h), Image.BILINEAR)
    w, h = img.size
    l = (w - 224) // 2
    t = (h - 224) // 2
    return img.crop((l, t, l + 224, t + 224))


def squash224(img):
    return img.resize((224, 224), Image.BILINEAR)


def degenerate_set():
    base = load(REAL[0])
    return {
        "noise": Image.fromarray(np.random.randint(0, 256, (300, 300, 3), dtype=np.uint8)),
        "blur12": base.filter(ImageFilter.GaussianBlur(12)),
        "blur16": base.filter(ImageFilter.GaussianBlur(16)),
        "tiny-up": base.resize((16, 16), Image.BILINEAR).resize((base.size), Image.NEAREST),
    }


# (resize_fn, norm_fn)
CONFIGS = {
    "A squash+[0,1]": (squash224, lambda x: x / 255.0),
    "B squash+[-1,1]": (squash224, lambda x: x / 127.5 - 1.0),
    "C crop+[0,1]": (crop224, lambda x: x / 255.0),
    "D crop+[-1,1]": (crop224, lambda x: x / 127.5 - 1.0),
}


def run(sess, img, resize_fn, norm_fn):
    a = np.asarray(resize_fn(img), dtype=np.float32)
    x = norm_fn(a)[None]
    out = sess.run(None, {"input": x})[0][0]
    score = float(np.sum(out * np.arange(1, 11)))
    peak = float(np.max(out))
    return score, peak


def main():
    np.random.seed(0)
    sess = ort.InferenceSession(MODEL, providers=["CPUExecutionProvider"])
    real_imgs = [load(p) for p in REAL]
    degen = degenerate_set()

    print(f"{'config':<16} {'real_mean':>9} {'real_peak':>9} {'degen_mean':>10} {'degen_peak':>10} {'separation':>11} {'sane':>5}")
    best = None
    for name, (rf, nf) in CONFIGS.items():
        rs = [run(sess, im, rf, nf) for im in real_imgs]
        ds = [run(sess, im, rf, nf) for im in degen.values()]
        rm = np.mean([s for s, _ in rs])
        rp = np.mean([p for _, p in rs])
        dm = np.mean([s for s, _ in ds])
        dp = np.mean([p for _, p in ds])
        sep = rm - dm
        sane = all(rm_i > dm for rm_i in [s for s, _ in rs] for dm in [s for s, _ in ds]) if False else (rm > dm)
        flag = "✓" if (sep > 0.3 and rm > dm) else "✗"
        print(f"{name:<16} {rm:9.3f} {rp:9.3f} {dm:10.3f} {dp:10.3f} {sep:11.3f} {flag:>5}")
        if best is None or sep > best[1]:
            best = (name, sep)

    print("\n退化明细（最佳口径候选）：")
    rf, nf = CONFIGS["D crop+[-1,1]"]
    for k, im in degen.items():
        s, p = run(sess, im, rf, nf)
        print(f"  {k:<10} score={s:.3f} peak={p:.3f}")
    print("真实明细：")
    for pth, im in zip(REAL, real_imgs):
        s, pk = run(sess, im, rf, nf)
        print(f"  {pth.name:<18} score={s:.3f} peak={pk:.3f}")

    print(f"\n最大分离度口径：{best[0]} (sep={best[1]:.3f})")


if __name__ == "__main__":
    main()
