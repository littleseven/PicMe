import SwiftUI
import SharedKit
import UIKit

/// 大图浏览（对齐 Android `MediaPager.kt`，量化基准 = dump gallery_pager，密度 3.33）：
/// 黑底横滑分页（去系统 page dots，页间距 16）、双指缩放 1–4x + 平移（clamp 公式对齐
/// `ZoomableImage:344-417`）、单击切换顶/底栏显隐（缩放时强制隐藏）。
/// 顶栏（dump：关闭/日期/图片信息/更多 4 项，按钮 48dp、栏内容高 68dp）；
/// 底栏（dump：发送/编辑/证照/删除 4 位 SpaceEvenly）——编辑/证照 iOS 无对应功能（Phase 6），
/// 保持 4 位布局节奏灰置占位，不假造交互；删除走 PHAssetChangeRequest 系统确认窗。
/// 系统栏（§1.3 登记）：状态栏显、黑底 → preferredColorScheme(.dark) 白内容色。
struct MediaPagerView: View {
    let items: [MediaAsset]
    @State private var index: Int
    @State private var barsVisible = true
    /// 当前是否有页处于缩放态：缩放时隐藏顶/底栏（对齐 Android showBarsVisible 语义）
    @State private var isZoomed = false
    @State private var showInfo = false
    @State private var sharePayload: SharePayload? = nil
    @State private var showDeleteConfirm = false
    /// 编辑器 fullScreenCover 载体（Edit 按钮 → PhotoEditorScreen）
    @State private var editTarget: EditorTarget?
    /// 证照入口「敬请期待」toast（iOS 无 ID-photo 流程，Phase 6）
    @State private var showIdComingSoon = false
    /// debug 开关门控「人脸关键点」入口（对齐 Android debugUiEnabled）
    @AppStorage("debug_ui_enabled") private var debugEnabled = false
    @State private var showFaceOverlay = false
    @Environment(\.dismiss) private var dismiss
    /// 删除直调 Swift 桥（PHAssetChangeRequest 自带系统确认；成功后观察者驱动网格刷新）
    private let bridge = PhMediaBridge()

    init(items: [MediaAsset], initial: String) {
        self.items = items
        _index = State(initialValue: max(0, items.firstIndex(where: { $0.uri == initial }) ?? 0))
    }

    private var currentAsset: MediaAsset? {
        items.indices.contains(index) ? items[index] : nil
    }

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            TabView(selection: $index) {
                ForEach(Array(items.enumerated()), id: \.element.uri) { i, asset in
                    ZoomablePagerPage(
                        localIdentifier: asset.uri,
                        isActive: i == index,
                        showFaceOverlay: showFaceOverlay,
                        onTap: { withAnimation(.easeInOut(duration: 0.2)) { barsVisible.toggle() } },
                        onZoomChange: { zoomed in
                            if zoomed { isZoomed = true } else if i == index { isZoomed = false }
                        })
                    .padding(.horizontal, 8)  // 对齐 Android pageSpacing 16dp
                    .tag(i)
                }
            }
            .tabViewStyle(.page(indexDisplayMode: .never))  // 去 page dots（Android 无指示器）
            .ignoresSafeArea()
            // 🔴 标识符挂 TabView（页内容叶子侧）：挂根 ZStack 会传播覆盖 pager_back/info/more 等全部子标识符
            .accessibilityIdentifier("media_pager")

