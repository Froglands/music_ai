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

private data class MusicTrack(val title: String, val audioBytes: ByteArray, val imageUrl: String? = null, val durationSeconds: Float? = null)

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

@Composable
fun MusicAiScreen() {
    val context = LocalContext.current
    val activity = context as ComponentActivity
    val bg = Color(0xFF101018)
    val panel = Color(0xFF1D1D2A)
    val accent = Color(0xFF9B7BFF)

    var page by remember { mutableStateOf(AppPage.STUDIO) }
    var title by remember { mutableStateOf("Lagu Baru") }
    var lyrics by remember {
        mutableStateOf("[Verse 1]\nTulis lirikmu di sini\n\n[Chorus]\nIsi bagian reff di sini")
    }
    var genre by remember { mutableStateOf("Dangdut Koplo") }
    var vocal by remember { mutableStateOf("female") }
    var bpm by remember { mutableFloatStateOf(110f) }
    var durationSeconds by remember { mutableIntStateOf(120) }
    var mood by remember { mutableStateOf("Enerjik dan emosional") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Siap membuat lagu.") }
    var audioBytes by remember { mutableStateOf<ByteArray?>(null) }
    var audioMimeType by remember { mutableStateOf("audio/mpeg") }
    var generatedTracks by remember { mutableStateOf(listOf<MusicTrack>()) }
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

    val genres = listOf("Dangdut Koplo", "Dangdut", "Pop", "Rock", "Ska", "Rap", "Jazz", "Reggae", "EDM", "Akustik")

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
                    put("title", title)
                    put("lyrics", lyrics)
                    put("genre", genre)
                    put("vocal", vocal)
                    put("bpm", bpm.toInt())
                    put("duration", durationSeconds)
                    put("mood", mood)
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
                        if (lastStatus == "FAILED") throw IllegalStateException(job.optString("error", "Generasi lagu gagal."))
                        if (lastStatus == "COMPLETED") completedTracks = job.optJSONArray("tracks")
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
                    generatedTracks = downloaded
                    selectedTrackIndex = 0
                    audioBytes = downloaded[0].audioBytes
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
                    if (selectedTrackIndex < generatedTracks.lastIndex) activity.runOnUiThread { selectTrack((selectedTrackIndex + 1).coerceAtMost(generatedTracks.lastIndex), true) }
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
                    .background(bg)
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(color = accent, shape = RoundedCornerShape(14.dp)) {
                        Icon(Icons.Default.MusicNote, null, Modifier.padding(12.dp), tint = Color.White)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("MusicAI", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                        Text("AI Music Studio", color = Color.LightGray, fontSize = 12.sp)
                    }
                }

                when (page) {
                    AppPage.STUDIO -> {
                        Text("Buat lagu impianmu", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        OutlinedTextField(
                            value = title, onValueChange = { title = it },
                            label = { Text("Judul lagu") }, modifier = Modifier.fillMaxWidth(), singleLine = true
                        )
                        OutlinedTextField(
                            value = lyrics, onValueChange = { lyrics = it },
                            label = { Text("Lirik lagu") },
                            modifier = Modifier.fillMaxWidth().height(240.dp),
                            placeholder = { Text("[Verse 1]\nTulis lirik di sini...") }
                        )

                        Text("Genre musik", fontWeight = FontWeight.SemiBold)
                        var genreExpanded by remember { mutableStateOf(false) }
                        Box {
                            OutlinedButton(onClick = { genreExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                                Text(genre, modifier = Modifier.weight(1f))
                                Text("⌄")
                            }
                            DropdownMenu(expanded = genreExpanded, onDismissRequest = { genreExpanded = false }) {
                                genres.forEach { item ->
                                    DropdownMenuItem(
                                        text = { Text(item) },
                                        onClick = { genre = item; genreExpanded = false }
                                    )
                                }
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
                        var durationExpanded by remember { mutableStateOf(false) }
                        Box {
                            OutlinedButton(onClick = { durationExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                                Text("${durationSeconds / 60} menit ${durationSeconds % 60} detik", modifier = Modifier.weight(1f))
                                Text("⌄")
                            }
                            DropdownMenu(expanded = durationExpanded, onDismissRequest = { durationExpanded = false }) {
                                listOf(30, 60, 120, 180, 240, 300, 380).forEach { seconds ->
                                    DropdownMenuItem(
                                        text = { Text(if (seconds % 60 == 0) "${seconds / 60} menit" else "${seconds} detik") },
                                        onClick = { durationSeconds = seconds; durationExpanded = false }
                                    )
                                }
                            }
                        }

                        OutlinedTextField(
                            value = mood, onValueChange = { mood = it },
                            label = { Text("Suasana / arahan musik") }, modifier = Modifier.fillMaxWidth()
                        )

                        Button(
                            onClick = { generateSong() },
                            enabled = !busy && lyrics.isNotBlank(),
                            modifier = Modifier.fillMaxWidth().height(54.dp),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            if (busy) CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                            else Text("BUAT LAGU SEKARANG", fontWeight = FontWeight.Bold)
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
                        Text("Genre: $genre • Vokal: ${if (vocal == "female") "Wanita" else "Pria"}")
                        Text("Tempo: ${bpm.toInt()} BPM")
                        Button(
                            onClick = { generateSong() },
                            enabled = !busy && lyrics.isNotBlank(),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(if (busy) "Membuat ulang..." else "Buat ulang dengan perubahan")
                        }

                        Text("Dua hasil generasi", fontWeight = FontWeight.Bold)
                        Text(status, color = Color.LightGray, fontSize = 13.sp)
                        generatedTracks.forEachIndexed { index, track ->
                            Surface(
                                color = if (selectedTrackIndex == index) Color(0xFF30264A) else panel,
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier.fillMaxWidth(),
                                onClick = { selectTrack(index, false) }
                            ) {
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
                                    else TextButton(onClick = { selectTrack(index, false) }) { Text("Pilih") }
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
                                Text("Versi 0.4.0 • Android native • Flow Music import", color = Color.LightGray, fontSize = 12.sp)
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
