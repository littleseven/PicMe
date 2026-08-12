import SwiftUI

/// 人物页（列表）——对标 Android `PersonScreen` + `PersonListItem`。
///
/// 聚类模型：人物来自 TAG 扫描人脸聚类（`TagDatabase.persons`），非手动建人。
/// 2 列网格，人脸感知封面卡，行内改名，关系 chip，Android 排序；顶栏动态计数标题 + 筛选/重聚类。
/// `onBack` 由 MainTabView（page 3）或 Settings（NavigationStack push）注入。
struct PersonView: View {

    var onBack: (() -> Void)? = nil

    @Environment(\.dismiss) private var dismiss
    @Environment(\.colorScheme) private var cs
    private var s: SchemeColors { appScheme(cs) }
    @StateObject private var vm = PersonViewModel()
    @State private var detailRoute: DetailRoute?

    private let columns = [
        GridItem(.flexible(), spacing: PersonTokens.gridSpacing),
        GridItem(.flexible(), spacing: PersonTokens.gridSpacing),
    ]

    var body: some View {
        ZStack {
            s.background.ignoresSafeArea()
            VStack(spacing: 0) {
                topBar
                content
            }
            if let toast = vm.toast {
                toastView(toast)
            }
        }
        .navigationBarHidden(true)
        .task { vm.onAppear() }
        .task(id: vm.toast) {
            guard vm.toast != nil else { return }
            try? await Task.sleep(nanoseconds: 3_000_000_000)
            vm.toast = nil
        }
        .fullScreenCover(item: $detailRoute) { route in
            PersonInfoView(personId: route.id) {
                detailRoute = nil
                vm.refresh()
            }
        }
    }

    // MARK: 顶栏

    private var visibleCount: Int { vm.items.count }

    private var topBar: some View {
        HStack(spacing: 8) {
            Button {
                if let onBack { onBack() } else { dismiss() }
            } label: {
                Image(systemName: "chevron.left")
                    .font(.system(size: 18, weight: .semibold))
                    .foregroundColor(s.onBackground)
                    .frame(width: 36, height: 36)
            }
            Text(String(format: L("People (%1$d/%2$d)"), visibleCount, vm.totalCount))
                .font(.system(size: CGFloat(TopBarTokens.titleFontSize), weight: .medium))
                .foregroundColor(s.onBackground)
                .lineLimit(1)
            Spacer()
            // 筛选切换
            Button {
                vm.toggleShowAll()
            } label: {
                Image(systemName: vm.showAll ? "line.3.horizontal.decrease.circle" : "line.3.horizontal.decrease")
                    .font(.system(size: 18, weight: .medium))
                    .foregroundColor(s.onBackground)
                    .frame(width: 36, height: 36)
            }
            .accessibilityLabel(Text(vm.showAll ? L("Hide unnamed single-face groups") : L("Show all people")))
            // 重聚类
            Button {
                vm.recluster()
            } label: {
                Image(systemName: "arrow.triangle.2.circlepath")
                    .font(.system(size: 18, weight: .medium))
                    .foregroundColor(s.onBackground)
                    .frame(width: 36, height: 36)
            }
            .accessibilityLabel(Text(L("Re-cluster faces")))
        }
        .padding(.horizontal, 12)
        .frame(height: TopBarTokens.height)
    }

    // MARK: 内容（对齐 Android：加载/空时不渲染占位）

    @ViewBuilder
    private var content: some View {
        if vm.items.isEmpty {
            s.background
        } else {
            ScrollView {
                LazyVGrid(columns: columns, spacing: PersonTokens.gridSpacing) {
                    ForEach(vm.items) { person in
                        PersonCardView(
                            person: person,
                            isEditing: vm.editingPersonId == person.id,
                            onCoverTap: { openDetail(person.id) },
                            onInfoTap: { openDetail(person.id) },
                            onStartEdit: { vm.startEditing(personId: person.id) },
                            onSaveName: { name in vm.updateName(personId: person.id, name: name) },
                            onCancelEdit: { vm.stopEditing() })
                    }
                }
                .padding(.horizontal, CGFloat(PersonTokens.gridSpacing))
                .padding(.vertical, CGFloat(PersonTokens.gridSpacing))
                .padding(.bottom, 120)  // 避让悬浮 Tab
            }
        }
    }

    private func openDetail(_ id: Int64) {
        detailRoute = DetailRoute(id: id)
    }

    // MARK: toast

    private func toastView(_ message: String) -> some View {
        VStack {
            Spacer()
            Text(message)
                .font(.system(size: 13))
                .foregroundColor(s.onBackground)
                .padding(.horizontal, 16)
                .padding(.vertical, 10)
                .background(Capsule().fill(s.surfaceContainerHigh.opacity(0.95)))
                .padding(.bottom, 140)
        }
        .transition(.opacity)
    }
}

/// 详情路由包装（fullScreenCover item 绑定需 Identifiable）。
private struct DetailRoute: Identifiable {
    let id: Int64
}

