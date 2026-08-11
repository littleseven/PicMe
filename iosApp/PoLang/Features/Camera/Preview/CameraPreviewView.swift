import SwiftUI
import MetalKit
import Photos

/// 相机主页面（对标 Android CameraPreviewContent.kt）
/// 🔴 预览全出血 edge-to-edge + 控件锚 safe area
struct CameraPreviewView: View {
    @EnvironmentObject private var container: AppContainer
    /// 相册入口回调（MainTabView 注入：切到相册页）
    var onGalleryTap: () -> Void = {}
    /// 🔴 相机激活门控（对标 Android `isActivePage = currentPage == CAMERA`）：全常驻 pager 下相机页
    /// 不会 disappear，改由 MainTabView 传 `currentPage == 0` 驱动 start/stop——
    /// true→授权/start/resume；false→stop 省电防发热。
    var isActive: Bool = false
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

    /// 比例启动覆盖（验收/诊断用；-ratio43 / -ratio169，否则 FULL）
    private static func resolveRatio() -> AspectMode {
        let args = ProcessInfo.processInfo.arguments
        if args.contains("-ratio43") { return .ratio43 }
        if args.contains("-ratio169") { return .ratio169 }
        return .full
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
    // 右列对齐（B2a）：构图网格 + 场景模式（对标 Android currentGrid / currentScene）
    @State private var currentGrid: GridType = .off
    @State private var currentScene: ScenePreset = .off
    @State private var currentRatio: AspectMode = Self.resolveRatio()
    // ProMode 独立轨道（对标 Android showProPanel；与 primary 组互斥渲染，可与 beauty 并存）
    @State private var showProPanel = false
    @State private var exposureComp: Double = 0      // EV -2..2（AVCapture setExposureBias）
    @State private var whiteBalanceMode = 0          // 0=auto/1=sunny/2=cloudy/3=incandescent/4=fluorescent

    enum ActivePanel: Equatable { case beauty, filter, grid, scene, ratio }
    enum CameraMode: String, CaseIterable { case video = "视频", photo = "照片", document = "文档" }
    // 构图网格（对标 Android GridType）
    enum GridType: Equatable { case off, thirds, golden }
    // 场景模式（对标 Android ScenePreset；NIGHT→EV+1，MOON→EV-2+3.2x）
    enum ScenePreset: Equatable { case off, night, moon }
    // 画面比例（对标 Android CameraAspectRatio：FULL=填充裁剪，4:3/16:9=FIT 留黑边）
    enum AspectMode: Equatable { case full, ratio43, ratio169 }

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
                                       landmarkStore: landmarkStore, isActive: isActive)
                .frame(width: geo.size.width, height: geo.size.height)
                .accessibilityIdentifier("camera_preview")

