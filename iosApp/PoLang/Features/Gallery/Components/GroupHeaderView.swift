import SwiftUI

/// 日期分组头（对齐 Android `MediaGroupHeader.kt`）：
/// 标题 titleMedium Bold + accentColor（Material primary 的 SwiftUI 对应）、
/// `(N)` 计数 bodyMedium + secondary 色，padding h16/v8，整行可点。
struct GroupHeaderView: View {
    let title: String
    let count: Int
    var onTap: (() -> Void)? = nil

    var body: some View {
        HStack(spacing: 4) {
            Text(title)
                .font(.headline.bold())
                .foregroundStyle(Color.accentColor)
            Text("(\(count))")
                .font(.subheadline)
                .foregroundStyle(.secondary)
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 8)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color(.systemBackground))
        .contentShape(Rectangle())
        .onTapGesture { onTap?() }
        .accessibilityIdentifier("group_\(title)")
    }
}
