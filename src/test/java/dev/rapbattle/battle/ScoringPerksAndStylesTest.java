package dev.rapbattle.battle;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ScoringPerksAndStylesTest {

	@Test
	void trophyPerksMatchLangDescriptions() {
		// DEFAULT & ENDER_DRAGON: +1 style bonus in computeBonuses
		var baseBonus = Scoring.computeBonuses(0, true, 0, 0);
		var trophyBonus = Scoring.computeBonuses(0, true, 0, 1);
		assertEquals(baseBonus.styleBonus() + 1, trophyBonus.styleBonus());
		assertEquals(baseBonus.total() + 1, trophyBonus.total());

		// ELDER_GUARDIAN: +0.05 on-beat window (0.33s delta is near-beat without perk, on-beat with perk)
		assertEquals(1, Scoring.hybridBeat(0.33, 2, false, -1, 600, 0.30, 0.35));
		assertEquals(2, Scoring.hybridBeat(0.33, 2, false, -1, 600, 0.35, 0.40));

		// WITHER: first silent bar forgiven (no 5-point silence damage)
		var normalSilent = Scoring.evaluateRound(Rank.GOLD, List.of(
			new Scoring.LineInput("MC", "word", 0, 0, false, false, "silence", 0, true, 2, 1.0, 3, 1.25, 0, 0, false)
		), baseBonus, 6, 0);
		var witherForgiven = Scoring.evaluateRound(Rank.GOLD, List.of(
			new Scoring.LineInput("MC", "word", 0, 0, false, false, "silence", 0, true, 2, 1.0, 3, 1.25, 0, 0, true)
		), baseBonus, 6, 0);
		assertEquals(1, normalSilent.silentBars());
		assertEquals(5, normalSilent.silenceDamage());
		assertEquals(0, witherForgiven.silentBars());
		assertEquals(0, witherForgiven.silenceDamage());
	}

	@Test
	void mcStylesDifferInPunchFlowWeakPenaltyAndRebuttalHeal() {
		Scoring.Bonuses b = Scoring.computeBonuses(0, false, 0);

		// Same 4-bar sequence: bar 1 punch (meaning 3), bar 2 on-beat (meaning 2), bar 3 weak (meaning 1), bar 4 rebuttal (meaning 2)
		List<Scoring.LineInput> puncherLines = List.of(
			McStyle.PUNCHER.lineInput("MC", "a", 8, 3, true, false, "n1", 2, false, false, 1.0, false),
			McStyle.PUNCHER.lineInput("MC", "b", 6, 2, false, false, "n2", 2, false, false, 1.0, false),
			McStyle.PUNCHER.lineInput("MC", "c", 4, 1, false, false, "n3", 1, false, false, 1.0, false),
			McStyle.PUNCHER.lineInput("MC", "d", 6, 2, false, true, "n4", 2, false, false, 1.0, false)
		);
		List<Scoring.LineInput> metronomeLines = List.of(
			McStyle.METRONOME.lineInput("MC", "a", 8, 3, true, false, "n1", 2, false, false, 1.0, false),
			McStyle.METRONOME.lineInput("MC", "b", 6, 2, false, false, "n2", 2, false, false, 1.0, false),
			McStyle.METRONOME.lineInput("MC", "c", 4, 1, false, false, "n3", 1, false, false, 1.0, false),
			McStyle.METRONOME.lineInput("MC", "d", 6, 2, false, true, "n4", 2, false, false, 1.0, false)
		);
		List<Scoring.LineInput> provokerLines = List.of(
			McStyle.PROVOKER.lineInput("MC", "a", 8, 3, true, false, "n1", 2, false, false, 1.0, false),
			McStyle.PROVOKER.lineInput("MC", "b", 6, 2, false, false, "n2", 2, false, false, 1.0, false),
			McStyle.PROVOKER.lineInput("MC", "c", 4, 1, false, false, "n3", 1, false, false, 1.0, false),
			McStyle.PROVOKER.lineInput("MC", "d", 6, 2, false, true, "n4", 2, false, false, 1.0, false)
		);

		var rP = Scoring.evaluateRound(Rank.IRON, puncherLines, b, 4, 0);
		var rM = Scoring.evaluateRound(Rank.IRON, metronomeLines, b, 4, 0);
		var rV = Scoring.evaluateRound(Rank.IRON, provokerLines, b, 4, 0);

		// Puncher: +3 punch bonus on bar 1 vs +2 (Metronome) and +1 (Provoker)
		assertTrue(rP.lines().get(0).damage() > rV.lines().get(0).damage());
		// Metronome: streak starts at 2nd hit with 1.35x (bar 2 flow > Puncher's 1.25x), weak line bar 3 loses 1 point
		assertTrue(rM.lines().get(1).flowMultiplier() > rP.lines().get(1).flowMultiplier());
		assertTrue(rM.lines().get(2).damage() < rP.lines().get(2).damage());
		// Provoker: 3 targets & heals +4 per rebuttal
		assertEquals(3, McStyle.PROVOKER.targetCount());
		assertEquals(0, rP.teamHeal());
		assertEquals(4, rV.teamHeal());
	}
}
