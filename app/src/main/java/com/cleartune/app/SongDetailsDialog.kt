package com.cleartune.app

import android.text.format.Formatter
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.cleartune.core.model.Song
import java.util.Locale

@Composable
internal fun SongDetailsDialog(
    song: Song,
    onDismiss: () -> Unit,
    artwork: @Composable (Modifier) -> Unit,
) {
    val density = LocalDensity.current
    val unknown = stringResource(R.string.unknown_format)
    val audio = listOf(
        stringResource(R.string.song_details_duration) to
            song.durationSeconds.takeIf { it > 0 }?.let { "%d:%02d".format(it / 60, it % 60) }.orUnknown(unknown),
        stringResource(R.string.song_details_format) to
            song.suffix?.trim()?.takeIf(String::isNotEmpty)?.uppercase(Locale.ROOT).orUnknown(unknown),
        stringResource(R.string.song_details_bitrate) to
            song.bitRate?.takeIf { it > 0 }?.let { stringResource(R.string.song_details_bitrate_value, it) }.orUnknown(unknown),
        stringResource(R.string.song_details_size) to
            song.sizeBytes?.takeIf { it > 0 }?.let { Formatter.formatShortFileSize(LocalContext.current, it) }.orUnknown(unknown),
    )
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        // Keep the caller's text scale in the dialog's separate composition.
        CompositionLocalProvider(LocalDensity provides density) {
            BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 24.dp),
                contentAlignment = Alignment.Center) {
                val maxDialogHeight = maxHeight
                Surface(
                    modifier = Modifier.widthIn(max = 440.dp).fillMaxWidth().heightIn(max = maxDialogHeight)
                        .testTag("song-details"),
                    shape = RoundedCornerShape(28.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Column(Modifier.padding(20.dp)) {
                        Text(stringResource(R.string.song_details),
                            modifier = Modifier.semantics { heading() },
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(20.dp))
                        BoxWithConstraints(Modifier.weight(1f, fill = false)) {
                            val stacked = maxWidth < 280.dp || LocalDensity.current.fontScale >= 1.5f
                            Column(Modifier.verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(20.dp)) {
                                if (stacked) {
                                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                        artwork(Modifier.size(72.dp).clip(RoundedCornerShape(16.dp)))
                                        SongDetailsHeading(song)
                                    }
                                } else {
                                    Row(verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                        artwork(Modifier.size(72.dp).clip(RoundedCornerShape(16.dp)))
                                        Column(Modifier.weight(1f)) { SongDetailsHeading(song) }
                                    }
                                }
                                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    SongDetailsField(stringResource(R.string.song_details_album),
                                        song.displayAlbumName() ?: unknown, stacked)
                                    song.year?.takeIf { it > 0 }?.let {
                                        SongDetailsField(stringResource(R.string.song_details_year), it.toString(), stacked)
                                    }
                                    song.genre?.takeIf(String::isNotBlank)?.let {
                                        SongDetailsField(stringResource(R.string.song_details_genre), it, stacked)
                                    }
                                }
                                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text(stringResource(R.string.song_details_audio),
                                        modifier = Modifier.semantics { heading() },
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    audio.chunked(if (stacked) 1 else 2).forEach { fields ->
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                            fields.forEach { (label, value) ->
                                                SongAudioDetail(label, value, Modifier.weight(1f))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(20.dp))
                        FilledTonalButton(onClick = onDismiss,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.close_action))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SongDetailsHeading(song: Song) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(song.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(song.displayArtistName() ?: stringResource(R.string.unknown_artist),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SongDetailsField(label: String, value: String, stacked: Boolean) {
    if (stacked) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodyMedium)
        }
    } else {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(label, modifier = Modifier.width(56.dp), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun SongAudioDetail(label: String, value: String, modifier: Modifier) {
    Surface(modifier = modifier, shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
        }
    }
}

private fun String?.orUnknown(unknown: String) = this ?: unknown
