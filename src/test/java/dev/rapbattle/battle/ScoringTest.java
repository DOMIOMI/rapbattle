package dev.rapbattle.battle;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ScoringTest {

	private static List<Scoring.LineInput> makeLines(String player, int count, int score, int punches, int rebuttals, int[] beats) {
		List<Scoring.LineInput> out = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			boolean punch = i < punches;
			boolean reb = i >= punches && i < punches + rebuttals;
			int b = beats[i % beats.length];
			out.add(new Scoring.LineInput(player, "word" + i, score, punch, reb, "note", b, false));
		}
		return out;
	}

	@Test
	void rubricToScore() {
		assertEquals(10, Scoring.scoreFromRubric(2, 3));
		assertEquals(6, Scoring.scoreFromRubric(1, 2));
		assertEquals(0, Scoring.scoreFromRubric(2, 0));
		assertEquals(2, Scoring.scoreFromRubric(0, 1));
	}

	@Test
	void mobLiveBudgetMatchesExactTotal() {
		int[] dmg8 = Scoring.mobLiveDamages(38, List.of(false, false, true, false, false, false, true, false));
		int sum8 = 0;
		for (int d : dmg8) sum8 += d;
		assertEquals(38, sum8);
		assertTrue(dmg8[2] >= dmg8[0] * 1.5);

		int[] dmg10 = Scoring.mobLiveDamages(38, List.of(false, false, true, false, false, false, true, false, false, false));
		int sum10 = 0;
		for (int d : dmg10) sum10 += d;
		assertEquals(38, sum10);
	}

	@Test
	void hybridBeatUsesWhisperThenMicFallback() {
		assertEquals(2, Scoring.hybridBeat(0.20, 2, false, 400, 600));
		assertEquals(1, Scoring.hybridBeat(0.50, 2, false, 50, 600));
		assertEquals(2, Scoring.hybridBeat(null, 1, false, 100, 600));
		assertEquals(0, Scoring.hybridBeat(null, 0, false, 100, 600));
		assertEquals(1, Scoring.hybridBeat(null, 0, true, 300, 600));
	}

	@Test
	void averagePlayerVsGoldWinsSlightly() {
		List<Scoring.LineInput> lines = makeLines("Steve", 8, 6, 1, 1, new int[]{2, 2, 1, 0, 2, 1, 2, 0});
		Scoring.Bonuses bonuses = Scoring.computeBonuses(48, true, 0);
		Scoring.RoundOutcome o = Scoring.evaluateRound(Rank.GOLD, lines, bonuses, 6, 0);
		assertTrue(o.playersWinRound(), "Average player should slightly beat Gold: team=" + o.respectTeamAfter() + " mob=" + o.respectMobAfter());
		assertTrue(o.respectTeamAfter() > o.respectMobAfter());
		assertEquals(5, bonuses.total());
	}

	@Test
	void strongPlayerVsGoldIsKnockout() {
		List<Scoring.LineInput> lines = makeLines("Steve", 8, 8, 2, 2, new int[]{2, 2, 2, 2, 2, 2, 2, 1});
		Scoring.Bonuses bonuses = Scoring.computeBonuses(96, true, 0);
		Scoring.RoundOutcome o = Scoring.evaluateRound(Rank.GOLD, lines, bonuses, 6, 0);
		assertEquals(0, o.respectMobAfter(), "Strong player should knock out Gold");
		assertTrue(o.knockout());
		assertTrue(o.playersWinRound());
	}

	@Test
	void averagePlayerVsNetheriteLosesButDealsOver35() {
		List<Scoring.LineInput> lines = makeLines("Steve", 8, 6, 1, 1, new int[]{2, 2, 1, 0, 2, 1, 2, 0});
		Scoring.Bonuses bonuses = Scoring.computeBonuses(48, true, 0);
		Scoring.RoundOutcome o = Scoring.evaluateRound(Rank.NETHERITE, lines, bonuses, 9, 0);
		assertFalse(o.playersWinRound(), "Average player should lose round to Netherite Boss");
		assertTrue(o.totalToMob() > 35, "Average player should still deal > 35 damage: " + o.totalToMob());
	}

	@Test
	void strongPlayerVsNetheriteWinsRound() {
		List<Scoring.LineInput> lines = makeLines("Steve", 8, 8, 2, 2, new int[]{2, 2, 2, 2, 2, 2, 2, 1});
		Scoring.Bonuses bonuses = Scoring.computeBonuses(96, true, 0);
		Scoring.RoundOutcome o = Scoring.evaluateRound(Rank.NETHERITE, lines, bonuses, 9, 0);
		assertTrue(o.playersWinRound(), "Strong player should win round vs Netherite Boss: toMob=" + o.totalToMob() + " toTeam=" + o.totalToTeam());
	}

	@Test
	void silentPlayerVsGoldTakesSilencePenalty() {
		List<Scoring.LineInput> lines = new ArrayList<>();
		for (int i = 0; i < 8; i++) lines.add(new Scoring.LineInput("Steve", "w" + i, 0, false, false, "silence", 0, true));
		Scoring.RoundOutcome o = Scoring.evaluateRound(Rank.GOLD, lines, new Scoring.Bonuses(0, 0, 0, 0), 6, 0);
		assertEquals(0, o.totalToMob());
		assertEquals(38 + 3 * 6 + 5 * 8, o.totalToTeam());
		assertFalse(o.playersWinRound());
	}

	@Test
	void coopTwoPlayersOnGoldMatchesSoloScale() {
		List<Scoring.LineInput> solo = makeLines("Steve", 8, 6, 1, 1, new int[]{2, 2, 1, 0, 2, 1, 2, 0});
		List<Scoring.LineInput> coop = new ArrayList<>();
		int[] beats = {2, 2, 1, 0, 2, 1, 2, 0};
		for (int i = 0; i < 12; i++) {
			String who = i % 2 == 0 ? "Steve" : "Alex";
			coop.add(new Scoring.LineInput(who, "w" + i, 6, i == 0, i == 6, "note", beats[i % beats.length], false));
		}
		Scoring.Bonuses bonuses = Scoring.computeBonuses(48, true, 0);
		Scoring.RoundOutcome oSolo = Scoring.evaluateRound(Rank.GOLD, solo, bonuses, 6, 0);
		Scoring.RoundOutcome oCoop = Scoring.evaluateRound(Rank.GOLD, coop, bonuses, 6, 0);
		assertTrue(Math.abs(oSolo.totalToMob() - oCoop.totalToMob()) <= 8,
			"Normalized coop (" + oCoop.totalToMob() + ") should be close to solo (" + oSolo.totalToMob() + ")");
		assertTrue(oCoop.playersWinRound());
	}

	@Test
	void averagePlayerVsLeatherIsLethal() {
		List<Scoring.LineInput> lines = makeLines("Steve", 4, 6, 0, 0, new int[]{2, 0, 2, 0});
		Scoring.RoundOutcome o = Scoring.evaluateRound(Rank.LEATHER, lines, new Scoring.Bonuses(0, 0, 0, 0), 2, 0);
		assertTrue(o.playersWinRound());
		assertTrue(Scoring.isLethalWin(o.playersWinRound(), o.knockout(), o.respectTeamAfter(), o.respectMobAfter(), false),
			"Average player vs Leather should finish the mob: margin=" + (o.respectTeamAfter() - o.respectMobAfter()));
	}

	@Test
	void sliceBarsShiftedTimestampsStillProducesNonEmptyBars() {
		List<Scoring.BarSlot> slots = new ArrayList<>();
		for (int i = 0; i < 8; i++) slots.add(new Scoring.BarSlot(i + 1, 36 + i * 4, "слово" + i));
		List<dev.rapbattle.ai.Transcriber.Word> words = List.of(
			new dev.rapbattle.ai.Transcriber.Word("Ты", 0.2),
			new dev.rapbattle.ai.Transcriber.Word("овца", 0.6),
			new dev.rapbattle.ai.Transcriber.Word("без", 1.0),
			new dev.rapbattle.ai.Transcriber.Word("мозгов", 1.4),
			new dev.rapbattle.ai.Transcriber.Word("я", 2.0),
			new dev.rapbattle.ai.Transcriber.Word("читаю", 2.5),
			new dev.rapbattle.ai.Transcriber.Word("жёстко", 3.0),
			new dev.rapbattle.ai.Transcriber.Word("шахте", 3.5)
		);
		String transcript = "Ты овца без мозгов я читаю жёстко шахте";
		var stt = new dev.rapbattle.ai.Transcriber.Result(transcript, words);
		// Shifted by 2 bars (8 beats = 4800 ms) earlier than slots[0]
		List<Scoring.SlicedBar> early = Scoring.sliceBars(stt, 36 * 600 - 4800, transcript, slots, 600, "ru_ru");
		assertTrue(early.stream().anyMatch(b -> !b.silent()), "Early shifted timestamps must not become all silent");
		// Shifted far past the end (2 bars after last slot)
		List<Scoring.SlicedBar> late = Scoring.sliceBars(stt, 72 * 600, transcript, slots, 600, "ru_ru");
		assertTrue(late.stream().anyMatch(b -> !b.silent()), "Late shifted timestamps must fall back to non-silent bars");
	}
}
