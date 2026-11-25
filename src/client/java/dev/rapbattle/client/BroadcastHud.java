package dev.rapbattle.client;

import dev.rapbattle.net.BattleView;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * "TV broadcast" battle screen (design C): a fighting-game scoreboard on top with the Respect bars, the battle table at
 * the bottom centre in place of the hotbar, and the judge's breakdown on the result screens. The middle of the screen
 * stays free for the stage.
 */
public final class BroadcastHud {
	private BroadcastHud() {}


	private static final int TEXT = 0xFFE6E6F0, DIM = 0xFF8A8FA8, YELLOW = 0xFFFFD84A, GREEN = 0xFF7CF0A0, ORANGE = 0xFFF4A261,
		RED = 0xFFFF5A5A, CYAN = 0xFF6EC6F0, DARK = 0xFF14141E, GOLD = 0xFFE8C14A, PANEL = 0xEC0C0E16, BLUE = 0xFF1D4FA8,
		CRIMSON = 0xFFA81D2E;

	// ------------------------------------------------------------------ scoreboard

	static int scoreboard(DrawContext ctx, TextRenderer tr, BattleView v, int w, boolean showRounds) {
		int y = BattleHud.top(v);
		int side = Math.min(170, (w - 120) / 2), mid = 100;
		int x0 = w / 2 - side - mid / 2;
		int h = 38;
		int[] shown = shownRespect(v);
		int max = Math.max(1, v.result != null && v.result.respectMax > 0 ? v.result.respectMax : v.respectMax);
		// Team
		ctx.fill(x0, y, x0 + side, y + h, BLUE);
		StringBuilder team = new StringBuilder();
		if (v.pvp && !v.team.isEmpty()) team.append(v.team.get(0).name);
		else for (BattleView.Member m : v.team) team.append(team.length() == 0 ? "" : " + ").append(m.name);
		String t = BattleHud.fit(tr, team.toString(), side - 12);
		ctx.drawText(tr, t, x0 + side - 6 - tr.getWidth(t), y + 4, 0xFFFFFFFF, true);
		String sub = v.team.isEmpty() ? "" : v.team.get(0).outfit + (v.team.get(0).streak > 1 ? " · серия " + v.team.get(0).streak : "");
		ctx.drawText(tr, BattleHud.fit(tr, sub, side - 12), x0 + side - 6 - tr.getWidth(BattleHud.fit(tr, sub, side - 12)), y + 15, 0xFFCFE0FF, false);
		// Team bar grows from the centre to the left, like a fighting game.
		respectBar(ctx, tr, x0 + 6, y + 27, side - 12, shown[0], max, true, 0);
		// Centre: VS / rounds and the crowd meter
		int cx0 = x0 + side;
		ctx.fill(cx0, y, cx0 + mid, y + h, 0xF0101018);
		BattleView.Result res = v.result;
		boolean verdict = res != null && ("ROUND_RESULT".equals(v.phase) || "RESULT".equals(v.phase)) && revealed(v) >= res.hits.size();
		if (verdict) {
			// After the breakdown: the verdict takes the centre of the scoreboard.
			String top = res.finalResult ? "ФИНАЛ" : "РАУНД " + v.round;
			String big = verdictWord(v, res);
			ctx.drawCenteredTextWithShadow(tr, top, cx0 + mid / 2, y + 3, DIM);
			ctx.drawCenteredTextWithShadow(tr, BattleHud.fit(tr, big, mid - 4), cx0 + mid / 2, y + 14, iWon(v, res) || v.spectator && v.pvp ? YELLOW : RED);
		} else {
			String vs = showRounds && v.rounds > 1 ? v.roundWinsPlayers + " : " + v.roundWinsMob : "VS";
			ctx.drawCenteredTextWithShadow(tr, vs, cx0 + mid / 2, y + 4, YELLOW);
			int mw = mid - 20, mx = cx0 + 10, my = y + 18;
			ctx.fill(mx, my, mx + mw, my + 5, 0xFF3A1016);
			int fill = (int) (mw * (0.5f + Math.max(-100, Math.min(100, v.hype)) / 200f));
			ctx.fill(mx, my, mx + fill, my + 5, 0xFF4AA8FF);
		}
		ctx.drawCenteredTextWithShadow(tr, "РЕСПЕКТ", cx0 + mid / 2, y + 27, 0xFF8A8FA8);
		// Mob
		int rx0 = cx0 + mid;
		ctx.fill(rx0, y, rx0 + side, y + h, CRIMSON);
		String mob = v.mobTeam.size() > 1 ? v.mobName + " и кореша" : v.mobName;
		ctx.drawText(tr, BattleHud.fit(tr, mob, side - 12), rx0 + 6, y + 4, 0xFFFFFFFF, true);
		ctx.drawText(tr, BattleHud.fit(tr, v.rankArmor + " · " + v.rankStreet, side - 12), rx0 + 6, y + 15, BattleHud.opaque(v.rankColor), false);
		respectBar(ctx, tr, rx0 + 6, y + 27, side - 12, shown[1], max, false, 1);
		// Combo under the centre while it burns.
		if ("TIMELINE".equals(v.phase) && v.combo >= 3) {
			String c = "КОМБО x" + (v.combo >= 6 ? "2" : "1.5") + " · " + v.combo;
			BattleHud.scaledText(ctx, tr, c, w / 2, y + h + 3, 1.2f, YELLOW, true);
		}
		return y + h;
	}

