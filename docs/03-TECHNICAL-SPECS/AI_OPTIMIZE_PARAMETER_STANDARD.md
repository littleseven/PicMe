# AI 一键优化：参数标准与预设规范

> **文档类型**：技术规范（Technical Specification）  
> **针对能力**：AI 一键优化（AI One-Click Image Optimization）  
> **最后更新**：2026-07-04  
> **维护者**：RD Agent（实现）+ CR Agent（合规审查）

---

## 1. 核心结论

### 1.1 是否存在行业统一标准？

**不存在强制统一的二进制标准**，但移动端修图领域有两套事实上的「用户心智」：

| 体系 | 代表产品 | 特点 | 适用参数 |
|------|----------|------|----------|
| **Apple Photos 体系** | iOS 相册、VSCO、Lightroom Mobile | 以 `-100 ~ +100` 或 `0 ~ 100` 为中心对称滑杆，0 为「无效果」 | 曝光、亮度、对比度、饱和度、色温、色调 |
| **国产美颜体系** | 小米相机、美图秀秀、轻颜相机 | 以 `0 ~ 100` 表示强度，50 或 0 为「自然/关闭」 | 磨皮、美白、瘦脸、大眼、唇色、腮红 |

### 1.2 PicMe 的选择

- **美颜参数**：对齐国产美颜体系，`0 ~ 100` 为强度，`0` 为关闭；瘦脸 `-50 ~ +50`，负值丰脸。
- **调色参数**：尽量对齐 Apple Photos 用户心智，但保留当前内部映射：
  - `brightness / exposure / tint`：`-100 ~ +100`，`0` 为原图。
  - `contrast / saturation`：`0 ~ 200`，`50/100` 为原图（与 Apple 的 `-100 ~ +100` 等价，只是零点偏移）。
  - `temperature`：`2000K ~ 8000K`，`5000K` 为原图（与 Apple Warmth 方向一致，但使用 Kelvin 标度）。

> 这样选择的原因是：底层大美丽 Shader  already 使用 `0~4` 对比度、`0~2` 饱和度映射，当前 `0~200` UI 范围可直接整除映射，改动最小；同时用户在滑杆上看到的「50 = 原图对比度、100 = 原图饱和度」与常见修图 App 的百分比概念接近。

---

## 2. 参数语义、范围与滑杆映射

### 2.1 美颜（Beauty）

| 参数 | UI 范围 | 默认值 | 语义 | 引擎归一化 | 参考来源 |
|------|---------|--------|------|------------|----------|
| `smoothing` 磨皮 | `0 ~ 100` | `0` | 0=关闭，100=最强磨皮 | `smoothing / 100` → `0.0 ~ 1.0` | 小米/美图 |
| `whitening` 美白 | `0 ~ 100` | `0` | 0=关闭，100=最强美白 | `whitening / 100` → `0.0 ~ 1.0` | 小米/美图 |
| `slimFace` 瘦脸 | `-50 ~ +50` | `0` | 负值=丰脸，正值=瘦脸 | `slimFace / 50 * 1.35` → `-1.0 ~ 1.0` | 小米/美图 |
| `bigEyes` 大眼 | `0 ~ 100` | `0` | 0=关闭，100=最大放大 | `bigEyes / 100` → `0.0 ~ 1.0` | 小米/美图 |
| `lipColor` 唇色 | `0 ~ 100` | `0` | 0=关闭，100=最强唇色 | `lipColor / 100` → `0.0 ~ 1.0` | 小米/美图 |
| `blush` 腮红 | `0 ~ 100` | `0` | 0=关闭，100=最强腮红 | `blush / 100` → `0.0 ~ 1.0` | 小米/美图 |
| `eyebrow` 眉毛 | `0 ~ 100` | `0` | 0=关闭，100=最深 | `eyebrow / 100` → `0.0 ~ 1.0` | 小米/美图 |
| `bodyEnhancement` 美体 | `-30 ~ +30` | `0` | 负值=压缩，正值=拉伸上半身 | `strength / 30 * 0.15` → 拉伸系数 | 国产美体 |
| `legExtension` 长腿 | `0 ~ 50` | `0` | 0=关闭，50=最大拉伸 | `strength / 50 * 0.15` → 拉伸系数 | 国产美体 |

### 2.2 调色（Adjustment）

| 参数 | UI 范围 | 默认值 | 语义 | 引擎归一化 | 参考来源 |
|------|---------|--------|------|------------|----------|
| `brightness` 亮度 | `-100 ~ +100` | `0` | 整体明暗偏移 | `/ 100` → `-1.0 ~ +1.0` | Apple Photos |
| `exposure` 曝光 | `-100 ~ +100` | `0` | 模拟曝光补偿 | `coerceIn(-10, +10)` → Shader 曝光 | Apple Photos |
| `contrast` 对比度 | `0 ~ 200` | `50` | 50=原图，0=最低，200=最高 | `/ 50` → `0.0 ~ 4.0` | 等价 Apple `-100 ~ +100` |
| `saturation` 饱和度 | `0 ~ 200` | `100` | 100=原图，0=灰度，200=最高 | `/ 100` → `0.0 ~ 2.0` | 等价 Apple `-100 ~ +100` |
| `temperature` 色温 | `2000K ~ 8000K` | `5000K` | 低=冷（蓝），高=暖（黄） | `(T-5000)/3000` → `-1.0 ~ +1.0` | Apple Warmth + Kelvin |
| `tint` 色调 | `-100 ~ +100` | `0` | 绿↔品红偏移 | `/ 100` → `-1.0 ~ +1.0` | Apple Photos |
| `vignette` 暗角 | `0 ~ 100` | `0` | 0=关闭，100=最强 | 待 Phase 2 实现 | Apple Photos |

