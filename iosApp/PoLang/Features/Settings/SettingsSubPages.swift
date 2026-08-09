import SwiftUI
import SharedKit

// MARK: - AI Agent Settings

struct AiAgentSettingsView: View {
    @Environment(\.dismiss) private var dismiss
    @AppStorage("auto_execute_plans") private var autoExecutePlans = true

    var body: some View {
        ScrollView {
            VStack(spacing: 12) {
                // Section: AI 智能助手
                settingsSection(String(localized: "AI Assistant"), String(localized: "Use remote model for natural language camera control.")) {
                    VStack(spacing: 0) {
                        // 自动执行计划
                        HStack {
                            VStack(alignment: .leading, spacing: 3) {
                                Text(String(localized: "Auto-Execute Multi-Step Plans"))
                                    .font(.system(size: 14))
                                Text(String(localized: "When disabled, Agent requires confirmation before executing multi-step plans."))
                                    .font(.system(size: 12))
                                    .foregroundColor(.secondary)
                            }
                            Spacer()
                            Toggle("", isOn: $autoExecutePlans).labelsHidden()
                        }
                        .padding(.vertical, 8)

                        Divider()

                        // 推理模式
                        HStack {
                            Text(String(localized: "Inference Mode")).font(.system(size: 14))
                            Spacer()
                            chip(String(localized: "Remote Model"), isSelected: true)
                        }
                        .padding(.vertical, 8)
                    }
                }

                // Section: 远程推理
                settingsSection(String(localized: "Remote Inference")) {
                    AiAgentRemoteModelsSection()
                        .environmentObject(ModelConfigStore.shared)
                }

                // Section: 语音控制
                settingsSection(String(localized: "Voice Control"), String(localized: "Control camera shooting via voice commands.")) {
                    VStack(spacing: 8) {
                        HStack {
                            Text(String(localized: "Voice Mode")).font(.system(size: 14))
                            Spacer()
                            Text(String(localized: "Not Available"))
                                .font(.system(size: 12))
                                .foregroundColor(.secondary)
                        }
                        .padding(.vertical, 4)
                    }
                }
            }
            .padding(.horizontal, 10)
            .padding(.vertical, 6)
        }
        .background(Color(.systemGroupedBackground).ignoresSafeArea())
        .navigationTitle(String(localized: "AI Assistant"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .navigationBarLeading) {
                Button { dismiss() } label: { MatIcon(name: "chevron.left", size: 20) }
            }
        }
    }

    private func chip(_ label: String, isSelected: Bool) -> some View {
        Text(label)
            .font(.system(size: 13, weight: isSelected ? .semibold : .regular))
            .foregroundColor(isSelected ? .white : .primary)
            .padding(.horizontal, 14)
            .padding(.vertical, 6)
            .background(isSelected ? Color.accentColor : Color(.tertiarySystemBackground))
            .clipShape(Capsule())
    }
}

// MARK: - Remote Models Section (inline in AI settings)

private struct AiAgentRemoteModelsSection: View {
    @EnvironmentObject private var store: ModelConfigStore
    @State private var showAddSheet = false

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            // 当前模型
            HStack {
                Image(matIcon: "check").font(.system(size: 18)).foregroundColor(.accentColor)
                VStack(alignment: .leading, spacing: 2) {
                    Text(String(localized: "Current Model"))
                        .font(.system(size: 11))
                        .foregroundColor(.secondary)
                    Text(store.activeConfig().modelId)
                        .font(.system(size: 14, weight: .medium))
                }
                Spacer()
            }
            .padding(12)
            .background(Color.accentColor.opacity(0.08))
            .clipShape(RoundedRectangle(cornerRadius: 10))

