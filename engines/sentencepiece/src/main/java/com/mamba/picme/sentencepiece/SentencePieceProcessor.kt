package com.mamba.picme.sentencepiece

/**
 * SentencePiece JNI 包装器，用于 Android 端加载 .spm 模型并进行编码/解码。
 *
 * 对应 native 库: libsentencepiece_android.so
 *
 * 使用示例:
 * ```kotlin
 * val tokenizer = SentencePieceProcessor()
 * tokenizer.loadModel("/path/to/source.spm")
 * val ids = tokenizer.encode("你好世界")
 * val text = tokenizer.decode(ids)
 * ```
 */
class SentencePieceProcessor {

    private var nativeHandle: Long = 0

    /**
     * 加载 SentencePiece 模型文件 (.spm)
     *
     * @param modelPath 模型文件绝对路径
     * @throws IllegalStateException 如果模型加载失败
     */
    fun loadModel(modelPath: String) {
        nativeHandle = nativeLoadModel(modelPath)
        if (nativeHandle == 0L) {
            throw IllegalStateException("Failed to load SentencePiece model: $modelPath")
        }
    }

    /**
     * 将文本编码为 token ID 数组
     *
     * @param text 输入文本
     * @return token ID 数组
     * @throws IllegalStateException 如果模型未加载
     */
    fun encode(text: String): IntArray {
        check(nativeHandle != 0L) { "Model not loaded. Call loadModel() first." }
        return nativeEncode(nativeHandle, text)
    }

    /**
     * 将文本编码为 token 字符串数组（pieces）
     *
     * @param text 输入文本
     * @return token 字符串数组
     * @throws IllegalStateException 如果模型未加载
     */
    fun encodeAsPieces(text: String): Array<String> {
        check(nativeHandle != 0L) { "Model not loaded. Call loadModel() first." }
        return nativeEncodeAsPieces(nativeHandle, text)
    }

    /**
     * 将 token ID 数组解码为文本
     *
     * @param ids token ID 数组
     * @return 解码后的文本
     * @throws IllegalStateException 如果模型未加载
     */
    fun decode(ids: IntArray): String {
        check(nativeHandle != 0L) { "Model not loaded. Call loadModel() first." }
        return nativeDecode(nativeHandle, ids)
    }

    /**
     * 获取词表大小
     *
     * @return 词表大小
     * @throws IllegalStateException 如果模型未加载
     */
    fun vocabSize(): Int {
        check(nativeHandle != 0L) { "Model not loaded. Call loadModel() first." }
        return nativeVocabSize(nativeHandle)
    }

    /**
     * 根据 ID 获取 token 字符串
     *
     * @param id token ID
     * @return token 字符串
     * @throws IllegalStateException 如果模型未加载
     */
    fun idToPiece(id: Int): String {
        check(nativeHandle != 0L) { "Model not loaded. Call loadModel() first." }
        return nativeIdToPiece(nativeHandle, id)
    }

    /**
     * 根据 token 字符串获取 ID
     *
     * @param piece token 字符串
     * @return token ID，如果未找到则返回 -1
     * @throws IllegalStateException 如果模型未加载
     */
    fun pieceToId(piece: String): Int {
        check(nativeHandle != 0L) { "Model not loaded. Call loadModel() first." }
        return nativePieceToId(nativeHandle, piece)
    }

    /**
     * 释放 native 资源
     */
    @Synchronized
    fun close() {
        if (nativeHandle != 0L) {
            nativeClose(nativeHandle)
            nativeHandle = 0
        }
    }

    protected fun finalize() {
        close()
    }

    // ── Native 方法 ─────────────────────────────────────────

    private external fun nativeLoadModel(modelPath: String): Long
    private external fun nativeEncode(handle: Long, text: String): IntArray
    private external fun nativeEncodeAsPieces(handle: Long, text: String): Array<String>
    private external fun nativeDecode(handle: Long, ids: IntArray): String
    private external fun nativeVocabSize(handle: Long): Int
    private external fun nativeIdToPiece(handle: Long, id: Int): String
    private external fun nativePieceToId(handle: Long, piece: String): Int
    private external fun nativeClose(handle: Long)

    companion object {
        init {
            System.loadLibrary("sentencepiece_android")
        }
    }
}
