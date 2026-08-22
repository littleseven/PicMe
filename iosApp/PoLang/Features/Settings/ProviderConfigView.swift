import SwiftUI
import SharedKit

/// 供应商配置页（spec settings.yaml §3d provider_config/{providerId}）。
///
/// - `provider != nil`：官方供应商形态 —— 摘要卡（徽章 + 官方服务协议）、API Key、
///   预置模型单选 + 自定义模型 ID 展开行（展开时覆盖预置单选）
/// - `provider == nil`：自定义供应商形态 —— 标题「自定义供应商」、额外 Base URL 输入、
///   无预置列表（仅自定义模型 ID 输入）
///
/// 保存复用 AddModelSheet 同路径：`store.add`（uniqueKey=providerId:modelId upsert，
/// 内部 toJson 回写 UserDefaults）→ `onSaved()` 由来源页 dismiss 弹回（确定性两级 pop）。
struct ProviderConfigView: View {
    @EnvironmentObject private var store: ModelConfigStore
    @Environment(\.colorScheme) private var cs
    private var s: SchemeColors { appScheme(cs) }

    /// 官方供应商；nil = 自定义供应商形态（provider_config/custom）
    let provider: RemoteModelProvider?
    /// 保存成功回调（来源页用来弹回上一级）
    var onSaved: () -> Void = {}

    @State private var apiKey: String = ""
    @State private var baseUrl: String = ""
    @State private var selectedModelId: String = ""
    /// 自定义形态初始展开（唯一输入框，少一次点击；契约=Android customModelExpanded 初始 true）
    @State private var customModelExpanded: Bool
    @State private var customModelId: String = ""

    private var isCustom: Bool { provider == nil }

    init(provider: RemoteModelProvider?, onSaved: @escaping () -> Void = {}) {
        self.provider = provider
        self.onSaved = onSaved
        _customModelExpanded = State(initialValue: provider == nil)
    }

    /// 生效模型 ID：自定义输入展开时覆盖预置单选（语义同原 AddProviderModelDialog）
    private var effectiveModelId: String {
        customModelExpanded
            ? customModelId.trimmingCharacters(in: .whitespaces)
            : selectedModelId
    }

