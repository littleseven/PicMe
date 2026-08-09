import SwiftUI
import MetalKit

/// 相机主页面（对标 Android CameraPreviewContent.kt）
struct CameraPreviewView: View {
    @EnvironmentObject private var container: AppContainer
    @State private var authorized = false
    @State private var controller = CaptureSessionController()
    @State private var photoController = PhotoCaptureController()
    @State private var faceService = FaceLandmarkService()

    @State private var activePanel: ActivePanel? = nil
    @State private var sharedRenderer: BeautyRenderer?
    @State private var zoomPreset: CGFloat = 1.0
    @State private var selectedMode: CameraMode = .photo

    enum ActivePanel: Equatable { case beauty, filter }
    enum CameraMode: String, CaseIterable { case video = "视频", photo = "照片", document = "文档" }

    var body: some View {
        ZStack {
            // 预览层（UILaunchScreen 修复后 TabView 不再 letterbox）
            MetalViewRepresentable(controller: controller, params: container.beautyParams,
                                   faceService: faceService, onRendererReady: { renderer in
                sharedRenderer = renderer
            })

            if !authorized {
                Color.black.ignoresSafeArea()
                permissionView
            } else {
                cameraOverlay
            }
        }
        .ignoresSafeArea(.all)
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

    // MARK: - 顶部控件（对标 Android dump 百分比布局）

    private var topControls: some View {
        GeometryReader { geo in
            HStack(alignment: .top, spacing: 0) {
                // 左列：返回（裸箭头无圆底）+ Refresh（重置相机状态，产品按钮）
                VStack(spacing: 8) {
                    Button { } label: {
                        Image(systemName: "chevron.left")
                            .font(.system(size: 20))
                            .foregroundColor(.white)
                            .frame(width: 48, height: 48)
                    }
                    CircleIconButton(systemName: "arrow.clockwise") { }
                }
                .padding(.leading, 16)
                .padding(.top, 16)

                Spacer()

                // 右列 6 按钮（Android 百分比：wand≈4% / ratio≈13% / grid≈20% / scene≈30% / filter≈37% / tune≈47%）
                VStack(spacing: 0) {
                    CircleIconButton(
                        systemName: "wand.and.stars",
                        isActive: activePanel == .beauty,
                        hasIndicator: container.beautyParams.whitening > 0 || container.beautyParams.smoothing > 0
                    ) {
                        withAnimation { activePanel = activePanel == .beauty ? nil : .beauty }
                    }

                    Spacer().frame(height: geo.size.height * 0.09)

                    CircleIconButton(systemName: "aspectratio") { }
                    Spacer().frame(height: geo.size.height * 0.07)
                    CircleIconButton(systemName: "square.grid.3x3") { }

                    Spacer().frame(height: geo.size.height * 0.10)

                    CircleIconButton(systemName: "mountain.2") { }
                    Spacer().frame(height: geo.size.height * 0.07)
                    CircleIconButton(
                        systemName: "circle.lefthalf.filled",
                        isActive: activePanel == .filter
                    ) {
                        withAnimation { activePanel = activePanel == .filter ? nil : .filter }
                    }

                    Spacer().frame(height: geo.size.height * 0.10)
                    CircleIconButton(systemName: "slider.horizontal.3") { }
                }
                .padding(.trailing, 16)
                .padding(.top, 16)
            }
        }
    }

    // MARK: - 底部三行控件

    private var bottomControls: some View {
        VStack(spacing: 20) {
            // 变焦条（纯文本行无 pill 底色）
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
                                Circle().fill(abs(zoomPreset - val) < 0.01 ? Color.white : Color.clear)
                            )
                    }
                }
            }
            .accessibilityIdentifier("camera_zoom_bar")

            // 模式选择器（选中=白 Bold / 未选=灰）
            HStack(spacing: 16) {
                ForEach(CameraMode.allCases, id: \.self) { mode in
                    Text(mode.rawValue)
                        .font(.system(size: 13, weight: selectedMode == mode ? .bold : .regular))
                        .foregroundColor(selectedMode == mode ? .white : .white.opacity(0.6))
                        .padding(.horizontal, 12)
                        .onTapGesture { selectedMode = mode }
                }
            }

            // 缩略图 | 快门 | 翻转
            HStack {
                Circle()
                    .fill(Color(red: 0.25, green: 0.25, blue: 0.25))
                    .frame(width: 48, height: 48)
                    .overlay(Image(systemName: "photo.fill").font(.system(size: 18)).foregroundColor(.white.opacity(0.5)))
                    .accessibilityIdentifier("camera_gallery_thumb")

                Spacer()

                ShutterButton {
                    guard let renderer = sharedRenderer else { return }
                    let flow = CaptureFlow(photoController: photoController, renderer: renderer)
                    flow.captureAndSave()
                }

                Spacer()

                Circle()
                    .fill(Color.white.opacity(0.2))
                    .frame(width: 48, height: 48)
                    .overlay(Image(systemName: "camera.rotate").font(.system(size: 18)).foregroundColor(.white))
                    .accessibilityIdentifier("camera_flip")
                    .onTapGesture { controller.flipCamera() }
            }
            .padding(.horizontal, 40)
        }
        .padding(.bottom, 33)
    }
}

// MARK: - 圆形图标按钮

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

// MARK: - MetalView bridge（简洁版，UILaunchScreen 修复后无需 hack）

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
        view.isOpaque = true
        view.backgroundColor = .black
        if let device = view.device {
            let renderer = BeautyRenderer(device: device)
            context.coordinator.renderer = renderer
            if let renderer {
                renderer.params = params
                onRendererReady(renderer)
            }
        }
        context.coordinator.controller = controller
        context.coordinator.faceService = faceService
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
