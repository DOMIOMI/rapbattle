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

import java.util.ArrayList;
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
		if (mc.currentScreen instanceof net.minecraft.client.gui.screen.DeathScreen) return; // never under the death screen
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
		if (ClientBattle.isChallenger()) {
			String t1 = (v.topic == 1 ? "§e" : "§7") + I18n.translate("rapbattle.hud.topic_1");
			String t2 = (v.topic == 2 ? "§e" : "§7") + I18n.translate("rapbattle.hud.topic_2");
			String t3 = (v.topic == 3 ? "§e" : "§7") + I18n.translate("rapbattle.hud.topic_3");
			ctx.drawCenteredTextWithShadow(tr, I18n.translate("rapbattle.hud.topic_pick", t1 + "  ·  " + t2 + "  ·  " + t3), mid, h - 42, TEXT);
		}
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
		int bar = Math.round(24 * Ui.easeOutCubic((t / 350f)));
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
		String sub = m.title.isEmpty()
			? (m.style.isEmpty() ? I18n.translate("rapbattle.hud.card.player") : I18n.translate("rapbattle.hud.card.player_style", m.style))
			: (m.style.isEmpty() ? I18n.translate("rapbattle.hud.card.title_quoted", m.title)
				: I18n.translate("rapbattle.hud.card.title_quoted", m.title) + " · " + m.style);
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
			// Strong mobs leave no time to prepare: the word shows up only shortly before its line.
			if (!r.mob && v.revealBeats > 0 && r.startBeat - beat > v.revealBeats) shown = "???";
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
			String key = r.playersWin ? "rapbattle.hud.final_win" : (v.team.size() > 1 ? "rapbattle.hud.final_lose_team" : "rapbattle.hud.final_lose_solo");
			if (!v.allowSwearing) key += ".clean";
			title = I18n.translate(key);
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
				String fkey = win ? "rapbattle.hud.spec_final_win" : "rapbattle.hud.spec_final_lose";
				if (!v.allowSwearing) fkey += ".clean";
				line = r.finalResult
					? I18n.translate(fkey, r.respectTeam, r.respectMob)
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

	private static Object choiceShown, inviteShown;
	private static long choiceAt, inviteAt;

	/** "Rap or fight" card: 3 clean lines with rank/armor/mic item icons, no bold. */
	private static void drawChoice(DrawContext ctx, TextRenderer tr, BattleView c, int w, int h) {
		long now = System.currentTimeMillis();
		if (choiceShown != c) {
			choiceShown = c;
			choiceAt = now;
		}
		long since = now - choiceAt;
		String name = !c.challengeFrom.isEmpty()
			? c.challengeFrom + ": " + c.mobName + " (" + c.mobSpecies + ")"
			: c.mobName + " (" + c.mobSpecies + ")";

		boolean challenge = !c.challengeFrom.isEmpty();
		int accent = challenge ? Ui.GOLD : !c.allowed ? 0xFF6A6A78 : c.boss ? Ui.BOSS : Ui.RED;
		// Refused: a red "REFUSED" headline plus the reason with item icons ("need X, yours Y").
		String reason = c.allowed ? "" : (!c.refusalReason.isEmpty() ? c.refusalReason : c.refusal);
		String note = challenge ? I18n.translate("rapbattle.hud.choice_answer_sub")
			: c.allowed && !c.gateWarning.isEmpty() ? c.gateWarning : "";

		// Buttons: [R] battle (only when allowed), [N] walk away, [LMB] fight.
		String fightLabel = I18n.translate("rapbattle.hud.btn_fight");
		List<String[]> buttons = new ArrayList<>();
		if (c.allowed || challenge) {
			buttons.add(new String[]{RapBattleClient.rapKeyName(),
				I18n.translate(challenge ? "rapbattle.hud.btn_answer" : c.boss ? "rapbattle.hud.btn_rap_boss" : "rapbattle.hud.btn_rap"), "g"});
		}
		buttons.add(new String[]{RapBattleClient.declineKeyName(),
			I18n.translate(challenge ? "rapbattle.hud.btn_decline_challenge" : "rapbattle.hud.btn_decline"), "d"});
		buttons.add(new String[]{RapBattleClient.attackKeyName(), fightLabel, "r"});
		int gap = 6, btnW = 0;
		for (String[] bt : buttons) btnW += keyButtonWidth(tr, bt[0], bt[1]) + gap;
		btnW -= gap;

		boolean hasIcons = !c.allowed && (c.needsMic || c.requiredArmorLevel > 0);
		int reasonW = tr.getWidth(reason) + (hasIcons ? (c.needsMic ? 20 : 38) : 0);
		int bw = Math.min(w - 16, Math.max(Math.max(tr.getWidth(name) + 34, btnW + 16),
			Math.max(reasonW + 24, Math.max(tr.getWidth(note) + 16, 210))));
		int bh = 26 + (c.allowed || challenge ? 0 : 30) + 20 + (note.isEmpty() ? 0 : 12) + 4;
		float in = Ui.easeOutBack(since / 320f);
		float a = Ui.clamp01(since / 150f);
		int cx = w / 2, x = cx - bw / 2;
		int y = Math.round(h / 2f + 22 + (1 - in) * 30);

		Ui.tab(ctx, tr, I18n.translate("rapbattle.hud.choice_title"), x, y, accent, Ui.WHITE, a);
		Ui.panel(ctx, x, y, bw, bh, accent, a);

		// Line 1: rank item icon + mob name & species
		ctx.getMatrices().pushMatrix();
		ctx.getMatrices().translate(x + 7, y + 4);
		ctx.getMatrices().scale(0.85f, 0.85f);
		ctx.drawItem(MobBadgeFx.rankIcon(c.rankLevel, c.boss), 0, 0);
		ctx.getMatrices().popMatrix();
		Ui.dropText(ctx, tr, Text.literal(BattleHud.fit(tr, name, bw - 30)), x + 24, y + 6, 1f, Ui.WHITE, accent, a, false);
		ctx.fill(x + 8, y + 20, x + bw - 8, y + 21, Ui.alphaOf(accent, 0.6f * a));

		int ly = y + 26;
		if (!c.allowed && !challenge) {
			Ui.text(ctx, tr, I18n.translate("rapbattle.hud.choice_refused_short"), x + 8, ly, 1.3f, RED, a, true);
			ly += 15;
			int textX = x + 8;
			if (c.needsMic) {
				drawSmallItem(ctx, new ItemStack(net.minecraft.item.Items.LIGHTNING_ROD), x + 8, ly - 2);
				textX += 15;
			} else if (c.requiredArmorLevel > 0) {
				drawSmallItem(ctx, MobBadgeFx.rankIcon(c.requiredArmorLevel, false), x + 8, ly - 2);
				drawSmallItem(ctx, c.yourArmorLevel > 0 ? MobBadgeFx.rankIcon(c.yourArmorLevel, false)
					: new ItemStack(net.minecraft.item.Items.BARRIER), x + 22, ly - 2);
				textX += 36;
			}
			Ui.text(ctx, tr, BattleHud.fit(tr, reason, bw - (textX - x) - 8), textX, ly, 1f, 0xFFE6C8C8, a, true);
			ly += 15;
		}

		// Button row, centred
		int bx = cx - btnW / 2;
		for (String[] bt : buttons) {
			int col = switch (bt[2]) { case "g" -> c.boss ? Ui.BOSS : GREEN; case "r" -> RED; default -> DIM; };
			bx += drawKeyButton(ctx, tr, bt[0], bt[1], bx, ly, col, a) + gap;
		}
		ly += 20;
		if (!note.isEmpty()) {
			Ui.text(ctx, tr, BattleHud.fit(tr, note, bw - 16), x + 8, ly, 1f, challenge ? DIM : 0xFFFFB84A, a, true);
		}

		// Draining bar along bottom edge
		float left = 1 - Ui.clamp01(since / (float) Math.max(1, c.choiceMs));
		ctx.fill(x, y + bh - 2, x + Math.round(bw * left), y + bh, Ui.alphaOf(Ui.mix(accent, Ui.WHITE, 0.4f), a));
		Ui.shine(ctx, x, y, bw, bh, (since - 250) / 450f, 0x22FFFFFF);
	}

	private static void drawSmallItem(DrawContext ctx, ItemStack stack, int x, int y) {
		ctx.getMatrices().pushMatrix();
		ctx.getMatrices().translate(x, y);
		ctx.getMatrices().scale(0.75f, 0.75f);
		ctx.drawItem(stack, 0, 0);
		ctx.getMatrices().popMatrix();
	}

	private static int keyButtonWidth(TextRenderer tr, String key, String label) {
		return Math.max(12, tr.getWidth(key) + 6) + 4 + tr.getWidth(label) + 6;
	}

	/** A keyboard-style button: a light key cap with the key name, then the action in the accent colour. */
	private static int drawKeyButton(DrawContext ctx, TextRenderer tr, String key, String label, int x, int y, int color, float a) {
		int bw = keyButtonWidth(tr, key, label), bh = 16;
		ctx.fill(x, y, x + bw, y + bh, Ui.alphaOf(Ui.mix(color, Ui.BG, 0.8f) | 0xFF000000, 0.9f * a));
		ctx.drawStrokedRectangle(x, y, bw, bh, Ui.alphaOf(color, 0.8f * a));
		int kw = Math.max(12, tr.getWidth(key) + 6);
		ctx.fill(x + 2, y + 2, x + 2 + kw, y + bh - 2, Ui.alphaOf(0xFFE6E6F0, a));
		ctx.fill(x + 2, y + bh - 4, x + 2 + kw, y + bh - 2, Ui.alphaOf(0xFF9A9AAA, a));
		Ui.text(ctx, tr, key, x + 2 + (kw - tr.getWidth(key)) / 2f, y + 4, 1f, Ui.DARK, a, false);
		Ui.text(ctx, tr, label, x + kw + 6, y + 4, 1f, color, a, true);
		return bw;
	}

	/** Invite to a battle (Y/N): drops in from the top, the keys in a second line, the frame pulses. */
	private static void drawInvite(DrawContext ctx, TextRenderer tr, BattleView inv, int w) {
		long now = System.currentTimeMillis();
		if (inviteShown != inv) {
			inviteShown = inv;
			inviteAt = now;
		}
		long since = now - inviteAt;
		String s1 = inv.pvp ? I18n.translate("rapbattle.hud.invite_pvp", inv.challenger)
			: I18n.translate("rapbattle.hud.invite_mob", inv.challenger, inv.mobName, inv.rankArmor);
		String s2 = inv.pvp ? I18n.translate("rapbattle.hud.invite_pvp_keys")
			: inv.allowed ? I18n.translate(inv.boss ? "rapbattle.hud.invite_boss_keys" : "rapbattle.hud.invite_keys")
			: I18n.translate("rapbattle.hud.invite_outfit", inv.requiredOutfit, inv.yourOutfit);
		int accent = !inv.allowed ? Ui.RED : inv.boss ? Ui.BOSS : Ui.GOLD;
		int bw = Math.min(w - 16, Math.max(tr.getWidth(s1), tr.getWidth(s2)) + 20);
		int bh = 28;
		float in = Ui.easeOutBack(since / 320f);
		float a = Ui.clamp01(since / 150f);
		int x = w / 2 - bw / 2, y = Math.round(44 - (1 - in) * 40);
		Ui.tab(ctx, tr, I18n.translate("rapbattle.hud.invite_title"), x, y, accent, Ui.DARK, a);
		Ui.panel(ctx, x, y, bw, bh, accent, a);
		float pulse = 0.4f + 0.4f * (float) Math.abs(Math.sin(now / 300.0));
		ctx.drawStrokedRectangle(x - 1, y - 1, bw + 2, bh + 2, Ui.alphaOf(accent, pulse * a));
		Ui.text(ctx, tr, BattleHud.fit(tr, s1, bw - 16), x + 8, y + 5, 1f, Ui.WHITE, a, true);
		Ui.text(ctx, tr, BattleHud.fit(tr, s2, bw - 16), x + 8, y + 16, 1f, inv.allowed ? Ui.mix(accent, Ui.WHITE, 0.5f) : RED, a, false);
		Ui.shine(ctx, x, y, bw, bh, (since - 250) / 450f, 0x22FFFFFF);
	}
}
