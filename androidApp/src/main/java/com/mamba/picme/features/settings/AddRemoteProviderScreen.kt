package com.mamba.picme.features.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mamba.picme.R
import com.mamba.picme.agent.core.remote.config.RemoteModelConfig
import com.mamba.picme.agent.core.remote.config.RemoteModelConfigs
import com.mamba.picme.agent.core.remote.config.RemoteModelProvider
import com.mamba.picme.core.designsystem.AppColors
import com.mamba.picme.core.designsystem.SettingsTokens
import com.mamba.picme.features.common.topbar.AppTopBar
import com.mamba.picme.features.common.topbar.AppTopBarNavBack
import com.mamba.picme.navigation.Screen

/**
 * 添加远程模型 — 供应商列表页（2026-08-21 弹窗改页面，spec=specs/screens/settings.yaml add_remote_provider）。
 *
 * 分区一「已接入」：数据源为 shared 侧 [RemoteModelConfig.PROVIDERS]（SSOT，双端共享）；
 * 分区二「更多供应商」：未接入厂商静态展示（不可点击）；底部独立卡片为自定义供应商入口。
 * 「已配置」胶囊由 UserPreferences 中已有 RemoteModelConfigs 判定（providerId 命中且 isConfigured）。
 */
@Composable
@Suppress("LongMethod")
fun AddRemoteProviderScreen(
    configsJson: String,
    onNavigateBack: () -> Unit,
    onProviderSelected: (String) -> Unit
) {
    val configuredProviderIds = remember(configsJson) {
        if (configsJson.isBlank()) {
            emptySet()
        } else {
            RemoteModelConfigs.fromJson(configsJson).configs
                .filter { config -> config.isConfigured }
                .map { config -> config.providerId }
                .toSet()
        }
    }
    val providers = RemoteModelConfig.PROVIDERS.filter { provider -> provider.isVisible }

    Scaffold(
        topBar = {
            AppTopBar(
                title = { Text(stringResource(R.string.add_remote_model_title)) },
                navigationIcon = { AppTopBarNavBack(onClick = onNavigateBack) }
            )
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
            ProviderSectionLabel(text = stringResource(R.string.provider_section_connected))
            SettingsListSection {
                providers.forEachIndexed { index, provider ->
                    if (index > 0) {
                        SettingsListDivider()
                    }
                    ConnectedProviderRow(
                        provider = provider,
                        configured = configuredProviderIds.contains(provider.providerId),
                        onClick = { onProviderSelected(provider.providerId) }
                    )
                }
            }

            ProviderSectionLabel(text = stringResource(R.string.provider_section_more))
            SettingsListSection {
                FUTURE_PROVIDERS.forEachIndexed { index, future ->
                    if (index > 0) {
                        SettingsListDivider()
                    }
                    FutureProviderRow(future = future)
                }
            }

            Spacer(modifier = Modifier.height(SettingsTokens.listSectionSpacing))
            SettingsListSection {
                CustomProviderRow(
                    onClick = { onProviderSelected(Screen.ProviderConfig.CUSTOM_PROVIDER_ID) }
                )
            }
        }
    }
}

/** 分区标签：13sp 次要色，起始缩进与卡片内文字对齐。 */
@Composable
private fun ProviderSectionLabel(text: String) {
    Text(
        text = text,
        fontSize = 13.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 6.dp)
    )
}

/** 已接入供应商行：品牌徽章 + 名称 + 预置模型摘要 + 可选「已配置」胶囊 + chevron。 */
@Composable
private fun ConnectedProviderRow(
    provider: RemoteModelProvider,
    configured: Boolean,
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
        ProviderBadge(
            letter = providerBadgeLetter(provider),
            background = providerBrandBrush(provider.providerId)
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = provider.displayName,
                fontSize = SettingsTokens.listTitleFontSize.value.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = stringResource(
                    R.string.provider_preset_models_summary,
                    provider.models.take(2).joinToString(" · "),
                    provider.models.size
                ),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (configured) {
            ProviderTag(
                text = stringResource(R.string.remote_model_configured),
                textColor = ConfiguredGreen,
                backgroundColor = ConfiguredGreen.copy(alpha = 0.12f)
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = SettingsTokens.rowChevronAlpha),
            modifier = Modifier.size(SettingsTokens.rowChevronSize)
        )
    }
}

/** 未接入供应商行：静态展示 + 灰色「即将支持」胶囊，无 chevron、不可点击。 */
@Composable
private fun FutureProviderRow(future: FutureProvider) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(SettingsTokens.rowHeightWithSubtitle)
            .padding(start = SettingsTokens.listRowPaddingH, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SettingsTokens.rowElementGap)
    ) {
        ProviderBadge(
            letter = future.letter,
            background = future.badgeBrush,
            fontSizeSp = future.letterFontSizeSp
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = stringResource(future.nameRes),
                fontSize = SettingsTokens.listTitleFontSize.value.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = stringResource(future.subtitleRes),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        ProviderTag(
            text = stringResource(R.string.provider_coming_soon),
            textColor = MaterialTheme.colorScheme.onSurfaceVariant,
            backgroundColor = MaterialTheme.colorScheme.surfaceVariant
        )
    }
}

