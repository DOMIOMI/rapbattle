package dev.rapbattle.client;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * The fighter card of the intro, fighting-game style: a light streak flies across the screen, the card lands
 * from its side with an overshoot and a flash, the portrait (the live 3D model) opens up, then the name, rank,
 * fit and the record fill in one after another, numbers count up and a shine runs over the card.
 * Red corner sits on the right next to the fighter the camera shows, blue corner on the left.
 */
final class FighterCard {
	private FighterCard() {}

	/** What the card shows. Labels are already translated. */
	record Fighter(boolean red, String name, String sub,
				   String row1Label, String row1, int tierColor, int tier,
				   String row2Label, String row2, int row2Color,
				   int wins, int losses, String extraLabel, String extra, int extraColor,
				   boolean debut, boolean boss, Entity entity, String skin, String mobType) {}

	// Card layout in its own units (scaled to the screen as a whole).
	private static final int W = 214, H = 108, BANNER = 14, PW = 62, PH = 80, PY = 20;
	private static final int RED = 0xFFE8333F, BLUE = 0xFF2F9BF0, WHITE = 0xFFFFFFFF, DIM = 0xFF8A8FA8,
		GREEN = 0xFF7CF0A0, LOSS = 0xFFFF6A6A, GOLD = 0xFFE8C14A, YELLOW = 0xFFFFD84A, BOSS = 0xFFB48CFF;

	/** Global fade of the card (1 = fully visible), applied to every colour drawn. */
	private static float fade = 1f;

