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
	private static KeyBinding accept, decline, rap, skip;

	static String rapKeyName() { return keyName(rap, "R"); }
	static String declineKeyName() { return keyName(decline, "N"); }

	/** Short name of the attack key ("LMB" / "ЛКМ" by default). */
	static String attackKeyName() {
		KeyBinding k = net.minecraft.client.MinecraftClient.getInstance().options.attackKey;
		return k.isDefault() ? net.minecraft.client.resource.language.I18n.translate("rapbattle.hud.key_lmb")
			: k.getBoundKeyLocalizedText().getString();
	}

	private static String keyName(KeyBinding k, String fallback) {
		if (k == null) return fallback;
		// Letter keys: show the Latin key cap (R), not the layout's letter (К on a Russian layout).
		String tk = k.getBoundKeyTranslationKey();
		if (tk != null && tk.startsWith("key.keyboard.") && tk.length() == "key.keyboard.".length() + 1) {
			return tk.substring(tk.length() - 1).toUpperCase(java.util.Locale.ROOT);
		}
		String s = k.getBoundKeyLocalizedText().getString();
		return s.isEmpty() ? fallback : s.toUpperCase(java.util.Locale.ROOT);
	}

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
		skip = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.rapbattle.skip", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_SPACE, category));

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
			fixKeyConflicts(client);
			pollSkipKey(client);
			// Keeps the beat and the mic going even when the HUD is hidden (F1).
			ClientBattle.pump();
		});

		HudElementRegistry.attachElementAfter(VanillaHudElements.CHAT, Identifier.of(RapBattle.MOD_ID, "battle"), BattleHud::render);
	}

	private static final boolean[] TOPIC_DOWN = new boolean[3];

	private static boolean skipDown;

	/**
	 * Skip everywhere in a battle: our own "skip" key (Space by default), the jump key wherever it is bound
	 * (also a mouse button) and plain Space. Polled raw because movement keys are frozen in a battle.
	 */
	private static void pollSkipKey(net.minecraft.client.MinecraftClient client) {
		if (ClientBattle.view == null && !Intro.active() || client.currentScreen != null) {
			skipDown = false;
			return;
		}
		boolean down = isDown(client, KeyBindingHelper.getBoundKeyOf(skip))
			|| isDown(client, KeyBindingHelper.getBoundKeyOf(client.options.jumpKey))
			|| net.minecraft.client.util.InputUtil.isKeyPressed(client.getWindow(), GLFW.GLFW_KEY_SPACE);
		if (down && !skipDown) {
			if (Intro.active()) Intro.skip();
			else ClientBattle.pressSkip();
		}
		skipDown = down;
	}

	private static boolean isDown(net.minecraft.client.MinecraftClient client, InputUtil.Key key) {
		int code = key.getCode();
		if (code < 0) return false;
		if (key.getCategory() == InputUtil.Type.MOUSE) {
			return GLFW.glfwGetMouseButton(client.getWindow().getHandle(), code) == GLFW.GLFW_PRESS;
		}
		if (key.getCategory() != InputUtil.Type.KEYSYM) return false;
		return net.minecraft.client.util.InputUtil.isKeyPressed(client.getWindow(), code);
	}

	private static boolean keysChecked;

	/**
	 * Iris binds "reload shaders" to R by default, the same key as our battle key, so every challenge
	 * reloaded the whole shader pack (a long freeze). Once per launch, unbind any other key that sits on our
	 * battle key and is known to be heavy (shader reload).
	 */
	private static void fixKeyConflicts(net.minecraft.client.MinecraftClient client) {
		if (keysChecked || client.options == null) return;
		keysChecked = true;
		InputUtil.Key ours = KeyBindingHelper.getBoundKeyOf(rap);
		boolean changed = false;
		for (KeyBinding k : client.options.allKeys) {
			if (k == rap || !"iris.keybind.reload".equals(k.getId())) continue;
			if (KeyBindingHelper.getBoundKeyOf(k).equals(ours)) {
				k.setBoundKey(InputUtil.UNKNOWN_KEY);
				changed = true;
				dev.rapbattle.RapBattle.LOG.info("Unbound Iris shader reload from {}: it clashed with the battle key", ours.getTranslationKey());
			}
		}
		if (changed) {
			KeyBinding.updateKeysByCode();
			client.options.write();
		}
	}

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
