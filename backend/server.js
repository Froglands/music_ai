import express from "express";
import cors from "cors";
import rateLimit from "express-rate-limit";

const app = express();
const port = Number(process.env.PORT || 3000);
const apiKey = process.env.STABILITY_API_KEY;

app.set("trust proxy", 1);
app.use(cors({
  origin: process.env.ALLOWED_ORIGIN || "*",
  methods: ["GET", "POST"],
  allowedHeaders: ["Content-Type"]
}));
app.use(express.json({ limit: "80kb" }));
app.use(rateLimit({ windowMs: 60_000, limit: 5, standardHeaders: "draft-7", legacyHeaders: false }));

app.get("/health", (_req, res) => {
  res.json({ ok: true, service: "musicai-backend", provider: "stable-audio-3" });
});

function cleanString(value, max) {
  return typeof value === "string" ? value.trim().slice(0, max) : "";
}

function makePrompt({ genre, mood, bpm, lyrics }) {
  const theme = lyrics.replace(/\[[^\]]+\]/g, " ").replace(/\s+/g, " ").slice(0, 1800);
  return [
    "Create a fully instrumental music track. No singing, no spoken words, no vocals.",
    `Genre: ${genre}.`,
    `Mood and arrangement: ${mood}.`,
    `Tempo direction: approximately ${bpm} BPM.`,
    "Use a complete musical arrangement with a clear intro, main theme, development, and satisfying ending.",
    "The following user lyrics are thematic inspiration only and must not be sung or spoken:",
    theme
  ].join(" ");
}

app.post("/api/generate", async (req, res) => {
  if (!apiKey) return res.status(503).json({ error: "Backend belum dikonfigurasi: STABILITY_API_KEY belum diatur." });

  const title = cleanString(req.body?.title, 80) || "MusicAI";
  const lyrics = cleanString(req.body?.lyrics, 6000);
  const genre = cleanString(req.body?.genre, 50) || "Pop";
  const mood = cleanString(req.body?.mood, 300) || "uplifting and melodic";
  const bpm = Number(req.body?.bpm || 110);
  const duration = Math.min(60, Math.max(15, Number(req.body?.duration || 30)));

  if (!lyrics || lyrics.length < 10) return res.status(400).json({ error: "Masukkan tema atau lirik minimal 10 karakter." });
  if (!Number.isFinite(bpm) || bpm < 50 || bpm > 200) return res.status(400).json({ error: "Tempo harus antara 50 dan 200 BPM." });
  if (!Number.isFinite(duration)) return res.status(400).json({ error: "Durasi tidak valid." });

  try {
    const form = new FormData();
    form.set("model", "stable-audio-3");
    form.set("prompt", makePrompt({ genre, mood, bpm, lyrics }));
    form.set("duration", String(duration));
    form.set("output_format", "mp3");

    const start = await fetch("https://api.stability.ai/v2beta/audio/stable-audio/text-to-audio", {
      method: "POST",
      headers: {
        Authorization: `Bearer ${apiKey}`,
        Accept: "audio/*, application/json"
      },
      body: form,
      signal: AbortSignal.timeout(20_000)
    });

    if (!start.ok) {
      const detail = await start.text();
      console.error("Stability start error:", start.status, detail.slice(0, 1500));
      const status = [400, 401, 402, 403, 413, 422, 429].includes(start.status) ? start.status : 502;
      let detailMessage = "Stability AI gagal menerima permintaan.";
      try {
        const parsed = JSON.parse(detail);
        if (Array.isArray(parsed.errors) && parsed.errors.length) detailMessage = parsed.errors.join(" ");
      } catch {}
      return res.status(status).json({
        error: start.status === 401 ? "API Key Stability AI tidak valid." :
          start.status === 402 ? "Kredit Stability AI tidak mencukupi." :
          start.status === 403 ? "Prompt ditolak sistem moderasi Stability AI." :
          start.status === 429 ? "Batas permintaan Stability AI tercapai. Coba lagi nanti." :
          detailMessage
      });
    }

    const started = await start.json();
    const generationId = started.id;
    if (!generationId) return res.status(502).json({ error: "Stability AI tidak mengembalikan ID generasi." });

    // Stable Audio 3 is asynchronous. Poll for up to 45 seconds to stay within
    // common serverless execution limits; Android can retry if the job takes longer.
    const deadline = Date.now() + 45_000;
    while (Date.now() < deadline) {
      await new Promise(resolve => setTimeout(resolve, 5_000));
      const result = await fetch(`https://api.stability.ai/v2beta/audio/results/${encodeURIComponent(generationId)}`, {
        method: "GET",
        headers: {
          Authorization: `Bearer ${apiKey}`,
          Accept: "audio/*"
        },
        signal: AbortSignal.timeout(20_000)
      });

      if (result.status === 202) continue;
      if (!result.ok) {
        const detail = await result.text();
        console.error("Stability result error:", result.status, detail.slice(0, 1500));
        return res.status(result.status === 402 ? 402 : 502).json({ error: "Gagal mengambil hasil audio dari Stability AI." });
      }

      const audio = Buffer.from(await result.arrayBuffer());
      if (!audio.length) return res.status(502).json({ error: "Stability AI mengembalikan audio kosong." });
      res.setHeader("Content-Type", result.headers.get("content-type") || "audio/mpeg");
      res.setHeader("Content-Disposition", `attachment; filename="${title.replace(/[^a-z0-9-_]/gi, "_")}.mp3"`);
      res.setHeader("Cache-Control", "no-store");
      return res.status(200).send(audio);
    }

    return res.status(504).json({ error: "Musik masih diproses. Coba buat ulang beberapa saat lagi." });
  } catch (error) {
    console.error("Stable Audio generation failed:", error);
    return res.status(504).json({ error: "Koneksi ke Stability AI timeout atau terputus." });
  }
});

app.listen(port, () => console.log(`MusicAI backend listening on ${port}`));
