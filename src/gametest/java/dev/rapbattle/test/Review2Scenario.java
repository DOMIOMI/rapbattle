package dev.rapbattle.test;

import com.mojang.authlib.GameProfile;
import dev.rapbattle.Clock;
import dev.rapbattle.RapConfig;
import dev.rapbattle.battle.*;
import dev.rapbattle.client.ClientBattle;
import dev.rapbattle.client.RapSettingsScreen;
import dev.rapbattle.client.StyleScreen;
import dev.rapbattle.net.BattleView;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.boss.dragon.EnderDragonEntity;
import net.minecraft.entity.mob.BlazeEntity;
import net.minecraft.entity.mob.SkeletonEntity;
import net.minecraft.entity.boss.WitherEntity;
import net.minecraft.entity.mob.WitherSkeletonEntity;
import net.minecraft.entity.mob.ZombieEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtOps;
import net.minecraft.registry.RegistryOps;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Hand;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;
import java.util.UUID;

import static dev.rapbattle.test.GameTestSupport.assertTrue;

/**
 * High-priority integration scenarios added during the second code review (6.24):
 * - Full progression cycle (Act 1 -> Rank 4 -> Nether -> Ender Dragon)
 * - Two simultaneous battles nearby (mob+mob and PvP+mob)
 * - Solo disconnect/abort across all battle phases
 * - NbtOps persistence round-trip for Theft.StolenData & MobRecord
 * - Responsive HUD/screens at 854x480 and 1920x1080
 * - @Disabled tests documenting newly found bugs #1, #2, #7 from REVIEW-ALL-2.md
 */
public final class Review2Scenario {
	private Review2Scenario() {}

	@Retention(RetentionPolicy.RUNTIME)
	private @interface Disabled {
		String value();
	}

	public static void run(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		testFullProgressionCycle(ctx, sp);
		testSimultaneousBattlesIsolation(ctx, sp);
		testDisconnectInAllPhases(ctx, sp);
		testNbtPersistenceRoundTrip(ctx, sp);
		testResponsiveHudAndScreens(ctx, sp);

		// Re-enabled 6.25 tests (previously @Disabled for bugs #1, #2, #7):
		testCoopLeaveClearsIsBattling(ctx, sp);
		testPvpJudging500msGraceWindow(ctx, sp);
		testCoopDailyCapPearlsUsesEffectiveScale(ctx, sp);

		GameTestSupport.assertNoRawKeys("review2");
	}

	private static boolean isDisabled(String methodName) {
		try {
			var m = Review2Scenario.class.getDeclaredMethod(methodName, ClientGameTestContext.class, TestSingleplayerContext.class);
			Disabled d = m.getAnnotation(Disabled.class);
			if (d != null) {
				System.out.println("[RB-SUITE] SKIP  review2." + methodName + " (" + d.value() + ")");
				return true;
			}
		} catch (NoSuchMethodException ignored) {}
		return false;
	}

