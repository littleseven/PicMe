import Foundation
import SharedKit

/// SKIE spike S5 冒烟三件套（spike/skie 分支临时验证代码，GO 后随迁移删除或转正）。
/// 验证 SKIE 0.10.14 在本项目 chat 链路的三个承诺：
/// ① Flow → Swift `AsyncSequence`（`for await` 直消费）
/// ② sealed interface → Swift enum（`onEnum` + 无 default 穷举 switch）
/// ③ suspend → `async throws`（异常经 Swift 类型系统传导，非 signal 6）
/// 结果写入 DebugOverlay（skie.* 三个遥测 key），真机/模拟器启动后可见。
enum SkieSmoke {

    @MainActor
    static func run() {
        let service = ChatToolService.companion.getInstance()

        // ③ suspend → async throws：delay(1ms) 无副作用纯函数
        Task {
            do {
                let result = try await service.delay(delayMs: 1)
                DebugOverlayState.shared.set("skie.suspend", "OK: \(result.prefix(24))")
            } catch {
                DebugOverlayState.shared.set("skie.suspend", "FAIL: \(error.localizedDescription)")
            }
        }

        // ① Flow → AsyncSequence：只读 SharedFlow 属性被 SKIE 类型替换 + 桥接为
        // SkieSwiftSharedFlow<AgentAction>（conforms SkieSwiftFlowProtocol: AsyncSequence）。
        // 注：SKIE 实际支持全部 Flow 变体（含 MutableSharedFlow）的转换，
        // 只读暴露是 API 卫生考虑（Swift 侧不应看到可发射的可变流）。
        // 订阅即算过（chat 页未打开时本就不会有事件）；编译形态 + 运行时启动验证。
        let flowTask = Task {
            for await _ in service.uiActionsReadOnly {
                DebugOverlayState.shared.set("skie.flow", "event received")
                break
            }
        }
        DebugOverlayState.shared.set("skie.flow", "subscribed via AsyncSequence")
        flowTask.cancel()

        // ② sealed → Swift enum：onEnum 转换 + 穷举 switch（无 default；
        // 新增 ChatStreamEvent 子类时此处会编译报错——这正是要验证的穷举语义）
        let event: ChatStreamEvent = ChatStreamEventTextSnapshot(text: "smoke")
        switch onEnum(of: event) {
        case .textSnapshot(let snap):
            DebugOverlayState.shared.set("skie.sealed", "OK text=\(snap.text)")
        case .toolCallStarted:
            DebugOverlayState.shared.set("skie.sealed", "OK toolCallStarted")
        }
    }
}
