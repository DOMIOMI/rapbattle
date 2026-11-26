package dev.rapbattle.client.mixin;

import dev.rapbattle.client.Moves;
import dev.rapbattle.client.RapDancer;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.entity.model.WitchEntityModel;
import net.minecraft.client.render.entity.state.LivingEntityRenderState;
import net.minecraft.client.render.entity.state.WitchEntityRenderState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(WitchEntityModel.class)
public abstract class WitchModelMixin implements RapDancer {
	@Shadow @Final private ModelPart head;
	@Shadow @Final private ModelPart arms;

	@Override
	public void rapbattle$dance(LivingEntityRenderState s) {
		if (!(s instanceof WitchEntityRenderState state)) return;
		Moves.simple(state, head, arms, null);
	}
}
