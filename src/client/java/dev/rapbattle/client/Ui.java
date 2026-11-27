package dev.rapbattle.client;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * The shared look of the battle HUD (the style of the intro fighter card): dark panels tinted with an accent colour,
 * an accent frame and bottom edge, slanted label tabs with bold text, slanted slabs, shines and easing.
 */
final class Ui {
	private Ui() {}

	static final int WHITE = 0xFFFFFFFF, TEXT = 0xFFE6E6F0, DIM = 0xFF8A8FA8, DARK = 0xFF14141E, BG = 0xFF0A0B12,
		RED = 0xFFE8333F, BLUE = 0xFF2F9BF0, GOLD = 0xFFE8C14A, YELLOW = 0xFFFFD84A, GREEN = 0xFF7CF0A0,
		LOSS = 0xFFFF6A6A, ORANGE = 0xFFF4A261, CYAN = 0xFF6EC6F0, BOSS = 0xFFB48CFF;

	/** Panel body: drop shadow, dark gradient tinted with the accent, accent frame and a brighter bottom edge. */
	static void panel(DrawContext ctx, int x, int y, int w, int h, int accent) {
		panel(ctx, x, y, w, h, accent, 1f);
	}

	static void panel(DrawContext ctx, int x, int y, int w, int h, int accent, float alpha) {
		ctx.fill(x + 3, y + 3, x + w + 3, y + h + 3, alphaOf(0x70000000, alpha));
		ctx.fillGradient(x, y, x + w, y + h, alphaOf(0xF0121420, alpha), alphaOf(0xF0000000 | (mix(accent, BG, 0.78f) & 0xFFFFFF), alpha));
		ctx.drawStrokedRectangle(x, y, w, h, alphaOf(accent, 0.55f * alpha));
		ctx.fill(x, y + h - 2, x + w, y + h, alphaOf(accent, 0.75f * alpha));
	}

	/** A slanted label tab whose bottom sits at {@code bottom}, bold text. Returns its width. */
	static int tab(DrawContext ctx, TextRenderer tr, String label, int x, int bottom, int accent, int textColor) {
		return tab(ctx, tr, label, x, bottom, accent, textColor, 1f);
	}

	static int tab(DrawContext ctx, TextRenderer tr, String label, int x, int bottom, int accent, int textColor, float alpha) {
		Text t = Text.literal(label);
		int th = 12, tw = tr.getWidth(t) + 16;
		for (int r = 0; r < th; r++) {
			int cut = (th - r) / 2;
			ctx.fill(x, bottom - th + r, x + tw - cut, bottom - th + r + 1, alphaOf(r == 0 ? mix(accent, WHITE, 0.35f) : accent, alpha));
		}
		text(ctx, tr, t, x + 5, bottom - th + 2, 1f, textColor, alpha, false);
		return tw;
	}

	/**
	 * A slab whose left and right edges lean by {@code dl}/{@code dr} units per row (fighting-game scoreboard shapes),
	 * filled with a vertical gradient.
	 */
	static void slab(DrawContext ctx, int x1, int x2, int y, int h, int top, int bottom, float dl, float dr) {
		for (int r = 0; r < h; r++) {
			int l = x1 + Math.round(dl * r), rr = x2 + Math.round(dr * r);
			if (rr > l) ctx.fill(l, y + r, rr, y + r + 1, mixA(top, bottom, h <= 1 ? 0 : r / (float) (h - 1)));
		}
	}

	/** A shine band sweeping over a rectangle, t 0..1. */
	static void shine(DrawContext ctx, int x, int y, int w, int h, float t, int color) {
		if (t <= 0 || t >= 1) return;
		float bx = -40 + (w + 80) * t;
		for (int r = 0; r < h; r++) {
			int x1 = Math.round(bx + (h - r) * 0.35f);
			int sx1 = Math.max(0, x1), sx2 = Math.min(w, x1 + 14);
			if (sx2 > sx1) ctx.fill(x + sx1, y + r, x + sx2, y + r + 1, color);
		}
	}

