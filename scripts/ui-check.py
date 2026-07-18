#!/usr/bin/env python3
"""
UI Check - PoLang UI 布局自动校验工具
用途: 通过像素分析验证截图中关键 UI 元素是否存在、位置是否正确
调用: python3 scripts/ui-check.py --screenshot <path> --check <check_type> [options]

说明: 本工具使用像素/颜色分析进行 UI 校验，无需 OCR 引擎。
      如需文字识别能力，可安装 tesseract: brew install tesseract

Options:
    --screenshot <path>   截图路径
    --check <type>        检查类型: blackscreen|fps_overlay|shutter_button|gallery_grid|face_landmarks
    --baseline <path>     基准截图（用于对比）
    --output <path>       输出标注后的截图
    --report              输出 JSON 报告

示例:
    python3 scripts/ui-check.py --screenshot screen.png --check blackscreen
    python3 scripts/ui-check.py --screenshot screen.png --check fps_overlay --output checked.png
    python3 scripts/ui-check.py --screenshot camera.png --check shutter_button
"""

import sys
import argparse
import json
from pathlib import Path
from PIL import Image
import numpy as np


def check_blackscreen(img_array: np.ndarray) -> dict:
    """检查是否黑屏"""
    # 计算平均亮度
    if len(img_array.shape) == 3:
        gray = np.mean(img_array, axis=2)
    else:
        gray = img_array.astype(float)
    
    mean_brightness = float(np.mean(gray))
    max_brightness = float(np.max(gray))
    
    # 黑屏判定：平均亮度 < 10 且最大亮度 < 30
    is_black = mean_brightness < 10 and max_brightness < 30
    
    return {
        "check": "blackscreen",
        "pass": not is_black,
        "mean_brightness": round(mean_brightness, 2),
        "max_brightness": round(max_brightness, 2),
        "message": "黑屏检测" if is_black else "画面正常",
    }


def check_fps_overlay(img_array: np.ndarray) -> dict:
    """检查 FPS 调试浮层是否存在（左上角区域）"""
    h, w = img_array.shape[:2]
    
    # FPS 浮层通常在左上角 (0, 0) 到 (w*0.3, h*0.1)
    overlay_region = img_array[0:int(h*0.1), 0:int(w*0.3)]
    
    # FPS 浮层通常有较高的文字对比度
    if len(overlay_region.shape) == 3:
        gray = np.mean(overlay_region, axis=2)
    else:
        gray = overlay_region.astype(float)
    
    # 检查该区域是否有明显的文字特征（高对比度像素比例）
    # 文字通常是亮色在暗色背景上，或暗色在亮色背景上
    std_dev = float(np.std(gray))
    
    # 标准差 > 30 说明有文字/图形内容
    has_overlay = std_dev > 30
    
    return {
        "check": "fps_overlay",
        "pass": True,  # FPS 浮层是可选的
        "std_dev": round(std_dev, 2),
        "detected": has_overlay,
        "message": "检测到 FPS 浮层" if has_overlay else "未检测到 FPS 浮层",
    }


def check_shutter_button(img_array: np.ndarray) -> dict:
    """检查快门按钮是否存在（通常在底部中央）"""
    h, w = img_array.shape[:2]
    
    # 快门按钮区域：底部中央 (w*0.3, h*0.75) 到 (w*0.7, h*0.95)
    button_region = img_array[int(h*0.75):int(h*0.95), int(w*0.3):int(w*0.7)]
    
    if len(button_region.shape) == 3:
        gray = np.mean(button_region, axis=2)
    else:
        gray = button_region.astype(float)
    
    # 快门按钮通常是圆形，有明显的边缘
    # 简单判断：区域内亮度变化较大（按钮 vs 背景）
    std_dev = float(np.std(gray))
    mean_val = float(np.mean(gray))
    
    # 标准差 > 20 且平均亮度在合理范围
    has_button = std_dev > 20 and 20 < mean_val < 240
    
    return {
        "check": "shutter_button",
        "pass": has_button,
        "std_dev": round(std_dev, 2),
        "mean_brightness": round(mean_val, 2),
        "message": "检测到快门按钮区域" if has_button else "未检测到快门按钮区域",
    }


def check_gallery_grid(img_array: np.ndarray) -> dict:
    """检查相册网格布局"""
    h, w = img_array.shape[:2]
    
    # 相册主区域 (顶部到 90% 高度)
    gallery_region = img_array[0:int(h*0.9), :]
    
    if len(gallery_region.shape) == 3:
        gray = np.mean(gallery_region, axis=2)
    else:
        gray = gallery_region.astype(float)
    
    # 网格布局特征：存在规律的边缘/分隔线
    # 使用梯度检测
    dx = np.abs(np.diff(gray, axis=1))
    dy = np.abs(np.diff(gray, axis=0))
    
    # 检测强边缘
    strong_edges_x = np.sum(dx > 50) / dx.size
    strong_edges_y = np.sum(dy > 50) / dy.size
    
    # 网格应该有水平和垂直的边缘
    has_grid = strong_edges_x > 0.01 and strong_edges_y > 0.01
    
    return {
        "check": "gallery_grid",
        "pass": has_grid,
        "horizontal_edges": round(strong_edges_y * 100, 2),
        "vertical_edges": round(strong_edges_x * 100, 2),
        "message": "检测到网格布局特征" if has_grid else "未检测到网格布局特征",
    }


