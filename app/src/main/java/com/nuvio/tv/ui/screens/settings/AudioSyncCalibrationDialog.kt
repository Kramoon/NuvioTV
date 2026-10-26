@file:OptIn(androidx.media3.common.util.UnstableApi::class, androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.RawResourceDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.player.FrameRateUtils
import com.nuvio.tv.data.local.FrameRateMatchingMode
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.ui.screens.player.AUDIO_DELAY_HOLD_FAST_STEP_MS
import com.nuvio.tv.ui.screens.player.AUDIO_DELAY_HOLD_FAST_THRESHOLD_MS
import com.nuvio.tv.ui.screens.player.AUDIO_DELAY_HOLD_REPEAT_INTERVAL_MS
import com.nuvio.tv.ui.screens.player.AUDIO_DELAY_HOLD_STEP_MS
import com.nuvio.tv.ui.screens.player.AUDIO_DELAY_HOLD_THRESHOLD_MS
import com.nuvio.tv.ui.screens.player.AUDIO_DELAY_MAX_MS
import com.nuvio.tv.ui.screens.player.AUDIO_DELAY_MIN_MS
import com.nuvio.tv.ui.screens.player.AUDIO_DELAY_STEP_MS
import com.nuvio.tv.ui.screens.player.AudioDelayMediaSource
import com.nuvio.tv.ui.screens.player.AudioOutputRoute
import com.nuvio.tv.ui.screens.player.AudioOutputRouteDetector
import com.nuvio.tv.ui.screens.player.DisplayModeInfo
import com.nuvio.tv.ui.screens.player.DisplayModeOverlay
import com.nuvio.tv.ui.screens.player.NuvioMpvSurfaceView
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/** Bundled Nuvio-authored H.264/AAC cues: a two-frame flash and matching tone every two seconds. */
private data class AudioSyncTestClip(
    val title: String,
    val fps: Float,
    val resourceId: Int
)

private val audioSyncTestClips = listOf(
    AudioSyncTestClip("23.98", 24000f / 1001f, R.raw.av_sync_2398),
    AudioSyncTestClip("24", 24f, R.raw.av_sync_24),
    AudioSyncTestClip("25", 25f, R.raw.av_sync_25),
    AudioSyncTestClip("29.97", 30000f / 1001f, R.raw.av_sync_2997),
    AudioSyncTestClip("50", 50f, R.raw.av_sync_50),
    AudioSyncTestClip("59.94", 60000f / 1001f, R.raw.av_sync_5994)
)

@Composable
internal fun AudioSyncCalibrationScreen(
    viewModel: PlaybackSettingsViewModel = hiltViewModel(),
    onBackPress: () -> Unit
) {
    BackHandler(onBack = onBackPress)
    val settings by viewModel.playerSettings.collectAsStateWithLifecycle(initialValue = PlayerSettings())
    AudioSyncCalibrationContent(
        settings = settings,
        viewModel = viewModel,
        onDismiss = onBackPress
    )
}

@Composable
private fun AudioSyncCalibrationContent(
    settings: PlayerSettings,
    viewModel: PlaybackSettingsViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val rememberDelay = settings.rememberAudioDelayPerDevice
    var activeRoute by remember { mutableStateOf<AudioOutputRoute?>(null) }
    var delayMs by remember { mutableIntStateOf(0) }
    var routeMessage by remember { mutableStateOf<String?>(null) }
    var routeVersion by remember { mutableIntStateOf(0) }
    var selectedClip by remember { mutableStateOf(audioSyncTestClips.first()) }
    var displayModeInfo by remember { mutableStateOf<DisplayModeInfo?>(null) }
    var showDisplayModeInfo by remember { mutableStateOf(false) }
    var isPaused by remember { mutableStateOf(false) }
    var routeRefreshJob by remember { mutableStateOf<Job?>(null) }
    val latestRoute by rememberUpdatedState(activeRoute)
    val currentActivity = remember(context) { context.findActivity() }
    val usesMpv = settings.internalPlayerEngine == com.nuvio.tv.data.local.InternalPlayerEngine.MVP_PLAYER
    val tunnelingRequested = !usesMpv && settings.effectiveTunnelingEnabled &&
        activeRoute != null && !AudioOutputRouteDetector.isUsbMediaOutput(context, activeRoute)

    suspend fun reloadRoute(route: AudioOutputRoute?) {
        activeRoute = route
        delayMs = route?.let { viewModel.getAudioDelayForRoute(it.key) } ?: 0
        routeVersion++
        routeMessage = route?.let {
            context.getString(R.string.audio_sync_calibration_route_changed, it.label)
        }
    }

    LaunchedEffect(Unit) {
        activeRoute = viewModel.detectActiveAudioRoute()
        delayMs = activeRoute?.let { viewModel.getAudioDelayForRoute(it.key) } ?: 0
    }

    val requestRouteRefresh = remember(context, viewModel, scope) {
        {
            routeRefreshJob?.cancel()
            routeRefreshJob = scope.launch {
                delay(700L)
                val nextRoute = viewModel.detectActiveAudioRoute()
                if (nextRoute?.key != latestRoute?.key) {
                    reloadRoute(nextRoute)
                }
            }
        }
    }

    DisposableEffect(context, requestRouteRefresh) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                requestRouteRefresh()
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                requestRouteRefresh()
            }
        }
        audioManager?.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
        onDispose {
            routeRefreshJob?.cancel()
            audioManager?.unregisterAudioDeviceCallback(callback)
        }
    }

    LaunchedEffect(currentActivity, selectedClip, settings.frameRateMatchingMode, activeRoute?.key) {
        if (currentActivity != null && activeRoute != null &&
            settings.frameRateMatchingMode != FrameRateMatchingMode.OFF
        ) {
            val switchResult = FrameRateUtils.matchFrameRateAndWait(
                activity = currentActivity,
                frameRate = selectedClip.fps,
                videoWidth = 1280,
                videoHeight = 720
            )
            switchResult?.appliedMode?.let { mode ->
                displayModeInfo = DisplayModeInfo(
                    width = mode.physicalWidth,
                    height = mode.physicalHeight,
                    refreshRate = mode.refreshRate
                )
                showDisplayModeInfo = true
            }
        }
    }

    val latestFrameRateMatchingMode by rememberUpdatedState(settings.frameRateMatchingMode)
    DisposableEffect(currentActivity) {
        onDispose {
            if (currentActivity != null) {
                if (latestFrameRateMatchingMode == FrameRateMatchingMode.START_STOP) {
                    FrameRateUtils.restoreOriginalDisplayMode(currentActivity)
                } else {
                    FrameRateUtils.cleanupDisplayListener()
                    FrameRateUtils.clearOriginalDisplayMode()
                }
            }
        }
    }

    val saveCalibration: () -> Unit = {
        scope.launch {
            val currentRoute = viewModel.detectActiveAudioRoute()
            val routeAtConfirmation = activeRoute
            if (currentRoute?.key != routeAtConfirmation?.key || routeAtConfirmation == null) {
                reloadRoute(currentRoute)
            } else {
                viewModel.saveAudioDelayForRoute(routeAtConfirmation.key, delayMs)
                routeMessage = context.getString(
                    R.string.audio_sync_calibration_saved,
                    routeAtConfirmation.label
                )
                onDismiss()
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF050505))
    ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 36.dp, vertical = 22.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(Color.Black),
                    contentAlignment = Alignment.Center
                ) {
                    if (activeRoute != null) {
                        if (usesMpv) {
                            MpvCalibrationVideo(
                                context = context,
                                clip = selectedClip,
                                delayMs = delayMs,
                                isPaused = isPaused,
                                isBluetoothRoute = activeRoute?.isBluetooth == true,
                                routeVersion = routeVersion,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            ExoCalibrationVideo(
                                context = context,
                                clip = selectedClip,
                                delayMs = delayMs,
                                isPaused = isPaused,
                                routeVersion = routeVersion,
                                tunnelingRequested = tunnelingRequested,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }

                Text(
                    text = stringResource(R.string.audio_sync_calibration_instruction),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.8f)
                )

                Text(
                    text = activeRoute?.let {
                        stringResource(R.string.audio_sync_calibration_route, it.label)
                    } ?: stringResource(R.string.audio_sync_calibration_no_route),
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    modifier = Modifier
                        .background(Color(0xFF292929), RoundedCornerShape(20.dp))
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.audio_sync_calibration_frame_rate),
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        modifier = Modifier.padding(end = 14.dp)
                    )
                    Button(
                        onClick = { isPaused = !isPaused },
                        enabled = activeRoute != null
                    ) {
                        Text(
                            stringResource(
                                if (isPaused) R.string.audio_sync_calibration_resume
                                else R.string.audio_sync_calibration_pause
                            )
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(audioSyncTestClips, key = { it.title }) { clip ->
                            Button(
                                onClick = { selectedClip = clip },
                                enabled = activeRoute != null,
                                modifier = Modifier.semantics { selected = clip == selectedClip }
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (clip == selectedClip) {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(Modifier.width(6.dp))
                                    }
                                    Text("${clip.title} fps")
                                }
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CalibrationStepCard(
                        label = "−",
                        description = stringResource(R.string.audio_sync_calibration_minus),
                        enabled = activeRoute != null && delayMs > AUDIO_DELAY_MIN_MS,
                        onStep = { amount -> delayMs = (delayMs - amount).coerceAtLeast(AUDIO_DELAY_MIN_MS) }
                    )
                    Column(
                        modifier = Modifier.width(220.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = stringResource(R.string.audio_delay_label),
                            color = Color.White.copy(alpha = 0.76f),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = formatCalibrationDelay(delayMs),
                            color = Color.White,
                            style = MaterialTheme.typography.headlineMedium
                        )
                    }
                    CalibrationStepCard(
                        label = "+",
                        description = stringResource(R.string.audio_sync_calibration_plus),
                        enabled = activeRoute != null && delayMs < AUDIO_DELAY_MAX_MS,
                        onStep = { amount -> delayMs = (delayMs + amount).coerceAtMost(AUDIO_DELAY_MAX_MS) }
                    )
                }

                if (!rememberDelay) {
                    Text(
                        text = stringResource(R.string.audio_sync_calibration_recall_off),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFFFD180)
                    )
                }
                if (tunnelingRequested) {
                    Text(
                        text = stringResource(R.string.audio_sync_calibration_tunnel_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.65f)
                    )
                }
                routeMessage?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.72f)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(onClick = onDismiss) {
                        Text(stringResource(R.string.audio_sync_calibration_cancel))
                    }
                    Spacer(Modifier.width(12.dp))
                    Button(onClick = saveCalibration, enabled = activeRoute != null) {
                        Text(stringResource(R.string.audio_sync_calibration_save))
                    }
                }
            }

            Text(
                text = stringResource(R.string.audio_sync_calibration_title),
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 36.dp, top = 22.dp)
                    .zIndex(2f)
            )
            DisplayModeOverlay(
                info = displayModeInfo,
                isVisible = showDisplayModeInfo,
                onAnimationComplete = { showDisplayModeInfo = false },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .zIndex(2f)
            )
    }
}

