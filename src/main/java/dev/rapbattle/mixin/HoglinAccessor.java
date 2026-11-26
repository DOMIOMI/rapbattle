package dev.rapbattle.mixin;

import net.minecraft.entity.mob.HoglinEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(HoglinEntity.class)
public interface HoglinAccessor {
	@Invoker("isImmuneToZombification")
	boolean rapbattle$isImmuneToZombification();
}
