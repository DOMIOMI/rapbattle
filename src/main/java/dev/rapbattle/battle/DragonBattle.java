package dev.rapbattle.battle;

import dev.rapbattle.Lang;
import net.minecraft.entity.Entity;
import net.minecraft.entity.boss.dragon.EnderDragonEntity;
import net.minecraft.entity.boss.dragon.phase.PhaseType;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.Random;

/**
 * Act 5: Ender Dragon battle rules (jammer crystal check, hover phase during battle, 60s loss cooldown).
 */
public final class DragonBattle {
	private DragonBattle() {}

	private static long cooldownUntil = 0L;

	public static int countCrystals(EnderDragonEntity dragon) {
		if (dragon != null && dragon.getEntityWorld() instanceof ServerWorld sw) {
			return sw.getEntitiesByClass(EndCrystalEntity.class, dragon.getBoundingBox().expand(192), Entity::isAlive).size();
		}
		return 0;
	}

	public static boolean canChallenge(ServerPlayerEntity sp, EnderDragonEntity dragon) {
		if (!dragon.isAlive() || dragon.ticksSinceDeath > 0
			|| dragon.getPhaseManager().getCurrent().getType() == PhaseType.DYING) {
			return false;
		}
		long now = System.currentTimeMillis();
		if (now < cooldownUntil) {
			long sec = Math.max(1, (cooldownUntil - now + 999) / 1000);
			sp.sendMessage(Text.translatable("rapbattle.act5.cooldown", sec).formatted(Formatting.RED), true);
			return false;
		}
		int crystals = countCrystals(dragon);
		if (crystals > 0) {
			sp.sendMessage(Text.translatable("rapbattle.act5.jammers", crystals).formatted(Formatting.LIGHT_PURPLE), true);
			return false;
		}
		Opponent opp = Opponent.of(dragon, new Random(), sp, Lang.of(sp));
		ProgressionRules.GateCheck gate = Progression.check(sp, dragon, opp, opp.rank(), 0);
		if (!gate.allowed()) {
			sp.sendMessage(Text.translatable(gate.messageKey(), gate.args()).formatted(Formatting.RED), false);
			return false;
		}
		return true;
	}

	static void holdHover(EnderDragonEntity dragon) {
		if (dragon.isAlive() && dragon.ticksSinceDeath == 0
			&& dragon.getPhaseManager().getCurrent().getType() != PhaseType.HOVER) {
			dragon.getPhaseManager().setPhase(PhaseType.HOVER);
		}
	}

	static void onLoss(EnderDragonEntity dragon) {
		cooldownUntil = System.currentTimeMillis() + 60_000L;
		if (dragon.isAlive() && dragon.ticksSinceDeath == 0) {
			dragon.getPhaseManager().setPhase(PhaseType.HOLDING_PATTERN);
		}
	}

	static void clear() {
		cooldownUntil = 0L;
	}
}
