import SwiftUI
import MetalKit

/// 相机主页面（对标 Android CameraPreviewContent.kt）
/// 🔴 预览全出血 edge-to-edge + 控件锚 safe area
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
        GeometryReader { geo in
            ZStack {
                // 预览层：铺满全屏（全出血）
                MetalViewRepresentable(controller: controller, params: container.beautyParams,
                                       faceService: faceService, onRendererReady: { renderer in
                    sharedRenderer = renderer
                })
                .frame(width: geo.size.width, height: geo.size.height)

                if !authorized {
                    Color.black
                    permissionView
                } else {
                    // 控件层：锚 safe area（通过 padding 避让）
                    cameraOverlay(screenHeight: geo.size.height, safeTop: geo.safeAreaInsets.top)
                }
            }
        }
        .ignoresSafeArea(.all) // 🔴 全出血：整个 GeometryReader 忽略 safe area
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

    // MARK: - 控件层（锚 safe area，不跟随预览延伸）

    private func cameraOverlay(screenHeight: CGFloat, safeTop: CGFloat) -> some View {
        ZStack {
            // 手势层
            CameraGesturesView(controller: controller)
                .allowsHitTesting(activePanel == nil)
                .contentShape(Rectangle())
                .onTapGesture {
                    if activePanel != nil { withAnimation { activePanel = nil } }
                }

            // 顶部控件
            topControls(screenHeight: screenHeight, safeTop: safeTop)

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

    // MARK: - 顶部控件（对标 Android dump y% 布局）

    /// 右列按钮中心 y 占屏高百分比（dump 实测）:
    /// wand 4.87% / ratio 14.16% / grid 21.24% / scene 30.52% / filter 37.60% / tune 46.89%
    private func topControls(screenHeight: CGFloat, safeTop: CGFloat) -> some View {
        // 按钮中心 y 绝对坐标 = screenHeight * 百分比
        // 按钮容器 48pt，中心到底/顶各 24pt
        let buttonSize: CGFloat = 48
        let halfButton = buttonSize / 2

        let yWand = screenHeight * 0.0487
        let yRatio = screenHeight * 0.1416
        let yGrid = screenHeight * 0.2124
        let yScene = screenHeight * 0.3052
        let yFilter = screenHeight * 0.3760
        let yTune = screenHeight * 0.4689

        return ZStack {
            // 左列：返回（裸箭头）+ Refresh（裸图标，无圆底，对标 Android CameraControlButtons.kt 左列）
            VStack(spacing: 8) {
                Button { } label: {
                    MatIcon(name: "chevron.left", size: 20)
                        .foregroundColor(.white)
                        .frame(width: buttonSize, height: buttonSize)
                }
                // Refresh: 裸图标（Android 左列是裸图标无圆底容器）
                Button { } label: {
                    MatIcon(name: "arrow.clockwise", size: 18)
                        .foregroundColor(.white)
                        .frame(width: buttonSize, height: buttonSize)
                }
            }
            .position(x: 16 + halfButton, y: safeTop + halfButton + 8) // 距顶 safeTop + 8pt

            // 右列：6 按钮按 dump y% 绝对定位
            rightColumnButton("wand.and.stars", y: yWand, isActive: activePanel == .beauty,
                              hasIndicator: container.beautyParams.whitening > 0 || container.beautyParams.smoothing > 0) {
                withAnimation { activePanel = activePanel == .beauty ? nil : .beauty }
            }
            rightColumnButton("aspectratio", y: yRatio) { }
            rightColumnButton("square.grid.3x3", y: yGrid) { }
            rightColumnButton("mountain.2", y: yScene) { }
            rightColumnButton("circle.lefthalf.filled", y: yFilter, isActive: activePanel == .filter) {
                withAnimation { activePanel = activePanel == .filter ? nil : .filter }
            }
            rightColumnButton("slider.horizontal.3", y: yTune) { }
        }
        .frame(width: UIScreen.main.bounds.width, height: screenHeight, alignment: .topLeading)
    }

    /// 右列单个按钮绝对定位
    @ViewBuilder
    private func rightColumnButton(_ systemName: String, y: CGFloat,
                                   isActive: Bool = false, hasIndicator: Bool = false,
                                   action: @escaping () -> Void) -> some View {
        CircleIconButton(systemName: systemName, isActive: isActive, hasIndicator: hasIndicator, action: action)
            .position(x: UIScreen.main.bounds.width - 16 - 24, y: y) // 右缘 16pt + 半按钮 24pt
    }

    // MARK: - 底部三行控件

    private var bottomControls: some View {
        VStack(spacing: 20) {
            // 变焦条（纯文本行无 pill）
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

            // 模式选择器（白 Bold / 灰）
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
                    .overlay(MatIcon(name: "photo.fill", size: 18).foregroundColor(.white.opacity(0.5)))
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
                    .overlay(MatIcon(name: "camera.rotate", size: 18).foregroundColor(.white))
                    .accessibilityIdentifier("camera_flip")
                    .onTapGesture { controller.flipCamera() }
            }
            .padding(.horizontal, 40)
        }
        .padding(.bottom, 33)
    }
}

// MARK: - 圆形图标按钮（右列容器，对标 Android FilledIconButton 48dp）

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
                        MatIcon(name: systemName, size: 18)
                            .foregroundColor(isActive ? .black : .white)
                    )
                if hasIndicator {
                    Circle()
                        .fill(Color(red: 0.0, green: 0.9, blue: 0.4)) // 绿色 badge
                        .frame(width: 8, height: 8)
                        .overlay(Circle().stroke(Color.black.opacity(0.6), lineWidth: 1))
                        .padding(.top, 4)
                        .padding(.trailing, 4)
                }
            }
        }
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
        view.isOpaque = true
        view.backgroundColor = .black
        view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
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
