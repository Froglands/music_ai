import express from "express";
import cors from "cors";
import rateLimit from "express-rate-limit";
import { timingSafeEqual } from "node:crypto";

const app = express();
const port = Number(process.env.PORT || 3000);
const apiKey = process.env.MUSICAPI_API_KEY;
const lyriaApiKey = process.env.GEMINI_API_KEY;
const devAccessCode = process.env.MUSICAI_DEV_ACCESS_CODE;

app.set("trust proxy", 1);
app.use(cors({ origin: process.env.ALLOWED_ORIGIN || "*", methods: ["GET", "POST"], allowedHeaders: ["Content-Type", "X-MusicAI-Dev-Code", "X-MusicAPI-Key", "X-Lyria-API-Key"] }));
app.use(express.json({ limit: "80kb" }));
const generationLimiter = rateLimit({ windowMs: 60_000, limit: 3, standardHeaders: "draft-7", legacyHeaders: false });

app.get("/health", (_req, res) => {
  res.json({ ok: true, service: "musicai-backend", providers: ["musicapi-sonic", "google-lyria-3.5"] });
});

const devVerifyLimiter = rateLimit({ windowMs: 60_000, limit: 5, standardHeaders: "draft-7", legacyHeaders: false });
app.post("/api/dev/verify", devVerifyLimiter, (req, res) => {
  if (!devAccessCode) return res.status(503).json({ authorized: false, error: "Developer access is not configured." });
  const submitted = typeof req.body?.code === "string" ? req.body.code : "";
  const expected = Buffer.from(devAccessCode);
  const received = Buffer.from(submitted);
  const authorized = expected.length === received.length && timingSafeEqual(expected, received);
  return res.status(authorized ? 200 : 401).json({ authorized });
});


function requestProviderKey(req) {
  const override = typeof req.get("X-MusicAPI-Key") === "string" ? req.get("X-MusicAPI-Key").trim() : "";
  if (!override) return apiKey;
  if (!devAccessCode) return null;
  const submitted = Buffer.from(req.get("X-MusicAI-Dev-Code") || "");
  const expected = Buffer.from(devAccessCode);
  if (submitted.length !== expected.length || !timingSafeEqual(submitted, expected)) return null;
  return override.slice(0, 512);
}

function cleanString(value, max) {
  return typeof value === "string" ? value.trim().slice(0, max) : "";
}

function providerError(payload, fallback) {
  return payload?.error?.message || payload?.error || payload?.message || fallback;
}

function requestLyriaKey(req) {
  const override = typeof req.get("X-Lyria-API-Key") === "string" ? req.get("X-Lyria-API-Key").trim() : "";
  if (!override) return lyriaApiKey;
  if (!devAccessCode) return null;
  const submitted = Buffer.from(req.get("X-MusicAI-Dev-Code") || "");
  const expected = Buffer.from(devAccessCode);
  if (submitted.length !== expected.length || !timingSafeEqual(submitted, expected)) return null;
  return override.slice(0, 512);
}

