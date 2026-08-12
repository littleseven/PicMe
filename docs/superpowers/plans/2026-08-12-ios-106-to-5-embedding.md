# iOS embedding 106→5 对齐接通 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 iOS TAG 扫描 embedding 路径从 RetinaFace native 5pt 对齐升级为 2D106→统一106→ArcFace5 对齐(与 Android 对齐),提升 same-person embedding 相似度。

**Architecture:** 复用 4 个现成零件(`detectLandmarks106:`、`MnnLandmarkAdapter.adapt`、`FaceAlignment.convert106ToLandmarks5` 现为 dead code、`alignFace`),只改 `Pass1Pipeline` 的 per-ROI 循环接通它们,native 5pt 降级为 fallback。两步走:Step1 接通+诊断(阈值不动),Step2 基于实测 sim 分布独立标定阈值。

**Tech Stack:** Swift / XCTest / MNN(ObjC++ 桥 PLMnnFaceDetector/PLMnnFaceEmbedder)/ SQLite(TagDatabase)。

**关联 spec:** `docs/superpowers/specs/2026-08-12-ios-106-to-5-embedding-design.md`

---

## File Structure

- **Create:** `iosApp/PoLangTests/FaceAlignment106To5Tests.swift` — convert106ToLandmarks5 纯函数单测(双端一致性守卫)。
- **Modify:** `iosApp/PoLang/Platform/FaceAlignment.swift:31-35` — 修正 convert 输入点序的误注释(原生序 → 统一序)。
- **Modify:** `iosApp/PoLang/Platform/Pass1Pipeline.swift:1-2`(加 `import simd`)、`:177-196`(接通 106→5 循环 + fallback)、新增诊断 dump 开关。
- **Create:** `scripts/ios_face_sim_diag.py` — 扫描后按 mediaId 分组算 same/cross-person pair sim 分布。
- **Modify(Step2):** `iosApp/PoLang/Platform/Tag/FaceClusterer.swift:10`、`iosApp/PoLang/Platform/Tag/FaceClusterMaintenance.swift:16-20` — 阈值独立标定 + 注释修正。

---

## Task 1: convert106ToLandmarks5 单元测试(双端一致性守卫)

**Files:**
- Create: `iosApp/PoLangTests/FaceAlignment106To5Tests.swift`

守护 iOS `convert106ToLandmarks5` 与 Android `TagGenerationPipeline.convert106ToLandmarks5`(`TagGenerationPipeline.kt:642-687`)索引导出一致。该函数现为 dead code,此测试先锁死其正确性,再在 Task 3 接通。

- [ ] **Step 1: 写失败测试**

创建 `iosApp/PoLangTests/FaceAlignment106To5Tests.swift`:

```swift
import XCTest
@testable import PoLang

/// 守护 FaceAlignment.convert106ToLandmarks5 与 Android 双端一致。
/// 索引定义(统一 106 序):左眼=52-57+72-73, 右眼=58-63+75-76, 鼻尖=49, 左嘴角=84, 右嘴角=94。
final class FaceAlignment106To5Tests: XCTestCase {

    func testConvert106To5_matchesArcFaceOrder() {
        // 构造统一 106 序扁平数组(212 float,x,y 交错,归一化 [0,1]),默认 0.1/0.2
        var lm106 = [Float](repeating: 0, count: 212)
        for i in 0..<106 { lm106[i * 2] = 0.1; lm106[i * 2 + 1] = 0.2 }
        func set(_ idx: Int, _ x: Float, _ y: Float) { lm106[idx * 2] = x; lm106[idx * 2 + 1] = y }

        for i in [52, 53, 54, 55, 56, 57, 72, 73] { set(i, 0.30, 0.40) }  // 左眼(8 点均值)
        for i in [58, 59, 60, 61, 62, 63, 75, 76] { set(i, 0.70, 0.40) }  // 右眼(8 点均值)
        set(49, 0.50, 0.60)  // 鼻尖
        set(84, 0.35, 0.80)  // 左嘴角
        set(94, 0.65, 0.80)  // 右嘴角

        let lm5 = FaceAlignment.convert106ToLandmarks5(landmarks106: lm106, width: 100, height: 200)

        // [左眼x,y, 右眼x,y, 鼻尖x,y, 左嘴x,y, 右嘴x,y](像素坐标 = 归一化×width/height)
        let expected: [Float] = [30, 80, 70, 80, 50, 120, 35, 160, 65, 160]
        XCTAssertEqual(lm5.count, 10)
        for i in 0..<10 {
            XCTAssertEqual(lm5[i], expected[i], accuracy: 0.001, "index \(i): got \(lm5[i]), want \(expected[i])")
        }
    }
}
```

