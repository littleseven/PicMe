import SwiftUI

/// SF Symbol → Material Icon 映射
/// 统一双端字形：iOS 使用 Material Icons Round（与 Android Icons.Rounded.* 同源）
/// Image(systemName:) → Image(matIconName:).renderingMode(.template)
extension Image {
    /// 用 Material 图标替代 SF Symbol
    /// 传入原 SF Symbol name，返回对应的 Material 图标
    init(matIcon sfName: String) {
        let matName = MaterialIconMap.map[sfName] ?? sfName
        self.init(matName, label: Text(matName))
    }
}

enum MaterialIconMap {
    /// SF Symbol name → Material icon asset name
    static let map: [String: String] = [
        // Camera
        "chevron.left":           "mat_arrow_back",
        "arrow.clockwise":        "mat_refresh",
        "wand.and.stars":         "mat_autofix",
        "aspectratio":            "mat_aspect_ratio",
        "square.grid.3x3":        "mat_grid_on",
        "mountain.2":             "mat_landscape",
        "circle.lefthalf.filled": "mat_filter_b_and_w",
        "slider.horizontal.3":    "mat_tune",
        "camera.rotate":          "mat_cameraswitch",
        "checkmark":              "mat_check",
        "photo.fill":             "mat_photo_library",
        "lock.fill":              "mat_lock",
        // Beauty panel
        "face.smiling":           "mat_face",
        "paintpalette":           "mat_color_lens",
        "eye":                    "mat_visibility",
        "person.crop.rectangle":  "mat_face_retouching",
    ]
}

/// 渲染 Material 图标（template mode + 固定尺寸）
struct MatIcon: View {
    let name: String
    var size: CGFloat = 18

    var body: some View {
        Image(matIcon: name)
            .renderingMode(.template)
            .resizable()
            .aspectRatio(contentMode: .fit)
            .frame(width: size, height: size)
    }
}
