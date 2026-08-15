import SwiftUI

/// 全 app 统一滑杆（对齐 Android `AppSlider`，规格见 `design-tokens.json appSlider`）。
/// 胶囊轨道 + 白圆点 primary 描边 thumb + 按压放大。camera/editor/beauty 共享。
/// 本组件在 DesignSystem 供编辑器首用；相机/美颜存量 `BeautySliderRow` 可后续迁移复用。
struct AppSlider: View {
    let value: Float
    let range: ClosedRange<Float>
    var activeTrackColor: Color = Color.accentColor
    var thumbBorderColor: Color = Color.accentColor
    var onValueChange: (Float) -> Void

    @State private var pressed = false
    @State private var dragWidth: CGFloat = 0

    private var fraction: CGFloat {
        let span = CGFloat(range.upperBound - range.lowerBound)
        guard span > 0 else { return 0 }
        return CGFloat((value - range.lowerBound) / (range.upperBound - range.lowerBound))
    }

    var body: some View {
        GeometryReader { geo in
            let trackH = AppSliderTokens.trackHeight
            let thumb = AppSliderTokens.thumbSize
            let radius = trackH / 2
            let clampedFraction = min(1, max(0, fraction))

            ZStack(alignment: .leading) {
                // 轨道（inactive）
                Capsule()
                    .fill(Color.primary.opacity(AppSliderTokens.inactiveTrackAlpha))
                    .frame(height: trackH)
                // active 段
                Capsule()
                    .fill(activeTrackColor)
                    .frame(width: max(thumb, clampedFraction * geo.size.width), height: trackH)
                // thumb
                Circle()
                    .fill(AppSliderTokens.thumbColor)
                    .overlay(
                        Circle().stroke(thumbBorderColor, lineWidth: AppSliderTokens.thumbBorderWidth)
                    )
                    .shadow(radius: AppSliderTokens.thumbShadowElevation)
                    .frame(width: thumb, height: thumb)
                    .scaleEffect(pressed ? AppSliderTokens.thumbPressedScale : 1)
                    .offset(x: max(0, min(geo.size.width - thumb,
                                          clampedFraction * geo.size.width - thumb / 2)))
                    .animation(.easeOut(duration: AppSliderTokens.animDurationMs / 1000),
                               value: pressed)
            }
            .frame(height: max(trackH, thumb))
            .frame(maxWidth: .infinity)
            .contentShape(Rectangle())
            .gesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { d in
                        pressed = true
                        dragWidth = geo.size.width
                        handle(d.location.x)
                    }
                    .onEnded { _ in pressed = false }
            )
        }
        .frame(height: AppSliderTokens.thumbSize)
    }

    private func handle(_ x: CGFloat) {
        guard dragWidth > 0 else { return }
        let f = Float(min(1, max(0, x / dragWidth)))
        onValueChange(range.lowerBound + f * (range.upperBound - range.lowerBound))
    }
}
