import SwiftUI
import UIKit

// MARK: - USER_IMAGE_TEXT 上图下文缩略图（PHAsset，对齐 Android AsyncImage 气泡）

/// 用户气泡顶部图片：localIdentifier → ThumbnailLoader 缩略图（async），缺图给占位图标。
///
/// 尺寸契约（Android ChatScreen.kt USER_IMAGE_TEXT，chat.yaml §5 user_image_bubble）：
/// `ContentScale.FillWidth` + fillMaxWidth —— 宽撑满图类气泡（240 上限 - 2×6 padding），
/// **高随原始宽高比，不裁切**（修复 2026-08-16 真机反馈：固定高 150 + fill 中心裁切
/// 导致「砍头 2」与大小不对齐）。圆角 12（Android 12dp）。
struct UserImageAttachment: View {
    let localIdentifier: String
    var onTap: (UIImage?) -> Void = { _ in }
    @State private var image: UIImage?

    var body: some View {
        Group {
            if let image {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFit()   // FillWidth 语义：宽撑满、高随宽高比，零裁切
            } else {
                // 加载占位（高度取原视觉尺寸；加载完成按真实宽高比替换）
                ZStack {
                    Color.white.opacity(0.15)
                    Image(matIcon: "photo")
                        .font(.system(size: 22))
                        .foregroundColor(.white.opacity(0.55))
                }
                .frame(height: 150)
            }
        }
        .frame(maxWidth: .infinity)
        .clipShape(RoundedRectangle(cornerRadius: 12))
        .contentShape(Rectangle())
        .onTapGesture { onTap(image) }
        .task {
            image = await ThumbnailLoader.shared.thumbnail(
                for: localIdentifier, size: CGSize(width: 720, height: 720)
            )
        }
    }
}

// MARK: - AGENT_EDIT_RESULT 编辑结果图卡（文件加载 + 失效占位，对齐 Android chatImageIsLive）

/// 编辑结果图：Documents/chat_edits 文件路径 → UIImage；文件缺失显示失效占位
/// （Android 语义：编辑产物文件可能被清理，显示「图片已失效」而非空白）。
///
/// 尺寸契约（Android ChatScreen.kt AGENT_EDIT_RESULT，chat.yaml §5 edit_result_bubble）：
/// `ContentScale.FillHeight` + height(200) + widthIn(max 260)——高定 200，超宽横向
/// 居中裁切；圆角 12。
struct ChatEditImageCard: View {
    let imagePath: String
    var onTap: (UIImage?) -> Void = { _ in }
    @State private var image: UIImage?

    private var fileExists: Bool {
        FileManager.default.fileExists(atPath: imagePath)
    }

    var body: some View {
        Group {
            if let image {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFill()   // FillHeight 语义：高 200 定尺，超宽裁切
                    .frame(height: 200)
                    .frame(maxWidth: 260)
                    .clipped()
                    .clipShape(RoundedRectangle(cornerRadius: 12))
                    .contentShape(Rectangle())
                    .onTapGesture { onTap(image) }
            } else if fileExists {
                // 加载中（同步读放 .task，避免卡主线程首帧）
                Color(.secondarySystemBackground)
                    .frame(height: 200)
                    .task { image = UIImage(contentsOfFile: imagePath) }
            } else {
                // 失效占位（对齐 Android 过期图提示）
                VStack(spacing: 6) {
                    Image(matIcon: "broken_image")
                        .font(.system(size: 24))
                        .foregroundColor(.secondary.opacity(0.6))
                    Text(String(localized: "Image no longer available"))
                        .font(.system(size: 12))
                        .foregroundColor(.secondary)
                }
                .frame(height: 120)
                .frame(maxWidth: .infinity)
                .background(Color(.secondarySystemBackground))
                .clipShape(RoundedRectangle(cornerRadius: 10))
            }
        }
        .frame(maxWidth: .infinity)
        .accessibilityIdentifier("chat_edit_image")
    }
}

// MARK: - 全屏图片预览（点 chat 图片/媒体卡打开；捏合缩放 1-5x，点图或 × 关闭）

struct ChatImagePreview: View {
    let image: UIImage
    @Environment(\.dismiss) private var dismiss
    @State private var scale: CGFloat = 1

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            Image(uiImage: image)
                .resizable()
                .aspectRatio(contentMode: .fit)
                .scaleEffect(scale)
                .gesture(
                    MagnificationGesture()
                        .onChanged { value in scale = min(max(value, 1), 5) }
                        .onEnded { _ in if scale < 1.05 { scale = 1 } }
                )
                .onTapGesture { dismiss() }
            VStack {
                HStack {
                    Spacer()
                    Button {
                        dismiss()
                    } label: {
                        Image(systemName: "xmark.circle.fill")
                            .font(.system(size: 28))
                            .foregroundColor(.white.opacity(0.85))
                            .padding(16)
                    }
                    .accessibilityIdentifier("chat_image_preview_close")
                }
                Spacer()
            }
        }
    }
}
