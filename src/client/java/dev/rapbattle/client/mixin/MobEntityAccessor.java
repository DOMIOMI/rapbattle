package dev.rapbattle.client.mixin;

import net.minecraft.entity.mob.MobEntity;
import net.minecraft.sound.SoundEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Lets the client play a mob's own ambient sound while it "reads" its verse. */
@Mixin(MobEntity.class)
public interface MobEntityAccessor {
	@Invoker("getAmbientSound")
	SoundEvent rapbattle$getAmbientSound();
}
