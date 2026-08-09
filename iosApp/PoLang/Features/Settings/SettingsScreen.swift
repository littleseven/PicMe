import SwiftUI
import AVFoundation
import Photos

/// 设置主屏（1:1 对标 Android SettingsScreen.kt）。
/// spec: specs/screens/settings.yaml
///
/// 结构：账号英雄卡片 + 主题卡片 + 语言卡片 + 分类网格（2 列）
struct SettingsScreen: View {
    @Environment(\.dismiss) private var dismiss
    @AppStorage("theme_mode") private var themeMode: String = "system"
    @AppStorage("app_language") private var appLanguage: String = "system"

    var body: some View {
        ScrollView {
            VStack(spacing: 12) {
                accountHeroCard
                themeCard
                languageCard
                categoryGrid
            }
            .padding(.horizontal, 10)
            .padding(.vertical, 6)
        }
        .background(Color(.systemGroupedBackground).ignoresSafeArea())
        .navigationTitle(String(localized: "Settings"))
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
                // Avatar
                ZStack {
                    Circle()
                        .fill(Color.accentColor.opacity(0.15))
                        .frame(width: 48, height: 48)
                    Image(matIcon: "person")
                        .font(.system(size: 24))
                        .foregroundColor(.accentColor)
                }

                VStack(alignment: .leading, spacing: 2) {
                    Text(String(localized: "Account"))
                        .font(.system(size: 16, weight: .medium))
                    Text(String(localized: "Sign in for more quota and features"))
                        .font(.system(size: 13))
                        .foregroundColor(.secondary)
                        .lineLimit(1)
                }

                Spacer()
                Image(matIcon: "chevron.right")
                    .font(.system(size: 16))
                    .foregroundColor(.secondary)
            }
            .padding(16)
            .background(Color(.secondarySystemBackground))
            .clipShape(RoundedRectangle(cornerRadius: 16))
        }
        .buttonStyle(.plain)
    }

    // MARK: - ② Theme Card

    private var themeCard: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(String(localized: "Theme"))
                .font(.system(size: 14, weight: .medium))

            HStack(spacing: 8) {
                themeChip("system", label: String(localized: "System"))
                themeChip("light", label: String(localized: "Light"))
                themeChip("dark", label: String(localized: "Dark"))
            }
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color(.secondarySystemBackground))
        .clipShape(RoundedRectangle(cornerRadius: 12))
    }

    private func themeChip(_ value: String, label: String) -> some View {
        let selected = themeMode == value
        return Button {
            themeMode = value
        } label: {
            Text(label)
                .font(.system(size: 13, weight: selected ? .semibold : .regular))
                .foregroundColor(selected ? .white : .primary)
                .padding(.horizontal, 14)
                .padding(.vertical, 7)
                .background(selected ? Color.accentColor : Color(.tertiarySystemBackground))
                .clipShape(Capsule())
        }
    }

    // MARK: - ③ Language Card

    private var languageCard: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(String(localized: "Language"))
                .font(.system(size: 14, weight: .medium))

            HStack(spacing: 8) {
                langChip("english", label: "English")
                langChip("chinese_simplified", label: "中文")
                langChip("chinese_traditional", label: "繁體中文")
            }
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color(.secondarySystemBackground))
        .clipShape(RoundedRectangle(cornerRadius: 12))
    }

    private func langChip(_ value: String, label: String) -> some View {
        let selected = appLanguage == value
        return Button {
            appLanguage = value
        } label: {
            Text(label)
                .font(.system(size: 13, weight: selected ? .semibold : .regular))
                .foregroundColor(selected ? .white : .primary)
                .padding(.horizontal, 14)
                .padding(.vertical, 7)
                .background(selected ? Color.accentColor : Color(.tertiarySystemBackground))
                .clipShape(Capsule())
        }
    }

    // MARK: - ④ Category Grid (2 columns)

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

    private func categoryCard(_ cat: SettingsCategory) -> some View {
        Group {
            switch cat.target {
            case .apiModels:
                NavigationLink { ModelCenterView().environmentObject(ModelConfigStore.shared) } label: { cardLabel(cat) }
            case .modelCenter:
                NavigationLink { ModelDownloadCenterView() } label: { cardLabel(cat) }
            case .dataPrivacy:
                NavigationLink { DataPrivacyView() } label: { cardLabel(cat) }
            case .about:
                NavigationLink { AboutView() } label: { cardLabel(cat) }
            default:
                if cat.isPlaceholder {
                    cardLabel(cat)
                        .opacity(0.5)
                }
            }
        }
        .buttonStyle(.plain)
    }

    private func cardLabel(_ cat: SettingsCategory) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Image(matIcon: cat.iconSF)
                .font(.system(size: 28))
                .foregroundColor(.accentColor)
            Text(cat.title)
                .font(.system(size: 14, weight: .medium))
                .foregroundColor(.primary)
                .lineLimit(1)
            Text(cat.isPlaceholder ? String(localized: "Coming Soon") : cat.desc)
                .font(.system(size: 12))
                .foregroundColor(cat.isPlaceholder ? .secondary.opacity(0.5) : .secondary)
                .lineLimit(2)
                .multilineTextAlignment(.leading)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(14)
        .background(Color(.secondarySystemBackground))
        .clipShape(RoundedRectangle(cornerRadius: 12))
    }
}

