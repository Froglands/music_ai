package com.ryuzeeproject.music_ai

import android.content.ContentValues
import android.media.MediaPlayer
import android.media.audiofx.Equalizer
import android.media.audiofx.BassBoost
import android.media.audiofx.Visualizer
import android.media.MediaMetadataRetriever
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.rotate
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.material3.*
import coil.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.delay
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.material3.LocalContentColor
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

private enum class AppPage { STUDIO, LIBRARY, PLAYER, EQUALIZER, SETTINGS }

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
        val prefKey = when (provider) { "lyria" -> "lyria_api_key_encrypted"; "stability" -> "stability_api_key_encrypted"; else -> "provider_api_key_encrypted" }
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

@Composable
private fun RotaryKnob(
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    label: String,
    display: String,
    enabled: Boolean,
    onValueChange: (Float) -> Unit
) {
    val trackColor = Color(0xFF59627D)
    val activeColor = Color(0xFFB69CFF)
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            Modifier.size(116.dp).pointerInput(enabled, valueRange) {
                if (enabled) detectTapGestures { position ->
                    val dx = position.x - size.width / 2f
                    val dy = position.y - size.height / 2f
                    var angle = Math.toDegrees(kotlin.math.atan2(dy, dx).toDouble()).toFloat()
                    if (angle < 0f) angle += 360f
                    if (angle < 135f) angle += 360f
                    val fraction = ((angle - 135f) / 270f).coerceIn(0f, 1f)
                    onValueChange(valueRange.start + fraction * (valueRange.endInclusive - valueRange.start))
                }
            }.pointerInput(enabled, valueRange) {
                if (enabled) detectDragGestures(
                    onDragStart = { position ->
                        val dx = position.x - size.width / 2f
                        val dy = position.y - size.height / 2f
                        var angle = Math.toDegrees(kotlin.math.atan2(dy, dx).toDouble()).toFloat()
                        if (angle < 0f) angle += 360f
                        if (angle < 135f) angle += 360f
                        val fraction = ((angle - 135f) / 270f).coerceIn(0f, 1f)
                        onValueChange(valueRange.start + fraction * (valueRange.endInclusive - valueRange.start))
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        val dx = change.position.x - size.width / 2f
                        val dy = change.position.y - size.height / 2f
                        var angle = Math.toDegrees(kotlin.math.atan2(dy, dx).toDouble()).toFloat()
                        if (angle < 0f) angle += 360f
                        if (angle < 135f) angle += 360f
                        val fraction = ((angle - 135f) / 270f).coerceIn(0f, 1f)
                        onValueChange(valueRange.start + fraction * (valueRange.endInclusive - valueRange.start))
                    }
                )
            },
            contentAlignment = Alignment.Center
        ) {
            androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                val stroke = 8.dp.toPx()
                val diameter = size.minDimension - stroke
                val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
                drawArc(trackColor, 135f, 270f, false, topLeft, androidx.compose.ui.geometry.Size(diameter, diameter), style = Stroke(stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round))
                val fraction = ((value - valueRange.start) / (valueRange.endInclusive - valueRange.start)).coerceIn(0f, 1f)
                drawArc(activeColor, 135f, 270f * fraction, false, topLeft, androidx.compose.ui.geometry.Size(diameter, diameter), style = Stroke(stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round))
                val angle = Math.toRadians((135f + 270f * fraction).toDouble())
                val radius = diameter / 2f
                val center = Offset(size.width / 2f, size.height / 2f)
                val inner = Offset(center.x + kotlin.math.cos(angle).toFloat() * (radius * 0.48f), center.y + kotlin.math.sin(angle).toFloat() * (radius * 0.48f))
                val outer = Offset(center.x + kotlin.math.cos(angle).toFloat() * (radius * 0.82f), center.y + kotlin.math.sin(angle).toFloat() * (radius * 0.82f))
                drawCircle(Color(0xFF202943), radius * 0.58f, center)
                drawLine(activeColor, inner, outer, strokeWidth = 4.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
                drawCircle(activeColor, 3.dp.toPx(), outer)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(display, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Text(label, color = Color(0xFFC9B8FF), fontSize = 9.sp)
            }
        }
    }
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
    val bg = Color(0xFF080B18)
    val panel = Color(0x552B3B5C)
    val accent = Color(0xFFB69CFF)
    val glassBorder = Color(0x99D8E7FF)

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
    var autoDuration by remember { mutableStateOf(false) }
    var equalizer by remember { mutableStateOf<Equalizer?>(null) }
    var bassBoost by remember { mutableStateOf<BassBoost?>(null) }
    var subwooferEnabled by remember { mutableStateOf(context.getSharedPreferences("musicai_player", android.content.Context.MODE_PRIVATE).getBoolean("subwoofer_enabled", true)) }
    var spectrumLevels by remember { mutableStateOf(List(28) { 0.04f }) }
    var spectrumEnabled by remember { mutableStateOf(false) }
    var spectrumAudioSessionId by remember { mutableIntStateOf(0) }
    var spectrumFrameReceived by remember { mutableStateOf(false) }
    var spectrumStatus by remember { mutableStateOf("Tekan Aktifkan untuk memulai spectrum analyzer.") }
    var hasAudioPermission by remember { mutableStateOf(context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) }
    val audioPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasAudioPermission = granted
        if (granted) {
            spectrumEnabled = true
            spectrumStatus = if (isPlaying) "Spectrum analyzer aktif." else "Izin aktif. Putar lagu untuk melihat gerakan spectrum."
        } else {
            spectrumStatus = "Izin mikrofon ditolak. Izinkan Audio di Pengaturan aplikasi."
        }
    }
    var equalizerLevels by remember { mutableStateOf(List(12) { 0f }) }
    var equalizerEnabled by remember {
        mutableStateOf(context.getSharedPreferences("musicai_player", android.content.Context.MODE_PRIVATE).getBoolean("equalizer_enabled", false))
    }
    var equalizerExpanded by remember { mutableStateOf(true) }
    var subwooferGain by remember { mutableFloatStateOf(context.getSharedPreferences("musicai_player", android.content.Context.MODE_PRIVATE).getFloat("subwoofer_gain", 0f)) }
    var subwooferFrequency by remember { mutableFloatStateOf(context.getSharedPreferences("musicai_player", android.content.Context.MODE_PRIVATE).getFloat("subwoofer_frequency", 80f)) }
    var importedFileName by remember { mutableStateOf("") }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var backendReachable by remember { mutableStateOf<Boolean?>(null) }
    var developerCode by remember { mutableStateOf("") }
    var developerUnlocked by remember { mutableStateOf(false) }
    var developerVerifying by remember { mutableStateOf(false) }
    var developerMessage by remember { mutableStateOf("") }
    var aiLogs by remember {
        val saved = context.getSharedPreferences("musicai_dev_logs", android.content.Context.MODE_PRIVATE).getString("entries", "[]")
        mutableStateOf(runCatching {
            val array = org.json.JSONArray(saved)
            List(array.length()) { index -> array.optString(index) }.filter { it.isNotBlank() }.takeLast(30)
        }.getOrDefault(emptyList()))
    }
    fun addAiLog(message: String) {
        val entry = "${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}  $message"
        activity.runOnUiThread {
            aiLogs = (aiLogs + entry).takeLast(30)
            val array = org.json.JSONArray()
            aiLogs.forEach { array.put(it) }
            context.getSharedPreferences("musicai_dev_logs", android.content.Context.MODE_PRIVATE)
                .edit().putString("entries", array.toString()).apply()
        }
    }
    var developerApiKey by remember { mutableStateOf(loadDeveloperApiKey(context)) }
    var developerLyriaApiKey by remember { mutableStateOf(loadDeveloperApiKey(context, "lyria")) }
    var developerStabilityApiKey by remember { mutableStateOf(loadDeveloperApiKey(context, "stability")) }
    var selectedProvider by remember { mutableStateOf(context.getSharedPreferences("musicai_settings", android.content.Context.MODE_PRIVATE).getString("provider", "musicapi") ?: "musicapi") }
    var selectedSunoModel by remember { mutableStateOf(context.getSharedPreferences("musicai_settings", android.content.Context.MODE_PRIVATE).getString("suno_model", "sonic-v6") ?: "sonic-v6") }
    var sunoModelMenuExpanded by remember { mutableStateOf(false) }
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

    DisposableEffect(spectrumAudioSessionId, isPlaying, hasAudioPermission, spectrumEnabled) {
        var visualizer: Visualizer? = null
        spectrumFrameReceived = false
        if (isPlaying && hasAudioPermission && spectrumEnabled && spectrumAudioSessionId > 0) {
            try {
                val effect = Visualizer(spectrumAudioSessionId)
                effect.captureSize = Visualizer.getCaptureSizeRange()[1]
                // Keep the real playback amplitude; NORMALIZED can flatten band-to-band dynamics.
                effect.scalingMode = Visualizer.SCALING_MODE_AS_PLAYED
                effect.setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(visualizer: Visualizer?, waveform: ByteArray?, samplingRate: Int) = Unit

                    override fun onFftDataCapture(visualizer: Visualizer?, fft: ByteArray?, samplingRate: Int) {
                        if (fft == null || fft.size < 16) return
                        // Android packs FFT as DC, Nyquist, then real/imaginary pairs.
                        val binCount = fft.size / 2
                        val levels = List(28) { index ->
                            val low = kotlin.math.exp(index * kotlin.math.ln((binCount - 1).toDouble()) / 28.0)
                                .toInt().coerceIn(1, binCount - 2)
                            val high = kotlin.math.exp((index + 1) * kotlin.math.ln((binCount - 1).toDouble()) / 28.0)
                                .toInt().coerceIn(low + 1, binCount - 1)
                            var energy = 0.0
                            var samples = 0
                            for (bin in low until high) {
                                val real = fft[bin * 2].toInt().toDouble()
                                val imaginary = fft[bin * 2 + 1].toInt().toDouble()
                                energy += real * real + imaginary * imaginary
                                samples++
                            }
                            val rms = if (samples > 0) kotlin.math.sqrt(energy / samples) else 0.0
                            val db = 20.0 * kotlin.math.log10(rms / 128.0 + 0.000001)
                            // A wider dynamic range keeps quiet bands visible while preserving musical movement.
                            Math.pow(((db + 72.0) / 72.0).coerceIn(0.0, 1.0), 0.62)
                                .toFloat().coerceIn(0.025f, 1f)
                        }
                        activity.runOnUiThread {
                            spectrumLevels = levels
                            if (!spectrumFrameReceived) {
                                spectrumFrameReceived = true
                                spectrumStatus = "Spectrum LIVE • dinamika audio terbaca."
                            }
                        }
                    }
                }, (Visualizer.getMaxCaptureRate() / 2).coerceAtLeast(8000), false, true)
                effect.enabled = true
                visualizer = effect
                spectrumStatus = "Visualizer tersambung. Membaca sinyal audio..."
            } catch (error: Exception) {
                spectrumStatus = "Visualizer gagal: ${error.javaClass.simpleName} • ${error.message ?: "akses audio ditolak"}"
                spectrumEnabled = false
                runCatching { visualizer?.release() }
                visualizer = null
            }
        } else if (spectrumEnabled && isPlaying && spectrumAudioSessionId <= 0) {
            spectrumStatus = "Audio session belum tersedia. Hentikan lalu putar ulang lagu."
        } else if (spectrumEnabled && !isPlaying) {
            spectrumStatus = "Spectrum siap. Putar lagu untuk membaca audio."
        }
        onDispose {
            runCatching { visualizer?.enabled = false }
            runCatching { visualizer?.release() }
            spectrumLevels = List(28) { 0.035f }
        }
    }

    LaunchedEffect(spectrumEnabled, isPlaying, spectrumAudioSessionId, hasAudioPermission) {
        if (spectrumEnabled && isPlaying && hasAudioPermission && spectrumAudioSessionId > 0) {
            delay(1800)
            if (!spectrumFrameReceived) {
                spectrumStatus = "Visualizer aktif tetapi belum menerima FFT. Coba hentikan dan putar ulang lagu."
            }
        }
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
                // OpenDocument URI segments may be numeric MediaStore IDs, not filenames.
                val documentName = runCatching {
                    context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(cursor.getColumnIndexOrThrow(android.provider.OpenableColumns.DISPLAY_NAME)) else null
                    }
                }.getOrNull()?.takeIf { it.isNotBlank() }
                val name = documentName
                    ?: uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':')?.takeIf { it.contains('.') && !it.all(Char::isDigit) }
                    ?: "Lagu lokal"
                val filenameTitle = name.substringAfterLast('/').substringBeforeLast('.', name).trim()
                    .takeIf { it.isNotBlank() && !it.all(Char::isDigit) }
                var importedTitle = filenameTitle ?: "Lagu lokal"
                var importedArtist: String? = null
                var importedDuration: Float? = null
                var coverName: String? = null
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context, uri)
                    val embeddedTitle = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                        ?.trim()?.takeIf { it.isNotBlank() && !it.all(Char::isDigit) }
                    if (embeddedTitle != null) importedTitle = embeddedTitle
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

    fun applyEqualizerSettings(eq: Equalizer) {
        val targetFrequencies = floatArrayOf(31f, 63f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 10000f, 12000f, 16000f)
        val hardwareCount = eq.numberOfBands.toInt()
        if (hardwareCount <= 0) return
        val hardwareFrequencies = (0 until hardwareCount).map { band ->
            (eq.getCenterFreq(band.toShort()) / 1000f).coerceAtLeast(1f)
        }
        val range = eq.bandLevelRange
        for (hardwareBand in 0 until hardwareCount) {
            val frequency = hardwareFrequencies[hardwareBand]
            val upper = targetFrequencies.indexOfFirst { it >= frequency }.let { if (it < 0) targetFrequencies.lastIndex else it }
            val lower = (upper - 1).coerceAtLeast(0)
            val lowHz = targetFrequencies[lower]
            val highHz = targetFrequencies[upper]
            val fraction = if (upper == lower || highHz == lowHz) 0f else
                (kotlin.math.ln(frequency / lowHz) / kotlin.math.ln(highHz / lowHz)).coerceIn(0f, 1f)
            val baseDb = equalizerLevels[lower] + (equalizerLevels[upper] - equalizerLevels[lower]) * fraction
            val bassDb = if (subwooferEnabled && frequency <= subwooferFrequency) subwooferGain else 0f
            val level = ((baseDb + bassDb) * 100f).toInt().coerceIn(range[0].toInt(), range[1].toInt())
            eq.setBandLevel(hardwareBand.toShort(), level.toShort())
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
        addAiLog("Mulai generasi • provider: ${selectedProvider.uppercase()} • judul: ${title.ifBlank { "Tanpa judul" }}")
        Thread {
            try {
                val json = JSONObject().apply {
                    put("provider", when { useStability -> "stability"; useLyria -> "lyria"; else -> "musicapi" })
                    put("sunoModel", selectedSunoModel)
                    put("title", title.trim())
                    put("lyrics", lyrics)
                    put("inputMode", "auto")
                    put("genre", (selectedGenres + listOf(customGenre.trim()).filter { it.isNotBlank() }).joinToString(", ").ifBlank { "Pop" })
                    put("vocal", vocal)
                    put("instrumental", vocal == "instrumental")
                    put("bpm", bpm.toInt())
                    if (!autoDuration) put("duration", durationSeconds)
                    put("durationMode", if (autoDuration) "auto" else "fixed")
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
                    append("Production quality: clean high-fidelity studio mix, clear lead vocal, controlled low end, balanced mids and highs, natural dynamics, distinct instrument separation, no clipping, no harsh distortion, no muddy or muffled sound. ")
                    append("Arrangement continuity: keep the same key, tempo, groove and core instrument palette from beginning to end. Do not introduce unrelated instruments or sudden genre changes. ")
                    append("Ending: build naturally into a final chorus, then resolve musically with a short instrumental outro and a clear final cadence or smooth fade. Do not cut off abruptly; make the last section feel intentional and connected to the song. ")
                    append("Use these original lyrics. Preserve the section labels and lyrics: ")
                    append(lyrics)
                }
                val stabilityPrompt = buildString {
                    append("Instrumental music only, no singing and no spoken words. ")
                    append("Title: ${title.ifBlank { "MusicAI instrumental" }}. ")
                    append("Style and genre: ${(selectedGenres + listOf(customGenre.trim()).filter { it.isNotBlank() }).joinToString(", ").ifBlank { "Pop" }}. ")
                    append("Mood: ${(selectedMoods + listOf(customMood.trim()).filter { it.isNotBlank() }).joinToString(", ").ifBlank { mood }}. ")
                    append("Tempo: ${bpm.toInt()} BPM. ")
                    append("Production: clean high-fidelity studio mix, balanced frequency spectrum, defined bass, clear drums, natural dynamics, separated instruments, no clipping, harsh distortion, muddiness or muffled highs. ")
                    append("Arrangement: maintain one coherent key, tempo, groove and instrument palette throughout; transitions must be smooth and musically motivated, with no random instrument changes. ")
                    append("Ending: resolve the final musical phrase naturally, then play a short connected instrumental outro with a clear final cadence or smooth fade. Never stop mid-phrase or end abruptly.")
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
                    addAiLog("Job MusicAPI dibuat • ID: $jobId")
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
                        if (completedTracks == null) {
                            addAiLog("Status job: ${lastStatus.uppercase()}")
                            activity.runOnUiThread { status = "Membuat lagu dengan MusicAPI... (${lastStatus.lowercase()})" }
                        }
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
                    addAiLog("SUKSES • ${downloaded.size} file audio diterima dan disimpan")
                    busy = false
                    page = AppPage.LIBRARY
                }
            } catch (e: Exception) {
                addAiLog("GAGAL • ${e.message ?: e.javaClass.simpleName}")
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
            equalizer?.release()
            equalizer = null
            bassBoost?.release()
            bassBoost = null
            player?.release()
            player = MediaPlayer().apply {
                setDataSource(temp.absolutePath)
                prepare()
                start()
                runCatching {
                    equalizer = Equalizer(0, audioSessionId).apply {
                        enabled = equalizerEnabled
                        applyEqualizerSettings(this)
                    }
                    bassBoost = BassBoost(0, audioSessionId).apply {
                        setStrength((subwooferGain / 6f * 500f).toInt().coerceIn(0, 500).toShort())
                        enabled = equalizerEnabled && subwooferEnabled && subwooferGain > 0f
                    }
                }
                isPlaying = true
                playbackDuration = duration.coerceAtLeast(0)
                playbackPosition = 0
                setOnCompletionListener {
                    isPlaying = false
                    if (selectedTrackIndex < generatedTracks.lastIndex) activity.runOnUiThread { selectedTrackIndex += 1; audioBytes = generatedTracks[selectedTrackIndex].audioBytes; title = generatedTracks[selectedTrackIndex].title; playbackPosition = 0; playAudio() }
                    else playbackPosition = playbackDuration
                }
            }
            spectrumAudioSessionId = player?.audioSessionId ?: 0
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
        equalizer?.release()
        equalizer = null
        bassBoost?.release()
        bassBoost = null
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
        background = bg, surface = Color(0xAA17213A), surfaceVariant = Color(0x77374461), primary = accent,
        secondary = Color(0xFF7DE3D1), onBackground = Color.White, onSurface = Color.White,
        onSurfaceVariant = Color(0xFFF0F2FF), outline = Color(0x99D7DFFF)
    )) {
        if (showGenreDialog) {
            AlertDialog(
                onDismissRequest = { showGenreDialog = false },
                containerColor = Color(0xCC171F35),
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
                        OutlinedTextField(value = customGenre, onValueChange = { customGenre = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Genre sendiri") }, placeholder = { Text("Contoh: ska reggae punk Sunda") }, singleLine = true, colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedLabelColor = Color(0xFFD9C8FF), unfocusedLabelColor = Color(0xFFC5CBE0), cursorColor = accent))
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
                containerColor = Color(0xEE171D32)
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
                containerColor = Color(0xEE171D32)
            )
        }
        androidx.compose.runtime.CompositionLocalProvider(LocalContentColor provides Color.White) {
        Scaffold(
            containerColor = Color.Transparent,
            bottomBar = {
                NavigationBar(containerColor = Color(0x661C2440), tonalElevation = 0.dp) {
                    NavigationBarItem(
                        selected = page == AppPage.STUDIO,
                        onClick = { page = AppPage.STUDIO },
                        icon = { Icon(Icons.Default.MusicNote, contentDescription = null, tint = Color.White) },
                        label = { Text("Studio", color = Color.White) }, alwaysShowLabel = true
                    )
                    NavigationBarItem(
                        selected = page == AppPage.LIBRARY,
                        onClick = { page = AppPage.LIBRARY },
                        icon = { Icon(Icons.Default.MusicNote, contentDescription = null, tint = Color.White) },
                        label = { Text("Pustaka", color = Color.White) }, alwaysShowLabel = true
                    )
                    NavigationBarItem(
                        selected = page == AppPage.SETTINGS,
                        onClick = { page = AppPage.SETTINGS },
                        icon = { Icon(Icons.Default.Settings, contentDescription = null, tint = Color.White) },
                        label = { Text("Pengaturan", color = Color.White) }, alwaysShowLabel = true
                    )
                }
            }
        ) { innerPadding ->
            Box(Modifier.fillMaxSize()) {
            // Ambient color blooms behind translucent surfaces create a deeper glass effect.
            Box(Modifier.fillMaxSize().background(Brush.linearGradient(
                        colorStops = arrayOf(
                            0.0f to Color(0xFF090B1A),
                            0.32f to Color(0xFF211A3D),
                            0.68f to Color(0xFF102F3B),
                            1.0f to Color(0xFF090D1D)
                        )
                    )))
            Box(
                Modifier.align(Alignment.TopEnd).padding(top = 90.dp, end = 4.dp)
                    .size(210.dp).blur(70.dp)
                    .background(Color(0x887D55FF), RoundedCornerShape(120.dp))
            )
            Box(
                Modifier.align(Alignment.CenterStart).padding(start = 0.dp, top = 240.dp)
                    .size(170.dp).blur(65.dp)
                    .background(Color(0x6670DCCB), RoundedCornerShape(100.dp))
            )
            Column(
                Modifier.fillMaxSize()
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
                        Text("MusicAI", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Text("CREATE YOUR MUSIC  •  AI MUSIC STUDIO", color = Color(0xFFBBA6D9), fontSize = 10.sp, letterSpacing = 1.2.sp)
                    }
                }

                when (page) {
                    AppPage.STUDIO -> {
                        Text("Studio kreatif", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                        Text("Wujudkan ide menjadi musik.", color = Color(0xFFB8B2C8), fontSize = 13.sp)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = !coverMode, onClick = { coverMode = false }, label = { Text("Lagu Baru", color = Color.White) }, leadingIcon = { Icon(Icons.Default.MusicNote, null, tint = Color.White) }, modifier = Modifier.weight(1f))
                            FilterChip(selected = coverMode, onClick = { coverMode = true; if (selectedProvider != "musicapi") { selectedProvider = "musicapi"; context.getSharedPreferences("musicai_settings", android.content.Context.MODE_PRIVATE).edit().putString("provider", "musicapi").apply() } }, label = { Text("Cover Lagu", color = Color.White) }, leadingIcon = { Icon(Icons.Default.Edit, null, tint = Color.White) }, modifier = Modifier.weight(1f))
                        }
                        Text("✦  AI GENERATOR", color = Color(0xFFE4D9FF), fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 1.sp)
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
                                modifier = Modifier.fillMaxWidth(0.88f).background(Color(0xCC1A2640))
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
                        if (!coverMode && selectedProvider == "musicapi") {
                            val sunoModels = listOf(
                                Triple("sonic-v6", "Suno v6 • Standar", "Model utama generasi terbaru"),
                                Triple("sonic-v6-wild", "Suno v6 Wild", "Eksperimental • hasil lebih berani"),
                                Triple("sonic-v6-mini", "Suno v6 Mini", "Lebih ringan dan cepat"),
                                Triple("sonic-v5-5", "Suno v5.5 (Legacy)", "ID lama • saat ini dipetakan ke v6"),
                                Triple("sonic-v5", "Suno v5 (Legacy)", "ID lama • saat ini dipetakan ke v6"),
                                Triple("sonic-v4-5-plus", "Suno v4.5+ (Legacy)", "ID lama • saat ini dipetakan ke v6"),
                                Triple("sonic-v4-5", "Suno v4.5 (Legacy)", "ID lama • saat ini dipetakan ke v6"),
                                Triple("sonic-v4", "Suno v4 (Legacy)", "ID lama • saat ini dipetakan ke v6"),
                                Triple("sonic-v3-5", "Suno v3.5 (Legacy)", "ID lama • saat ini dipetakan ke v6")
                            )
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("MODEL SUNO", color = Color(0xFFD6C7FF), fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                Box {
                                    OutlinedButton(
                                        onClick = { sunoModelMenuExpanded = true },
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(14.dp),
                                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)
                                    ) {
                                        Text(sunoModels.firstOrNull { it.first == selectedSunoModel }?.second ?: "Suno v6 • Standar", modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Start)
                                        Text("⌄", fontSize = 18.sp)
                                    }
                                    DropdownMenu(
                                        expanded = sunoModelMenuExpanded,
                                        onDismissRequest = { sunoModelMenuExpanded = false },
                                        modifier = Modifier.fillMaxWidth(0.9f).background(Color(0xF01A2640))
                                    ) {
                                        sunoModels.forEach { (modelId, modelName, modelDescription) ->
                                            DropdownMenuItem(
                                                text = {
                                                    Column {
                                                        Text(modelName, fontWeight = FontWeight.SemiBold)
                                                        Text(modelDescription, color = Color.LightGray, fontSize = 10.sp)
                                                    }
                                                },
                                                onClick = {
                                                    selectedSunoModel = modelId
                                                    sunoModelMenuExpanded = false
                                                    context.getSharedPreferences("musicai_settings", android.content.Context.MODE_PRIVATE)
                                                        .edit().putString("suno_model", modelId).apply()
                                                },
                                                trailingIcon = { if (selectedSunoModel == modelId) Text("✓", color = Color(0xFF70D7A0)) }
                                            )
                                        }
                                    }
                                }
                                Text("Catatan: pilihan v5 dan versi lebih lama tetap tersedia untuk kompatibilitas, tetapi MusicAPI menyatakan versi tersebut sekarang dijalankan menggunakan v6.", color = Color.LightGray, fontSize = 10.sp)
                            }
                        }
                        if (coverMode) {
                            Surface(color = Color(0x444C6385), shape = RoundedCornerShape(18.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x99D7E7FF)), modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text("♫  AUDIO SUMBER", color = Color(0xFFE4D9FF), fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                    Text(if (coverSourceName.isBlank()) "Belum ada lagu dipilih" else "$coverSourceName • ${"%.1f".format(coverSourceDuration)} detik • ${coverSourceBytes?.size?.div(1024) ?: 0} KB", color = Color.White, fontSize = 13.sp)
                                    OutlinedButton(onClick = { coverAudioLauncher.launch(arrayOf("audio/*")) }, modifier = Modifier.fillMaxWidth()) { Text(if (coverSourceBytes == null) "Pilih lagu dari HP" else "Ganti lagu") }
                                    Text("MP3/WAV/M4A • 6 detik–8 menit • maksimal 4 MB", color = Color.LightGray, fontSize = 11.sp)
                                }
                            }
                            OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Judul cover") }, modifier = Modifier.fillMaxWidth(), singleLine = true, colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedLabelColor = Color(0xFFD9C8FF), unfocusedLabelColor = Color(0xFFC5CBE0), cursorColor = accent))
                        } else {
                            OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Judul lagu (opsional)") }, modifier = Modifier.fillMaxWidth(), singleLine = true, colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedLabelColor = Color(0xFFD9C8FF), unfocusedLabelColor = Color(0xFFC5CBE0), cursorColor = accent))
                        }

                        if (!coverMode) Surface(color = Color(0x66333B5C), shape = RoundedCornerShape(26.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x88D7C8FF)), modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("✎  IDE & LIRIK", color = Color(0xFFE4D9FF), fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 1.5.sp)
                            OutlinedTextField(
                                value = lyrics, onValueChange = { lyrics = it },
                                label = { Text("Lirik atau ide lagu") },
                                modifier = Modifier.fillMaxWidth().height(240.dp),
                                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedLabelColor = Color(0xFFD9C8FF), unfocusedLabelColor = Color(0xFFC5CBE0), cursorColor = accent, focusedPlaceholderColor = Color(0xFFB8C0D8), unfocusedPlaceholderColor = Color(0xFF9DA8C5), focusedContainerColor = Color(0x553B4968), unfocusedContainerColor = Color(0x333C4B68), focusedBorderColor = Color(0xFFB69BFF), unfocusedBorderColor = Color(0x887F8AAE)),
                                placeholder = { Text("Tulis lirik lengkap atau cukup satu ide...\n\nContoh: Buatkan lagu tema mistis tentang desa tua di pegunungan.\n\n") }
                            )
                            }
                        }

                        Surface(color = Color(0x44516A88), shape = RoundedCornerShape(22.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x99C8E7FF)), modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text("♫  GENRE MUSIK", color = Color(0xFFE4D9FF), fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 1.2.sp)
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

                        if (!coverMode) Text("Vokal", fontWeight = FontWeight.SemiBold)
                        if (!coverMode) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("male" to "Pria", "female" to "Wanita", "duet" to "Duet", "instrumental" to "Instrumen").forEach { (value, label) ->
                                FilterChip(
                                    selected = vocal == value,
                                    onClick = { if (selectedProvider != "stability") vocal = value },
                                    enabled = selectedProvider != "stability" || value == "instrumental",
                                    label = { Text(label) }
                                )
                            }
                        }
                        if (!coverMode) Surface(color = panel, shape = RoundedCornerShape(16.dp), border = androidx.compose.foundation.BorderStroke(1.dp, glassBorder), modifier = Modifier.fillMaxWidth()) {
                            Text(
                                if (selectedProvider == "stability") "Stability hanya mendukung musik instrumental. Pilihan vokal dikunci ke Instrumen selama provider ini aktif." else "Pilih Pria, Wanita, Duet Pria + Wanita, atau Instrumen. Mode duet mengarahkan AI membuat vokal bergantian dan harmoni bersama.",
                                color = Color.LightGray,
                                fontSize = 13.sp,
                                modifier = Modifier.padding(12.dp)
                            )
                        }

                        if (!coverMode) Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Tempo", fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.weight(1f))
                            Text("${bpm.toInt()} BPM", color = accent, fontWeight = FontWeight.Bold)
                        }
                        if (!coverMode) Slider(value = bpm, onValueChange = { bpm = it }, valueRange = 50f..200f)
                        if (!coverMode) Text("Durasi musik", fontWeight = FontWeight.SemiBold)
                        if (!coverMode) Text(when { selectedProvider == "stability" -> "Stability Stable Audio 2.5: instrumental hingga 3 menit."; selectedProvider == "lyria" -> "Lyria 3.5: durasi hingga sekitar 3 menit."; else -> "Durasi maksimal Suno / MusicAPI: 6 menit." }, color = Color.LightGray, fontSize = 11.sp)
                        var durationExpanded by remember { mutableStateOf(false) }
                        if (!coverMode) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                FilterChip(selected = !autoDuration, onClick = { autoDuration = false }, label = { Text("Atur sendiri") })
                                FilterChip(selected = autoDuration, onClick = { autoDuration = true }, label = { Text("Terserah AI") })
                            }
                            if (!autoDuration) Box {
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
                            } else Text("AI akan menentukan durasi yang paling sesuai dengan materi lagu.", color = Color.LightGray, fontSize = 11.sp)
                        }

                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { Icon(Icons.Default.Equalizer, null, tint = accent); Text(if (coverMode) "ARAHAN GAYA COVER" else "SUASANA / MOOD", fontWeight = FontWeight.SemiBold, color = Color.White) }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            moodOptions.forEach { option ->
                                FilterChip(
                                    selected = option in selectedMoods,
                                    onClick = { selectedMoods = if (option in selectedMoods) selectedMoods - option else selectedMoods + option },
                                    label = { Text(option, color = if (option in selectedMoods) Color.White else Color(0xFFE3E7F5)) },
                                    leadingIcon = { Icon(Icons.Default.MusicNote, null, tint = if (option in selectedMoods) accent else Color(0xFFB9C3DC), modifier = Modifier.size(14.dp)) },
                                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Color(0x995E4A91), containerColor = Color(0x55323D5B), labelColor = Color(0xFFE3E7F5), iconColor = Color(0xFFB9C3DC), selectedLabelColor = Color.White, selectedLeadingIconColor = accent)
                                )
                            }
                        }
                        OutlinedTextField(value = customMood, onValueChange = { customMood = it }, label = { Text("Arahan manual (opsional)") }, placeholder = { Text("Contoh: slap bass dominan, brass ceria, vokal serak...") }, modifier = Modifier.fillMaxWidth(), colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedLabelColor = Color(0xFFD9C8FF), unfocusedLabelColor = Color(0xFFC5CBE0), cursorColor = accent, focusedContainerColor = Color(0x55424C70), unfocusedContainerColor = Color(0x33424C70)))

                        if (coverMode) {
                            Text("Pengaruh lagu asli: ${(coverAudioWeight * 100).toInt()}%", color = accent, fontWeight = FontWeight.SemiBold)
                            Slider(value = coverAudioWeight, onValueChange = { coverAudioWeight = it }, valueRange = 0f..1f)
                            Text("Rendah = lebih bebas diaransemen ulang • Tinggi = lebih dekat ke lagu asli", color = Color.LightGray, fontSize = 11.sp)
                        }
                        Button(
                            onClick = { if (coverMode) generateCover() else if (selectedProvider == "lyria") showLyriaCostConfirm = true else generateSong() },
                            enabled = !busy && if (coverMode) coverSourceBytes != null else lyrics.isNotBlank(),
                            modifier = Modifier.fillMaxWidth().height(54.dp),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            if (busy) CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                            else Text(if (coverMode) "BUAT COVER LAGU" else "CREATE YOUR MUSIC", fontWeight = FontWeight.Bold)
                        }
                        val aiTracks = generatedTracks.withIndex().filter { it.value.isAiGenerated }
                        if (aiTracks.isNotEmpty()) {
                            Text("HASIL AI TERBARU", color = Color(0xFFD6C7FF), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            aiTracks.takeLast(3).asReversed().forEach { indexedTrack ->
                                val trackIndex = indexedTrack.index
                                val track = indexedTrack.value
                                Surface(color = panel, shape = RoundedCornerShape(18.dp), border = androidx.compose.foundation.BorderStroke(1.dp, glassBorder), modifier = Modifier.fillMaxWidth(), onClick = { selectTrack(trackIndex, false); page = AppPage.PLAYER }) {
                                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        if (track.imageUrl != null) {
                                            AsyncImage(model = track.imageUrl, contentDescription = "Cover ${track.title}", modifier = Modifier.size(58.dp), contentScale = ContentScale.Crop)
                                        } else {
                                            Surface(color = Color(0x88434A68), shape = RoundedCornerShape(10.dp), modifier = Modifier.size(58.dp)) {
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
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) { Icon(Icons.Default.MusicNote, null, tint = accent, modifier = Modifier.size(28.dp)); Text("Pustaka musik", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White) }
                        Text("Semua lagu buatan MusicAI dan audio lokal yang kamu impor.", color = Color.LightGray, fontSize = 13.sp)
                        OutlinedButton(onClick = { importAudioLauncher.launch(arrayOf("audio/*")) }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.Download, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Tambah lagu dari perangkat")
                        }
                        Text("KOLEKSI • ${generatedTracks.size} LAGU", color = Color(0xFFD6C7FF), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        if (generatedTracks.isEmpty()) {
                            Surface(color = panel, shape = RoundedCornerShape(22.dp), border = androidx.compose.foundation.BorderStroke(1.dp, glassBorder), modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(Icons.Default.MusicNote, null, tint = accent, modifier = Modifier.size(42.dp))
                                    Text("Pustaka masih kosong", fontWeight = FontWeight.Bold)
                                    Text("Buat lagu di Studio atau tambahkan audio dari perangkat.", color = Color.LightGray, fontSize = 12.sp)
                                }
                            }
                        }
                        generatedTracks.forEachIndexed { index, track ->
                            var menuExpanded by remember(track.fileName) { mutableStateOf(false) }
                            Surface(color = if (selectedTrackIndex == index) Color(0x99543E78) else panel, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth(), onClick = {
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
                            IconButton(onClick = { page = AppPage.EQUALIZER }) { Icon(Icons.Default.Equalizer, "Buka equalizer", tint = accent) }
                            TextButton(onClick = { if (selectedTrackIndex in generatedTracks.indices) trackToDelete = selectedTrackIndex }) {
                                Icon(Icons.Default.Delete, null)
                                Text("Hapus")
                            }
                        }
                        val currentTrack = generatedTracks.getOrNull(selectedTrackIndex)
                        Text("SEDANG DIPUTAR", color = Color(0xFFD6C7FF), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                        Surface(color = Color(0x88313B5B), shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
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
                                    Surface(color = Color(0x885B4A80), shape = RoundedCornerShape(50)) {
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
                        Surface(color = Color(0x66313D5B), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("LIVE AUDIO SPECTRUM", color = Color(0xFFD6C7FF), fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                    Text(if (isPlaying && spectrumEnabled && hasAudioPermission) "LIVE" else "STANDBY", color = if (isPlaying && spectrumEnabled && hasAudioPermission) Color(0xFF81D9A7) else Color.LightGray, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                }
                                androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(66.dp)) {
                                    val gap = 4.dp.toPx()
                                    val count = spectrumLevels.size
                                    val barWidth = ((size.width - gap * (count - 1)) / count).coerceAtLeast(1f)
                                    spectrumLevels.forEachIndexed { index, level ->
                                        val barHeight = (size.height * level.coerceIn(0.04f, 1f)).coerceAtLeast(3.dp.toPx())
                                        drawRoundRect(
                                            brush = Brush.verticalGradient(listOf(Color(0xFF77E6D1), Color(0xFF9A7BFF), Color(0xFF463A80))),
                                            topLeft = Offset(index * (barWidth + gap), size.height - barHeight),
                                            size = androidx.compose.ui.geometry.Size(barWidth, barHeight),
                                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth / 2f)
                                        )
                                    }
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(spectrumStatus, color = Color.LightGray, fontSize = 10.sp, modifier = Modifier.weight(1f))
                                    if (!spectrumEnabled || !hasAudioPermission) {
                                        TextButton(onClick = {
                                            if (!hasAudioPermission) audioPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                                            else {
                                                spectrumEnabled = true
                                                spectrumStatus = if (isPlaying) "Mengaktifkan spectrum..." else "Siap. Putar lagu untuk menampilkan sinyal audio."
                                            }
                                        }) { Text(if (!hasAudioPermission) "Izinkan & Aktifkan" else "Aktifkan") }
                                    } else {
                                        TextButton(onClick = { spectrumEnabled = false; spectrumStatus = "Spectrum analyzer dimatikan." }) { Text("Matikan") }
                                    }
                                }
                            }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Surface(color = Color(0x553E4C70), shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f)) {
                                Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                                    Text("FORMAT", color = Color(0xFFBFB5D2), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                    Text(if (audioMimeType.contains("wav")) "WAV" else "MP3 / AUDIO", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                            Surface(color = Color(0x553E4C70), shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f)) {
                                Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                                    Text("EQUALIZER", color = Color(0xFFBFB5D2), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                    Text(if (equalizerEnabled) "AKTIF" else "NONAKTIF", color = if (equalizerEnabled) Color(0xFF81D9A7) else Color.LightGray, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                            Surface(color = Color(0x553E4C70), shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f)) {
                                Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                                    Text("UKURAN", color = Color(0xFFBFB5D2), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                    Text(audioBytes?.let { bytes -> if (bytes.size >= 1024 * 1024) String.format("%.1f MB", bytes.size / 1048576f) else "${bytes.size / 1024} KB" } ?: "—", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                        Text(status, color = Color.LightGray, fontSize = 12.sp)
                    }

                    AppPage.EQUALIZER -> {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { page = AppPage.PLAYER }) { Text("‹  Pemutar") }
                            Spacer(Modifier.weight(1f))
                            Text("EQUALIZER", fontWeight = FontWeight.Bold, color = Color.White)
                        }
                        Surface(color = Color(0x66313D5B), shape = RoundedCornerShape(24.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x99D8C7FF)), modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text("12-BAND EQUALIZER", fontWeight = FontWeight.Bold, color = Color.White)
                                        Text("Atur karakter suara sesuai selera", color = Color.LightGray, fontSize = 11.sp)
                                    }
                                    Text(if (equalizerEnabled) "ON" else "OFF", color = if (equalizerEnabled) Color(0xFF81D9A7) else Color.LightGray, fontWeight = FontWeight.Bold)
                                    Switch(checked = equalizerEnabled, onCheckedChange = { enabled ->
                                        equalizerEnabled = enabled
                                        context.getSharedPreferences("musicai_player", android.content.Context.MODE_PRIVATE).edit().putBoolean("equalizer_enabled", enabled).apply()
                                        runCatching { equalizer?.enabled = enabled }
                                        runCatching { bassBoost?.enabled = enabled && subwooferEnabled && subwooferGain > 0f }
                                    })
                                }
                                Divider(color = Color(0x66D8C7FF))
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    listOf("Flat", "Bass", "Vocal", "Rock").forEach { preset ->
                                        FilterChip(selected = false, onClick = {
                                            equalizerLevels = when (preset) {
                                                "Bass" -> listOf(8f, 7f, 5f, 2f, 0f, -1f, -2f, -1f, 1f, 2f, 2f, 2f)
                                                "Vocal" -> listOf(-3f, -2f, 0f, 3f, 5f, 5f, 3f, 1f, 0f, 0f, -1f, -2f)
                                                "Rock" -> listOf(5f, 3f, -1f, -3f, -1f, 2f, 4f, 5f, 3f, 2f, 3f, 4f)
                                                else -> List(12) { 0f }
                                            }
                                            if (equalizerEnabled) runCatching { equalizer?.let { applyEqualizerSettings(it) } }
                                        }, label = { Text(preset, fontSize = 10.sp) }, enabled = equalizerEnabled)
                                    }
                                }
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text("SUBWOOFER", color = Color(0xFFE2D7FF), fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.weight(1f))
                                    Text(if (subwooferEnabled) "ON" else "OFF", color = if (subwooferEnabled) Color(0xFF81D9A7) else Color.LightGray, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                    Switch(checked = subwooferEnabled, onCheckedChange = { enabled ->
                                        subwooferEnabled = enabled
                                        context.getSharedPreferences("musicai_player", android.content.Context.MODE_PRIVATE).edit().putBoolean("subwoofer_enabled", enabled).apply()
                                        runCatching {
                                            equalizer?.let { applyEqualizerSettings(it) }
                                            bassBoost?.enabled = equalizerEnabled && enabled && subwooferGain > 0f
                                        }
                                    }, enabled = equalizerEnabled)
                                }
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                                    RotaryKnob(value = subwooferGain, valueRange = 0f..6f, label = "LEVEL", display = "+${subwooferGain.toInt()} dB", enabled = equalizerEnabled && subwooferEnabled, onValueChange = {
                                        subwooferGain = it
                                        context.getSharedPreferences("musicai_player", android.content.Context.MODE_PRIVATE).edit().putFloat("subwoofer_gain", it).apply()
                                        if (equalizerEnabled) runCatching {
                                            equalizer?.let { eq -> applyEqualizerSettings(eq) }
                                            bassBoost?.apply {
                                                setStrength((subwooferGain / 6f * 500f).toInt().coerceIn(0, 500).toShort())
                                                enabled = subwooferEnabled && subwooferGain > 0f
                                            }
                                        }
                                    })
                                    RotaryKnob(value = subwooferFrequency, valueRange = 40f..160f, label = "FREQ", display = "${subwooferFrequency.toInt()} Hz", enabled = equalizerEnabled && subwooferEnabled, onValueChange = {
                                        subwooferFrequency = it
                                        context.getSharedPreferences("musicai_player", android.content.Context.MODE_PRIVATE).edit().putFloat("subwoofer_frequency", it).apply()
                                        if (equalizerEnabled) runCatching { equalizer?.let { eq -> applyEqualizerSettings(eq) } }
                                    })
                                }
                                Text("Level menambah bass pada band rendah. Frekuensi menentukan batas band yang ikut diperkuat; hasil akhirnya mengikuti kemampuan equalizer hardware HP.", color = Color.LightGray, fontSize = 11.sp)
                                Divider(color = Color(0x66D8C7FF))
                                Text("12-BAND • GESER HORIZONTAL", color = Color(0xFFE2D7FF), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                Text("12 band • batang vertikal • rentang ±12 dB. Geser deretan kartu ke samping.", color = Color.LightGray, fontSize = 11.sp)
                                val labels = listOf("31 Hz", "63 Hz", "125 Hz", "250 Hz", "500 Hz", "1 kHz", "2 kHz", "4 kHz", "8 kHz", "10 kHz", "12 kHz", "16 kHz")
                                Row(
                                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    labels.forEachIndexed { index, label ->
                                        Surface(
                                            color = Color(0x553E4C70),
                                            shape = RoundedCornerShape(14.dp),
                                            modifier = Modifier.width(82.dp).height(218.dp)
                                        ) {
                                            Column(
                                                Modifier.padding(horizontal = 6.dp, vertical = 10.dp),
                                                horizontalAlignment = Alignment.CenterHorizontally,
                                                verticalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Text(label, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                                Text(
                                                    if (equalizerLevels[index] > 0f) "+${equalizerLevels[index].toInt()}" else equalizerLevels[index].toInt().toString(),
                                                    color = Color(0xFFD9CCFF), fontSize = 11.sp, fontWeight = FontWeight.Bold
                                                )
                                                Box(
                                                    Modifier.width(48.dp).height(132.dp),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Slider(
                                                        value = equalizerLevels[index],
                                                        onValueChange = { value ->
                                                            equalizerLevels = equalizerLevels.toMutableList().also { it[index] = value }
                                                            if (equalizerEnabled) runCatching { equalizer?.let { eq -> applyEqualizerSettings(eq) } }
                                                        },
                                                        valueRange = -12f..12f,
                                                        enabled = equalizerEnabled,
                                                        modifier = Modifier.width(128.dp).height(36.dp).rotate(-90f)
                                                    )
                                                }
                                                Text("−12 / +12", color = Color.Gray, fontSize = 8.sp)
                                            }
                                        }
                                    }
                                }
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Text("Perubahan diterapkan langsung saat audio diputar.", color = Color.Gray, fontSize = 10.sp, modifier = Modifier.weight(1f))
                                    TextButton(onClick = {
                                        equalizerLevels = List(12) { 0f }
                                        subwooferGain = 0f
                                        subwooferFrequency = 80f
                                        context.getSharedPreferences("musicai_player", android.content.Context.MODE_PRIVATE).edit().putFloat("subwoofer_gain", 0f).putFloat("subwoofer_frequency", 80f).apply()
                                        runCatching { equalizer?.let { applyEqualizerSettings(it) } }
                                    }) { Text("Reset") }
                                }
                            }
                        }
                    }

                    AppPage.SETTINGS -> {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) { Icon(Icons.Default.Settings, null, tint = accent, modifier = Modifier.size(26.dp)); Text("Pengaturan", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White) }
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
                                Surface(color = Color(0x55313D5B), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Default.Equalizer, null, tint = accent, modifier = Modifier.size(18.dp))
                                            Spacer(Modifier.width(8.dp))
                                            Text("LOG AI / AKTIVITAS GENERASI", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.weight(1f))
                                            TextButton(onClick = {
                                                aiLogs = emptyList()
                                                context.getSharedPreferences("musicai_dev_logs", android.content.Context.MODE_PRIVATE).edit().remove("entries").apply()
                                            }, enabled = aiLogs.isNotEmpty()) { Text("Bersihkan", fontSize = 11.sp) }
                                        }
                                        Text("Catatan proses request, status job, hasil, dan error. Tidak menyimpan API key atau isi kredensial.", color = Color.LightGray, fontSize = 10.sp)
                                        if (aiLogs.isEmpty()) {
                                            Text("Belum ada aktivitas AI.", color = Color.Gray, fontSize = 11.sp)
                                        } else {
                                            Column(
                                                Modifier.fillMaxWidth().heightIn(max = 220.dp).verticalScroll(rememberScrollState()),
                                                verticalArrangement = Arrangement.spacedBy(5.dp)
                                            ) {
                                                aiLogs.asReversed().forEach { entry ->
                                                    Text(entry, color = if (entry.contains("GAGAL")) Color(0xFFFF9A9A) else if (entry.contains("SUKSES")) Color(0xFF91D9A8) else Color(0xFFD8DDF0), fontSize = 10.sp)
                                                }
                                            }
                                        }
                                    }
                                }
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
                                Divider(color = Color(0x887F8AAE))
                                Divider(color = Color(0x887F8AAE))
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
                                Divider(color = Color(0x887F8AAE))
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
                                Divider(color = Color(0x887F8AAE))
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
}
}
