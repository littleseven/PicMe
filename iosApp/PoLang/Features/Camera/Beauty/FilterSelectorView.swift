import SwiftUI
import UIKit

/// 滤镜选择器（对标 Android FilterSelector.kt:50-125）
/// 5 列 LazyVGrid，固定高度 280pt（对标 Android LazyVerticalGrid height=280dp）
/// StyleFilter 5 款留 Phase 6
struct FilterSelectorView: View {
    @Binding var selectedFilter: FilterType

    private let columns = Array(repeating: GridItem(.flexible(), spacing: 4), count: 5)

    var body: some View {
        ScrollView(.vertical) {
            LazyVGrid(columns: columns, spacing: 8) {
                ForEach(FilterType.allCases) { filter in
                    FilterThumbnailView(
                        filter: filter,
                        isSelected: selectedFilter == filter
                    ) {
                        selectedFilter = filter
                    }
                }
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
        }
        .frame(height: 280)
        .accessibilityIdentifier("filter_selector")
    }
}

private struct FilterThumbnailView: View {
    let filter: FilterType
    let isSelected: Bool
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
                    Image(systemName: "checkmark")
                        .font(.system(size: 18, weight: .bold))
                        .foregroundColor(.accentColor)
                }
            }
            .scaleEffect(isSelected ? 1.08 : 1.0)
            .animation(.easeInOut(duration: 0.15), value: isSelected)

            Text(filter.displayName)
                .font(.system(size: 10))
                .fontWeight(isSelected ? .bold : .regular)
                .foregroundColor(isSelected ? .accentColor : .white.opacity(0.85))
                .lineLimit(1)
        }
        .accessibilityIdentifier("filter_\(filter.thumbnailName)")
        .onTapGesture(perform: onTap)
    }

    private var thumbnailImage: UIImage? {
        loadFilterThumbnail(filter.thumbnailName)
    }
}

/// 🟡8 静态缓存
private var filterThumbnailCache: [String: UIImage] = [:]

private func loadFilterThumbnail(_ name: String) -> UIImage? {
    if let cached = filterThumbnailCache[name] { return cached }
    let img: UIImage?
    if let url = Bundle.main.url(forResource: name, withExtension: "jpg", subdirectory: "Assets/filters"),
       let data = try? Data(contentsOf: url) {
        img = UIImage(data: data)
    } else {
        img = UIImage(named: name)
    }
    if let img { filterThumbnailCache[name] = img }
    return img
}

#Preview {
    FilterSelectorView(selectedFilter: .constant(.none))
}
