package dev.rapbattle.battle;

import com.mojang.serialization.JsonOps;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.function.ToIntFunction;

import static org.junit.jupiter.api.Assertions.*;

class ProgressionTest {
	private static final ToIntFunction<String> RANKS = id -> switch (id) {
		case "zombie", "cow" -> 1;
		case "skeleton", "creeper" -> 2;
		case "enderman", "blaze", "wither_skeleton" -> 3;
		case "guardian", "witch" -> 4;
		case "iron_golem", "piglin_brute" -> 5;
		case "warden", "ender_dragon", "wither", "elder_guardian" -> 6;
		default -> 2;
	};

	@Test
	void modeParsingDefaultsToSoft() {
		assertEquals(ProgressionRules.Mode.SOFT, ProgressionRules.Mode.parse(null));
		assertEquals(ProgressionRules.Mode.SOFT, ProgressionRules.Mode.parse(""));
		assertEquals(ProgressionRules.Mode.SOFT, ProgressionRules.Mode.parse("garbage"));
		assertEquals(ProgressionRules.Mode.SOFT, ProgressionRules.Mode.parse("soft"));
		assertEquals(ProgressionRules.Mode.STRICT, ProgressionRules.Mode.parse("STRICT"));
		assertEquals(ProgressionRules.Mode.OFF, ProgressionRules.Mode.parse("off"));
	}

	@Test
	void rank4And5GatesRespectAndSpeciesAndGangDiscount() {
		// Locked at 0 Respect, 0 species: soft allows without bonuses, strict blocks
		var r4Soft = ProgressionRules.checkRankGate(ProgressionRules.Mode.SOFT, 4, false, 0, 1, false);
		assertTrue(r4Soft.allowed());
		assertFalse(r4Soft.bonusesAllowed());
		assertEquals("rapbattle.progression.soft_warning", r4Soft.messageKey());

		var r4Strict = ProgressionRules.checkRankGate(ProgressionRules.Mode.STRICT, 4, false, 0, 1, false);
		assertFalse(r4Strict.allowed());
		assertFalse(r4Strict.bonusesAllowed());
		assertEquals("rapbattle.progression.strict_refusal", r4Strict.messageKey());

		// Unlocked by 300 Respect OR 2 rank-3 species
		assertTrue(ProgressionRules.checkRankGate(ProgressionRules.Mode.STRICT, 4, false, 300, 0, false).bonusesAllowed());
		assertTrue(ProgressionRules.checkRankGate(ProgressionRules.Mode.STRICT, 4, false, 0, 2, false).bonusesAllowed());

		// Own gang gear halves Respect requirement (150 for rank 4, 250 for rank 5)
		assertFalse(ProgressionRules.checkRankGate(ProgressionRules.Mode.STRICT, 4, false, 149, 0, true).allowed());
		assertTrue(ProgressionRules.checkRankGate(ProgressionRules.Mode.STRICT, 4, false, 150, 0, true).bonusesAllowed());
		assertFalse(ProgressionRules.checkRankGate(ProgressionRules.Mode.STRICT, 5, false, 249, 0, true).allowed());
		assertTrue(ProgressionRules.checkRankGate(ProgressionRules.Mode.STRICT, 5, false, 250, 0, true).bonusesAllowed());

		// Rank 5 unlocked by 500 Respect or 2 rank-4 species
		assertTrue(ProgressionRules.checkRankGate(ProgressionRules.Mode.STRICT, 5, false, 500, 0, false).bonusesAllowed());
		assertTrue(ProgressionRules.checkRankGate(ProgressionRules.Mode.STRICT, 5, false, 0, 2, false).bonusesAllowed());

		// Off mode bypasses everything
		assertTrue(ProgressionRules.checkRankGate(ProgressionRules.Mode.OFF, 5, false, 0, 0, false).bonusesAllowed());
	}

