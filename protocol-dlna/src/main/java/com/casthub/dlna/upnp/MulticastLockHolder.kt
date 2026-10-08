package com.casthub.dlna.upnp

import android.content.Context
import android.net.wifi.WifiManager
import com.casthub.core.CastLogger

/**
 * Wi-Fi 多播锁持有器。
 *
 * ── 为什么必须有它（真机测试暴露的关键问题）──────────────────────────
 * Android 为省电默认会在 Wi-Fi 芯片层**过滤掉入站多播/广播包**。
 * 应用若不持有 `WifiManager.MulticastLock`，`MulticastSocket.receive()`
 * 将永远收不到任何数据 —— 表现为：
 *   - SSDP 的 `ssdp:alive` 通告能正常**发出**（发送不受限）；
 *   - 但对端发来的 `M-SEARCH` **一个都收不到**；
 *   - 结果就是投屏 App 的设备列表里**永远看不到本机**。
 *
 * 真机验证：未加锁时收到的 M-SEARCH 数量为 0；加锁后即可正常响应。
 *
 * 注意：需要在 AndroidManifest 中声明 `CHANGE_WIFI_MULTICAST_STATE` 权限，
 * 两者缺一不可。
 */
internal class MulticastLockHolder(context: Context, private val tag: String) {

    private val wifiManager: WifiManager? =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    private var lock: WifiManager.MulticastLock? = null

    /** 获取多播锁。返回是否成功。幂等。 */
    fun acquire(): Boolean {
        lock?.let { if (it.isHeld) return true }

        val manager = wifiManager ?: run {
            CastLogger.w(TAG, "[$tag] 无 WifiManager，无法获取多播锁（有线网络环境下多播通常不受限）")
            return false
        }

        return runCatching {
            lock = manager.createMulticastLock(tag).apply {
                // 单次获取即可，避免多次 acquire/release 计数不一致
                setReferenceCounted(false)
                acquire()
            }
            CastLogger.i(TAG, "[$tag] 已获取 Wi-Fi 多播锁")
            true
        }.getOrElse { t ->
            CastLogger.w(TAG, "[$tag] 获取多播锁失败：${t.message}", t)
            false
        }
    }

    /** 释放多播锁。幂等。 */
    fun release() {
        val current = lock ?: return
        lock = null
        runCatching {
            if (current.isHeld) current.release()
            CastLogger.i(TAG, "[$tag] 已释放 Wi-Fi 多播锁")
        }.onFailure { CastLogger.w(TAG, "[$tag] 释放多播锁失败", it) }
    }

    companion object {
        private const val TAG = "MulticastLock"
    }
}
