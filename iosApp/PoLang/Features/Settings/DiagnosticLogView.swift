import SwiftUI
import Foundation

// MARK: - 诊断日志查看器（spec settings.yaml §6.6）
//
// 数据源：Application Support/llm_log/ 下三个 JSONL 文件（shared iosMain
// `IosDiagnosticLogStore` 落盘，注入点与 Android Room 三表同构）：
//   llm_calls.jsonl / tool_calls.jsonl / js_runs.jsonl，每行一条独立 JSON。
// 解析容错：逐行 decode，坏行跳过；读文件失败/不存在 → 空列表。列表按 createdAt 倒序。
// Release 隐私：captureContent=false 时 request/response/script/resultPreview 为 null，
// 详情页内容区显示说明卡（隐私红线，双端一致）。

// MARK: - 数据模型

/// 携带所属 JSONL 原始行（详情页「Raw」分区展示原始文本用；不参与 decode）
protocol RawLineAttachable {
    var rawLine: String? { get set }
}

struct LlmCallRecord: Identifiable, Codable, RawLineAttachable {
    let createdAt: Int64
    let source: String
    let model: String?
    let success: Bool
    let latencyMs: Int64?
    let promptTokens: Int?
    let completionTokens: Int?
    let totalTokens: Int?
    let requestJson: String?
    let responseJson: String?
    let errorMessage: String?
    let traceId: String?

    var id: Int64 { createdAt }
    var rawLine: String?

    private enum CodingKeys: String, CodingKey {
        case createdAt, source, model, success, latencyMs
        case promptTokens, completionTokens, totalTokens
        case requestJson, responseJson, errorMessage, traceId
    }
}

struct ToolCallRecord: Identifiable, Codable, RawLineAttachable {
    let createdAt: Int64
    let capability: String
    let commandType: String
    let latencyMs: Int64
    let success: Bool
    let errorCode: Int?
    let errorMessage: String?
    let traceId: String?

    var id: Int64 { createdAt }
    var rawLine: String?

    private enum CodingKeys: String, CodingKey {
        case createdAt, capability, commandType, latencyMs, success, errorCode, errorMessage, traceId
    }
}

struct JsRunRecord: Identifiable, Codable, RawLineAttachable {
    let createdAt: Int64
    let source: String
    let kind: String
    let script: String?
    let scriptLength: Int
    let success: Bool
    let errorCode: String?
    let errorMessage: String?   // Kotlin 侧成功时写 JSON null，必须可选否则整行 decode 失败
    let resultPreview: String?
    let latencyMs: Int64
    let traceId: String?        // 非 chat 来源为 null

    var id: Int64 { createdAt }
    var rawLine: String?

    private enum CodingKeys: String, CodingKey {
        case createdAt, source, kind, script, scriptLength, success
        case errorCode, errorMessage, resultPreview, latencyMs, traceId
    }
}

// MARK: - JSONL 读取

enum DiagnosticLogStore {
    static func logDirectory() -> URL? {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first?
            .appendingPathComponent("llm_log", isDirectory: true)
    }

    /// 逐行 decode JSONL；坏行跳过，读文件失败/不存在 → 空列表；每行记录附带原始行文本。
    static func load<T: Decodable & RawLineAttachable>(_ type: T.Type, fileName: String) -> [T] {
        guard let dir = logDirectory() else { return [] }
        let url = dir.appendingPathComponent(fileName)
        guard let data = try? Data(contentsOf: url),
              let text = String(data: data, encoding: .utf8) else { return [] }
        let decoder = JSONDecoder()
        var result: [T] = []
        for line in text.split(separator: "\n") {
            guard !line.trimmingCharacters(in: .whitespaces).isEmpty else { continue }
            if let lineData = String(line).data(using: .utf8),
               var record = try? decoder.decode(T.self, from: lineData) {
                record.rawLine = String(line)
                result.append(record)
            }
        }
        return result
    }

    /// 清空三个 JSONL 文件（删除后由查看器重读触发空列表）。
    static func clearAll() {
        guard let dir = logDirectory() else { return }
        for name in ["llm_calls.jsonl", "tool_calls.jsonl", "js_runs.jsonl"] {
            let url = dir.appendingPathComponent(name)
            try? FileManager.default.removeItem(at: url)
        }
    }
}

