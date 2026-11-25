package dev.rapbattle.client;

import dev.rapbattle.net.BattleView;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.resource.language.I18n;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;

import java.util.List;
import java.util.Locale;

import static dev.rapbattle.client.Ui.*;

/**
 * "TV broadcast" battle screen in the fighter-card style: a fighting-game scoreboard on top with slanted team / mob
 * plates and the Respect bars, the battle table at the bottom centre in place of the hotbar, the judge's breakdown
 * on the result screens. The middle of the screen stays free for the stage.
 */
public final class BroadcastHud {
	private BroadcastHud() {}

	private static final int PANEL_TOP = 0xF0121420;

	// ------------------------------------------------------------------ scoreboard

	static int scoreboard(DrawContext ctx, TextRenderer tr, BattleView v, int w, boolean showRounds) {
		int y = BattleHud.top(v);
		int side = Math.min(170, (w - 120) / 2), mid = 100;
		int x0 = w / 2 - side - mid / 2;
		int h = 38;
		float k = 1 / 3f;
		int lean = Math.round((h - 1) * k);
		int[] shown = shownRespect(v);
		int max = Math.max(1, v.result != null && v.result.respectMax > 0 ? v.result.respectMax : v.respectMax);
		int cx0 = x0 + side, rx0 = cx0 + mid;

		// Plates: team (blue) leans into the centre, the centre widens downwards, the mob (red) mirrors the team.
		ctx.fill(x0 + 3, y + 3, rx0 + side + 3, y + h + 3, 0x50000000);
		slab(ctx, x0, cx0 - 1, y, h, 0xF0204C96, 0xF00C1530, 0, -k);
		slab(ctx, cx0 + 1, rx0 - 1, y, h, 0xF01A1C28, 0xF00A0B12, -k, k);
		slab(ctx, rx0 + 1, rx0 + side, y, h, 0xF0962030, 0xF0300C12, k, 0);
		slab(ctx, x0, cx0 - 1, y, 1, mix(BLUE, WHITE, 0.4f), mix(BLUE, WHITE, 0.4f), 0, 0);
		slab(ctx, rx0 + 1, rx0 + side, y, 1, mix(RED, WHITE, 0.4f), mix(RED, WHITE, 0.4f), 0, 0);
		ctx.fill(x0, y, x0 + 3, y + h, BLUE);
		ctx.fill(rx0 + side - 3, y, rx0 + side, y + h, RED);
		ctx.fill(x0, y + h - 2, cx0 - 1 - lean, y + h, BLUE);
		ctx.fill(rx0 + 1 + lean, y + h - 2, rx0 + side, y + h, RED);

		// Team
		StringBuilder team = new StringBuilder();
		if (v.pvp && !v.team.isEmpty()) team.append(v.team.get(0).name);
		else for (BattleView.Member m : v.team) team.append(team.length() == 0 ? "" : " + ").append(m.name);
		String t = BattleHud.fit(tr, team.toString(), side - 16);
		Text tt = bold(t);
		dropText(ctx, tr, tt, cx0 - 8 - tr.getWidth(tt), y + 4, 1f, WHITE, BLUE, 1f, false);
		String sub = v.team.isEmpty() ? ""
			: v.team.get(0).streak > 1 ? I18n.translate("rapbattle.broadcast.outfit_streak", v.team.get(0).outfit, v.team.get(0).streak)
			: v.team.get(0).outfit;
		sub = BattleHud.fit(tr, sub, side - 22);
		ctx.drawText(tr, sub, cx0 - 13 - tr.getWidth(sub), y + 15, 0xFFB8D4FF, false);
		respectBar(ctx, tr, x0 + 7, y + 26, side - 7 - 8 - lean, shown[0], max, true, 0, BLUE);

		// Centre: VS / rounds and the crowd meter, or the verdict once the breakdown has run.
		BattleView.Result res = v.result;
		boolean verdict = res != null && ("ROUND_RESULT".equals(v.phase) || "RESULT".equals(v.phase)) && revealed(v) >= res.hits.size();
		int cm = cx0 + mid / 2;
		if (verdict) {
			long since = System.currentTimeMillis() - verdictAt(v);
			String top = res.finalResult ? I18n.translate("rapbattle.broadcast.final") : I18n.translate("rapbattle.hud.round_upper", v.round);
			ctx.drawCenteredTextWithShadow(tr, top, cm, y + 3, DIM);
			Text big = bold(BattleHud.fit(tr, verdictWord(v, res), mid - 10));
			float pop = easeOutBack(since / 260f);
			float sc = Math.min(1.25f, (mid - 10) / (float) Math.max(1, tr.getWidth(big)));
			float s = sc * (2.2f - 1.2f * pop);
			boolean good = iWon(v, res) || v.spectator && v.pvp;
			dropText(ctx, tr, big, cm, y + 13 + (1.25f - sc) * 4, s, good ? YELLOW : LOSS, good ? 0xFF8A5A00 : 0xFF5A0A10, clamp01(since / 120f), true);
		} else {
			String vs = showRounds && v.rounds > 1 ? v.roundWinsPlayers + " : " + v.roundWinsMob : "VS";
			float beat = 1 + 0.12f * (1 - ClientBattle.beatFrac()) * ("TIMELINE".equals(v.phase) ? 1 : 0);
			dropText(ctx, tr, bold(vs), cm, y + 2, 1.5f * beat, YELLOW, RED, 1f, true);
			int mw = mid - 26, mx = cx0 + 13, my = y + 17;
			int fill = (int) (mw * (0.5f + Math.max(-100, Math.min(100, v.hype)) / 200f));
			ctx.fill(mx - 1, my - 1, mx + mw + 1, my + 6, 0xFF05060A);
			ctx.fillGradient(mx, my, mx + fill, my + 5, mix(BLUE, WHITE, 0.25f), mix(BLUE, BG, 0.3f));
			ctx.fillGradient(mx + fill, my, mx + mw, my + 5, mix(RED, WHITE, 0.2f), mix(RED, BG, 0.4f));
			ctx.fill(mx + fill - 1, my - 2, mx + fill + 1, my + 7, WHITE);
		}
		Text respect = Text.literal(I18n.translate("rapbattle.broadcast.respect"));
		text(ctx, tr, respect, cm - tr.getWidth(respect) * 0.75f / 2, y + 28, 0.75f, DIM, 1f, false);

		// Mob
		String mob = v.mobTeam.size() > 1 ? I18n.translate("rapbattle.hud.and_crew", v.mobName) : v.mobName;
		dropText(ctx, tr, bold(BattleHud.fit(tr, mob, side - 16)), rx0 + 9, y + 4, 1f, WHITE, RED, 1f, false);
		ctx.drawText(tr, BattleHud.fit(tr, v.rankArmor + " · " + v.rankStreet, side - 22), rx0 + 14, y + 15, BattleHud.opaque(v.rankColor), false);
		respectBar(ctx, tr, rx0 + 1 + lean + 1, y + 26, side - lean - 2 - 7, shown[1], max, false, 1, RED);

		// Combo under the centre while it burns.
		if ("TIMELINE".equals(v.phase) && v.combo >= 3) {
			String c = I18n.translate("rapbattle.broadcast.combo", v.combo >= 6 ? "2" : "1.5", v.combo);
			Text ct = bold(c);
			float s = 1.1f + 0.15f * (1 - ClientBattle.beatFrac());
			int cw = Math.round(tr.getWidth(ct) * s) + 14;
			int col = v.combo >= 6 ? ORANGE : GOLD;
			slab(ctx, w / 2 - cw / 2, w / 2 + cw / 2, y + h + 2, 14, col, mix(col, BG, 0.45f), 0.3f, -0.3f);
			text(ctx, tr, ct, w / 2f - tr.getWidth(ct) * s / 2, y + h + 9 - 4 * s, s, DARK, 1f, false);
		}
		return y + h;
	}

