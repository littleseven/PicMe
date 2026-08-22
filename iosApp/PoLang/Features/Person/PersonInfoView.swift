import SwiftUI

/// 人物详情/编辑页——对标 Android `PersonInfoScreen` + `PersonRelationPicker` + `PersonCoverPickerSheet`。
///
/// 区块：封面（55%×180，可点改）→ 名称（headline 行内编辑）→ 这是我 + 关系 chip 组 → 保存。
/// 标题为 `Cluster #N`（簇 ID，非名字）。封面/关系/本人经保存按钮持久化；封面可即时换。
/// `onBack` 关闭 fullScreenCover 并通知列表刷新。
struct PersonInfoView: View {

    let personId: Int64
    var onBack: () -> Void = {}
    @Environment(\.dismiss) private var dismiss
    @StateObject private var vm: PersonDetailViewModel

    // 本地编辑态（对标 Android PersonInfoScreen local state）
    @State private var nameText: String = ""
    @State private var currentRelation: String?
    @State private var customLabel: String = ""
    @State private var currentIsSelf: Bool = false
    @State private var isEditingName = false
    @State private var showCoverPicker = false
    @State private var didSync = false

    init(personId: Int64, onBack: @escaping () -> Void = {}) {
        self.personId = personId
        self.onBack = onBack
        _vm = StateObject(wrappedValue: PersonDetailViewModel(personId: personId))
    }

    private var customActive: Bool { !customLabel.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }

    // 随 app 主题（PoLangApp 全局 preferredColorScheme 驱动）
    @Environment(\.colorScheme) private var cs
    private var s: SchemeColors { appScheme(cs) }

    private var family: [RelationOptionItem] { RelationOptions.all().filter { $0.isFamily } }
    private var social: [RelationOptionItem] { RelationOptions.all().filter { !$0.isFamily } }

    var body: some View {
        ZStack {
            s.background.ignoresSafeArea()
            VStack(spacing: 0) {
                topBar
                ScrollView {
                    VStack(spacing: 0) {
                        coverHeader
                            .padding(.vertical, 12)
                        nameSection
                            .padding(.vertical, 12)
                        relationSection
                            .padding(.vertical, 12)
                    }
                    .padding(.horizontal, 16)
                    .padding(.bottom, 120)
                }
            }
        }
        .navigationBarHidden(true)
        .task { vm.load() }
        .onChange(of: vm.isLoading) { loading in
            if !loading, !didSync { syncLocalFromVm() }
        }
        .sheet(isPresented: $showCoverPicker) {
            coverPickerSheet
        }
    }

    private func syncLocalFromVm() {
        didSync = true
        nameText = vm.person?.name ?? ""
        currentIsSelf = vm.person?.isSelf ?? false
        currentRelation = vm.relation?.predicate
        customLabel = vm.relation?.customLabel ?? ""
    }

    private func close() {
        dismiss()
        onBack()
    }

    // MARK: 保存（对标 Android doSave）

    private func doSave() {
        let trimmed = nameText.trimmingCharacters(in: .whitespacesAndNewlines)
        vm.saveName(trimmed.isEmpty ? nil : trimmed)
        vm.saveRelation(predicate: currentRelation, customLabel: customLabel)
        vm.saveSelf(currentIsSelf)
        close()
    }

    private func resetRelation() {
        currentRelation = nil
        customLabel = ""
    }

    // MARK: 顶栏

    private var topBar: some View {
        HStack(spacing: 8) {
            Button { close() } label: {
                MatIcon(name: "mat_o_arrow_back", size: 18)
                    .foregroundColor(s.onBackground)
                    .frame(width: 36, height: 36)
            }
            Text(String(format: L("Cluster #%1$d"), Int(personId)))
                .font(.system(size: CGFloat(TopBarTokens.titleFontSize), weight: .medium))
                .foregroundColor(s.onBackground)
            Spacer()
            Button { resetRelation() } label: {
                MatIcon(name: "mat_o_undo", size: 18)
                    .foregroundColor(s.onBackground)
                    .frame(width: 36, height: 36)
            }
            .accessibilityLabel(Text(L("Reset")))
            Button { doSave() } label: {
                MatIcon(name: "mat_o_check", size: 18)
                    .foregroundColor(s.onBackground)
                    .frame(width: 36, height: 36)
            }
            .accessibilityLabel(Text(L("Save")))
        }
        .padding(.horizontal, 12)
        .padding(.top, 8)
        .padding(.bottom, 8)
    }

    // MARK: 封面（55% 宽 × 180 高，人脸感知，可点改）

    private var coverHeader: some View {
        GeometryReader { geo in
            let w = geo.size.width * PersonTokens.detailCoverWidthRatio
            Button { showCoverPicker = true } label: {
                ZStack {
                    if let lid = coverLocalId {
                        ThumbnailView(localIdentifier: lid, faceFocusY: coverFocusY, cornerRadius: PersonTokens.cardRadius)
                    } else {
                        coverPlaceholder
                    }
                }
                .frame(width: w, height: PersonTokens.detailCoverHeight)
                .clipShape(RoundedRectangle(cornerRadius: PersonTokens.cardRadius, style: .continuous))
            }
            .buttonStyle(.plain)
            .frame(width: geo.size.width, alignment: .center)
        }
        .frame(height: PersonTokens.detailCoverHeight)
    }

