package com.xmediadownloader

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xmediadownloader.data.MediaVariant
import com.xmediadownloader.data.TweetData
import com.xmediadownloader.data.TweetMedia
import com.xmediadownloader.data.MediaType
import kotlinx.coroutines.Job

/**
 * List of the loaded tweet: header with author/text + "Download all",
 * followed by a card for each media item.
 */
@Composable
fun LoadedContent(
    tweet: TweetData,
    selections: SnapshotStateMap<Int, Int>,
    downloadStates: SnapshotStateMap<String, DownloadUi>,
    runningJobs: SnapshotStateMap<String, Job>,
    onDownload: (TweetMedia, Int) -> Unit,
    onDownloadAll: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "@${tweet.authorScreenName}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                if (tweet.shortText.isNotBlank()) {
                    Text(
                        text = tweet.shortText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            TextButton(onClick = onDownloadAll) { Text("Download all") }
        }

        tweet.media.forEach { media ->
            val selectedIdx = selections[media.index] ?: 0
            MediaCard(
                media = media,
                selectedOptionIndex = selectedIdx,
                onSelectOption = { selections[media.index] = it },
                downloadState = media.options.getOrNull(selectedIdx)?.url
                    ?.let { downloadStates[it] } ?: DownloadUi.Idle,
                onDownload = { onDownload(media, selectedIdx) },
                onCancel = { optionUrl -> cancelDownload(optionUrl, runningJobs) },
            )
        }
    }
}

/** Card for one media item: type, size/quality chips and download button with progress. */
@Composable
fun MediaCard(
    media: TweetMedia,
    selectedOptionIndex: Int,
    onSelectOption: (Int) -> Unit,
    downloadState: DownloadUi,
    onDownload: () -> Unit,
    onCancel: (String) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(mediaTypeLabel(media.type), style = MaterialTheme.typography.titleSmall)
            }

            Text(
                text = if (media.type == MediaType.PHOTO) {
                    "Choose the image size:"
                } else {
                    "Choose the video quality:"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                media.options.forEachIndexed { i, option ->
                    FilterChip(
                        selected = i == selectedOptionIndex,
                        onClick = { onSelectOption(i) },
                        label = { Text(option.label) },
                    )
                }
            }

            DownloadActionRow(
                option = media.options.getOrNull(selectedOptionIndex),
                state = downloadState,
                onDownload = onDownload,
                onCancel = onCancel,
            )
        }
    }
}

@Composable
private fun DownloadActionRow(
    option: MediaVariant?,
    state: DownloadUi,
    onDownload: () -> Unit,
    onCancel: (String) -> Unit,
) {
    when (state) {
        is DownloadUi.Idle -> ElevatedButton(onClick = onDownload) {
            Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
            Text("Download" + (option?.let { " (${it.label})" } ?: ""))
        }

        is DownloadUi.Running -> {
            val url = option?.url
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (url != null) {
                    IconButton(onClick = { onCancel(url) }) {
                        Icon(Icons.Filled.Cancel, contentDescription = "Cancel")
                    }
                }
                Column(Modifier.fillMaxWidth()) {
                    val indeterminate = state.total <= 0
                    val fraction: Float = if (!indeterminate) {
                        (state.received.toFloat() / state.total.toFloat()).coerceIn(0f, 1f)
                    } else {
                        0f
                    }
                    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                    val downloaded = formatBytes(state.received)
                    val totalTxt = if (!indeterminate) " of ${formatBytes(state.total)}" else ""
                    val speed = formatSpeed(state.speedBps)
                    val speedTxt = if (speed.isEmpty()) "" else " · $speed"
                    val hint = if (indeterminate) " (total size unknown)" else ""
                    Text(
                        text = "$downloaded$totalTxt$speedTxt$hint",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        is DownloadUi.Done -> Text(
            text = "✅ Saved to Downloads/XMediaDownloader/${state.fileName}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )

        is DownloadUi.Failed -> Text(
            text = "❌ ${state.message}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}
