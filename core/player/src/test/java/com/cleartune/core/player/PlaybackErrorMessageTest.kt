package com.cleartune.core.player

import androidx.media3.common.PlaybackException
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackErrorMessageTest {
    @Test
    fun reportsNetworkAndDecodingFailuresWithoutClaimingSongWasDeleted() {
        assertEquals("无法连接音乐服务器，请检查网络",
            playbackErrorMessage(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED))
        assertEquals("播放连接超时，请稍后重试",
            playbackErrorMessage(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT))
        assertEquals("音频格式不支持或文件损坏",
            playbackErrorMessage(PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED))
        assertEquals("服务器未能提供音频，请稍后重试",
            playbackErrorMessage(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS))
    }
}
