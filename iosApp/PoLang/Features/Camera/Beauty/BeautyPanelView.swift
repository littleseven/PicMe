import SwiftUI

/// 美颜面板（对标 Android BeautyPanel.kt:55-157）
/// 双 Tab（FACE / MAKEUP），拖拽手柄，35% 屏高，24pt 顶部圆角
struct BeautyPanelView: View {
    @Binding var params: BeautyRenderer.Params
    @State private var selectedTab = 0

    var body: some View {
        VStack(spacing: 0) {
            // 拖拽手柄（36x4pt 胶囊）
            Capsule()
                .fill(Color.white.opacity(0.3))
                .frame(width: 36, height: 4)
                .padding(.top, 10)
                .padding(.bottom, 4)

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

            // Tab 栏（对标 Android BeautyTab）
            HStack(spacing: 0) {
                TabIconButton(
                    systemName: "face.smiling",
                    isSelected: selectedTab == 0
                ) { selectedTab = 0 }

                TabIconButton(
                    systemName: "paintpalette",
                    isSelected: selectedTab == 1
                ) { selectedTab = 1 }
            }
            .padding(.vertical, 8)
        }
        .frame(maxHeight: UIScreen.main.bounds.height * 0.35) // 对标 Android BeautyPanel PANEL_HEIGHT_RATIO=0.35
        .background(
            RoundedRectangle(cornerRadius: 24, style: .continuous)
                .fill(.ultraThinMaterial)
                .shadow(color: .black.opacity(0.3), radius: 16)
                .ignoresSafeArea(edges: .bottom)
        )
        .clipShape(RoundedRectangle(cornerRadius: 24, style: .continuous))
    }
}

// MARK: - Tab Button

private struct TabIconButton: View {
    let systemName: String
    let isSelected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            MatIcon(name: systemName, size: 20)
                .foregroundColor(isSelected ? .accentColor : .white.opacity(0.5))
                .frame(maxWidth: .infinity)
                .padding(.vertical, 8)
                .background(
                    RoundedRectangle(cornerRadius: 10)
                        .fill(isSelected ? Color.accentColor.opacity(0.12) : Color.clear)
                )
        }
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
                            .foregroundColor(value != 0 ? .accentColor : .white.opacity(0.6))
                        Text(label)
                            .font(.system(size: 14, weight: .medium))
                            .foregroundColor(value != 0 ? .accentColor : .white.opacity(0.6))
                    }
                }
                .buttonStyle(.plain)

                Spacer()

                Text(displayValue)
                    .font(.system(size: 14, weight: .bold))
                    .foregroundColor(value != 0 ? .accentColor : .white.opacity(0.4))
            }

            Slider(value: $value, in: range)
                .tint(.accentColor)
        }
    }

    private var displayValue: String {
        if value == 0 { return "--" }
        switch displayFormat {
        case .percent: return "\(Int(value))"
        case .raw: return "\(Int(value))"
        }
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
