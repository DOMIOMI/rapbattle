package dev.rapbattle.client.mixin;

import dev.rapbattle.client.ClientBattle;
import dev.rapbattle.client.ClientConfig;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Adds a brief +1.5 deg FOV kick when a mob lands a punchline during a battle. */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
	@Inject(method = "getFov", at = @At("RETURN"), cancellable = true)
	private void rapbattle$punchFov(Camera camera, float tickDelta, boolean changingFov, CallbackInfoReturnable<Float> cir) {
		if (!ClientConfig.get().cameraMotion || !ClientBattle.onStage()) return;
		float kick = ClientBattle.fovImpulse();
		if (kick > 0) cir.setReturnValue(cir.getReturnValue() + 1.5f * kick);
	}
}
