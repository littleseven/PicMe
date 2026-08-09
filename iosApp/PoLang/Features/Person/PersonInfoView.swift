import SwiftUI
import SharedKit

/// 人物详情/编辑页——对标 Android `PersonDetailSheet`。
///
/// 区块：封面 → 名称/本人 → 关系图谱（谓词来自 :shared `RelationPredicate`）→ 已指派照片。
/// `onBack` 关闭 fullScreenCover 并通知列表刷新。
struct PersonInfoView: View {

    let personId: Int64
    var onBack: () -> Void = {}

    @EnvironmentObject private var container: AppContainer
    @Environment(\.dismiss) private var dismiss

    @StateObject private var vm: PersonDetailViewModel
    @State private var showRename = false
    @State private var showCoverPicker = false
    @State private var showAddRelation = false
    @State private var showPhotoPicker = false
    @State private var showDeleteConfirm = false

    init(personId: Int64, onBack: @escaping () -> Void = {}) {
        self.personId = personId
        self.onBack = onBack
        _vm = StateObject(wrappedValue: PersonDetailViewModel(personId: personId))
    }

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            if let person = vm.person {
                VStack(spacing: 0) {
                    topBar(person: person)
                    ScrollView {
                        VStack(spacing: 22) {
                            coverBlock(person: person)
                            attributesBlock(person: person)
                            relationsBlock
                            photosBlock
                            deleteButton
                        }
                        .padding(.horizontal, 16)
                        .padding(.top, 12)
                        .padding(.bottom, 120)
                    }
                }
            } else {
                ProgressView().tint(.white)
            }
        }
        .navigationBarHidden(true)
        .task { vm.load() }
        .sheet(isPresented: $showRename) {
            RenameSheet(initial: vm.person?.name ?? "") { newName in
                if let p = vm.person {
                    try? PersonStore.shared.updatePerson(
                        id: p.id, name: newName,
                        coverMediaId: p.coverMediaId, isSelf: p.isSelf)
                    vm.load()
                }
            }
        }
        .sheet(isPresented: $showCoverPicker) {
            MediaPickerSheet(mode: .single, initiallySelected: coverSet) { picked in
                if let p = vm.person, let id = picked.first {
                    try? PersonStore.shared.updatePerson(
                        id: p.id, name: p.name, coverMediaId: id, isSelf: p.isSelf)
                    vm.load()
                }
            }
            .environmentObject(container)
        }
        .sheet(isPresented: $showAddRelation) {
            AddRelationSheet(excludePersonId: personId) { targetId, predicate in
                vm.addRelation(objectPersonId: targetId, predicate: predicate)
            }
        }
        .sheet(isPresented: $showPhotoPicker) {
            MediaPickerSheet(
                mode: .multi,
                initiallySelected: Set(vm.assignedMediaIds)) { picked in
                let current = Set(vm.assignedMediaIds)
                vm.applyAssignments(
                    add: Array(picked.subtracting(current)),
                    remove: Array(current.subtracting(picked)))
            }
            .environmentObject(container)
        }
        .confirmationDialog(L("Delete this person?"), isPresented: $showDeleteConfirm, titleVisibility: .visible) {
            Button(L("Delete Person"), role: .destructive) {
                if let p = vm.person { try? PersonStore.shared.deletePerson(id: p.id) }
                close()
            }
            Button(L("Cancel"), role: .cancel) {}
        } message: {
            Text(L("Assigned photos will be unassigned, not deleted."))
        }
    }

    private var coverSet: Set<String> {
        Set([vm.person?.coverMediaId].compactMap { $0 })
    }

    private func close() {
        dismiss()
        onBack()
    }

    // MARK: 顶栏

    private func topBar(person: PersonStore.PersonRow) -> some View {
        HStack(spacing: 12) {
            Button { close() } label: {
                Image(systemName: "chevron.left")
                    .font(.system(size: 18, weight: .semibold))
                    .foregroundColor(.white)
                    .frame(width: 36, height: 36)
            }
            Text(person.name)
                .font(.system(size: 18, weight: .semibold))
                .foregroundColor(.white)
                .lineLimit(1)
            Spacer()
            Menu {
                Button {
                    showCoverPicker = true
                } label: { Label(L("Change Cover"), systemImage: "photo") }
                if vm.person?.coverMediaId != nil {
                    Button(role: .destructive) {
                        if let p = vm.person {
                            try? PersonStore.shared.updatePerson(
                                id: p.id, name: p.name, coverMediaId: nil, isSelf: p.isSelf)
                            vm.load()
                        }
                    } label: { Label(L("Remove Cover"), systemImage: "photo.badge.exclamationmark") }
                }
            } label: {
                Image(systemName: "ellipsis.circle")
                    .font(.system(size: 20))
                    .foregroundColor(.white)
                    .frame(width: 36, height: 36)
            }
        }
        .padding(.horizontal, 12)
        .padding(.top, 8)
        .padding(.bottom, 8)
    }

    // MARK: 封面

    private func coverBlock(person: PersonStore.PersonRow) -> some View {
        Button { showCoverPicker = true } label: {
            ZStack {
                if let coverId = person.coverMediaId {
                    ThumbnailView(localIdentifier: coverId)
                        .frame(height: 220)
                } else {
                    ZStack {
                        Color(white: 0.16)
                        VStack(spacing: 8) {
                            Image(systemName: "person.crop.square")
                                .font(.system(size: 40))
                                .foregroundColor(.white.opacity(0.45))
                            Text(L("Set Cover"))
                                .font(.system(size: 13, weight: .medium))
                                .foregroundColor(.white.opacity(0.6))
                        }
                    }
                    .frame(height: 220)
                    .clipShape(RoundedRectangle(cornerRadius: 12))
                }
            }
            .frame(maxWidth: .infinity)
        }
        .buttonStyle(.plain)
    }

    // MARK: 名称 / 本人

    private func attributesBlock(person: PersonStore.PersonRow) -> some View {
        VStack(spacing: 0) {
            Button { showRename = true } label: {
                row(label: L("Name"), value: person.name)
            }
            .buttonStyle(.plain)
            Divider().background(Color.white.opacity(0.08))
            Toggle(isOn: Binding(
                get: { person.isSelf },
                set: { newValue in
                    try? PersonStore.shared.updatePerson(
                        id: person.id, name: person.name,
                        coverMediaId: person.coverMediaId, isSelf: newValue)
                    vm.load()
                })) {
                Text(L("This is me"))
                    .font(.system(size: 15))
                    .foregroundColor(.white)
            }
            .toggleStyle(SwitchToggleStyle(tint: .white))
            .padding(.horizontal, 14)
            .padding(.vertical, 12)
        }
        .background(RoundedRectangle(cornerRadius: 12).fill(Color.white.opacity(0.06)))
    }

    // MARK: 关系

    private var relationsBlock: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text(L("Relations"))
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundColor(.white)
                Spacer()
                Button { showAddRelation = true } label: {
                    HStack(spacing: 4) {
                        Image(systemName: "plus")
                        Text(L("Add Relation"))
                    }
                    .font(.system(size: 13, weight: .medium))
                    .foregroundColor(.white.opacity(0.8))
                }
            }
            if vm.relations.isEmpty {
                Text(L("No relations yet"))
                    .font(.system(size: 13))
                    .foregroundColor(.white.opacity(0.45))
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(14)
                    .background(RoundedRectangle(cornerRadius: 12).fill(Color.white.opacity(0.05)))
            } else {
                VStack(spacing: 0) {
                    ForEach(vm.relations) { rel in
                        relationRow(rel)
                        if rel.id != vm.relations.last?.id {
                            Divider().background(Color.white.opacity(0.08))
                        }
                    }
                }
                .background(RoundedRectangle(cornerRadius: 12).fill(Color.white.opacity(0.06)))
            }
        }
    }

    private func relationRow(_ rel: PersonStore.RelationRow) -> some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text(rel.objectName ?? L("Unknown"))
                    .font(.system(size: 14, weight: .medium))
                    .foregroundColor(.white)
                Text(RelationOptions.label(predicateId: rel.predicate))
                    .font(.system(size: 12))
                    .foregroundColor(.white.opacity(0.55))
            }
            Spacer()
            Button {
                vm.deleteRelation(relationId: rel.id)
            } label: {
                Image(systemName: "minus.circle")
                    .foregroundColor(.red.opacity(0.85))
            }
            .padding(.leading, 8)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
    }

    // MARK: 照片

    private var photosBlock: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text(L("Photos"))
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundColor(.white)
                Spacer()
                Button { showPhotoPicker = true } label: {
                    HStack(spacing: 4) {
                        Image(systemName: "plus")
                        Text(L("Add Photos"))
                    }
                    .font(.system(size: 13, weight: .medium))
                    .foregroundColor(.white.opacity(0.8))
                }
            }
            if vm.assignedMediaIds.isEmpty {
                Text(L("No photos assigned"))
                    .font(.system(size: 13))
                    .foregroundColor(.white.opacity(0.45))
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(14)
                    .background(RoundedRectangle(cornerRadius: 12).fill(Color.white.opacity(0.05)))
            } else {
                photoGrid
            }
        }
    }

    private var photoGrid: some View {
        let cols = [GridItem(.flexible(), spacing: 4), GridItem(.flexible(), spacing: 4), GridItem(.flexible(), spacing: 4)]
        return LazyVGrid(columns: cols, spacing: 4) {
            ForEach(vm.assignedMediaIds, id: \.self) { mediaId in
                ThumbnailView(localIdentifier: mediaId)
            }
        }
    }

    // MARK: 删除

    private var deleteButton: some View {
        Button { showDeleteConfirm = true } label: {
            Text(L("Delete Person"))
                .font(.system(size: 15, weight: .semibold))
                .foregroundColor(.red)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 14)
                .background(RoundedRectangle(cornerRadius: 12).fill(Color.white.opacity(0.06)))
        }
    }

    // MARK: 通用行

    private func row(label: String, value: String) -> some View {
        HStack {
            Text(label)
                .font(.system(size: 15))
                .foregroundColor(.white.opacity(0.7))
            Spacer()
            Text(value)
                .font(.system(size: 15))
                .foregroundColor(.white)
                .lineLimit(1)
            Image(systemName: "chevron.right")
                .font(.system(size: 13))
                .foregroundColor(.white.opacity(0.3))
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 12)
    }
}

