package com.hanwenjiang.uhfrfid.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * 配色说明（这一版是「专业仪器」方向，不是 Material You）：
 *
 * 上一版在 Android 12+ 上走动态取色，模拟器出来的是一套很淡的紫灰 —— 发灰、
 * 对比度低、还带着壁纸的味道，跟"射频仪器"这个气质完全不搭。这一版**去掉动态取色**，
 * 手工定一套「深青蓝 + 青绿」：
 *
 *   - primary   = 深青蓝（#00646F 一族），和旧 XML 主题的 #263238 同一个调子但更有色相
 *   - secondary = 仪器面上的青绿，次要操作（写入按钮等）用它
 *   - tertiary  = 钢蓝，用来区分"第三类"元素，不让整个界面只有一种颜色
 *
 * **页面底色是纯白 #FFFFFF，这条是硬要求、不随主题变**：
 *   background = surface = #FFFFFF
 *   surfaceContainerLow(#FAFAFB) < surfaceContainer(#F7F8F9)
 *   < surfaceContainerHigh(#F1F2F4) < surfaceContainerHighest(#EAECEF)
 * 卡片、控制面板用这几档很浅的灰，再配 1dp 描边跟纯白底分开 ——
 * 白色留给"纸面"，灰色只出现在"面板"上。
 *
 * 上一版的 background(#EEF4F6) / surface(#F7FAFB) 那套青灰整个去掉了：青灰底色
 * 在手机屏幕（尤其户外）看着脏，而且"白底 + 细描边"本来就更像仪器面板。
 *
 * 深色那一套（[DarkColors]）**保留但不再启用**：浅色是 [LightColors] 钉死的，
 * [UhfTheme] 不读系统深色模式 —— 用户手机开着深色，这个 App 也是白的。
 */

// ---------------------------------------------------------------- 浅色

private val LightPrimary = Color(0xFF00646F)
private val LightOnPrimary = Color(0xFFFFFFFF)
private val LightPrimaryContainer = Color(0xFFB6EBF4)
private val LightOnPrimaryContainer = Color(0xFF00252B)
private val LightSecondary = Color(0xFF00696E)
private val LightOnSecondary = Color(0xFFFFFFFF)
private val LightSecondaryContainer = Color(0xFFB0ECF0)
private val LightOnSecondaryContainer = Color(0xFF002022)
private val LightTertiary = Color(0xFF2F5F7D)
private val LightOnTertiary = Color(0xFFFFFFFF)
private val LightTertiaryContainer = Color(0xFFC6E4F7)
private val LightOnTertiaryContainer = Color(0xFF001E2E)

/*
 * 白色安全说明：底 / 面都是纯白 #FFFFFF，绝不动；层次只在"面板"上做 ——
 * surfaceContainerLow(#FAFAFB) < surfaceContainer(#F7F8F9)
 * < surfaceContainerHigh(#F1F2F4) < surfaceContainerHighest(#EAECEF)。
 * 这几档灰跟页面底色只差一点点，所以每块面板都再压一条 1dp 描边才分得开。
 */
private val LightBackground = Color(0xFFFFFFFF)
private val LightSurface = Color(0xFFFFFFFF)
private val LightSurfaceContainerLowest = Color(0xFFFFFFFF)
private val LightSurfaceContainerLow = Color(0xFFFAFAFB)
private val LightSurfaceContainer = Color(0xFFF7F8F9)
private val LightSurfaceContainerHigh = Color(0xFFF1F2F4)
private val LightSurfaceContainerHighest = Color(0xFFEAECEF)
private val LightSurfaceVariant = Color(0xFFF1F2F4)
private val LightOnSurface = Color(0xFF141C1E)
private val LightOnSurfaceVariant = Color(0xFF3E4A4E)
private val LightOutline = Color(0xFF6B7A80)
private val LightOutlineVariant = Color(0xFFD5DBDE)

private val LightError = Color(0xFFBA1A1A)
private val LightOnError = Color(0xFFFFFFFF)
private val LightErrorContainer = Color(0xFFFFDAD6)
private val LightOnErrorContainer = Color(0xFF410002)

// ---------------------------------------------------------------- 深色

private val DarkPrimary = Color(0xFF54D6E6)
private val DarkOnPrimary = Color(0xFF00363D)
private val DarkPrimaryContainer = Color(0xFF004E57)
private val DarkOnPrimaryContainer = Color(0xFFACECF7)
private val DarkSecondary = Color(0xFF5FDCDF)
private val DarkOnSecondary = Color(0xFF003739)
private val DarkSecondaryContainer = Color(0xFF004F53)
private val DarkOnSecondaryContainer = Color(0xFF9CF1F4)
private val DarkTertiary = Color(0xFFA5CCE8)
private val DarkOnTertiary = Color(0xFF08344C)
private val DarkTertiaryContainer = Color(0xFF264C64)
private val DarkOnTertiaryContainer = Color(0xFFC6E4F7)

private val DarkBackground = Color(0xFF0B1315)
private val DarkSurface = Color(0xFF0F171A)
private val DarkSurfaceContainerLowest = Color(0xFF070E10)
private val DarkSurfaceContainerLow = Color(0xFF151E21)
private val DarkSurfaceContainer = Color(0xFF192326)
private val DarkSurfaceContainerHigh = Color(0xFF232E32)
private val DarkSurfaceContainerHighest = Color(0xFF2D393D)
private val DarkSurfaceVariant = Color(0xFF3D494D)
private val DarkOnSurface = Color(0xFFDDE4E6)
private val DarkOnSurfaceVariant = Color(0xFFBCC9CD)
private val DarkOutline = Color(0xFF85939A)
private val DarkOutlineVariant = Color(0xFF3B484C)

private val DarkError = Color(0xFFFFB4AB)
private val DarkOnError = Color(0xFF690005)
private val DarkErrorContainer = Color(0xFF93000A)
private val DarkOnErrorContainer = Color(0xFFFFDAD6)

val LightColors = lightColorScheme(
    primary = LightPrimary,
    onPrimary = LightOnPrimary,
    primaryContainer = LightPrimaryContainer,
    onPrimaryContainer = LightOnPrimaryContainer,
    secondary = LightSecondary,
    onSecondary = LightOnSecondary,
    secondaryContainer = LightSecondaryContainer,
    onSecondaryContainer = LightOnSecondaryContainer,
    tertiary = LightTertiary,
    onTertiary = LightOnTertiary,
    tertiaryContainer = LightTertiaryContainer,
    onTertiaryContainer = LightOnTertiaryContainer,
    background = LightBackground,
    onBackground = LightOnSurface,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    surfaceContainerLowest = LightSurfaceContainerLowest,
    surfaceContainerLow = LightSurfaceContainerLow,
    surfaceContainer = LightSurfaceContainer,
    surfaceContainerHigh = LightSurfaceContainerHigh,
    surfaceContainerHighest = LightSurfaceContainerHighest,
    outline = LightOutline,
    outlineVariant = LightOutlineVariant,
    error = LightError,
    onError = LightOnError,
    errorContainer = LightErrorContainer,
    onErrorContainer = LightOnErrorContainer,
)

/**
 * 深色配色 —— **保留但不启用**。
 *
 * <p>用户的要求是"背景必须是纯白，这个不能变"，所以 [UhfTheme] 钉死了 [LightColors]，
 * 这个对象现在没有任何调用点。留着是为了以后真要做深色时不用重画一遍；
 * 它下面那一组 private 的 Dark* 常量正是它用的，不算死代码。
 */
@Suppress("unused")
val DarkColors = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkOnPrimary,
    primaryContainer = DarkPrimaryContainer,
    onPrimaryContainer = DarkOnPrimaryContainer,
    secondary = DarkSecondary,
    onSecondary = DarkOnSecondary,
    secondaryContainer = DarkSecondaryContainer,
    onSecondaryContainer = DarkOnSecondaryContainer,
    tertiary = DarkTertiary,
    onTertiary = DarkOnTertiary,
    tertiaryContainer = DarkTertiaryContainer,
    onTertiaryContainer = DarkOnTertiaryContainer,
    background = DarkBackground,
    onBackground = DarkOnSurface,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    surfaceContainerLowest = DarkSurfaceContainerLowest,
    surfaceContainerLow = DarkSurfaceContainerLow,
    surfaceContainer = DarkSurfaceContainer,
    surfaceContainerHigh = DarkSurfaceContainerHigh,
    surfaceContainerHighest = DarkSurfaceContainerHighest,
    outline = DarkOutline,
    outlineVariant = DarkOutlineVariant,
    error = DarkError,
    onError = DarkOnError,
    errorContainer = DarkErrorContainer,
    onErrorContainer = DarkOnErrorContainer,
)

/**
 * 字体：往「仪器面板」的方向收紧一档 —— 正文 13.5、标签 11、标题 18，
 * 比 Material 默认小半号到一号，同样的屏幕能多放一行信息。
 *
 * EPC / TID 那种十六进制串另外走等宽（见 [MonoSpan] / [MonoValue]），
 * 不然 0 和 O、1 和 I 混在一起看不清。
 */
private val UhfTypography = Typography(
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 26.sp,
        lineHeight = 31.sp,
        letterSpacing = 0.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 21.sp,
        lineHeight = 26.sp,
        letterSpacing = 0.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 18.sp,
        lineHeight = 23.sp,
        letterSpacing = 0.2.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        lineHeight = 17.sp,
        letterSpacing = 0.1.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 21.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 13.5.sp,
        lineHeight = 19.sp,
        letterSpacing = 0.05.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 11.5.sp,
        lineHeight = 15.5.sp,
        letterSpacing = 0.1.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.5.sp,
        lineHeight = 19.sp,
        letterSpacing = 0.3.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 10.5.sp,
        lineHeight = 14.sp,
        letterSpacing = 1.0.sp,   // 分节标题用大字距，一眼看出是"标签"不是正文
    ),
)

/**
 * 圆角：比 Material 默认收一点（卡片 16、按钮 14），仪器类界面方一点更利落。
 */
private val UhfShapes = Shapes(
    extraSmall = RoundedCornerShape(5.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(22.dp),
)

/**
 * 界面语义色 —— 状态灯、边框、结果提示都用这里的颜色。
 *
 * 单独抽出来是因为它们不随主题的「主色」变：合格就得是绿的、不合格就得是红的。
 *
 * <p>**这一版起这四个方法一律返回浅色那支**（不再看 `isSystemInDarkTheme()`）：
 * App 已经钉死浅色主题、底色是纯白，如果手机开着深色模式就返回"深色专用"的亮色，
 * 那些亮绿/亮红压在纯白底上会淡得看不清。深色那支常量留着，以后真要做深色再启用。
 */
object StatusColors {
    /** 合格 / 成功。 */
    val Ok = Color(0xFF1B8A4B)
    val OkDark = Color(0xFF5FD98A)

    /** 不合格 / 失败。 */
    val Bad = Color(0xFFC62828)
    val BadDark = Color(0xFFFF8A80)

    /** 进行中 / 命中云端。 */
    val Busy = Color(0xFF1565C0)
    val BusyDark = Color(0xFF82BDF7)

    /** 待机（灰）。 */
    val Idle = Color(0xFF7A8B92)
    val IdleDark = Color(0xFF82969E)

    /**
     * 注意 / 需要人过目（不是失败）。
     *
     * 标签库里"异常"那条用琥珀色而不是红色：红色在这个 App 里已经归"写入失败 /
     * 不合格"了，异常只是"这条数据脏、值得看一眼"，两个语义分开才不会被误读。
     */
    val Warn = Color(0xFFB26A00)
    val WarnDark = Color(0xFFFFBE6B)

    /** 合格 / 成功 —— 浅色主题下用 600 号色，压在纯白底上对比度够。 */
    fun ok(): Color = Ok

    /** 不合格 / 失败。 */
    fun bad(): Color = Bad

    /** 进行中 / 命中云端。 */
    fun busy(): Color = Busy

    /** 待机（灰）。 */
    fun idle(): Color = Idle

    /** 注意（琥珀）—— 不是失败，只是"值得看一眼"。 */
    fun warn(): Color = Warn
}

/**
 * 全App 的主题包裹。
 *
 * **两件钉死的事**：
 * <ol>
 *   <li>**不接动态取色**（上一版接了，在模拟器上出来是一套很淡的紫灰，被否了）。</li>
 *   <li>**只用浅色**：颜色固定 [LightColors]，参数和函数体里都不出现
 *       `isSystemInDarkTheme()` —— 用户手机开着深色模式，这个 App 的底色
 *       照样是纯白 #FFFFFF，不会变黑。</li>
 * </ol>
 * 深色那套 [DarkColors] 保留着不删，以后真要支持深色时在这里换一下就行。
 */
@Composable
fun UhfTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColors,
        typography = UhfTypography,
        shapes = UhfShapes,
        content = content,
    )
}
