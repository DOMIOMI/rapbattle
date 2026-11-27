package dev.rapbattle.mixin;

import dev.rapbattle.battle.BattleManager;
import net.minecraft.network.packet.c2s.play.ClickSlotC2SPacket;
import net.minecraft.network.packet.c2s.play.CreativeInventoryActionC2SPacket;
import net.minecraft.network.packet.c2s.play.PickItemFromBlockC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Blocks dropping items, offhand swaps, spear stabs, creative/pick-item, and slot clicks during a battle on the main thread. */
@Mixin(ServerPlayNetworkHandler.class)
public abstract class ServerPlayNetworkHandlerMixin {
	@Shadow public ServerPlayerEntity player;

	private static final String FORCE_MAIN =
		"Lnet/minecraft/network/NetworkThreadUtils;forceMainThread(Lnet/minecraft/network/packet/Packet;Lnet/minecraft/network/listener/PacketListener;Lnet/minecraft/server/world/ServerWorld;)V";

	@Inject(method = "onPlayerAction", at = @At(value = "INVOKE", target = FORCE_MAIN, shift = At.Shift.AFTER), cancellable = true)
	private void rapbattle$blockDropAndOffhandSwap(PlayerActionC2SPacket packet, CallbackInfo ci) {
		if (!BattleManager.isBattling(player)) return;
		PlayerActionC2SPacket.Action action = packet.getAction();
		if (action == PlayerActionC2SPacket.Action.DROP_ITEM
			|| action == PlayerActionC2SPacket.Action.DROP_ALL_ITEMS
			|| action == PlayerActionC2SPacket.Action.SWAP_ITEM_WITH_OFFHAND
			|| action == PlayerActionC2SPacket.Action.STAB) {
			BattleManager.micOnlyHint(player);
			player.currentScreenHandler.syncState();
			ci.cancel();
		}
	}

	@Inject(method = "onClickSlot", at = @At(value = "INVOKE", target = FORCE_MAIN, shift = At.Shift.AFTER), cancellable = true)
	private void rapbattle$blockInventoryClicks(ClickSlotC2SPacket packet, CallbackInfo ci) {
		if (!BattleManager.isBattling(player)) return;
		BattleManager.micOnlyHint(player);
		player.currentScreenHandler.syncState();
		ci.cancel();
	}

	@Inject(method = "onCreativeInventoryAction", at = @At(value = "INVOKE", target = FORCE_MAIN, shift = At.Shift.AFTER), cancellable = true)
	private void rapbattle$blockCreativeInventory(CreativeInventoryActionC2SPacket packet, CallbackInfo ci) {
		if (!BattleManager.isBattling(player)) return;
		BattleManager.micOnlyHint(player);
		player.currentScreenHandler.syncState();
		ci.cancel();
	}

	@Inject(method = "onPickItemFromBlock", at = @At(value = "INVOKE", target = FORCE_MAIN, shift = At.Shift.AFTER), cancellable = true)
	private void rapbattle$blockPickItem(PickItemFromBlockC2SPacket packet, CallbackInfo ci) {
		if (!BattleManager.isBattling(player)) return;
		BattleManager.micOnlyHint(player);
		player.currentScreenHandler.syncState();
		ci.cancel();
	}
}
