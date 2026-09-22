package com.hanwenjiang.uhfrfid.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.animateLottieCompositionAsState
import com.airbnb.lottie.compose.rememberLottieComposition

/*
 * 这一文件放"到处都要用"的小零件：带热区的按钮、状态标签、分节标题、
 * 等宽文本样式、结果动画。界面文件里只写布局，零件都从这里拿，改一处全变。
 *
 * 密度是这一版的重点：卡片内边距 12、元素间距 10、正文 13.5 —— 比 Material
 * 默认紧一档，一屏能多放一行，看着像仪表而不是"移动端 Demo"。
 */

// ---------------------------------------------------------------- 等宽文本样式

/**
 * EPC / TID / 原始报文专用 —— 0 和 O、1 和 I 得能分清。
 *
 * 比正文大一号（15sp）并加了 0.8 的字距：十六进制串是这一行的主角，
 * 密排的等宽字挤在一起很难扫读，拉开一点一眼能数清位数。
 */
val MonoSpan = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 15.sp,
    lineHeight = 20.sp,
    letterSpacing = 0.8.sp,
    fontWeight = FontWeight.Medium,
)

/** 等宽的小号变体：列表里那些次要的十六进制串用它，不抢主角的位置。 */
val MonoSmall = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 11.5.sp,
    lineHeight = 15.sp,
    letterSpacing = 0.4.sp,
)

/** 只读的大字数字（dBm、计数这类"仪表读数"）。 */
val MonoReadout = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 17.sp,
    lineHeight = 21.sp,
    letterSpacing = 0.2.sp,
    fontWeight = FontWeight.Bold,
)

// ---------------------------------------------------------------- 分节卡片

/**
 * 一节：小号大字距的标题 + 圆角卡片内容。设置页和古法写入页用。
 *
 * 标题压到 labelSmall（10.5sp / 字距 1.0），像仪器面板上的丝印标签 ——
 * 比原来 15sp 的正标题省一半高度，又靠字距和颜色保持可读。
 */
@Composable
fun SectionCard(
    title: String?,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (title != null) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 4.dp, bottom = 5.dp),
            )
        }
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                content = content,
            )
        }
    }
}

// ---------------------------------------------------------------- 带右侧热区的按钮

/**
 * 左主区 + 右热区 的按钮。
 *
 * <p>「读标签」右边是上传、「写入」右边是下拉挑标签 —— 两件事挤在一个按钮上，
 * 所以中间画一条竖分隔线：左边是主操作，右边是"这个按钮的另一个用途"。
 *
 * <p>为什么不用 Material 的 Button：Button 的 contentPadding 没法让两个热区都撑满，
 * 而且禁用态的配色改不动。这里自己用 Surface 拼一个，高度统一 [height]，
 * 按下时整颗缩到 0.97（只用 scale，不参与布局，所以按下时旁边的东西不会动）。
 *
 * @param enabled    整个按钮是否可用（含右侧热区）
 * @param rightEnabled 只控制右侧热区能不能点
 * @param hint       按钮下面那行小字；null（默认）就不占地方。
 *                   首页和古法写入页都已经不再传它 —— 那种"这按钮是干嘛的"说明性
 *                   小字用户要求删掉。参数留着，是为了以后真需要一行数据型副标题时
 *                   不用把这段布局重写一遍。
 */
