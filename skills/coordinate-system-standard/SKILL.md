---
name: coordinate-system-standard
description: |
  人脸关键点坐标、渲染管线、UI 标注的坐标系规范化标准。
version: 1.1.0
created: 2026-05-03
updated: 2026-05-25
maintainer: [RD] 全栈工程师 + [CR] 规范守护者
tags:
  - coordinate-system
  - landmark
  - opengl
  - rendering
  - standard
---


# Coordinate System Standard Skill

> **定位**：人脸关键点坐标、渲染管线、UI 标注的坐标系规范化标准。
> **触发时机**：用户涉及坐标映射、关键点对齐、渲染坐标转换或 UI 标注规范时自动启用。


**技能名称**: coordinate-system-standard
**版本**: 1.0.0
**适用场景**: 人脸关键点坐标、渲染管线、UI 标注的坐标系规范化

## 📋 技能概述

本 Skill 用于确保 PoLang 项目中所有涉及坐标系的代码和文档都遵循统一规范：**允许两种坐标系并存，但严禁混用**。

### 核心原则

1. **明确标注**：所有坐标相关描述必须标注 `[图像坐标系]` 或 `[人脸坐标系]`
2. **禁止混用**：同一作用域内只能使用一种坐标系
3. **明确转换**：跨坐标系转换必须有明确的转换函数

---

## 🎯 使用场景

### 场景 1: 代码审查时检查坐标系规范

当审查包含人脸关键点、坐标转换、渲染逻辑的代码时：

```bash
# 运行自动化检测
scripts/check-coordinate-annotation.sh
```

**检查要点**：
- [ ] 变量名是否包含坐标系前缀（`imageLeft` / `userLeft`）
- [ ] 注释是否标注坐标系类型
- [ ] 同一函数内是否混用两种坐标系
- [ ] 跨坐标系转换是否有明确函数

### 场景 2: 编写技术文档时规范描述

当编写涉及人脸关键点、坐标映射的文档时：

```bash
# 运行文档检测
scripts/check-doc-coordinate-annotation.sh
```

**规范要求**：
- 章节开头必须添加坐标系声明
- 所有"左/右"描述必须带坐标系标注
- 提供前后置摄像头对照表

### 场景 3: 新增坐标转换功能

当需要添加新的坐标转换逻辑时：

**步骤**：
1. 确定源坐标系和目标坐标系
2. 创建明确的转换函数
3. 在函数 KDoc 中标注坐标系
4. 添加单元测试验证前后置摄像头

### 场景 4: 修复坐标系相关 Bug

当遇到瘦脸偏移、妆容贴附不准等问题时：

**诊断流程**：
1. 检查是否混用了两种坐标系
2. 确认前置摄像头的镜像处理是否正确
3. 验证坐标转换函数的逻辑
4. 查看日志中的 Step1~Step4 坐标转换过程

---

## 📚 坐标系定义

### 图像坐标系（Image Space）

**定义**：
- 基于图像边界
- 原点：左上角 (0, 0)
- x 轴：向右增加
- y 轴：向下增加
- 归一化范围：[0.0, 1.0]

**适用场景**：
- GPU Shader 渲染
- OpenGL 纹理映射
- 图像处理算法
- 与显示系统对接

**命名示例**：
```kotlin
val imageLeftEye = landmarks[IMAGE_LEFT_EYE_INDEX]
val imageRightEyebrow = landmarks[IMAGE_RIGHT_EYEBROW_INDEX]
```

### 人脸坐标系（Face Space / User Space）

**定义**：
- 基于被拍摄者身体
- 左/右：以被拍摄者为参照
- 需要区分前后置摄像头

**适用场景**：
- UI 文案显示
- 用户交互提示
- 业务逻辑层（可选）
- 与外部 SDK 对接

**命名示例**：
```kotlin
val userLeftEye = getUserLeftEye(isFrontCamera)
val faceRightCheek = getFaceRightCheek(isFrontCamera)
```

---

## 🔧 分层架构

```
┌─────────────────────────────────────────┐
│         坐标系分层架构                    │
├─────────────────────────────────────────┤
│                                         │
│  UI / 业务层                             │
│  ├── 可使用 [人脸坐标系]                 │
│  ├── 便于用户理解                        │
│  └── 需处理前后置差异                    │
│                                         │
│  ↓ 转换边界（明确函数）                  │
│  convertUserToImageCoordinates()        │
│  convertImageToUserCoordinates()        │
│                                         │
│  渲染 / 算法层                           │
│  ├── 必须使用 [图像坐标系]               │
│  ├── GPU Shader 处理                    │
│  ├── OpenGL 纹理映射                    │
│  └── 图像处理算法                        │
│                                         │
└─────────────────────────────────────────┘
```

