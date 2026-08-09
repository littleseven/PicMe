import SwiftUI
import AVFoundation
import Photos

/// 设置页框架（对标 Android SettingsScreen）
/// 本期：权限状态行 + 语言说明 + 版本号；Phase 6 占位分组灰置

/// 设置页框架（对标 Android SettingsScreen）
/// 本期：权限状态行 + 语言说明 + 版本号；Phase 6 占位分组灰置
struct SettingsScreen: View {
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                // 权限
                Section(String(localized: "Permissions")) {
                    PermissionRow(title: String(localized: "Camera"),
                                  status: permissionStatus(for: "camera"))
                    PermissionRow(title: String(localized: "Photo Library"),
                                  status: permissionStatus(for: "photos"))
                }

                // 语言
                Section(String(localized: "Language")) {
                    HStack {
                        Text(String(localized: "Language"))
                        Spacer()
                        Text(String(localized: "Follow System"))
                            .foregroundColor(.secondary)
                    }
                }

                // Phase 6 占位分组
                Section(String(localized: "AI Assistant")) {
                    Phase6PlaceholderRow(title: String(localized: "AI Assistant"))
                }
                Section(String(localized: "Account & Services")) {
                    Phase6PlaceholderRow(title: String(localized: "Account & Services"))
                }
                Section(String(localized: "Data & Privacy")) {
                    Phase6PlaceholderRow(title: String(localized: "Data & Privacy"))
                }
                Section(String(localized: "Model Center")) {
                    Phase6PlaceholderRow(title: String(localized: "Model Center"))
                }

                // 关于
                Section(String(localized: "About")) {
                    HStack {
                        Text(String(localized: "Version"))
                        Spacer()
                        Text(appVersion)
                            .foregroundColor(.secondary)
                    }
                }
            }
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
        .preferredColorScheme(.dark)
    }

    private var appVersion: String {
        let v = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "?"
        let b = Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "?"
        return "\(v) (\(b))"
    }

    private func permissionStatus(for type: String) -> String {
        switch type {
        case "camera":
            let s = AVCaptureDevice.authorizationStatus(for: .video)
            return s == .authorized ? String(localized: "Granted") : String(localized: "Not Granted")
        case "photos":
            let s = PHPhotoLibrary.authorizationStatus(for: .readWrite)
            switch s {
            case .authorized: return String(localized: "Full Access")
            case .limited: return String(localized: "Limited Access")
            default: return String(localized: "Not Granted")
            }
        default: return ""
        }
    }
}

private struct PermissionRow: View {
    let title: String
    let status: String

    var body: some View {
        HStack {
            Text(title)
            Spacer()
            Text(status).foregroundColor(.secondary)
            Button(String(localized: "Open Settings")) {
                if let url = URL(string: UIApplication.openSettingsURLString) {
                    UIApplication.shared.open(url)
                }
            }
            .font(.caption)
        }
    }
}

private struct Phase6PlaceholderRow: View {
    let title: String
    var body: some View {
        HStack {
            Text(title).foregroundColor(.secondary)
            Spacer()
            Text(String(localized: "Coming Soon"))
                .font(.caption)
                .foregroundColor(.secondary)
        }
    }
}
