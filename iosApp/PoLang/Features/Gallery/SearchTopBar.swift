import SwiftUI

/// 搜索态顶栏（spec gallery-grid.yaml §search_top_bar）：
/// 替换常态 AppTopBar，leading 返回（chevron.left → 退出搜索态）+ center 胶囊搜索框
/// + trailing 结果计数（仅搜索完成后显示）。
///
/// 胶囊搜索框引用 SearchFieldTokens 全套：24pt 圆角 / surfaceVariant@0.7 背景 /
/// padding 12/8 / 搜索图标@0.5 / 清除 xmark 20-16@0.5 / placeholder@0.4 / 14pt / cursor=accentColor。
/// 进入即 autofocus，singleLine。
///
/// 所有尺寸引用 DesignTokens；颜色走 iOS 语义角色（对齐项目现有用法）。
struct SearchTopBar: View {
    /// 双向绑定查询文本（由父视图绑定到 ViewModel.searchQuery）
    @Binding var query: String
    /// nil = 尚未搜索过（不渲染计数）；some(count) = 搜索已完成
    var resultCount: Int?
    /// 返回按钮：清空并退出搜索态（back_stack search_active 分支）
    var onBack: () -> Void
    /// 查询文本变化回调（触发防抖搜索）
    var onQueryChange: (String) -> Void

    @FocusState private var isFocused: Bool

    var body: some View {
        HStack(spacing: SearchFieldTokens.iconGap) {
            // ── leading：返回 ──
            Button(action: onBack) {
                MatIcon(name: "chevron.left", size: TopBarTokens.iconSize)
                    .frame(width: TopBarTokens.buttonSize, height: TopBarTokens.buttonSize)
                    .foregroundStyle(Color.primary)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("search_back")

            // ── center：胶囊搜索框 ──
            // spec: surfaceVariant@alpha0.7 → iOS semantic: secondarySystemBackground
            HStack(spacing: SearchFieldTokens.iconGap) {
                MatIcon(name: "magnifyingglass", size: SearchFieldTokens.fontSize)
                    .foregroundStyle(Color.primary.opacity(AppAlpha.hint))

                TextField(String(localized: "gallery_search_hint"), text: $query)
                    .font(.system(size: SearchFieldTokens.fontSize))
                    .foregroundStyle(Color.primary)
                    .focused($isFocused)
                    .submitLabel(.search)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .accessibilityIdentifier("search_field")

                if !query.isEmpty {
                    Button {
                        query = ""          // 绑定直清；onChange 传播到 onQueryChange
                    } label: {
                        MatIcon(name: "xmark", size: SearchFieldTokens.clearIconSize)
                            .frame(width: SearchFieldTokens.clearButtonSize,
                                   height: SearchFieldTokens.clearButtonSize)
                            .foregroundStyle(Color.primary.opacity(AppAlpha.hint))
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityIdentifier("search_clear")
                }
            }
            .padding(.horizontal, SearchFieldTokens.paddingH)
            .padding(.vertical, SearchFieldTokens.paddingV)
            .background(Color(.secondarySystemBackground))
            .clipShape(Capsule())

            // ── trailing：结果计数（spec: visible_when search_finished）──
            if let count = resultCount {
                Text(String(format: String(localized: "people_photos_count"), count))
                    .font(.system(size: SearchFieldTokens.resultCountFontSize))
                    .foregroundStyle(Color.primary.opacity(AppAlpha.secondary))
                    .lineLimit(1)
                    .fixedSize()
            }
        }
        .padding(.horizontal, TopBarTokens.horizontalPadding)
        .frame(height: TopBarTokens.height)
        .frame(maxWidth: .infinity)
        .background(Color(.systemBackground))
        .onAppear { isFocused = true }   // spec: autofocus
        .onChange(of: query) { newValue in onQueryChange(newValue) }
    }
}

// MARK: - Preview

#if DEBUG
#Preview("SearchTopBar · 浅色") {
    VStack(spacing: 0) {
        SearchTopBar(
            query: .constant("大宝"),
            resultCount: 255,
            onBack: {},
            onQueryChange: { _ in }
        )
        Spacer()
    }
    .background(Color(.systemBackground))
}

#Preview("SearchTopBar · 深色") {
    VStack(spacing: 0) {
        SearchTopBar(
            query: .constant("sunset"),
            resultCount: 3,
            onBack: {},
            onQueryChange: { _ in }
        )
        Spacer()
    }
    .background(Color(.systemBackground))
    .preferredColorScheme(.dark)
}

#Preview("SearchTopBar · 空输入") {
    VStack(spacing: 0) {
        SearchTopBar(
            query: .constant(""),
            resultCount: nil,
            onBack: {},
            onQueryChange: { _ in }
        )
        Spacer()
    }
    .background(Color(.systemBackground))
}
#endif
