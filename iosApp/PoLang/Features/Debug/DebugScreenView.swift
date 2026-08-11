import SwiftUI

// MARK: - 图片下载页（对标 Android DebugScreen：Batch Generate + Pexels 两 tab）
//
// #if DEBUG 调试工具，入口在 DeveloperSettings → Image Download。

struct DebugScreenView: View {
    @State private var tab: Int = 0

    var body: some View {
        VStack(spacing: 0) {
            Picker("", selection: $tab) {
                Text(L("Batch Generate")).tag(0)
                Text(L("Pexels Gallery")).tag(1)
            }
            .pickerStyle(.segmented)
            .padding(.horizontal, 12)
            .padding(.vertical, 8)

            if tab == 0 {
                BatchGenerateTab()
            } else {
                PexelsDownloadView()
            }
        }
        .navigationTitle(L("Image Download"))
        .navigationBarTitleDisplayMode(.inline)
    }
}

// MARK: - Batch Generate tab（对标 Android GenerateTabContent）

private struct BatchGenerateTab: View {
    @StateObject private var gen = SampleDataGenerator()
    @State private var grep: String = ""

    private let cols = [GridItem(.flexible(), spacing: 10), GridItem(.flexible(), spacing: 10)]

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                if gen.isGenerating {
                    statusCard
                }
                Text(L("Data Generation"))
                    .font(.system(size: 15, weight: .semibold))
                LazyVGrid(columns: cols, spacing: 10) {
                    genButton(L("Person"), "person.fill", .blue) { gen.populatePerson() }
                    genButton(L("Landscape"), "mountain.2.fill", .green) { gen.populateLandscape() }
                    genButton(L("Swimwear"), "water.waves", .teal) { gen.populateSwimwear() }
                    genButton(L("Sexy"), "heart.fill", .pink) { gen.populateSexy() }
                }
                Button(role: .destructive) { gen.clearTestData() } label: {
                    Label(L("Clear Test Data"), systemImage: "trash")
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 8)
                }
                .buttonStyle(.bordered)
                .disabled(gen.isGenerating)

                Divider().padding(.vertical, 2)
                logWindow
            }
            .padding(12)
        }
    }

    private var statusCard: some View {
        VStack(spacing: 10) {
            HStack(spacing: 10) {
                ProgressView()
                Text(gen.progress.isEmpty ? L("Pause") : gen.progress)
                    .font(.system(size: 13))
                    .lineLimit(2)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            HStack(spacing: 10) {
                Button { if gen.isPaused { gen.resumeGen() } else { gen.pause() } } label: {
                    Label(gen.isPaused ? L("Resume") : L("Pause"), systemImage: gen.isPaused ? "play.fill" : "pause.fill")
                        .frame(maxWidth: .infinity).padding(.vertical, 6)
                }
                .buttonStyle(.bordered)
                Button(role: .destructive) { gen.stop() } label: {
                    Label(L("Stop"), systemImage: "stop.fill")
                        .frame(maxWidth: .infinity).padding(.vertical, 6)
                }
                .buttonStyle(.bordered)
            }
        }
        .padding(12)
        .background(RoundedRectangle(cornerRadius: 12).fill(Color(.secondarySystemGroupedBackground)))
    }

    private func genButton(_ title: String, _ icon: String, _ tint: Color, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(spacing: 6) {
                Image(systemName: icon).font(.system(size: 22)).foregroundColor(tint)
                Text(title).font(.system(size: 13, weight: .medium)).foregroundColor(.primary)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 16)
            .background(RoundedRectangle(cornerRadius: 12).fill(Color(.secondarySystemGroupedBackground)))
        }
        .buttonStyle(.plain)
        .disabled(gen.isGenerating)
    }

    private var logWindow: some View {
        VStack(alignment: .leading, spacing: 6) {
            TextField(L("Grep logs..."), text: $grep)
                .textFieldStyle(.roundedBorder)
                .font(.system(size: 13))
            let filtered = grep.isEmpty ? gen.logs : gen.logs.filter { $0.localizedCaseInsensitiveContains(grep) }
            ScrollView {
                VStack(alignment: .leading, spacing: 2) {
                    ForEach(Array(filtered.enumerated()), id: \.offset) { _, line in
                        Text(line)
                            .font(.system(size: 10, design: .monospaced))
                            .foregroundColor(logColor(line))
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                }
            }
            .frame(height: 220)
            .padding(6)
            .background(RoundedRectangle(cornerRadius: 8).fill(Color(.tertiarySystemBackground)))
        }
    }

    private func logColor(_ line: String) -> Color {
        if line.contains("Saved") { return .green }
        if line.contains("Error") || line.contains("failed") || line.contains("HTTP") { return .red }
        return .secondary
    }
}
