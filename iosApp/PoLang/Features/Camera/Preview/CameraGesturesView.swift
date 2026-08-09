import SwiftUI
import UIKit

/// 相机手势叠加层（对标 Android CameraOverlays.kt:397-516 + CameraControls.kt:99-138）
///
/// - 点按 → 对焦+曝光 + 青色 L 型对焦十字星
/// - 捏合 → 变焦（保留，Android 同时有预设按钮）
/// - 垂直拖动 → 曝光补偿（保留，Android 在 ProMode 面板做）
struct CameraGesturesView: View {
    let controller: CaptureSessionController

    @State private var zoomBase: CGFloat = 1.0
    @State private var focusLocation: CGPoint?
    @State private var showFocusRing = false

    var body: some View {
        GeometryReader { geo in
            ZStack {
                Color.clear
                    .contentShape(Rectangle())

                    .onTapGesture { location in
                        let devicePoint = convertViewToDevicePoint(location, in: geo)
                        print("[PoLang] gesture.focus: view=(\(String(format: "%.1f", location.x)),\(String(format: "%.1f", location.y))) device=(\(String(format: "%.3f", devicePoint.x)),\(String(format: "%.3f", devicePoint.y)))")
                        controller.focus(at: devicePoint)
                        triggerFocusRing(at: location)
                    }

                    .gesture(
                        MagnificationGesture()
                            .onChanged { value in
                                let factor = zoomBase * value
                                controller.setZoom(factor)
                            }
                            .onEnded { value in
                                zoomBase *= value
                                zoomBase = max(1.0, min(zoomBase, 10.0))
                            }
                    )

                    .gesture(
                        DragGesture(minimumDistance: 20)
                            .onChanged { value in
                                // 只响应垂直主导拖动（水平滑动留给全局切页手势，避免误清曝光）
                                guard abs(value.translation.height) > abs(value.translation.width) else { return }
                                let normalized = -Float(value.translation.height / geo.size.height) * 4.0
                                let clamped = max(-2.0, min(2.0, normalized))
                                controller.setExposureBias(clamped)
                            }
                    )

                // 对焦框（对标 Android FaceFocusCrosshair: 100pt, 青色 L 型角, 中心十字）
                if showFocusRing, let loc = focusLocation {
                    FocusCrosshairView()
                        .position(loc)
                        .transition(.opacity)
                }
            }
        }
    }

    /// portrait: (1-y, x) 变换（🔴8）
    private func convertViewToDevicePoint(_ point: CGPoint, in geo: GeometryProxy) -> CGPoint {
        let normalizedX = point.x / geo.size.width
        let normalizedY = point.y / geo.size.height
        return CGPoint(x: 1.0 - normalizedY, y: normalizedX)
    }

    private func triggerFocusRing(at location: CGPoint) {
        withAnimation(.spring(response: 0.3, dampingFraction: 0.7)) {
            focusLocation = location
            showFocusRing = true
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) {
            withAnimation(.easeOut(duration: 0.4)) {
                showFocusRing = false
            }
        }
    }
}

/// 对焦十字星（对标 Android FaceFocusCrosshair）
/// 100pt 外框，4 角 L 型标记（20pt 拐角长度），青色 #00E5FF，中心 16pt 十字 + 3pt 中心点
private struct FocusCrosshairView: View {
    private let size: CGFloat = 100
    private let cornerLen: CGFloat = 20
    private let lineWidth: CGFloat = 2
    private let color = Color(red: 0, green: 0.9, blue: 1) // #00E5FF

    var body: some View {
        ZStack {
            // 4 角 L 型标记
            // 左上
            Path { p in
                p.move(to: CGPoint(x: 0, y: cornerLen))
                p.addLine(to: CGPoint(x: 0, y: 0))
                p.addLine(to: CGPoint(x: cornerLen, y: 0))
            }
            .stroke(color, style: StrokeStyle(lineWidth: lineWidth, lineCap: .round))
            // 右上
            Path { p in
                p.move(to: CGPoint(x: size - cornerLen, y: 0))
                p.addLine(to: CGPoint(x: size, y: 0))
                p.addLine(to: CGPoint(x: size, y: cornerLen))
            }
            .stroke(color, style: StrokeStyle(lineWidth: lineWidth, lineCap: .round))
            // 左下
            Path { p in
                p.move(to: CGPoint(x: 0, y: size - cornerLen))
                p.addLine(to: CGPoint(x: 0, y: size))
                p.addLine(to: CGPoint(x: cornerLen, y: size))
            }
            .stroke(color, style: StrokeStyle(lineWidth: lineWidth, lineCap: .round))
            // 右下
            Path { p in
                p.move(to: CGPoint(x: size - cornerLen, y: size))
                p.addLine(to: CGPoint(x: size, y: size))
                p.addLine(to: CGPoint(x: size, y: size - cornerLen))
            }
            .stroke(color, style: StrokeStyle(lineWidth: lineWidth, lineCap: .round))

            // 中心十字（16pt）
            Path { p in
                p.move(to: CGPoint(x: size/2 - 8, y: size/2))
                p.addLine(to: CGPoint(x: size/2 + 8, y: size/2))
                p.move(to: CGPoint(x: size/2, y: size/2 - 8))
                p.addLine(to: CGPoint(x: size/2, y: size/2 + 8))
            }
            .stroke(color.opacity(0.6), style: StrokeStyle(lineWidth: 1.5, lineCap: .round))

            // 中心点（3pt）
            Circle()
                .fill(color)
                .frame(width: 3, height: 3)
                .position(x: size/2, y: size/2)
        }
        .frame(width: size, height: size)
    }
}
