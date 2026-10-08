package com.casthub.app

import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.casthub.core.PictureMode
import com.casthub.core.TakeoverPolicy

object PlaybackSettings {
    fun show(activity: AppCompatActivity, changed: () -> Unit = {}) {
        val store = (activity.application as CastHubApplication).store
        val labels = arrayOf(
            "自动重试：${if (store.autoRetry()) "开启（最多 3 次）" else "关闭"}",
            "接管规则：${store.takeoverPolicy().label}",
            "画面：${store.pictureMode().label}",
            "字幕字号：${store.subtitleSize()}%",
            "字幕离底部：${store.subtitleBottom()}%",
            "字幕时间偏移：${store.subtitleOffsetMs() / 1000.0} 秒（正值延后）",
            "卡住检测：${if (store.stallRecovery()) "开启" else "关闭"}",
            "历史与续播：${if (store.historyEnabled()) "开启" else "关闭"}",
            "查看 / 清空播放历史",
            "音轨与字幕偏好",
        )
        fun pick(title: String, options: List<String>, selected: Int, apply: (Int) -> Unit) {
            AlertDialog.Builder(activity).setTitle(title)
                .setSingleChoiceItems(options.toTypedArray(), selected) { dialog, index ->
                    apply(index); changed(); dialog.dismiss(); show(activity, changed)
                }.setNegativeButton("关闭", null).show()
        }
        AlertDialog.Builder(activity).setTitle("播放与接管设置").setItems(labels) { _, index ->
            when (index) {
                0 -> { store.setAutoRetry(!store.autoRetry()); changed(); show(activity, changed) }
                1 -> pick("接管规则", TakeoverPolicy.entries.map { it.label }, store.takeoverPolicy().ordinal) { store.setTakeoverPolicy(TakeoverPolicy.entries[it]) }
                2 -> pick("画面模式", PictureMode.entries.map { it.label }, store.pictureMode().ordinal) { store.setPictureMode(PictureMode.entries[it]) }
                3 -> {
                    val values = listOf(50, 75, 100, 125, 150, 175, 200)
                    pick("字幕字号", values.map { "$it%" }, values.indexOf(store.subtitleSize())) { store.setSubtitleSize(values[it]) }
                }
                4 -> {
                    val values = listOf(0, 8, 15, 25, 40, 60, 80)
                    pick("字幕离底部", values.map { "$it%" }, values.indexOf(store.subtitleBottom())) { store.setSubtitleBottom(values[it]) }
                }
                5 -> {
                    val values = listOf(-10000L, -5000, -3000, -2000, -1000, -500, 0, 500, 1000, 2000, 3000, 5000, 10000)
                    pick("字幕时间偏移（正值延后）", values.map { "${it / 1000.0} 秒" }, values.indexOf(store.subtitleOffsetMs())) { store.setSubtitleOffsetMs(values[it]) }
                }
                6 -> { store.setStallRecovery(!store.stallRecovery()); show(activity, changed) }
                7 -> { store.setHistoryEnabled(!store.historyEnabled()); show(activity, changed) }
                8 -> PlaybackExtras.history(activity)
                9 -> PlaybackExtras.preferences(activity)
            }
        }.setNegativeButton("关闭", null).show()
    }
}
