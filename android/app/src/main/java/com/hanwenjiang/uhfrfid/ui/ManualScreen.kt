package com.hanwenjiang.uhfrfid.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hanwenjiang.uhfrfid.AppState
import com.hanwenjiang.uhfrfid.R
import com.hanwenjiang.uhfrfid.TagCache
import com.hanwenjiang.uhfrfid.TagOps
import com.hanwenjiang.uhfrfid.TagWrite

/** EPC 和 TID 的后 15 位必须一致，这里就用这个长度去比。 */
private const val PAIR_TAIL = 15

/**
 * 输入框里那串 Hex 专用的样式：等宽 + 加大字距 + 加粗。
 *
 * 手敲 24 位十六进制是最容易敲错字的动作，等宽字加宽字距能让 0/O、1/I、B/8
 * 分开，也好一位一位数。
 */
private val HexField = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 15.sp,
    lineHeight = 20.sp,
    letterSpacing = 1.2.sp,
    fontWeight = FontWeight.SemiBold,
)

/**
 * 古法写入：手敲 EPC / TID，边敲边按云端已有的标签补全。
 *
 * <p>补全用的是内存缓存（[TagCache]），不联网 —— 敲一个字查一次云端的话，
 * 手还没抬起来就卡住了。缓存什么时候刷新见 `TagCache` 的注释。
 *
 * <p>三条规则，都是从旧版的坑里总结出来的：
 * <ol>
 *   <li><b>只在"变长了"的时候补</b>。否则一退格就被补回来，变成删不掉的输入框。</li>
 *   <li><b>尾巴留 5 位自己敲</b>。那 5 位是"这颗是哪颗"，替你填掉最容易写错标签。</li>
 *   <li><b>TID 被手动改过之后，就不再跟着 EPC 自动变</b>。不然用户刚改完，
 *       动一下 EPC 就把他的修改覆盖了。</li>
 * </ol>
 *
 * <p>光标只在**文本真的变了**的时候移到末尾：补全是在用户敲的那个字符后面接一长串，
 * 光标留在中间的话，下一个字符会插进补出来的内容里。反过来，纯挪光标（文本一个字没变）
 * 就尊重用户给的 selection —— 一律拽回末尾的话，"在光标处插字"就成了空话。
 *
 * <p><b>输入只有一条口子</b>：系统键盘和粘贴板敲进来的内容，全都先过 [hexOnly] 过滤
 * （只留 `0-9 A-F`、统一大写），**再判断 grew**、再补全。
 * 顺序不能反 —— Android 没有 hex 专用的键盘类型，系统键盘上 `Z` 就挨着 `A`，
 * 脏字符要是先算进 grew 再进补全，等于拿着一串带 Z 的值去匹配标签。
 *
 * <p>页面上**没有多余的说明文字**：开头那句"边敲边按云端已有的标签补全。"已删
 * （行为本身就是那个行为，看一眼输入框就知道了）；唯一留下的解释是写入按钮底下
 * 那一句短提示"写完会回读校验。"—— 那是按下按钮前最该知道的一件事。
 * 两个框的 24 位进度计数（`EPC 12/24　TID 24/24`）和底下的红色警告都是**数据**，留着。
 *
 * <p>曾经在这一页挂过一套可折叠的十六进制小键盘（数字 + A-F + 退格 + EPC/TID 切换），
 * 已整个删掉 —— 太抽象，用户要的就是系统键盘 + 这道过滤。
 */
