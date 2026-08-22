import SwiftUI
import SharedKit

/// 模型中心：管理远程模型配置（添加/选择/删除）。
///
/// 对标 Android `AiAgentRemoteModelsSection` + `AddProviderModelDialog`：
/// - 已配置模型列表（RadioButton 选中 + 删除）
/// - 当前选中高亮卡片
/// - + 添加模型：导航 AddRemoteProviderView 两页流（2026-08-21 弹窗下线；AddModelSheet 保留无入口）
/// - 访客模式提示
struct ModelCenterView: View {
    @EnvironmentObject private var store: ModelConfigStore
    @Environment(\.dismiss) private var dismiss
    @Environment(\.colorScheme) private var cs
    private var s: SchemeColors { appScheme(cs) }
    @State private var editTarget: EditModelTarget?

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
                // 添加模型：导航两页流（原 AddModelSheet 弹窗入口下线，spec §3c/§3d）
                NavigationLink {
                    AddRemoteProviderView()
                } label: {
                    Image(matIcon: "add")
                        .font(.system(size: 20))
                }
            }
        }
        .sheet(item: $editTarget) { target in
            EditApiKeySheet(modelName: target.config.modelId, initialApiKey: target.config.apiKey) { newKey in
                store.updateApiKey(uniqueKey: target.config.uniqueKey, apiKey: newKey)
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

            // Edit (改 API Key)
            Button {
                editTarget = EditModelTarget(config: config)
            } label: {
                Image(matIcon: "tune")
                    .font(.system(size: 18))
                    .foregroundColor(s.primary)
            }

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
    @State private var isCustomModel: Bool = false
    @State private var customModelId: String = ""
    @State private var apiKey: String = ""

    private var providers: [RemoteModelProvider] {
        (RemoteModelConfig.companion.PROVIDERS as? [RemoteModelProvider])?
            .filter { $0.isVisible } ?? []
    }

    private var availableModels: [String] {
        guard let provider = selectedProvider else { return [] }
        return provider.models as? [String] ?? []
    }

    /// 生效模型 ID：预置选项或自定义输入（对齐 Android AddProviderModelDialog 的自定义 chip）
    private var effectiveModelId: String {
        isCustomModel ? customModelId.trimmingCharacters(in: .whitespaces) : selectedModelId
    }

    private var canConfirm: Bool {
        selectedProvider != nil && !effectiveModelId.isEmpty && !apiKey.trimmingCharacters(in: .whitespaces).isEmpty
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

                // 模型选择（预置 + 自定义模型 ID）
                if selectedProvider != nil {
                    Section(L("Model")) {
                        ForEach(availableModels, id: \.self) { modelId in
                            modelChip(modelId)
                        }
                        customModelChip
                        if isCustomModel {
                            TextField(L("Model ID"), text: $customModelId)
                                .textInputAutocapitalization(.never)
                                .autocorrectionDisabled()
                        }
                    }
                }

                // API Key
                Section(L("API Key")) {
                    SecureField(L("Enter API Key"), text: $apiKey)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    if let provider = selectedProvider,
                       !provider.apiKeyUrl.isEmpty,
                       let url = URL(string: provider.apiKeyUrl) {
                        Link(L("Get API Key"), destination: url)
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
                            onConfirm(provider, effectiveModelId, apiKey.trimmingCharacters(in: .whitespaces))
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
            isCustomModel = false
            customModelId = ""
        }
    }

    private func modelChip(_ modelId: String) -> some View {
        HStack {
            Image(matIcon: !isCustomModel && selectedModelId == modelId
                ? "radio_button_checked" : "radio_button_unchecked")
                .font(.system(size: 20))
                .foregroundColor(!isCustomModel && selectedModelId == modelId ? .accentColor : .secondary)
            Text(modelId)
                .font(.system(size: 14))
            Spacer()
        }
        .contentShape(Rectangle())
        .onTapGesture {
            selectedModelId = modelId
            isCustomModel = false
        }
    }

    private var customModelChip: some View {
        HStack {
            Image(matIcon: isCustomModel
                ? "radio_button_checked" : "radio_button_unchecked")
                .font(.system(size: 20))
                .foregroundColor(isCustomModel ? .accentColor : .secondary)
            Text(L("Add Custom Model"))
                .font(.system(size: 14))
            Spacer()
        }
        .contentShape(Rectangle())
        .onTapGesture {
            isCustomModel = true
            selectedModelId = ""
        }
    }
}

// MARK: - Edit API Key（对齐 Android RemoteModelConfigCard 编辑：预定义模型改 apiKey）

private struct EditModelTarget: Identifiable {
    let config: RemoteModelConfig
    var id: String { config.uniqueKey }
}

struct EditApiKeySheet: View {
    @Environment(\.dismiss) private var dismiss
    let modelName: String
    let initialApiKey: String
    let onSave: (String) -> Void

    @State private var apiKey: String = ""

    var body: some View {
        NavigationStack {
            Form {
                Section(L("API Key")) {
                    SecureField(L("Enter API Key"), text: $apiKey)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                }
            }
            .navigationTitle(L("Edit Model"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    Button(L("Cancel")) { dismiss() }
                }
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button(L("Save")) {
                        let trimmed = apiKey.trimmingCharacters(in: .whitespaces)
                        if !trimmed.isEmpty { onSave(trimmed); dismiss() }
                    }
                    .disabled(apiKey.trimmingCharacters(in: .whitespaces).isEmpty)
                }
            }
        }
        .onAppear { apiKey = initialApiKey }
    }
}

#Preview {
    NavigationStack {
        ModelCenterView()
            .environmentObject(ModelConfigStore.shared)
    }
}
