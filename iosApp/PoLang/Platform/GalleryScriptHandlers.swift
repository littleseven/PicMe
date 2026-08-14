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

    /// 注册 Tier 1+2+3 全部 handler 到 [runtime]（12/12 只读 handler 齐备）。
    static func registerAll(into runtime: JsRuntime) {
        runtime.register(handler: GallerySummaryHandler())
        runtime.register(handler: GalleryTagsHandler())
        runtime.register(handler: TagScanStatusHandler())
        runtime.register(handler: GalleryQueryHandler())
        runtime.register(handler: MediaMetaHandler())
        runtime.register(handler: MediaBatchMetaHandler())
        runtime.register(handler: GalleryStatsByTagHandler())
        runtime.register(handler: GalleryTimelineHandler())
        runtime.register(handler: GalleryIntersectHandler())
        runtime.register(handler: GalleryCityHandler())
        runtime.register(handler: FaceClusterHandler())
        runtime.register(handler: TagAuditHandler())
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

    // MARK: - gallery.tags（标签清单 + 关联计数，扁平 {tag: count} 对齐 Android toTagsJsValue）

    static func buildTags() -> JsValue {
        let counts = TagDatabase.shared.tagCounts(limit: 50)
        var entries: [String: JsValue] = [:]
        for pair in counts {
            entries[pair.name] = JsValue.Num(value: Double(pair.count))
        }
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

    // MARK: - Tier 2：gallery.query / media.meta / media.batch_meta / gallery.stats_by_tag

    /// JS filter 对象 → GalleryQueryFilter（移植 Android parseQueryFilter，全可选/缺省走默认）。
    static func parseQueryFilter(_ args: JsValue) -> GalleryQueryFilter {
        guard let obj = args as? JsValue.Obj else { return GalleryQueryFilter() }
        let entries = obj.entries
        func str(_ key: String) -> String? {
            guard let s = entries[key] as? JsValue.Str else { return nil }
            return s.value.nilIfBlank
        }
        func num(_ key: String) -> Int64? {
            guard let n = entries[key] as? JsValue.Num else { return nil }
            return Int64(n.value)
        }
        func bool(_ key: String) -> Bool? {
            guard let b = entries[key] as? JsValue.Bool else { return nil }
            return b.value
        }
        let limit: Int? = {
            guard let n = entries["limit"] as? JsValue.Num else { return nil }
            return Int(n.value)
        }()
        var filter = GalleryQueryFilter()
        filter.label = str("label")
        filter.ocr = str("ocr")
        filter.location = str("location")
        filter.fromMs = num("fromMs")
        filter.toMs = num("toMs")
        filter.hasFace = bool("hasFace")
        filter.person = str("person")
        filter.limit = limit ?? GalleryQueryFilter.defaultLimit
        return filter
    }

    /// gallery.query：filter → 命中 id（截断 limit）+ 未截断 total。对齐 Android toResultJsValue。
    static func buildQueryResult(filter: GalleryQueryFilter) -> JsValue {
        let ids = TagDatabase.shared.queryMediaIds(filter: filter)
        let truncated = Array(ids.prefix(filter.limit))
        var idItems: [JsValue] = []
        idItems.reserveCapacity(truncated.count)
        for id in truncated {
            idItems.append(JsValue.Num(value: Double(id)))
        }
        var entries: [String: JsValue] = [:]
        entries["ids"] = JsValue.Arr(items: idItems)
        entries["total"] = JsValue.Num(value: Double(ids.count))
        return JsValue.Obj(entries: entries)
    }

    /// MediaDbRow → media.meta 白名单元数据（隐私红线：无 uri/gps/ocrText/embedding）。
    /// 对齐 Android MediaEntity.toMetaJsValue。
    static func buildMeta(_ row: MediaDbRow) -> JsValue {
        var entries: [String: JsValue] = [:]
        entries["id"] = JsValue.Num(value: Double(row.id))
        entries["type"] = JsValue.Str(value: row.type)
        entries["captureMs"] = JsValue.Num(value: Double(row.captureMs))
        entries["fileName"] = JsValue.Str(value: row.fileName)
        entries["labels"] = parseLabelArrayJsValue(row.labels)
        entries["locationName"] = optStr(row.locationName)
        entries["city"] = optStr(row.city)
        entries["hasFace"] = JsValue.Bool(value: row.hasFace)
        entries["faceId"] = optStr(row.faceId)
        entries["aestheticScore"] = optNum(row.aestheticScore)
        entries["faceQualityScore"] = optNum(row.faceQualityScore)
        return JsValue.Obj(entries: entries)
    }

    /// media.batch_meta：多行 → JsValue.Arr。
    static func buildBatchMeta(_ rows: [MediaDbRow]) -> JsValue {
        JsValue.Arr(items: rows.map { buildMeta($0) })
    }

    /// gallery.stats_by_tag：filter 结果集内标签分布（扁平 {tag: count}，对齐 Android toTagsJsValue）。
    static func buildStatsByTag(filter: GalleryQueryFilter) -> JsValue {
        let counts = TagDatabase.shared.tagsByFilter(filter: filter, limit: 50)
        var entries: [String: JsValue] = [:]
        for pair in counts {
            entries[pair.name] = JsValue.Num(value: Double(pair.count))
        }
        return JsValue.Obj(entries: entries)
    }

    /// labels JSON 数组串 `["猫","户外"]` → JsValue.Arr；空/异常 → 空数组。
    private static func parseLabelArrayJsValue(_ raw: String?) -> JsValue {
        guard let raw = raw, !raw.isEmpty,
              let data = raw.data(using: .utf8),
              let array = try? JSONSerialization.jsonObject(with: data) as? [String] else {
            return JsValue.Arr(items: [])
        }
        return JsValue.Arr(items: array.map { JsValue.Str(value: $0) })
    }

    /// String? → JsValue.Str 或 .Null（显式 JsValue 返回，规避 map+?? 跨子类型推断）。
    private static func optStr(_ value: String?) -> JsValue {
        guard let value = value else { return JsValue.Null() }
        return JsValue.Str(value: value)
    }

    /// Double? → JsValue.Num 或 .Null。
    private static func optNum(_ value: Double?) -> JsValue {
        guard let value = value else { return JsValue.Null() }
        return JsValue.Num(value: value)
    }

    /// 解析 media.meta 的 id 参数：Num 或 Arr[0]（对齐 Android）。
    static func parseId(_ args: JsValue) -> Int64? {
        if let num = args as? JsValue.Num {
            return Int64(num.value)
        }
        if let arr = args as? JsValue.Arr, let first = arr.items.first as? JsValue.Num {
            return Int64(first.value)
        }
        return nil
    }

    /// 解析 media.batch_meta 的 id 列表：Arr 或 {ids:[...]}（对齐 Android）。
    static func parseIdList(_ args: JsValue) -> [Int64] {
        if let arr = args as? JsValue.Arr {
            return arr.items.compactMap { ($0 as? JsValue.Num).map { Int64($0.value) } }
        }
        if let obj = args as? JsValue.Obj, let ids = obj.entries["ids"] as? JsValue.Arr {
            return ids.items.compactMap { ($0 as? JsValue.Num).map { Int64($0.value) } }
        }
        return []
    }

    // MARK: - Tier 3：timeline / intersect / city / face.cluster / tag.audit

    /// 近似月分桶（30 天，对齐 Android BUCKET_MONTH_MS）。
    static let bucketMonthMs: Int64 = Int64(30) * 24 * 60 * 60 * 1000

    /// gallery.timeline：{fromMs?, toMs?, bucketMs?} → 分桶统计（扁平 {桶起始ms: 计数}）。
    static func buildTimeline(args: JsValue) -> JsValue {
        let entries = (args as? JsValue.Obj)?.entries ?? [:]
        let fromMs = (entries["fromMs"] as? JsValue.Num).map { Int64($0.value) }
        let toMs = (entries["toMs"] as? JsValue.Num).map { Int64($0.value) }
        let bucketMs = (entries["bucketMs"] as? JsValue.Num).map { Int64($0.value) } ?? bucketMonthMs
        let buckets = TagDatabase.shared.timelineCounts(fromMs: fromMs, toMs: toMs, bucketMs: bucketMs)
        var out: [String: JsValue] = [:]
        for bucket in buckets {
            out[String(bucket.bucketMs)] = JsValue.Num(value: Double(bucket.count))
        }
        return JsValue.Obj(entries: out)
    }

    /// gallery.intersect：{idsA:[...], idsB:[...], op} → {ids, total}（纯端侧集合运算，不查 DB）。
    static func buildIntersect(args: JsValue) -> JsValue {
        let entries = (args as? JsValue.Obj)?.entries ?? [:]
        func parseIds(_ key: String) -> [Int64] {
            guard let arr = entries[key] as? JsValue.Arr else { return [] }
            return arr.items.compactMap { ($0 as? JsValue.Num).map { Int64($0.value) } }
        }
        let idsA = parseIds("idsA")
        let idsB = parseIds("idsB")
        let op = (entries["op"] as? JsValue.Str)?.value.lowercased() ?? "intersect"
        let orderedA = LinkedOrderSet(idsA)
        let setB = Set(idsB)
        let result: [Int64]
        switch op {
        case "union":
            var merged = orderedA.array
            for id in idsB where !orderedA.set.contains(id) { merged.append(id) }
            result = merged
        case "diff":
            result = orderedA.array.filter { !setB.contains($0) }
        default: // intersect
            result = orderedA.array.filter { setB.contains($0) }
        }
        var out: [String: JsValue] = [:]
        out["ids"] = JsValue.Arr(items: result.map { JsValue.Num(value: Double($0)) })
        out["total"] = JsValue.Num(value: Double(result.count))
        return JsValue.Obj(entries: out)
    }

    /// gallery.stats_by_city：按城市分组媒体计数（扁平 {city: count}）。
    static func buildCity(args: JsValue) -> JsValue {
        let counts = TagDatabase.shared.cityCounts(limit: parseTopN(args))
        var out: [String: JsValue] = [:]
        for pair in counts {
            out[pair.city] = JsValue.Num(value: Double(pair.count))
        }
        return JsValue.Obj(entries: out)
    }

    /// face.cluster：人脸聚类盘点（计数 + topPersons，不回 embedding 原始数据）。
    static func buildFaceCluster(args: JsValue) -> JsValue {
        let topN = parseTopN(args)
        let persons = TagDatabase.shared.allPersonRows()
        let embeddings = TagDatabase.shared.embeddingCounts()
        let topPersons = persons.sorted { $0.faceCount > $1.faceCount }.prefix(topN)
        var topItems: [JsValue] = []
        for person in topPersons {
            var item: [String: JsValue] = [:]
            item["personId"] = JsValue.Num(value: Double(person.personId))
            item["name"] = optStr((person.name ?? "").isEmpty ? nil : person.name)
            item["faceCount"] = JsValue.Num(value: Double(person.faceCount))
            item["coverMediaId"] = optNum(person.coverMediaId.map { Double($0) })
            topItems.append(JsValue.Obj(entries: item))
        }
        var out: [String: JsValue] = [:]
        out["clusterCount"] = JsValue.Num(value: Double(persons.count))
        out["namedCount"] = JsValue.Num(value: Double(persons.filter { !($0.name ?? "").isEmpty }.count))
        out["totalEmbeddings"] = JsValue.Num(value: Double(embeddings.total))
        out["unassignedEmbeddings"] = JsValue.Num(value: Double(embeddings.unassigned))
        out["topPersons"] = JsValue.Arr(items: topItems)
        return JsValue.Obj(entries: out)
    }

    /// tag.audit：打标覆盖审计（COUNT 聚合 + outOfVocabTags 词表外标签 topN）。
    static func buildTagAudit(args: JsValue) -> JsValue {
        let topN = parseTopN(args)
        let counts = TagDatabase.shared.tagAuditCounts()
        let distribution = TagDatabase.shared.tagCounts(limit: Int.maxSafe)
        let vocab = vocabTags()
        let outOfVocab = distribution
            .filter { !vocab.contains($0.name) }
            .sorted { $0.count > $1.count }
            .prefix(topN)
        var outOfVocabObj: [String: JsValue] = [:]
        for pair in outOfVocab {
            outOfVocabObj[pair.name] = JsValue.Num(value: Double(pair.count))
        }
        var out: [String: JsValue] = [:]
        out["totalMedia"] = JsValue.Num(value: Double(counts.totalMedia))
        out["unlabeledCount"] = JsValue.Num(value: Double(counts.unlabeled))
        out["neverScannedCount"] = JsValue.Num(value: Double(counts.neverScanned))
        out["lastScanAt"] = optNum(counts.lastScanAt.map { Double($0) })
        out["outOfVocabTags"] = JsValue.Obj(entries: outOfVocabObj)
        return JsValue.Obj(entries: out)
    }

    /// 解析 {topN?:number}（默认 10，上限 50，对齐 Android parseTopN）。
    private static func parseTopN(_ args: JsValue) -> Int {
        let raw = ((args as? JsValue.Obj)?.entries["topN"] as? JsValue.Num)?.value
        return min(max(Int(raw ?? 10), 1), 50)
    }

    /// 受控词表全集（controlled_vocab.json 所有类别 zh+en），用于 tag.audit outOfVocabTags。懒缓存。
    private static var vocabCache: Set<String>?
    private static let vocabLock = NSLock()
    static func vocabTags() -> Set<String> {
        vocabLock.lock()
        defer { vocabLock.unlock() }
        if let cached = vocabCache { return cached }
        var tags = Set<String>()
        if let url = Bundle.main.url(forResource: "controlled_vocab", withExtension: "json"),
           let data = try? Data(contentsOf: url),
           let json = try? JSONSerialization.jsonObject(with: data) as? [String: [String]] {
            for (_, arr) in json { tags.formUnion(arr) }
        }
        vocabCache = tags
        return tags
    }
}

/// 保序去重集（intersect/union 保持 idsA 顺序，对齐 Android LinkedHashSet）。
private struct LinkedOrderSet {
    let array: [Int64]
    let set: Set<Int64>
    init(_ items: [Int64]) {
        var seen = Set<Int64>()
        var ordered: [Int64] = []
        for x in items where !seen.contains(x) {
            seen.insert(x)
            ordered.append(x)
        }
        array = ordered
        set = seen
    }
}

private extension Int {
    /// tagCounts 全量上限（防硬编码魔法数）。
    static var maxSafe: Int { 10_000 }
}

private extension String {
    /// 空白串 → nil（对齐 Kotlin takeIf { isNotBlank }）。
    var nilIfBlank: String? { trimmingCharacters(in: .whitespaces).isEmpty ? nil : self }
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

// MARK: - Tier 2 Handler 类（query / meta / batch_meta / stats_by_tag）

/// gallery.query：多维 AND filter → 命中 id（截断 limit）+ total。
final class GalleryQueryHandler: NSObject, NativeHandlerAsync {
    let name = "gallery.query"
    func __invoke(args: JsValue, completionHandler: @escaping (JsValue?, (any Error)?) -> Void) {
        let filter = GalleryScriptHandlers.parseQueryFilter(args)
        completionHandler(GalleryScriptHandlers.buildQueryResult(filter: filter), nil)
    }
}

/// media.meta：单媒体白名单元数据（id 缺失/未找到 → null）。
final class MediaMetaHandler: NSObject, NativeHandlerAsync {
    let name = "media.meta"
    func __invoke(args: JsValue, completionHandler: @escaping (JsValue?, (any Error)?) -> Void) {
        let value: JsValue
        if let id = GalleryScriptHandlers.parseId(args),
           let row = TagDatabase.shared.mediaRow(id: id) {
            value = GalleryScriptHandlers.buildMeta(row)
        } else {
            value = JsValue.Null()
        }
        completionHandler(value, nil)
    }
}

/// media.batch_meta：批量白名单元数据（id 列表，截断 50 防爆量）。
final class MediaBatchMetaHandler: NSObject, NativeHandlerAsync {
    let name = "media.batch_meta"
    func __invoke(args: JsValue, completionHandler: @escaping (JsValue?, (any Error)?) -> Void) {
        let ids = GalleryScriptHandlers.parseIdList(args)
        let rows = TagDatabase.shared.mediaRows(ids: ids)
        completionHandler(GalleryScriptHandlers.buildBatchMeta(rows), nil)
    }
}

/// gallery.stats_by_tag：filter 结果集内标签分布（扁平 {tag: count}）。
final class GalleryStatsByTagHandler: NSObject, NativeHandlerAsync {
    let name = "gallery.stats_by_tag"
    func __invoke(args: JsValue, completionHandler: @escaping (JsValue?, (any Error)?) -> Void) {
        let filter = GalleryScriptHandlers.parseQueryFilter(args)
        completionHandler(GalleryScriptHandlers.buildStatsByTag(filter: filter), nil)
    }
}

// MARK: - Tier 3 Handler 类（timeline / intersect / city / face.cluster / tag.audit）

/// gallery.timeline：时间分桶统计（{fromMs?, toMs?, bucketMs?}）。
final class GalleryTimelineHandler: NSObject, NativeHandlerAsync {
    let name = "gallery.timeline"
    func __invoke(args: JsValue, completionHandler: @escaping (JsValue?, (any Error)?) -> Void) {
        completionHandler(GalleryScriptHandlers.buildTimeline(args: args), nil)
    }
}

/// gallery.intersect：id 集合交/并/差（纯计算）。
final class GalleryIntersectHandler: NSObject, NativeHandlerAsync {
    let name = "gallery.intersect"
    func __invoke(args: JsValue, completionHandler: @escaping (JsValue?, (any Error)?) -> Void) {
        completionHandler(GalleryScriptHandlers.buildIntersect(args: args), nil)
    }
}

/// gallery.stats_by_city：按城市分组媒体计数。
final class GalleryCityHandler: NSObject, NativeHandlerAsync {
    let name = "gallery.stats_by_city"
    func __invoke(args: JsValue, completionHandler: @escaping (JsValue?, (any Error)?) -> Void) {
        completionHandler(GalleryScriptHandlers.buildCity(args: args), nil)
    }
}

/// face.cluster：人脸聚类盘点（计数 + topPersons，不回 embedding 原始数据）。
final class FaceClusterHandler: NSObject, NativeHandlerAsync {
    let name = "face.cluster"
    func __invoke(args: JsValue, completionHandler: @escaping (JsValue?, (any Error)?) -> Void) {
        completionHandler(GalleryScriptHandlers.buildFaceCluster(args: args), nil)
    }
}

/// tag.audit：打标覆盖审计（COUNT + outOfVocabTags）。
final class TagAuditHandler: NSObject, NativeHandlerAsync {
    let name = "tag.audit"
    func __invoke(args: JsValue, completionHandler: @escaping (JsValue?, (any Error)?) -> Void) {
        completionHandler(GalleryScriptHandlers.buildTagAudit(args: args), nil)
    }
}
