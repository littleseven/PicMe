import SwiftUI

/// 证照屏底部 Tab 行（对标 specs/screens/idphoto.yaml §2.3；定稿截图 tmp/ui-reference/idphoto-02-screen.png）。
/// 底色/尺寸/边缘/修补 四个 filter chip spaceEvenly 平铺：
/// 选中 = colorScheme.primary(dark) 容器 + onPrimary 字；未选中 = idphoto.tabUnselectedContainer + 白字。
struct IdPhotoTabRow: View {
    let selected: IdPhotoTab
    let onSelect: (IdPhotoTab) -> Void

    var body: some View {
        HStack(spacing: 0) {
            ForEach(IdPhotoTab.allCases) { tab in
                chip(tab)
                    .frame(maxWidth: .infinity)   // 等宽槽位 ≈ Arrangement.SpaceEvenly
            }
        }
        .frame(maxWidth: .infinity)
    }

    private func chip(_ tab: IdPhotoTab) -> some View {
        let isSelected = tab == selected
        let scheme = AppColorScheme.dark
        return Button {
            onSelect(tab)
        } label: {
            Text(L(tab.labelKey))
                .font(AppTypography.labelLarge.font)
                .foregroundStyle(isSelected ? scheme.onPrimary : .white)
                .padding(.horizontal, Spacing.lg)
                .frame(height: ChipTokens.smallHeight)
                .background(
                    RoundedRectangle(cornerRadius: AppRadius.small)
                        .fill(isSelected ? scheme.primary : IdPhotoTokens.tabUnselectedContainer)
                )
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(isSelected ? [.isSelected] : [])
    }
}

private extension IdPhotoTab {
    /// Tab 标题键（§11；域类型 IdPhotoTab 无 labelKey——UI 侧映射，顺序契约 bgColor/size/edge/repair）
    var labelKey: String {
        switch self {
        case .bgColor: return "id_photo_tab_color"
        case .size: return "id_photo_tab_size"
        case .edge: return "id_photo_tab_edge"
        case .repair: return "id_photo_tab_repair"
        }
    }
}
