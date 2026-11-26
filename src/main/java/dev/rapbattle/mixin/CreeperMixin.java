package dev.rapbattle.mixin;

import dev.rapbattle.battle.BattleManager;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.mob.CreeperEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Prevents creepers in a battle's crowd or mobTeam from igniting or exploding during the show. */
@Mixin(CreeperEntity.class)
public class CreeperMixin {
	@Shadow @Final private static TrackedData<Boolean> IGNITED;
	@Shadow private int currentFuseTime;
	@Shadow private int lastFuseTime;

	@Inject(method = "tick", at = @At("HEAD"))
	private void rapbattle$defuseInBattle(CallbackInfo ci) {
		CreeperEntity self = (CreeperEntity) (Object) this;
		if (BattleManager.byMob(self) != null || BattleManager.inAnyCrowd(self)) {
			self.getDataTracker().set(IGNITED, false);
			self.setFuseSpeed(-1);
			currentFuseTime = 0;
			lastFuseTime = 0;
		}
	}

	@Inject(method = "ignite", at = @At("HEAD"), cancellable = true)
	private void rapbattle$blockIgniteInBattle(CallbackInfo ci) {
		CreeperEntity self = (CreeperEntity) (Object) this;
		if (BattleManager.byMob(self) != null || BattleManager.inAnyCrowd(self)) {
			ci.cancel();
		}
	}
}