            // 模型列表
            if store.configs.isEmpty {
                VStack(spacing: 4) {
                    Text(String(localized: "Default remote model has time limits"))
                        .font(.system(size: 13))
                        .foregroundColor(.secondary)
                    Text(String(localized: "Add your own model to remove restrictions"))
                        .font(.system(size: 12))
                        .foregroundColor(.secondary.opacity(0.7))
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, 16)
            } else {
                ForEach(store.configs, id: \.uniqueKey) { config in
                    HStack(spacing: 10) {
                        Image(matIcon: store.selectedModelId == config.modelId ? "radio_button_checked" : "radio_button_unchecked")
                            .font(.system(size: 20))
                            .foregroundColor(store.selectedModelId == config.modelId ? .accentColor : .secondary)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(config.modelId).font(.system(size: 14, weight: .medium))
                            Text(providerName(config)).font(.system(size: 11)).foregroundColor(.secondary)
                        }
                        Spacer()
                        Button { store.remove(uniqueKey: config.uniqueKey) } label: {
                            Image(matIcon: "delete").font(.system(size: 18)).foregroundColor(.red.opacity(0.6))
                        }
                    }
                    .contentShape(Rectangle())
                    .onTapGesture { store.select(modelId: config.modelId) }
                    .padding(.vertical, 6)
                    if config.uniqueKey != store.configs.last?.uniqueKey {
                        Divider()
                    }
                }
            }

            // 添加按钮
            Button { showAddSheet = true } label: {
                HStack {
                    Image(matIcon: "add").font(.system(size: 16))
                    Text(String(localized: "Add Model")).font(.system(size: 14, weight: .medium))
                }
                .foregroundColor(.accentColor)
            }
        }
        .sheet(isPresented: $showAddSheet) {
            AddModelSheet { provider, modelId, apiKey in
                store.add(provider: provider, modelId: modelId, apiKey: apiKey)
            }
        }
    }

    private func providerName(_ config: RemoteModelConfig) -> String {
        let providers = RemoteModelConfig.companion.PROVIDERS as? [RemoteModelProvider] ?? []
        return providers.first { $0.providerId == config.providerId }?.displayName ?? config.baseUrl
    }
}

// MARK: - Communication Channel Settings

struct CommunicationChannelView: View {
    @Environment(\.dismiss) private var dismiss
    @AppStorage("channel_type") private var channelType: String = "none"
    @AppStorage("feishu_app_id") private var feishuAppId = ""
    @AppStorage("feishu_app_secret") private var feishuAppSecret = ""
    @AppStorage("telegram_bot_token") private var telegramBotToken = ""
    @AppStorage("telegram_chat_id") private var telegramChatId = ""

    var body: some View {
        ScrollView {
            VStack(spacing: 12) {
                // Channel selection
                settingsSection(String(localized: "Current Channel")) {
                    VStack(spacing: 8) {
                        HStack(spacing: 8) {
                            channelChip("feishu", label: String(localized: "Feishu"))
                            channelChip("telegram", label: "Telegram")
                            channelChip("none", label: String(localized: "None"))
                        }
                        .padding(.vertical, 4)
                    }
                }

                // Feishu config
                if channelType == "feishu" {
                    settingsSection(String(localized: "Feishu"), String(localized: "Connect Feishu to receive remote commands via IM messages.")) {
                        VStack(spacing: 10) {
                            credentialField(title: "App ID", text: $feishuAppId, placeholder: String(localized: "Feishu App ID"))
                            credentialField(title: "App Secret", text: $feishuAppSecret, placeholder: String(localized: "Feishu App Secret"), isPassword: true)
                        }
                    }
                }

                // Telegram config
                if channelType == "telegram" {
                    settingsSection("Telegram", String(localized: "Connect via Telegram Bot long polling (no public IP needed).")) {
                        VStack(spacing: 10) {
                            credentialField(title: "Bot Token", text: $telegramBotToken, placeholder: "123456:ABC-DEF...", isPassword: true)
                            credentialField(title: String(localized: "Allowed Chat ID"), text: $telegramChatId, placeholder: "123456789")
                            Text(String(localized: "Create a bot via @BotFather then paste its token."))
                                .font(.system(size: 11)).foregroundColor(.secondary)
                            Text(String(localized: "Only this chat can send commands (security whitelist). Without Chat ID, the bot rejects all messages."))
                                .font(.system(size: 11)).foregroundColor(.orange)
                        }
                    }
                }
            }
            .padding(.horizontal, 10)
            .padding(.vertical, 6)
        }
        .background(Color(.systemGroupedBackground).ignoresSafeArea())
        .navigationTitle(String(localized: "Channels"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .navigationBarLeading) {
                Button { dismiss() } label: { MatIcon(name: "chevron.left", size: 20) }
            }
        }
    }

    private func channelChip(_ value: String, label: String) -> some View {
        let selected = channelType == value
        return Button { channelType = value } label: {
            Text(label)
                .font(.system(size: 13, weight: selected ? .semibold : .regular))
                .foregroundColor(selected ? .white : .primary)
                .padding(.horizontal, 14)
                .padding(.vertical, 7)
                .background(selected ? Color.accentColor : Color(.tertiarySystemBackground))
                .clipShape(Capsule())
        }
    }
}

