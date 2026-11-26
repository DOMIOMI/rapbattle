package dev.rapbattle.battle;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ReputationTest {
	@Test
	void credDurationRespectsMvpCoopAndOverdressed() {
		assertEquals(Reputation.STREET_CRED_MS, Reputation.computeCredDurationMs(false, true, 0));
		assertEquals(Reputation.STREET_CRED_MS / 2, Reputation.computeCredDurationMs(false, false, 15));
		assertEquals(0L, Reputation.computeCredDurationMs(false, false, 0));
		assertEquals(Reputation.OVERDRESSED_CRED_MS, Reputation.computeCredDurationMs(true, true, 40));
		assertEquals(Reputation.OVERDRESSED_CRED_MS / 2, Reputation.computeCredDurationMs(true, false, 10));
	}

	@Test
	void mergeCredTakesMaxLevelAndCapsAtThreeMinutes() {
		long now = 1_000_000L;
		var first = Reputation.mergeCred(null, 3, Reputation.STREET_CRED_MS, now);
		assertNotNull(first);
		assertEquals(3, first.level());
		assertEquals(now + Reputation.STREET_CRED_MS, first.until());

		// Second win with lower rank keeps max level (3) and refreshes timer without stacking > 3 min
		var second = Reputation.mergeCred(first, 2, Reputation.STREET_CRED_MS, now + 60_000L);
		assertEquals(3, second.level());
		assertEquals(now + 60_000L + Reputation.STREET_CRED_MS, second.until());

		// Win with higher rank (e.g. gang rivalry +1) raises level to 4
		var third = Reputation.mergeCred(second, 4, Reputation.STREET_CRED_MS, now + 90_000L);
		assertEquals(4, third.level());
	}

	@Test
	void stealWhitelistAndBlacklistByRank() {
		// Empty inventory
		assertEquals(-1, TheftRules.pickStealIndex(List.of(), 0, 3, false));

		// Only forbidden items (sword, enchanted, trophy)
		var onlyForbidden = List.of(
			new TheftRules.SlotItem("diamond_sword", true),
			new TheftRules.SlotItem("bread", true),
			new TheftRules.SlotItem("diamond", true)
		);
		assertEquals(-1, TheftRules.pickStealIndex(onlyForbidden, 0, 5, false));

		// Rank 3 skips valuables and steals minor item
		var mixed = List.of(
			new TheftRules.SlotItem("diamond", false),
			new TheftRules.SlotItem("emerald", false),
			new TheftRules.SlotItem("string", false)
		);
		assertEquals(2, TheftRules.pickStealIndex(mixed, 0, 3, false));

		// Rank 4 skips diamond and steals emerald
		assertEquals(1, TheftRules.pickStealIndex(mixed, 0, 4, false));

		// Rank 4 with no valuables falls back to minor item
		var noValuables = List.of(
			new TheftRules.SlotItem("diamond", false),
			new TheftRules.SlotItem("bread", false)
		);
		assertEquals(1, TheftRules.pickStealIndex(noValuables, 0, 4, false));

		// Rank 5 prioritizes non-forbidden main hand item, otherwise diamond
		assertEquals(0, TheftRules.pickStealIndex(mixed, 0, 5, false));
		var rank5HandForbidden = List.of(
			new TheftRules.SlotItem("netherite_sword", true),
			new TheftRules.SlotItem("diamond", false)
		);
		assertEquals(1, TheftRules.pickStealIndex(rank5HandForbidden, 0, 5, false));

		// Peaceful mob always steals minor only, even if rank >= 4
		assertEquals(2, TheftRules.pickStealIndex(mixed, 0, 5, true));
	}
}
