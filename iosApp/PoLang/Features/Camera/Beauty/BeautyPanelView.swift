import SwiftUI

/// 美颜面板（对标 Android BeautyPanel.kt:55-157）
/// 双 Tab（FACE / MAKEUP）；容器由调用方 ControlPanel 提供，本视图只负责内容与 Tab 栏
struct BeautyPanelView: View {
    @Binding var params: BeautyRenderer.Params
    @State private var selectedTab = 0

    var body: some View {
        VStack(spacing: 0) {
            // 内容区
            ScrollView {
                VStack(spacing: 12) {
                    if selectedTab == 0 {
                        FacialRefinementContent(params: $params)
                    } else {
                        MakeupPlaceholderContent()
                    }
                }
                .padding(.horizontal, 24)
                .padding(.vertical, 8)
            }

            // Tab 栏（对标 Android BeautyTab：图标 + 文字标签 面部精修/妆容调节）
            HStack(spacing: 0) {
                TabIconButton(
                    systemName: "face.smiling",
                    label: String(localized: "Facial Refinement"),
                    isSelected: selectedTab == 0
                ) { selectedTab = 0 }

                TabIconButton(
                    systemName: "paintpalette",
                    label: String(localized: "Makeup Tab"),
                    isSelected: selectedTab == 1
                ) { selectedTab = 1 }
            }
            .padding(.vertical, 8)
        }
    }
}

// MARK: - Tab Button

private struct TabIconButton: View {
    let systemName: String
    let label: String
    let isSelected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(spacing: 2) {
                MatIcon(name: systemName, size: 20)
                    .foregroundColor(isSelected ? CameraTokens.cameraAccent : .white.opacity(0.5))
                Text(label)
                    .font(.system(size: 12, weight: .medium))
                    .foregroundColor(isSelected ? CameraTokens.cameraAccent : .white.opacity(0.5))
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 8)
            .background(
                RoundedRectangle(cornerRadius: 10)
                    .fill(isSelected ? CameraTokens.cameraAccent.opacity(0.12) : Color.clear)
            )
        }
        .accessibilityLabel(label)
    }
}

// MARK: - FACE Tab：4 条 BeautySlider

private struct FacialRefinementContent: View {
    @Binding var params: BeautyRenderer.Params

    var body: some View {
        VStack(spacing: 12) {
            BeautySliderRow(
                icon: "face.smiling", label: String(localized: "Smoothing"),
                value: $params.smoothing, range: 0...100, displayFormat: .percent
            )
            BeautySliderRow(
                icon: "wand.and.stars", label: String(localized: "Whitening"),
                value: $params.whitening, range: 0...100, displayFormat: .percent
            )
            BeautySliderRow(
                icon: "person.crop.rectangle", label: String(localized: "Slim Face"),
                value: $params.slimFace, range: -50...50, displayFormat: .raw
            )
            BeautySliderRow(
                icon: "eye", label: String(localized: "Big Eyes"),
                value: $params.bigEyes, range: 0...100, displayFormat: .percent
            )
        }
    }
}

// MARK: - BeautySliderRow（对标 Android CameraBaseComponents.kt:294-358）

private struct BeautySliderRow: View {
    let icon: String
    let label: String
    @Binding var value: Float
    let range: ClosedRange<Float>
    let displayFormat: DisplayFormat

    enum DisplayFormat { case percent, raw }

    var body: some View {
        VStack(spacing: 8) {
            HStack {
                // 点击图标+标签 → 重置
                Button { value = 0 } label: {
                    HStack(spacing: 8) {
                        MatIcon(name: icon, size: 15)
                            .foregroundColor(value != 0 ? CameraTokens.cameraAccent : .white.opacity(0.6))
                        Text(label)
                            .font(.system(size: 14, weight: .medium))
                            .foregroundColor(value != 0 ? CameraTokens.cameraAccent : .white.opacity(0.6))
                    }
                }
                .buttonStyle(.plain)

                Spacer()

                Text(displayValue)
                    .font(.system(size: 14, weight: .bold))
                    .foregroundColor(value != 0 ? CameraTokens.cameraAccent : .white.opacity(0.4))
            }

            // 对标 Android HyperOS 滑杆:自绘胶囊轨道+描边 thumb(AppSlider),不用系统 Slider
            AppSlider(
                value: value,
                range: range,
                activeTrackColor: CameraTokens.cameraAccent,
                thumbBorderColor: CameraTokens.cameraAccent,
                onValueChange: { value = $0 }
            )
        }
    }

    private var displayValue: String {
        "\(Int(value))"  // 对标 Android:始终显示数值(0 也显示 "0",非 "--")
    }
}

// MARK: - MAKEUP Tab 占位（Phase 6）

private struct MakeupPlaceholderContent: View {
    var body: some View {
        VStack(spacing: 12) {
            Text(String(localized: "Makeup (Phase 6)"))
                .font(.system(size: 14))
                .foregroundColor(.white.opacity(0.4))
        }
        .frame(maxWidth: .infinity, minHeight: 80)
    }
}
