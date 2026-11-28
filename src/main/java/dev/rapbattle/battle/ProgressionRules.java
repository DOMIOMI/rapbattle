package dev.rapbattle.battle;

import java.util.Map;
import java.util.function.ToIntFunction;

/**
 * Pure progression rules (no Minecraft registry references so unit tests run without Bootstrap):
 * - Rank 4 & 5 gates (Respect OR distinct species wins at previous rank, halved Respect with gang gear)
 * - Boss gates (Elder Guardian, Wither, Warden, Ender Dragon)
 * - Daily per-species farm cap (100% / 100% / 50% / 0%)
 * - Low-rank Respect cap (25% from rank 1-2 once player reaches 300+ Respect)
 */
public final class ProgressionRules {
	private ProgressionRules() {}

	public static final int RANK4_RESPECT = 300;
	public static final int RANK5_RESPECT = 500;
	public static final int SPECIES_NEEDED = 2;
	public static final int WARDEN_RESPECT = 500;
	public static final int DRAGON_RESPECT = 1000;
	public static final int LOW_RANK_CAP_RESPECT = 300;
	public static final long TICKS_PER_DAY = 24_000L;

	public enum Mode {
		OFF, SOFT, STRICT;

		public static Mode parse(String raw) {
			if (raw == null) return SOFT;
			return switch (raw.trim().toLowerCase()) {
				case "off" -> OFF;
				case "strict" -> STRICT;
				default -> SOFT;
			};
		}
	}

	/**
	 * Result of checking a rank 4/5 or boss gate.
	 * {@code allowed}: whether the battle can start.
	 * {@code bonusesAllowed}: whether act/loot bonuses are enabled for this battle.
	 * {@code messageKey}: translation key for warning (soft) or refusal (strict/boss), empty if unlocked.
	 */
	public record GateCheck(boolean allowed, boolean bonusesAllowed, String messageKey, Object[] args) {
		public static final GateCheck OK = new GateCheck(true, true, "", new Object[0]);
	}

	/** Pure effective rank levels: raid captain boosts battleRank only; gangRival & bribe affect both. */
	public record EffectiveRankLevels(int battleRank, int gateRank) {}

	public static EffectiveRankLevels computeEffectiveRanks(int baseRank, boolean isRaidCaptain, boolean gangRival, boolean bribed) {
		int gate = baseRank;
		if (gangRival && gate < 5) gate++;
		if (bribed && gate > 1) gate--;

		int battle = baseRank;
		if (isRaidCaptain && battle < 4) battle = 4;
		if (gangRival && battle < 5) battle++;
		if (bribed && battle > 1) battle--;
		return new EffectiveRankLevels(battle, gate);
	}

	public static long currentDay(long overworldTime) {
		return Math.max(0L, overworldTime / TICKS_PER_DAY);
	}

	/** Bonus multiplier for the next win against a species given how many wins already happened today. */
	public static double dailyBonusScale(int previousWinsToday) {
		if (previousWinsToday <= 1) return 1.0;
		if (previousWinsToday == 2) return 0.5;
		return 0.0;
	}

	/** Respect gained from rank 1-2 mobs drops to 25% once player has 300+ Respect. */
	public static int adjustRespectGain(int currentRespect, int oppRankLevel, int rawGain) {
		if (rawGain <= 0) return 0;
		if (currentRespect >= LOW_RANK_CAP_RESPECT && oppRankLevel <= 2) {
			return Math.max(1, (int) Math.round(rawGain * 0.25));
		}
		return rawGain;
	}

	public static int countSpeciesWinsAtExactRank(Map<String, Integer> speciesWins, int rank, ToIntFunction<String> baseRankOf) {
		if (speciesWins == null || speciesWins.isEmpty()) return 0;
		int count = 0;
		for (var e : speciesWins.entrySet()) {
			if (e.getValue() != null && e.getValue() > 0 && baseRankOf.applyAsInt(e.getKey()) == rank) {
				count++;
			}
		}
		return count;
	}

	public static int countSpeciesWinsAtLeastRank(Map<String, Integer> speciesWins, int minRank, ToIntFunction<String> baseRankOf) {
		if (speciesWins == null || speciesWins.isEmpty()) return 0;
		int count = 0;
		for (var e : speciesWins.entrySet()) {
			if (e.getValue() != null && e.getValue() > 0 && baseRankOf.applyAsInt(e.getKey()) >= minRank) {
				count++;
			}
		}
		return count;
	}

	/** Base ranks before the starter mobs moved to rank 1 (6.29/6.30); their old wins keep these ranks. */
	private static final Map<String, Integer> LEGACY_RANKS = Map.ofEntries(
		Map.entry("zombie", 3), Map.entry("husk", 3), Map.entry("drowned", 3), Map.entry("zombie_villager", 3),
		Map.entry("skeleton", 3), Map.entry("stray", 3), Map.entry("bogged", 3), Map.entry("parched", 3),
		Map.entry("spider", 3), Map.entry("sheep", 2), Map.entry("cow", 2), Map.entry("pig", 2));

	/** Base rank a species had before the starter-mob change (used only to migrate old per-species wins). */
	public static int legacyBaseRank(String species, ToIntFunction<String> currentBaseRank) {
		Integer old = LEGACY_RANKS.get(species);
		return old != null ? old : currentBaseRank.applyAsInt(species);
	}

	/** Converts pre-6.29 {@code species -> wins} into {@code "species:rank" -> wins} using the old base ranks. */
	public static Map<String, Integer> migrateLegacySpeciesWins(Map<String, Integer> speciesWins, ToIntFunction<String> currentBaseRank) {
		Map<String, Integer> out = new java.util.HashMap<>();
		if (speciesWins == null) return out;
		for (var e : speciesWins.entrySet()) {
			if (e.getKey() != null && e.getValue() != null && e.getValue() > 0) {
				out.put(e.getKey() + ":" + legacyBaseRank(e.getKey(), currentBaseRank), e.getValue());
			}
		}
		return out;
	}