### 2.3 滤镜（Filter）

| 滤镜 | 类型 | 推荐场景 | 说明 |
|------|------|----------|------|
| `NONE` | 无 | 通用 | 直通 |
| `LEICA_CLASSIC` | 色调矩阵 | 人像、街拍 | 降低蓝绿通道，模拟德味 |
| `LEICA_VIBRANT` | 饱和度 | 风景、美食 | 饱和度 +30% |
| `LEICA_BW` | 饱和度=0 | 人文、建筑 | 黑白 |
| `FILM_GOLD` | 色调矩阵 | 人像、日落 | 暖黄胶片感 |
| `FILM_FUJI` | 色调矩阵 | 风景、绿植 | 偏青绿胶片感 |
| `VINTAGE` | 色调矩阵 | 复古主题 | 褪色、暖调 |
| `COOL` | 色调矩阵 | 雪景、夜景 | 增强蓝色 |
| `WARM` | 色调矩阵 | 美食、日落 | 增强红黄 |

---

## 3. 推荐预设值（MVP）

> 所有预设以「自然、克制、可二次微调」为原则，避免一键后过曝、过磨、过饱和。

### 3.1 自拍（SELFIE）

```json
{
  "beauty": { "smoothing": 35, "whitening": 25, "slimFace": 10, "bigEyes": 15, "lipColor": 25, "blush": 10, "eyebrow": 10 },
  "filter": { "colorFilter": "NONE", "styleFilter": "NONE" },
  "adjustment": { "brightness": 5, "exposure": 0, "contrast": 52, "saturation": 102, "temperature": 5200, "tint": 2 }
}
```

### 3.2 人像（PORTRAIT）

```json
{
  "beauty": { "smoothing": 25, "whitening": 20, "slimFace": 5, "bigEyes": 10, "lipColor": 20, "blush": 8, "eyebrow": 8 },
  "filter": { "colorFilter": "FILM_GOLD", "styleFilter": "NONE" },
  "adjustment": { "brightness": 3, "exposure": 0, "contrast": 53, "saturation": 103, "temperature": 5300, "tint": 2 }
}
```

### 3.3 合影（GROUP）

```json
{
  "beauty": { "smoothing": 20, "whitening": 15, "slimFace": 0, "bigEyes": 0, "lipColor": 10, "blush": 5, "eyebrow": 5 },
  "filter": { "colorFilter": "NONE", "styleFilter": "NONE" },
  "adjustment": { "brightness": 3, "exposure": 0, "contrast": 52, "saturation": 102, "temperature": 5200, "tint": 1 }
}
```

### 3.4 美食（FOOD）

```json
{
  "beauty": { "enabled": false },
  "filter": { "colorFilter": "LEICA_VIBRANT", "styleFilter": "NONE" },
  "adjustment": { "brightness": 2, "exposure": 0, "contrast": 55, "saturation": 110, "temperature": 5400, "tint": 3 }
}
```

### 3.5 风景（LANDSCAPE）

```json
{
  "beauty": { "enabled": false },
  "filter": { "colorFilter": "LEICA_VIBRANT", "styleFilter": "NONE" },
  "adjustment": { "brightness": 0, "exposure": 0, "contrast": 58, "saturation": 110, "temperature": 5000, "tint": 0 }
}
```

### 3.6 夜景/暗光（LOW_LIGHT）

```json
{
  "beauty": { "smoothing": 15, "whitening": 10 },
  "filter": { "colorFilter": "WARM", "styleFilter": "NONE" },
  "adjustment": { "brightness": 12, "exposure": 5, "contrast": 55, "saturation": 100, "temperature": 5600, "tint": 3 }
}
```

### 3.7 文档（DOCUMENT）

```json
{
  "beauty": { "enabled": false },
  "filter": { "colorFilter": "NONE", "styleFilter": "NONE" },
  "adjustment": { "brightness": 8, "exposure": 0, "contrast": 60, "saturation": 95, "temperature": 5000, "tint": 0 }
}
```

### 3.8 通用（GENERAL）

```json
{
  "beauty": { "smoothing": 15, "whitening": 10 },
  "filter": { "colorFilter": "NONE", "styleFilter": "NONE" },
  "adjustment": { "brightness": 2, "exposure": 0, "contrast": 52, "saturation": 100, "temperature": 5000, "tint": 0 }
}
```

---

## 4. 代码侧改动清单

- [x] `PhotoProcessorImpl`：EGL 上下文失效后彻底释放 Shader/FBO/纹理，避免复用无效句柄导致黑屏。
- [x] `PhotoEditorViewModel`：为照片处理创建独立单线程调度器，避免 `Dispatchers.Default` 线程池切换导致 EGL 上下文丢失。
- [x] `RecipeApplier`：增加 GPU 输出全黑检测与 CPU 滤镜兜底，确保极端情况下不显示黑屏。
- [x] `AppContainer`：编辑器使用独立的 `PhotoProcessor` 实例，避免与相机拍照路径共享 EGL 上下文。
- [x] `optimize_presets.json`：按本节推荐值重新调整，降低美颜强度，使调色更自然。
- [x] 新增本文档作为参数标准与预设规范的唯一事实来源。

---

## 5. 验收标准

- AI 优化点击后 500ms 内完成预览，且不再出现全黑画面。
- 各场景预设应用后，美颜强度可被普通用户感知但不夸张。
- 对比度/饱和度/色温/亮度滑杆默认值与文档一致。
- 后续新增参数必须在本文档中补充语义、范围、参考来源。