	private static final int WIN_COUNT = 12, TEAM_WIN_COUNT = 8, LOSE_COUNT = 8, TEAM_LOSE_COUNT = 7, ROUND_WIN_COUNT = 4, ROUND_LOSE_COUNT = 4;

	/** Did I win: my team against the mob, or me against the other player. */
	static boolean iWon(BattleView v, BattleView.Result res) {
		return v.pvp ? ClientBattle.me().equals(res.winner) : res.playersWin;
	}

	/** A different word every battle, but steady while the screen is up. */
	static String verdictWord(BattleView v, BattleView.Result res) {
		int seed = Math.abs((v.id * 31 + v.round) * 17 + res.comment.hashCode());
		if (v.pvp && v.spectator) return I18n.translate("rapbattle.broadcast.pvp_winner", res.winner.toUpperCase(Locale.ROOT));
		boolean team = v.team.size() > 1 && !v.pvp;
		boolean won = iWon(v, res);
		String prefix;
		int count;
		if (!res.finalResult) {
			prefix = won ? "rapbattle.broadcast.round_win." : "rapbattle.broadcast.round_lose.";
			count = won ? ROUND_WIN_COUNT : ROUND_LOSE_COUNT;
		} else if (won) {
			prefix = team ? "rapbattle.broadcast.team_win." : "rapbattle.broadcast.win.";
			count = team ? TEAM_WIN_COUNT : WIN_COUNT;
		} else {
			prefix = team ? "rapbattle.broadcast.team_lose." : "rapbattle.broadcast.lose.";
			count = team ? TEAM_LOSE_COUNT : LOSE_COUNT;
		}
		return I18n.translate(prefix + (seed % count));
	}

	/** Last shown value per bar, and the latest drop to flash next to it. */
	private static final int[] lastShown = {-1, -1};
	private static final int[] dropAmount = {0, 0};
	private static final long[] dropAt = {0, 0};

