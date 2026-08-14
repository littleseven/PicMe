import Foundation
import JavaScriptCore

/// CHART 图表生成器：加载 `chart_bootstrap.js`（定义全局 `Chart.bar/line/pie/timeline`），
/// JavaScriptCore eval 生成 SVG。对齐 Android `ChartJs`（QuickJS + chart_bootstrap.js）。
///
/// chart_bootstrap.js 自包含（纯 JS 拼 SVG 字符串，**不需 gallery handler / bridge**），
/// 故本引擎独立实现（Swift + JSCore），不走 commonMain JsEngine——draw_chart 数据来自
/// 远程 LLM（结构化 labels/values），不取端侧相册数据。
enum ChartJsEngine {
    private static let context: JSContext? = {
        guard let ctx = JSContext() else {
            NSLog("[PoLang:ChartJs] JSContext alloc failed")
            return nil
        }
        ctx.exceptionHandler = { _, exception in
            NSLog("[PoLang:ChartJs] %@", exception?.description ?? "unknown")
        }
        if let url = Bundle.main.url(forResource: "chart_bootstrap", withExtension: "js", subdirectory: "js"),
           let js = try? String(contentsOf: url, encoding: .utf8) {
            ctx.evaluateScript(js)
        } else if let url = Bundle.main.url(forResource: "chart_bootstrap", withExtension: "js"),
                  let js = try? String(contentsOf: url, encoding: .utf8) {
            ctx.evaluateScript(js)
        } else {
            NSLog("[PoLang:ChartJs] chart_bootstrap.js not found in bundle")
        }
        return ctx
    }()

    struct Result {
        let svg: String
        let summary: String
    }

    /// 渲染图表：type(bar/line/pie) + title/labels/values/unit → {svg, summary}。
    /// 对齐 Android `onDrawChart`: `eval("Chart.{fn}({args})")` 取 chart/summary。
    static func render(type: String, title: String, labels: [String], values: [Double], unit: String?) -> Result? {
        guard let ctx = context else { return nil }
        var args: [String: Any] = [
            "title": title,
            "labels": labels,
            "values": values,
        ]
        if let unit { args["unit"] = unit }
        guard JSONSerialization.isValidJSONObject(args),
              let data = try? JSONSerialization.data(withJSONObject: args),
              let jsonStr = String(data: data, encoding: .utf8) else { return nil }
        guard let res = ctx.evaluateScript("Chart.\(type)(\(jsonStr))") else { return nil }
        let svg = res.objectForKeyedSubscript("chart")?.toString() ?? ""
        let summary = res.objectForKeyedSubscript("summary")?.toString() ?? ""
        return svg.isEmpty ? nil : Result(svg: svg, summary: summary)
    }
}
