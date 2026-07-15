package com.mamba.picme.domain.backup

import android.content.Context
import com.mamba.picme.domain.backup.TagDataBackupRepository.ExportResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 备份 TAG 数据与用户偏好到应用内部存储。
 *
 * 默认路径：/data/data/<package>/files/backups/tag_data_backup.json
 * 包含 DataStore 中的账号、Token、设置等用户偏好。
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
        // 使用外部媒体目录（/sdcard/Android/media/<package>/PicMeBackup/）。
        // 该目录属于应用自身存储区域，无需额外权限即可读写，且 adb 可以直接 pull/push，
        // 因此同时支持 debug 与 release 包。
        val baseDir = File(context.externalMediaDirs.firstOrNull() ?: File(context.filesDir, "backups"), "PicMeBackup")
        return File(baseDir, "tag_data_backup.json")
    }
}
