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

    var body: some View {
        ScrollView {
            VStack(spacing: 14) {
                accountHeroCard
                themeCard
                languageCard
                categoryGrid
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
        }
        .background(Color(.systemGroupedBackground).ignoresSafeArea())
        .navigationTitle(L("Settings"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .navigationBarLeading) {
                Button { dismiss() } label: {
                    MatIcon(name: "chevron.left", size: 20)
                }
            }
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
            .background(Color(.secondarySystemBackground))
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
        .background(Color(.secondarySystemBackground))
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
        .background(Color(.secondarySystemBackground))
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
                .background(isSelected ? Color.accentColor : Color(.tertiarySystemBackground))
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
        .background(Color(.secondarySystemBackground))
        .clipShape(RoundedRectangle(cornerRadius: 12))

        Group {
            switch cat.target {
            case .aiAgent:
                NavigationLink { AiAgentSettingsView() } label: { cardContent }
            case .apiModels:
                NavigationLink { ModelCenterView().environmentObject(ModelConfigStore.shared) } label: { cardContent }
            case .modelCenter:
                NavigationLink { ModelDownloadCenterView() } label: { cardContent }
            case .dataPrivacy:
                NavigationLink { DataPrivacyView() } label: { cardContent }
            case .about:
                NavigationLink { AboutView() } label: { cardContent }
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
            default:
                cardContent.opacity(0.5)
            }
        }
        .buttonStyle(.plain)
    }

    private var categories: [SettingsCategoryItem] {
        [
            // Row 1
            .init(icon: "smart_toy", title: L("AI Assistant"), desc: L("Remote AI inference, voice control"), target: .aiAgent, isPlaceholder: false),
            .init(icon: "psychology", title: L("AI Memory"), desc: L("View, edit, delete AI remembered facts"), target: .memoryFacts, isPlaceholder: false),
            // Row 2
            .init(icon: "person", title: L("People"), desc: L("Manage people and relationships"), target: .people, isPlaceholder: false),
            .init(icon: "forum", title: L("Channels"), desc: L("Configure Feishu / Telegram remote control"), target: .channels, isPlaceholder: false),
            // Row 3
            .init(icon: "photo_library", title: L("Gallery"), desc: L("Tag scanning, face clustering, model management"), target: .gallery, isPlaceholder: true),
            .init(icon: "camera_alt", title: L("Camera & Beauty"), desc: L("Face detection, beauty engine, camera behavior"), target: .cameraBeauty, isPlaceholder: true),
            // Row 4
            .init(icon: "cloud_download", title: L("Model Center"), desc: L("Download and manage all local models"), target: .modelCenter, isPlaceholder: false),
            .init(icon: "terminal", title: L("Developer"), desc: L("Debug overlay and advanced diagnostics"), target: .developer, isPlaceholder: false),
            // Row 5
            .init(icon: "storage", title: L("Backup & Restore"), desc: L("Export or import TAG, face clusters, OCR, settings"), target: .backup, isPlaceholder: true),
            .init(icon: "privacy_tip", title: L("Data & Privacy"), desc: L("Privacy policy, data retention, deletion"), target: .dataPrivacy, isPlaceholder: false),
        ]
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
        case aiAgent, memoryFacts, people, channels, gallery, cameraBeauty
        case modelCenter, apiModels, developer, backup, dataPrivacy, about
    }
}

// MARK: - Account Settings

struct AccountSettingsView: View {
    @Environment(\.dismiss) private var dismiss

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
        .background(Color(.systemGroupedBackground).ignoresSafeArea())
        .navigationTitle(L("Account"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .navigationBarLeading) {
                Button { dismiss() } label: { MatIcon(name: "chevron.left", size: 20) }
            }
        }
    }
}

// MARK: - Data & Privacy

struct DataPrivacyView: View {
    @Environment(\.dismiss) private var dismiss

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
        .background(Color(.systemGroupedBackground).ignoresSafeArea())
        .navigationTitle(L("Data & Privacy"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .navigationBarLeading) {
                Button { dismiss() } label: { MatIcon(name: "chevron.left", size: 20) }
            }
        }
    }
}

// MARK: - About

struct AboutView: View {
    @Environment(\.dismiss) private var dismiss

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
                .background(Color(.secondarySystemBackground))
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
        .background(Color(.systemGroupedBackground).ignoresSafeArea())
        .navigationTitle(L("About"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .navigationBarLeading) {
                Button { dismiss() } label: { MatIcon(name: "chevron.left", size: 20) }
            }
        }
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

#Preview {
    NavigationStack {
        SettingsScreen()
    }
}
