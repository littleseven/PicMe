import Foundation
import UIKit
import CoreGraphics

/// MNN 端侧推理离线自检（真机自动化验收用）。
///
/// 加载 bundle 内固定人脸图（face_test.jpg）→ BGRA 光栅化 → 调用 PLMnnFaceDetector 两阶段检测
///（RetinaFace det_500m → 2d106det）→ 把结果（模型加载 / 是否检出 / 106 点统计 / 样本坐标）
/// 写入 Documents/mnn-verify.txt，供 `devicectl copy from` 拉取，作为「iOS 端 MNN 端侧推理 0 的突破」
/// 的客观证据。仅在启动参数 `-mnnSelfTest` 存在时执行（默认不跑，不影响产品行为）。
enum MnnSelfTest {
    struct Result {
        let headline: String
        let lines: [String]
    }

    static func runIfRequested() {
        guard ProcessInfo.processInfo.arguments.contains("-mnnSelfTest") else { return }
        DispatchQueue.global(qos: .userInitiated).async {
            let result = perform()
            write(result)
            // print 也输出一份（iOS 日志可能 <private>，文件才是可靠通道）
            print("[PoLang] MNN self-test headline: \(result.headline)")
            for line in result.lines {
                print("[PoLang] MNN self-test | \(line)")
            }
        }
    }

    // MARK: - 核心

    private static func perform() -> Result {
        guard let url = resolveResource("face_test", "jpg"),
              let img = UIImage(contentsOfFile: url.path),
              let cg = img.cgImage else {
            return Result(headline: "FAIL: face_test.jpg missing", lines: [])
        }
        let w = cg.width
        let h = cg.height

        guard let bgra = rasterizeBGRA(cg, width: w, height: h) else {
            return Result(headline: "FAIL: BGRA rasterize", lines: [])
        }

        let det = PLMnnFaceDetector()
        let retinaPath = resolveResource("det_500m", "mnn")?.path ?? ""
        let landmarkPath = resolveResource("2d106det", "mnn")?.path ?? ""
        let loaded = det.loadRetinaModel(retinaPath, landmarkModel: landmarkPath)

        // 106 个点 × (x,y) = 212 floats（InsightFace 原生序，未镜像）
        var native = [Float](repeating: 0, count: 212)
        let found = loaded && bgra.withUnsafeBytes { (raw: UnsafeRawBufferPointer) -> Bool in
            guard let base = raw.baseAddress?.assumingMemoryBound(to: UInt8.self) else { return false }
            return det.detect(
                base,
                width: Int32(w),
                height: Int32(h),
                bytesPerRow: Int32(w * 4),
                outPoints: &native
            )
        }

        var lines: [String] = []
        lines.append("image: face_test.jpg \(w)x\(h)")
        lines.append("retinaModel: \(retinaPath.isEmpty ? "MISSING" : "OK")")
        lines.append("landmarkModel: \(landmarkPath.isEmpty ? "MISSING" : "OK")")
        lines.append("loadReady: \(det.ready)")
        lines.append("debugInfo: \(det.debugInfo ?? "")")
        lines.append("faceFound: \(found)")

        if found {
            // 原生 106 → 统一序（isFrontCamera=false：静态照片不做前置镜像）
            if let unified = MnnLandmarkAdapter.adapt(native, isFrontCamera: false) {
                let xs = unified.map { $0.x }
                let ys = unified.map { $0.y }
                let minX = xs.min() ?? 0
                let maxX = xs.max() ?? 0
                let minY = ys.min() ?? 0
                let maxY = ys.max() ?? 0
                lines.append("landmarksUnified: \(unified.count)")
                lines.append(String(
                    format: "bbox: x[%.3f,%.3f] y[%.3f,%.3f] w=%.3f h=%.3f",
                    minX, maxX, minY, maxY, maxX - minX, maxY - minY
                ))
                let sample = unified.prefix(10).map { String(format: "(%.3f,%.3f)", $0.x, $0.y) }
                    .joined(separator: " ")
                lines.append("sample10: \(sample)")
            } else {
                lines.append("landmarksUnified: ADAPT_FAILED")
            }
        }

        let headline = found ? "OK face detected, 2-stage pipeline ran" : "NO FACE (pipeline executed)"
        return Result(headline: headline, lines: lines)
    }

    // MARK: - 辅助

    private static func resolveResource(_ name: String, _ ext: String) -> URL? {
        for dir in ["Assets/Mnn", "Assets", ""] {
            if let url = Bundle.main.url(forResource: name, withExtension: ext, subdirectory: dir) {
                return url
            }
        }
        return Bundle.main.url(forResource: name, withExtension: ext)
    }

    /// CGImage → BGRA（B,G,R,A 字节序，与 PLMnnFaceDetector.detect 期望一致）。
    private static func rasterizeBGRA(_ cg: CGImage, width: Int, height: Int) -> Data? {
        let bytesPerRow = width * 4
        var data = Data(count: bytesPerRow * height)
        let ctx: CGContext? = data.withUnsafeMutableBytes { rawBuffer in
            CGContext(
                data: rawBuffer.baseAddress,
                width: width,
                height: height,
                bitsPerComponent: 8,
                bytesPerRow: bytesPerRow,
                space: CGColorSpaceCreateDeviceRGB(),
                bitmapInfo: CGBitmapInfo.byteOrder32Little.rawValue | CGImageAlphaInfo.premultipliedFirst.rawValue
            )
        }
        ctx?.draw(cg, in: CGRect(x: 0, y: 0, width: width, height: height))
        return ctx != nil ? data : nil
    }

    private static func write(_ result: Result) {
        guard let doc = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first else { return }
        let file = doc.appendingPathComponent("mnn-verify.txt")
        let payload = """
        # MNN iOS on-device self-test
        timestamp-since-boot: \(ProcessInfo.processInfo.systemUptime)
        \(result.lines.joined(separator: "\n"))
        headline: \(result.headline)

        """
        try? payload.write(to: file, atomically: true, encoding: .utf8)
        print("[PoLang] MNN self-test written: \(file.path)")
    }
}
