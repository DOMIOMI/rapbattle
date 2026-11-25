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
}
