import SwiftUI

/// 证照屏尺寸面板（对标 specs/screens/idphoto.yaml §4；定稿截图 tmp/ui-reference/idphoto-03-tab-size.png）。
/// 1寸/2寸/小1寸/小2寸 assist chip（间距 spacing.sm=8，顺序契约 IDPhotoSizeSpec.allCases）：
/// 选中 = primaryContainer 底 + onPrimaryContainer 字 + 无边框；
/// 未选中 = 透明底白字 + 1pt White alpha(idphoto.sizeChipUnselectedBorderAlpha) 边框。
/// 点选 → 预览框宽高比随之联动（§2.1）。
struct SizeChipRow: View {
    let selectedIndex: Int
    let onSelect: (Int) -> Void

    var body: some View {
        HStack(spacing: Spacing.sm) {
            ForEach(IDPhotoSizeSpec.allCases.indices, id: \.self) { index in
                chip(index)
            }
        }
    }

    private func chip(_ index: Int) -> some View {
        let spec = IDPhotoSizeSpec.allCases[index]
        let isSelected = index == selectedIndex
        let scheme = AppColorScheme.dark
        return Button {
            onSelect(index)
        } label: {
            Text(L(spec.labelKey))
                .font(AppTypography.labelLarge.font)
                .foregroundStyle(isSelected ? scheme.onPrimaryContainer : .white)
                .padding(.horizontal, Spacing.lg)
                .frame(height: ChipTokens.smallHeight)
                .background(
                    RoundedRectangle(cornerRadius: AppRadius.small)
                        .fill(isSelected ? scheme.primaryContainer : .clear)
                )
                .overlay(
                    // 未选中才有边框（§4：1dp White alpha 0.5；宽 1 复用 swatchUnselectedBorderWidth token）
                    Group {
                        if !isSelected {
                            RoundedRectangle(cornerRadius: AppRadius.small)
                                .strokeBorder(
                                    Color.white.opacity(IdPhotoTokens.sizeChipUnselectedBorderAlpha),
                                    lineWidth: IdPhotoTokens.swatchUnselectedBorderWidth
                                )
                        }
                    }
                )
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(isSelected ? [.isSelected] : [])
    }
}
