package com.mamba.picme.features.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import android.widget.Toast
import com.mamba.picme.R
import com.mamba.picme.data.download.DownloadState
import com.mamba.picme.data.download.DownloadStatus
import com.mamba.picme.data.download.ModelConfig
import com.mamba.picme.domain.model.DetectionModelType
import com.mamba.picme.domain.model.DetectionStage
import com.mamba.picme.domain.model.InferenceDevicePreference
import com.mamba.picme.domain.model.StageConfig

@Composable
internal fun StageConfigSection(
    stage: DetectionStage,
    config: StageConfig,
    onModelTypeSelected: (DetectionModelType) -> Unit,
    onDevicePreferenceSelected: (InferenceDevicePreference) -> Unit,
    onNavigateToModelManager: (String) -> Unit,
    isModelDownloaded: (DetectionModelType) -> Boolean,
    getModelId: (DetectionModelType, DetectionStage) -> String?,
    downloadModel: (String, ModelConfig) -> Unit,
    downloadStates: Map<String, DownloadState>,
    allModels: List<ModelConfig>
) {
    val context = LocalContext.current
    val title = when (stage) {
        DetectionStage.ROI -> stringResource(R.string.stage_roi_title)
        DetectionStage.LANDMARK -> stringResource(R.string.stage_landmark_title)
    }
    val description = when (stage) {
        DetectionStage.ROI -> stringResource(R.string.stage_roi_desc)
        DetectionStage.LANDMARK -> stringResource(R.string.stage_landmark_desc)
    }

    SettingsSection(
        title = title,
        description = description
    ) {
        Text(
            text = stringResource(R.string.model_type),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 12.dp, top = 4.dp, bottom = 2.dp)
        )
        ModelTypeSelection(
            currentType = config.modelType,
            stage = stage,
            onTypeSelected = onModelTypeSelected,
            isModelDownloaded = isModelDownloaded,
            getModelId = getModelId,
            downloadModel = downloadModel,
            downloadStates = downloadStates,
            allModels = allModels
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.inference_device_preference),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 12.dp, top = 4.dp, bottom = 2.dp)
        )
        InferenceDevicePreferenceSelection(
            currentPreference = config.devicePreference,
            onPreferenceSelected = onDevicePreferenceSelected
        )

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = { onNavigateToModelManager("Vision") })
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = stringResource(R.string.model_center),
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = stringResource(R.string.model_center_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = Icons.Outlined.CloudDownload,
                contentDescription = null,
                modifier = Modifier
                    .size(22.dp)
                    .padding(start = 4.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModelTypeSelection(
    currentType: DetectionModelType,
    stage: DetectionStage,
    onTypeSelected: (DetectionModelType) -> Unit,
    isModelDownloaded: (DetectionModelType) -> Boolean,
    getModelId: (DetectionModelType, DetectionStage) -> String?,
    downloadModel: (String, ModelConfig) -> Unit,
    downloadStates: Map<String, DownloadState>,
    allModels: List<ModelConfig>
) {
    val context = LocalContext.current

    val options = when (stage) {
        DetectionStage.ROI -> listOf(
            DetectionModelType.MEDIAPIPE to stringResource(R.string.model_mediapipe),
            DetectionModelType.DET_500M_MNN to stringResource(R.string.model_det10g_mnn)
        )
        DetectionStage.LANDMARK -> listOf(
            DetectionModelType.MEDIAPIPE to stringResource(R.string.model_mediapipe),
            DetectionModelType.FACE_2D106_MNN to stringResource(R.string.model_2d106_mnn)
        )
    }

    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        maxLines = 2
    ) {
        options.forEach { (modelType, label) ->
            val downloaded = isModelDownloaded(modelType)
            val isMediaPipe = modelType == DetectionModelType.MEDIAPIPE
            val modelId = getModelId(modelType, stage)
            val downloadState = modelId?.let { downloadStates[it] }
            val isDownloading = downloadState?.status == DownloadStatus.DOWNLOADING
            val downloadProgress = if (isDownloading && downloadState.totalBytes > 0) {
                (downloadState.downloadedBytes.toFloat() / downloadState.totalBytes * 100).toInt()
            } else 0

            FilterChip(
                selected = modelType == currentType,
                onClick = {
                    if (downloaded || isMediaPipe) {
                        onTypeSelected(modelType)
                    } else if (!isDownloading) {
                        val mId = modelId
                        val modelConfig = mId?.let { id -> allModels.find { it.id == id } }
                        if (modelConfig != null && mId != null) {
                            downloadModel(mId, modelConfig)
                            Toast.makeText(
                                context,
                                "开始下载 ${modelConfig.name}，下载完成后自动生效",
                                Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            Toast.makeText(
                                context,
                                "模型配置未找到，请先进入模型管理页面下载",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                },
                label = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodySmall
                        )
                        when {
                            isDownloading -> {
                                Text(
                                    text = "${downloadProgress}%",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            !downloaded && !isMediaPipe -> {
                                Icon(
                                    imageVector = Icons.Outlined.CloudDownload,
                                    contentDescription = "未下载",
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                ),
                enabled = !isDownloading
            )
        }
    }
}

@Composable
private fun InferenceDevicePreferenceSelection(
    currentPreference: InferenceDevicePreference,
    onPreferenceSelected: (InferenceDevicePreference) -> Unit
) {
    val options = listOf(
        InferenceDevicePreference.AUTO to stringResource(R.string.device_preference_auto),
        InferenceDevicePreference.FORCE_CPU to stringResource(R.string.device_preference_force_cpu),
        InferenceDevicePreference.FORCE_GPU to stringResource(R.string.device_preference_force_gpu)
    )

    CompactOptionChips(
        options = options,
        currentValue = currentPreference,
        maxLines = 1,
        onSelected = onPreferenceSelected
    )
}
