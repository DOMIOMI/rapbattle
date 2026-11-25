package dev.rapbattle.client;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Latency calibration: the game clicks 10 times, you clap (or say "та") on every click. The mic hears your claps a
 * little after the clicks were sent: that gap is your sound card + headphones + mic delay. The battle then shifts your
 * recording by it, so "on the beat" means on the beat you hear.
 */
public class CalibrationScreen extends Screen {
	private static final int CLICKS = 10, SKIP = 2;
	private static final long STEP_MS = 600, LEAD_MS = 900;

	private final Screen parent;
	private final MicRecorder mic = new MicRecorder();
	private long micStart = -1;
	private final List<Long> clickAt = new ArrayList<>();
	private int measured = -1;
	private String message = "Нажми «Старт» и хлопай в ладоши ровно на каждый щелчок (или говори «та»). Наушники тоже подходят.";
	private ButtonWidget start, save;

	public CalibrationScreen(Screen parent) {
		super(Text.literal("Рэп-баттлы: калибровка задержки"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		int y = height / 2 + 20;
		start = addDrawableChild(ButtonWidget.builder(Text.literal("Старт"), b -> begin()).dimensions(width / 2 - 154, y, 100, 20).build());
		save = addDrawableChild(ButtonWidget.builder(Text.literal("Сохранить"), b -> {
			ClientConfig.get().latencyMs = measured;
			ClientConfig.save();
			close();
		}).dimensions(width / 2 - 50, y, 100, 20).build());
		save.active = measured >= 0;
		addDrawableChild(ButtonWidget.builder(Text.literal("Сбросить на 0"), b -> {
			ClientConfig.get().latencyMs = 0;
			ClientConfig.save();
			message = "Задержка сброшена: 0 мс.";
		}).dimensions(width / 2 + 54, y, 100, 20).build());
		addDrawableChild(ButtonWidget.builder(Text.literal("Назад"), b -> close()).dimensions(width / 2 - 100, height - 28, 200, 20).build());
	}

	private void begin() {
		String err = mic.start();
		if (err != null) {
			message = "Микрофон не открылся: " + err;
			return;
		}
		micStart = System.currentTimeMillis();
		clickAt.clear();
		measured = -1;
		start.active = false;
		save.active = false;
		message = "Слушай щелчки и хлопай вместе с ними…";
	}

	@Override
	public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
		// Clicks are timed per frame, not per tick, so they are steady to a few milliseconds.
		if (micStart >= 0) {
			long t = System.currentTimeMillis() - micStart;
			if (clickAt.size() < CLICKS && t >= LEAD_MS + clickAt.size() * STEP_MS) {
				clickAt.add(System.currentTimeMillis() - micStart);
				client.getSoundManager().play(PositionedSoundInstance.ui(SoundEvents.BLOCK_NOTE_BLOCK_HAT.value(), 1.0f, 1.0f));
			}
			if (clickAt.size() == CLICKS && t >= LEAD_MS + CLICKS * STEP_MS + 500) finish();
		}
		super.render(ctx, mouseX, mouseY, delta);
		ctx.drawCenteredTextWithShadow(textRenderer, title, width / 2, 14, 0xFFFFFFFF);
		ctx.drawCenteredTextWithShadow(textRenderer, Text.literal("Сейчас: " + ClientConfig.get().latencyMs + " мс"), width / 2, 30, 0xFFB0B6CC);
		var lines = textRenderer.wrapLines(Text.literal(message), Math.min(320, width - 20));
		int my = height / 2 - 12 - lines.size() * 10;
		for (var line : lines) {
			ctx.drawCenteredTextWithShadow(textRenderer, line, width / 2, my, 0xFFFFD84A);
			my += 10;
		}
		if (micStart >= 0) {
			int done = clickAt.size();
			for (int i = 0; i < CLICKS; i++) {
				int x = width / 2 - CLICKS * 9 + i * 18;
				ctx.fill(x, height / 2, x + 14, height / 2 + 8, i < done ? 0xFF7CF0A0 : 0xFF2A2F44);
			}
		}
	}

	private void finish() {
		byte[] wav = mic.stop();
		micStart = -1;
		start.active = true;
		int lat = analyse(wav);
		if (lat < 0) {
			message = "Не расслышал хлопки. Хлопай громче или поднеси микрофон ближе и попробуй ещё раз.";
			return;
		}
		measured = lat;
		save.active = true;
		message = "Твоя задержка: " + lat + " мс. Нажми «Сохранить».";
	}

	/** Median gap between each click and the first loud sound after it, in ms; -1 if too few were heard. */
	private int analyse(byte[] wav) {
		int off = 44;
		for (int i = 12; i + 8 < wav.length; i++) {
			if (wav[i] == 'd' && wav[i + 1] == 'a' && wav[i + 2] == 't' && wav[i + 3] == 'a') {
				off = i + 8;
				break;
			}
		}
		int sr = 16000, frame = sr / 200; // 5 ms frames
		int frames = (wav.length - off) / 2 / frame;
		float[] env = new float[frames];
		for (int f = 0; f < frames; f++) {
			int peak = 0;
			for (int k = 0; k < frame; k++) {
				int idx = off + (f * frame + k) * 2;
				int s = (short) ((wav[idx] & 0xFF) | (wav[idx + 1] << 8));
				peak = Math.max(peak, Math.abs(s));
			}
			env[f] = peak / 32768f;
		}
		float[] sorted = env.clone();
		java.util.Arrays.sort(sorted);
		float floor = sorted.length == 0 ? 0 : sorted[sorted.length / 2];
		float threshold = Math.max(0.08f, floor * 5);
		List<Integer> lags = new ArrayList<>();
		for (int i = SKIP; i < clickAt.size(); i++) {
			long c = clickAt.get(i);
			int from = (int) Math.max(0, (c - 80) / 5), to = (int) Math.min(frames - 1, (c + 450) / 5);
			for (int f = Math.max(1, from); f <= to; f++) {
				if (env[f] >= threshold && env[f - 1] < threshold) {
					lags.add((int) (f * 5 - c));
					break;
				}
			}
		}
		if (lags.size() < (CLICKS - SKIP) / 2) return -1;
		Collections.sort(lags);
		return Math.max(0, Math.min(600, lags.get(lags.size() / 2)));
	}

	@Override
	public void close() {
		if (mic.isRunning()) mic.stop();
		client.setScreen(parent);
	}
}
