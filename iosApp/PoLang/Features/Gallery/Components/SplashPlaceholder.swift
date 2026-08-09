import SwiftUI

/// 冷启动占位页（对齐 Android `GalleryPermission.kt` 的 GallerySplashPlaceholder）：
/// 顶部 28% 留白 + 衬线斜体格言 + 作者，格言池与 Android 同源（GalleryQuotes.swift）。
struct SplashPlaceholder: View {
    /// 同一生命周期内格言稳定（SwiftUI @State 跨 body 重建保持，等价 Android rememberSaveable）
    @State private var quote: GalleryQuote = getQuotesForLocale().randomElement()
        ?? GalleryQuote(text: "", author: "")

    var body: some View {
        GeometryReader { geo in
            VStack(spacing: 0) {
                Spacer().frame(height: geo.size.height * 0.28)
                VStack(spacing: 12) {
                    Text(quote.text)
                        .font(.system(size: 20, design: .serif).italic())
                        .multilineTextAlignment(.center)
                    Text("— \(quote.author)")
                        .font(.system(size: 15, design: .serif))
                        .foregroundStyle(.secondary)
                }
                .padding(.horizontal, 32)
                Spacer()
            }
            .frame(width: geo.size.width)
        }
        .accessibilityIdentifier("gallery_splash")
    }
}
