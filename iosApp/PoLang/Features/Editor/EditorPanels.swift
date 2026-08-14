import SwiftUI

// 编辑器底部组件与各 tab 面板（editor.yaml §5/§7-§11）。
// 复用 AppSlider（DesignSystem）、EditorTokens/ChipTokens/Spacing。
// FILTER: 9 色调 + 5 风格（14 项，互斥）；BEAUTY: 滑杆可调 + 参数存档，渲染 DEFER（B1）。

// MARK: - 通用 Chip

struct EditorChip: View {
    let title: String
    var isSelected: Bool = false
    var icon: String? = nil
    let action: () -> Void

    var body: some View {
        // 编辑器画布锁黑底——语义色固定暗色档
        let s = AppColorScheme.dark
        Button(action: action) {
            HStack(spacing: 4) {
                if let icon { Image(systemName: icon).font(.system(size: 14)) }
                Text(title).font(.system(size: 14))
            }
            .frame(height: ChipTokens.height)
            .padding(.horizontal, 12)
            .background(isSelected
                        ? s.primaryContainer
                        : s.surfaceVariant.opacity(ChipTokens.unselectedContainerAlpha))
            .foregroundStyle(isSelected ? s.onPrimaryContainer : s.onSurfaceVariant)
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
        .frame(maxWidth: .infinity, maxHeight: EditorTokens.adjustPanelMaxHeight)
    }
}

// MARK: - FilterPanel（9 色调 + 5 风格 = 14 项；color/style 互斥）

struct FilterPanel: View {
    @Binding var colorFilter: FilterType
    @Binding var styleFilter: StyleFilter
    var body: some View {
        let s = AppColorScheme.dark
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: Spacing.md) {
                // 9 色调滤镜
                ForEach(FilterType.allCases) { f in
                    filterItem(
                        label: f.displayName,
                        thumbnail: filterThumbnailImage(named: f.thumbnailName),
                        isSelected: f == .none
                            ? (colorFilter == .none && styleFilter == .none)
                            : colorFilter == f,
                        scheme: s
                    ) {
                        colorFilter = f
                        styleFilter = .none   // 互斥
                    }
                }
                // 5 风格滤镜（不含 .none）
                ForEach(StyleFilter.allCases.filter { $0 != .none }) { sf in
                    filterItem(
                        label: sf.displayName,
                        thumbnail: filterThumbnailImage(named: sf.thumbnailName),
                        isSelected: styleFilter == sf,
                        scheme: s
                    ) {
                        styleFilter = sf
                        colorFilter = .none    // 互斥
                    }
                }
            }
            .padding(.horizontal, Spacing.lg)
            .padding(.vertical, Spacing.sm)
        }
        .frame(height: EditorTokens.filterPanelHeight)
    }

    // MARK: 单个滤镜条目

    @ViewBuilder
    private func filterItem(label: String, thumbnail: UIImage?,
                            isSelected: Bool, scheme s: SchemeColors,
                            onTap: @escaping () -> Void) -> some View {
        VStack(spacing: 4) {
            ZStack(alignment: .bottom) {
                // 真实缩略图（assets filters/*.jpg 圆裁剪，与 Android 同图）；无图时回退语义灰底
                Circle()
                    .fill(s.surfaceVariant.opacity(0.5))
                    .frame(width: EditorTokens.filterThumbSize,
                           height: EditorTokens.filterThumbSize)
                    .overlay {
                        if let thumbnail {
                            Image(uiImage: thumbnail)
                                .resizable()
                                .scaledToFill()
                                .frame(width: EditorTokens.filterThumbSize,
                                       height: EditorTokens.filterThumbSize)
                                .clipShape(Circle())
                        }
                    }
                // 选中底部横条 overlay（spec selected_overlay）
                if isSelected {
                    Capsule()
                        .fill(s.primary.opacity(0.25))
                        .frame(width: EditorTokens.filterThumbSize - 12, height: 4)
                        .padding(.bottom, 4)
                }
                // 边框
                Circle()
                    .strokeBorder(
                        isSelected
                        ? LinearGradient(colors: [s.primary, s.onSurface],
                                         startPoint: .topLeading, endPoint: .bottomTrailing)
                        : LinearGradient(colors: [s.onSurface.opacity(0.3), s.onSurface.opacity(0.1)],
                                         startPoint: .top, endPoint: .bottom),
                        lineWidth: isSelected
                        ? EditorTokens.filterSelectedBorderWidth
                        : EditorTokens.filterUnselectedBorderWidth)
                    .frame(width: EditorTokens.filterThumbSize,
                           height: EditorTokens.filterThumbSize)
            }
            Text(label)
                .font(.system(size: EditorTokens.filterLabelSize))
                .foregroundStyle(isSelected ? s.primary : s.onSurface.opacity(0.85))
        }
        .frame(width: EditorTokens.filterItemWidth)
        .contentShape(Rectangle())
        .onTapGesture(perform: onTap)
    }
}

