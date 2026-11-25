package dev.rapbattle.client.mixin;

import dev.rapbattle.client.RapDancer;
import net.minecraft.client.render.entity.state.LivingEntityRenderState;

import dev.rapbattle.client.Moves;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.entity.model.QuadrupedEntityModel;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Sheep, cows, pigs and friends: headbang and stomp. */
@Mixin(QuadrupedEntityModel.class)
public abstract class QuadrupedEntityModelMixin implements RapDancer {
	@Shadow @Final protected ModelPart head;
	@Shadow @Final protected ModelPart rightFrontLeg;
	@Shadow @Final protected ModelPart leftFrontLeg;
	@Shadow @Final protected ModelPart rightHindLeg;
	@Shadow @Final protected ModelPart leftHindLeg;

	@Override
	public void rapbattle$dance(LivingEntityRenderState s) {
		if (!(s instanceof LivingEntityRenderState state)) return;
		Moves.quadruped(state, head, rightFrontLeg, leftFrontLeg, rightHindLeg, leftHindLeg);
	}
}
