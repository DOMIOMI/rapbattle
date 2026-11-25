package dev.rapbattle.client.mixin;

import dev.rapbattle.client.BattleDance;
import dev.rapbattle.client.ClientBattle;
import dev.rapbattle.client.ClientConfig;
import dev.rapbattle.client.Intro;
import dev.rapbattle.client.MobMoves;
import net.minecraft.client.render.Camera;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Flies the camera along the battle intro path (and shows your own character while it does). */
@Mixin(Camera.class)
public abstract class CameraMixin {
	@Shadow private boolean thirdPerson;
	@Shadow private float yaw;
	@Shadow private float pitch;

	@Shadow protected abstract void setPos(Vec3d pos);

	@Shadow protected abstract void setRotation(float yaw, float pitch);

	@Inject(method = "update", at = @At("TAIL"))
	private void rapbattle$intro(World area, Entity focusedEntity, boolean thirdPerson, boolean inverseView, float tickProgress, CallbackInfo ci) {
		Intro.Shot shot = Intro.shot(tickProgress);
		if (shot != null) {
			this.setRotation(shot.yaw(), shot.pitch());
			this.setPos(shot.pos());
			this.thirdPerson = true;
			return;
		}
		if (ClientConfig.get().cameraMotion && ClientBattle.onStage() && BattleDance.onBeat()) {
			float f = ClientBattle.beatFrac();
			float nod = 0.4f * (f < 0.08f ? f / 0.08f : (float) Math.exp(-(f - 0.08f) * 4.5));
			this.setRotation(this.yaw, this.pitch + nod);
		}
	}
}
