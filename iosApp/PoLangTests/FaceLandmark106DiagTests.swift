import XCTest
import UIKit
import CoreGraphics
@testable import PoLang

/// 🔬 2d106det 点序稳定性诊断(对应 memory ios-face-clustering-native5pt-state 的"鼻尖 idx 跳"反证)。
///
/// 对 face_test.jpg 每张脸:打印原生 106 点中离 RetinaFace nose 最近的原生 idx
/// (memory 称跨脸在 61/84/31/83 跳;若稳定且=80 则 FULL_REMAP[49] 取对),
/// 以及 adapt 后统一序 49(应=鼻尖)与 RetinaFace nose 的归一化距离 pxRel(<0.05=对齐)。
///
/// 判读:跨脸原生 idx 一致 + pxRel<0.05 → 点序稳定,adapt+convert 可用 → Task3 继续;
///      原生 idx 跳 或 pxRel 大 → memory 反证成立 → 回退 Task3。
final class FaceLandmark106DiagTests: XCTestCase {

    /// 遍历 bundle 子目录找资源(对标 Pass1Pipeline.resolveBundledModel:Assets/Mnn、Assets、根)
    private static func bundled(_ name: String, _ ext: String) -> String? {
        for dir in ["Assets/Mnn", "Assets", ""] {
            if let p = Bundle.main.path(forResource: name, ofType: ext, inDirectory: dir) { return p }
        }
        return nil
    }

    func testDetect106_landmarkOrderDiagnostic() throws {
        // 1. 加载 fixture 人脸图(app bundle Assets/Mnn/face_test.jpg)
        guard let imgPath = Self.bundled("face_test", "jpg"),
              let img = UIImage(contentsOfFile: imgPath),
              let cg = img.cgImage else {
            throw NSError(domain: "diag", code: 1, userInfo: [NSLocalizedDescriptionKey: "face_test.jpg not found in bundle"])
        }
        let w = cg.width
        let h = cg.height
        let bpr = w * 4
        XCTAssertEqual(bpr, w * 4)

        // 2. 构造 BGRA 像素(对标 Pass1Pipeline.pixelDataFromImage)
        var data = Data(count: bpr * h)
        data.withUnsafeMutableBytes { raw in
            guard let base = raw.baseAddress else { return }
            let ctx = CGContext(
                data: base, width: w, height: h,
                bitsPerComponent: 8, bytesPerRow: bpr,
                space: CGColorSpaceCreateDeviceRGB(),
                bitmapInfo: CGBitmapInfo.byteOrder32Little.rawValue | CGImageAlphaInfo.premultipliedFirst.rawValue
            )
            ctx?.draw(cg, in: CGRect(x: 0, y: 0, width: w, height: h))
        }

        // 3. 初始化 detector + 加载 bundled 模型
        let det = PLMnnFaceDetector()
        guard let retinaPath = Self.bundled("det_500m", "mnn"),
              let lmPath = Self.bundled("2d106det", "mnn") else {
            throw NSError(domain: "diag", code: 2, userInfo: [NSLocalizedDescriptionKey: "det_500m/2d106det not in bundle"])
        }
        XCTAssertTrue(det.loadRetinaModel(retinaPath, landmarkModel: lmPath), "模型加载失败")

        // 4. 多脸检测(扁平缓冲,15 float/脸)
        let maxFaces = 32
        var faceBuf = [Float](repeating: 0, count: 15 * maxFaces)
        let faceCount: Int = faceBuf.withUnsafeMutableBufferPointer { fbuf -> Int in
            guard let fbase = fbuf.baseAddress else { return 0 }
            return Int(data.withUnsafeBytes { (raw: UnsafeRawBufferPointer) -> Int32 in
                guard let bgra = raw.baseAddress?.assumingMemoryBound(to: UInt8.self) else { return 0 }
                return det.detectAllFacesFlat(bgra, width: Int32(w), height: Int32(h),
                                              bytesPerRow: Int32(bpr), outBuf: fbase, maxFaces: Int32(maxFaces))
            })
        }
        XCTAssertGreaterThan(faceCount, 0, "face_test.jpg 未检测到人脸")
        print("🔬 DIAG face_test.jpg \(w)×\(h) 检测到 \(faceCount) 张脸")

        // 5. 每张脸:106 检测 + 点序诊断
        for fi in 0..<faceCount {
            let off = fi * 15
            let roiX = faceBuf[off + 0]
            let roiY = faceBuf[off + 1]
            let roiW = faceBuf[off + 2]
            let roiH = faceBuf[off + 3]
            // RetinaFace 5 点:[lex,ley,rex,rey,nosex,nosey,...];nose 在 index 4,5
            let noseX = faceBuf[off + 5 + 4]
            let noseY = faceBuf[off + 5 + 5]

            var native106 = [Float](repeating: 0, count: 212)
            let ok106: Bool = native106.withUnsafeMutableBufferPointer { pbuf -> Bool in
                guard let pbase = pbuf.baseAddress else { return false }
                return data.withUnsafeBytes { (raw: UnsafeRawBufferPointer) -> Bool in
                    guard let bgra = raw.baseAddress?.assumingMemoryBound(to: UInt8.self) else { return false }
                    return det.detectLandmarks106(bgra, width: Int32(w), height: Int32(h),
                                                  bytesPerRow: Int32(bpr),
                                                  roiX: roiX, roiY: roiY, roiW: roiW, roiH: roiH,
                                                  outPoints: pbase)
                }
            }
            if !ok106 {
                print("🔬 DIAG face=\(fi): detectLandmarks106 失败")
                continue
            }

            // 原生 106 中离 RetinaFace nose 最近的原生 idx
            var bestIdx = 0
            var bestDist: Float = Float.infinity
            for i in 0..<106 {
                let nx = native106[i * 2] * Float(w)
                let ny = native106[i * 2 + 1] * Float(h)
                let d = (nx - noseX) * (nx - noseX) + (ny - noseY) * (ny - noseY)
                if d < bestDist { bestDist = d; bestIdx = i }
            }

            // adapt → 统一序 49(应=鼻尖)pxRel
            guard let unified = MnnLandmarkAdapter.adapt(native106, isFrontCamera: false) else {
                print("🔬 DIAG face=\(fi): adapt 失败")
                continue
            }
            let u49x = unified[49].x * Float(w)
            let u49y = unified[49].y * Float(h)
            let pxRel = ((u49x - noseX) * (u49x - noseX) + (u49y - noseY) * (u49y - noseY)).squareRoot() / Float(w)

            print("🔬 DIAG face=\(fi): 原生鼻尖idx=\(bestIdx) (FULL_REMAP[49]=80,期望≈80) | 统一49 pxRel=\(String(format: "%.4f", pxRel)) (<0.05=对齐鼻尖)")
        }
    }
}
