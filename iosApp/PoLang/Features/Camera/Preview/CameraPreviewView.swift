import SwiftUI
import MetalKit

/// 相机主页面（对标 Android CameraPreviewContent.kt）
/// 沉浸式全屏 + 顶部双侧控件列 + 底部三行控件 + 弹出面板
struct CameraPreviewView: View {
    @EnvironmentObject private var container: AppContainer
    @State private var authorized = false
    @State private var controller = CaptureSessionController()
    @State private var photoController = PhotoCaptureController()
    @State private var faceService = FaceLandmarkService()

    // 面板状态（互斥：同时只能开一个）
    @State private var activePanel: ActivePanel? = nil
    @State private var sharedRenderer: BeautyRenderer?
    @State private var zoomPreset: CGFloat = 1.0

    enum ActivePanel: Equatable { case beauty, filter }

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            if authorized {
                cameraLayout
            } else {
                permissionView
            }
        }
        .task {
            authorized = await controller.checkAuthorizationAndStart()
            DebugOverlayState.shared.set("camera.auth", authorized ? "granted" : "denied")
            if authorized {
                photoController.attach(to: controller.session)
                controller.onFrame = { [faceService] pixelBuffer, ts in
                    faceService.enqueue(pixelBuffer: pixelBuffer, timestampMs: ts)
                }
                controller.faceServiceIsFrontCamera = { [faceService] isFront in
                    faceService.isFrontCamera = isFront
                }
            }
        }
        .onDisappear { controller.stop() }
        .accessibilityIdentifier("camera_preview")
    }

    // MARK: - 权限页

    private var permissionView: some View {
        VStack(spacing: 12) {
            Text(String(localized: "Camera Permission Required"))
            Button(String(localized: "Open Settings")) {
                if let url = URL(string: UIApplication.openSettingsURLString) {
                    UIApplication.shared.open(url)
                }
            }
        }
        .accessibilityIdentifier("camera_denied")
    }

    // MARK: - 相机主布局（对标 Android CameraPreviewContent）

    private var cameraLayout: some View {
        ZStack {
            // 预览 + 手势层
            MetalViewRepresentable(controller: controller, params: container.beautyParams,
                                   faceService: faceService, onRendererReady: { renderer in
                sharedRenderer = renderer
            })
            .overlay {
                CameraGesturesView(controller: controller)
                    .allowsHitTesting(activePanel == nil) // 面板打开时禁用手势
                    .contentShape(Rectangle())
                    .onTapGesture {
                        if activePanel != nil { withAnimation { activePanel = nil } }
                    }
            }

            // 顶部控件层
            VStack {
                topControls
                Spacer()
            }

            // 底部控件 + 面板
            VStack(spacing: 0) {
                Spacer()

                // 弹出面板
                if let panel = activePanel {
                    switch panel {
                    case .beauty:
                        BeautyPanelView(params: $container.beautyParams)
                            .padding(.horizontal, 24)
                            .transition(.move(edge: .bottom).combined(with: .opacity))
                    case .filter:
                        VStack {
                            FilterSelectorView(selectedFilter: $container.beautyParams.colorFilter)
                                .background(.ultraThinMaterial)
                                .clipShape(RoundedRectangle(cornerRadius: 24))
                            }
                            .padding(.horizontal, 24)
                            .transition(.move(edge: .bottom).combined(with: .opacity))
                    }
                }

                // 底部三行控件（对标 Android CameraControls）
                bottomControls
            }
        }
    }

    // MARK: - 顶部控件（对标 Android CameraLeftControls + CameraRightControls + dump）

    private var topControls: some View {
        HStack(alignment: .top, spacing: 0) {
            // 左侧：返回按钮（dump: bounds 52,52 156×156 = x16,y16 47×47dp）
            VStack(spacing: 8) {
                CircleIconButton(systemName: "chevron.left") {
                    // 退出相机（留空——Pager 横滑返回 Gallery）
                }
            }
            .padding(.leading, 16)
            .padding(.top, 16)

            Spacer()

            // 右侧功能列（dump: 6 按钮，分组配对，组内 33px≈10dp，组间 92px≈28dp）
            VStack(spacing: 0) {
                // 美颜入口（组1）
                CircleIconButton(
                    systemName: "wand.and.stars",
                    isActive: activePanel == .beauty,
                    hasIndicator: container.beautyParams.whitening > 0 || container.beautyParams.smoothing > 0
                ) {
                    withAnimation { activePanel = activePanel == .beauty ? nil : .beauty }
                }

                Spacer().frame(height: 28) // 组间间距（92px≈28dp）

                // 比例（组2）
                CircleIconButton(systemName: "aspectratio") { }
                Spacer().frame(height: 10) // 组内间距（33px≈10dp）
                // 网格（组2）
                CircleIconButton(systemName: "square.grid.3x3") { }

                Spacer().frame(height: 28) // 组间

                // 场景（组3）
                CircleIconButton(systemName: "mountain.2") { }
                Spacer().frame(height: 10) // 组内
                // 滤镜入口（组3）
                CircleIconButton(
                    systemName: "circle.lefthalf.filled",
                    isActive: activePanel == .filter
                ) {
                    withAnimation { activePanel = activePanel == .filter ? nil : .filter }
                }

                Spacer().frame(height: 28) // 组间
                // ProMode（组4）
                CircleIconButton(systemName: "slider.horizontal.3") { }
            }
            .padding(.trailing, 16)
            .padding(.top, 16)
        }
    }

    // MARK: - 底部三行控件（对标 Android CameraControls）

    private var bottomControls: some View {
        VStack(spacing: 20) {
            // 变焦预设条（对标 CameraControls.kt:99-138；dump: 始终可见）
            ZoomPresetBar(zoomPreset: $zoomPreset, controller: controller)

            // 模式选择器（对标 CameraControls.kt:161-193）
            // PHOTO only（VIDEO/DOCUMENT 留 Phase 6）
            HStack(spacing: 24) {
                Text("PHOTO")
                    .font(.system(size: 13, weight: .bold))
                    .foregroundColor(.accentColor)
            }
            .padding(.vertical, 4)

            // 缩略图 | 快门 | 翻转
            HStack {
                // 相册缩略图入口（对标 CameraControls.kt:231-254）
                Circle()
                    .fill(Color(red: 0.25, green: 0.25, blue: 0.25))
                    .frame(width: 48, height: 48)
                    .overlay(Image(systemName: "photo.fill").foregroundColor(.white.opacity(0.5)))
                    .accessibilityIdentifier("camera_gallery_thumb")

                Spacer()

                // 快门（76pt，对标 CameraControls.kt:196-228）
                ShutterButton {
                    guard let renderer = sharedRenderer else { return }
                    let flow = CaptureFlow(photoController: photoController, renderer: renderer)
                    flow.captureAndSave()
                }

                Spacer()

                // 翻转摄像头（对标 CameraControls.kt:257-267）
                Circle()
                    .fill(Color.white.opacity(0.2))
                    .frame(width: 48, height: 48)
                    .overlay(Image(systemName: "camera.rotate").foregroundColor(.white))
                    .accessibilityIdentifier("camera_flip")
                    .onTapGesture { controller.flipCamera() }
            }
            .padding(.horizontal, 40)
        }
        .padding(.bottom, 33) // dump: 底排距底 110px ÷ 3.33 ≈ 33dp
    }
}

