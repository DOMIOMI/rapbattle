package dev.rapbattle.mixin;

import dev.rapbattle.battle.BastionBattle;
import dev.rapbattle.battle.BattleManager;
import dev.rapbattle.battle.PlayerStatus;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.AbstractPiglinEntity;
import net.minecraft.entity.mob.PiglinBrain;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.world.ServerWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Suppresses piglin anger during battles and for players with the 10-minute Bastion Guest status. */
@Mixin(PiglinBrain.class)
public class PiglinBrainMixin {
	@Inject(method = "onGuardedBlockInteracted", at = @At("HEAD"), cancellable = true)
	private static void rapbattle$bastionGuestChests(ServerWorld world, PlayerEntity player, boolean blockOpen, CallbackInfo ci) {
		if (PlayerStatus.has(player.getUuid(), PlayerStatus.BASTION_GUEST) && BastionBattle.inBastion(world, player.getBlockPos())) {
			ci.cancel();
		}
	}

	@Inject(method = "becomeAngryWith", at = @At("HEAD"), cancellable = true)
	private static void rapbattle$quietDuringBattle(ServerWorld world, AbstractPiglinEntity piglin, LivingEntity target, CallbackInfo ci) {
		if (target instanceof PlayerEntity p && BattleManager.byPlayer(p.getUuid()) != null) {
			ci.cancel();
		}
	}
}