- [ ] **Step 2: 把文件加入 Xcode 测试 target**

PoLang 用 xcodegen 管理工程(`project.yml`)。新源文件需重新生成工程:

```bash
cd iosApp && xcodegen generate
```

确认 `FaceAlignment106To5Tests.swift` 被纳入 PoLangTests target(xcodegen 按目录通配,通常自动)。

- [ ] **Step 3: 跑测试验证通过**

`convert106ToLandmarks5` 已存在(dead code 但可编译),测试应直接通过。开发机为 Intel,MNN.framework 仅 arm64 → 测试 target 若链接 MNN 需真机;该测试是纯 Swift 不碰 MNN,但 PoLangTests 整体可能 link MNN,故用真机:

```bash
cd iosApp && xcodebuild test -workspace PoLang.xcworkspace -scheme PoLang \
  -destination 'platform=iOS,name=<你的真机名>' \
  -only-testing:PoLangTests/FaceAlignment106To5Tests
```

Expected: `Test Suite 'FaceAlignment106To5Tests' passed`。若提示 `destination` 不匹配,改用 `-destination 'platform=iOS,id=<UDID>'`(`xcrun xctrace list devices` 取 UDID)。

- [ ] **Step 4: Commit**

```bash
git add iosApp/PoLangTests/FaceAlignment106To5Tests.swift
git commit -m "test(ios): convert106ToLandmarks5 双端一致性单测

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Task 2: 修正 convert106ToLandmarks5 输入点序误注释

**Files:**
- Modify: `iosApp/PoLang/Platform/FaceAlignment.swift:31-35`

`FaceAlignment.swift:34` 现注释 *"106-point order: InsightFace 2D106det canonical order"* 错误——convert 索引按**统一序**定义,输入必须经 adapt。接通前改正,防后续误用原生序直喂。

- [ ] **Step 1: 改注释**

把 `FaceAlignment.swift:31-35` 的文档注释:

```swift
    /// 106-point landmarks → ArcFace 5-point landmarks (x,y interleaved, normalized [0,1]).
    ///
    /// Port of `TagGenerationPipeline.convert106ToLandmarks5`.
    /// 106-point order: InsightFace 2D106det canonical order.
    /// ArcFace order: left_eye, right_eye, nose, left_mouth, right_mouth.
```

改为:

```swift
    /// 统一 106 点 → ArcFace 5 点(扁平 x,y 交错,归一化 [0,1])。
    ///
    /// 对标 `TagGenerationPipeline.convert106ToLandmarks5`。
    /// ⚠️ 输入必须是经 `MnnLandmarkAdapter.adapt` 重排后的**统一 106 序**,
    ///    而非 2d106det 模型直出的 InsightFace 原生序——索引(52-57 等)按统一序定义,
    ///    喂原生序会取到错误解剖位置(如眼取到嘴)。
    /// ArcFace 顺序:left_eye, right_eye, nose, left_mouth, right_mouth。
```

- [ ] **Step 2: 编译确认**

```bash
cd iosApp && xcodebuild build -workspace PoLang.xcworkspace -scheme PoLang \
  -destination 'generic/platform=iOS' -quiet
```

Expected: `** BUILD SUCCEEDED **`(仅改注释,不会破坏编译)。

- [ ] **Step 3: Commit**

```bash
git add iosApp/PoLang/Platform/FaceAlignment.swift
git commit -m "docs(ios): 修正 convert106ToLandmarks5 输入点序注释(统一序非原生序)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Task 3: 接通 Pass1Pipeline 106→5 循环 + native 5pt fallback

**Files:**
- Modify: `iosApp/PoLang/Platform/Pass1Pipeline.swift:1-2`(加 import)、`:177-196`(替换循环体)

核心改动:每 ROI 跑 `detectLandmarks106 → adapt → convert106ToLandmarks5`,失败回退 RetinaFace native 5pt。

- [ ] **Step 1: 顶部加 `import simd`**

`Pass1Pipeline.swift:1-2` 现为:

```swift
import UIKit
import Foundation
```

改为:

```swift
import UIKit
import Foundation
import simd
```