	private static void respectBar(DrawContext ctx, TextRenderer tr, int x, int y, int bw, int value, int max, boolean fromRight, int slot, int accent) {
		long now = System.currentTimeMillis();
		if (lastShown[slot] >= 0 && value < lastShown[slot]) {
			dropAmount[slot] = (now - dropAt[slot] < 600 ? dropAmount[slot] : 0) + (lastShown[slot] - value);
			dropAt[slot] = now;
		}
		lastShown[slot] = value;
		int bh = 8;
		ctx.fill(x - 1, y - 1, x + bw + 1, y + bh + 1, 0xFF05060A);
		ctx.fill(x, y, x + bw, y + bh, 0xFF141722);
		int f = (int) (bw * Math.max(0, Math.min(1, value / (float) max)));
		int col = value <= max * 0.25f ? 0xFFFF4A4A : value <= max * 0.5f ? 0xFFFFB84A : 0xFF5CE68C;
		int bx1 = fromRight ? x + bw - f : x, bx2 = fromRight ? x + bw : x + f;
		ctx.fillGradient(bx1, y, bx2, y + bh, mix(col, WHITE, 0.25f), mix(col, BG, 0.35f));
		if (bx2 > bx1) ctx.fill(bx1, y, bx2, y + 1, alphaOf(WHITE, 0.45f));
		// Segment ticks every tenth, like a fighting-game life bar.
		for (int i = 1; i < 10; i++) {
			int tx = x + bw * i / 10;
			ctx.fill(tx, y + 1, tx + 1, y + bh, 0x50000000);
		}
		// The recent drop shows as a white chunk that fades out.
		long since = now - dropAt[slot];
		if (since < 700 && dropAmount[slot] > 0) {
			int chunk = (int) (bw * Math.min(1, dropAmount[slot] / (float) max));
			int a = (int) (255 * (1 - since / 700f));
			int cx = fromRight ? x + bw - f - chunk : x + f;
			ctx.fill(Math.max(x, cx), y, Math.min(x + bw, cx + chunk), y + bh, (a << 24) | 0xFFFFFF);
		}
		// The number in a small plate on the outer end.
		Text num = bold(String.valueOf(value));
		int nw = tr.getWidth(num) + 6;
		int nx = fromRight ? x : x + bw - nw;
		ctx.fill(nx, y - 1, nx + nw, y + bh + 1, 0xE0000000);
		ctx.fill(fromRight ? nx : nx + nw - 1, y - 1, fromRight ? nx + 1 : nx + nw, y + bh + 1, accent);
		ctx.drawText(tr, num, nx + 3, y, WHITE, false);
		if (since < 900 && dropAmount[slot] > 0) {
			String d = "-" + dropAmount[slot];
			int rise = (int) (since / 60);
			int dx = fromRight ? x + bw - f - tr.getWidth(d) - 2 : x + f + 2;
			ctx.drawText(tr, d, Math.max(x, Math.min(x + bw - tr.getWidth(d), dx)), y - 9 - rise, YELLOW, true);
		}
	}

	// ------------------------------------------------------------------ the breakdown reveal

	private static String revealKey = "";
	private static long revealAt;
	static final long HIT_MS = 400, FIRST_HIT_MS = 700;

	/** How many breakdown lines are on screen now (they appear one by one). */
	static int revealed(BattleView v) {
		BattleView.Result r = v.result;
		if (r == null || !("ROUND_RESULT".equals(v.phase) || "RESULT".equals(v.phase))) return 0;
		String key = v.id + ":" + v.round + ":" + r.finalResult + ":" + r.hits.size() + ":" + r.comment.hashCode();
		long now = System.currentTimeMillis();
		if (!key.equals(revealKey)) {
			revealKey = key;
			revealAt = now;
		}
		long t = now - revealAt - FIRST_HIT_MS;
		return t < 0 ? 0 : (int) Math.min(r.hits.size(), t / HIT_MS + 1);
	}

	/** When the last breakdown line comes in, i.e. when the verdict lands. */
	private static long verdictAt(BattleView v) {
		revealed(v);
		int n = v.result == null ? 0 : v.result.hits.size();
		return revealAt + FIRST_HIT_MS + Math.max(0, n - 1) * HIT_MS;
	}

	/** Respect on the bars: live during the verse, counting down hit by hit on the result screens. */
	static int[] shownRespect(BattleView v) {
		BattleView.Result r = v.result;
		if (r != null && ("ROUND_RESULT".equals(v.phase) || "RESULT".equals(v.phase)) && r.respectMax > 0) {
			int n = revealed(v);
			int team = r.teamBefore, mob = r.mobBefore;
			for (int i = 0; i < n; i++) {
				BattleView.Hit hit = r.hits.get(i);
				if (hit.toMob) mob -= hit.damage;
				else team -= hit.damage;
			}
			if (n >= r.hits.size()) {
				team = r.respectTeam;
				mob = r.respectMob;
			}
			return new int[]{Math.max(0, team), Math.max(0, mob)};
		}
		return new int[]{v.respectTeam, v.respectMob};
	}

	// ------------------------------------------------------------------ during the verse

