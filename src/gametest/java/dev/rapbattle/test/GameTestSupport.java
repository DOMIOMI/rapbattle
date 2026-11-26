package dev.rapbattle.test;

import dev.rapbattle.battle.BattleManager;
import dev.rapbattle.battle.PvpBattle;
import dev.rapbattle.client.ClientBattle;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.MinecraftClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Shared helpers for client gametests: language switching, raw-key audit, WAV loading, and assertions. */
public final class GameTestSupport {
	private GameTestSupport() {}

	private static final Pattern RAW_KEY = Pattern.compile("\\brapbattle\\.[a-z0-9_.]+");
	private static final List<String> RAW_MESSAGES = new ArrayList<>();
	private static boolean listenerInstalled;

	public static void assertTrue(boolean cond, String msg) {
		if (!cond) throw new AssertionError(msg);
	}

	public static byte[] wav() {
		try {
			return Files.readAllBytes(Path.of(System.getenv("RB_TEST_WAV")));
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	public static void installKeyAudit() {
		RAW_MESSAGES.clear();
		if (listenerInstalled) return;
		listenerInstalled = true;
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
			String s = message.getString();
			Matcher m = RAW_KEY.matcher(s);
			if (m.find()) {
				RAW_MESSAGES.add((overlay ? "[actionbar] " : "[chat] ") + s);
			}
		});
	}

	public static void assertNoRawKeys(String testName) {
		if (!RAW_MESSAGES.isEmpty()) {
			String joined = String.join(" | ", RAW_MESSAGES);
			RAW_MESSAGES.clear();
			throw new AssertionError("Raw translation key(s) found in " + testName + ": " + joined);
		}
	}

	/** Applies RB_TEST_LANG (e.g. "en_us" or "ru_ru") if set, and reloads client resources + syncs settings. */
	public static void applyLang(ClientGameTestContext ctx) {
		String lang = System.getenv("RB_TEST_LANG");
		if (lang == null || lang.isBlank()) return;
		boolean[] reloadStarted = new boolean[1];
		ctx.runOnClient(c -> {
			if (!lang.equals(c.options.language)) {
				c.options.language = lang;
				c.getLanguageManager().setLanguage(lang);
				c.reloadResources();
				c.options.write();
				reloadStarted[0] = true;
			}
			c.options.sendClientSettings();
		});
		if (reloadStarted[0]) {
			ctx.waitFor(c -> c.getOverlay() == null, 400);
		}
		ctx.runOnClient(c -> c.options.sendClientSettings());
		ctx.waitTicks(5);
	}

	/** Takes a screenshot and, when RB_TEST_LANG is set, moves it into screenshots/<lang>/<name>.png. */
	public static void takeScreenshot(ClientGameTestContext ctx, String name) {
		Path file = ctx.takeScreenshot(name);
		String lang = System.getenv("RB_TEST_LANG");
		if (lang == null || lang.isBlank() || file == null) return;
		try {
			Path dir = file.getParent().resolve(lang);
			Files.createDirectories(dir);
			Files.move(file, dir.resolve(file.getFileName()), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			throw new RuntimeException("Failed to move screenshot to lang subfolder", e);
		}
	}

	public static void cleanupBattles(net.minecraft.server.MinecraftServer server) {
		for (var p : server.getPlayerManager().getPlayerList()) {
			BattleManager.clearPendingForTest(p.getUuid());
			var b = BattleManager.byPlayer(p.getUuid());
			if (b != null) b.abort("test cleanup");
			var pb = PvpBattle.byPlayer(p.getUuid());
			if (pb != null) pb.forceEndForTest();
		}
		ClientBattle.view = null;
	}
}
