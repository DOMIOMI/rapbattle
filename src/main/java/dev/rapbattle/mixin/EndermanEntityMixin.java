package dev.rapbattle.mixin;

import dev.rapbattle.battle.Stats;
import dev.rapbattle.battle.Trophies;
import net.minecraft.entity.mob.EndermanEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Post-dragon progression: once a player has beaten the Ender Dragon in a rap battle
 * and holds a trophy microphone in their off-hand, Endermen no longer aggro from eye contact.
 * Hitting an Enderman still angers it normally.
 */
@Mixin(EndermanEntity.class)
public abstract class EndermanEntityMixin {
	@Inject(method = "isPlayerStaring", at = @At("HEAD"), cancellable = true)
	private void rapbattle$calmGazeAfterDragon(PlayerEntity player, CallbackInfoReturnable<Boolean> cir) {
		if (player instanceof ServerPlayerEntity sp
			&& Stats.of(sp.getEntityWorld().getServer()).player(sp.getUuid()).dragonWon
			&& Trophies.isTrophyMic(sp)) {
			cir.setReturnValue(false);
		}
	}
}
