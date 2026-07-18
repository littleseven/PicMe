#!/usr/bin/env python3
"""
Screenshot Diff - PoLang 截图像素级对比工具
用途: 对比当前截图与基准截图，检测 UI 回归、渲染异常、布局偏移
调用: python3 scripts/screenshot-diff.py --baseline <path> --current <path> [options]

Options:
    --baseline <path>   基准截图路径
    --current <path>    当前截图路径
    --threshold <float> 相似度阈值 (默认: 0.95)
    --output <path>     输出 diff 图片路径
    --ignore <regions>  忽略区域，格式: x,y,w,h;x,y,w,h
    --report            输出 JSON 格式报告

示例:
    python3 scripts/screenshot-diff.py --baseline baseline/camera.png --current current.png
    python3 scripts/screenshot-diff.py --baseline baseline.png --current screen.png --threshold 0.90 --output diff.png
"""

import sys
import argparse
import json
from pathlib import Path
from PIL import Image
import numpy as np


def parse_ignore_regions(regions_str: str) -> list:
    """解析忽略区域字符串，格式: x,y,w,h;x,y,w,h"""
    if not regions_str:
        return []
    regions = []
    for region in regions_str.split(";"):
        parts = [int(p.strip()) for p in region.split(",")]
        if len(parts) == 4:
            regions.append(tuple(parts))
    return regions


def apply_ignore_regions(img_array: np.ndarray, regions: list) -> np.ndarray:
    """将忽略区域置为黑色（不计入对比）"""
    result = img_array.copy()
    for x, y, w, h in regions:
        result[y:y+h, x:x+w] = 0
    return result


def compute_similarity(baseline: np.ndarray, current: np.ndarray) -> dict:
    """计算两张图片的相似度指标"""
    # 确保尺寸一致
    if baseline.shape != current.shape:
        return {
            "match": False,
            "reason": f"尺寸不匹配: 基准{baseline.shape} vs 当前{current.shape}",
            "similarity": 0.0,
        }

    # 像素级差异
    diff = np.abs(baseline.astype(float) - current.astype(float))
    
    # 平均差异值 (0-255)
    mean_diff = float(np.mean(diff))
    
    # 最大差异值
    max_diff = float(np.max(diff))
    
    # 差异像素比例 (> 10 的视为差异)
    threshold = 10
    diff_pixels = np.sum(diff > threshold)
    total_pixels = baseline.shape[0] * baseline.shape[1] * baseline.shape[2] if len(baseline.shape) == 3 else baseline.shape[0] * baseline.shape[1]
    diff_ratio = float(diff_pixels / total_pixels) if total_pixels > 0 else 0.0
    
    # 结构相似性 (简化版 SSIM)
    # 使用局部窗口的均值差异
    window_size = 8
    h, w = baseline.shape[:2]
    ssim_score = 1.0
    
    if h >= window_size and w >= window_size:
        ssim_scores = []
        for y in range(0, h - window_size + 1, window_size):
            for x in range(0, w - window_size + 1, window_size):
                b_window = baseline[y:y+window_size, x:x+window_size].astype(float)
                c_window = current[y:y+window_size, x:x+window_size].astype(float)
                
                b_mean = np.mean(b_window)
                c_mean = np.mean(c_window)
                b_var = np.var(b_window)
                c_var = np.var(c_window)
                cov = np.mean((b_window - b_mean) * (c_window - c_mean))
                
                c1 = (0.01 * 255) ** 2
                c2 = (0.03 * 255) ** 2
                
                numerator = (2 * b_mean * c_mean + c1) * (2 * cov + c2)
                denominator = (b_mean**2 + c_mean**2 + c1) * (b_var + c_var + c2)
                
                if denominator > 0:
                    ssim_scores.append(numerator / denominator)
        
        if ssim_scores:
            ssim_score = float(np.mean(ssim_scores))
    
    # 综合相似度 (SSIM 加权)
    similarity = ssim_score * 0.7 + (1.0 - diff_ratio) * 0.3
    
    return {
        "similarity": round(similarity, 4),
        "ssim": round(ssim_score, 4),
        "mean_diff": round(mean_diff, 2),
        "max_diff": round(max_diff, 2),
        "diff_ratio": round(diff_ratio, 4),
        "diff_pixels": int(diff_pixels),
        "total_pixels": int(total_pixels),
    }


