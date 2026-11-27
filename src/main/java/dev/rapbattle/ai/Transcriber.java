package dev.rapbattle.ai;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.rapbattle.Lang;
import dev.rapbattle.RapBattle;
import dev.rapbattle.RapConfig;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/** Speech-to-text through any OpenAI-compatible /audio/transcriptions endpoint (Groq, OpenAI, local whisper). */
public final class Transcriber {
	private Transcriber() {}

	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

	/** One recognised word and when it starts, in seconds from the beginning of the recording. */
	public record Word(String text, double start) {}

	/** The text and its words with timestamps (the list may be empty if the service gives no word times). */
	public record Result(String text, List<Word> words) {}

	/** Transcription outcome distinguishing service failure (key/5xx/timeout) from valid silence or corrupt/4xx input. */
	public record Outcome(Result result, boolean serviceFailure) {}

	/** Validates a PCM 16-kHz mono 16-bit RIFF/WAVE header and chunk structure. */
	public static boolean isValidWav16kMono16(byte[] wav) {
		if (wav == null || wav.length < 44) return false;
		if (wav[0] != 'R' || wav[1] != 'I' || wav[2] != 'F' || wav[3] != 'F') return false;
		if (wav[8] != 'W' || wav[9] != 'A' || wav[10] != 'V' || wav[11] != 'E') return false;
		boolean hasFmt = false;
		boolean hasData = false;
		int pos = 12;
		while (pos + 8 <= wav.length) {
			int id0 = wav[pos] & 0xFF, id1 = wav[pos + 1] & 0xFF, id2 = wav[pos + 2] & 0xFF, id3 = wav[pos + 3] & 0xFF;
			int size = le32(wav, pos + 4);
			if (size < 0 || pos + 8L + size > wav.length) return false;
			if (id0 == 'f' && id1 == 'm' && id2 == 't' && id3 == ' ') {
				if (size < 16) return false;
				int audioFormat = le16(wav, pos + 8);
				int channels = le16(wav, pos + 10);
				int sampleRate = le32(wav, pos + 12);
				int bitsPerSample = le16(wav, pos + 22);
				if (audioFormat != 1 || channels != 1 || sampleRate != 16000 || bitsPerSample != 16) return false;
				hasFmt = true;
			} else if (id0 == 'd' && id1 == 'a' && id2 == 't' && id3 == 'a') {
				hasData = true;
			}
			pos += 8 + size + (size & 1);
		}
		return hasFmt && hasData;
	}

	private static int le16(byte[] b, int off) {
		return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
	}

	private static int le32(byte[] b, int off) {
		return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8)
			| ((b[off + 2] & 0xFF) << 16) | ((b[off + 3] & 0xFF) << 24);
	}

	/** Returns the recognised text, "" for silence, or null when STT is not configured / failed. */
	public static String transcribe(byte[] wav, List<String> hintWords, String lang) {
		Result r = transcribeTimed(wav, hintWords, lang);
		return r == null ? null : r.text();
	}

	@Deprecated
	public static String transcribe(byte[] wav, List<String> hintWords) {
		return transcribe(wav, hintWords, Lang.RU);
	}

	/** Like {@link #transcribe}, with word timestamps; null when STT is not configured / failed. */
	public static Result transcribeTimed(byte[] wav, List<String> hintWords, String lang) {
		Outcome out = transcribeTimedChecked(wav, hintWords, lang);
		return out.serviceFailure() ? null : out.result();
	}

	/** Transcribes a 16-kHz mono 16-bit WAV, distinguishing service errors (5xx/timeout/no key) from bad client WAV/4xx. */
	public static Outcome transcribeTimedChecked(byte[] wav, List<String> hintWords, String lang) {
		RapConfig cfg = RapConfig.get();
		String key = cfg.sttKey();
		if (key.isEmpty()) {
			RapBattle.LOG.warn("Speech-to-text key is not set (sttApiKey in config/rapbattle-server.json or RAPBATTLE_STT_KEY)");
			return new Outcome(null, true);
		}
		if (wav == null || !isValidWav16kMono16(wav) || wav.length < 2000) {
			return new Outcome(new Result("", List.of()), false);
		}
		try {
			String boundary = "----rapbattle" + UUID.randomUUID().toString().replace("-", "");
			ByteArrayOutputStream body = new ByteArrayOutputStream(wav.length + 2048);
			field(body, boundary, "model", cfg.sttModel);
			String sttLang = resolveLanguage(cfg.sttLanguage, lang);
			if (sttLang != null && !sttLang.isBlank()) field(body, boundary, "language", sttLang);
			field(body, boundary, "response_format", "verbose_json");
			field(body, boundary, "timestamp_granularities[]", "word");
			field(body, boundary, "temperature", "0");
			// Whisper uses the prompt as spelling hints; the rhyme words are the most likely words in the verse.
			String prefix = Lang.isRu(lang) ? "Рэп-баттл. " : "Rap battle. ";
			if (!hintWords.isEmpty()) field(body, boundary, "prompt", prefix + String.join(", ", hintWords));
			body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"verse.wav\"\r\n"
				+ "Content-Type: audio/wav\r\n\r\n").getBytes(StandardCharsets.UTF_8));
			body.write(wav);
			body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

			String base = cfg.sttBaseUrl.endsWith("/") ? cfg.sttBaseUrl.substring(0, cfg.sttBaseUrl.length() - 1) : cfg.sttBaseUrl;
			HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/audio/transcriptions"))
				.timeout(Duration.ofSeconds(40))
				.header("Authorization", "Bearer " + key)
				.header("Content-Type", "multipart/form-data; boundary=" + boundary)
				.POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
				.build();
			HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
			int status = resp.statusCode();
			if (status / 100 != 2) {
				RapBattle.LOG.error("STT HTTP {}: {}", status, resp.body());
				boolean serviceErr = status / 100 != 4;
				return new Outcome(serviceErr ? null : new Result("", List.of()), serviceErr);
			}
			JsonObject o = JsonParser.parseString(resp.body()).getAsJsonObject();
			String text = o.has("text") ? o.get("text").getAsString().trim() : "";
			List<Word> words = new java.util.ArrayList<>();
			if (o.has("words")) {
				for (var e : o.getAsJsonArray("words")) {
					JsonObject w = e.getAsJsonObject();
					words.add(new Word(w.get("word").getAsString().trim(), w.get("start").getAsDouble()));
				}
			}
			return new Outcome(new Result(text, words), false);
		} catch (Exception e) {
			RapBattle.LOG.error("STT request failed", e);
			return new Outcome(null, true);
		}
	}

	@Deprecated
	public static Result transcribeTimed(byte[] wav, List<String> hintWords) {
		return transcribeTimed(wav, hintWords, Lang.RU);
	}

	private static String resolveLanguage(String configured, String lang) {
		if (lang == null || lang.isBlank()) return configured;
		if (configured != null && !configured.isBlank()
			&& !Lang.RU.equalsIgnoreCase(configured) && !Lang.EN.equalsIgnoreCase(configured)) {
			return configured;
		}
		return Lang.sttCode(lang);
	}

	private static void field(ByteArrayOutputStream out, String boundary, String name, String value) {
		out.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n"
			+ value + "\r\n").getBytes(StandardCharsets.UTF_8));
	}
}
