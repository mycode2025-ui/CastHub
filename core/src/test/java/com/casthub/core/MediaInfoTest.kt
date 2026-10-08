package com.casthub.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 媒体信息模型的单元测试。
 *
 * 重点验证「按 URL 后缀判断格式」这条规则 —— 实测发送端给出的 protocolInfo 不可信
 * （夸克把 m3u8 标成 video/mp4），若按 protocolInfo 决定解码方式会直接播放失败。
 */
class MediaInfoTest {

    @Test
    fun `识别 HLS 流`() {
        assertTrue(MediaInfo("http://a/b/c.m3u8").isHls)
        assertTrue(MediaInfo("https://a/b/c.M3U8").isHls)
    }

    @Test
    fun `识别带查询参数的 HLS 流`() {
        // 真实场景：夸克直链形如 .../media.m3u8?auth_key=...&token=...
        val media = MediaInfo(
            "https://video-play-hp-zb.drive.quark.cn/qv/x/media.m3u8" +
                "?auth_key=1791195862-12671-10800-92b07d0e&sp=100&token=4-3-2-1024"
        )
        assertTrue("查询参数不应影响后缀判断", media.isHls)
    }

    @Test
    fun `HLS 的 MIME 不被声明值覆盖`() {
        // 发送端把 m3u8 标成 video/mp4 —— 必须仍然按 HLS 处理
        val media = MediaInfo(
            uri = "http://a/media.m3u8",
            declaredMimeType = "video/mp4",
        )
        assertTrue(media.isHls)
        assertEquals("application/x-mpegURL", media.effectiveMimeType)
    }

    @Test
    fun `非 HLS 时回落到声明值`() {
        val media = MediaInfo("http://a/b.mp4", declaredMimeType = "video/mp4")
        assertFalse(media.isHls)
        assertEquals("video/mp4", media.effectiveMimeType)
    }

    @Test
    fun `识别 DASH`() {
        val media = MediaInfo("http://a/b.mpd")
        assertTrue(media.isDash)
        assertEquals("application/dash+xml", media.effectiveMimeType)
    }

    @Test
    fun `无声明无后缀时 MIME 为空`() {
        assertEquals(null, MediaInfo("http://a/stream").effectiveMimeType)
    }

    @Test
    fun `时长未知时进度为零`() {
        val position = PlaybackPosition(positionMs = 5_000, durationMs = -1, state = PlaybackState.PLAYING)
        assertEquals(0f, position.progress, 0.001f)
    }

    @Test
    fun `进度按比例计算并裁剪到_0_1`() {
        assertEquals(
            0.5f,
            PlaybackPosition(30_000, 60_000, PlaybackState.PLAYING).progress,
            0.001f,
        )
        assertEquals(
            1f,
            PlaybackPosition(90_000, 60_000, PlaybackState.PLAYING).progress,
            0.001f,
        )
    }

    @Test
    fun `播放状态活动性判断`() {
        assertTrue(PlaybackState.PLAYING.isActive)
        assertTrue(PlaybackState.PAUSED.isActive)
        assertTrue(PlaybackState.BUFFERING.isActive)
        assertFalse(PlaybackState.STOPPED.isActive)
        assertFalse(PlaybackState.IDLE.isActive)
        assertFalse(PlaybackState.ERROR.isActive)
    }
}