// MARK: - Memory Facts View

struct MemoryFactsView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var facts: [String] = []
    @State private var showClearConfirm = false

    var body: some View {
        Group {
            if facts.isEmpty {
                VStack(spacing: 12) {
                    Image(matIcon: "psychology").font(.system(size: 48)).foregroundColor(.secondary.opacity(0.3))
                    Text(String(localized: "No memories yet. Tell Xiaolang \"remember...\" in chat to add."))
                        .font(.system(size: 14)).foregroundColor(.secondary)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, 40)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                List {
                    ForEach(facts, id: \.self) { fact in
                        Text(fact)
                    }
                    .onDelete { _ in }
                }
            }
        }
        .background(Color(.systemGroupedBackground).ignoresSafeArea())
        .navigationTitle(String(localized: "AI Memory"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .navigationBarLeading) {
                Button { dismiss() } label: { MatIcon(name: "chevron.left", size: 20) }
            }
            if !facts.isEmpty {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button(String(localized: "Clear All")) { showClearConfirm = true }
                        .foregroundColor(.red)
                }
            }
        }
        .confirmationDialog(String(localized: "Clear all memories?"), isPresented: $showClearConfirm, titleVisibility: .visible) {
            Button(String(localized: "Clear"), role: .destructive) { facts = [] }
            Button(String(localized: "Cancel"), role: .cancel) {}
        }
    }
}

// MARK: - Developer Settings View

struct DeveloperSettingsView: View {
    @Environment(\.dismiss) private var dismiss
    @AppStorage("debug_ui_enabled") private var debugEnabled = false
    @AppStorage("show_camera_info_in_preview") private var showCameraInfo = false
    @AppStorage("show_face_debug_overlay") private var showFaceDebug = false
    @AppStorage("show_log_overlay") private var showLogOverlay = false

    var body: some View {
        ScrollView {
            VStack(spacing: 12) {
                settingsSection(String(localized: "Debug Tools"), String(localized: "Recommended for debugging only.")) {
                    VStack(spacing: 0) {
                        toggleRow(String(localized: "Debug"), isOn: $debugEnabled)
                        if debugEnabled {
                            Divider()
                            toggleRow(String(localized: "Show Camera Info"), isOn: $showCameraInfo)
                            Divider()
                            toggleRow(String(localized: "Show Face Debug"), isOn: $showFaceDebug)
                            Divider()
                            toggleRow(String(localized: "Show Log Overlay"), isOn: $showLogOverlay)
                        }
                    }
                }
            }
            .padding(.horizontal, 10)
            .padding(.vertical, 6)
        }
        .background(Color(.systemGroupedBackground).ignoresSafeArea())
        .navigationTitle(String(localized: "Developer"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .navigationBarLeading) {
                Button { dismiss() } label: { MatIcon(name: "chevron.left", size: 20) }
            }
        }
    }
}

// MARK: - Reusable Components

private func settingsSection<C: View>(_ title: String, _ desc: String? = nil, @ViewBuilder content: () -> C) -> some View {
    VStack(alignment: .leading, spacing: 6) {
        Text(title)
            .font(.system(size: 14, weight: .medium))
            .padding(.leading, 4)
        if let desc {
            Text(desc)
                .font(.system(size: 12))
                .foregroundColor(.secondary)
                .padding(.leading, 4)
        }
        content()
            .padding(12)
            .background(Color(.secondarySystemBackground))
            .clipShape(RoundedRectangle(cornerRadius: 12))
    }
}

private func toggleRow(_ title: String, isOn: Binding<Bool>) -> some View {
    HStack {
        Text(title).font(.system(size: 14))
        Spacer()
        Toggle("", isOn: isOn).labelsHidden()
    }
    .padding(.vertical, 8)
}

private func credentialField(title: String, text: Binding<String>, placeholder: String, isPassword: Bool = false) -> some View {
    VStack(alignment: .leading, spacing: 4) {
        Text(title).font(.system(size: 12)).foregroundColor(.secondary)
        if isPassword {
            SecureField(placeholder, text: text)
                .font(.system(size: 14))
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
        } else {
            TextField(placeholder, text: text)
                .font(.system(size: 14))
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
        }
    }
}
