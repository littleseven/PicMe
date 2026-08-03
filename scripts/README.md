# Scripts 目录

本目录包含 PoLang 项目的各种工具脚本。

---

## 📋 脚本分类

### 1. 应用数据备份脚本

用于在 release/debug 包切换或卸载重装时，快速备份/恢复 PoLang TAG 数据，避免重新执行耗时的 TAG 扫描。

| 脚本 | 说明 | 用法 |
|------|------|------|
| `app-data-backup.sh` | 备份/恢复 TAG 数据快照 | `./app-data-backup.sh backup before_release` |

**示例**：
```bash
# 备份当前 TAG 数据
./scripts/app-data-backup.sh backup before_release

# 切换包并重装后，恢复 TAG 数据
./scripts/app-data-backup.sh restore before_release

# 先 dry-run，查看媒体 URI 匹配情况
./scripts/app-data-backup.sh dry-run before_release

# 列出所有快照
./scripts/app-data-backup.sh list
```

**说明**：
- 备份内容为 Room DB 中的 TAG 相关数据（标签、媒体-标签关联、扫描任务、TAG 元数据），以媒体 URI 为跨安装匹配键。
- 该脚本通过 adb 调用应用内测试广播接收器，无需 root。
- 恢复前请确保已安装目标 APK 并授予媒体读取权限，否则 URI 无法匹配。

---

### 2. 坐标系规范检测脚本

用于检查代码和文档中的坐标系标注是否符合规范。

| 脚本 | 说明 | 用法 |
|------|------|------|
| `check-coordinate-annotation.sh` | 检测代码中未标注坐标系的注释 | `./check-coordinate-annotation.sh` |
| `check-doc-coordinate-annotation.sh` | 检测文档中未标注坐标系的描述 | `./check-doc-coordinate-annotation.sh` |

**相关文档**：
- [COORDINATE_SYSTEM_STANDARD.md](../docs/07-STANDARDS/COORDINATE_SYSTEM.md)
- [ADR-003-coordinate-system-management.md](../docs/02-ARCHITECTURE/ADR/ADR-003-coordinate-system-management.md)

---

### 3. 人脸关键点可视化脚本

用于可视化和分析人脸关键点。

| 脚本 | 说明 | 输入 | 输出 |
|------|------|------|------|
| `visualize_eyes_landmarks.py` | 可视化眼睛关键点 | `input_images/` | `output_images/` |
| `visualize_nose_landmarks.py` | 可视化鼻子关键点 | `input_images/` | `output_images/` |
| `visualize_mediapipe_468.py` | 可视化 MediaPipe 468 点 | `input_images/` | `output_images/` |

**依赖**：
```bash
pip install opencv-python numpy matplotlib
```

**用法**：
```bash
python scripts/visualize_eyes_landmarks.py
```

---

### 4. 轮廓映射脚本

用于生成和分析人脸轮廓映射关系。

| 脚本 | 说明 | 用途 |
|------|------|------|
| `analyze_contour.py` | 分析轮廓关键点 | 轮廓质量检测 |
| `generate_contour_mapping.py` | 生成轮廓映射表 | 映射关系生成 |
| `remap_contour.py` | 重新映射轮廓点 | 映射调整 |
| `crop_face_4_3.py` | 裁剪 4:3 人脸区域 | 图像预处理 |

**用法**：
```bash
python scripts/generate_contour_mapping.py --input input_images/face.jpg
```

---

### 5. 坐标提取脚本

用于从现有数据中提取坐标信息。

| 脚本 | 说明 | 用途 |
|------|------|------|
| `extract_gpupixel_texcoords.py` | 提取参考纹理坐标（历史脚本，GPUPixel 已移除） | Shader 开发 |
| `check_alignment.py` | 检查关键点对齐 | 质量验证 |

---

### 6. 相机调试脚本

用于调试相机相关问题。

| 脚本 | 说明 | 用途 |
|------|------|------|
| `fix_camera_screen.py` | 修复相机屏幕显示问题 | 相机调试 |

---