	private static final String[] WIN_WORDS = {"РАЗЪЕБАЛ", "УНИЧТОЖИЛ", "РАЗНЁС", "ВЫНЕС", "ПОХОРОНИЛ", "ЗАКОПАЛ",
		"РАСТОПТАЛ", "СЛОМАЛ", "ПОРВАЛ", "СТЁР В ПЫЛЬ", "УРОНИЛ", "ЗАДАВИЛ"};
	private static final String[] TEAM_WIN_WORDS = {"РАЗЪЕБАЛИ", "УНИЧТОЖИЛИ", "РАЗНЕСЛИ", "ВЫНЕСЛИ", "ПОХОРОНИЛИ",
		"ЗАКОПАЛИ", "РАСТОПТАЛИ", "ПОРВАЛИ"};
	private static final String[] LOSE_WORDS = {"ТЕБЯ РАЗЪЕБАЛИ", "ТЕБЯ ВЫНЕСЛИ", "ТЕБЯ ЗАКОПАЛИ", "ТЕБЯ ПОРВАЛИ",
		"ТЕБЯ РАЗНЕСЛИ", "ТЫ ВСЁ", "ТЕБЯ СТЁРЛИ", "ПОЗОР"};
	private static final String[] TEAM_LOSE_WORDS = {"ВАС РАЗЪЕБАЛИ", "ВАС ВЫНЕСЛИ", "ВАС ЗАКОПАЛИ", "ВАС ПОРВАЛИ",
		"ВАС РАЗНЕСЛИ", "ВЫ ВСЁ", "ПОЗОР"};
	private static final String[] ROUND_WIN = {"ВАШ", "ЗА ВАМИ", "ВЗЯЛИ", "ЕСТЬ!"};
	private static final String[] ROUND_LOSE = {"МОБА", "ЗА МОБОМ", "МИМО", "СЛИЛИ"};

	/** Did I win: my team against the mob, or me against the other player. */
	static boolean iWon(BattleView v, BattleView.Result res) {
		return v.pvp ? ClientBattle.me().equals(res.winner) : res.playersWin;
	}

	/** A different word every battle, but steady while the screen is up. */
	static String verdictWord(BattleView v, BattleView.Result res) {
		int seed = Math.abs((v.id * 31 + v.round) * 17 + res.comment.hashCode());
		if (v.pvp && v.spectator) return "ПОБЕДА: " + res.winner.toUpperCase(java.util.Locale.ROOT);
		boolean team = v.team.size() > 1 && !v.pvp;
		boolean won = iWon(v, res);
		String[] pool = !res.finalResult ? (won ? ROUND_WIN : ROUND_LOSE)
			: won ? (team ? TEAM_WIN_WORDS : WIN_WORDS) : (team ? TEAM_LOSE_WORDS : LOSE_WORDS);
		return pool[seed % pool.length];
	}

	/** Last shown value per bar, and the latest drop to flash next to it. */
	private static final int[] lastShown = {-1, -1};
	private static final int[] dropAmount = {0, 0};
	private static final long[] dropAt = {0, 0};

