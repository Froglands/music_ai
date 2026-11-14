import express from "express";
import cors from "cors";
import rateLimit from "express-rate-limit";

const app = express();
const port = Number(process.env.PORT || 3000);
const apiKey = process.env.ELEVENLABS_API_KEY;

app.use(cors({
  origin: process.env.ALLOWED_ORIGIN || "*",
  methods: ["GET", "POST"],
  allowedHeaders: ["Content-Type"]
}));
app.use(express.json({ limit: "80kb" }));
app.use(rateLimit({ windowMs: 60_000, limit: 5, standardHeaders: "draft-7", legacyHeaders: false }));

app.get("/health", (_req, res) => {
  res.json({ ok: true, service: "nadaai-backend" });
});

function cleanString(value, max) {
  return typeof value === "string" ? value.trim().slice(0, max) : "";
}

function lyricChunks(lyrics, styles) {
  const sections = [];
  let current = [];
  for (const rawLine of lyrics.split(/\r?\n/)) {
    const line = rawLine.trim();
    if (!line) continue;
    if (/^\[(.+)\]$/.test(line) && current.length) {
      sections.push(current.join("\n"));
      current = [line];
    } else {
      current.push(line);
    }
  }
  if (current.length) sections.push(current.join("\n"));
  if (!sections.length) throw new Error("Lirik tidak boleh kosong.");
  if (sections.length > 30) throw new Error("Maksimal 30 bagian lagu (contoh: Verse, Chorus, Bridge).");
  if (sections.some(section => section.split("\n").some(line => line.length > 200))) {
    throw new Error("Setiap baris lirik maksimal 200 karakter.");
  }

  // Distribute a 2-minute target across sections. Each chunk must be 3–120 seconds.
  const totalMs = 120_000;
  const perChunk = Math.max(3_000, Math.floor(totalMs / sections.length));
  return sections.map((text, index) => ({
    text,
    duration_ms: Math.min(120_000, perChunk),
    positive_styles: index === 0 ? styles : styles.slice(-4),
    negative_styles: ["spoken narration", "instrumental only"],
    context_adherence: "high"
  }));
}

app.post("/api/generate", async (req, res) => {
  if (!apiKey) return res.status(503).json({ error: "Backend belum dikonfigurasi: ELEVENLABS_API_KEY belum diatur." });

  const title = cleanString(req.body?.title, 80) || "Untitled";
  const lyrics = cleanString(req.body?.lyrics, 6000);
  const genre = cleanString(req.body?.genre, 50) || "Pop";
  const vocal = req.body?.vocal === "female" ? "female lead vocals" : "male lead vocals";
  const mood = cleanString(req.body?.mood, 80) || "emotional and melodic";
  const bpm = Number(req.body?.bpm || 110);

  if (!lyrics || lyrics.length < 10) return res.status(400).json({ error: "Masukkan lirik lagu minimal 10 karakter." });
  if (!Number.isFinite(bpm) || bpm < 50 || bpm > 200) return res.status(400).json({ error: "Tempo harus antara 50 dan 200 BPM." });

  const styles = [
    genre.toLowerCase(),
    vocal,
    `${bpm} BPM tempo direction`,
    mood,
    "clear lead vocal",
    "polished studio production",
    "catchy melodic arrangement"
  ];

  let chunks;
  try {
    chunks = lyricChunks(lyrics, styles);
  } catch (e) {
    return res.status(400).json({ error: e.message });
  }

  const payload = {
    model_id: "music_v2_5",
    composition_plan: { chunks }
  };

  try {
    const upstream = await fetch("https://api.elevenlabs.io/v1/music?output_format=mp3_48000_192", {
      method: "POST",
      headers: {
        "xi-api-key": apiKey,
        "Content-Type": "application/json"
      },
      body: JSON.stringify(payload),
      signal: AbortSignal.timeout(10 * 60 * 1000)
    });

    if (!upstream.ok) {
      const detail = await upstream.text();
      console.error("ElevenLabs error:", upstream.status, detail.slice(0, 1500));
      return res.status(upstream.status === 429 ? 429 : 502).json({
        error: upstream.status === 429
          ? "Layanan sedang membatasi permintaan. Coba lagi nanti."
          : "ElevenLabs gagal membuat lagu. Periksa akses model, parameter, dan kredit akun."
      });
    }

    const audio = Buffer.from(await upstream.arrayBuffer());
    if (!audio.length) return res.status(502).json({ error: "ElevenLabs mengembalikan audio kosong." });

    res.setHeader("Content-Type", "audio/mpeg");
    res.setHeader("Content-Disposition", `attachment; filename="${title.replace(/[^a-z0-9-_]/gi, "_")}.mp3"`);
    res.setHeader("Cache-Control", "no-store");
    return res.status(200).send(audio);
  } catch (error) {
    console.error("Generation failed:", error);
    return res.status(504).json({ error: "Permintaan ke ElevenLabs timeout atau koneksi terputus." });
  }
});

app.listen(port, () => console.log(`NadaAI backend listening on ${port}`));
