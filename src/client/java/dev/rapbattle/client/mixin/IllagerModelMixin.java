package dev.rapbattle.client.mixin;

import dev.rapbattle.client.RapDancer;
import net.minecraft.client.render.entity.state.LivingEntityRenderState;

import dev.rapbattle.client.Moves;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.entity.model.IllagerEntityModel;
import net.minecraft.client.render.entity.state.IllagerEntityRenderState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(IllagerEntityModel.class)
public abstract class IllagerModelMixin implements RapDancer {
	@Shadow @Final private ModelPart head;
	@Shadow @Final private ModelPart rightArm;
	@Shadow @Final private ModelPart leftArm;

	@Override
	public void rapbattle$dance(LivingEntityRenderState s) {
		if (!(s instanceof IllagerEntityRenderState state)) return;
		Moves.simple(state, head, rightArm, leftArm);
	}
}
