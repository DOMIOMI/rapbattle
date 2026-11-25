package dev.rapbattle.client;

import dev.rapbattle.battle.Rank;
import dev.rapbattle.net.BattleView;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.PlayerSkinDrawer;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.resource.language.I18n;
import net.minecraft.entity.EntityType;
import net.minecraft.item.ItemStack;
import net.minecraft.item.SpawnEggItem;
import net.minecraft.registry.Registries;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.List;
import java.util.Locale;

/** Draws every battle screen: lobby, rap/fight choice, countdown, the beat table, judge, round and final result, invites. */
public final class BattleHud {
	private BattleHud() {}

	// Palette (ARGB; alpha must be non-zero in 1.21.6+).
	private static final int BG = 0xE0141826, CELL = 0xFF1E2333, CELL_ACTIVE = 0xFF2C3350, CELL_MOB_ACTIVE = 0xFF4A2F66,
		TEXT = 0xFFE6E6F0, DIM = 0xFF8A8FA8, YELLOW = 0xFFFFD84A, GREEN = 0xFF7CF0A0, ORANGE = 0xFFF4A261,
		RED = 0xFFFF5A5A, CYAN = 0xFF6EC6F0, DARK_TEXT = 0xFF14141E, GOLD = 0xFFE8C14A;

	private static final int COL_W = 64, WORD_W = 96, ROW_H = 12, VISIBLE_ROWS = 5;

	public static void render(DrawContext ctx, RenderTickCounter tick) {
		ClientBattle.pump();
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.options.hudHidden) return;
		TextRenderer tr = mc.textRenderer;
		int w = ctx.getScaledWindowWidth(), h = ctx.getScaledWindowHeight();