// MARK: - MarkupPanel（工具/颜色/粗细）

struct MarkupPanel: View {
    @ObservedObject var toolState: MarkupToolState
    @Binding var actions: [MarkupAction]
    var body: some View {
        let s = AppColorScheme.dark
        VStack(spacing: Spacing.sm) {
            // 工具行（spec tool_row: spaceEvenly）
            HStack {
                EditorChip(title: String(localized: "Doodle"),
                           isSelected: toolState.tool == .doodle,
                           icon: "paintbrush") { toolState.tool = .doodle }
                Spacer()
                EditorChip(title: String(localized: "Mosaic"),
                           isSelected: toolState.tool == .mosaic,
                           icon: "circle.hexagongrid") { toolState.tool = .mosaic }
                Spacer()
                EditorChip(title: String(localized: "Text"),
                           isSelected: toolState.tool == .text,
                           icon: "textformat") { toolState.tool = .text }
            }
            // 颜色行（spec color_row: pad top12）
            HStack(spacing: Spacing.sm) {
                ForEach(MarkupConstants.colors, id: \.self) { c in
                    let selected = toolState.color == c
                    Circle()
                        .fill(Color(uiColor: UIColor(argb: c)))
                        .frame(width: selected ? EditorTokens.markupSwatchSelected : EditorTokens.markupSwatchUnselected,
                               height: selected ? EditorTokens.markupSwatchSelected : EditorTokens.markupSwatchUnselected)
                        .overlay(Circle().stroke(selected ? s.primary : s.outline,
                                                 lineWidth: selected ? 2 : 1))
                        .contentShape(Circle())
                        .onTapGesture { toolState.color = c }
                }
            }
            .padding(.top, Spacing.md)
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

// MARK: - BeautyPanel（B1：滑杆可调 + 参数存档，预览/保存不渲染）

struct BeautyPanel: View {
    @Binding var beauty: BeautySettings
    var body: some View {
        let s = AppColorScheme.dark
        VStack(spacing: Spacing.sm) {
            // 提示文案（spec beauty_panel：beauty_preview_unavailable）
            Text(L("beauty_preview_unavailable"))
                .font(.system(size: 12))
                .foregroundStyle(s.onSurfaceVariant)
                .multilineTextAlignment(.center)
                .padding(.bottom, Spacing.xs)
            ScrollView(.vertical, showsIndicators: false) {
                VStack(spacing: Spacing.sm) {
                    ForEach(BeautySettings.Param.allCases, id: \.self) { param in
                        HStack {
                            Text(String(localized: String.LocalizationValue(param.labelKey)))
                                .font(.system(size: 14))
                                .frame(width: 56, alignment: .leading)
                            AppSlider(value: param.get(beauty),
                                      range: BeautySettings.Param.range) { v in
                                param.set(&beauty, v)
                            }
                            Text(String(format: "%.0f", param.get(beauty)))
                                .font(.system(size: 14))
                                .foregroundStyle(s.onSurfaceVariant)
                                .frame(width: 32, alignment: .trailing)
                        }
                    }
                }
            }
        }
        .padding(.horizontal, Spacing.lg)
        .padding(.vertical, Spacing.sm)
        .frame(maxHeight: EditorTokens.adjustPanelMaxHeight)
    }
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
