package com.mamba.picme.domain.agent.capability.optimize

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.InputStream

/**
 * 打开图片输入流。
 *
 * content:// 与 file:// 走 [Context.getContentResolver]；无 scheme 的裸绝对路径
 * （chat 附件持久化格式，/data/user/0/.../picme_images/img_xxx.jpg）直接走文件系统——
 * 裸路径交给 ContentResolver 会抛 "No content provider"（真机抽卡整体降级的根因）。
 *
 * 文件不存在时抛 [java.io.FileNotFoundException]，由调用方统一 catch 记录。
 */
internal fun openImageInputStream(context: Context, imageUri: String): InputStream? {
    val uri = Uri.parse(imageUri)
    return if (uri.scheme.isNullOrBlank()) {
        File(imageUri).inputStream()
    } else {
        context.contentResolver.openInputStream(uri)
    }
}
