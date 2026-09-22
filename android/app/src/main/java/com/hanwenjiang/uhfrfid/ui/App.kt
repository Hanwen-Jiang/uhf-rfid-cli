package com.hanwenjiang.uhfrfid.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Contactless
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.UsbOff
import androidx.compose.material.icons.outlined.Contactless
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Straighten
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hanwenjiang.uhfrfid.AppState
import com.hanwenjiang.uhfrfid.CloudClient
import com.hanwenjiang.uhfrfid.R
import com.hanwenjiang.uhfrfid.TagCache
import com.hanwenjiang.uhfrfid.TagOps
import com.hanwenjiang.uhfrfid.TagWrite
import com.hanwenjiang.uhfrfid.UsbConnect

/**
 * 底部导航栏的四个页签。
 *
 * 图标是按"这个页签到底干什么"选的，不是按字面：
 * <ul>
 *   <li>读 / 写 —— [Icons.Filled.Contactless]：一圈圈扩散的射频波，就是"读标签"这件事本身。
 *       更早那版这里是 [Icons.Filled.Build]（扳手），跟读写标签毫无关系，已换掉。</li>
 *   <li>标签库 —— [Icons.Filled.Inventory2]：云端那份标签表，是"库"不是"书签"。</li>
 *   <li>古法写入 —— [Icons.Filled.Straighten]：一把尺子，对应这页真正的动作"凑够 24 位 Hex"。</li>
 *   <li>设置 —— [Icons.Filled.Tune]：推子，读写器参数（天线 / 功率）的调法。</li>
 * </ul>
 * 未选中用描边版、选中用实心版 —— 选中态一眼能看出来。[NavigationBarItem] 自带
 * 选中高亮（药丸形指示器 + 实心图标），不用自己画。
 */
enum class Tab(
    val labelRes: Int,
    val selectedIcon: ImageVector,
    val icon: ImageVector,
) {
    Home(R.string.tab_home, Icons.Filled.Contactless, Icons.Outlined.Contactless),
    Library(R.string.tab_library, Icons.Filled.Inventory2, Icons.Outlined.Inventory2),
    Manual(R.string.tab_manual, Icons.Filled.Straighten, Icons.Outlined.Straighten),
    Settings(R.string.tab_settings, Icons.Filled.Tune, Icons.Outlined.Tune),
}