// MARK: - Categories

struct SettingsCategory {
    let iconSF: String
    let title: String
    let desc: String
    let target: Target
    let isPlaceholder: Bool

    enum Target {
        case aiAgent, memoryFacts, people, channels, gallery, cameraBeauty
        case modelCenter, apiModels, dataPrivacy, about
    }
}

private extension SettingsScreen {
    var categories: [SettingsCategory] {
        [
            .init(iconSF: "smart_toy", title: "AI Assistant", desc: "Remote model, voice control", target: .aiAgent, isPlaceholder: true),
            .init(iconSF: "psychology", title: "AI Memory", desc: "Manage remembered facts", target: .memoryFacts, isPlaceholder: true),
            .init(iconSF: "account_circle", title: "People", desc: "Face clusters & relationships", target: .people, isPlaceholder: true),
            .init(iconSF: "forum", title: "Channels", desc: "Feishu, Telegram control", target: .channels, isPlaceholder: true),
            .init(iconSF: "photo_library", title: "Gallery", desc: "Tags, duplicates, search", target: .gallery, isPlaceholder: true),
            .init(iconSF: "camera_alt", title: "Camera & Beauty", desc: "Face detection, landmarks", target: .cameraBeauty, isPlaceholder: true),
            .init(iconSF: "download", title: "Model Center", desc: "Download AI models", target: .modelCenter, isPlaceholder: false),
            .init(iconSF: "api_key", title: "API Models", desc: "Remote LLM config", target: .apiModels, isPlaceholder: false),
            .init(iconSF: "privacy_tip", title: "Data & Privacy", desc: "Privacy policy, data control", target: .dataPrivacy, isPlaceholder: false),
            .init(iconSF: "info", title: "About", desc: "Version, open source", target: .about, isPlaceholder: false),
        ]
    }
}

// MARK: - Account Settings (placeholder for Phase 6.3)

struct AccountSettingsView: View {
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        VStack(spacing: 16) {
            Spacer()
            Image(matIcon: "person")
                .font(.system(size: 64))
                .foregroundColor(.secondary.opacity(0.3))
            Text(String(localized: "Account registration coming in a future version."))
                .font(.system(size: 14))
                .foregroundColor(.secondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 40)
            Text(String(localized: "You are currently using guest mode with limited quota."))
                .font(.system(size: 13))
                .foregroundColor(.secondary.opacity(0.7))
            Spacer()
        }
        .background(Color(.systemGroupedBackground).ignoresSafeArea())
        .navigationTitle(String(localized: "Account"))
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
        ("Account Data", "Your email is used only for authentication. No password is stored — we use verification code login."),
        ("Device Identifier", "A device ID is generated for guest quota tracking. This resets when you reinstall the app."),
        ("Data Retention", "Chat history is stored locally on your device. AI memory facts persist until you delete them."),
        ("Data Deletion", "You can delete all data at any time: chat history (clear in chat), AI memory (clear in settings), and model files (delete in model center)."),
        ("Local Processing", "All media processing (beauty, tagging, face detection) runs 100% on-device. Your photos and videos never leave your device."),
        ("Remote Processing", "Only text and metadata (file names, dates, counts) are sent to remote LLMs for inference. No photos or videos are uploaded."),
        ("Contact", "budao.gs@gmail.com"),
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
                    Link(String(localized: "View Full Privacy Policy"), destination: url)
                        .font(.system(size: 14, weight: .medium))
                        .padding(.top, 8)
                }
            }
            .padding(20)
        }
        .background(Color(.systemGroupedBackground).ignoresSafeArea())
        .navigationTitle(String(localized: "Data & Privacy"))
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
                // App icon + name
                VStack(spacing: 8) {
                    Image(matIcon: "photo_library")
                        .font(.system(size: 48))
                        .foregroundColor(.accentColor)
                    Text("PoLang")
                        .font(.system(size: 20, weight: .bold))
                    Text(String(localized: "破浪相册 · AI Agent Album"))
                        .font(.system(size: 13))
                        .foregroundColor(.secondary)
                }
                .padding(.top, 20)

                // Info rows
                VStack(spacing: 0) {
                    infoRow(label: String(localized: "Version"), value: appVersion)
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

                Text(String(localized: "Built with Kotlin Multiplatform, Koog, SwiftUI, Metal, MNN"))
                    .font(.system(size: 11))
                    .foregroundColor(.secondary.opacity(0.7))
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 30)

                Spacer()
            }
        }
        .background(Color(.systemGroupedBackground).ignoresSafeArea())
        .navigationTitle(String(localized: "About"))
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
                Image(matIcon: "chevron.right")
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
