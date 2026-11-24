import express from "express";
import cors from "cors";
import rateLimit from "express-rate-limit";

const app = express();
const port = Number(process.env.PORT || 3000);
const apiKey = process.env.MUSICAPI_API_KEY;

app.set("trust proxy", 1);
app.use(cors({ origin: process.env.ALLOWED_ORIGIN || "*", methods: ["GET", "POST"], allowedHeaders: ["Content-Type"] }));
app.use(express.json({ limit: "80kb" }));
const generationLimiter = rateLimit({ windowMs: 60_000, limit: 3, standardHeaders: "draft-7", legacyHeaders: false });

app.get("/health", (_req, res) => {
  res.json({ ok: true, service: "musicai-backend", provider: "musicapi-sonic" });
});

function cleanString(value, max) {
  return typeof value === "string" ? value.trim().slice(0, max) : "";
}

function providerError(payload, fallback) {
  return payload?.error?.message || payload?.error || payload?.message || fallback;
}

app.post("/api/generate", generationLimiter, async (req, res) => {
  if (!apiKey) return res.status(503).json({ error: "Backend belum dikonfigurasi: MUSICAPI_API_KEY belum diatur di Vercel." });

  const title = cleanString(req.body?.title, 80);
  const lyrics = cleanString(req.body?.lyrics, 5000);
  const genre = cleanString(req.body?.genre, 100) || "Pop";
  const mood = cleanString(req.body?.mood, 200) || "uplifting and melodic";
  const instrumental = req.body?.instrumental === true || req.body?.vocal === "instrumental";
  const hasLyricsStructure = /\[(verse|chorus|bridge|intro|outro|pre-chorus)/i.test(lyrics) || lyrics.split(/\r?\n/).filter(line => line.trim()).length >= 5;
  const inputMode = req.body?.inputMode === "prompt" || req.body?.inputMode === "auto" && !hasLyricsStructure ? "prompt" : "lyrics";
  const vocal = req.body?.vocal === "male" ? "m" : "f";
  const duration = Math.max(10, Math.min(360, Math.round(Number(req.body?.duration || 180))));
  const bpm = Number(req.body?.bpm || 110);

  if (!instrumental && (!lyrics || lyrics.length < 10)) return res.status(400).json({ error: inputMode === "prompt" ? "Tulis ide lagu minimal 10 karakter." : "Masukkan lirik minimal 10 karakter (maksimal 5.000)." });
  if (!Number.isFinite(bpm) || bpm < 50 || bpm > 200) return res.status(400).json({ error: "Tempo harus antara 50 dan 200 BPM." });

  const tags = [genre, mood, `${bpm} BPM`].join(", ");
  try {
    const response = await fetch("https://api.musicapi.ai/api/v1/sonic/create", {
      method: "POST",
      headers: { Authorization: `Bearer ${apiKey}`, "Content-Type": "application/json" },
      body: JSON.stringify({
        task_type: "create_music",
        custom_mode: !instrumental && inputMode === "lyrics",
        mv: "sonic-v5-5",
        ...(title ? { title } : {}),
        ...(instrumental
          ? { make_instrumental: true, custom_mode: false, gpt_description_prompt: "Instrumental music, no vocals, no lyrics. " + lyrics + ". " + tags }
          : inputMode === "prompt"
            ? { auto_lyrics: true, prompt: lyrics, vocal_gender: vocal }
            : { prompt: lyrics, vocal_gender: vocal }),
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
  if (!apiKey) return res.status(503).json({ error: "MUSICAPI_API_KEY belum diatur di Vercel." });
  const id = cleanString(req.params.id, 80);
  if (!/^[0-9a-f-]{36}$/i.test(id)) return res.status(400).json({ error: "ID generasi tidak valid." });

  try {
    const response = await fetch(`https://api.musicapi.ai/api/v1/sonic/task/${encodeURIComponent(id)}`, {
      headers: { Authorization: `Bearer ${apiKey}`, Accept: "application/json" },
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