### 坐标系选择策略

| 层级 | 推荐坐标系 | 原因 |
|------|-----------|------|
| **UI 展示层** | 人脸坐标系 | 用户更容易理解"左眼"、"右眼" |
| **业务逻辑层** | 图像坐标系（推荐）或人脸坐标系 | 根据团队习惯统一选择 |
| **渲染引擎层** | **必须**使用图像坐标系 | GPU Shader、OpenGL 原生支持 |
| **算法处理层** | **必须**使用图像坐标系 | OpenCV、MediaPipe 等库的标准 |

---

## ✅ 规范检查清单

### 代码审查

#### 命名规范
- [ ] 变量名包含坐标系前缀（`imageLeft` / `imageRight` / `userLeft` / `userRight`）
- [ ] 函数名体现坐标系（`getImageLeftEye()` / `getUserLeftEye()`）
- [ ] 枚举值标注坐标系（`IMAGE_LEFT` / `USER_LEFT`）

#### 注释规范
- [ ] 所有坐标相关注释标注坐标系类型
- [ ] 函数 KDoc 第一行标注坐标系
- [ ] 复杂转换逻辑有详细说明

#### 逻辑规范
- [ ] **同一作用域内未混用两种坐标系**（⚠️ 关键检查点）
- [ ] 跨坐标系转换调用明确的转换函数
- [ ] 前置摄像头的镜像处理正确（`x = 1 - x`）

### 文档审查

#### 描述规范
- [ ] 所有"左/右"描述标注坐标系（`[图像坐标系]` / `[人脸坐标系]`）
- [ ] 章节开头添加坐标系声明（使用引用块 `>`）
- [ ] 提供前后置摄像头对照表

#### 图示规范
- [ ] 包含坐标系图示
- [ ] 标注原点和轴向
- [ ] 说明归一化范围

---

## 🛠️ 常用工具

### 1. 代码检测脚本

```bash
# 检测代码中未标注坐标系的注释
scripts/check-coordinate-annotation.sh
```

**输出示例**：
```
🔍 检查未标注坐标系的注释...

搜索: 左眼
搜索: 右眼
搜索: 左眉
搜索: 右眉

✅ 坐标系标注检查通过
```

### 2. 文档检测脚本

```bash
# 检测文档中未标注坐标系的描述
scripts/check-doc-coordinate-annotation.sh
```

### 3. Git Pre-commit Hook

详见 [reference.md](reference.md) §Pre-commit Hook：坐标系标注自动检查脚本。

---

## 📝 代码示例

### 示例 1: 正确的坐标转换

```kotlin
/**
 * [坐标转换] 将人脸坐标系转换为图像坐标系
 * 
 * @param userLandmarks [人脸坐标系] 人脸关键点列表
 * @param isFrontCamera 是否为前置摄像头
 * @return [图像坐标系] 转换后的关键点列表
 */
fun convertUserToImageCoordinates(
    userLandmarks: List<Point>,
    isFrontCamera: Boolean
): List<Point> {
    return userLandmarks.map { point ->
        if (isFrontCamera) {
            // 前置摄像头：镜像翻转
            Point(1.0f - point.x, point.y)
        } else {
            // 后置摄像头：保持不变
            point
        }
    }
}
```

### 示例 2: 渲染层使用图像坐标系

```kotlin
/**
 * [图像坐标系] 应用瘦脸效果到 GPU 纹理
 * 
 * @param textureId OpenGL 纹理 ID
 * @param imageLandmarks [图像坐标系] 人脸关键点
 * @param strength 瘦脸强度 (0.0 - 1.0)
 */
fun applySlimFaceOnGPU(
    textureId: Int,
    imageLandmarks: List<Point>,
    strength: Float
) {
    // 渲染层必须使用图像坐标系
    val imageLeftCheek = imageLandmarks[IMAGE_LEFT_CHEEK_INDEX]
    val imageRightCheek = imageLandmarks[IMAGE_RIGHT_CHEEK_INDEX]
    
    // 传递给 Shader（OpenGL 使用图像坐标系）
    shader.setUniform("u_leftCheek", imageLeftCheek.toNDC())
    shader.setUniform("u_rightCheek", imageRightCheek.toNDC())
    shader.setUniform("u_strength", strength)
    
    // 执行渲染
    shader.render(textureId)
}
```

### 示例 3: UI 层使用人脸坐标系

