import SwiftUI

/// 权限引导页（对齐 Android `GalleryPermission.kt` 的 GalleryPermissionMessage）：
/// 居中 PhotoLibrary 图标 64pt accentColor + headlineSmall 标题 + bodyMedium 描述 + 按钮组。
/// 用于 notDetermined / denied / addOnly 三态。
struct PermissionMessageView: View {
    struct ActionButton {
        let title: String
        var accessibilityID: String? = nil
        let action: () -> Void
    }

    var systemImage: String = "photo.on.rectangle"
    let title: String
    let description: String
    var primaryButton: ActionButton? = nil
    var secondaryButton: ActionButton? = nil

    var body: some View {
        VStack(spacing: 16) {
            Spacer()
            Image(systemName: systemImage)
                .font(.system(size: 64))
                .foregroundStyle(Color.accentColor)
            Text(title)
                .font(.title3)
                .multilineTextAlignment(.center)
            Text(description)
                .font(.body)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 32)
            if let primaryButton {
                Button(primaryButton.title, action: primaryButton.action)
                    .buttonStyle(.borderedProminent)
                    .accessibilityIdentifier(primaryButton.accessibilityID ?? "permission_primary")
            }
            if let secondaryButton {
                Button(secondaryButton.title, action: secondaryButton.action)
                    .buttonStyle(.plain)
                    .foregroundStyle(Color.accentColor)
                    .accessibilityIdentifier(secondaryButton.accessibilityID ?? "permission_secondary")
            }
            Spacer()
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

/// 空相册提示（对齐 Android `EmptyGalleryMessage`：居中 bodyLarge 纯文本，无图标）。
struct EmptyGalleryMessage: View {
    let text: String

    var body: some View {
        Text(text)
            .font(.body)
            .foregroundStyle(.secondary)
            .multilineTextAlignment(.center)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .accessibilityIdentifier("gallery_empty")
    }
}
