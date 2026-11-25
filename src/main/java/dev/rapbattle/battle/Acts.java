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

	public static final int BLAZE_RODS_LETHAL_MIN = 2;
	public static final int BLAZE_RODS_LETHAL_MAX = 3;
	public static final int BLAZE_RODS_CLOSE = 1;
	public static final double WITHER_SKULL_CHANCE_40 = 0.30;
	public static final double WITHER_SKULL_CHANCE_25 = 0.10;

	/** Act 3: extra blaze rods beyond vanilla drops. */
	public static int blazeExtraRods(boolean lethal, java.util.Random rnd) {
		return lethal ? BLAZE_RODS_LETHAL_MIN + rnd.nextInt(BLAZE_RODS_LETHAL_MAX - BLAZE_RODS_LETHAL_MIN + 1) : BLAZE_RODS_CLOSE;
	}

	/** Act 3: wither skeleton skull drop chance by win margin (30% on KO/40+, 10% on 25-39, 0 otherwise). */
	public static double witherSkullChance(boolean knockout, int margin) {
		if (knockout || margin >= 40) return WITHER_SKULL_CHANCE_40;
		if (margin >= 25) return WITHER_SKULL_CHANCE_25;
		return 0.0;
	}

	/** Act 4: Enderman drops 2-4 ender pearls on early knockout, 1-2 on normal win. */
	public static int enderPearls(boolean knockout, java.util.Random rnd) {
		return knockout ? 2 + rnd.nextInt(3) : 1 + rnd.nextInt(2);
	}

	private static final java.util.Map<String, String> GANG_RIVALS = java.util.Map.of(
		"illager", "villager",
		"villager", "illager",
		"zombie", "skeleton",
		"skeleton", "zombie",
		"piglin", "hoglin",
		"hoglin", "piglin"
	);

	/** Gang War: symmetric rival gang kin mapping. */
	public static String rivalOf(String kin) {
		return kin == null ? null : GANG_RIVALS.get(kin);
	}
}