	/** Counts distinct species with at least 1 win at the exact battle {@code rank} (keys formatted as {@code "species:rank"}). */
	public static int countRankedSpeciesWinsAtExactRank(Map<String, Integer> rankedWins, int rank) {
		if (rankedWins == null || rankedWins.isEmpty()) return 0;
		java.util.Set<String> species = new java.util.HashSet<>();
		for (var e : rankedWins.entrySet()) {
			if (e.getValue() == null || e.getValue() <= 0 || e.getKey() == null) continue;
			int sep = e.getKey().lastIndexOf(':');
			if (sep <= 0 || sep >= e.getKey().length() - 1) continue;
			try {
				if (Integer.parseInt(e.getKey().substring(sep + 1)) == rank) {
					species.add(e.getKey().substring(0, sep));
				}
			} catch (NumberFormatException ignored) {}
		}
		return species.size();
	}

	/** Counts distinct species with at least 1 win at battle rank {@code >= minRank} (keys formatted as {@code "species:rank"}). */
	public static int countRankedSpeciesWinsAtLeastRank(Map<String, Integer> rankedWins, int minRank) {
		if (rankedWins == null || rankedWins.isEmpty()) return 0;
		java.util.Set<String> species = new java.util.HashSet<>();
		for (var e : rankedWins.entrySet()) {
			if (e.getValue() == null || e.getValue() <= 0 || e.getKey() == null) continue;
			int sep = e.getKey().lastIndexOf(':');
			if (sep <= 0 || sep >= e.getKey().length() - 1) continue;
			try {
				if (Integer.parseInt(e.getKey().substring(sep + 1)) >= minRank) {
					species.add(e.getKey().substring(0, sep));
				}
			} catch (NumberFormatException ignored) {}
		}
		return species.size();
	}

	public static int respectThreshold(int targetRank, boolean ownGangGear) {
		int base = switch (targetRank) {
			case 4 -> RANK4_RESPECT;
			case 5 -> RANK5_RESPECT;
			default -> 0;
		};
		return ownGangGear ? base / 2 : base;
	}

	/** Checks Rank 4/5 progression gate (not for bosses; bosses have {@link #checkBossGate}). */
	public static GateCheck checkRankGate(Mode mode, int targetRank, boolean isBoss, int respect,
										  int prevRankSpeciesWins, boolean ownGangGear) {
		if (mode == Mode.OFF || isBoss || (targetRank != 4 && targetRank != 5)) {
			return GateCheck.OK;
		}
		int reqRespect = respectThreshold(targetRank, ownGangGear);
		int prevRank = targetRank - 1;
		if (respect >= reqRespect || prevRankSpeciesWins >= SPECIES_NEEDED) {
			return GateCheck.OK;
		}
		Object[] args = new Object[]{reqRespect, prevRank, Math.min(prevRankSpeciesWins, SPECIES_NEEDED), SPECIES_NEEDED};
		if (mode == Mode.SOFT) {
			return new GateCheck(true, false, "rapbattle.progression.soft_warning", args);
		}
		return new GateCheck(false, false, "rapbattle.progression.strict_refusal", args);
	}

	/**
	 * Checks Boss progression gates. Active in both {@link Mode#SOFT} and {@link Mode#STRICT},
	 * bypassed only in {@link Mode#OFF}.
	 * For Ender Dragon, returns the first unmet condition.
	 */
	public static GateCheck checkBossGate(Mode mode, String bossId, int crystalsAlive,
										  boolean hasAnyMic, boolean hasHighTierTrophyMic,
										  int respect, int guardianWins, int witherSkeletonWins,
										  int rank4PlusSpeciesWins, int rank5OrBossSpeciesWins) {
		if (mode == Mode.OFF || bossId == null) {
			return GateCheck.OK;
		}
		return switch (bossId) {
			case "elder_guardian" -> guardianWins >= 1
				? GateCheck.OK
				: new GateCheck(false, false, "rapbattle.progression.boss.elder_guardian", new Object[0]);
			case "wither" -> witherSkeletonWins >= 1
				? GateCheck.OK
				: new GateCheck(false, false, "rapbattle.progression.boss.wither", new Object[0]);
			case "warden" -> {
				if (!hasAnyMic) {
					yield new GateCheck(false, false, "rapbattle.progression.boss.warden.mic", new Object[0]);
				}
				if (respect < WARDEN_RESPECT) {
					yield new GateCheck(false, false, "rapbattle.progression.boss.warden.respect",
						new Object[]{WARDEN_RESPECT, respect});
				}
				if (rank4PlusSpeciesWins < 1) {
					yield new GateCheck(false, false, "rapbattle.progression.boss.warden.rank4", new Object[0]);
				}
				yield GateCheck.OK;
			}
			case "ender_dragon" -> {
				if (crystalsAlive > 0) {
					yield new GateCheck(false, false, "rapbattle.act5.jammers", new Object[]{crystalsAlive});
				}
				if (!hasHighTierTrophyMic) {
					yield new GateCheck(false, false, "rapbattle.progression.boss.dragon.mic", new Object[0]);
				}
				if (respect < DRAGON_RESPECT) {
					yield new GateCheck(false, false, "rapbattle.progression.boss.dragon.respect",
						new Object[]{DRAGON_RESPECT, respect});
				}
				if (rank5OrBossSpeciesWins < 1) {
					yield new GateCheck(false, false, "rapbattle.progression.boss.dragon.rank5", new Object[0]);
				}
				yield GateCheck.OK;
			}
			default -> GateCheck.OK;
		};
	}
}
