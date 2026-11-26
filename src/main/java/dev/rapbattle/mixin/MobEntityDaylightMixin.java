package dev.rapbattle.mixin;

import dev.rapbattle.battle.BattleManager;
import net.minecraft.entity.mob.MobEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Prevents champions, active battle participants/crowd, pending challengers, and mobs in truce from catching fire in daylight. */
@Mixin(MobEntity.class)
public class MobEntityDaylightMixin {
	@Inject(method = "isAffectedByDaylight", at = @At("HEAD"), cancellable = true)
	private void rapbattle$protectFromDaylight(CallbackInfoReturnable<Boolean> cir) {
		MobEntity self = (MobEntity) (Object) this;
		if (BattleManager.isProtectedFromSun(self)) {
			cir.setReturnValue(false);
		}
	}
}
