package dev.rapbattle.test;

import dev.rapbattle.Lang;
import dev.rapbattle.battle.GuideBook;
import dev.rapbattle.battle.Stats;
import dev.rapbattle.net.Payloads;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.screen.ingame.BookScreen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.WrittenBookContentComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;

/** Client gametest for 6.14: MC Handbook first-join drop, no duplicate on re-join, /rapbattle guide, and BookScreen layout. */
public class GuideBookGameTest implements FabricClientGameTest {
	private static int countGuideBooks(ServerPlayerEntity p) {
		int count = 0;
		for (int i = 0; i < p.getInventory().size(); i++) {
			if (GuideBook.isGuideBook(p.getInventory().getStack(i))) count++;
		}
		return count;
	}

	@Override
	public void runTest(ClientGameTestContext ctx) {
		GameTestSupport.installKeyAudit();
		ctx.getInput().resizeWindow(1280, 720);
		ctx.waitTicks(5);
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			sp.getClientWorld().waitForChunksRender();
			GameTestSupport.applyLang(ctx);

			// 1. First join in a new world puts 1 MC Handbook into the player's inventory.
			sp.getServer().runOnServer(server -> {
				ServerPlayerEntity p = server.getPlayerManager().getPlayerList().get(0);
				GameTestSupport.assertTrue(countGuideBooks(p) == 1, "New world must give 1 MC Handbook on first join");
				GameTestSupport.assertTrue(Stats.of(server).player(p.getUuid()).guideGiven, "guideGiven must be true after first join");
			});

			// 2. Re-joining the same world must NOT give a second handbook; /rapbattle guide DOES give one.
			sp.getServer().runOnServer(server -> {
				ServerPlayerEntity p = server.getPlayerManager().getPlayerList().get(0);
				p.getInventory().clear();
				GuideBook.giveOnFirstJoin(p, server);
				GameTestSupport.assertTrue(countGuideBooks(p) == 0, "Re-join must not give a second handbook");
			});
			ctx.runOnClient(c -> ClientPlayNetworking.send(new Payloads.ActionC2S("{\"type\":\"guide\"}")));
			ctx.waitTicks(5);
			sp.getServer().runOnServer(server -> {
				ServerPlayerEntity p = server.getPlayerManager().getPlayerList().get(0);
				GameTestSupport.assertTrue(countGuideBooks(p) == 1, "/rapbattle guide must give a handbook");
			});

			// 3. Verify all 14 pages fit BookScreen (<= 14 wrapped lines at width 114) and capture screenshots of page 1 & page 8 (Act 2).
			ctx.runOnClient(c -> {
				String lang = Lang.ofCode(c.getLanguageManager().getLanguage());
				ItemStack book = GuideBook.create(lang);
				WrittenBookContentComponent content = book.get(DataComponentTypes.WRITTEN_BOOK_CONTENT);
				GameTestSupport.assertTrue(content != null && content.pages().size() == GuideBook.PAGE_COUNT,
					"Handbook must have 14 pages");
				for (int i = 0; i < content.pages().size(); i++) {
					int lines = c.textRenderer.wrapLines(content.pages().get(i).get(false), 114).size();
					GameTestSupport.assertTrue(lines <= 14,
						"Guide page " + (i + 1) + " (" + lang + ") wrapped to " + lines + " lines (> 14)");
				}
				c.setScreen(new BookScreen(BookScreen.Contents.create(book)));
			});
			ctx.waitTicks(3);
			GameTestSupport.takeScreenshot(ctx, "guidebook-page-1");

			ctx.runOnClient(c -> {
				if (c.currentScreen instanceof BookScreen bs) bs.setPage(7);
			});
			ctx.waitTicks(3);
			GameTestSupport.takeScreenshot(ctx, "guidebook-page-8-act2");

			ctx.runOnClient(c -> c.setScreen(null));
			GameTestSupport.assertNoRawKeys("GuideBookGameTest");
		}
	}
}