/** 底部独立卡片：自定义供应商入口（绿色 + 徽章）。 */
@Composable
private fun CustomProviderRow(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(SettingsTokens.rowHeightWithSubtitle)
            .clickable(onClick = onClick)
            .padding(start = SettingsTokens.listRowPaddingH, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SettingsTokens.rowElementGap)
    ) {
        Box(
            modifier = Modifier
                .size(SettingsTokens.listIconBlockSize)
                .clip(RoundedCornerShape(ProviderBadgeRadius))
                .background(AppColors.vibrantGreen),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.Add,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(SettingsTokens.listIconInnerSize)
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = stringResource(R.string.custom_provider_name),
                fontSize = SettingsTokens.listTitleFontSize.value.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = stringResource(R.string.custom_provider_subtitle),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = SettingsTokens.rowChevronAlpha),
            modifier = Modifier.size(SettingsTokens.rowChevronSize)
        )
    }
}

/** 状态小胶囊（已配置 / 即将支持）。 */
@Composable
private fun ProviderTag(
    text: String,
    textColor: Color,
    backgroundColor: Color
) {
    Box(
        modifier = Modifier
            .height(20.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(backgroundColor)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontSize = 11.sp,
            color = textColor
        )
    }
}

/** 供应商字母徽章：28dp 圆角块 + 白色字母（供添加页与配置页共用）。 */
@Composable
internal fun ProviderBadge(
    letter: String,
    background: Brush,
    fontSizeSp: Int = 14
) {
    Box(
        modifier = Modifier
            .size(SettingsTokens.listIconBlockSize)
            .clip(RoundedCornerShape(ProviderBadgeRadius))
            .background(background),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = letter,
            fontSize = fontSizeSp.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White
        )
    }
}

internal val ProviderBadgeRadius = 8.dp

/** 「已配置」绿（#0B9E4A，12% 透明度底）。 */
internal val ConfiguredGreen = Color(0xFF0B9E4A)

/** 已接入供应商品牌色（设计稿映射，与列表页旧徽章色并存、互不影响）。 */
internal fun providerBrandColor(providerId: String): Color {
    val id = providerId.lowercase()
    return when {
        id.startsWith("openai") -> Color(0xFF000000)
        id.startsWith("anthropic") -> Color(0xFFD97757)
        id.startsWith("deepseek") -> Color(0xFF4D6BFE)
        id.startsWith("kimi") || id.startsWith("moonshot") -> Color(0xFF4F378B)
        id.startsWith("tencent") -> Color(0xFF0052D9)
        else -> Color(0xFF938F99)
    }
}

internal fun providerBrandBrush(providerId: String): Brush =
    SolidColor(providerBrandColor(providerId))

/** 徽章字母：Kimi 取 Moonshot 的 M，TokenHub 取 T，其余取名称首字母。 */
internal fun providerBadgeLetter(provider: RemoteModelProvider): String {
    val id = provider.providerId.lowercase()
    return when {
        id.startsWith("kimi") || id.startsWith("moonshot") -> "M"
        id.startsWith("tencent") -> "T"
        else -> provider.displayName.first().uppercase()
    }
}

/** 未接入供应商静态条目（「更多供应商」分区）。 */
private data class FutureProvider(
    @StringRes val nameRes: Int,
    @StringRes val subtitleRes: Int,
    val letter: String,
    val badgeBrush: Brush,
    val letterFontSizeSp: Int = 14
)

private val FUTURE_PROVIDERS = listOf(
    FutureProvider(
        nameRes = R.string.provider_gemini_name,
        subtitleRes = R.string.provider_gemini_subtitle,
        letter = "G",
        badgeBrush = Brush.linearGradient(listOf(Color(0xFF4B8BF5), Color(0xFF9B72F2)))
    ),
    FutureProvider(
        nameRes = R.string.provider_qwen_name,
        subtitleRes = R.string.provider_qwen_subtitle,
        letter = "Q",
        badgeBrush = SolidColor(Color(0xFF7147E8))
    ),
    FutureProvider(
        nameRes = R.string.provider_zhipu_name,
        subtitleRes = R.string.provider_zhipu_subtitle,
        letter = "Z",
        badgeBrush = SolidColor(Color(0xFF3B5BFD))
    ),
    FutureProvider(
        nameRes = R.string.provider_xai_name,
        subtitleRes = R.string.provider_xai_subtitle,
        letter = "X",
        badgeBrush = SolidColor(Color(0xFF202124))
    ),
    FutureProvider(
        nameRes = R.string.provider_mistral_name,
        subtitleRes = R.string.provider_mistral_subtitle,
        letter = "M",
        badgeBrush = SolidColor(Color(0xFFFF7000))
    ),
    FutureProvider(
        nameRes = R.string.provider_openrouter_name,
        subtitleRes = R.string.provider_openrouter_subtitle,
        letter = "OR",
        badgeBrush = SolidColor(Color(0xFF6566F1)),
        letterFontSizeSp = 9
    )
)