    /// 当前封面（cover_media_id）的 localIdentifier / faceFocusY，从簇候选解析。
    private var coverLocalId: String? {
        guard let cid = vm.person?.coverMediaId else { return nil }
        return vm.coverCandidates.first { $0.mediaId == cid }?.localIdentifier
    }
    private var coverFocusY: Float? {
        guard let cid = vm.person?.coverMediaId else { return nil }
        return vm.coverCandidates.first { $0.mediaId == cid }?.faceFocusY
    }

    private var coverPlaceholder: some View {
        ZStack {
            s.surfaceContainer
            VStack(spacing: 8) {
                Image(systemName: "person.crop.square")
                    .font(.system(size: 40))
                    .foregroundColor(s.onSurfaceVariant.opacity(0.45))
                Text(L("Tap to set cover"))
                    .font(.system(size: 14, weight: .medium))
                    .foregroundColor(s.onBackground.opacity(0.6))
            }
        }
    }

    // MARK: 名称（headline 行内编辑）

    private var nameSection: some View {
        Group {
            if isEditingName {
                TextField("", text: $nameText)
                    .font(.system(size: CGFloat(AppTypography.headlineSmall.size), weight: .bold))
                    .foregroundColor(s.onSurface)
                    .multilineTextAlignment(.center)
                    .submitLabel(.done)
                    .onSubmit { isEditingName = false }
                    .padding(.vertical, 8)
            } else {
                Text(nameText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? L("Add name") : nameText)
                    .font(.system(size: CGFloat(AppTypography.headlineSmall.size), weight: .bold))
                    .foregroundColor(nameText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? s.onSurfaceVariant : s.onSurface)
                    .lineLimit(1)
                    .onTapGesture { isEditingName = true }
            }
        }
        .frame(maxWidth: .infinity)
    }

    // MARK: 关系（这是我 + 家庭/社会 chip 组 + 自定义）

    private var relationSection: some View {
        VStack(spacing: 12) {
            // 这是我
            Button {
                currentIsSelf.toggle()
            } label: {
                HStack(spacing: 6) {
                    if currentIsSelf {
                        Image(systemName: "checkmark")
                            .font(.system(size: 13, weight: .bold))
                    }
                    Text(L("This is me"))
                        .font(.system(size: 14, weight: .medium))
                }
                .foregroundColor(currentIsSelf ? s.onPrimaryContainer : s.onSurfaceVariant)
                .padding(.horizontal, 14)
                .frame(height: ChipTokens.height)
                .background(
                    RoundedRectangle(cornerRadius: PersonTokens.relationChipRadius, style: .continuous)
                        .fill(currentIsSelf ? s.primaryContainer : s.surfaceVariant.opacity(0.5)))
            }
            .buttonStyle(.plain)

            chipGroup(title: L("Family"), options: family)
            chipGroup(title: L("Social"), options: social)

            // 自定义称呼
            VStack(alignment: .leading, spacing: 4) {
                Text(L("Custom label"))
                    .font(.system(size: 14, weight: .medium))
                    .foregroundColor(s.onSurfaceVariant)
                TextField(L("e.g. childhood buddy, second son"), text: $customLabel)
                    .font(.system(size: 14))
                    .foregroundColor(s.onSurface)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 10)
                    .background(RoundedRectangle(cornerRadius: AppRadius.button, style: .continuous)
                        .stroke(s.outline))
                Text(L("Custom text takes priority when filled"))
                    .font(.system(size: 11))
                    .foregroundColor(s.onSurfaceVariant)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func chipGroup(title: String, options: [RelationOptionItem]) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title)
                .font(.system(size: 12, weight: .medium))
                .foregroundColor(s.onSurfaceVariant)
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 72), spacing: 6)], alignment: .leading, spacing: 6) {
                ForEach(options) { opt in
                    let selected = !customActive && currentRelation == opt.id
                    Button {
                        currentRelation = opt.id
                        customLabel = ""
                    } label: {
                        Text(opt.label)
                            .font(.system(size: 13, weight: .medium))
                            .foregroundColor(selected ? s.onPrimaryContainer : s.onSurfaceVariant)
                            .padding(.horizontal, 12)
                            .frame(maxWidth: .infinity, minHeight: ChipTokens.height)
                            .background(
                                RoundedRectangle(cornerRadius: PersonTokens.relationChipRadius, style: .continuous)
                                    .fill(selected ? s.primaryContainer : s.surfaceVariant.opacity(0.5)))
                    }
                    .buttonStyle(.plain)
                }
            }
        }
    }

    // MARK: 封面选择器（3 列，簇内候选，按时间倒序）

    private var coverPickerSheet: some View {
        VStack(spacing: 0) {
            HStack {
                Text(L("Select cover"))
                    .font(AppTypography.titleLarge.font)
                    .foregroundColor(.primary)
                Spacer()
                Button(L("Cancel")) { showCoverPicker = false }
                    .font(.system(size: 17))
                    .foregroundColor(.primary)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 12)

            ScrollView {
                LazyVGrid(columns: [
                    GridItem(.flexible(), spacing: 4),
                    GridItem(.flexible(), spacing: 4),
                    GridItem(.flexible(), spacing: 4),
                ], spacing: 4) {
                    ForEach(vm.coverCandidates, id: \.mediaId) { cand in
                        Button {
                            vm.saveCover(cand.mediaId)
                            showCoverPicker = false
                        } label: {
                            ThumbnailView(localIdentifier: cand.localIdentifier, faceFocusY: cand.faceFocusY, cornerRadius: AppRadius.small)
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(.horizontal, 16)
                .padding(.bottom, 24)
            }
        }
        .background(Color(.systemBackground).ignoresSafeArea())
    }
}
