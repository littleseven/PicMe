import SwiftUI
import AVFoundation
import Photos

/// 设置主屏（1:1 对标 Android SettingsScreen.kt）。
/// spec: specs/screens/settings.yaml
/// 截图参考: /tmp/android-ui-reference/02-settings-main.png
///
/// 结构：账号英雄卡片 → 主题模式卡片 → 语言卡片 → 分类网格（2 列×5 行 = 10 卡片）
struct SettingsScreen: View {
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject private var settings: AppSettings
    @Environment(\.colorScheme) private var cs
    /// M3 语义色（对标 Android MaterialTheme.colorScheme）
    private var s: SchemeColors { appScheme(cs) }

    /// 开发者选项解锁态（连点版本号 7 次解锁，持久化；对标 Android developer_options_unlocked）
    @AppStorage("developer_options_unlocked") private var developerUnlocked: Bool = false
    @State private var unlockTapCount: Int = 0
    @State private var lastUnlockTap: Date = .distantPast
    @State private var unlockHint: String?
    /// 相册设置（扫描控制台）以 fullScreenCover 呈现（TagScanScreen 既有模式）
    @State private var showGalleryConsole = false

    var body: some View {
        ScrollView {
            VStack(spacing: 14) {
                accountHeroCard
                themeCard
                languageCard
                categoryGrid
                versionFooter
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
        }
        .background(s.background.ignoresSafeArea())
        .navigationTitle(L("Settings"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .navigationBarLeading) {
                Button { dismiss() } label: {
                    MatIcon(name: "chevron.left", size: 20)
                }
            }
        }
        .fullScreenCover(isPresented: $showGalleryConsole) {
            TagScanScreen(onDismiss: { showGalleryConsole = false })
        }
    }

    // MARK: - Version footer + 开发者选项连点解锁

    private var appVersionText: String {
        let v = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0"
        return "PoLang v\(v)"
    }

    private var versionFooter: some View {
        VStack(spacing: 4) {
            Text(appVersionText)
                .font(.system(size: 12))
                .foregroundColor(.secondary.opacity(0.6))
                .contentShape(Rectangle())
                .onTapGesture { handleVersionTap() }
            if let hint = unlockHint {
                Text(hint)
                    .font(.system(size: 12))
                    .foregroundColor(.accentColor)
                    .transition(.opacity)
            }
        }
        .padding(.top, 16)
        .padding(.bottom, 8)
        .animation(.easeInOut(duration: 0.2), value: unlockHint)
    }

    private func handleVersionTap() {
        let now = Date()
        // 距上次点击超 4 秒则归零（防误触累积）
        if now.timeIntervalSince(lastUnlockTap) > 4 {
            unlockTapCount = 0
        }
        unlockTapCount += 1
        lastUnlockTap = now
        if unlockTapCount >= 7 {
            unlockTapCount = 0
            developerUnlocked = true
            unlockHint = L("Developer options enabled.")
        } else {
            unlockHint = String(format: NSLocalizedString("Tap %d more times to enable developer options.", comment: ""), 7 - unlockTapCount)
        }
        // 2 秒后清空提示
        let hint = unlockHint
        DispatchQueue.main.asyncAfter(deadline: .now() + 2) {
            if unlockHint == hint { withAnimation { unlockHint = nil } }
        }
    }

    // MARK: - ① Account Hero Card

    private var accountHeroCard: some View {
        NavigationLink {
            AccountSettingsView()
        } label: {
            HStack(spacing: 12) {
                ZStack {
                    Circle()
                        .fill(Color.accentColor.opacity(0.15))
                        .frame(width: 48, height: 48)
                    Image(matIcon: "person")
                        .font(.system(size: 24))
                        .foregroundColor(.accentColor)
                }

                VStack(alignment: .leading, spacing: 2) {
                    Text(L("Account"))
                        .font(.system(size: 16, weight: .medium))
                    Text(L("Sign in for more quota and features"))
                        .font(.system(size: 13))
                        .foregroundColor(.secondary)
                        .lineLimit(1)
                }

                Spacer()
                Image(matIcon: "arrow_forward")
                    .font(.system(size: 16))
                    .foregroundColor(.secondary)
            }
            .padding(16)
            .background(s.surfaceContainerHighest)
            .clipShape(RoundedRectangle(cornerRadius: 16))
        }
        .buttonStyle(.plain)
    }

    // MARK: - ② Theme Mode Card

    private var themeCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(L("Theme"))
                .font(.system(size: 14, weight: .medium))

            HStack(spacing: 8) {
                filterChip("system", label: L("System Default"))
                filterChip("light", label: L("Light"))
                filterChip("dark", label: L("Dark"))
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(s.surfaceContainerHighest)
        .clipShape(RoundedRectangle(cornerRadius: 12))
    }

    // MARK: - ③ Language Card

    private var languageCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(L("Language"))
                .font(.system(size: 14, weight: .medium))

            HStack(spacing: 8) {
                filterChip("english", label: "English", isSelected: settings.appLanguage == "english")
                filterChip("chinese_simplified", label: "中文", isSelected: settings.appLanguage == "chinese_simplified")
                filterChip("chinese_traditional", label: "繁體中文", isSelected: settings.appLanguage == "chinese_traditional")
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(s.surfaceContainerHighest)
        .clipShape(RoundedRectangle(cornerRadius: 12))
    }

    // 通用 FilterChip（主题用）
    private func filterChip(_ value: String, label: String) -> some View {
        filterChip(value, label: label, isSelected: settings.themeMode == value)
    }

    private func filterChip(_ value: String, label: String, isSelected: Bool) -> some View {
        Button {
            if ["system", "light", "dark"].contains(value) {
                settings.themeMode = value
            } else {
                settings.appLanguage = value
            }
        } label: {
            Text(label)
                .font(.system(size: 13, weight: isSelected ? .semibold : .regular))
                .foregroundColor(isSelected ? .white : .primary)
                .padding(.horizontal, 14)
                .padding(.vertical, 7)
                .background(isSelected ? Color.accentColor : s.surfaceContainerHigh)
                .clipShape(Capsule())
        }
    }

    // MARK: - ④ Category Grid (2 columns × 5 rows = 10 cards)

    private var categoryGrid: some View {
        let columns = [
            GridItem(.flexible(), spacing: 10),
            GridItem(.flexible(), spacing: 10),
        ]
        return LazyVGrid(columns: columns, spacing: 10) {
            ForEach(categories, id: \.title) { cat in
                categoryCard(cat)
            }
        }
    }

    @ViewBuilder
    private func categoryCard(_ cat: SettingsCategoryItem) -> some View {
        let cardContent = VStack(alignment: .leading, spacing: 8) {
            Image(matIcon: cat.icon)
                .font(.system(size: 28))
                .foregroundColor(.accentColor)
            Text(cat.title)
                .font(.system(size: 14, weight: .medium))
                .foregroundColor(.primary)
                .lineLimit(1)
            Text(cat.isPlaceholder ? L("Coming Soon") : cat.desc)
                .font(.system(size: 12))
                .foregroundColor(cat.isPlaceholder ? .secondary.opacity(0.5) : .secondary)
                .lineLimit(2)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .background(s.surfaceContainerHighest)
        .clipShape(RoundedRectangle(cornerRadius: 12))

        Group {
            switch cat.target {
            case .modelCenter:
                NavigationLink { ModelDownloadCenterView() } label: { cardContent }
            case .remoteModels:
                NavigationLink { ModelCenterView().environmentObject(ModelConfigStore.shared) } label: { cardContent }
            case .localModels:
                NavigationLink { LocalModelsSettingsView() } label: { cardContent }
            case .sandbox:
                NavigationLink { SandboxSettingsView() } label: { cardContent }
            case .dataPrivacy:
                NavigationLink { DataPrivacyView() } label: { cardContent }
            case .memoryFacts:
                NavigationLink { MemoryFactsView() } label: { cardContent }
            case .channels:
                NavigationLink { CommunicationChannelView() } label: { cardContent }
            case .people:
                NavigationLink {
                    PersonView().environmentObject(AppContainer.shared)
                } label: { cardContent }
            case .developer:
                NavigationLink { DeveloperSettingsView() } label: { cardContent }
            case .gallery:
                Button { showGalleryConsole = true } label: { cardContent }
            }
        }
        .buttonStyle(.plain)
    }

    private var categories: [SettingsCategoryItem] {
        var items: [SettingsCategoryItem] = [
            // Row 1
            .init(icon: "psychology", title: L("AI Memory"), desc: L("View, edit, delete AI remembered facts"), target: .memoryFacts, isPlaceholder: false),
            .init(icon: "account_circle", title: L("People"), desc: L("Manage people and relationships"), target: .people, isPlaceholder: false),
            // Row 2
            .init(icon: "forum", title: L("Channels"), desc: L("Configure Feishu / Telegram remote control"), target: .channels, isPlaceholder: false),
            .init(icon: "photo_library", title: L("Gallery Settings"), desc: L("Tag scanning, people clustering, tags & duplicates"), target: .gallery, isPlaceholder: false),
            // Row 3
            .init(icon: "smart_toy", title: L("Remote Models"), desc: L("Remote model providers, API keys, and the active model."), target: .remoteModels, isPlaceholder: false),
            .init(icon: "storage", title: L("Local Models"), desc: L("Pick on-device face-detection, tagging and voice models."), target: .localModels, isPlaceholder: false),
            // Row 4
            .init(icon: "cloud_download", title: L("Model Center"), desc: L("Download and manage all local models"), target: .modelCenter, isPlaceholder: false),
            .init(icon: "lock", title: L("Sandbox & Permissions"), desc: L("Auto-execute, JS engine, device access."), target: .sandbox, isPlaceholder: false),
            // Row 5
            .init(icon: "privacy_tip", title: L("Data & Privacy"), desc: L("Privacy policy, data retention, deletion"), target: .dataPrivacy, isPlaceholder: false),
        ]
        // 开发者选项仅在连点解锁后出现（对标 Android developer_options_unlocked 门控）
        if developerUnlocked {
            items.append(.init(icon: "terminal", title: L("Developer Options"), desc: L("Debug overlays and advanced diagnostics."), target: .developer, isPlaceholder: false))
        }
        return items
    }
}

// MARK: - Category Item Model

struct SettingsCategoryItem {
    let icon: String
    let title: String
    let desc: String
    let target: Target
    let isPlaceholder: Bool

    enum Target {
        case memoryFacts, people, channels, gallery
        case modelCenter, remoteModels, localModels, sandbox
        case developer, dataPrivacy
    }
}

// MARK: - Account Settings

struct AccountSettingsView: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.colorScheme) private var cs
    private var s: SchemeColors { appScheme(cs) }

