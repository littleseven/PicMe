import SwiftUI

/// iOS 设备日志工具链不可用（spike 实测），内部状态直接画屏。
/// 🟡 P1.5: 对齐 Android「顶部一行小字」形式；默认展开（验收期直接显示全部遥测）。
@MainActor
final class DebugOverlayState: ObservableObject {
    static let shared = DebugOverlayState()
    @Published private(set) var entries: [(key: String, value: String)] = []
    @Published var isExpanded = true  // 默认展开（验收期直接显示 face.mnn/face.engine.active/camera.fps；点摘要行可折叠）
    private var map: [String: String] = [:]
    var isEnabled = true

    private init() {}

    func set(_ key: String, _ value: String) {
        guard isEnabled else { return }
        map[key] = value
        entries = map.sorted { $0.key < $1.key }.map { ($0.key, $0.value) }
    }
}

struct DebugOverlayView: View {
    @ObservedObject var state = DebugOverlayState.shared

    var body: some View {
        if state.isEnabled && !state.entries.isEmpty {
            VStack(alignment: .leading, spacing: 2) {
                // 摘要行（对标 Android "Beauty: ACTIVE 14.6fps ▼"）
                summaryLine
                    .accessibilityIdentifier("debug_summary")
                    .onTapGesture { state.isExpanded.toggle() }

                // 展开详情
                if state.isExpanded {
                    ForEach(state.entries, id: \.key) { entry in
                        Text("\(entry.key): \(entry.value)")
                            .font(.system(size: 10, design: .monospaced))
                            .foregroundColor(.green)
                            .accessibilityIdentifier("debug_entry_\(entry.key)")
                    }
                }
            }
            .padding(6)
            .background(Color.black.opacity(0.55))
            .clipShape(RoundedRectangle(cornerRadius: 6))
            .padding(.top, 4)
            .allowsHitTesting(true)
        }
    }

    /// 一行摘要：当前人脸引擎 + fps（对标 Android 顶部状态行）
    /// 读 `face.engine.active`（MNN/MediaPipe）；此前误读从不写入的 `face.engine` → 恒显 OFF。
    private var summaryLine: some View {
        let fps = state.entries.first { $0.key == "camera.fps" }?.value ?? "--"
        let engine = state.entries.first { $0.key == "face.engine.active" }?.value
        let engineStr = engine ?? "OFF"
        let arrow = state.isExpanded ? "▲" : "▼"
        return Text("Beauty: \(engineStr) \(fps)fps \(arrow)")
            .font(.system(size: 11, design: .monospaced))
            .foregroundColor(.green)
    }
}

#Preview {
    DebugOverlayView()
        .onAppear {
            DebugOverlayState.shared.set("camera.fps", "30")
            DebugOverlayState.shared.set("face.engine.active", "MNN")
            DebugOverlayState.shared.set("face.mnn", "106pts")
        }
}
