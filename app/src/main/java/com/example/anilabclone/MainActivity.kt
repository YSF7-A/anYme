package com.example.anilabclone

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = Color(0xFF0B0C10))) {
                AniLabMainScreen()
            }
        }
    }
}

@Composable
fun AniLabMainScreen() {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var currentTab by remember { mutableStateOf("HOME") }
    var animeList by remember { mutableStateOf<List<AnimeMedia>>(emptyList()) }
    var scheduleList by remember { mutableStateOf<List<ScheduleItem>>(emptyList()) }
    var watchHistory by remember { mutableStateOf<List<WatchHistoryItem>>(emptyList()) }
    var bookmarkList by remember { mutableStateOf<List<AnimeMedia>>(emptyList()) }
    var offlineList by remember { mutableStateOf<List<OfflineEpisodeItem>>(emptyList()) }

    var selectedAnime by remember { mutableStateOf<AnimeMedia?>(null) }
    var isCurrentBookmarked by remember { mutableStateOf(false) }

    var searchQuery by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }

    var selectedAudioType by remember { mutableStateOf("SUB") }
    var currentEpisode by remember { mutableStateOf(1) }
    var availableStreams by remember { mutableStateOf<List<StreamLink>>(emptyList()) }
    var activeStream by remember { mutableStateOf<StreamLink?>(null) }
    var activeOfflineItem by remember { mutableStateOf<OfflineEpisodeItem?>(null) }
    var selectedResolution by remember { mutableStateOf<Int?>(null) }
    var resumePositionMs by remember { mutableLongStateOf(0L) }
    var isFullScreen by remember { mutableStateOf(false) }

    fun refreshLocalData() {
        watchHistory = LocalStorageManager.getWatchHistory(context)
        bookmarkList = LocalStorageManager.getBookmarks(context)
        offlineList = LocalStorageManager.getOfflineEpisodes()
        selectedAnime?.let {
            isCurrentBookmarked = LocalStorageManager.isBookmarked(context, it.id)
        }
    }

    fun loadEpisodeStreams(anime: AnimeMedia, epNum: Int, audioType: String) {
        selectedAnime = anime
        currentEpisode = epNum
        selectedAudioType = audioType
        isCurrentBookmarked = LocalStorageManager.isBookmarked(context, anime.id)
        resumePositionMs = LocalStorageManager.getSavedEpisodePosition(context, anime.id, epNum)

        scope.launch {
            isLoading = true
            availableStreams = AnimeRepository.resolveEpisodeStreams(
                animeTitle = anime.title,
                romajiTitle = anime.romajiTitle,
                episodeNumber = epNum,
                audioType = audioType
            )
            activeStream = availableStreams.firstOrNull()
            isLoading = false
        }
    }

    LaunchedEffect(Unit) {
        refreshLocalData()
        isLoading = true
        animeList = AnimeRepository.fetchAnimeList()
        scheduleList = AnimeRepository.fetchWeeklySchedule()
        isLoading = false
    }

    Scaffold(
        containerColor = Color(0xFF0B0C10),
        bottomBar = {
            if (selectedAnime == null && !isFullScreen) {
                NavigationBar(containerColor = Color(0xFF14161F)) {
                    NavigationBarItem(
                        selected = currentTab == "HOME",
                        onClick = {
                            activeOfflineItem = null
                            refreshLocalData()
                            currentTab = "HOME"
                        },
                        icon = { Icon(Icons.Default.Home, contentDescription = "Home") },
                        label = { Text("Discover") }
                    )
                    NavigationBarItem(
                        selected = currentTab == "SCHEDULE",
                        onClick = {
                            activeOfflineItem = null
                            currentTab = "SCHEDULE"
                        },
                        icon = { Icon(Icons.Default.CalendarMonth, contentDescription = "Schedule") },
                        label = { Text("Schedule") }
                    )
                    NavigationBarItem(
                        selected = currentTab == "MY_LIST",
                        onClick = {
                            activeOfflineItem = null
                            refreshLocalData()
                            currentTab = "MY_LIST"
                        },
                        icon = { Icon(Icons.Default.Bookmark, contentDescription = "My List") },
                        label = { Text("My List") }
                    )
                    NavigationBarItem(
                        selected = currentTab == "DOWNLOADS",
                        onClick = {
                            refreshLocalData()
                            currentTab = "DOWNLOADS"
                        },
                        icon = { Icon(Icons.Default.DownloadDone, contentDescription = "Downloads") },
                        label = { Text("Downloads") }
                    )
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(if (isFullScreen) PaddingValues(0.dp) else padding)
                .padding(if (isFullScreen) 0.dp else 12.dp)
        ) {
            if (selectedAnime != null) {
                val anime = selectedAnime!!
                BackHandler(enabled = !isFullScreen) {
                    activeStream = null
                    selectedAnime = null
                    refreshLocalData()
                }

                if (!isFullScreen) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = {
                            activeStream = null
                            selectedAnime = null
                            refreshLocalData()
                        }) {
                            Text("← Back", color = Color.White)
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = {
                                    val added = LocalStorageManager.toggleBookmark(context, anime)
                                    isCurrentBookmarked = added
                                    refreshLocalData()
                                    Toast.makeText(
                                        context,
                                        if (added) "Added to My List" else "Removed from My List",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            ) {
                                Icon(
                                    imageVector = if (isCurrentBookmarked) Icons.Default.BookmarkAdded else Icons.Default.BookmarkBorder,
                                    contentDescription = "Bookmark",
                                    tint = if (isCurrentBookmarked) Color(0xFFE11D48) else Color.White
                                )
                            }

                            Spacer(Modifier.width(6.dp))

                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(20.dp))
                                    .background(Color(0xFF1C1F2B))
                                    .padding(4.dp)
                            ) {
                                listOf("SUB", "DUB").forEach { type ->
                                    val isSelected = selectedAudioType == type
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(16.dp))
                                            .background(if (isSelected) Color(0xFFE11D48) else Color.Transparent)
                                            .clickable { loadEpisodeStreams(anime, currentEpisode, type) }
                                            .padding(horizontal = 14.dp, vertical = 6.dp)
                                    ) {
                                        Text(type, color = Color.White, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }

                if (isLoading && !isFullScreen) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Color(0xFFE11D48))
                    Spacer(Modifier.height(6.dp))
                }

                activeStream?.let { stream ->
                    AniLabVideoPlayer(
                        episodeKey = "${anime.id}_$currentEpisode",
                        streamLink = stream,
                        preferDubAudioTrack = selectedAudioType == "DUB",
                        selectedResolutionHeight = selectedResolution,
                        initialPositionMs = resumePositionMs,
                        onSaveProgress = { posMs, durMs ->
                            LocalStorageManager.saveWatchProgress(
                                context = context,
                                anime = anime,
                                episodeNumber = currentEpisode,
                                audioType = selectedAudioType,
                                positionMs = posMs,
                                durationMs = durMs
                            )
                        },
                        onEpisodeEnded = {
                            if (currentEpisode < anime.episodes) {
                                loadEpisodeStreams(anime, currentEpisode + 1, selectedAudioType)
                            } else {
                                Toast.makeText(
                                    context,
                                    "You have completed the final episode!",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        },
                        isFullScreen = isFullScreen,
                        onFullScreenChange = { full -> isFullScreen = full }
                    )

                    if (!isFullScreen) {
                        Spacer(Modifier.height(10.dp))

                        Text(
                            text = "Servers (${availableStreams.size} available):",
                            color = Color.LightGray,
                            style = MaterialTheme.typography.labelMedium
                        )
                        Spacer(Modifier.height(6.dp))

                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(availableStreams) { serverItem ->
                                val isCurrentServer = serverItem.url == stream.url
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (isCurrentServer) Color(0xFFE11D48) else Color(0xFF1C1F2B))
                                        .clickable { activeStream = serverItem }
                                        .padding(horizontal = 12.dp, vertical = 7.dp)
                                ) {
                                    Text(
                                        text = "${serverItem.serverName} • ${serverItem.quality}",
                                        color = Color.White,
                                        fontWeight = if (isCurrentServer) FontWeight.Bold else FontWeight.Normal,
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(10.dp))

                        val resolutionOptions = listOf(
                            "Auto" to null,
                            "1080p" to 1080,
                            "720p" to 720,
                            "480p" to 480,
                            "360p" to 360
                        )

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "Quality:",
                                color = Color.LightGray,
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(end = 8.dp)
                            )
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(resolutionOptions) { (label, heightVal) ->
                                    val isSelectedRes = selectedResolution == heightVal
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(if (isSelectedRes) Color(0xFF38BDF8) else Color(0xFF161821))
                                            .clickable { selectedResolution = heightVal }
                                            .padding(horizontal = 10.dp, vertical = 5.dp)
                                    ) {
                                        Text(
                                            text = label,
                                            color = if (isSelectedRes) Color.Black else Color.White,
                                            fontWeight = FontWeight.Bold,
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                    }
                }

                if (!isFullScreen) {
                    Text(anime.title, color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))

                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items((1..anime.episodes).toList()) { epNum ->
                            val savedEpPos = LocalStorageManager.getSavedEpisodePosition(context, anime.id, epNum)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (currentEpisode == epNum) Color(0xFF291522) else Color(0xFF161821))
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable { loadEpisodeStreams(anime, epNum, selectedAudioType) }
                                ) {
                                    Icon(Icons.Default.PlayArrow, contentDescription = "Play", tint = Color(0xFFE11D48))
                                    Spacer(Modifier.width(8.dp))
                                    Column {
                                        Text("Episode $epNum", color = Color.White, fontWeight = FontWeight.SemiBold)
                                        val statusText = if (savedEpPos > 5000L) {
                                            "Resume at ${LocalStorageManager.formatTimeMs(savedEpPos)} • $selectedAudioType"
                                        } else {
                                            "Audio: $selectedAudioType • 1080p"
                                        }
                                        Text(statusText, color = Color(0xFF38BDF8), style = MaterialTheme.typography.labelSmall)
                                    }
                                }

                                IconButton(
                                    onClick = {
                                        scope.launch {
                                            val streams = AnimeRepository.resolveEpisodeStreams(
                                                animeTitle = anime.title,
                                                romajiTitle = anime.romajiTitle,
                                                episodeNumber = epNum,
                                                audioType = selectedAudioType
                                            )
                                            val downloadable = streams.firstOrNull { !it.isM3u8 } ?: streams.firstOrNull()
                                            if (downloadable != null) {
                                                downloadAnimeEpisode(
                                                    context = context,
                                                    animeTitle = anime.title,
                                                    episodeNum = epNum,
                                                    audioType = selectedAudioType,
                                                    videoUrl = downloadable.url,
                                                    referer = downloadable.referer
                                                )
                                            }
                                        }
                                    }
                                ) {
                                    Icon(Icons.Default.Download, contentDescription = "Download", tint = Color(0xFF38BDF8))
                                }
                            }
                        }
                    }
                }

            } else if (currentTab == "HOME") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("Search Anime...") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            scope.launch {
                                isLoading = true
                                animeList = AnimeRepository.fetchAnimeList(searchQuery)
                                isLoading = false
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE11D48))
                    ) {
                        Text("Go")
                    }
                }

                Spacer(Modifier.height(12.dp))

                if (watchHistory.isNotEmpty() && searchQuery.isBlank()) {
                    Text(
                        text = "Continue Watching",
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(8.dp))

                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(watchHistory) { item ->
                            val progressFraction = (item.positionMs.toFloat() / item.durationMs.toFloat()).coerceIn(0.05f, 1f)
                            Column(
                                modifier = Modifier
                                    .width(175.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xFF161821))
                                    .clickable {
                                        loadEpisodeStreams(item.anime, item.episodeNumber, item.audioType)
                                    }
                            ) {
                                Box {
                                    AsyncImage(
                                        model = item.anime.coverImage,
                                        contentDescription = item.anime.title,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxWidth().height(95.dp)
                                    )
                                    IconButton(
                                        onClick = {
                                            LocalStorageManager.removeFromWatchHistory(context, item.anime.id)
                                            refreshLocalData()
                                        },
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .size(26.dp)
                                            .background(Color.Black.copy(alpha = 0.65f))
                                    ) {
                                        Icon(
                                            Icons.Default.Close,
                                            contentDescription = "Remove",
                                            tint = Color.White,
                                            modifier = Modifier.size(15.dp)
                                        )
                                    }
                                    Text(
                                        text = "Ep ${item.episodeNumber} • ${item.audioType}",
                                        color = Color.White,
                                        style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier
                                            .align(Alignment.BottomStart)
                                            .background(Color(0xFFE11D48))
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                                LinearProgressIndicator(
                                    progress = { progressFraction },
                                    modifier = Modifier.fillMaxWidth().height(4.dp),
                                    color = Color(0xFFE11D48),
                                    trackColor = Color(0xFF2A2E3D)
                                )
                                Column(modifier = Modifier.padding(6.dp)) {
                                    Text(
                                        text = item.anime.title,
                                        color = Color.White,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = "${LocalStorageManager.formatTimeMs(item.positionMs)} / ${LocalStorageManager.formatTimeMs(item.durationMs)}",
                                        color = Color.Gray,
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                }

                LazyVerticalGrid(
                    columns = GridCells.Adaptive(110.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(animeList) { anime ->
                        Column(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF161821))
                                .clickable { loadEpisodeStreams(anime, 1, selectedAudioType) }
                        ) {
                            AsyncImage(
                                model = anime.coverImage,
                                contentDescription = anime.title,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxWidth().height(155.dp)
                            )
                            Text(
                                text = anime.title,
                                color = Color.White,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(6.dp)
                            )
                        }
                    }
                }

            } else if (currentTab == "MY_LIST") {
                Text(
                    text = "My Watchlist (${bookmarkList.size})",
                    color = Color.White,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(10.dp))

                if (bookmarkList.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No bookmarked anime yet. Tap the bookmark icon on any anime!", color = Color.Gray)
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(110.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(bookmarkList) { anime ->
                            Column(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xFF161821))
                                    .clickable { loadEpisodeStreams(anime, 1, selectedAudioType) }
                            ) {
                                AsyncImage(
                                    model = anime.coverImage,
                                    contentDescription = anime.title,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxWidth().height(155.dp)
                                )
                                Text(
                                    text = anime.title,
                                    color = Color.White,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(6.dp)
                                )
                            }
                        }
                    }
                }

            } else if (currentTab == "DOWNLOADS") {
                BackHandler(enabled = activeOfflineItem != null && !isFullScreen) {
                    activeOfflineItem = null
                }

                activeOfflineItem?.let { offlineEp ->
                    val offlineStream = StreamLink(
                        serverName = "Offline Storage",
                        url = offlineEp.fileUri,
                        isM3u8 = false,
                        quality = "Downloaded MP4 (${offlineEp.sizeMb})",
                        audioType = offlineEp.audioType
                    )

                    if (!isFullScreen) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${offlineEp.displayTitle} • Ep ${offlineEp.episodeNumber}",
                                color = Color(0xFFE11D48),
                                fontWeight = FontWeight.Bold,
                                maxLines = 1
                            )
                            TextButton(onClick = { activeOfflineItem = null }) {
                                Text("Close Player ✕", color = Color.LightGray)
                            }
                        }
                    }

                    AniLabVideoPlayer(
                        episodeKey = "offline_${offlineEp.fileName}",
                        streamLink = offlineStream,
                        preferDubAudioTrack = offlineEp.audioType == "DUB",
                        selectedResolutionHeight = null,
                        initialPositionMs = LocalStorageManager.getSavedEpisodePosition(
                            context,
                            offlineEp.displayTitle.hashCode(),
                            offlineEp.episodeNumber
                        ),
                        onSaveProgress = { posMs, _ ->
                            context.getSharedPreferences("anilab_local_store", Context.MODE_PRIVATE)
                                .edit()
                                .putLong("ep_pos_${offlineEp.displayTitle.hashCode()}_${offlineEp.episodeNumber}", posMs)
                                .apply()
                        },
                        onEpisodeEnded = {
                            val currentIndex = offlineList.indexOf(offlineEp)
                            if (currentIndex >= 0 && currentIndex + 1 < offlineList.size) {
                                activeOfflineItem = offlineList[currentIndex + 1]
                            }
                        },
                        isFullScreen = isFullScreen,
                        onFullScreenChange = { full -> isFullScreen = full }
                    )

                    if (!isFullScreen) {
                        Spacer(Modifier.height(14.dp))
                    }
                }

                if (!isFullScreen) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Offline Downloads (${offlineList.size})",
                                color = Color.White,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Folder: Movies/AniLab • Works without internet",
                                color = Color.Gray,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }

                        IconButton(onClick = { refreshLocalData() }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = Color(0xFF38BDF8))
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    if (offlineList.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                text = "No downloaded episodes found.\nTap the download icon on any episode to watch offline!",
                                color = Color.Gray
                            )
                        }
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(offlineList) { item ->
                                val isPlayingThis = activeOfflineItem?.filePath == item.filePath
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (isPlayingThis) Color(0xFF291522) else Color(0xFF161821))
                                        .clickable { activeOfflineItem = item }
                                        .padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.PlayCircleFilled,
                                            contentDescription = "Play Offline",
                                            tint = Color(0xFFE11D48),
                                            modifier = Modifier.size(36.dp)
                                        )
                                        Spacer(Modifier.width(12.dp))
                                        Column {
                                            Text(
                                                text = item.displayTitle,
                                                color = Color.White,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Spacer(Modifier.height(2.dp))
                                            Text(
                                                text = "Episode ${item.episodeNumber} • ${item.audioType} • ${item.sizeMb}",
                                                color = Color(0xFF38BDF8),
                                                style = MaterialTheme.typography.labelSmall
                                            )
                                        }
                                    }

                                    IconButton(
                                        onClick = {
                                            if (activeOfflineItem?.filePath == item.filePath) {
                                                activeOfflineItem = null
                                            }
                                            val deleted = LocalStorageManager.deleteOfflineEpisode(item.filePath)
                                            refreshLocalData()
                                            Toast.makeText(
                                                context,
                                                if (deleted) "Deleted ${item.fileName}" else "Could not delete file",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.DeleteOutline,
                                            contentDescription = "Delete File",
                                            tint = Color.Gray
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

            } else {
                val days = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
                var selectedDay by remember { mutableStateOf(days.first()) }

                Text("Weekly Simulcast Schedule", color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))

                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(days) { day ->
                        FilterChip(
                            selected = selectedDay == day,
                            onClick = { selectedDay = day },
                            label = { Text(day.take(3)) }
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))

                val filteredSchedule = scheduleList.filter { it.dayName.equals(selectedDay, ignoreCase = true) }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(filteredSchedule) { item ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF161821))
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AsyncImage(
                                model = item.coverImage,
                                contentDescription = item.title,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.size(55.dp, 75.dp).clip(RoundedCornerShape(6.dp))
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(item.title, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1)
                                Text("Episode ${item.episode}", color = Color(0xFFE11D48), style = MaterialTheme.typography.bodySmall)
                            }
                            Text(item.formattedTime, color = Color(0xFF38BDF8), fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}
