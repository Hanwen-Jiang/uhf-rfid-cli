package com.hanwenjiang.uhfrfid.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hanwenjiang.uhfrfid.AppState
import com.hanwenjiang.uhfrfid.R
import com.hanwenjiang.uhfrfid.TagOps
import com.hanwenjiang.uhfrfid.UsbConnect
import kotlinx.coroutines.delay

/**
 * 控制面板固定就这么高 —— 内容和状态怎么变，这个框都不动。
 *
 * 上一版 212dp，实测平时（形态三）底下空出近 100dp，这一版量着内容收到 152dp，
 * 重排了内部层次（状态行 / 命中大名字 / 分隔线 / EPC·TID / 结论）刚好填满。
 */
private val PanelHeight = 152.dp

/**
 * 主页：控制面板 + 读标签 + 写入。
 *
 * <p>控制面板是这一页的主角，**高度写死 152dp**：连接中、连接成功、连接失败、
 * 命中云端、读到标签 —— 五种情况下内容差别很大，如果让它自己撑高，每次状态一变
 * 整页都会上下抖一下，下面的按钮跟着跳，按都按不准。所以框定死，内容在里面切换。
 *
 * <p>**这一版把首页所有"解释性"文字都删了**：按钮下面那两行 hint
 * （"读一次标签，结果直接上传云端"/"把选中的标签写进贴近天线的这一颗"）、
 * 页面底下那句"两个按钮右侧的图标：云 = 上传，▼ = 挑标签"、面板里那句
 * "写完一律回读校验。"，还有连接中面板里的进度说明 —— 全去掉。
 * 页面上剩下的只有**数据**（状态行、命中名字、EPC / TID、校验结论、命中标记）
 * 和**按钮自己的文字**。判据：这句话是"数据"还是"解释"？解释就删。
 *
 * <p>去掉按钮底下的 hint 之后，右边的热区图标（云 = 上传、▼ = 挑标签）就没字解释了。
 * 这两颗图标的无障碍描述还在（[SplitActionButton] 的 rightContentDescription），
 * 读屏软件念得出来；视觉上它们跟在按钮右边、中间有竖分隔线，就是"同一颗按钮的另一半"。
 */
@Composable
fun HomeScreen(
    usbConnect: UsbConnect,
    tagOps: TagOps,
    onPickTag: () -> Unit,
    onRequestWrite: (com.hanwenjiang.uhfrfid.CloudClient.Tag) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ControlPanel()

        // ---- 读标签（右侧热区 = 上传）
        val target = AppState.target
        val unnamed = stringResource(R.string.cloud_unnamed)
        SplitActionButton(
            text = stringResource(R.string.read_short),
            icon = Icons.Filled.PlayArrow,
            onClick = {
                if (AppState.connected) {
                    tagOps.read()
                } else {
                    // 没连上就把"为什么连不上"讲清楚，而不是笼统说一句没连
                    AppState.status = usbConnect.whyNotConnected()
                }
            },
            rightIcon = Icons.Filled.CloudUpload,
            rightContentDescription = stringResource(R.string.cloud_upload),
            onRightClick = { tagOps.upload() },
            rightEnabled = true,
            loading = AppState.status == currentReadingText(),
        )

        // ---- 写入（右侧热区 = 挑标签）
        SplitActionButton(
            text = if (target != null) {
                stringResource(
                    R.string.write_now_target,
                    target.name?.takeIf { it.isNotEmpty() } ?: unnamed,
                    target.suffix ?: "—",
                )
            } else {
                stringResource(R.string.write_now)
            },
            icon = null,
            onClick = {
                // 主区在没挑标签时是可点的（按钮看着灰，但点下去会开挑标签的弹窗），
                // 所以这里第一件事就是看目标在不在，别拿 UI 的禁用态当保护
                if (target != null) onRequestWrite(target) else onPickTag()
            },
            rightIcon = Icons.Filled.ArrowDropDown,
            rightContentDescription = stringResource(R.string.write_pick),
            onRightClick = onPickTag,
            // 没挑标签时按钮是灰的、文字只剩下「写入」；这时点主区直接开挑标签的弹窗，
            // 而不是弹个"请先挑标签" —— 反正下一步就是挑，别让人多点一次。
            enabled = target != null,
            loading = AppState.writing,
            containerColor = MaterialTheme.colorScheme.secondary,
            contentColor = MaterialTheme.colorScheme.onSecondary,
        )
    }
}

