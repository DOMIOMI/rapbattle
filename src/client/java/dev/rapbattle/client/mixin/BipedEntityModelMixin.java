package dev.rapbattle.client.mixin;

import dev.rapbattle.client.RapDancer;
import net.minecraft.client.render.entity.state.LivingEntityRenderState;

import dev.rapbattle.client.Moves;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.entity.model.BipedEntityModel;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.client.render.entity.state.BipedEntityRenderState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Humanoid and player dance moves (mic in hand, nods, victory dances). */
@Mixin(BipedEntityModel.class)
public abstract class BipedEntityModelMixin implements RapDancer {
	@Shadow @Final public ModelPart head;
	@Shadow @Final public ModelPart rightArm;
	@Shadow @Final public ModelPart leftArm;
	@Shadow @Final public ModelPart body;
	@Shadow @Final public ModelPart rightLeg;
	@Shadow @Final public ModelPart leftLeg;

	@Override
	public void rapbattle$dance(LivingEntityRenderState s) {
		if (!(s instanceof BipedEntityRenderState state)) return;
		Moves.biped(state, head, body, rightArm, leftArm, rightLeg, leftLeg, (Object) this instanceof PlayerEntityModel);
	}
}
