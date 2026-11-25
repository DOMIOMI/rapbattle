package dev.rapbattle.client.mixin;

import dev.rapbattle.client.FirstPersonMic;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.item.HeldItemRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Hand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Replaces the empty right hand with a microphone during TIMELINE and animates left-hand reactions. */
@Mixin(HeldItemRenderer.class)
public abstract class HeldItemRendererMixin {
	@Inject(method = "renderFirstPersonItem", at = @At("HEAD"), cancellable = true)
	private void rapbattle$firstPersonMic(AbstractClientPlayerEntity player, float tickDelta, float pitch, Hand hand,
										  float swingProgress, ItemStack item, float equipProgress,
										  MatrixStack matrices, OrderedRenderCommandQueue queue, int light, CallbackInfo ci) {
		if (FirstPersonMic.shouldRenderRight(player, hand, item)) {
			FirstPersonMic.renderRight(player, matrices, queue, light);
			ci.cancel();
			return;
		}
		FirstPersonMic.applyLeftHand(player, hand, matrices);
	}
}