// MARK: - 时间格式化

private let listTimeFormatter: DateFormatter = {
    let f = DateFormatter()
    f.dateFormat = "MM-dd HH:mm:ss"
    return f
}()

private let detailTimeFormatter: DateFormatter = {
    let f = DateFormatter()
    f.dateFormat = "yyyy-MM-dd HH:mm:ss.SSS"
    return f
}()

// MARK: - 主视图

struct DiagnosticLogView: View {
    enum Tab: String, CaseIterable { case llm = "LLM", tool = "Tool", js = "JS" }

    @State private var tab: Tab = .llm
    @State private var llmCalls: [LlmCallRecord] = []
    @State private var toolCalls: [ToolCallRecord] = []
    @State private var jsRuns: [JsRunRecord] = []
    @State private var showClearConfirm = false

    var body: some View {
        VStack(spacing: 0) {
            Picker("", selection: $tab) {
                ForEach(Tab.allCases, id: \.self) { t in
                    Text(L(t.rawValue)).tag(t)
                }
            }
            .pickerStyle(.segmented)
            .padding(.horizontal, 12)
            .padding(.vertical, 6)

            switch tab {
            case .llm:
                logList(items: llmCalls.map { r in
                    DiagnosticLogRow(
                        success: r.success,
                        time: listTimeFormatter.string(from: Date(timeIntervalSince1970: Double(r.createdAt) / 1000)),
                        chip: r.source,
                        title: .init(text: r.model ?? "—"),
                        subtitle: Self.llmSubtitle(r)) {
                        LlmCallDetailView(record: r)
                    }
                })
            case .tool:
                logList(items: toolCalls.map { r in
                    DiagnosticLogRow(
                        success: r.success,
                        time: listTimeFormatter.string(from: Date(timeIntervalSince1970: Double(r.createdAt) / 1000)),
                        chip: r.capability,
                        title: .init(text: r.commandType),
                        subtitle: Self.toolSubtitle(r)) {
                        ToolCallDetailView(record: r)
                    }
                })
            case .js:
                logList(items: jsRuns.map { r in
                    DiagnosticLogRow(
                        success: r.success,
                        time: listTimeFormatter.string(from: Date(timeIntervalSince1970: Double(r.createdAt) / 1000)),
                        chip: r.source,
                        title: .init(text: jsTitle(r), monospaced: true),
                        subtitle: Self.jsSubtitle(r)) {
                        JsRunDetailView(record: r)
                    }
                })
            }
        }
        .background(Color(.systemGroupedBackground).ignoresSafeArea())
        .navigationTitle(L("LLM Call Log"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                HStack(spacing: 14) {
                    Button { reload() } label: {
                        Image(systemName: "arrow.clockwise")
                    }
                    .accessibilityLabel(Text(L("Refresh")))
                    Button { showClearConfirm = true } label: {
                        Image(systemName: "trash")
                    }
                    .accessibilityLabel(Text(L("Clear")))
                }
            }
        }
        .confirmationDialog(L("Clear all diagnostic logs?"), isPresented: $showClearConfirm, titleVisibility: .visible) {
            Button(L("Clear"), role: .destructive) {
                DiagnosticLogStore.clearAll()
                reload()
            }
            Button(L("Cancel"), role: .cancel) {}
        }
        .onAppear { reload() }
    }

    // MARK: - 列表

