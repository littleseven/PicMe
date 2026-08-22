import SwiftUI
import SharedKit

/// ARGB hex 色构造（与 DesignTokens.swift/TagScanComponents.swift 同款 file-private 扩展）。
private extension Color {
    init(hex: String) {
        let scanner = Scanner(string: hex)
        var hexNumber: UInt64 = 0
        scanner.scanHexInt64(&hexNumber)
        let a = Double((hexNumber & 0xFF00_0000) >> 24) / 255
        let r = Double((hexNumber & 0x00FF_0000) >> 16) / 255
        let g = Double((hexNumber & 0x0000_FF00) >> 8) / 255
        let b = Double(hexNumber & 0x0000_00FF) / 255
        self.init(.sRGB, red: r, green: g, blue: b, opacity: a)
    }
}

/// 添加远程模型页（spec settings.yaml §3c add_remote_provider，2026-08-21 弹窗改页面）。
///
/// 三段结构：
/// - 已接入：shared `RemoteModelConfig.PROVIDERS`（SSOT，双端共享，filter isVisible）
/// - 更多供应商：静态预告列表（不可点击、无 chevron，右侧「即将支持」胶囊）
/// - 自定义供应商：底部独立卡片（vibrantGreen "+" 徽章）
///
/// 行点击 → `ProviderConfigView`（provider_config/{providerId}）；
/// 保存完成后经 `ProviderConfigView.onSaved` 回调由本页 `dismiss()` 弹回来源页（确定性两级 pop）。
struct AddRemoteProviderView: View {
    @EnvironmentObject private var store: ModelConfigStore
    @Environment(\.dismiss) private var dismiss
    @Environment(\.colorScheme) private var cs
    private var s: SchemeColors { appScheme(cs) }

    /// 已接入供应商（SSOT：shared PROVIDERS，filter isVisible）
    private var providers: [RemoteModelProvider] {
        (RemoteModelConfig.companion.PROVIDERS as? [RemoteModelProvider])?
            .filter { $0.isVisible } ?? []
    }

    /// 更多供应商（静态预告，未接入；名称/副题走 L() 供三语翻译）
    private let futureProviders: [(name: String, subtitle: String, letter: String, fontSize: CGFloat, colors: [Color])] = [
        (L("Google Gemini"), L("Gemini 2.5 Pro · Flash"), "G", 14,
         [Color(hex: "FF4B8BF5"), Color(hex: "FF9B72F2")]),
        (L("Qwen"), L("Qwen3 Max · Qwen Plus"), "Q", 14, [Color(hex: "FF7147E8")]),
        (L("Zhipu AI"), L("GLM-5 · GLM-4.6 Series"), "Z", 14, [Color(hex: "FF3B5BFD")]),
        (L("xAI"), L("Grok 4 · Grok Code"), "X", 14, [Color(hex: "FF202124")]),
        (L("Mistral AI"), L("Mistral Large · Codestral"), "M", 14, [Color(hex: "FFFF7000")]),
        (L("OpenRouter"), L("200+ Models · OpenAI Compatible"), "OR", 9, [Color(hex: "FF6566F1")]),
    ]

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                // 已接入
                sectionLabel(L("Connected"))
                connectedCard

                // 更多供应商
                sectionLabel(L("More Providers"))
                moreProvidersCard

