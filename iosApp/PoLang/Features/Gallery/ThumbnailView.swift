import SwiftUI

/// 网格缩略图（对齐 Android `MediaGrid.kt` 的 MediaItem）：
/// 正方形自适应格宽、圆角 2pt、surface 占位色、视频居中播放标记（32pt 白 80%）、
/// 人脸感知纵向对齐（公式对齐 `FaceAwareAlignment.kt:20-33`）。
struct ThumbnailView: View {
    let localIdentifier: String
    /// 人脸纵向聚焦点（归一化 0~1，nil = 无人脸/未回填 → 居中裁切）
    var faceFocusY: Float? = nil
    var isVideo: Bool = false
    /// 圆角（默认 2pt 相册网格；人物卡封面用 16pt 等覆盖）
    var cornerRadius: CGFloat = 2
    @State private var image: UIImage?

    var body: some View {
        GeometryReader { geo in
            ZStack {
                Color(.secondarySystemBackground)  // surface 占位色
                if let image {
                    faceAwareImage(image, in: geo.size)
                }
                if isVideo {
                    Image(systemName: "play.circle.fill")
                        .font(.system(size: 32))
                        .foregroundStyle(.white.opacity(0.8))
                }
            }
        }
        .aspectRatio(1, contentMode: .fit)
        .clipShape(RoundedRectangle(cornerRadius: cornerRadius))
        .clipped()
        .task(id: localIdentifier) {
            // 高清档 400px：覆盖 adaptive 列宽（最小 110pt）@3x
            image = await ThumbnailLoader.shared.thumbnail(
                for: localIdentifier, size: CGSize(width: 400, height: 400))
        }
        .accessibilityIdentifier("thumb_\(localIdentifier)")
    }

    /// 人脸感知对齐（公式对齐 Android FaceAwareAlignment）：
    /// scale = max(w/iw, h/ih)；rawY = h/2 − h/6 − f·scaledH；clamp 到 [h−scaledH, 0]。
    /// faceFocusY == nil → 垂直居中裁切。
    private func faceAwareImage(_ image: UIImage, in size: CGSize) -> some View {
        let iw = max(image.size.width, 1)
        let ih = max(image.size.height, 1)
        let scale = max(size.width / iw, size.height / ih)
        let scaledW = iw * scale
        let scaledH = ih * scale
        var offsetY = (size.height - scaledH) / 2  // 默认居中
        if let focusY = faceFocusY {
            let rawY = size.height / 2 - size.height / 6 - CGFloat(focusY) * scaledH
            offsetY = min(0, max(size.height - scaledH, rawY))
        }
        return Image(uiImage: image)
            .resizable()
            .frame(width: scaledW, height: scaledH)
            .offset(y: offsetY)
            .frame(width: size.width, height: size.height, alignment: .top)
            .clipped()
    }
}
