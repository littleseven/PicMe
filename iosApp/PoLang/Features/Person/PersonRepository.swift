import Foundation

// MARK: - 人物域显示模型

/// 人物列表显示项（聚合 persons + 封面 + 对己关系 + 照片数）。
/// 对标 Android PersonScreen 的 persons + covers + relations + photoCounts 四张映射。
struct PersonDisplayItem: Identifiable, Equatable {
    let id: Int64                 // personId
    let name: String?             // nil/空 = 未命名
    let coverLocalIdentifier: String?
    let coverFaceFocusY: Float?
    let photoCount: Int
    let isSelf: Bool
    let updatedAt: Int64
    let relation: PersonRelationDb?   // 该人物对己关系
}

/// 列表快照：过滤+排序后的可见项 + 过滤前总数（标题"可见/总数"用）。
struct PersonListSnapshot: Equatable {
    let items: [PersonDisplayItem]
    let totalCount: Int
}

// MARK: - PersonRepository（对标 Android PersonRepository + PersonCoverResolver + 过滤/排序）

/// 人物域编排层。读 `TagDatabase` 聚类数据，产出列表快照；提供详情编辑原子操作。
/// 谓词→isFamily 映射复用 `RelationOptions`（SSOT = :shared RelationPredicate）。
final class PersonRepository: @unchecked Sendable {
    static let shared = PersonRepository()

    private let db = TagDatabase.shared
    private init() {}

    // MARK: - 加载

    /// 进入页：reconcile（修复孤儿/悬空）→ load。幂等。
    /// 注：**不**自动触发 Pass2 重聚类——iOS Pass2 为全量重扫（清空 persons/名字），
    /// 与 Android 增量 split/merge 不同；重聚类由工具栏显式触发（见 ViewModel.recluster）。
    func reconcileAndLoad(showAll: Bool) -> PersonListSnapshot {
        db.reconcilePersons()
        return load(showAll: showAll)
    }

    /// 全量重建快照：封面解析 + photoCount + 对己关系 + coverable 过滤 + 单人组过滤 + 排序。
    func load(showAll: Bool) -> PersonListSnapshot {
        let all = db.allPersonRows()
        let totalCount = all.count
        let familySet = Set(RelationOptions.all().filter { $0.isFamily }.map { $0.id })

        // 封面批量解析（避免 N 次查询）
        let coverIds = all.compactMap { $0.coverMediaId }
        let coverMap = db.coverInfos(for: coverIds)

        var items: [PersonDisplayItem] = []
        items.reserveCapacity(all.count)
        for row in all {
            let cover = row.coverMediaId.flatMap { coverMap[$0] }
            let named = (row.name?.isEmpty == false)
            let photoCount = db.photoCountForPerson(personId: row.personId, name: named ? row.name : nil)
            items.append(PersonDisplayItem(
                id: row.personId,
                name: row.name,
                coverLocalIdentifier: cover?.localIdentifier,
                coverFaceFocusY: cover?.faceFocusY,
                photoCount: photoCount,
                isSelf: row.isSelf,
                updatedAt: row.updatedAt,
                relation: db.relationToSelf(personId: row.personId)))
        }

        // coverable 过滤：封面须可解析（localIdentifier 非空）
        var filtered = items.filter { ($0.coverLocalIdentifier?.isEmpty == false) }

        // 默认（showAll=false）隐藏「未命名 且 photoCount<2」的单人噪声组
        if !showAll {
            filtered = filtered.filter { item in
                let named = (item.name?.isEmpty == false)
                return named || item.photoCount >= 2
            }
        }

        // 排序（对齐 Android sortedForDisplay）
        let sorted = filtered.sorted { a, b in
            let ia = intimacy(of: a, familySet: familySet)
            let ib = intimacy(of: b, familySet: familySet)
            if ia != ib { return ia > ib }
            let aNamed = (a.name?.isEmpty == false)
            let bNamed = (b.name?.isEmpty == false)
            if aNamed != bNamed { return aNamed }            // 命名优先
            if aNamed {
                if a.photoCount != b.photoCount { return a.photoCount > b.photoCount }   // 命名：照片数降序
            } else if a.updatedAt != b.updatedAt {
                return a.updatedAt > b.updatedAt             // 未命名：最近优先
            }
            return a.updatedAt > b.updatedAt                 // 兜底
        }
        return PersonListSnapshot(items: sorted, totalCount: totalCount)
    }

    /// 排序亲密度权重（对齐 Android：self>浪漫>偶像>家庭>社会>无）。
    private static let romanticPredicates: Set<String> = ["PARTNER", "SPOUSE"]
    private func intimacy(of item: PersonDisplayItem, familySet: Set<String>) -> Int {
        if item.isSelf { return 5 }
        guard let pred = item.relation?.predicate, !pred.isEmpty else { return 0 }
        if Self.romanticPredicates.contains(pred) { return 4 }
        if pred == "IDOL" { return 3 }
        if familySet.contains(pred) { return 2 }
        return 1
    }

    // MARK: - 详情编辑（原子操作；VM 调用后自行 reload）

    func person(_ personId: Int64) -> PersonDbRow? { db.personRow(personId) }
    func rename(personId: Int64, name: String?) { db.renamePerson(personId: personId, name: name) }
    func updateCover(personId: Int64, mediaId: Int64?) { db.updatePersonCover(personId: personId, mediaId: mediaId) }
    func setSelf(personId: Int64, isSelf: Bool) { db.setSelf(personId: personId, isSelf: isSelf) }
    func relation(personId: Int64) -> PersonRelationDb? { db.relationToSelf(personId: personId) }
    func coverCandidates(personId: Int64) -> [MediaCoverInfo] { db.coverCandidates(personId: personId) }

    /// 保存对己关系：清旧→写新。customLabel 非空→effective predicate=OTHER（对齐 Android doSave）。
    /// predicate=nil 且 customLabel 空 → 仅清空（不设置关系）。
    func saveRelation(personId: Int64, predicate: String?, customLabel: String?, source: String) {
        db.clearRelationToSelf(personId: personId)
        let customFilled = (customLabel?.isEmpty == false)
        if let pred = predicate, !pred.isEmpty {
            let effective = customFilled ? "OTHER" : pred
            _ = db.upsertRelationToSelf(
                subjectPersonId: personId, predicate: effective,
                source: source, customLabel: customLabel)
        } else if customFilled {
            // 仅自定义、无谓词 → OTHER
            _ = db.upsertRelationToSelf(
                subjectPersonId: personId, predicate: "OTHER",
                source: source, customLabel: customLabel)
        }
    }
}