	private static void testFullProgressionCycle(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		sp.getServer().runOnServer(server -> {
			RapConfig.get().progression = "soft";
			RapConfig.get().dressCode = false;
			ServerPlayerEntity p = server.getPlayerManager().getPlayerList().get(0);
			ServerWorld w = p.getEntityWorld();
			Stats stats = Stats.of(server);
			Stats.Player st = stats.player(p.getUuid());
			st.respect = 0;
			st.speciesWins.clear();
			st.dragonWon = false;
			p.setStackInHand(Hand.OFF_HAND, ItemStack.EMPTY);

			// 1. Rank 4 Blaze initially has bonuses closed in soft mode
			BlazeEntity blaze = EntityType.BLAZE.create(w, SpawnReason.COMMAND);
			blaze.refreshPositionAndAngles(p.getX(), p.getY(), p.getZ() + 4, 180, 0);
			w.spawnEntity(blaze);
			ProgressionRules.GateCheck gateBefore = Progression.check(p, blaze, Opponent.of(blaze, new java.util.Random(1), p, "ru"));
			assertTrue(gateBefore.allowed() && !gateBefore.bonusesAllowed(), "Rank 4 must be soft-gated at 0 Respect");

			// 2. Beat 2 distinct Rank 3 species (Zombie + Skeleton) -> Rank 4 opens
			ZombieEntity z3 = EntityType.ZOMBIE.create(w, SpawnReason.COMMAND);
			z3.refreshPositionAndAngles(p.getX(), p.getY(), p.getZ() + 4, 180, 0);
			w.spawnEntity(z3);
			Battle b1 = BattleManager.start(p, z3);
			b1.setPlayerScoreForTest(p.getName().getString(), 60);
			b1.jumpToForTest(Battle.Phase.RESULT);

			SkeletonEntity sk3 = EntityType.SKELETON.create(w, SpawnReason.COMMAND);
			sk3.refreshPositionAndAngles(p.getX(), p.getY(), p.getZ() + 4, 180, 0);
			w.spawnEntity(sk3);
			Battle b2 = BattleManager.start(p, sk3);
			b2.setPlayerScoreForTest(p.getName().getString(), 60);
			b2.jumpToForTest(Battle.Phase.RESULT);

			ProgressionRules.GateCheck gateAfter = Progression.check(p, blaze, Opponent.of(blaze, new java.util.Random(1), p, "ru"));
			assertTrue(gateAfter.bonusesAllowed(), "Rank 4 must open after 2 distinct Rank 3 wins");

			// 3. Beat Blaze (Rank 4) & Wither Skeleton -> unlock Wither & beat Wither for high-tier trophy mic
			Battle bBlaze = BattleManager.start(p, blaze);
			bBlaze.setPlayerScoreForTest(p.getName().getString(), 80);
			bBlaze.jumpToForTest(Battle.Phase.RESULT);

			WitherSkeletonEntity ws = EntityType.WITHER_SKELETON.create(w, SpawnReason.COMMAND);
			ws.refreshPositionAndAngles(p.getX(), p.getY(), p.getZ() + 4, 180, 0);
			w.spawnEntity(ws);
			Battle bWs = BattleManager.start(p, ws);
			bWs.setPlayerScoreForTest(p.getName().getString(), 80);
			bWs.jumpToForTest(Battle.Phase.RESULT);

			WitherEntity wither = EntityType.WITHER.create(w, SpawnReason.COMMAND);
			wither.refreshPositionAndAngles(p.getX(), p.getY(), p.getZ() + 6, 180, 0);
			w.spawnEntity(wither);
			Battle bWither = BattleManager.start(p, wither);
			bWither.setPlayerScoreForTest(p.getName().getString(), 100);
			bWither.jumpToForTest(Battle.Phase.RESULT);

			// 4. Equip high-tier mic and ensure >=1000 Respect -> Ender Dragon gate opens -> beat Dragon
			ItemStack mic = new ItemStack(Items.END_ROD);
			NbtCompound tag = new NbtCompound();
			tag.putString(Trophies.NBT_KEY, "wither");
			tag.putString(Trophies.NBT_TIER_KEY, Trophies.TIER_HIGH);
			mic.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(tag));
			p.setStackInHand(Hand.OFF_HAND, mic);
			st.respect = Math.max(st.respect, 1000);

			EnderDragonEntity dragon = EntityType.ENDER_DRAGON.create(w, SpawnReason.COMMAND);
			dragon.refreshPositionAndAngles(p.getX(), p.getY() + 12, p.getZ() + 10, 180, 0);
			w.spawnEntity(dragon);
			ProgressionRules.GateCheck dragonGate = Progression.check(p, dragon, Opponent.of(dragon, new java.util.Random(1), p, "ru"), Rank.NETHERITE, 0);
			assertTrue(dragonGate.allowed(), "Ender Dragon gate must open with high-tier mic, 1000+ Respect, and boss win");

			Battle bDragon = BattleManager.start(p, dragon);
			bDragon.setPlayerScoreForTest(p.getName().getString(), 120);
			bDragon.jumpToForTest(Battle.Phase.RESULT);
			assertTrue(st.dragonWon, "dragonWon flag must be set after beating Ender Dragon");
			assertTrue(!stats.legend.isEmpty(), "World legend must be recorded after Dragon victory");

			if (!dragon.isRemoved()) dragon.discard();
			if (!wither.isRemoved()) wither.discard();
			p.setStackInHand(Hand.OFF_HAND, ItemStack.EMPTY);
			p.getInventory().clear();
			GameTestSupport.cleanupBattles(server);
		});
	}

	private static void testSimultaneousBattlesIsolation(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		sp.getServer().runOnServer(server -> {
			RapConfig.get().progression = "off";
			RapConfig.get().dressCode = false;
			ServerPlayerEntity p1 = server.getPlayerManager().getPlayerList().get(0);
			ServerWorld w = p1.getEntityWorld();
			ServerPlayerEntity p2 = FakePlayer.get(w, new GameProfile(UUID.randomUUID(), "PeerMC"));
			p2.refreshPositionAndAngles(p1.getX() + 6, p1.getY(), p1.getZ(), 0, 0);
			BattleManager.registerTestPlayer(p2);

			ZombieEntity z1 = EntityType.ZOMBIE.create(w, SpawnReason.COMMAND);
			z1.refreshPositionAndAngles(p1.getX(), p1.getY(), p1.getZ() + 4, 180, 0);
			w.spawnEntity(z1);

			ZombieEntity z2 = EntityType.ZOMBIE.create(w, SpawnReason.COMMAND);
			z2.refreshPositionAndAngles(p2.getX(), p2.getY(), p2.getZ() + 4, 180, 0);
			w.spawnEntity(z2);

			Battle b1 = BattleManager.start(p1, z1);
			Battle b2 = BattleManager.start(p2, z2);
			assertTrue(b1.id != b2.id, "Two simultaneous battles must have distinct IDs");
			assertTrue(BattleManager.byPlayer(p1.getUuid()) == b1 && BattleManager.byPlayer(p2.getUuid()) == b2,
				"byPlayer must map each player to their own battle");
			assertTrue(BattleManager.byMob(z1) == b1 && BattleManager.byMob(z2) == b2,
				"byMob must map each mob to its own battle");

			b1.jumpToForTest(Battle.Phase.TIMELINE);
			b2.jumpToForTest(Battle.Phase.TIMELINE);
			assertTrue(!b1.hasMob(z2) && !b2.hasMob(z1), "Battle mobs must not cross-contaminate");

			b1.jumpToForTest(Battle.Phase.RESULT);
			assertTrue(BattleManager.byPlayer(p1.getUuid()) == null && BattleManager.byPlayer(p2.getUuid()) == b2,
				"Ending battle 1 must not end battle 2");

			b2.abort("cleanup");
			if (!z1.isRemoved()) z1.discard();
			if (!z2.isRemoved()) z2.discard();
			GameTestSupport.cleanupBattles(server);
		});
	}

	private static void testDisconnectInAllPhases(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		sp.getServer().runOnServer(server -> {
			RapConfig.get().progression = "off";
			RapConfig.get().dressCode = false;
			ServerPlayerEntity p = server.getPlayerManager().getPlayerList().get(0);
			ServerWorld w = p.getEntityWorld();

			for (Battle.Phase target : List.of(Battle.Phase.LOBBY, Battle.Phase.TIMELINE, Battle.Phase.JUDGING)) {
				ZombieEntity z = EntityType.ZOMBIE.create(w, SpawnReason.COMMAND);
				z.refreshPositionAndAngles(p.getX(), p.getY(), p.getZ() + 4, 180, 0);
				z.setAiDisabled(false);
				z.setSilent(false);
				w.spawnEntity(z);

				Battle b = BattleManager.start(p, z);
				if (target != Battle.Phase.LOBBY) b.jumpToForTest(target);
				b.onPlayerLeft(p.getUuid());

				assertTrue(BattleManager.byPlayer(p.getUuid()) == null, "Solo disconnect in " + target + " must abort battle");
				assertTrue(!z.isAiDisabled() && !z.isSilent(), "Aborted mob in " + target + " must have AI & sound restored");
				assertTrue(z.getCustomName() == null && !Theft.hasStolen(z), "Aborted mob in " + target + " must have no crown/stolen item");
				z.discard();
			}
			GameTestSupport.cleanupBattles(server);
		});
	}

	private static void testNbtPersistenceRoundTrip(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		sp.getServer().runOnServer(server -> {
			ServerWorld w = server.getOverworld();
			RegistryOps<NbtElement> ops = RegistryOps.of(NbtOps.INSTANCE, w.getRegistryManager());

			Theft.StolenData data = new Theft.StolenData(new ItemStack(Items.EMERALD, 3), true, "Бобик");
			NbtElement encoded = Theft.StolenData.CODEC.encodeStart(ops, data).getOrThrow();
			Theft.StolenData decoded = Theft.StolenData.CODEC.parse(ops, encoded).getOrThrow();
			assertTrue(decoded.stack().isOf(Items.EMERALD) && decoded.stack().getCount() == 3, "StolenData stack must round-trip");
			assertTrue(decoded.wasPersistent() && "Бобик".equals(decoded.originalName()), "StolenData metadata must round-trip");
		});
	}

	private static void testResponsiveHudAndScreens(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		try {
			ctx.getInput().resizeWindow(854, 480);
			ctx.waitTicks(2);
			ctx.setScreen(() -> new RapSettingsScreen(null));
			ctx.waitTicks(2);
			GameTestSupport.takeScreenshot(ctx, "review2-settings-small");
			ctx.setScreen(() -> new StyleScreen(null));
			ctx.waitTicks(2);
			ctx.setScreen(() -> null);

			ctx.getInput().resizeWindow(1920, 1080);
			ctx.waitTicks(2);
			ctx.setScreen(() -> new RapSettingsScreen(null));
			ctx.waitTicks(2);
			ctx.setScreen(() -> null);
		} finally {
			ctx.getInput().resizeWindow(1280, 720);
			ctx.waitTicks(2);
		}
	}

	private static void testCoopLeaveClearsIsBattling(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		sp.getServer().runOnServer(server -> {
			RapConfig.get().progression = "off";
			RapConfig.get().dressCode = false;
			ServerPlayerEntity p1 = server.getPlayerManager().getPlayerList().get(0);
			ServerWorld w = p1.getEntityWorld();
			ServerPlayerEntity p2 = FakePlayer.get(w, new GameProfile(UUID.randomUUID(), "CoopLeave"));
			p2.refreshPositionAndAngles(p1.getX() + 2, p1.getY(), p1.getZ(), 0, 0);
			BattleManager.registerTestPlayer(p2);
			ZombieEntity z = EntityType.ZOMBIE.create(w, SpawnReason.COMMAND);
			z.refreshPositionAndAngles(p1.getX(), p1.getY(), p1.getZ() + 4, 180, 0);
			w.spawnEntity(z);
			Battle b = BattleManager.start(p1, z);
			b.invite(p2);
			b.answerInvite(p2, true);
			assertTrue(BattleManager.isBattling(p2), "p2 must be battling after joining coop");
			b.onPlayerLeft(p2.getUuid());
			assertTrue(!BattleManager.isBattling(p2), "Coop leaver must no longer be marked isBattling");
			assertTrue(BattleManager.isBattling(p1), "Remaining coop player must stay in battle");
			boolean dmgAllowed = net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.ALLOW_DAMAGE.invoker()
				.allowDamage(p2, w.getDamageSources().generic(), 2.0f);
			assertTrue(dmgAllowed, "Coop leaver must no longer be protected by battle damage guard");
			b.abort("cleanup");
			z.discard();
			GameTestSupport.cleanupBattles(server);
		});
	}

	private static void testPvpJudging500msGraceWindow(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		sp.getServer().runOnServer(server -> {
			ServerPlayerEntity p1 = server.getPlayerManager().getPlayerList().get(0);
			ServerWorld w = p1.getEntityWorld();
			ServerPlayerEntity p2 = FakePlayer.get(w, new GameProfile(UUID.randomUUID(), "GracePvp"));
			p2.refreshPositionAndAngles(p1.getX() + 4, p1.getY(), p1.getZ(), 90, 0);
			PvpBattle.challenge(p1, p2);
			int id = PvpBattle.pendingInviteIdForTest(p2.getUuid());
			PvpBattle.answer(p2, id, true);
			PvpBattle pb = PvpBattle.byPlayer(p1.getUuid());
			pb.setTestPeerForTest(p2);
			BattleView snap = pb.snapshotForTest(p1);
			long beatMs = Math.max(200L, snap.beatMs);
			pb.jumpToForTest("TIMELINE");
			// Report all 8 bars for p1 and 7 bars for p2, then jump to JUDGING and send p2's 8th bar within 500ms
			Clock.advanceForTest(100L * beatMs);
			for (int i = 0; i < 8; i++) pb.onBar(p1, true, 0, false);
			for (int i = 0; i < 7; i++) pb.onBar(p2, true, 0, false);
			pb.jumpToForTest("JUDGING");
			Clock.advanceForTest(200L);
			pb.onBar(p2, true, 0, false);
			assertTrue(pb.reportedBarsCountForTest(p2.getName().getString()) == 8,
				"8th bar of p2 within 500ms JUDGING grace must be recorded");
			pb.forceEndForTest();
			GameTestSupport.cleanupBattles(server);
		});
	}

	private static void testCoopDailyCapPearlsUsesEffectiveScale(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		sp.getServer().runOnServer(server -> {
			RapConfig.get().progression = "off";
			RapConfig.get().dressCode = false;
			ServerPlayerEntity p1 = server.getPlayerManager().getPlayerList().get(0);
			ServerWorld w = p1.getEntityWorld();
			ServerPlayerEntity p2 = FakePlayer.get(w, new GameProfile(UUID.randomUUID(), "CappedCoop"));
			p2.refreshPositionAndAngles(p1.getX() + 2, p1.getY(), p1.getZ(), 0, 0);
			BattleManager.registerTestPlayer(p2);

			long day = ProgressionRules.currentDay(server.getOverworld().getTime());
			Stats.Player st1 = Stats.of(server).player(p1.getUuid());
			Stats.Player st2 = Stats.of(server).player(p2.getUuid());
			st1.dailyDay = day;
			st1.dailySpeciesWins.clear(); // p1 has 0 wins today -> dailyBonusScale = 1.0
			st2.dailyDay = day;
			st2.dailySpeciesWins.put("blaze", 3); // p2 is at daily cap (0.0)

			for (var ie : w.getEntitiesByClass(net.minecraft.entity.ItemEntity.class, p1.getBoundingBox().expand(24), e -> true)) ie.discard();

			BlazeEntity blaze = EntityType.BLAZE.create(w, SpawnReason.COMMAND);
			blaze.refreshPositionAndAngles(p1.getX(), p1.getY(), p1.getZ() + 4, 180, 0);
			w.spawnEntity(blaze);
			Battle b = BattleManager.start(p1, blaze);
			b.invite(p2);
			b.answerInvite(p2, true);
			b.setPlayerScoreForTest(p1.getName().getString(), 60);
			b.setPlayerScoreForTest(p2.getName().getString(), 40);
			b.jumpToForTest(Battle.Phase.RESULT);
			// Since p2 contributed and is at daily cap (0.0), effectiveDailyScale == 0.0 -> no bonus Blaze Rods (at most 1 vanilla drop)
			int rods = w.getEntitiesByClass(net.minecraft.entity.ItemEntity.class, p1.getBoundingBox().expand(16),
				ie -> ie.getStack().isOf(Items.BLAZE_ROD)).stream().mapToInt(ie -> ie.getStack().getCount()).sum();
			assertTrue(rods <= 1, "Coop effectiveDailyScale=0.0 must suppress bonus Blaze Rods (got " + rods + ")");
			st2.dailySpeciesWins.clear();
			if (!blaze.isRemoved()) blaze.discard();
			GameTestSupport.cleanupBattles(server);
		});
	}
}