```kotlin
/**
 * [人脸坐标系] 绘制人脸关键点调试浮层
 * 
 * @param canvas 绘制画布
 * @param userLandmarks [人脸坐标系] 人脸关键点
 * @param isFrontCamera 是否为前置摄像头
 */
fun drawDebugOverlay(
    canvas: Canvas,
    userLandmarks: List<Point>,
    isFrontCamera: Boolean
) {
    // UI 层使用人脸坐标系，便于用户理解
    val userLeftEye = userLandmarks[USER_LEFT_EYE_INDEX]
    val userRightEye = userLandmarks[USER_RIGHT_EYE_INDEX]
    
    // 绘制标签（显示给用户看）
    canvas.drawText("左眼", userLeftEye.x, userLeftEye.y, paint)
    canvas.drawText("右眼", userRightEye.x, userRightEye.y, paint)
}
```

### 示例 4: ❌ 错误的混用示例

```kotlin
// ❌ 错误：混用两种坐标系
class FaceProcessor {
    fun process() {
        val imageLeftEye = landmarks[IMAGE_LEFT_EYE]     // [图像坐标系]
        val userRightEye = getUserRightEye()              // [人脸坐标系]
        
        // ❌ 错误：两种坐标系混合计算
        val center = (imageLeftEye + userRightEye) / 2
    }
}

// ✅ 正确：统一使用图像坐标系
class FaceProcessor {
    fun process() {
        val imageLeftEye = landmarks[IMAGE_LEFT_EYE]
        val imageRightEye = landmarks[IMAGE_RIGHT_EYE]
        
        // ✅ 正确：同一种坐标系计算
        val center = (imageLeftEye + imageRightEye) / 2
    }
}
```

---

## 🔍 常见问题排查

### 问题 1: 前置摄像头瘦脸效果位置错误

**症状**：
- 后置摄像头正常
- 前置摄像头瘦脸位置偏移或方向相反

**原因**：
- 未正确处理前置摄像头的镜像翻转
- 混用了图像坐标系和人脸坐标系

**解决**：
1. 检查坐标转换函数是否包含镜像逻辑
2. 确认所有关键点都经过 `convertUserToImageCoordinates()` 转换
3. 查看日志中的 Step2 [镜像] 输出

**调试日志**：
```kotlin
Log.d(TAG, "Step1 [归一化]: norm=($normX,$normY)")
Log.d(TAG, "Step2 [镜像]: lens=${if (isFront) "前" else "后"}, mirrored=($mirroredX,$normY)")
Log.d(TAG, "Step3 [旋转补偿]: rot=$rotation, adjusted=($adjustedX,$adjustedY)")
Log.d(TAG, "Step4 [像素映射]: screen=($screenX,$screenY)")
```

### 问题 2: 妆容贴附不准确

**症状**：
- 眼影、腮红等妆容位置偏移
- 前后置摄像头效果不一致

**原因**：
- 关键点索引使用了错误的坐标系
- Shader 中 UV 坐标计算错误

**解决**：
1. 确认关键点索引基于图像坐标系
2. 检查 Shader 中的纹理坐标映射
3. 验证 FBO 采样时是否重复应用了 Y 轴翻转

### 问题 3: 文档描述歧义

**症状**：
- 新成员不理解"左眼"指的是哪一侧
- Code Review 时对左右方向有争议

**解决**：
1. 所有文档添加坐标系声明
2. 使用 `[图像坐标系] 图像左侧眼睛` 格式
3. 提供前后置对照表

---

## 📖 相关文档

- [COORDINATE_SYSTEM_STANDARD.md](docs/07-STANDARDS/COORDINATE_SYSTEM.md) - 坐标系规范详细说明
- [ADR-003-coordinate-system-management.md](docs/02-ARCHITECTURE/ADR/ADR-003-coordinate-system-management.md) - 技术决策文档
- [FACE_LANDMARKS.md](docs/03-TECHNICAL-SPECS/FACE_LANDMARKS.md) - 关键点映射文档

---

## 🎓 学习路径

### 初学者

1. 阅读 COORDINATE_SYSTEM_STANDARD.md 了解基本概念
2. 运行检测脚本熟悉常见问题
3. 查看代码示例理解正确用法

### 进阶开发者

1. 研究 ADR-003 理解决策背景
2. 分析坐标转换函数的实现细节
3. 参与 Code Review 实践规范检查

### 专家级

1. 优化坐标转换性能
2. 设计新的坐标系适配层
3. 制定更细粒度的规范

---

## 🔄 版本历史

| 版本 | 日期 | 变更内容 |
|------|------|---------|
| 1.0.0 | 2026-05-03 | 初始版本，确立"允许两种坐标系但严禁混用"原则 |

---

**Skill 维护者**: PoLang AI Team  
**最后更新**: 2026-05-03

## 相关文件

## 版本历史

| 版本 | 日期 | 变更 |
|------|------|------|
| 1.1.0 | 2026-05-03 | 初始版本 |
