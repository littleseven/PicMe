package com.mamba.picme.domain.backup

import android.content.Context
import android.os.Environment
import com.mamba.picme.domain.backup.TagDataBackupRepository.ExportResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 备份 TAG 数据到外部可访问文件。
 *
 * 默认路径：/sdcard/Documents/PicMe/tag_data_backup.json
 *（Android 10 以下可直接写入；Android 10+ 建议通过系统文件选择器或 adb 指定自定义路径。）
 */
class BackupTagDataUseCase(
    private val context: Context,
    private val repository: TagDataBackupRepository
) {
    suspend operator fun invoke(customFile: File? = null): ExportResult = withContext(Dispatchers.IO) {
        val file = customFile ?: defaultBackupFile()
        repository.exportToFile(file)
    }

    fun defaultBackupFile(): File {
        val baseDir = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            File(context.getExternalFilesDir(null), "backups")
        } else {
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "PicMe")
        }
        return File(baseDir, "tag_data_backup.json")
    }
}
