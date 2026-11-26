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
	private static final List<String> ACTIONBAR_MESSAGES = new ArrayList<>();
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
		ACTIONBAR_MESSAGES.clear();
		if (listenerInstalled) return;
		listenerInstalled = true;
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
			String s = message.getString();
			if (overlay) ACTIONBAR_MESSAGES.add(s);
			Matcher m = RAW_KEY.matcher(s);
			if (m.find()) {
				RAW_MESSAGES.add((overlay ? "[actionbar] " : "[chat] ") + s);
			}
		});
	}

	public static void clearActionbarMessages() {
		ACTIONBAR_MESSAGES.clear();
	}

	public static int countActionbarContaining(String part) {
		int n = 0;
		for (String s : ACTIONBAR_MESSAGES) if (s.contains(part)) n++;
		return n;
	}

	public static void assertNoRawKeys(String testName) {
		if (!RAW_MESSAGES.isEmpty()) {
			String joined = String.join(" | ", RAW_MESSAGES);
			RAW_MESSAGES.clear();
			throw new AssertionError("Raw translation key(s) found in " + testName + ": " + joined);
		}
	}

	/** Switches client language at runtime without restarting the client, and waits for resource reload. */
	public static void setLang(ClientGameTestContext ctx, String lang) {
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
		ctx.waitTicks(3);
	}

	/** Applies RB_TEST_LANG (e.g. "en_us" or "ru_ru") if set, and reloads client resources + syncs settings. */
	public static void applyLang(ClientGameTestContext ctx) {
		setLang(ctx, System.getenv("RB_TEST_LANG"));
	}

	/** Takes a screenshot and moves it into screenshots/<clientLang>/<name>.png. */
	public static void takeScreenshot(ClientGameTestContext ctx, String name) {
		Path file = ctx.takeScreenshot(name);
		if (file == null) return;
		String[] langRef = {System.getenv("RB_TEST_LANG")};
		if (langRef[0] == null || langRef[0].isBlank()) {
			ctx.runOnClient(c -> langRef[0] = c.getLanguageManager().getLanguage());
		}
		String lang = langRef[0];
		if (lang == null || lang.isBlank()) return;
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

	/** Full state & arena reset between scenarios in the single-world suite. */
	public static void resetScenario(ClientGameTestContext ctx,
									 net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext sp,
									 int slotIndex) {
		ctx.runOnClient(c -> {
			c.setScreen(null);
			ClientBattle.view = null;
			ClientBattle.choice = null;
			c.inGameHud.getChatHud().clear(false);
		});
		sp.getServer().runOnServer(server -> {
			BattleManager.clearAllForTest();
			for (var w : server.getWorlds()) {
				java.util.List<net.minecraft.entity.Entity> toRemove = new java.util.ArrayList<>();
				for (var e : w.iterateEntities()) {
					if (!(e instanceof net.minecraft.server.network.ServerPlayerEntity)) toRemove.add(e);
				}
				for (var e : toRemove) e.discard();
			}
			var cfg = dev.rapbattle.RapConfig.get();
			cfg.progression = "soft";
			cfg.dressCode = false;
			cfg.skipChoice = false;
			cfg.allowSwearing = false;

			var ow = server.getWorld(net.minecraft.world.World.OVERWORLD);
			int cx = slotIndex * 128;
			var p = server.getPlayerManager().getPlayerList().get(0);
			p.clearStatusEffects();
			p.getInventory().clear();
			p.setHealth(p.getMaxHealth());
			p.changeGameMode(net.minecraft.world.GameMode.SURVIVAL);
			p.setInvulnerable(true);
			p.getAbilities().flying = false;
			p.sendAbilitiesUpdate();

			var st = dev.rapbattle.battle.Stats.of(server).player(p.getUuid());
			st.respect = 0;
			st.streak = 0;
			st.bestStreak = 0;
			st.wins = 0;
			st.losses = 0;
			st.speciesWins.clear();
			st.dailySpeciesWins.clear();
			st.dailyDay = -1;
			st.dragonWon = false;
			st.hinted500 = false;
			st.hinted1500 = false;

			// Build clean grass platform at y=63 so every scenario starts on flat ground at y=64
			for (int dx = -12; dx <= 12; dx++) {
				for (int dz = -12; dz <= 12; dz++) {
					ow.setBlockState(new net.minecraft.util.math.BlockPos(cx + dx, 63, dz),
						net.minecraft.block.Blocks.GRASS_BLOCK.getDefaultState());
					for (int dy = 64; dy <= 70; dy++) {
						ow.setBlockState(new net.minecraft.util.math.BlockPos(cx + dx, dy, dz),
							net.minecraft.block.Blocks.AIR.getDefaultState());
					}
				}
			}
			p.teleport(ow, cx + 0.5, 64.0, 0.5, java.util.Set.of(), 0f, 0f, false);
		});
		RAW_MESSAGES.clear();
		ctx.waitTicks(2);
	}
}
