import SwiftUI

/// 访客注册引导弹层（chat.yaml §4.1 sheet，2026-08-22；设计稿 refs/ardot/chat-guest-nudge-sheet）。
///
/// 双选项：① 邮箱注册·领免费额度（展开 Email OTP 表单，复用 Settings 的
/// PoLangAuthClient sendCode/verify 流程与同源 server_auth_token 存储 key）；
/// ② 配置自己的 Token（跳设置·远程模型页）。verify 成功 → 访客计数清零 + 关闭。
struct ChatRegistrationSheet: View {
    /// 已用免费对话轮数（底部 note 展示）
    let usedCount: Int
    /// 邮箱 verify 成功（ChatViewModel.registrationSuccess：清零计数 + 关 sheet）
    var onRegisterSuccess: () -> Void
    /// 「配置自己的 Token」（ChatView：关 sheet + 全屏打开远程模型页）
    var onUseOwnToken: () -> Void

    @State private var showOtpForm = false
    // OTP 表单（形态复用 SettingsScreen EmailAuthSection：同 client、同存储 key、同取词）
    @State private var emailInput = ""
    @State private var codeInput = ""
    @State private var codeSent = false
    @State private var sending = false
    @State private var verifying = false
    @State private var errorMsg: String?
    private let client = PoLangAuthClient.shared

    var body: some View {
        ScrollView {
            VStack(spacing: 0) {
                Text(L("Enjoying Xiaolang? Unlock the full experience"))
                    .font(.system(size: 20, weight: .semibold))
                    .foregroundColor(Color(.label))
                    .multilineTextAlignment(.center)

                Spacer().frame(height: 8)

                Text(L("Register for free quota, or configure your own LLM Token. Either way, keep chatting."))
                    .font(.system(size: 13))
                    .foregroundColor(Color(.secondaryLabel))
                    .multilineTextAlignment(.center)

                Spacer().frame(height: 20)

                if showOtpForm {
                    otpForm
                } else {
                    ctaButtons
                }

                Spacer().frame(height: 14)

                // 底部说明（{count} 已用轮数；管理入口=设置）
                Text(String(
                    format: L("%lld free conversations used · Manage anytime in Settings → AI Assistant"),
                    usedCount
                ))
                    .font(.system(size: 12))
                    .foregroundColor(Color(.secondaryLabel).opacity(0.8))
                    .multilineTextAlignment(.center)
            }
            .padding(.horizontal, 20)
            .padding(.top, 20)
            .padding(.bottom, 16)
        }
        .frame(maxWidth: .infinity)
        .background(Color(.systemBackground))
        .alert(
            L("Error"),
            isPresented: Binding(
                get: { errorMsg != nil },
                set: { if !$0 { errorMsg = nil } }
            )
        ) {
            Button(L("OK"), role: .cancel) {}
        } message: {
            Text(errorMsg ?? "")
        }
        .accessibilityIdentifier("chat_registration_sheet")
    }

    // MARK: - 双选项 CTA（未展开 OTP 表单时）

    private var ctaButtons: some View {
        VStack(spacing: 10) {
            // 主 CTA：品牌渐变实底 r22 h48 白字 15 semibold → 展开 Email OTP 表单
            Button { withAnimation { showOtpForm = true } } label: {
                Text(L("Sign up with email · Get free quota"))
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundColor(.white)
                    .frame(maxWidth: .infinity)
                    .frame(height: 48)
                    .background(
                        RoundedRectangle(cornerRadius: 22).fill(
                            LinearGradient(
                                colors: [ChatBubbleTokens.brandGradientStart, ChatBubbleTokens.brandGradientEnd],
                                startPoint: .topLeading, endPoint: .bottomTrailing
                            )
                        )
                    )
            }
            .accessibilityIdentifier("chat_reg_primary")

            // 次 CTA：1.5pt 品牌描边 r22 h48 品牌色文字 → 跳设置远程模型页
            Button { onUseOwnToken() } label: {
                Text(L("Use my own Token"))
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundColor(ChatBubbleTokens.brandGradientStart)
                    .frame(maxWidth: .infinity)
                    .frame(height: 48)
                    .background(
                        RoundedRectangle(cornerRadius: 22)
                            .strokeBorder(ChatBubbleTokens.brandGradientStart, lineWidth: 1.5)
                    )
            }
            .accessibilityIdentifier("chat_reg_secondary")
        }
    }

