package dev.rapbattle.client;

import dev.rapbattle.RapBattle;
import dev.rapbattle.net.Payloads;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;

public class RapBattleClient implements ClientModInitializer {
	private static KeyBinding accept, decline, rap;

	@SuppressWarnings({"unchecked", "rawtypes"})
	private static void registerBling(net.minecraft.client.render.entity.LivingEntityRenderer renderer,
									  net.fabricmc.fabric.api.client.rendering.v1.LivingEntityFeatureRendererRegistrationCallback.RegistrationHelper helper) {
		helper.register(new BlingFeature(renderer));
	}

	@Override
	public void onInitializeClient() {
		ClientConfig.load();
		StageFx.register();
		MobBadgeFx.register();
		SmokeFx.register();
		PlayerMoves.register();
		// Settings: /rapbattle (works without Mod Menu).
		net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) ->
			dispatcher.register(net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal("rapbattle")
				.then(net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal("clip").executes(ctx -> {
					ClipRecorder.save();
					return 1;
				}))
				.then(net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal("style").executes(ctx -> {
					var mc = net.minecraft.client.MinecraftClient.getInstance();
					mc.send(() -> mc.setScreen(new StyleScreen(null)));
					return 1;
				}))
				.then(net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal("legend").executes(ctx -> {
					ClientPlayNetworking.send(new Payloads.ActionC2S("{\"type\":\"legend\"}"));
					return 1;
				}))
				.then(net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal("guide").executes(ctx -> {
					ClientPlayNetworking.send(new Payloads.ActionC2S("{\"type\":\"guide\"}"));
					return 1;
				}))
				.then(net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal("leave").executes(ctx -> {
					ClientPlayNetworking.send(new Payloads.ActionC2S("{\"type\":\"leave\"}"));
					return 1;
				}))
				.executes(ctx -> {
				var mc = net.minecraft.client.MinecraftClient.getInstance();
				// Open on the next frame: the chat screen closes right after the command runs.
				mc.send(() -> mc.setScreen(new RapSettingsScreen(null)));
				return 1;
			})));
		net.fabricmc.fabric.api.client.rendering.v1.LivingEntityFeatureRendererRegistrationCallback.EVENT.register(
			(type, renderer, helper, context) -> registerBling(renderer, helper));
		// During your battle the table takes the place of the hotbar and the bars: hide them.
		for (Identifier id : new Identifier[]{VanillaHudElements.HOTBAR, VanillaHudElements.ARMOR_BAR, VanillaHudElements.HEALTH_BAR,
			VanillaHudElements.FOOD_BAR, VanillaHudElements.AIR_BAR, VanillaHudElements.MOUNT_HEALTH, VanillaHudElements.INFO_BAR,
			VanillaHudElements.EXPERIENCE_LEVEL, VanillaHudElements.HELD_ITEM_TOOLTIP}) {
			HudElementRegistry.replaceElement(id, old -> (ctx, tick) -> {
				if (!ClientBattle.onStage()) old.render(ctx, tick);
			});
		}
		KeyBinding.Category category = KeyBinding.Category.create(Identifier.of(RapBattle.MOD_ID, "main"));
		accept = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.rapbattle.accept", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_Y, category));
		decline = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.rapbattle.decline", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_N, category));
		rap = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.rapbattle.rap", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_R, category));

		ClientPlayNetworking.registerGlobalReceiver(Payloads.StateS2C.ID, (payload, ctx) -> ClientBattle.onPacket(payload.json()));
		ClientPlayNetworking.registerGlobalReceiver(Payloads.MobBadgesS2C.ID, (payload, ctx) -> ctx.client().execute(() -> MobBadgeFx.onPacket(payload)));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			ClientBattle.disconnect();
			MobBadgeFx.clear();
		});

		ClientTickEvents.START_CLIENT_TICK.register(client -> {
			if (client.player != null && ClientBattle.view != null && !ClientBattle.view.spectator) {
				client.options.forwardKey.setPressed(false);
				client.options.backKey.setPressed(false);
				client.options.leftKey.setPressed(false);
				client.options.rightKey.setPressed(false);
				client.options.sprintKey.setPressed(false);
				if (!Intro.active()) client.options.jumpKey.setPressed(false);
			}
		});

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (accept.wasPressed()) ClientBattle.pressAccept();
			while (decline.wasPressed()) ClientBattle.pressDecline();
			while (rap.wasPressed()) ClientBattle.pressRap();
			pollTopicKeys(client);
			if (Intro.active() && client.options.jumpKey.isPressed()) Intro.skip();
			// Keeps the beat and the mic going even when the HUD is hidden (F1).
			ClientBattle.pump();
		});

		HudElementRegistry.attachElementAfter(VanillaHudElements.CHAT, Identifier.of(RapBattle.MOD_ID, "battle"), BattleHud::render);
	}

	private static final boolean[] TOPIC_DOWN = new boolean[3];

	private static void pollTopicKeys(net.minecraft.client.MinecraftClient client) {
		if (!ClientBattle.inLobbyChallenger() || client.currentScreen != null) {
			java.util.Arrays.fill(TOPIC_DOWN, false);
			return;
		}
		var win = client.getWindow();
		int[] keys = {GLFW.GLFW_KEY_1, GLFW.GLFW_KEY_2, GLFW.GLFW_KEY_3};
		for (int i = 0; i < 3; i++) {
			boolean down = net.minecraft.client.util.InputUtil.isKeyPressed(win, keys[i]);
			if (down && !TOPIC_DOWN[i]) ClientBattle.chooseTopic(i + 1);
			TOPIC_DOWN[i] = down;
		}
	}
}
