import SwiftUI
import UIKit

/// 滤镜选择条（对标 Android UnifiedFilterSelector — FilterType 九款）
/// StyleFilter（TOON/SKETCH/POSTERIZE/EMBOSS/CROSSHATCH）移 Phase 6（spec S3）
///
/// [S5] 双端一致：名称/排序与 Android FilterType.ordinal 一致。
struct FilterSelectorView: View {
    @Binding var selectedFilter: FilterType

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
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
        }
        .accessibilityIdentifier("filter_selector")
    }
}

/// 🟡8: 静态图片缓存，避免 body 内每次重复同步磁盘 IO 读 9 张 jpg
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
                        .fill(Color.accentColor.opacity(0.2))
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
                .lineLimit(1)
        }
        .accessibilityIdentifier("filter_\(filter.thumbnailName)")
        .onTapGesture(perform: onTap)
    }

    private var thumbnailImage: UIImage? {
        loadFilterThumbnail(filter.thumbnailName)
    }
}

#Preview {
    FilterSelectorView(selectedFilter: .constant(.none))
}
