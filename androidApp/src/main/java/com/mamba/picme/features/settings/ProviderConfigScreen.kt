package com.mamba.picme.features.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mamba.picme.R
import com.mamba.picme.agent.core.remote.config.RemoteModelConfig
import com.mamba.picme.agent.core.remote.config.RemoteModelConfigs
import com.mamba.picme.agent.core.remote.config.RemoteProtocol
import com.mamba.picme.core.designsystem.AppColors
import com.mamba.picme.core.designsystem.SettingsTokens
import com.mamba.picme.features.common.topbar.AppTopBar
import com.mamba.picme.features.common.topbar.AppTopBarNavBack
import com.mamba.picme.navigation.Screen

/**
 * 供应商配置页（2026-08-21 弹窗改页面，spec=specs/screens/settings.yaml provider_config）。
 *
 * 按 providerId 动态渲染：预置供应商展示摘要卡 + API Key + 预置模型单选 + 自定义模型 ID；
 * [Screen.ProviderConfig.CUSTOM_PROVIDER_ID] 为自定义供应商形态（额外 Base URL 输入、无预置模型）。
 * 保存路径与原 AddProviderModelDialog 完全一致：构造 [RemoteModelConfig] 后经
 * [RemoteModelConfigs.addConfig] upsert、toJson 回写 UserPreferences（uniqueKey = providerId:modelId）。
 */
@Composable
@Suppress("LongMethod")
fun ProviderConfigScreen(
    providerId: String,
    configsJson: String,
    onConfigsChange: (String) -> Unit,
    onSaved: () -> Unit,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val provider = remember(providerId) { RemoteModelConfig.getProvider(providerId) }
    val isCustomProvider = provider == null

    var apiKey by remember { mutableStateOf("") }
    var baseUrl by remember { mutableStateOf("") }
    var selectedModel by remember { mutableStateOf<String?>(null) }
    var customModelExpanded by remember { mutableStateOf(isCustomProvider) }
    var customModelId by remember { mutableStateOf("") }

    // 生效模型 ID：自定义输入优先（展开时），否则取预置单选（语义同原对话框）
    val effectiveModelId = if (customModelExpanded) customModelId.trim() else selectedModel.orEmpty()
    val canSubmit = apiKey.isNotBlank() &&
        effectiveModelId.isNotBlank() &&
        (!isCustomProvider || baseUrl.isNotBlank())

    val pageTitle = provider?.displayName ?: stringResource(R.string.custom_provider_name)

    Scaffold(
        topBar = {
            AppTopBar(
                title = { Text(pageTitle) },
                navigationIcon = { AppTopBarNavBack(onClick = onNavigateBack) }
            )
        },
        bottomBar = {
            Box(
                modifier = Modifier
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(16.dp)
            ) {
                Button(
                    onClick = {
                        val configs = if (configsJson.isBlank()) {
                            RemoteModelConfigs()
                        } else {
                            RemoteModelConfigs.fromJson(configsJson)
                        }
                        val newConfig = RemoteModelConfig(
                            modelId = effectiveModelId,
                            providerId = provider?.providerId ?: Screen.ProviderConfig.CUSTOM_PROVIDER_ID,
                            protocol = provider?.protocol ?: RemoteProtocol.OPENAI,
                            baseUrl = provider?.baseUrl ?: baseUrl.trim(),
                            apiKey = apiKey.trim()
                        )
                        onConfigsChange(RemoteModelConfigs.toJson(configs.addConfig(newConfig)))
                        onSaved()
                    },
                    enabled = canSubmit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AppColors.vibrantBlue,
                        contentColor = Color.White,
                        disabledContainerColor = AppColors.vibrantBlue.copy(alpha = 0.35f),
                        disabledContentColor = Color.White.copy(alpha = 0.8f)
                    )
                ) {
                    Text(
                        text = stringResource(R.string.add_model),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(
                    horizontal = SettingsTokens.listSectionPaddingH,
                    vertical = 8.dp
                )
        ) {
            // ── 供应商摘要卡 ──
            SettingsListSection {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(SettingsTokens.rowHeightWithSubtitle)
                        .padding(horizontal = SettingsTokens.listRowPaddingH),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(SettingsTokens.rowElementGap)
                ) {
                    if (provider != null) {
                        ProviderBadge(
                            letter = providerBadgeLetter(provider),
                            background = providerBrandBrush(provider.providerId)
                        )
                    } else {
                        ProviderBadge(letter = "+", background = providerBrandBrush(""))
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = pageTitle,
                            fontSize = SettingsTokens.listTitleFontSize.value.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = if (provider != null) {
                                stringResource(
                                    R.string.provider_summary_official,
                                    stringResource(providerProtocolNameRes(provider.protocol))
                                )
                            } else {
                                stringResource(R.string.provider_summary_custom)
                            },
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // ── API Key（自定义供应商额外 Base URL）──
            ConfigSectionLabel(text = stringResource(R.string.api_key))
            SettingsListSection {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (isCustomProvider) {
                        InsetInputField(
                            value = baseUrl,
                            onValueChange = { newValue -> baseUrl = newValue },
                            placeholder = stringResource(R.string.base_url_hint)
                        )
                    }
                    InsetInputField(
                        value = apiKey,
                        onValueChange = { newValue -> apiKey = newValue },
                        placeholder = stringResource(R.string.api_key_hint),
                        isPassword = true
                    )
                    Text(
                        text = stringResource(R.string.api_key_privacy_hint),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (provider != null && provider.apiKeyUrl.isNotBlank()) {
                        Text(
                            text = stringResource(R.string.provider_console_link, provider.displayName),
                            fontSize = 12.sp,
                            color = AppColors.vibrantBlue,
                            modifier = Modifier.clickable {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(provider.apiKeyUrl))
                                )
                            }
                        )
                    }
                }
            }

            // ── 选择模型 ──
            ConfigSectionLabel(text = stringResource(R.string.select_model_section))
            SettingsListSection {
                if (provider != null) {
                    provider.models.forEachIndexed { index, modelId ->
                        if (index > 0) {
                            SettingsListDivider()
                        }
                        PresetModelRow(
                            modelId = modelId,
                            selected = !customModelExpanded && selectedModel == modelId,
                            onClick = {
                                selectedModel = modelId
                                customModelExpanded = false
                                customModelId = ""
                            }
                        )
                    }
                    SettingsListDivider()
                    CustomModelRow(
                        expanded = customModelExpanded,
                        customModelId = customModelId,
                        onToggle = {
                            customModelExpanded = !customModelExpanded
                            if (customModelExpanded) {
                                selectedModel = null
                            }
                        },
                        onCustomModelIdChange = { newValue -> customModelId = newValue }
                    )
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp)
                    ) {
                        InsetInputField(
                            value = customModelId,
                            onValueChange = { newValue -> customModelId = newValue },
                            placeholder = stringResource(R.string.custom_model_id_subtitle)
                        )
                    }
                }
            }
        }
    }
}

