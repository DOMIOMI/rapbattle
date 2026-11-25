package dev.rapbattle.client.mixin;

import dev.rapbattle.client.BattleDance;
import dev.rapbattle.client.Bling;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.state.CameraRenderState;
import dev.rapbattle.client.RapRenderState;
import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.client.render.entity.state.LivingEntityRenderState;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.LivingEntity;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.rapbattle.client.RapDancer;
import net.minecraft.client.render.entity.model.EntityModel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Makes mobs and players in a battle bounce to the beat (every living entity, vanilla or modded). */
@Mixin(LivingEntityRenderer.class)
public class LivingEntityRendererMixin {
	@Inject(method = "updateRenderState(Lnet/minecraft/entity/LivingEntity;Lnet/minecraft/client/render/entity/state/LivingEntityRenderState;F)V",
		at = @At("TAIL"))
	private void rapbattle$rememberId(LivingEntity entity, LivingEntityRenderState state, float tickDelta, CallbackInfo ci) {
		((RapRenderState) state).rapbattle$setEntityId(entity.getId());
	}

	/** The pose the features (armor, held items, bling) are placed from: dance it too, so they move with the body. */
	@WrapOperation(method = "render(Lnet/minecraft/client/render/entity/state/LivingEntityRenderState;Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;Lnet/minecraft/client/render/state/CameraRenderState;)V",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/entity/model/EntityModel;setAngles(Ljava/lang/Object;)V"))
	private void rapbattle$pose(EntityModel<?> model, Object state, Operation<Void> original) {
		original.call(model, state);
		RapDancer.after(model, state);
	}

	@Inject(method = "setupTransforms", at = @At("TAIL"))
	private void rapbattle$dance(LivingEntityRenderState state, MatrixStack matrices, float bodyYaw, float baseHeight, CallbackInfo ci) {
		BattleDance.apply(((RapRenderState) state).rapbattle$entityId(), state, matrices);
	}
}
