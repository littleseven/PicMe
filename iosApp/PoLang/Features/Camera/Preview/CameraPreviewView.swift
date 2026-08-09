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
    @State private var selectedMode: CameraMode = .photo

    enum ActivePanel: Equatable { case beauty, filter }
    enum CameraMode: String, CaseIterable { case video = "视频", photo = "照片", document = "文档" }

    var body: some View {
        // 🔴 P0.1: 预览作为 .background 全屏铺设（绕过 TabView safe area 限制）
        // 控件作为前景内容，锚 safe area
        cameraOverlay
            .background(
                MetalViewRepresentableWrapper(controller: controller, params: container.beautyParams,
                                              faceService: faceService, onRendererReady: { renderer in
                    sharedRenderer = renderer
                })
                .ignoresSafeArea(.all)
            )
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

    // MARK: - 叠加层（手势 + 顶部控件 + 底部控件 + 面板）

    @ViewBuilder
    private var cameraOverlay: some View {
        ZStack {
            if !authorized {
                permissionView
            } else {
                // 手势层
                CameraGesturesView(controller: controller)
                    .allowsHitTesting(activePanel == nil)
                    .contentShape(Rectangle())
                    .onTapGesture {
                        if activePanel != nil { withAnimation { activePanel = nil } }
                    }

                // 顶部控件
                VStack {
                    topControls
                    Spacer()
                }

                // 底部控件 + 面板
                VStack(spacing: 0) {
                    Spacer()

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

                    bottomControls
                }
            }
        }
    }

    // MARK: - 顶部控件（对标 Android dump camera_idle.txt）

    private var topControls: some View {
        HStack(alignment: .top, spacing: 0) {
            // 左侧：返回按钮（dump: bounds 52,52 156×156 = 16dp,16dp 47×47dp）
            VStack(spacing: 8) {
                CircleIconButton(systemName: "chevron.left") { }
            }
            .padding(.leading, 16)
            .padding(.top, 16)

            Spacer()

            // 右侧功能列（dump: 6 按钮，y 从 52px→1330px，跨度约 48% 屏高）
            // 分组：beauty / ratio+grid / scene+filter / promode
            VStack(spacing: 0) {
                CircleIconButton(
                    systemName: "wand.and.stars",
                    isActive: activePanel == .beauty,
                    hasIndicator: container.beautyParams.whitening > 0 || container.beautyParams.smoothing > 0
                ) {
                    withAnimation { activePanel = activePanel == .beauty ? nil : .beauty }
                }

                Spacer().frame(height: 24) // 组间（dump 92px÷3.33≈28dp，扣除容器内间距后 ~24）

                CircleIconButton(systemName: "aspectratio") { }
                Spacer().frame(height: 10) // 组内（dump 33px÷3.33≈10dp）
                CircleIconButton(systemName: "square.grid.3x3") { }

                Spacer().frame(height: 24)

                CircleIconButton(systemName: "mountain.2") { }
                Spacer().frame(height: 10)
                CircleIconButton(
                    systemName: "circle.lefthalf.filled",
                    isActive: activePanel == .filter
                ) {
                    withAnimation { activePanel = activePanel == .filter ? nil : .filter }
                }

                Spacer().frame(height: 24)
                CircleIconButton(systemName: "slider.horizontal.3") { }
            }
            .padding(.trailing, 16)
            .padding(.top, 16)
        }
    }

    // MARK: - 底部三行控件（对标 Android dump）

    private var bottomControls: some View {
        VStack(spacing: 20) {
            // 变焦预设条（对标 dump: 始终可见；纯文本行无 pill 底色）
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
            .accessibilityIdentifier("camera_zoom_bar")

            // 模式选择器（对标 dump: 视频/照片/文档 三项居中）
            HStack(spacing: 16) {
                ForEach(CameraMode.allCases, id: \.self) { mode in
                    Text(mode.rawValue)
                        .font(.system(size: 13, weight: selectedMode == mode ? .bold : .regular))
                        .foregroundColor(selectedMode == mode ? .accentColor : .white.opacity(0.6))
                        .padding(.horizontal, 12)
                        .onTapGesture { selectedMode = mode }
                }
            }

            // 缩略图 | 快门 | 翻转（对标 dump: 三件套，各占其位不重叠）
            HStack {
                // 相册缩略图入口
                Circle()
                    .fill(Color(red: 0.25, green: 0.25, blue: 0.25))
                    .frame(width: 48, height: 48)
                    .overlay(Image(systemName: "photo.fill")
                        .font(.system(size: 18))
                        .foregroundColor(.white.opacity(0.5)))
                    .accessibilityIdentifier("camera_gallery_thumb")

                Spacer()

                // 快门（62pt，对标 dump 207px÷3.33）
                ShutterButton {
                    guard let renderer = sharedRenderer else { return }
                    let flow = CaptureFlow(photoController: photoController, renderer: renderer)
                    flow.captureAndSave()
                }

                Spacer()

                // 翻转摄像头（对标 dump: 底排右侧，不与右列 ProMode 重叠）
                Circle()
                    .fill(Color.white.opacity(0.2))
                    .frame(width: 48, height: 48)
                    .overlay(Image(systemName: "camera.rotate")
                        .font(.system(size: 18))
                        .foregroundColor(.white))
                    .accessibilityIdentifier("camera_flip")
                    .onTapGesture { controller.flipCamera() }
            }
            .padding(.horizontal, 40)
        }
        .padding(.bottom, 33) // dump: 底排距底 110px÷3.33 ≈ 33dp
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
                            .font(.system(size: 18))
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

// MARK: - MetalView wrapper（UIViewControllerRepresentable 强制 edge-to-edge）

/// 🔴 P0.1: 用 UIViewControllerRepresentable 而非 UIViewRepresentable
/// 在 viewWillLayoutSubviews 中强制 MTKView frame = viewController.view.bounds
/// 确保 MTKView 延伸到状态栏/Home Indicator 下方（真沉浸式）
struct MetalViewRepresentableWrapper: UIViewControllerRepresentable {
    let controller: CaptureSessionController
    let params: BeautyRenderer.Params
    let faceService: FaceLandmarkService
    let onRendererReady: (BeautyRenderer) -> Void

    func makeUIViewController(context: Context) -> MetalViewController {
        let vc = MetalViewController()
        vc.coordinator = makeCoordinator()
        vc.controller = controller
        vc.faceService = faceService
        vc.onRendererReady = onRendererReady
        vc.coordinator.renderer?.params = params
        return vc
    }

    func updateUIViewController(_ uiViewController: MetalViewController, context: Context) {
        uiViewController.coordinator.renderer?.params = params
    }

    func makeCoordinator() -> MetalCoordinator { MetalCoordinator() }

    final class MetalViewController: UIViewController, MTKViewDelegate {
        var mtkView: MTKView!
        var coordinator: MetalCoordinator!
        var controller: CaptureSessionController!
        var faceService: FaceLandmarkService!
        var onRendererReady: ((BeautyRenderer) -> Void)?
        private var frames = 0
        private var lastFpsTick = Date()

        override func viewDidLoad() {
            super.viewDidLoad()
            view.backgroundColor = .black
            // 🔴 P0.1: 强制 view 延伸到全屏（绕过 SwiftUI TabView safe area 限制）
            view.insetsLayoutMarginsFromSafeArea = false
            mtkView = MTKView()
            mtkView.device = MTLCreateSystemDefaultDevice()
            mtkView.delegate = self
            mtkView.enableSetNeedsDisplay = false
            mtkView.isPaused = false
            mtkView.colorPixelFormat = .bgra8Unorm
            mtkView.isOpaque = true
            mtkView.backgroundColor = .black
            mtkView.translatesAutoresizingMaskIntoConstraints = false
            view.addSubview(mtkView)
            NSLayoutConstraint.activate([
                mtkView.topAnchor.constraint(equalTo: view.topAnchor),
                mtkView.bottomAnchor.constraint(equalTo: view.bottomAnchor),
                mtkView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
                mtkView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            ])
            if let device = mtkView.device {
                let renderer = BeautyRenderer(device: device)
                coordinator.renderer = renderer
                if let renderer { onRendererReady?(renderer) }
            }
            coordinator.controller = controller
            coordinator.faceService = faceService
        }

        override func viewDidLayoutSubviews() {
            super.viewDidLayoutSubviews()
            // 🔴 P0.1: 强制 MTKView frame = 窗口全屏 bounds
            if let window = view.window {
                mtkView.frame = window.bounds
            }
        }

        func mtkView(_ view: MTKView, drawableSizeWillChange size: CGSize) {}

        func draw(in view: MTKView) {
            guard let pb = controller?.readBuffer() else { return }
            if let fs = faceService {
                let tsMs = Int(Date().timeIntervalSince1970 * 1000)
                if let points = fs.latestWithinWindow(currentTimestampMs: tsMs) {
                    coordinator.renderer?.updateFacePoints(points, hasFace: true)
                } else {
                    coordinator.renderer?.updateFacePoints([], hasFace: false)
                }
            }
            coordinator.renderer?.draw(pixelBuffer: pb, in: view)
            frames += 1
            if Date().timeIntervalSince(lastFpsTick) >= 1.0 {
                DebugOverlayState.shared.set("camera.fps", "\(frames)")
                frames = 0
                lastFpsTick = Date()
            }
        }
    }
}

// MARK: - MetalView Coordinator（共享）

final class MetalCoordinator: NSObject, MTKViewDelegate {
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