/** 协议显示名（摘要卡「官方服务 · X 协议」）。 */
private fun providerProtocolNameRes(protocol: RemoteProtocol): Int = when (protocol) {
    RemoteProtocol.OPENAI -> R.string.protocol_name_openai
    RemoteProtocol.CLAUDE -> R.string.protocol_name_anthropic
}

/** 分区标签：13sp 次要色（与添加页 ProviderSectionLabel 同规范）。 */
@Composable
private fun ConfigSectionLabel(text: String) {
    Text(
        text = text,
        fontSize = 13.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 6.dp)
    )
}

/** 内凹浅色底输入框：圆角 10、无指示线、可选密码掩码。 */
@Composable
private fun InsetInputField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    isPassword: Boolean = false
) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = {
            Text(
                text = placeholder,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        singleLine = true,
        shape = RoundedCornerShape(10.dp),
        visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent
        ),
        modifier = Modifier.fillMaxWidth()
    )
}

/** 预置模型单选行：标题 + 一句说明，选中项右侧蓝色 ✓。 */
@Composable
private fun PresetModelRow(
    modelId: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(SettingsTokens.rowHeightWithSubtitle)
            .clickable(onClick = onClick)
            .padding(start = SettingsTokens.listRowPaddingH, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SettingsTokens.rowElementGap)
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = modelId,
                fontSize = SettingsTokens.listTitleFontSize.value.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = stringResource(R.string.preset_model_desc),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (selected) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = null,
                tint = AppColors.vibrantBlue,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/** 「自定义模型 ID」行：chevron 展开内联输入框。 */
@Composable
private fun CustomModelRow(
    expanded: Boolean,
    customModelId: String,
    onToggle: () -> Unit,
    onCustomModelIdChange: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(SettingsTokens.rowHeightWithSubtitle)
                .clickable(onClick = onToggle)
                .padding(start = SettingsTokens.listRowPaddingH, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SettingsTokens.rowElementGap)
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = stringResource(R.string.custom_model_id_title),
                    fontSize = SettingsTokens.listTitleFontSize.value.sp,
                    fontWeight = if (expanded) FontWeight.SemiBold else FontWeight.Normal,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = stringResource(R.string.custom_model_id_subtitle),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(
                imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = SettingsTokens.rowChevronAlpha),
                modifier = Modifier.size(SettingsTokens.rowChevronSize)
            )
        }
        if (expanded) {
            Box(modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
                InsetInputField(
                    value = customModelId,
                    onValueChange = onCustomModelIdChange,
                    placeholder = stringResource(R.string.model_id)
                )
            }
        }
    }
}