                // 构图网格叠加（对标 Android CompositionGrid：虚线 white 0.5，THIRDS/GOLDEN）
                if currentGrid != .off {
                    compositionGridOverlay(width: geo.size.width, height: geo.size.height)
                }

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
        // 🔴 相机硬件由 isActive 门控（对标 Android `isActivePage`）：全常驻 pager 下相机页不会 disappear，
        // 改由 MainTabView 传 `currentPage == 0` 驱动 start/stop——滑离相机页 stop 省电防发热，滑回 resume。
        // 用 .task(id:) 而非 onChange：首次 view 组合也会跑（onChange 不触发初值），-startPage 0 直进相机页也覆盖。
        .task(id: isActive) {
            if isActive {
                if authorized {
                    controller.resume()   // 已授权已配置：恢复 running（不重配 session）
                } else {
                    // 首次：授权 + 完整配置 session + start
                    authorized = await controller.checkAuthorizationAndStart()
                    DebugOverlayState.shared.set("camera.auth", authorized ? "granted" : "denied")
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
                }
            } else if authorized {
                controller.stop()         // 滑离相机页：腾出相机给系统
            }
        }
        // 🔴 一次性配置（不随进出相机页重复）：MNN 自检 / Debug 叠加层 / 引擎 / 美颜参数 / 缩略图
        .task {
            // MNN 端侧推理离线自检（-mnnSelfTest 时跑；写 Documents/mnn-verify.txt 供验收拉取）
            MnnSelfTest.runIfRequested()
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
        .onChange(of: currentScene) { s in
            // 场景模式作用相机（NIGHT→EV+1，MOON→EV-2+3.2x，NONE→EV 0）
            applyScene(s)
        }
        .onChange(of: exposureComp) { ev in
            // ProMode EV → AVCapture 曝光补偿（-2..2；场景模式也写 EV，后到者覆盖，对标 Android）
            controller.setExposureBias(Float(ev))
        }
        // 相机 stop 改由 isActive 门控（见 .task(id: isActive)）：全常驻 pager 下相机页不会 disappear，
        // onDisappear 不触发，故移除——滑离相机页由 isActive=false → controller.stop()。
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

    /// 构图网格（对标 Android CompositionGrid：white alpha 0.5，1pt，虚线 [10,10]）
    @ViewBuilder
    private func compositionGridOverlay(width: CGFloat, height: CGFloat) -> some View {
        Canvas { ctx, size in
            let isThirds = currentGrid == .thirds
            let xs = isThirds ? [size.width / 3.0, 2.0 * size.width / 3.0]
                              : [size.width * 0.382, size.width * 0.618]
            let ys = isThirds ? [size.height / 3.0, 2.0 * size.height / 3.0]
                              : [size.height * 0.382, size.height * 0.618]
            var path = Path()
            for x in xs {
                path.move(to: CGPoint(x: x, y: 0))
                path.addLine(to: CGPoint(x: x, y: size.height))
            }
            for y in ys {
                path.move(to: CGPoint(x: 0, y: y))
                path.addLine(to: CGPoint(x: size.width, y: y))
            }
            ctx.stroke(path, with: .color(.white.opacity(0.5)),
                       style: StrokeStyle(lineWidth: 1, dash: [10, 10]))
        }
        .frame(width: width, height: height)
        .allowsHitTesting(false)
    }

    /// 场景模式作用到相机（对标 Android：NIGHT→EV+1，MOON→EV-2+3.2x，NONE→EV 0）
    private func applyScene(_ s: ScenePreset) {
        switch s {
        case .off: controller.setExposureBias(0)
        case .night: controller.setExposureBias(1)
        case .moon:
            controller.setExposureBias(-2)
            controller.setZoom(3.2)
        }
    }

    private func closePanel() { withAnimation { activePanel = nil } }

    /// 当前比例对应的拍照裁剪 h/w（FULL=nil 不裁；4:3→4/3，16:9→16/9，对标 Android 拍照 aspect crop）
    private var captureCropHPerW: CGFloat? {
        switch currentRatio {
        case .full: return nil
        case .ratio43: return 4.0 / 3.0
        case .ratio169: return 16.0 / 9.0
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
                    // ProMode 独立轨道：开则单关 Pro；否则关 primary 面板（对标 Android 空白点击语义）
                    if showProPanel { withAnimation { showProPanel = false } }
                    else if activePanel != nil { withAnimation { activePanel = nil } }
                }

            // 顶部控件
            topControls(screenHeight: screenHeight, safeTop: safeTop)

            // 底部控件（底对齐，独立层）
            VStack(spacing: 0) {
                Spacer()
                bottomControls
            }

            // 面板层：底对齐、覆盖底栏（Z 序在底栏之上，对标 Android align(BottomCenter) 覆盖 CameraBottomControls）
            VStack(spacing: 0) {
                Spacer()
                if let panel = activePanel {
                    switch panel {
                    case .beauty:
                        BeautyPanelView(params: $container.beautyParams)
                            .transition(.move(edge: .bottom).combined(with: .opacity))
                    case .filter:
                        ControlPanel {
                            FilterSelectorView(selectedFilter: $container.beautyParams.colorFilter)
                        }
                        .transition(.move(edge: .bottom).combined(with: .opacity))
                    case .grid:
                        ControlPanel {
                            HStack(spacing: 12) {
                                OptionButton(titleKey: "None", isSelected: currentGrid == .off) { currentGrid = .off; closePanel() }
                                OptionButton(titleKey: "Thirds", isSelected: currentGrid == .thirds) { currentGrid = .thirds; closePanel() }
                                OptionButton(titleKey: "Golden Ratio", isSelected: currentGrid == .golden) { currentGrid = .golden; closePanel() }
                            }
                        }
                        .transition(.move(edge: .bottom).combined(with: .opacity))
                    case .scene:
                        ControlPanel {
                            HStack(spacing: 12) {
                                OptionButton(titleKey: "None", isSelected: currentScene == .off) { currentScene = .off; closePanel() }
                                OptionButton(titleKey: "Night", isSelected: currentScene == .night) { currentScene = .night; closePanel() }
                                OptionButton(titleKey: "Moon", isSelected: currentScene == .moon) { currentScene = .moon; closePanel() }
                            }
                        }
                        .transition(.move(edge: .bottom).combined(with: .opacity))
                    case .ratio:
                        ControlPanel {
                            HStack(spacing: 16) {
                                OptionButton(titleKey: "Ratio 4:3", isSelected: currentRatio == .ratio43) { currentRatio = .ratio43; closePanel() }
                                OptionButton(titleKey: "Ratio 16:9", isSelected: currentRatio == .ratio169) { currentRatio = .ratio169; closePanel() }
                                OptionButton(titleKey: "Full Screen", isSelected: currentRatio == .full) { currentRatio = .full; closePanel() }
                            }
                        }
                        .transition(.move(edge: .bottom).combined(with: .opacity))
                    }
                }
                // ProMode 独立面板（与 beauty 可并存；被 filter/grid/scene primary 面板抑制渲染）
                if showProPanel && activePanel != .filter && activePanel != .grid && activePanel != .scene {
                    ProModePanel(
                        exposure: $exposureComp,
                        whiteBalance: $whiteBalanceMode,
                        contrast: Binding(get: { Double(container.beautyParams.contrast) },
                                          set: { container.beautyParams.contrast = Float($0) }),
                        saturation: Binding(get: { Double(container.beautyParams.saturation) },
                                            set: { container.beautyParams.saturation = Float($0) }),
                        temperature: Binding(get: { Double(container.beautyParams.temperature) },
                                             set: { container.beautyParams.temperature = Float($0) }),
                        onDismiss: { withAnimation { showProPanel = false } }
                    )
                    .padding(.horizontal, 24)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
                }
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
            rightColumnButton("aspectratio", y: yRatio, isActive: activePanel == .ratio) {
                withAnimation { activePanel = activePanel == .ratio ? nil : .ratio }
            }
            rightColumnButton("square.grid.3x3", y: yGrid, isActive: activePanel == .grid) {
                withAnimation { activePanel = activePanel == .grid ? nil : .grid }
            }
            rightColumnButton("mountain.2", y: yScene, isActive: activePanel == .scene) {
                withAnimation { activePanel = activePanel == .scene ? nil : .scene }
            }
            rightColumnButton("circle.lefthalf.filled", y: yFilter, isActive: activePanel == .filter) {
                withAnimation { activePanel = activePanel == .filter ? nil : .filter }
            }
            rightColumnButton("slider.horizontal.3", y: yTune, isActive: showProPanel) {
                withAnimation { showProPanel.toggle() }
            }
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
                    let flow = CaptureFlow(photoController: photoController, renderer: renderer, cropHPerW: captureCropHPerW)
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

// MARK: - ControlPanel 容器（对标 Android ControlPanel：半屏 50% + 顶部圆角 24 + 拖拽手柄 + 底部渐变遮罩 + 边框 + 实色 surface）

private struct ControlPanel<Content: View>: View {
    var onDismiss: (() -> Void)? = nil
    @ViewBuilder let content: () -> Content

    var body: some View {
        ZStack(alignment: .bottom) {
            // 底部渐变遮罩（Transparent→Black0.55→Black0.82），在 surface 之后（对标 Android ControlPanel 外层 Box）
            LinearGradient(colors: [.clear, .black.opacity(0.55), .black.opacity(0.82)],
                           startPoint: .top, endPoint: .bottom)
                .frame(maxWidth: .infinity)
                .frame(height: UIScreen.main.bounds.height * 0.5 + 24)
                .allowsHitTesting(false)
            VStack(spacing: 0) {
                // 拖拽手柄 36×4（onSurface alpha 0.2）；可点关闭
                Capsule().fill(Color.white.opacity(0.2))
                    .frame(width: 36, height: 4)
                    .padding(.top, 10).padding(.bottom, 4)
                    .onTapGesture { onDismiss?() }
                // 内容自适应高度（maxHeight 上限 50%，非 ScrollView 强制占满；对标 Android heightIn(max)）
                content()
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 24).padding(.vertical, 12)
            }
            .frame(maxWidth: .infinity)
            .frame(maxHeight: UIScreen.main.bounds.height * 0.5, alignment: .top)
            .background(RoundedRectangle(cornerRadius: 24, style: .continuous)
                .fill(Color(red: 0.11, green: 0.10, blue: 0.12).opacity(0.95)))
            .overlay(RoundedRectangle(cornerRadius: 24, style: .continuous)
                .stroke(Color.white.opacity(0.25), lineWidth: 0.5))
            .shadow(color: .black.opacity(0.5), radius: 16)
        }
    }
}

// 选项按钮（对标 Android RatioItem：selected=primary/onPrimary，unselected=DarkGray/onSurface，12sp）
private struct OptionButton: View {
    let titleKey: LocalizedStringKey
    let isSelected: Bool
    let action: () -> Void
    var body: some View {
        Button(action: action) {
            Text(titleKey).font(.system(size: 12))
                .foregroundColor(isSelected ? .black : .white)
                .padding(.horizontal, 12).padding(.vertical, 8)
                .background(RoundedRectangle(cornerRadius: 10, style: .continuous)
                    .fill(isSelected ? Color.accentColor : Color(white: 0.25)))
        }
    }
}

// MARK: - ProMode 面板（对标 Android ProModeControls：WB chips + EV/对比度/饱和度/色温）

private struct ProModePanel: View {
    @Binding var exposure: Double
    @Binding var whiteBalance: Int
    @Binding var contrast: Double
    @Binding var saturation: Double
    @Binding var temperature: Double
    var onDismiss: () -> Void

    private let wbOptions: [(label: String, value: Int)] = [
        ("Auto", 0), ("Sunny", 1), ("Cloudy", 2), ("Incandescent", 3), ("Fluorescent", 4)
    ]

    var body: some View {
        ControlPanel(onDismiss: onDismiss) {
            VStack(alignment: .leading, spacing: 14) {
                VStack(alignment: .leading, spacing: 8) {
                    Text("White Balance").font(.system(size: 12)).foregroundStyle(.white.opacity(0.7))
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 8) {
                            ForEach(wbOptions, id: \.value) { opt in
                                OptionButton(titleKey: LocalizedStringKey(opt.label), isSelected: whiteBalance == opt.value) {
                                    whiteBalance = opt.value
                                }
                            }
                        }
                    }
                }
                sliderRow(label: "Exposure", value: $exposure, range: -2...2, step: 1, display: String(format: "%+.0f", exposure))
                sliderRow(label: "Contrast", value: $contrast, range: 0...200, step: 1, display: "\(Int(contrast))")
                sliderRow(label: "Saturation", value: $saturation, range: 0...200, step: 1, display: "\(Int(saturation))")
                sliderRow(label: "Color Temperature", value: $temperature, range: 2000...8000, step: 50, display: "\(Int(temperature))K")
            }
        }
    }

    private func sliderRow(label: LocalizedStringKey, value: Binding<Double>,
                           range: ClosedRange<Double>, step: Double, display: String) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text(label).font(.system(size: 12)).foregroundStyle(.white.opacity(0.7))
                Spacer()
                Text(display).font(.system(size: 12, weight: .bold)).foregroundStyle(.white)
            }
            Slider(value: value, in: range, step: step).tint(.accentColor)
        }
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
    /// 🔴 相机激活门控透传：isActive=false 时暂停 MTKView 自循环，防全常驻 pager 下非活跃相机页 GPU 空转发热
    var isActive: Bool = true

    func makeUIView(context: Context) -> MTKView {
        let view = MTKView()
        view.device = MTLCreateSystemDefaultDevice()
        view.delegate = context.coordinator
        view.enableSetNeedsDisplay = false
        view.isPaused = !isActive   // 🔴 活跃才自循环渲染；非活跃暂停防 GPU 空转发热
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
        uiView.isPaused = !isActive   // 🔴 isActive 变化同步暂停/恢复 MTKView 自循环
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