                // 自定义供应商
                customProviderCard
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
        }
        .background(s.background.ignoresSafeArea())
        .navigationTitle(L("Add Remote Model"))
        .navigationBarTitleDisplayMode(.inline)
    }

    // MARK: - Section Labels / Cards

    private func sectionLabel(_ text: String) -> some View {
        Text(text)
            .font(.system(size: 13))
            .foregroundColor(s.onSurfaceVariant)
            .padding(.leading, 4)
    }

    /// 已接入卡片：r12 surfaceContainerHighest + 行间 inset 分隔线（inset_start 56 = 16 padding + 28 badge + 12 gap）
    private var connectedCard: some View {
        VStack(spacing: 0) {
            ForEach(Array(providers.enumerated()), id: \.element.providerId) { index, provider in
                NavigationLink {
                    ProviderConfigView(provider: provider, onSaved: { dismiss() })
                } label: {
                    providerRow(provider)
                }
                .buttonStyle(.plain)
                if index < providers.count - 1 {
                    insetDivider
                }
            }
        }
        .background(s.surfaceContainerHighest)
        .clipShape(AppShapes.card)
    }

    /// 更多供应商卡片：静态、不可点击、无 chevron，右侧灰色「即将支持」胶囊
    private var moreProvidersCard: some View {
        VStack(spacing: 0) {
            ForEach(Array(futureProviders.enumerated()), id: \.offset) { index, item in
                HStack(spacing: 12) {
                    StaticBadge(letter: item.letter, fontSize: item.fontSize, colors: item.colors)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(item.name)
                            .font(.system(size: 15, weight: .semibold))
                            .foregroundColor(s.onSurface)
                        Text(item.subtitle)
                            .font(.system(size: 12))
                            .foregroundColor(s.onSurfaceVariant)
                    }
                    Spacer()
                    comingSoonPill
                }
                .padding(.horizontal, 16)
                .frame(minHeight: 64)
                .contentShape(Rectangle())
                if index < futureProviders.count - 1 {
                    insetDivider
                }
            }
        }
        .background(s.surfaceContainerHighest)
        .clipShape(AppShapes.card)
    }

    /// 自定义供应商：底部独立卡片（vibrantGreen "+" 徽章）
    private var customProviderCard: some View {
        NavigationLink {
            ProviderConfigView(provider: nil, onSaved: { dismiss() })
        } label: {
            HStack(spacing: 12) {
                ZStack {
                    RoundedRectangle(cornerRadius: 8)
                        .fill(AppColors.vibrantGreen)
                        .frame(width: 28, height: 28)
                    Image(matIcon: "add")
                        .font(.system(size: 18, weight: .semibold))
                        .foregroundColor(.white)
                }
                VStack(alignment: .leading, spacing: 2) {
                    Text(L("Custom Provider"))
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundColor(s.onSurface)
                    Text(L("OpenAI-compatible · custom Base URL"))
                        .font(.system(size: 12))
                        .foregroundColor(s.onSurfaceVariant)
                }
                Spacer()
                Image(matIcon: "arrow_forward")
                    .font(.system(size: SettingsTokens.rowChevronSize))
                    .foregroundColor(s.onSurfaceVariant.opacity(SettingsTokens.rowChevronAlpha))
            }
            .padding(.horizontal, 16)
            .frame(minHeight: 64)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .padding(.top, 4)
    }

    // MARK: - Rows / Pieces

    private func providerRow(_ provider: RemoteModelProvider) -> some View {
        HStack(spacing: 12) {
            ProviderBrandBadge(providerId: provider.providerId, displayName: provider.displayName)
            VStack(alignment: .leading, spacing: 2) {
                Text(provider.displayName)
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundColor(s.onSurface)
                Text(presetSummary(for: provider))
                    .font(.system(size: 12))
                    .foregroundColor(s.onSurfaceVariant)
            }
            Spacer()
            if isProviderConfigured(provider.providerId) {
                configuredPill
            }
            Image(matIcon: "arrow_forward")
                .font(.system(size: SettingsTokens.rowChevronSize))
                .foregroundColor(s.onSurfaceVariant.opacity(SettingsTokens.rowChevronAlpha))
        }
        .padding(.horizontal, 16)
        .frame(minHeight: SettingsTokens.rowHeightWithSubtitle)
        .contentShape(Rectangle())
    }

    /// 预置模型摘要：前两个模型 + 总数（「GPT-5 · GPT-4.1 等 4 个预置模型」动态生成）
    private func presetSummary(for provider: RemoteModelProvider) -> String {
        let models = provider.models as? [String] ?? []
        if models.count <= 2 {
            return models.joined(separator: " · ")
        }
        return String(format: L("%1$@ · %2$@ · %3$d preset models"), models[0], models[1], models.count)
    }

    /// 该 providerId 下已有 isConfigured 配置 → 显示 Configured 胶囊（#0B9E4A @ 12%）
    private func isProviderConfigured(_ providerId: String) -> Bool {
        store.configs.contains { $0.providerId == providerId && $0.isConfigured }
    }

    private var configuredPill: some View {
        Text(L("Configured"))
            .font(.system(size: 11))
            .foregroundColor(Color(hex: "FF0B9E4A"))
            .padding(.horizontal, 8)
            .padding(.vertical, 3)
            .background(Color(hex: "FF0B9E4A").opacity(0.12))
            .clipShape(Capsule())
    }

    private var comingSoonPill: some View {
        Text(L("Coming Soon"))
            .font(.system(size: 11))
            .foregroundColor(s.onSurfaceVariant)
            .padding(.horizontal, 8)
            .padding(.vertical, 3)
            .background(s.onSurfaceVariant.opacity(0.12))
            .clipShape(Capsule())
    }

    /// 行间分隔线（inset_start 56，对齐 spec §3c divider）
    private var insetDivider: some View {
        Rectangle()
            .fill(s.outlineVariant.opacity(SettingsTokens.rowChevronAlpha))
            .frame(height: 0.5)
            .padding(.leading, 56)
    }
}

// MARK: - Brand Badge（§3c 徽章规则，ProviderConfigView 复用）

/// 品牌色圆角字母徽章：28×28、r8、白色 SemiBold 字母。
/// 字母规则：Kimi=M(Moonshot)、TokenHub=T，其余取名称首字母；
/// 品牌色为 spec §3c 内联值（不进 design-tokens，见 contracts.md §4 Token 决策）。
struct ProviderBrandBadge: View {
    let providerId: String
    let displayName: String

    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 8)
                .fill(brandColor)
            Text(letter)
                .font(.system(size: 14, weight: .semibold))
                .foregroundColor(.white)
        }
        .frame(width: 28, height: 28)
    }

    private var letter: String {
        switch providerId {
        case "tencent-tokenhub": return "T"
        case "kimi-official": return "M"
        case "openai-official": return "O"
        case "anthropic-official": return "A"
        case "deepseek-official": return "D"
        default: return String(displayName.prefix(1))
        }
    }

    private var brandColor: Color {
        switch providerId {
        case "openai-official": return Color(hex: "FF000000")
        case "anthropic-official": return Color(hex: "FFD97757")
        case "deepseek-official": return Color(hex: "FF4D6BFE")
        case "kimi-official": return Color(hex: "FF4F378B")
        case "tencent-tokenhub": return Color(hex: "FF0052D9")
        default: return Color(hex: "FF4D6BFE")
        }
    }
}

/// 更多供应商静态徽章：单色或渐变（Gemini 蓝紫渐变）
private struct StaticBadge: View {
    let letter: String
    let fontSize: CGFloat
    let colors: [Color]

    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 8)
                .fill(LinearGradient(
                    colors: colors,
                    startPoint: .topLeading,
                    endPoint: .bottomTrailing
                ))
            Text(letter)
                .font(.system(size: fontSize, weight: .semibold))
                .foregroundColor(.white)
        }
        .frame(width: 28, height: 28)
    }
}

#Preview {
    NavigationStack {
        AddRemoteProviderView()
            .environmentObject(ModelConfigStore.shared)
    }
}