(`MnnLandmarkAdapter.adapt` 返回 `[SIMD2<Float>]`,访问 `.x/.y` 需要 simd。)

- [ ] **Step 2: 替换 per-ROI 循环体**

把 `Pass1Pipeline.swift:177-196` 整段:

```swift
        for fi in 0..<faceCount {
            let off = fi * 15
            let lm5 = Array(faceBuf[(off + 5)..<(off + 15)])  // RetinaFace 原生 5 点
            allLandmarks5.append(lm5)

            // 仿射对齐到 112×112
            guard let alignedFace = FaceAlignment.alignFace(image: scaledImage, landmarks5: lm5) else { continue }

            // Glint360K embedding（MNN）
            guard let rgbData = rgbBytesFromImage(alignedFace, size: 112) else { continue }
            if let embedding = rgbData.withUnsafeBytes({ (ptr: UnsafeRawBufferPointer) -> Data? in
                guard let base = ptr.bindMemory(to: UInt8.self).baseAddress else { return nil }
                return faceEmbedder.extractEmbedding(base, width: 112, height: 112)
            }) {
                // 验证 embedding 非零非 NaN
                if isValidEmbedding(embedding) {
                    embeddings.append(embedding as Data)
                }
            }
        }
```

替换为:

```swift
        for fi in 0..<faceCount {
            let off = fi * 15
            let roiX = faceBuf[off + 0]
            let roiY = faceBuf[off + 1]
            let roiW = faceBuf[off + 2]
            let roiH = faceBuf[off + 3]
            let retinaLm5 = Array(faceBuf[(off + 5)..<(off + 15)])  // RetinaFace 原生 5 点(fallback)

            // 方案 B(对标 Android):ROI → 2D106 → adapt(原生→统一)→ convert106To5。
            // 失败回退 RetinaFace 原生 5 点(与 Android fallback 一致)。
            let lm5: [Float]
            var native106 = [Float](repeating: 0, count: 212)
            let ok106: Bool = native106.withUnsafeMutableBufferPointer { pbuf -> Bool in
                guard let pbase = pbuf.baseAddress else { return false }
                return pixelData.withUnsafeBytes { (raw: UnsafeRawBufferPointer) -> Bool in
                    guard let bgra = raw.baseAddress?.assumingMemoryBound(to: UInt8.self) else { return false }
                    return faceDetector.detectLandmarks106(bgra, width: Int32(width), height: Int32(height),
                                                           bytesPerRow: Int32(bytesPerRow),
                                                           roiX: roiX, roiY: roiY, roiW: roiW, roiH: roiH,
                                                           outPoints: pbase)
                }
            }
            if ok106, let unified = MnnLandmarkAdapter.adapt(native106, isFrontCamera: false) {
                // adapt 输出 [SIMD2<Float>](统一序,归一化)→ 扁平化喂 convert106ToLandmarks5
                var flat106 = [Float](repeating: 0, count: 212)
                for i in 0..<106 {
                    flat106[i * 2] = unified[i].x
                    flat106[i * 2 + 1] = unified[i].y
                }
                lm5 = FaceAlignment.convert106ToLandmarks5(landmarks106: flat106, width: width, height: height)
            } else {
                scanDebugLog("P1 face=\(fi) fallback native5pt ok106=\(ok106)")
                lm5 = retinaLm5
            }
            allLandmarks5.append(lm5)

            // 仿射对齐到 112×112
            guard let alignedFace = FaceAlignment.alignFace(image: scaledImage, landmarks5: lm5) else { continue }

            // Glint360K embedding（MNN）
            guard let rgbData = rgbBytesFromImage(alignedFace, size: 112) else { continue }
            if let embedding = rgbData.withUnsafeBytes({ (ptr: UnsafeRawBufferPointer) -> Data? in
                guard let base = ptr.bindMemory(to: UInt8.self).baseAddress else { return nil }
                return faceEmbedder.extractEmbedding(base, width: 112, height: 112)
            }) {
                // 验证 embedding 非零非 NaN
                if isValidEmbedding(embedding) {
                    embeddings.append(embedding as Data)
                }
            }
        }
```

- [ ] **Step 3: 编译确认**

```bash
cd iosApp && xcodebuild build -workspace PoLang.xcworkspace -scheme PoLang \
  -destination 'generic/platform=iOS' -quiet
```