@Composable
fun SplitActionButton(
    text: String,
    onClick: () -> Unit,
    icon: ImageVector? = null,
    rightIcon: ImageVector? = null,
    onRightClick: (() -> Unit)? = null,
    rightContentDescription: String? = null,
    enabled: Boolean = true,
    rightEnabled: Boolean = enabled,
    loading: Boolean = false,
    containerColor: Color = MaterialTheme.colorScheme.primary,
    contentColor: Color = MaterialTheme.colorScheme.onPrimary,
    hint: String? = null,
    hintColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier: Modifier = Modifier,
    height: Dp = 50.dp,
) {
    val shape = RoundedCornerShape(14.dp)
    val bg by animateColorAsState(
        targetValue = if (enabled) containerColor else MaterialTheme.colorScheme.surfaceContainerHigh,
        animationSpec = tween(220),
        label = "按钮底色",
    )
    val fg by animateColorAsState(
        targetValue = if (enabled) contentColor else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(220),
        label = "按钮字色",
    )
    // 禁用时主区压到 60% —— 只是变淡，尺寸一点不变，切换时版面不跳
    val mainAlpha = if (enabled) 1f else 0.6f
    val rightAlpha = if (enabled && rightEnabled) 1f else 0.4f

    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) 0.97f else 1f,
        animationSpec = tween(120),
        label = "按下缩放",
    )

    Column(modifier = modifier.fillMaxWidth()) {
        Surface(
            modifier = Modifier.fillMaxWidth().height(height).scale(scale),
            shape = shape,
            color = bg,
            contentColor = fg,
            tonalElevation = 0.dp,
        ) {
            Row(
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // ---- 主区
                // 注意：主区**永远可点**（只有按下反馈和缩放看 enabled）。
                // 「写入」在主区不可用时，点下去要做的是"打开挑标签弹窗"而不是什么都不干 ——
                // 那种"看得见、点不动"的灰按钮是最让人恼火的。
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .clickable(
                            interactionSource = source,
                            indication = null,
                        ) { onClick() },
                    contentAlignment = Alignment.Center,
                ) {
                    Row(
                        modifier = Modifier.alpha(mainAlpha),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        if (loading) {
                            SpinningRing(color = fg)
                            Spacer(Modifier.width(9.dp))
                        } else if (icon != null) {
                            Icon(icon, contentDescription = null, modifier = Modifier.size(19.dp))
                            Spacer(Modifier.width(9.dp))
                        }
                        Text(
                            text = text,
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                // ---- 右侧热区
                if (onRightClick != null && rightIcon != null) {
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(24.dp)
                            .background(fg.copy(alpha = 0.30f))
                    )
                    Box(
                        modifier = Modifier
                            .width(54.dp)
                            .fillMaxSize()
                            .clip(RoundedCornerShape(topEnd = 14.dp, bottomEnd = 14.dp))
                            .clickable(enabled = enabled && rightEnabled) { onRightClick() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = rightIcon,
                            contentDescription = rightContentDescription,
                            modifier = Modifier.size(22.dp).alpha(rightAlpha),
                        )
                    }
                }
            }
        }

        if (hint != null) {
            Text(
                text = hint,
                style = MaterialTheme.typography.bodySmall,
                color = hintColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 6.dp, top = 4.dp),
            )
        }
    }
}

/** 小转圈：按钮里表示"正在忙"，用 Canvas 画，不占额外空间。 */
@Composable
private fun SpinningRing(color: Color, size: Dp = 17.dp) {
    val transition = rememberInfiniteTransition(label = "转圈")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing)),
        label = "角度",
    )
    Canvas(modifier = Modifier.size(size).rotate(angle)) {
        val w = this.size.minDimension * 0.16f
        drawArc(
            color = color,
            startAngle = 0f,
            sweepAngle = 280f,
            useCenter = false,
            style = Stroke(width = w, cap = StrokeCap.Round),
        )
    }
}

// ---------------------------------------------------------------- 状态标签

/**
 * 顶栏那颗连接状态标签 —— **一句话，不是一段话**。
 *
 * 上一版顶栏塞了两行（App 名 + "USB 直连（OTG 线接读写器）"），副标题又长又占地方，
 * 还把"连没连上"这件真正要紧的事埋在 App 名底下。现在这里只有一个短标签：
 * 有色小圆点 + 「已连接 / 未连接 / 连接中」，颜色走语义色（绿 / 灰 / 蓝）。
 */
