# S5 双端一致性自查报告：iOS 相机段 vs Android 美颜参数

> **日期**：2026-08-08
> **执行者**：GLM 相机段实例
> **范围**：BeautySettings 参数（滑杆范围/默认值/名称排序）+ FilterType 九款 ColorMatrix
> **红线**：[S5] 双端体验一致——美颜参数默认值/滑杆范围/滤镜名与排序 = Android `BeautySettings`

## 对照源

| 维度 | Android 源 | iOS 源 |
|------|-----------|--------|
| 参数定义 | `shared/.../beauty/api/BeautySettings.kt` | `BeautyRenderer.swift` Params struct |
| 滑杆范围 | `androidApp/.../camera/components/BeautyPanel.kt` | `BeautyPanelView.swift` |
| 滤镜矩阵 | `engines/beauty-engine/.../api/FilterTypeExt.kt` | `FilterColorMatrix.swift` |
| 滤镜枚举 | `shared/.../beauty/api/FilterType.kt` | `FilterColorMatrix.swift` |
| 滤镜 UI 排序 | `androidApp/.../camera/components/FilterSelector.kt` | `FilterSelectorView.swift` |

## 对照表

### 美颜参数（滑杆范围 + 默认值）

| 参数 | Android 范围 | Android 默认 | iOS 范围（修复前） | iOS 范围（修复后） | iOS 默认 | 判定 |
|------|-------------|-------------|-------------------|-------------------|---------|------|
| smoothing | 0..100 | 0 | 0..1 ❌ | **0..100** ✅ | 0 ✅ | ✅ 已修复 |
| whitening | 0..100 | 0 | 0..1 ❌ | **0..100** ✅ | 0 ✅ | ✅ 已修复 |
| slimFace | **-50..50** | 0 | 0..1 ❌ | **-50..50** ✅ | 0 ✅ | ✅ 已修复 |
| bigEyes | 0..100 | 0 | 0..1 ❌ | **0..100** ✅ | 0 ✅ | ✅ 已修复 |

**修复内容**：
- `BeautyPanelView.swift`：4 条 Slider 范围从 0..1 改为与 Android 一致（0..100 / -50..50）
- `BeautyRenderer.Params`：新增归一化计算属性（`normalizedWhitening`/`normalizedSmoothing`/`normalizedSlimFace`/`normalizedBigEyes`），在传入 shader 前转换（/100 或 /50）
- `BeautyRenderer.draw`/`renderToImage`：uniform 赋值改用归一化值

### 滤镜枚举（FilterType 9 款）

| Ordinal | Android (FilterType.kt) | iOS (FilterColorMatrix.swift) | 判定 |
|---------|------------------------|-------------------------------|------|
| 0 | NONE | .none | ✅ |
| 1 | LEICA_CLASSIC | .leicaClassic | ✅ |
| 2 | LEICA_VIBRANT | .leicaVibrant | ✅ |
| 3 | LEICA_BW | .leicaBW | ✅ |
| 4 | FILM_GOLD | .filmGold | ✅ |
| 5 | FILM_FUJI | .filmFuji | ✅ |
| 6 | VINTAGE | .vintage | ✅ |
| 7 | COOL | .cool | ✅ |
| 8 | WARM | .warm | ✅ |

排序一致（`FilterType.allCases` 按 ordinal 顺序，与 Android `FilterSelector.kt` `allFilters` 列表一致）。✅

### ColorMatrix 逐值对照（9 款）

| 滤镜 | Android (FilterTypeExt.kt) | iOS (FilterColorMatrix.swift) | 判定 |
|------|---------------------------|-------------------------------|------|
| NONE | 无矩阵（ColorMatrix()） | 返回 nil | ✅ |
| LEICA_CLASSIC | [0.95,0,0,0/ 0,0.9,0,0/ 0,0,0.85,0/ 0,0,0,1] | 同 | ✅ |
| LEICA_VIBRANT | setSaturation(1.3f) → 精确公式展开 | 同（setSaturation 公式逐项展开） | ✅ |
| LEICA_BW | setSaturation(0f) → 全灰矩阵 | 同 | ✅ |
| FILM_GOLD | [1.1,0.1,0/ 0.1,1.0,0/ 0,0,0.8] | 同 | ✅ |
| FILM_FUJI | [0.9,0,0.1/ 0,1.1,0/ 0.1,0,1.0] | 同 | ✅ |
| VINTAGE | [0.9,0,0/ 0,0.8,0/ 0,0.5,0] | 同 | ✅ |
| COOL | [0.8,0,0/ 0,0.9,0/ 0,0,1.2] | 同 | ✅ |
| WARM | [1.15,0.05,0,off:0.03/ 0.02,1.05,0/ 0,0,0.85,off:-0.03] | 同 | ✅ |

### StyleFilter（5 款 — Phase 6 范围，本 Phase 不实现）

| Android | iOS | 判定 |
|---------|-----|------|
| NONE/TOON/SKETCH/POSTERIZE/EMBOSS/CROSSHATCH | 不实现（spec S3 移 Phase 6） | ✅ 一致（双方约定 Phase 6） |

## 滤镜缩略图资源名对照

| FilterType | Android (filterAssetPath) | iOS (thumbnailName) | 判定 |
|------------|--------------------------|---------------------|------|
| NONE | filter_none.jpg | filter_none | ✅ |
| LEICA_CLASSIC | filter_leica_classic.jpg | filter_leica_classic | ✅ |
| LEICA_VIBRANT | filter_leica_vibrant.jpg | filter_leica_vibrant | ✅ |
| LEICA_BW | filter_leica_bw.jpg | filter_leica_bw | ✅ |
| FILM_GOLD | filter_film_gold.jpg | filter_film_gold | ✅ |
| FILM_FUJI | filter_film_fuji.jpg | filter_film_fuji | ✅ |
| VINTAGE | filter_vintage.jpg | filter_vintage | ✅ |
| COOL | filter_cool.jpg | filter_cool | ✅ |
| WARM | filter_warm.jpg | filter_warm | ✅ |

## 结论

**S5 红线达标**。发现并修复 4 项滑杆范围不一致（iOS 全部误用 0..1，已改为 Android 的 0..100 / -50..50），通过归一化属性保持 shader 侧 0..1 语义不变。ColorMatrix 9 款逐值一致。FilterType 排序一致。xcodebuild build + 7 XCTest 回归绿。