	static void timeline(DrawContext ctx, TextRenderer tr, BattleView v, int w, int h) {
		scoreboard(ctx, tr, v, w, true);
		if (v.totalBeats <= 0) return;
		int beat = Math.max(0, ClientBattle.beat());
		String me = ClientBattle.me();
		float fr = ClientBattle.beatFrac();

		int cur = -1;
		for (int i = 0; i < v.rows.size(); i++) {
			BattleView.Row r = v.rows.get(i);
			if (beat >= r.startBeat && beat < r.startBeat + 4) cur = i;
		}
		BattleView.Row live = cur >= 0 ? v.rows.get(cur) : null;
		BattleView.Segment seg = null;
		for (BattleView.Segment s : v.segments) if (beat >= s.leadInBeat && beat < s.endBeat) seg = s;

		// Countdown: a big number popping on every beat in the middle, nothing else.
		if (beat < 4) {
			countdown(ctx, tr, String.valueOf(4 - beat), I18n.translate("rapbattle.broadcast.countdown"), YELLOW, RED, fr, w, h);
			String beatName = I18n.translate("rapbattle.broadcast.beat", ClientBattle.styleName());
			int bw = tr.getWidth(beatName) + 16;
			panel(ctx, w / 2 - bw / 2, h / 2 + 22, bw, 14, YELLOW);
			ctx.drawCenteredTextWithShadow(tr, beatName, w / 2, h / 2 + 25, TEXT);
			return;
		}

		String tag;
		int tagColor;
		BattleView.Row first, second;
		boolean leadIn = false;
		if (live != null && live.mob) {
			tag = I18n.translate("rapbattle.broadcast.tag_mob", (live.reader == null ? v.mobName : live.reader).toUpperCase(Locale.ROOT), v.mobLanguage);
			tagColor = ORANGE;
			first = live;
			second = cur + 1 < v.rows.size() && v.rows.get(cur + 1).mob ? v.rows.get(cur + 1) : null;
		} else if (live != null) {
			boolean mine = me.equals(live.reader);
			tag = mine ? I18n.translate("rapbattle.broadcast.tag_you", live.line, live.lines)
				: I18n.translate("rapbattle.broadcast.tag_player", live.reader.toUpperCase(Locale.ROOT), live.line, live.lines);
			tagColor = mine ? GREEN : CYAN;
			first = live;
			second = cur + 1 < v.rows.size() ? v.rows.get(cur + 1) : null;
		} else if (seg != null) {
			boolean mine = me.equals(seg.reader);
			leadIn = true;
			int left = Math.max(1, seg.startBeat - beat);
			tag = mine ? I18n.translate("rapbattle.broadcast.tag_ready_you", left)
				: I18n.translate("rapbattle.broadcast.tag_ready_player", seg.reader.toUpperCase(Locale.ROOT), left);
			tagColor = mine ? GREEN : CYAN;
			first = rowAtBeat(v, seg.startBeat);
			int fi = first == null ? -1 : v.rows.indexOf(first);
			second = fi >= 0 && fi + 1 < v.rows.size() ? v.rows.get(fi + 1) : null;
			// Your cue: "you're up" and the beats left, big in the middle.
			if (mine) countdown(ctx, tr, String.valueOf(left), I18n.translate("rapbattle.broadcast.your_cue"), GREEN, 0xFF1F6B3A, fr, w, h);
		} else {
			return;
		}

		String fx = live == null ? null : live.effect;
		int k = live == null ? -1 : beat - live.startBeat;
		// The table sits at the very bottom, centred, where the hotbar and hearts were (they are hidden during the battle).
		// Cells grow to fit the mob's words; only on a very narrow screen the whole panel is drawn smaller.
		// Same widths for the whole battle, so the panel does not jump from line to line.
		int[] cw = widths(tr, v.rows.toArray(new BattleView.Row[0]));
		int lw = cw[0] + cw[1] + cw[2] + cw[3] + 3 * GAP + 2 * PAD;
		beginPanel(ctx, w, h, lw);
		int lx = 0;
		if ("shake".equals(fx)) lx += (int) (Math.sin(System.currentTimeMillis() / 25.0) * 3);
		if ("ram".equals(fx) && k == 0) lx += (int) ((1 - fr) * 30);
		int rowsH = 2 * ROW + GAP + 2 * PAD;
		int ly = -6 - rowsH;

		// Tag: who is on, a slanted tab; REC on the right end.
		boolean rec = ClientBattle.recording();
		tag = BattleHud.fit(tr, tag, lw - (rec ? 64 : 24));
		tab(ctx, tr, tag, lx, ly, tagColor, DARK);
		if (rec) {
			Text r = bold("REC");
			int rw = tr.getWidth(r) + 18, rx = lx + lw - rw;
			ctx.fill(rx, ly - 12, rx + rw, ly, 0xE8100A0C);
			ctx.fill(rx, ly - 12, rx + rw, ly - 11, RED);
			float pulse = 0.55f + 0.45f * (float) Math.abs(Math.sin(System.currentTimeMillis() / 260.0));
			ctx.fill(rx + 5, ly - 8, rx + 10, ly - 3, alphaOf(RED, pulse));
			ctx.drawText(tr, r, rx + 13, ly - 10, alphaOf(RED, 0.5f + pulse / 2), false);
		}

		// The two rows (now, next): three cells (mob words, or beat dots), then the rhyme.
		panel(ctx, lx, ly, lw, rowsH, tagColor);
		boolean blind = "ink".equals(fx) || "blind".equals(fx);
		if (first != null) cellRow(ctx, tr, first, cw, lx + PAD, ly + PAD, !leadIn && live == first, leadIn ? -1 : k, me, tagColor, fr);
		if (second != null && !blind) cellRow(ctx, tr, second, cw, lx + PAD, ly + PAD + ROW + GAP, false, -1, me, tagColor, fr);

		// Bottom edge: the beat pulse, or your mic level while you record.
		int by = ly + rowsH;
		ctx.fill(lx, by, lx + lw, by + 3, 0xFF1A1D2A);
		if (rec) {
			int lev = (int) (Math.min(1f, ClientBattle.micLevel() * 2.5f) * lw);
			ctx.fillGradient(lx, by, lx + lev, by + 3, mix(RED, WHITE, 0.3f), RED);
		} else {
			int pw = (int) ((1 - fr) * lw);
			ctx.fillGradient(lx, by, lx + pw, by + 3, mix(tagColor, WHITE, 0.35f), tagColor);
		}

		// Llama spit over the panel.
		if ("spit".equals(fx) && k <= 1) {
			for (int i = 0; i < 6; i++) {
				int sx = lx + 20 + (i * 53) % (lw - 40), sy = ly + 3 + (i * 9) % (rowsH - 10);
				ctx.fill(sx, sy, sx + 20 + i % 3 * 6, sy + 9, 0xD0B8C9A0);
			}
		}
		ctx.getMatrices().popMatrix();
	}

