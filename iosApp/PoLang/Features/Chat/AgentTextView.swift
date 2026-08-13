import SwiftUI
import SharedKit

/// Agent 文本富渲染：用 commonMain `MarkdownSegmenter` 分段（MARKDOWN / TABLE / CODE），
/// 各段原生 SwiftUI 渲染。对齐 Android `SegmentedAgentText` + `AgentTable` + `CodeBlock`。
struct AgentTextView: View {
    let content: String

    var body: some View {
        let segments = segmentMarkdown(content: content)
        return VStack(alignment: .leading, spacing: 6) {
            ForEach(segments.indices, id: \.self) { i in
                let seg = segments[i]
                switch seg.type {
                case .table:
                    AgentTableView(raw: seg.text)
                case .code:
                    CodeBlockView(raw: seg.text)
                default:
                    MarkdownText(text: seg.text)
                }
            }
        }
    }
}

/// 代码块：折叠/展开 + 复制。代码体由 commonMain `extractCodeBody` 提取（去首尾围栏行）。
struct CodeBlockView: View {
    let raw: String
    @State private var expanded = false

    var body: some View {
        let code = extractCodeBody(raw: raw)
        let total = Int(codeLineCount(code: code))
        let collapsible = total > 3
        let shown = (expanded || !collapsible) ? code : previewCode(code: code, limit: 3)
        return VStack(alignment: .leading, spacing: 4) {
            Text(shown)
                .font(.system(size: 12, design: .monospaced))
                .foregroundColor(Color(.label))
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(8)
                .background(Color(.secondarySystemBackground))
                .cornerRadius(6)
                .accessibilityIdentifier("chat_code_block")
            HStack(spacing: 12) {
                if collapsible {
                    Button(expanded ? String(localized: "Collapse") : "\(total) \(String(localized: "lines"))") {
                        expanded.toggle()
                    }
                }
                Button(String(localized: "Copy")) { UIPasteboard.general.string = code }
            }
            .font(.system(size: 11))
            .foregroundColor(Color(.secondaryLabel))
        }
    }
}

/// 表格：commonMain `parseMarkdownTable` → 表头 + 数据行 → SwiftUI 网格（表头加粗）。
struct AgentTableView: View {
    let raw: String

    var body: some View {
        let table = parseMarkdownTable(raw: raw)
        return VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 0) {
                ForEach(table.header.indices, id: \.self) { c in
                    Text(table.header[c])
                        .bold()
                        .font(.system(size: 12))
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(6)
                }
            }
            .background(Color(.secondarySystemBackground))
            ForEach(table.rows.indices, id: \.self) { r in
                let row = table.rows[r]
                HStack(spacing: 0) {
                    ForEach(row.indices, id: \.self) { c in
                        Text(row[c])
                            .font(.system(size: 12))
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(6)
                    }
                }
                .background(Color(.tertiarySystemBackground))
            }
        }
        .cornerRadius(6)
        .overlay(RoundedRectangle(cornerRadius: 6).stroke(Color(.separator), lineWidth: 0.5))
    }
}
