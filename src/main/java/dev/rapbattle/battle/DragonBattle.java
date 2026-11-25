package dev.rapbattle.battle;

import net.minecraft.entity.Entity;
import net.minecraft.entity.boss.dragon.EnderDragonEntity;
import net.minecraft.entity.boss.dragon.phase.PhaseType;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * Act 5: Ender Dragon battle rules (jammer crystal check, hover phase during battle, 60s loss cooldown).
 */
public final class DragonBattle {
	private DragonBattle() {}

	private static long cooldownUntil = 0L;

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
		if (dragon.getEntityWorld() instanceof ServerWorld sw) {
			int crystals = sw.getEntitiesByClass(EndCrystalEntity.class, dragon.getBoundingBox().expand(192), Entity::isAlive).size();
			if (crystals > 0) {
				sp.sendMessage(Text.translatable("rapbattle.act5.jammers", crystals).formatted(Formatting.LIGHT_PURPLE), true);
				return false;
			}
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
			dragon.getPhaseManager().setPhase(PhaseType.TAKEOFF);
		}
	}
}
