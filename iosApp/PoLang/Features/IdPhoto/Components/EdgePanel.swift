import SwiftUI

/// 证照屏边缘面板（对标 specs/screens/idphoto.yaml §5；定稿截图 tmp/ui-reference/idphoto-04-tab-edge.png）。
/// 三行滑杆（羽化 0-20px / 收缩扩张 -20...20px / 边缘对比度 1.0-4.0）+ 右对齐「重置参数」。
/// 🔴 提交契约（§5 commit_contract）：拖动中仅更新本地浮点显示；任一滑杆 release 时
/// 一次性提交全部三项 setEdgeParams（重算底图成本高，逐帧提交会卡）。
struct EdgePanel: View {
    /// 当前 VM 参数（外部 reset 回灌本地，见 .task(id:)）
    let edgeParams: EdgeParams
    let onCommit: (EdgeParams) -> Void
    let onReset: () -> Void

    @State private var feather: Float
    @State private var shrinkExpand: Float
    @State private var contrast: Float

    init(edgeParams: EdgeParams,
         onCommit: @escaping (EdgeParams) -> Void,
         onReset: @escaping () -> Void) {
        self.edgeParams = edgeParams
        self.onCommit = onCommit
        self.onReset = onReset
        // 初值即当前参数（防重进面板闪默认值）
        _feather = State(initialValue: edgeParams.featherRadiusPx)
        _shrinkExpand = State(initialValue: edgeParams.shrinkExpandPx)
        _contrast = State(initialValue: edgeParams.contrast)
    }

    var body: some View {
        VStack(spacing: Spacing.lg) {
            sliderRow(
                labelKey: "id_photo_edge_feather",
                value: $feather,
                range: 0...EdgeParams.maxFeatherPx,
                display: { value in "\(Int(value)) px" }
            )
            sliderRow(
                labelKey: "id_photo_edge_shrink_expand",
                value: $shrinkExpand,
                range: -EdgeParams.maxShrinkExpandPx...EdgeParams.maxShrinkExpandPx,
                display: { value in "\(Int(value)) px" }
            )
            sliderRow(
                labelKey: "id_photo_edge_contrast",
                value: $contrast,
                range: EdgeParams.minContrast...EdgeParams.maxContrast,
                display: { value in String(format: "%.1f", value) }
            )
            HStack {
                Spacer()
                Button {
                    onReset()
                } label: {
                    Text(L("id_photo_edge_reset"))
                        .font(AppTypography.labelLarge.font)
                        .foregroundStyle(AppColorScheme.dark.primary)
                }
                .buttonStyle(.plain)
            }
        }
        // 本地态与 VM 同步：外部 reset 回灌三项（自己提交同值回灌无害）
        .task(id: edgeParams) {
            feather = edgeParams.featherRadiusPx
            shrinkExpand = edgeParams.shrinkExpandPx
            contrast = edgeParams.contrast
        }
    }

    /// 行结构（§5 row_layout）：label（白 bodySmall weight 1f）+ value（白 0.6 bodySmall 右对齐）+ 滑杆
    private func sliderRow(labelKey: String,
                           value: Binding<Float>,
                           range: ClosedRange<Float>,
                           display: @escaping (Float) -> String) -> some View {
        VStack(spacing: Spacing.sm) {
            HStack {
                Text(L(labelKey))
                    .font(AppTypography.bodySmall.font)
                    .foregroundStyle(.white)
                    .frame(maxWidth: .infinity, alignment: .leading)
                Text(display(value.wrappedValue))
                    .font(AppTypography.bodySmall.font)
                    .foregroundStyle(.white.opacity(AppAlpha.secondary))
            }
            AppSlider(
                value: value.wrappedValue,
                range: range,
                activeTrackColor: AppColorScheme.dark.primary,   // 强制深色屏不用 accentColor（§8）
                thumbBorderColor: AppColorScheme.dark.primary,
                onValueChange: { v in value.wrappedValue = v },
                onEditingChanged: { editing in
                    if !editing { commit() }   // release 时一次性提交三项
                }
            )
        }
    }

    private func commit() {
        onCommit(EdgeParams(contrast: contrast,
                            shrinkExpandPx: shrinkExpand,
                            featherRadiusPx: feather))
    }
}
