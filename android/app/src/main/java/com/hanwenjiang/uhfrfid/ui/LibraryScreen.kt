package com.hanwenjiang.uhfrfid.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.hanwenjiang.uhfrfid.AppState
import com.hanwenjiang.uhfrfid.CloudClient
import com.hanwenjiang.uhfrfid.R
import com.hanwenjiang.uhfrfid.RfidSession
import com.hanwenjiang.uhfrfid.TagOps

/**
 * 标签库：云端有哪些标签，改名、删除、一键写进去。
 *
 * <p>进页面就自动拉一次；「刷新」和「只看没命名的」都走
 * [TagOps.loadLibrary]，结果由它写进 [AppState]，这边只管画。
 *
 * <p>密度上比上一版紧一档：标题换成大字距的小标签 + 一行「共 N 条」，
 * 「只看没命名的」从开关换成 FilterChip，一行的信息塞进三行（名称 / 后 5 位 + 次数 /
 * EPC），行距 8dp。
 */
@Composable
fun LibraryScreen(
    tagOps: TagOps,
    onRequestWrite: (CloudClient.Tag) -> Unit,
) {
    // 只有第一次进页面拉一次。放 LaunchedEffect(Unit) 而不是每次重组，
    // 否则翻个屏、切个深色模式都会重发一次请求。
    LaunchedEffect(Unit) { tagOps.loadLibrary() }

    /** 点开某一行后的操作对象。 */
    var opened by remember { mutableStateOf<CloudClient.Tag?>(null) }

    // 字符串在组合里取好，回调里直接用 —— 回调不是 @Composable，拿不到 stringResource
    val needConnect = stringResource(R.string.need_connect_first)

    Column(modifier = Modifier.fillMaxSize()) {

        // ---- 顶部工具条：小标签 + 条数 + 刷新，一行解决
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 10.dp, top = 9.dp, bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.cloud_title),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(6.dp))
                    if (AppState.tags.isNotEmpty()) {
                        Text(
                            text = stringResource(R.string.library_count, AppState.tags.size),
                            style = MonoSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (AppState.tagsLoading) {
                        Spacer(Modifier.width(6.dp))
                        CircularProgressIndicator(
                            modifier = Modifier.size(13.dp),
                            strokeWidth = 1.5.dp,
                        )
                    }
                }
                // 这一行只说"上一把拉取的结果"（数据）：正在读取 / 出错原因 /
                // 服务端的条数。没话说时把这行整个省掉（原来兜底显示一句
                // "点一行可以命名 / 删除…"，那是说明文字，已删）。
                val status = AppState.tagsStatus
                if (status.isNotEmpty()) {
                    Text(
                        text = status,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(6.dp))
            FilledTonalButton(
                onClick = { tagOps.loadLibrary() },
                shape = MaterialTheme.shapes.small,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Icon(
                    Icons.Filled.CloudDownload,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    text = stringResource(R.string.cloud_refresh),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }

        // ---- 只看没命名的：一个 Chip 就够，不用占一整行。
        // 原来右边还有一句"点一行改名 / 删除"，是操作说明、不是数据，已删。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 1.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(
                selected = AppState.onlyUnnamed,
                onClick = {
                    AppState.onlyUnnamed = !AppState.onlyUnnamed
                    // 筛选项变了必须重新拉 —— 筛选是在服务端做的
                    tagOps.loadLibrary()
                },
                label = {
                    Text(
                        text = stringResource(R.string.cloud_only_unnamed),
                        style = MaterialTheme.typography.labelMedium,
                    )
                },
                shape = MaterialTheme.shapes.small,
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
                modifier = Modifier.height(32.dp),
            )
        }

        Spacer(Modifier.height(8.dp))

        // ---- 列表
        if (AppState.tags.isEmpty() && !AppState.tagsLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Filled.CloudDownload,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(30.dp),
                    )
                    EmptyHint(
                        text = AppState.tagsStatus.ifEmpty {
                            stringResource(R.string.pick_empty)
                        }
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 14.dp, end = 14.dp, top = 2.dp, bottom = 20.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(items = AppState.tags, key = { it.id }) { tag ->
                    LibraryRow(
                        tag = tag,
                        onClick = { opened = tag },
                        onWrite = {
                            if (RfidSession.get().isConnected()) {
                                onRequestWrite(tag)
                            } else {
                                // 没连上读写器就别让确认框弹出来 —— 弹了也写不成。
                                // 顺手把结果面板点成"失败 + 原因"，用户回主页能看见为什么。
                                AppState.status = needConnect
                                AppState.connectResult = false
                                AppState.connectMessage = needConnect
                            }
                        },
                    )
                }
            }
        }
    }

    // ---- 点一行：改名 / 删除
    opened?.let { tag ->
        TagActionDialog(
            tag = tag,
            onDismiss = { opened = null },
            onRename = { name ->
                tagOps.rename(tag.id, name)
                opened = null
            },
            onDelete = {
                tagOps.delete(tag.id)
                opened = null
            },
        )
    }
}