/** 「读取中」那句状态文字，用来顺手把按钮切成转圈。 */
@Composable
private fun currentReadingText(): String = stringResource(R.string.reading)

// ---------------------------------------------------------------- 控制面板

/**
 * 控制面板。
 *
 * <p>三种形态共用一个 200dp 的框：
 * <ol>
 *   <li>正在连接 —— 不确定态进度条 + 当前步骤</li>
 *   <li>刚连完 —— 成功/失败的动画 + 结果文字，几秒后自己切回第 3 种</li>
 *   <li>平时 —— 状态行、命中云端的大名字、EPC / TID、完整性结论</li>
 * </ol>
 *
 * <p>边框颜色表达状态：待机灰、连接中/命中蓝、成功绿、失败红。连接成功那一下
 * 边框还会呼吸两下（只改颜色不改宽度，所以里面一个字都不会动）。
 */
@Composable
private fun ControlPanel() {
    val connecting = AppState.connecting
    val result = AppState.connectResult
    val hit = AppState.hitName

    val baseColor = MaterialTheme.colorScheme.outlineVariant
    val targetBorder = when {
        connecting -> StatusColors.busy()
        result == true -> StatusColors.ok()
        result == false -> StatusColors.bad()
        hit != null -> StatusColors.busy()
        else -> baseColor
    }
    val borderColor by animateColorAsState(
        targetValue = targetBorder,
        animationSpec = tween(420),
        label = "面板边框",
    )

    // 成功那一下脉冲：只让描边自己亮暗，不参与布局
    val pulse = rememberInfiniteTransition(label = "成功脉冲")
    val pulseAlpha by pulse.animateFloat(
        initialValue = 1f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(tween(520), repeatMode = RepeatMode.Reverse),
        label = "脉冲透明度",
    )

    PanelSurface(
        borderColor = if (result == true) borderColor.copy(alpha = pulseAlpha) else borderColor,
        modifier = Modifier.fillMaxWidth().height(PanelHeight),
    ) {
        Box(modifier = Modifier.fillMaxSize().padding(horizontal = 13.dp, vertical = 11.dp)) {
            when {
                connecting -> ConnectingBody()
                result != null -> ConnectResultBody(ok = result)
                else -> IdleBody()
            }
        }
    }

    // 结果展示完之后自己收回去。延时放在 Compose 这边（AppState 那边只管写结果），
    // 这样"显示多久"是纯界面的事，以后想改不用动 Java。
    LaunchedEffect(result) {
        if (result != null) {
            delay(if (result) 1200L else 3500L)
            AppState.connectResult = null
        }
    }
}

