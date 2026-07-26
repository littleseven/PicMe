#!/usr/bin/env python3
"""
获取 opus-mt-en-zh（英→中）ONNX INT8，产出 OpusMtTranslator 期望的文件布局。

实测路径（HF 在部分网络被墙）：用镜像 https://hf-mirror.com 下载
`onnx-community/opus-mt-en-zh` 的**已量化** ONNX（仓库已提供 *_quantized.onnx，
无需自量化、也绕过 Marian→ONNX 转换坑），拍平 onnx/ 前缀即可。

产出目录（整目录上传 ModelScope，如 budaoshou/OPUS-MT-En-Zh-ONNX-INT8）：
  encoder_model_quantized.onnx
  decoder_model_quantized.onnx
  decoder_with_past_model_quantized.onnx
  source.spm   # 英文（源语）
  target.spm   # 中文（目标语）
  tokenizer.json / tokenizer_config.json / config.json / ...

用法（国内）：
  HF_ENDPOINT=https://hf-mirror.com python3 scripts/fetch-opus-mt-en-zh.py --out /Users/guoshuai/code/opus-mt-en-zh
"""

import argparse
import os
import shutil
import sys

try:
    from huggingface_hub import hf_hub_download
except ImportError:
    sys.exit("pip install huggingface_hub")

REPO_ID = "onnx-community/opus-mt-en-zh"

# onnx/ 子目录里的量化文件 → 拍平到根，文件名不变
ONNX_QUANTIZED = [
    "onnx/encoder_model_quantized.onnx",
    "onnx/decoder_model_quantized.onnx",
    "onnx/decoder_with_past_model_quantized.onnx",
]
ROOT_FILES = [
    "source.spm", "target.spm", "config.json", "generation_config.json",
    "tokenizer.json", "tokenizer_config.json", "special_tokens_map.json",
]


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True)
    ap.add_argument("--repo", default=REPO_ID)
    args = ap.parse_args()
    os.makedirs(args.out, exist_ok=True)

    def fetch(path: str, required: bool) -> str | None:
        try:
            local = hf_hub_download(repo_id=args.repo, filename=path)
            dst = os.path.join(args.out, os.path.basename(path))
            shutil.copy2(local, dst)
            print(f"  ✓ {path} -> {os.path.basename(path)} ({os.path.getsize(dst) // 1024} KB)")
            return dst
        except Exception as e:
            print(f"  {'✗(必需)' if required else '~(可选)'} {path}: {type(e).__name__}: {e}")
            return None

    print(f"[1/2] 下载量化 ONNX（拍平 onnx/ 前缀）from {args.repo} ...")
    for f in ONNX_QUANTIZED:
        fetch(f, required=True)

    print("[2/2] 下载 tokenizer/config ...")
    for f in ROOT_FILES:
        fetch(f, required=(f in ("source.spm", "target.spm", "config.json")))

    # 校验方向
    cfg = os.path.join(args.out, "tokenizer_config.json")
    if os.path.exists(cfg):
        import json
        try:
            d = json.load(open(cfg))
            print(f"\n✅ 方向校验: source_lang={d.get('source_lang')} target_lang={d.get('target_lang')}")
            print("   OpusMtTranslator 据此构造 srcTag='>>source_lang<<'（应为 en/eng）")
        except Exception as e:
            print(f"  tokenizer_config 解析失败: {e}")
    print(f"\n产出目录: {args.out}")
    print("下一步：整目录上传 ModelScope → 把 llm_models.json 里 opus-mt-en-zh 的 sources.ModelScope 改成你的 repo")


if __name__ == "__main__":
    main()
