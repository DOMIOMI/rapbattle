package dev.rapbattle.mixin;

import dev.rapbattle.battle.Speech;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.Angerable;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** When a hostile mob picks a player as its new target, it shouts a line. */
@Mixin(MobEntity.class)
public abstract class MobEntityTargetMixin {
	@Inject(method = "setTarget", at = @At("HEAD"), cancellable = true)
	private void rapbattle$aggroLine(LivingEntity target, CallbackInfo ci) {
		MobEntity self = (MobEntity) (Object) this;
		if (target != null && dev.rapbattle.battle.Crowd.inTruce(self)) {
			ci.cancel();
			return;
		}
		if (!(target instanceof ServerPlayerEntity player) || self.getTarget() == target) return;
		if (self instanceof HostileEntity || self instanceof Angerable) Speech.onAggro(self, player);
	}
}
