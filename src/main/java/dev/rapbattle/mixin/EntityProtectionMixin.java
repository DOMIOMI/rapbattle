package dev.rapbattle.mixin;

import dev.rapbattle.battle.BattleManager;
import net.minecraft.entity.Entity;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Prevents battling players from being ignited or losing underwater air. */
@Mixin(Entity.class)
public abstract class EntityProtectionMixin {
	@Inject(method = "setFireTicks", at = @At("HEAD"), cancellable = true)
	private void rapbattle$preventFireTicks(int fireTicks, CallbackInfo ci) {
		if ((Object) this instanceof ServerPlayerEntity sp && fireTicks > 0 && BattleManager.isBattling(sp)) {
			ci.cancel();
		}
	}

	@Inject(method = "setAir", at = @At("HEAD"), cancellable = true)
	private void rapbattle$keepFullAir(int air, CallbackInfo ci) {
		if ((Object) this instanceof ServerPlayerEntity sp && air < sp.getMaxAir() && BattleManager.isBattling(sp)) {
			ci.cancel();
		}
	}
}