            if barsVisible && !isZoomed {
                VStack(spacing: 0) {
                    topBar
                    Spacer()
                    if currentAsset?.type != MediaType.video { bottomBar }
                }
                .transition(.opacity)
            }
        }
        .preferredColorScheme(.dark)  // §1.3 登记：黑底页状态栏白色内容
        .statusBarHidden(isZoomed || !barsVisible)
        .sheet(isPresented: $showInfo) {
            if let asset = currentAsset { PhotoInfoSheet(asset: asset) }
        }
        .sheet(item: $sharePayload) { payload in
            ActivityView(activityItems: payload.images)
        }
        .confirmationDialog(String(localized: "Delete this photo?"),
                            isPresented: $showDeleteConfirm, titleVisibility: .visible) {
            Button(String(localized: "Delete"), role: .destructive) { deleteCurrent() }
            Button(String(localized: "Cancel"), role: .cancel) {}
        }
        .fullScreenCover(item: $editTarget) { target in
            PhotoEditorScreen(localIdentifier: target.id)
        }
        .overlay {
            if showIdComingSoon {
                Text("This feature is coming soon")
                    .font(.system(size: 14))
                    .padding(.horizontal, 16).padding(.vertical, 10)
                    .background(Color.black.opacity(0.8))
                    .clipShape(Capsule())
                    .transition(.opacity)
            }
        }
        .animation(.easeInOut(duration: 0.2), value: showIdComingSoon)
        .onChange(of: showIdComingSoon) { onset in
            guard onset else { return }
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.6) { showIdComingSoon = false }
        }
    }

    /// 顶栏（对齐 Android `mediaPagerTopControls`：黑 0.85 底、h16/v10 padding（内容高 68dp）、
    /// 按钮 48dp）：返回 mat_arrow_back 24dp + 日期 14sp/白 0.85（间距 12），
    /// 右侧 mat_info/mat_more_horiz 22dp（间距 4）；更多菜单（图像理解/提取文字/人脸关键点，
    /// 均依赖 Phase 6 VLM/OCR/人脸管线，列出但灰置，菜单项图标 20dp 对齐 Android 下拉项）
    private var topBar: some View {
        HStack(spacing: 0) {
            Button { dismiss() } label: {
                MatIcon(name: "mat_arrow_back", size: 24)
                    .frame(width: 48, height: 48)
                    .contentShape(Rectangle())
            }
            .accessibilityIdentifier("pager_back")
            Text(formattedDate(currentAsset?.captureDate))
                .font(.system(size: 14, weight: .medium))
                .foregroundStyle(Color.white.opacity(0.85))
                .lineLimit(1)
                .padding(.leading, 12)
            Spacer()
            HStack(spacing: 4) {
                Button { showInfo = true } label: {
                    MatIcon(name: "mat_info", size: 22)
                        .frame(width: 48, height: 48)
                        .contentShape(Rectangle())
                }
                .accessibilityIdentifier("pager_info")
                Menu {
                    Button {} label: {
                        Label {
                            Text(String(localized: "Image Analysis"))
                        } icon: {
                            MatIcon(name: "mat_auto_awesome", size: 20)
                        }
                    }.disabled(true)
                    Button {} label: {
                        Label {
                            Text(String(localized: "Extract Text"))
                        } icon: {
                            MatIcon(name: "mat_text_snippet", size: 20)
                        }
                    }.disabled(true)
                    Button { showFaceOverlay.toggle() } label: {
                        Label {
                            Text(String(localized: "Face Landmarks"))
                        } icon: {
                            MatIcon(name: "mat_face", size: 20)
                        }
                    }
                    .disabled(!debugEnabled || currentAsset?.type == .video)
                } label: {
                    MatIcon(name: "mat_more_horiz", size: 22)
                        .frame(width: 48, height: 48)
                        .contentShape(Rectangle())
                }
                // Menu 内建按钮样式自带横向 padding，定死 48 框防挤压顶栏布局
                .frame(width: 48, height: 48)
                .menuIndicator(.hidden)
                .accessibilityIdentifier("pager_more")
            }
        }
        .padding(.horizontal, 16)
        .frame(height: 68)  // = Android v10 padding + 48dp 按钮
        .foregroundStyle(.white)
        .background(Color.black.opacity(0.85))
    }

    /// 底栏（对齐 Android `mediaPagerBottomBar`：4 位 SpaceEvenly、h8/v4 padding、
    /// 按钮 48dp、icon 22dp/白 0.9 + label 10sp/白 0.7）：
    /// 发送/删除实做；编辑→PhotoEditorScreen（fullScreenCover）；
    /// 证照（ID-photo）iOS 无流程（Phase 6 matting），点出「敬请期待」toast
    private var bottomBar: some View {
        HStack(spacing: 0) {
            Spacer()
            bottomBarItem(icon: "mat_send",
                          title: String(localized: "Send"),
                          accessibilityID: "pager_share") { shareCurrent() }
            Spacer()
            bottomBarItem(icon: "mat_autofix",
                          title: String(localized: "Edit"),
                          accessibilityID: "pager_edit",
                          isEnabled: true) {
                if let uri = currentAsset?.uri { editTarget = EditorTarget(localIdentifier: uri) }
            }
            Spacer()
            bottomBarItem(icon: "mat_badge",
                          title: String(localized: "ID"),
                          accessibilityID: "pager_id_photo",
                          isEnabled: true) { showIdComingSoon = true }
            Spacer()
            bottomBarItem(icon: "mat_delete",
                          title: String(localized: "Delete"),
                          accessibilityID: "pager_delete") { showDeleteConfirm = true }
            Spacer()
        }
        .padding(.horizontal, 8)
        .padding(.vertical, 4)
        .background(Color.black.opacity(0.85))
    }

    private func bottomBarItem(icon: String, title: String,
                               accessibilityID: String,
                               isEnabled: Bool = true,
                               action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(spacing: 2) {
                MatIcon(name: icon, size: 22)
                    .foregroundStyle(Color.white.opacity(0.9))
                Text(title)
                    .font(.system(size: 10))
                    .foregroundStyle(Color.white.opacity(0.7))
            }
            .frame(width: 48, height: 48)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .opacity(isEnabled ? 1 : 0.3)
        .disabled(!isEnabled)
        .accessibilityIdentifier(accessibilityID)
    }

    private func formattedDate(_ captureDate: Int64?) -> String {
        guard let captureDate else { return "" }
        let fmt = DateFormatter()
        fmt.dateFormat = "yyyy-MM-dd"
        return fmt.string(from: Date(timeIntervalSince1970: TimeInterval(captureDate) / 1000))
    }

    private func shareCurrent() {
        guard let asset = currentAsset else { return }
        Task {
            if let image = await ThumbnailLoader.shared.thumbnail(
                for: asset.uri, size: CGSize(width: 2000, height: 2000), highQuality: true) {
                sharePayload = SharePayload(images: [image])
            }
        }
    }

    private func deleteCurrent() {
        guard let asset = currentAsset else { return }
        _ = bridge.deleteMedia(localIdentifiers: [asset.uri])
        dismiss()  // 删除后退出大图页；网格经 PHPhotoLibraryObserver 自动刷新
    }
}

