import SwiftUI

/// 美颜面板（对标 Android BeautyPanel；MVP：美白+磨皮+瘦脸+大眼四条滑杆）
struct BeautyPanelView: View {
    @Binding var params: BeautyRenderer.Params

    var body: some View {
        VStack(spacing: 8) {
            HStack {
                Text(String(localized: "Whitening"))
                Slider(value: $params.whitening, in: 0...1)
                    .accessibilityIdentifier("beauty_whitening_slider")
            }
            HStack {
                Text(String(localized: "Smoothing"))
                Slider(value: $params.smoothing, in: 0...1)
                    .accessibilityIdentifier("beauty_smoothing_slider")
            }
            HStack {
                Text(String(localized: "Slim Face"))
                Slider(value: $params.slimFace, in: 0...1)
                    .accessibilityIdentifier("beauty_slimface_slider")
            }
            HStack {
                Text(String(localized: "Big Eyes"))
                Slider(value: $params.bigEyes, in: 0...1)
                    .accessibilityIdentifier("beauty_bigeyes_slider")
            }
        }
        .padding()
        .background(.ultraThinMaterial)
        .clipShape(RoundedRectangle(cornerRadius: 16))
    }
}

#Preview {
    BeautyPanelView(params: .constant(BeautyRenderer.Params()))
}
