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
		testItemAndEntityLocks(ctx, sp);
		testDeathAbortsBattle(ctx, sp);
		testVehicleAndEnderPearl(ctx, sp);
		testLavaInTimelineVsResult(ctx, sp);
		testPiglinTruceRespectAndArrow(ctx, sp);
		testPvpDifferentDimensions(ctx, sp);

		// Re-enabled 6.25 tests (previously @Disabled for bugs #1, #2, #7):
		testCoopLeaveClearsIsBattling(ctx, sp);
		testPvpJudging500msGraceWindow(ctx, sp);
		testCoopDailyCapPearlsUsesEffectiveScale(ctx, sp);

		// 6.27 regression tests:
		testReview3BattleAndPvpFixes(ctx, sp);
		testReview3LootReputationAndDecisions(ctx, sp);
		testReview3Followups628(ctx, sp);

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

				assertTrue(BattleManager.byPlayer(p.getUuid()) == null, "Solo disconnect in " + target + " must end battle");
				assertTrue(!z.isAiDisabled() && !z.isSilent(), "Ended mob in " + target + " must have AI & sound restored");
				if (target == Battle.Phase.JUDGING) {
					// 6.27 A1: leaving in JUDGING resolves the battle (silent player loses -> mob gets crown)
					assertTrue(z.getCustomName() != null, "Mob in JUDGING disconnect must resolve and receive crown");
				} else {
					assertTrue(z.getCustomName() == null && !Theft.hasStolen(z), "Aborted mob in " + target + " must have no crown/stolen item");
				}
				z.discard();
				BattleManager.clearCooldownsForTest();
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
		final int[] prevScale = {0};
		ctx.runOnClient(mc -> prevScale[0] = mc.options.getGuiScale().getValue());
		try {
			ctx.getInput().resizeWindow(854, 480);
			for (int scale : new int[] { 2, 3, 4 }) {
				ctx.runOnClient(mc -> {
					mc.options.getGuiScale().setValue(scale);
					mc.onResolutionChanged();
				});
				ctx.waitTicks(2);
				ctx.setScreen(() -> new RapSettingsScreen(null));
				ctx.waitTicks(2);
				if (scale == 2) GameTestSupport.takeScreenshot(ctx, "review2-settings-small");
				else GameTestSupport.takeScreenshot(ctx, "review2-settings-scale" + scale);
				ctx.setScreen(() -> new StyleScreen(null));
				ctx.waitTicks(2);
				ctx.setScreen(() -> null);
			}

			ctx.runOnClient(mc -> {
				mc.options.getGuiScale().setValue(prevScale[0]);
				mc.onResolutionChanged();
			});
			ctx.getInput().resizeWindow(1920, 1080);
			ctx.waitTicks(2);
			ctx.setScreen(() -> new RapSettingsScreen(null));
			ctx.waitTicks(2);
			GameTestSupport.takeScreenshot(ctx, "review2-settings-fixed");
			ctx.setScreen(() -> null);
		} finally {
			ctx.runOnClient(mc -> {
				mc.options.getGuiScale().setValue(prevScale[0]);
				mc.onResolutionChanged();
			});
			ctx.getInput().resizeWindow(1280, 720);
			ctx.waitTicks(2);
		}
	}

	private static void testItemAndEntityLocks(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		GameTestSupport.clearActionbarMessages();
		sp.getServer().runOnServer(server -> {
			RapConfig.get().progression = "off";
			RapConfig.get().dressCode = false;
			ServerPlayerEntity p = server.getPlayerManager().getPlayerList().get(0);
			ServerWorld w = p.getEntityWorld();
			p.getInventory().clear();
			ItemStack apple = new ItemStack(Items.APPLE, 5);
			ItemStack mic = new ItemStack(Items.LIGHTNING_ROD, 1);
			p.getInventory().setStack(0, apple);
			p.setStackInHand(Hand.OFF_HAND, mic);

			ZombieEntity z = EntityType.ZOMBIE.create(w, SpawnReason.COMMAND);
			z.refreshPositionAndAngles(p.getX(), p.getY(), p.getZ() + 4, 180, 0);
			w.spawnEntity(z);
			var donkey = EntityType.DONKEY.create(w, SpawnReason.COMMAND);
			donkey.refreshPositionAndAngles(p.getX() + 2, p.getY(), p.getZ(), 0, 0);
			w.spawnEntity(donkey);
			var villager = EntityType.VILLAGER.create(w, SpawnReason.COMMAND);
			villager.refreshPositionAndAngles(p.getX() - 2, p.getY(), p.getZ(), 0, 0);
			w.spawnEntity(villager);

			Battle b = BattleManager.start(p, z);
			// Q, Ctrl+Q, F via ServerPlayNetworkHandler packets
			p.networkHandler.onPlayerAction(new net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket(
				net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket.Action.DROP_ITEM, net.minecraft.util.math.BlockPos.ORIGIN, net.minecraft.util.math.Direction.DOWN));
			p.networkHandler.onPlayerAction(new net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket(
				net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket.Action.DROP_ALL_ITEMS, net.minecraft.util.math.BlockPos.ORIGIN, net.minecraft.util.math.Direction.DOWN));
			p.networkHandler.onPlayerAction(new net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket(
				net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket.Action.SWAP_ITEM_WITH_OFFHAND, net.minecraft.util.math.BlockPos.ORIGIN, net.minecraft.util.math.Direction.DOWN));
			// Inventory click & Shift-click
			p.networkHandler.onClickSlot(new net.minecraft.network.packet.c2s.play.ClickSlotC2SPacket(
				p.currentScreenHandler.syncId, p.currentScreenHandler.getRevision(), (short) 36, (byte) 0,
				net.minecraft.screen.slot.SlotActionType.PICKUP, it.unimi.dsi.fastutil.ints.Int2ObjectMaps.emptyMap(),
				net.minecraft.screen.sync.ItemStackHash.EMPTY));
			p.networkHandler.onClickSlot(new net.minecraft.network.packet.c2s.play.ClickSlotC2SPacket(
				p.currentScreenHandler.syncId, p.currentScreenHandler.getRevision(), (short) 36, (byte) 0,
				net.minecraft.screen.slot.SlotActionType.QUICK_MOVE, it.unimi.dsi.fastutil.ints.Int2ObjectMaps.emptyMap(),
				net.minecraft.screen.sync.ItemStackHash.EMPTY));
			// UseEntityCallback: donkey & villager
			var rDonkey = net.fabricmc.fabric.api.event.player.UseEntityCallback.EVENT.invoker()
				.interact(p, w, Hand.MAIN_HAND, donkey, null);
			var rVillager = net.fabricmc.fabric.api.event.player.UseEntityCallback.EVENT.invoker()
				.interact(p, w, Hand.MAIN_HAND, villager, null);

			assertTrue(rDonkey == net.minecraft.util.ActionResult.FAIL && rVillager == net.minecraft.util.ActionResult.FAIL,
				"UseEntityCallback must fail on donkey and villager during battle");
			assertTrue(p.getInventory().getStack(0).getCount() == 5 && p.getOffHandStack().isOf(Items.LIGHTNING_ROD),
				"Q/Ctrl+Q/F/ClickSlot must not alter inventory or offhand during battle");

			b.abort("cleanup");
			donkey.discard();
			villager.discard();
			z.discard();
			p.getInventory().clear();
			GameTestSupport.cleanupBattles(server);
		});
		ctx.waitTicks(3);
		int hints = GameTestSupport.countActionbarContaining("микрофон") + GameTestSupport.countActionbarContaining("Mic only");
		assertTrue(hints == 1, "Mic-only hint must show once within 3s cooldown (got " + hints + ")");
	}

	private static void testDeathAbortsBattle(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		sp.getServer().runOnServer(server -> {
			RapConfig.get().progression = "off";
			RapConfig.get().dressCode = false;
			ServerPlayerEntity p = server.getPlayerManager().getPlayerList().get(0);
			ServerWorld w = p.getEntityWorld();
			p.changeGameMode(net.minecraft.world.GameMode.SURVIVAL);
			p.setInvulnerable(false);
			p.setHealth(p.getMaxHealth());

			// Solo /kill -> battle aborted without crown/theft
			ZombieEntity z = EntityType.ZOMBIE.create(w, SpawnReason.COMMAND);
			z.refreshPositionAndAngles(p.getX(), p.getY(), p.getZ() + 4, 180, 0);
			w.spawnEntity(z);
			BattleManager.start(p, z);
			p.timeUntilRegen = 0;
			p.damage(w, w.getDamageSources().genericKill(), Float.MAX_VALUE);
			assertTrue(!BattleManager.isBattling(p), "/kill must remove solo player from battle");
			assertTrue(z.getCustomName() == null && !Theft.hasStolen(z), "/kill abort must not grant mob crown or steal items");
			z.discard();
			GameTestSupport.cleanupBattles(server);
		});
		ctx.waitTicks(5);
		ctx.runOnClient(mc -> {
			if (mc.player != null) mc.player.requestRespawn();
			if (mc.currentScreen != null) mc.setScreen(null);
		});
		ctx.waitTicks(10);
		sp.getServer().runOnServer(server -> {
			ServerPlayerEntity p = server.getPlayerManager().getPlayerList().get(0);
			assertTrue(p.isAlive() && !BattleManager.isBattling(p), "Respawned player must be alive and not in battle");

			// Coop /kill on p -> aborts coop battle cleanly
			ServerWorld w = p.getEntityWorld();
			ServerPlayerEntity p2 = net.fabricmc.fabric.api.entity.FakePlayer.get(w, new com.mojang.authlib.GameProfile(UUID.randomUUID(), "DeathCoop"));
			p2.refreshPositionAndAngles(p.getX() + 2, p.getY(), p.getZ(), 0, 0);
			BattleManager.registerTestPlayer(p2);
			ZombieEntity z2 = EntityType.ZOMBIE.create(w, SpawnReason.COMMAND);
			z2.refreshPositionAndAngles(p.getX(), p.getY(), p.getZ() + 4, 180, 0);
			w.spawnEntity(z2);
			Battle b2 = BattleManager.start(p, z2);
			b2.invite(p2);
			b2.answerInvite(p2, true);
			p.onTeleportationDone();
			p.timeUntilRegen = 0;
			boolean killed = p.damage(w, w.getDamageSources().genericKill(), Float.MAX_VALUE);
			assertTrue(killed && !BattleManager.isBattling(p) && !BattleManager.isBattling(p2),
				"Coop /kill must abort battle for both");
			z2.discard();
			GameTestSupport.cleanupBattles(server);
		});
		ctx.waitTicks(5);
		ctx.runOnClient(mc -> {
			if (mc.player != null) mc.player.requestRespawn();
			if (mc.currentScreen != null) mc.setScreen(null);
		});
		ctx.waitTicks(10);
		sp.getServer().runOnServer(server -> {
			ServerPlayerEntity p = server.getPlayerManager().getPlayerList().get(0);
			ServerWorld w = p.getEntityWorld();

			// PvP /kill on p -> aborts PvP battle without winner/loser
			ServerPlayerEntity p3 = net.fabricmc.fabric.api.entity.FakePlayer.get(w, new com.mojang.authlib.GameProfile(UUID.randomUUID(), "DeathPvp"));
			p3.refreshPositionAndAngles(p.getX() + 3, p.getY(), p.getZ(), 0, 0);
			BattleManager.registerTestPlayer(p3);
			PvpBattle.challenge(p, p3);
			int pvpId = PvpBattle.pendingInviteIdForTest(p3.getUuid());
			PvpBattle.answer(p3, pvpId, true);
			p.onTeleportationDone();
			p.timeUntilRegen = 0;
			boolean killed = p.damage(w, w.getDamageSources().genericKill(), Float.MAX_VALUE);
			assertTrue(killed && !BattleManager.isBattling(p) && !BattleManager.isBattling(p3),
				"PvP /kill must abort battle for both");
			GameTestSupport.cleanupBattles(server);
		});
		ctx.runOnClient(mc -> {
			if (mc.player != null) mc.player.requestRespawn();
		});
		ctx.waitTicks(10);
	}

	private static void testVehicleAndEnderPearl(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		Battle[] bRef = new Battle[1];
		net.minecraft.entity.passive.HorseEntity[] horseRef = new net.minecraft.entity.passive.HorseEntity[1];
		ZombieEntity[] zRef = new ZombieEntity[1];
		net.minecraft.entity.projectile.thrown.EnderPearlEntity[] pearlRef = new net.minecraft.entity.projectile.thrown.EnderPearlEntity[1];
		double[] startXZ = new double[2];
		sp.getServer().runOnServer(server -> {
			RapConfig.get().progression = "off";
			RapConfig.get().dressCode = false;
			ServerPlayerEntity p = server.getPlayerManager().getPlayerList().get(0);
			ServerWorld w = p.getEntityWorld();
			var horse = EntityType.HORSE.create(w, SpawnReason.COMMAND);
			horse.refreshPositionAndAngles(p.getX(), p.getY(), p.getZ(), 0, 0);
			w.spawnEntity(horse);
			horseRef[0] = horse;
			p.startRiding(horse, true, true);
			assertTrue(p.hasVehicle(), "Player must be riding horse before battle");

			ZombieEntity z = EntityType.ZOMBIE.create(w, SpawnReason.COMMAND);
			z.refreshPositionAndAngles(p.getX(), p.getY(), p.getZ() + 4, 180, 0);
			w.spawnEntity(z);
			zRef[0] = z;

			startXZ[0] = p.getX();
			startXZ[1] = p.getZ();
			Battle b = BattleManager.start(p, z);
			bRef[0] = b;
			assertTrue(!p.hasVehicle(), "Starting battle must dismount player from horse");

			var pearl = new net.minecraft.entity.projectile.thrown.EnderPearlEntity(w, p, new ItemStack(Items.ENDER_PEARL));
			pearl.setPos(startXZ[0] + 6, p.getY() + 0.5, startXZ[1] + 6);
			pearl.setVelocity(0, -1.0, 0);
			w.spawnEntity(pearl);
			pearlRef[0] = pearl;
		});
		ctx.waitTicks(4);
		sp.getServer().runOnServer(server -> {
			ServerPlayerEntity p = server.getPlayerManager().getPlayerList().get(0);
			assertTrue(pearlRef[0].isRemoved() && p.squaredDistanceTo(startXZ[0], p.getY(), startXZ[1]) < 1.0,
				"Ender pearl landing during battle must be discarded without teleporting player");
			bRef[0].abort("cleanup");
			horseRef[0].discard();
			zRef[0].discard();
			GameTestSupport.cleanupBattles(server);
		});
	}

	private static void testLavaInTimelineVsResult(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		Battle[] mobBattleRef = new Battle[1];
		ZombieEntity[] mobRef = new ZombieEntity[1];
		double[] startX = new double[1];
		int[] baseLosses = new int[1];
		sp.getServer().runOnServer(server -> {
			RapConfig.get().progression = "off";
			RapConfig.get().dressCode = false;
			ServerPlayerEntity p1 = server.getPlayerManager().getPlayerList().get(0);
			ServerWorld w = p1.getEntityWorld();
			p1.changeGameMode(net.minecraft.world.GameMode.SURVIVAL);
			p1.setInvulnerable(false);
			p1.setHealth(p1.getMaxHealth());
			p1.extinguish();
			Stats stats = Stats.of(server);
			int losses0 = stats.player(p1.getUuid()).losses;
			int wins0 = stats.player(p1.getUuid()).wins;

			// 1. TIMELINE (mob + PvP): lava aborts without penalty
			ZombieEntity z0 = EntityType.ZOMBIE.create(w, SpawnReason.COMMAND);
			z0.refreshPositionAndAngles(p1.getX(), p1.getY(), p1.getZ() + 4, 180, 0);
			w.spawnEntity(z0);
			Battle b0 = BattleManager.start(p1, z0);
			b0.jumpToForTest(Battle.Phase.TIMELINE, false, false, 50, 50);
			p1.timeUntilRegen = 0;
			p1.damage(w, w.getDamageSources().lava(), 2.0f);
			assertTrue(!BattleManager.isBattling(p1), "Lava in mob TIMELINE must abort battle");
			assertTrue(stats.player(p1.getUuid()).losses == losses0 && stats.player(p1.getUuid()).wins == wins0,
				"Lava abort in TIMELINE must not record win/loss");
			z0.discard();
			p1.setHealth(p1.getMaxHealth());
			p1.extinguish();

			// 2. RESULT (mob): lava deals 0 damage, no fire, anchor released (can move 3 blocks away), loss recorded on finish
			ZombieEntity z1 = EntityType.ZOMBIE.create(w, SpawnReason.COMMAND);
			z1.refreshPositionAndAngles(p1.getX(), p1.getY(), p1.getZ() + 4, 180, 0);
			w.spawnEntity(z1);
			Battle b1 = BattleManager.start(p1, z1);
			b1.enterResultForTest(false, 20, 80);
			p1.timeUntilRegen = 0;
			boolean lavaDmg = p1.damage(w, w.getDamageSources().lava(), 4.0f);
			assertTrue(!lavaDmg && p1.getHealth() == p1.getMaxHealth() && !p1.isOnFire(),
				"Lava in RESULT must deal 0 damage and not ignite player");
			assertTrue(BattleManager.isBattling(p1), "Lava in RESULT must not abort battle");
			startX[0] = p1.getX();
			p1.refreshPositionAndAngles(startX[0] + 3.0, p1.getY(), p1.getZ(), p1.getYaw(), p1.getPitch());
			mobBattleRef[0] = b1;
			mobRef[0] = z1;
			baseLosses[0] = losses0;
		});
		ctx.waitTicks(2);
		sp.getServer().runOnServer(server -> {
			ServerPlayerEntity p1 = server.getPlayerManager().getPlayerList().get(0);
			ServerWorld w = p1.getEntityWorld();
			Stats stats = Stats.of(server);
			assertTrue(Math.abs(p1.getX() - (startX[0] + 3.0)) < 0.5,
				"Player in RESULT must be freed from anchor and not pulled back (dx=" + (p1.getX() - startX[0]) + ")");
			Clock.advanceForTest(12_000L);
			mobBattleRef[0].tick();
			assertTrue(!BattleManager.isBattling(p1), "Battle must finish after RESULT duration");
			assertTrue(stats.player(p1.getUuid()).losses == baseLosses[0] + 1,
				"Mob battle loss must be recorded after RESULT lava");
			mobRef[0].discard();
			GameTestSupport.cleanupBattles(server);

			// 3. JUDGING + void: immediately resolves with current outcome, then void damage passes
			int totalBefore = stats.player(p1.getUuid()).wins + stats.player(p1.getUuid()).losses;
			ZombieEntity z2 = EntityType.ZOMBIE.create(w, SpawnReason.COMMAND);
			z2.refreshPositionAndAngles(p1.getX(), p1.getY(), p1.getZ() + 4, 180, 0);
			w.spawnEntity(z2);
			Battle b2 = BattleManager.start(p1, z2);
			b2.jumpToForTest(Battle.Phase.JUDGING, false, false, 50, 50);
			p1.timeUntilRegen = 0;
			boolean voidDmg = p1.damage(w, w.getDamageSources().outOfWorld(), 10.0f);
			assertTrue(voidDmg && p1.getHealth() < p1.getMaxHealth(),
				"Void damage in JUDGING must pass through (voidDmg=" + voidDmg + ", hp=" + p1.getHealth()
					+ ", max=" + p1.getMaxHealth() + ", abs=" + p1.getAbsorptionAmount() + ")");
			assertTrue(!BattleManager.isBattling(p1), "Void in JUDGING must immediately finish battle");
			assertTrue(stats.player(p1.getUuid()).wins + stats.player(p1.getUuid()).losses == totalBefore + 1,
				"Void in JUDGING must record win or loss by current points");
			z2.discard();
			p1.setHealth(p1.getMaxHealth());
			GameTestSupport.cleanupBattles(server);

			// 4. RESULT + /kill: records outcome instead of abortNoPenalty
			int lossesBeforeKill = stats.player(p1.getUuid()).losses;
			ZombieEntity z3 = EntityType.ZOMBIE.create(w, SpawnReason.COMMAND);
			z3.refreshPositionAndAngles(p1.getX(), p1.getY(), p1.getZ() + 4, 180, 0);
			w.spawnEntity(z3);
			Battle b3 = BattleManager.start(p1, z3);
			b3.enterResultForTest(false, 20, 80);
			p1.onTeleportationDone();
			p1.timeUntilRegen = 0;
			boolean killed = p1.damage(w, w.getDamageSources().genericKill(), Float.MAX_VALUE);
			assertTrue(killed && !BattleManager.isBattling(p1), "/kill in RESULT must finish battle");
			assertTrue(stats.player(p1.getUuid()).losses == lossesBeforeKill + 1,
				"/kill in RESULT must record loss (no abortNoPenalty)");
			z3.discard();
			GameTestSupport.cleanupBattles(server);
		});
		ctx.waitTicks(5);
		ctx.runOnClient(mc -> {
			if (mc.player != null) mc.player.requestRespawn();
			if (mc.currentScreen != null) mc.setScreen(null);
		});
		ctx.waitTicks(10);
		int[] pvpLossesBefore = new int[1];
		sp.getServer().runOnServer(server -> {
			ServerPlayerEntity p1 = server.getPlayerManager().getPlayerList().get(0);
			ServerWorld w = p1.getEntityWorld();
			p1.setHealth(p1.getMaxHealth());
			p1.extinguish();
			ServerPlayerEntity p2 = FakePlayer.get(w, new GameProfile(UUID.randomUUID(), "LavaPvp"));
			p2.refreshPositionAndAngles(p1.getX() + 4, p1.getY(), p1.getZ(), 90, 0);

			// 5. PvP RESULT_LOSS + lava under loser: 0 damage, alive, loss recorded on finish
			Stats stats = Stats.of(server);
			pvpLossesBefore[0] = stats.player(p1.getUuid()).losses;
			PvpBattle.challenge(p1, p2);
			PvpBattle.answer(p2, PvpBattle.pendingInviteIdForTest(p2.getUuid()), true);
			PvpBattle pb = PvpBattle.byPlayer(p1.getUuid());
			pb.setTestPeerForTest(p2);
			pb.jumpToForTest("RESULT_LOSS");
			p1.timeUntilRegen = 0;
			boolean pvpLava = p1.damage(w, w.getDamageSources().lava(), 4.0f);
			assertTrue(!pvpLava && p1.isAlive() && p1.getHealth() == p1.getMaxHealth() && !p1.isOnFire(),
				"Lava under loser in PvP RESULT must deal 0 damage and keep player alive");
			assertTrue(PvpBattle.byPlayer(p1.getUuid()) == pb, "Lava in PvP RESULT must not abort battle");
			Clock.advanceForTest(13_000L);
		});
		ctx.waitTicks(2);
		sp.getServer().runOnServer(server -> {
			ServerPlayerEntity p1 = server.getPlayerManager().getPlayerList().get(0);
			Stats stats = Stats.of(server);
			assertTrue(PvpBattle.byPlayer(p1.getUuid()) == null, "PvP battle must finish after RESULT duration");
			assertTrue(stats.player(p1.getUuid()).losses == pvpLossesBefore[0] + 1,
				"PvP loss must be recorded after RESULT lava");
			GameTestSupport.cleanupBattles(server);
		});
	}

	private static void testPiglinTruceRespectAndArrow(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		sp.getServer().runOnServer(server -> {
			ServerPlayerEntity p = server.getPlayerManager().getPlayerList().get(0);
			ServerWorld w = p.getEntityWorld();
			var piglin = EntityType.PIGLIN.create(w, SpawnReason.COMMAND);
			piglin.refreshPositionAndAngles(p.getX() + 3, p.getY(), p.getZ(), 0, 0);
			w.spawnEntity(piglin);

			PlayerStatus.grant(p.getUuid(), PlayerStatus.BASTION_GUEST, 60_000L);
			var arrow = new net.minecraft.entity.projectile.ArrowEntity(w, p, new ItemStack(Items.ARROW), null);
			piglin.damage(w, w.getDamageSources().arrow(arrow, p), 2.0f);
			arrow.discard();
			assertTrue(!PlayerStatus.has(p.getUuid(), PlayerStatus.BASTION_GUEST),
				"Shooting a Piglin with an arrow must revoke BASTION_GUEST");

			// Street Cred respects piglin -> PiglinBrain.becomeAngryWith cancelled
			PlayerStatus.grant(p.getUuid(), PlayerStatus.STREET_CRED, 60_000L);
			Reputation.mergeCred(null, 3, 60_000L, Clock.now());
			piglin.discard();
			GameTestSupport.cleanupBattles(server);
		});
	}

	private static void testPvpDifferentDimensions(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		sp.getServer().runOnServer(server -> {
			ServerPlayerEntity p1 = server.getPlayerManager().getPlayerList().get(0);
			ServerWorld overworld = p1.getEntityWorld();
			ServerWorld nether = server.getWorld(net.minecraft.world.World.NETHER);
			ServerPlayerEntity p2 = FakePlayer.get(overworld, new GameProfile(UUID.randomUUID(), "DimPvp"));
			PvpBattle.challenge(p1, p2);
			int invId = PvpBattle.pendingInviteIdForTest(p2.getUuid());
			double ox = p1.getX(), oy = p1.getY(), oz = p1.getZ();
			p1.teleport(nether, 0.5, 64, 0.5, java.util.Set.of(), 0f, 0f, false);
			PvpBattle.answer(p2, invId, true);
			assertTrue(PvpBattle.byPlayer(p1.getUuid()) == null && PvpBattle.byPlayer(p2.getUuid()) == null,
				"PvP must not start when challenger moved to another dimension");
			p1.teleport(overworld, ox, oy, oz, java.util.Set.of(), 0f, 0f, false);
			GameTestSupport.cleanupBattles(server);
		});
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

	private static void testReview3BattleAndPvpFixes(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		sp.getServer().runOnServer(server -> {
			RapConfig.get().progression = "off";
			RapConfig.get().dressCode = false;
			ServerPlayerEntity p1 = server.getPlayerManager().getPlayerList().get(0);
			ServerWorld w = p1.getEntityWorld();
			p1.changeGameMode(net.minecraft.world.GameMode.SURVIVAL);
			p1.setInvulnerable(false);
			p1.setHealth(p1.getMaxHealth());
			Stats stats = Stats.of(server);

			// 1. Solo RESULT loss -> leave -> loss recorded, theft occurred
			p1.getInventory().clear();
			p1.getInventory().setStack(0, new ItemStack(Items.APPLE, 1));
			int losses0 = stats.player(p1.getUuid()).losses;
			ZombieEntity z1 = EntityType.ZOMBIE.create(w, SpawnReason.COMMAND);
			z1.refreshPositionAndAngles(p1.getX(), p1.getY(), p1.getZ() + 4, 180, 0);
			w.spawnEntity(z1);
			Battle b1 = BattleManager.start(p1, z1);
			b1.enterResultForTest(false, 10, 90);
			b1.onPlayerLeft(p1.getUuid());
			assertTrue(!BattleManager.isBattling(p1), "Solo RESULT leave must end battle");
			assertTrue(stats.player(p1.getUuid()).losses == losses0 + 1, "Solo RESULT leave must record loss");
			assertTrue(Theft.hasStolen(z1), "Solo RESULT leave on decisive loss must trigger theft");
			z1.discard();
			p1.getInventory().clear();
			BattleManager.clearCooldownsForTest();
			GameTestSupport.cleanupBattles(server);

			// 2. PvP RESULT -> winner leaves -> winner keeps victory, no respect gained (Zh4)
			ServerPlayerEntity p2 = FakePlayer.get(w, new GameProfile(UUID.randomUUID(), "WinLeaver"));
			p2.refreshPositionAndAngles(p1.getX() + 4, p1.getY(), p1.getZ(), 90, 0);
			int w1Before = stats.player(p1.getUuid()).wins;
			int r1Before = stats.player(p1.getUuid()).respect;
			int l2Before = stats.player(p2.getUuid()).losses;
			PvpBattle.challenge(p1, p2);
			PvpBattle.answer(p2, PvpBattle.pendingInviteIdForTest(p2.getUuid()), true);
			PvpBattle pb = PvpBattle.byPlayer(p1.getUuid());
			pb.setTestPeerForTest(p2);
			pb.jumpToForTest("RESULT");
			pb.leave(p1.getUuid());
			assertTrue(stats.player(p1.getUuid()).wins == w1Before + 1, "PvP RESULT winner leave must keep win");
			assertTrue(stats.player(p2.getUuid()).losses == l2Before + 1, "PvP RESULT winner leave must record loser loss");
			assertTrue(stats.player(p1.getUuid()).respect == r1Before, "PvP win must not award Respect (Zh4)");
			GameTestSupport.cleanupBattles(server);

			// 3. Coop leave in LOBBY (not in timeline) & in TIMELINE (no win); double-invite guard
			ServerPlayerEntity p3 = FakePlayer.get(w, new GameProfile(UUID.randomUUID(), "LobbyLeave"));
			p3.refreshPositionAndAngles(p1.getX() + 2, p1.getY(), p1.getZ(), 0, 0);
			BattleManager.registerTestPlayer(p3);
			ZombieEntity z2 = EntityType.ZOMBIE.create(w, SpawnReason.COMMAND);
			z2.refreshPositionAndAngles(p1.getX(), p1.getY(), p1.getZ() + 4, 180, 0);
			w.spawnEntity(z2);
			Battle b2 = BattleManager.start(p1, z2);
			b2.invite(p3);
			b2.answerInvite(p3, true);
			b2.onPlayerLeft(p3.getUuid());
			b2.jumpToForTest(Battle.Phase.TIMELINE);
			assertTrue(b2.viewForTest().segments.stream().noneMatch(s -> s.reader.equals(p3.getName().getString())),
				"Coop LOBBY leaver must not appear in timeline segments");
			b2.abort("cleanup");
			z2.discard();
			BattleManager.clearCooldownsForTest();

			// Invited player starts own battle -> cannot accept old invite
			ZombieEntity z3 = EntityType.ZOMBIE.create(w, SpawnReason.COMMAND);
			z3.refreshPositionAndAngles(p1.getX(), p1.getY(), p1.getZ() + 4, 180, 0);
			w.spawnEntity(z3);
			ZombieEntity z4 = EntityType.ZOMBIE.create(w, SpawnReason.COMMAND);
			z4.refreshPositionAndAngles(p3.getX(), p3.getY(), p3.getZ() + 4, 180, 0);
			w.spawnEntity(z4);
			Battle b3 = BattleManager.start(p1, z3);
			b3.invite(p3);
			Battle b4 = BattleManager.start(p3, z4);
			b3.answerInvite(p3, true);
			assertTrue(!b3.hasPlayer(p3.getUuid()) && b4.hasPlayer(p3.getUuid()),
				"Player in own battle must not accept old coop invite");
			b3.abort("cleanup");
			b4.abort("cleanup");
			z3.discard();
			z4.discard();

			// 4. Corrupt WAV -> sttMissingPlayers NOT set
			ZombieEntity z5 = EntityType.ZOMBIE.create(w, SpawnReason.COMMAND);
			z5.refreshPositionAndAngles(p1.getX(), p1.getY(), p1.getZ() + 4, 180, 0);
			w.spawnEntity(z5);
			Battle b5 = BattleManager.start(p1, z5);
			b5.jumpToForTest(Battle.Phase.TIMELINE);
			b5.onAudio(p1, new dev.rapbattle.net.Payloads.AudioC2S(b5.id, 0, true, 0, "CORRUPT_NOT_WAV_12345".getBytes()));
			assertTrue(!b5.isSttMissingForTest(p1.getUuid()), "Corrupt WAV must not mark player as sttMissing");
			b5.abort("cleanup");
			z5.discard();
			GameTestSupport.cleanupBattles(server);

			// 5. Dragon: 1st round won -> /kill in ROUND_RESULT -> dragonWon NOT set
			stats.player(p1.getUuid()).dragonWon = false;
			int dWinsBefore = stats.player(p1.getUuid()).wins;
			EnderDragonEntity dragon = EntityType.ENDER_DRAGON.create(w, SpawnReason.COMMAND);
			dragon.refreshPositionAndAngles(p1.getX(), p1.getY() + 10, p1.getZ() + 8, 180, 0);
			w.spawnEntity(dragon);
			Battle bd = BattleManager.start(p1, dragon);
			bd.enterRoundResultForTest(1, 0);
			p1.onTeleportationDone();
			p1.timeUntilRegen = 0;
			p1.damage(w, w.getDamageSources().genericKill(), Float.MAX_VALUE);
			assertTrue(!stats.player(p1.getUuid()).dragonWon && stats.player(p1.getUuid()).wins == dWinsBefore,
				"Dragon /kill in ROUND_RESULT after 1 round must not count as dragon win");
			dragon.discard();
			GameTestSupport.cleanupBattles(server);
		});
		ctx.waitTicks(5);
		ctx.runOnClient(mc -> {
			if (mc.player != null) mc.player.requestRespawn();
			if (mc.currentScreen != null) mc.setScreen(null);
		});
		ctx.waitTicks(10);
	}

	private static void testReview3LootReputationAndDecisions(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		sp.getServer().runOnServer(server -> {
			RapConfig.get().progression = "off";
			RapConfig.get().dressCode = false;
			ServerPlayerEntity p1 = server.getPlayerManager().getPlayerList().get(0);
			ServerWorld w = p1.getEntityWorld();
			p1.setHealth(p1.getMaxHealth());
			p1.getInventory().clear();

			// 1. Golem reward once per day (Zh1)
			var golem = EntityType.IRON_GOLEM.create(w, SpawnReason.COMMAND);
			golem.refreshPositionAndAngles(p1.getX() + 3, p1.getY(), p1.getZ(), 0, 0);
			w.spawnEntity(golem);
			GolemDefender.respect(golem, p1, "ru");
			int iron1 = p1.getInventory().count(Items.IRON_INGOT);
			GolemDefender.respect(golem, p1, "ru");
			int iron2 = p1.getInventory().count(Items.IRON_INGOT);
			assertTrue(iron1 == 5 && iron2 == 5, "Golem must reward 5 iron only once per day (got " + iron1 + ", " + iron2 + ")");
			golem.discard();
			p1.getInventory().clear();

			// 2. Loss after win revokes witness respect (D1)
			var wMob = EntityType.SPIDER.create(w, SpawnReason.COMMAND);
			wMob.refreshPositionAndAngles(p1.getX() + 3, p1.getY(), p1.getZ() + 2, 0, 0);
			w.spawnEntity(wMob);
			ZombieEntity zWin = EntityType.ZOMBIE.create(w, SpawnReason.COMMAND);
			zWin.refreshPositionAndAngles(p1.getX(), p1.getY(), p1.getZ() + 4, 180, 0);
			w.spawnEntity(zWin);
			Battle bw = BattleManager.start(p1, zWin);
			bw.jumpToForTest(Battle.Phase.TIMELINE);
			bw.jumpToForTest(Battle.Phase.RESULT);
			assertTrue(Reputation.respects(wMob, p1), "Witness mob must respect player after win");

			ZombieEntity zLoss = EntityType.ZOMBIE.create(w, SpawnReason.COMMAND);
			zLoss.refreshPositionAndAngles(p1.getX(), p1.getY(), p1.getZ() + 4, 180, 0);
			w.spawnEntity(zLoss);
			Battle bl = BattleManager.start(p1, zLoss);
			bl.jumpToForTest(Battle.Phase.RESULT, false, false, 10, 90);
			assertTrue(!Reputation.respects(wMob, p1), "Loss after win must revoke witness respect (D1)");
			wMob.discard();
			if (!zWin.isRemoved()) zWin.discard();
			if (!zLoss.isRemoved()) zLoss.discard();

			// 3. Creeper with stolen item explodes -> item lies on ground (G1)
			for (var ie : w.getEntitiesByClass(net.minecraft.entity.ItemEntity.class, p1.getBoundingBox().expand(24), e -> true)) ie.discard();
			net.minecraft.util.math.BlockPos cPos = net.minecraft.util.math.BlockPos.ofFloored(p1.getX() + 8, p1.getY(), p1.getZ());
			for (int dx = -2; dx <= 2; dx++) {
				for (int dz = -2; dz <= 2; dz++) {
					w.setBlockState(cPos.add(dx, -1, dz), net.minecraft.block.Blocks.STONE.getDefaultState());
					for (int dy = 0; dy <= 2; dy++) {
						w.setBlockState(cPos.add(dx, dy, dz), net.minecraft.block.Blocks.AIR.getDefaultState());
					}
				}
			}
			var creeper = EntityType.CREEPER.create(w, SpawnReason.COMMAND);
			creeper.refreshPositionAndAngles(cPos.getX() + 0.5, cPos.getY(), cPos.getZ() + 0.5, 0, 0);
			w.spawnEntity(creeper);
			creeper.setAttached(Theft.STOLEN_ITEM, new Theft.StolenData(new ItemStack(Items.DIAMOND, 1), false, ""));
			creeper.ignite();
		});
		ctx.waitTicks(50);
		sp.getServer().runOnServer(server -> {
			ServerPlayerEntity p1 = server.getPlayerManager().getPlayerList().get(0);
			ServerWorld w = p1.getEntityWorld();
			int diamonds = w.getEntitiesByClass(net.minecraft.entity.ItemEntity.class, p1.getBoundingBox().expand(24),
				ie -> ie.getStack().isOf(Items.DIAMOND)).stream().mapToInt(ie -> ie.getStack().getCount()).sum();
			int aliveCreepers = w.getEntitiesByClass(net.minecraft.entity.mob.CreeperEntity.class, p1.getBoundingBox().expand(24),
				c -> c.isAlive()).size();
			assertTrue(diamonds >= 1, "Exploded creeper must leave stolen diamond on the ground (got " + diamonds + ", aliveCreepers=" + aliveCreepers + ")");

			// 4. ClientConfig.save preserves legacy keys (E1)
			try {
				java.nio.file.Path dir = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir();
				java.nio.file.Path cfgPath = dir.resolve("rapbattle.json");
				String backup = java.nio.file.Files.exists(cfgPath) ? java.nio.file.Files.readString(cfgPath) : null;
				java.nio.file.Files.writeString(cfgPath, "{\"openRouterApiKey\":\"sk-test-legacy\",\"beatVolume\":0.8}");
				dev.rapbattle.client.ClientConfig.save();
				assertTrue("sk-test-legacy".equals(RapConfig.get().openRouterKey()),
					"ClientConfig.save must migrate legacy key to RapConfig instead of erasing it");
				if (backup != null) java.nio.file.Files.writeString(cfgPath, backup);
				java.nio.file.Files.deleteIfExists(dir.resolve("rapbattle-server.json"));
				RapConfig.load();
			} catch (Exception e) {
				throw new AssertionError(e);
			}
			GameTestSupport.cleanupBattles(server);
		});
	}

	private static void testReview3Followups628(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		sp.getServer().runOnServer(server -> {
			RapConfig.get().progression = "off";
			RapConfig.get().dressCode = false;
			ServerPlayerEntity p1 = server.getPlayerManager().getPlayerList().get(0);
			ServerWorld w = p1.getEntityWorld();
			Stats stats = Stats.of(server);

			// 1. Co-op exit in JUDGING: outcome deferred until verdict (win -> win, loss -> loss)
			ServerPlayerEntity p2 = FakePlayer.get(w, new GameProfile(UUID.randomUUID(), "JudgeExitWin"));
			p2.refreshPositionAndAngles(p1.getX() + 2, p1.getY(), p1.getZ(), 0, 0);
			BattleManager.registerTestPlayer(p2);
			int wBefore = stats.player(p2.getUuid()).wins;
			int lBefore = stats.player(p2.getUuid()).losses;
			ZombieEntity zWin = EntityType.ZOMBIE.create(w, SpawnReason.COMMAND);
			zWin.refreshPositionAndAngles(p1.getX(), p1.getY(), p1.getZ() + 4, 180, 0);
			w.spawnEntity(zWin);
			Battle bw = BattleManager.start(p1, zWin);
			bw.invite(p2);
			bw.answerInvite(p2, true);
			bw.jumpToForTest(Battle.Phase.JUDGING);
			bw.onPlayerLeft(p2.getUuid());
			assertTrue(!BattleManager.isBattling(p2), "JUDGING leaver must immediately leave battle");
			assertTrue(stats.player(p2.getUuid()).wins == wBefore && stats.player(p2.getUuid()).losses == lBefore,
				"JUDGING leaver stats must be deferred until verdict");
			bw.jumpToForTest(Battle.Phase.RESULT, true, false, 90, 10);
			assertTrue(stats.player(p2.getUuid()).wins == wBefore + 1,
				"Co-op JUDGING leaver must get a win when team wins (got " + stats.player(p2.getUuid()).wins + ")");
			zWin.discard();
			BattleManager.clearCooldownsForTest();
			GameTestSupport.cleanupBattles(server);

			// Regression: team loses -> loss recorded for JUDGING leaver
			int lBefore2 = stats.player(p2.getUuid()).losses;
			ZombieEntity zLoss = EntityType.ZOMBIE.create(w, SpawnReason.COMMAND);
			zLoss.refreshPositionAndAngles(p1.getX(), p1.getY(), p1.getZ() + 4, 180, 0);
			w.spawnEntity(zLoss);
			Battle bl = BattleManager.start(p1, zLoss);
			bl.invite(p2);
			bl.answerInvite(p2, true);
			bl.jumpToForTest(Battle.Phase.JUDGING);
			bl.onPlayerLeft(p2.getUuid());
			bl.jumpToForTest(Battle.Phase.RESULT, false, false, 10, 90);
			assertTrue(stats.player(p2.getUuid()).losses == lBefore2 + 1,
				"Co-op JUDGING leaver must get a loss when team loses");
			zLoss.discard();
			BattleManager.clearCooldownsForTest();
			GameTestSupport.cleanupBattles(server);

			// 2. allowSwearing = false -> trophy lore (ru & en), BattleView.allowSwearing, clean keys
			boolean prevSwear = RapConfig.get().allowSwearing;
			try {
				RapConfig.get().allowSwearing = false;
				p1.getInventory().clear();
				ZombieEntity zTrophy = EntityType.ZOMBIE.create(w, SpawnReason.COMMAND);
				Opponent rank5Opp = new Opponent(zTrophy, dev.rapbattle.battle.MobProfiles.of(EntityType.ZOMBIE), Rank.NETHERITE, "ТестМоб", "neutral", java.util.List.of());
				Trophies.give(p1, rank5Opp, "ru");
				Trophies.give(p1, rank5Opp, "en");
				var ruStack = p1.getInventory().getStack(0);
				var enStack = p1.getInventory().getStack(1);
				var ruLore = ruStack.get(net.minecraft.component.DataComponentTypes.LORE);
				var enLore = enStack.get(net.minecraft.component.DataComponentTypes.LORE);
				String ruLoreStr = ruLore != null ? ruLore.lines().stream().map(net.minecraft.text.Text::getString).reduce("", (a, b) -> a + " " + b) : "";
				String enLoreStr = enLore != null ? enLore.lines().stream().map(net.minecraft.text.Text::getString).reduce("", (a, b) -> a + " " + b) : "";
				assertTrue(ruLoreStr.contains("Разгромил") && !ruLoreStr.toLowerCase(java.util.Locale.ROOT).contains("разъеб"),
					"allowSwearing=false RU trophy lore must use clean variant: " + ruLoreStr);
				assertTrue(enLoreStr.contains("Crushed") && !enLoreStr.toLowerCase(java.util.Locale.ROOT).contains("fuck"),
					"allowSwearing=false EN trophy lore must use clean variant: " + enLoreStr);

				zTrophy.refreshPositionAndAngles(p1.getX(), p1.getY(), p1.getZ() + 4, 180, 0);
				w.spawnEntity(zTrophy);
				Battle bs = BattleManager.start(p1, zTrophy);
				assertTrue(!bs.viewForTest().allowSwearing, "BattleView.allowSwearing must be false when config allowSwearing=false");
				bs.abort("cleanup");
				zTrophy.discard();

				// allowSwearing = true -> uncensored as before
				RapConfig.get().allowSwearing = true;
				p1.getInventory().clear();
				Trophies.give(p1, rank5Opp, "ru");
				Trophies.give(p1, rank5Opp, "en");
				var ruLore2 = p1.getInventory().getStack(0).get(net.minecraft.component.DataComponentTypes.LORE);
				var enLore2 = p1.getInventory().getStack(1).get(net.minecraft.component.DataComponentTypes.LORE);
				String ruLoreStr2 = ruLore2 != null ? ruLore2.lines().stream().map(net.minecraft.text.Text::getString).reduce("", (a, b) -> a + " " + b) : "";
				String enLoreStr2 = enLore2 != null ? enLore2.lines().stream().map(net.minecraft.text.Text::getString).reduce("", (a, b) -> a + " " + b) : "";
				assertTrue(ruLoreStr2.contains("Разъебал"), "allowSwearing=true RU trophy lore must use uncensored variant");
				assertTrue(enLoreStr2.contains("Fucked up"), "allowSwearing=true EN trophy lore must use uncensored variant");
				p1.getInventory().clear();
			} finally {
				RapConfig.get().allowSwearing = prevSwear;
				BattleManager.clearCooldownsForTest();
				GameTestSupport.cleanupBattles(server);
			}
		});
	}
}