    // MARK: - Email OTP 表单（复用 Settings PoLangAuthClient 流程，紧凑化形态）

    private var otpForm: some View {
        VStack(spacing: 12) {
            TextField(L("Email"), text: $emailInput)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .keyboardType(.emailAddress)
                .font(.system(size: 15))
                .foregroundColor(Color(.label))
                .padding(12)
                .background(Color(.secondarySystemBackground))
                .clipShape(RoundedRectangle(cornerRadius: 12))
                .accessibilityIdentifier("chat_reg_email")

            if codeSent {
                TextField(L("Verification Code"), text: $codeInput)
                    .textInputAutocapitalization(.never)
                    .keyboardType(.numberPad)
                    .font(.system(size: 15))
                    .foregroundColor(Color(.label))
                    .padding(12)
                    .background(Color(.secondarySystemBackground))
                    .clipShape(RoundedRectangle(cornerRadius: 12))
                    .accessibilityIdentifier("chat_reg_code")
            }

            Button { Task { await sendCode() } } label: {
                HStack(spacing: 8) {
                    if sending { ProgressView().tint(.white) }
                    Text(sending ? L("Sending…") : L("Send Code"))
                        .font(.system(size: 15, weight: .semibold))
                }
                .foregroundColor(.white)
                .frame(maxWidth: .infinity)
                .frame(height: 48)
                .background(
                    RoundedRectangle(cornerRadius: 22).fill(
                        LinearGradient(
                            colors: [ChatBubbleTokens.brandGradientStart, ChatBubbleTokens.brandGradientEnd],
                            startPoint: .topLeading, endPoint: .bottomTrailing
                        )
                    )
                )
            }
            .disabled(emailInput.isEmpty || sending)
            .accessibilityIdentifier("chat_reg_send_code")

            if codeSent {
                Button { Task { await verify() } } label: {
                    HStack(spacing: 8) {
                        if verifying { ProgressView().tint(ChatBubbleTokens.brandGradientStart) }
                        Text(verifying ? L("Verifying…") : L("Verify & Sign In"))
                            .font(.system(size: 15, weight: .semibold))
                    }
                    .foregroundColor(ChatBubbleTokens.brandGradientStart)
                    .frame(maxWidth: .infinity)
                    .frame(height: 48)
                    .background(
                        RoundedRectangle(cornerRadius: 22)
                            .strokeBorder(ChatBubbleTokens.brandGradientStart, lineWidth: 1.5)
                    )
                }
                .disabled(codeInput.isEmpty || verifying)
                .accessibilityIdentifier("chat_reg_verify")
            }
        }
    }

    // MARK: - Auth Actions（同 SettingsScreen AccountSettingsView 流程/存储 key）

    private func sendCode() async {
        sending = true
        defer { sending = false }
        do {
            try await client.sendCode(email: emailInput)
            codeSent = true
        } catch {
            errorMsg = (error as? AuthError)?.message ?? L("Network error")
        }
    }

    private func verify() async {
        verifying = true
        defer { verifying = false }
        do {
            let result = try await client.verify(email: emailInput, code: codeInput)
            // 与 SettingsScreen 同源存储 key（server 会话 token/email）——chat 与设置双注册入口共享登录态
            UserDefaults.standard.set(result.token, forKey: "server_auth_token")
            UserDefaults.standard.set(emailInput, forKey: "server_auth_email")
            onRegisterSuccess()  // 访客计数清零 + 关 sheet（ChatViewModel.registrationSuccess）
        } catch {
            errorMsg = (error as? AuthError)?.message ?? L("Network error")
        }
    }
}