async function generateLyria(req, res) {
  const activeKey = requestLyriaKey(req);
  if (!activeKey) return res.status(req.get("X-Lyria-API-Key") ? 401 : 503).json({
    error: req.get("X-Lyria-API-Key") ? "Akses pengembang tidak valid untuk API key Lyria." : "Atur GEMINI_API_KEY di Vercel atau simpan API key Lyria di menu Pengembang."
  });

  const title = cleanString(req.body?.title, 80) || "MusicAI";
  const lyrics = cleanString(req.body?.lyrics, 5000);
  const genre = cleanString(req.body?.genre, 160) || "Pop";
  const mood = cleanString(req.body?.mood, 200) || "uplifting";
  const bpm = Number(req.body?.bpm || 110);
  const duration = Math.max(60, Math.min(180, Math.round(Number(req.body?.duration || 120))));
  const vocal = req.body?.vocal || "female";
  if (!Number.isFinite(bpm) || bpm < 50 || bpm > 200) return res.status(400).json({ error: "Tempo harus antara 50 dan 200 BPM." });
  if (!lyrics || lyrics.length < 10) return res.status(400).json({ error: "Masukkan lirik atau ide lagu minimal 10 karakter." });

  const vocalText = vocal === "instrumental" ? "Instrumental only, no singing and no spoken words." :
    vocal === "male" ? "Male lead singing voice." :
    vocal === "duet" ? "Male and female duet vocals, alternating verses and harmonized chorus." :
    "Female lead singing voice.";
  const prompt = [
    `Create an original full-length song, approximately ${duration} seconds (${Math.floor(duration / 60)} minutes and ${duration % 60} seconds).`,
    `Title: ${title}.`,
    `Genre and arrangement: ${genre}.`,
    `Mood: ${mood}.`,
    `Tempo: ${bpm} BPM.`,
    vocalText,
    "Use the following original lyrics exactly as the song lyrics. Preserve section labels and do not omit verses:",
    lyrics
  ].join("\\n\\n");

  try {
    const response = await fetch("https://generativelanguage.googleapis.com/v1beta/interactions", {
      method: "POST",
      headers: { "x-goog-api-key": activeKey, "Content-Type": "application/json" },
      body: JSON.stringify({ model: "lyria-3.5", input: prompt, store: false }),
      signal: AbortSignal.timeout(540_000)
    });
    const payload = await response.json().catch(() => ({}));
    if (!response.ok) {
      console.error("Lyria generation error:", response.status, JSON.stringify(payload).slice(0, 1000));
      const message = providerError(payload, "Google Lyria gagal membuat lagu.");
      return res.status(response.status === 429 ? 429 : response.status === 401 || response.status === 403 ? 401 : response.status >= 500 ? 502 : 400)
        .json({ error: message });
    }

    let audioData = payload.output_audio?.data || null;
    let outputText = payload.output_text || "";
    if (!audioData && Array.isArray(payload.steps)) {
      for (const step of payload.steps) {
        if (step.type !== "model_output" || !Array.isArray(step.content)) continue;
        for (const block of step.content) {
          if (block.type === "audio" && block.data) audioData = block.data;
          if (block.type === "text" && block.text) outputText += (outputText ? "\\n" : "") + block.text;
        }
      }
    }
    if (!audioData) return res.status(502).json({ error: "Lyria merespons tetapi data audio tidak ditemukan." });
    return res.json({
      provider: "lyria-3.5",
      status: "completed",
      tracks: [{ title, audioBase64: audioData, mimeType: "audio/mpeg", duration, lyrics: outputText }]
    });
  } catch (error) {
    console.error("Lyria request failed:", error);
    return res.status(504).json({ error: "Permintaan ke Lyria timeout atau koneksi terputus. Periksa status sebelum mengulang agar tidak terkena biaya dua kali." });
  }
}