// ---------------------------------------------------------------- 一行

/**
 * 列表里的一条标签。点整行改名/删除，右边「写入」直接写。
 *
 * <p>三行：名称（+ 异常标）/ 后 5 位 + 读到写过次数 / EPC。异常那条在最左边竖一道
 * 琥珀色标记条 —— 比把整行刷成淡红干净，也不跟"写入失败"的红撞语义。
 */
@Composable
private fun LibraryRow(
    tag: CloudClient.Tag,
    onClick: () -> Unit,
    onWrite: () -> Unit,
) {
    val unnamed = tag.name.isNullOrEmpty()
    val anomaly = tag.status != null && tag.status.equals("anomaly", ignoreCase = true)

    val container by animateColorAsState(
        targetValue = MaterialTheme.colorScheme.surfaceContainer,
        animationSpec = tween(300),
        label = "行底色",
    )
    val markColor = if (anomaly) StatusColors.warn() else MaterialTheme.colorScheme.primary

    Card(
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = container),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(
            1.dp, MaterialTheme.colorScheme.outlineVariant
        ),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            // 左侧状态条：异常琥珀、正常主色 —— 扫一眼就能挑出脏数据
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(62.dp)
                    .background(markColor.copy(alpha = 0.75f))
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(
                    start = 10.dp, end = 8.dp, top = 8.dp, bottom = 8.dp,
                ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = if (unnamed) {
                                stringResource(R.string.cloud_unnamed)
                            } else {
                                tag.name
                            },
                            style = MaterialTheme.typography.titleSmall,
                            // 未命名的用灰色，一眼能看出"这条还缺个名字"
                            color = if (unnamed) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (anomaly) {
                            Spacer(Modifier.width(6.dp))
                            MiniChip(
                                text = stringResource(R.string.row_anomaly),
                                color = StatusColors.warn(),
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        if (tag.suffix != null) {
                            Text(
                                text = stringResource(R.string.row_suffix, tag.suffix),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                            )
                        }
                    }
                    Spacer(Modifier.height(1.dp))
                    Text(
                        text = stringResource(R.string.row_seen, tag.seenCount) + "　" +
                            stringResource(R.string.row_written, tag.writeCount) +
                            (tag.operator?.takeIf { it.isNotEmpty() }?.let { "　$it" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = tag.epc ?: "",
                        style = MonoSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(8.dp))
                FilledTonalButton(
                    onClick = onWrite,
                    shape = MaterialTheme.shapes.small,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = stringResource(R.string.write_label),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 改名 / 删除

/**
 * 点一行之后的对话框：改名，或者删除。
 *
 * <p>删除要**再确认一次** —— 云端库删了就没了，误触的代价太大。
 */
@Composable
private fun TagActionDialog(
    tag: CloudClient.Tag,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
) {
    var name by remember(tag.id) { mutableStateOf(tag.name ?: "") }
    var confirmingDelete by remember(tag.id) { mutableStateOf(false) }

    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text(stringResource(R.string.cloud_delete_title)) },
            text = {
                Text(
                    text = stringResource(
                        R.string.cloud_name_msg,
                        tag.name?.takeIf { it.isNotEmpty() }
                            ?: stringResource(R.string.cloud_unnamed),
                        tag.suffix ?: "—",
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = onDelete) {
                    Text(
                        text = stringResource(R.string.cloud_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = false }) {
                    Text(stringResource(R.string.dialog_cancel))
                }
            },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.cloud_name_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.cloud_row_msg, tag.suffix ?: "—"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                tag.epc?.takeIf { it.isNotEmpty() }?.let { epc ->
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = epc,
                        style = MonoSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.cloud_name_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onRename(name) },
                enabled = name.trim().isNotEmpty() && name != tag.name,
            ) { Text(stringResource(R.string.cloud_save)) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { confirmingDelete = true }) {
                    Text(
                        text = stringResource(R.string.cloud_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
            }
        },
    )
}
