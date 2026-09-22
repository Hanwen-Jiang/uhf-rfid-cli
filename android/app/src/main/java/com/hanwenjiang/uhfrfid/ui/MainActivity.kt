package com.hanwenjiang.uhfrfid.ui

import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.remember
import com.hanwenjiang.uhfrfid.TagOps
import com.hanwenjiang.uhfrfid.UsbConnect

/**
 * 唯一的 Activity。
 *
 * <p>旧版这里是 4 个 Fragment + 2 个 Activity，界面全在 XML 里；现在整层界面换成
 * Compose，Activity 只剩下"把系统事件转交给 Compose"这一件事：
 *
 * <ul>
 *   <li>持有 [UsbConnect] 和 [TagOps]（都是纯逻辑类，不碰控件）</li>
 *   <li>把 USB 广播接收器注册上 —— 拔插读写器、授权结果都靠它</li>
 *   <li>回前台时催一次自动连接</li>
 * </ul>
 *
 * <p>**故意不在 onDestroy 里断开连接**：转屏、切深色模式、被系统回收重建都会走
 * onDestroy，断在这儿等于"用着用着就掉线了"。连接的生命周期跟着
 * [com.hanwenjiang.uhfrfid.RfidSession] 那个单例走，只有拔线和用户主动断开才断。
 *
 * <p>类上带 `open`：清单里声明的还是 `com.hanwenjiang.uhfrfid.MainActivity`
 * （根包），那边留了一个只做继承的空壳指向这里，清单一个字都不用动。
 */
open class MainActivity : ComponentActivity() {

    private lateinit var usbConnect: UsbConnect
    private lateinit var tagOps: TagOps

    /** 别重复注册：onResume 可能来很多次。 */
    private var receiverRegistered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        usbConnect = UsbConnect(this)
        tagOps = TagOps(this)

        registerUsbReceiver()

        // 打开 App 就把补全数据拉回来，古法写入那边一敲字就有东西可补
        tagOps.refreshCacheAsync()

        setContent {
            UhfTheme {
                // Activity 重建（转屏等）时 UsbConnect/TagOps 会跟着重建，
                // 所以用 remember 把这一份实例钉在组合里，别在重组里再造新的。
                val usb = remember { usbConnect }
                val ops = remember { tagOps }
                App(usbConnect = usb, tagOps = ops)
            }
        }
    }

    /**
     * 注册 USB 广播。
     *
     * <p>Android 13（API 33）之后注册广播必须显式声明导不导出。这三个动作里
     * `ACTION_USB_PERMISSION` 是发给自己进程的（PendingIntent 里已经 setPackage），
     * 插拔广播是系统的，所以用 NOT_EXPORTED —— 既够用又不会被别的 App 伪造。
     */
    private fun registerUsbReceiver() {
        if (receiverRegistered) return
        val filter = UsbConnect.filter()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbConnect.receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(usbConnect.receiver, filter)
        }
        receiverRegistered = true
    }

    private fun unregisterUsbReceiver() {
        if (!receiverRegistered) return
        try {
            unregisterReceiver(usbConnect.receiver)
        } catch (ignored: IllegalArgumentException) {
            // 没注册上也会走到这儿；忽略
        }
        receiverRegistered = false
    }

    override fun onResume() {
        super.onResume()
        // 插上读写器 / 从后台回来：能连就连（UsbConnect 自己保证一次只连一个）
        usbConnect.autoConnect()
    }

    override fun onDestroy() {
        unregisterUsbReceiver()
        // 注意：这里**不**断开读写器 —— 见类注释
        super.onDestroy()
    }
}
