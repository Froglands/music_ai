import express from "express";
import cors from "cors";
import rateLimit from "express-rate-limit";

const app = express();
const port = Number(process.env.PORT || 3000);
const apiKey = process.env.APIFRAME_API_KEY;

app.set("trust proxy", 1);
app.use(cors({ origin: process.env.ALLOWED_ORIGIN || "*", methods: ["GET", "POST"], allowedHeaders: ["Content-Type"] }));
app.use(express.json({ limit: "80kb" }));
app.use(rateLimit({ windowMs: 60_000, limit: 5, standardHeaders: "draft-7", legacyHeaders: false }));

app.get("/health", (_req, res) => {
  res.json({ ok: true, service: "musicai-backend", provider: "apiframe-suno" });
});

function cleanString(value, max) {
  return typeof value === "string" ? value.trim().slice(0, max) : "";
}

app.post("/api/generate", async (req, res) => {
  if (!apiKey) return res.status(503).json({ error: "Backend belum dikonfigurasi: APIFRAME_API_KEY belum diatur." });

  const title = cleanString(req.body?.title, 80) || "MusicAI";
  const lyrics = cleanString(req.body?.lyrics, 5000);
  const genre = cleanString(req.body?.genre, 100) || "Pop";
  const mood = cleanString(req.body?.mood, 200) || "uplifting and melodic";
  const vocal = req.body?.vocal === "male" ? "m" : "f";
  const bpm = Number(req.body?.bpm || 110);

  if (!lyrics || lyrics.length < 10) return res.status(400).json({ error: "Masukkan lirik minimal 10 karakter (maksimal 5.000)." });
  if (!Number.isFinite(bpm) || bpm < 50 || bpm > 200) return res.status(400).json({ error: "Tempo harus antara 50 dan 200 BPM." });

  const style = [genre, mood, `${bpm} BPM`, vocal === "m" ? "male lead vocal" : "female lead vocal"].join(", ");
  try {
    const response = await fetch("https://api.apiframe.ai/v2/music/generate", {
      method: "POST",
      headers: { "X-API-Key": apiKey, "Content-Type": "application/json" },
      body: JSON.stringify({
        prompt: lyrics,
        model: "suno",
        sunoParams: {
          custom_mode: true,
          instrumental: false,
          model_version: "V5_5",
          title,
          style,
          vocal_gender: vocal
        }
      }),
      signal: AbortSignal.timeout(30_000)
    });

    const payload = await response.json().catch(() => ({}));
    if (!response.ok) {
      console.error("Apiframe generation error:", response.status, JSON.stringify(payload).slice(0, 1200));
      const status = [400, 401, 402, 429, 503].includes(response.status) ? response.status : 502;
      return res.status(status).json({
        error: response.status === 401 ? "API key Apiframe tidak valid." :
          response.status === 402 ? "Kredit Apiframe tidak cukup." :
          response.status === 429 ? "Batas permintaan Apiframe tercapai. Coba lagi nanti." :
          payload.error || payload.message || "Apiframe gagal memulai generasi."
      });
    }
    if (!payload.jobId) return res.status(502).json({ error: "Apiframe tidak mengembalikan jobId." });
    return res.status(202).json({ jobId: payload.jobId, status: payload.status || "QUEUED", title });
  } catch (error) {
    console.error("Apiframe submit failed:", error);
    return res.status(504).json({ error: "Koneksi ke Apiframe timeout atau terputus." });
  }
});

app.get("/api/job/:id", async (req, res) => {
  if (!apiKey) return res.status(503).json({ error: "APIFRAME_API_KEY belum diatur." });
  const id = cleanString(req.params.id, 80);
  if (!/^[0-9a-f-]{36}$/i.test(id)) return res.status(400).json({ error: "ID generasi tidak valid." });
  try {
    const response = await fetch(`https://api.apiframe.ai/v2/jobs/${encodeURIComponent(id)}`, {
      headers: { "X-API-Key": apiKey, Accept: "application/json" },
      signal: AbortSignal.timeout(20_000)
    });
    const payload = await response.json().catch(() => ({}));
    if (!response.ok) {
      const status = [400, 401, 404, 429].includes(response.status) ? response.status : 502;
      return res.status(status).json({ error: payload.error || payload.message || "Gagal memeriksa status generasi." });
    }
    return res.json({
      jobId: payload.id || id,
      status: payload.status,
      progress: payload.progress ?? null,
      error: payload.error || null,
      tracks: payload.result?.tracks || []
    });
  } catch (error) {
    console.error("Apiframe job check failed:", error);
    return res.status(504).json({ error: "Koneksi saat memeriksa hasil terputus." });
  }
});

app.listen(port, () => console.log(`MusicAI backend listening on ${port}`));
