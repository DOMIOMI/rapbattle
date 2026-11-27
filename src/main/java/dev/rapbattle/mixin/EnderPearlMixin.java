package dev.rapbattle.mixin;

import dev.rapbattle.battle.BattleManager;
import net.minecraft.entity.projectile.thrown.EnderPearlEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.hit.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Cancels ender pearl teleports for players currently participating in a rap battle. */
@Mixin(EnderPearlEntity.class)
public abstract class EnderPearlMixin {
	@Inject(method = "onCollision", at = @At("HEAD"), cancellable = true)
	private void rapbattle$cancelPearlInBattle(HitResult hitResult, CallbackInfo ci) {
		EnderPearlEntity self = (EnderPearlEntity) (Object) this;
		if (!self.getEntityWorld().isClient()
			&& self.getOwner() instanceof ServerPlayerEntity sp
			&& BattleManager.isBattling(sp)) {
			self.discard();
			ci.cancel();
		}
	}
}
