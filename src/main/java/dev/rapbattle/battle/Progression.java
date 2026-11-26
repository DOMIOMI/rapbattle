package dev.rapbattle.battle;

import dev.rapbattle.Lang;
import dev.rapbattle.RapConfig;
import net.minecraft.entity.Entity;
import net.minecraft.entity.boss.dragon.EnderDragonEntity;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;

import java.util.Random;

/**
 * Server-side bridge between Minecraft entities/stats and pure {@link ProgressionRules}.
 */
public final class Progression {
	private Progression() {}

	public static ProgressionRules.GateCheck check(ServerPlayerEntity p, MobEntity mob, Opponent o) {
		if (p == null || mob == null || o == null) return ProgressionRules.GateCheck.OK;
		ProgressionRules.Mode mode = RapConfig.get().progressionMode();
		if (mode == ProgressionRules.Mode.OFF) return ProgressionRules.GateCheck.OK;
		Stats.Player st = Stats.of(p.getEntityWorld().getServer()).player(p.getUuid());
		if (o.rank().isBoss()) {
			int crystals = 0;
			if (mob instanceof EnderDragonEntity dragon && dragon.getEntityWorld() instanceof ServerWorld sw) {
				crystals = sw.getEntitiesByClass(EndCrystalEntity.class, dragon.getBoundingBox().expand(192), Entity::isAlive).size();
			}
			int rank4Plus = ProgressionRules.countSpeciesWinsAtLeastRank(st.speciesWins, 4, MobProfiles::baseRankOf);
			int rank5Plus = ProgressionRules.countSpeciesWinsAtLeastRank(st.speciesWins, 5, MobProfiles::baseRankOf);
			return ProgressionRules.checkBossGate(
				mode,
				o.profile().id(),
				crystals,
				Trophies.hasAnyMic(p),
				Trophies.hasHighTierTrophyMic(p),
				st.respect,
				st.speciesWins.getOrDefault("guardian", 0),
				st.speciesWins.getOrDefault("wither_skeleton", 0),
				rank4Plus,
				rank5Plus);
		}
		int prevRank = o.rank().level() - 1;
		int prevWins = prevRank >= 1
			? ProgressionRules.countSpeciesWinsAtExactRank(st.speciesWins, prevRank, MobProfiles::baseRankOf)
			: 0;
		boolean ownGang = Gangs.isOwn(p, o.profile());
		return ProgressionRules.checkRankGate(mode, o.rank().level(), false, st.respect, prevWins, ownGang);
	}

	/** Whether an automatic callout (bastion brute, raid captain, champion, rematch) may trigger for {@code p}. */
	public static boolean canAutoChallengeMob(ServerPlayerEntity p, MobEntity mob) {
		if (p == null || mob == null) return false;
		Opponent o = Opponent.of(mob, new Random(), p, Lang.of(p));
		return check(p, mob, o).allowed();
	}

	/** Formatted access status line shown under Respect in StyleScreen. */
	public static String styleSummary(ServerPlayerEntity p, String lang) {
		if (p == null) return "";
		ProgressionRules.Mode mode = RapConfig.get().progressionMode();
		if (mode == ProgressionRules.Mode.OFF) {
			return Lang.t(lang, "rapbattle.progression.style_summary_off");
		}
		Stats.Player st = Stats.of(p.getEntityWorld().getServer()).player(p.getUuid());
		int r3Wins = ProgressionRules.countSpeciesWinsAtExactRank(st.speciesWins, 3, MobProfiles::baseRankOf);
		int r4Wins = ProgressionRules.countSpeciesWinsAtExactRank(st.speciesWins, 4, MobProfiles::baseRankOf);
		int r5PlusWins = ProgressionRules.countSpeciesWinsAtLeastRank(st.speciesWins, 5, MobProfiles::baseRankOf);

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
