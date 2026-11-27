package dev.rapbattle.client;

import net.minecraft.client.resource.language.I18n;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.TargetDataLine;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Records the default microphone as 16 kHz mono 16-bit PCM and returns a WAV file. */
public final class MicRecorder {
	private static final AudioFormat FORMAT = new AudioFormat(16000f, 16, 1, true, false);

	private TargetDataLine line;
	private Thread thread;
	private final ByteArrayOutputStream pcm = new ByteArrayOutputStream();
	private volatile boolean running;
	/** Peak level of the last read block, 0..1, for the HUD meter. */
	public volatile float level;

	/** @return null on success, or an error message (no microphone, access denied...). */
	public String start() {
		if (running) return null;
		try {
			DataLine.Info info = new DataLine.Info(TargetDataLine.class, FORMAT);
			if (!AudioSystem.isLineSupported(info)) return I18n.translate("rapbattle.mic.not_found");
			line = (TargetDataLine) AudioSystem.getLine(info);
			line.open(FORMAT, 16000);
			line.start();
		} catch (Exception e) {
			line = null;
			return I18n.translate("rapbattle.mic.open_failed", e.getMessage());
		}
		running = true;
		thread = new Thread(() -> {
			byte[] buf = new byte[3200]; // 100 ms
			while (running) {
				int n = line.read(buf, 0, buf.length);
				if (n <= 0) continue;
				synchronized (pcm) { pcm.write(buf, 0, n); }
				int peak = 0;
				for (int i = 0; i + 1 < n; i += 2) peak = Math.max(peak, Math.abs((short) ((buf[i] & 0xff) | (buf[i + 1] << 8))));
				level = peak / 32768f;
			}
		}, "rapbattle-mic");
		thread.setDaemon(true);
		thread.start();
		return null;
	}

	public boolean isRunning() { return running; }

	/** Stops recording and returns the WAV bytes (empty array if nothing was recorded). */
	public byte[] stop() {
		running = false;
		if (line != null) {
			line.stop();
			line.close();
			line = null;
		}
		if (thread != null) {
			try { thread.join(500); } catch (InterruptedException ignored) {}
		}
		level = 0;
		byte[] data;
		synchronized (pcm) { data = pcm.toByteArray(); pcm.reset(); }
		return data.length == 0 ? new byte[0] : wav(data);
	}

	private static byte[] wav(byte[] pcm) {
		ByteBuffer b = ByteBuffer.allocate(44 + pcm.length).order(ByteOrder.LITTLE_ENDIAN);
		b.put("RIFF".getBytes()).putInt(36 + pcm.length).put("WAVE".getBytes());
		b.put("fmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) 1)
			.putInt(16000).putInt(16000 * 2).putShort((short) 2).putShort((short) 16);
		b.put("data".getBytes()).putInt(pcm.length).put(pcm);
		return b.array();
	}
}