	/**
	 * @param age  ms since this card's window opened
	 * @param left ms until it closes
	 * @param len  length of the window in ms (short windows play the same animation faster)
	 */
	static void draw(DrawContext ctx, TextRenderer tr, Fighter f, float age, float left, float len, int w, int h) {
		float k = Math.max(0.35f, Math.min(1f, len / 2000f));
		age /= k;
		left /= k;

		MinecraftClient mc = MinecraftClient.getInstance();
		int gs = Math.max(1, mc.getWindow().getScaleFactor());
		float s = Math.min(w * 0.45f / W, (h - 56) * 0.6f / H);
		s = Math.max(0.5f, Math.min(3f, s));
		s = Math.max(1, Math.round(s * gs)) / (float) gs; // whole physical pixels, so the font stays crisp

		int accent = f.red ? RED : BLUE;
		float dir = f.red ? 1 : -1; // towards the card's own screen edge
		float cw = W * s, ch = H * s;
		float margin = Math.max(8, w * 0.035f);
		float baseX = f.red ? w - margin - cw : margin;
		float baseY = Math.max(28, h - 24 - 10 - ch);

		float in = easeOutBack(clamp01(age / 420f));
		float out = easeInCubic(clamp01(1 - left / 260f));
		float x = baseX + dir * (cw + margin + 30) * ((1 - in) + out);
		float y = baseY;
		fade = 1 - out;

		streak(ctx, f, accent, age, w, baseY + ch * 0.4f, s);
		if (age < 0) return;

		int px = f.red ? W - 7 - PW : 7;
		int tx = f.red ? 10 : px + PW + 9;
		int tw = W - PW - 26;
		float reveal = easeOutCubic(clamp01((age - 140) / 320f));
		int ry1 = PY + Math.round(PH / 2f * (1 - reveal)), ry2 = PY + PH - Math.round(PH / 2f * (1 - reveal));

		ctx.getMatrices().pushMatrix();
		ctx.getMatrices().translate(x, y);
		ctx.getMatrices().scale(s, s);

		// Shadow, body, banner, edges.
		ctx.fill(4, 4, W + 4, H + 4, a(0x70000000));
		ctx.fillGradient(0, BANNER, W, H, a(0xF0121420), a(0xF0000000 | (mix(accent, 0xFF0A0B12, 0.78f) & 0xFFFFFF)));
		for (int r = 0; r < BANNER; r++) {
			int cut = (BANNER - r) / 2; // slanted inner end
			int x1 = f.red ? cut + 2 : 0, x2 = f.red ? W : W - cut - 2;
			ctx.fill(x1, r, x2, r + 1, a(r == 0 ? mix(accent, WHITE, 0.35f) : accent));
		}
		ctx.fill(f.red ? W - 3 : 0, 0, f.red ? W : 3, H, a(mix(accent, WHITE, 0.2f)));
		ctx.drawStrokedRectangle(0, BANNER, W, H - BANNER, a(alphaOf(accent, 0.55f)));
		ctx.fill(0, H - 2, W, H, a(alphaOf(accent, 0.7f)));

		// Banner text: the corner, and a boss badge on the portrait side.
		Text corner = Text.translatable(f.red ? "rapbattle.hud.red_corner" : "rapbattle.hud.blue_corner").formatted(Formatting.BOLD);
		float ba = appear(age, 120);
		text(ctx, tr, corner, tx + 2 + (1 - ba) * 14 * dir, 3, 1f, WHITE, ba);
		if (f.boss) {
			Text boss = Text.translatable("rapbattle.hud.card.boss").formatted(Formatting.BOLD);
			int bw = tr.getWidth(boss) + 8;
			int bx = f.red ? W - 6 - bw : 6;
			ctx.fill(bx, 2, bx + bw, 12, a(0xFF1A0E2A));
			ctx.drawStrokedRectangle(bx, 2, bw, 10, a(BOSS));
			text(ctx, tr, boss, bx + 4, 3, 1f, BOSS, ba);
		}

		// Portrait backdrop (the model itself goes on top, in screen space, below).
		if (reveal > 0) {
			ctx.fillGradient(px, ry1, px + PW, ry2, a(mix(accent, 0xFF000000, 0.45f)), a(0xFF07070B));
			for (int line = 0; line < 3; line++) {
				int ox = line * 26 - 10;
				for (int r = ry1; r < ry2; r++) {
					int lx = px + ox + (PY + PH - r) / 2;
					if (lx >= px && lx + 6 <= px + PW) ctx.fill(lx, r, lx + 6, r + 1, a(0x10FFFFFF));
				}
			}
			LivingEntity le = f.entity instanceof LivingEntity l ? l : null;
			if (le == null && reveal > 0.6f) {
				if (f.skin != null) BattleHud.drawHead(ctx, f.skin, px + 7, PY + 16, 48);
				else if (f.mobType != null) BattleHud.drawMobIcon(ctx, f.mobType, px + PW / 2, PY + PH / 2, 3f);
			}
		}

		// Name: big, white, with an accent drop.
		float na = appear(age, 220);
		float ns = Math.min(2f, tw / (float) Math.max(1, tr.getWidth(f.name)));
		String name = f.name;
		if (ns < 1.25f) {
			ns = 1.25f;
			name = BattleHud.fit(tr, name, (int) (tw / ns));
		}
		float nx = tx + (1 - na) * 18 * dir;
		float ny = 20 + (2f - ns) * 4;
		textPlain(ctx, tr, name, nx + 1, ny + 1, ns, accent, na);
		textPlain(ctx, tr, name, nx, ny, ns, WHITE, na);

		float sa = appear(age, 300);
		if (!f.sub.isEmpty()) text(ctx, tr, BattleHud.fit(tr, f.sub, tw), tx + (1 - sa) * 14 * dir, 39, 1f, mix(accent, WHITE, 0.55f), sa);

		// Divider grows from the portrait side.
		float da = appear(age, 340);
		int dw = Math.round(tw * da);
		int dx = f.red ? tx + tw - dw : tx;
		ctx.fill(dx, 50, dx + dw, 51, a(alphaOf(accent, 0.9f)));
		ctx.fill(dx, 51, dx + dw, 52, a(0x30000000));

		// Rank / fit with its tier pips, then the second line.
		float r1 = appear(age, 400);
		text(ctx, tr, f.row1Label, tx + (1 - r1) * 12 * dir, 56, 0.75f, DIM, r1);
		text(ctx, tr, BattleHud.fit(tr, f.row1, tw - 36), tx + 36 + (1 - r1) * 12 * dir, 55, 1f, f.tierColor, r1);
		for (int i = 0; i < 6; i++) {
			float lit = clamp01((age - 520 - i * 70) / 90f);
			int pxi = tx + 36 + i * 12;
			ctx.fill(pxi, 66, pxi + 10, 69, a(alphaOf(0xFFFFFFFF, 0.12f * r1)));
			if (i < f.tier && lit > 0) {
				int c = lit < 1 ? mix(f.tierColor, WHITE, 1 - lit) : f.tierColor;
				ctx.fill(pxi, 66, pxi + 10, 69, a(alphaOf(c, lit)));
			}
		}
		float r2 = appear(age, 470);
		text(ctx, tr, f.row2Label, tx + (1 - r2) * 12 * dir, 74, 0.75f, DIM, r2);
		text(ctx, tr, BattleHud.fit(tr, f.row2, tw - 36), tx + 36 + (1 - r2) * 12 * dir, 73, 1f, f.row2Color, r2);

		// Record: wins, losses and a third stat, counting up.
		float ra = appear(age, 540);
		float count = easeOutCubic(clamp01((age - 600) / 450f));
		int bw = (tw - 6) / 3, by = 85;
		String[] labels = {lang("rapbattle.hud.card.wins"), lang("rapbattle.hud.card.losses"), f.extraLabel};
		String[] values = {String.valueOf(Math.round(f.wins * count)), String.valueOf(Math.round(f.losses * count)), f.extra};
		int[] colors = {GREEN, LOSS, f.extraColor};
		for (int i = 0; i < (f.debut ? 0 : 3); i++) { // a debut gets the stamp instead of zeros
			int bx = tx + i * (bw + 3);
			float lift = (1 - appear(age, 540 + i * 60)) * 6;
			ctx.getMatrices().pushMatrix();
			ctx.getMatrices().translate(0, lift);
			ctx.fill(bx, by, bx + bw, by + 19, a(alphaOf(0xD0000000, ra)));
			ctx.fill(bx, by + 18, bx + bw, by + 19, a(alphaOf(colors[i], 0.8f * ra)));
			String lab = BattleHud.fit(tr, labels[i], (int) ((bw - 4) / 0.62f));
			text(ctx, tr, lab, bx + bw / 2f - tr.getWidth(lab) * 0.62f / 2, by + 2, 0.62f, DIM, ra);
			String val = BattleHud.fit(tr, values[i], (int) ((bw - 4) / 1.2f));
			text(ctx, tr, val, bx + bw / 2f - tr.getWidth(val) * 1.2f / 2, by + 7.5f, 1.2f, colors[i], ra);
			ctx.getMatrices().popMatrix();
		}

		// Impact flash when the card lands, then a shine sweeping over it.
		float flash = clamp01((age - 360) / 200f);
		if (flash > 0 && flash < 1) ctx.fill(0, 0, W, H, a(alphaOf(WHITE, 0.4f * (1 - flash))));
		float shine = clamp01((age - 760) / 420f);
		if (shine > 0 && shine < 1) {
			float bx = -40 + (W + 80) * shine;
			for (int r = 0; r < H; r++) {
				int x1 = Math.round(bx + (H - r) * 0.35f);
				int sx1 = Math.max(0, x1), sx2 = Math.min(W, x1 + 14);
				if (sx2 > sx1) ctx.fill(sx1, r, sx2, r + 1, a(0x26FFFFFF));
			}
		}

		// "First battle" stamp slammed over the record.
		if (f.debut) {
			float st = clamp01((age - 560) / 160f);
			if (st > 0) {
				Text stamp = Text.translatable("rapbattle.hud.first_battle").formatted(Formatting.BOLD);
				int sw = tr.getWidth(stamp) + 10;
				ctx.getMatrices().pushMatrix();
				ctx.getMatrices().translate(tx + tw / 2f, by + 10);
				ctx.getMatrices().rotate(-0.06f);
				float sc = 1 + (1 - st) * 1.3f;
				ctx.getMatrices().scale(sc, sc);
				ctx.fill(-sw / 2, -7, sw / 2, 7, a(alphaOf(0xE8141018, st)));
				ctx.drawStrokedRectangle(-sw / 2, -7, sw, 14, a(alphaOf(YELLOW, st)));
				text(ctx, tr, stamp, -sw / 2f + 5, -3.5f, 1f, YELLOW, st);
				ctx.getMatrices().popMatrix();
			}
		}
		ctx.getMatrices().popMatrix();

		// The live model in the portrait, in screen space (GUI entities ignore the matrix), clipped to the reveal.
		int sx1 = Math.round(x + px * s), sx2 = Math.round(x + (px + PW) * s);
		int sy1 = Math.round(y + PY * s), sy2 = Math.round(y + (PY + PH) * s);
		int cy1 = Math.round(y + ry1 * s), cy2 = Math.round(y + ry2 * s);
		if (f.entity instanceof LivingEntity le && cy2 > cy1) {
			float eh = Math.max(0.3f, le.getHeight()), ew = Math.max(0.3f, le.getWidth());
			float boxH = sy2 - sy1, boxW = sx2 - sx1;
			int size = Math.max(4, Math.round(Math.min(boxH / (eh * 0.85f), boxW / (ew * 1.6f + 0.4f))));
			float mouseX = f.red ? sx1 - boxW * 0.6f : sx2 + boxW * 0.6f;
			float mouseY = sy1 + boxH * 0.2f;
			ctx.enableScissor(sx1, cy1, sx2, cy2);
			InventoryScreen.drawEntity(ctx, sx1, sy1, sx2, sy2, size, eh * 0.1f, mouseX, mouseY, le);
			ctx.disableScissor();
		}
		if (cy2 > cy1) {
			ctx.drawStrokedRectangle(sx1 - 1, cy1 - 1, sx2 - sx1 + 2, cy2 - cy1 + 2, a(accent));
			// Corner ticks on the portrait frame.
			int t = Math.max(2, Math.round(3 * s));
			ctx.fill(sx1 - 2, cy1 - 2, sx1 - 2 + t * 2, cy1 - 2 + Math.max(1, t / 2), a(WHITE));
			ctx.fill(sx2 + 2 - t * 2, cy2 + 2 - Math.max(1, t / 2), sx2 + 2, cy2 + 2, a(WHITE));
		}
		fade = 1f;
	}