	@Test
	void bossGatesCheckEachConditionInOrder() {
		// Elder Guardian: needs 1 guardian win (active in SOFT too!)
		assertFalse(ProgressionRules.checkBossGate(ProgressionRules.Mode.SOFT, "elder_guardian", 0, false, false, 9999, 0, 0, 5, 5).allowed());
		assertTrue(ProgressionRules.checkBossGate(ProgressionRules.Mode.SOFT, "elder_guardian", 0, false, false, 0, 1, 0, 0, 0).allowed());

		// Wither: needs 1 wither_skeleton win
		assertFalse(ProgressionRules.checkBossGate(ProgressionRules.Mode.SOFT, "wither", 0, false, false, 9999, 0, 0, 5, 5).allowed());
		assertTrue(ProgressionRules.checkBossGate(ProgressionRules.Mode.SOFT, "wither", 0, false, false, 0, 0, 1, 0, 0).allowed());

		// Warden: mic -> 500 Respect -> rank 4+ win
		assertEquals("rapbattle.progression.boss.warden.mic",
			ProgressionRules.checkBossGate(ProgressionRules.Mode.SOFT, "warden", 0, false, false, 1000, 0, 0, 1, 0).messageKey());
		assertEquals("rapbattle.progression.boss.warden.respect",
			ProgressionRules.checkBossGate(ProgressionRules.Mode.SOFT, "warden", 0, true, false, 499, 0, 0, 1, 0).messageKey());
		assertEquals("rapbattle.progression.boss.warden.rank4",
			ProgressionRules.checkBossGate(ProgressionRules.Mode.SOFT, "warden", 0, true, false, 500, 0, 0, 0, 0).messageKey());
		assertTrue(ProgressionRules.checkBossGate(ProgressionRules.Mode.SOFT, "warden", 0, true, false, 500, 0, 0, 1, 0).allowed());

		// Ender Dragon: crystals -> high-tier trophy mic -> 1000 Respect -> rank 5/boss win
		assertEquals("rapbattle.act5.jammers",
			ProgressionRules.checkBossGate(ProgressionRules.Mode.SOFT, "ender_dragon", 2, true, true, 1500, 0, 0, 1, 1).messageKey());
		assertEquals("rapbattle.progression.boss.dragon.mic",
			ProgressionRules.checkBossGate(ProgressionRules.Mode.SOFT, "ender_dragon", 0, true, false, 1500, 0, 0, 1, 1).messageKey());
		assertEquals("rapbattle.progression.boss.dragon.respect",
			ProgressionRules.checkBossGate(ProgressionRules.Mode.SOFT, "ender_dragon", 0, true, true, 999, 0, 0, 1, 1).messageKey());
		assertEquals("rapbattle.progression.boss.dragon.rank5",
			ProgressionRules.checkBossGate(ProgressionRules.Mode.SOFT, "ender_dragon", 0, true, true, 1000, 0, 0, 1, 0).messageKey());
		assertTrue(ProgressionRules.checkBossGate(ProgressionRules.Mode.SOFT, "ender_dragon", 0, true, true, 1000, 0, 0, 0, 1).allowed());

		// OFF bypasses boss gates
		assertTrue(ProgressionRules.checkBossGate(ProgressionRules.Mode.OFF, "ender_dragon", 3, false, false, 0, 0, 0, 0, 0).allowed());
	}

	@Test
	void dailyFarmCapAndRolloverAndLowRankRespectCap() {
		assertEquals(1.0, ProgressionRules.dailyBonusScale(0));
		assertEquals(1.0, ProgressionRules.dailyBonusScale(1));
		assertEquals(0.5, ProgressionRules.dailyBonusScale(2));
		assertEquals(0.0, ProgressionRules.dailyBonusScale(3));
		assertEquals(0.0, ProgressionRules.dailyBonusScale(7));

		Stats.Player sp = new Stats.Player();
		long day0 = ProgressionRules.currentDay(10_000L);
		long day1 = ProgressionRules.currentDay(24_500L);
		assertEquals(0L, day0);
		assertEquals(1L, day1);
		sp.recordDailyWin("blaze", day0);
		sp.recordDailyWin("blaze", day0);
		sp.recordDailyWin("blaze", day0);
		assertEquals(3, sp.dailyWinsFor("blaze", day0));
		assertEquals(0.0, ProgressionRules.dailyBonusScale(sp.dailyWinsFor("blaze", day0)));
		assertEquals(0, sp.dailyWinsFor("blaze", day1));
		assertEquals(1.0, ProgressionRules.dailyBonusScale(sp.dailyWinsFor("blaze", day1)));

		// Respect cap: rank 1-2 gives 25% when currentRespect >= 300
		assertEquals(40, ProgressionRules.adjustRespectGain(299, 2, 40));
		assertEquals(10, ProgressionRules.adjustRespectGain(300, 2, 40));
		assertEquals(10, ProgressionRules.adjustRespectGain(500, 1, 40));
		assertEquals(40, ProgressionRules.adjustRespectGain(500, 3, 40));

		// Species count helper
		Map<String, Integer> sw = Map.of("enderman", 2, "blaze", 1, "zombie", 5, "guardian", 1);
		assertEquals(2, ProgressionRules.countSpeciesWinsAtExactRank(sw, 3, RANKS));
		assertEquals(1, ProgressionRules.countSpeciesWinsAtLeastRank(sw, 4, RANKS));
	}

	@Test
	void oldStatsJsonWithoutNewFieldsDecodesCleanly() {
		JsonObject legacy = new JsonObject();
		legacy.addProperty("wins", 5);
		legacy.addProperty("respect", 420);
		Stats.Player p = Stats.Player.CODEC.parse(JsonOps.INSTANCE, legacy).getOrThrow();
		assertEquals(5, p.wins);
		assertEquals(420, p.respect);
		assertFalse(p.dragonWon);
		assertEquals(0L, p.dailyDay);
		assertTrue(p.dailySpeciesWins.isEmpty());
	}
}
