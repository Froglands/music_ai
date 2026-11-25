package com.ryuzeeproject.music_ai

import android.content.ContentValues
import android.media.MediaPlayer
import android.media.MediaMetadataRetriever
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
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
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

// Backend URL is configured and saved from the Settings screen.

private enum class AppPage { STUDIO, LIBRARY, PLAYER, SETTINGS }

private data class MusicTrack(val title: String, val audioBytes: ByteArray, val imageUrl: String? = null, val durationSeconds: Float? = null, val fileName: String? = null, val coverFileName: String? = null, val artist: String? = null, val isAiGenerated: Boolean = false)

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
                    if (item.has("duration")) item.optDouble("duration").toFloat() else null, fileName,
                    item.optString("coverFileName").takeIf { it.isNotBlank() },
                    item.optString("artist").takeIf { it.isNotBlank() }, item.optBoolean("isAiGenerated", false)))
            }
        }
    }
}

private fun saveMusicHistory(context: android.content.Context, tracks: List<MusicTrack>): List<MusicTrack> {
    val folder = java.io.File(context.filesDir, "music_history").apply { mkdirs() }
    val saved = tracks.mapIndexed { index, track ->
        val name = track.fileName ?: "track_${System.currentTimeMillis()}_${index}.mp3"
        java.io.File(folder, name).writeBytes(track.audioBytes)
        val coverName = track.coverFileName ?: if (track.imageUrl == null && track.coverFileName == null) null else track.coverFileName
        track.copy(fileName = name, coverFileName = coverName)
    }
    val records = org.json.JSONArray()
    saved.forEach { track ->
        records.put(JSONObject().apply {
            put("title", track.title)
            put("fileName", track.fileName)
            put("imageUrl", track.imageUrl ?: "")
            put("coverFileName", track.coverFileName ?: "")
            put("artist", track.artist ?: "")
            put("isAiGenerated", track.isAiGenerated)
            track.durationSeconds?.let { put("duration", it.toDouble()) }
        })
    }
    context.getSharedPreferences("musicai_history", android.content.Context.MODE_PRIVATE)
        .edit().putString("tracks", records.toString()).apply()
    return saved
}


private fun apiKeySecret(context: android.content.Context): javax.crypto.SecretKey {
    val store = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    val alias = "MusicAI.ApiKey"
    (store.getKey(alias, null) as? javax.crypto.SecretKey)?.let { return it }
    val generator = javax.crypto.KeyGenerator.getInstance("AES", "AndroidKeyStore")
    generator.init(android.security.keystore.KeyGenParameterSpec.Builder(
        alias,
        android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT
    ).setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
        .setRandomizedEncryptionRequired(true).build())
    return generator.generateKey()
}

private fun saveDeveloperApiKey(context: android.content.Context, value: String, provider: String = "musicapi") {
    val prefs = context.getSharedPreferences("musicai_settings", android.content.Context.MODE_PRIVATE)
    val prefKey = when (provider) { "lyria" -> "lyria_api_key_encrypted"; "stability" -> "stability_api_key_encrypted"; else -> "provider_api_key_encrypted" }
    if (value.isBlank()) {
        prefs.edit().remove(prefKey).apply()
        return
    }
    val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, apiKeySecret(context))
    val encrypted = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
    prefs.edit().putString(prefKey, android.util.Base64.encodeToString(encrypted, android.util.Base64.NO_WRAP)).apply()
}

