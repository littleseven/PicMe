import SwiftUI
import SharedKit

/// 模型中心：管理远程模型配置（添加/选择/删除）。
///
/// 对标 Android `AiAgentRemoteModelsSection` + `AddProviderModelDialog`：
/// - 已配置模型列表（RadioButton 选中 + 删除）
/// - 当前选中高亮卡片
/// - + 添加模型 sheet（供应商 → 模型 → API Key）
/// - 访客模式提示
struct ModelCenterView: View {
    @EnvironmentObject private var store: ModelConfigStore
    @Environment(\.dismiss) private var dismiss
    @Environment(\.colorScheme) private var cs
    private var s: SchemeColors { appScheme(cs) }
    @State private var showAddSheet = false

    var body: some View {
        List {
            // 当前选中卡片
            Section {
                currentModelCard
            } header: {
                Text(L("Current Model"))
            }

            // 已配置模型列表
            if !store.configs.isEmpty {
                Section {
                    ForEach(store.configs, id: \.uniqueKey) { config in
                        modelRow(config)
                    }
                } header: {
                    Text(L("Configured Models"))
                }
            }

            // 访客模式提示
            Section {
                VStack(alignment: .leading, spacing: 6) {
                    HStack(spacing: 6) {
                        Image(matIcon: "info")
                            .font(.system(size: 14))
                            .foregroundColor(.secondary)
                        Text(L("Guest Mode"))
                            .font(.system(size: 14, weight: .medium))
                            .foregroundColor(.secondary)
                    }
                    Text(L("Without a configured model, PoLang uses a free proxy server with limited guest quota. Add your own API key for unlimited access."))
                        .font(.system(size: 12))
                        .foregroundColor(.secondary.opacity(0.8))
                }
            } footer: {
                EmptyView()
            }
        }
        .navigationTitle(L("Remote Models"))
        .navigationBarTitleDisplayMode(.inline)
        .scrollContentBackground(.hidden)
        .background(s.background.ignoresSafeArea())
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                Button {
                    showAddSheet = true
                } label: {
                    Image(matIcon: "add")
                        .font(.system(size: 20))
                }
            }
        }
        .sheet(isPresented: $showAddSheet) {
            AddModelSheet { provider, modelId, apiKey in
                store.add(provider: provider, modelId: modelId, apiKey: apiKey)
            }
        }
    }

    // MARK: - Current Model Card

    private var currentModelCard: some View {
        HStack(spacing: 12) {
            Image(matIcon: "cloud_download")
                .font(.system(size: 24))
                .foregroundColor(s.primary)
            VStack(alignment: .leading, spacing: 2) {
                Text(store.activeConfig().modelId)
                    .font(.system(size: 15, weight: .semibold))
                Text(providerName(for: store.activeConfig()))
                    .font(.system(size: 12))
                    .foregroundColor(.secondary)
            }
            Spacer()
            if store.activeConfig().isConfigured {
                Text(L("API Key"))
                    .font(.system(size: 10))
                    .padding(.horizontal, 6)
                    .padding(.vertical, 3)
                    .background(Color.green.opacity(0.2))
                    .foregroundColor(.green)
                    .clipShape(Capsule())
            } else {
                Text(L("Guest"))
                    .font(.system(size: 10))
                    .padding(.horizontal, 6)
                    .padding(.vertical, 3)
                    .background(Color.orange.opacity(0.2))
                    .foregroundColor(.orange)
                    .clipShape(Capsule())
            }
        }
        .padding(.vertical, 4)
    }

    // MARK: - Model Row

    private func modelRow(_ config: RemoteModelConfig) -> some View {
        HStack(spacing: 12) {
            // RadioButton
            Image(matIcon: store.selectedModelId == config.modelId ? "radio_button_checked" : "radio_button_unchecked")
                .font(.system(size: 20))
                .foregroundColor(store.selectedModelId == config.modelId ? .accentColor : .secondary)

            VStack(alignment: .leading, spacing: 2) {
                Text(config.modelId)
                    .font(.system(size: 14, weight: .medium))
                Text(providerName(for: config))
                    .font(.system(size: 11))
                    .foregroundColor(.secondary)
            }

            Spacer()

            // Delete
            Button {
                store.remove(uniqueKey: config.uniqueKey)
            } label: {
                Image(matIcon: "delete")
                    .font(.system(size: 18))
                    .foregroundColor(.red.opacity(0.7))
            }
        }
        .contentShape(Rectangle())
        .onTapGesture {
            store.select(modelId: config.modelId)
        }
    }

    // MARK: - Helpers

    private func providerName(for config: RemoteModelConfig) -> String {
        let providers = RemoteModelConfig.companion.PROVIDERS as? [RemoteModelProvider] ?? []
        return providers.first { $0.providerId == config.providerId }?.displayName
            ?? (config.baseUrl.isEmpty ? "PoLang Server" : config.baseUrl)
    }
}

