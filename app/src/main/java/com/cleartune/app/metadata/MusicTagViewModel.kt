package com.cleartune.app.metadata

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cleartune.core.model.*
import com.cleartune.core.network.MusicTagClient
import com.cleartune.core.network.MusicTagException
import com.cleartune.core.network.normalizeMusicTagAddress
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow

data class MusicTagUiState(
    val settings: MusicTagSettings = MusicTagSettings(),
    val settingsShown: Boolean = false,
    val song: Song? = null,
    val busy: Boolean = false,
    val progress: String = "",
    val message: String? = null,
    val isError: Boolean = false,
    val sourcePath: String? = null,
    val targetPath: String? = null,
    val artworkPath: String? = null,
    val searched: Boolean = false,
    val queryTitle: String = "",
    val queryArtist: String = "",
    val candidates: List<MusicTagCandidate> = emptyList(),
    val snapshot: MusicTagSnapshot? = null,
    val values: Map<MusicTagField, String> = emptyMap(),
    val candidateValues: Map<MusicTagField, String> = emptyMap(),
    val selected: Set<MusicTagField> = emptySet(),
    val pending: Boolean = false,
    val fileSaved: Boolean = false,
    val syncing: Boolean = false,
    val coverReview: MusicTagCoverReview? = null,
    val syncVersion: Int = 0,
    val fillVersion: Int = 0,
) {
    val changes: Map<MusicTagField, String> get() = musicTagDraftChanges(snapshot, values)
    val dirty: Boolean get() = changes.isNotEmpty()
}

data class MusicTagSyncStatus(val song: Song, val running: Boolean, val attempt: Long)

internal fun musicTagSyncFinished(state: MusicTagUiState, song: Song, synced: Boolean): MusicTagUiState {
    if (state.song?.id != song.id || (!state.pending && state.dirty)) return state
    return state.copy(song = song, pending = !synced, syncing = false, fileSaved = true,
        selected = emptySet(), isError = false, syncVersion = state.syncVersion + if (synced) 1 else 0,
        message = if (synced) "标签已保存，曲库已同步；已下载音频仍是旧副本，可按需重新下载"
            else "标签已保存，曲库同步未完成，可重试同步，不会重复写入标签")
}

internal fun musicTagDraftChanges(snapshot: MusicTagSnapshot?, values: Map<MusicTagField, String>): Map<MusicTagField, String> =
    if (snapshot == null) emptyMap() else values.filter { (field, value) -> value != snapshot.values[field].orEmpty() }

internal fun fillMusicTagDraft(draft: Map<MusicTagField, String>, candidate: Map<MusicTagField, String>, selected: Set<MusicTagField>) =
    draft + candidate.filter { (field, value) -> field in selected && value.isNotBlank() }

@HiltViewModel
class MusicTagViewModel @Inject constructor(private val repository: MusicTagRepository) : ViewModel() {
    private val _state = MutableStateFlow(MusicTagUiState(busy = true))
    val state = _state.asStateFlow()
    private val drafts = mutableMapOf<String, MusicTagUiState>()
    private val syncedSongs = Channel<Song>(Channel.UNLIMITED)
    val syncCompleted = syncedSongs.receiveAsFlow()
    private val syncQueue = MusicTagSyncQueue(viewModelScope, repository::sync, { repository.active }) { song, synced ->
        val latest = if (synced) try { repository.latestSong(song.id) ?: song }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { song } else song
        if (repository.active) {
            _state.update { musicTagSyncFinished(it, latest, synced) }
            if (synced) syncedSongs.send(latest)
        }
    }
    val syncs = syncQueue.states

    init {
        viewModelScope.launch {
            try { _state.update { it.copy(settings = repository.settings()) } }
            catch (_: Exception) { _state.update { it.copy(message = "无法读取 Music Tag 配置，请重新配置") } }
            finally { _state.update { it.copy(busy = false) } }
        }
    }

    fun showSettings() { if (!_state.value.busy) _state.update { it.copy(settingsShown = true, message = null) } }
    fun closeSettings() {
        if (_state.value.busy) return
        _state.update { it.copy(settingsShown = false, message = null) }
        if (_state.value.song != null && _state.value.snapshot == null && _state.value.settings.configured) reload()
    }
    fun close() { if (!_state.value.busy) _state.update { MusicTagUiState(settings = it.settings, syncVersion = it.syncVersion) } }
    fun keepDraftAndClose() {
        val current = _state.value
        if (current.busy || current.pending) return
        current.song?.let { drafts[it.id] = current }
        close()
    }