    private func logList(items: [DiagnosticLogRow]) -> some View {
        Group {
            if items.isEmpty {
                VStack(spacing: 10) {
                    Image(systemName: "doc.text.magnifyingglass")
                        .font(.system(size: 44)).foregroundColor(.secondary.opacity(0.3))
                    Text(L("No logs yet"))
                        .font(.system(size: 14))
                        .foregroundColor(.secondary)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                ScrollView {
                    LazyVStack(spacing: 8) {
                        ForEach(items) { row in
                            NavigationLink { row.detail } label: { row.labelView }
                                .buttonStyle(.plain)
                        }
                    }
                    .padding(.horizontal, 12)
                    .padding(.vertical, 6)
                }
            }
        }
    }

    // MARK: - 副标题拼装

    private static func llmSubtitle(_ r: LlmCallRecord) -> String {
        var parts = ["\(r.latencyMs ?? 0) ms"]
        if r.success, let p = r.promptTokens, let c = r.completionTokens {
            parts.append("in \(p) / out \(c)")
        }
        if !r.success {
            parts.append(errorBrief(nil, r.errorMessage))  // LLM 记录无 errorCode 字段
        }
        return parts.joined(separator: " · ")
    }

    private static func toolSubtitle(_ r: ToolCallRecord) -> String {
        var parts = ["\(r.latencyMs) ms"]
        if !r.success {
            parts.append(errorBrief(r.errorCode.map(String.init), r.errorMessage))
        }
        return parts.joined(separator: " · ")
    }

    private static func jsSubtitle(_ r: JsRunRecord) -> String {
        var parts = ["\(r.latencyMs) ms"]
        if !r.success {
            parts.append(errorBrief(r.errorCode, r.errorMessage))
        }
        return parts.joined(separator: " · ")
    }

    /// 失败时的 errorCode/errorMessage 摘要（前 40 字符）。详情页复用。
    static func errorBrief(_ code: String?, _ message: String?) -> String {
        var s = ""
        if let code, !code.isEmpty { s += "[\(code)] " }
        if let message { s += message }
        return String(s.prefix(40))
    }

    private func jsTitle(_ r: JsRunRecord) -> String {
        let firstLine = (r.script ?? "")
            .split(separator: "\n").first.map(String.init) ?? ""
        let head = String(firstLine.prefix(60))
        return "\(r.kind) \(head)"
    }

    private func reload() {
        llmCalls = DiagnosticLogStore.load(LlmCallRecord.self, fileName: "llm_calls.jsonl")
            .sorted { $0.createdAt > $1.createdAt }
        toolCalls = DiagnosticLogStore.load(ToolCallRecord.self, fileName: "tool_calls.jsonl")
            .sorted { $0.createdAt > $1.createdAt }
        jsRuns = DiagnosticLogStore.load(JsRunRecord.self, fileName: "js_runs.jsonl")
            .sorted { $0.createdAt > $1.createdAt }
    }
}

// MARK: - 列表行卡片

/// 列表行数据载荷：用于在泛型 logList 中携带 label 与 detail。
/// labelView = 卡片外观（spec list_row），detail = 点行进入的详情页。
private struct DiagnosticLogRow: View, Identifiable {
    let rowId = UUID()
    var id: UUID { rowId }
    struct Title {
        let text: String
        var monospaced: Bool = false
        init(text: String, monospaced: Bool = false) {
            self.text = text
            self.monospaced = monospaced
        }
    }

    let success: Bool
    let time: String
    let chip: String
    let title: Title
    let subtitle: String
    @ViewBuilder let detail: AnyView

    init(success: Bool, time: String, chip: String, title: Title, subtitle: String,
         @ViewBuilder detail: () -> some View) {
        self.success = success
        self.time = time
        self.chip = chip
        self.title = title
        self.subtitle = subtitle
        self.detail = AnyView(detail())
    }

    var body: some View { labelView }

    var labelView: some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: success ? "checkmark.circle.fill" : "xmark.circle.fill")
                .font(.system(size: 18))
                .foregroundColor(success ? Color(hex: 0x2E7D32) : Color(hex: 0xC62828))
                .padding(.top, 1)
            VStack(alignment: .leading, spacing: 3) {
                HStack(spacing: 8) {
                    Text(time).font(.system(size: 12, design: .monospaced)).foregroundColor(.secondary)
                    Spacer()
                    Text(chip)
                        .font(.system(size: 11, weight: .medium))
                        .foregroundColor(.accentColor)
                        .padding(.horizontal, 8).padding(.vertical, 3)
                        .background(Color.accentColor.opacity(0.12))
                        .clipShape(RoundedRectangle(cornerRadius: 8))
                }
                Text(title.text)
                    .font(.system(size: 14, weight: .medium,
                                  design: title.monospaced ? .monospaced : .default))
                    .foregroundColor(.primary)
                    .lineLimit(1)
                    .truncationMode(.tail)
                Text(subtitle)
                    .font(.system(size: 12))
                    .foregroundColor(.secondary)
                    .lineLimit(1)
            }
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color(.secondarySystemGroupedBackground))
        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
        .contentShape(Rectangle())
    }
}