// MARK: - 改名表单

struct RenameSheet: View {
    @Environment(\.dismiss) private var dismiss
    @State private var name: String
    let onSave: (String) -> Void

    init(initial: String, onSave: @escaping (String) -> Void) {
        _name = State(initialValue: initial)
        self.onSave = onSave
    }

    var body: some View {
        NavigationView {
            ZStack {
                Color(.systemBackground).ignoresSafeArea()
                VStack(alignment: .leading, spacing: 12) {
                    Text(L("Name")).font(.system(size: 14)).foregroundColor(.secondary)
                    TextField(L("Enter a name"), text: $name)
                        .textFieldStyle(.roundedBorder)
                        .submitLabel(.done)
                    Spacer()
                }
                .padding(20)
            }
            .navigationTitle(L("Rename"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(L("Cancel")) { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button(L("Save")) {
                        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
                        if !trimmed.isEmpty { onSave(trimmed); dismiss() }
                    }
                    .disabled(name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
            }
        }
    }
}

// MARK: - 添加关系（谓词列表 → 目标人物，NavigationStack 内 push，避免链式 sheet 抖动）

/// 单 sheet 双级选择：第一级选择关系谓词（亲属/其他分组），push 第二级选择目标人物。
/// `onPick` 回调在选目标时触发，随即 dismiss 整个 sheet（捕获的是本 sheet 的 dismiss）。
struct AddRelationSheet: View {
    @Environment(\.dismiss) private var dismiss
    let excludePersonId: Int64
    let onPick: (_ targetId: Int64, _ predicate: String) -> Void

    private var family: [RelationOptionItem] { RelationOptions.all().filter { $0.isFamily } }
    private var others: [RelationOptionItem] { RelationOptions.all().filter { !$0.isFamily } }

    var body: some View {
        NavigationView {
            ZStack {
                Color(.systemBackground).ignoresSafeArea()
                List {
                    Section(L("Family")) {
                        ForEach(family) { opt in
                            NavigationLink(opt.label) { targetList(predicate: opt) }
                        }
                    }
                    Section(L("Others")) {
                        ForEach(others) { opt in
                            NavigationLink(opt.label) { targetList(predicate: opt) }
                        }
                    }
                }
            }
            .navigationTitle(L("Select Relation"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(L("Cancel")) { dismiss() } }
            }
        }
    }

    /// 第二级：目标人物列表（排除当前人物）。选中后回调 + 关闭整个 sheet。
    private func targetList(predicate: RelationOptionItem) -> some View {
        TargetPersonList(excludePersonId: excludePersonId) { targetId in
            onPick(targetId, predicate.id)
            dismiss()  // 捕获的是 AddRelationSheet 的 dismiss → 关闭 sheet
        }
    }
}

private struct TargetPersonList: View {
    let excludePersonId: Int64
    let onPick: (Int64) -> Void

    @State private var persons: [PersonStore.PersonRow] = []

    var body: some View {
        ZStack {
            Color(.systemBackground).ignoresSafeArea()
            if persons.isEmpty {
                VStack(spacing: 8) {
                    Image(systemName: "person.2").font(.system(size: 36)).foregroundColor(.secondary)
                    Text(L("No other people yet")).font(.system(size: 14)).foregroundColor(.secondary)
                }
            } else {
                List {
                    ForEach(persons) { person in
                        Button {
                            onPick(person.id)
                        } label: {
                            HStack {
                                Text(person.name).foregroundColor(.primary)
                                Spacer()
                                if person.isSelf {
                                    Text(L("Me")).font(.caption).foregroundColor(.secondary)
                                }
                            }
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
        }
        .navigationTitle(L("Select Person"))
        .navigationBarTitleDisplayMode(.inline)
        .task {
            persons = (try? PersonStore.shared.allPersonsSorted().filter { row in row.id != excludePersonId }) ?? []
        }
    }
}

// MARK: - 媒体选择器（单/多；封面=单，照片指派=多）

/// 从相册全部媒体中选取（localIdentifier）。单选确认后回调首个；多选确认后回调全部选中。
struct MediaPickerSheet: View {
    enum Mode { case single, multi }

    @EnvironmentObject private var container: AppContainer
    @Environment(\.dismiss) private var dismiss

    let mode: Mode
    let initiallySelected: Set<String>
    let onConfirm: (Set<String>) -> Void

    @State private var items: [String] = []   // 全部 localIdentifier（去重保序）
    @State private var selected: Set<String> = []

    private let cols = [
        GridItem(.flexible(), spacing: 4), GridItem(.flexible(), spacing: 4), GridItem(.flexible(), spacing: 4),
    ]

    var body: some View {
        NavigationView {
            ZStack {
                Color(.systemBackground).ignoresSafeArea()
                ScrollView {
                    LazyVGrid(columns: cols, spacing: 4) {
                        ForEach(items, id: \.self) { mediaId in
                            cell(mediaId)
                        }
                    }
                    .padding(4)
                }
            }
            .navigationTitle(mode == .single ? L("Set Cover") : L("Add Photos"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(L("Cancel")) { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button(L("Done")) {
                        onConfirm(selected)
                        dismiss()
                    }
                    .disabled(mode == .single && selected.isEmpty)
                }
            }
            .task {
                let all = container.mediaBridge.fetchAllMedia().map { item -> String in item.localIdentifier }
                // 去重保序
                var seen = Set<String>()
                items = all.filter { seen.insert($0).inserted }
                selected = initiallySelected
            }
        }
    }

    private func cell(_ mediaId: String) -> some View {
        let isSelected = selected.contains(mediaId)
        return Button {
            if mode == .single {
                selected = [mediaId]
            } else if isSelected {
                selected.remove(mediaId)
            } else {
                selected.insert(mediaId)
            }
        } label: {
            ZStack(alignment: .bottomTrailing) {
                ThumbnailView(localIdentifier: mediaId)
                if isSelected {
                    ZStack {
                        Circle().fill(Color.white)
                        Image(systemName: "checkmark.circle.fill")
                            .font(.system(size: 20))
                            .foregroundColor(.blue)
                    }
                    .frame(width: 22, height: 22)
                    .padding(6)
                }
            }
        }
        .buttonStyle(.plain)
    }
}