	/** A light streak flying across the screen from the card's side, just before the card lands. */
	private static void streak(DrawContext ctx, Fighter f, int accent, float age, int w, float yy, float s) {
		float t = clamp01((age + 120) / 380f);
		if (t <= 0 || t >= 1) return;
		float len = w * 0.55f;
		float lead = easeOutCubic(t) * (w + len);
		float alpha = 1 - t;
		int th = Math.max(2, Math.round(3 * s));
		for (int part = 0; part < 4; part++) {
			float x1 = lead - len * (part + 1) / 4f, x2 = lead - len * part / 4f;
			int c = alphaOf(part == 0 ? WHITE : accent, alpha * (1 - part * 0.22f));
			if (f.red) ctx.fill(Math.round(w - x2), Math.round(yy), Math.round(w - x1), Math.round(yy) + th, c);
			else ctx.fill(Math.round(x1), Math.round(yy), Math.round(x2), Math.round(yy) + th, c);
		}
		int y2 = Math.round(yy + 7 * s);
		float lead2 = lead * 0.8f;
		int c2 = alphaOf(WHITE, alpha * 0.5f);
		if (f.red) ctx.fill(Math.round(w - lead2), y2, Math.round(w - lead2 + len * 0.5f), y2 + 1, c2);
		else ctx.fill(Math.round(lead2 - len * 0.5f), y2, Math.round(lead2), y2 + 1, c2);
	}

