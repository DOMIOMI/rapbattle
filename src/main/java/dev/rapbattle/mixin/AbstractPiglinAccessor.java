package dev.rapbattle.mixin;

import net.minecraft.entity.mob.AbstractPiglinEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(AbstractPiglinEntity.class)
public interface AbstractPiglinAccessor {
	@Invoker("isImmuneToZombification")
	boolean rapbattle$isImmuneToZombification();
}
