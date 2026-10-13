package com.cleartune.core.player

import androidx.media3.common.PlaybackException

internal fun playbackErrorMessage(code: Int): String = when (code) {
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> "无法连接音乐服务器，请检查网络"
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "播放连接超时，请稍后重试"
    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "服务器未能提供音频，请稍后重试"
    PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "音频文件无法读取，正在核对歌曲信息"
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
    PlaybackException.ERROR_CODE_DECODING_FAILED,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> "音频格式不支持或文件损坏"
    else -> "播放失败，请检查网络或音频格式"
}
