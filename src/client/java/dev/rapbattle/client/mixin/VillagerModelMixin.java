package dev.rapbattle.client.mixin;

import dev.rapbattle.client.RapDancer;
import net.minecraft.client.render.entity.state.LivingEntityRenderState;

import dev.rapbattle.client.Moves;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.entity.model.VillagerResemblingModel;
import net.minecraft.client.render.entity.state.VillagerEntityRenderState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(VillagerResemblingModel.class)
public abstract class VillagerModelMixin implements RapDancer {
	@Shadow @Final private ModelPart head;
	@Shadow @Final private ModelPart arms;

	@Override
	public void rapbattle$dance(LivingEntityRenderState s) {
		if (!(s instanceof VillagerEntityRenderState state)) return;
		Moves.simple(state, head, arms, null);
	}
}
