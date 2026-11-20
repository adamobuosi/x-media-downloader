package com.xmediadownloader

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.xmediadownloader.data.TweetApi
import com.xmediadownloader.data.TweetData
import com.xmediadownloader.data.TweetMedia
import com.xmediadownloader.data.TweetUrlParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    /** Handler installed by the UI; receives the text/URL when a new intent arrives. */
    private var onIncoming: ((String) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val initialUrl = extractFromIntent(intent)
        setContent {
            MaterialTheme {
                RootScreen(
                    initialUrl = initialUrl,
                    registerIncoming = { handler -> onIncoming = handler },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        extractFromIntent(intent)?.let { url ->
            onIncoming?.invoke(url)
        }
    }

    /** Extracts a tweet URL/ID shared via the "Share" menu or ACTION_VIEW. */
    private fun extractFromIntent(intent: Intent?): String? {
        val raw = when (intent?.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_VIEW -> intent.dataString
            else -> null
        } ?: return null
        return parseSharedText(raw)
    }
}

/** A share may come as a bare URL, text containing a URL, or just the ID. */
private fun parseSharedText(raw: String): String? {
    if (raw.isBlank()) return null
    return TweetUrlParser.extractFirstUrl(raw)
        ?: TweetUrlParser.extractTweetId(raw)?.let { "https://x.com/i/status/$it" }
        ?: raw.trim()
}

// =============================================================================
// Main screen
// =============================================================================

@Composable
fun RootScreen(
    initialUrl: String?,
    registerIncoming: ((String) -> Unit) -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    var urlText by rememberSaveable { mutableStateOf(initialUrl.orEmpty()) }
    var fetchState by remember { mutableStateOf<FetchUi>(FetchUi.Idle) }
    var fetchJob by remember { mutableStateOf<Job?>(null) }

    // per-media user selection: mediaIndex -> optionIndex (0 = best quality)
    val selections = remember { mutableStateMapOf<Int, Int>() }
    // download state per option: optionUrl -> DownloadUi
    val downloadStates = remember { mutableStateMapOf<String, DownloadUi>() }
    val runningJobs = remember { mutableStateMapOf<String, Job>() }
    val scope = rememberCoroutineScope()

    // Write permission (only Android <= 9 via the legacy public folder)
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val legacyPermission = Manifest.permission.WRITE_EXTERNAL_STORAGE
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val action = pendingAction
        pendingAction = null
        if (granted) action?.invoke()
    }

    fun needsLegacyPermission(): Boolean =
        Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            context.checkSelfPermission(legacyPermission) != PackageManager.PERMISSION_GRANTED

    fun withPermission(action: () -> Unit) {
        if (needsLegacyPermission()) {
            pendingAction = action
            permissionLauncher.launch(legacyPermission)
        } else {
            action()
        }
    }

    fun downloadOne(media: TweetMedia, optionIndex: Int, tweet: TweetData) {
        val option = media.options.getOrNull(optionIndex) ?: return
        withPermission {
            scope.launch {
                runDownload(context, tweet, media, option, scope, downloadStates, runningJobs)
            }
        }
    }

    fun downloadAll(tweet: TweetData) {
        withPermission {
            tweet.media.forEach { media ->
                val idx = selections[media.index] ?: 0
                val option = media.options.getOrNull(idx) ?: return@forEach
                scope.launch {
                    runDownload(context, tweet, media, option, scope, downloadStates, runningJobs)
                }
            }
        }
    }

    fun fetch(input: String) {
        val query = input.trim()
        if (query.isEmpty()) return
        fetchJob?.cancel() // prevents concurrent fetches from overwriting the state
        fetchJob = scope.launch {
            fetchState = FetchUi.Loading
            fetchState = try {
                FetchUi.Loaded(TweetApi.fetchTweet(query))
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                FetchUi.Error(e.message ?: "Unknown error while fetching the tweet.")
            }
        }
    }

    fun acceptShared(text: String) {
        val candidate = parseSharedText(text)
        if (candidate.isNullOrBlank()) return
        urlText = candidate
        fetch(candidate)
    }

    // Share received while the app is already open.
    LaunchedEffect(Unit) {
        registerIncoming { text -> acceptShared(text) }
    }

    // Share that opened the app for the first time: fetch only once,
    // without re-fetching on rotation/composition recreation.
    var handledInitialUrl by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(initialUrl) {
        if (!initialUrl.isNullOrBlank() && !handledInitialUrl) {
            handledInitialUrl = true
            fetch(initialUrl)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = "X Media Downloader", style = MaterialTheme.typography.headlineSmall)

        Text(
            text = "Paste the URL of a tweet (or use Share → X Media Downloader " +
                "inside the X app) and download the photos or videos at the " +
                "size you choose.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(
            value = urlText,
            onValueChange = { urlText = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text("https://x.com/user/status/123456789") },
            trailingIcon = {
                Row {
                    IconButton(onClick = {
                        clipboard.getText()?.let { annotated ->
                            TweetUrlParser.extractFirstUrl(annotated.text)?.let { urlText = it }
                        }
                    }) {
                        Icon(Icons.Filled.ContentPaste, contentDescription = "Paste from clipboard")
                    }
                    IconButton(onClick = {
                        urlText = ""
                        fetchState = FetchUi.Idle
                    }) {
                        Icon(Icons.Filled.Clear, contentDescription = "Clear")
                    }
                }
            },
        )

        Button(
            onClick = { fetch(urlText) },
            enabled = urlText.isNotBlank() && fetchState !is FetchUi.Loading,
        ) {
            Text(if (fetchState is FetchUi.Loading) "Fetching…" else "Fetch media")
        }

        when (val state = fetchState) {
            is FetchUi.Error -> Text(
                text = state.message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )

            is FetchUi.Loaded -> LoadedContent(
                tweet = state.tweet,
                selections = selections,
                downloadStates = downloadStates,
                runningJobs = runningJobs,
                onDownload = { media, optionIndex ->
                    downloadOne(media, optionIndex, state.tweet)
                },
                onDownloadAll = { downloadAll(state.tweet) },
            )

            FetchUi.Loading -> CircularProgressIndicator()
            FetchUi.Idle -> Box(Modifier.height(1.dp))
        }
    }
}