def check_face_landmarks(img_array: np.ndarray) -> dict:
    """检查人脸关键点覆盖层是否存在"""
    h, w = img_array.shape[:2]
    
    # 人脸通常在画面中央区域
    center_region = img_array[int(h*0.2):int(h*0.8), int(w*0.2):int(w*0.8)]
    
    if len(center_region.shape) == 3:
        # 关键点通常是亮色线条（绿色/红色/白色）
        # 检查绿色通道的高亮像素
        green_channel = center_region[:, :, 1]
        red_channel = center_region[:, :, 0]
        
        # 绿色高亮像素比例（MediaPipe 默认绿色）
        green_highlight = np.sum(green_channel > 200) / green_channel.size
        red_highlight = np.sum(red_channel > 200) / red_channel.size
        
        has_landmarks = green_highlight > 0.001 or red_highlight > 0.001
        
        return {
            "check": "face_landmarks",
            "pass": True,  # 关键点覆盖层是可选的
            "green_highlight": round(green_highlight * 100, 4),
            "red_highlight": round(red_highlight * 100, 4),
            "detected": has_landmarks,
            "message": "检测到关键点覆盖层" if has_landmarks else "未检测到关键点覆盖层",
        }
    else:
        return {
            "check": "face_landmarks",
            "pass": True,
            "detected": False,
            "message": "灰度图无法检测关键点颜色",
        }


def annotate_image(img_array: np.ndarray, check_results: list) -> np.ndarray:
    """在图片上标注检查结果"""
    from PIL import ImageDraw, ImageFont
    
    img = Image.fromarray(img_array)
    draw = ImageDraw.Draw(img)
    
    h, w = img_array.shape[:2]
    
    # 尝试加载字体
    try:
        font = ImageFont.truetype("/System/Library/Fonts/Helvetica.ttc", 20)
    except:
        font = ImageFont.load_default()
    
    # 绘制信息面板
    panel_x = 10
    panel_y = 10
    line_height = 25
    
    for i, result in enumerate(check_results):
        text = f"{result['check']}: {'PASS' if result['pass'] else 'FAIL'}"
        color = (0, 255, 0) if result['pass'] else (255, 0, 0)
        
        draw.text((panel_x, panel_y + i * line_height), text, fill=color, font=font)
    
    return np.array(img)


CHECK_FUNCTIONS = {
    "blackscreen": check_blackscreen,
    "fps_overlay": check_fps_overlay,
    "shutter_button": check_shutter_button,
    "gallery_grid": check_gallery_grid,
    "face_landmarks": check_face_landmarks,
}


def main():
    parser = argparse.ArgumentParser(description="PoLang UI Check Tool")
    parser.add_argument("--screenshot", required=True, help="截图路径")
    parser.add_argument("--check", required=True, help="检查类型，逗号分隔: " + ",".join(CHECK_FUNCTIONS.keys()))
    parser.add_argument("--output", help="输出标注后的截图路径")
    parser.add_argument("--report", action="store_true", help="输出 JSON 报告")
    
    args = parser.parse_args()
    
    if not Path(args.screenshot).exists():
        print(f"❌ 截图不存在: {args.screenshot}", file=sys.stderr)
        sys.exit(1)
    
    # 加载图片
    img = Image.open(args.screenshot).convert("RGB")
    img_array = np.array(img)
    
    # 执行检查
    check_types = [c.strip() for c in args.check.split(",")]
    results = []
    all_pass = True
    
    for check_type in check_types:
        if check_type not in CHECK_FUNCTIONS:
            print(f"⚠️ 未知检查类型: {check_type}", file=sys.stderr)
            continue
        
        result = CHECK_FUNCTIONS[check_type](img_array)
        results.append(result)
        if not result["pass"]:
            all_pass = False
    
    # 输出报告
    if args.report:
        print(json.dumps({
            "screenshot": args.screenshot,
            "all_pass": all_pass,
            "checks": results,
        }, indent=2))
    else:
        print("=" * 50)
        print("📱 PoLang UI Check Report")
        print("=" * 50)
        print(f"截图: {args.screenshot}")
        print(f"分辨率: {img.size[0]}x{img.size[1]}")
        print("")
        
        for result in results:
            status = "✅ PASS" if result["pass"] else "❌ FAIL"
            print(f"{status} {result['check']}")
            print(f"       {result['message']}")
            # 输出额外指标
            for key, value in result.items():
                if key not in ("check", "pass", "message"):
                    print(f"       {key}: {value}")
            print("")
        
        print("=" * 50)
        if all_pass:
            print("✅ 所有检查通过")
        else:
            print("❌ 存在未通过的检查")
        print("=" * 50)
    
    # 保存标注图
    if args.output:
        annotated = annotate_image(img_array, results)
        Image.fromarray(annotated).save(args.output)
        print(f"📄 标注图已保存: {args.output}")
    
    sys.exit(0 if all_pass else 1)


if __name__ == "__main__":
    main()
