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
}
