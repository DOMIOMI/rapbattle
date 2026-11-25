package dev.rapbattle.client;

import net.minecraft.client.render.entity.state.LivingEntityRenderState;

/**
 * A model that can pose itself for the battle. Implemented by our model mixins and called right after the game has
 * fully posed the model (see {@link #after}), so subclasses that re-pose arms in their own setAngles (zombies,
 * drowned, skeletons, piglins, endermen, sheep heads...) can no longer override the dance.
 */
public interface RapDancer {
	void rapbattle$dance(LivingEntityRenderState state);

	/** Called after every {@code model.setAngles(state)} the game makes for rendering. */
	static void after(Object model, Object state) {
		if (model instanceof RapDancer d && state instanceof LivingEntityRenderState s) d.rapbattle$dance(s);
	}
}