    var body: some View {
        VStack(spacing: 16) {
            Spacer()
            Image(matIcon: "person")
                .font(.system(size: 64))
                .foregroundColor(.secondary.opacity(0.3))
            Text(L("Account registration coming in a future version."))
                .font(.system(size: 14))
                .foregroundColor(.secondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 40)
            Text(L("You are currently using guest mode with limited quota."))
                .font(.system(size: 13))
                .foregroundColor(.secondary.opacity(0.7))
            Spacer()
        }
        .background(s.background.ignoresSafeArea())
        .navigationTitle(L("Account"))
        .navigationBarTitleDisplayMode(.inline)
    }
}

// MARK: - Data & Privacy

struct DataPrivacyView: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.colorScheme) private var cs
    private var s: SchemeColors { appScheme(cs) }

    private let sections: [(title: String, body: String)] = [
        (L("Account Data"), L("Your email is used only for authentication and LLM free trial usage counting (default 100 times). No passwords are collected — login uses email verification codes.")),
        (L("Device Identifier"), L("A device identifier is generated to count free trial usage for unregistered guests. This identifier is sent to api.polang.net and is not used for personal identification or shared with third parties.")),
        (L("Data Retention"), L("After an account is deleted, data will be retained for 90 days (for anti-fraud and recovery purposes) before being permanently deleted, including usage logs.")),
        (L("Delete Your Account"), L("You can delete your account through Settings → Account → Delete Account, or by emailing us.")),
        (L("Local Processing"), L("Photos, beauty filters, facial keypoints, OCR text, media location, and chat memory are all processed locally on your device and are never uploaded to a server.")),
        (L("Remote Inference"), L("After authenticating, remote LLM conversations are proxied through api.polang.net to the LLM provider for the current request only. The server only records call counts and token usage, not conversation content.")),
        (L("Contact Us"), "budao.gs@gmail.com"),
    ]

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                // 备份与恢复入口（iOS 尚未实现，占位；Android 已并入此页）
                HStack {
                    Text(L("Backup & Restore"))
                        .font(.system(size: 15, weight: .semibold))
                    Spacer()
                    Text(L("Coming Soon"))
                        .font(.system(size: 12))
                        .foregroundColor(.secondary.opacity(0.6))
                }
                .padding(.vertical, 4)

                ForEach(sections, id: \.title) { section in
                    VStack(alignment: .leading, spacing: 6) {
                        Text(section.title)
                            .font(.system(size: 15, weight: .semibold))
                            .foregroundColor(.accentColor)
                        Text(section.body)
                            .font(.system(size: 13))
                            .foregroundColor(.secondary)
                    }
                }

                if let url = URL(string: "https://polang.net/privacy-policy/") {
                    Link(L("View Full Privacy Policy"), destination: url)
                        .font(.system(size: 14, weight: .medium))
                        .padding(.top, 8)
                }
            }
            .padding(20)
        }
        .background(s.background.ignoresSafeArea())
        .navigationTitle(L("Data & Privacy"))
        .navigationBarTitleDisplayMode(.inline)
        // back 由 NavigationStack 系统提供，无需手动 toolbar
    }
}