private extension Color {
    /// "0xRRGGBB" 便捷初始化（仅本文件成败图标用；语义色仍走 appScheme/DesignTokens）。
    init(hex: UInt32) {
        self.init(.sRGB,
                  red: Double((hex >> 16) & 0xFF) / 255,
                  green: Double((hex >> 8) & 0xFF) / 255,
                  blue: Double(hex & 0xFF) / 255)
    }
}

// MARK: - 详情页

private struct LlmCallDetailView: View {
    let record: LlmCallRecord

    var body: some View {
        ScrollView {
            VStack(spacing: 10) {
                header(time: record.createdAt, primary: record.model ?? "—", source: record.source)
                statusLine(success: record.success, latencyMs: record.latencyMs,
                           promptTokens: record.promptTokens, completionTokens: record.completionTokens,
                           errorCode: nil, errorMessage: record.errorMessage)
                if hasContent(record.requestJson, record.responseJson) {
                    contentCard(title: L("Request"), text: prettyJson(record.requestJson))
                    contentCard(title: L("Response"), text: prettyJson(record.responseJson))
                } else {
                    releaseHintCard()
                }
                if let err = record.errorMessage, !err.isEmpty {
                    contentCard(title: L("Error"), text: err)
                }
                traceCard(record.traceId)
                // 原始文本（未格式化的 JSONL 原始行，三 Tab 统一展示）
                contentCard(title: L("Raw"), text: record.rawLine)
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 6)
        }
        .background(Color(.systemGroupedBackground).ignoresSafeArea())
        .navigationTitle(L("LLM"))
        .navigationBarTitleDisplayMode(.inline)
    }
}

private struct ToolCallDetailView: View {
    let record: ToolCallRecord

    var body: some View {
        ScrollView {
            VStack(spacing: 10) {
                header(time: record.createdAt, primary: record.commandType, source: record.capability)
                statusLine(success: record.success, latencyMs: record.latencyMs,
                           promptTokens: nil, completionTokens: nil,
                           errorCode: record.errorCode.map(String.init), errorMessage: record.errorMessage)
                if let err = record.errorMessage, !err.isEmpty {
                    contentCard(title: L("Error"), text: err)
                }
                traceCard(record.traceId)
                // 原始文本（未格式化的 JSONL 原始行，三 Tab 统一展示）
                contentCard(title: L("Raw"), text: record.rawLine)
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 6)
        }
        .background(Color(.systemGroupedBackground).ignoresSafeArea())
        .navigationTitle(L("Tool"))
        .navigationBarTitleDisplayMode(.inline)
    }
}

private struct JsRunDetailView: View {
    let record: JsRunRecord

    var body: some View {
        ScrollView {
            VStack(spacing: 10) {
                header(time: record.createdAt, primary: record.kind, source: record.source)
                statusLine(success: record.success, latencyMs: record.latencyMs,
                           promptTokens: nil, completionTokens: nil,
                           errorCode: record.errorCode, errorMessage: record.errorMessage)
                if record.script != nil || record.resultPreview != nil {
                    if let script = record.script {
                        contentCard(title: L("Script"), text: script)
                    }
                    if let preview = record.resultPreview {
                        contentCard(title: L("Result"), text: prettyJson(preview))
                    }
                } else {
                    releaseHintCard()
                }
                if let err = record.errorMessage, !err.isEmpty {
                    contentCard(title: L("Error"), text: err)
                }
                traceCard(record.traceId)
                // 原始文本（未格式化的 JSONL 原始行，三 Tab 统一展示）
                contentCard(title: L("Raw"), text: record.rawLine)
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 6)
        }
        .background(Color(.systemGroupedBackground).ignoresSafeArea())
        .navigationTitle(L("JS"))
        .navigationBarTitleDisplayMode(.inline)
    }
}

// MARK: - 详情页共享组件

