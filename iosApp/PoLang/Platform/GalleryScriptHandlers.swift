import Foundation
import SharedKit

/// `JsCallback` 的 Swift 实现盒：桥 block 捕获它，handler 完成后由 Kotlin `JsBridge.dispatchAsync` 回调。
///
/// 对齐 Node 风格 `(err, result)`：成功 `error=nil, result=<值>`；失败 `error=JsValue.Str`。
final class JsCallbackBox: NSObject, JsCallback {
    private let body: (JsValue?, JsValue?) -> Void
    init(_ body: @escaping (JsValue?, JsValue?) -> Void) {
        self.body = body
    }
    func invoke(error: JsValue?, result: JsValue?) {
        body(error, result)
    }
}

/// Gallery JS 沙盒只读 handler 集合（Tier 1：3 个盘点 handler）。
///
/// 对齐 Android `GalleryScriptHandlers.registerGalleryHandlers`——本批实现 `gallery.summary` /
/// `gallery.tags` / `tag.scan_status` 三个只读 handler，数据来自 `TagDatabase` / `TagScanOrchestrator`。
/// 其余（query/meta/timeline/intersect/stats_by_*、face.cluster、tag.audit、capability.dispatch 写操作）
/// 留 Tier 2/3。所有 handler 为 async（脚本用 `await bridge.callAsync(name, args)` 调）。
enum GalleryScriptHandlers {

    /// 注册 Tier 1 全部 handler 到 [runtime]。
    static func registerAll(into runtime: JsRuntime) {
        runtime.register(handler: GallerySummaryHandler())
        runtime.register(handler: GalleryTagsHandler())
        runtime.register(handler: TagScanStatusHandler())
    }

    // MARK: - gallery.summary（相册总览，对齐 Android GallerySummary.toJsValue）

    static func buildSummary() -> JsValue {
        let stats = TagDatabase.shared.scanStats()
        let typeCounts = TagDatabase.shared.mediaTypeCounts()
        let progress = TagScanOrchestrator.shared.currentProgress()
        let isScanning = progress?.state == ScanSessionState.running
        let unlabeled = max(0, stats.totalMedia - stats.withLabels)
        // 逐项赋值，避免大字典字面量导致 Swift 类型检查器超时。
        var entries: [String: JsValue] = [:]
        entries["totalPhotos"] = JsValue.Num(value: Double(typeCounts.photos))
        entries["totalVideos"] = JsValue.Num(value: Double(typeCounts.videos))
        entries["totalMedia"] = JsValue.Num(value: Double(stats.totalMedia))
        entries["hasFaceCount"] = JsValue.Num(value: Double(stats.withFace))
        entries["personClusterCount"] = JsValue.Num(value: Double(stats.personCount))
        entries["namedPersonCount"] = JsValue.Num(value: Double(stats.namedPersonCount))
        entries["labeledCount"] = JsValue.Num(value: Double(stats.withLabels))
        entries["unlabeledCount"] = JsValue.Num(value: Double(unlabeled))
        entries["semanticEncodedCount"] = JsValue.Num(value: Double(stats.withSemantic))
        entries["remainingPass1"] = JsValue.Num(value: Double(stats.remainingPass1))
        entries["remainingPass3"] = JsValue.Num(value: Double(stats.remainingPass3))
        entries["isScanning"] = JsValue.Bool(value: isScanning)
        entries["currentPass"] = currentPassString(progress)
        // Tier 1：不计算扫描建议逻辑，默认 none（对齐枚举名）
        entries["recommendation"] = JsValue.Str(value: "none")
        return JsValue.Obj(entries: entries)
    }

    // MARK: - gallery.tags（标签清单 + 关联计数）

    static func buildTags() -> JsValue {
        let counts = TagDatabase.shared.tagCounts(limit: 50)
        var tags: [JsValue] = []
        tags.reserveCapacity(counts.count)
        for pair in counts {
            var item: [String: JsValue] = [:]
            item["name"] = JsValue.Str(value: pair.name)
            item["count"] = JsValue.Num(value: Double(pair.count))
            tags.append(JsValue.Obj(entries: item))
        }
        var entries: [String: JsValue] = [:]
        entries["totalTags"] = JsValue.Num(value: Double(counts.count))
        entries["tags"] = JsValue.Arr(items: tags)
        return JsValue.Obj(entries: entries)
    }

    // MARK: - tag.scan_status（扫描会话快照）

    static func buildScanStatus() -> JsValue {
        let stats = TagDatabase.shared.scanStats()
        let progress = TagScanOrchestrator.shared.currentProgress()
        let isScanning = progress?.state == ScanSessionState.running
        var entries: [String: JsValue] = [:]
        entries["isScanning"] = JsValue.Bool(value: isScanning)
        entries["state"] = JsValue.Str(value: progress.map { String(describing: $0.state) } ?? "idle")
        entries["currentPass"] = currentPassString(progress)
        entries["processed"] = JsValue.Num(value: Double(progress?.processed ?? 0))
        entries["total"] = JsValue.Num(value: Double(progress?.total ?? 0))
        entries["pending"] = JsValue.Num(value: Double(progress?.pending ?? 0))
        entries["failed"] = JsValue.Num(value: Double(progress?.failed ?? 0))
        entries["remainingPass1"] = JsValue.Num(value: Double(stats.remainingPass1))
        entries["remainingPass3"] = JsValue.Num(value: Double(stats.remainingPass3))
        return JsValue.Obj(entries: entries)
    }

    /// currentPass → 字符串（无会话时 null，对齐 Android currentPass: String?）。
    private static func currentPassString(_ progress: TagScanSessionProgress?) -> JsValue {
        guard let pass = progress?.currentPass else { return JsValue.Null() }
        return JsValue.Str(value: String(describing: pass))
    }
}

// MARK: - Handler 类（NativeHandlerAsync）
//
// SKIE 把 Kotlin `suspend invoke` 重写为 Swift 协议方法 `__invoke`（双下划线前缀），
// 签名 `__invoke(args:completionHandler:)`（error 参数为 `(any Error)?`）。handler 工作为
// 同步只读（TagDatabase 串行 queue），completionHandler 立即同步调用。

/// gallery.summary：相册总览（计数/扫描覆盖/人物聚类）。
final class GallerySummaryHandler: NSObject, NativeHandlerAsync {
    let name = "gallery.summary"
    func __invoke(args: JsValue, completionHandler: @escaping (JsValue?, (any Error)?) -> Void) {
        completionHandler(GalleryScriptHandlers.buildSummary(), nil)
    }
}

/// gallery.tags：标签清单 + 关联媒体计数。
final class GalleryTagsHandler: NSObject, NativeHandlerAsync {
    let name = "gallery.tags"
    func __invoke(args: JsValue, completionHandler: @escaping (JsValue?, (any Error)?) -> Void) {
        completionHandler(GalleryScriptHandlers.buildTags(), nil)
    }
}

/// tag.scan_status：扫描会话状态快照（只读，不触发/控制扫描）。
final class TagScanStatusHandler: NSObject, NativeHandlerAsync {
    let name = "tag.scan_status"
    func __invoke(args: JsValue, completionHandler: @escaping (JsValue?, (any Error)?) -> Void) {
        completionHandler(GalleryScriptHandlers.buildScanStatus(), nil)
    }
}
