import SwiftUI

// 编辑器底部组件与各 tab 面板（editor.yaml §5/§7-§11）。
// 复用 AppSlider（DesignSystem）、EditorTokens/ChipTokens/Spacing。
// 本轮 lite 差异：FILTER 仅 9 色调（无缩略图资源→文本 chip）；BEAUTY 渲染 DEFER（提示页）。

// MARK: - 通用 Chip

struct EditorChip: View {
    let title: String
    var isSelected: Bool = false
    var icon: String? = nil
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 4) {
                if let icon { Image(systemName: icon).font(.system(size: 14)) }
                Text(title).font(.system(size: 14))
            }
            .frame(height: ChipTokens.height - 8)
            .padding(.horizontal, 12)
            .background(isSelected
                        ? Color.accentColor.opacity(0.18)
                        : Color.secondary.opacity(ChipTokens.unselectedContainerAlpha))
            .foregroundStyle(isSelected ? Color.accentColor : Color.primary)
            .clipShape(Capsule())
        }
        .buttonStyle(.plain)
    }
}

// MARK: - EditorBottomBar（5 tab）

struct EditorBottomBar: View {
    let selectedTab: EditorTab
    let onSelect: (EditorTab) -> Void

    var body: some View {
        HStack(alignment: .center) {
            ForEach(EditorTab.allCases, id: \.self) { tab in
                EditorChip(
                    title: String(localized: String.LocalizationValue(tab.labelKey)),
                    isSelected: selectedTab == tab) {
                    onSelect(tab)
                }
                Spacer(minLength: 0)
            }
        }
        .padding(.horizontal, Spacing.sm)
        .padding(.vertical, Spacing.xs)
    }
}

// MARK: - CropPanel

struct CropPanel: View {
    @Binding var crop: CropRecipe

    var body: some View {
        VStack(alignment: .leading, spacing: Spacing.sm) {
            Text("Aspect Ratio").font(.system(size: 14, weight: .medium))
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: Spacing.sm) {
                    ForEach(AspectRatio.allCases, id: \.self) { ratio in
                        EditorChip(
                            title: String(localized: String.LocalizationValue(ratio.labelKey)),
                            isSelected: crop.aspectRatio == ratio) {
                            crop.aspectRatio = ratio
                        }
                    }
                }
            }
        }
        .padding(.horizontal, Spacing.lg)
        .padding(.vertical, Spacing.sm)
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

// MARK: - AdjustPanel

struct AdjustPanel: View {
    @Binding var adjustments: AdjustmentRecipe
    @State private var selected: AdjustmentRecipe.Param = .brightness

    var body: some View {
        VStack(spacing: Spacing.sm) {
            // 当前参数 + 数值 + 重置
            HStack {
                Text(String(localized: String.LocalizationValue(selected.labelKey)))
                    .font(.system(size: 16, weight: .medium))
                Spacer()
                Text(String(format: "%.0f", selected.get(adjustments)))
                    .font(.system(size: 16))
                    .foregroundStyle(.secondary)
                    .padding(.horizontal, Spacing.sm)
                Button(String(localized: "Reset")) {
                    selected.set(&adjustments, selected.resetValue)
                }
                .font(.system(size: 14))
            }
            AppSlider(value: selected.get(adjustments),
                      range: selected.range) { v in
                selected.set(&adjustments, v)
            }
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: Spacing.sm) {
                    ForEach(AdjustmentRecipe.Param.allCases, id: \.self) { p in
                        EditorChip(
                            title: String(localized: String.LocalizationValue(p.labelKey)),
                            isSelected: selected == p) {
                            selected = p
                        }
                    }
                }
            }
        }
        .padding(.horizontal, Spacing.lg)
        .padding(.vertical, Spacing.sm)
        .frame(maxWidth: .infinity)
    }
}

// MARK: - FilterPanel（9 色调滤镜；styleFilter 本轮 DEFER）