private fun loadDeveloperApiKey(context: android.content.Context, provider: String = "musicapi"): String {
    return runCatching {
        val prefKey = if (provider == "lyria") "lyria_api_key_encrypted" else "provider_api_key_encrypted"
        val encoded = context.getSharedPreferences("musicai_settings", android.content.Context.MODE_PRIVATE)
            .getString(prefKey, null) ?: return ""
        val bytes = android.util.Base64.decode(encoded, android.util.Base64.NO_WRAP)
        val iv = bytes.copyOfRange(0, 12)
        val payload = bytes.copyOfRange(12, bytes.size)
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(javax.crypto.Cipher.DECRYPT_MODE, apiKeySecret(context), javax.crypto.spec.GCMParameterSpec(128, iv))
        String(cipher.doFinal(payload), Charsets.UTF_8)
    }.getOrDefault("")
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
    var title by remember { mutableStateOf(context.getSharedPreferences("musicai_draft", android.content.Context.MODE_PRIVATE).getString("title", "") ?: "") }
    var lyrics by remember { mutableStateOf(context.getSharedPreferences("musicai_draft", android.content.Context.MODE_PRIVATE).getString("lyrics", "") ?: "") }
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
    var trackToDelete by remember { mutableIntStateOf(-1) }
    var isPlaying by remember { mutableStateOf(false) }
    var playbackPosition by remember { mutableIntStateOf(0) }
    var playbackDuration by remember { mutableIntStateOf(0) }
    var importedFileName by remember { mutableStateOf("") }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var backendReachable by remember { mutableStateOf<Boolean?>(null) }
    var developerCode by remember { mutableStateOf("") }
    var developerUnlocked by remember { mutableStateOf(false) }
    var developerVerifying by remember { mutableStateOf(false) }
    var developerMessage by remember { mutableStateOf("") }
    var developerApiKey by remember { mutableStateOf(loadDeveloperApiKey(context)) }
    var developerLyriaApiKey by remember { mutableStateOf(loadDeveloperApiKey(context, "lyria")) }
    var developerStabilityApiKey by remember { mutableStateOf(loadDeveloperApiKey(context, "stability")) }
    var selectedProvider by remember { mutableStateOf(context.getSharedPreferences("musicai_settings", android.content.Context.MODE_PRIVATE).getString("provider", "musicapi") ?: "musicapi") }
    var providerMenuExpanded by remember { mutableStateOf(false) }
    var coverMode by remember { mutableStateOf(false) }
    var coverSourceBytes by remember { mutableStateOf<ByteArray?>(null) }
    var coverSourceName by remember { mutableStateOf("") }
    var coverSourceMime by remember { mutableStateOf("audio/mpeg") }
    var coverSourceDuration by remember { mutableFloatStateOf(0f) }
    var coverAudioWeight by remember { mutableFloatStateOf(0.6f) }
    var showLyriaCostConfirm by remember { mutableStateOf(false) }
    var apiKeyVisible by remember { mutableStateOf(false) }
    var backendUrl by remember {
        val prefs = context.getSharedPreferences("musicai_settings", android.content.Context.MODE_PRIVATE)
        val savedUrl = prefs.getString("backend_url", null)
        val defaultUrl = "https://music-ai-git-main-valve7.vercel.app"
        val resolvedUrl = if (savedUrl.isNullOrBlank() || savedUrl == "https://music-ai-valve7.vercel.app") defaultUrl else savedUrl
        if (resolvedUrl != savedUrl) prefs.edit().putString("backend_url", resolvedUrl).apply()
        mutableStateOf(resolvedUrl)
    }

    LaunchedEffect(title, lyrics) {
        context.getSharedPreferences("musicai_draft", android.content.Context.MODE_PRIVATE)
            .edit().putString("title", title).putString("lyrics", lyrics).apply()
    }

    val coverAudioLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw IllegalStateException("File audio tidak bisa dibaca.")
                if (bytes.isEmpty()) throw IllegalStateException("File audio kosong.")
                if (bytes.size > 4 * 1024 * 1024) throw IllegalStateException("Ukuran file maksimal 4 MB untuk upload cover.")
                val mime = context.contentResolver.getType(uri) ?: "audio/mpeg"
                if (!mime.startsWith("audio/")) throw IllegalStateException("Pilih file audio.")
                val retriever = MediaMetadataRetriever()
                val duration = try { retriever.setDataSource(context, uri); (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toFloatOrNull() ?: 0f) / 1000f } finally { runCatching { retriever.release() } }
                if (duration < 6f || duration > 480f) throw IllegalStateException("Durasi audio harus 6 detik sampai 8 menit.")
                coverSourceBytes = bytes
                coverSourceMime = when { mime.contains("wav") -> "audio/wav"; mime.contains("mp4") || mime.contains("m4a") -> "audio/mp4"; mime.contains("aac") -> "audio/aac"; mime.contains("flac") -> "audio/flac"; else -> "audio/mpeg" }
                coverSourceName = uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':') ?: "Audio cover"
                coverSourceDuration = duration
                if (title.isBlank()) title = coverSourceName.substringBeforeLast('.', coverSourceName)
                status = "Audio sumber siap untuk dibuat cover."
            } catch (e: Exception) { status = "Gagal memilih audio: ${e.message}" }
        }
    }
    val importAudioLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw IllegalStateException("File tidak bisa dibaca.")
                if (bytes.isEmpty()) throw IllegalStateException("File audio kosong.")
                val mime = context.contentResolver.getType(uri) ?: "audio/mpeg"
                val name = uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':') ?: "Flow Music"
                var importedTitle = name.substringBeforeLast('.', name).ifBlank { "Lagu lokal" }
                var importedArtist: String? = null
                var importedDuration: Float? = null
                var coverName: String? = null
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context, uri)
                    importedTitle = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() } ?: importedTitle
                    importedArtist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                        ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
                    importedDuration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toFloatOrNull()?.div(1000f)
                    val picture = retriever.embeddedPicture
                    if (picture != null && picture.isNotEmpty()) {
                        val coverDir = java.io.File(context.filesDir, "music_covers").apply { mkdirs() }
                        coverName = "cover_${System.currentTimeMillis()}.jpg"
                        java.io.File(coverDir, coverName!!).writeBytes(picture)
                    }
                } finally {
                    runCatching { retriever.release() }
                }
                val importedTrack = MusicTrack(importedTitle, bytes, durationSeconds = importedDuration, coverFileName = coverName, artist = importedArtist)
                generatedTracks = saveMusicHistory(context, generatedTracks + importedTrack)
                selectedTrackIndex = generatedTracks.lastIndex
                audioBytes = bytes
                audioMimeType = mime
                importedFileName = name
                title = importedTitle
                status = "Lagu berhasil ditambahkan ke Pustaka • ${bytes.size / 1024} KB"
                page = AppPage.PLAYER
            } catch (e: Exception) {
                status = "Gagal mengimpor audio: ${e.message}"
            }
        }
    }

    val genres = listOf("Pop", "Pop Rock", "Indie Pop", "Rock", "Alternative Rock", "Punk Rock", "Pop Punk", "Ska", "Ska-Punk", "Reggae", "Roots Reggae", "Dancehall", "Dangdut", "Dangdut Koplo", "Dangdut Rock", "Keroncong", "Rap", "Hip-Hop", "Trap", "R&B", "Soul", "Funk", "Jazz", "Blues", "Country", "Folk", "Metal", "Heavy Metal", "Hardcore Punk", "Post-Hardcore", "Emo Rock", "Acoustic", "Ballad", "EDM", "House", "Techno", "Trance", "Drum and Bass", "Lo-fi", "Cinematic", "Orchestral")
    val moodOptions = listOf("Bahagia", "Sedih", "Emosional", "Romantis", "Patah hati", "Nostalgia", "Rindu", "Sendu", "Enerjik", "Semangat", "Agresif", "Gelap", "Tenang", "Damai", "Dreamy", "Melankolis", "Epik", "Ceria", "Santai", "Heroik", "Intim", "Optimistis", "Misterius")

    fun generateSong() {
        if (busy) return
        val useStability = selectedProvider == "stability"
        if (!useStability && backendUrl.isBlank()) {
            status = "Masukkan URL backend HTTPS di Pengaturan terlebih dahulu."
            page = AppPage.SETTINGS
            return
        }
        if (!useStability && lyrics.trim().length < 10) {
            status = "Lirik minimal 10 karakter."
            return
        }
        val useLyria = selectedProvider == "lyria"
        val apiKeyOverride = if (developerUnlocked) developerApiKey.trim() else ""
        val lyriaKeyOverride = if (developerUnlocked) developerLyriaApiKey.trim() else ""
        val stabilityKeyOverride = if (developerUnlocked) developerStabilityApiKey.trim() else ""
        val devCodeForRequest = if (apiKeyOverride.isNotBlank() || lyriaKeyOverride.isNotBlank() || stabilityKeyOverride.isNotBlank()) developerCode else ""
        busy = true
        status = "Mengirim permintaan ke server..."
        Thread {
            try {
                val json = JSONObject().apply {
                    put("provider", when { useStability -> "stability"; useLyria -> "lyria"; else -> "musicapi" })
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
                if (useStability && stabilityKeyOverride.isBlank()) throw IllegalStateException("Masukkan dan simpan Stability API key di menu Pengembang.")
                if (useLyria && lyriaKeyOverride.isBlank()) {
                    throw IllegalStateException("Masukkan dan simpan Gemini API key di menu Pengembang, lalu buka akses pengembang sebelum memakai Lyria.")
                }
                val lyriaPrompt = buildString {
                    append("Create an original full-length song, approximately ${durationSeconds} seconds. ")
                    append("Title: ${title.ifBlank { "MusicAI" }}. ")
                    append("Genre and arrangement: ${(selectedGenres + listOf(customGenre.trim()).filter { it.isNotBlank() }).joinToString(", ")}. ")
                    append("Mood: ${(selectedMoods + listOf(customMood.trim()).filter { it.isNotBlank() }).joinToString(", ")}. ")
                    append("Tempo: ${bpm.toInt()} BPM. ")
                    append(when (vocal) {
                        "male" -> "Male lead singing voice. "
                        "duet" -> "Male and female duet vocals, alternating verses and harmonized chorus. "
                        "instrumental" -> "Instrumental only, no vocals. "
                        else -> "Female lead singing voice. "
                    })
                    append("Use these original lyrics. Preserve the section labels and lyrics: ")
                    append(lyrics)
                }
                val stabilityPrompt = buildString {
                    append("Instrumental music only, no singing and no spoken words. ")
                    append("Title: ${title.ifBlank { "MusicAI instrumental" }}. ")
                    append("Style and genre: ${(selectedGenres + listOf(customGenre.trim()).filter { it.isNotBlank() }).joinToString(", ").ifBlank { "Pop" }}. ")
                    append("Mood: ${(selectedMoods + listOf(customMood.trim()).filter { it.isNotBlank() }).joinToString(", ").ifBlank { mood }}. ")
                    append("Tempo: ${bpm.toInt()} BPM. ")
                    append("Create a coherent, polished instrumental arrangement with a clear beginning, development and ending.")
                }
                val request = if (useStability) {
                    val form = MultipartBody.Builder().setType(MultipartBody.FORM)
                        .addFormDataPart("model", "stable-audio-2.5")
                        .addFormDataPart("prompt", stabilityPrompt)
                        .addFormDataPart("duration", durationSeconds.coerceIn(1, 180).toString())
                        .addFormDataPart("output_format", "mp3")
                        .build()
                    Request.Builder().url("https://api.stability.ai/v2beta/audio/stable-audio/text-to-audio")
                        .header("Authorization", "Bearer $stabilityKeyOverride")
                        .header("Accept", "audio/*")
                        .post(form).build()
                } else if (useLyria) {
                    val lyriaBody = JSONObject().put("model", "lyria-3.5").put("input", lyriaPrompt).put("store", false)
                    Request.Builder()
                        .url("https://generativelanguage.googleapis.com/v1beta/interactions")
                        .header("x-goog-api-key", lyriaKeyOverride)
                        .post(lyriaBody.toString().toRequestBody("application/json".toMediaType()))
                        .build()
                } else {
                    Request.Builder()
                        .url("${backendUrl.trim().trimEnd('/')}/api/generate")
                        .post(json.toString().toRequestBody("application/json".toMediaType()))
                        .apply { if (apiKeyOverride.isNotBlank()) header("X-MusicAI-Dev-Code", devCodeForRequest).header("X-MusicAPI-Key", apiKeyOverride) }
                        .build()
                }
                val downloaded = mutableListOf<MusicTrack>()
                if (useStability) {
                    val audio = client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            val errorBody = response.body?.string().orEmpty()
                            val detail = runCatching { JSONObject(errorBody).optJSONArray("errors")?.optString(0) }.getOrNull()
                            throw IllegalStateException(detail?.takeIf { it.isNotBlank() } ?: "Stability API error ${response.code}: $errorBody")
                        }
                        response.body?.bytes() ?: throw IllegalStateException("Stability tidak mengembalikan audio.")
                    }
                    if (audio.isEmpty()) throw IllegalStateException("File audio Stability kosong.")
                    downloaded.add(MusicTrack(title = title.ifBlank { "Stability Instrumental" }, audioBytes = audio, durationSeconds = durationSeconds.coerceIn(1, 180).toFloat(), isAiGenerated = true))
                } else if (useLyria) {
                    val body = client.newCall(request).execute().use { response ->
                        val responseText = response.body?.string().orEmpty()
                        if (!response.isSuccessful) {
                            val message = try {
                                val errorJson = JSONObject(responseText)
                                errorJson.optJSONObject("error")?.optString("message")
                                    ?: errorJson.optString("error", "Lyria error ${response.code}")
                            } catch (_: Exception) { "Lyria error ${response.code}" }
                            throw IllegalStateException(message)
                        }
                        JSONObject(responseText)
                    }
                    var encodedAudio = body.optJSONObject("output_audio")?.optString("data").orEmpty()
                    if (encodedAudio.isBlank()) {
                        val steps = body.optJSONArray("steps")
                        if (steps != null) {
                            for (stepIndex in 0 until steps.length()) {
                                val step = steps.optJSONObject(stepIndex) ?: continue
                                if (step.optString("type") != "model_output") continue
                                val blocks = step.optJSONArray("content") ?: continue
                                for (blockIndex in 0 until blocks.length()) {
                                    val contentBlock = blocks.optJSONObject(blockIndex) ?: continue
                                    if (contentBlock.optString("type") == "audio") encodedAudio = contentBlock.optString("data")
                                    if (encodedAudio.isNotBlank()) break
                                }
                                if (encodedAudio.isNotBlank()) break
                            }
                        }
                    }
                    if (encodedAudio.isBlank()) throw IllegalStateException("Lyria merespons tetapi audio tidak ditemukan.")
                    val audio = android.util.Base64.decode(encodedAudio, android.util.Base64.DEFAULT)
                    if (audio.isNotEmpty()) downloaded.add(MusicTrack(
                        title = title.ifBlank { "Lyria MusicAI" },
                        audioBytes = audio,
                        durationSeconds = durationSeconds.toFloat(),
                        isAiGenerated = true
                    ))
                } else {
                    val jobId = client.newCall(request).execute().use { response ->
                        val responseText = response.body?.string().orEmpty()
                        if (!response.isSuccessful) {
                            val message = try { JSONObject(responseText).optString("error", "Server error ${response.code}") } catch (_: Exception) { "Server error ${response.code}" }
                            throw IllegalStateException(message)
                        }
                        JSONObject(responseText).optString("jobId").takeIf { it.isNotBlank() }
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
                            .apply { if (apiKeyOverride.isNotBlank()) header("X-MusicAI-Dev-Code", devCodeForRequest).header("X-MusicAPI-Key", apiKeyOverride) }
                            .build()
                        client.newCall(statusRequest).execute().use { response ->
                            val responseText = response.body?.string().orEmpty()
                            if (!response.isSuccessful) {
                                val message = try { JSONObject(responseText).optString("error", "Gagal memeriksa status.") } catch (_: Exception) { "Gagal memeriksa status." }
                                throw IllegalStateException(message)
                            }
                            val job = JSONObject(responseText)
                            lastStatus = job.optString("status", "PROCESSING")
                            val normalizedStatus = lastStatus.uppercase()
                            if (normalizedStatus == "FAILED") throw IllegalStateException(job.optString("error", "Generasi lagu gagal."))
                            if (normalizedStatus == "COMPLETED" || normalizedStatus == "SUCCEEDED") completedTracks = job.optJSONArray("tracks")
                        }
                        if (completedTracks == null) activity.runOnUiThread { status = "Membuat lagu dengan MusicAPI... (${lastStatus.lowercase()})" }
                    }
                    val tracks = completedTracks ?: throw IllegalStateException("Generasi belum selesai setelah 7,5 menit. Coba periksa kembali nanti.")
                    if (tracks.length() == 0) throw IllegalStateException("MusicAPI selesai tetapi tidak mengembalikan audio.")
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
                            durationSeconds = if (track.has("duration")) track.optDouble("duration").toFloat() else null,
                            isAiGenerated = true
                        ))
                    }
                }

                if (downloaded.isEmpty()) throw IllegalStateException("Suno selesai tetapi audio tidak bisa diunduh.")
                activity.runOnUiThread {
                    val firstNewTrack = generatedTracks.size
                    generatedTracks = saveMusicHistory(context, generatedTracks + downloaded)
                    selectedTrackIndex = firstNewTrack
                    audioBytes = generatedTracks[selectedTrackIndex].audioBytes
                    title = generatedTracks[selectedTrackIndex].title
                    audioMimeType = "audio/mpeg"
                    status = "${downloaded.size} hasil audio berhasil dibuat dengan ${when { useStability -> "Stability AI"; useLyria -> "Google Lyria"; else -> "MusicAPI / Suno" }} dan siap dipilih."
                    busy = false
                    page = AppPage.LIBRARY
                }
            } catch (e: Exception) {
                activity.runOnUiThread {
                    status = "Gagal: ${e.message ?: "Periksa koneksi dan server."}"
                    busy = false
                }
            }
        }.start()
    }


    fun generateCover() {
        if (busy) return
        val source = coverSourceBytes ?: run { status = "Pilih file lagu dari HP terlebih dahulu."; return }
        if (backendUrl.isBlank()) { status = "URL backend belum diatur."; page = AppPage.SETTINGS; return }
        if (selectedProvider != "musicapi") { status = "Cover lagu saat ini menggunakan provider Suno / MusicAPI."; return }
        val apiKeyOverride = if (developerUnlocked) developerApiKey.trim() else ""
        val devCodeForRequest = if (apiKeyOverride.isNotBlank()) developerCode else ""
        val coverTitle = title.trim().ifBlank { "MusicAI Cover" }
        val tags = (selectedGenres + listOf(customGenre.trim()).filter { it.isNotBlank() } + selectedMoods + listOf(customMood.trim()).filter { it.isNotBlank() }).joinToString(", ").ifBlank { "pop, modern" }
        val prompt = "Create a new cover arrangement of the uploaded source song. Preserve its recognizable core melody, song structure and emotional identity while re-recording it in this style: $tags. ${customMood.trim()} Keep the arrangement polished and musically coherent."
        busy = true
        status = "Mengunggah audio sumber..."
        Thread {
            try {
                val client = OkHttpClient.Builder().callTimeout(10, TimeUnit.MINUTES).connectTimeout(30, TimeUnit.SECONDS).readTimeout(10, TimeUnit.MINUTES).build()
                val request = Request.Builder().url("${backendUrl.trim().trimEnd('/')}/api/cover")
                    .header("Content-Type", coverSourceMime).header("X-Cover-Title", coverTitle)
                    .header("X-Cover-Tags", tags).header("X-Cover-Prompt", prompt).header("X-Cover-Weight", coverAudioWeight.toString())
                    .apply { if (apiKeyOverride.isNotBlank()) header("X-MusicAI-Dev-Code", devCodeForRequest).header("X-MusicAPI-Key", apiKeyOverride) }
                    .post(source.toRequestBody(coverSourceMime.toMediaType())).build()
                val jobId = client.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) { val error = runCatching { JSONObject(body).optString("error") }.getOrNull().orEmpty(); throw IllegalStateException(error.ifBlank { "Server cover error ${response.code}: $body" }) }
                    JSONObject(body).optString("jobId").takeIf { it.isNotBlank() } ?: throw IllegalStateException("Server tidak mengembalikan ID cover.")
                }
                activity.runOnUiThread { status = "AI sedang mengubah aransemen lagu..." }
                var completedTracks: org.json.JSONArray? = null
                var lastStatus = "QUEUED"
                var attempts = 0
                while (completedTracks == null && attempts < 90) {
                    attempts++; Thread.sleep(5_000)
                    val statusRequest = Request.Builder().url("${backendUrl.trim().trimEnd('/')}/api/job/$jobId").get()
                        .apply { if (apiKeyOverride.isNotBlank()) header("X-MusicAI-Dev-Code", devCodeForRequest).header("X-MusicAPI-Key", apiKeyOverride) }.build()
                    client.newCall(statusRequest).execute().use { response ->
                        val body = response.body?.string().orEmpty()
                        if (!response.isSuccessful) throw IllegalStateException("Gagal memeriksa status cover: HTTP ${response.code}")
                        val job = JSONObject(body); lastStatus = job.optString("status", "PROCESSING")
                        when (lastStatus.lowercase()) { "failed" -> throw IllegalStateException(job.optString("error", "Generasi cover gagal.")); "completed", "succeeded" -> completedTracks = job.optJSONArray("tracks") }
                    }
                    if (completedTracks == null) activity.runOnUiThread { status = "Membuat cover... (${lastStatus.lowercase()})" }
                }
                val tracks = completedTracks ?: throw IllegalStateException("Cover belum selesai setelah 7,5 menit. Periksa kembali sebelum mengulang agar tidak terkena biaya dua kali.")
                val downloaded = mutableListOf<MusicTrack>()
                for (i in 0 until tracks.length()) {
                    val item = tracks.getJSONObject(i); val audioUrl = item.optString("audioUrl"); if (!audioUrl.startsWith("https://")) continue
                    val bytes = client.newCall(Request.Builder().url(audioUrl).get().build()).execute().use { response -> if (!response.isSuccessful) throw IllegalStateException("Gagal mengunduh hasil cover (${response.code})."); response.body?.bytes() ?: ByteArray(0) }
                    if (bytes.isNotEmpty()) downloaded.add(MusicTrack(title = item.optString("title").ifBlank { "$coverTitle - Cover" }, audioBytes = bytes, imageUrl = item.optString("imageUrl").takeIf { it.startsWith("https://") }, durationSeconds = if (item.has("duration")) item.optDouble("duration").toFloat() else coverSourceDuration, isAiGenerated = true))
                }
                if (downloaded.isEmpty()) throw IllegalStateException("Cover selesai tetapi file audio tidak ditemukan.")
                activity.runOnUiThread {
                    val firstNewTrack = generatedTracks.size; generatedTracks = saveMusicHistory(context, generatedTracks + downloaded)
                    selectedTrackIndex = firstNewTrack; audioBytes = generatedTracks[selectedTrackIndex].audioBytes; title = generatedTracks[selectedTrackIndex].title
                    audioMimeType = "audio/mpeg"; status = "${downloaded.size} cover berhasil dibuat dan disimpan ke Pustaka."
                    busy = false; coverSourceBytes = null; coverSourceName = ""; page = AppPage.LIBRARY
                }
            } catch (e: Exception) { activity.runOnUiThread { status = "Gagal membuat cover: ${e.message ?: "Periksa koneksi dan backend."}"; busy = false } }
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
        if (index == selectedTrackIndex && player != null) {
            if (autoplay && !isPlaying) { player?.start(); isPlaying = true }
            return
        }
        selectedTrackIndex = index
        audioBytes = generatedTracks[index].audioBytes
        title = generatedTracks[index].title
        audioMimeType = "audio/mpeg"
        playbackPosition = 0
        playbackDuration = (generatedTracks[index].durationSeconds?.times(1000))?.toInt() ?: 0
        player?.release()
        player = null
        isPlaying = false
        if (autoplay) playAudio()
    }

    fun deleteTrack(index: Int) {
        if (index !in generatedTracks.indices) return
        val removed = generatedTracks[index]
        if (index == selectedTrackIndex) {
            player?.stop()
            player?.release()
            player = null
            isPlaying = false
            audioBytes = null
            playbackPosition = 0
            playbackDuration = 0
        }
        removed.fileName?.let { java.io.File(java.io.File(context.filesDir, "music_history"), it).delete() }
        removed.coverFileName?.let { java.io.File(java.io.File(context.filesDir, "music_covers"), it).delete() }
        val updated = generatedTracks.toMutableList().also { it.removeAt(index) }
        generatedTracks = saveMusicHistory(context, updated)
        selectedTrackIndex = when {
            generatedTracks.isEmpty() -> 0
            index < selectedTrackIndex -> selectedTrackIndex - 1
            index == selectedTrackIndex -> (index.coerceAtMost(generatedTracks.lastIndex))
            else -> selectedTrackIndex
        }
        if (generatedTracks.isNotEmpty() && audioBytes == null) {
            audioBytes = generatedTracks[selectedTrackIndex].audioBytes
            title = generatedTracks[selectedTrackIndex].title
        }
        status = "Lagu dihapus dari Pustaka MusicAI. File asli di perangkat tetap aman."
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
        if (showLyriaCostConfirm) {
            AlertDialog(
                onDismissRequest = { showLyriaCostConfirm = false },
                title = { Text("Konfirmasi biaya Lyria") },
                text = { Text("Google Lyria 3.5 mengenakan biaya sekitar US$0,08 (± Rp1.336) untuk satu lagu penuh. Biaya aktual mengikuti kurs dan pajak. Lanjutkan generasi?") },
                confirmButton = {
                    TextButton(onClick = { showLyriaCostConfirm = false; generateSong() }) { Text("Lanjut • ± Rp1.336") }
                },
                dismissButton = {
                    TextButton(onClick = { showLyriaCostConfirm = false }) { Text("Batal") }
                },
                containerColor = Color(0xFF211A32)
            )
        }
        if (trackToDelete >= 0 && trackToDelete < generatedTracks.size) {
            AlertDialog(
                onDismissRequest = { trackToDelete = -1 },
                title = { Text("Hapus dari MusicAI?") },
                text = { Text("\" ${generatedTracks[trackToDelete].title} \" hanya akan dihapus dari Pustaka MusicAI. File asli di penyimpanan perangkat tetap aman dan tidak disentuh.") },
                confirmButton = {
                    TextButton(onClick = { val index = trackToDelete; trackToDelete = -1; deleteTrack(index); if (page == AppPage.PLAYER && generatedTracks.isEmpty()) page = AppPage.LIBRARY }) { Text("Hapus", color = Color(0xFFFF8A80)) }
                },
                dismissButton = { TextButton(onClick = { trackToDelete = -1 }) { Text("Batal") } },
                containerColor = Color(0xFF211A32)
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
                        selected = page == AppPage.LIBRARY,
                        onClick = { page = AppPage.LIBRARY },
                        icon = { Icon(Icons.Default.MusicNote, contentDescription = null) },
                        label = { Text("Pustaka") }
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
                        Text("CREATE YOUR MUSIC  •  AI MUSIC STUDIO", color = Color(0xFFBBA6D9), fontSize = 10.sp, letterSpacing = 1.2.sp)
                    }
                }

                when (page) {
                    AppPage.STUDIO -> {
                        Text("Studio kreatif", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                        Text("Wujudkan ide menjadi musik.", color = Color(0xFFB8B2C8), fontSize = 13.sp)
                        Text("AI GENERATOR", color = Color(0xFFD6C7FF), fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 1.sp)
                        Box(Modifier.fillMaxWidth()) {
                            OutlinedButton(
                                onClick = { providerMenuExpanded = true },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp),
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 13.dp)
                            ) {
                                Text(if (selectedProvider == "stability") "Stability AI • Stable Audio 2.5" else "Suno • MusicAPI", modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Start)
                                Text("⌄", fontSize = 18.sp)
                            }
                            DropdownMenu(
                                expanded = providerMenuExpanded,
                                onDismissRequest = { providerMenuExpanded = false },
                                modifier = Modifier.fillMaxWidth(0.88f).background(Color(0xFF191821))
                            ) {
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text("Suno", fontWeight = FontWeight.SemiBold)
                                            Text("Lagu dengan lirik dan vokal", color = Color.LightGray, fontSize = 11.sp)
                                        }
                                    },
                                    onClick = {
                                        selectedProvider = "musicapi"
                                        providerMenuExpanded = false
                                        context.getSharedPreferences("musicai_settings", android.content.Context.MODE_PRIVATE).edit().putString("provider", "musicapi").apply()
                                    },
                                    trailingIcon = { if (selectedProvider == "musicapi") Text("✓", color = Color(0xFF70D7A0)) }
                                )
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text("Stability AI", fontWeight = FontWeight.SemiBold)
                                            Text("Stable Audio 2.5 • Instrumental", color = Color.LightGray, fontSize = 11.sp)
                                        }
                                    },
                                    onClick = {
                                        selectedProvider = "stability"
                                        vocal = "instrumental"
                                        if (durationSeconds > 180) durationSeconds = 180
                                        providerMenuExpanded = false
                                        context.getSharedPreferences("musicai_settings", android.content.Context.MODE_PRIVATE).edit().putString("provider", "stability").apply()
                                    },
                                    trailingIcon = { if (selectedProvider == "stability") Text("✓", color = Color(0xFF70D7A0)) }
                                )
                            }
                        }
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
                            listOf("male" to "Pria", "female" to "Wanita", "duet" to "Duet", "instrumental" to "Instrumen").forEach { (value, label) ->
                                FilterChip(
                                    selected = vocal == value,
                                    onClick = { if (selectedProvider != "stability") vocal = value },
                                    enabled = selectedProvider != "stability" || value == "instrumental",
                                    label = { Text(label) }
                                )
                            }
                        }
                        Surface(color = panel, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                            Text(
                                if (selectedProvider == "stability") "Stability hanya mendukung musik instrumental. Pilihan vokal dikunci ke Instrumen selama provider ini aktif." else "Pilih Pria, Wanita, Duet Pria + Wanita, atau Instrumen. Mode duet mengarahkan AI membuat vokal bergantian dan harmoni bersama.",
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
                        Text(when { selectedProvider == "stability" -> "Stability Stable Audio 2.5: instrumental hingga 3 menit."; selectedProvider == "lyria" -> "Lyria 3.5: durasi hingga sekitar 3 menit."; else -> "Durasi maksimal Suno / MusicAPI: 6 menit." }, color = Color.LightGray, fontSize = 11.sp)
                        var durationExpanded by remember { mutableStateOf(false) }
                        Box {
                            OutlinedButton(onClick = { durationExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                                Text("${durationSeconds / 60} menit ${durationSeconds % 60} detik", modifier = Modifier.weight(1f))
                                Text("⌄")
                            }
                            DropdownMenu(expanded = durationExpanded, onDismissRequest = { durationExpanded = false }) {
                                (if (selectedProvider == "lyria" || selectedProvider == "stability") listOf(30, 60, 90, 120, 180) else listOf(60, 120, 180, 240, 300, 360)).forEach { seconds ->
                                    DropdownMenuItem(
                                        text = { Text(if (seconds == 360) "Maksimal AI • 6 menit" else if (seconds % 60 == 0) "${seconds / 60} menit" else "${seconds} detik") },
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
                            onClick = { if (selectedProvider == "lyria") showLyriaCostConfirm = true else generateSong() },
                            enabled = !busy && lyrics.isNotBlank(),
                            modifier = Modifier.fillMaxWidth().height(54.dp),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            if (busy) CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                            else Text("CREATE YOUR MUSIC", fontWeight = FontWeight.Bold)
                        }
                        val aiTracks = generatedTracks.withIndex().filter { it.value.isAiGenerated }
                        if (aiTracks.isNotEmpty()) {
                            Text("HASIL AI TERBARU", color = Color(0xFFD6C7FF), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            aiTracks.takeLast(3).asReversed().forEach { indexedTrack ->
                                val trackIndex = indexedTrack.index
                                val track = indexedTrack.value
                                Surface(color = panel, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth(), onClick = { selectTrack(trackIndex, false); page = AppPage.PLAYER }) {
                                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        if (track.imageUrl != null) {
                                            AsyncImage(model = track.imageUrl, contentDescription = "Cover ${track.title}", modifier = Modifier.size(58.dp), contentScale = ContentScale.Crop)
                                        } else {
                                            Surface(color = Color(0xFF39324D), shape = RoundedCornerShape(10.dp), modifier = Modifier.size(58.dp)) {
                                                Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.MusicNote, null, tint = accent, modifier = Modifier.size(30.dp)) }
                                            }
                                        }
                                        Text(track.title, modifier = Modifier.weight(1f), maxLines = 2, fontWeight = FontWeight.SemiBold)
                                        IconButton(onClick = { selectTrack(trackIndex, true); page = AppPage.PLAYER }) { Icon(Icons.Default.PlayArrow, "Putar") }
                                    }
                                }
                            }
                            OutlinedButton(onClick = { page = AppPage.LIBRARY }, modifier = Modifier.fillMaxWidth()) {
                                Text("Buka Pustaka • ${generatedTracks.size} lagu")
                            }
                        }
                    }

                    AppPage.LIBRARY -> {
                        Text("Pustaka musik", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                        Text("Semua lagu buatan MusicAI dan audio lokal yang kamu impor.", color = Color.LightGray, fontSize = 13.sp)
                        OutlinedButton(onClick = { importAudioLauncher.launch(arrayOf("audio/*")) }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.Download, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Tambah lagu dari perangkat")
                        }
                        Text("KOLEKSI • ${generatedTracks.size} LAGU", color = Color(0xFFD6C7FF), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        if (generatedTracks.isEmpty()) {
                            Surface(color = panel, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(Icons.Default.MusicNote, null, tint = accent, modifier = Modifier.size(42.dp))
                                    Text("Pustaka masih kosong", fontWeight = FontWeight.Bold)
                                    Text("Buat lagu di Studio atau tambahkan audio dari perangkat.", color = Color.LightGray, fontSize = 12.sp)
                                }
                            }
                        }
                        generatedTracks.forEachIndexed { index, track ->
                            var menuExpanded by remember(track.fileName) { mutableStateOf(false) }
                            Surface(color = if (selectedTrackIndex == index) Color(0xFF30264A) else panel, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth(), onClick = {
                                if (index != selectedTrackIndex) selectTrack(index, false)
                                page = AppPage.PLAYER
                            }) {
                                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    val localCover = track.coverFileName?.let { java.io.File(java.io.File(context.filesDir, "music_covers"), it) }?.takeIf { it.exists() }
                                    if (track.imageUrl != null || localCover != null) AsyncImage(model = track.imageUrl ?: localCover, contentDescription = "Cover lagu", modifier = Modifier.size(62.dp), contentScale = ContentScale.Crop)
                                    else Surface(color = Color(0xFF39324D), shape = RoundedCornerShape(12.dp), modifier = Modifier.size(62.dp)) {
                                        Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.MusicNote, null, tint = accent, modifier = Modifier.size(30.dp)) }
                                    }
                                    Column(Modifier.weight(1f)) {
                                        Text(track.title, fontWeight = FontWeight.SemiBold, maxLines = 2)
                                        Text(listOfNotNull(track.artist, track.durationSeconds?.let { formatTime((it * 1000).toInt()) }).joinToString(" • ").ifBlank { "Audio • tersimpan di perangkat" }, color = Color.LightGray, fontSize = 11.sp)
                                    }
                                    IconButton(onClick = { if (index != selectedTrackIndex) selectTrack(index, false); page = AppPage.PLAYER; if (!isPlaying) togglePlayback() }) { Icon(Icons.Default.PlayArrow, "Putar lagu", tint = accent) }
                                    Box {
                                        IconButton(onClick = { menuExpanded = true }) { Icon(Icons.Default.MoreVert, "Opsi lagu") }
                                        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                                            DropdownMenuItem(text = { Text("Buka pemutar") }, onClick = { menuExpanded = false; if (index != selectedTrackIndex) selectTrack(index, false); page = AppPage.PLAYER })
                                            DropdownMenuItem(text = { Text("Hapus dari MusicAI") }, leadingIcon = { Icon(Icons.Default.Delete, null) }, onClick = { menuExpanded = false; trackToDelete = index })
                                        }
                                    }
                                }
                            }
                        }
                        Text(status, color = Color.LightGray, fontSize = 12.sp)
                    }

                    AppPage.PLAYER -> {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { page = AppPage.LIBRARY }) { Text("‹  Pustaka") }
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = { if (selectedTrackIndex in generatedTracks.indices) trackToDelete = selectedTrackIndex }) {
                                Icon(Icons.Default.Delete, null)
                                Text("Hapus")
                            }
                        }
                        val currentTrack = generatedTracks.getOrNull(selectedTrackIndex)
                        Text("SEDANG DIPUTAR", color = Color(0xFFD6C7FF), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                        Surface(color = Color(0xFF201A31), shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                val playerCover = currentTrack?.coverFileName?.let { java.io.File(java.io.File(context.filesDir, "music_covers"), it) }?.takeIf { it.exists() }
                                if (currentTrack?.imageUrl != null || playerCover != null) {
                                    AsyncImage(model = currentTrack?.imageUrl ?: playerCover, contentDescription = "Cover lagu", modifier = Modifier.fillMaxWidth().aspectRatio(1f), contentScale = ContentScale.Crop)
                                } else {
                                    Box(Modifier.fillMaxWidth().aspectRatio(1f).background(Brush.linearGradient(listOf(Color(0xFF39245F), Color(0xFF101D32))), RoundedCornerShape(18.dp)), contentAlignment = Alignment.Center) {
                                        Icon(Icons.Default.MusicNote, null, tint = Color(0xFFD6C7FF), modifier = Modifier.size(112.dp))
                                    }
                                }
                                Text(currentTrack?.title ?: title.ifBlank { "Belum ada lagu dipilih" }, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 2)
                                Text(currentTrack?.artist?.takeIf { it.isNotBlank() } ?: "MusicAI • Lagu", color = Color.LightGray, fontSize = 13.sp)
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                                    Surface(color = Color(0xFF3A2D55), shape = RoundedCornerShape(50)) {
                                        Text(if (currentTrack?.imageUrl != null || playerCover != null) "COVER ASLI" else "ARTWORK MUSICAI", modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp), color = Color(0xFFD6C7FF), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                                Slider(value = playbackPosition.toFloat().coerceIn(0f, playbackDuration.coerceAtLeast(1).toFloat()), onValueChange = { seekTo(it.toInt()) }, valueRange = 0f..playbackDuration.coerceAtLeast(1).toFloat(), enabled = playbackDuration > 0)
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(formatTime(playbackPosition), color = Color.LightGray, fontSize = 11.sp)
                                    Text(formatTime(playbackDuration), color = Color.LightGray, fontSize = 11.sp)
                                }
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(onClick = { playPrevious() }, enabled = selectedTrackIndex > 0) { Icon(Icons.Default.SkipPrevious, "Sebelumnya", modifier = Modifier.size(30.dp)) }
                                    FilledIconButton(onClick = { togglePlayback() }, enabled = audioBytes != null, modifier = Modifier.size(68.dp)) {
                                        Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, "Putar atau jeda", modifier = Modifier.size(38.dp))
                                    }
                                    IconButton(onClick = { playNext() }, enabled = selectedTrackIndex < generatedTracks.lastIndex) { Icon(Icons.Default.SkipNext, "Berikutnya", modifier = Modifier.size(30.dp)) }
                                }
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    OutlinedButton(onClick = { saveAudio() }, enabled = audioBytes != null, modifier = Modifier.weight(1f)) {
                                        Icon(Icons.Default.Download, null)
                                        Spacer(Modifier.width(6.dp))
                                        Text("Simpan")
                                    }
                                    OutlinedButton(onClick = { if (selectedTrackIndex in generatedTracks.indices) trackToDelete = selectedTrackIndex }, enabled = currentTrack != null, modifier = Modifier.weight(1f)) {
                                        Icon(Icons.Default.Delete, null)
                                        Spacer(Modifier.width(6.dp))
                                        Text("Hapus")
                                    }
                                }
                            }
                        }
                        Surface(color = panel, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.MusicNote, null, tint = accent, modifier = Modifier.size(28.dp))
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("MusicAI Player", fontWeight = FontWeight.SemiBold)
                                    Text("Kontrol pemutaran lagu", color = Color.LightGray, fontSize = 11.sp)
                                }
                                Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, null, tint = accent)
                            }
                        }
                        Text(status, color = Color.LightGray, fontSize = 12.sp)
                    }

                    AppPage.SETTINGS -> {
                        Text("Pengaturan", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        Surface(color = panel, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("Koneksi server", fontWeight = FontWeight.Bold)
                                Text("Mode khusus pengembang • ryuzee_project", color = Color(0xFFD6C7FF), fontSize = 12.sp)
                                if (!developerUnlocked) {
                                    OutlinedTextField(value = developerCode, onValueChange = { developerCode = it }, label = { Text("Kode akses pengembang") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                                    OutlinedButton(onClick = {
                                        developerVerifying = true
                                        Thread {
                                            try {
                                                val body = JSONObject().put("code", developerCode).toString().toRequestBody("application/json".toMediaType())
                                                val req = Request.Builder().url("${backendUrl.trim().trimEnd('/')}/api/dev/verify").post(body).build()
                                                OkHttpClient().newCall(req).execute().use { response ->
                                                    val ok = response.isSuccessful && JSONObject(response.body?.string().orEmpty()).optBoolean("authorized")
                                                    activity.runOnUiThread {
                                                        developerUnlocked = ok
                                                        developerMessage = if (ok) "Akses pengembang dibuka." else "Kode salah atau kode pengembang belum diatur di server."
                                                        developerVerifying = false
                                                    }
                                                }
                                            } catch (_: Exception) {
                                                activity.runOnUiThread { developerMessage = "Gagal memverifikasi. Pastikan URL server benar."; developerVerifying = false }
                                            }
                                        }.start()
                                    }, enabled = backendUrl.startsWith("https://") && developerCode.isNotBlank() && !developerVerifying) {
                                        Text(if (developerVerifying) "Memeriksa..." else "Buka mode pengembang")
                                    }
                                    if (developerMessage.isNotBlank()) Text(developerMessage, color = Color.LightGray, fontSize = 12.sp)
                                } else {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("Akses pengembang aktif", color = Color(0xFF81C784), modifier = Modifier.weight(1f))
                                        TextButton(onClick = { developerUnlocked = false; developerCode = ""; developerMessage = "" }) { Text("Kunci") }
                                    }
                                }
                                if (developerUnlocked) {
                                Text(
                                    "API key pengganti hanya dipakai saat mode pengembang terbuka. Key disimpan terenkripsi di perangkat dan dikirim ke backend melalui HTTPS; Vercel environment variable tidak diubah.",
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
                                Divider(color = Color(0x665F5477))
                                Divider(color = Color(0x665F5477))
                                Text("PROVIDER GENERASI", color = Color(0xFFD6C7FF), fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    FilterChip(
                                        selected = selectedProvider == "musicapi",
                                        onClick = {
                                            selectedProvider = "musicapi"
                                            context.getSharedPreferences("musicai_settings", android.content.Context.MODE_PRIVATE).edit().putString("provider", "musicapi").apply()
                                        },
                                        label = { Text("MusicAPI / Suno") }
                                    )
                                    FilterChip(
                                        selected = selectedProvider == "lyria",
                                        onClick = {
                                            selectedProvider = "lyria"
                                            context.getSharedPreferences("musicai_settings", android.content.Context.MODE_PRIVATE).edit().putString("provider", "lyria").apply()
                                        },
                                        label = { Text("Google Lyria 3.5") }
                                    )
                                }
                                Text("Provider yang dipilih dipakai saat membuat lagu. Pilihan dan seluruh pengaturan pengembang tetap tersimpan.", color = Color.LightGray, fontSize = 11.sp)
                                Text("API KEY MUSICAPI • KHUSUS PENGEMBANG", color = Color(0xFFD6C7FF), fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                Text("Ganti key kapan saja tanpa mengubah environment variable Vercel. Key tersimpan terenkripsi di perangkat ini.", color = Color.LightGray, fontSize = 12.sp)
                                OutlinedTextField(
                                    value = developerApiKey,
                                    onValueChange = { developerApiKey = it },
                                    label = { Text("API key baru") },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    visualTransformation = if (apiKeyVisible) androidx.compose.ui.text.input.VisualTransformation.None else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                                    trailingIcon = {
                                        TextButton(onClick = { apiKeyVisible = !apiKeyVisible }) { Text(if (apiKeyVisible) "Sembunyikan" else "Lihat") }
                                    }
                                )
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(
                                        onClick = {
                                            saveDeveloperApiKey(context, developerApiKey.trim())
                                            developerMessage = if (developerApiKey.isBlank()) "API key lokal dihapus." else "API key tersimpan terenkripsi di perangkat."
                                        },
                                        modifier = Modifier.weight(1f),
                                        enabled = developerCode.isNotBlank()
                                    ) { Text("Simpan API key") }
                                    OutlinedButton(
                                        onClick = {
                                            developerApiKey = ""
                                            saveDeveloperApiKey(context, "")
                                            developerMessage = "API key lokal dihapus. Backend kembali memakai key Vercel."
                                        },
                                        modifier = Modifier.weight(1f)
                                    ) { Text("Hapus / Reset") }
                                }
                                Divider(color = Color(0x665F5477))
                                Text("STABILITY AI • STABLE AUDIO 2.5", color = Color(0xFFD6C7FF), fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                Text("API key Stability disimpan terenkripsi di perangkat dan dikirim langsung ke Stability melalui HTTPS. Mode ini menghasilkan instrumental tanpa vokal. Akun baru mendapat 25 kredit; satu generasi Stable Audio 2.5 menggunakan 20 kredit.", color = Color.LightGray, fontSize = 12.sp)
                                OutlinedTextField(
                                    value = developerStabilityApiKey,
                                    onValueChange = { developerStabilityApiKey = it },
                                    label = { Text("Stability API key") },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    visualTransformation = if (apiKeyVisible) androidx.compose.ui.text.input.VisualTransformation.None else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                                    trailingIcon = { TextButton(onClick = { apiKeyVisible = !apiKeyVisible }) { Text(if (apiKeyVisible) "Sembunyikan" else "Lihat") } }
                                )
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(onClick = {
                                        saveDeveloperApiKey(context, developerStabilityApiKey.trim(), "stability")
                                        developerMessage = if (developerStabilityApiKey.isBlank()) "Stability API key dihapus." else "Stability API key tersimpan terenkripsi."
                                    }, modifier = Modifier.weight(1f)) { Text("Simpan Stability key") }
                                    OutlinedButton(onClick = {
                                        developerStabilityApiKey = ""
                                        saveDeveloperApiKey(context, "", "stability")
                                        developerMessage = "Stability API key dihapus dari perangkat."
                                    }, modifier = Modifier.weight(1f)) { Text("Hapus key") }
                                }
                                Divider(color = Color(0x665F5477))
                                Text("GOOGLE LYRIA 3.5 • KHUSUS PENGEMBANG", color = Color(0xFFD6C7FF), fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                Text("Masukkan Gemini API key dari Google AI Studio. Key disimpan terenkripsi di perangkat dan dikirim langsung ke Google hanya ketika provider Lyria dipilih.", color = Color.LightGray, fontSize = 12.sp)
                                OutlinedTextField(
                                    value = developerLyriaApiKey,
                                    onValueChange = { developerLyriaApiKey = it },
                                    label = { Text("Gemini API key • Lyria") },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    visualTransformation = if (apiKeyVisible) androidx.compose.ui.text.input.VisualTransformation.None else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                                    trailingIcon = {
                                        TextButton(onClick = { apiKeyVisible = !apiKeyVisible }) { Text(if (apiKeyVisible) "Sembunyikan" else "Lihat") }
                                    }
                                )
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(
                                        onClick = {
                                            saveDeveloperApiKey(context, developerLyriaApiKey.trim(), "lyria")
                                            developerMessage = if (developerLyriaApiKey.isBlank()) "API key Lyria lokal dihapus." else "API key Lyria tersimpan terenkripsi."
                                        },
                                        modifier = Modifier.weight(1f)
                                    ) { Text("Simpan key Lyria") }
                                    OutlinedButton(
                                        onClick = {
                                            developerLyriaApiKey = ""
                                            saveDeveloperApiKey(context, "", "lyria")
                                            developerMessage = "API key Lyria lokal dihapus. Backend akan memakai GEMINI_API_KEY jika tersedia."
                                        },
                                        modifier = Modifier.weight(1f)
                                    ) { Text("Hapus key") }
                                }
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
                        }
                        Surface(color = panel, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("Tentang MusicAI", fontWeight = FontWeight.Bold)
                                Text("Versi 0.5.0 • Android native", color = Color.LightGray, fontSize = 12.sp)
                                Text("MusicAI adalah studio musik berbasis kecerdasan buatan (AI) yang membantu siapa saja menciptakan lagu dengan mudah, mulai dari ide sederhana hingga lirik lengkap.\n\nPilih genre, mood, tempo, durasi, serta karakter vokal pria, wanita, duet (pria + wanita), atau instrumental. MusicAI akan membantu mengubah kreativitasmu menjadi sebuah karya musik.\n\nKamu dapat memutar, mengelola, mengedit informasi lagu, dan mengunduh hasil musik ke perangkat untuk dinikmati kapan saja.\n\nMusicAI — Your Ideas, Your Music.", color = Color.LightGray, fontSize = 13.sp)
                                Text("Dikembangkan oleh ryuzee_project", color = Color(0xFFD6C7FF), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }

                if (page != AppPage.LIBRARY && page != AppPage.PLAYER) {
                    Text(status, color = Color.LightGray, fontSize = 12.sp)
                }
                Text("MusicAI • Flow Music companion", color = Color.Gray, fontSize = 11.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
            }
        }
    }
}
