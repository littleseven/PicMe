import Foundation
import UIKit
import Photos

// MARK: - SampleDataGenerator（对标 Android SampleDataGenerator.kt）
//
// 调试批量生成测试图片：Baidu 图片搜索(acjson) + Weibo(m.weibo.cn) → 正则挖 URL →
// 下载(重试/退避/≥5KB) → 内容+皮肤比例分析(CGImage 像素) → 保存到 Photos(随机拍摄时间)。
// 清除：按保存时记录的 localIdentifier 批量删除（Android 按 TEST_ 文件名前缀）。
// #if DEBUG 调试工具，对标 Android DebugScreen 的 Batch Generate tab。

struct ImageCandidate: Sendable { let url: String; let source: String }
struct ContentAnalysis { let isValidContent: Bool; let skinRatio: Float }

@MainActor
final class SampleDataGenerator: ObservableObject {

    @Published private(set) var isGenerating = false
    @Published private(set) var isPaused = false
    @Published private(set) var progress = ""
    @Published private(set) var logs: [String] = []

    // MARK: - 关键词表（对标 Android，逐字照搬）
    private static let starNames: [String] = [
        "杨幂", "迪丽热巴", "古力娜扎", "关晓彤", "虞书欣", "赵露思", "白鹿", "鞠婧祎", "刘亦菲", "赵丽颖",
        "倪妮", "刘诗诗", "景甜", "柳岩", "徐冬冬", "张雨绮", "钟楚曦", "李沁", "王楚然", "周也", "张婧仪",
        "孟子义", "金晨", "乔欣", "谭松韵", "张天爱", "林志玲", "高圆圆", "江疏影", "唐嫣", "佟丽娅", "辛芷蕾",
        "宋茜", "毛晓彤", "李一桐", "白冰", "曾黎", "张俪", "周雨彤", "宋轶", "郭碧婷", "文咏珊", "吴谨言",
        "秦岚", "王丽坤", "舒淇", "安以轩", "陈乔恩", "林依晨", "陈都灵", "章若楠", "田曦薇", "王佳怡",
    ]
    private static let landscapeKeywords: [String] = [
        "雪山", "草原", "森林", "大海", "星空", "沙漠", "秋色", "雨林", "冰川", "极光",
        "瀑布", "湖泊", "峡谷", "梯田", "海岛", "晚霞", "日出", "湿地", "溶洞", "戈壁",
        "丹霞", "枫林", "花海", "向日葵", "竹林", "古村", "园林", "海滩", "礁石", "悬崖",
        "云海", "绿洲", "雾凇", "冰湖", "古堡", "灯塔", "断桥", "稻田", "荷塘", "郁金香",
        "银杏", "繁星", "晨曦", "夕阳", "平原", "火山", "泉水", "红叶", "翠竹", "山川",
    ]
    private static let swimwearKeywords: [String] = [
        "泳装写真 高清", "比基尼 4k 摄影", "超模 泳装 唯美", "性感 泳衣 大片",
        "三点式 摄影 写真", "沙滩 泳装 气质", "杂志 泳装 封面", "尤物 泳装 4k",
        "时尚 泳装 模特", "亚洲模特 泳装写真", "车展模特 泳装", "维密写真 泳装",
    ]
    private static let sexyKeywords: [String] = [
        "性感 礼服大片", "气质写真 高清", "吊带写真 诱惑", "大长腿 气质 摄影",
        "深V 写真 唯美", "尤物写真 高清", "私房写真 性感", "甜辣写真 少女",
        "时尚大片 性感", "人体艺术 唯美 摄影", "艺术写真 4k", "气质女神 性感",
    ]
    private static let userAgents: [String] = [
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
        "Mozilla/5.0 (iPhone; CPU iPhone OS 17_3_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.2 Mobile/15E148 Safari/604.1",
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.6261.64 Mobile Safari/537.36",
    ]
    private static let testIdsUD = "test_asset_localidentifiers"

    private nonisolated static func randomUA() -> String {
        userAgents[Int.random(in: 0..<userAgents.count)]
    }

    // MARK: - 日志 / 控制

