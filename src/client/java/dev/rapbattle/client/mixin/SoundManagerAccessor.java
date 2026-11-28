package dev.rapbattle.client.mixin;

import net.minecraft.client.sound.SoundManager;
import net.minecraft.client.sound.SoundSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Lets the beat player reach the playing OpenAL source to keep the beat on the battle clock. */
@Mixin(SoundManager.class)
public interface SoundManagerAccessor {
	@Accessor("soundSystem")
	SoundSystem rapbattle$soundSystem();
}
