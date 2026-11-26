package dev.rapbattle.mixin;

import dev.rapbattle.battle.BattleManager;
import dev.rapbattle.battle.PlayerStatus;
import dev.rapbattle.battle.PvpBattle;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.AbstractPiglinEntity;
import net.minecraft.entity.mob.PiglinBruteBrain;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.world.ServerWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;
import java.util.UUID;

/** Suppresses piglin brute aggression for players in battles/PvP/pending or with Bastion Guest status. */
@Mixin(PiglinBruteBrain.class)
public class PiglinBruteBrainMixin {
	@Inject(method = "getTarget", at = @At("RETURN"), cancellable = true)
	private static void rapbattle$ignoreProtectedPlayers(ServerWorld world, AbstractPiglinEntity piglin,
			CallbackInfoReturnable<Optional<? extends LivingEntity>> cir) {
		Optional<? extends LivingEntity> opt = cir.getReturnValue();
		if (opt != null && opt.isPresent() && opt.get() instanceof PlayerEntity p) {
			UUID u = p.getUuid();
			if (BattleManager.byPlayer(u) != null || PvpBattle.byPlayer(u) != null || BattleManager.hasPending(u)
				|| PlayerStatus.has(u, PlayerStatus.BASTION_GUEST)) {
				cir.setReturnValue(Optional.empty());
			}
		}
	}
}