	/**
	 * Fighting-game bold for all characters (ASCII, Cyrillic ф/ш/щ/ж/ё, ★): rendered as a crisp double pass
	 * in {@link #text} instead of Minecraft's Formatting.BOLD, which smears dense Cyrillic strokes into blocks.
	 */
	static Text bold(String s) {
		if (s == null || s.isEmpty()) return Text.empty();
		return Text.literal(s).styled(st -> st.withInsertion("rapbattle:bold"));
	}

	static void text(DrawContext ctx, TextRenderer tr, String s, float x, float y, float scale, int color, float alpha, boolean shadow) {
		text(ctx, tr, Text.literal(s), x, y, scale, color, alpha, shadow);
	}

	static void text(DrawContext ctx, TextRenderer tr, Text s, float x, float y, float scale, int color, float alpha, boolean shadow) {
		int c = alphaOf(color, alpha);
		if ((c >>> 24) < 10) return;
		boolean fauxBold = scale >= 1f && "rapbattle:bold".equals(s.getStyle().getInsertion());
		ctx.getMatrices().pushMatrix();
		ctx.getMatrices().translate(x, y);
		ctx.getMatrices().scale(scale, scale);
		ctx.drawText(tr, s, 0, 0, c, shadow);
		if (fauxBold) {
			ctx.getMatrices().translate(0.65f, 0f);
			ctx.drawText(tr, s, 0, 0, c, false);
		}
		ctx.getMatrices().popMatrix();
	}

	/** Big text with a coloured drop under it (the fighter-card name look). x is the left edge, or the centre. */
	static void dropText(DrawContext ctx, TextRenderer tr, Text s, float x, float y, float scale, int color, int drop, float alpha, boolean centered) {
		float left = centered ? x - tr.getWidth(s) * scale / 2f : x;
		float d = Math.max(1f, scale * 0.6f);
		text(ctx, tr, s, left + d, y + d, scale, drop, alpha, false);
		text(ctx, tr, s, left, y, scale, color, alpha, false);
	}

	// ------------------------------------------------------------------ maths

	static int alphaOf(int argb, float f) {
		int al = Math.round(((argb >>> 24) & 0xFF) * clamp01(f));
		return (al << 24) | (argb & 0xFFFFFF);
	}

	/** RGB mix, opaque result. */
	static int mix(int c1, int c2, float t) {
		return 0xFF000000 | (mixA(c1, c2, t) & 0xFFFFFF);
	}

	/** ARGB mix including alpha. */
	static int mixA(int c1, int c2, float t) {
		t = clamp01(t);
		int a = Math.round(((c1 >>> 24) & 0xFF) * (1 - t) + ((c2 >>> 24) & 0xFF) * t);
		int r = Math.round(((c1 >> 16) & 0xFF) * (1 - t) + ((c2 >> 16) & 0xFF) * t);
		int g = Math.round(((c1 >> 8) & 0xFF) * (1 - t) + ((c2 >> 8) & 0xFF) * t);
		int b = Math.round((c1 & 0xFF) * (1 - t) + (c2 & 0xFF) * t);
		return (a << 24) | (r << 16) | (g << 8) | b;
	}

	static float clamp01(float f) { return f < 0 ? 0 : f > 1 ? 1 : f; }

	static float easeOutCubic(float t) {
		float u = 1 - clamp01(t);
		return 1 - u * u * u;
	}

	static float easeInCubic(float t) {
		t = clamp01(t);
		return t * t * t;
	}

	static float easeOutBack(float t) {
		t = clamp01(t);
		float c1 = 1.70158f, c3 = c1 + 1, u = t - 1;
		return 1 + c3 * u * u * u + c1 * u * u;
	}

	/** 0..1 appear curve for something that starts at {@code start} ms, over {@code dur} ms. */
	static float appear(long since, float start, float dur) { return easeOutCubic((since - start) / dur); }
}