app.post("/api/generate", generationLimiter, async (req, res) => {
  if (req.body?.provider === "lyria") return generateLyria(req, res);
  const activeApiKey = requestProviderKey(req);
  if (!activeApiKey) return res.status(req.get("X-MusicAPI-Key") ? 401 : 503).json({ error: req.get("X-MusicAPI-Key") ? "Akses pengembang tidak valid untuk API key pengganti." : "Backend belum dikonfigurasi: MUSICAPI_API_KEY belum diatur di Vercel." });

  const title = cleanString(req.body?.title, 80);
  const lyrics = cleanString(req.body?.lyrics, 5000);
  const genre = cleanString(req.body?.genre, 100) || "Pop";
  const mood = cleanString(req.body?.mood, 200) || "uplifting and melodic";
  const instrumental = req.body?.instrumental === true || req.body?.vocal === "instrumental";
  const hasLyricsStructure = /\[(verse|chorus|bridge|intro|outro|pre-chorus)/i.test(lyrics) || lyrics.split(/\r?\n/).filter(line => line.trim()).length >= 5;
  const inputMode = req.body?.inputMode === "prompt" || req.body?.inputMode === "auto" && !hasLyricsStructure ? "prompt" : "lyrics";
  const vocal = req.body?.vocal === "male" ? "m" : req.body?.vocal === "female" ? "f" : null;
  const duration = Math.max(10, Math.min(360, Math.round(Number(req.body?.duration || 180))));
  const bpm = Number(req.body?.bpm || 110);

  if (!instrumental && (!lyrics || lyrics.length < 10)) return res.status(400).json({ error: inputMode === "prompt" ? "Tulis ide lagu minimal 10 karakter." : "Masukkan lirik minimal 10 karakter (maksimal 5.000)." });
  if (!Number.isFinite(bpm) || bpm < 50 || bpm > 200) return res.status(400).json({ error: "Tempo harus antara 50 dan 200 BPM." });

  const vocalDirection = req.body?.vocal === "duet" ? "male and female duet vocals, alternating male and female verses, harmonized duet chorus" : req.body?.vocal === "male" ? "male lead vocal" : req.body?.vocal === "female" ? "female lead vocal" : instrumental ? "instrumental, no vocals" : "vocal song";
  const tags = [genre, mood, `${bpm} BPM`, vocalDirection].join(", ");
  try {
    const response = await fetch("https://api.musicapi.ai/api/v1/sonic/create", {
      method: "POST",
      headers: { Authorization: `Bearer ${activeApiKey}`, "Content-Type": "application/json" },
      body: JSON.stringify({
        task_type: "create_music",
        custom_mode: !instrumental,
        mv: "sonic-v5-5",
        ...(title ? { title } : {}),
        ...(instrumental
          ? { make_instrumental: true, custom_mode: false, gpt_description_prompt: "Instrumental music, no vocals, no lyrics. " + lyrics + ". " + tags }
          : inputMode === "prompt"
            ? { auto_lyrics: true, prompt: lyrics, vocal_gender: vocal }
            : { prompt: lyrics, ...(vocal ? { vocal_gender: vocal } : {}) }),
        tags,
        duration
      }),
      signal: AbortSignal.timeout(30_000)
    });

    const payload = await response.json().catch(() => ({}));
    if (!response.ok || !payload.task_id) {
      console.error("MusicAPI generation error:", response.status, JSON.stringify(payload).slice(0, 1200));
      const status = response.status === 401 ? 401 : response.status === 402 || response.status === 403 ? 402 : response.status === 429 ? 429 : response.status >= 500 ? 502 : 400;
      return res.status(status).json({
        error: response.status === 401 ? "API key MusicAPI tidak valid." :
          status === 402 ? "Kredit MusicAPI tidak cukup." :
          response.status === 429 ? "Batas permintaan MusicAPI tercapai. Coba lagi nanti." :
          providerError(payload, "MusicAPI gagal memulai generasi.")
      });
    }
    return res.status(202).json({ jobId: payload.task_id, status: "pending", title });
  } catch (error) {
    console.error("MusicAPI submit failed:", error);
    return res.status(504).json({ error: "Koneksi ke MusicAPI timeout atau terputus." });
  }
});

app.get("/api/job/:id", async (req, res) => {
  const activeApiKey = requestProviderKey(req);
  if (!activeApiKey) return res.status(req.get("X-MusicAPI-Key") ? 401 : 503).json({ error: req.get("X-MusicAPI-Key") ? "Akses pengembang tidak valid untuk API key pengganti." : "MUSICAPI_API_KEY belum diatur di Vercel." });
  const id = cleanString(req.params.id, 80);
  if (!/^[0-9a-f-]{36}$/i.test(id)) return res.status(400).json({ error: "ID generasi tidak valid." });

  try {
    const response = await fetch(`https://api.musicapi.ai/api/v1/sonic/task/${encodeURIComponent(id)}`, {
      headers: { Authorization: `Bearer ${activeApiKey}`, Accept: "application/json" },
      signal: AbortSignal.timeout(20_000)
    });
    const payload = await response.json().catch(() => ({}));
    if (!response.ok) {
      const status = [400, 401, 404, 429].includes(response.status) ? response.status : 502;
      return res.status(status).json({ error: providerError(payload, "Gagal memeriksa status generasi.") });
    }

    const songs = Array.isArray(payload.data) ? payload.data : [];
    const state = payload.status === "failed" || songs.some(song => song.state === "failed")
      ? "failed"
      : songs.length > 0 && songs.every(song => song.state === "succeeded")
        ? "completed"
        : songs.some(song => song.state === "running")
          ? "running"
          : "pending";

    return res.json({
      jobId: id,
      status: state,
      progress: null,
      error: payload.error || songs.find(song => song.state === "failed")?.error || null,
      tracks: songs.filter(song => song.state === "succeeded" && song.audio_url).map(song => ({
        id: song.clip_id || null,
        title: song.title || "MusicAI",
        audioUrl: song.audio_url || null,
        imageUrl: song.image_url || null,
        duration: song.duration || null,
        tags: song.tags || null
      }))
    });
  } catch (error) {
    console.error("MusicAPI job check failed:", error);
    return res.status(504).json({ error: "Koneksi saat memeriksa hasil terputus." });
  }
});

app.listen(port, () => console.log(`MusicAI backend listening on ${port}`));
