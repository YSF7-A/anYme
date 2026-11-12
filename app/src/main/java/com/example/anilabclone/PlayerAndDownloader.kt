package com.example.anilabclone

import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

fun Context.findActivity(): Activity? {
    var context = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}

fun setFullscreenLandscape(context: Context, isFullScreen: Boolean) {
    val activity = context.findActivity() ?: return
    val window = activity.window
    val insetsController = WindowCompat.getInsetsController(window, window.decorView)

    if (isFullScreen) {
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        insetsController.hide(WindowInsetsCompat.Type.systemBars())
        insetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    } else {
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        insetsController.show(WindowInsetsCompat.Type.systemBars())
    }
}

fun downloadAnimeEpisode(
    context: Context,
    animeTitle: String,
    episodeNum: Int,
    audioType: String,
    videoUrl: String,
    referer: String? = null
) {
    try {
        val cleanTitle = animeTitle.replace(Regex("[^a-zA-Z0-9 ]"), "").trim().replace(" ", "_")
        val fileName = "${cleanTitle}_Ep${episodeNum}_${audioType}.mp4"

        val request = DownloadManager.Request(Uri.parse(videoUrl)).apply {
            setTitle("$animeTitle - Episode $episodeNum ($audioType)")
            setDescription("Downloading episode to Movies/AniLab...")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setAllowedOverMetered(true)
            setAllowedOverRoaming(true)
            addRequestHeader("User-Agent", "Mozilla/5.0 (Android 14; Mobile)")
            if (!referer.isNullOrBlank()) {
                addRequestHeader("Referer", referer)
            }
            setDestinationInExternalPublicDir(Environment.DIRECTORY_MOVIES, "AniLab/$fileName")
        }

        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        dm.enqueue(request)
        Toast.makeText(context, "Download started: $fileName", Toast.LENGTH_LONG).show()
    } catch (e: Exception) {
        Toast.makeText(context, "Download Error: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
    }
}

@OptIn(UnstableApi::class)
@Composable
fun AniLabVideoPlayer(
    episodeKey: String,
    streamLink: StreamLink,
    preferDubAudioTrack: Boolean,
    selectedResolutionHeight: Int?,
    initialPositionMs: Long,
    onSaveProgress: (positionMs: Long, durationMs: Long) -> Unit,
    onEpisodeEnded: () -> Unit,
    isFullScreen: Boolean,
    onFullScreenChange: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val audioManager = remember(context) {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    val currentOnEpisodeEnded by rememberUpdatedState(onEpisodeEnded)
    val currentOnSaveProgress by rememberUpdatedState(onSaveProgress)

    var savedPositionMs by remember(episodeKey) { mutableLongStateOf(initialPositionMs) }
    var playerViewRef by remember { mutableStateOf<PlayerView?>(null) }

    val speedOptions = remember { listOf(1.0f, 1.25f, 1.5f, 2.0f) }
    var playbackSpeed by remember { mutableFloatStateOf(1.0f) }
    var autoPlayNext by remember { mutableStateOf(true) }

    val maxSystemVolume = remember {
        audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    }
    var volumeFraction by remember {
        val currentVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        mutableFloatStateOf(currentVol.toFloat() / maxSystemVolume.toFloat())
    }
    var brightnessFraction by remember {
        val currentBright = activity?.window?.attributes?.screenBrightness ?: -1f
        mutableFloatStateOf(if (currentBright in 0f..1f) currentBright else 0.5f)
    }

    var activeHudType by remember { mutableStateOf<String?>(null) }
    var isDraggingGesture by remember { mutableStateOf(false) }

    LaunchedEffect(activeHudType, isDraggingGesture) {
        if (activeHudType != null && !isDraggingGesture) {
            delay(650L)
            activeHudType = null
        }
    }

    BackHandler(enabled = isFullScreen) {
        setFullscreenLandscape(context, false)
        onFullScreenChange(false)
    }

    val exoPlayer = remember(streamLink.url, preferDubAudioTrack) {
        val headers = mutableMapOf<String, String>()
        streamLink.referer?.let { headers["Referer"] = it }

        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
            .setDefaultRequestProperties(headers)
            .setAllowCrossProtocolRedirects(true)

        val dataSourceFactory = DefaultDataSource.Factory(context, httpDataSourceFactory)

        val mediaItem = MediaItem.fromUri(streamLink.url)
        val mediaSource = if (streamLink.isM3u8) {
            HlsMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)
        } else {
            ProgressiveMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)
        }

        ExoPlayer.Builder(context)
            .setSeekBackIncrementMs(10_000L)
            .setSeekForwardIncrementMs(10_000L)
            .build()
            .apply {
                val paramsBuilder = trackSelectionParameters
                    .buildUpon()
                    .setPreferredAudioLanguage(if (preferDubAudioTrack) "en" else "ja")
                    .setPreferredTextLanguage(if (preferDubAudioTrack) null else "en")
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, preferDubAudioTrack)

                if (selectedResolutionHeight != null) {
                    paramsBuilder.setMaxVideoSize(Int.MAX_VALUE, selectedResolutionHeight)
                } else {
                    paramsBuilder.clearVideoSizeConstraints()
                }
                trackSelectionParameters = paramsBuilder.build()

                setMediaSource(mediaSource)
                setPlaybackSpeed(playbackSpeed)
                prepare()
                if (savedPositionMs > 0L) {
                    seekTo(savedPositionMs)
                }
                playWhenReady = true
            }
    }

    LaunchedEffect(playbackSpeed, exoPlayer) {
        exoPlayer.setPlaybackSpeed(playbackSpeed)
    }

    DisposableEffect(exoPlayer, autoPlayNext) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED && autoPlayNext) {
                    Toast.makeText(context, "Playing next episode...", Toast.LENGTH_SHORT).show()
                    currentOnEpisodeEnded()
                }
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
        }
    }

    LaunchedEffect(exoPlayer) {
        while (true) {
            delay(5_000L)
            if (exoPlayer.isPlaying) {
                val pos = exoPlayer.currentPosition.coerceAtLeast(0L)
                val dur = exoPlayer.duration.takeIf { it != C.TIME_UNSET } ?: 1L
                savedPositionMs = pos
                currentOnSaveProgress(pos, dur)
            }
        }
    }

    LaunchedEffect(selectedResolutionHeight, exoPlayer) {
        val builder = exoPlayer.trackSelectionParameters.buildUpon()
        if (selectedResolutionHeight == null) {
            builder.clearVideoSizeConstraints()
        } else {
            builder.setMaxVideoSize(Int.MAX_VALUE, selectedResolutionHeight)
            builder.setMinVideoSize(0, (selectedResolutionHeight - 260).coerceAtLeast(144))
        }
        exoPlayer.trackSelectionParameters = builder.build()
    }

    DisposableEffect(streamLink.url, preferDubAudioTrack) {
        onDispose {
            val pos = exoPlayer.currentPosition.coerceAtLeast(0L)
            val dur = exoPlayer.duration.takeIf { it != C.TIME_UNSET } ?: 1L
            savedPositionMs = pos
            currentOnSaveProgress(pos, dur)
            exoPlayer.release()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            setFullscreenLandscape(context, false)
        }
    }

    Box(
        modifier = if (isFullScreen) {
            Modifier.fillMaxSize().background(Color.Black)
        } else {
            Modifier.fillMaxWidth().height(240.dp).background(Color.Black)
        }
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = true
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
                    setShowSubtitleButton(true)
                    playerViewRef = this
                }
            },
            update = {
                it.player = exoPlayer
                playerViewRef = it
            },
            modifier = Modifier.fillMaxSize()
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 48.dp, bottom = 68.dp)
                .pointerInput(exoPlayer) {
                    detectTapGestures(
                        onTap = {
                            playerViewRef?.let { view ->
                                if (view.isControllerFullyVisible) {
                                    view.hideController()
                                } else {
                                    view.showController()
                                }
                            }
                        },
                        onDoubleTap = { offset ->
                            if (offset.x < size.width / 2f) {
                                exoPlayer.seekBack()
                                activeHudType = "SEEK_BACK"
                            } else {
                                exoPlayer.seekForward()
                                activeHudType = "SEEK_FORWARD"
                            }
                        }
                    )
                }
                .pointerInput(Unit) {
                    var isLeftZone = true
                    detectVerticalDragGestures(
                        onDragStart = { startOffset ->
                            isLeftZone = startOffset.x < (size.width / 2f)
                            isDraggingGesture = true
                            if (isLeftZone) {
                                activeHudType = "BRIGHTNESS"
                            } else {
                                val curVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                                volumeFraction = curVol.toFloat() / maxSystemVolume.toFloat()
                                activeHudType = "VOLUME"
                            }
                        },
                        onVerticalDrag = { change, dragAmount ->
                            change.consume()
                            val heightPx = size.height.toFloat().coerceAtLeast(1f)
                            val delta = (-dragAmount / heightPx) * 1.25f

                            if (isLeftZone) {
                                brightnessFraction = (brightnessFraction + delta).coerceIn(0f, 1f)
                                activity?.window?.let { win ->
                                    val lp = win.attributes
                                    lp.screenBrightness = brightnessFraction.coerceIn(0.005f, 1f)
                                    win.attributes = lp
                                }
                            } else {
                                volumeFraction = (volumeFraction + delta).coerceIn(0f, 1f)
                                val targetVol = (volumeFraction * maxSystemVolume)
                                    .roundToInt()
                                    .coerceIn(0, maxSystemVolume)
                                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVol, 0)
                            }
                        },
                        onDragEnd = { isDraggingGesture = false },
                        onDragCancel = { isDraggingGesture = false }
                    )
                }
        )

        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 10.dp, end = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(
                        if (autoPlayNext) Color(0xFF161821).copy(alpha = 0.85f)
                        else Color.DarkGray.copy(alpha = 0.65f)
                    )
                    .clickable {
                        autoPlayNext = !autoPlayNext
                        Toast.makeText(
                            context,
                            if (autoPlayNext) "Auto-Play Next: ON" else "Auto-Play Next: OFF",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.SkipNext,
                    contentDescription = "Auto Next",
                    tint = if (autoPlayNext) Color(0xFF38BDF8) else Color.Gray,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = if (autoPlayNext) "Auto-Next: ON" else "Auto-Next: OFF",
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 11.sp
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFF161821).copy(alpha = 0.85f))
                    .clickable {
                        val nextIndex = (speedOptions.indexOf(playbackSpeed) + 1) % speedOptions.size
                        playbackSpeed = speedOptions[nextIndex]
                        activeHudType = "SPEED"
                    }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Speed,
                    contentDescription = "Playback Speed",
                    tint = Color(0xFFFACC15),
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(4.dp))
                val speedText = if (playbackSpeed % 1f == 0f) "${playbackSpeed.toInt()}x" else "${playbackSpeed}x"
                Text(
                    text = speedText,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )
            }
        }

        activeHudType?.let { hud ->
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .align(Alignment.Center)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black.copy(alpha = 0.78f))
                    .padding(horizontal = 18.dp, vertical = 12.dp)
            ) {
                when (hud) {
                    "BRIGHTNESS" -> {
                        val pct = (brightnessFraction * 100).roundToInt()
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = if (pct < 35) Icons.Default.BrightnessLow else Icons.Default.BrightnessHigh,
                                    contentDescription = "Brightness",
                                    tint = Color(0xFFFACC15)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = "Brightness: $pct%",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            LinearProgressIndicator(
                                progress = { brightnessFraction },
                                modifier = Modifier
                                    .width(130.dp)
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp)),
                                color = Color(0xFFFACC15),
                                trackColor = Color.DarkGray
                            )
                        }
                    }

                    "VOLUME" -> {
                        val pct = (volumeFraction * 100).roundToInt()
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = if (pct == 0) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                                    contentDescription = "Volume",
                                    tint = Color(0xFF38BDF8)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = "Volume: $pct%",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            LinearProgressIndicator(
                                progress = { volumeFraction },
                                modifier = Modifier
                                    .width(130.dp)
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp)),
                                color = Color(0xFF38BDF8),
                                trackColor = Color.DarkGray
                            )
                        }
                    }

                    "SEEK_BACK" -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.FastRewind, contentDescription = null, tint = Color.White)
                            Spacer(Modifier.width(6.dp))
                            Text("-10s", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }

                    "SEEK_FORWARD" -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.FastForward, contentDescription = null, tint = Color.White)
                            Spacer(Modifier.width(6.dp))
                            Text("+10s", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }

                    "SPEED" -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Speed, contentDescription = null, tint = Color(0xFFFACC15))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "Speed: ${playbackSpeed}x",
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 14.dp, bottom = 56.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFFE11D48).copy(alpha = 0.92f))
                    .clickable {
                        val targetPos = exoPlayer.currentPosition + 85_000L
                        val maxDur = exoPlayer.duration
                        exoPlayer.seekTo(
                            if (maxDur != C.TIME_UNSET && targetPos > maxDur) maxDur else targetPos
                        )
                        Toast.makeText(context, "Skipped Intro (+85s)", Toast.LENGTH_SHORT).show()
                    }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.FastForward,
                    contentDescription = "Skip Intro",
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "+85s Skip Intro",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
            }

            IconButton(
                onClick = {
                    val nextState = !isFullScreen
                    setFullscreenLandscape(context, nextState)
                    onFullScreenChange(nextState)
                },
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF161821).copy(alpha = 0.85f))
            ) {
                Icon(
                    imageVector = if (isFullScreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                    contentDescription = "Toggle Fullscreen",
                    tint = Color.White
                )
            }
        }
    }
}
