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
    @State private var faceRouter = FaceEngineRouter()
    /// 引擎开关镜像（值类型 @State → 触发 toggle 视图重绘；同步到 faceRouter.useMnn）
    /// **来源优先级**：自动化验收启动参数(`-mnnEngine` / `-useMediaPipe`) > 设置页 `camera_use_mnn`(默认 true)。
    /// iOS 端 MediaPipe `face_landmarker.task` 未内置（走模型中心下载，Phase 6 才做）→ MediaPipe 无检测，
    /// 故默认 MNN；MNN 两阶段 RetinaFace det_500m → 2d106 已真机 live 验证可用。
    /// 运行时仍可点顶部 MNN/MediaPipe 胶囊切换，或在 设置→相机与美颜 切换默认引擎。
    /// 瘦脸强度走 设置→相机与美颜（持久化）或 `-slim <Float>`（验收覆盖，range -50..50）。
    @State private var useMnnEngine = Self.resolveUseMnn()
    /// 设置页引擎默认值镜像（@AppStorage 持久化；onChange 实时同步到 faceRouter，设置页改即生效）
    @AppStorage("camera_use_mnn") private var settingsUseMnn = true

    /// 引擎默认值解析：启动参数覆盖 > 设置页持久值 > 兜底 MNN(true)
    private static func resolveUseMnn() -> Bool {
        if parseLaunchFlag("-useMediaPipe") { return false }
        if parseLaunchFlag("-mnnEngine") { return true }
        return (UserDefaults.standard.object(forKey: "camera_use_mnn") as? Bool) ?? true
    }

    /// 关键点/人脸框 overlay：启动参数锁定开；否则跟随设置页持久值。
    private static func resolveShowLandmarks() -> Bool {
        if parseLaunchFlag("-showLandmarks") { return true }
        return (UserDefaults.standard.object(forKey: "camera_show_landmarks") as? Bool) ?? false
    }

    /// 启动参数解析（与 MainTabView -startPage 同模式；仅自动化验收用，不影响产品默认）。
    private static func parseLaunchFlag(_ key: String) -> Bool {
        ProcessInfo.processInfo.arguments.contains(key)
    }
    private static func parseLaunchFloat(_ key: String) -> Float? {
        let args = ProcessInfo.processInfo.arguments
        guard let i = args.firstIndex(of: key), args.count > i + 1,
              let v = Float(args[i + 1]) else { return nil }
        return v
    }

    /// 🔴 逐点关键点调试 overlay（对标 Android FaceDebugOverlayBigBeauty）。
    /// `-showLandmarks` 开启：把 BeautyRenderer 消费的 106 点 + 9 对瘦脸/2 对大眼控制点画到预览，
    /// 肉眼裁决「形变区域不对/偏转」= 点云错位还是 warp 感知问题。
    @StateObject private var landmarkStore = LandmarkOverlayStore()
    /// 🔴 关键点/人脸框 overlay 开关：启动参数 `-showLandmarks` 锁定开（自动化验收）；
    /// 否则跟随 设置→相机与美颜 的 `camera_show_landmarks` 开关（对标 Android face debug overlay）。
    @State private var showLandmarks = Self.resolveShowLandmarks()
    @AppStorage("camera_show_landmarks") private var settingsShowLandmarks = false

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
                                       params: container.beautyParams, faceRouter: faceRouter,
                                       landmarkStore: landmarkStore)
                .frame(width: geo.size.width, height: geo.size.height)
                .accessibilityIdentifier("camera_preview")

                // 🔴 逐点关键点 overlay（-showLandmarks）：铺在预览之上、控件之下
                if showLandmarks {
                    LandmarkDebugOverlay(store: landmarkStore)
                        .frame(width: geo.size.width, height: geo.size.height)
                }

                if !authorized {
                    Color.black
                    permissionView
                } else {
                    // 控件层：锚安全区（🔴 根 GeometryReader 忽略了 safe area，
                    // geo.safeAreaInsets 恒为 0，必须用 UIKit 读真实安全区）
                    cameraOverlay(screenHeight: geo.size.height, safeTop: realSafeTop)
                        // 引擎切换胶囊同样避让刘海（safeTop + 4）
                        .overlay(alignment: .top) { engineToggle.padding(.top, realSafeTop + 4) }
                }

                // 快门黑闪反馈（对标 Android CameraScreen.kt:1517-1523 拍照黑场闪屏）
                Color.black
                    .opacity(shutterFlash ? ShutterTokens.flashAlpha : 0)
                    .ignoresSafeArea()
                    .allowsHitTesting(false)
                    .animation(.easeOut(duration: ShutterTokens.flashFadeMs / 1000), value: shutterFlash)
            }
        }
        .ignoresSafeArea(.all) // 🔴 全出血：整个 GeometryReader 忽略 safe area
        .task {
            // MNN 端侧推理离线自检（-mnnSelfTest 时跑；写 Documents/mnn-verify.txt 供验收拉取）
            MnnSelfTest.runIfRequested()
            authorized = await controller.checkAuthorizationAndStart()
            DebugOverlayState.shared.set("camera.auth", authorized ? "granted" : "denied")
            // Debug 叠加层：设置页开关控制（默认启用，验收期直接看遥测）
            DebugOverlayState.shared.isEnabled =
                (UserDefaults.standard.object(forKey: "camera_debug_overlay") as? Bool) ?? true
            // 引擎：启动参数 > 设置页；自动化验收用 -mnnEngine / -useMediaPipe 覆盖
            faceRouter.setUseMnn(useMnnEngine)
            // 瘦脸/大眼/形变强度：验收 -slim 覆盖；否则用设置页持久值（beauty_slim_debug 等）
            if let slim = Self.parseLaunchFloat("-slim") {
                container.beautyParams.slimFace = slim
            } else if let savedSlim = UserDefaults.standard.object(forKey: "beauty_slim_debug") as? Float {
                container.beautyParams.slimFace = savedSlim
            }
            if let savedBigEyes = UserDefaults.standard.object(forKey: "beauty_bigeyes_debug") as? Float {
                container.beautyParams.bigEyes = savedBigEyes
            }
            if let savedStrength = UserDefaults.standard.object(forKey: "beauty_warp_strength") as? Float {
                container.beautyParams.warpStrength = savedStrength
            }
            // 验收覆盖：-warpStrength <Float> 强制形变倍率（A/B 排查强度；默认 1.0）
            if let ws = Self.parseLaunchFloat("-warpStrength") {
                container.beautyParams.warpStrength = ws
            }
            DebugOverlayState.shared.set("face.engine.active", faceRouter.activeLabel)
            print("[PoLang] launch.args: mnnEngine=\(useMnnEngine) slim=\(Self.parseLaunchFloat("-slim") ?? -1) " +
                  "persistedSlim=\(UserDefaults.standard.object(forKey: "beauty_slim_debug") as? Float ?? -1) " +
                  "warpStrength=\(UserDefaults.standard.object(forKey: "beauty_warp_strength") as? Float ?? 1)")
            if authorized {
                // 🔴 串行挂载：走 capture 队列与 session 配置块保序（主线程直挂会和配置竞态）
                controller.attachOutput(photoController.photoOutput)
                controller.onFrame = { [faceRouter] pixelBuffer, ts in
                    faceRouter.enqueue(pixelBuffer: pixelBuffer, timestampMs: ts)
                }
                controller.faceServiceIsFrontCamera = { [faceRouter] isFront in
                    faceRouter.setFrontCamera(isFront)
                }
            }
            refreshLatestThumb()
        }
        .onChange(of: settingsUseMnn) { v in
            // 设置页改了默认引擎：实时同步（启动参数锁定引擎时不覆盖，保留验收确定性）
            guard !Self.parseLaunchFlag("-mnnEngine"), !Self.parseLaunchFlag("-useMediaPipe") else { return }
            useMnnEngine = v
            faceRouter.setUseMnn(v)
            print("[PoLang] face.engine (settings) → \(faceRouter.activeLabel)")
        }
        .onChange(of: settingsShowLandmarks) { v in
            // 关键点 overlay 实时同步（启动参数锁定开时不覆盖）
            guard !Self.parseLaunchFlag("-showLandmarks") else { return }
            showLandmarks = v
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

    // MARK: - 人脸引擎切换（MediaPipe 默认 / MNN 双引擎可切换）

    /// 顶部胶囊：点击切换人脸检测引擎。亮色 = 当前引擎。
    ///（top padding 由调用方按 safeTop 避让刘海，本视图不内嵌）
    private var engineToggle: some View {
        Button {
            useMnnEngine.toggle()
            faceRouter.setUseMnn(useMnnEngine)
            print("[PoLang] face.engine switch → \(faceRouter.activeLabel)")
        } label: {
            HStack(spacing: 5) {
                Image(systemName: useMnnEngine ? "cpu" : "faceid")
                    .font(.system(size: 11, weight: .bold))
                Text("MNN")
                    .font(.system(size: 12, weight: .bold))
                    .foregroundColor(useMnnEngine ? .yellow : .white.opacity(0.45))
                Text("/")
                    .font(.system(size: 12, weight: .bold))
                    .foregroundColor(.white.opacity(0.35))
                Text("MediaPipe")
                    .font(.system(size: 12, weight: .bold))
                    .foregroundColor(useMnnEngine ? .white.opacity(0.45) : .yellow)
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 8)
            .background(.ultraThinMaterial, in: Capsule())
            .overlay(Capsule().stroke(Color.white.opacity(0.15), lineWidth: 0.5))
        }
    }

    // MARK: - 控件层（锚安全区，不跟随预览延伸）

    /// 真实顶部安全区高度（刘海/灵动岛）。
    /// 🔴 不能读 `GeometryProxy.safeAreaInsets`：根 GeometryReader 打了 `.ignoresSafeArea(.all)`，
    /// proxy 报告的 insets 恒为 0（SwiftUI 陷阱），只能从 UIKit keyWindow 拿真实值。
    private var realSafeTop: CGFloat {
        UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .flatMap { $0.windows }
            .first { $0.isKeyWindow }?.safeAreaInsets.top ?? 0
    }

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

    // MARK: - 顶部控件（右列锚定 safeTop，间距保持 Android dump 节奏）

    /// 右列相邻按钮中心 y% 差（dump 实测）：ratio-wand 9.29% / grid-ratio 7.08% /
    /// scene-grid 9.28% / filter-scene 7.08% / tune-filter 9.29%
    private func topControls(screenHeight: CGFloat, safeTop: CGFloat) -> some View {
        // 按钮容器 48pt，中心到底/顶各 24pt
        let buttonSize: CGFloat = 48
        let halfButton = buttonSize / 2

        // 🔴 刘海屏适配：首按钮锚定 safeTop（与左列同一基线），不再用屏高绝对 y%——
        // 旧 wand 中心 = 屏高 4.87%（刘海机上 ~41pt < safeTop 59pt），撞刘海/灵动岛
        let yWand = safeTop + halfButton + 8
        let yRatio = yWand + screenHeight * 0.0929
        let yGrid = yRatio + screenHeight * 0.0708
        let yScene = yGrid + screenHeight * 0.0928
        let yFilter = yScene + screenHeight * 0.0708
        let yTune = yFilter + screenHeight * 0.0929

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
            // 🔴 .position 锚的是整个 VStack 的中心（2×48+8=104pt，半高 52）：
            // 首按钮中心 = safeTop+8+52-52+24 = safeTop+32，与右列 wand 同基线
            .position(x: 16 + halfButton, y: safeTop + 8 + 52)

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
                        .fill(Color.accentColor) // 启用 badge（对标 Android primary 色）
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
    let faceRouter: FaceEngineRouter
    let landmarkStore: LandmarkOverlayStore

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
        context.coordinator.faceRouter = faceRouter
        context.coordinator.landmarkStore = landmarkStore
        return view
    }

    func updateUIView(_ uiView: MTKView, context: Context) {
        context.coordinator.renderer?.params = params
    }

    func makeCoordinator() -> Coordinator { Coordinator() }

    final class Coordinator: NSObject, MTKViewDelegate {
        var renderer: BeautyRenderer?
        var controller: CaptureSessionController?
        var faceRouter: FaceEngineRouter?
        var landmarkStore: LandmarkOverlayStore?
        private var frames = 0
        private var lastFpsTick = Date()

        func mtkView(_ view: MTKView, drawableSizeWillChange size: CGSize) {}

        func draw(in view: MTKView) {
            // 🔴 用 readFrame() 拿 (buffer, 相机PTS毫秒)：检测端 latest.timestampMs 也来自同一 PTS，
            // 两者同域 → latestWithinWindow 的 200ms 窗口 join 才成立（此前误用墙钟 epoch ms，
            // 与相机 PTS 差万亿 ms → 恒 nil → 106 点进不了渲染器 → 瘦脸无效）。
            guard let (pb, tsMs) = controller?.readFrame() else { return }
            if let fs = faceRouter {
                if let points = fs.latestWithinWindow(currentTimestampMs: tsMs) {
                    renderer?.updateFacePoints(points, hasFace: true)
                    // 🔴 转发到逐点调试 overlay（主线程；@Published 合并重绘）
                    if let store = landmarkStore {
                        let snap = points
                        // overlay 的 aspect-fill crop 需与 BeautyRenderer 同 buffer 尺寸（portrait 720×1280）；
                        // 从当前帧实测，避免硬编码假设（裁剪错位会让人脸框/关键点整体偏移）。
                        let bw = CVPixelBufferGetWidth(pb)
                        let bh = CVPixelBufferGetHeight(pb)
                        DispatchQueue.main.async {
                            store.bufferSize = CGSize(width: bw, height: bh)
                            store.points = snap
                        }
                    }
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
