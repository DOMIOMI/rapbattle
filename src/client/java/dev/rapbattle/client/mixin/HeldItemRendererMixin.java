package dev.rapbattle.client.mixin;

import dev.rapbattle.client.FirstPersonMic;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.item.HeldItemRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Arm;
import net.minecraft.util.Hand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Animates the right-hand fist with a microphone during TIMELINE and left-hand reactions. */
@Mixin(HeldItemRenderer.class)
public abstract class HeldItemRendererMixin {
	@Inject(method = "renderArmHoldingItem", at = @At("HEAD"))
	private void rapbattle$armHead(MatrixStack matrices, OrderedRenderCommandQueue queue, int light,
								   float equipProgress, float swingProgress, Arm arm, CallbackInfo ci) {
		matrices.push();
		FirstPersonMic.applyArmTransform(matrices, arm);
	}

	@Inject(method = "renderArmHoldingItem", at = @At("RETURN"))
	private void rapbattle$armTail(MatrixStack matrices, OrderedRenderCommandQueue queue, int light,
								   float equipProgress, float swingProgress, Arm arm, CallbackInfo ci) {
		FirstPersonMic.renderMicInArm(matrices, queue, light, arm);
		matrices.pop();
	}

	@Inject(method = "renderFirstPersonItem", at = @At("HEAD"))
	private void rapbattle$leftHand(AbstractClientPlayerEntity player, float tickDelta, float pitch, Hand hand,
									float swingProgress, ItemStack item, float equipProgress,
									MatrixStack matrices, OrderedRenderCommandQueue queue, int light, CallbackInfo ci) {
		FirstPersonMic.applyLeftHand(player, hand, matrices);
	}
}
