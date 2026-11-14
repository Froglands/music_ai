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
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
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

private const val BACKEND_URL = "https://YOUR-BACKEND.example"

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
    var title by remember { mutableStateOf("Lagu Baru") }
    var lyrics by remember { mutableStateOf("[Verse 1]\\nTulis lirikmu di sini\\n\\n[Chorus]\\nIsi bagian reff di sini") }
    var genre by remember { mutableStateOf("Dangdut Koplo") }
    var vocal by remember { mutableStateOf("female") }
    var bpm by remember { mutableFloatStateOf(110f) }
    var mood by remember { mutableStateOf("Enerjik dan emosional") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Siap membuat lagu.") }
    var audioBytes by remember { mutableStateOf<ByteArray?>(null) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }

    val genres = listOf("Dangdut Koplo", "Pop", "Rock", "Rap", "Jazz", "Reggae", "EDM", "Akustik")

    MaterialTheme(colorScheme = darkColorScheme(
        background = bg, surface = panel, primary = accent,
        onBackground = Color.White, onSurface = Color.White
    )) {
        Column(
            Modifier.fillMaxSize().background(bg).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = accent, shape = RoundedCornerShape(14.dp)) {
                    Icon(Icons.Default.MusicNote, null, Modifier.padding(12.dp), tint = Color.White)
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("NadaAI", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    Text("AI Music Studio", color = Color.LightGray, fontSize = 12.sp)
                }
            }

            Text("Buat lagu impianmu", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            OutlinedTextField(title, { title = it }, label = { Text("Judul lagu") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(
                lyrics, { lyrics = it }, label = { Text("Lirik lagu") },
                modifier = Modifier.fillMaxWidth().height(220.dp),
                placeholder = { Text("[Verse 1]\\nTulis lirik di sini...") }
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
                        DropdownMenuItem(text = { Text(item) }, onClick = { genre = item; genreExpanded = false })
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
            Slider(value = bpm, onValueChange = { bpm = it }, valueRange = 60f..180f)

            OutlinedTextField(mood, { mood = it }, label = { Text("Suasana / arahan musik") }, modifier = Modifier.fillMaxWidth())

            Button(
                onClick = {
                    if (busy) return@Button
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
                                .url("$BACKEND_URL/api/generate")
                                .post(json.toString().toRequestBody("application/json".toMediaType()))
                                .build()
                            client.newCall(request).execute().use { response ->
                                if (!response.isSuccessful) {
                                    val message = response.body?.string() ?: "Server error ${response.code}"
                                    throw IllegalStateException(message)
                                }
                                val bytes = response.body?.bytes() ?: throw IllegalStateException("Audio kosong.")
                                activity.runOnUiThread {
                                    audioBytes = bytes
                                    status = "Lagu berhasil dibuat. ${bytes.size / 1024} KB"
                                    busy = false
                                }
                            }
                        } catch (e: Exception) {
                            activity.runOnUiThread {
                                status = "Gagal: ${e.message ?: "Periksa koneksi dan server."}"
                                busy = false
                            }
                        }
                    }.start()
                },
                enabled = !busy && lyrics.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(54.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                else Text("BUAT LAGU SEKARANG", fontWeight = FontWeight.Bold)
            }

            Surface(color = panel, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Studio Output", fontWeight = FontWeight.Bold)
                    Text(status, color = Color.LightGray, fontSize = 13.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(
                            onClick = {
                                audioBytes?.let { bytes ->
                                    try {
                                        val temp = java.io.File(context.cacheDir, "nadaai-preview.mp3")
                                        temp.writeBytes(bytes)
                                        player?.release()
                                        player = MediaPlayer().apply {
                                            setDataSource(temp.absolutePath)
                                            prepare()
                                            start()
                                        }
                                        status = "Memutar lagu..."
                                    } catch (e: Exception) { status = "Gagal memutar: ${e.message}" }
                                }
                            },
                            enabled = audioBytes != null,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.PlayArrow, null)
                            Text("Putar")
                        }
                        Button(
                            onClick = {
                                audioBytes?.let { bytes ->
                                    try {
                                        val values = ContentValues().apply {
                                            put(MediaStore.MediaColumns.DISPLAY_NAME, "${title.ifBlank { "NadaAI" }}.mp3")
                                            put(MediaStore.MediaColumns.MIME_TYPE, "audio/mpeg")
                                            put(MediaStore.MediaColumns.RELATIVE_PATH, "Music/NadaAI")
                                        }
                                        val uri = context.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                                            ?: throw IllegalStateException("Tidak bisa membuat file.")
                                        context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                                        status = "MP3 tersimpan di Music/NadaAI."
                                    } catch (e: Exception) { status = "Gagal menyimpan: ${e.message}" }
                                }
                            },
                            enabled = audioBytes != null,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Download, null)
                            Text("Unduh MP3")
                        }
                    }
                }
            }
            Text("NadaAI • Powered by ElevenLabs", color = Color.Gray, fontSize = 11.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
        }
    }
}
