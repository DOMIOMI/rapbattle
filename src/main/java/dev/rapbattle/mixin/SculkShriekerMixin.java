package dev.rapbattle.mixin;

import dev.rapbattle.battle.PlayerStatus;
import net.minecraft.block.entity.SculkShriekerBlockEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Suppresses sculk shriekers within 48 blocks for players with the 15-minute Warden Quiet status. */
@Mixin(SculkShriekerBlockEntity.class)
public class SculkShriekerMixin {
	@Inject(method = "shriek(Lnet/minecraft/server/world/ServerWorld;Lnet/minecraft/server/network/ServerPlayerEntity;)V",
		at = @At("HEAD"), cancellable = true)
	private void rapbattle$wardenQuiet(ServerWorld world, ServerPlayerEntity player, CallbackInfo ci) {
		if (player != null && PlayerStatus.hasInRadius(player.getUuid(), PlayerStatus.WARDEN_QUIET, world,
			((SculkShriekerBlockEntity) (Object) this).getPos())) {
			ci.cancel();
		}
	}
}