// MARK: - About

struct AboutView: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.colorScheme) private var cs
    private var s: SchemeColors { appScheme(cs) }

    private var appVersion: String {
        let v = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "?"
        let b = Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "?"
        return "\(v) (\(b))"
    }

    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                VStack(spacing: 8) {
                    Image(matIcon: "photo_library")
                        .font(.system(size: 48))
                        .foregroundColor(.accentColor)
                    Text("PoLang")
                        .font(.system(size: 20, weight: .bold))
                    Text(L("PoLang · AI Agent Album"))
                        .font(.system(size: 13))
                        .foregroundColor(.secondary)
                }
                .padding(.top, 20)

                VStack(spacing: 0) {
                    infoRow(label: L("Version"), value: appVersion)
                    Divider().background(Color(.separator))
                    if let url = URL(string: "https://github.com/littleseven/polang") {
                        Link(destination: url) {
                            infoRow(label: "GitHub", value: "littleseven/polang", showArrow: true)
                        }
                        .buttonStyle(.plain)
                    }
                }
                .background(s.surfaceContainerHighest)
                .clipShape(RoundedRectangle(cornerRadius: 12))
                .padding(.horizontal, 16)

                Text(L("Built with Kotlin Multiplatform, Koog, SwiftUI, Metal, MNN"))
                    .font(.system(size: 11))
                    .foregroundColor(.secondary.opacity(0.7))
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 30)

                Spacer()
            }
        }
        .background(s.background.ignoresSafeArea())
        .navigationTitle(L("About"))
        .navigationBarTitleDisplayMode(.inline)
        // back 由 NavigationStack 系统提供，无需手动 toolbar
    }

    private func infoRow(label: String, value: String, showArrow: Bool = false) -> some View {
        HStack {
            Text(label)
                .font(.system(size: 14))
            Spacer()
            Text(value)
                .font(.system(size: 14))
                .foregroundColor(.secondary)
            if showArrow {
                Image(matIcon: "arrow_forward")
                    .font(.system(size: 14))
                    .foregroundColor(.secondary)
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 12)
    }
}