    private func addLog(_ message: String) {
        let df = DateFormatter(); df.dateFormat = "HH:mm:ss"
        let time = df.string(from: Date())
        logs.insert("[\(time)] \(message)", at: 0)
        if logs.count > 200 { logs.removeLast() }
        NSLog("PoLang:SampleGen %@", message)
    }

    func pause() { isPaused = true; progress = L("Pause"); addLog("Action: Paused") }
    func resumeGen() { isPaused = false; addLog("Action: Resumed") }
    func stop() { isGenerating = false; isPaused = false; progress = L("Stop"); addLog("Action: Stopped") }

    // MARK: - 入口（4 类）

    func populatePerson() { Task { await generateData(keywords: Self.starNames, prefix: "TEST_PERSON") } }
    func populateLandscape() { Task { await generateData(keywords: Self.landscapeKeywords, prefix: "TEST_LANDSCAPE") } }
    func populateSwimwear() {
        let expanded = Self.starNames.shuffled().prefix(15).map { "\($0) 泳装" } + Self.swimwearKeywords
        Task { await generateData(keywords: expanded, prefix: "TEST_SWIMWEAR") }
    }
    func populateSexy() {
        let expanded = Self.starNames.shuffled().prefix(15).map { "\($0) 性感" } + Self.sexyKeywords
        Task { await generateData(keywords: expanded, prefix: "TEST_SEXY") }
    }

    // MARK: - 生成主循环（对标 generateData）

    private struct Outcome: Sendable { let saved: Bool; let source: String }
    private var downloadedCount = 0

    private func generateData(keywords: [String], prefix: String) async {
        if isGenerating && !isPaused { return }
        isGenerating = true; isPaused = false
        downloadedCount = 0
        addLog("Starting generation for \(prefix)...")
        var attempts: [String: Int] = [:]
        var success: [String: Int] = [:]
        let target = 10
        let sem = AsyncSemaphore(2)

        for keyword in keywords {
            if !isGenerating { break }
            while isPaused && isGenerating { try? await Task.sleep(nanoseconds: 500_000_000) }
            if !isGenerating { break }
            progress = keyword
            let candidates = await Self.searchImagesParallel(keyword: keyword,
                                                             isLandscape: prefix == "TEST_LANDSCAPE")
            await withTaskGroup(of: Outcome?.self) { group in
                for candidate in candidates {
                    if downloadedCount >= target || !isGenerating { break }
                    group.addTask { [weak self] in
                        guard let self else { return nil }
                        await sem.wait()
                        let out = await self.processOne(candidate, prefix: prefix, keyword: keyword, target: target)
                        await sem.signal()
                        return out
                    }
                }
                for await out in group {
                    guard let out else { continue }
                    attempts[out.source, default: 0] += 1
                    if out.saved { success[out.source, default: 0] += 1 }
                }
            }
        }

        var summary = "Round Quality Report:\n"
        for ch in attempts.keys.sorted(by: { Self.sourceOrder($0) < Self.sourceOrder($1) }) {
            let att = attempts[ch] ?? 0, succ = success[ch] ?? 0
            let rate = att > 0 ? succ * 100 / att : 0
            summary += " - \(ch.uppercased()): \(succ)/\(att) (\(rate)%)\n"
        }
        addLog(summary)
        isGenerating = false; isPaused = false; progress = ""
        addLog("Finished generation")
    }

    /// 单候选：下载→分析→保存。cap 检查与计数在 MainActor 同步段内，原子。
    private func processOne(_ candidate: ImageCandidate, prefix: String, keyword: String, target: Int) async -> Outcome {
        try? await Task.sleep(nanoseconds: UInt64(1_000_000_000 + Int.random(in: 0..<2_000_000_000)))
        guard isGenerating else { return Outcome(saved: false, source: candidate.source) }
        guard let data = await Self.downloadWithRetry(candidate.url) else { return Outcome(saved: false, source: candidate.source) }
        guard let image = UIImage(data: data) else { return Outcome(saved: false, source: candidate.source) }
        let analysis = Self.analyze(image)
        guard analysis.isValidContent else { return Outcome(saved: false, source: candidate.source) }
        if (prefix == "TEST_SWIMWEAR" || prefix == "TEST_SEXY") && analysis.skinRatio < 10.0 {
            return Outcome(saved: false, source: candidate.source)
        }
        guard isGenerating, downloadedCount < target else { return Outcome(saved: false, source: candidate.source) }
        let captureDate = Date().addingTimeInterval(-Double.random(in: 0...(180 * 86400)))
        if let localId = await Self.saveToPhotos(image, creationDate: captureDate) {
            Self.trackTestAsset(localId)
            downloadedCount += 1
            progress = "\(keyword) (\(downloadedCount)/\(target))"
            addLog("Saved to album [\(candidate.source.uppercased())]")
            return Outcome(saved: true, source: candidate.source)
        }
        return Outcome(saved: false, source: candidate.source)
    }

