package dev.rapbattle.client;

/** Extra field mixed into LivingEntityRenderState: which entity this render state belongs to. */
public interface RapRenderState {
	int rapbattle$entityId();

	void rapbattle$setEntityId(int id);
}
