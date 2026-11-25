package dev.rapbattle.client.mixin;

import dev.rapbattle.client.Intro;
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

	@Shadow protected abstract void setPos(Vec3d pos);

	@Shadow protected abstract void setRotation(float yaw, float pitch);

	@Inject(method = "update", at = @At("TAIL"))
	private void rapbattle$intro(World area, Entity focusedEntity, boolean thirdPerson, boolean inverseView, float tickProgress, CallbackInfo ci) {
		Intro.Shot shot = Intro.shot(tickProgress);
		if (shot == null) return;
		this.setRotation(shot.yaw(), shot.pitch());
		this.setPos(shot.pos());
		this.thirdPerson = true;
	}
}