private func header(time createdAt: Int64, primary: String, source: String) -> some View {
    VStack(alignment: .leading, spacing: 4) {
        Text(detailTimeFormatter.string(from: Date(timeIntervalSince1970: Double(createdAt) / 1000)))
            .font(.system(size: 16, weight: .semibold, design: .monospaced))
        HStack(spacing: 8) {
            Text(primary).font(.system(size: 14, weight: .medium))
            Text(source)
                .font(.system(size: 11, weight: .medium))
                .foregroundColor(.accentColor)
                .padding(.horizontal, 8).padding(.vertical, 3)
                .background(Color.accentColor.opacity(0.12))
                .clipShape(RoundedRectangle(cornerRadius: 8))
        }
    }
    .frame(maxWidth: .infinity, alignment: .leading)
    .padding(12)
    .background(Color(.secondarySystemGroupedBackground))
    .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
}

private func statusLine(success: Bool, latencyMs: Int64?, promptTokens: Int?, completionTokens: Int?,
                        errorCode: String?, errorMessage: String?) -> some View {
    HStack(spacing: 10) {
        Label(success ? L("Success") : L("Failed"),
              systemImage: success ? "checkmark.circle.fill" : "xmark.circle.fill")
            .font(.system(size: 13, weight: .semibold))
            .foregroundColor(success ? Color(hex: 0x2E7D32) : Color(hex: 0xC62828))
        Spacer()
        Text("\(latencyMs ?? 0) ms")
            .font(.system(size: 13, design: .monospaced))
            .foregroundColor(.secondary)
        if success, let p = promptTokens, let c = completionTokens {
            Text("in \(p) / out \(c)")
                .font(.system(size: 13, design: .monospaced))
                .foregroundColor(.secondary)
        }
        if !success {
            Text(DiagnosticLogView.errorBrief(errorCode, errorMessage))
                .font(.system(size: 13, design: .monospaced))
                .foregroundColor(Color(hex: 0xC62828))
                .lineLimit(1)
        }
    }
    .padding(.horizontal, 12).padding(.vertical, 10)
    .frame(maxWidth: .infinity)
    .background(Color(.secondarySystemGroupedBackground))
    .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
}

/// 内容卡片：标题行（titleSmall + 复制按钮）+ 等宽正文。
@ViewBuilder
private func contentCard(title: String, text: String?) -> some View {
    if let text, !text.isEmpty {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text(title).font(AppTypography.titleSmall.font)
                Spacer()
                Button {
                    UIPasteboard.general.string = text
                } label: {
                    MatIcon(name: "doc.on.doc", size: 16)
                }
                .accessibilityLabel(Text(L("Copy")))
            }
            Text(text)
                .font(.system(size: 12, design: .monospaced))
                .textSelection(.enabled)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(12)
        .background(Color(.secondarySystemGroupedBackground))
        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
    }
}

/// traceId 卡片（等宽小字，带复制）。
@ViewBuilder
private func traceCard(_ traceId: String?) -> some View {
    if let traceId, !traceId.isEmpty {
        contentCard(title: L("Trace"), text: traceId)
    }
}

/// Release 构建仅记录指标的说明卡（captureContent=false → 内容字段全 null）。
private func releaseHintCard() -> some View {
    Text(L("Release build records metrics only."))
        .font(.system(size: 13))
        .foregroundColor(.secondary)
        .multilineTextAlignment(.center)
        .frame(maxWidth: .infinity)
        .padding(.vertical, 24)
        .background(Color(.secondarySystemGroupedBackground))
        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
}

private func hasContent(_ a: String?, _ b: String?) -> Bool {
    (a?.isEmpty == false) || (b?.isEmpty == false)
}

/// 尝试 pretty-print JSON（JSONSerialization .prettyPrinted），失败原样返回。
private func prettyJson(_ raw: String?) -> String? {
    guard let raw, !raw.isEmpty else { return nil }
    if let data = raw.data(using: .utf8),
       let obj = try? JSONSerialization.jsonObject(with: data, options: [.fragmentsAllowed]),
       let pretty = try? JSONSerialization.data(withJSONObject: obj, options: [.prettyPrinted, .sortedKeys]),
       let text = String(data: pretty, encoding: .utf8) {
        return text
    }
    return raw
}
