#!/usr/bin/env python3
"""基于用户提供的原型图生成滤镜预览图"""
from PIL import Image, ImageDraw, ImageFont, ImageFilter
import os
import sys

# 用户需要将新图片保存到 input_images/new_portrait.png
base_image_path = 'input_images/new_portrait.png'
output_dir = 'androidApp/src/main/assets/filters'

if not os.path.exists(base_image_path):
    print(f"错误：未找到原型图 {base_image_path}")
    print("请将新图片保存到 input_images/new_portrait.png")
    print("或使用以下命令复制：")
    print("  cp <your_image_path> input_images/new_portrait.png")
    sys.exit(1)

os.makedirs(output_dir, exist_ok=True)

# 打开并预处理原型图
try:
    base_img = Image.open(base_image_path).convert('RGB')
    # 调整大小到 512x512，保持居中裁剪
    width, height = base_img.size
    min_dim = min(width, height)
    left = (width - min_dim) // 2
    top = (height - min_dim) // 2
    right = left + min_dim
    bottom = top + min_dim
    base_img = base_img.crop((left, top, right, bottom)).resize((512, 512), Image.LANCZOS)
    print(f"原型图加载成功：{width}x{height} -> 512x512")
except Exception as e:
    print(f"加载图片失败：{e}")
    sys.exit(1)

# 滤镜效果函数
def apply_leica_classic(img):
    """徕卡经典：暖色调，增强对比度"""
    pixels = img.load()
    for i in range(img.width):
        for j in range(img.height):
            r, g, b = pixels[i, j]
            r = min(255, max(0, int((r - 128) * 1.15 + 128) + 10))
            g = min(255, max(0, int((g - 128) * 1.05 + 128) + 5))
            b = min(255, max(0, int((b - 128) * 0.95 + 128)))
            pixels[i, j] = (r, g, b)
    return img

def apply_leica_vibrant(img):
    """徕卡生动：高饱和度"""
    pixels = img.load()
    for i in range(img.width):
        for j in range(img.height):
            r, g, b = pixels[i, j]
            r = min(255, int(r * 1.2))
            g = min(255, int(g * 1.15))
            b = min(255, int(b * 1.1))
            pixels[i, j] = (r, g, b)
    return img

def apply_leica_bw(img):
    """徕卡黑白：高对比度黑白"""
    img_gray = img.convert('L')
    pixels = img_gray.load()
    for i in range(img_gray.width):
        for j in range(img_gray.height):
            gray = pixels[i, j]
            gray = min(255, max(0, int((gray - 128) * 1.3 + 128)))
            pixels[i, j] = gray
    return img_gray.convert('RGB')

def apply_film_gold(img):
    """胶片金：金色暖调"""
    pixels = img.load()
    for i in range(img.width):
        for j in range(img.height):
            r, g, b = pixels[i, j]
            r = min(255, r + 30)
            g = min(255, g + 15)
            b = max(0, b - 20)
            pixels[i, j] = (r, g, b)
    return img

def apply_film_fuji(img):
    """富士胶片：青绿色调"""
    pixels = img.load()
    for i in range(img.width):
        for j in range(img.height):
            r, g, b = pixels[i, j]
            r = max(0, r - 15)
            g = min(255, g + 10)
            b = min(255, b + 25)
            pixels[i, j] = (r, g, b)
    return img

def apply_vintage(img):
    """复古：Sepia 褐色调"""
    pixels = img.load()
    for i in range(img.width):
        for j in range(img.height):
            r, g, b = pixels[i, j]
            tr = int(0.393 * r + 0.769 * g + 0.189 * b)
            tg = int(0.349 * r + 0.686 * g + 0.168 * b)
            tb = int(0.272 * r + 0.534 * g + 0.131 * b)
            pixels[i, j] = (min(255, tr), min(255, tg), min(255, tb))
    return img

def apply_cool(img):
    """冷色：蓝青色调"""
    pixels = img.load()
    for i in range(img.width):
        for j in range(img.height):
            r, g, b = pixels[i, j]
            r = max(0, r - 25)
            b = min(255, b + 30)
            pixels[i, j] = (r, g, b)
    return img

def apply_warm(img):
    """暖色：橙黄色调"""
    pixels = img.load()
    for i in range(img.width):
        for j in range(img.height):
            r, g, b = pixels[i, j]
            r = min(255, r + 35)
            g = min(255, g + 20)
            b = max(0, b - 25)
            pixels[i, j] = (r, g, b)
    return img