/// 可缩放单页（对齐 Android `ZoomableImage`）：
/// MagnificationGesture 1–4x clamp；缩放态 DragGesture 平移并按 w*(scale-1)/2 clamp；
/// scale ≤ 1.02 复位并恢复横滑翻页；切页自动复位。
private struct ZoomablePagerPage: View {
    let localIdentifier: String
    let isActive: Bool
    let showFaceOverlay: Bool
    let onTap: () -> Void
    let onZoomChange: (Bool) -> Void

    @State private var image: UIImage?
    @State private var scale: CGFloat = 1
    @State private var lastScale: CGFloat = 1
    @State private var offset: CGSize = .zero
    @State private var lastOffset: CGSize = .zero

    /// 人脸关键点检测状态机：idle→loading→success(画点)/noFace(反馈)。
    /// 对标 Android FaceLandmarkDetectionState；成功态 overlay 留在本 ZStack 跟随缩放。
    private enum FaceState {
        case idle, loading
        case success(StaticFaceDetector.Outcome)
        case noFace
    }
    @State private var faceState: FaceState = .idle

    var body: some View {
        GeometryReader { geo in
            Group {
                if let image {
                    // 🔴 静态检测 overlay 与 Image 同 ZStack、同 frame，跟踪 scaleEffect/offset（缩放时一起动）
                    ZStack {
                        Image(uiImage: image).resizable().scaledToFit()
                        if showFaceOverlay {
                            if case .success(let outcome) = faceState {
                                GalleryFaceOverlay(points: outcome.points, imageSize: outcome.imageSize)
                            } else if case .loading = faceState {
                                GalleryFaceFeedback(phase: .loading)
                            } else if case .noFace = faceState {
                                GalleryFaceFeedback(phase: .noFace)
                            }
                        }
                    }
                } else {
                    ProgressView().tint(.white)
                }
            }
            .frame(width: geo.size.width, height: geo.size.height)
            .scaleEffect(scale)
            .offset(offset)
            .contentShape(Rectangle())
            .onTapGesture { onTap() }
            .gesture(magnify(in: geo.size))
            // 缩放态高优先级平移：压过 TabView 横滑；非缩放态禁用，翻页手势不受影响
            .highPriorityGesture(pan(in: geo.size), isEnabled: scale > 1.02)
        }
        .clipped()
        .onChange(of: isActive) { active in  // iOS 16 部署目标：用单参闭包形式
            if !active { resetZoom() } else { detectIfNeeded() }
        }
        .task(id: showFaceOverlay) {
            // 用 .task(id:) 而非 onChange：父层开关变化在 TabView 页面里 onChange 偶发不触发，
            // .task(id:) 在 id 变化时必然重跑，更可靠地驱动 detectIfNeeded。
            guard showFaceOverlay else {
                faceState = .idle
                return
            }
            detectIfNeeded()
        }
        .task(id: localIdentifier) {
            faceState = .idle  // 切页重置：新图重新走 idle→loading→...
            image = await ThumbnailLoader.shared.thumbnail(
                for: localIdentifier,
                size: CGSize(width: 1600, height: 1600),
                highQuality: true)
            detectIfNeeded()  // 图就绪后，若仍「激活+开关开」则触发检测
        }
    }

