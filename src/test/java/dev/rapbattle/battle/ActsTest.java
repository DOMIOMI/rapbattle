package dev.rapbattle.battle;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ActsTest {
	@Test
	void nightHuntDoubleLootOnlyForRanks1To2AndSoloMob() {
		assertTrue(Acts.isNightHuntDoubleLoot(Rank.LEATHER, true, 1, false));
		assertTrue(Acts.isNightHuntDoubleLoot(Rank.CHAIN, true, 1, false));
		// Day, crew (>1), overdressed, or rank >= 3 should not trigger Act 1 night hunt
		assertFalse(Acts.isNightHuntDoubleLoot(Rank.LEATHER, false, 1, false));
		assertFalse(Acts.isNightHuntDoubleLoot(Rank.CHAIN, true, 2, false));
		assertFalse(Acts.isNightHuntDoubleLoot(Rank.LEATHER, true, 1, true));
		assertFalse(Acts.isNightHuntDoubleLoot(Rank.IRON, true, 1, false));
	}

	@Test
	void nightHuntXpMultiplierIs150PercentOnRanks1To2AtNight() {
		assertEquals(15, Acts.applyNightHuntXp(10, Rank.LEATHER, true));
		assertEquals(15, Acts.applyNightHuntXp(10, Rank.CHAIN, true));
		assertEquals(10, Acts.applyNightHuntXp(10, Rank.LEATHER, false));
		assertEquals(10, Acts.applyNightHuntXp(10, Rank.IRON, true));
	}

	@Test
	void villagerDiscountIsLimitedToOncePerGameDay() {
		assertTrue(Acts.canGiveVillagerDiscount(0L, 1000L));
		assertFalse(Acts.canGiveVillagerDiscount(1000L, 1000L + 23_999L));
		assertTrue(Acts.canGiveVillagerDiscount(1000L, 1000L + 24_000L));
	}

	@Test
	void villageDefenseRequiresAtLeastTwoWitnesses() {
		assertFalse(Acts.hasVillageDefenseWitnesses(0));
		assertFalse(Acts.hasVillageDefenseWitnesses(1));
		assertTrue(Acts.hasVillageDefenseWitnesses(2));
		assertTrue(Acts.hasVillageDefenseWitnesses(5));
	}

	@Test
	void raidFinaleRequiresFinalWaveAndAliveCaptain() {
		assertTrue(Acts.isFinalWaveWithCaptain(true, true, 3, 3, true));
		assertFalse(Acts.isFinalWaveWithCaptain(false, true, 3, 3, true));
		assertFalse(Acts.isFinalWaveWithCaptain(true, false, 3, 3, true));
		assertFalse(Acts.isFinalWaveWithCaptain(true, true, 2, 3, true));
		assertFalse(Acts.isFinalWaveWithCaptain(true, true, 3, 3, false));
	}

	@Test
	void fortressRewardsMatchBlazeAndWitherSkullRules() {
		java.util.Random rnd = new java.util.Random(42);
		for (int i = 0; i < 20; i++) {
			int r = Acts.blazeExtraRods(true, rnd);
			assertTrue(r >= Acts.BLAZE_RODS_LETHAL_MIN && r <= Acts.BLAZE_RODS_LETHAL_MAX);
		}
		assertEquals(1, Acts.blazeExtraRods(false, rnd));
		assertEquals(0.30, Acts.witherSkullChance(true, 10), 1e-6);
		assertEquals(0.30, Acts.witherSkullChance(false, 40), 1e-6);
		assertEquals(0.10, Acts.witherSkullChance(false, 25), 1e-6);
		assertEquals(0.10, Acts.witherSkullChance(false, 39), 1e-6);
		assertEquals(0.0, Acts.witherSkullChance(false, 24), 1e-6);
	}

	@Test
	void endermanDropsMatchKnockoutVsRegularWin() {
		java.util.Random rnd = new java.util.Random(7);
		for (int i = 0; i < 20; i++) {
			int ko = Acts.enderPearls(true, rnd);
			int reg = Acts.enderPearls(false, rnd);
			assertTrue(ko >= 2 && ko <= 4);
			assertTrue(reg >= 1 && reg <= 2);
		}
	}

	@Test
	void gangRivalsAreSymmetricPairs() {
		assertEquals("villager", Acts.rivalOf("illager"));
		assertEquals("illager", Acts.rivalOf("villager"));
		assertEquals("skeleton", Acts.rivalOf("zombie"));
		assertEquals("zombie", Acts.rivalOf("skeleton"));
		assertEquals("hoglin", Acts.rivalOf("piglin"));
		assertEquals("piglin", Acts.rivalOf("hoglin"));
		assertNull(Acts.rivalOf("sheep"));
	}

	@Test
	void raidModeAppliesOnlyToFinalWaveCaptain() {
		assertTrue(Acts.isFinalWaveCaptainMatch(true, true, 3, 3, true));
		assertFalse(Acts.isFinalWaveCaptainMatch(true, true, 1, 3, true));
		assertFalse(Acts.isFinalWaveCaptainMatch(true, true, 3, 3, false));
		assertFalse(Acts.isFinalWaveCaptainMatch(true, false, 3, 3, true));
	}

	@Test
	void cleanTargetNeverGluesMultiWordTargets() {
		assertEquals("лавовый", dev.rapbattle.ai.Llm.cleanTarget("лавовый компот"));
		assertEquals("шахтёр", dev.rapbattle.ai.Llm.cleanTarget("шахтёр!"));
		String multi = dev.rapbattle.ai.Llm.cleanTarget("пять дней");
		assertFalse(multi.equals("пятьдней"), "Multi-word targets must not glue without spaces");
		assertEquals("пять", multi);
	}
}