    fun configure(settings: MusicTagSettings) = task("正在测试连接并保存…") {
        check(!_state.value.settings.configured) { "请先退出当前 Music Tag 连接，再重新配置" }
        val normalized = settings.copy(baseUrl = normalizeMusicTagAddress(settings.baseUrl, settings.allowHttp))
        MusicTagPath.resolve("sample.flac", normalized.sourceRoot, normalized.targetRoot)
        repository.client(normalized)
        repository.saveSettings(normalized)
        drafts.clear()
        _state.update { it.copy(settings = normalized, snapshot = null, sourcePath = null, targetPath = null,
            searched = false, values = emptyMap(), selected = emptySet(),
            candidates = emptyList(), message = "连接成功，配置已保存。目录写入权限将在应用标签时检查") }
    }

    fun disconnect() = task("正在退出 Music Tag 连接…") {
        repository.disconnect()
        drafts.clear()
        _state.update { MusicTagUiState(settingsShown = true, song = it.song,
            queryTitle = it.queryTitle, queryArtist = it.queryArtist, pending = it.pending,
            syncVersion = it.syncVersion, message = "已退出 Music Tag 连接，可重新配置") }
    }

    fun open(song: Song) {
        if (_state.value.busy) return
        drafts.remove(song.id)?.let { draft ->
            _state.update { draft.copy(settings = it.settings, song = song, syncVersion = it.syncVersion,
                message = "已恢复未保存草稿，保存时会核对原文件是否发生变化") }
            return
        }
        _state.update { MusicTagUiState(settings = it.settings, song = song, queryTitle = song.title,
            artworkPath = repository.cachedArtwork(song),
            queryArtist = song.artistName.takeUnless { name -> name == "未知艺术家" }.orEmpty(), syncVersion = it.syncVersion) }
        reload()
    }

    fun reload() = task("正在读取文件标签…") {
        val current = _state.value
        val song = current.song ?: return@task
        val pending = repository.pending(song.id)
        _state.update { it.copy(pending = pending != null) }
        if (!current.settings.configured) {
            _state.update { it.copy(message = "请在设置中配置 Music Tag 服务，再刮削和维护标签") }
            return@task
        }
        _state.update { it.copy(sourcePath = null, targetPath = null) }
        val sourcePath = repository.sourcePath(song, current.settings)
        _state.update { it.copy(sourcePath = sourcePath) }
        val path = MusicTagPath.resolve(sourcePath, current.settings.sourceRoot, current.settings.targetRoot)
        _state.update { it.copy(targetPath = path) }
        val client = repository.client(current.settings)
        val snapshot = client.read(path)
        val latestPending = repository.pending(song.id)
        val syncing = syncs.value[song.id]?.running == true && latestPending != null
        _state.update { it.copy(snapshot = snapshot, values = snapshot.values, candidateValues = emptyMap(), selected = emptySet(),
            pending = latestPending != null, fileSaved = latestPending?.fileVerified == true, syncing = syncing,
            message = when {
                syncing -> "标签已保存，曲库后台同步中，可返回继续使用"
                latestPending?.fileVerified == true -> "标签已保存，曲库待同步，可重试同步"
                latestPending != null -> "上次操作尚未完成确认，请检查保存结果"
                else -> "已读取原文件标签，可直接编辑，也可通过刮削填充"
            }) }
        if (latestPending?.fileVerified == true && !syncing) completeSync(song)
    }

    fun query(title: String, artist: String) { if (!_state.value.busy) _state.update { it.copy(queryTitle = title, queryArtist = artist) } }
    fun source(value: String) { if (!_state.value.busy) _state.update { it.copy(settings = it.settings.copy(source = value)) } }

    fun search() = task("正在刮削匹配…") {
        val current = _state.value
        val client = repository.client(current.settings)
        val candidates = client.search(current.queryTitle, current.queryArtist, current.song?.albumName.orEmpty(), current.settings.source)
        _state.update { it.copy(candidates = candidates, searched = true, candidateValues = emptyMap(), selected = emptySet(),
            message = if (candidates.isEmpty()) "没有匹配结果，请调整检索词或切换来源" else "找到 ${candidates.size} 个候选，请核对歌曲版本") }
    }