@Composable
private fun ExoCalibrationVideo(
    context: Context,
    clip: AudioSyncTestClip,
    delayMs: Int,
    isPaused: Boolean,
    routeVersion: Int,
    tunnelingRequested: Boolean,
    modifier: Modifier = Modifier
) {
    val delayUs = remember { AtomicLong(0L) }
    SideEffect { delayUs.set(delayMs.toLong() * 1000L) }
    val player = remember(context, tunnelingRequested, routeVersion) {
        val selector = DefaultTrackSelector(context).apply {
            setParameters(
                buildUponParameters()
                    .setTunnelingEnabled(tunnelingRequested)
                    .build()
            )
        }
        ExoPlayer.Builder(context)
            .setTrackSelector(selector)
            .build()
            .apply {
                setAudioAttributes(AudioOutputRouteDetector.mediaMovieAudioAttributes(), true)
                repeatMode = Player.REPEAT_MODE_ONE
            }
    }

    DisposableEffect(player) {
        onDispose { player.release() }
    }

    LaunchedEffect(player, clip.resourceId, routeVersion) {
        val mediaItem = MediaItem.fromUri(RawResourceDataSource.buildRawResourceUri(clip.resourceId))
        val childSource = DefaultMediaSourceFactory(context).createMediaSource(mediaItem)
        player.setMediaSource(
            AudioDelayMediaSource(childSource) { delayUs.get() }
        )
        player.prepare()
        player.playWhenReady = !isPaused
    }

    LaunchedEffect(player, isPaused) { player.playWhenReady = !isPaused }

    AndroidView(
        factory = {
            PlayerView(it).apply {
                useController = false
                isFocusable = false
                isFocusableInTouchMode = false
                descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                keepScreenOn = true
                this.player = player
            }
        },
        update = { if (it.player !== player) it.player = player },
        modifier = modifier.focusProperties { canFocus = false }
    )
}