	// ------------------------------------------------------------------ helpers

	private static String lang(String key) { return net.minecraft.client.resource.language.I18n.translate(key); }

	private static void text(DrawContext ctx, TextRenderer tr, String s, float x, float y, float scale, int color, float alpha) {
		text(ctx, tr, Text.literal(s), x, y, scale, color, alpha);
	}

	private static void text(DrawContext ctx, TextRenderer tr, Text s, float x, float y, float scale, int color, float alpha) {
		int c = a(alphaOf(color, alpha));
		if ((c >>> 24) < 10) return;
		ctx.getMatrices().pushMatrix();
		ctx.getMatrices().translate(x, y);
		ctx.getMatrices().scale(scale, scale);
		ctx.drawText(tr, s, 0, 0, c, true);
		ctx.getMatrices().popMatrix();
	}

	private static void textPlain(DrawContext ctx, TextRenderer tr, String s, float x, float y, float scale, int color, float alpha) {
		int c = a(alphaOf(color, alpha));
		if ((c >>> 24) < 10) return;
		ctx.getMatrices().pushMatrix();
		ctx.getMatrices().translate(x, y);
		ctx.getMatrices().scale(scale, scale);
		ctx.drawText(tr, s, 0, 0, c, false);
		ctx.getMatrices().popMatrix();
	}

	private static float appear(float age, float start) { return easeOutCubic(clamp01((age - start) / 220f)); }

	/** Applies the card's global fade. */
	private static int a(int argb) { return alphaOf(argb, fade); }

	static int alphaOf(int argb, float f) {
		int al = Math.round(((argb >>> 24) & 0xFF) * clamp01(f));
		return (al << 24) | (argb & 0xFFFFFF);
	}

	static int mix(int c1, int c2, float t) {
		t = clamp01(t);
		int r = Math.round(((c1 >> 16) & 0xFF) * (1 - t) + ((c2 >> 16) & 0xFF) * t);
		int g = Math.round(((c1 >> 8) & 0xFF) * (1 - t) + ((c2 >> 8) & 0xFF) * t);
		int b = Math.round((c1 & 0xFF) * (1 - t) + (c2 & 0xFF) * t);
		return 0xFF000000 | (r << 16) | (g << 8) | b;
	}

	static float clamp01(float f) { return f < 0 ? 0 : f > 1 ? 1 : f; }

	static float easeOutCubic(float t) {
		float u = 1 - t;
		return 1 - u * u * u;
	}

	static float easeInCubic(float t) { return t * t * t; }

	static float easeOutBack(float t) {
		float c1 = 1.70158f, c3 = c1 + 1, u = t - 1;
		return 1 + c3 * u * u * u + c1 * u * u;
	}
}
