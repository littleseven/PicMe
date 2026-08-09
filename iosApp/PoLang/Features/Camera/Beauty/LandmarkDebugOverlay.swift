import SwiftUI
import simd

// LandmarkDebugOverlay.swift — 逐点关键点 + 人脸框可视化（对标 Android CameraDebugOverlay.kt 的
// FaceDebugOverlayBigBeauty）。
//
// 诊断目标：用户报「瘦脸形变区域不对 / 偏转」。静态分析已逐一核对（remap✓ warp 索引✓
// crop✓ aspect✓ Y 朝向✓）均通过，故改用可视化裁决——把 BeautyRenderer 实际消费的
// 106 统一点 + 人脸框画到预览上：
//   - 人脸框 + 点云正好落在眉/眼/鼻/嘴/下颌 → 点正确，「偏转」在 warp 感知/强度；
//   - 点云整体旋转/歪斜/镜像 → 直接锁定坐标空间 bug。
//
// 人脸框来源：106 点包围盒（含轮廓/眉/眼/鼻/嘴，pad 5%）。与 Android 的 RetinaFace ROI 等价
// 呈现（橙色框 + 黄色角点 + 尺寸标注），且对 MNN/MediaPipe 双引擎一致（都产出 106 点）。
//
// 坐标映射：face 特征在 buffer 归一化点 P（Y-down）经与 shader 相同的 aspect-fill crop 后显示在屏。
// shader 对 drawable quad-uv `s` 采样 `s*cropScale+cropOffset`，故 P 显示在屏 s=(P-cropOffset)/cropScale，
// 屏像素 = s * viewSize。cropScale/cropOffset 由 (buffer 尺寸, view) 等比放大覆盖求得（与
// BeautyRenderer.draw 同公式）。buffer 尺寸由 Coordinator.draw 从当前帧实测写入 store.bufferSize。
//
// 开关：启动参数 `-showLandmarks`（锁定开）或 设置→相机与美颜「Show Face Landmarks」。

/// 最新 106 点 + buffer 尺寸的可观察容器（Coordinator.draw 在 Metal 线程更新 → 主线程 @Published）。
final class LandmarkOverlayStore: ObservableObject {
    @Published var points: [SIMD2<Float>] = []
    /// 检测 buffer 尺寸（portrait=720×1280）；aspect-fill crop 计算用，由 Coordinator 实测写入。
    var bufferSize: CGSize = CGSize(width: 720, height: 1280)
}

struct LandmarkDebugOverlay: View {
    @ObservedObject var store: LandmarkOverlayStore

    // 与 warp.metal gpupixelThinFace 的 9 对控制点完全一致（unified-106 索引）
    private let thinPairs: [(Int, Int)] = [
        (3, 44), (29, 44),   // 轮廓上 → 鼻梁上
        (7, 45), (25, 45),   // 脸颊 → 鼻梁中
        (10, 46), (22, 46),  // 下颌 → 鼻梁下
        (14, 16), (18, 16),  // 下颌角 → 下巴中心
        (15, 16)             // 下巴旁 → 下巴中心
    ]
    // 与 warp.metal gpupixelBigEye 一致
    private let eyePairs: [(Int, Int)] = [(74, 72), (77, 75)]

    // 关键解剖点速查（unified-106 索引）——画小标签，肉眼快速判断朝向是否正确
    private let keyLabels: [(Int, String)] = [
        (16, "下巴"), (49, "鼻尖"), (72, "右眼角"), (75, "左眼角"), (0, "鬓角")
    ]