	/** A big number popping on every beat with an expanding frame, and a slanted caption above it. */
	private static void countdown(DrawContext ctx, TextRenderer tr, String number, String caption, int color, int drop, float fr, int w, int h) {
		float pop = easeOutCubic(fr / 0.35f);
		float s = 5f * (1.45f - 0.45f * pop);
		int cy = h / 2 - 22;
		// Expanding frame from the number.
		int r = Math.round(18 + 40 * fr);
		ctx.drawStrokedRectangle(w / 2 - r, cy - r, r * 2, r * 2, alphaOf(color, 0.6f * (1 - fr)));
		Text n = bold(number);
		dropText(ctx, tr, n, w / 2f, cy - 4 * s, s, color, drop, 1f, true);
		Text c = bold(caption);
		int cw = tr.getWidth(c) + 20;
		int top = cy - Math.round(4 * 5f) - 26;
		slab(ctx, w / 2 - cw / 2, w / 2 + cw / 2, top, 13, color, mix(color, BG, 0.4f), 0.3f, -0.3f);
		ctx.drawText(tr, c, w / 2 - tr.getWidth(c) / 2, top + 3, DARK, false);
	}

	/**
	 * Starts a bottom-centred panel of natural width {@code lw}: local x 0..lw, local y 0 is the bottom edge.
	 * Shrinks it only when the screen is narrower than the panel. Pair with popMatrix.
	 */
	static void beginPanel(DrawContext ctx, int w, int h, int lw) {
		float sc = Math.max(0.5f, Math.min(1f, (float) (w - 12) / lw));
		ctx.getMatrices().pushMatrix();
		ctx.getMatrices().translate(w / 2f - lw * sc / 2f, h - 4);
		ctx.getMatrices().scale(sc, sc);
	}

	private static final int ROW = 15, GAP = 2, PAD = 4, MIN_COL = 46, MIN_WORD = 72;

	/** Column widths so every mob word and rhyme fits whole. */
	private static int[] widths(TextRenderer tr, BattleView.Row... rows) {
		int[] cw = {MIN_COL, MIN_COL, MIN_COL, MIN_WORD};
		for (BattleView.Row r : rows) {
			if (r == null) continue;
			if (r.mob) for (int c = 0; c < 3; c++) if (r.cols[c] != null) cw[c] = Math.max(cw[c], tr.getWidth(r.cols[c]) + 10);
			if (r.word != null) cw[3] = Math.max(cw[3], tr.getWidth(bold(r.word)) + 14);
			if (!r.mob && r.reader != null) cw[0] = Math.max(cw[0], Math.min(90, tr.getWidth(r.reader) + 10));
		}
		return cw;
	}

