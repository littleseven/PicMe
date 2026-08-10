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
                settingsSection(L("AI Assistant"), L("Use remote model for natural language camera control.")) {
                    VStack(spacing: 0) {
                        // 自动执行计划
                        HStack {
                            VStack(alignment: .leading, spacing: 3) {
                                Text(L("Auto-Execute Multi-Step Plans"))
                                    .font(.system(size: 14))
                                Text(L("When disabled, Agent requires confirmation before executing multi-step plans."))
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
                            Text(L("Inference Mode")).font(.system(size: 14))
                            Spacer()
                            chip(L("Remote Model"), isSelected: true)
                        }
                        .padding(.vertical, 8)
                    }
                }

                // Section: 远程推理
                settingsSection(L("Remote Inference")) {
                    AiAgentRemoteModelsSection()
                        .environmentObject(ModelConfigStore.shared)
                }

                // Section: 语音控制
                settingsSection(L("Voice Control"), L("Control camera shooting via voice commands.")) {
                    VStack(spacing: 8) {
                        HStack {
                            Text(L("Voice Mode")).font(.system(size: 14))
                            Spacer()
                            Text(L("Not Available"))
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
        .navigationTitle(L("AI Assistant"))
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
                    Text(L("Current Model"))
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
                    Text(L("Default remote model has time limits"))
                        .font(.system(size: 13))
                        .foregroundColor(.secondary)
                    Text(L("Add your own model to remove restrictions"))
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
                    Text(L("Add Model")).font(.system(size: 14, weight: .medium))
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
                settingsSection(L("Current Channel")) {
                    VStack(spacing: 8) {
                        HStack(spacing: 8) {
                            channelChip("feishu", label: L("Feishu"))
                            channelChip("telegram", label: "Telegram")
                            channelChip("none", label: L("None"))
                        }
                        .padding(.vertical, 4)
                    }
                }

                // Feishu config
                if channelType == "feishu" {
                    settingsSection(L("Feishu"), L("Connect Feishu to receive remote commands via IM messages.")) {
                        VStack(spacing: 10) {
                            credentialField(title: "App ID", text: $feishuAppId, placeholder: L("Feishu App ID"))
                            credentialField(title: "App Secret", text: $feishuAppSecret, placeholder: L("Feishu App Secret"), isPassword: true)
                        }
                    }
                }

                // Telegram config
                if channelType == "telegram" {
                    settingsSection("Telegram", L("Connect via Telegram Bot long polling (no public IP needed).")) {
                        VStack(spacing: 10) {
                            credentialField(title: "Bot Token", text: $telegramBotToken, placeholder: "123456:ABC-DEF...", isPassword: true)
                            credentialField(title: L("Allowed Chat ID"), text: $telegramChatId, placeholder: "123456789")
                            Text(L("Create a bot via @BotFather then paste its token."))
                                .font(.system(size: 11)).foregroundColor(.secondary)
                            Text(L("Only this chat can send commands (security whitelist). Without Chat ID, the bot rejects all messages."))
                                .font(.system(size: 11)).foregroundColor(.orange)
                        }
                    }
                }
            }
            .padding(.horizontal, 10)
            .padding(.vertical, 6)
        }
        .background(Color(.systemGroupedBackground).ignoresSafeArea())
        .navigationTitle(L("Channels"))
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
        .navigationTitle(L("AI Memory"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .navigationBarLeading) {
                Button { dismiss() } label: { MatIcon(name: "chevron.left", size: 20) }
            }
            if !facts.isEmpty {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button(L("Clear All")) { showClearConfirm = true }
                        .foregroundColor(.red)
                }
            }
        }
        .confirmationDialog(L("Clear all memories?"), isPresented: $showClearConfirm, titleVisibility: .visible) {
            Button(L("Clear"), role: .destructive) { facts = [] }
            Button(L("Cancel"), role: .cancel) {}
        }
    }
}

// MARK: - Developer Settings View

