import SwiftUI

/// iOS 设备日志工具链不可用（spike 实测），内部状态直接画屏。
/// 🟡 P1.5: 对齐 Android「顶部一行小字」形式；默认展开（验收期直接显示全部遥测）。
@MainActor
final class DebugOverlayState: ObservableObject {
    static let shared = DebugOverlayState()
    @Published private(set) var entries: [(key: String, value: String)] = []
    @Published var isExpanded = true  // 默认展开（验收期直接显示 face.mnn/face.engine.active/camera.fps；点摘要行可折叠）
    @Published var isEnabled = false  // 默认关闭；由 Settings / CameraPreviewView 根据 UserDefaults 同步
    private var map: [String: String] = [:]

    private init() {}

    func set(_ key: String, _ value: String) {
        guard isEnabled else { return }
        map[key] = value
        entries = map.sorted { $0.key < $1.key }.map { ($0.key, $0.value) }
    }

    /// 移除一个遥测项（引擎切换时清非活跃引擎的遗留错误，避免误读）。
    nonisolated func clear(_ key: String) {
        DispatchQueue.main.async { [key] in
            guard Self.shared.map[key] != nil else { return }
            Self.shared.map.removeValue(forKey: key)
            Self.shared.entries = Self.shared.map.sorted { $0.key < $1.key }.map { ($0.key, $0.value) }
        }
    }
}

struct DebugOverlayView: View {
    @ObservedObject var state = DebugOverlayState.shared
    // 开发者选项 3 开关（对标 Android show_camera_info/show_face_debug/show_log_overlay）：
    // 按遥测 key 前缀分类过滤——camera.*/face.*/其余(log.*)，使开关真正生效。
    @AppStorage("show_camera_info_in_preview") private var showCamera = true
    @AppStorage("show_face_debug_overlay") private var showFace = true
    @AppStorage("show_log_overlay") private var showLog = true

    private func passes(_ key: String) -> Bool {
        if key.hasPrefix("camera.") { return showCamera }
        if key.hasPrefix("face.") { return showFace }
        return showLog
    }

    var body: some View {
        if state.isEnabled {
            let filtered = state.entries.filter { passes($0.key) }
            VStack(alignment: .leading, spacing: 2) {
                // 摘要行（对标 Android "Beauty: ACTIVE 14.6fps ▼"）
                summaryLine
                    .accessibilityIdentifier("debug_summary")
                    .onTapGesture { state.isExpanded.toggle() }

                // 展开详情（按开发者开关过滤）
                if state.isExpanded && !filtered.isEmpty {
                    ForEach(filtered, id: \.key) { entry in
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
