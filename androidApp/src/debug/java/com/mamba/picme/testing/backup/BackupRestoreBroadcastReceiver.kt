package com.mamba.picme.testing.backup

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.mamba.picme.PoLangApplication
import com.mamba.picme.core.common.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 备份/恢复脚本化入口（仅 debug 构建）。
 *
 * 配合 `scripts/app-data-backup.sh` 使用，替代已退役的 AgentTestBroadcastReceiver
 * （ADR-011）中的 backup_tag_data / restore_tag_data 两个命令：
 *
 * ```
 * am broadcast -a com.mamba.picme.AGENT_TEST \
 *   -n com.mamba.picme/.testing.backup.BackupRestoreBroadcastReceiver \
 *   --es json '{"method":"backup_tag_data","params":{"path":"..."}}'
 * ```
 *
 * 协议约定：
 * - 请求：extra `json` 为 `{"method":"backup_tag_data|restore_tag_data","params":{"path":...,"dryRun":bool}}`
 * - 完成标志：操作结束后在 `<备份文件>.result.json` 写入结果 JSON，脚本轮询该文件
 * - release 包不包含本 receiver，release 走应用内 SAF（设置 → 备份与恢复）
 */
class BackupRestoreBroadcastReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_AGENT_TEST) return

        val rawJson = intent.getStringExtra(EXTRA_JSON) ?: run {
            Logger.w(TAG, "Missing '$EXTRA_JSON' extra, ignored")
            return
        }

        val (method, params) = try {
            val json = JSONObject(rawJson)
            json.getString("method") to json.optJSONObject("params")
        } catch (e: Exception) {
            Logger.w(TAG, "Malformed command json: $rawJson", e)
            return
        }

        val app = context.applicationContext as PoLangApplication
        val customFile = params?.optString("path")?.takeIf { it.isNotBlank() }?.let { File(it) }
        // 结果文件路径与脚本约定一致：<备份文件>.result.json，成功/失败都写这里
        val backupFile = customFile ?: app.container.backupTagDataUseCase.defaultBackupFile()

        when (method) {
            METHOD_BACKUP -> handleAsync(context, backupFile, method) {
                val result = app.container.backupTagDataUseCase(customFile)
                JSONObject().apply {
                    put("type", "cmd_result")
                    put("cmd", method)
                    put("status", "success")
                    put("path", result.file.absolutePath)
                    put("tagCount", result.tagCount)
                    put("mediaCount", result.mediaCount)
                    put("crossRefCount", result.crossRefCount)
                    put("scanTaskCount", result.scanTaskCount)
                    put("personCount", result.personCount)
                    put("faceEmbeddingCount", result.faceEmbeddingCount)
                    put("ocrWordCount", result.ocrWordCount)
                    put("ocrWordOccurrenceCount", result.ocrWordOccurrenceCount)
                    put("locationCount", result.locationCount)
                    put("mediaLocationCount", result.mediaLocationCount)
                    put("mediaFeedbackCount", result.mediaFeedbackCount)
                    put("preferenceCount", result.preferenceCount)
                    put("chatSessionCount", result.chatSessionCount)
                    put("chatMessageCount", result.chatMessageCount)
                    put("personRelationCount", result.personRelationCount)
                    put("memoryFactCount", result.memoryFactCount)
                    put("photoEditRecipeCount", result.photoEditRecipeCount)
                }
            }

            METHOD_RESTORE -> handleAsync(context, backupFile, method) {
                val dryRun = params?.optBoolean("dryRun", false) ?: false
                val result = app.container.restoreTagDataUseCase(customFile, dryRun)
                JSONObject().apply {
                    put("type", "cmd_result")
                    put("cmd", method)
                    put("status", "success")
                    put("dryRun", dryRun)
                    put("matchedMediaCount", result.matchedMediaCount)
                    put("unmatchedMediaCount", result.unmatchedUris.size)
                    put("restoredTagCount", result.restoredTagCount)
                    put("restoredCrossRefCount", result.restoredCrossRefCount)
                    put("restoredScanTaskCount", result.restoredScanTaskCount)
                    put("restoredMetadataCount", result.restoredMetadataCount)
                    put("restoredPersonCount", result.restoredPersonCount)
                    put("restoredFaceEmbeddingCount", result.restoredFaceEmbeddingCount)
                    put("restoredOcrWordCount", result.restoredOcrWordCount)
                    put("restoredOcrWordOccurrenceCount", result.restoredOcrWordOccurrenceCount)
                    put("restoredLocationCount", result.restoredLocationCount)
                    put("restoredMediaLocationCount", result.restoredMediaLocationCount)
                    put("restoredMediaFeedbackCount", result.restoredMediaFeedbackCount)
                    put("restoredPreferenceCount", result.restoredPreferenceCount)
                    put("restoredChatSessionCount", result.restoredChatSessionCount)
                    put("restoredChatMessageCount", result.restoredChatMessageCount)
                    put("restoredPersonRelationCount", result.restoredPersonRelationCount)
                    put("restoredMemoryFactCount", result.restoredMemoryFactCount)
                    put("restoredPhotoEditRecipeCount", result.restoredPhotoEditRecipeCount)
                    put("unmatchedUris", JSONArray(result.unmatchedUris))
                }
            }

            else -> Logger.w(TAG, "Unsupported method: $method")
        }
    }

    /**
     * goAsync + 协程执行备份/恢复，完成后将结果写入 [backupFile].result.json；
     * 异常时同样写入（status=error），避免脚本轮询超时后才察觉失败。
     */
    private fun handleAsync(
        context: Context,
        backupFile: File,
        method: String,
        block: suspend () -> JSONObject
    ) {
        val pendingResult = goAsync()
        scope.launch {
            try {
                val resultJson = block()
                writeOperationResult(backupFile, resultJson.toString())
                Logger.i(TAG, "$method succeeded: ${resultJson.toString().take(200)}...")
            } catch (e: Exception) {
                Logger.e(TAG, "$method failed", e)
                val errorJson = JSONObject().apply {
                    put("type", "cmd_result")
                    put("cmd", method)
                    put("status", "error")
                    put("message", e.message ?: e.javaClass.simpleName)
                }
                writeOperationResult(backupFile, errorJson.toString())
            } finally {
                pendingResult.finish()
            }
        }
    }

    /**
     * 将操作结果写入备份文件同目录的 *.result.json，便于 PC 端脚本轮询检测完成状态。
     */
    private fun writeOperationResult(backupFile: File, resultJson: String) {
        try {
            val resultFile = File(backupFile.parentFile, backupFile.name + ".result.json")
            resultFile.parentFile?.mkdirs()
            resultFile.writeText(resultJson)
            Logger.i(TAG, "Operation result written to ${resultFile.absolutePath}")
        } catch (e: Exception) {
            Logger.w(TAG, "Failed to write operation result file", e)
        }
    }

    companion object {
        private const val TAG = "BackupRestoreReceiver"
        private const val ACTION_AGENT_TEST = "com.mamba.picme.AGENT_TEST"
        private const val EXTRA_JSON = "json"
        private const val METHOD_BACKUP = "backup_tag_data"
        private const val METHOD_RESTORE = "restore_tag_data"

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