Expected: `** BUILD SUCCEEDED **`。常见报错排查:
- `'MnnLandmarkAdapter' is not in scope` → 确认 `MnnLandmarkAdapter.swift` 在 PoLang app target(它在 `Features/Camera/Beauty/`,同 target,应可见)。
- `Cannot convert value of type '[SIMD2<Float>]'` → 检查 `import simd` 已加。
- `detectLandmarks106` 参数标签不匹配 → 以 `MnnFaceDetectorBridge.h:101-109` 为准。

- [ ] **Step 4: 真机扫描烟测**

装到真机,触发一次 TAG 扫描(相册里几张含人脸的图),拉日志:

```bash
# 触发扫描后:
adb logcat -s "PoLang:*" 2>/dev/null || true   # iOS 无 logcat,改用:
# devicectl 拉 Documents/scan_debug.log:
xcrun devicectl device copy from --device <UDID> --source /var/mobile/Containers/Data/Application/<App>/Documents/scan_debug.log --output ./scan_debug.log
```

Expected: 日志里 `P1 done` 正常出现,**几乎不出现** `fallback native5pt`(2D106 应成功)。若大量 fallback → 排查 `detectLandmarks106` 的 ROI 参数。

- [ ] **Step 5: Commit**

```bash
git add iosApp/PoLang/Platform/Pass1Pipeline.swift
git commit -m "feat(ios): embedding 接通 106→5 对齐(对标 Android),native5pt 降级 fallback

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Task 4: Step1 诊断——对齐脸 dump 开关

**Files:**
- Modify: `iosApp/PoLang/Platform/Pass1Pipeline.swift`(新增 dump 属性 + 循环内 dump)

肉眼核对 106→5 对齐后的人脸有无翻转/畸变(对标 Android commit `87458683` "保存前 30 张对齐图")。用 UserDefaults 开关,默认关。

- [ ] **Step 1: 加 dump 开关属性**

在 `Pass1Pipeline` 类内(`private let maxDetectSize` 附近)加:

```swift
    /// 对齐后人脸 dump 开关(UserDefaults "pass1_dump_debug_faces"=YES 开启)。
    /// 开启后每张对齐 112×112 脸存 PNG 到 Documents/debug_faces/,供肉眼核对翻转/畸变。
    private let dumpAlignedFaces: Bool = UserDefaults.standard.bool(forKey: "pass1_dump_debug_faces")
```

- [ ] **Step 2: 循环内 dump 对齐脸**

在 Task 3 替换后的循环里,`guard let alignedFace = FaceAlignment.alignFace(...)` 之后、`rgbBytesFromImage` 之前插入:

```swift
            if dumpAlignedFaces, let png = alignedFace.pngData() {
                let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
                let dir = docs.appendingPathComponent("debug_faces", isDirectory: true)
                try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
                try? png.write(to: dir.appendingPathComponent("face_\(mediaId)_\(fi).png"))
            }
```

- [ ] **Step 3: 编译 + 真机开启 dump 验证**

```bash
cd iosApp && xcodebuild build -workspace PoLang.xcworkspace -scheme PoLang \
  -destination 'generic/platform=iOS' -quiet
```

真机设置 `UserDefaults` 开关(可用 app 内开发者页或临时加一行,或 `devicectl` 不支持改 UserDefaults 则在代码里临时 `UserDefaults.standard.set(true, forKey:"pass1_dump_debug_faces")` 跑一次)。扫描几张图后拉 `Documents/debug_faces/`:

```bash
xcrun devicectl device copy from --device <UDID> \
  --source /var/mobile/Containers/Data/Application/<App>/Documents/debug_faces --output ./debug_faces
```

Expected: 得到若干 `face_*.png`(112×112),**肉眼检查人脸正向、无水平翻转、无畸变**。若翻转 → adapt 镜像或 ROI 坐标系问题;若畸变 → 归一化/width-height 传参问题。

- [ ] **Step 4: Commit**

```bash
git add iosApp/PoLang/Platform/Pass1Pipeline.swift
git commit -m "feat(ios): Pass1 对齐脸 debug dump 开关(UserDefaults)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Task 5: Step1 诊断——same/cross-person pair sim 分布脚本

**Files:**
- Create: `scripts/ios_face_sim_diag.py`

扫描后,从设备 SQLite 取 `face_embeddings` 表,按开发者指定的人物分组算 pair 余弦相似度分布,量化"same-person sim 是否从 ~0.5 回升"。`face_embeddings` 表由 `Pass1Pipeline.insertEmbeddings(mediaId:embeddings:)` 写入,含 `mediaId` 列、embedding 为 512×float32 BLOB(2048 字节)。

