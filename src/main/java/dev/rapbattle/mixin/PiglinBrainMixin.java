package dev.rapbattle.mixin;

import dev.rapbattle.battle.BastionBattle;
import dev.rapbattle.battle.BattleManager;
import dev.rapbattle.battle.Crowd;
import dev.rapbattle.battle.PlayerStatus;
import dev.rapbattle.battle.PvpBattle;
import dev.rapbattle.battle.Reputation;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.mob.AbstractPiglinEntity;
import net.minecraft.entity.mob.PiglinBrain;
import net.minecraft.entity.mob.PiglinEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.world.ServerWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;
import java.util.UUID;

/** Suppresses piglin anger during battles/PvP/pending, for Bastion Guests, and (gold-check only) for Piglin Pals. */
@Mixin(PiglinBrain.class)
public class PiglinBrainMixin {
	private static boolean isQuietPlayer(LivingEntity target) {
		if (!(target instanceof PlayerEntity p)) return false;
		UUID u = p.getUuid();
		return BattleManager.byPlayer(u) != null || PvpBattle.byPlayer(u) != null || BattleManager.hasPending(u);
	}

	@Inject(method = "onGuardedBlockInteracted", at = @At("HEAD"), cancellable = true)
	private static void rapbattle$bastionGuestChests(ServerWorld world, PlayerEntity player, boolean blockOpen, CallbackInfo ci) {
		if (PlayerStatus.has(player.getUuid(), PlayerStatus.BASTION_GUEST) && BastionBattle.inBastion(world, player.getBlockPos())) {
			ci.cancel();
		}
	}

	@Inject(method = "becomeAngryWith", at = @At("HEAD"), cancellable = true)
	private static void rapbattle$quietDuringBattle(ServerWorld world, AbstractPiglinEntity piglin, LivingEntity target, CallbackInfo ci) {
		if (isQuietPlayer(target) || (target instanceof PlayerEntity p
			&& (PlayerStatus.has(p.getUuid(), PlayerStatus.BASTION_GUEST)
			|| Crowd.inTruce(piglin, p.getUuid())
			|| Reputation.respects(piglin, p)))) {
			ci.cancel();
		}
	}

	@Inject(method = "getPreferredTarget", at = @At("RETURN"), cancellable = true)
	private static void rapbattle$ignoreQuietPlayers(ServerWorld world, PiglinEntity piglin,
			CallbackInfoReturnable<Optional<? extends LivingEntity>> cir) {
		Optional<? extends LivingEntity> opt = cir.getReturnValue();
		if (opt == null || opt.isEmpty() || !(opt.get() instanceof PlayerEntity p)) return;
		UUID u = p.getUuid();
		if (isQuietPlayer(p) || PlayerStatus.has(u, PlayerStatus.BASTION_GUEST)
			|| Crowd.inTruce(piglin, u) || Reputation.respects(piglin, p)) {
			cir.setReturnValue(Optional.empty());
			return;
		}
		if (PlayerStatus.has(u, PlayerStatus.PIGLIN_PAL)) {
			// Piglin Pal acts like wearing gold: still gets angry from chests/gold mining (ANGRY_AT), not from bare armor.
			boolean angryAtThis = piglin.getBrain().getOptionalRegisteredMemory(MemoryModuleType.ANGRY_AT)
				.map(a -> a.equals(u)).orElse(false);
			if (!angryAtThis) {
				cir.setReturnValue(Optional.empty());
			}
		}
	}
}