@Composable
private fun MpvCalibrationVideo(
    context: Context,
    clip: AudioSyncTestClip,
    delayMs: Int,
    isPaused: Boolean,
    isBluetoothRoute: Boolean,
    routeVersion: Int,
    modifier: Modifier = Modifier
) {
    var localFile by remember(clip.resourceId) { mutableStateOf<File?>(null) }
    LaunchedEffect(clip.resourceId) {
        localFile = withContext(Dispatchers.IO) {
            val file = File(context.cacheDir, "av-sync-${clip.title}.mp4")
            if (!file.exists() || file.length() == 0L) {
                context.resources.openRawResource(clip.resourceId).use { input ->
                    file.outputStream().use(input::copyTo)
                }
            }
            file
        }
    }

    val file = localFile ?: return
    val view = remember(context) {
        NuvioMpvSurfaceView(context).apply {
            isFocusable = false
            isFocusableInTouchMode = false
        }
    }
    AndroidView(
        factory = { view },
        update = { it.setAudioDelayMs(delayMs) },
        modifier = modifier.focusProperties { canFocus = false }
    )
    LaunchedEffect(view, file, routeVersion) {
        view.setMediaUsingLoadfile(file.absolutePath, emptyMap())
        view.setLoopFileEnabled(true)
        view.applyBluetoothAudioRoute(isBluetoothRoute, reloadOutput = routeVersion > 0)
        view.setAudioDelayMs(delayMs)
        view.setPaused(isPaused)
    }
    LaunchedEffect(view, isPaused) { view.setPaused(isPaused) }
    DisposableEffect(view) {
        onDispose { view.releasePlayer() }
    }
}