		BattleView v = ClientBattle.view;
		if (v != null) {
			if (v.spectator) {
				if (ClientConfig.get().spectatorHud) BroadcastHud.spectator(ctx, tr, v, w, h);
			} else {
				if (Intro.active()) {
					drawCards(ctx, tr, v, w, h);
					if (ClientBattle.choice != null) drawChoice(ctx, tr, ClientBattle.choice, w, h);
					return;
				}
				switch (v.phase) {
					case "LOBBY" -> drawLobby(ctx, tr, v, w, h);
					case "PREP" -> drawPrep(ctx, tr, v, w, h);
					case "TIMELINE" -> BroadcastHud.timeline(ctx, tr, v, w, h);
					case "JUDGING" -> BroadcastHud.judging(ctx, tr, v, w, h);
					case "ROUND_RESULT", "RESULT" -> BroadcastHud.result(ctx, tr, v, w, h);
					default -> {}
				}
			}
		}
		if (ClientBattle.choice != null) drawChoice(ctx, tr, ClientBattle.choice, w, h);
		if (ClientBattle.invite != null) drawInvite(ctx, tr, ClientBattle.invite, w);
	}

	// ------------------------------------------------------------------ helpers

	static void scaledText(DrawContext ctx, TextRenderer tr, String s, int cx, int y, float scale, int color, boolean centered) {
		ctx.getMatrices().pushMatrix();
		float x = centered ? cx - tr.getWidth(s) * scale / 2f : cx;
		ctx.getMatrices().translate(x, y);
		ctx.getMatrices().scale(scale, scale);
		ctx.drawText(tr, s, 0, 0, 0xFF000000, false);
		ctx.getMatrices().translate(-0.6f, -0.6f);
		ctx.drawText(tr, s, 0, 0, color, false);
		ctx.getMatrices().popMatrix();
	}

	static void box(DrawContext ctx, int x, int y, int w, int h, int fill, int border) {
		ctx.fill(x, y, x + w, y + h, fill);
		if (border != 0) ctx.drawStrokedRectangle(x, y, w, h, border);
	}

	static String fit(TextRenderer tr, String s, int width) {
		if (s == null) return "";
		if (tr.getWidth(s) <= width) return s;
		while (s.length() > 1 && tr.getWidth(s + "…") > width) s = s.substring(0, s.length() - 1);
		return s + "…";
	}

	static int opaque(int rgb) { return 0xFF000000 | rgb; }

	/** Rank plate in the colour of its armour material: "ЖЕЛЕЗНЫЙ · Уличный". */
	private static int rankPlate(DrawContext ctx, TextRenderer tr, BattleView v, int cx, int y) {
		String s = v.rankArmor + " · " + v.rankStreet;
		int tw = tr.getWidth(s) + 10;
		int x = cx - tw / 2;
		box(ctx, x, y, tw, 12, 0xF0101018, opaque(v.rankColor));
		ctx.fill(x + 1, y + 1, x + 4, y + 11, opaque(v.rankColor));
		ctx.drawText(tr, s, x + 7, y + 2, opaque(v.rankColor), false);
		return tw;
	}

	/** Vertical offset so our header never sits under a vanilla boss bar. */
	static int top(BattleView v) { return v.boss ? 22 : 0; }

	private static void header(DrawContext ctx, TextRenderer tr, BattleView v, int w) {
		int y0 = top(v);
		StringBuilder team = new StringBuilder();
		for (BattleView.Member m : v.team) team.append(team.length() == 0 ? "" : " + ").append(m.name);
		String vs = "  VS  ";
		String mob = v.mobTeam.size() > 1 ? I18n.translate("rapbattle.hud.and_crew", v.mobName) : v.mobName;
		String rank = "  ·  " + v.rankArmor;
		String rounds = v.rounds > 1 ? I18n.translate("rapbattle.hud.header_rounds", v.round, v.rounds, v.roundWinsPlayers, v.roundWinsMob) : "";
		int total = tr.getWidth(team + vs + mob + rank + rounds);
		int x = w / 2 - total / 2;
		ctx.fill(x - 4, y0 + 2, x + total + 4, y0 + 13, 0xC0101018);
		ctx.drawText(tr, team.toString(), x, y0 + 4, CYAN, false);
		x += tr.getWidth(team.toString());
		ctx.drawText(tr, vs, x, y0 + 4, TEXT, false);
		x += tr.getWidth(vs);
		ctx.drawText(tr, mob, x, y0 + 4, ORANGE, false);
		x += tr.getWidth(mob);
		ctx.drawText(tr, rank, x, y0 + 4, opaque(v.rankColor), false);
		x += tr.getWidth(rank);
		if (!rounds.isEmpty()) ctx.drawText(tr, rounds, x, y0 + 4, YELLOW, false);
	}

	static void drawHead(DrawContext ctx, String name, int x, int y, int size) {
		MinecraftClient mc = MinecraftClient.getInstance();
		PlayerListEntry e = mc.getNetworkHandler() == null ? null : mc.getNetworkHandler().getPlayerListEntry(name);
		if (e != null) PlayerSkinDrawer.draw(ctx, e.getSkinTextures(), x, y, size);
		else ctx.fill(x, y, x + size, y + size, 0xFF6B4E3D);
	}

	static void drawMobIcon(DrawContext ctx, String mobType, int cx, int cy, float scale) {
		EntityType<?> type = Registries.ENTITY_TYPE.get(Identifier.tryParse(mobType));
		SpawnEggItem egg = SpawnEggItem.forEntity(type);
		if (egg == null) return;
		ctx.getMatrices().pushMatrix();
		ctx.getMatrices().translate(cx - 8 * scale, cy - 8 * scale);
		ctx.getMatrices().scale(scale, scale);
		ctx.drawItem(new ItemStack(egg), 0, 0);
		ctx.getMatrices().popMatrix();
	}

	// ------------------------------------------------------------------ lobby (VS screen)

	private static void drawLobby(DrawContext ctx, TextRenderer tr, BattleView v, int w, int h) {
		int mid = w / 2;
		ctx.fill(0, 0, mid, h, 0xF0101A3C);
		ctx.fill(mid, 0, w, h, v.boss ? 0xF0280A30 : 0xF03C1016);
		ctx.fill(mid - 2, 0, mid, h, 0xFF4AA8FF);
		ctx.fill(mid, 0, mid + 2, h, v.boss ? 0xFF8B5CF6 : 0xFFFF4A5A);

		int cy = h / 2 - 14;
		// Team on the left: head, nick, outfit.
		int n = v.team.size();
		int head = n > 2 ? 24 : 32;
		int gap = 14;
		int startX = mid / 2 - (n * head + (n - 1) * gap) / 2;
		for (int i = 0; i < n; i++) {
			BattleView.Member m = v.team.get(i);
			int x = startX + i * (head + gap);
			ctx.drawStrokedRectangle(x - 1, cy - head / 2 - 1, head + 2, head + 2, 0xFF4AA8FF);
			drawHead(ctx, m.name, x, cy - head / 2, head);
			ctx.drawCenteredTextWithShadow(tr, m.name, x + head / 2, cy + head / 2 + 4, CYAN);
			ctx.drawCenteredTextWithShadow(tr, I18n.translate("rapbattle.hud.outfit", m.outfit), x + head / 2, cy + head / 2 + 15, DIM);
			ctx.drawCenteredTextWithShadow(tr, statLine(m), x + head / 2, cy + head / 2 + 26, TEXT);
			if (!m.title.isEmpty()) ctx.drawCenteredTextWithShadow(tr, "«" + m.title + "»", x + head / 2, cy + head / 2 + 37, 0xFFD08CF0);
		}

		// Mob on the right: icon, name, rank plate, mood, tags, record, team.
		int mx = mid + mid / 2;
		drawMobIcon(ctx, v.mobType, mx, cy - 10, 2.5f);
		ctx.drawCenteredTextWithShadow(tr, v.mobTeam.size() > 1 ? I18n.translate("rapbattle.hud.and_crew", v.mobName) : v.mobName, mx, cy + 14, ORANGE);
		rankPlate(ctx, tr, v, mx, cy + 25);
		String mood = v.mobMood.toUpperCase(Locale.ROOT);
		int mw = tr.getWidth(mood);
		ctx.fill(mx - mw / 2 - 3, cy + 41, mx + mw / 2 + 3, cy + 52, 0xFFD02B3A);
		ctx.drawText(tr, mood, mx - mw / 2, cy + 43, 0xFFFFFFFF, false);
		int ty = cy + 56;
		if (!v.tags.isEmpty()) {
			ctx.drawCenteredTextWithShadow(tr, String.join(" · ", v.tags), mx, ty, GOLD);
			ty += 11;
		}
		if (v.mobWins + v.mobLosses > 0) {
			String rec = v.mobStreak > 1 ? I18n.translate("rapbattle.hud.mob_record_win_streak", v.mobWins, v.mobLosses, v.mobStreak)
				: v.mobStreak < -1 ? I18n.translate("rapbattle.hud.mob_record_lose_streak", v.mobWins, v.mobLosses, -v.mobStreak)
				: I18n.translate("rapbattle.hud.mob_record", v.mobWins, v.mobLosses);
			ctx.drawCenteredTextWithShadow(tr, rec, mx, ty, TEXT);
			ty += 11;
		}
		if (v.speciesMobWins + v.speciesPlayerWins > 0) {
			ctx.drawCenteredTextWithShadow(tr, I18n.translate("rapbattle.hud.species_record", v.mobSpecies, v.speciesMobWins, v.speciesPlayerWins), mx, ty, DIM);
			ty += 11;
		}
		if (!v.trick.isEmpty()) {
			ctx.drawCenteredTextWithShadow(tr, fit(tr, I18n.translate("rapbattle.hud.trick", v.trick), mid - 16), mx, ty, 0xFF9FE0FF);
			ty += 11;
		}
		if (v.mobTeam.size() > 1) ctx.drawCenteredTextWithShadow(tr, fit(tr, String.join(", ", v.mobTeam), mid - 20), mx, ty, DIM);

		scaledText(ctx, tr, "VS", mid, cy - 16, 3.5f, YELLOW, true);
		if (v.boss) {
			scaledText(ctx, tr, I18n.translate("rapbattle.hud.boss"), mid, cy + 18, 1.6f, 0xFFB48CFF, true);
			String warn = I18n.translate("rapbattle.hud.boss_warn");
			int ww = tr.getWidth(warn) + 10;
			ctx.fill(mid - ww / 2, h - 52, mid + ww / 2, h - 40, 0xE0300010);
			ctx.drawCenteredTextWithShadow(tr, warn, mid, h - 50, RED);
		}

		long secs = (v.lobbyMsLeft + 999) / 1000;
		String bottom = ClientBattle.isChallenger()
			? I18n.translate("rapbattle.hud.lobby_challenger", secs)
			: I18n.translate("rapbattle.hud.lobby_wait", secs);
		ctx.drawCenteredTextWithShadow(tr, bottom, mid, h - 30, DIM);
	}

	private static String statLine(BattleView.Member m) {
		if (m.wins + m.losses == 0) return I18n.translate("rapbattle.hud.first_battle");
		if (m.streak > 1 && m.mvp > 0) return I18n.translate("rapbattle.hud.stats_streak_mvp", m.wins, m.losses, m.streak, m.mvp);
		if (m.streak > 1) return I18n.translate("rapbattle.hud.stats_streak", m.wins, m.losses, m.streak);
		if (m.mvp > 0) return I18n.translate("rapbattle.hud.stats_mvp", m.wins, m.losses, m.mvp);
		return I18n.translate("rapbattle.hud.stats", m.wins, m.losses);
	}

	/** Intro: letterbox bars and the fighter card of whoever the camera is showing (see {@link FighterCard}). */
	private static void drawCards(DrawContext ctx, TextRenderer tr, BattleView v, int w, int h) {
		float t = Intro.elapsedMs();
		int bar = Math.round(24 * FighterCard.easeOutCubic(FighterCard.clamp01(t / 350f)));
		ctx.fill(0, 0, w, bar, 0xFF000000);
		ctx.fill(0, h - bar, w, h, 0xFF000000);
		String skip = I18n.translate("rapbattle.hud.skip_intro");
		ctx.drawText(tr, skip, w - tr.getWidth(skip) - 6, h - 16, DIM, false);
		String card = Intro.card();
		if (card == null || v.team.isEmpty()) return;
		boolean mobCard = card.equals("mob");
		float from = mobCard ? Intro.MOB_FROM : Intro.TEAM_FROM, to = mobCard ? Intro.MOB_TO : Intro.TEAM_TO;
		FighterCard.Fighter f;
		if (v.pvp) {
			// Player battle: the other player in the red corner, you in the blue one.
			int me = MinecraftClient.getInstance().player == null ? -1 : MinecraftClient.getInstance().player.getId();
			BattleView.Member m = null;
			for (BattleView.Member mm : v.team) if (mobCard == (mm.entityId != me)) m = mm;
			if (m == null) return;
			f = playerCard(m, mobCard);
		} else if (mobCard) {
			f = mobCard(v);
		} else {
			// Several players share the team window, one card each.
			int n = v.team.size();
			float span = (to - from) / n;
			int idx = Math.max(0, Math.min(n - 1, (int) ((Intro.progress() - from) / span)));
			from += idx * span;
			to = from + span;
			f = playerCard(v.team.get(idx), false);
		}
		float now = Intro.elapsedMs();
		FighterCard.draw(ctx, tr, f, now - Intro.msOf(from), Intro.msOf(to) - now, Intro.msOf(to - from), w, h);
	}

	private static FighterCard.Fighter mobCard(BattleView v) {
		MinecraftClient mc = MinecraftClient.getInstance();
		String sub = v.mobTeam.size() > 1 ? I18n.translate("rapbattle.hud.and_crew", v.mobSpecies) : v.mobSpecies;
		String streakLabel = I18n.translate("rapbattle.hud.card.streak");
		String extra = v.mobStreak > 1 ? "×" + v.mobStreak : v.mobStreak < -1 ? "−" + (-v.mobStreak) : "—";
		int extraColor = v.mobStreak > 1 ? ORANGE : DIM;
		return new FighterCard.Fighter(true, v.mobName, sub,
			I18n.translate("rapbattle.hud.card.rank"), v.rankArmor + " · " + v.rankStreet, opaque(v.rankColor), v.rankLevel,
			I18n.translate("rapbattle.hud.card.mood"), v.mobMood, 0xFFFF8A7A,
			v.mobWins, v.mobLosses, streakLabel, extra, extraColor,
			v.mobWins + v.mobLosses == 0, v.boss,
			mc.world == null ? null : mc.world.getEntityById(v.mobEntityId), null, v.mobType);
	}

	private static FighterCard.Fighter playerCard(BattleView.Member m, boolean red) {
		MinecraftClient mc = MinecraftClient.getInstance();
		int tier = Math.max(0, Math.min(6, m.outfitLevel));
		int tierColor = tier <= 0 ? 0xFF9A9A9A : opaque(Rank.ofLevel(tier).color);
		String sub = m.title.isEmpty() ? I18n.translate("rapbattle.hud.card.player") : I18n.translate("rapbattle.hud.card.title_quoted", m.title);
		String extraLabel, extra;
		int extraColor;
		if (m.streak > 1) {
			extraLabel = I18n.translate("rapbattle.hud.card.streak");
			extra = "×" + m.streak;
			extraColor = ORANGE;
		} else if (m.mvp > 0) {
			extraLabel = "MVP";
			extra = "×" + m.mvp;
			extraColor = GOLD;
		} else {
			extraLabel = I18n.translate("rapbattle.hud.card.streak");
			extra = "—";
			extraColor = DIM;
		}
		return new FighterCard.Fighter(red, m.name, sub,
			I18n.translate("rapbattle.hud.card.fit"), m.outfit, tierColor, tier,
			I18n.translate("rapbattle.hud.card.best"), m.best > 0 ? String.valueOf(m.best) : "—", GOLD,
			m.wins, m.losses, extraLabel, extra, extraColor,
			m.wins + m.losses == 0, false,
			mc.world == null ? null : mc.world.getEntityById(m.entityId), m.name, null);
	}

	private static void drawPrep(DrawContext ctx, TextRenderer tr, BattleView v, int w, int h) {
		header(ctx, tr, v, w);
		if (v.rounds > 1) scaledText(ctx, tr, I18n.translate("rapbattle.hud.round_upper", v.round), w / 2, h / 2 - 40, 3.0f, YELLOW, true);
		String dots = ".".repeat((int) (System.currentTimeMillis() / 400 % 4));
		String who = v.pvp ? I18n.translate("rapbattle.hud.prep_pvp", v.challenger, v.mobName, dots)
			: v.mobTeam.size() > 1 ? I18n.translate("rapbattle.hud.prep_crew", v.mobName, dots)
			: I18n.translate("rapbattle.hud.prep_mob", v.mobName, dots);
		ctx.drawCenteredTextWithShadow(tr, who, w / 2, h / 2 - 6, DIM);
	}

	// ------------------------------------------------------------------ timeline

	private static void drawTimeline(DrawContext ctx, TextRenderer tr, BattleView v, int w, int h) {
		header(ctx, tr, v, w);
		if (v.totalBeats <= 0) return;
		int beat = Math.max(0, ClientBattle.beat());
		String me = ClientBattle.me();

		int cur = 0;
		boolean active = false;
		String title;
		int titleColor;
		String tab;
		BattleView.Segment seg = null;
		for (BattleView.Segment s : v.segments) if (beat >= s.leadInBeat && beat < s.endBeat) seg = s;

		BattleView.Row live = null;
		for (int i = 0; i < v.rows.size(); i++) {
			BattleView.Row r = v.rows.get(i);
			if (beat >= r.startBeat && beat < r.startBeat + 4) {
				cur = i;
				active = true;
				live = r;
			}
		}

		boolean mobTab = false;
		if (beat < 4) {
			title = String.valueOf(4 - beat);
			titleColor = YELLOW;
			tab = I18n.translate("rapbattle.hud.translation_from", v.mobLanguage);
			mobTab = true;
		} else if (beat < v.mobEndBeat) {
			String reader = live != null && live.reader != null ? live.reader : v.mobName;
			title = I18n.translate("rapbattle.hud.turn", reader.toUpperCase(Locale.ROOT));
			titleColor = ORANGE;
			tab = I18n.translate("rapbattle.hud.translation_from", v.mobLanguage);
			mobTab = true;
		} else if (live != null && !live.mob) {
			boolean mine = me.equals(live.reader);
			title = mine ? I18n.translate("rapbattle.hud.your_turn") : I18n.translate("rapbattle.hud.turn", live.reader.toUpperCase(Locale.ROOT));
			titleColor = mine ? GREEN : CYAN;
			tab = mine ? I18n.translate("rapbattle.hud.line_progress", live.line, live.lines) : I18n.translate("rapbattle.hud.reading", live.reader);
		} else if (seg != null) {
			boolean mine = me.equals(seg.reader);
			title = v.team.size() > 1 && seg.startBeat != firstOwnRow(v, seg.reader) ? I18n.translate("rapbattle.hud.get_ready_team")
				: (mine ? I18n.translate("rapbattle.hud.your_turn") : I18n.translate("rapbattle.hud.turn", seg.reader.toUpperCase(Locale.ROOT)));
			titleColor = mine ? GREEN : CYAN;
			int left = Math.max(1, (seg.startBeat - beat));
			tab = mine ? I18n.translate("rapbattle.hud.get_ready_count", left) : I18n.translate("rapbattle.hud.getting_ready", seg.reader);
		} else {
			title = I18n.translate("rapbattle.hud.judge_thinking_upper");
			titleColor = YELLOW;
			tab = null;
		}
		if (!active && seg != null && beat < seg.startBeat) {
			for (int i = 0; i < v.rows.size(); i++) {
				if (v.rows.get(i).startBeat >= seg.startBeat) {
					cur = i;
					break;
				}
			}
		}

		scaledText(ctx, tr, title, w / 2, 18 + top(v), beat < 4 ? 3.0f : 1.6f, titleColor, true);
		if (live != null && live.mob) drawBubble(ctx, tr, live, w, h);
		drawTable(ctx, tr, v, w, h, cur, active, beat, tab, mobTab);
	}

	private static int firstOwnRow(BattleView v, String reader) {
		for (BattleView.Row r : v.rows) if (reader.equals(r.reader) && !r.mob) return r.startBeat;
		return -1;
	}

	private static void drawBubble(DrawContext ctx, TextRenderer tr, BattleView.Row row, int w, int h) {
		int bw = 130;
		List<OrderedText> lines = tr.wrapLines(Text.literal(row.fullLine), bw - 8);
		int bh = 14 + lines.size() * 9;
		int x = w / 2 + 34, y = h / 2 - 60;
		box(ctx, x, y, bw, bh, 0xF2F4F4F0, row.punch ? 0xFFE04040 : 0xFF2A2A2A);
		ctx.fill(x - 4, y + bh - 10, x, y + bh - 6, 0xF2F4F4F0);
		ctx.drawText(tr, row.reader == null ? "" : row.reader, x + 4, y + 3, 0xFFC0283A, false);
		for (int i = 0; i < lines.size(); i++) ctx.drawText(tr, lines.get(i), x + 4, y + 13 + i * 9, 0xFF202020, false);
	}

	private static void drawTable(DrawContext ctx, TextRenderer tr, BattleView v, int w, int h, int cur, boolean active, int beat, String tab, boolean mobTab) {
		int tableW = COL_W * 3 + WORD_W + 3 * 2 + 4;
		int tableH = VISIBLE_ROWS * (ROW_H + 1) + 4;
		int x0 = w / 2 - tableW / 2;
		BattleView.Row liveRow = active ? v.rows.get(cur) : null;
		String fx = liveRow == null ? null : liveRow.effect;
		float fr = ClientBattle.beatFrac();
		int k = active ? beat - liveRow.startBeat : -1;
		// Mob tricks that move the whole table.
		if ("shake".equals(fx)) x0 += (int) (Math.sin(System.currentTimeMillis() / 25.0) * 3);
		if ("ram".equals(fx) && k == 0) x0 += (int) ((1 - fr) * 28);
		int y0 = h - 62 - tableH; // above hotbar, hearts and hunger
		box(ctx, x0, y0, tableW, tableH, BG, 0xFF2A2F44);

		if (tab != null) {
			int tw = tr.getWidth(tab) + 6;
			ctx.fill(x0, y0 - 10, x0 + tw, y0, mobTab ? 0xFF7A3E1C : 0xFF1F3A2A);
			ctx.drawText(tr, tab, x0 + 3, y0 - 9, mobTab ? ORANGE : GREEN, false);
		}

		float frac = ClientBattle.beatFrac();
		int pulse = (int) ((1 - frac) * tableH);
		ctx.fill(x0 - 5, y0 + tableH - pulse, x0 - 3, y0 + tableH, GREEN);

		int kInBar = active ? beat - v.rows.get(cur).startBeat : -1;
		String me = ClientBattle.me();

		for (int vi = 0; vi < VISIBLE_ROWS; vi++) {
			int idx = cur + vi;
			if (idx >= v.rows.size()) break;
			BattleView.Row r = v.rows.get(idx);
			int y = y0 + 2 + vi * (ROW_H + 1);
			boolean live = active && vi == 0;
			int x = x0 + 2;
			for (int c = 0; c < 3; c++) {
				boolean hit = live && kInBar == c;
				int fill = live ? (r.mob && hit ? CELL_MOB_ACTIVE : CELL_ACTIVE) : CELL;
				ctx.fill(x, y, x + COL_W, y + ROW_H, fill);
				if (hit && r.mob) ctx.fill(x, y + ROW_H - 1, x + COL_W, y + ROW_H, ORANGE);
				String text;
				if (r.mob) text = r.cols[c] == null ? "" : r.cols[c];
				else if (c == 0 && !live && r.reader != null) text = r.reader;
				else text = null;
				if (text != null && !text.isEmpty()) {
					int color = r.mob ? (live ? TEXT : DIM) : (me.equals(r.reader) ? GREEN : CYAN);
					String s = fit(tr, text, COL_W - 4);
					ctx.drawText(tr, s, x + COL_W / 2 - tr.getWidth(s) / 2, y + 2, color, false);
				} else if (text == null) {
					int dx = x + COL_W / 2, dy = y + ROW_H / 2;
					int sz = hit ? 2 : 1;
					ctx.fill(dx - sz, dy - sz, dx + sz, dy + sz, hit ? YELLOW : 0xFF55597A);
				}
				x += COL_W + 2;
			}
			String shown = r.word;
			int cellColor = opaque(r.color);
			boolean stolen = false;
			if (r.effect != null && !r.mob) {
				switch (r.effect) {
					case "hidden" -> shown = "???";
					case "stolen" -> {
						shown = I18n.translate("rapbattle.hud.word_stolen");
						cellColor = 0xFF3A1A1A;
						stolen = true;
					}
					case "burn" -> {
						if (live && kInBar >= 2) {
							shown = I18n.translate("rapbattle.hud.word_burned");
							cellColor = 0xFFE86A17;
						}
					}
					case "illusion" -> {
						if (live && kInBar < 3) {
							BattleView.Row fake = v.rows.get(Math.floorMod((int) (System.currentTimeMillis() / 300) + idx, v.rows.size()));
							shown = fake.word;
						}
					}
					default -> {}
				}
			}
			ctx.fill(x, y, x + WORD_W, y + ROW_H, cellColor);
			if (live && kInBar == 3) ctx.drawStrokedRectangle(x - 1, y - 1, WORD_W + 2, ROW_H + 2, YELLOW);
			else if (live) ctx.drawStrokedRectangle(x - 1, y - 1, WORD_W + 2, ROW_H + 2, 0xFFFFFFFF);
			String word = fit(tr, shown, WORD_W - 4);
			ctx.drawText(tr, word, x + WORD_W / 2 - tr.getWidth(word) / 2, y + 2, stolen ? RED : DARK_TEXT, false);
			// Ink and blindness: only the live row stays readable.
			if (vi > 0 && ("ink".equals(fx) || "blind".equals(fx))) ctx.fill(x0 + 1, y - 1, x0 + tableW - 1, y + ROW_H + 1, 0xF0050508);
		}
		// Llama spit over the start of the row.
		if ("spit".equals(fx) && k <= 1) {
			for (int i = 0; i < 7; i++) {
				int sx = x0 + 10 + (i * 37) % (COL_W * 3), sy = y0 + 3 + (i * 11) % (ROW_H * 2);
				ctx.fill(sx, sy, sx + 14 + i % 3 * 4, sy + 8, 0xD0B8C9A0);
			}
		}

		if (ClientBattle.recording()) {
			int mx = x0 + tableW + 4;
			int mh = (int) (Math.min(1f, ClientBattle.micLevel() * 2.5f) * tableH);
			ctx.fill(mx, y0, mx + 3, y0 + tableH, 0xFF2A2F44);
			ctx.fill(mx, y0 + tableH - mh, mx + 3, y0 + tableH, RED);
			boolean blink = System.currentTimeMillis() / 500 % 2 == 0;
			ctx.drawText(tr, "● REC", mx + 6, y0, blink ? RED : 0xFF802020, false);
		}
	}

	// ------------------------------------------------------------------ judge and results

	private static void drawJudging(DrawContext ctx, TextRenderer tr, BattleView v, int w, int h) {
		header(ctx, tr, v, w);
		int dots = (int) (System.currentTimeMillis() / 400 % 4);
		String base = I18n.translate("rapbattle.hud.judge_thinking_base");
		scaledText(ctx, tr, base + ".".repeat(dots), w / 2 - (int) (tr.getWidth(base + "...") * 2.2f / 2), 20 + top(v), 2.2f, YELLOW, false);
	}

	private static void drawResult(DrawContext ctx, TextRenderer tr, BattleView v, int w, int h) {
		BattleView.Result r = v.result;
		if (r == null) return;
		boolean roundOnly = !r.finalResult;
		boolean roundWon = r.playersWin;
		String title;
		int color;
		if (roundOnly) {
			title = I18n.translate(roundWon ? "rapbattle.hud.round_won" : "rapbattle.hud.round_lost", v.round);
			color = roundWon ? GREEN : RED;
		} else {
			title = I18n.translate(r.playersWin ? "rapbattle.hud.final_win" : (v.team.size() > 1 ? "rapbattle.hud.final_lose_team" : "rapbattle.hud.final_lose_solo"));
			color = r.playersWin ? YELLOW : RED;
		}
		scaledText(ctx, tr, title, w / 2, 14 + top(v), roundOnly ? 2.6f : 3.5f, color, true);
		String sub = v.rounds > 1 ? I18n.translate("rapbattle.hud.sub_rounds", v.roundWinsPlayers, v.roundWinsMob)
			: (r.knockout ? I18n.translate("rapbattle.hud.sub_knockout") : I18n.translate("rapbattle.hud.sub_points", r.respectTeam, r.respectMob));
		scaledText(ctx, tr, sub, w / 2, 48 + top(v), 1.4f, TEXT, true);

		int pw = Math.min(300, w - 20), px = w / 2 - pw / 2;
		List<OrderedText> lines = tr.wrapLines(Text.literal("«" + r.comment + "»"), pw - 16);
		boolean many = r.players.size() > 1;
		int listH = many ? 4 + r.players.size() * 10 : 0;
		String notes = r.outfitBonus > 0 && r.overdressed ? I18n.translate("rapbattle.hud.note_both", r.outfitBonus)
			: r.outfitBonus > 0 ? I18n.translate("rapbattle.hud.note_outfit_bonus", r.outfitBonus)
			: r.overdressed ? I18n.translate("rapbattle.hud.note_overdressed") : "";
		int ph = 44 + listH + lines.size() * 10 + 6 + (notes.isEmpty() ? 0 : 10);
		int py = h - 66 - ph;
		box(ctx, px, py, pw, ph, 0xF0101018, GOLD);
		String judgeLabel = I18n.translate("rapbattle.hud.judge_label");
		ctx.fill(px, py - 9, px + tr.getWidth(judgeLabel) + 6, py, GOLD);
		ctx.drawText(tr, judgeLabel, px + 3, py - 8, DARK_TEXT, false);

		int half = (pw - 24) / 2;
		int bx1 = px + 8, bx2 = px + 16 + half, by = py + 6;
		boolean teamBetter = r.respectTeam > r.respectMob;
		box(ctx, bx1, by, half, 30, 0xFF1A1E2C, teamBetter ? 0 : GOLD);
		box(ctx, bx2, by, half, 30, 0xFF1A1E2C, teamBetter ? GOLD : 0);
		ctx.drawCenteredTextWithShadow(tr, fit(tr, r.mobLabel.toUpperCase(Locale.ROOT), half - 6), bx1 + half / 2, by + 3, ORANGE);
		scaledText(ctx, tr, String.valueOf(r.respectMob), bx1 + half / 2, by + 14, 1.4f, TEXT, true);
		ctx.drawCenteredTextWithShadow(tr, fit(tr, r.teamLabel, half - 6), bx2 + half / 2, by + 3, CYAN);
		scaledText(ctx, tr, String.valueOf(r.respectTeam), bx2 + half / 2, by + 14, 1.4f, TEXT, true);

		int y = py + 42;
		if (many) {
			for (BattleView.PlayerScore ps : r.players) {
				boolean mvp = ps.name.equals(r.mvp);
				String line = I18n.translate(mvp ? "rapbattle.hud.player_score_mvp" : "rapbattle.hud.player_score", ps.name, ps.score);
				ctx.drawCenteredTextWithShadow(tr, line, w / 2, y, mvp ? GOLD : TEXT);
				y += 10;
			}
			y += 4;
		}
		for (OrderedText line : lines) {
			ctx.drawText(tr, line, w / 2 - tr.getWidth(line) / 2, y, TEXT, false);
			y += 10;
		}
		if (!notes.isEmpty()) ctx.drawCenteredTextWithShadow(tr, notes.trim(), w / 2, y + 2, DIM);

		String me = ClientBattle.me();
		for (BattleView.PlayerScore ps : r.players) {
			if (ps.name.equals(me)) ctx.drawCenteredTextWithShadow(tr, fit(tr, I18n.translate("rapbattle.hud.you_said", ps.transcript), w - 40), w / 2, py + ph + 4, DIM);
		}
		header(ctx, tr, v, w);
	}

	// ------------------------------------------------------------------ spectator

	private static void drawSpectator(DrawContext ctx, TextRenderer tr, BattleView v, int w, int h) {
		header(ctx, tr, v, w);
		String line;
		int color = DIM;
		switch (v.phase) {
			case "LOBBY" -> line = I18n.translate("rapbattle.hud.spec_lobby");
			case "PREP" -> line = I18n.translate("rapbattle.hud.spec_prep", v.mobName);
			case "TIMELINE" -> {
				BattleView.Row r = ClientBattle.liveRow();
				line = r == null ? "" : I18n.translate("rapbattle.hud.spec_turn", r.reader);
				color = r != null && r.mob ? ORANGE : CYAN;
			}
			case "JUDGING" -> line = I18n.translate("rapbattle.hud.spec_judging");
			case "ROUND_RESULT", "RESULT" -> {
				BattleView.Result r = v.result;
				if (r == null) return;
				boolean win = r.playersWin;
				line = r.finalResult
					? I18n.translate(win ? "rapbattle.hud.spec_final_win" : "rapbattle.hud.spec_final_lose", r.respectTeam, r.respectMob)
					: I18n.translate(win ? "rapbattle.hud.spec_round_win" : "rapbattle.hud.spec_round_lose", v.round, r.respectTeam, r.respectMob);
				if (!r.mvp.isEmpty()) line = I18n.translate("rapbattle.hud.spec_mvp_suffix", line, r.mvp);
				color = win ? GREEN : RED;
			}
			default -> line = "";
		}
		if (!line.isEmpty()) {
			int tw = tr.getWidth(line) + 8;
			ctx.fill(w / 2 - tw / 2, 15 + top(v), w / 2 + tw / 2, 27 + top(v), 0xC0101018);
			ctx.drawCenteredTextWithShadow(tr, line, w / 2, 17 + top(v), color);
		}
	}

	// ------------------------------------------------------------------ choice and invite

	private static void drawChoice(DrawContext ctx, TextRenderer tr, BattleView c, int w, int h) {
		int cx = w / 2, y = h / 2 + 14;
		String name = c.mobName + " (" + c.mobSpecies + ")";
		String l1, l2;
		int l1c;
		if (!c.challengeFrom.isEmpty()) {
			name = c.challengeFrom + ": " + c.mobName;
			l1 = I18n.translate("rapbattle.hud.choice_answer");
			l1c = YELLOW;
			l2 = I18n.translate("rapbattle.hud.choice_answer_sub");
		} else if (c.allowed) {
			l1 = I18n.translate(c.boss ? "rapbattle.hud.choice_rap_boss" : "rapbattle.hud.choice_rap");
			l1c = c.boss ? 0xFFB48CFF : GREEN;
			l2 = I18n.translate("rapbattle.hud.choice_fight");
		} else {
			l1 = I18n.translate("rapbattle.hud.choice_refused", c.refusal);
			l1c = RED;
			l2 = I18n.translate("rapbattle.hud.choice_refused_sub", c.requiredOutfit, c.yourOutfit);
		}
		int bw = Math.max(Math.max(tr.getWidth(name), tr.getWidth(l1)), tr.getWidth(l2)) + 16;
		int bh = 50;
		box(ctx, cx - bw / 2, y, bw, bh, 0xE8101018, opaque(c.rankColor));
		ctx.drawCenteredTextWithShadow(tr, name, cx, y + 4, ORANGE);
		rankPlate(ctx, tr, c, cx, y + 14);
		ctx.drawCenteredTextWithShadow(tr, l1, cx, y + 29, l1c);
		ctx.drawCenteredTextWithShadow(tr, l2, cx, y + 39, DIM);
	}

	private static void drawInvite(DrawContext ctx, TextRenderer tr, BattleView inv, int w) {
		String s1 = inv.pvp ? I18n.translate("rapbattle.hud.invite_pvp", inv.challenger)
			: I18n.translate("rapbattle.hud.invite_mob", inv.challenger, inv.mobName, inv.rankArmor);
		String s2 = inv.pvp ? I18n.translate("rapbattle.hud.invite_pvp_keys")
			: inv.allowed ? I18n.translate(inv.boss ? "rapbattle.hud.invite_boss_keys" : "rapbattle.hud.invite_keys")
			: I18n.translate("rapbattle.hud.invite_outfit", inv.requiredOutfit, inv.yourOutfit);
		int bw = Math.max(tr.getWidth(s1), tr.getWidth(s2)) + 12;
		int x = w / 2 - bw / 2, y = 30;
		box(ctx, x, y, bw, 26, 0xE0101018, inv.allowed ? YELLOW : RED);
		ctx.drawCenteredTextWithShadow(tr, s1, w / 2, y + 4, YELLOW);
		ctx.drawCenteredTextWithShadow(tr, s2, w / 2, y + 15, inv.allowed ? TEXT : RED);
	}
}