/** 形态一：正在连接。 */
@Composable
private fun ConnectingBody() {
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = stringResource(R.string.connecting),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(10.dp))
        LinearProgressIndicator(
            modifier = Modifier.fillMaxWidth().height(5.dp),
            color = StatusColors.busy(),
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        // 进度条底下只有一句"现在到哪一步了"（数据），
        // 原来那句"连不上会自动重试；连上后按…预处理"是解释，已删。
        Text(
            text = AppState.connectStep,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 形态二：刚连完，成功或失败。 */
@Composable
private fun ConnectResultBody(ok: Boolean) {
    val color = if (ok) StatusColors.ok() else StatusColors.bad()
    Row(modifier = Modifier.fillMaxSize()) {
        ResultAnimation(ok = ok, size = 48.dp)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.fillMaxSize()) {
            Text(
                text = stringResource(
                    if (ok) R.string.connect_ok_title else R.string.connect_fail_title
                ),
                style = MaterialTheme.typography.titleMedium,
                color = color,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
            // 成功时这行是固件/天线信息，可能好几行；给它自己滚，别把框撑破
            Text(
                text = AppState.connectMessage,
                style = MonoSpan,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
            )
        }
    }
}

/**
 * 形态三：平时。
 *
 * 层次从上到下：状态行 → 命中云端的大名字 → 分隔线 → EPC / TID 读数 → 完整性结论。
 * EPC / TID 用等宽大字（15sp + 0.8 字距），分两行排 —— 24 位十六进制挤一行在手机上
 * 会折断换行，反而更难读；上下两行还能直接对齐着比"第 9 位是不是 0 / 2"。
 */
@Composable
private fun IdleBody() {
    val hit = AppState.hitName
    Column(modifier = Modifier.fillMaxSize()) {
        // 状态行：最多两行，超了省略 —— 宁可能看不全，也不让它把框顶变形
        Text(
            text = AppState.status,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )

        // 命中云端 / 刚写入：名字用大字
        AnimatedVisibility(
            visible = hit != null,
            enter = fadeIn(tween(200)) + expandVertically(tween(200)),
            exit = fadeOut(tween(140)) + shrinkVertically(tween(160)),
        ) {
            Column {
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = hit ?: "",
                        style = MaterialTheme.typography.headlineSmall,
                        color = if (AppState.justWritten) StatusColors.ok()
                        else MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (AppState.justWritten) {
                        Spacer(Modifier.width(6.dp))
                        MiniChip(
                            text = stringResource(R.string.result_ok),
                            color = StatusColors.ok(),
                        )
                    }
                }
                AppState.hitSub?.let { sub ->
                    Text(
                        text = sub,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        Spacer(Modifier.height(6.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(6.dp))

        ReadoutLine(
            label = stringResource(R.string.epc),
            value = AppState.epc,
            hit = AppState.hitName != null && !AppState.justWritten,
        )
        Spacer(Modifier.height(1.dp))
        ReadoutLine(
            label = stringResource(R.string.tid),
            value = AppState.tid,
            hit = AppState.hitName != null && !AppState.justWritten,
        )
        Spacer(Modifier.height(5.dp))

        // 结论行：左边一句话说这读数合不合格，右边一个「清空」把两条读数抹掉。
        // 没读过（checkOk == null）时这行留空 —— 原来兜底显示「写完一律回读校验。」
        // 是句解释，而且"没读到标签"的真实原因已经在上面状态行里说了，不重复。
        val checkColor = when (AppState.checkOk) {
            true -> StatusColors.ok()
            false -> StatusColors.bad()
            null -> MaterialTheme.colorScheme.onSurfaceVariant
        }
        val cleared = stringResource(R.string.cleared)
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(color = checkColor, size = 7.dp)
            Spacer(Modifier.width(6.dp))
            Text(
                text = AppState.checkText.ifEmpty {
                    when (AppState.checkOk) {
                        true -> stringResource(R.string.check_pass)
                        false -> stringResource(R.string.check_fail)
                        null -> ""
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = checkColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (!AppState.epc.isNullOrEmpty() && AppState.epc != "—") {
                Spacer(Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.clear_readout),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable {
                        AppState.epc = "—"
                        AppState.tid = "—"
                        AppState.checkOk = null
                        AppState.checkText = ""
                        AppState.clearHit()
                        AppState.status = cleared
                    },
                )
            }
        }
    }
}

/**
 * 一行读数：左边一个窄标签，右边等宽大字。
 *
 * <p>取到值之后还会标一个「命中」—— 命中云端说明这一串 EPC 在库里，是页面当前
 * 最重要的结论，用主色标出来；没取到值时用灰色的 `—`，不会因为空串把行高撑没。
 * 点一下这行能把值复制走（现场经常要把 EPC 发给人对账）。
 */
@Composable
private fun ReadoutLine(label: String, value: String, hit: Boolean) {
    val empty = value.isEmpty() || value == "—"
    val clipboard = LocalClipboardManager.current
    val copied = stringResource(R.string.copied)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !empty) {
                clipboard.setText(AnnotatedString(value))
                AppState.status = copied + "：" + value
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(30.dp),
        )
        Text(
            text = if (empty) "—" else value,
            style = MonoSpan,
            color = when {
                empty -> MaterialTheme.colorScheme.onSurfaceVariant
                hit -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurface
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (hit) {
            Spacer(Modifier.width(5.dp))
            Text(
                text = stringResource(R.string.hit_mark),
                style = MaterialTheme.typography.labelSmall,
                color = StatusColors.busy(),
                letterSpacing = 0.sp,
            )
        }
    }
}
