package dev.rapbattle.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.rapbattle.client.RapDancer;
import net.minecraft.client.model.Model;
import net.minecraft.client.render.command.ModelCommandRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** The final pose of every model before it is drawn: our battle moves go on top of it. */
@Mixin(ModelCommandRenderer.class)
public class ModelCommandRendererMixin {
	@WrapOperation(method = "render(Lnet/minecraft/client/render/command/OrderedRenderCommandQueueImpl$ModelCommand;Lnet/minecraft/client/render/RenderLayer;Lnet/minecraft/client/render/VertexConsumer;Lnet/minecraft/client/render/OutlineVertexConsumerProvider;Lnet/minecraft/client/render/VertexConsumerProvider$Immediate;)V",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/model/Model;setAngles(Ljava/lang/Object;)V"),
		require = 0)
	private void rapbattle$dance(Model<?> model, Object state, Operation<Void> original) {
		original.call(model, state);
		RapDancer.after(model, state);
	}
}