    // MARK: - 清除测试数据

    func clearTestData() {
        let ids = UserDefaults.standard.stringArray(forKey: Self.testIdsUD) ?? []
        guard !ids.isEmpty else { addLog("Action: No test data to clear"); return }
        Task {
            let assets = PHAsset.fetchAssets(withLocalIdentifiers: ids, options: nil)
            var n = 0
            assets.enumerateObjects { _, _, _ in n += 1 }
            do {
                try await PHPhotoLibrary.shared().performChanges {
                    PHAssetChangeRequest.deleteAssets(assets)
                }
                UserDefaults.standard.removeObject(forKey: Self.testIdsUD)
                await MainActor.run { self.addLog("Action: Cleared test data (\(n) assets)") }
            } catch {
                let msg = error.localizedDescription
                await MainActor.run { self.addLog("Clear failed: \(msg)") }
            }
        }
    }

    private static func trackTestAsset(_ localId: String) {
        var ids = UserDefaults.standard.stringArray(forKey: testIdsUD) ?? []
        ids.append(localId)
        UserDefaults.standard.set(ids, forKey: testIdsUD)
    }

    private static func sourceOrder(_ s: String) -> Int {
        switch s {
        case "duitang": return 0; case "xiuren": return 1; case "tuchong": return 2
        case "metcn": return 3; case "metart": return 4; case "500px": return 5
        case "unsplash": return 6; case "natgeo": return 7; case "xiaohongshu": return 8
        case "huaban": return 9; case "weibo": return 10; default: return 11
        }
    }

    // MARK: - 搜索（Baidu / Weibo，非隔离静态）

    private nonisolated static func searchImagesParallel(keyword: String, isLandscape: Bool) async -> [ImageCandidate] {
        async let dbaidu = searchBaidu(keyword)
        if isLandscape {
            async let dNatGeo = searchBaidu("site:nationalgeographic.com \(keyword)")
            async let dUnsplash = searchBaidu("site:unsplash.com \(keyword)")
            async let dPexels = searchBaidu("site:pexels.com \(keyword)")
            let natgeo = await dNatGeo.map { ImageCandidate(url: $0, source: "natgeo") }
            let unsplash = await dUnsplash.map { ImageCandidate(url: $0, source: "unsplash") }
            let pexels = await dPexels.map { ImageCandidate(url: $0, source: "pexels") }
            let baidu = await dbaidu.map { ImageCandidate(url: $0, source: "baidu") }
            return natgeo + unsplash + pexels + baidu.shuffled()
        }
        async let dDuitang = searchBaidu("site:duitang.com \(keyword)")
        async let dXiuren = searchBaidu("site:xiuren.org \(keyword)")
        async let dTuchong = searchBaidu("site:tuchong.com \(keyword)")
        async let dMetCn = searchBaidu("site:metcn.com \(keyword)")
        async let dMetArt = searchBaidu("site:met-art.com \(keyword)")
        async let d500 = searchBaidu("site:500px.com \(keyword)")
        async let dXhs = searchBaidu("site:xiaohongshu.com \(keyword)")
        async let dHuaban = searchBaidu("site:huaban.com \(keyword)")
        async let dWeibo = searchWeibo(keyword)
        let duitang = await dDuitang.map { ImageCandidate(url: $0, source: "duitang") }
        let xiuren = await dXiuren.map { ImageCandidate(url: $0, source: "xiuren") }
        let tuchong = await dTuchong.map { ImageCandidate(url: $0, source: "tuchong") }
        let metcn = await dMetCn.map { ImageCandidate(url: $0, source: "metcn") }
        let metart = await dMetArt.map { ImageCandidate(url: $0, source: "metart") }
        let p500 = await d500.map { ImageCandidate(url: $0, source: "500px") }
        let xhs = await dXhs.map { ImageCandidate(url: $0, source: "xiaohongshu") }
        let huaban = await dHuaban.map { ImageCandidate(url: $0, source: "huaban") }
        let weibo = await dWeibo.map { ImageCandidate(url: $0, source: "weibo") }
        let baidu = await dbaidu.map { ImageCandidate(url: $0, source: "baidu") }
        return duitang + xiuren + tuchong + metcn + metart + p500 + xhs + huaban + weibo + baidu.shuffled()
    }

