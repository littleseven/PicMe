import Foundation
import SharedKit

/// shared（Kotlin）边界的统一入口。
/// 约定：Kotlin 异常不经 @Throws 导出会 signal 6 崩溃（kmp-ios-interop skill 铁律 1），
/// shared 侧所有跨边界调用已在 Kotlin 内 try/catch 兜底；本层只做类型转接。
enum KotlinBridge {
    /// 冒烟：连续两次取 id，验证 K/N 对象导出与调用链通畅
    static func smokeIds() -> (Int32, Int32) {
        let a = AgentIdGenerator.shared.nextId()
        let b = AgentIdGenerator.shared.nextId()
        return (a, b)
    }
}