// MARK: - 圆形图标按钮（对标 Android FilledIconButton 48dp）

struct CircleIconButton: View {
    let systemName: String
    var isActive: Bool = false
    var hasIndicator: Bool = false
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            ZStack(alignment: .topTrailing) {
                Circle()
                    .fill(isActive ? Color.accentColor : Color.black.opacity(0.5))
                    .frame(width: 48, height: 48)
                    .overlay(
                        Image(systemName: systemName)
                            .font(.system(size: 22))
                            .foregroundColor(isActive ? .black : .white)
                    )
                if hasIndicator {
                    Circle()
                        .fill(Color.accentColor)
                        .frame(width: 8, height: 8)
                        .overlay(Circle().stroke(Color.black.opacity(0.6), lineWidth: 1))
                        .padding(.top, 4)
                        .padding(.trailing, 4)
                }
            }
        }
    }
}

// MARK: - 变焦预设条（对标 CameraControls.kt:99-138）

struct ZoomPresetBar: View {
    @Binding var zoomPreset: CGFloat
    let controller: CaptureSessionController

    var body: some View {
        HStack(spacing: 12) {
            ForEach([(0.6, "0.6x"), (1.0, "1x"), (2.0, "2x"), (3.2, "3.2x")], id: \.0) { val, label in
                Button {
                    zoomPreset = val
                    controller.setZoom(val)
                } label: {
                    Text(label)
                        .font(.system(size: 10, weight: .bold))
                        .foregroundColor(abs(zoomPreset - val) < 0.01 ? .black : .white)
                        .frame(width: 32, height: 32)
                        .background(
                            Circle()
                                .fill(abs(zoomPreset - val) < 0.01 ? Color.white : Color.clear)
                        )
                }
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 6)
        .background(Capsule().fill(Color.black.opacity(0.4)))
        .accessibilityIdentifier("camera_zoom_bar")
    }
}

// MARK: - MetalView bridge

private struct MetalViewRepresentable: UIViewRepresentable {
    let controller: CaptureSessionController
    let params: BeautyRenderer.Params
    let faceService: FaceLandmarkService
    let onRendererReady: (BeautyRenderer) -> Void

    func makeUIView(context: Context) -> MTKView {
        let view = MTKView()
        view.device = MTLCreateSystemDefaultDevice()
        view.delegate = context.coordinator
        view.enableSetNeedsDisplay = false
        view.isPaused = false
        view.colorPixelFormat = .bgra8Unorm
        if let device = view.device {
            let renderer = BeautyRenderer(device: device)
            context.coordinator.renderer = renderer
            if let renderer { onRendererReady(renderer) }
        }
        context.coordinator.controller = controller
        context.coordinator.faceService = faceService
        context.coordinator.renderer?.params = params
        return view
    }

    func updateUIView(_ uiView: MTKView, context: Context) {
        context.coordinator.renderer?.params = params
    }

    func makeCoordinator() -> Coordinator { Coordinator() }

    final class Coordinator: NSObject, MTKViewDelegate {
        var renderer: BeautyRenderer?
        var controller: CaptureSessionController?
        var faceService: FaceLandmarkService?
        private var frames = 0
        private var lastFpsTick = Date()

        func mtkView(_ view: MTKView, drawableSizeWillChange size: CGSize) {}

        func draw(in view: MTKView) {
            guard let pb = controller?.readBuffer() else { return }
            if let fs = faceService {
                let tsMs = Int(Date().timeIntervalSince1970 * 1000)
                if let points = fs.latestWithinWindow(currentTimestampMs: tsMs) {
                    renderer?.updateFacePoints(points, hasFace: true)
                } else {
                    renderer?.updateFacePoints([], hasFace: false)
                }
            }
            renderer?.draw(pixelBuffer: pb, in: view)
            frames += 1
            if Date().timeIntervalSince(lastFpsTick) >= 1.0 {
                DebugOverlayState.shared.set("camera.fps", "\(frames)")
                frames = 0
                lastFpsTick = Date()
            }
        }
    }
}
