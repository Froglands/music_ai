package com.music_ai.app

import android.content.ContentValues
import android.media.MediaPlayer
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.ui.draw.blur
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import coil.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.delay
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.border
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

// Backend URL is configured and saved from the Settings screen.

private enum class AppPage { STUDIO, EDIT, SETTINGS }

private data class MusicTrack(val title: String, val audioBytes: ByteArray, val imageUrl: String? = null, val durationSeconds: Float? = null, val fileName: String? = null)

private fun loadMusicHistory(context: android.content.Context): List<MusicTrack> {
    val prefs = context.getSharedPreferences("musicai_history", android.content.Context.MODE_PRIVATE)
    val records = runCatching { org.json.JSONArray(prefs.getString("tracks", "[]")) }.getOrNull() ?: return emptyList()
    val folder = java.io.File(context.filesDir, "music_history")
    return buildList {
        for (i in 0 until records.length()) {
            val item = records.optJSONObject(i) ?: continue
            val fileName = item.optString("fileName")
            val file = java.io.File(folder, fileName)
            if (fileName.isBlank() || !file.exists() || file.length() == 0L) continue
            runCatching {
                add(MusicTrack(item.optString("title", "MusicAI"), file.readBytes(),
                    item.optString("imageUrl").takeIf { it.startsWith("https://") },
                    if (item.has("duration")) item.optDouble("duration").toFloat() else null, fileName))
            }
        }
    }
}

private fun saveMusicHistory(context: android.content.Context, tracks: List<MusicTrack>): List<MusicTrack> {
    val folder = java.io.File(context.filesDir, "music_history").apply { mkdirs() }
    val saved = tracks.mapIndexed { index, track ->
        val name = track.fileName ?: "track_${System.currentTimeMillis()}_${index}.mp3"
        java.io.File(folder, name).writeBytes(track.audioBytes)
        track.copy(fileName = name)
    }
    val records = org.json.JSONArray()
    saved.forEach { track ->
        records.put(JSONObject().apply {
            put("title", track.title)
            put("fileName", track.fileName)
            put("imageUrl", track.imageUrl ?: "")
            track.durationSeconds?.let { put("duration", it.toDouble()) }
        })
    }
    context.getSharedPreferences("musicai_history", android.content.Context.MODE_PRIVATE)
        .edit().putString("tracks", records.toString()).apply()
    return saved
}