def generate_diff_image(baseline: np.ndarray, current: np.ndarray, output_path: str):
    """生成差异热力图"""
    diff = np.abs(baseline.astype(float) - current.astype(float))
    
    # 归一化到 0-255
    max_val = np.max(diff)
    if max_val > 0:
        diff_normalized = (diff / max_val * 255).astype(np.uint8)
    else:
        diff_normalized = diff.astype(np.uint8)
    
    # 创建热力图：差异越大越红
    h, w = diff_normalized.shape[:2]
    heatmap = np.zeros((h, w, 3), dtype=np.uint8)
    
    if len(diff_normalized.shape) == 3:
        gray_diff = np.mean(diff_normalized, axis=2).astype(np.uint8)
    else:
        gray_diff = diff_normalized
    
    # 红色通道 = 差异强度
    heatmap[:, :, 0] = gray_diff
    # 绿色通道 = 255 - 差异强度（使无差异区域呈绿色）
    heatmap[:, :, 1] = 255 - gray_diff
    
    # 叠加原图（半透明）
    alpha = 0.3
    if len(baseline.shape) == 3:
        overlay = baseline.copy()
    else:
        overlay = np.stack([baseline, baseline, baseline], axis=2)
    
    blended = (overlay * (1 - alpha) + heatmap * alpha).astype(np.uint8)
    
    Image.fromarray(blended).save(output_path)
    return output_path


def main():
    parser = argparse.ArgumentParser(description="PoLang Screenshot Diff Tool")
    parser.add_argument("--baseline", required=True, help="基准截图路径")
    parser.add_argument("--current", required=True, help="当前截图路径")
    parser.add_argument("--threshold", type=float, default=0.95, help="相似度阈值 (默认: 0.95)")
    parser.add_argument("--output", help="输出 diff 图片路径")
    parser.add_argument("--ignore", help="忽略区域，格式: x,y,w,h;x,y,w,h")
    parser.add_argument("--report", action="store_true", help="输出 JSON 报告")
    
    args = parser.parse_args()
    
    # 检查文件
    if not Path(args.baseline).exists():
        print(f"❌ 基准截图不存在: {args.baseline}", file=sys.stderr)
        sys.exit(1)
    
    if not Path(args.current).exists():
        print(f"❌ 当前截图不存在: {args.current}", file=sys.stderr)
        sys.exit(1)
    
    # 加载图片
    baseline_img = Image.open(args.baseline).convert("RGB")
    current_img = Image.open(args.current).convert("RGB")
    
    # 如果尺寸不同，将当前图缩放至基准图尺寸
    if baseline_img.size != current_img.size:
        print(f"⚠️ 尺寸不同: 基准{baseline_img.size} vs 当前{current_img.size}，将缩放当前图")
        current_img = current_img.resize(baseline_img.size, Image.Resampling.LANCZOS)
    
    baseline_arr = np.array(baseline_img)
    current_arr = np.array(current_img)
    
    # 应用忽略区域
    ignore_regions = parse_ignore_regions(args.ignore)
    if ignore_regions:
        print(f"ℹ️ 忽略区域: {ignore_regions}")
        baseline_arr = apply_ignore_regions(baseline_arr, ignore_regions)
        current_arr = apply_ignore_regions(current_arr, ignore_regions)
    
    # 计算相似度
    result = compute_similarity(baseline_arr, current_arr)
    result["baseline"] = args.baseline
    result["current"] = args.current
    result["threshold"] = args.threshold
    result["pass"] = result["similarity"] >= args.threshold
    
    # 生成 diff 图
    if args.output:
        generate_diff_image(baseline_arr, current_arr, args.output)
        result["diff_image"] = args.output
    
    # 输出
    if args.report:
        print(json.dumps(result, indent=2))
    else:
        print("=" * 50)
        print("📊 PoLang Screenshot Diff Report")
        print("=" * 50)
        print(f"基准: {args.baseline}")
        print(f"当前: {args.current}")
        print(f"阈值: {args.threshold}")
        print("")
        print(f"相似度:     {result['similarity']:.2%}")
        print(f"SSIM:       {result['ssim']:.4f}")
        print(f"平均差异:   {result['mean_diff']:.1f}/255")
        print(f"最大差异:   {result['max_diff']:.1f}/255")
        print(f"差异像素:   {result['diff_pixels']:,} / {result['total_pixels']:,} ({result['diff_ratio']:.2%})")
        print("")
        if result["pass"]:
            print(f"✅ 通过: 相似度 {result['similarity']:.2%} >= 阈值 {args.threshold}")
        else:
            print(f"❌ 失败: 相似度 {result['similarity']:.2%} < 阈值 {args.threshold}")
            if args.output:
                print(f"📄 Diff 图: {args.output}")
        print("=" * 50)
    
    sys.exit(0 if result["pass"] else 1)


if __name__ == "__main__":
    main()
