package com.casthub.app

import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.casthub.core.LocalPlaybackControl
import com.casthub.core.MediaInfo
import java.net.URI

object PlaybackQueueUi {
    fun show(activity: AppCompatActivity, control: LocalPlaybackControl) {
        val entries = control.queueEntries()
        val store = (activity.application as CastHubApplication).store
        fun feedback(ok: Boolean) {
            if (!ok) Toast.makeText(activity, "无法执行：已到边界或队列已变化", Toast.LENGTH_SHORT).show()
            if (control.queueEntries().isNotEmpty()) show(activity, control)
        }
        val labels = listOf("上一集", "下一集", "添加视频地址", "播放模式：${store.playbackMode().label}") + entries.mapIndexed { index, entry ->
            "${if (entry.id == control.queueCurrentId()) "▶ " else ""}${index + 1}. ${entry.media.title ?: "视频 ${index + 1}"}"
        }
        AlertDialog.Builder(activity).setTitle("播放队列（${entries.size}/100）")
            .setItems(labels.toTypedArray()) { _, index ->
                when (index) {
                    0 -> feedback(control.queueStep(-1))
                    1 -> feedback(control.queueStep(1))
                    2 -> {
                        val input = EditText(activity).apply {
                            hint = "http / https 视频地址，每行一条"
                            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                            minLines = 2
                        }
                        val dialog = AlertDialog.Builder(activity).setTitle("添加视频地址").setView(input)
                            .setPositiveButton("添加", null).setNegativeButton("取消", null).create()
                        dialog.setOnShowListener {
                            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                                val urls = input.text.toString().lines().map(String::trim).filter(String::isNotEmpty)
                                val valid = urls.isNotEmpty() && urls.all { url -> runCatching {
                                    val uri = URI(url)
                                    uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank()
                                }.getOrDefault(false) }
                                if (!valid || control.queueEntries().size + urls.size > 100) {
                                    input.error = "请输入有效地址，队列最多 100 条"; return@setOnClickListener
                                }
                                val ok = urls.all { control.queueAdd(MediaInfo(it)) }
                                dialog.dismiss(); feedback(ok)
                            }
                        }
                        dialog.show()
                    }
                    3 -> AlertDialog.Builder(activity).setTitle("队列播放模式")
                        .setSingleChoiceItems(com.casthub.core.PlaybackMode.entries.map { it.label }.toTypedArray(), store.playbackMode().ordinal) { dialog, mode ->
                            store.setPlaybackMode(com.casthub.core.PlaybackMode.entries[mode])
                            control.refreshTransportSettings()
                            dialog.dismiss(); show(activity, control)
                        }.setNegativeButton("关闭", null).show()
                    else -> {
                        val entry = entries[index - 4]
                        AlertDialog.Builder(activity).setTitle(entry.media.title ?: "视频 ${index - 3}")
                            .setItems(arrayOf("播放此项", "上移", "下移", "删除")) { _, action ->
                                if (action == 3 && entry.id == control.queueCurrentId()) {
                                    AlertDialog.Builder(activity).setTitle("删除正在播放的项目？")
                                        .setMessage("将播放队列中的其他项目；若只剩这一项，将结束投屏。")
                                        .setPositiveButton("删除") { _, _ -> feedback(control.queueRemove(entry.id)) }
                                        .setNegativeButton("取消", null).show()
                                } else feedback(when (action) {
                                    0 -> control.queuePlay(entry.id)
                                    1 -> control.queueMove(entry.id, -1)
                                    2 -> control.queueMove(entry.id, 1)
                                    else -> control.queueRemove(entry.id)
                                })
                            }.setNegativeButton("关闭", null).show()
                    }
                }
            }.setNegativeButton("关闭", null).show()
    }
}
