import SwiftUI
import WebKit

/// CHART 图卡：WKWebView 渲染 SVG 字符串（对齐 Android `ChartSvgCard` + AndroidSVG）。
/// 点击全屏预览。SVG 来自 `ChartJsEngine`（chart_bootstrap.js 生成，含 width/height）。
struct ChartSvgCard: View {
    let svg: String
    @State private var showPreview = false

    var body: some View {
        ChartWebView(svg: svg)
            .frame(height: 220)
            .accessibilityIdentifier("chat_chart_card")
            .onTapGesture { showPreview = true }
            .sheet(isPresented: $showPreview) {
                VStack {
                    ChartWebView(svg: svg)
                    Button(String(localized: "Close")) { showPreview = false }
                        .padding()
                }
            }
    }
}

private struct ChartWebView: UIViewRepresentable {
    let svg: String
    func makeUIView(context: Context) -> WKWebView {
        let wv = WKWebView()
        wv.scrollView.isScrollEnabled = false
        wv.isOpaque = false
        wv.backgroundColor = .clear
        // svg 已含 width/height；居中自适应容器宽度
        let html = "<html><head><meta name='viewport' content='width=device-width, initial-scale=1'></head>" +
                   "<body style='margin:0;display:flex;justify-content:center'>\(svg)</body></html>"
        wv.loadHTMLString(html, baseURL: nil)
        return wv
    }
    func updateUIView(_: WKWebView, context _: Context) {}
}
