import SwiftUI

/// 日期/地点分组头（对齐 Android `MediaGroupHeader.kt`）：
/// 标题左对齐（16sp Bold + primary 色 → accentColor，对齐 titleMedium），`(N)` 计数**右对齐**（14sp secondary，对齐 bodyMedium），
/// padding h16/v8，整行高 156px≈47dp，整行可点。
/// 注：上一轮误把计数放在标题右侧相邻位——dump 显示计数右缘对齐（x1085–1141px），已修正。
struct GroupHeaderView: View {
    let title: String
    let count: Int
    var onTap: (() -> Void)? = nil

    var body: some View {
        HStack(spacing: 4) {
            Text(title)
                .font(.system(size: 16, weight: .bold))
                .foregroundStyle(Color.accentColor)
            Spacer(minLength: 0)
            Text("(\(count))")
                .font(.system(size: 14))
                .foregroundStyle(.secondary)
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