	private static void respectBar(DrawContext ctx, TextRenderer tr, int x, int y, int bw, int value, int max, boolean fromRight, int slot) {
		long now = System.currentTimeMillis();
		if (lastShown[slot] >= 0 && value < lastShown[slot]) {
			dropAmount[slot] = (now - dropAt[slot] < 600 ? dropAmount[slot] : 0) + (lastShown[slot] - value);
			dropAt[slot] = now;
		}
		lastShown[slot] = value;
		int bh = 7;
		ctx.fill(x, y, x + bw, y + bh, 0xFF0A0C14);
		int f = (int) (bw * Math.max(0, Math.min(1, value / (float) max)));
		int col = value <= max * 0.25f ? 0xFFFF5A5A : value <= max * 0.5f ? 0xFFFFB84A : 0xFF7CF0A0;
		// The recent drop shows as a white chunk that fades out.
		long since = now - dropAt[slot];
		if (since < 700 && dropAmount[slot] > 0) {
			int chunk = (int) (bw * Math.min(1, dropAmount[slot] / (float) max));
			int a = (int) (255 * (1 - since / 700f));
			int cx = fromRight ? x + bw - f - chunk : x + f;
			ctx.fill(Math.max(x, cx), y, Math.min(x + bw, cx + chunk), y + bh, (a << 24) | 0xFFFFFF);
		}
		if (fromRight) ctx.fill(x + bw - f, y, x + bw, y + bh, col);
		else ctx.fill(x, y, x + f, y + bh, col);
		String num = String.valueOf(value);
		int tx = fromRight ? x + 2 : x + bw - tr.getWidth(num) - 2;
		ctx.drawText(tr, num, tx, y - 1, 0xFFFFFFFF, true);
		if (since < 900 && dropAmount[slot] > 0) {
			String d = "-" + dropAmount[slot];
			int rise = (int) (since / 60);
			int dx = fromRight ? x + bw - f - tr.getWidth(d) - 2 : x + f + 2;
			ctx.drawText(tr, d, Math.max(x, Math.min(x + bw - tr.getWidth(d), dx)), y - 9 - rise, 0xFFFFD84A, true);
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
		int under = scoreboard(ctx, tr, v, w, true);
		if (v.totalBeats <= 0) return;
		int beat = Math.max(0, ClientBattle.beat());
		String me = ClientBattle.me();

		int cur = -1;
		for (int i = 0; i < v.rows.size(); i++) {
			BattleView.Row r = v.rows.get(i);
			if (beat >= r.startBeat && beat < r.startBeat + 4) cur = i;
		}
		BattleView.Row live = cur >= 0 ? v.rows.get(cur) : null;
		BattleView.Segment seg = null;
		for (BattleView.Segment s : v.segments) if (beat >= s.leadInBeat && beat < s.endBeat) seg = s;

		// Countdown: a big number in the middle, nothing else.
		if (beat < 4) {
			BattleHud.scaledText(ctx, tr, String.valueOf(4 - beat), w / 2, h / 2 - 40, 5f, YELLOW, true);
			BattleHud.scaledText(ctx, tr, "бит: " + ClientBattle.styleName(), w / 2, h / 2 + 8, 1.3f, DIM, true);
			return;
		}

		String tag;
		int tagColor;
		BattleView.Row first, second;
		boolean leadIn = false;
		if (live != null && live.mob) {
			tag = "ХОД: " + (live.reader == null ? v.mobName : live.reader).toUpperCase(Locale.ROOT) + " · перевод с " + v.mobLanguage;
			tagColor = ORANGE;
			first = live;
			second = cur + 1 < v.rows.size() && v.rows.get(cur + 1).mob ? v.rows.get(cur + 1) : null;
		} else if (live != null) {
			boolean mine = me.equals(live.reader);
			tag = (mine ? "ТВОЙ ХОД" : "ЧИТАЕТ " + live.reader.toUpperCase(Locale.ROOT)) + " · СТРОКА " + live.line + "/" + live.lines;
			tagColor = mine ? GREEN : CYAN;
			first = live;
			second = cur + 1 < v.rows.size() ? v.rows.get(cur + 1) : null;
		} else if (seg != null) {
			boolean mine = me.equals(seg.reader);
			leadIn = true;
			tag = (mine ? "ГОТОВЬСЯ" : "ГОТОВИТСЯ " + seg.reader.toUpperCase(Locale.ROOT)) + " · " + Math.max(1, seg.startBeat - beat);
			tagColor = mine ? GREEN : CYAN;
			first = rowAtBeat(v, seg.startBeat);
			int fi = first == null ? -1 : v.rows.indexOf(first);
			second = fi >= 0 && fi + 1 < v.rows.size() ? v.rows.get(fi + 1) : null;
		} else {
			return;
		}

		String fx = live == null ? null : live.effect;
		int k = live == null ? -1 : beat - live.startBeat;
		float fr = ClientBattle.beatFrac();
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
		int ly = -3 - rowsH;

		// Tag bar: who is on, and REC on the right end of the same bar.
		ctx.fill(lx, ly - 13, lx + lw, ly, 0xEC101420);
		tag = BattleHud.fit(tr, tag, lw - (ClientBattle.recording() ? 52 : 14));
		int tw = tr.getWidth(tag) + 12;
		ctx.fill(lx, ly - 13, lx + tw, ly, tagColor);
		ctx.drawText(tr, tag, lx + 6, ly - 10, DARK, false);
		if (ClientBattle.recording()) {
			boolean blink = System.currentTimeMillis() / 500 % 2 == 0;
			ctx.drawText(tr, "● REC", lx + lw - tr.getWidth("● REC") - 6, ly - 10, blink ? RED : 0xFF802020, false);
		}

		// The two rows (now, next), cells like the first version: three cells (mob words, or beat dots), then the rhyme.
		ctx.fill(lx, ly, lx + lw, ly + rowsH, PANEL);
		boolean blind = "ink".equals(fx) || "blind".equals(fx);
		if (first != null) cellRow(ctx, tr, first, cw, lx + PAD, ly + PAD, !leadIn && live == first, leadIn ? -1 : k, me);
		if (second != null && !blind) cellRow(ctx, tr, second, cw, lx + PAD, ly + PAD + ROW + GAP, false, -1, me);

		// Bottom edge of the panel: the beat pulse, or your mic level while you record. Part of the panel, no gap.
		int by = ly + rowsH;
		ctx.fill(lx, by, lx + lw, by + 3, 0xFF2A2F44);
		if (ClientBattle.recording()) {
			int lev = (int) (Math.min(1f, ClientBattle.micLevel() * 2.5f) * lw);
			ctx.fill(lx, by, lx + lev, by + 3, RED);
		} else {
			ctx.fill(lx, by, lx + (int) ((1 - fr) * lw), by + 3, tagColor);
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

	private static final int ROW = 14, GAP = 2, PAD = 3, MIN_COL = 46, MIN_WORD = 72;

	/** Column widths so every mob word and rhyme fits whole. */
	private static int[] widths(TextRenderer tr, BattleView.Row... rows) {
		int[] cw = {MIN_COL, MIN_COL, MIN_COL, MIN_WORD};
		for (BattleView.Row r : rows) {
			if (r == null) continue;
			if (r.mob) for (int c = 0; c < 3; c++) if (r.cols[c] != null) cw[c] = Math.max(cw[c], tr.getWidth(r.cols[c]) + 10);
			if (r.word != null) cw[3] = Math.max(cw[3], tr.getWidth(r.word) + 14);
			if (!r.mob && r.reader != null) cw[0] = Math.max(cw[0], Math.min(90, tr.getWidth(r.reader) + 10));
		}
		return cw;
	}

	/** One table row: three dark cells (mob words, or beat dots for players) and the coloured rhyme cell. */
	private static void cellRow(DrawContext ctx, TextRenderer tr, BattleView.Row r, int[] cw, int x, int y, boolean live, int k, String me) {
		int cellBg = live ? 0xFF2C3350 : 0xFF1E2333;
		for (int c = 0; c < 3; c++) {
			int col = cw[c];
			boolean hit = live && k == c;
			ctx.fill(x, y, x + col, y + ROW, r.mob && hit ? 0xFF4A2F66 : cellBg);
			if (hit && r.mob) ctx.fill(x, y + ROW - 1, x + col, y + ROW, ORANGE);
			String text = r.mob ? (r.cols[c] == null ? "" : r.cols[c]) : (c == 0 && !live && r.reader != null ? r.reader : null);
			if (text != null && !text.isEmpty()) {
				int color = r.mob ? (live ? (hit ? YELLOW : TEXT) : DIM) : (me.equals(r.reader) ? GREEN : CYAN);
				String t = BattleHud.fit(tr, text, col - 4);
				ctx.drawText(tr, t, x + col / 2 - tr.getWidth(t) / 2, y + 3, color, false);
			} else if (text == null) {
				int dx = x + col / 2, dy = y + ROW / 2, sz = hit ? 3 : 1;
				ctx.fill(dx - sz, dy - sz, dx + sz, dy + sz, hit ? YELLOW : 0xFF55597A);
			}
			x += col + GAP;
		}
		int word = cw[3];
		String shown = shownWord(r, live ? k : -1);
		int cell = "украдено".equals(shown) ? 0xFF3A1A1A : "сгорела".equals(shown) ? 0xFFE86A17 : BattleHud.opaque(r.color);
		ctx.fill(x, y, x + word, y + ROW, cell);
		if (live) ctx.drawStrokedRectangle(x - 1, y - 1, word + 2, ROW + 2, k == 3 ? YELLOW : 0xFFFFFFFF);
		String wt = BattleHud.fit(tr, shown, word - 6);
		ctx.drawText(tr, wt, x + word / 2 - tr.getWidth(wt) / 2, y + 3, "украдено".equals(shown) ? RED : DARK, false);
	}

	private static BattleView.Row rowAtBeat(BattleView v, int beat) {
		for (BattleView.Row r : v.rows) if (r.startBeat == beat) return r;
		return null;
	}

	/** The rhyme as the mob's tricks let you see it. */
	private static String shownWord(BattleView.Row r, int k) {
		if (r.effect == null || r.mob) return r.word;
		return switch (r.effect) {
			case "hidden" -> "???";
			case "stolen" -> "украдено";
			case "burn" -> k >= 2 ? "сгорела" : r.word;
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
		int dots = (int) (System.currentTimeMillis() / 400 % 4);
		int lx = 0, lw = 300, ly = -30;
		beginPanel(ctx, w, h, lw);
		ctx.fill(lx, ly - 12, lx + tr.getWidth("РЕШЕНИЕ СУДЬИ") + 12, ly, GOLD);
		ctx.drawText(tr, "РЕШЕНИЕ СУДЬИ", lx + 6, ly - 10, DARK, false);
		ctx.fill(lx, ly, lx + lw, ly + 30, PANEL);
		BattleHud.scaledText(ctx, tr, "Судья думает" + ".".repeat(dots), lx + 8, ly + 8, 1.4f, YELLOW, false);
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

		// How it was won, in a strip under the scoreboard, once the breakdown has run.
		if (allIn && !roundOnly) {
			String how = r.knockout ? "НОКАУТ" : "ПО ОЧКАМ  " + r.respectTeam + " : " + r.respectMob;
			int tw = tr.getWidth(how) + 12;
			ctx.fill(w / 2 - tw / 2, under, w / 2 + tw / 2, under + 12, 0xF0101018);
			ctx.drawCenteredTextWithShadow(tr, how, w / 2, under + 2, r.knockout ? ORANGE : (won ? YELLOW : RED));
		}

		// The breakdown on the left: line by line, newest at the bottom.
		int cw = Math.min(210, w / 2 - 20), cx = 8, cy = under + 16;
		int maxRows = Math.max(4, Math.min(10, (h - 120 - cy) / 11));
		int from = Math.max(0, n - maxRows);
		int rows = n - from;
		int statsH = allIn ? 16 + (r.bestLine.isEmpty() ? 0 : 11) + (r.players.size() > 1 ? r.players.size() * 10 : 0) : 0;
		ctx.fill(cx, cy, cx + cw, cy + 14 + rows * 11 + statsH, PANEL);
		ctx.fill(cx, cy, cx + 3, cy + 14 + rows * 11 + statsH, GOLD);
		ctx.drawText(tr, "РАЗБОР", cx + 8, cy + 3, GOLD, false);
		int ry = cy + 14;
		for (int i = from; i < n; i++) {
			BattleView.Hit hit = r.hits.get(i);
			String target = !v.pvp ? (hit.toMob ? "мобу" : "вам")
				: hit.toMob ? v.mobName : (v.team.isEmpty() ? "" : v.team.get(0).name);
			String dmg = hit.damage == 0 ? "0" : target + " -" + hit.damage;
			int dw = tr.getWidth(dmg);
			String who = hit.who.isEmpty() ? "" : hit.who + " ";
			String text = BattleHud.fit(tr, who + hit.note, cw - 18 - dw);
			int col = !hit.toMob ? 0xFFFF8A8A : hit.damage == 0 ? DIM : hit.punch ? ORANGE : TEXT;
			ctx.drawText(tr, text, cx + 8, ry, col, false);
			ctx.drawText(tr, dmg, cx + cw - 6 - dw, ry, hit.toMob ? (hit.damage == 0 ? DIM : YELLOW) : RED, false);
			ry += 11;
		}
		if (allIn) {
			ry += 3;
			ctx.fill(cx + 8, ry - 2, cx + cw - 6, ry - 1, 0xFF2A2F44);
			ctx.drawText(tr, BattleHud.fit(tr, "урон " + r.dealt + " · в бит " + r.accuracy + "% · комбо " + r.bestCombo, cw - 14), cx + 8, ry + 1, CYAN, false);
			ry += 11;
			if (!r.bestLine.isEmpty()) {
				ctx.drawText(tr, BattleHud.fit(tr, "лучшая: " + r.bestLine, cw - 14), cx + 8, ry + 1, DIM, false);
				ry += 11;
			}
			if (r.players.size() > 1) {
				for (BattleView.PlayerScore ps : r.players) {
					boolean mvp = ps.name.equals(r.mvp);
					ctx.drawText(tr, BattleHud.fit(tr, (mvp ? "♛ " : "") + ps.name + "  урон за раунд " + ps.score, cw - 14), cx + 8, ry + 1, mvp ? GOLD : TEXT, false);
					ry += 10;
				}
			}
		}

		// Judge's decision: the whole comment, wrapped, never cut. Bottom centre, where the hotbar was.
		int lw = Math.min(380, w - 12), lx = 0;
		beginPanel(ctx, w, h, lw);
		List<OrderedText> lines = tr.wrapLines(Text.literal("«" + r.comment + "»"), lw - 16);
		String notes = r.overdressed ? "перебор с прикидом: награда урезана" : "";
		String mine = "";
		for (BattleView.PlayerScore ps : r.players) if (ps.name.equals(ClientBattle.me())) mine = "ты: " + ps.transcript;
		int ph = 8 + lines.size() * 10 + (notes.isEmpty() ? 0 : 11) + (mine.isEmpty() ? 0 : 11) + 4;
		int ly = -ph;
		ctx.fill(lx, ly - 12, lx + tr.getWidth("РЕШЕНИЕ СУДЬИ") + 12, ly, GOLD);
		ctx.drawText(tr, "РЕШЕНИЕ СУДЬИ", lx + 6, ly - 10, DARK, false);
		ctx.fill(lx, ly, lx + lw, ly + ph, PANEL);
		int ty = ly + 6;
		for (OrderedText line : lines) {
			ctx.drawText(tr, line, lx + 8, ty, TEXT, false);
			ty += 10;
		}
		if (!notes.isEmpty()) {
			ctx.drawText(tr, notes, lx + 8, ty + 1, DIM, false);
			ty += 11;
		}
		if (!mine.isEmpty()) ctx.drawText(tr, BattleHud.fit(tr, mine, lw - 16), lx + 8, ty + 1, 0xFF6F7590, false);
		ctx.getMatrices().popMatrix();
	}

	// ------------------------------------------------------------------ bystanders

	static void spectator(DrawContext ctx, TextRenderer tr, BattleView v, int w, int h) {
		int under = scoreboard(ctx, tr, v, w, true);
		String line;
		int color = DIM;
		switch (v.phase) {
			case "LOBBY" -> line = "сейчас начнётся баттл";
			case "PREP" -> line = v.mobName + " готовится";
			case "TIMELINE" -> {
				BattleView.Row r = ClientBattle.liveRow();
				line = r == null ? "" : "читает: " + r.reader;
				color = r != null && r.mob ? ORANGE : CYAN;
			}
			case "JUDGING" -> line = "судья думает...";
			case "ROUND_RESULT", "RESULT" -> {
				BattleView.Result r = v.result;
				if (r == null) return;
				boolean win = r.playersWin;
				line = (v.pvp ? "победил " + r.winner : win ? "игроки взяли" : "моб взял")
					+ (r.finalResult ? (v.pvp ? "" : " баттл") + (r.knockout ? " нокаутом" : "") : " раунд " + v.round)
					+ "  " + r.respectTeam + " : " + r.respectMob
					+ (r.mvp.isEmpty() ? "" : " · MVP " + r.mvp);
				color = win ? GREEN : RED;
			}
			default -> line = "";
		}
		if (line.isEmpty()) return;
		int tw = tr.getWidth(line) + 10;
		ctx.fill(w / 2 - tw / 2, under + 2, w / 2 + tw / 2, under + 14, 0xC0101018);
		ctx.drawCenteredTextWithShadow(tr, line, w / 2, under + 4, color);
	}
}
