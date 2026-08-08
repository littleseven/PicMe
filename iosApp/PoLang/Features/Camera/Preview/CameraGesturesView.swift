import SwiftUI
import UIKit

/// 相机手势叠加层（对标 Android 相机手势：对焦/变焦/曝光）
///
/// 透明覆盖在 MetalPreviewView 上方：
/// - 点按 → 对焦 + 曝光（在该点）
/// - 捏合 → 变焦（videoZoomFactor）
/// - 垂直拖动 → 曝光补偿（[-2, +2]）
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

                    // 点按对焦
                    .onTapGesture { location in
                        let devicePoint = convertViewToDevicePoint(location, in: geo)
                        controller.focus(at: devicePoint)
                        triggerFocusRing(at: location)
                    }

                    // 捏合变焦
                    .gesture(
                        MagnifyGesture()
                            .onChanged { value in
                                let factor = zoomBase * value.magnification
                                controller.setZoom(factor)
                            }
                            .onEnded { value in
                                zoomBase *= value.magnification
                                zoomBase = max(1.0, min(zoomBase, 10.0))
                            }
                    )

                    // 垂直拖动曝光补偿
                    .gesture(
                        DragGesture(minimumDistance: 20)
                            .onChanged { value in
                                // 垂直拖动距离映射到 [-2, +2]
                                let normalized = -Float(value.translation.height / geo.size.height) * 4.0
                                let clamped = max(-2.0, min(2.0, normalized))
                                controller.setExposureBias(clamped)
                            }
                    )

                // 对焦框动画
                if showFocusRing, let loc = focusLocation {
                    FocusRingView()
                        .frame(width: 60, height: 60)
                        .position(loc)
                        .transition(.opacity)
                }
            }
        }
    }

    /// SwiftUI 视图坐标 → AVCaptureDevice 归一化坐标（后置竖屏 Portrait）
    /// 设备坐标系：左上 (0,0) 右下 (1,1)，
    /// 与 AVCaptureDevice.focusPointOfInterest 约定一致（portrait 模式无翻转）
    private func convertViewToDevicePoint(_ point: CGPoint, in geo: GeometryProxy) -> CGPoint {
        CGPoint(
            x: point.x / geo.size.width,
            y: point.y / geo.size.height
        )
    }

    private func triggerFocusRing(at location: CGPoint) {
        withAnimation(.easeOut(duration: 0.2)) {
            focusLocation = location
            showFocusRing = true
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.2) {
            withAnimation(.easeIn(duration: 0.3)) {
                showFocusRing = false
            }
        }
    }
}

/// 对焦框（方框缩放动画）
private struct FocusRingView: View {
    @State private var scale: CGFloat = 1.4

    var body: some View {
        RoundedRectangle(cornerRadius: 6)
            .stroke(Color.yellow, lineWidth: 2)
            .scaleEffect(scale)
            .opacity(0.8)
            .onAppear {
                withAnimation(.easeOut(duration: 0.8)) {
                    scale = 1.0
                }
            }
    }
}
