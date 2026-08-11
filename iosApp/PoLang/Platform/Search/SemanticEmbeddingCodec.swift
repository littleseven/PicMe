import Foundation

// MARK: - SemanticEmbeddingCodec（双端契约 SSOT: contracts.md §5.5 / §14 R6）
//
// semanticEmbedding 列存储格式（TEXT，不是 blob）：
//   FloatArray(512) → 每个 float32 按**大端序（MSB 先写）**写 4 字节 → 共 2048 字节
//   → Base64.NO_WRAP（无换行）字符串。
//
// 解码校验（照抄 Android `SemanticSearchEngine.kt:394-428`）：
//   Base64 解码 → 字节数 % 4 != 0 拒绝 → float 数 != 512 拒绝 → 按大端组 32-bit int 后还原 float。
//
// ⚠️ R6：iOS 写入侧（MobileCLIP 图像路径，Pass1Pipeline）必须经本 codec 编码，
// 与 Android 逐字节一致，否则双端备份互导/同库共读时语义搜索静默失效。

/// semanticEmbedding Base64（大端 float32×512）编解码器。纯函数，无状态。
enum SemanticEmbeddingCodec {

    /// 向量维度（契约 §5.3 `EMBEDDING_DIM = 512`；Android `SEMANTIC_EMBEDDING_DIM`）。
    static let dimension = 512

    /// 编码：[Float] → 大端 float32 字节流 → Base64（无换行）。
    /// - Parameter vector: 512 维向量；维度不符返回 nil（契约：float 数 != 512 拒绝）。
    /// - Returns: Base64.NO_WRAP 字符串（iOS `Data.base64EncodedString()` 默认即无换行）。
    static func encode(_ vector: [Float]) -> String? {
        guard vector.count == dimension else { return nil }
        var data = Data()
        data.reserveCapacity(dimension * 4)
        for value in vector {
            // bitPattern 取 IEEE-754 位级表示，bigEndian 转大端字节序（MSB 先写）。
            var bits = value.bitPattern.bigEndian
            withUnsafeBytes(of: &bits) { data.append(contentsOf: $0) }
        }
        return data.base64EncodedString()
    }

    /// 解码：Base64 → 大端 float32 字节流 → [Float]。
    /// 校验照抄 Android：Base64 解码失败拒绝；字节数 % 4 != 0 拒绝；float 数 != 512 拒绝。
    /// - Parameter base64String: semanticEmbedding 列原始字符串；nil/空白返回 nil。
    static func decode(_ base64String: String?) -> [Float]? {
        guard let s = base64String, !s.trimmingCharacters(in: .whitespaces).isEmpty else { return nil }
        guard let bytes = Data(base64Encoded: s) else { return nil }
        guard bytes.count % 4 == 0 else { return nil }
        let floatCount = bytes.count / 4
        guard floatCount == dimension else { return nil }

        return bytes.withUnsafeBytes { raw -> [Float] in
            var result = [Float]()
            result.reserveCapacity(floatCount)
            for i in 0..<floatCount {
                let b0 = UInt32(raw[i * 4])
                let b1 = UInt32(raw[i * 4 + 1])
                let b2 = UInt32(raw[i * 4 + 2])
                let b3 = UInt32(raw[i * 4 + 3])
                let bits = (b0 << 24) | (b1 << 16) | (b2 << 8) | b3
                result.append(Float(bitPattern: bits))
            }
            return result
        }
    }
}
