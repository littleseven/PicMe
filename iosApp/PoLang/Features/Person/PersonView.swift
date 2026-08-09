import SwiftUI

/// 人物页（列表）——对标 Android `PersonScreen`。
///
/// 显示全部人物（本人优先），2 列网格；点击进入 `PersonInfoView` 详情。
/// `onBack` 由 MainTabView（page 3）或 Settings（NavigationStack push）注入：
///  - 非 nil：page 3 用法，自定义顶栏返回按钮回调。
///  - nil：嵌入 NavigationStack，用系统返回；本视图顶栏返回仍可用（dismiss）。
struct PersonView: View {

    var onBack: (() -> Void)? = nil

    @Environment(\.dismiss) private var dismiss
    @StateObject private var vm = PersonViewModel()
    @State private var showAdd = false
    @State private var detailRoute: DetailRoute?

    private let columns = [GridItem(.flexible(), spacing: 12), GridItem(.flexible(), spacing: 12)]

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            VStack(spacing: 0) {
                topBar
                content
            }
        }
        .navigationBarHidden(true)
        .task { vm.load() }
        .sheet(isPresented: $showAdd) {
            AddPersonSheet { name, isSelf in
                vm.createPerson(name: name, coverMediaId: nil, isSelf: isSelf)
            }
        }
        .fullScreenCover(item: $detailRoute) { route in
            PersonInfoView(personId: route.id) {
                detailRoute = nil
                vm.load()  // 详情可能改了封面/名字，返回时刷新列表
            }
        }
    }

    // MARK: 顶栏

    private var topBar: some View {
        HStack(spacing: 12) {
            Button {
                if let onBack { onBack() } else { dismiss() }
            } label: {
                Image(systemName: "chevron.left")
                    .font(.system(size: 18, weight: .semibold))
                    .foregroundColor(.white)
                    .frame(width: 36, height: 36)
            }
            Text(L("People"))
                .font(.system(size: 18, weight: .semibold))
                .foregroundColor(.white)
            Spacer()
            Button {
                showAdd = true
            } label: {
                Image(systemName: "person.badge.plus")
                    .font(.system(size: 18, weight: .medium))
                    .foregroundColor(.white)
                    .frame(width: 36, height: 36)
            }
        }
        .padding(.horizontal, 12)
        .padding(.top, 8)
        .padding(.bottom, 8)
    }

    @ViewBuilder
    private var content: some View {
        if vm.persons.isEmpty {
            emptyState
        } else {
            ScrollView {
                LazyVGrid(columns: columns, spacing: 12) {
                    ForEach(vm.persons) { person in
                        PersonCardView(person: person) {
                            detailRoute = DetailRoute(id: person.id)
                        }
                    }
                }
                .padding(.horizontal, 16)
                .padding(.top, 8)
                .padding(.bottom, 120)  // 避让悬浮 Tab
            }
        }
    }

    /// 诚实空状态：未建人物时引导手动添加（Phase 6.3 无聚类，不造假数据）。
    private var emptyState: some View {
        VStack(spacing: 14) {
            Image(systemName: "person.2.crop.square.stack")
                .font(.system(size: 44))
                .foregroundColor(.white.opacity(0.35))
            Text(L("No people yet"))
                .font(.system(size: 16, weight: .semibold))
                .foregroundColor(.white.opacity(0.85))
            Text(L("Add people you care about to organize and remember them."))
                .font(.system(size: 13))
                .foregroundColor(.white.opacity(0.5))
                .multilineTextAlignment(.center)
                .padding(.horizontal, 48)
            Button {
                showAdd = true
            } label: {
                HStack(spacing: 6) {
                    Image(systemName: "plus")
                    Text(L("Add Person"))
                }
                .font(.system(size: 14, weight: .semibold))
                .foregroundColor(.black)
                .padding(.horizontal, 20)
                .padding(.vertical, 10)
                .background(Capsule().fill(Color.white))
            }
            .padding(.top, 6)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

/// 详情路由包装（fullScreenCover item 绑定需 Identifiable）。
private struct DetailRoute: Identifiable {
    let id: Int64
}

// MARK: - 人物卡片

/// 2 列网格单元：方形封面（有封面用 ThumbnailView，无则首字母占位）+ 名称 + 副标题。
struct PersonCardView: View {

    let person: PersonStore.PersonRow
    let onTap: () -> Void

    var body: some View {
        Button(action: onTap) {
            VStack(alignment: .leading, spacing: 8) {
                cover
                Text(person.name)
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundColor(.white)
                    .lineLimit(1)
                Text(subtitle)
                    .font(.system(size: 12))
                    .foregroundColor(.white.opacity(0.55))
                    .lineLimit(1)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .buttonStyle(.plain)
    }

    @ViewBuilder
    private var cover: some View {
        ZStack(alignment: .topTrailing) {
            if let coverId = person.coverMediaId {
                ThumbnailView(localIdentifier: coverId)
            } else {
                // 无封面：首字母占位（深灰底 + 首字符）
                ZStack {
                    Color(white: 0.16)
                    Text(String(person.name.prefix(1)).uppercased())
                        .font(.system(size: 30, weight: .semibold))
                        .foregroundColor(.white.opacity(0.6))
                }
                .aspectRatio(1, contentMode: .fit)
                .clipShape(RoundedRectangle(cornerRadius: 8))
            }
            if person.isSelf {
                Text(L("Me"))
                    .font(.system(size: 10, weight: .bold))
                    .foregroundColor(.black)
                    .padding(.horizontal, 6)
                    .padding(.vertical, 2)
                    .background(Capsule().fill(Color.white.opacity(0.9)))
                    .padding(6)
            }
        }
        .frame(maxWidth: .infinity)
        .aspectRatio(1, contentMode: .fit)
    }

    private var subtitle: String {
        if person.isSelf { return L("You") }
        return "\(person.photoCount) \(L("photos"))"
    }
}

// MARK: - 新建人物表单

/// 新建人物：名称 + "这是我" 开关（开启时自动取消其他人物的本人标记）。
struct AddPersonSheet: View {

    @Environment(\.dismiss) private var dismiss
    @State private var name: String = ""
    @State private var isSelf: Bool = false

    let onCreate: (String, Bool) -> Void

    var body: some View {
        NavigationView {
            ZStack {
                Color(.systemBackground).ignoresSafeArea()
                VStack(alignment: .leading, spacing: 20) {
                    HStack {
                        Text(L("Name"))
                            .font(.system(size: 14, weight: .medium))
                            .foregroundColor(.secondary)
                        Spacer()
                    }
                    TextField(L("Enter a name"), text: $name)
                        .textFieldStyle(.roundedBorder)
                        .submitLabel(.done)

                    Toggle(isOn: $isSelf) {
                        Text(L("This is me"))
                            .font(.system(size: 15))
                    }
                    Spacer()
                }
                .padding(20)
            }
            .navigationTitle(L("Add Person"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L("Cancel")) { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(L("Create")) {
                        onCreate(name, isSelf)
                        dismiss()
                    }
                    .disabled(name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
            }
        }
    }
}
