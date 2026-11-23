package com.nadaai.app

import android.content.ContentValues
import android.media.MediaPlayer
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
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

// Replace this with the deployed HTTPS backend URL before using song generation.
private const val BACKEND_URL = "https://YOUR-BACKEND.example"

private enum class AppPage { STUDIO, EDIT, SETTINGS }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { NadaAiScreen() }
    }
}

@Composable
fun NadaAiScreen() {
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
    var mood by remember { mutableStateOf("Enerjik dan emosional") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Siap membuat lagu.") }
    var audioBytes by remember { mutableStateOf<ByteArray?>(null) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var backendReachable by remember { mutableStateOf<Boolean?>(null) }

    val genres = listOf("Dangdut Koplo", "Dangdut", "Pop", "Rock", "Ska", "Rap", "Jazz", "Reggae", "EDM", "Akustik")

    fun generateSong() {
        if (busy) return
        if (BACKEND_URL.contains("YOUR-BACKEND")) {
            status = "Backend belum diatur. Tambahkan URL server HTTPS di MainActivity.kt."
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
                    put("mood", mood)
                }
                val client = OkHttpClient.Builder()
                    .callTimeout(10, TimeUnit.MINUTES)
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(10, TimeUnit.MINUTES)
                    .build()
                val request = Request.Builder()
                    .url("${BACKEND_URL.trimEnd('/')}/api/generate")
                    .post(json.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        val message = response.body?.string() ?: "Server error ${response.code}"
                        throw IllegalStateException(message)
                    }
                    val bytes = response.body?.bytes() ?: throw IllegalStateException("Audio kosong.")
                    if (bytes.isEmpty()) throw IllegalStateException("Server mengembalikan audio kosong.")
                    activity.runOnUiThread {
                        audioBytes = bytes
                        status = "Lagu berhasil dibuat • ${bytes.size / 1024} KB"
                        busy = false
                        page = AppPage.EDIT
                    }
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
            val temp = java.io.File(context.cacheDir, "musicai-preview.mp3")
            temp.writeBytes(bytes)
            player?.release()
            player = MediaPlayer().apply {
                setDataSource(temp.absolutePath)
                prepare()
                start()
                setOnCompletionListener { it.release(); player = null }
            }
            status = "Memutar lagu..."
        } catch (e: Exception) {
            status = "Gagal memutar: ${e.message}"
        }
    }

    fun saveAudio() {
        val bytes = audioBytes ?: return
        try {
            val safeName = title.trim().ifBlank { "MusicAI" }.replace(Regex("[^a-zA-Z0-9 _-]"), "_")
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "$safeName.mp3")
                put(MediaStore.MediaColumns.MIME_TYPE, "audio/mpeg")
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

                        Text("Karakter vokal", fontWeight = FontWeight.SemiBold)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            FilterChip(selected = vocal == "male", onClick = { vocal = "male" }, label = { Text("Pria") })
                            FilterChip(selected = vocal == "female", onClick = { vocal = "female" }, label = { Text("Wanita") })
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Tempo", fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.weight(1f))
                            Text("${bpm.toInt()} BPM", color = accent, fontWeight = FontWeight.Bold)
                        }
                        Slider(value = bpm, onValueChange = { bpm = it }, valueRange = 50f..200f)

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
                        Text(
                            "Ubah judul, lirik, genre, vokal, atau tempo. Jika ingin mendengar versi perubahan, buat ulang lagu.",
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

                        Surface(color = panel, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("Hasil lagu", fontWeight = FontWeight.Bold)
                                Text(status, color = Color.LightGray, fontSize = 13.sp)
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    OutlinedButton(
                                        onClick = { playAudio() },
                                        enabled = audioBytes != null,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(Icons.Default.PlayArrow, null)
                                        Text("Putar")
                                    }
                                    Button(
                                        onClick = { saveAudio() },
                                        enabled = audioBytes != null,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(Icons.Default.Download, null)
                                        Text("Unduh MP3")
                                    }
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
                                    if (BACKEND_URL.contains("YOUR-BACKEND")) "Belum dikonfigurasi" else BACKEND_URL,
                                    color = if (BACKEND_URL.contains("YOUR-BACKEND")) Color(0xFFFFB74D) else Color.LightGray
                                )
                                Text(
                                    "URL backend diatur saat build aplikasi. API key ElevenLabs harus disimpan sebagai environment variable di server, bukan di aplikasi.",
                                    color = Color.LightGray, fontSize = 12.sp
                                )
                                OutlinedButton(
                                    onClick = {
                                        Thread {
                                            try {
                                                val request = Request.Builder().url("${BACKEND_URL.trimEnd('/')}/health").get().build()
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
                                    enabled = !BACKEND_URL.contains("YOUR-BACKEND")
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
                                Text("Versi 0.2.0 • Android native • Powered by ElevenLabs", color = Color.LightGray, fontSize = 12.sp)
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
                Text("MusicAI • Powered by ElevenLabs", color = Color.Gray, fontSize = 11.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
            }
        }
    }
}
