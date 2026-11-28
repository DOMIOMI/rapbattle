package dev.rapbattle.battle;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FirstMinutesTest {
	@Test
	void starterGearChanceScalesWithDistanceAndDay() {
		// Day 0 at spawn: ~95% rank 1 (none or leather)
		assertEquals(0, StarterGear.rollTier(0.0, 0, 0.50));
		assertEquals(1, StarterGear.rollTier(0.0, 0, 0.06));
		assertEquals(2, StarterGear.rollTier(0.0, 0, 0.02));
		assertEquals(3, StarterGear.rollTier(0.0, 0, 0.005));

		// Far from spawn on day 14: iron chance 0.25, chain chance 0.35
		assertEquals(3, StarterGear.rollTier(700.0, 14, 0.20));
		assertEquals(2, StarterGear.rollTier(700.0, 14, 0.45));
		assertEquals(1, StarterGear.rollTier(700.0, 14, 0.65));
		assertEquals(0, StarterGear.rollTier(700.0, 14, 0.85));
	}

	@Test
	void speechBubbleDurationScalesWithLength() {
		assertEquals(4000L, Speech.durationMs(""));
		assertTrue(Speech.durationMs("Привет") >= 4000L);
		assertEquals(7000L, Speech.durationMs("a".repeat(50)));
		assertEquals(10_000L, Speech.durationMs("a".repeat(120)));
		assertEquals(10_000L, Speech.durationMs("a".repeat(300)));
	}

	@Test
	void rankedSpeciesWinsGateUsesActualBattleRank() {
		// Naked zombie & skeleton (rank 1) do NOT count toward rank 4 gate (needs 2 species at rank 3)
		Map<String, Integer> lowOnly = Map.of("zombie:1", 3, "skeleton:1", 2);
		assertEquals(0, ProgressionRules.countRankedSpeciesWinsAtExactRank(lowOnly, 3));
		assertFalse(ProgressionRules.checkRankGate(ProgressionRules.Mode.STRICT, 4, false, 0, 0, false).allowed());

		// Armored zombie & skeleton (rank 3) DO open rank 4 gate
		Map<String, Integer> armoredWins = Map.of("zombie:3", 1, "skeleton:3", 1);
		int r3 = ProgressionRules.countRankedSpeciesWinsAtExactRank(armoredWins, 3);
		assertEquals(2, r3);
		assertTrue(ProgressionRules.checkRankGate(ProgressionRules.Mode.STRICT, 4, false, 0, r3, false).allowed());
	}

	@Test
	void legacyWinsKeepOldRank3ForMovedSpecies() {
		// Pre-6.29 save: zombie + skeleton wins were rank-3 wins; blaze keeps its current rank
		Map<String, Integer> legacy = Map.of("zombie", 4, "skeleton", 1, "blaze", 2, "cow", 0);
		Map<String, Integer> ranked = ProgressionRules.migrateLegacySpeciesWins(legacy, id -> id.equals("blaze") ? 4 : 1);
		assertEquals(Map.of("zombie:3", 4, "skeleton:3", 1, "blaze:4", 2), ranked);
		assertEquals(2, ProgressionRules.countRankedSpeciesWinsAtExactRank(ranked, 3));
	}
}
