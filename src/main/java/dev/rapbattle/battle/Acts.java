package dev.rapbattle.battle;

/**
 * Pure, Minecraft-free rules for Acts 1 and 2 (night hunt, village authority, raid finale),
 * covered by unit tests in {@code ActsTest}.
 */
public final class Acts {
	private Acts() {}

	/** Act 1: at night, ranks 1-2 against a solo mob (no crew) yield double loot if not overdressed. */
	public static boolean isNightHuntDoubleLoot(Rank rank, boolean isNight, int mobTeamSize, boolean overdressed) {
		return rank != null && rank.level() <= 2 && isNight && mobTeamSize == 1 && !overdressed;
	}

	/** Act 1: at night, ranks 1-2 award 1.5x XP. */
	public static int applyNightHuntXp(int baseXp, Rank rank, boolean isNight) {
		if (baseXp <= 0) return 0;
		return (rank != null && rank.level() <= 2 && isNight) ? (int) Math.round(baseXp * 1.5) : baseXp;
	}

	public static final long TICKS_PER_DAY = 24_000L;

	/** Act 2: villager battle discounts can be granted at most once per in-game day (24000 ticks). */
	public static boolean canGiveVillagerDiscount(long lastDiscountTick, long worldTime) {
		return lastDiscountTick <= 0 || (worldTime - lastDiscountTick) >= TICKS_PER_DAY;
	}

	/** Act 2: defending the village requires at least 2 nearby villager witnesses within 32 blocks. */
	public static boolean hasVillageDefenseWitnesses(int nearbyVillagerCount) {
		return nearbyVillagerCount >= 2;
	}

	/** Act 2: raid finale triggers on the final wave when the patrol captain is alive. */
	public static boolean isFinalWaveWithCaptain(boolean hasRaid, boolean raidActive, int groupsSpawned,
												 int maxWaves, boolean captainAlive) {
		return hasRaid && raidActive && maxWaves > 0 && groupsSpawned >= maxWaves && captainAlive;
	}
}
