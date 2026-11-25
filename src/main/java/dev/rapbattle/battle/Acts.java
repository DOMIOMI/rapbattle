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
}