    /// 满足「开关开 + 当前页 + 图已加载 + idle」时触发一次 MNN 检测；否则空操作。
    private func detectIfNeeded() {
        guard showFaceOverlay, isActive, let image else { return }
        guard case .idle = faceState else { return }  // 已在检测/已有结果则不重复
        faceState = .loading
        let snapshot = image
        Task.detached(priority: .userInitiated) {
            let outcome = StaticFaceDetector.detect(snapshot)
            await MainActor.run {
                guard showFaceOverlay, isActive else { return }  // 仍开且仍是当前页
                if let outcome {
                    faceState = .success(outcome)
                } else {
                    faceState = .noFace
                }
            }
        }
    }

    private func magnify(in size: CGSize) -> some Gesture {
        MagnificationGesture()
            .onChanged { value in
                scale = min(4, max(1, lastScale * value))
                offset = clamped(offset, in: size)
                onZoomChange(scale > 1.02)
            }
            .onEnded { _ in
                lastScale = scale
                if scale <= 1.02 {
                    resetZoom()
                } else {
                    offset = clamped(offset, in: size)
                    lastOffset = offset
                }
            }
    }

    private func pan(in size: CGSize) -> some Gesture {
        DragGesture()
            .onChanged { value in
                offset = clamped(CGSize(width: lastOffset.width + value.translation.width,
                                        height: lastOffset.height + value.translation.height),
                                 in: size)
            }
            .onEnded { _ in
                lastOffset = offset
            }
    }

    /// 平移边界（对齐 Android：maxX = w*(scale-1)/2，maxY 同理）
    private func clamped(_ value: CGSize, in size: CGSize) -> CGSize {
        let maxX = size.width * (scale - 1) / 2
        let maxY = size.height * (scale - 1) / 2
        return CGSize(width: min(maxX, max(-maxX, value.width)),
                      height: min(maxY, max(-maxY, value.height)))
    }

    private func resetZoom() {
        withAnimation(.easeInOut(duration: 0.2)) {
            scale = 1
            offset = .zero
        }
        lastScale = 1
        lastOffset = .zero
        onZoomChange(false)
    }
}

/// 照片信息浮层（简化版对齐 Android PhotoInfoDialog：文件名/类型/拍摄时间/时长；
/// OCR/Vision/标签/美学评分 iOS 无数据（Phase 6），不显示）
private struct PhotoInfoSheet: View {
    let asset: MediaAsset
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                infoRow(String(localized: "File Name"), value: asset.fileName)
                infoRow(String(localized: "Type"),
                        value: asset.type == MediaType.video
                            ? String(localized: "Video") : String(localized: "Photo"))
                infoRow(String(localized: "Captured"), value: formattedDateTime)
                if let duration = asset.duration {
                    infoRow(String(localized: "Duration"), value: formatDuration(duration.int64Value))
                }
                if let locationName = asset.locationName {
                    infoRow(String(localized: "Location"), value: locationName)
                }
            }
            .navigationTitle(String(localized: "Info"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button(String(localized: "Done")) { dismiss() }
                }
            }
        }
        .presentationDetents([.medium])
    }

    private func infoRow(_ label: String, value: String) -> some View {
        HStack {
            Text(label).foregroundStyle(.secondary)
            Spacer()
            Text(value).multilineTextAlignment(.trailing)
        }
    }

    private var formattedDateTime: String {
        let fmt = DateFormatter()
        fmt.dateFormat = "yyyy-MM-dd HH:mm"
        return fmt.string(from: Date(timeIntervalSince1970: TimeInterval(asset.captureDate) / 1000))
    }

    private func formatDuration(_ ms: Int64) -> String {
        let totalSeconds = ms / 1000
        return String(format: "%d:%02d", totalSeconds / 60, totalSeconds % 60)
    }
}

#Preview {
    MediaPagerView(items: [], initial: "")
}