// MARK: - Add Model Sheet

struct AddModelSheet: View {
    @Environment(\.dismiss) private var dismiss
    let onConfirm: (RemoteModelProvider, String, String) -> Void

    @State private var selectedProvider: RemoteModelProvider?
    @State private var selectedModelId: String = ""
    @State private var apiKey: String = ""

    private var providers: [RemoteModelProvider] {
        (RemoteModelConfig.companion.PROVIDERS as? [RemoteModelProvider])?
            .filter { $0.isVisible } ?? []
    }

    private var availableModels: [String] {
        guard let provider = selectedProvider else { return [] }
        return provider.models as? [String] ?? []
    }

    private var canConfirm: Bool {
        selectedProvider != nil && !selectedModelId.isEmpty && !apiKey.trimmingCharacters(in: .whitespaces).isEmpty
    }

    var body: some View {
        NavigationStack {
            Form {
                // 供应商选择
                Section(L("Provider")) {
                    ForEach(providers, id: \.providerId) { provider in
                        providerChip(provider)
                    }
                }

                // 模型选择
                if !availableModels.isEmpty {
                    Section(L("Model")) {
                        ForEach(availableModels, id: \.self) { modelId in
                            modelChip(modelId)
                        }
                    }
                }

                // API Key
                Section(L("API Key")) {
                    SecureField(L("Enter API Key"), text: $apiKey)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    if let provider = selectedProvider {
                        Link(L("Get API Key"),
                             destination: URL(string: apiKeyUrl(for: provider.providerId))!)
                            .font(.caption)
                    }
                }
            }
            .navigationTitle(L("Add Model"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    Button(L("Cancel")) { dismiss() }
                }
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button(L("Add")) {
                        if let provider = selectedProvider {
                            onConfirm(provider, selectedModelId, apiKey.trimmingCharacters(in: .whitespaces))
                            dismiss()
                        }
                    }
                    .disabled(!canConfirm)
                    .bold(canConfirm)
                }
            }
        }
    }

    // MARK: - Chips

    private func providerChip(_ provider: RemoteModelProvider) -> some View {
        HStack {
            Image(matIcon: selectedProvider?.providerId == provider.providerId
                ? "radio_button_checked" : "radio_button_unchecked")
                .font(.system(size: 20))
                .foregroundColor(selectedProvider?.providerId == provider.providerId ? .accentColor : .secondary)
            Text(provider.displayName)
                .font(.system(size: 14))
            Spacer()
        }
        .contentShape(Rectangle())
        .onTapGesture {
            selectedProvider = provider
            selectedModelId = ""
        }
    }

    private func modelChip(_ modelId: String) -> some View {
        HStack {
            Image(matIcon: selectedModelId == modelId
                ? "radio_button_checked" : "radio_button_unchecked")
                .font(.system(size: 20))
                .foregroundColor(selectedModelId == modelId ? .accentColor : .secondary)
            Text(modelId)
                .font(.system(size: 14))
            Spacer()
        }
        .contentShape(Rectangle())
        .onTapGesture {
            selectedModelId = modelId
        }
    }

    private func apiKeyUrl(for providerId: String) -> String {
        switch providerId {
        case "tencent-tokenhub": return "https://console.cloud.tencent.com/"
        case "kimi-official": return "https://platform.moonshot.cn/"
        case "deepseek-official": return "https://platform.deepseek.com/"
        default: return "https://api.polang.net/"
        }
    }
}

#Preview {
    NavigationStack {
        ModelCenterView()
            .environmentObject(ModelConfigStore.shared)
    }
}
