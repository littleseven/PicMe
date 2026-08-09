import SwiftUI
import MetalKit
import Photos

/// 相机主页面（对标 Android CameraPreviewContent.kt）
/// 🔴 预览全出血 edge-to-edge + 控件锚 safe area
struct CameraPreviewView: View {
    @EnvironmentObject private var container: AppContainer
    /// 相册入口回调（MainTabView 注入：切到相册页）
    var onGalleryTap: () -> Void = {}
    @State private var authorized = false
    @State private var controller = CaptureSessionController()
    @State private var photoController = PhotoCaptureController()
    @State private var faceService = FaceLandmarkService()

    @State private var activePanel: ActivePanel? = nil
    // 🔴 renderer 提到视图层直持：快门链路不再依赖 representable 回调往返（nil 则拍照静默失败）
    @State private var sharedRenderer: BeautyRenderer? = CameraPreviewView.makeRenderer()
    @State private var zoomPreset: CGFloat = 1.0
    @State private var selectedMode: CameraMode = .photo
    @State private var shutterFlash = false
    @State private var lastThumb: UIImage?

    enum ActivePanel: Equatable { case beauty, filter }
    enum CameraMode: String, CaseIterable { case video = "视频", photo = "照片", document = "文档" }

    /// 视图层直建 renderer（failable init，失败时快门报错而非静默）
    private static func makeRenderer() -> BeautyRenderer? {
        guard let device = MTLCreateSystemDefaultDevice() else { return nil }
        return BeautyRenderer(device: device)
    }

    var body: some View {
        GeometryReader { geo in
            ZStack {
                // 预览层：铺满全屏（全出血）
                // 🔴 camera_preview 标识只挂叶子视图：挂容器会沿子树传播、覆盖子孙自身标识符
                MetalViewRepresentable(controller: controller, renderer: sharedRenderer,
                                       params: container.beautyParams, faceService: faceService)
                .frame(width: geo.size.width, height: geo.size.height)
                .accessibilityIdentifier("camera_preview")

                if !authorized {
                    Color.black
                    permissionView
                } else {
                    // 控件层：锚 safe area（通过 padding 避让）
                    cameraOverlay(screenHeight: geo.size.height, safeTop: geo.safeAreaInsets.top)
                }

                // 快门白闪反馈（对标 Android 拍照闪屏，确认点击已注册）
                Color.white
                    .opacity(shutterFlash ? 0.9 : 0)
                    .ignoresSafeArea()
                    .allowsHitTesting(false)
                    .animation(.easeOut(duration: 0.25), value: shutterFlash)
            }
        }
        .ignoresSafeArea(.all) // 🔴 全出血：整个 GeometryReader 忽略 safe area
        .task {
            authorized = await controller.checkAuthorizationAndStart()
            DebugOverlayState.shared.set("camera.auth", authorized ? "granted" : "denied")
            if authorized {
                // 🔴 串行挂载：走 capture 队列与 session 配置块保序（主线程直挂会和配置竞态）
                controller.attachOutput(photoController.photoOutput)
                controller.onFrame = { [faceService] pixelBuffer, ts in
                    faceService.enqueue(pixelBuffer: pixelBuffer, timestampMs: ts)
                }
                controller.faceServiceIsFrontCamera = { [faceService] isFront in
                    faceService.isFrontCamera = isFront
                }
            }
            refreshLatestThumb()
        }
        .onDisappear { controller.stop() }
    }

    // MARK: - 权限页

    /// 拉取相册最新一张照片缩略图（48pt@3x），显示在左下相册入口上
    private func refreshLatestThumb() {
        let opts = PHFetchOptions()
        opts.sortDescriptors = [NSSortDescriptor(key: "creationDate", ascending: false)]
        opts.fetchLimit = 1
        guard let asset = PHAsset.fetchAssets(with: .image, options: opts).firstObject else { return }
        let req = PHImageRequestOptions()
        req.deliveryMode = .highQualityFormat
        req.isNetworkAccessAllowed = false
        PHImageManager.default().requestImage(
            for: asset, targetSize: CGSize(width: 144, height: 144),
            contentMode: .aspectFill, options: req
        ) { image, _ in
            guard let image else { return }
            DispatchQueue.main.async { self.lastThumb = image }
        }
    }

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
                    .overlay(
                        Group {
                            if let lastThumb {
                                Image(uiImage: lastThumb)
                                    .resizable()
                                    .scaledToFill()
                            } else {
                                MatIcon(name: "photo.fill", size: 18)
                                    .foregroundColor(.white.opacity(0.5))
                            }
                        }
                    )
                    .clipShape(Circle())
                    .accessibilityIdentifier("camera_gallery_thumb")
                    .contentShape(Circle())
                    .onTapGesture { onGalleryTap() }

                Spacer()

                ShutterButton {
                    // 白闪反馈（确认点击注册，对标 Android 拍照闪屏）
                    shutterFlash = true
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.12) { shutterFlash = false }
                    guard let renderer = sharedRenderer else {
                        print("[PoLang] shutter.FAIL: sharedRenderer nil")
                        DebugOverlayState.shared.set("camera.shutter", "error: renderer nil")
                        return
                    }
                    let flow = CaptureFlow(photoController: photoController, renderer: renderer)
                    flow.onSaved = { refreshLatestThumb() }
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
    let renderer: BeautyRenderer?
    let params: BeautyRenderer.Params
    let faceService: FaceLandmarkService

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
        // renderer 由视图层注入（快门与预览同一实例）；兜底再自建
        if let renderer {
            renderer.params = params
            context.coordinator.renderer = renderer
        } else if let device = view.device, let fallback = BeautyRenderer(device: device) {
            fallback.params = params
            context.coordinator.renderer = fallback
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
