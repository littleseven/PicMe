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
        .accessibilityIdentifier("media_pager")
    }

    /// 顶栏（dump：栏内容高 68dp、按钮 48dp/字形 24dp、右缘 16dp）：
    /// 关闭 ← + 日期（20sp）+ 图片信息 + 更多（图像理解/提取文字/人脸关键点，
    /// 均依赖 Phase 6 VLM/OCR/人脸管线，列出但灰置）
    private var topBar: some View {
        HStack(spacing: 8) {
            Button { dismiss() } label: {
                Image(systemName: "chevron.left")
                    .font(.system(size: 24))
                    .frame(width: 48, height: 48)
                    .contentShape(Rectangle())
            }
            .accessibilityIdentifier("pager_back")
            Text(formattedDate(currentAsset?.captureDate))
                .font(.system(size: 20, weight: .medium))
                .lineLimit(1)
            Spacer()
            Button { showInfo = true } label: {
                Image(systemName: "info.circle")
                    .font(.system(size: 24))
                    .frame(width: 48, height: 48)
                    .contentShape(Rectangle())
            }
            .accessibilityIdentifier("pager_info")
            Menu {
                Button {} label: { Text(String(localized: "Image Analysis")) }.disabled(true)
                Button {} label: { Text(String(localized: "Extract Text")) }.disabled(true)
                Button {} label: { Text(String(localized: "Face Landmarks")) }.disabled(true)
            } label: {
                Image(systemName: "ellipsis")
                    .font(.system(size: 24))
                    .frame(width: 48, height: 48)
                    .contentShape(Rectangle())
            }
            // Menu 内建按钮样式自带横向 padding，定死 48 框防挤压顶栏布局
            .frame(width: 48, height: 48)
            .menuIndicator(.hidden)
            .accessibilityIdentifier("pager_more")
        }
        .padding(.leading, 4)
        .padding(.trailing, 16)  // dump：更多按钮右缘 52px≈16dp
        .frame(height: 68)       // dump：栏内容高 (362-133)px≈69dp
        .foregroundStyle(.white)
        .background(Color.black.opacity(0.85))
    }

    /// 底栏（dump：4 位 SpaceEvenly，按钮 48dp，icon 22dp + label 12sp）：
    /// 发送/删除实做；编辑/证照 iOS 无功能（Phase 6）灰置占位，保持 4 位节奏
    private var bottomBar: some View {
        HStack(spacing: 0) {
            Spacer()
            bottomBarItem(systemName: "paperplane",
                          title: String(localized: "Send"),
                          accessibilityID: "pager_share") { shareCurrent() }
            Spacer()
            bottomBarItem(systemName: "wand.and.stars",
                          title: String(localized: "Edit"),
                          accessibilityID: "pager_edit",
                          isEnabled: false) {}
            Spacer()
            bottomBarItem(systemName: "person.text.rectangle",
                          title: String(localized: "ID"),
                          accessibilityID: "pager_id_photo",
                          isEnabled: false) {}
            Spacer()
            bottomBarItem(systemName: "trash",
                          title: String(localized: "Delete"),
                          accessibilityID: "pager_delete") { showDeleteConfirm = true }
            Spacer()
        }
        .padding(.vertical, 10)
        .foregroundStyle(.white)
        .background(Color.black.opacity(0.85))
    }

    private func bottomBarItem(systemName: String, title: String,
                               accessibilityID: String,
                               isEnabled: Bool = true,
                               action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(spacing: 4) {
                Image(systemName: systemName).font(.system(size: 22))
                Text(title).font(.system(size: 12))
            }
            .frame(width: 48, height: 48)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .foregroundStyle(isEnabled ? Color.white : Color.white.opacity(0.3))
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
    let onTap: () -> Void
    let onZoomChange: (Bool) -> Void

    @State private var image: UIImage?
    @State private var scale: CGFloat = 1
    @State private var lastScale: CGFloat = 1
    @State private var offset: CGSize = .zero
    @State private var lastOffset: CGSize = .zero

    var body: some View {
        GeometryReader { geo in
            Group {
                if let image {
                    Image(uiImage: image).resizable().scaledToFit()
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
            if !active { resetZoom() }
        }
        .task(id: localIdentifier) {
            image = await ThumbnailLoader.shared.thumbnail(
                for: localIdentifier,
                size: CGSize(width: 1600, height: 1600),
                highQuality: true)  // 大图要高清档（🟡-8）
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
