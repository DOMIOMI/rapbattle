package dev.rapbattle.mixin;

import dev.rapbattle.battle.BattleManager;
import net.minecraft.entity.ai.control.MoveControl;
import net.minecraft.entity.mob.MobEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keeps placed crowd spectators completely still by suppressing MoveControl ticks while on their spot. */
@Mixin(MoveControl.class)
public class MoveControlMixin {
	@Shadow @Final protected MobEntity entity;

	@Inject(method = "tick", at = @At("HEAD"), cancellable = true)
	private void rapbattle$freezePlacedCrowd(CallbackInfo ci) {
		if (BattleManager.isPlacedCrowd(entity)) {
			entity.setForwardSpeed(0);
			entity.setSidewaysSpeed(0);
			ci.cancel();
		}
	}
}
