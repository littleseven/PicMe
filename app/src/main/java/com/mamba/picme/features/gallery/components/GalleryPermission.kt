package com.mamba.picme.features.gallery.components

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.mamba.picme.R
import kotlin.random.Random

fun hasGalleryPermission(context: Context): Boolean {
    return galleryReadPermissions().all { permission ->
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }
}

fun galleryReadPermissions(): Array<String> {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO
        )
    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    } else {
        arrayOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        )
    }
}

@Composable
fun GalleryPermissionMessage(
    onGrantPermission: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Default.PhotoLibrary,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.size(20.dp))
            Text(
                text = stringResource(R.string.gallery_permission_title),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.size(8.dp))
            Text(
                text = stringResource(R.string.gallery_permission_desc),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.size(24.dp))
            Button(
                onClick = onGrantPermission,
                modifier = Modifier.padding(horizontal = 16.dp)
            ) {
                Text(text = stringResource(R.string.grant_permissions))
            }
        }
    }
}

@Composable
fun EmptyGalleryMessage(message: String? = null) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = message ?: stringResource(R.string.no_media),
            style = MaterialTheme.typography.bodyLarge
        )
    }
}

/**
 * 相册暖启动占位页，代替 [EmptyGalleryMessage] 在冷启动时显示。
 *
 * 在 Room 查询返回前，展示与系统语言匹配的名人格言（含作者），
 * 避免"未找到任何媒体文件"闪烁。数据加载完成后自动过渡到相册内容。
 */
@Composable
fun GallerySplashPlaceholder() {
    val locale = LocalConfiguration.current.locales[0]
    // 按系统/应用语言选取对应格言池；locale 作为 key，语言切换时重新取池。
    val pool = remember(locale) { getQuotesForLocale(locale) }
    // 关键修复：用 rememberSaveable 持久化下标，使其跨 Activity 重建保持稳定。
    // 启动时若持久化的应用语言（DataStore 异步加载）与 StateFlow 初始值 SYSTEM 不同，
    // MainActivity 的 LaunchedEffect(appLanguage) 会触发 recreate() 以应用语言；
    // 普通 remember 在重建后重新随机，会导致"格言刷两次且内容不同"的闪烁。
    // 以 pool.size 作为输入：仅在语言切换导致池容量变化时才重新抽取。
    val quoteIndex = rememberSaveable(pool.size) {
        Random.nextInt(pool.size)
    }
    val randomQuote = pool[quoteIndex % pool.size]

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .padding(horizontal = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(
                space = 16.dp,
                alignment = Alignment.Top
            )
        ) {
            // 顶部留白，将格言推至约屏幕 30% 位置，比正中更有呼吸感
            Spacer(modifier = Modifier.fillMaxHeight(0.28f))

            // 格言正文 — 衬线字体，更具艺术感
            Text(
                text = "\u201C${randomQuote.text}\u201D",
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontFamily = FontFamily.Serif,
                    fontStyle = FontStyle.Italic,
                    fontWeight = FontWeight.Normal,
                    fontSize = 20.sp,
                    lineHeight = 30.sp
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            // 作者 / 出处
            Text(
                text = "\u2014 ${randomQuote.author}",
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.Serif,
                    fontStyle = FontStyle.Normal,
                    fontWeight = FontWeight.Medium,
                    fontSize = 15.sp,
                    lineHeight = 22.sp
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                textAlign = TextAlign.Center
            )
        }
    }
}
