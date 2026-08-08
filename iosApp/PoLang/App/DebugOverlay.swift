import SwiftUI

/// iOS 设备日志工具链不可用（spike 实测），内部状态直接画屏。
/// 用法：DebugOverlayState.shared.set("camera.fps", "30.0")，
/// 在根视图叠 .overlay(alignment: .topLeading) { DebugOverlayView() }
@MainActor
final class DebugOverlayState: ObservableObject {
    static let shared = DebugOverlayState()
    @Published private(set) var entries: [(key: String, value: String)] = []
    private var map: [String: String] = [:]
    var isEnabled = true  // Release/TestFlight 可关

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
                ForEach(state.entries, id: \.key) { entry in
                    Text("\(entry.key): \(entry.value)")
                        .font(.system(size: 11, design: .monospaced))
                        .foregroundColor(.green)
                }
            }
            .padding(6)
            .background(Color.black.opacity(0.55))
            .padding(.top, 48)
            .padding(.leading, 8)
            .allowsHitTesting(false)
        }
    }
}

#Preview {
    DebugOverlayView()
        .onAppear {
            DebugOverlayState.shared.set("test.key", "value")
        }
}
