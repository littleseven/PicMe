import SwiftUI

// MARK: - Design Tokens（双端 SSOT: shared/src/commonMain/resources/design-tokens.json）
//
// 修改 token 时：先更新 JSON 源文件，再同步此文件。
// 所有新增 UI 必须引用这些常量，禁止硬编码尺寸/颜色/圆角。

// MARK: - Spacing

enum Spacing {
    static let xs: CGFloat = 4
    static let sm: CGFloat = 8
    static let md: CGFloat = 12
    static let lg: CGFloat = 16
    static let xl: CGFloat = 24
    static let xxl: CGFloat = 32
}

// MARK: - TopBar

enum TopBarTokens {
    static let height: CGFloat = 48
    static let buttonSize: CGFloat = 36
    static let iconSize: CGFloat = 22
    static let titleFontSize: CGFloat = 17
    static let spacing: CGFloat = 8
    static let horizontalPadding: CGFloat = 8
}

// MARK: - Shutter

enum ShutterTokens {
    static let diameter: CGFloat = 76
    static let innerDiameter: CGFloat = 58
}

// MARK: - Beauty Panel

enum BeautyPanelTokens {
    static let topCornerRadius: CGFloat = 24
    static let iconSize: CGFloat = 24
    static let sliderThumbSize: CGFloat = 18
    static let sliderTrackHeight: CGFloat = 6
    static let heightRatio: CGFloat = 0.35
}

// MARK: - Grid

enum GridTokens {
    static let minCellSize: CGFloat = 110
    static let spacing: CGFloat = 2
    static let cornerRadius: CGFloat = 2
}

// MARK: - Pager

enum PagerTokens {
    static let pageSpacing: CGFloat = 16
    static let actionIconSize: CGFloat = 32
    static let overlayMaxWidth: CGFloat = 400
    static let overlayMaxHeight: CGFloat = 500
}

// MARK: - Radius

enum AppRadius {
    static let panel: CGFloat = 24
    static let card: CGFloat = 12
    static let button: CGFloat = 10
    static let small: CGFloat = 8
    static let thumbnail: CGFloat = 2
}

// MARK: - Icon Sizes

enum IconSize {
    static let sm: CGFloat = 18
    static let md: CGFloat = 22
    static let lg: CGFloat = 24
    static let xl: CGFloat = 32
}

// MARK: - App Colors（不随主题切换的固定功能色）

enum AppColors {
    static let focusRing = Color(red: 0, green: 0.9, blue: 1)        // #00E5FF
    static let panelBackground = Color.black.opacity(0.8)              // #CC000000
    static let shutterRing = Color.white                               // #FFFFFF
    static let sliderThumb = Color.white                               // #FFFFFF
    static let vibrantGreen = Color(red: 0, green: 0.902, blue: 0.463) // #00E676
    static let vibrantBlue = Color(red: 0.161, green: 0.475, blue: 1)  // #2979FF
    static let vibrantOrange = Color(red: 1, green: 0.569, blue: 0)    // #FF9100
    static let vibrantPink = Color(red: 1, green: 0.251, blue: 0.506)  // #FF4081
}

// MARK: - App Shapes

enum AppShapes {
    static let panel = RoundedRectangle(cornerRadius: AppRadius.panel, style: .continuous)
    static let card = RoundedRectangle(cornerRadius: AppRadius.card, style: .continuous)
    static let button = RoundedRectangle(cornerRadius: AppRadius.button, style: .continuous)
    static let small = RoundedRectangle(cornerRadius: AppRadius.small, style: .continuous)
    static let thumbnail = RoundedRectangle(cornerRadius: AppRadius.thumbnail, style: .continuous)
}
