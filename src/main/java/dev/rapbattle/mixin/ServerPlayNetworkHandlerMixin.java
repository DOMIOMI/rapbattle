package dev.rapbattle.mixin;

import dev.rapbattle.battle.BattleManager;
import net.minecraft.network.packet.c2s.play.ClickSlotC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Blocks dropping items, offhand swaps, and inventory slot clicks during an active battle without client desync. */
@Mixin(ServerPlayNetworkHandler.class)
public abstract class ServerPlayNetworkHandlerMixin {
	@Shadow public ServerPlayerEntity player;

	@Inject(method = "onPlayerAction", at = @At("HEAD"), cancellable = true)
	private void rapbattle$blockDropAndOffhandSwap(PlayerActionC2SPacket packet, CallbackInfo ci) {
		if (!BattleManager.isBattling(player)) return;
		PlayerActionC2SPacket.Action action = packet.getAction();
		if (action == PlayerActionC2SPacket.Action.DROP_ITEM
			|| action == PlayerActionC2SPacket.Action.DROP_ALL_ITEMS
			|| action == PlayerActionC2SPacket.Action.SWAP_ITEM_WITH_OFFHAND) {
			BattleManager.micOnlyHint(player);
			player.currentScreenHandler.syncState();
			ci.cancel();
		}
	}

	@Inject(method = "onClickSlot", at = @At("HEAD"), cancellable = true)
	private void rapbattle$blockInventoryClicks(ClickSlotC2SPacket packet, CallbackInfo ci) {
		if (!BattleManager.isBattling(player)) return;
		BattleManager.micOnlyHint(player);
		player.currentScreenHandler.syncState();
		ci.cancel();
	}
}