    private nonisolated static func extractUrls(_ text: String) -> [String] {
        let pattern = #"https?:[\\/]+[^"\\\s]+?(?:\.jpg|\.jpeg|\.png|sinaimg|bdimg)[^"\\\s]*"#
        guard let re = try? NSRegularExpression(pattern: pattern) else { return [] }
        let nsText = text as NSString
        let matches = re.matches(in: text, range: NSRange(location: 0, length: nsText.length))
        return matches.compactMap { m -> String? in
            let s = nsText.substring(with: m.range)
            return s.replacingOccurrences(of: "\\/", with: "/")
        }
    }

    private nonisolated static func searchBaidu(_ keyword: String) async -> [String] {
        guard let encoded = keyword.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) else { return [] }
        guard let url = URL(string: "https://image.baidu.com/search/acjson?tn=resultjson_com&ipn=rj&ct=201326592&word=\(encoded)&rn=30") else { return [] }
        var req = URLRequest(url: url, timeoutInterval: 15)
        req.setValue(randomUA(), forHTTPHeaderField: "User-Agent")
        req.setValue("https://image.baidu.com/", forHTTPHeaderField: "Referer")
        guard let (data, resp) = try? await URLSession.shared.data(for: req),
              let http = resp as? HTTPURLResponse, http.statusCode == 200,
              let text = String(data: data, encoding: .utf8) ?? String(data: data, encoding: .isoLatin1) else { return [] }
        return extractUrls(text)
    }

    private nonisolated static func searchWeibo(_ keyword: String) async -> [String] {
        guard let encoded = keyword.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) else { return [] }
        guard let url = URL(string: "https://m.weibo.cn/api/container/getIndex?containerid=100103type%3D1%26q%3D\(encoded)") else { return [] }
        var req = URLRequest(url: url, timeoutInterval: 15)
        req.setValue(randomUA(), forHTTPHeaderField: "User-Agent")
        req.setValue("XMLHttpRequest", forHTTPHeaderField: "X-Requested-With")
        guard let (data, resp) = try? await URLSession.shared.data(for: req),
              let http = resp as? HTTPURLResponse, http.statusCode == 200,
              let text = String(data: data, encoding: .utf8) ?? String(data: data, encoding: .isoLatin1) else { return [] }
        return extractUrls(text)
            .map { $0.replacingOccurrences(of: "thumbnail", with: "large").replacingOccurrences(of: "orj360", with: "large") }
    }

    // MARK: - 下载（重试/退避/≥5KB）

    private nonisolated static func downloadWithRetry(_ urlStr: String) async -> Data? {
        var delayNs: UInt64 = 2_000_000_000
        let maxRetries = 2
        for attempt in 0..<maxRetries {
            if let result = await downloadAndValidate(urlStr) { return result }
            if attempt < maxRetries - 1 {
                try? await Task.sleep(nanoseconds: delayNs)
                delayNs *= 2
            }
        }
        return nil
    }

    private nonisolated static func downloadAndValidate(_ urlStr: String) async -> Data? {
        guard let url = URL(string: urlStr) else { return nil }
        var req = URLRequest(url: url, timeoutInterval: 10)
        req.setValue(randomUA(), forHTTPHeaderField: "User-Agent")
        req.setValue("image/avif,image/webp,image/apng,image/*,*/*;q=0.8", forHTTPHeaderField: "Accept")
        let host = url.host ?? ""
        let referer: String
        if host.contains("sinaimg") { referer = "https://weibo.com/" }
        else if host.contains("baidu") { referer = "https://image.baidu.com/" }
        else { referer = "https://\(host)/" }
        req.setValue(referer, forHTTPHeaderField: "Referer")
        guard let (data, resp) = try? await URLSession.shared.data(for: req),
              let http = resp as? HTTPURLResponse, http.statusCode == 200, data.count >= 5120 else { return nil }
        return data
    }

    // MARK: - 内容+皮肤分析（对标 analyzeContentAndSkin，CGImage 像素）

    private nonisolated static func analyze(_ image: UIImage) -> ContentAnalysis {
        guard let cg = image.cgImage else { return ContentAnalysis(isValidContent: false, skinRatio: 0) }
        let maxDim: CGFloat = 400
        let scale = min(1, maxDim / max(image.size.width, image.size.height))
        let w = max(1, Int(image.size.width * scale))
        let h = max(1, Int(image.size.height * scale))
        guard let ctx = CGContext(data: nil, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w * 4,
                                  space: CGColorSpaceCreateDeviceRGB(),
                                  bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else {
            return ContentAnalysis(isValidContent: false, skinRatio: 0)
        }
        ctx.draw(cg, in: CGRect(x: 0, y: 0, width: w, height: h))
        guard let buf = ctx.data else { return ContentAnalysis(isValidContent: false, skinRatio: 0) }
        let totalPixels = w * h
        let ptr = buf.assumingMemoryBound(to: UInt8.self)
        var skinPixels = 0
        var totalBrightness: Double = 0
        var rSum: Double = 0
        var reds = [UInt8](repeating: 0, count: totalPixels)
        for i in 0..<totalPixels {
            let r = ptr[i * 4], g = ptr[i * 4 + 1], b = ptr[i * 4 + 2]
            totalBrightness += Double(r) * 0.299 + Double(g) * 0.587 + Double(b) * 0.114
            rSum += Double(r)
            reds[i] = r
            if r > 95 && g > 40 && b > 20 && abs(Int(r) - Int(g)) > 15 && r > g && r > b { skinPixels += 1 }
        }
        let avgR = rSum / Double(totalPixels)
        let sampleSize = max(1, totalPixels / 100)
        var variance = 0.0
        var sampled = 0
        var i = 0
        while i < totalPixels {
            let dr = Double(reds[i]) - avgR
            variance += dr * dr
            sampled += 1
            i += sampleSize
        }
        let stdDev = (sampled > 0) ? (variance / Double(sampled)).squareRoot() : 0
        let avgBrightness = totalBrightness / Double(totalPixels)
        let valid = avgBrightness > 20.0 && stdDev > 5.0
        let skinRatio = Float(skinPixels) / Float(totalPixels) * 100
        return ContentAnalysis(isValidContent: valid, skinRatio: skinRatio)
    }

    // MARK: - 存 Photos（随机拍摄时间）

    private nonisolated static func saveToPhotos(_ image: UIImage, creationDate: Date) async -> String? {
        do {
            var createdId: String?
            try await PHPhotoLibrary.shared().performChanges {
                let req = PHAssetChangeRequest.creationRequestForAsset(from: image)
                req.creationDate = creationDate
                createdId = req.placeholderForCreatedAsset?.localIdentifier
            }
            return createdId
        } catch {
            NSLog("PoLang:SampleGen save failed: %@", error.localizedDescription)
            return nil
        }
    }
}

// MARK: - 简易异步信号量（Semaphore(2) 并发下载）
actor AsyncSemaphore {
    private var permits: Int
    private var pending: [CheckedContinuation<Void, Never>] = []
    init(_ n: Int) { permits = n }
    func wait() async {
        if permits > 0 { permits -= 1; return }
        await withCheckedContinuation { (c: CheckedContinuation<Void, Never>) in pending.append(c) }
    }
    func signal() {
        if let next = pending.first { pending.removeFirst(); next.resume() }
        else { permits += 1 }
    }
}
