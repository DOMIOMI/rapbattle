package dev.rapbattle.mixin;

import dev.rapbattle.battle.BattleManager;
import dev.rapbattle.battle.Reputation;
import dev.rapbattle.battle.Theft;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.mob.CreeperEntity;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Prevents creepers in battles or respecting a player from igniting, and drops stolen items on explosion. */
@Mixin(CreeperEntity.class)
public class CreeperMixin {
	@Shadow @Final private static TrackedData<Boolean> IGNITED;
	@Shadow private int currentFuseTime;
	@Shadow private int lastFuseTime;

	@Inject(method = "tick", at = @At("HEAD"))
	private void rapbattle$defuseInBattle(CallbackInfo ci) {
		CreeperEntity self = (CreeperEntity) (Object) this;
		boolean respectedTarget = self.getTarget() instanceof PlayerEntity p && Reputation.respects(self, p);
		if (BattleManager.byMob(self) != null || BattleManager.inAnyCrowd(self) || respectedTarget) {
			if (respectedTarget) self.setTarget(null);
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

	@Inject(method = "explode", at = @At("TAIL"))
	private void rapbattle$dropStolenOnExplode(CallbackInfo ci) {
		Theft.dropOnDeathOrExplode((CreeperEntity) (Object) this);
	}
}
