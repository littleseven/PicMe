import SwiftUI

/// 证照屏底色面板（对标 specs/screens/idphoto.yaml §3；定稿截图 tmp/ui-reference/idphoto-02-screen.png）。
/// 左对齐色板行（间距 spacing.lg=16）：标准蓝/标准红/白 三圆形色块（顺序契约 IDPhotoColorSpec.allCases）。
/// 选中 3pt primary 描边；未选中 1pt 灰描边；点选即时重算底图。
struct ColorSwatchRow: View {
    let selectedIndex: Int
    let onSelect: (Int) -> Void

    var body: some View {
        HStack(spacing: Spacing.lg) {
            ForEach(IDPhotoColorSpec.allCases.indices, id: \.self) { index in
                swatch(index)
            }
        }
    }

    private func swatch(_ index: Int) -> some View {
        let spec = IDPhotoColorSpec.allCases[index]
        let isSelected = index == selectedIndex
        let scheme = AppColorScheme.dark
        return Button {
            onSelect(index)
        } label: {
            Circle()
                .fill(swatchColor(spec))
                .frame(width: IdPhotoTokens.swatchSize, height: IdPhotoTokens.swatchSize)
                .overlay(
                    Circle().stroke(
                        isSelected ? scheme.primary : scheme.outline,
                        lineWidth: isSelected
                            ? IdPhotoTokens.swatchSelectedBorderWidth
                            : IdPhotoTokens.swatchUnselectedBorderWidth
                    )
                )
        }
        .buttonStyle(.plain)
        .accessibilityLabel(L(spec.labelKey))   // §3 a11y：颜色名
        .accessibilityAddTraits(isSelected ? [.isSelected] : [])
    }

    /// 色板颜色（token idphoto.color*，按 IDPhotoColorSpec 顺序契约映射）
    private func swatchColor(_ spec: IDPhotoColorSpec) -> Color {
        switch spec {
        case .blue: return IdPhotoTokens.colorBlue
        case .red: return IdPhotoTokens.colorRed
        case .white: return IdPhotoTokens.colorWhite
        }
    }
}