- [ ] **Step 1: 写诊断脚本**

创建 `scripts/ios_face_sim_diag.py`:

```python
#!/usr/bin/env python3
"""
iOS 人脸 embedding pair 相似度诊断。
用法:
  1. 扫描后从设备拉 TagDatabase sqlite:
     xcrun devicectl device copy from --device <UDID> \
       --source .../Documents/TagDatabase.sqlite --output ./Tag.sqlite
  2. 准备分组:从 app 人物页肉眼确认哪几个 mediaId 是同一人,
     写进 GROUPS(每组 = 同一人;另取若干跨组做 cross-person)。
  3. python3 scripts/ios_face_sim_diag.py ./Tag.sqlite
"""
import sqlite3
import struct
import sys
from itertools import combinations
from statistics import median

GROUPS = {
    # "person_a": [<mediaId>, <mediaId>, ...],   # 改成实测 mediaId
    # "person_b": [<mediaId>, ...],
}

def load_embeddings(db_path):
    conn = sqlite3.connect(db_path)
    cur = conn.cursor()
    # 列名按 TagDatabase 实际 schema;若不符,先 sqlite3 .schema face_embeddings 核对
    cur.execute("SELECT media_id, embedding FROM face_embeddings")
    out = {}
    for media_id, blob in cur.fetchall():
        if blob is None or len(blob) != 512 * 4:
            continue
        vec = struct.unpack("<512f", blob)
        out.setdefault(media_id, []).append(vec)
    conn.close()
    return out  # media_id -> [embedding,...] (一张图可能多脸)

def cos(a, b):
    dot = sum(x * y for x, y in zip(a, b))
    na = sum(x * x for x in a) ** 0.5
    nb = sum(y * y for y in b) ** 0.5
    return dot / (na * nb) if na and nb else 0.0

def main(db_path):
    embs = load_embeddings(db_path)
    # same-person:同组内任意 media_id 的任意 embedding 两两
    same = []
    for person, mids in GROUPS.items():
        vecs = []
        for m in mids:
            vecs.extend(embs.get(m, []))
        for a, b in combinations(vecs, 2):
            same.append(cos(a, b))
    # cross-person:不同组间
    cross = []
    persons = list(GROUPS.items())
    for i in range(len(persons)):
        for j in range(i + 1, len(persons)):
            va = []
            for m in persons[i][1]:
                va.extend(embs.get(m, []))
            vb = []
            for m in persons[j][1]:
                vb.extend(embs.get(m, []))
            for a in va:
                for b in vb:
                    cross.append(cos(a, b))

    def pct(xs, p):
        if not xs:
            return float("nan")
        xs = sorted(xs)
        return xs[min(len(xs) - 1, int(len(xs) * p))]

    print(f"same-person  n={len(same)}  median={median(same) if same else float('nan'):.3f}  "
          f"P10={pct(same,0.1):.3f}  P50={pct(same,0.5):.3f}  P90={pct(same,0.9):.3f}")
    print(f"cross-person n={len(cross)} median={median(cross) if cross else float('nan'):.3f}  "
          f"P10={pct(cross,0.1):.3f}  P50={pct(cross,0.5):.3f}  P90={pct(cross,0.9):.3f}")
    print(f"gap(same P50 - cross P50) = {(pct(same,0.5)-pct(cross,0.5)):.3f}")

if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "Tag.sqlite")
```

- [ ] **Step 2: 跑脚本(扫描后)**

```bash
# (1) 拉设备 sqlite(路径用 devicectl 查 app container)
# (2) 改 GROUPS 为实测 mediaId(从 app 人物页或 sqlite 查 media_id)
sqlite3 ./Tag.sqlite "SELECT DISTINCT media_id FROM face_embeddings LIMIT 20;"
# (3) 跑
python3 scripts/ios_face_sim_diag.py ./Tag.sqlite
```

- [ ] **Step 3: 判读 Step1 验收标准**

Expected(Step1 验收):
- `same-person median` 从接通前的 **~0.5** 回升到 **≥ 0.60**。
- `gap(same P50 - cross P50)` ≥ **0.20**(分离度增大)。
- 若 same median 仍 < 0.55 → 106→5 链路有问题,查 Task 4 dump 图有无翻转/畸变。

- [ ] **Step 4: Commit**