/**
 * 整个界面：顶栏（左边空着 + 状态 + 动作图标）+ 四个页面 + 底部导航栏。
 *
 * <p>**页签在底部**：[NavigationBar] 里四个 [NavigationBarItem] 一字排开，点哪个切哪页。
 * 中间那版把页签搬进左上角的侧拉栏（ModalNavigationDrawer + 汉堡图标），
 * 已经整个回退掉了 —— 底部栏才是这个 App 一直在用的导航方式，
 * 四个页签常驻可见，点一下就到位，不用先拉开再点。
 *
 * <p>顶栏**故意不放 App 名和副标题**：那两行字（"UHF RFID" + 一句 USB 说明）
 * 说的是用户已经知道的事，却霸着最贵的一行位置。所以左上角一个字都没有，
 * 右边只有"连没连上"（[StatusChip]）+ 扫描诊断 + 连接动作。
 *
 * <p>页签切换用 [AnimatedContent] 做"淡入 + 横向轻推"：往前切从右边推进来，
 * 往回切从左边，方向感对得上点的那一下。
 *
 * <p>三个跨页面共用的弹窗（挑标签、确认写入、命名）都挂在这一层 —— 主页和标签库
 * 都可能触发写入，放在各自页面里就得写两遍，而且切页签弹窗会跟着页面一起消失。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(usbConnect: UsbConnect, tagOps: TagOps) {
    var tab by remember { mutableStateOf(Tab.Home) }
    var previousTab by remember { mutableIntStateOf(Tab.Home.ordinal) }

    // ---- 弹窗状态
    var pickerOpen by remember { mutableStateOf(false) }
    /** 准备写入的那颗（非 null 就弹确认框）。 */
    var pendingWrite by remember { mutableStateOf<CloudClient.Tag?>(null) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                // 左上角保持空的 —— 没有汉堡、没有 logo、没有 App 名、没有副标题
                title = {},
                actions = {
                    ConnectionChip(
                        connected = AppState.connected,
                        connecting = AppState.connecting,
                    )
                    // 扫描：把 USB 诊断信息丢到控制面板的状态行里
                    IconButton(
                        modifier = Modifier.size(40.dp),
                        onClick = {
                            AppState.status =
                                usbConnect.usbDiagnostics() + "\n" + usbConnect.chipProbe()
                        },
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Search,
                            contentDescription = stringResource(R.string.scan_usb),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    IconButton(
                        modifier = Modifier.size(40.dp),
                        onClick = { usbConnect.toggle() },
                    ) {
                        Icon(
                            imageVector = if (AppState.connected) Icons.Filled.Usb
                            else Icons.Filled.UsbOff,
                            contentDescription = stringResource(
                                if (AppState.connected) R.string.topbar_disconnect
                                else R.string.topbar_connect
                            ),
                            tint = if (AppState.connected) StatusColors.ok()
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    // 顶栏跟页面一个色（纯白），靠下面的内容把层次做出来
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
        },
        bottomBar = {
            AppBottomBar(
                tab = tab,
                onSelect = { item ->
                    if (item != tab) {
                        previousTab = tab.ordinal
                        tab = item
                    }
                },
            )
        },
    ) { padding ->
        val forward = tab.ordinal > previousTab
        AnimatedContent(
            targetState = tab,
            transitionSpec = {
                val dir = if (forward) 1 else -1
                (
                    fadeIn(tween(200)) +
                        slideInHorizontally(tween(240)) { w -> dir * w / 14 }
                    ) togetherWith (
                    fadeOut(tween(140)) +
                        slideOutHorizontally(tween(200)) { w -> -dir * w / 18 }
                    )
            },
            label = "页签切换",
            modifier = Modifier.fillMaxSize().padding(padding),
        ) { current ->
            when (current) {
                Tab.Home -> HomeScreen(
                    usbConnect = usbConnect,
                    tagOps = tagOps,
                    onPickTag = { pickerOpen = true },
                    onRequestWrite = { pendingWrite = it },
                )

                Tab.Library -> LibraryScreen(
                    tagOps = tagOps,
                    onRequestWrite = { pendingWrite = it },
                )

                Tab.Manual -> ManualScreen(tagOps = tagOps)
                Tab.Settings -> SettingsScreen(tagOps = tagOps)
            }
        }

        // ---- 挑选弹窗（只设置 target，不写入）
        if (pickerOpen) {
            val cache = remember(AppState.cacheCount, AppState.tags.size) { TagCache.tags() }
            TagPickerDialog(
                all = cache,
                onDismiss = { pickerOpen = false },
                onPick = { picked ->
                    AppState.target = picked
                    AppState.status = picked.suffix?.let { suffix ->
                        // 挑完给一句明确回执，不然点完弹窗关了像什么都没发生
                        "已选中：${picked.name ?: "(未命名)"}（后 5 位 $suffix）"
                    } ?: "已选中：${picked.name ?: "(未命名)"}"
                    pickerOpen = false
                },
            )
        }

        // ---- 写入确认
        pendingWrite?.let { tag ->
            WriteConfirmDialog(
                tag = tag,
                onDismiss = { pendingWrite = null },
                onConfirm = {
                    tagOps.write(tag)
                    pendingWrite = null
                },
            )
        }

        // ---- 上传完还没命名 -> 弹命名框
        AppState.nameTag?.let { tag ->
            NameDialog(
                tag = tag,
                onDismiss = { AppState.nameTag = null },
                onConfirm = { name ->
                    tagOps.rename(tag.id, name)
                    AppState.nameTag = null
                },
            )
        }
    }
}

// ---------------------------------------------------------------- 底部页签

/**
 * 底部那四条页签。
 *
 * <p>文字用 [MaterialTheme.typography.labelSmall]（这套字体里是 10.5sp），
 * **不用默认的 labelMedium**：默认字号下"古法写入"四个字在这个宽度里会折成两行，
 * 把整条导航栏撑高一截。再配 `maxLines = 1` + `softWrap = false` 兜底，
 * 换什么机型都是一行。
 *
 * <p>底色跟页面一样是纯白（`tonalElevation = 0.dp`，不然 Material 会往上面叠一层
 * 主色色调），选中态靠药丸形指示器 + 实心图标区分，不靠底色。
 */
@Composable
private fun AppBottomBar(tab: Tab, onSelect: (Tab) -> Unit) {
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
    ) {
        Tab.entries.forEach { item ->
            val selected = tab == item
            NavigationBarItem(
                selected = selected,
                onClick = { onSelect(item) },
                icon = {
                    Icon(
                        imageVector = if (selected) item.selectedIcon else item.icon,
                        contentDescription = null,
                        modifier = Modifier.size(21.dp),
                    )
                },
                label = {
                    Text(
                        text = stringResource(item.labelRes),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Clip,
                    )
                },
                alwaysShowLabel = true,
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    selectedTextColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        }
    }
}

// ---------------------------------------------------------------- 顶栏连接状态

/**
 * 顶栏那颗连接状态标签 —— 短，而且点的就是"连接/断开"。
 *
 * <p>三种状态三种语义色：连接中=蓝（并在呼吸）、已连接=绿、未连接=灰。
 * 点它等于点原来那颗 USB 图标，所以顶栏右边不再重复放一个"连接"按钮。
 */
@Composable
private fun ConnectionChip(connected: Boolean, connecting: Boolean) {
    val color = when {
        connecting -> StatusColors.busy()
        connected -> StatusColors.ok()
        else -> StatusColors.idle()
    }
    val text = stringResource(
        when {
            connecting -> R.string.status_connecting
            connected -> R.string.status_connected
            else -> R.string.status_disconnected
        }
    )
    StatusChip(
        text = text,
        color = color,
        pulsing = connecting,
        modifier = Modifier.padding(end = 2.dp),
    )
}

// ---------------------------------------------------------------- 弹窗

/**
 * 挑一颗要写的标签。
 *
 * <p>数据取自 [TagCache]（内存里的补全数据，不看当前列表页筛没筛）。
 * **这里只挑不写** —— 挑完只是把 [AppState.target] 设上，真正写要再点一次
 * 「写入」并确认，免得手滑把标签写坏。
 */
@Composable
private fun TagPickerDialog(
    all: List<CloudClient.Tag>,
    onDismiss: () -> Unit,
    onPick: (CloudClient.Tag) -> Unit,
) {
    var query by remember { mutableStateOf("") }

    val filtered = remember(all, query) {
        val q = TagWrite.norm(query)
        if (q.isEmpty()) {
            all
        } else {
            all.filter { t ->
                val name = t.name?.uppercase() ?: ""
                val suffix = t.suffix?.uppercase() ?: ""
                val epc = TagWrite.norm(t.epc)
                name.contains(q) || suffix.contains(q) || epc.contains(q)
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        // 标题就是一句完整的话（"挑一颗要写的标签"），底下原来还有一行
        // "挑一颗写进贴近天线的标签。"，同一个意思说两遍，已删。
        title = { Text(stringResource(R.string.pick_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(stringResource(R.string.pick_filter_hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Characters,
                        imeAction = ImeAction.Search,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                when {
                    all.isEmpty() -> Text(
                        text = stringResource(R.string.pick_need_cache),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    filtered.isEmpty() -> Text(
                        text = stringResource(R.string.pick_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    else -> Column(
                        modifier = Modifier
                            .heightIn(max = 360.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        filtered.forEach { tag -> PickerRow(tag = tag, onPick = { onPick(tag) }) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_close)) }
        },
    )
}

/** 挑标签列表里的一行：名称、后 5 位、读到/写过次数、EPC、行尾「选择」。 */
@Composable
private fun PickerRow(tag: CloudClient.Tag, onPick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = tag.name?.takeIf { it.isNotEmpty() }
                        ?: stringResource(R.string.cloud_unnamed),
                    style = MaterialTheme.typography.titleSmall,
                    color = if (tag.name.isNullOrEmpty()) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.row_suffix, tag.suffix ?: "—"),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                )
            }
            Text(
                text = stringResource(R.string.row_seen, tag.seenCount) + "　" +
                    stringResource(R.string.row_written, tag.writeCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = tag.epc ?: "",
                style = MonoSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(6.dp))
        TextButton(onClick = onPick) { Text(stringResource(R.string.pick_select)) }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

/** 写入前确认：把名称和 EPC 摆出来，别让人写错标签。 */
@Composable
private fun WriteConfirmDialog(
    tag: CloudClient.Tag,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val name = tag.name?.takeIf { it.isNotEmpty() } ?: stringResource(R.string.cloud_unnamed)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.cloud_write_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.write_target, name, tag.suffix ?: "—"),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = tag.epc ?: "",
                    style = MonoSpan,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.small)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.cloud_write)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
        },
    )
}

/** 命名框：上传完没名字的那条会弹出来。 */
@Composable
private fun NameDialog(
    tag: CloudClient.Tag,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember(tag.id) { mutableStateOf(tag.name ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.cloud_name_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.cloud_name_msg, tag.epc ?: "", tag.suffix ?: "—"),
                    style = MonoSpan,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(stringResource(R.string.cloud_name_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(text) },
                enabled = text.trim().isNotEmpty(),
            ) { Text(stringResource(R.string.cloud_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
        },
    )
}
