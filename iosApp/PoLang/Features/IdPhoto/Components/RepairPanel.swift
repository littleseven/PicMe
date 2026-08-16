import SwiftUI

/// 修补常量（spec §6：笔刷 range 8...80 默认 32；软边 softness 开=0.5 / 关=0）
enum RepairConstants {
    static let minBrushSize: Float = 8
    static let maxBrushSize: Float = 80
    static let defaultBrushSize: Float = 32
    static let softnessOn: Float = 0.5
    static let softnessOff: Float = 0
}

/// 证照屏修补面板（对标 specs/screens/idphoto.yaml §6；定稿截图 tmp/ui-reference/idphoto-05-tab-repair.png）。
/// 行1 模式 chip（恢复/擦除，默认擦除——同 Tab chip 样式）
/// 行2 笔刷大小滑杆（8...80px，实时生效）
/// 行3 软边开关（softness 0.5/0）
/// 行4 撤销/重做/清除描边 三 TextButton SpaceEvenly
/// 描边画布与手势在预览框上（IDPhotoScreen painting_contract），本面板只管工具参数。
struct RepairPanel: View {
    @Binding var brushMode: StrokeMode
    @Binding var brushSize: Float
    @Binding var softEdge: Bool

    let canUndo: Bool
    let canRedo: Bool
    let hasStrokes: Bool

    let onUndo: () -> Void
    let onRedo: () -> Void
    let onClear: () -> Void

    var body: some View {
        VStack(spacing: Spacing.lg) {
            // 行1：模式（§6 mode_toggle；默认 erase 由调用方初始 brushMode 提供）
            HStack(spacing: Spacing.sm) {
                modeChip(L("id_photo_repair_restore"), mode: .restore)
                modeChip(L("id_photo_repair_erase"), mode: .erase)
            }

            // 行2：笔刷大小（实时——拖动即生效，直改 binding）
            VStack(spacing: Spacing.sm) {
                HStack {
                    Text(L("id_photo_repair_brush_size"))
                        .font(AppTypography.bodySmall.font)
                        .foregroundStyle(.white)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    Text("\(Int(brushSize)) px")
                        .font(AppTypography.bodySmall.font)
                        .foregroundStyle(.white.opacity(AppAlpha.secondary))
                }
                AppSlider(
                    value: brushSize,
                    range: RepairConstants.minBrushSize...RepairConstants.maxBrushSize,
                    activeTrackColor: AppColorScheme.dark.primary,
                    thumbBorderColor: AppColorScheme.dark.primary,
                    onValueChange: { v in brushSize = v }
                )
            }

            // 行3：软边开关
            HStack {
                Text(L("id_photo_repair_soft_edge"))
                    .font(AppTypography.bodySmall.font)
                    .foregroundStyle(.white)
                Spacer()
                Toggle("", isOn: $softEdge)
                    .labelsHidden()
                    .tint(AppColorScheme.dark.primary)
                    .accessibilityLabel(L("id_photo_repair_soft_edge"))
            }

            // 行4：撤销/重做/清除（§6 actions_row，SpaceEvenly）
            HStack {
                textButton(L("id_photo_repair_undo"), enabled: canUndo, action: onUndo)
                    .frame(maxWidth: .infinity)
                textButton(L("id_photo_repair_redo"), enabled: canRedo, action: onRedo)
                    .frame(maxWidth: .infinity)
                textButton(L("id_photo_repair_clear"), enabled: hasStrokes, action: onClear)
                    .frame(maxWidth: .infinity)
            }
        }
    }

    /// 模式 chip（同 IdPhotoTabRow chip 样式：选中 primary/onPrimary，未选中 tabUnselectedContainer/白）
    private func modeChip(_ label: String, mode: StrokeMode) -> some View {
        let isSelected = brushMode == mode
        let scheme = AppColorScheme.dark
        return Button {
            brushMode = mode
        } label: {
            Text(label)
                .font(AppTypography.labelLarge.font)
                .foregroundStyle(isSelected ? scheme.onPrimary : .white)
                .padding(.horizontal, Spacing.lg)
                .frame(height: ChipTokens.smallHeight)
                .background(
                    RoundedRectangle(cornerRadius: AppRadius.small)
                        .fill(isSelected ? scheme.primary : IdPhotoTokens.tabUnselectedContainer)
                )
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(isSelected ? [.isSelected] : [])
    }

    /// TextButton：dark.primary 文字色；disabled 0.3 透明度（§6）
    private func textButton(_ label: String, enabled: Bool, action: @escaping () -> Void) -> some View {
        Button {
            guard enabled else { return }
            action()
        } label: {
            Text(label)
                .font(AppTypography.labelLarge.font)
                .foregroundStyle(AppColorScheme.dark.primary.opacity(enabled ? 1 : AppAlpha.faint))
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
    }
}