	/** One table row: three dark cells (mob words, or beat dots for players) and the coloured rhyme cell. */
	private static void cellRow(DrawContext ctx, TextRenderer tr, BattleView.Row r, int[] cw, int x, int y, boolean live, int k, String me, int accent, float fr) {
		for (int c = 0; c < 3; c++) {
			int col = cw[c];
			boolean hit = live && k == c;
			if (hit && r.mob) ctx.fillGradient(x, y, x + col, y + ROW, 0xFF6A3F90, 0xFF3A2258);
			else if (hit) ctx.fillGradient(x, y, x + col, y + ROW, mix(accent, BG, 0.45f), mix(accent, BG, 0.75f));
			else if (live) ctx.fillGradient(x, y, x + col, y + ROW, 0xFF34406A, 0xFF232A48);
			else ctx.fillGradient(x, y, x + col, y + ROW, 0xFF22263A, 0xFF181B2A);
			if (hit) ctx.fill(x, y + ROW - 2, x + col, y + ROW, r.mob ? ORANGE : accent);
			else if (live) ctx.fill(x, y, x + col, y + 1, 0x30FFFFFF);
			String text = r.mob ? (r.cols[c] == null ? "" : r.cols[c]) : (c == 0 && !live && r.reader != null ? r.reader : null);
			if (text != null && !text.isEmpty()) {
				int color = r.mob ? (live ? (hit ? YELLOW : TEXT) : DIM) : (me.equals(r.reader) ? GREEN : CYAN);
				String t = BattleHud.fit(tr, text, col - 4);
				ctx.drawText(tr, t, x + col / 2 - tr.getWidth(t) / 2, y + 4, color, hit);
			} else if (text == null) {
				// Beat marks: a diamond that swells on its beat.
				int dx = x + col / 2, dy = y + ROW / 2;
				int sz = hit ? 2 + Math.round(2 * (1 - fr)) : 1;
				int dc = hit ? YELLOW : 0xFF55597A;
				for (int i = -sz; i <= sz; i++) {
					int half = sz - Math.abs(i);
					ctx.fill(dx - half, dy + i, dx + half + 1, dy + i + 1, dc);
				}
			}
			x += col + GAP;
		}
		int word = cw[3];
		int activeK = live ? k : -1;
		String shown = shownWord(r, activeK);
		boolean stolen = !r.mob && "stolen".equals(r.effect);
		boolean burned = !r.mob && "burn".equals(r.effect) && activeK >= 2;
		int cell = stolen ? 0xFF3A1A1A : burned ? 0xFFE86A17 : BattleHud.opaque(r.color);
		if (live && k == 3) {
			// The rhyme's beat: a glow around the cell.
			int g = Math.round(2 + 2 * (1 - fr));
			ctx.fill(x - g, y - g, x + word + g, y + ROW + g, alphaOf(YELLOW, 0.35f * (1 - fr) + 0.15f));
		}
		ctx.fillGradient(x, y, x + word, y + ROW, mix(cell, WHITE, 0.12f), mix(cell, BG, 0.18f));
		ctx.fill(x, y, x + word, y + 1, 0x60FFFFFF);
		ctx.fill(x, y + ROW - 1, x + word, y + ROW, 0x50000000);
		if (live) ctx.drawStrokedRectangle(x - 1, y - 1, word + 2, ROW + 2, k == 3 ? YELLOW : WHITE);
		Text wt = bold(BattleHud.fit(tr, shown, word - 8));
		ctx.drawText(tr, wt, x + word / 2 - tr.getWidth(wt) / 2, y + 4, stolen ? RED : DARK, false);
	}

	private static BattleView.Row rowAtBeat(BattleView v, int beat) {
		for (BattleView.Row r : v.rows) if (r.startBeat == beat) return r;
		return null;
	}

	/** The rhyme as the mob's tricks let you see it. */
	private static String shownWord(BattleView.Row r, int k) {
		BattleView v = ClientBattle.view;
		if (!r.mob && v != null && v.revealBeats > 0 && r.startBeat - ClientBattle.beat() > v.revealBeats) return "???";
		if (r.effect == null || r.mob) return r.word;
		return switch (r.effect) {
			case "hidden" -> "???";
			case "stolen" -> I18n.translate("rapbattle.hud.word_stolen");
			case "burn" -> k >= 2 ? I18n.translate("rapbattle.hud.word_burned") : r.word;
			case "illusion" -> k >= 0 && k < 3 ? fake(r) : r.word;
			default -> r.word;
		};
	}

	private static String fake(BattleView.Row r) {
		BattleView v = ClientBattle.view;
		if (v == null || v.rows.isEmpty()) return r.word;
		return v.rows.get(Math.floorMod((int) (System.currentTimeMillis() / 300) + r.startBeat, v.rows.size())).word;
	}

	// ------------------------------------------------------------------ judge and result

	static void judging(DrawContext ctx, TextRenderer tr, BattleView v, int w, int h) {
		scoreboard(ctx, tr, v, w, true);
		long now = System.currentTimeMillis();
		String dots = ".".repeat((int) (now / 400 % 4));
		int lx = 0, lw = 300, ph = 32, ly = -6 - ph;
		beginPanel(ctx, w, h, lw);
		tab(ctx, tr, I18n.translate("rapbattle.broadcast.judge_verdict"), lx, ly, GOLD, DARK);
		panel(ctx, lx, ly, lw, ph, GOLD);
		dropText(ctx, tr, bold(I18n.translate("rapbattle.broadcast.judge_thinking", dots)), lx + 10, ly + 7, 1.4f, YELLOW, 0xFF6A4A00, 1f, false);
		// An indeterminate bar: a gold segment running back and forth.
		int bx = lx + 10, bw = lw - 20, by = ly + ph - 7;
		ctx.fill(bx, by, bx + bw, by + 2, 0xFF2A2410);
		float t = (float) ((Math.sin(now / 350.0) + 1) / 2);
		int seg = bw / 4, sx = bx + Math.round((bw - seg) * t);
		ctx.fill(sx, by, sx + seg, by + 2, GOLD);
		ctx.getMatrices().popMatrix();
	}

