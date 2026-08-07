package com.mamba.picme.domain.backup

import android.content.Context
import com.mamba.picme.domain.backup.TagDataBackupRepository.RestoreResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 从 JSON 备份文件还原 TAG 数据。
 *
 * 默认读取 [BackupTagDataUseCase] 生成的默认路径；
 * 跨安装还原时建议通过 adb pull/push 将备份文件放到对应目录，或传入自定义路径。
 */
class RestoreTagDataUseCase(
    private val context: Context,
    private val repository: TagDataBackupRepository,
    private val backupTagDataUseCase: BackupTagDataUseCase
) {
    suspend operator fun invoke(
        customFile: File? = null,
        dryRun: Boolean = false
    ): RestoreResult = withContext(Dispatchers.IO) {
        val file = customFile ?: backupTagDataUseCase.defaultBackupFile()
        repository.importFromFile(file, dryRun)
    }
}