    var body: some View {
        GeometryReader { geo in
            Canvas { ctx, size in
                let pts = store.points
                guard pts.count >= 106 else { return }

                let bw = Float(store.bufferSize.width)
                let bh = Float(store.bufferSize.height)
                let dW = Float(size.width), dH = Float(size.height)
                guard bw > 0, bh > 0, dW > 0, dH > 0 else { return }
                // aspect-fill（覆盖）：与 BeautyRenderer.draw 同式
                let scale = max(dW / bw, dH / bh)
                let visW = dW / (bw * scale)
                let visH = dH / (bh * scale)
                let cropScale = SIMD2<Float>(visW, visH)
                let cropOffset = SIMD2<Float>((1 - visW) / 2, (1 - visH) / 2)

                func toScreen(_ p: SIMD2<Float>) -> CGPoint {
                    let s = (p - cropOffset) / cropScale
                    return CGPoint(x: CGFloat(s.x) * size.width, y: CGFloat(s.y) * size.height)
                }

                // 0) 人脸框（人脸框位置）——106 点包围盒（pad 5%），橙色框 + 黄色角点 + 尺寸标注
                let xs = pts.map { $0.x }
                let ys = pts.map { $0.y }
                var minX = (xs.min() ?? 0), maxX = (xs.max() ?? 0)
                var minY = (ys.min() ?? 0), maxY = (ys.max() ?? 0)
                let padX = (maxX - minX) * 0.05
                let padY = (maxY - minY) * 0.05
                minX = max(0, minX - padX); maxX = min(1, maxX + padX)
                minY = max(0, minY - padY); maxY = min(1, maxY + padY)
                let tl = toScreen(SIMD2<Float>(minX, minY))
                let br = toScreen(SIMD2<Float>(maxX, maxY))
                let boxRect = CGRect(x: tl.x, y: tl.y, width: br.x - tl.x, height: br.y - tl.y)
                ctx.stroke(
                    Path(boxRect),
                    with: .color(Color(red: 1.0, green: 0.43, blue: 0.0, opacity: 0.85)),
                    lineWidth: 2
                )
                drawCornerBrackets(ctx, rect: boxRect, color: .yellow, len: min(boxRect.width, boxRect.height) * 0.18, width: 3)
                let wPct = Int((maxX - minX) * 100)
                let hPct = Int((maxY - minY) * 100)
                ctx.draw(
                    Text("脸 \(wPct)%×\(hPct)%")
                        .font(.system(size: 11, weight: .bold)).foregroundColor(.white),
                    at: CGPoint(x: boxRect.midX, y: max(boxRect.minY - 10, 10))
                )

                // 1) 轮廓连线（0-32）—— 画出来若呈正向椭圆=点云朝向正确
                var contour = Path()
                for i in 0...min(32, pts.count - 1) {
                    let c = toScreen(pts[i])
                    if i == 0 { contour.move(to: c) } else { contour.addLine(to: c) }
                }
                ctx.stroke(contour, with: .color(.cyan.opacity(0.6)), lineWidth: 1.5)

                // 2) 全部 106 点（蓝色小圆点）
                for i in 0..<pts.count {
                    let c = toScreen(pts[i])
                    let r: CGFloat = 2.5
                    ctx.fill(Path(ellipseIn: CGRect(x: c.x - r, y: c.y - r, width: r * 2, height: r * 2)),
                             with: .color(.blue.opacity(0.85)))
                }

                // 2b) 关键解剖点小标签（裁决朝向：下巴在底、鼻尖在中、眼角水平）
                for (idx, label) in keyLabels where idx < pts.count {
                    let c = toScreen(pts[idx])
                    ctx.draw(
                        Text("\(idx):\(label)")
                            .font(.system(size: 9, weight: .semibold)).foregroundColor(.yellow),
                        at: CGPoint(x: c.x, y: c.y - 10)
                    )
                }

                // 3) 9 对瘦脸控制点（红 origin → 绿 target + 彩色箭头）
                for (idx, pair) in thinPairs.enumerated() {
                    let (o, t) = (pair.0, pair.1)
                    guard o < pts.count, t < pts.count else { continue }
                    let a = toScreen(pts[o])
                    let b = toScreen(pts[t])
                    var line = Path(); line.move(to: a); line.addLine(to: b)
                    let color: Color = {
                        switch idx {
                        case 0, 1: return Color(red: 1.0, green: 0.42, blue: 0.0)      // 橙 上部
                        case 2, 3: return Color(red: 0.0, green: 0.9, blue: 1.0)        // 青 中部
                        case 4, 5: return Color(red: 0.46, green: 1.0, blue: 0.12)      // 绿 下部
                        default:    return Color(red: 1.0, green: 0.0, blue: 1.0)      // 紫 底部
                        }
                    }()
                    ctx.stroke(line, with: .color(color.opacity(0.9)), lineWidth: 2)
                    // origin（大红圈）
                    let ro: CGFloat = 6
                    ctx.stroke(Path(ellipseIn: CGRect(x: a.x - ro, y: a.y - ro, width: ro * 2, height: ro * 2)),
                               with: .color(.red.opacity(0.95)), lineWidth: 2)
                    // target（绿点）
                    let rt: CGFloat = 4
                    ctx.fill(Path(ellipseIn: CGRect(x: b.x - rt, y: b.y - rt, width: rt * 2, height: rt * 2)),
                             with: .color(.green.opacity(0.95)))
                }

                // 4) 2 对大眼控制点（黄圈 origin=瞳孔）
                for pair in eyePairs {
                    let (o, t) = (pair.0, pair.1)
                    guard o < pts.count, t < pts.count else { continue }
                    let a = toScreen(pts[o])
                    let b = toScreen(pts[t])
                    var line = Path(); line.move(to: a); line.addLine(to: b)
                    ctx.stroke(line, with: .color(.yellow.opacity(0.7)), lineWidth: 2)
                    let ro: CGFloat = 8
                    ctx.fill(Path(ellipseIn: CGRect(x: a.x - ro, y: a.y - ro, width: ro * 2, height: ro * 2)),
                             with: .color(.yellow.opacity(0.25)))
                    ctx.stroke(Path(ellipseIn: CGRect(x: a.x - ro, y: a.y - ro, width: ro * 2, height: ro * 2)),
                               with: .color(.yellow.opacity(0.9)), lineWidth: 2)
                }
            }
        }
        .allowsHitTesting(false)
        .accessibilityIdentifier("landmark_debug_overlay")
    }

    /// 四角 L 形括号（对标 Android roiRect 的黄色角点）
    private func drawCornerBrackets(_ ctx: GraphicsContext, rect: CGRect, color: Color, len: CGFloat, width: CGFloat) {
        let l = min(len, rect.width / 2, rect.height / 2)
        let p = rect.origin
        let s = rect.size
        // 四个角，每个角两段
        let corners: [(CGPoint, CGPoint, CGPoint)] = [
            (p, CGPoint(x: p.x + l, y: p.y), CGPoint(x: p.x, y: p.y + l)),                 // 左上
            (CGPoint(x: p.x + s.width, y: p.y), CGPoint(x: p.x + s.width - l, y: p.y), CGPoint(x: p.x + s.width, y: p.y + l)), // 右上
            (CGPoint(x: p.x, y: p.y + s.height), CGPoint(x: p.x + l, y: p.y + s.height), CGPoint(x: p.x, y: p.y + s.height - l)), // 左下
            (CGPoint(x: p.x + s.width, y: p.y + s.height), CGPoint(x: p.x + s.width - l, y: p.y + s.height), CGPoint(x: p.x + s.width, y: p.y + s.height - l)) // 右下
        ]
        for (corner, mid1, mid2) in corners {
            var path = Path()
            path.move(to: mid1); path.addLine(to: corner); path.addLine(to: mid2)
            ctx.stroke(path, with: .color(color.opacity(0.95)), lineWidth: width)
        }
    }
}
