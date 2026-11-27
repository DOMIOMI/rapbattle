package dev.rapbattle.battle;

import dev.rapbattle.Lang;
import dev.rapbattle.RapConfig;
import net.minecraft.entity.boss.dragon.EnderDragonEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.village.raid.Raid;

import java.util.Random;

/**
 * Server-side bridge between Minecraft entities/stats and pure {@link ProgressionRules}.
 */
public final class Progression {
	private Progression() {}

	public record EffectiveRanks(Rank battleRank, Rank gateRank, boolean gangRival, boolean canBribe, Raid raid) {}

	public record StartCheck(boolean allowed, boolean micAllowed, boolean dressAllowed, ProgressionRules.GateCheck gate,
							 EffectiveRanks ranks, Opponent opp, Outfit.Score score) {}

	public static boolean isBribable(String mobId) {
		return "villager".equals(mobId) || "wandering_trader".equals(mobId)
			|| "pillager".equals(mobId) || "vindicator".equals(mobId);
	}

	/** Computes effective battle and gate ranks without mutating player inventory (emerald is consumed only on start). */
	public static EffectiveRanks computeRanks(ServerPlayerEntity challenger, MobEntity mob, Opponent opp) {
		Rank base = opp.rank();
		Raid raid = RaidBattle.activeRaidFor(mob);
		boolean gangRival = challenger != null && Gangs.isRival(challenger, opp.profile());
		boolean canBribe = challenger != null && isBribable(opp.profile().id())
			&& challenger.getOffHandStack().isOf(Items.EMERALD) && base.level() > 1;
		ProgressionRules.EffectiveRankLevels lv = ProgressionRules.computeEffectiveRanks(
			base.level(), raid != null, gangRival, canBribe);
		return new EffectiveRanks(Rank.ofLevel(lv.battleRank()), Rank.ofLevel(lv.gateRank()), gangRival, canBribe, raid);
	}

	public static ProgressionRules.GateCheck check(ServerPlayerEntity p, MobEntity mob, Opponent o) {
		if (p == null || mob == null || o == null) return ProgressionRules.GateCheck.OK;
		EffectiveRanks ranks = computeRanks(p, mob, o);
		int crystals = mob instanceof EnderDragonEntity d ? DragonBattle.countCrystals(d) : 0;
		return check(p, mob, o, ranks.gateRank(), crystals);
	}

	public static ProgressionRules.GateCheck check(ServerPlayerEntity p, MobEntity mob, Opponent o,
												   Rank gateRank, int precomputedCrystals) {
		if (p == null || mob == null || o == null) return ProgressionRules.GateCheck.OK;
		ProgressionRules.Mode mode = RapConfig.get().progressionMode();
		if (mode == ProgressionRules.Mode.OFF) return ProgressionRules.GateCheck.OK;
		Stats.Player st = Stats.of(p.getEntityWorld().getServer()).player(p.getUuid());
		var ranked = st.effectiveRankedSpeciesWins();
		if (gateRank.isBoss() || o.rank().isBoss()) {
			int rank4Plus = ProgressionRules.countRankedSpeciesWinsAtLeastRank(ranked, 4);
			int rank5Plus = ProgressionRules.countRankedSpeciesWinsAtLeastRank(ranked, 5);
			return ProgressionRules.checkBossGate(
				mode,
				o.profile().id(),
				Math.max(0, precomputedCrystals),
				Trophies.hasAnyMic(p),
				Trophies.hasHighTierTrophyMic(p),
				st.respect,
				st.speciesWins.getOrDefault("guardian", 0),
				st.speciesWins.getOrDefault("wither_skeleton", 0),
				rank4Plus,
				rank5Plus);
		}
		int prevRank = gateRank.level() - 1;
		int prevWins = prevRank >= 1
			? ProgressionRules.countRankedSpeciesWinsAtExactRank(ranked, prevRank)
			: 0;
		boolean ownGang = Gangs.isOwn(p, o.profile());
		return ProgressionRules.checkRankGate(mode, gateRank.level(), false, st.respect, prevWins, ownGang);
	}

	/** Unified start eligibility check used by offerChoice, offerOnApproach, skipChoice, and auto-challenges. */
	public static StartCheck canStart(ServerPlayerEntity p, MobEntity mob, boolean checkDressCode,
									  int precomputedCrystals, Random rnd) {
		String lang = Lang.of(p);
		Opponent o = Opponent.of(mob, rnd, p, lang);
		EffectiveRanks ranks = computeRanks(p, mob, o);
		Outfit.Score score = Outfit.of(p, lang);
		boolean micAllowed = Trophies.hasAnyMic(p);
		boolean dressAllowed = !checkDressCode || !RapConfig.get().dressCode
			|| score.level() >= Outfit.required(ranks.gateRank()) || Gangs.isOwn(p, o.profile());
		ProgressionRules.GateCheck gate = check(p, mob, o, ranks.gateRank(), precomputedCrystals);
		return new StartCheck(micAllowed && dressAllowed && gate.allowed(), micAllowed, dressAllowed, gate, ranks, o, score);
	}

	/** Whether an automatic callout (bastion brute, raid captain, champion, rematch) may trigger for {@code p}. */
	public static boolean canAutoChallengeMob(ServerPlayerEntity p, MobEntity mob) {
		if (p == null || mob == null) return false;
		return canStart(p, mob, false, 0, new Random()).allowed();
	}

	/** Formatted access status line shown under Respect in StyleScreen. */
	public static String styleSummary(ServerPlayerEntity p, String lang) {
		if (p == null) return "";
		ProgressionRules.Mode mode = RapConfig.get().progressionMode();
		if (mode == ProgressionRules.Mode.OFF) {
			return Lang.t(lang, "rapbattle.progression.style_summary_off");
		}
		Stats.Player st = Stats.of(p.getEntityWorld().getServer()).player(p.getUuid());
		var ranked = st.effectiveRankedSpeciesWins();
		int r3Wins = ProgressionRules.countRankedSpeciesWinsAtExactRank(ranked, 3);
		int r4Wins = ProgressionRules.countRankedSpeciesWinsAtExactRank(ranked, 4);
		int r5PlusWins = ProgressionRules.countRankedSpeciesWinsAtLeastRank(ranked, 5);

		String r4 = (st.respect >= ProgressionRules.RANK4_RESPECT || r3Wins >= ProgressionRules.SPECIES_NEEDED)
			? Lang.t(lang, "rapbattle.progression.rank_open", 4)
			: Lang.t(lang, "rapbattle.progression.rank_locked", 4, st.respect, ProgressionRules.RANK4_RESPECT,
				Math.min(r3Wins, ProgressionRules.SPECIES_NEEDED), ProgressionRules.SPECIES_NEEDED);
		String r5 = (st.respect >= ProgressionRules.RANK5_RESPECT || r4Wins >= ProgressionRules.SPECIES_NEEDED)
			? Lang.t(lang, "rapbattle.progression.rank_open", 5)
			: Lang.t(lang, "rapbattle.progression.rank_locked", 5, st.respect, ProgressionRules.RANK5_RESPECT,
				Math.min(r4Wins, ProgressionRules.SPECIES_NEEDED), ProgressionRules.SPECIES_NEEDED);
		boolean dragonOpen = Trophies.hasHighTierTrophyMic(p) && st.respect >= ProgressionRules.DRAGON_RESPECT && r5PlusWins >= 1;
		String dr = Lang.t(lang, dragonOpen ? "rapbattle.progression.dragon_open" : "rapbattle.progression.dragon_locked");
		return Lang.t(lang, "rapbattle.progression.style_summary", r4, r5, dr);
	}
}
