package com.casthub.app

import android.content.Intent
import android.net.Uri
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.casthub.core.LocalPlaybackControl
import com.casthub.core.PlaybackHistory

object PlaybackExtras {
    private val languages = listOf("", "zh", "en", "ja", "ko", "fr", "de", "es")
    private val names = arrayOf("自动", "中文", "英语", "日语", "韩语", "法语", "德语", "西班牙语")
    fun preferences(activity: AppCompatActivity) {
        val app = activity.application as CastHubApplication
        val store = app.store
        val items = arrayOf("音轨首选语言：${store.audioLanguage().ifEmpty { "自动" }}",
            "字幕首选语言：${store.textLanguage().ifEmpty { "自动" }}",
            "字幕默认：${if (store.subtitlesEnabled()) "开启" else "关闭"}")
        fun language(audio: Boolean) {
            val current = if (audio) store.audioLanguage() else store.textLanguage()
            AlertDialog.Builder(activity).setTitle(if (audio) "音轨首选语言" else "字幕首选语言")
                .setSingleChoiceItems(names, languages.indexOf(current)) { dialog, index ->
                    if (audio) store.setAudioLanguage(languages[index]) else { store.setTextLanguage(languages[index]); store.setSubtitlesEnabled(true) }
                    app.activeControl()?.applyTrackPreferences()
                    dialog.dismiss(); preferences(activity)
                }.setNegativeButton("关闭", null).show()
        }
        AlertDialog.Builder(activity).setTitle("音轨与字幕偏好").setItems(items) { _, index ->
            when (index) {
                0 -> language(true)
                1 -> language(false)
                2 -> { store.setSubtitlesEnabled(!store.subtitlesEnabled()); app.activeControl()?.applyTrackPreferences(); preferences(activity) }
            }
        }.setNegativeButton("关闭", null).show()
    }
    fun history(activity: AppCompatActivity) {
        val store = PlaybackHistory(activity)
        val entries = store.entries()
        val items = entries.map { "${it.title} · ${it.positionMs / 1000} / ${it.durationMs / 1000} 秒" }.toTypedArray()
        AlertDialog.Builder(activity).setTitle("播放历史（最多 50 条）")
            .apply {
                if (items.isEmpty()) setMessage("暂无记录。再次投送同一地址时可以继续播放；历史不保存视频地址。")
                else setItems(items) { _, index -> AlertDialog.Builder(activity).setTitle(entries[index].title)
                    .setMessage("记录位置：${entries[index].positionMs / 1000} 秒。请从手机重新投送同一视频；旧地址可能已过期，历史不保存地址或访问凭据。")
                    .setPositiveButton("确定", null).show() }
            }.setNeutralButton("清空记录") { _, _ ->
                AlertDialog.Builder(activity).setTitle("清空播放历史？").setMessage("将删除本机保存的全部播放进度。")
                    .setPositiveButton("清空") { _, _ -> store.clear(); history(activity) }.setNegativeButton("取消", null).show()
            }.setNegativeButton("关闭", null).show()
    }
    fun externalMenu(activity: AppCompatActivity, control: LocalPlaybackControl, pickFile: () -> Unit) {
        AlertDialog.Builder(activity).setTitle("外部字幕").setItems(arrayOf("输入 SRT / VTT 地址", "选择本地字幕文件", "移除外部字幕")) { _, index ->
            when (index) {
                0 -> {
                    val input = EditText(activity).apply { hint = "http://…/字幕.srt 或 https://…/字幕.vtt"; inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI }
                    val dialog = AlertDialog.Builder(activity).setTitle("外部字幕地址").setView(input)
                        .setPositiveButton("加载", null).setNegativeButton("取消", null).create()
                    dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val address = input.text.toString().trim()
                        val parsed = Uri.parse(address)
                        val extension = parsed.path.orEmpty().substringAfterLast('.').lowercase()
                        if (parsed.scheme !in listOf("http", "https") || extension !in listOf("srt", "vtt")) { input.error = "请输入 HTTP / HTTPS 的 .srt 或 .vtt 地址"; return@setOnClickListener }
                        load(activity, control, address, extension == "vtt"); dialog.dismiss()
                    } }; dialog.show()
                }
                1 -> pickFile()
                2 -> load(activity, control, null, false)
            }
        }.setNegativeButton("关闭", null).show()
    }
    fun load(activity: AppCompatActivity, control: LocalPlaybackControl, uri: String?, vtt: Boolean) {
        val ok = control.externalSubtitle(uri, if (vtt) "text/vtt" else "application/x-subrip")
        Toast.makeText(activity, if (ok) "字幕设置已应用" else "当前媒体无法加载字幕", Toast.LENGTH_LONG).show()
    }
}