struct FilterPanel: View {
    @Binding var colorFilter: FilterType

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: Spacing.md) {
                ForEach(FilterType.allCases) { f in
                    VStack(spacing: 4) {
                        ZStack {
                            Circle()
                                .fill(Color.secondary.opacity(0.25))
                                .frame(width: EditorTokens.filterThumbSize,
                                       height: EditorTokens.filterThumbSize)
                            Circle()
                                .strokeBorder(
                                    f == colorFilter
                                    ? LinearGradient(colors: [.accentColor, .primary],
                                                     startPoint: .topLeading, endPoint: .bottomTrailing)
                                    : LinearGradient(colors: [.secondary.opacity(0.3)],
                                                     startPoint: .top, endPoint: .bottom),
                                    lineWidth: f == colorFilter
                                    ? EditorTokens.filterSelectedBorderWidth
                                    : EditorTokens.filterUnselectedBorderWidth)
                                .frame(width: EditorTokens.filterThumbSize,
                                       height: EditorTokens.filterThumbSize)
                        }
                        Text(f.displayName)
                            .font(.system(size: EditorTokens.filterLabelSize))
                            .foregroundStyle(f == colorFilter ? Color.accentColor : Color.primary.opacity(0.85))
                    }
                    .frame(width: EditorTokens.filterItemWidth)
                    .contentShape(Rectangle())
                    .onTapGesture { colorFilter = f }
                }
            }
            .padding(.horizontal, Spacing.lg)
            .padding(.vertical, Spacing.sm)
        }
        .frame(height: EditorTokens.filterPanelHeight)
    }
}

// MARK: - MarkupPanel（工具/颜色/粗细）

struct MarkupPanel: View {
    @ObservedObject var toolState: MarkupToolState
    @Binding var actions: [MarkupAction]

    var body: some View {
        VStack(spacing: Spacing.sm) {
            // 工具行
            HStack(spacing: Spacing.sm) {
                EditorChip(title: String(localized: "Doodle"),
                           isSelected: toolState.tool == .doodle,
                           icon: "paintbrush.pointed") { toolState.tool = .doodle }
                EditorChip(title: String(localized: "Mosaic"),
                           isSelected: toolState.tool == .mosaic,
                           icon: "circle.grid.cross") { toolState.tool = .mosaic }
                EditorChip(title: String(localized: "Text"),
                           isSelected: toolState.tool == .text,
                           icon: "textformat") { toolState.tool = .text }
            }
            // 颜色行
            HStack(spacing: Spacing.sm) {
                ForEach(MarkupConstants.colors, id: \.self) { c in
                    let selected = toolState.color == c
                    Circle()
                        .fill(Color(uiColor: UIColor(argb: c)))
                        .frame(width: selected ? EditorTokens.markupSwatchSelected : EditorTokens.markupSwatchUnselected,
                               height: selected ? EditorTokens.markupSwatchSelected : EditorTokens.markupSwatchUnselected)
                        .overlay(Circle().stroke(selected ? Color.accentColor : Color.secondary.opacity(0.5),
                                                 lineWidth: selected ? 2 : 1))
                        .contentShape(Circle())
                        .onTapGesture { toolState.color = c }
                }
            }
            // 粗细 + 清除
            HStack {
                Text("Stroke width").font(.system(size: 14, weight: .medium))
                AppSlider(value: toolState.strokeWidth,
                          range: MarkupConstants.strokeMin...MarkupConstants.strokeMax) { v in
                    toolState.strokeWidth = v
                }
                Button(String(localized: "Clear")) { actions = [] }
                    .font(.system(size: 14))
                    .disabled(actions.isEmpty)
            }
        }
        .padding(.horizontal, Spacing.lg)
        .padding(.vertical, Spacing.sm)
    }
}

// MARK: - MarkupToolState（工具/颜色/粗细，屏幕层持有）

final class MarkupToolState: ObservableObject {
    enum Tool { case doodle, mosaic, text }
    @Published var tool: Tool = .doodle
    @Published var color: Int = MarkupConstants.colors.first ?? 0xFF000000
    @Published var strokeWidth: Float = MarkupConstants.defaultStrokeWidth
}

// MARK: - UIColor(hex/argb) 扩展（DesignTokens 用 hex；此处 argb 复用）

extension UIColor {
    convenience init(argb: Int) {
        let a = CGFloat((argb >> 24) & 0xFF) / 255
        let r = CGFloat((argb >> 16) & 0xFF) / 255
        let g = CGFloat((argb >> 8) & 0xFF) / 255
        let b = CGFloat(argb & 0xFF) / 255
        self.init(red: r, green: g, blue: b, alpha: a)
    }
}
