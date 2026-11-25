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
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Renders the raised right fist with a detailed microphone during TIMELINE and left-hand reactions. */
@Mixin(HeldItemRenderer.class)
public abstract class HeldItemRendererMixin {
	@Shadow
	protected abstract void renderArmHoldingItem(MatrixStack matrices, OrderedRenderCommandQueue queue, int light,
												 float equipProgress, float swingProgress, Arm arm);

	@Inject(method = "renderFirstPersonItem", at = @At("HEAD"), cancellable = true)
	private void rapbattle$firstPersonMic(AbstractClientPlayerEntity player, float tickDelta, float pitch, Hand hand,
										  float swingProgress, ItemStack item, float equipProgress,
										  MatrixStack matrices, OrderedRenderCommandQueue queue, int light, CallbackInfo ci) {
		if (FirstPersonMic.shouldRenderRight(player, hand, item)) {
			matrices.push();
			FirstPersonMic.applyFistOffset(player, matrices);
			this.renderArmHoldingItem(matrices, queue, light, 0f, swingProgress, Arm.RIGHT);
			// renderArmHoldingItem leaves its arm transform on the stack: follow it down to the fist.
			org.joml.Vector3f fist = FirstPersonMic.fistPosition(matrices);
			matrices.pop();
			FirstPersonMic.renderMicCameraSpace(player, matrices, queue, light, fist);
			ci.cancel();
			return;
		}
		FirstPersonMic.applyLeftHand(player, hand, matrices);
	}
}