// MARK: - 本地模型设置（P3：人脸检测/照片打标/语音）

struct LocalModelsSettingsView: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.colorScheme) private var cs
    private var s: SchemeColors { appScheme(cs) }

    // 人脸检测——引擎二选一（真接，对标 Android face detection engine）
    @AppStorage("camera_use_mnn") private var useMnn: Bool = true
    // 人脸细分（iOS 不支持，持久化占位）
    @AppStorage("face_landmark_mode") private var landmarkMode: Bool = true
    @AppStorage("adaptive_face_detection_interval") private var adaptiveInterval: Bool = true
    @AppStorage("face_detect_interval_profile") private var intervalProfile: String = "BALANCED"
    // 照片打标模型（Florence 真接；Qwen iOS 不可用）
    @AppStorage("tagger_model_key") private var taggerModelKey: String = "auto"
    // 语音（iOS 无引擎，持久化占位）
    @AppStorage("voice_command_mode") private var voiceMode: String = "DISABLED"

    var body: some View {
        ScrollView {
            VStack(spacing: 14) {
                faceDetectionCard
                photoTaggingCard
                voiceCard
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
        }
        .background(s.background.ignoresSafeArea())
        .navigationTitle(L("Local Models"))
        .navigationBarTitleDisplayMode(.inline)
        // back 由 NavigationStack 系统提供，无需手动 toolbar
    }

    private func sectionCard<Content: View>(title: String, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(title).font(.system(size: 14, weight: .semibold))
            content()
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .background(s.surfaceContainerHighest)
        .clipShape(RoundedRectangle(cornerRadius: 12))
    }

    private func chip(_ label: String, isSelected: Bool, isDisabled: Bool = false, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(label)
                .font(.system(size: 13, weight: isSelected ? .semibold : .regular))
                .foregroundColor(isSelected ? .white : (isDisabled ? .secondary : .primary))
                .padding(.horizontal, 14).padding(.vertical, 7)
                .background(isSelected ? Color.accentColor : s.surfaceContainerHigh)
                .clipShape(Capsule())
        }
        .disabled(isDisabled)
    }

    private func iosUnsupportedNote(_ text: String) -> some View {
        Text(text)
            .font(.system(size: 11))
            .foregroundColor(.secondary.opacity(0.7))
            .frame(maxWidth: .infinity, alignment: .leading)
    }

    // MARK: 人脸检测
    private var faceDetectionCard: some View {
        sectionCard(title: L("Face Detection")) {
            Text(L("Choose face detection models and device; tune landmarks and cadence."))
                .font(.system(size: 12)).foregroundColor(.secondary)
            // 引擎二选一（真接 camera_use_mnn）
            Text(L("Detection engine"))
                .font(.system(size: 13, weight: .medium)).foregroundColor(.secondary)
                .padding(.top, 4)
            HStack(spacing: 8) {
                chip("MNN", isSelected: useMnn) { useMnn = true }
                chip("MediaPipe", isSelected: !useMnn) { useMnn = false }
            }
            // 细分项（iOS 不支持，占位灰显）
            Divider().padding(.vertical, 2)
            HStack {
                Text(L("Face landmark mode")).font(.system(size: 14))
                Spacer()
                Toggle("", isOn: $landmarkMode).labelsHidden().disabled(true)
            }.foregroundColor(.secondary)
            HStack {
                Text(L("Adaptive detection interval")).font(.system(size: 14))
                Spacer()
                Toggle("", isOn: $adaptiveInterval).labelsHidden().disabled(true)
            }.foregroundColor(.secondary)
            iosUnsupportedNote(L("iOS uses a single fixed pipeline (CPU only). Per-stage models, GPU and interval profiles are not configurable on iOS."))
        }
    }

    // MARK: 照片打标
    private var photoTaggingCard: some View {
        sectionCard(title: L("Photo Tagging")) {
            Text(L("Auto-detect photo content into tags (scene, objects, people, activity, aesthetic) for search and grouping."))
                .font(.system(size: 12)).foregroundColor(.secondary)
            Text(L("Tagging model"))
                .font(.system(size: 13, weight: .medium)).foregroundColor(.secondary)
                .padding(.top, 4)
            HStack(spacing: 8) {
                chip(L("Auto"), isSelected: taggerModelKey == "auto") { taggerModelKey = "auto" }
                chip("Florence-2", isSelected: taggerModelKey == "florence2_base") { taggerModelKey = "florence2_base" }
                chip("Qwen3-VL", isSelected: taggerModelKey == "qwen3_vl_2b", isDisabled: true) {
                    taggerModelKey = "qwen3_vl_2b"
                }
            }
            iosUnsupportedNote(L("iOS supports Florence-2 only. Qwen3-VL tagging is unavailable on iOS; selecting it falls back to Florence-2."))
        }
    }

    // MARK: 语音
    private var voiceCard: some View {
        sectionCard(title: L("Voice")) {
            iosUnsupportedNote(L("On-device voice recognition (ASR) and wake word (KWS) are not yet implemented on iOS. Settings below are placeholders for future versions."))
            Text(L("Voice interaction mode"))
                .font(.system(size: 13, weight: .medium)).foregroundColor(.secondary)
                .padding(.top, 4)
            HStack(spacing: 8) {
                chip(L("Disabled"), isSelected: voiceMode == "DISABLED", isDisabled: true) { }
                chip(L("Push to talk"), isSelected: voiceMode == "PUSH_TO_TALK", isDisabled: true) { }
                chip(L("Wake word"), isSelected: voiceMode == "WAKE_WORD", isDisabled: true) { }
            }
        }
    }
}