struct DeveloperSettingsView: View {
    @Environment(\.dismiss) private var dismiss
    @AppStorage("debug_ui_enabled") private var debugEnabled = false
    @AppStorage("show_camera_info_in_preview") private var showCameraInfo = true
    @AppStorage("show_face_debug_overlay") private var showFaceDebug = true
    @AppStorage("show_log_overlay") private var showLogOverlay = true

    var body: some View {
        ScrollView {
            VStack(spacing: 12) {
                settingsSection(L("Debug Tools"), L("Recommended for debugging only.")) {
                    VStack(spacing: 0) {
                        toggleRow(L("Debug"), isOn: $debugEnabled)
                        if debugEnabled {
                            Divider()
                            toggleRow(L("Show Camera Info"), isOn: $showCameraInfo)
                            Divider()
                            toggleRow(L("Show Face Debug"), isOn: $showFaceDebug)
                            Divider()
                            toggleRow(L("Show Log Overlay"), isOn: $showLogOverlay)
                        }
                    }
                }
            }
            .padding(.horizontal, 10)
            .padding(.vertical, 6)
        }
        .background(Color(.systemGroupedBackground).ignoresSafeArea())
        .navigationTitle(L("Developer"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .navigationBarLeading) {
                Button { dismiss() } label: { MatIcon(name: "chevron.left", size: 20) }
            }
        }
    }
}

// MARK: - Camera & Beauty Settings View

/// 相机与美颜调试设置（对标 Android SettingsScreen 的 cameraBeauty 入口）。
/// 🔴 瘦脸诊断中枢：实时瘦脸/大眼/形变强度 + 人脸引擎切换 + 调试可视化开关 + live 遥测。
///
/// 设计动机：相机页美颜面板(wand 图标)已有 Slim/BigEyes 滑杆，但瘦脸真机不可见；
/// 此页补充 ① 形变强度倍率(warpStrength)排查「强度不足」、② hasFace 遥测排查「关键点未送达」、
/// ③ 引擎默认值持久化、④ 调试开关集中入口。
struct CameraBeautySettingsView: View {
    @Environment(\.dismiss) private var dismiss
    @ObservedObject private var container = AppContainer.shared
    @ObservedObject private var dbg = DebugOverlayState.shared
    @AppStorage("camera_use_mnn") private var cameraUseMnn = true
    @AppStorage("camera_debug_overlay") private var debugOverlay = true
    @AppStorage("camera_show_landmarks") private var showLandmarks = false

