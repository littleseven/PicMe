import SwiftUI
import UIKit

/// 滤镜选择器（对标 Android FilterSelector.kt:50-125 + dump）
/// 5 列 LazyVGrid，面板占屏 53%，14 款（9 色调 + 5 风格占位）
struct FilterSelectorView: View {
    @Binding var selectedFilter: FilterType

    private let columns = Array(repeating: GridItem(.flexible(), spacing: 4), count: 5)

    var body: some View {
        LazyVGrid(columns: columns, spacing: 8) {
            // 9 色调滤镜（已实现 ColorMatrix）
            ForEach(FilterType.allCases) { filter in
                FilterThumbnailView(
                    filter: filter,
                    isSelected: selectedFilter == filter
                ) {
                    selectedFilter = filter
                }
            }
            // 5 风格滤镜占位（Phase 6，对标 Android style/*.glsl）
            ForEach(StyleFilterPlaceholder.allCases) { style in
                FilterThumbnailView(
                    filter: nil,
                    stylePlaceholder: style,
                    isSelected: false
                ) { }
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .accessibilityIdentifier("filter_selector")
    }
}

/// 风格滤镜占位（Phase 6：TOON/SKETCH/POSTERIZE/EMBOSS/CROSSHATCH）
/// 对标 Android StyleFilter.kt ordinal 顺序 + FilterSelector.kt:69-77 排序
enum StyleFilterPlaceholder: String, CaseIterable, Identifiable {
    case toon = "卡通"
    case sketch = "素描"
    case posterize = "色块"
    case emboss = "浮雕"
    case crosshatch = "交叉线"

    var id: String { rawValue }
    var thumbnailName: String {
        switch self {
        case .toon: return "style_toon"
        case .sketch: return "style_sketch"
        case .posterize: return "style_posterize"
        case .emboss: return "style_emboss"
        case .crosshatch: return "style_crosshatch"
        }
    }
}

private struct FilterThumbnailView: View {
    var filter: FilterType?
    var stylePlaceholder: StyleFilterPlaceholder?
    var isSelected: Bool
    let onTap: () -> Void

    var body: some View {
        VStack(spacing: 4) {
            ZStack {
                Circle()
                    .fill(Color.gray.opacity(0.2))
                    .frame(width: 56, height: 56)
                    .overlay {
                        if let image = thumbnailImage {
                            Image(uiImage: image)
                                .resizable()
                                .scaledToFill()
                                .frame(width: 56, height: 56)
                                .clipShape(Circle())
                        }
                    }
                    .overlay {
                        Circle()
                            .strokeBorder(
                                isSelected ? Color.accentColor : Color.clear,
                                lineWidth: 2.5
                            )
                    }
                if isSelected {
                    Circle()
                        .fill(Color.accentColor.opacity(0.25))
                        .frame(width: 56, height: 56)
                    MatIcon(name: "checkmark", size: 14)
                        .foregroundColor(.accentColor)
                }
                // Phase 6 占位标记
                if stylePlaceholder != nil {
                    MatIcon(name: "lock.fill", size: 14)
                        .foregroundColor(.white.opacity(0.4))
                }
            }
            .scaleEffect(isSelected ? 1.08 : 1.0)
            .animation(.easeInOut(duration: 0.15), value: isSelected)

            Text(displayName)
                .font(.system(size: 10))
                .fontWeight(isSelected ? .bold : .regular)
                .foregroundColor(isSelected ? .accentColor : .white.opacity(0.85))
                .lineLimit(1)
        }
        .accessibilityIdentifier("filter_\(accessibilitySuffix)")
        .onTapGesture(perform: onTap)
    }

    private var displayName: String {
        if let filter { return filter.displayName }
        return stylePlaceholder?.rawValue ?? ""
    }

    private var accessibilitySuffix: String {
        if let filter { return filter.thumbnailName }
        return stylePlaceholder?.thumbnailName ?? "unknown"
    }

    private var thumbnailImage: UIImage? {
        // 复用 Android 端同名 JPG 缩略图（assets/filters/*.jpg，已 bundle）
        if let filter { return filterThumbnailImage(named: filter.thumbnailName) }
        if let stylePlaceholder { return filterThumbnailImage(named: stylePlaceholder.thumbnailName) }
        return nil
    }
}

#Preview {
    FilterSelectorView(selectedFilter: .constant(.none))
}
