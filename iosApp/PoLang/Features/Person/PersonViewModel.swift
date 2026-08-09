import Foundation
import SharedKit

// MARK: - 关系选项桥接（:shared `PersonRelationSupport` → Swift 友好结构）

/// 当前 App 语言代码（与 LanguageManager 对齐："跟随系统"时取系统首选）。
enum PersonLanguage {
    static func code() -> String {
        switch LanguageManager.shared.currentLanguage {
        case "english": return "en"
        case "chinese_simplified", "chinese_traditional": return "zh"
        default:
            let pref = Locale.preferredLanguages.first ?? "en"
            if pref.hasPrefix("zh") { return "zh" }
            if pref.hasPrefix("ja") { return "ja" }
            return "en"
        }
    }
}

/// 关系谓词选项（Swift 端纯值类型，避免 ForEach 直接持有 K/N 对象）。
/// 谓词词汇的单一事实来源 = :shared `RelationPredicate`；本结构仅做渲染桥接。
struct RelationOptionItem: Identifiable, Hashable {
    let id: String
    let labelZh: String
    let labelEn: String
    let labelJa: String
    let isFamily: Bool

    var label: String {
        switch PersonLanguage.code() {
        case "zh": return labelZh
        case "ja": return labelJa
        default: return labelEn
        }
    }
}

enum RelationOptions {
    /// 全部关系选项（顺序对齐 :shared 枚举顺序）。
    static func all() -> [RelationOptionItem] {
        PersonRelationSupport.shared.allOptions().map { opt in
            RelationOptionItem(
                id: opt.id,
                labelZh: opt.labelZh,
                labelEn: opt.labelEn,
                labelJa: opt.labelJa,
                isFamily: opt.isFamily)
        }
    }

    /// 谓词 id → 当前语言标签（关系 chip 兜底展示）。
    static func label(predicateId: String) -> String {
        guard let opt = PersonRelationSupport.shared.allOptions().first(where: { $0.id == predicateId }) else {
            return predicateId
        }
        switch PersonLanguage.code() {
        case "zh": return opt.labelZh
        case "ja": return opt.labelJa
        default: return opt.labelEn
        }
    }
}

// MARK: - 人物列表 ViewModel

/// 人物页（列表）状态。对标 Android `PersonViewModel`：加载/增/删/改名/设封面/设本人。
@MainActor
final class PersonViewModel: ObservableObject {

    @Published private(set) var persons: [PersonStore.PersonRow] = []
    @Published private(set) var isLoading = false

    private let store: PersonStore

    init(store: PersonStore = .shared) {
        self.store = store
    }

    func load() {
        isLoading = true
        defer { isLoading = false }
        do {
            persons = try store.allPersonsSorted()
        } catch {
            NSLog("PoLang:Person load failed: \(error)")
            persons = []
        }
    }

    func createPerson(name: String, coverMediaId: String?, isSelf: Bool) {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        do {
            _ = try store.createPerson(name: trimmed, coverMediaId: coverMediaId, isSelf: isSelf)
            persons = try store.allPersonsSorted()
        } catch {
            NSLog("PoLang:Person create failed: \(error)")
        }
    }

    func rename(id: Int64, name: String) {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, let current = persons.first(where: { $0.id == id }) else { return }
        do {
            try store.updatePerson(id: id, name: trimmed, coverMediaId: current.coverMediaId, isSelf: current.isSelf)
            persons = try store.allPersonsSorted()
        } catch {
            NSLog("PoLang:Person rename failed: \(error)")
        }
    }

    func setCover(id: Int64, coverMediaId: String?) {
        guard let current = persons.first(where: { $0.id == id }) else { return }
        do {
            try store.updatePerson(id: id, name: current.name, coverMediaId: coverMediaId, isSelf: current.isSelf)
            persons = try store.allPersonsSorted()
        } catch {
            NSLog("PoLang:Person setCover failed: \(error)")
        }
    }

    func setSelf(id: Int64, isSelf: Bool) {
        guard let current = persons.first(where: { $0.id == id }) else { return }
        do {
            try store.updatePerson(id: id, name: current.name, coverMediaId: current.coverMediaId, isSelf: isSelf)
            persons = try store.allPersonsSorted()
        } catch {
            NSLog("PoLang:Person setSelf failed: \(error)")
        }
    }

    func deletePerson(id: Int64) {
        do {
            try store.deletePerson(id: id)
            persons = try store.allPersonsSorted()
        } catch {
            NSLog("PoLang:Person delete failed: \(error)")
        }
    }
}

// MARK: - 人物详情 ViewModel

/// 人物详情状态：本体 + 发出关系 + 已指派照片。支持关系增删、照片批量指派。
@MainActor
final class PersonDetailViewModel: ObservableObject {

    @Published private(set) var person: PersonStore.PersonRow?
    @Published private(set) var relations: [PersonStore.RelationRow] = []
    @Published private(set) var assignedMediaIds: [String] = []
    @Published private(set) var isLoading = false

    private let store: PersonStore
    private let personId: Int64

    init(personId: Int64, store: PersonStore = .shared) {
        self.personId = personId
        self.store = store
    }

    func load() {
        isLoading = true
        defer { isLoading = false }
        reload()
    }

    private func reload() {
        do {
            person = try store.person(id: personId)
            relations = try store.relations(subjectPersonId: personId)
            assignedMediaIds = try store.assignedMediaIds(personId: personId)
        } catch {
            NSLog("PoLang:PersonDetail load failed: \(error)")
        }
    }

    // MARK: 关系

    func addRelation(objectPersonId: Int64, predicate: String) {
        do {
            try store.upsertRelation(
                subjectPersonId: personId,
                objectPersonId: objectPersonId,
                predicate: predicate,
                source: RelationSource.renameDialog.name,  // iOS 暂无聊天声明通道，统一记为对话框来源
                customLabel: nil)
            reload()
        } catch {
            NSLog("PoLang:PersonDetail addRelation failed: \(error)")
        }
    }

    func deleteRelation(relationId: Int64) {
        do {
            try store.deleteRelation(relationId: relationId)
            reload()
        } catch {
            NSLog("PoLang:PersonDetail deleteRelation failed: \(error)")
        }
    }

    // MARK: 照片指派

    func applyAssignments(add: [String], remove: [String]) {
        guard !(add.isEmpty && remove.isEmpty) else { return }
        do {
            try store.applyAssignments(personId: personId, add: add, remove: remove)
            reload()
        } catch {
            NSLog("PoLang:PersonDetail applyAssignments failed: \(error)")
        }
    }
}