@Composable
private fun CalibrationStepCard(
    label: String,
    description: String,
    enabled: Boolean,
    onStep: (Int) -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val latestOnStep by rememberUpdatedState(onStep)

    LaunchedEffect(isPressed, enabled) {
        if (!isPressed || !enabled) return@LaunchedEffect
        delay(AUDIO_DELAY_HOLD_THRESHOLD_MS)
        var heldMs = AUDIO_DELAY_HOLD_THRESHOLD_MS
        while (isActive) {
            latestOnStep(
                if (heldMs >= AUDIO_DELAY_HOLD_FAST_THRESHOLD_MS) {
                    AUDIO_DELAY_HOLD_FAST_STEP_MS
                } else {
                    AUDIO_DELAY_HOLD_STEP_MS
                }
            )
            delay(AUDIO_DELAY_HOLD_REPEAT_INTERVAL_MS)
            heldMs += AUDIO_DELAY_HOLD_REPEAT_INTERVAL_MS
        }
    }

    Card(
        onClick = { if (enabled) onStep(AUDIO_DELAY_STEP_MS) },
        interactionSource = interactionSource,
        modifier = Modifier
            .size(84.dp)
            .focusProperties { canFocus = enabled }
            .semantics { contentDescription = description },
        colors = CardDefaults.colors(
            containerColor = if (enabled) Color.Transparent else Color.White.copy(alpha = 0.06f),
            focusedContainerColor = if (enabled) Color(0xFF2B2B2B) else Color.White.copy(alpha = 0.06f)
        ),
        shape = CardDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md)),
        border = CardDefaults.border(
            border = Border(
                border = BorderStroke(1.dp, if (enabled) Color.White.copy(alpha = 0.22f) else Color.Transparent),
                shape = RoundedCornerShape(NuvioTheme.radii.md)
            ),
            focusedBorder = Border(
                border = BorderStroke(2.dp, if (enabled) Color.White else Color.Transparent),
                shape = RoundedCornerShape(NuvioTheme.radii.md)
            )
        ),
        scale = CardDefaults.scale(focusedScale = 1f, pressedScale = 1f)
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Text(
                text = label,
                color = if (enabled) Color.White else Color.White.copy(alpha = 0.35f),
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center
            )
        }
    }
}

private fun formatCalibrationDelay(delayMs: Int): String =
    if (delayMs == 0) String.format(Locale.US, "%.3fs", 0f)
    else String.format(Locale.US, "%+.3fs", delayMs / 1000f)

private tailrec fun Context.findActivity(): android.app.Activity? = when (this) {
    is android.app.Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}
