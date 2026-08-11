import Foundation

// MARK: - Pexels API（对标 Android PexelsApi.kt / PexelsModels.kt）
//
// 两个 GET：search（关键词）/ curated（精选）。Authorization header = 用户输入的 key。
// 下载用 photos[].src.large2x；网格缩略图用 photos[].src.medium。next_page==null 表示到底。
// 免费额度 200/小时、20000/月；401→key 无效、429→额度用尽。

struct PexelsPhoto: Codable, Identifiable, Equatable, Hashable {
    let id: Int64
    let width: Int?
    let height: Int?
    let photographer: String?
    let alt: String?
    let src: Src

    struct Src: Codable, Equatable, Hashable {
        let medium: String?
        let large2x: String?
    }
}

struct PexelsResponse: Codable {
    let photos: [PexelsPhoto]?
    /// Pexels 返回 next_page 下一页 URL；nil/空 = 到底。
    let nextPage: String?

    enum CodingKeys: String, CodingKey {
        case photos
        case nextPage = "next_page"
    }
}

enum PexelsError: Error, Equatable {
    case unauthorized   // 401：key 无效
    case rateLimited    // 429：额度用尽
    case network        // 其他
}

enum PexelsApi {
    static let perPage = 30
    private static let base = "https://api.pexels.com/v1/"

    /// 关键词搜索。
    static func search(query: String, page: Int, apiKey: String) async throws -> PexelsResponse {
        guard var comp = URLComponents(string: base + "search") else { throw PexelsError.network }
        comp.queryItems = [
            URLQueryItem(name: "query", value: query),
            URLQueryItem(name: "page", value: String(page)),
            URLQueryItem(name: "per_page", value: String(perPage)),
        ]
        guard let url = comp.url else { throw PexelsError.network }
        return try await get(url, apiKey: apiKey)
    }

    /// 精选流（query 为空时用）。
    static func curated(page: Int, apiKey: String) async throws -> PexelsResponse {
        guard var comp = URLComponents(string: base + "curated") else { throw PexelsError.network }
        comp.queryItems = [
            URLQueryItem(name: "page", value: String(page)),
            URLQueryItem(name: "per_page", value: String(perPage)),
        ]
        guard let url = comp.url else { throw PexelsError.network }
        return try await get(url, apiKey: apiKey)
    }

    private static func get(_ url: URL, apiKey: String) async throws -> PexelsResponse {
        var req = URLRequest(url: url, timeoutInterval: 15)
        req.setValue(apiKey, forHTTPHeaderField: "Authorization")
        let (data, resp): (Data, URLResponse)
        do {
            (data, resp) = try await URLSession.shared.data(for: req)
        } catch {
            throw PexelsError.network
        }
        guard let http = resp as? HTTPURLResponse else { throw PexelsError.network }
        switch http.statusCode {
        case 200..<300: break
        case 401: throw PexelsError.unauthorized
        case 429: throw PexelsError.rateLimited
        default: throw PexelsError.network
        }
        do {
            return try JSONDecoder().decode(PexelsResponse.self, from: data)
        } catch {
            throw PexelsError.network
        }
    }
}