    /// API Key 非空 且 已选模型（或已填自定义模型 ID）；自定义形态另需 Base URL 非空
    private var canSubmit: Bool {
        let keyOk = !apiKey.trimmingCharacters(in: .whitespaces).isEmpty
        let modelOk = !effectiveModelId.isEmpty
        let urlOk = !isCustom || !baseUrl.trimmingCharacters(in: .whitespaces).isEmpty
        return keyOk && modelOk && urlOk
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                summaryCard
                apiKeySection
                modelSection
                submitButton
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
        }
        .background(s.background.ignoresSafeArea())
        .navigationTitle(provider?.displayName ?? L("Custom Provider"))
        .navigationBarTitleDisplayMode(.inline)
    }

    // MARK: - Summary Card

    private var summaryCard: some View {
        HStack(spacing: 12) {
            if let provider {
                ProviderBrandBadge(providerId: provider.providerId, displayName: provider.displayName)
            } else {
                ZStack {
                    RoundedRectangle(cornerRadius: 8)
                        .fill(AppColors.vibrantGreen)
                        .frame(width: 28, height: 28)
                    Image(matIcon: "add")
                        .font(.system(size: 18, weight: .semibold))
                        .foregroundColor(.white)
                }
            }
            VStack(alignment: .leading, spacing: 2) {
                Text(provider?.displayName ?? L("Custom Provider"))
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundColor(s.onSurface)
                Text(summarySubtitle)
                    .font(.system(size: 12))
                    .foregroundColor(s.onSurfaceVariant)
            }
            Spacer()
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(s.surfaceContainerHighest)
        .clipShape(AppShapes.card)
    }

    /// 官方服务 · OpenAI/Anthropic 协议（按 protocol 字段）；自定义形态为「自定义供应商 · OpenAI 兼容协议」
    private var summarySubtitle: String {
        if isCustom {
            return L("Custom Provider · OpenAI-Compatible Protocol")
        }
        if provider?.protocol == RemoteProtocol.claude {
            return L("Official Service · Anthropic Protocol")
        }
        return L("Official Service · OpenAI Protocol")
    }

    // MARK: - API Key Section

    private var apiKeySection: some View {
        SettingsM3Section(title: L("API Key")) {
            VStack(alignment: .leading, spacing: 10) {
                if isCustom {
                    fieldLabel(L("Base URL"))
                    TextField(L("https://api.example.com/v1/"), text: $baseUrl)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .keyboardType(.URL)
                        .padding(12)
                        .background(s.surfaceVariant.opacity(0.6))
                        .clipShape(RoundedRectangle(cornerRadius: 10))
                }
                fieldLabel(L("API Key"))
                SecureField(L("Enter API Key"), text: $apiKey)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .padding(12)
                    .background(s.surfaceVariant.opacity(0.6))
                    .clipShape(RoundedRectangle(cornerRadius: 10))
                Text(L("API Key is stored only on this device and never uploaded to our servers."))
                    .font(.system(size: 11))
                    .foregroundColor(s.onSurfaceVariant)
                consoleLink
            }
        }
    }

    /// 前往控制台获取：仅 provider.apiKeyUrl 非空时显示（shared SSOT 字段），系统浏览器打开
    @ViewBuilder
    private var consoleLink: some View {
        if let provider, !provider.apiKeyUrl.isEmpty, let url = URL(string: provider.apiKeyUrl) {
            Link(destination: url) {
                Text(String(format: L("Go to %@ console →"), provider.displayName))
                    .font(.system(size: 13, weight: .medium))
                    .foregroundColor(AppColors.vibrantBlue)
            }
        }
    }

    private func fieldLabel(_ text: String) -> some View {
        Text(text)
            .font(.system(size: 12))
            .foregroundColor(s.onSurfaceVariant)
    }

    // MARK: - Model Section

    private var modelSection: some View {
        SettingsM3Section(title: L("Select Model")) {
            VStack(spacing: 0) {
                if !isCustom, let provider {
                    let models = provider.models as? [String] ?? []
                    ForEach(models, id: \.self) { modelId in
                        presetRow(modelId)
                        insetDivider
                    }
                }
                customModelRow
                if customModelExpanded {
                    customModelInput
                }
            }
        }
    }

    /// 预置模型单选行：标题 $modelId + 官方预置模型描述；选中项右侧 vibrantBlue ✓
    private func presetRow(_ modelId: String) -> some View {
        Button {
            selectedModelId = modelId
            customModelExpanded = false
        } label: {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text(modelId)
                        .font(.system(size: 14, weight: .medium))
                        .foregroundColor(s.onSurface)
                    Text(L("Official preset model"))
                        .font(.system(size: 12))
                        .foregroundColor(s.onSurfaceVariant)
                }
                Spacer()
                if !customModelExpanded && selectedModelId == modelId {
                    Image(matIcon: "check")
                        .font(.system(size: 20))
                        .foregroundColor(AppColors.vibrantBlue)
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 10)
            .frame(minHeight: SettingsTokens.rowHeightNoSubtitle)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    /// 自定义模型 ID 行：chevron 展开内联输入框；展开时覆盖预置单选
    private var customModelRow: some View {
        Button {
            withAnimation(.easeInOut(duration: 0.15)) {
                customModelExpanded.toggle()
            }
        } label: {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text(L("Custom Model ID"))
                        .font(.system(size: 14, weight: .medium))
                        .foregroundColor(s.onSurface)
                    Text(L("Enter any model name, e.g. gpt-5-pro"))
                        .font(.system(size: 12))
                        .foregroundColor(s.onSurfaceVariant)
                }
                Spacer()
                Image(matIcon: "arrow_forward")
                    .font(.system(size: SettingsTokens.rowChevronSize))
                    .foregroundColor(s.onSurfaceVariant.opacity(SettingsTokens.rowChevronAlpha))
                    .rotationEffect(.degrees(customModelExpanded ? -90 : 90))
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 10)
            .frame(minHeight: SettingsTokens.rowHeightNoSubtitle)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    private var customModelInput: some View {
        VStack(alignment: .leading, spacing: 6) {
            TextField(L("Model ID"), text: $customModelId)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .padding(12)
                .background(s.surfaceVariant.opacity(0.6))
                .clipShape(RoundedRectangle(cornerRadius: 10))
            Text(L("Overrides the preset selection"))
                .font(.system(size: 11))
                .foregroundColor(s.onSurfaceVariant)
        }
        .padding(.horizontal, 16)
        .padding(.bottom, 10)
    }

    private var insetDivider: some View {
        Rectangle()
            .fill(s.outlineVariant.opacity(SettingsTokens.rowChevronAlpha))
            .frame(height: 0.5)
            .padding(.leading, 56)
    }

    // MARK: - Submit

    /// 全宽 48 高、圆角 12、vibrantBlue 底白字；禁用态 35% 透明度
    private var submitButton: some View {
        Button(action: save) {
            Text(L("Add Model"))
                .font(.system(size: 15, weight: .semibold))
                .foregroundColor(.white)
                .frame(maxWidth: .infinity, minHeight: 48)
                .background(AppColors.vibrantBlue.opacity(canSubmit ? 1.0 : 0.35))
                .clipShape(RoundedRectangle(cornerRadius: 12))
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(!canSubmit)
        .padding(.top, 4)
    }

    private func save() {
        guard canSubmit else { return }
        let trimmedKey = apiKey.trimmingCharacters(in: .whitespaces)
        if let provider {
            store.add(provider: provider, modelId: effectiveModelId, apiKey: trimmedKey)
        } else {
            // 自定义供应商：合成 provider 走同一 add 路径（uniqueKey=custom:modelId upsert）
            let customProvider = RemoteModelProvider(
                providerId: "custom",
                displayName: L("Custom Provider"),
                baseUrl: baseUrl.trimmingCharacters(in: .whitespaces),
                protocol: RemoteProtocol.openai,
                models: [],
                isVisible: false,
                apiKeyUrl: ""
            )
            store.add(provider: customProvider, modelId: effectiveModelId, apiKey: trimmedKey)
        }
        onSaved()
    }
}

#Preview {
    NavigationStack {
        ProviderConfigView(provider: nil)
            .environmentObject(ModelConfigStore.shared)
    }
}
