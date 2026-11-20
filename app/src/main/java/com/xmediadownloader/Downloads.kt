package com.xmediadownloader

import android.content.Context
import android.os.SystemClock
import com.xmediadownloader.data.MediaDownloader
import com.xmediadownloader.data.MediaVariant
import com.xmediadownloader.data.TweetData
import com.xmediadownloader.data.TweetMedia
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Runs the download of one media option, with UI state and cancellation.
 * Only one download is active per option (same URL).
 */
fun runDownload(
    context: Context,
    tweet: TweetData,
    media: TweetMedia,
    option: MediaVariant,
    scope: CoroutineScope,
    downloadStates: MutableMap<String, DownloadUi>,
    runningJobs: MutableMap<String, Job>,
) {
    val key = option.url

    synchronized(runningJobs) {
        if (shouldSkipBecauseAlreadyRunning(runningJobs[key])) return

        val job = scope.launch {
            try {
                downloadStates[key] = DownloadUi.Running(received = 0, total = -1, speedBps = 0)

                var lastUpdateMs = 0L
                val startMs = SystemClock.elapsedRealtime()
                val fileName = MediaDownloader.download(
                    context = context,
                    url = option.url,
                    tweetId = tweet.tweetId,
                    mediaIndex = media.index,
                    mime = option.mime,
                ) { received, total ->
                    // Smooths updates (~6x per second)
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastUpdateMs >= 150) {
                        lastUpdateMs = now
                        val elapsed = (now - startMs).coerceAtLeast(1)
                        downloadStates[key] = DownloadUi.Running(
                            received = received,
                            total = total,
                            speedBps = received * 1000 / elapsed,
                        )
                    }
                }
                downloadStates[key] = DownloadUi.Done(fileName)
            } catch (e: CancellationException) {
                downloadStates[key] = DownloadUi.Idle
                throw e
            } catch (e: Exception) {
                downloadStates[key] = DownloadUi.Failed(e.message ?: "Download failed")
            }
        }
        runningJobs[key] = job
    }
}

private fun shouldSkipBecauseAlreadyRunning(job: Job?): Boolean = job != null && job.isActive

/** Cancels the download of one option, if it is running. */
fun cancelDownload(optionUrl: String, runningJobs: MutableMap<String, Job>) {
    runningJobs[optionUrl]?.cancel()
}
