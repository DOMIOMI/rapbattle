package dev.rapbattle.client.mixin;

import dev.rapbattle.client.RapDancer;
import net.minecraft.client.render.entity.state.LivingEntityRenderState;

import dev.rapbattle.client.Moves;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.entity.model.IronGolemEntityModel;
import net.minecraft.client.render.entity.state.IronGolemEntityRenderState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(IronGolemEntityModel.class)
public abstract class IronGolemModelMixin implements RapDancer {
	@Shadow @Final private ModelPart head;
	@Shadow @Final private ModelPart rightArm;
	@Shadow @Final private ModelPart leftArm;

	@Override
	public void rapbattle$dance(LivingEntityRenderState s) {
		if (!(s instanceof IronGolemEntityRenderState state)) return;
		Moves.simple(state, head, rightArm, leftArm);
	}
}