@Composable
fun StatusChip(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    pulsing: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    // 连接中的点会呼吸 —— 只改透明度，不动尺寸
    val pulse = rememberInfiniteTransition(label = "状态呼吸")
    val pulseAlpha by pulse.animateFloat(
        initialValue = 1f,
        targetValue = 0.30f,
        animationSpec = infiniteRepeatable(tween(620), repeatMode = androidx.compose.animation.core.RepeatMode.Reverse),
        label = "呼吸透明度",
    )
    val dotAlpha = if (pulsing) pulseAlpha else 1f
    val stableColor by animateColorAsState(targetValue = color, animationSpec = tween(320), label = "状态色")

    Surface(
        modifier = modifier.then(
            if (onClick != null) Modifier.clickable { onClick() } else Modifier
        ),
        shape = RoundedCornerShape(50),
        color = stableColor.copy(alpha = 0.13f),
        border = BorderStroke(1.dp, stableColor.copy(alpha = 0.45f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(color = stableColor.copy(alpha = dotAlpha * 0.95f), size = 7.dp)
            Spacer(Modifier.width(5.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                color = stableColor,
                maxLines = 1,
            )
        }
    }
}

// ---------------------------------------------------------------- 结果动画

/**
 * 连接成功 / 失败的动画。
 *
 * <p>先试 Lottie（assets/connect_ok.json、connect_fail.json）；**读不到就退回
 * Canvas 自己画** —— 拿 PathMeasure 量出勾的长度，把实线按进度截断，就是描边写字的效果。
 * 保底方案不依赖任何资源，所以哪怕 Lottie 的 JSON 有问题，这里也一定看得见东西。
 */
@Composable
fun ResultAnimation(ok: Boolean, size: Dp = 48.dp, modifier: Modifier = Modifier) {
    val composition by rememberLottieComposition(
        LottieCompositionSpec.Asset(if (ok) "connect_ok.json" else "connect_fail.json")
    )
    // 资源读不到（composition 为 null）时直接走 Canvas 那套，不画空白
    val lottieOk = composition != null
    val progress by animateLottieCompositionAsState(
        composition = composition,
        isPlaying = lottieOk,
    )
    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        if (lottieOk) {
            LottieAnimation(
                composition = composition,
                progress = { progress },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            DrawnResultMark(ok = ok, modifier = Modifier.fillMaxSize())
        }
    }
}

/**
 * 保底动画：圆环淡入 + 勾/叉描边生长。
 *
 * <p>用 PathMeasure 量路径总长，按动画进度只画前 [t] 那一段，接上圆头笔帽，
 * 看着就像一笔写出来的。整段动画只改描边的长度，不缩放、不改尺寸。
 */
@Composable
private fun DrawnResultMark(ok: Boolean, modifier: Modifier = Modifier) {
    val color = if (ok) StatusColors.ok() else StatusColors.bad()
    val ring = remember { Animatable(0f) }
    val mark = remember { Animatable(0f) }
    // 失败动画慢一点：一闪而过看不清为什么，还不如让红叉多写一会儿
    val markDuration = if (ok) 480 else 640
    LaunchedEffect(ok) {
        ring.snapTo(0f)
        mark.snapTo(0f)
        ring.animateTo(1f, tween(320))
        mark.animateTo(1f, tween(markDuration))
    }

    val path = remember(ok) {
        Path().apply {
            if (ok) {
                moveTo(0.26f, 0.53f)
                lineTo(0.43f, 0.70f)
                lineTo(0.75f, 0.32f)
            } else {
                moveTo(0.32f, 0.32f)
                lineTo(0.68f, 0.68f)
                moveTo(0.68f, 0.32f)
                lineTo(0.32f, 0.68f)
            }
        }
    }
    Canvas(modifier = modifier) {
        val side = this.size.minDimension
        val inset = side * 0.10f
        val stroke = side * 0.085f

        // 圆环：先画出来，勾再压在上面
        drawArc(
            color = color.copy(alpha = 0.85f * ring.value),
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = Size(side - inset * 2, side - inset * 2),
            style = Stroke(width = side * 0.06f),
        )

        // 勾 / 叉：按进度截断路径
        val measure = PathMeasure()
        measure.setPath(path, false)
        val total = measure.length
        val drawn = Path()
        if (total > 0f) {
            measure.getSegment(0f, total * mark.value.coerceIn(0f, 1f), drawn, true)
        }
        drawPath(
            path = drawn,
            color = color,
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
    }
}

// ---------------------------------------------------------------- 数据行

/** 左侧标签列的宽度 —— 读/写页和设置页共用，两页的左边缘才对得齐。 */
private val LabelColumn = 40.dp

/**
 * 一行「标签：值」，值用等宽字体。
 *
 * 标签窄（40dp）、值大（等宽 15sp），一行的信息量比"上下两行"高得多；
 * 值取不到时用灰色「—」，不会因为空串把行高撑没。
 */
@Composable
fun MonoRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
    valueStyle: TextStyle = MonoSpan,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(LabelColumn).padding(top = 2.dp),
        )
        Text(
            text = value,
            style = valueStyle,
            color = valueColor,
            modifier = Modifier.weight(1f),
        )
    }
}

/** 居中的一条灰提示，列表空的时候用。 */
@Composable
fun EmptyHint(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = modifier.fillMaxWidth().padding(20.dp),
    )
}

/** 一个带内边距、圆角、可选边框的小色块（标签库里标「异常」用）。 */
@Composable
fun MiniChip(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(5.dp))
            .background(color.copy(alpha = 0.14f))
            .border(1.dp, color.copy(alpha = 0.45f), RoundedCornerShape(5.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            letterSpacing = 0.2.sp,
        )
    }
}

/**
 * 带描边的卡片容器（控制面板外层用它做状态边框）。
 *
 * 底色用 surfaceContainerHigh（#F1F2F4）而不是页面底色 —— 页面是纯白，面板差一层很浅的灰，
 * 配上状态色描边，一眼就能看出"这块是仪表盘，它的边框颜色就是现在状态"。
 */
@Composable
fun PanelSurface(
    borderColor: Color,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 0.dp,
        border = BorderStroke(1.5.dp, borderColor),
        content = content,
    )
}

/** 纯色小圆点，放在状态文字前面当指示灯。 */
@Composable
fun StatusDot(color: Color, size: Dp = 8.dp, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(color)
    )
}

/**
 * 一行状态：指示灯 + 窄标签 + 说明。
 *
 * 「古法写入」页底下那组"当前生效"用的就是它 —— 左边一颗语义色小圆点，
 * 一眼看出这行是好是坏，右边才是文字。
 */
@Composable
fun StatusLine(
    label: String,
    value: String,
    color: Color,
    dot: Color,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(color = dot, size = 7.dp)
        Spacer(Modifier.width(7.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(38.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}