	static void result(DrawContext ctx, TextRenderer tr, BattleView v, int w, int h) {
		BattleView.Result r = v.result;
		if (r == null) return;
		boolean roundOnly = !r.finalResult;
		boolean won = iWon(v, r);
		int under = scoreboard(ctx, tr, v, w, true);
		int n = revealed(v);
		boolean allIn = n >= r.hits.size();
		long now = System.currentTimeMillis();

		// How it was won, a slanted strip under the scoreboard, once the breakdown has run.
		if (allIn && !roundOnly) {
			String how = r.knockout ? I18n.translate("rapbattle.broadcast.knockout")
				: I18n.translate("rapbattle.broadcast.by_points", r.respectTeam, r.respectMob);
			Text ht = bold(how);
			int col = r.knockout ? ORANGE : (won ? YELLOW : LOSS);
			float a = clamp01((now - verdictAt(v)) / 200f);
			int tw = tr.getWidth(ht) + 20;
			slab(ctx, w / 2 - tw / 2, w / 2 + tw / 2, under + 1, 13, alphaOf(0xF0101018, a), alphaOf(0xF0060609, a), 0.3f, -0.3f);
			ctx.fill(w / 2 - tw / 2 + 4, under + 13, w / 2 + tw / 2 - 4, under + 14, alphaOf(col, a));
			text(ctx, tr, ht, w / 2f - tr.getWidth(ht) / 2f, under + 3, 1f, col, a, false);
		}

		// The breakdown on the left: line by line, each sliding in, newest at the bottom.
		int cw = Math.min(214, w / 2 - 20), cx = 8, cy = under + 30;
		int maxRows = Math.max(4, Math.min(10, (h - 120 - cy) / 11));
		int from = Math.max(0, n - maxRows);
		int rows = n - from;
		int statsH = allIn ? 16 + (r.bestLine.isEmpty() ? 0 : 11) + (r.players.size() > 1 ? r.players.size() * 10 : 0) : 0;
		int ph = 6 + rows * 11 + statsH + 2;
		tab(ctx, tr, I18n.translate("rapbattle.broadcast.breakdown"), cx, cy, GOLD, DARK);
		panel(ctx, cx, cy, cw, ph, GOLD);
		ctx.fill(cx, cy, cx + 3, cy + ph, GOLD);
		int ry = cy + 5;
		for (int i = from; i < n; i++) {
			BattleView.Hit hit = r.hits.get(i);
			long since = now - (revealAt + FIRST_HIT_MS + i * HIT_MS);
			float a = easeOutCubic(since / 220f);
			int off = Math.round((1 - a) * -16);
			if ((i & 1) == 0) ctx.fill(cx + 4, ry - 2, cx + cw - 2, ry + 9, 0x0CFFFFFF);
			String target = !v.pvp ? I18n.translate(hit.toMob ? "rapbattle.broadcast.target_mob" : "rapbattle.broadcast.target_team")
				: hit.toMob ? v.mobName : (v.team.isEmpty() ? "" : v.team.get(0).name);
			String dmg = hit.damage == 0 ? "0" : target + " -" + hit.damage;
			int dw = tr.getWidth(dmg);
			String who = hit.who.isEmpty() ? "" : hit.who + " ";
			String text = BattleHud.fit(tr, who + hit.note, cw - 26 - dw);
			int col = !hit.toMob ? 0xFFFF8A8A : hit.damage == 0 ? DIM : hit.punch ? ORANGE : TEXT;
			int dcol = hit.toMob ? (hit.damage == 0 ? DIM : YELLOW) : RED;
			if (hit.punch && hit.toMob) ctx.fill(cx + 4, ry - 2, cx + 6, ry + 9, alphaOf(ORANGE, a));
			text(ctx, tr, text, cx + 9 + off, ry, 1f, col, a, false);
			int px = cx + cw - 8 - dw;
			ctx.fill(px - 3, ry - 2, cx + cw - 4, ry + 9, alphaOf(mix(dcol, BG, 0.8f), a));
			text(ctx, tr, dmg, px, ry, 1f, dcol, a, false);
			// A fresh line flashes once.
			if (since >= 0 && since < 200) ctx.fill(cx + 4, ry - 2, cx + cw - 2, ry + 9, alphaOf(WHITE, 0.25f * (1 - since / 200f)));
			ry += 11;
		}
		if (allIn) {
			ry += 3;
			ctx.fill(cx + 8, ry - 2, cx + cw - 6, ry - 1, alphaOf(GOLD, 0.5f));
			ctx.drawText(tr, BattleHud.fit(tr, I18n.translate("rapbattle.broadcast.stats_summary", r.dealt, r.accuracy, r.bestCombo), cw - 14), cx + 9, ry + 1, CYAN, false);
			ry += 11;
			if (!r.bestLine.isEmpty()) {
				ctx.drawText(tr, BattleHud.fit(tr, I18n.translate("rapbattle.broadcast.best_line", r.bestLine), cw - 14), cx + 9, ry + 1, DIM, false);
				ry += 11;
			}
			if (r.players.size() > 1) {
				for (BattleView.PlayerScore ps : r.players) {
					boolean mvp = ps.name.equals(r.mvp);
					String dmgLine = I18n.translate(mvp ? "rapbattle.broadcast.round_damage_mvp" : "rapbattle.broadcast.round_damage", ps.name, ps.score);
					ctx.drawText(tr, BattleHud.fit(tr, dmgLine, cw - 14), cx + 9, ry + 1, mvp ? GOLD : TEXT, false);
					ry += 10;
				}
			}
			shine(ctx, cx, cy, cw, ph, (now - verdictAt(v) - 150) / 500f, 0x22FFFFFF);
		}

		// Judge's decision: the whole comment, wrapped, never cut. Bottom centre, where the hotbar was. Slides up.
		int lw = Math.min(380, w - 12), lx = 0;
		beginPanel(ctx, w, h, lw);
		float up = easeOutCubic((now - revealAt) / 350f);
		ctx.getMatrices().translate(0, (1 - up) * 40);
		List<OrderedText> lines = tr.wrapLines(Text.literal("«" + r.comment + "»"), lw - 18);
		String notes = r.overdressed ? I18n.translate("rapbattle.hud.note_overdressed") : "";
		String mine = "";
		for (BattleView.PlayerScore ps : r.players) if (ps.name.equals(ClientBattle.me())) mine = I18n.translate("rapbattle.hud.you_said", ps.transcript);
		int qh = 10 + lines.size() * 10 + (notes.isEmpty() ? 0 : 11) + (mine.isEmpty() ? 0 : 11) + 4;
		int ly = -6 - qh;
		tab(ctx, tr, I18n.translate("rapbattle.broadcast.judge_verdict"), lx, ly, GOLD, DARK, up);
		panel(ctx, lx, ly, lw, qh, GOLD, up);
		int ty = ly + 7;
		for (OrderedText line : lines) {
			ctx.drawText(tr, line, lx + 9, ty, alphaOf(TEXT, up), false);
			ty += 10;
		}
		if (!notes.isEmpty()) {
			ctx.drawText(tr, notes, lx + 9, ty + 1, alphaOf(DIM, up), false);
			ty += 11;
		}
		if (!mine.isEmpty()) ctx.drawText(tr, BattleHud.fit(tr, mine, lw - 18), lx + 9, ty + 1, alphaOf(0xFF6F7590, up), false);
		ctx.getMatrices().popMatrix();
	}