### 7. Kimi CLI 工具

Kimi AI 助手的命令行工具。AI 工具配置详见 [`AI_TOOLS.md`](../AI_TOOLS.md)。

| 脚本 | 说明 | 用法 |
|------|------|------|
| `kimi-cli.sh` | Kimi CLI 快捷命令脚本（含项目引导） | `source scripts/kimi-cli.sh` |

---

### 8. 测试脚本

| 脚本 | 说明 | 用途 |
|------|------|------|
| `insert_test.py` | 插入测试数据 | 单元测试 |
| `generate_from_user_image.py` | 从用户图像生成测试数据 | 测试数据生成 |

---

## 🔧 通用依赖安装

### Python 依赖

```bash
# 基础依赖
pip install opencv-python numpy matplotlib Pillow

# 可选依赖（根据脚本需求）
pip install mediapipe tensorflow torch
```

### Shell 依赖

大部分 Shell 脚本只需要标准的 Unix 工具：
- bash
- grep
- awk
- sed

---

## 📝 使用示例

### 示例 1: 运行坐标系检测

```bash
cd /Users/guoshuai/AndroidStudioProjects/PoLang

# 检测代码
./scripts/check-coordinate-annotation.sh

# 检测文档
./scripts/check-doc-coordinate-annotation.sh
```

### 示例 2: 可视化人脸关键点

```bash
# 准备输入图像
cp your_image.jpg input_images/face.jpg

# 运行可视化脚本
python scripts/visualize_eyes_landmarks.py

# 查看输出
open output_images/face001_left_eye.png
```

### 示例 3: 生成轮廓映射

```bash
python scripts/generate_contour_mapping.py \
    --input input_images/face.jpg \
    --output docs/03-TECHNICAL-SPECS/contour_mapping.json
```

---

## 🎯 最佳实践

### 1. 脚本命名规范

- 使用小写字母和下划线
- 名称应清晰表达功能
- Python 脚本以 `.py` 结尾
- Shell 脚本以 `.sh` 结尾

### 2. 脚本组织

- 按功能分类存放
- 每个脚本应有明确的用途
- 复杂脚本应提供 `--help` 选项

### 3. 依赖管理

- 在脚本开头声明依赖
- 提供依赖安装说明
- 避免硬编码路径

### 4. 错误处理

- 检查输入文件是否存在
- 提供清晰的错误消息
- 返回适当的退出码

---

## 🔄 添加新脚本

### 步骤

1. **创建脚本文件**
   ```bash
   touch scripts/my_new_script.py
   chmod +x scripts/my_new_script.py
   ```

2. **添加 shebang**
   ```python
   #!/usr/bin/env python3
   # -*- coding: utf-8 -*-
   """
   脚本说明
   """
   ```

3. **更新本文档**
   - 在相应分类中添加脚本信息
   - 提供使用说明和示例

4. **提交代码**
   ```bash
   git add scripts/my_new_script.py
   git commit -m "feat: 添加新脚本 my_new_script"
   ```

---

## 📖 相关文档

- [COORDINATE_SYSTEM_STANDARD.md](../docs/07-STANDARDS/COORDINATE_SYSTEM.md) - 坐标系规范
- [BEAUTY_ENGINE_TECH_SPEC.md](../docs/03-TECHNICAL-SPECS/BEAUTY_ENGINE_TECH_SPEC.md) - 相机技术规范（原 CAMERA_PREVIEW_TECH_SPEC.md 已并入）
- [BEAUTY_ENGINE_TECH_SPEC.md](../docs/03-TECHNICAL-SPECS/BEAUTY_ENGINE_TECH_SPEC.md) - 美颜引擎规范

---

## ⚠️ 注意事项

1. **不要删除现有脚本**：除非确认不再使用
2. **保持向后兼容**：修改脚本时注意兼容性
3. **测试后再提交**：确保脚本在本地运行正常
4. **更新文档**：添加或修改脚本时同步更新本文档

---

**最后更新**: 2026-05-03  
**维护者**: PoLang AI Team
