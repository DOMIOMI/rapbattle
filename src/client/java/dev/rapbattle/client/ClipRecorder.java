package dev.rapbattle.client;

import dev.rapbattle.battle.BeatTracks;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.resource.language.I18n;
import net.minecraft.client.sound.OggAudioStream;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CompletableFuture;

/**
 * Clips: your verse over the beat, as a WAV you can send to friends. After your verse the chat offers
 * "Сохранить клип"; clicking it mixes your recorded voice with the battle's beat (shifted by your calibrated
 * latency, so it sits on the beat like you heard it) and saves it to .minecraft/rapbattle-clips/.
 */
public final class ClipRecorder {
	private ClipRecorder() {}

	private static final int SR = 44100, VOICE_SR = 16000;

	private record Take(byte[] voiceWav, long voiceStart, long from, long to, String track, long beatMs, String opponent) {}

	private static Take last;
	private static boolean saving;

	/** Keeps the verse just recorded and offers to save it. */
	static void keep(byte[] voiceWav, long voiceStart, long from, long to, String track, long beatMs, String opponent) {
		if (voiceWav == null || voiceWav.length < 4000) return;
		last = new Take(voiceWav, voiceStart, from, to, track, beatMs, opponent);
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.player == null) return;
		Text button = Text.translatable("rapbattle.clip.save_button").styled(s -> s.withColor(Formatting.AQUA).withUnderline(true)
			.withClickEvent(new ClickEvent.RunCommand("/rapbattle clip"))
			.withHoverEvent(new HoverEvent.ShowText(Text.translatable("rapbattle.clip.save_hover"))));
		mc.player.sendMessage(Text.translatable("rapbattle.clip.prefix").formatted(Formatting.GOLD).append(button), false);
	}

	/** /rapbattle clip: mixes and saves the last verse in the background. */
	public static void save() {
		MinecraftClient mc = MinecraftClient.getInstance();
		Take t = last;
		if (t == null || saving) {
			if (mc.player != null) mc.player.sendMessage(Text.translatable(saving ? "rapbattle.clip.already_saving" : "rapbattle.clip.nothing_to_save")
				.formatted(Formatting.GRAY), false);
			return;
		}
		saving = true;
		CompletableFuture.supplyAsync(() -> mix(mc, t)).whenComplete((path, err) -> mc.execute(() -> {
			saving = false;
			if (mc.player == null) return;
			if (err != null || path == null) {
				Object reason = err == null ? Text.translatable("rapbattle.clip.no_audio") : err.getMessage();
				mc.player.sendMessage(Text.translatable("rapbattle.clip.save_failed", reason).formatted(Formatting.RED), false);
				return;
			}
			Text open = Text.literal(path.getFileName().toString()).styled(s -> s.withColor(Formatting.GREEN).withUnderline(true)
				.withClickEvent(new ClickEvent.OpenFile(path.getParent())));
			mc.player.sendMessage(Text.translatable("rapbattle.clip.saved").formatted(Formatting.GOLD).append(open), false);
		}));
	}

	private static Path mix(MinecraftClient mc, Take t) {
		try {
			int frames = (int) ((t.to() - t.from()) * SR / 1000);
			float[] out = new float[frames * 2];
			// The beat: the same loop the battle played, from the same place on the grid.
			BeatTracks.Track track = t.track() == null ? null : BeatTracks.byId(t.track());
			if (track != null) {
				short[] beat;
				int ch;
				var res = mc.getResourceManager().getResource(Identifier.of("rapbattle", "sounds/beat/" + track.id() + ".ogg"));
				try (InputStream in = res.orElseThrow().getInputStream(); OggAudioStream ogg = new OggAudioStream(in)) {
					ch = ogg.getFormat().getChannels();
					ByteBuffer pcm = ogg.readAll().order(ByteOrder.LITTLE_ENDIAN);
					beat = new short[pcm.remaining() / 2];
					pcm.asShortBuffer().get(beat);
				}
				long loopMs = (long) track.bars() * 4 * t.beatMs();
				long loopFrames = loopMs * SR / 1000;
				int beatFrames = beat.length / ch;
				for (int i = 0; i < frames; i++) {
					double clock = t.from() + i * 1000.0 / SR;
					if (clock < 0) continue;
					long f = (long) ((clock % loopMs) * SR / 1000.0);
					if (f >= Math.min(loopFrames, beatFrames)) continue;
					float l = beat[(int) f * ch] / 32768f, r = ch > 1 ? beat[(int) f * ch + 1] / 32768f : l;
					out[i * 2] += l * 0.55f;
					out[i * 2 + 1] += r * 0.55f;
				}
			}
			// The voice: 16 kHz mono, placed where it was said on the battle clock.
			byte[] w = t.voiceWav();
			int off = dataOffset(w);
			int n = (w.length - off) / 2;
			for (int i = 0; i < frames; i++) {
				double clock = t.from() + i * 1000.0 / SR;
				double pos = (clock - t.voiceStart()) * VOICE_SR / 1000.0;
				int k = (int) Math.floor(pos);
				if (k < 0 || k + 1 >= n) continue;
				double frac = pos - k;
				float a = sample(w, off, k), b = sample(w, off, k + 1);
				float v = (float) (a + (b - a) * frac) * 1.1f;
				out[i * 2] += v;
				out[i * 2 + 1] += v;
			}
			Path dir = FabricLoader.getInstance().getGameDir().resolve("rapbattle-clips");
			Files.createDirectories(dir);
			String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
			String name = t.opponent() == null || t.opponent().isBlank()
				? I18n.translate("rapbattle.clip.file_solo", ts)
				: I18n.translate("rapbattle.clip.file_vs", ts, t.opponent().replaceAll("[^\\p{L}\\p{N}_-]", ""));
			Path file = dir.resolve(name);
			Files.write(file, wav(out));
			return file;
		} catch (Exception e) {
			throw new RuntimeException(e.getMessage() == null ? e.toString() : e.getMessage(), e);
		}
	}

	private static float sample(byte[] w, int off, int i) {
		int idx = off + i * 2;
		return (short) ((w[idx] & 0xFF) | (w[idx + 1] << 8)) / 32768f;
	}

	private static int dataOffset(byte[] w) {
		for (int i = 12; i + 8 < w.length; i++) {
			if (w[i] == 'd' && w[i + 1] == 'a' && w[i + 2] == 't' && w[i + 3] == 'a') return i + 8;
		}
		return 44;
	}

	/** 44.1 kHz stereo 16-bit WAV, soft-limited so loud spots do not crackle. */
	private static byte[] wav(float[] stereo) {
		ByteArrayOutputStream o = new ByteArrayOutputStream(44 + stereo.length * 2);
		int data = stereo.length * 2;
		ByteBuffer h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
		h.put("RIFF".getBytes()).putInt(36 + data).put("WAVE".getBytes()).put("fmt ".getBytes()).putInt(16).putShort((short) 1)
			.putShort((short) 2).putInt(SR).putInt(SR * 4).putShort((short) 4).putShort((short) 16).put("data".getBytes()).putInt(data);
		o.writeBytes(h.array());
		ByteBuffer b = ByteBuffer.allocate(data).order(ByteOrder.LITTLE_ENDIAN);
		for (float s : stereo) b.putShort((short) Math.round(Math.tanh(s) * 32000));
		o.writeBytes(b.array());
		return o.toByteArray();
	}
}
