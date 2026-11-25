package dev.rapbattle.client.mixin;

import dev.rapbattle.client.RapRenderState;
import net.minecraft.client.render.entity.state.LivingEntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(LivingEntityRenderState.class)
public class LivingEntityRenderStateMixin implements RapRenderState {
	@Unique
	private int rapbattle$id = -1;

	@Override
	public int rapbattle$entityId() { return rapbattle$id; }

	@Override
	public void rapbattle$setEntityId(int id) { rapbattle$id = id; }
}