private fun formatTime(milliseconds: Int): String {
    val seconds = milliseconds.coerceAtLeast(0) / 1000
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MusicAiScreen() }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MusicAiScreen() {
    val context = LocalContext.current
    val activity = context as ComponentActivity
    val bg = Color(0xFF090B16)
    val panel = Color(0xCC211D35)
    val accent = Color(0xFF9B7BFF)

    var page by remember { mutableStateOf(AppPage.STUDIO) }
    var title by remember { mutableStateOf("") }
    var lyrics by remember { mutableStateOf("") }
    var selectedGenres by remember { mutableStateOf(setOf("Dangdut Koplo")) }
    var customGenre by remember { mutableStateOf("") }
    var showGenreDialog by remember { mutableStateOf(false) }
    var vocal by remember { mutableStateOf("female") }
    var bpm by remember { mutableFloatStateOf(110f) }
    var durationSeconds by remember { mutableIntStateOf(120) }
    var mood by remember { mutableStateOf("Enerjik dan emosional") }
    var inputMode by remember { mutableStateOf("auto") }
    var selectedMoods by remember { mutableStateOf(setOf("Emosional", "Sedikit sedih")) }
    var customMood by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Siap membuat lagu.") }
    var audioBytes by remember { mutableStateOf<ByteArray?>(null) }
    var audioMimeType by remember { mutableStateOf("audio/mpeg") }
    var generatedTracks by remember { mutableStateOf(loadMusicHistory(context)) }
    var selectedTrackIndex by remember { mutableIntStateOf(0) }
    var isPlaying by remember { mutableStateOf(false) }
    var playbackPosition by remember { mutableIntStateOf(0) }
    var playbackDuration by remember { mutableIntStateOf(0) }
    var importedFileName by remember { mutableStateOf("") }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var backendReachable by remember { mutableStateOf<Boolean?>(null) }
    var backendUrl by remember {
        mutableStateOf(context.getSharedPreferences("musicai_settings", android.content.Context.MODE_PRIVATE).getString("backend_url", "") ?: "")
    }

    val importAudioLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw IllegalStateException("File tidak bisa dibaca.")
                if (bytes.isEmpty()) throw IllegalStateException("File audio kosong.")
                val mime = context.contentResolver.getType(uri) ?: "audio/mpeg"
                val name = uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':') ?: "Flow Music"
                audioBytes = bytes
                audioMimeType = mime
                importedFileName = name
                title = name.substringBeforeLast('.', name).ifBlank { "Lagu dari Flow" }
                status = "Audio berhasil diimpor • ${bytes.size / 1024} KB"
                page = AppPage.EDIT
            } catch (e: Exception) {
                status = "Gagal mengimpor audio: ${e.message}"
            }
        }
    }

    val genres = listOf("Pop", "Pop Rock", "Indie Pop", "Rock", "Alternative Rock", "Punk Rock", "Pop Punk", "Ska", "Ska-Punk", "Reggae", "Roots Reggae", "Dancehall", "Dangdut", "Dangdut Koplo", "Dangdut Rock", "Keroncong", "Rap", "Hip-Hop", "Trap", "R&B", "Soul", "Funk", "Jazz", "Blues", "Country", "Folk", "Metal", "Heavy Metal", "Hardcore Punk", "Post-Hardcore", "Emo Rock", "Acoustic", "Ballad", "EDM", "House", "Techno", "Trance", "Drum and Bass", "Lo-fi", "Cinematic", "Orchestral")
    val moodOptions = listOf("Bahagia", "Sedih", "Emosional", "Romantis", "Patah hati", "Nostalgia", "Rindu", "Sendu", "Enerjik", "Semangat", "Agresif", "Gelap", "Tenang", "Damai", "Dreamy", "Melankolis", "Epik", "Ceria", "Santai", "Heroik", "Intim", "Optimistis", "Misterius")

    fun generateSong() {
        if (busy) return
        if (backendUrl.isBlank()) {
            status = "Masukkan URL backend HTTPS di Pengaturan terlebih dahulu."
            page = AppPage.SETTINGS
            return
        }
        if (lyrics.trim().length < 10) {
            status = "Lirik minimal 10 karakter."
            return
        }
        busy = true
        status = "Mengirim permintaan ke server..."
        Thread {
            try {
                val json = JSONObject().apply {
                    put("title", title.trim())
                    put("lyrics", lyrics)
                    put("inputMode", "auto")
                    put("genre", (selectedGenres + listOf(customGenre.trim()).filter { it.isNotBlank() }).joinToString(", ").ifBlank { "Pop" })
                    put("vocal", vocal)
                    put("instrumental", vocal == "instrumental")
                    put("bpm", bpm.toInt())
                    put("duration", durationSeconds)
                    put("mood", (selectedMoods + listOf(customMood.trim()).filter { it.isNotBlank() }).joinToString(", ").ifBlank { mood })
                }
                val client = OkHttpClient.Builder()
                    .callTimeout(10, TimeUnit.MINUTES)
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(10, TimeUnit.MINUTES)
                    .build()
                val request = Request.Builder()
                    .url("${backendUrl.trim().trimEnd('/')}/api/generate")
                    .post(json.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                val jobId = client.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        val message = try { JSONObject(body).optString("error", "Server error ${response.code}") } catch (_: Exception) { "Server error ${response.code}" }
                        throw IllegalStateException(message)
                    }
                    JSONObject(body).optString("jobId").takeIf { it.isNotBlank() }
                        ?: throw IllegalStateException("Server tidak mengembalikan ID generasi.")
                }

                var completedTracks: org.json.JSONArray? = null
                var lastStatus = "QUEUED"
                var attempts = 0
                while (completedTracks == null && attempts < 90) {
                    attempts++
                    Thread.sleep(5_000)
                    val statusRequest = Request.Builder()
                        .url("${backendUrl.trim().trimEnd('/')}/api/job/${jobId}")
                        .get()
                        .build()
                    client.newCall(statusRequest).execute().use { response ->
                        val body = response.body?.string().orEmpty()
                        if (!response.isSuccessful) {
                            val message = try { JSONObject(body).optString("error", "Gagal memeriksa status.") } catch (_: Exception) { "Gagal memeriksa status." }
                            throw IllegalStateException(message)
                        }
                        val job = JSONObject(body)
                        lastStatus = job.optString("status", "PROCESSING")
                        val normalizedStatus = lastStatus.uppercase()
                        if (normalizedStatus == "FAILED") throw IllegalStateException(job.optString("error", "Generasi lagu gagal."))
                        if (normalizedStatus == "COMPLETED" || normalizedStatus == "SUCCEEDED") completedTracks = job.optJSONArray("tracks")
                    }
                    if (completedTracks == null) {
                        activity.runOnUiThread { status = "Membuat lagu dengan Suno... (${lastStatus.lowercase()})" }
                    }
                }
                val tracks = completedTracks ?: throw IllegalStateException("Generasi belum selesai setelah 7,5 menit. Coba periksa kembali nanti.")
                if (tracks.length() == 0) throw IllegalStateException("Suno selesai tetapi tidak mengembalikan audio.")
                val downloaded = mutableListOf<MusicTrack>()
                for (index in 0 until tracks.length()) {
                    val track = tracks.getJSONObject(index)
                    val audioUrl = track.optString("audioUrl")
                    if (!audioUrl.startsWith("https://")) continue
                    val bytes = client.newCall(Request.Builder().url(audioUrl).get().build()).execute().use { response ->
                        if (!response.isSuccessful) throw IllegalStateException("Gagal mengunduh hasil ${index + 1} (${response.code}).")
                        response.body?.bytes() ?: throw IllegalStateException("Audio hasil ${index + 1} kosong.")
                    }
                    if (bytes.isNotEmpty()) downloaded.add(MusicTrack(
                        title = track.optString("title", "$title - Hasil ${index + 1}").ifBlank { "$title - Hasil ${index + 1}" },
                        audioBytes = bytes,
                        imageUrl = track.optString("imageUrl").takeIf { it.startsWith("https://") },
                        durationSeconds = if (track.has("duration")) track.optDouble("duration").toFloat() else null
                    ))
                }
                if (downloaded.isEmpty()) throw IllegalStateException("Suno selesai tetapi audio tidak bisa diunduh.")
                activity.runOnUiThread {
                    generatedTracks = saveMusicHistory(context, downloaded)
                    selectedTrackIndex = 0
                    audioBytes = generatedTracks[0].audioBytes
                    title = downloaded[0].title
                    audioMimeType = "audio/mpeg"
                    status = "${downloaded.size} hasil lagu berhasil dibuat dan siap dipilih."
                    busy = false
                    page = AppPage.EDIT
                }
            } catch (e: Exception) {
                activity.runOnUiThread {
                    status = "Gagal: ${e.message ?: "Periksa koneksi dan server."}"
                    busy = false
                }
            }
        }.start()
    }


    fun playAudio() {
        val bytes = audioBytes ?: return
        try {
            val extension = if (audioMimeType.contains("wav")) "wav" else "mp3"
            val temp = java.io.File(context.cacheDir, "musicai-preview-$selectedTrackIndex.$extension")
            temp.writeBytes(bytes)
            player?.release()
            player = MediaPlayer().apply {
                setDataSource(temp.absolutePath)
                prepare()
                start()
                isPlaying = true
                playbackDuration = duration.coerceAtLeast(0)
                playbackPosition = 0
                setOnCompletionListener {
                    isPlaying = false
                    if (selectedTrackIndex < generatedTracks.lastIndex) activity.runOnUiThread { selectedTrackIndex += 1; audioBytes = generatedTracks[selectedTrackIndex].audioBytes; title = generatedTracks[selectedTrackIndex].title; playbackPosition = 0; playAudio() }
                    else playbackPosition = playbackDuration
                }
            }
            status = "Memutar: $title"
        } catch (e: Exception) {
            isPlaying = false
            status = "Gagal memutar: ${e.message}"
        }
    }


    fun selectTrack(index: Int, autoplay: Boolean = false) {
        if (index !in generatedTracks.indices) return
        selectedTrackIndex = index
        audioBytes = generatedTracks[index].audioBytes
        title = generatedTracks[index].title
        playbackPosition = 0
        player?.release()
        player = null
        isPlaying = false
        if (autoplay) playAudio()
    }

    fun togglePlayback() {
        val current = player
        if (current == null) playAudio()
        else if (isPlaying) { current.pause(); isPlaying = false; playbackPosition = current.currentPosition }
        else { current.start(); isPlaying = true }
    }

    fun seekTo(position: Int) {
        val target = position.coerceIn(0, playbackDuration.coerceAtLeast(0))
        player?.seekTo(target)
        playbackPosition = target
    }

    fun playNext() = selectTrack((selectedTrackIndex + 1).coerceAtMost(generatedTracks.lastIndex), true)
    fun playPrevious() = selectTrack((selectedTrackIndex - 1).coerceAtLeast(0), true)


    fun saveAudio() {
        val bytes = audioBytes ?: return
        try {
            val safeName = title.trim().ifBlank { "MusicAI" }.replace(Regex("[^a-zA-Z0-9 _-]"), "_")
            val values = ContentValues().apply {
                val extension = if (audioMimeType.contains("wav")) "wav" else "mp3"
                put(MediaStore.MediaColumns.DISPLAY_NAME, "$safeName.$extension")
                put(MediaStore.MediaColumns.MIME_TYPE, audioMimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Music/MusicAI")
            }
            val uri = context.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Tidak bisa membuat file.")
            context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                ?: throw IllegalStateException("Tidak bisa menulis file.")
            status = "MP3 tersimpan di Music/MusicAI."
        } catch (e: Exception) {
            status = "Gagal menyimpan: ${e.message}"
        }
    }

    LaunchedEffect(player, isPlaying) {
        while (isPlaying && player != null) {
            playbackPosition = runCatching { player?.currentPosition ?: 0 }.getOrDefault(0)
            delay(500)
        }
    }

    MaterialTheme(colorScheme = darkColorScheme(
        background = bg, surface = panel, primary = accent,
        onBackground = Color.White, onSurface = Color.White
    )) {
        if (showGenreDialog) {
            AlertDialog(
                onDismissRequest = { showGenreDialog = false },
                containerColor = Color(0xFF211A32),
                title = { Text("Pilih genre", color = Color.White, fontWeight = FontWeight.Bold) },
                text = {
                    Column(Modifier.fillMaxWidth().heightIn(max = 430.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Bisa pilih beberapa genre sekaligus.", color = Color(0xFFBFB5D2), fontSize = 12.sp)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            genres.forEach { item ->
                                FilterChip(selected = item in selectedGenres, onClick = { selectedGenres = if (item in selectedGenres) selectedGenres - item else selectedGenres + item }, label = { Text(item, fontSize = 12.sp) })
                            }
                        }
                        Divider(color = Color(0x55B69BFF))
                        OutlinedTextField(value = customGenre, onValueChange = { customGenre = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Genre sendiri") }, placeholder = { Text("Contoh: ska reggae punk Sunda") }, singleLine = true)
                    }
                },
                confirmButton = { TextButton(onClick = { showGenreDialog = false }) { Text("Selesai") } },
                dismissButton = { TextButton(onClick = { selectedGenres = emptySet(); customGenre = "" }) { Text("Reset") } }
            )
        }
        Scaffold(
            containerColor = bg,
            bottomBar = {
                NavigationBar(containerColor = panel) {
                    NavigationBarItem(
                        selected = page == AppPage.STUDIO,
                        onClick = { page = AppPage.STUDIO },
                        icon = { Icon(Icons.Default.MusicNote, contentDescription = null) },
                        label = { Text("Studio") }
                    )
                    NavigationBarItem(
                        selected = page == AppPage.EDIT,
                        onClick = { page = AppPage.EDIT },
                        icon = { Icon(Icons.Default.Edit, contentDescription = null) },
                        label = { Text("Edit") }
                    )
                    NavigationBarItem(
                        selected = page == AppPage.SETTINGS,
                        onClick = { page = AppPage.SETTINGS },
                        icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                        label = { Text("Pengaturan") }
                    )
                }
            }
        ) { innerPadding ->
            Column(
                Modifier.fillMaxSize()
                    .background(Brush.linearGradient(listOf(Color(0xFF090B16), Color(0xFF17112B), Color(0xFF0B1725))))
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(color = Color(0xFF8C68E8).copy(alpha = 0.22f), shape = RoundedCornerShape(16.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFBBA6FF).copy(alpha = 0.35f))) {
                        Icon(Icons.Default.MusicNote, null, Modifier.padding(12.dp), tint = Color.White)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("MusicAI", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                        Text("CREATE YOUR SOUND  •  AI MUSIC STUDIO", color = Color(0xFFBBA6D9), fontSize = 10.sp, letterSpacing = 1.2.sp)
                    }
                }

                when (page) {
                    AppPage.STUDIO -> {
                        Text("Studio kreatif", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                        Text("Wujudkan ide menjadi musik.", color = Color(0xFFB8B2C8), fontSize = 13.sp)
                        OutlinedTextField(
                            value = title, onValueChange = { title = it },
                            label = { Text("Judul lagu (opsional)") }, modifier = Modifier.fillMaxWidth(), singleLine = true
                        )

                        Surface(color = Color(0x442B2144), shape = RoundedCornerShape(26.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x66D0B8FF)), modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("IDE & LIRIK", color = Color(0xFFD6C7FF), fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 1.5.sp)
                            OutlinedTextField(
                                value = lyrics, onValueChange = { lyrics = it },
                                label = { Text("Lirik atau ide lagu") },
                                modifier = Modifier.fillMaxWidth().height(240.dp),
                                colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = Color(0x33201B31), unfocusedContainerColor = Color(0x22201B31), focusedBorderColor = Color(0xFFB69BFF), unfocusedBorderColor = Color(0x665F5477)),
                                placeholder = { Text("Tulis lirik lengkap atau cukup satu ide...\n\nContoh: Buatkan lagu tema mistis tentang desa tua di pegunungan.\n\n") }
                            )
                            }
                        }

                        Surface(color = Color(0x442D2544), shape = RoundedCornerShape(22.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x66C4ADFF)), modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text("GENRE MUSIK", color = Color(0xFFD5C7FF), fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 1.2.sp)
                                        Text(if (selectedGenres.isEmpty() && customGenre.isBlank()) "Pilih satu atau beberapa genre" else (selectedGenres + listOf(customGenre.trim()).filter { it.isNotBlank() }).joinToString("  •  "), color = Color.White, fontSize = 13.sp, maxLines = 2)
                                    }
                                    TextButton(onClick = { showGenreDialog = true }) { Text("Ubah") }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                                    (selectedGenres.toList().take(2) + listOf(customGenre.trim()).filter { it.isNotBlank() }.take(1)).forEach { selected ->
                                        Surface(color = Color(0xFF8B6CE0).copy(alpha = 0.28f), shape = RoundedCornerShape(50), border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x88C6B4FF))) { Text(selected, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp), color = Color(0xFFE5DBFF), fontSize = 11.sp) }
                                    }
                                    TextButton(onClick = { showGenreDialog = true }) { Text("+ Tambah") }
                                }
                            }
                        }

                        Text("Vokal", fontWeight = FontWeight.SemiBold)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("male" to "Pria", "female" to "Wanita", "instrumental" to "Instrumen").forEach { (value, label) ->
                                FilterChip(selected = vocal == value, onClick = { vocal = value }, label = { Text(label) })
                            }
                        }
                        Surface(color = panel, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                            Text(
                                "Suno akan membuat lagu dengan vokal sesuai pilihan pria atau wanita dan lirik yang kamu masukkan.",
                                color = Color.LightGray,
                                fontSize = 13.sp,
                                modifier = Modifier.padding(12.dp)
                            )
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Tempo", fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.weight(1f))
                            Text("${bpm.toInt()} BPM", color = accent, fontWeight = FontWeight.Bold)
                        }
                        Slider(value = bpm, onValueChange = { bpm = it }, valueRange = 50f..200f)

                        Text("Durasi musik", fontWeight = FontWeight.SemiBold)
                        Text("Maksimal 6 menit sesuai batas durasi AI.", color = Color.LightGray, fontSize = 11.sp)
                        var durationExpanded by remember { mutableStateOf(false) }
                        Box {
                            OutlinedButton(onClick = { durationExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                                Text("${durationSeconds / 60} menit ${durationSeconds % 60} detik", modifier = Modifier.weight(1f))
                                Text("⌄")
                            }
                            DropdownMenu(expanded = durationExpanded, onDismissRequest = { durationExpanded = false }) {
                                listOf(60, 120, 180, 240, 300, 360).forEach { seconds ->
                                    DropdownMenuItem(
                                        text = { Text(if (seconds % 60 == 0) "${seconds / 60} menit" else "${seconds} detik") },
                                        onClick = { durationSeconds = seconds; durationExpanded = false }
                                    )
                                }
                            }
                        }

                        Text("Suasana / mood • pilih beberapa", fontWeight = FontWeight.SemiBold)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            moodOptions.forEach { option ->
                                FilterChip(selected = option in selectedMoods, onClick = { selectedMoods = if (option in selectedMoods) selectedMoods - option else selectedMoods + option }, label = { Text(option) })
                            }
                        }
                        OutlinedTextField(value = customMood, onValueChange = { customMood = it }, label = { Text("Arahan manual (opsional)") }, placeholder = { Text("Contoh: slap bass dominan, brass ceria, vokal serak...") }, modifier = Modifier.fillMaxWidth())

                        Button(
                            onClick = { generateSong() },
                            enabled = !busy && lyrics.isNotBlank(),
                            modifier = Modifier.fillMaxWidth().height(54.dp),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            if (busy) CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                            else Text("CREATE YOUR MUSIC", fontWeight = FontWeight.Bold)
                        }
                    }

                    AppPage.EDIT -> {
                        Text("Edit lagu", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        OutlinedButton(
                            onClick = { importAudioLauncher.launch(arrayOf("audio/*")) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Download, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Impor lagu dari Google Flow")
                        }
                        if (importedFileName.isNotBlank()) {
                            Text("File: $importedFileName", color = Color.LightGray, fontSize = 12.sp)
                        }
                        Text(
                            "Impor lagu dari Google Flow, putar, simpan, atau edit detail proyek musikmu.",
                            color = Color.LightGray, fontSize = 13.sp
                        )
                        OutlinedTextField(
                            value = title, onValueChange = { title = it },
                            label = { Text("Judul lagu") }, modifier = Modifier.fillMaxWidth(), singleLine = true
                        )
                        OutlinedTextField(
                            value = lyrics, onValueChange = { lyrics = it },
                            label = { Text("Edit lirik") }, modifier = Modifier.fillMaxWidth().height(260.dp)
                        )
                        Text("Genre: ${(selectedGenres + listOf(customGenre.trim()).filter { it.isNotBlank() }).joinToString(", ")} • Vokal: ${when(vocal) { "female" -> "Wanita"; "instrumental" -> "Instrumen"; else -> "Pria" }}")
                        Text("Tempo: ${bpm.toInt()} BPM")
                        Button(
                            onClick = { generateSong() },
                            enabled = !busy && lyrics.isNotBlank(),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(if (busy) "Membuat ulang..." else "Buat ulang dengan perubahan")
                        }

                        Text("Riwayat & hasil lagu", fontWeight = FontWeight.Bold)
                        if (generatedTracks.isEmpty()) Text("Belum ada riwayat lagu. Hasil generasi akan tersimpan di perangkat ini.", color = Color.LightGray, fontSize = 12.sp)
                        Text(status, color = Color.LightGray, fontSize = 13.sp)
                        generatedTracks.forEachIndexed { index, track ->
                            Surface(
                                color = if (selectedTrackIndex == index) Color(0xFF30264A) else panel,
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier.fillMaxWidth(),
                                onClick = { selectTrack(index, true) }
                            ) {
                                Box(Modifier.fillMaxWidth()) {
                                    if (selectedTrackIndex == index && track.imageUrl != null) {
                                        AsyncImage(model = track.imageUrl, contentDescription = null, modifier = Modifier.matchParentSize().blur(22.dp), contentScale = ContentScale.Crop)
                                        Box(Modifier.matchParentSize().background(Color(0xAA171321)))
                                    }
                                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    if (track.imageUrl != null) {
                                        AsyncImage(model = track.imageUrl, contentDescription = "Cover lagu", modifier = Modifier.size(68.dp), contentScale = ContentScale.Crop)
                                    } else {
                                        Surface(color = Color(0xFF39324D), shape = RoundedCornerShape(12.dp), modifier = Modifier.size(68.dp)) {
                                            Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.MusicNote, null, tint = accent, modifier = Modifier.size(32.dp)) }
                                        }
                                    }
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text("HASIL ${index + 1}", color = accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        Text(track.title, fontWeight = FontWeight.SemiBold, maxLines = 2)
                                        Text(track.durationSeconds?.let { formatTime((it * 1000).toInt()) } ?: "Suno track", color = Color.LightGray, fontSize = 11.sp)
                                    }
                                    if (selectedTrackIndex == index) Icon(Icons.Default.MusicNote, null, tint = accent)
                                    else TextButton(onClick = { selectTrack(index, true) }) { Text("Putar") }
                                    }
                                }
                            }
                        }
                        Surface(color = panel, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("Pemutar musik", fontWeight = FontWeight.Bold)
                                Text(title, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                Slider(value = playbackPosition.toFloat().coerceIn(0f, playbackDuration.coerceAtLeast(1).toFloat()), onValueChange = { seekTo(it.toInt()) }, valueRange = 0f..playbackDuration.coerceAtLeast(1).toFloat(), enabled = playbackDuration > 0)
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(formatTime(playbackPosition), color = Color.LightGray, fontSize = 11.sp)
                                    Text(formatTime(playbackDuration), color = Color.LightGray, fontSize = 11.sp)
                                }
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(onClick = { playPrevious() }, enabled = selectedTrackIndex > 0) { Icon(Icons.Default.SkipPrevious, "Lagu sebelumnya") }
                                    IconButton(onClick = { togglePlayback() }, enabled = audioBytes != null) { Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, if (isPlaying) "Pause" else "Play", Modifier.size(38.dp)) }
                                    IconButton(onClick = { playNext() }, enabled = selectedTrackIndex < generatedTracks.lastIndex) { Icon(Icons.Default.SkipNext, "Lagu selanjutnya") }
                                }
                                Button(onClick = { saveAudio() }, enabled = audioBytes != null, modifier = Modifier.fillMaxWidth()) {
                                    Icon(Icons.Default.Download, null)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Unduh MP3")
                                }
                            }
                        }
                    }

                    AppPage.SETTINGS -> {
                        Text("Pengaturan", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        Surface(color = panel, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("Koneksi server", fontWeight = FontWeight.Bold)
                                Text(
                                    "Masukkan URL HTTPS backend yang sudah online. API key Apiframe disimpan sebagai environment variable di server, bukan di aplikasi.",
                                    color = Color.LightGray, fontSize = 12.sp
                                )
                                OutlinedTextField(
                                    value = backendUrl,
                                    onValueChange = { value ->
                                        backendUrl = value.trim()
                                        context.getSharedPreferences("musicai_settings", android.content.Context.MODE_PRIVATE)
                                            .edit().putString("backend_url", value.trim()).apply()
                                        backendReachable = null
                                    },
                                    label = { Text("URL backend HTTPS") },
                                    placeholder = { Text("https://nama-server.onrender.com") },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true
                                )
                                OutlinedButton(
                                    onClick = {
                                        Thread {
                                            try {
                                                val request = Request.Builder().url("${backendUrl.trim().trimEnd('/')}/health").get().build()
                                                OkHttpClient().newCall(request).execute().use { response ->
                                                    val ok = response.isSuccessful
                                                    activity.runOnUiThread {
                                                        backendReachable = ok
                                                        status = if (ok) "Server aktif." else "Server merespons dengan HTTP ${response.code}."
                                                    }
                                                }
                                            } catch (e: Exception) {
                                                activity.runOnUiThread {
                                                    backendReachable = false
                                                    status = "Server tidak terjangkau: ${e.message}"
                                                }
                                            }
                                        }.start()
                                    },
                                    enabled = backendUrl.startsWith("https://")
                                ) { Text("Tes koneksi server") }
                                backendReachable?.let { reachable ->
                                    Text(
                                        if (reachable) "Server dapat dihubungi." else "Koneksi server gagal.",
                                        color = if (reachable) Color(0xFF81C784) else Color(0xFFEF9A9A)
                                    )
                                }
                            }
                        }
                        Surface(color = panel, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("Tentang MusicAI", fontWeight = FontWeight.Bold)
                                Text("Versi 0.5.0 • Android native", color = Color.LightGray, fontSize = 12.sp)
                                Text("MusicAI adalah studio musik berbasis AI untuk membuat lagu dari ide atau lirik, memilih genre, mood, tempo, dan vokal pria atau wanita. Hasil dapat diputar, diedit, diunduh, dan tersimpan di riwayat perangkat.", color = Color.LightGray, fontSize = 13.sp)
                                Text("Dikembangkan oleh ryuzee_project", color = Color(0xFFD6C7FF), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }

                if (page != AppPage.EDIT) {
                    Text(status, color = Color.LightGray, fontSize = 12.sp)
                    if (audioBytes != null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(onClick = { page = AppPage.EDIT }, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Default.Edit, null)
                                Text("Edit hasil")
                            }
                            Button(onClick = { saveAudio() }, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Default.Download, null)
                                Text("Unduh MP3")
                            }
                        }
                    }
                }
                Text("MusicAI • Flow Music companion", color = Color.Gray, fontSize = 11.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
            }
        }
    }
}