def apply_toon(img):
    """卡通：色彩量化"""
    img = img.filter(ImageFilter.SMOOTH_MORE)
    pixels = img.load()
    for i in range(img.width):
        for j in range(img.height):
            r, g, b = pixels[i, j]
            pixels[i, j] = (
                (r // 32) * 32 + 16,
                (g // 32) * 32 + 16,
                (b // 32) * 32 + 16
            )
    return img

def apply_sketch(img):
    """素描：灰度高对比"""
    img_gray = img.convert('L')
    pixels = img_gray.load()
    for i in range(img_gray.width):
        for j in range(img_gray.height):
            gray = pixels[i, j]
            gray = min(255, max(0, int((gray - 128) * 1.6 + 128)))
            pixels[i, j] = gray
    return img_gray.convert('RGB')

def apply_posterize(img):
    """色块：减少色阶"""
    pixels = img.load()
    for i in range(img.width):
        for j in range(img.height):
            r, g, b = pixels[i, j]
            pixels[i, j] = (
                (r // 64) * 64 + 32,
                (g // 64) * 64 + 32,
                (b // 64) * 64 + 32
            )
    return img

def apply_emboss(img):
    """浮雕：浮雕滤镜"""
    return img.filter(ImageFilter.EMBOSS)

def apply_crosshatch(img):
    """交叉线：添加线条纹理"""
    draw = ImageDraw.Draw(img)
    for i in range(0, 512, 8):
        draw.line([(i, 0), (i, 512)], fill=(50, 50, 50), width=1)
        draw.line([(0, i), (512, i)], fill=(50, 50, 50), width=1)
    return img

def add_label(img, label):
    """添加文字标签"""
    draw = ImageDraw.Draw(img)
    size = img.size[0]
    
    font_paths = [
        '/System/Library/Fonts/PingFang.ttc',
        '/System/Library/Fonts/STHeiti Light.ttc',
        '/System/Library/Fonts/Hiragino Sans GB.ttc',
    ]
    
    font = None
    for fp in font_paths:
        if os.path.exists(fp):
            try:
                font = ImageFont.truetype(fp, 32)
                break
            except:
                pass
    
    if font is None:
        font = ImageFont.load_default()
    
    bbox = draw.textbbox((0, 0), label, font=font)
    text_w = bbox[2] - bbox[0]
    text_h = bbox[3] - bbox[1]
    text_x = (size - text_w) // 2
    text_y = size - 60
    
    draw.rounded_rectangle(
        [text_x - 10, text_y - 5, text_x + text_w + 10, text_y + text_h + 5],
        radius=6,
        fill='#000000',
        outline='#FFFFFF',
        width=2
    )
    
    draw.text((text_x, text_y), label, fill='#FFFFFF', font=font)
    return img

# 滤镜配置
filters = [
    ('filter_none.jpg', '原图', lambda img: img.copy()),
    ('filter_leica_classic.jpg', '徕卡经典', apply_leica_classic),
    ('filter_leica_vibrant.jpg', '徕卡生动', apply_leica_vibrant),
    ('filter_leica_bw.jpg', '徕卡黑白', apply_leica_bw),
    ('filter_film_gold.jpg', '胶片金', apply_film_gold),
    ('filter_film_fuji.jpg', '富士胶片', apply_film_fuji),
    ('filter_vintage.jpg', '复古', apply_vintage),
    ('filter_cool.jpg', '冷色', apply_cool),
    ('filter_warm.jpg', '暖色', apply_warm),
    ('style_toon.jpg', '卡通', apply_toon),
    ('style_sketch.jpg', '素描', apply_sketch),
    ('style_posterize.jpg', '色块', apply_posterize),
    ('style_emboss.jpg', '浮雕', apply_emboss),
    ('style_crosshatch.jpg', '交叉线', apply_crosshatch),
]

# 生成所有滤镜图
for filename, label, effect_func in filters:
    try:
        filtered_img = effect_func(base_img.copy())
        labeled_img = add_label(filtered_img, label)
        filepath = os.path.join(output_dir, filename)
        labeled_img.save(filepath, 'JPEG', quality=90)
        print(f'生成：{filename} ({os.path.getsize(filepath)/1024:.1f}KB)')
    except Exception as e:
        print(f'生成 {filename} 失败：{e}')

print('\n全部 14 张滤镜图标生成完成！')
