#!/usr/bin/env python3
"""
图片质量分析脚本
检测黑屏、人脸位置、调试信息等
"""

import sys
import os
from PIL import Image
import numpy as np

def analyze_image(image_path, detect_face=False):
    """分析图片质量"""
    if not os.path.exists(image_path):
        print(f"错误: 文件不存在 - {image_path}")
        return False
    
    print(f"\n{'='*50}")
    print(f"=== 图片质量分析报告 ===")
    print(f"{'='*50}\n")
    
    # 1. 基本信息
    try:
        img = Image.open(image_path)
        width, height = img.size
        mode = img.mode
        file_size = os.path.getsize(image_path) / (1024 * 1024)  # MB
        
        print(f"文件: {os.path.basename(image_path)}")
        print(f"分辨率: {width}x{height}")
        print(f"色彩模式: {mode}")
        print(f"文件大小: {file_size:.2f} MB\n")
        
        # 转换为 numpy 数组进行分析
        img_array = np.array(img)
        
    except Exception as e:
        print(f"错误: 无法打开图片 - {e}")
        return False
    
    # 2. 黑屏检测
    is_black, avg_brightness = check_black_screen(img_array)
    status = "✗ 黑屏!" if is_black else "✓ 正常"
    print(f"[{status}] 黑屏检测: {'全黑或接近全黑' if is_black else '正常'} (平均亮度: {avg_brightness:.1f}/255)")
    
    if is_black:
        print("    ⚠ 建议: 检查 GPU 渲染管线、FBO 状态、EGL 上下文")
    
    # 3. 人脸检测（可选）
    if detect_face:
        has_face, face_info = detect_faces_simple(img_array, width, height)
        status = "✓ 检测到" if has_face else "✗ 未检测到"
        print(f"\n[{status}] 人脸检测: {face_info}")
        
        if not has_face:
            print("    ⚠ 建议: 检查光线、摄像头角度、人脸检测引擎配置")
    
    # 4. 调试信息检测
    has_debug = check_debug_overlay(img_array)
    status = "✓ 检测到" if has_debug else "○ 未检测到"
    print(f"\n[{status}] 调试信息: {'存在文字覆盖' if has_debug else '无调试信息'}")
    
    # 5. 总结
    print(f"\n{'='*50}")
    overall_pass = not is_black
    conclusion = "画面质量合格" if overall_pass else "画面质量异常，请检查上述问题"
    print(f"结论: {conclusion}")
    print(f"{'='*50}\n")
    
    return overall_pass


def check_black_screen(img_array, threshold=5.0):
    """
    检测是否黑屏
    返回: (is_black, avg_brightness)
    """
    if len(img_array.shape) == 3:
        # RGB/RGBA: 计算所有通道的平均值
        brightness = np.mean(img_array[:, :, :3])
    else:
        # Grayscale
        brightness = np.mean(img_array)
    
    is_black = brightness < threshold
    return is_black, brightness


def detect_faces_simple(img_array, width, height):
    """
    简化版人脸检测（基于亮度和边缘）
    实际项目中应集成 ML Kit 或 InsightFace
    """
    # 这里仅作示例，实际应调用项目的人脸检测模块
    # 可以通过 adb 命令触发应用内的人脸检测并读取日志
    
    has_face = False
    info = "需要启用 --detect-face 并集成人脸检测模型"
    
    # 简单启发式：如果画面有明显的亮度变化区域，可能有人脸
    if len(img_array.shape) == 3:
        gray = np.mean(img_array[:, :, :3], axis=2)
        variance = np.var(gray)
        
        # 方差较大说明有内容（非纯色）
        if variance > 1000:
            has_face = True
            info = "检测到画面内容（需完整人脸模型确认）"
    
    return has_face, info


def check_debug_overlay(img_array):
    """
    检测是否有调试文字覆盖
    通过检测高对比度小区域来判断
    """
    if len(img_array.shape) != 3:
        return False
    
    # 检查左上角和右上角区域（常见的调试信息显示位置）
    h, w = img_array.shape[:2]
    
    # 左上角 200x50 区域
    top_left = img_array[10:60, 10:210, :3]
    # 右上角 200x50 区域
    top_right = img_array[10:60, w-210:w-10, :3]
    
    # 计算区域的对比度
    contrast_left = np.std(top_left)
    contrast_right = np.std(top_right)
    
    # 如果对比度较高，可能有文字
    has_text = contrast_left > 30 or contrast_right > 30
    
    return has_text


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("用法: python3 analyze_image.py <image_path> [--detect-face]")
        sys.exit(1)
    
    image_path = sys.argv[1]
    detect_face = "--detect-face" in sys.argv
    
    success = analyze_image(image_path, detect_face)
    sys.exit(0 if success else 1)