// MARK: - 人物卡片（对标 Android PersonListItem）

private struct PersonCardView: View {
    // 随 app 主题（PoLangApp 全局 preferredColorScheme 驱动）
    @Environment(\.colorScheme) private var cs
    private var s: SchemeColors { appScheme(cs) }

    let person: PersonDisplayItem
    let isEditing: Bool
    let onCoverTap: () -> Void
    let onInfoTap: () -> Void
    let onStartEdit: () -> Void
    let onSaveName: (String) -> Void
    let onCancelEdit: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            cover
            infoColumn
        }
        .background(RoundedRectangle(cornerRadius: PersonTokens.cardRadius, style: .continuous)
            .fill(s.surfaceContainerHigh))
        .clipShape(RoundedRectangle(cornerRadius: PersonTokens.cardRadius, style: .continuous))
        .shadow(color: .black.opacity(0.08), radius: 2, y: 1)
    }

    private var cover: some View {
        Button(action: onCoverTap) {
            ZStack {
                s.surfaceContainer
                if let lid = person.coverLocalIdentifier {
                    ThumbnailView(localIdentifier: lid, faceFocusY: person.coverFaceFocusY, cornerRadius: 0)
                } else {
                    s.surfaceContainerHigh
                }
            }
        }
        .buttonStyle(.plain)
        .aspectRatio(1, contentMode: .fit)
        .clipShape(UnevenRoundedRectangle(cornerRadii: .init(
            topLeading: PersonTokens.cardRadius, topTrailing: PersonTokens.cardRadius)))
        .accessibilityLabel(Text(person.name ?? String(format: L("Person #%1$d"), Int(person.id))))
    }

    private var infoColumn: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 8) {
                nameBlock
                Spacer(minLength: 0)
                if !isEditing {
                    Text(photoCountText)
                        .font(.system(size: CGFloat(PersonTokens.photoCountFontSize)))
                        .foregroundColor(s.onSurfaceVariant)
                        .padding(.trailing, 4)
                    Button(action: onInfoTap) {
                        Image(systemName: "info.circle")
                            .font(.system(size: 20))
                            .foregroundColor(s.onSurfaceVariant)
                            .frame(width: 32, height: 32)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(Text(L("Person info")))
                }
            }
            if showRelationChip {
                relationChip
            }
        }
        .padding(12)
    }

    @ViewBuilder
    private var nameBlock: some View {
        if isEditing {
            NameEditor(
                initial: person.name ?? "",
                onSave: { onSaveName($0) },
                onCancel: onCancelEdit)
        } else {
            Text(person.name?.isEmpty == false ? person.name! : L("Tap to name"))
                .font(.system(size: CGFloat(PersonTokens.nameFontSize), weight: .semibold))
                .foregroundColor((person.name?.isEmpty == false) ? s.onSurface : s.onSurfaceVariant)
                .lineLimit(1)
                .onTapGesture { onStartEdit() }
        }
    }

    private var photoCountText: String {
        String(format: L("%1$d photos"), person.photoCount)
    }

    private var showRelationChip: Bool {
        person.isSelf || person.relation != nil
    }

    private var relationChipLabel: String {
        if person.isSelf { return L("This is me") }
        if let rel = person.relation {
            return rel.customLabel ?? RelationOptions.label(predicateId: rel.predicate)
        }
        return L("Not set")
    }

    private var relationChip: some View {
        Text(relationChipLabel)
            .font(.system(size: 12, weight: .medium))
            .foregroundColor(.white)
            .padding(.horizontal, 10)
            .padding(.vertical, 4)
            .background(
                Capsule()
                    .fill(Color.accentColor))
            .onTapGesture { onInfoTap() }
    }
}

// MARK: - 行内改名编辑器（对标 Android NameEditor）

private struct NameEditor: View {
    // 随 app 主题（与 PersonCardView 一致）
    @Environment(\.colorScheme) private var cs
    private var s: SchemeColors { appScheme(cs) }

    let initial: String
    let onSave: (String) -> Void
    let onCancel: () -> Void

    @State private var text: String = ""
    @FocusState private var focused: Bool

    var body: some View {
        HStack(spacing: 4) {
            TextField("", text: $text)
                .font(.system(size: CGFloat(PersonTokens.nameFontSize), weight: .semibold))
                .foregroundColor(s.onSurface)
                .focused($focused)
                .submitLabel(.done)
                .onSubmit { onSave(text) }
            Button { onSave(text) } label: {
                Image(systemName: "checkmark")
                    .font(.system(size: 18, weight: .semibold))
                    .foregroundColor(s.primary)
                    .frame(width: 28, height: 28)
            }
            .buttonStyle(.plain)
            Button { onCancel() } label: {
                Image(systemName: "xmark")
                    .font(.system(size: 18, weight: .semibold))
                    .foregroundColor(s.onSurfaceVariant)
                    .frame(width: 28, height: 28)
            }
            .buttonStyle(.plain)
        }
        .onAppear {
            text = initial
            focused = true
        }
    }
}