    var body: some View {
        ScrollView {
            VStack(spacing: 12) {
                // Section: 美颜实时调试
                settingsSection(L("Beauty Debug"), L("Live slim/big-eyes strength. Also adjustable on the camera beauty panel (wand icon).")) {
                    VStack(spacing: 0) {
                        sliderRow(L("Slim Face"), value: $container.beautyParams.slimFace, range: -50...50)
                            .onChange(of: container.beautyParams.slimFace) { v in
                                UserDefaults.standard.set(v, forKey: "beauty_slim_debug")
                            }
                        Divider()
                        sliderRow(L("Big Eyes"), value: $container.beautyParams.bigEyes, range: 0...100)
                            .onChange(of: container.beautyParams.bigEyes) { v in
                                UserDefaults.standard.set(v, forKey: "beauty_bigeyes_debug")
                            }
                        Divider()
                        sliderRow(L("Warp Strength"), value: $container.beautyParams.warpStrength,
                                  range: 0...8, step: 0.5, format: { String(format: "%.1fx", $0) })
                            .onChange(of: container.beautyParams.warpStrength) { v in
                                UserDefaults.standard.set(v, forKey: "beauty_warp_strength")
                            }
                        Text(L("Warp strength magnifies the slim delta. 1.0 = default (subtle); raise it to test if slim is too weak to see."))
                            .font(.system(size: 11))
                            .foregroundColor(.secondary)
                            .padding(.top, 6)
                    }
                }

                // Section: 人脸检测引擎
                settingsSection(L("Face Detection Engine"),
                                L("Default engine. MNN ships on-device models (RetinaFace + 2d106); MediaPipe needs a downloaded model.")) {
                    HStack(spacing: 8) {
                        engineChip(mnn: true, label: "MNN")
                        engineChip(mnn: false, label: "MediaPipe")
                    }
                    .padding(.vertical, 4)
                }

                // Section: 调试可视化
                settingsSection(L("Debug Visualization")) {
                    VStack(spacing: 0) {
                        toggleRow(L("Debug Overlay"), isOn: $debugOverlay)
                            .onChange(of: debugOverlay) { v in DebugOverlayState.shared.isEnabled = v }
                        Divider()
                        toggleRow(L("Show Face Landmarks"), isOn: $showLandmarks)
                        Text(L("Draw detected face landmarks on the preview to verify detection feeds the renderer."))
                            .font(.system(size: 11))
                            .foregroundColor(.secondary)
                            .padding(.top, 6)
                    }
                }

                // Section: live 诊断遥测
                settingsSection(L("Live Diagnostics"),
                                L("Read-only. beauty.hasFace=0 means face points never reach the shader — slim cannot work no matter the strength.")) {
                    VStack(alignment: .leading, spacing: 6) {
                        ForEach(dbg.entries, id: \.key) { entry in
                            HStack {
                                Text(entry.key)
                                    .font(.system(size: 12, design: .monospaced))
                                    .foregroundColor(.secondary)
                                Spacer()
                                Text(entry.value)
                                    .font(.system(size: 12, weight: .semibold, design: .monospaced))
                                    .foregroundColor(diagnosticColor(entry.key, entry.value))
                            }
                        }
                    }
                }
            }
            .padding(.horizontal, 10)
            .padding(.vertical, 6)
        }
        .background(Color(.systemGroupedBackground).ignoresSafeArea())
        .navigationTitle(L("Camera & Beauty"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .navigationBarLeading) {
                Button { dismiss() } label: { MatIcon(name: "chevron.left", size: 20) }
            }
        }
    }

    private func engineChip(mnn: Bool, label: String) -> some View {
        let selected = cameraUseMnn == mnn
        return Button { cameraUseMnn = mnn } label: {
            Text(label)
                .font(.system(size: 13, weight: selected ? .semibold : .regular))
                .foregroundColor(selected ? .white : .primary)
                .padding(.horizontal, 14)
                .padding(.vertical, 7)
                .background(selected ? Color.accentColor : Color(.tertiarySystemBackground))
                .clipShape(Capsule())
        }
    }

    /// 遥测着色：hasFace=1 绿 / =0 红，方便一眼判断 warp 是否在跑
    private func diagnosticColor(_ key: String, _ value: String) -> Color {
        if key == "beauty.hasFace" { return value == "1" ? .green : .red }
        if key == "face.error" { return .orange }
        return .primary
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

/// 调试滑杆行（相机美颜设置专用）：标签 + 当前值 + Slider。
private struct DebugSliderRow: View {
    let label: String
    @Binding var value: Float
    let range: ClosedRange<Float>
    var step: Float = 1.0
    var format: ((Float) -> String)?

    var body: some View {
        VStack(spacing: 6) {
            HStack {
                Text(label).font(.system(size: 14))
                Spacer()
                Text(displayText)
                    .font(.system(size: 14, weight: .bold))
                    .foregroundColor(value != 0 ? .accentColor : .secondary)
            }
            Slider(value: $value, in: range, step: step) { Text(label) }
                .tint(.accentColor)
        }
        .padding(.vertical, 8)
    }

    private var displayText: String {
        if let format { return format(value) }
        return value == 0 ? "--" : "\(Int(value))"
    }
}

/// 文件内 sliderRow 视图构造器（包 DebugSliderRow，供 CameraBeautySettingsView 使用）
private func sliderRow(_ label: String, value: Binding<Float>, range: ClosedRange<Float>,
                        step: Float = 1.0, format: ((Float) -> String)? = nil) -> DebugSliderRow {
    DebugSliderRow(label: label, value: value, range: range, step: step, format: format)
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
