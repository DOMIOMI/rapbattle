package dev.rapbattle.mixin;

import dev.rapbattle.battle.BattleManager;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffectCategory;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Blocks external harmful status effects on battling players while allowing mod-applied effects. */
@Mixin(LivingEntity.class)
public abstract class LivingEntityProtectionMixin {
	@Inject(method = "canHaveStatusEffect", at = @At("HEAD"), cancellable = true)
	private void rapbattle$blockHarmfulEffects(StatusEffectInstance effect, CallbackInfoReturnable<Boolean> cir) {
		if ((Object) this instanceof ServerPlayerEntity sp
			&& BattleManager.isBattling(sp)
			&& !BattleManager.isModForcedStatusEffect()
			&& effect.getEffectType().value().getCategory() == StatusEffectCategory.HARMFUL) {
			cir.setReturnValue(false);
		}
	}
}
