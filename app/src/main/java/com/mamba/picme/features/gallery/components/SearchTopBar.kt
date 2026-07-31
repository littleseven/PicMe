package com.mamba.picme.features.gallery.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mamba.picme.R
import com.mamba.picme.features.common.SearchField
import com.mamba.picme.features.common.topbar.AppTopBar

/**
 * 搜索模式下的顶部栏（统一走 [AppTopBar]：48dp、内置刘海避让）。
 *
 * @param searchQuery 当前搜索词
 * @param onQueryChange 搜索词变化回调
 * @param onClose 关闭搜索回调
 * @param resultCount 搜索结果数量（null=还未搜索）
 */
@Composable
fun SearchTopBar(
    searchQuery: String,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit,
    resultCount: Int?,
    modifier: Modifier = Modifier
) {
    AppTopBar(
        modifier = modifier,
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = stringResource(R.string.close)
                )
            }
        },
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                SearchField(
                    query = searchQuery,
                    onQueryChange = onQueryChange,
                    placeholder = "搜索照片，如 猫、去年夏天、上海...",
                    modifier = Modifier.weight(1f)
                )
                if (resultCount != null) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "${resultCount} 张",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        fontSize = 12.sp
                    )
                }
            }
        }
    )
}
