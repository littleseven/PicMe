import SwiftUI
import SharedKit
import UIKit

/// 大图浏览（对齐 Android `MediaPager.kt` 重做）：
/// 黑底横滑分页（去系统 page dots，页间距 16）、双指缩放 1–4x + 平移（clamp 公式对齐
/// `ZoomableImage:344-417`）、单击切换顶/底栏显隐（缩放时强制隐藏）、
/// 顶栏（返回 + yyyy-MM-dd 日期 + Info）、底栏（分享/删除，仅照片）。
/// 编辑/证件照 iOS 无对应功能（Phase 6），不假造入口；删除走 PHAssetChangeRequest 系统确认窗。
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
        .statusBarHidden(isZoomed || !barsVisible)
        .sheet(isPresented: $showInfo) {
            if let asset = currentAsset { PhotoInfoSheet(asset: asset) }
        }
        .sheet(item: $sharePayload) { payload in
            ActivityView(activityItems: [payload.image])
        }
        .confirmationDialog(String(localized: "Delete this photo?"),
                            isPresented: $showDeleteConfirm, titleVisibility: .visible) {
            Button(String(localized: "Delete"), role: .destructive) { deleteCurrent() }
            Button(String(localized: "Cancel"), role: .cancel) {}
        }
        .accessibilityIdentifier("media_pager")
    }

    /// 顶栏（48pt 黑半透明，对齐 Android：返回 + 日期 + Info；debug 菜单不移植）
    private var topBar: some View {
        HStack(spacing: 8) {
            Button { dismiss() } label: {
                Image(systemName: "chevron.left")
                    .font(.system(size: 20))
                    .frame(width: 36, height: 36)
                    .contentShape(Rectangle())
            }
            .accessibilityIdentifier("pager_back")
            Spacer()
            Text(formattedDate(currentAsset?.captureDate))
                .font(.system(size: 17, weight: .medium))
                .lineLimit(1)
            Spacer()
            Button { showInfo = true } label: {
                Image(systemName: "info.circle")
                    .font(.system(size: 20))
                    .frame(width: 36, height: 36)
                    .contentShape(Rectangle())
            }
            .accessibilityIdentifier("pager_info")
        }
        .padding(.horizontal, 8)
        .frame(height: 48)
        .foregroundStyle(.white)
        .background(Color.black.opacity(0.85))
    }

    /// 底栏（黑半透明，icon+label 均布，对齐 Android：分享/编辑/证件照/删除；
    /// 编辑与证件照 iOS 无对应功能（Phase 6），只实做分享与删除）
    private var bottomBar: some View {
        HStack(spacing: 0) {
            bottomBarItem(systemName: "square.and.arrow.up",
                          title: String(localized: "Share"),
                          accessibilityID: "pager_share") { shareCurrent() }
            bottomBarItem(systemName: "trash",
                          title: String(localized: "Delete"),
                          accessibilityID: "pager_delete") { showDeleteConfirm = true }
        }
        .padding(.vertical, 10)
        .foregroundStyle(.white)
        .background(Color.black.opacity(0.85))
    }

    private func bottomBarItem(systemName: String, title: String,
                               accessibilityID: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(spacing: 4) {
                Image(systemName: systemName).font(.system(size: 24))
                Text(title).font(.system(size: 12))
            }
            .frame(maxWidth: .infinity)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
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
                sharePayload = SharePayload(image: image)
            }
        }
    }

    private func deleteCurrent() {
        guard let asset = currentAsset else { return }
        _ = bridge.deleteMedia(localIdentifiers: [asset.uri])
        dismiss()  // 删除后退出大图页；网格经 PHPhotoLibraryObserver 自动刷新
    }
}

/// 分享 payload（Identifiable 以驱动 sheet(item:)）
private struct SharePayload: Identifiable {
    let id = UUID()
    let image: UIImage
}

/// UIActivityViewController 桥（系统分享面板）
private struct ActivityView: UIViewControllerRepresentable {
    let activityItems: [Any]
    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: activityItems, applicationActivities: nil)
    }
    func updateUIViewController(_ uiViewController: UIActivityViewController, context: Context) {}
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
