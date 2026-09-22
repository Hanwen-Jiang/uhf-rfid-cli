package com.hanwenjiang.uhfrfid.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hanwenjiang.uhfrfid.AppState
import com.hanwenjiang.uhfrfid.Prefs
import com.hanwenjiang.uhfrfid.R
import com.hanwenjiang.uhfrfid.TagOps
import kotlin.math.roundToInt

/**
 * 设置：读写器参数 + 云端地址。
 *
 * <p>这里的每一项都是"改了要存下来"的，所以统一走一个「保存设置」按钮，
 * 而不是像开关那样一点就写 —— 天线口和功率这类参数改一半就生效，
 * 有可能正好在读写过程中把连接搞乱。保存前只动本地状态，不动 Prefs。
 *
 * <p>这一版把天线口从四个 FilterChip 换成 SingleChoiceSegmentedButtonRow（M3 里
 * 单选项就该用它），功率读数换成等宽粗体，两个底部操作并排一行。
 */
@Composable
fun SettingsScreen(tagOps: TagOps) {
    val ctx = LocalContext.current

    // 本地暂存：进页面时从 Prefs 读一次，点保存才写回去
    var antenna by remember { mutableStateOf(Prefs.antenna(ctx)) }
    var power by remember { mutableFloatStateOf(Prefs.power(ctx).toFloat()) }
    var restartOnConnect by remember { mutableStateOf(Prefs.restartOnConnect(ctx)) }
    var muteBeep by remember { mutableStateOf(Prefs.muteBeep(ctx)) }
    var autoUpload by remember { mutableStateOf(Prefs.autoUpload(ctx)) }
    var base by remember { mutableStateOf(Prefs.base(ctx)) }
    var apiKey by remember { mutableStateOf(Prefs.apiKey(ctx)) }
    var operator by remember { mutableStateOf(Prefs.operator(ctx)) }

    /** 保存/恢复后的回执文字。 */
    var message by remember { mutableStateOf("") }

    /** 两个 Hex 输入框的展开状态（默认收起，省得它们把页面撑得老长）。 */
    var baseOpen by remember { mutableStateOf(false) }
    var keyOpen by remember { mutableStateOf(false) }

    val deviceName = remember { Prefs.deviceName(ctx) }
    val savedText = stringResource(
        R.string.settings_saved,
        antenna,
        power.roundToInt(),
    )
    val resetText = stringResource(R.string.cloud_reset_done)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ---------------------------------------------------------- 读写器
        SectionCard(title = stringResource(R.string.radio_section)) {

            Text(
                text = stringResource(R.string.antenna_label),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                (1..4).forEachIndexed { index, port ->
                    SegmentedButton(
                        selected = antenna == port,
                        onClick = { antenna = port },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = 4),
                        modifier = Modifier.height(38.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.antenna_port, port),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.power_label),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = stringResource(R.string.settings_power_unit, power.roundToInt()),
                        style = MonoReadout,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Slider(
                    value = power,
                    onValueChange = { power = it },
                    valueRange = 0f..30f,
                    steps = 29,
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            SwitchRow(
                title = stringResource(R.string.restart),
                subtitle = null,
                checked = restartOnConnect,
                onCheckedChange = { restartOnConnect = it },
            )
            SwitchRow(
                title = stringResource(R.string.mute),
                subtitle = null,
                checked = muteBeep,
                onCheckedChange = { muteBeep = it },
            )
            SwitchRow(
                title = stringResource(R.string.cloud_auto_upload),
                // 副标题只在"关掉会有后果"的时候出现 —— 它说的是当前状态（自动上传已关），
                // 属于数据，不是解释，保留
                subtitle = stringResource(R.string.cloud_auto_off_hint),
                checked = autoUpload,
                onCheckedChange = { autoUpload = it },
            )
        }

        // ---------------------------------------------------------- 云端
        SectionCard(title = stringResource(R.string.cloud_section)) {

            Column {
                OutlinedTextField(
                    value = operator,
                    onValueChange = { operator = it },
                    label = { Text(stringResource(R.string.cloud_operator_hint)) },
                    singleLine = true,
                    shape = MaterialTheme.shapes.small,
                    // 默认值直接写在 helper text 里，省得用户猜"空着会怎样"
                    supportingText = {
                        Text(
                            text = stringResource(R.string.cloud_operator_default, deviceName),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // 地址和 key 平时是内置好的，默认收起来 —— 展开才占地方
            AdvancedField(
                label = stringResource(R.string.cloud_base_hint),
                value = base,
                placeholder = stringResource(R.string.not_set),
                expanded = baseOpen,
                onToggle = { baseOpen = !baseOpen },
                onValueChange = { base = it },
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Next,
            )
            AdvancedField(
                label = stringResource(R.string.cloud_key_hint),
                value = apiKey,
                placeholder = stringResource(R.string.not_set),
                expanded = keyOpen,
                onToggle = { keyOpen = !keyOpen },
                onValueChange = { apiKey = it },
                keyboardType = KeyboardType.Ascii,
                imeAction = ImeAction.Done,
            )

            // 补全数据有多少条 —— 古法写入那边能补出什么全看这个
            val cacheOk = AppState.cacheCount > 0
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(
                    color = if (cacheOk) StatusColors.ok() else StatusColors.warn(),
                    size = 7.dp,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (cacheOk) {
                        stringResource(R.string.cache_count, AppState.cacheCount)
                    } else {
                        stringResource(R.string.cache_empty)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (cacheOk) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        StatusColors.warn()
                    },
                )
            }

            // 这一版把原来的 cloud_setup_tip（三句话讲地址/key/使用者怎么来的）
            // 和页尾的 settings_tip（一大段讲"连接后复位""关蜂鸣器""读完自动上传"）
            // 都删了 —— 全是解释文字，用户明确不要。设置项自己的标题、
            // 输入框的 label、以及"默认用手机名：xxx"这种当前值都留着。
        }

        // ---------------------------------------------------------- 底部操作
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SplitActionButton(
                text = stringResource(R.string.save_settings),
                icon = Icons.Filled.Save,
                onClick = {
                    Prefs.setAntenna(ctx, antenna)
                    Prefs.setPower(ctx, power.roundToInt())
                    Prefs.setRestartOnConnect(ctx, restartOnConnect)
                    Prefs.setMuteBeep(ctx, muteBeep)
                    Prefs.setAutoUpload(ctx, autoUpload)
                    Prefs.setBase(ctx, base)
                    Prefs.setApiKey(ctx, apiKey)
                    Prefs.setOperator(ctx, operator)
                    // 地址/key/使用者都变了，缓存得按新配置重拉一遍
                    tagOps.refreshCacheAsync()
                    message = savedText
                },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.weight(1.35f),
                height = 46.dp,
            )
            SplitActionButton(
                text = stringResource(R.string.cloud_reset),
                icon = Icons.Filled.Restore,
                onClick = {
                    // 清空 = 回到编译内置的默认值（Prefs 里就是这么实现的）
                    Prefs.setBase(ctx, "")
                    Prefs.setApiKey(ctx, "")
                    Prefs.setOperator(ctx, "")
                    Prefs.setAntenna(ctx, 1)
                    Prefs.setPower(ctx, 26)
                    Prefs.setRestartOnConnect(ctx, true)
                    Prefs.setMuteBeep(ctx, true)
                    Prefs.setAutoUpload(ctx, true)

                    // 界面上的暂存值也跟着回到默认，不然显示的还是旧值
                    base = Prefs.base(ctx)
                    apiKey = Prefs.apiKey(ctx)
                    operator = Prefs.operator(ctx)
                    antenna = 1
                    power = 26f
                    restartOnConnect = true
                    muteBeep = true
                    autoUpload = true

                    tagOps.refreshCacheAsync()
                    message = resetText
                },
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
                height = 46.dp,
            )
        }

        if (message.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(color = StatusColors.ok(), size = 7.dp)
                Spacer(Modifier.width(6.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = StatusColors.ok(),
                )
            }
        }

        Spacer(Modifier.height(4.dp))
    }
}

/**
 * 折叠的输入框：平时只显示一行「标签 + 当前值」，点一下才展开成可编辑的输入框。
 *
 * <p>地址和 key 都是内置好的，绝大多数人一辈子不用改 —— 让它们一直张着两个大输入框，
 * 就把"天线口 / 功率"这些天天要动的参数挤到屏幕外面去了。
 */
@Composable
private fun AdvancedField(
    label: String,
    value: String,
    placeholder: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    onValueChange: (String) -> Unit,
    keyboardType: KeyboardType,
    imeAction: ImeAction,
) {
    if (!expanded) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onToggle() },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(76.dp),
            )
            Text(
                text = value.ifEmpty { placeholder },
                style = MonoSmall,
                color = if (value.isEmpty()) {
                    MaterialTheme.colorScheme.outline
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(R.string.edit_field),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    } else {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = stringResource(R.string.collapse_field),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { onToggle() },
                )
            }
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                shape = MaterialTheme.shapes.small,
                textStyle = MonoSmall,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    keyboardType = keyboardType,
                    imeAction = imeAction,
                ),
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
        }
    }
}

/** 开关一行：左边标题（可选副标题），右边开关。副标题只在关掉的时候显示。 */
@Composable
private fun SwitchRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyMedium)
            if (subtitle != null && !checked) {
                // 副标题只在"关掉会有后果"的时候显示，开着的时候不用占地方
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