    fun choose(candidate: MusicTagCandidate) = task("正在获取候选歌词…") {
        var values = candidate.values
        var message = "已载入候选，请选择要填入表单的字段"
        try {
            val client = repository.client(_state.value.settings)
            values = values + (MusicTagField.LYRICS to client.lyrics(candidate))
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { message = "候选已载入，歌词暂不可用；原歌词会保留" }
        _state.update { it.copy(candidateValues = values, selected = values.filter { (field, value) ->
            value.isNotBlank() && value != it.values[field]
        }.keys, message = message) }
    }

    fun select(field: MusicTagField, selected: Boolean) {
        if (!_state.value.busy) _state.update { it.copy(selected = if (selected) it.selected + field else it.selected - field) }
    }

    fun edit(field: MusicTagField, value: String) {
        if (!_state.value.busy && !_state.value.pending && _state.value.snapshot != null)
            _state.update { it.copy(values = it.values + (field to value), fileSaved = false, message = null, isError = false) }
    }

    fun dismissCandidate() { if (!_state.value.busy) _state.update { it.copy(candidateValues = emptyMap(), selected = emptySet()) } }

    fun fillCandidate() {
        if (_state.value.busy || _state.value.pending || _state.value.snapshot == null) return
        _state.update { it.copy(values = fillMusicTagDraft(it.values, it.candidateValues, it.selected),
            candidateValues = emptyMap(), selected = emptySet(), fileSaved = false, fillVersion = it.fillVersion + 1,
            message = "已填入表单，变更字段已高亮；保存后才会写入文件") }
    }

    fun apply() = task("正在应用标签，播放将短暂停顿…") {
        val current = _state.value
        val song = checkNotNull(current.song)
        val snapshot = checkNotNull(current.snapshot) { "请先读取并确认目标文件" }
        check(!current.pending) { "请先检查上次保存结果" }
        val changes = current.changes
        require(changes.isNotEmpty() && changes.values.all { it.isNotBlank() && "\${" !in it }) { "请选择有效标签；本版不支持清空或模板表达式" }
        changes[MusicTagField.YEAR]?.let { require(it.toIntOrNull() in 1..9999) { "年份应为 1–9999" } }
        val client = repository.client(current.settings)
        val snapshotAfter = repository.apply(song, current.settings, client, snapshot, changes)
        _state.update { it.copy(snapshot = snapshotAfter, values = snapshotAfter.values, fileSaved = true, pending = true, progress = "文件已保存，正在更新曲库…") }
        completeSync(song)
    }

    fun verifyAndSync() = task("正在检查保存结果…") {
        val current = _state.value
        val song = checkNotNull(current.song)
        if (repository.pending(song.id)?.fileVerified == true) {
            completeSync(song)
            return@task
        }
        val client = repository.client(current.settings)
        val snapshot = repository.verify(song, current.settings, client)
        _state.update { it.copy(snapshot = snapshot, values = snapshot.values, coverReview = null, fileSaved = true, progress = "文件已保存，正在更新曲库…") }
        completeSync(song)
    }

    fun confirmCoverAndSync() = task("正在核对读回封面并刷新曲库…") {
        val current = _state.value
        val review = checkNotNull(current.coverReview)
        val song = checkNotNull(current.song)
        val client = repository.client(current.settings)
        val snapshot = repository.verify(song, current.settings, client, approvedCover = review)
        _state.update { it.copy(snapshot = snapshot, values = snapshot.values, coverReview = null, fileSaved = true, progress = "封面已确认，正在更新曲库…") }
        completeSync(song)
    }

    private fun completeSync(song: Song) {
        if (syncs.value[song.id]?.running == true) return
        _state.update { if (it.song?.id != song.id) it else it.copy(fileSaved = true, pending = true,
            syncing = true, isError = false, message = "标签已保存，曲库后台同步中，可返回继续使用") }
        if (!syncQueue.start(song)) _state.update { if (it.song?.id != song.id) it else it.copy(
            syncing = false, message = "标签已保存，曲库同步尚未启动，可稍后重试") }
    }

    fun rereadAfterReview() = task("正在重新读取…") {
        val current = _state.value
        val song = checkNotNull(current.song)
        check(syncs.value[song.id]?.running != true) { "曲库正在同步，可直接返回" }
        val client = repository.client(current.settings)
        val path = repository.songPath(song, current.settings)
        val snapshot = client.read(path)
        repository.acknowledgeMismatch(song.id)
        _state.update { it.copy(snapshot = snapshot, coverReview = null, pending = false, fileSaved = false, values = snapshot.values,
            selected = emptySet(), message = "已重新读取当前标签，可直接编辑或刮削填充") }
    }

    private fun task(progress: String, block: suspend () -> Unit) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, progress = progress, message = null, isError = false) }
        viewModelScope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (review: MusicTagCoverReviewRequired) {
                _state.update { it.copy(snapshot = review.snapshot, coverReview = review.review, pending = true,
                    isError = false, message = if (review.review.versionLimited)
                        "当前版本仅提供封面缩略图，无法自动确认原图。请点击「核对封面」查看文件读回的封面。"
                        else "封面读回内容与候选原图未完全一致，请核对是否符合预期。") }
            }
            catch (error: Exception) {
                val pending = _state.value.song?.let { repository.pending(it.id) } != null
                val message = when (error) {
                    is MusicTagException, is IllegalArgumentException, is IllegalStateException -> error.message ?: "操作未完成"
                    else -> "连接中断或服务暂不可用，请稍后重试"
                }
                _state.update { it.copy(pending = pending, isError = true, message = if (pending) "$message。保存记录已保留，请检查保存结果；不要重复提交" else message) }
            } finally {
                if (repository.active) _state.update { it.copy(busy = false, progress = "") }
            }
        }
    }
}
