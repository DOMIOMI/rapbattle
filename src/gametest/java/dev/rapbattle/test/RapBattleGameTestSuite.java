package dev.rapbattle.test;

import dev.rapbattle.RapConfig;
import dev.rapbattle.ai.Llm;
import dev.rapbattle.client.CalibrationScreen;
import dev.rapbattle.client.KeysScreen;
import dev.rapbattle.client.RapSettingsScreen;
import dev.rapbattle.client.StyleScreen;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import java.util.*;

/**
 * Unified single-world client gametest runner (6.20):
 * - Runs all scenario groups in one TestSingleplayerContext on ru_ru, resetting state & shifting coordinates between groups.
 * - Supports RB_TEST_ONLY=group1,group2 to run a subset.
 * - Runs en_us text/layout pass at the end without restarting the client.
 * - Catches failures per group so one failure does not abort remaining scenarios.
 */
public class RapBattleGameTestSuite implements FabricClientGameTest {
	@FunctionalInterface
	private interface Scenario {
		void run(ClientGameTestContext ctx, TestSingleplayerContext sp);
	}

	private record Entry(String name, Scenario fn) {}

	@Override
	public void runTest(ClientGameTestContext ctx) {
		GameTestSupport.installKeyAudit();
		Llm.setOfflineStubForTest(true);
		ctx.getInput().resizeWindow(1280, 720);
		ctx.waitTicks(3);

		String onlyEnv = System.getenv("RB_TEST_ONLY");
		Set<String> filter = new LinkedHashSet<>();
		if (onlyEnv != null && !onlyEnv.isBlank()) {
			for (String s : onlyEnv.split(",")) {
				String t = s.trim().toLowerCase(Locale.ROOT);
				if (!t.isEmpty()) filter.add(t);
			}
		}

		long totalStart = System.currentTimeMillis();
		Map<String, Long> timingsMs = new LinkedHashMap<>();
		List<String> failures = new ArrayList<>();

		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			sp.getClientWorld().waitForChunksRender();
			GameTestSupport.setLang(ctx, "ru_ru");
			sp.getServer().runCommand("gamerule doMobSpawning false");
			sp.getServer().runCommand("gamerule doDaylightCycle false");
			sp.getServer().runCommand("gamerule doWeatherCycle false");

			List<Entry> scenarios = List.of(
				new Entry("guidebook", (c, s) -> GuideBookScenario.run(c, s, true)),
				new Entry("e2e", E2eScenario::run),
				new Entry("crowd", CrowdScenario::run),
				new Entry("acts", ActsScenario::run),
				new Entry("gangs", GangsScenario::run),
				new Entry("reputation", ReputationScenario::run),
				new Entry("progression", ProgressionScenario::run),
				new Entry("pvp", PvpScenario::run),
				new Entry("coverage", CoverageScenario::run),
				new Entry("review2", Review2Scenario::run),
				new Entry("lang_en", RapBattleGameTestSuite::runEnglishTextPass)
			);

			int slot = 0;
			boolean first = true;
			for (Entry e : scenarios) {
				if (!filter.isEmpty() && !filter.contains(e.name())) continue;
				if (!first) {
					GameTestSupport.resetScenario(ctx, sp, ++slot);
				}
				first = false;
				long t0 = System.currentTimeMillis();
				try {
					System.out.println("[RB-SUITE] START " + e.name());
					e.fn().run(ctx, sp);
					long dt = System.currentTimeMillis() - t0;
					timingsMs.put(e.name(), dt);
					System.out.println("[RB-SUITE] PASS  " + e.name() + " (" + dt + " ms)");
				} catch (Throwable t) {
					long dt = System.currentTimeMillis() - t0;
					timingsMs.put(e.name(), dt);
					String msg = e.name() + " FAILED (" + dt + " ms): " + t;
					failures.add(msg);
					System.err.println("[RB-SUITE] FAIL  " + msg);
					t.printStackTrace(System.err);
				}
			}
		}

		long totalMs = System.currentTimeMillis() - totalStart;
		System.out.println("[RB-SUITE] SUMMARY total=" + totalMs + " ms, groups=" + timingsMs + ", failures=" + failures.size());
		if (!failures.isEmpty()) {
			throw new AssertionError("RapBattleGameTestSuite failed (" + failures.size() + "): " + String.join(" ; ", failures));
		}
	}

	private static void runEnglishTextPass(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		GameTestSupport.setLang(ctx, "en_us");

		// 1. GuideBook all 14 pages fit & screenshots
		GuideBookScenario.verifyBookPages(ctx, true);

		// 2. StyleScreen (en_us)
		ctx.setScreen(() -> new StyleScreen(null));
		ctx.waitTicks(3);
		GameTestSupport.takeScreenshot(ctx, "style-screen");
		ctx.setScreen(() -> null);

		// 3. KeysScreen with fake masked keys only
		String origOr = RapConfig.get().openRouterApiKey;
		String origStt = RapConfig.get().sttApiKey;
		ctx.setScreen(() -> new KeysScreen(null));
		ctx.waitTicks(2);
		RapConfig.get().openRouterApiKey = "test-key-not-real-or";
		RapConfig.get().sttApiKey = "test-key-not-real-stt";
		try {
			GameTestSupport.assertTrue("…l-or".equals(RapConfig.mask(RapConfig.get().openRouterApiKey)), "Mask must hide prefix");
			ctx.waitTicks(2);
			GameTestSupport.takeScreenshot(ctx, "keys-screen-masked");
			ctx.setScreen(() -> null);
		} finally {
			RapConfig.get().openRouterApiKey = origOr;
			RapConfig.get().sttApiKey = origStt;
		}

		// 4. CalibrationScreen (en_us)
		ctx.setScreen(() -> new CalibrationScreen(null));
		ctx.waitTicks(3);
		GameTestSupport.takeScreenshot(ctx, "calibration-screen");
		ctx.setScreen(() -> null);

		// 5. RapSettingsScreen (en_us)
		ctx.setScreen(() -> new RapSettingsScreen(null));
		ctx.waitTicks(3);
		GameTestSupport.takeScreenshot(ctx, "settings-screen");
		ctx.setScreen(() -> null);

		GameTestSupport.assertNoRawKeys("lang_en");
	}
}