// MARK: - 沙盒与权限（P4：执行 / 设备访问）

struct SandboxSettingsView: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.colorScheme) private var cs
    private var s: SchemeColors { appScheme(cs) }

    // 执行（软开关，两端均 persistence-only，能力层消费待接入）
    @AppStorage("auto_execute_plans") private var autoExecute: Bool = true
    @AppStorage("js_engine_enabled") private var jsEngine: Bool = false
    // 设备访问软开关（persistence-only）
    @AppStorage("agent_camera_access_enabled") private var cameraAccess: Bool = true
    @AppStorage("agent_gallery_access_enabled") private var galleryAccess: Bool = true
    // 语音（iOS 无引擎，占位）
    @AppStorage("voice_command_mode") private var voiceMode: String = "DISABLED"
    @AppStorage("voice_entry_enabled") private var voiceEntry: Bool = false

    var body: some View {
        ScrollView {
            VStack(spacing: 14) {
                executionCard
                deviceAccessCard
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
        }
        .background(s.background.ignoresSafeArea())
        .navigationTitle(L("Sandbox & Permissions"))
        .navigationBarTitleDisplayMode(.inline)
        // back 由 NavigationStack 系统提供，无需手动 toolbar
    }

    private func sectionCard<Content: View>(title: String, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(title).font(.system(size: 14, weight: .semibold))
            content()
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .background(s.surfaceContainerHighest)
        .clipShape(RoundedRectangle(cornerRadius: 12))
    }

    private func toggleRow(_ title: String, isOn: Binding<Bool>) -> some View {
        HStack {
            Text(title).font(.system(size: 14))
            Spacer()
            Toggle("", isOn: isOn).labelsHidden()
        }
    }

    private func systemPermissionRow(_ title: String) -> some View {
        Button {
            if let url = URL(string: UIApplication.openSettingsURLString) {
                UIApplication.shared.open(url)
            }
        } label: {
            HStack {
                Text(title).font(.system(size: 14)).foregroundColor(.primary)
                Spacer()
                Image(matIcon: "arrow_forward").font(.system(size: 14)).foregroundColor(.secondary)
            }
        }
        .buttonStyle(.plain)
    }

    private var executionCard: some View {
        sectionCard(title: L("Execution")) {
            Text(L("Control what the Agent can run autonomously."))
                .font(.system(size: 12)).foregroundColor(.secondary)
            toggleRow(L("Auto-execute multi-step plans"), isOn: $autoExecute)
            Divider()
            toggleRow(L("JS engine execution"), isOn: $jsEngine)
            Text(L("Allow the Agent to execute JS sandbox scripts."))
                .font(.system(size: 11)).foregroundColor(.secondary.opacity(0.7))
        }
    }

    private var deviceAccessCard: some View {
        sectionCard(title: L("Device Access")) {
            Text(L("Control which device capabilities the Agent may use."))
                .font(.system(size: 12)).foregroundColor(.secondary)
            toggleRow(L("Camera access"), isOn: $cameraAccess)
            systemPermissionRow(L("Camera permission (system)"))
            Divider()
            toggleRow(L("Gallery access"), isOn: $galleryAccess)
            systemPermissionRow(L("Gallery permission (system)"))
            Divider()
            // 语音（iOS 占位灰显）
            HStack {
                Text(L("Voice interaction mode")).font(.system(size: 14)).foregroundColor(.secondary)
                Spacer()
                Text(L("Disabled")).font(.system(size: 13)).foregroundColor(.secondary)
            }
            Text(L("On-device voice recognition (ASR) and wake word (KWS) are not yet implemented on iOS."))
                .font(.system(size: 11)).foregroundColor(.secondary.opacity(0.7))
        }
    }
}

#Preview {
    NavigationStack {
        SettingsScreen()
    }
}