```bash
git add scripts/ios_face_sim_diag.py
git commit -m "feat(ios): 人脸 embedding pair sim 诊断脚本(Step1 验收)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Task 6: Step2 — 阈值独立标定 + 注释修正

**前置条件:** Task 5 脚本确认 same-person median ≥ 0.60、分离度达标。否则回到 Task 3/4 排查对齐质量,不进入本任务。

**Files:**
- Modify: `iosApp/PoLang/Platform/Tag/FaceClusterer.swift:10`
- Modify: `iosApp/PoLang/Platform/Tag/FaceClusterMaintenance.swift:16-20`

两端聚类系数独立(spec §3),**不照搬 Android 0.65**。基于 Task 5 实测分布标定:阈值取 same-person 分布 P10 与 cross-person 分布 P90 之间的值(兼顾召回与精度)。

- [ ] **Step 1: 确定阈值**

从 Task 5 输出读 same P10 与 cross P90。设:
- `T = midpoint(same_P10, cross_P90)`,通常落在 [0.50, 0.62]。

例:若 same P10=0.58、cross P90=0.40 → T≈0.49(偏低,说明分离好但 same 下沿不高);若 same P10=0.68、cross P90=0.45 → T≈0.56。**用实测值,不预设。**

- [ ] **Step 2: 改 FaceClusterer.minSimilarity**

`FaceClusterer.swift:10`(现 `minSimilarity = 0.45`)改为用 Step1 确定的 T:

```swift
    static let minSimilarity: Float = <T>   // 原 0.45;Step1 实测标定,见 ios_face_sim_diag.py
```

- [ ] **Step 3: 改 FaceClusterMaintenance 阈值 + 注释**

`FaceClusterMaintenance.swift:16-20` 现含 `cosineThreshold=0.45`、`mergeSimilarityThreshold=0.50`、`splitIntraMin=0.45`,以及 `:20` 的注释 *"(iOS 5pt sim~0.5)"*。把三个阈值改为基于 T 的关联值(T 主控,merge 略宽于 T,split 略严),并修正注释:

```swift
    // 原:"...降低以拆分「同人组混入异人」(iOS 5pt sim~0.5)"
    // 改为:
    // 106→5 对齐后 same-person sim 已回升(见 ios_face_sim_diag.py),阈值独立标定,
    // 不再沿用 native5pt 时代的 0.45 代偿值。
```

具体数值:cosineThreshold=T、splitIntraMin=T、mergeSimilarityThreshold=max(T, merge 原值视分离度)。**用 Step1 实测,不预设。**

- [ ] **Step 4: 真机扫描回归**

全量重扫(归零后重跑,确保 embedding 用新对齐),观察聚类:
- 同人合并正确(不再碎成多个小簇)。
- 不同人不并组(尤其外观相似的人)。
- 无性能回退(扫描总时长与 Step1 基线持平)。

- [ ] **Step 5: Commit**

```bash
git add iosApp/PoLang/Platform/Tag/FaceClusterer.swift iosApp/PoLang/Platform/Tag/FaceClusterMaintenance.swift
git commit -m "feat(ios): 聚类阈值独立标定(基于 106→5 实测 sim 分布)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Self-Review 结果

- **Spec 覆盖**:§1-2 背景/点序 → Task 2 注释 + 设计前提;§3 边界 → 不动 embedder/聚类结构全遵守;§4 数据流 → Task 3;§5 两步走 → Task 3-5(Step1)+ Task 6(Step2);§6 误注释 → Task 2;§7 错误处理 → Task 3 fallback;§8 测试 → Task 1(双端一致)+ Task 4(肉眼)+ Task 5(数据);§9 性能风险 → Task 3 Step4 烟测 + Task 6 Step4 回归观测。全覆盖。
- **占位扫描**:Task 6 阈值 `<T>` 是数据驱动的待填(spec §5 明确两步走),附明确取值规则 `midpoint(same_P10, cross_P90)`,非"TODO"。其余步骤代码完整。
- **类型一致**:`detectLandmarks106` 参数(`MnnFaceDetectorBridge.h:101`)、`adapt` 返回 `[SIMD2<Float>]?`、`convert106ToLandmarks5` 入参 `[Float]`、`alignFace` 入参 `[Float]`、`extractEmbedding(_:width:height:)` 跨任务一致。adapt→flat→convert 的类型桥接在 Task 3 Step2 显式写出。
