python3 -c '
code = """package com.example.anilabclone

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
    var bookmarkList by remember { mutableStateOf<List<AnimeMedia>>(emptyList()) }
    var offlineList by remember { mutableStateOf<List<OfflineEpisodeItem>>(emptyList()) }
    var selectedAnime by remember { mutableStateOf<AnimeMedia?>(null) }
    var isCurrentBookmarked by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var selectedAudioType by remember { mutableStateOf("SUB") }
    var currentEpisode by remember { mutableIntStateOf(1) }
    var activeStream by remember { mutableStateOf<StreamLink?>(null) }
    var resumePositionMs by remember { mutableLongStateOf(0L) }
    var isFullScreen by remember { mutableStateOf(false) }

    fun refreshLocalData() {
        bookmarkList = LocalStorageManager.getBookmarks(context)
        offlineList = LocalStorageManager.getOfflineEpisodes()
        selectedAnime?.let { isCurrentBookmarked = LocalStorageManager.isBookmarked(context, it.id) }
    }

    fun loadEpisodeStreams(anime: AnimeMedia, epNum: Int, audioType: String) {
        selectedAnime = anime
        currentEpisode = epNum
        selectedAudioType = audioType
        isCurrentBookmarked = LocalStorageManager.isBookmarked(context, anime.id)
        resumePositionMs = LocalStorageManager.getSavedEpisodePosition(context, anime.id, epNum)
        scope.launch {
            isLoading = true
            val streams = AnimeRepository.resolveEpisodeStreams(anime.title, anime.romajiTitle, epNum, audioType)
            activeStream = streams.firstOrNull()
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
                    NavigationBarItem(selected = currentTab == "HOME", onClick = { refreshLocalData(); currentTab = "HOME" }, icon = { Icon(Icons.Default.Home, null) }, label = { Text("Discover") })
                    NavigationBarItem(selected = currentTab == "SCHEDULE", onClick = { currentTab = "SCHEDULE" }, icon = { Icon(Icons.Default.CalendarMonth, null) }, label = { Text("Schedule") })
                    NavigationBarItem(selected = currentTab == "MY_LIST", onClick = { refreshLocalData(); currentTab = "MY_LIST" }, icon = { Icon(Icons.Default.Bookmark, null) }, label = { Text("My List") })
                    NavigationBarItem(selected = currentTab == "DOWNLOADS", onClick = { refreshLocalData(); currentTab = "DOWNLOADS" }, icon = { Icon(Icons.Default.DownloadDone, null) }, label = { Text("Downloads") })
                }
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(if (isFullScreen) PaddingValues(0.dp) else padding).padding(if (isFullScreen) 0.dp else 12.dp)) {
            if (selectedAnime != null) {
                val anime = selectedAnime!!
                BackHandler(enabled = !isFullScreen) { activeStream = null; selectedAnime = null; refreshLocalData() }
                if (!isFullScreen) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { activeStream = null; selectedAnime = null; refreshLocalData() }) { Text("Back", color = Color.White) }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = {
                                isCurrentBookmarked = LocalStorageManager.toggleBookmark(context, anime)
                                refreshLocalData()
                            }) {
                                Icon(if (isCurrentBookmarked) Icons.Default.BookmarkAdded else Icons.Default.BookmarkBorder, null, tint = if (isCurrentBookmarked) Color(0xFFE11D48) else Color.White)
                            }
                            listOf("SUB", "DUB").forEach { type ->
                                TextButton(onClick = { loadEpisodeStreams(anime, currentEpisode, type) }) {
                                    Text(type, color = if (selectedAudioType == type) Color(0xFFE11D48) else Color.Gray, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
                if (isLoading && !isFullScreen) LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Color(0xFFE11D48))
                activeStream?.let { stream ->
                    AniLabVideoPlayer(
                        episodeKey = "${anime.id}_$currentEpisode",
                        streamLink = stream,
                        preferDubAudioTrack = selectedAudioType == "DUB",
                        selectedResolutionHeight = null,
                        initialPositionMs = resumePositionMs,
                        onSaveProgress = { pos, dur -> LocalStorageManager.saveWatchProgress(context, anime, currentEpisode, selectedAudioType, pos, dur) },
                        onEpisodeEnded = { if (currentEpisode < anime.episodes) loadEpisodeStreams(anime, currentEpisode + 1, selectedAudioType) },
                        isFullScreen = isFullScreen,
                        onFullScreenChange = { isFullScreen = it }
                    )
                }
                if (!isFullScreen) {
                    Spacer(Modifier.height(8.dp))
                    Text(anime.title, color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items((1..anime.episodes).toList()) { epNum ->
                            Row(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(if (currentEpisode == epNum) Color(0xFF291522) else Color(0xFF161821)).padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("Episode $epNum ($selectedAudioType)", color = Color.White, modifier = Modifier.weight(1f).clickable { loadEpisodeStreams(anime, epNum, selectedAudioType) })
                                IconButton(onClick = {
                                    scope.launch {
                                        val s = AnimeRepository.resolveEpisodeStreams(anime.title, anime.romajiTitle, epNum, selectedAudioType).firstOrNull()
                                        if (s != null) downloadAnimeEpisode(context, anime.title, epNum, selectedAudioType, s.url, s.referer)
                                    }
                                }) { Icon(Icons.Default.Download, null, tint = Color(0xFF38BDF8)) }
                            }
                        }
                    }
                }
            } else if (currentTab == "HOME") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(value = searchQuery, onValueChange = { searchQuery = it }, placeholder = { Text("Search Anime...") }, singleLine = true, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { scope.launch { isLoading = true; animeList = AnimeRepository.fetchAnimeList(searchQuery); isLoading = false } }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE11D48))) { Text("Go") }
                }
                Spacer(Modifier.height(10.dp))
                if (isLoading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Color(0xFFE11D48))
                LazyVerticalGrid(columns = GridCells.Adaptive(105.dp), verticalArrangement = Arrangement.spacedBy(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(animeList) { anime ->
                        Column(modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Color(0xFF161821)).clickable { loadEpisodeStreams(anime, 1, selectedAudioType) }) {
                            AsyncImage(model = anime.coverImage, contentDescription = anime.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().height(150.dp))
                            Text(anime.title, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(6.dp))
                        }
                    }
                }
            } else if (currentTab == "MY_LIST") {
                LazyVerticalGrid(columns = GridCells.Adaptive(105.dp), verticalArrangement = Arrangement.spacedBy(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(bookmarkList) { anime ->
                        Column(modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Color(0xFF161821)).clickable { loadEpisodeStreams(anime, 1, selectedAudioType) }) {
                            AsyncImage(model = anime.coverImage, contentDescription = anime.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().height(150.dp))
                            Text(anime.title, color = Color.White, maxLines = 2, modifier = Modifier.padding(6.dp))
                        }
                    }
                }
            } else if (currentTab == "DOWNLOADS") {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(offlineList) { item ->
                        Text("${item.displayTitle} - Ep ${item.episodeNumber} (${item.sizeMb})", color = Color.White, modifier = Modifier.fillMaxWidth().background(Color(0xFF161821)).padding(12.dp))
                    }
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(scheduleList) { item ->
                        Row(modifier = Modifier.fillMaxWidth().background(Color(0xFF161821)).padding(10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("${item.title} (Ep ${item.episode})", color = Color.White, modifier = Modifier.weight(1f))
                            Text(item.formattedTime, color = Color(0xFF38BDF8))
                        }
                    }
                }
            }
        }
    }
}
"""
open("app/src/main/java/com/example/anilabclone/MainActivity.kt", "w").write(code)
print("SAVED BYTES:", len(code))
' && git add . && git commit -m "Write MainActivity via python" && git push -u origin main
