import SwiftUI
import UIKit
import simd

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
        // 滤镜：运行时生成（彩色渐变 + ColorMatrix）；风格占位：无图（lock 图标）
        if let filter { return generateFilterThumbnail(filter) }
        return nil
    }
}

/// 滤镜缩略图静态缓存（运行时生成的 ColorMatrix 渐变缩略图）
private var filterThumbnailCache: [FilterType: UIImage] = [:]

/// 生成滤镜缩略图：彩色渐变 + 应用该滤镜 ColorMatrix（运行时生成，对标 Android 运行时矩阵应用；无需 bundle JPG）
private func generateFilterThumbnail(_ filter: FilterType) -> UIImage? {
    if let cached = filterThumbnailCache[filter] { return cached }
    guard let base = makeColorGradientImage(size: 56) else { return nil }
    let img: UIImage?
    if let cm = filter.colorMatrix {
        img = applyColorMatrix(base, rows: cm.rows, offset: cm.offset)
    } else {
        img = base // NONE → 原渐变
    }
    if let img { filterThumbnailCache[filter] = img }
    return img
}

/// 56×56 彩色渐变底图（红→黄→绿→青→蓝→品，斜向）
private func makeColorGradientImage(size: Int) -> UIImage? {
    let cs = CGColorSpaceCreateDeviceRGB()
    guard let ctx = CGContext(data: nil, width: size, height: size, bitsPerComponent: 8,
                              bytesPerRow: size * 4, space: cs,
                              bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return nil }
    let cgColors = [UIColor.red, .yellow, .green, .cyan, .blue, .magenta].compactMap({ $0.cgColor })
    guard let grad = CGGradient(colorsSpace: cs, colors: cgColors as CFArray,
                                locations: [0, 0.2, 0.4, 0.6, 0.8, 1.0]) else { return nil }
    ctx.drawLinearGradient(grad, start: CGPoint(x: 0, y: 0), end: CGPoint(x: size, y: size), options: [])
    guard let cg = ctx.makeImage() else { return nil }
    return UIImage(cgImage: cg)
}

/// 应用 ColorMatrix（4×4 rows + offset 0-255）到 UIImage 每像素，返回新 UIImage
private func applyColorMatrix(_ image: UIImage,
                              rows: (SIMD4<Float>, SIMD4<Float>, SIMD4<Float>, SIMD4<Float>),
                              offset: SIMD4<Float>) -> UIImage? {
    guard let cg = image.cgImage else { return nil }
    let w = cg.width, h = cg.height
    let cs = CGColorSpaceCreateDeviceRGB()
    var pixels = [UInt8](repeating: 0, count: w * h * 4)
    guard let ctx = CGContext(data: &pixels, width: w, height: h, bitsPerComponent: 8,
                              bytesPerRow: w * 4, space: cs,
                              bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return nil }
    ctx.draw(cg, in: CGRect(x: 0, y: 0, width: w, height: h))
    let off = SIMD4<Float>(offset.x / 255.0, offset.y / 255.0, offset.z / 255.0, offset.w / 255.0)
    for i in stride(from: 0, to: pixels.count, by: 4) {
        let v = SIMD4<Float>(Float(pixels[i]) / 255.0, Float(pixels[i + 1]) / 255.0,
                             Float(pixels[i + 2]) / 255.0, 1.0)
        let nr = (simd_dot(rows.0, v) + off.x).clamped(0.0...1.0)
        let ng = (simd_dot(rows.1, v) + off.y).clamped(0.0...1.0)
        let nb = (simd_dot(rows.2, v) + off.z).clamped(0.0...1.0)
        pixels[i] = UInt8(nr * 255.0)
        pixels[i + 1] = UInt8(ng * 255.0)
        pixels[i + 2] = UInt8(nb * 255.0)
        pixels[i + 3] = 255
    }
    guard let out = ctx.makeImage() else { return nil }
    return UIImage(cgImage: out)
}

#Preview {
    FilterSelectorView(selectedFilter: .constant(.none))
}
