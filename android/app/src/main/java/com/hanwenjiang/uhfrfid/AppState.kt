package com.hanwenjiang.uhfrfid

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 界面的可观察状态。
 *
 * <p>为什么要有这一层：读写标签、连接读写器的逻辑都在 Java 里（那部分是逆协议逆出来的，
 * 不动它最安全），而 Compose 需要"能观察的状态"才会自动重组。所以 Java 那边往这里写，
 * Compose 这边读 —— 两边通过它对接，谁都不用改自己的写法。
 *
 * <p>Java 调用方式：`AppState.INSTANCE.setStatus("...")`。
 */
object AppState {

    // ---------------------------------------------------------------- 连接

    /** 顶栏那颗 USB 图标的状态。 */
    var connected by mutableStateOf(false)

    /** 正在连接中（要显示进度条）。 */
    var connecting by mutableStateOf(false)

    /** 连接进行到哪一步。 */
    var connectStep by mutableStateOf("")

    /**
     * 连接结果：null = 还没结果 / true = 成功 / false = 失败。
     * 成功后界面会弹一下动画，然后自己清回 null。
     */
    var connectResult by mutableStateOf<Boolean?>(null)

    /** 连接结果的文字（成功是固件/天线信息，失败是原因）。 */
    var connectMessage by mutableStateOf("")

    // ---------------------------------------------------------------- 读

    /** 控制面板上那行状态文字。 */
    var status by mutableStateOf("插上 OTG 线和读写器就会自动连接。")

    var epc by mutableStateOf("—")
    var tid by mutableStateOf("—")

    /** 完整性校验：null = 还没读 / true = 合格 / false = 不合格。 */
    var checkOk by mutableStateOf<Boolean?>(null)
    var checkText by mutableStateOf("")

    /** 命中云端时显示的名字和说明；没命中就是 null。 */
    var hitName by mutableStateOf<String?>(null)
    var hitSub by mutableStateOf<String?>(null)

    /** 刚写完的标记 —— 写着"刚写入 ✓"，和"命中云端"区分开。 */
    var justWritten by mutableStateOf(false)

    // ---------------------------------------------------------------- 写

    /** 挑好准备写进去的那颗。 */
    var target by mutableStateOf<CloudClient.Tag?>(null)

    /** 是否正在写入。 */
    var writing by mutableStateOf(false)


    // ---------------------------------------------------------------- 命名 / 写入结果

    /** 刚上传、还没命名的那条 —— 界面弹命名框用；null 表示不用弹。 */
    var nameTag by mutableStateOf<CloudClient.Tag?>(null)

    /** 上一次写入的结果：null = 没写过 / true = 成功 / false = 失败。 */
    var writeOk by mutableStateOf<Boolean?>(null)

    // ---------------------------------------------------------------- 标签库

    val tags = mutableStateListOf<CloudClient.Tag>()
    var tagsLoading by mutableStateOf(false)
    var tagsStatus by mutableStateOf("")
    var onlyUnnamed by mutableStateOf(false)

    // ---------------------------------------------------------------- 古法写入

    var manualEpc by mutableStateOf("")
    var manualTid by mutableStateOf("")
    var manualStatus by mutableStateOf("")
    var rawLog by mutableStateOf("")

    // ---------------------------------------------------------------- 设置

    var cacheCount by mutableStateOf(0)

    // ---------------------------------------------------------------- 便利方法（给 Java 用）

    /** 一次把"读到一颗标签"的结果写进来。 */
    @JvmStatic
    fun setRead(epc: String, tid: String, checkOk: Boolean?, checkText: String) {
        this.epc = epc
        this.tid = tid
        this.checkOk = checkOk
        this.checkText = checkText
    }

    /** 清掉命中云端的显示。 */
    @JvmStatic
    fun clearHit() {
        hitName = null
        hitSub = null
        justWritten = false
    }

    @JvmStatic
    fun setHit(name: String?, sub: String?, written: Boolean) {
        hitName = name
        hitSub = sub
        justWritten = written
    }

    @JvmStatic
    fun setTags(list: List<CloudClient.Tag>?) {
        tags.clear()
        if (list != null) tags.addAll(list)
        cacheCount = tags.size
    }
}