@Composable
fun ManualScreen(tagOps: TagOps) {
    var epc by remember { mutableStateOf(TextFieldValue("")) }
    var tid by remember { mutableStateOf(TextFieldValue("")) }

    /** 用户自己动过 TID 没有 —— 动过就不再让 EPC 覆盖它。 */
    var tidTouched by remember { mutableStateOf(false) }

    /** 点了写入、结果还没回来 —— 按钮上转圈用。 */
    var writing by remember { mutableStateOf(false) }
    LaunchedEffect(AppState.manualStatus) { writing = false }

    /**
     * EPC 框来了新内容：**先过滤 → 再看这次长没长 → 再补全 → 写回**（光标放末尾）。
     * 顺带按第 9 位的规律把 TID 推出来（用户自己动过 TID 就不动它）。
     */
    fun putEpc(nv: TextFieldValue) {
        val now = hexOnly(nv.text)
        if (now == epc.text) {
            // 文本没变：这一下只是挪光标，或者敲进来一个被过滤掉的字符（Z、空格…）。
            // 光标要听用户的 —— 一律拽回末尾的话，"在光标处插字"就成了空话。
            epc = TextFieldValue(now, caretIn(nv, now))
            return
        }
        val grew = now.length > epc.text.length
        val before = epc.text
        val text = complete(now, TagCache.epcs(), grew)
        epc = TextFieldValue(text, TextRange(text.length))
        AppState.manualEpc = text
        // EPC 变了就顺手把 TID 推出来（用户自己改过 TID 的话不动它）
        if (!tidTouched && text != before) {
            val guess = TagWrite.tidFromEpc(text)
            if (guess.isNotEmpty()) {
                tid = TextFieldValue(guess, TextRange(guess.length))
                AppState.manualTid = guess
            }
        }
    }

    /** TID 框那一份，同上。 */
    fun putTid(nv: TextFieldValue) {
        val now = hexOnly(nv.text)
        if (now == tid.text) {
            tid = TextFieldValue(now, caretIn(nv, now))
            return
        }
        val grew = now.length > tid.text.length
        val text = complete(now, TagCache.tids(), grew)
        tid = TextFieldValue(text, TextRange(text.length))
        // 手动改过 TID 之后就不再由 EPC 自动覆盖；但清空 = 想让它跟着 EPC 走
        tidTouched = text.isNotEmpty()
        AppState.manualTid = text
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionCard(title = stringResource(R.string.section_epc_tid)) {
            // ---- EPC
            OutlinedTextField(
                value = epc,
                onValueChange = { nv -> putEpc(nv) },
                label = { Text(stringResource(R.string.epc_hint)) },
                singleLine = true,
                isError = epcError(epc.text),
                textStyle = HexField,
                shape = MaterialTheme.shapes.small,
                // hex 只需要 0-9 A-F：键盘类型退而求其次用 Ascii（Android 没有 hex 专用键盘），
                // 真正兜底的是 putEpc 里那道过滤；自动大写和自动纠错都关掉，
                // 免得输入法往里塞它"猜"出来的词。
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Ascii,
                    imeAction = ImeAction.Next,
                ),
                supportingText = { LengthHint(epc.text, tid.text) },
                modifier = Modifier.fillMaxWidth(),
            )

            // ---- TID
            OutlinedTextField(
                value = tid,
                onValueChange = { nv -> putTid(nv) },
                label = { Text(stringResource(R.string.tid_hint)) },
                singleLine = true,
                isError = tidError(tid.text),
                textStyle = HexField,
                shape = MaterialTheme.shapes.small,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Ascii,
                    imeAction = ImeAction.Done,
                ),
                modifier = Modifier.fillMaxWidth(),
            )

            // ---- 只在真有问题时才红字提醒
            val shortWarn = epc.text.length < TagWrite.FULL || tid.text.length < TagWrite.FULL
            val mismatchWarn = !shortWarn && mismatch(epc.text, tid.text)

            if (shortWarn) {
                WarningLine(
                    text = stringResource(
                        R.string.manual_short,
                        TagWrite.FULL,
                        epc.text.length,
                        tid.text.length,
                    )
                )
            } else if (mismatchWarn) {
                WarningLine(text = stringResource(R.string.manual_mismatch))
            }

            // ---- 写入
            SplitActionButton(
                text = stringResource(R.string.write_now),
                icon = Icons.Filled.Create,
                onClick = {
                    writing = true
                    tagOps.writeManual(epc.text, tid.text)
                },
                enabled = epc.text.isNotEmpty() || tid.text.isNotEmpty(),
                loading = writing,
                containerColor = MaterialTheme.colorScheme.secondary,
                contentColor = MaterialTheme.colorScheme.onSecondary,
                // 这一行是全页唯一留下的说明 —— "写完会回读校验"是这一刻最该知道的一件事，
                // 短到一句。原来那句尾巴"EPC 与 TID 后 15 位要一致"跟下面的红色不一致
                // 警告重复（那条是"真出问题才显示"的数据型警告），删掉不丢信息。
                hint = stringResource(R.string.manual_write_hint),
                height = 46.dp,
            )

            if (AppState.manualStatus.isNotEmpty()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text(
                    text = AppState.manualStatus,
                    style = MonoSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        // ---- 原始报文
        if (AppState.rawLog.isNotEmpty()) {
            SectionCard(title = stringResource(R.string.raw_log)) {
                Text(
                    text = AppState.rawLog,
                    style = MonoSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .verticalScroll(rememberScrollState()),
                )
            }
        }

        // ---- 当前状态：一页纸的"下笔之前先看一眼"
        // 这页就两个输入框 + 一个按钮，底下会剩一大片空；与其空着，不如把
        // 三个"你正要对什么下手"的事实摆出来：补全数据有几条、读写器连没连、目标是谁。
        // 都是 [AppState] 里现成的状态，不新增任何逻辑。
        SectionCard(title = stringResource(R.string.manual_section_status)) {
            val cacheOk = AppState.cacheCount > 0
            StatusLine(
                label = stringResource(R.string.precache),
                value = if (cacheOk) {
                    stringResource(R.string.manual_cache_line, AppState.cacheCount)
                } else {
                    stringResource(R.string.manual_cache_none)
                },
                color = if (cacheOk) MaterialTheme.colorScheme.onSurface
                else StatusColors.warn(),
                dot = if (cacheOk) StatusColors.ok() else StatusColors.warn(),
            )
            StatusLine(
                label = stringResource(R.string.reader),
                value = stringResource(
                    R.string.manual_reader_line,
                    stringResource(
                        if (AppState.connected) R.string.status_connected
                        else R.string.status_disconnected
                    ),
                ),
                color = MaterialTheme.colorScheme.onSurface,
                dot = if (AppState.connected) StatusColors.ok() else StatusColors.idle(),
            )
            StatusLine(
                label = stringResource(R.string.target),
                value = AppState.target?.let { tag ->
                    (tag.name?.takeIf { it.isNotEmpty() }
                        ?: stringResource(R.string.cloud_unnamed)) +
                        (tag.suffix?.let { "（${stringResource(R.string.row_suffix, it)}）" } ?: "")
                } ?: stringResource(R.string.write_target_none),
                color = AppState.target?.let { MaterialTheme.colorScheme.onSurface }
                    ?: MaterialTheme.colorScheme.onSurfaceVariant,
                dot = if (AppState.target != null) StatusColors.busy() else StatusColors.idle(),
            )
        }
    }

    // 从别处（设置里灌进来的、上一轮留下的）带过来的初值也对齐一次：
    // 只在第一帧跑，避免和用户正在敲的内容打架。
    LaunchedEffect(Unit) {
        if (epc.text.isEmpty() && AppState.manualEpc.isNotEmpty()) {
            epc = TextFieldValue(AppState.manualEpc, TextRange(AppState.manualEpc.length))
        }
        if (tid.text.isEmpty() && AppState.manualTid.isNotEmpty()) {
            tid = TextFieldValue(AppState.manualTid, TextRange(AppState.manualTid.length))
        }
    }
}

// ---------------------------------------------------------------- 输入

/**
 * 是不是 hex 字符（`0-9 A-F a-f`）。
 *
 * <p>刻意不写 `Char.isDigit()`：那个认全世界的数字（阿拉伯-印度数字、全角数字都算），
 * 而 EPC/TID 里只认这 16 个 ASCII 字符。
 */
private fun isHex(c: Char): Boolean =
    c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

/**
 * 只留 `0-9 A-F`，字母统一成大写；其余字符（Z、空格、标点、中文…）整颗丢掉。
 *
 * <p>这是整个页面唯一的一道输入关口：系统键盘和粘贴板进来的内容都从这儿过。
 * [KeyboardType.Ascii] 出来的还是全键盘（`Z` 就挨着 `A`，最容易误触），
 * 所以真正的兜底只能是这里。
 */
private fun hexOnly(raw: String): String {
    val sb = StringBuilder(raw.length)
    for (c in raw) {
        if (isHex(c)) sb.append(c.uppercaseChar())
    }
    return sb.toString()
}

/** [index] 之前有几个字符能留下 —— 把原始串上的下标换算到过滤后的串上。 */
private fun hexIndex(raw: String, index: Int): Int {
    var kept = 0
    for (i in 0 until index.coerceIn(0, raw.length)) {
        if (isHex(raw[i])) kept++
    }
    return kept
}

/**
 * 这次改动落在过滤后的文本上的哪个位置。
 *
 * <p>挪光标时 [TextFieldValue.text] 一个字都没变，进来的只是新的 selection；
 * 敲进来一个被过滤掉的字符时，selection 又是在**原始**串上算的 ——
 * 被丢掉的字符不占位置，所以两个端点都得按"前面留了几个 hex 字符"重算，不能直接抄。
 */
private fun caretIn(nv: TextFieldValue, filtered: String): TextRange = TextRange(
    hexIndex(nv.text, nv.selection.min).coerceIn(0, filtered.length),
    hexIndex(nv.text, nv.selection.max).coerceIn(0, filtered.length),
)

/**
 * 补全一步。
 *
 * <p>先跑 [TagWrite.completeFrom]（补到候选的公共前缀、尾巴留 5 位）；
 * 如果用户敲的已经是个够长的尾巴、而且**唯一**命中一颗已知标签，
 * 那就直接补成完整那颗 —— 这正是"贴标签时只敲序号"的用法。
 */
private fun complete(typed: String, known: List<String>, grew: Boolean): String {
    val base = TagWrite.completeFrom(typed, known, grew)
    if (!grew || known.isEmpty()) return base
    val s = TagWrite.norm(base)
    if (s.length >= TagWrite.MIN_TAIL_MATCH && s.length < TagWrite.FULL) {
        val hits = known.filter { it.endsWith(s) || it.contains(s) }
        if (hits.size == 1) return hits[0]
    }
    return base
}

// ---------------------------------------------------------------- 提示

/** 是否该显示红色错误边框：填了就得不长不短刚刚 24 位。 */
private fun epcError(epc: String): Boolean =
    epc.isNotEmpty() && epc.length != TagWrite.FULL

/** 同上，TID 那份。 */
private fun tidError(tid: String): Boolean =
    tid.isNotEmpty() && tid.length != TagWrite.FULL

/**
 * 长度进度：`EPC 12/24　TID 24/24`。
 *
 * 两个都满 24 位才转成绿色 —— 这一行就是"能不能按写入"的判据，值得一直显示。
 */
@Composable
private fun LengthHint(epc: String, tid: String) {
    val full = epc.length == TagWrite.FULL && tid.length == TagWrite.FULL
    Text(
        text = "EPC ${epc.length}/${TagWrite.FULL}　TID ${tid.length}/${TagWrite.FULL}",
        style = MonoSmall,
        color = if (full) StatusColors.ok() else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** 一行红字警告。 */
@Composable
private fun WarningLine(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = Icons.Filled.Warning,
            contentDescription = null,
            tint = StatusColors.bad(),
            modifier = Modifier.height(15.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = StatusColors.bad(),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 5.dp),
        )
    }
}

/**
 * EPC 和 TID 配不配对：都满 24 位、前 8 位相同（同一批标签的标志）、
 * 但**后 15 位**不一样。
 *
 * <p>这批标签的规律是第 9 位 EPC=0 / TID=2、其余全同，所以后 15 位一致就等于配对。
 * 不一致的话写完每次读数都会被判不合格，值得在写之前就提醒。
 *
 * <p>前 8 位都不同的情况不提醒 —— 那是用户故意在写一颗完全不同的标签，不是敲错了。
 */
private fun mismatch(epc: String, tid: String): Boolean {
    val e = TagWrite.norm(epc)
    val t = TagWrite.norm(tid)
    if (e.length != TagWrite.FULL || t.length != TagWrite.FULL) return false
    if (e.substring(0, TagWrite.DIFF) != t.substring(0, TagWrite.DIFF)) return false
    return e.takeLast(PAIR_TAIL) != t.takeLast(PAIR_TAIL)
}