	// ------------------------------------------------------------------ bystanders

	static void spectator(DrawContext ctx, TextRenderer tr, BattleView v, int w, int h) {
		int under = scoreboard(ctx, tr, v, w, true);
		String line;
		int color = DIM;
		switch (v.phase) {
			case "LOBBY" -> line = I18n.translate("rapbattle.broadcast.spec_lobby");
			case "PREP" -> line = I18n.translate("rapbattle.broadcast.spec_prep", v.mobName);
			case "TIMELINE" -> {
				BattleView.Row r = ClientBattle.liveRow();
				line = r == null ? "" : I18n.translate("rapbattle.broadcast.spec_reading", r.reader);
				color = r != null && r.mob ? ORANGE : CYAN;
			}
			case "JUDGING" -> line = I18n.translate("rapbattle.broadcast.spec_judging");
			case "ROUND_RESULT", "RESULT" -> {
				BattleView.Result r = v.result;
				if (r == null) return;
				boolean win = r.playersWin;
				if (v.pvp) {
					line = r.finalResult
						? I18n.translate(r.knockout ? "rapbattle.broadcast.spec_pvp_final_ko" : "rapbattle.broadcast.spec_pvp_final", r.winner, r.respectTeam, r.respectMob)
						: I18n.translate("rapbattle.broadcast.spec_pvp_round", r.winner, v.round, r.respectTeam, r.respectMob);
				} else if (win) {
					line = r.finalResult
						? I18n.translate(r.knockout ? "rapbattle.broadcast.spec_players_final_ko" : "rapbattle.broadcast.spec_players_final", r.respectTeam, r.respectMob)
						: I18n.translate("rapbattle.broadcast.spec_players_round", v.round, r.respectTeam, r.respectMob);
				} else {
					line = r.finalResult
						? I18n.translate(r.knockout ? "rapbattle.broadcast.spec_mob_final_ko" : "rapbattle.broadcast.spec_mob_final", r.respectTeam, r.respectMob)
						: I18n.translate("rapbattle.broadcast.spec_mob_round", v.round, r.respectTeam, r.respectMob);
				}
				if (!r.mvp.isEmpty()) line = I18n.translate("rapbattle.broadcast.spec_mvp_suffix", line, r.mvp);
				color = win ? GREEN : RED;
			}
			default -> line = "";
		}
		// "● LIVE" badge and the line: a broadcast lower strip under the scoreboard.
		Text live = bold(I18n.translate("rapbattle.broadcast.live"));
		int bw = tr.getWidth(live) + 18;
		int lw = line.isEmpty() ? 0 : Math.min(w - 40 - bw, tr.getWidth(line) + 16);
		int total = bw + lw, x = w / 2 - total / 2, y = under + 4;
		ctx.fill(x + 2, y + 2, x + total + 2, y + 15, 0x60000000);
		ctx.fill(x, y, x + bw, y + 13, 0xFFB01E2A);
		float pulse = 0.5f + 0.5f * (float) Math.abs(Math.sin(System.currentTimeMillis() / 400.0));
		ctx.fill(x + 5, y + 4, x + 10, y + 9, alphaOf(WHITE, pulse));
		ctx.drawText(tr, live, x + 13, y + 3, WHITE, false);
		if (lw > 0) {
			ctx.fillGradient(x + bw, y, x + total, y + 13, PANEL_TOP, 0xF0060609);
			ctx.fill(x + bw, y + 12, x + total, y + 13, alphaOf(color, 0.8f));
			ctx.drawText(tr, BattleHud.fit(tr, line, lw - 12), x + bw + 8, y + 3, color, false);
		}
	}
}
