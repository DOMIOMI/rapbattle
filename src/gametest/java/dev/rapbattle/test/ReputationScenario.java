package dev.rapbattle.test;

import com.mojang.authlib.GameProfile;
import dev.rapbattle.Clock;
import dev.rapbattle.RapConfig;
import dev.rapbattle.battle.*;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.*;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Box;

import java.util.List;
import java.util.UUID;

import static dev.rapbattle.test.GameTestSupport.assertTrue;

/** Reputation scenario: Street Cred, consolidated loss (Run! + cred revoke + theft + tag + crown + 12s clock), Piglin 2-tier, all thieves, coop worst theft, cleanup. */
public final class ReputationScenario {
	private ReputationScenario() {}

	public static void run(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		sp.getServer().runCommand("time set 14500");
		testStreetCredAndWitness(ctx, sp);
		testLossConsolidated(ctx, sp);
		testPiglinTwoTiers(ctx, sp);
		testBastionLossDelay(ctx, sp);
		testTheftAllThievesAndEdgeCases(ctx, sp);
		testCoopWorstTheftAndZeroDealt(ctx, sp);
		testThiefChampionNameAndMapCleanup(ctx, sp);
		GameTestSupport.assertNoRawKeys("reputation");
	}

	private static void testStreetCredAndWitness(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		ZombieEntity[] zRef = new ZombieEntity[1];
		SkeletonEntity[] skRef = new SkeletonEntity[1];
		CreeperEntity[] witnessRef = new CreeperEntity[1];
		CreeperEntity[] strangerRef = new CreeperEntity[1];

		sp.getServer().runOnServer(server -> {
			RapConfig.get().progression = "off";
			ServerPlayerEntity p = server.getPlayerManager().getPlayerList().get(0);
			ServerWorld w = p.getEntityWorld();

			ZombieEntity z = EntityType.ZOMBIE.create(w, SpawnReason.COMMAND);
			z.refreshPositionAndAngles(p.getX(), p.getY(), p.getZ() + 4.0, 180, 0);
			// Rank 4 (gold helmet), as the old rank-3 zombie + night bonus was: Street Cred 4 covers the rank-4 creeper.
			// Note: jumping straight to RESULT skips the lobby, so no crowd gathers and the witness path itself is not exercised.
			z.equipStack(net.minecraft.entity.EquipmentSlot.HEAD, new net.minecraft.item.ItemStack(net.minecraft.item.Items.GOLDEN_HELMET));
			w.spawnEntity(z);
			zRef[0] = z;

			CreeperEntity witness = EntityType.CREEPER.create(w, SpawnReason.COMMAND);
			witness.refreshPositionAndAngles(p.getX() + 4.0, p.getY(), p.getZ() + 4.0, 180, 0);
			w.spawnEntity(witness);
			witnessRef[0] = witness;

			Battle b = BattleManager.start(p, z);
			b.jumpToForTest(Battle.Phase.RESULT);
		});
		ctx.waitTicks(2);

		sp.getServer().runOnServer(server -> {
			ServerPlayerEntity p = server.getPlayerManager().getPlayerList().get(0);
			ServerWorld w = p.getEntityWorld();
			assertTrue(PlayerStatus.has(p.getUuid(), PlayerStatus.STREET_CRED), "Must have Street Cred after winning");
			assertTrue(Reputation.credLevel(p.getUuid()) >= 1, "Street Cred level must be >= 1");
			assertTrue(Reputation.respects(witnessRef[0], p), "Crowd witness must respect player regardless of rank");

			SkeletonEntity sk = EntityType.SKELETON.create(w, SpawnReason.COMMAND);
			sk.refreshPositionAndAngles(p.getX() - 3.0, p.getY(), p.getZ() + 3.0, 180, 0);
			w.spawnEntity(sk);
			skRef[0] = sk;

			int cred = Reputation.credLevel(p.getUuid());
			MobEntity stranger = (cred < 4 ? EntityType.CREEPER : EntityType.GIANT).create(w, SpawnReason.COMMAND);
			stranger.refreshPositionAndAngles(p.getX() + 8.0, p.getY(), p.getZ() + 8.0, 180, 0);
			w.spawnEntity(stranger);
			strangerRef[0] = stranger instanceof CreeperEntity ce ? ce : null;

			Clock.advanceForTest(20_000L); // expire truce immediately
			sk.setTarget(p);
			witnessRef[0].setTarget(p);
			stranger.setTarget(p);
			assertTrue(sk.getTarget() == null, "Skeleton <= cred rank must not target player");
			assertTrue(witnessRef[0].getTarget() == null, "Witness creeper must not target player");
			assertTrue(!Reputation.respects(stranger, p), "Higher-rank stranger must not respect player");
			assertTrue(stranger.getTarget() == p, "Higher-rank stranger must target player (cred=" + cred + ")");
			if (strangerRef[0] == null) stranger.discard();

			Reputation.onPlayerHostileAction(p, skRef[0]);
			assertTrue(!PlayerStatus.has(p.getUuid(), PlayerStatus.STREET_CRED), "Hostile action must revoke Street Cred");
			skRef[0].setTarget(p);
			assertTrue(skRef[0].getTarget() == p, "Skeleton must target player after cred loss");

			GameTestSupport.cleanupBattles(server);
			for (var e : java.util.Arrays.asList(zRef[0], skRef[0], witnessRef[0], strangerRef[0])) {
				if (e != null && !e.isRemoved()) e.discard();
			}
			Reputation.clear();
			RapConfig.get().progression = "soft";
		});
	}

	/** One loss checks Run! (truce + Speed I), Street Cred revocation, theft, stolen tag, champion crown, and 12s clock advance -> aggro. */
	private static void testLossConsolidated(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		ZombieEntity[] zRef = new ZombieEntity[1];
		sp.getServer().runOnServer(server -> {
			RapConfig.get().progression = "off";
			ServerPlayerEntity p = server.getPlayerManager().getPlayerList().get(0);
			ServerWorld w = p.getEntityWorld();

			// First earn Street Cred so loss can revoke it
			ZombieEntity dummy = EntityType.ZOMBIE.create(w, SpawnReason.COMMAND);
			dummy.refreshPositionAndAngles(p.getX() - 4.0, p.getY(), p.getZ() + 4.0, 180, 0);
			w.spawnEntity(dummy);
			Battle bWin = BattleManager.start(p, dummy);
			bWin.jumpToForTest(Battle.Phase.RESULT);
			dummy.discard();
			assertTrue(PlayerStatus.has(p.getUuid(), PlayerStatus.STREET_CRED), "Precondition: Street Cred active before loss");

			p.getInventory().clear();
			p.getInventory().setStack(0, new ItemStack(Items.APPLE, 1));
			p.getInventory().setSelectedSlot(0);

			ZombieEntity z = EntityType.ZOMBIE.create(w, SpawnReason.COMMAND);
			z.refreshPositionAndAngles(p.getX(), p.getY(), p.getZ() + 4.0, 180, 0);
			w.spawnEntity(z);
			zRef[0] = z;

			Battle b = BattleManager.start(p, z);
			b.jumpToForTest(Battle.Phase.RESULT, false, true, 0, 90);

			assertTrue(PlayerStatus.has(p.getUuid(), PlayerStatus.RUN_AWAY), "Loss check: must have RUN_AWAY");
			assertTrue(p.hasStatusEffect(StatusEffects.SPEED), "Loss check: must have Speed I");
			assertTrue(!PlayerStatus.has(p.getUuid(), PlayerStatus.STREET_CRED), "Loss check: must revoke Street Cred");
			assertTrue(Theft.hasStolen(z) && Theft.getStolen(z).isOf(Items.APPLE), "Loss check: mob must steal item");
			String cname = z.getCustomName() != null ? z.getCustomName().getString() : "";
			assertTrue(cname.contains("[") && cname.contains("♛"), "Loss check: mob name must contain stolen tag and crown: " + cname);
			assertTrue(Crowd.inTruce(z), "Loss check: winning mob must be in 10s truce");
			z.setTarget(p);
			assertTrue(z.getTarget() == null, "Loss check: winning mob must not attack during Run! truce");

			// Advance clock by 12 seconds -> truce expired -> mob can attack
			Clock.advanceForTest(12_000L);
		});
		ctx.waitTicks(2);
		sp.getServer().runOnServer(server -> {
			ServerPlayerEntity p = server.getPlayerManager().getPlayerList().get(0);
			assertTrue(!Crowd.inTruce(zRef[0]), "Loss check: truce must expire after 12s clock advance");
			zRef[0].setTarget(p);
			assertTrue(zRef[0].getTarget() == p, "Loss check: mob must attack after 12s truce expires");

			GameTestSupport.cleanupBattles(server);
			if (zRef[0] != null && !zRef[0].isRemoved()) zRef[0].discard();
			p.clearStatusEffects();
			p.getInventory().clear();
			Reputation.clear();
			RapConfig.get().progression = "soft";
		});
	}

	private static void testPiglinTwoTiers(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		sp.getServer().runOnServer(server -> {
			RapConfig.get().progression = "off";
			ServerPlayerEntity p = server.getPlayerManager().getPlayerList().get(0);
			ServerWorld w = p.getEntityWorld();

			PiglinEntity pg = EntityType.PIGLIN.create(w, SpawnReason.COMMAND);
			pg.setImmuneToZombification(true);
			pg.refreshPositionAndAngles(p.getX(), p.getY(), p.getZ() + 4.0, 180, 0);
			w.spawnEntity(pg);
			Battle b = BattleManager.start(p, pg);
			b.jumpToForTest(Battle.Phase.RESULT);
			assertTrue(PlayerStatus.has(p.getUuid(), PlayerStatus.PIGLIN_PAL), "Must gain PIGLIN_PAL after beating piglin");

			PiglinBruteEntity br = EntityType.PIGLIN_BRUTE.create(w, SpawnReason.COMMAND);
			br.setImmuneToZombification(true);
			br.refreshPositionAndAngles(p.getX() + 4.0, p.getY(), p.getZ() + 4.0, 180, 0);
			w.spawnEntity(br);
			Battle b2 = BattleManager.start(p, br);
			b2.jumpToForTest(Battle.Phase.RESULT);
			assertTrue(PlayerStatus.has(p.getUuid(), PlayerStatus.BASTION_GUEST), "Must gain BASTION_GUEST after beating brute");

			Reputation.onPlayerHostileAction(p, pg);
			assertTrue(!PlayerStatus.has(p.getUuid(), PlayerStatus.PIGLIN_PAL), "Hitting piglin must revoke PIGLIN_PAL");
			assertTrue(!PlayerStatus.has(p.getUuid(), PlayerStatus.BASTION_GUEST), "Hitting piglin must revoke BASTION_GUEST");

			GameTestSupport.cleanupBattles(server);
			pg.discard();
			br.discard();
			Reputation.clear();
			RapConfig.get().progression = "soft";
		});
	}

	private static void testTheftAllThievesAndEdgeCases(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		sp.getServer().runCommand("time set 6000");
		sp.getServer().runOnServer(server -> {
			RapConfig.get().progression = "off";
			ServerPlayerEntity p = server.getPlayerManager().getPlayerList().get(0);
			p.getInventory().clear();
			p.getInventory().setStack(0, new ItemStack(Items.DIAMOND_SWORD));
			p.getInventory().setStack(1, GuideBook.create("en"));
			ItemStack trophy = new ItemStack(Items.END_ROD);
			NbtCompound tag = new NbtCompound();
			tag.putString(Trophies.NBT_KEY, "wither");
			trophy.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(tag));
			p.getInventory().setStack(2, trophy);

			ZombieEntity z = EntityType.ZOMBIE.create(p.getEntityWorld(), SpawnReason.COMMAND);
			z.refreshPositionAndAngles(p.getX(), p.getY(), p.getZ() + 4.0, 180, 0);
			p.getEntityWorld().spawnEntity(z);
			Battle b = BattleManager.start(p, z);
			b.jumpToForTest(Battle.Phase.RESULT, false, true, 0, 90);
			assertTrue(!Theft.hasStolen(z), "Forbidden items must never be stolen");
			z.discard();
			GameTestSupport.cleanupBattles(server);
		});

		runThiefCase(sp, EntityType.ZOMBIE, Items.STRING, Items.EMERALD, false);
		runThiefCase(sp, EntityType.WITCH, Items.EMERALD, Items.BREAD, false);
		runThiefCase(sp, EntityType.SPIDER, Items.BONE, null, true);
		runThiefCase(sp, EntityType.SHEEP, Items.WHEAT, Items.EMERALD, false);
		runThiefCase(sp, EntityType.CHICKEN, Items.WHEAT_SEEDS, Items.EMERALD, false);

		sp.getServer().runOnServer(server -> {
			ServerPlayerEntity p = server.getPlayerManager().getPlayerList().get(0);
			ServerWorld w = p.getEntityWorld();
			p.getInventory().clear();
			p.getInventory().setStack(0, new ItemStack(Items.EMERALD, 1));
			CreeperEntity cr = EntityType.CREEPER.create(w, SpawnReason.COMMAND);
			cr.refreshPositionAndAngles(p.getX(), p.getY(), p.getZ() + 4.0, 180, 0);
			w.spawnEntity(cr);
			Battle b = BattleManager.start(p, cr);
			b.jumpToForTest(Battle.Phase.RESULT, false, true, 0, 90);
			assertTrue(Theft.hasStolen(cr), "Creeper must steal emerald");
			Theft.dropOnDeathOrExplode(cr);
			cr.discard();
			List<ItemEntity> drops = w.getEntitiesByClass(ItemEntity.class, new Box(cr.getBlockPos()).expand(3), e -> e.getStack().isOf(Items.EMERALD));
			assertTrue(!drops.isEmpty(), "Stolen item must drop when creeper explodes");
			for (ItemEntity it : drops) it.discard();
			GameTestSupport.cleanupBattles(server);

			p.getInventory().clear();
			p.getInventory().setStack(0, new ItemStack(Items.GOLD_NUGGET, 1));
			PiglinEntity pg = EntityType.PIGLIN.create(w, SpawnReason.COMMAND);
			pg.refreshPositionAndAngles(p.getX(), p.getY(), p.getZ() + 4.0, 180, 0);
			w.spawnEntity(pg);
			Battle b2 = BattleManager.start(p, pg);
			b2.jumpToForTest(Battle.Phase.RESULT, false, true, 0, 90);
			assertTrue(Theft.hasStolen(pg), "Piglin must steal gold nugget");
			ZombifiedPiglinEntity zp = EntityType.ZOMBIFIED_PIGLIN.create(w, SpawnReason.CONVERSION);
			zp.refreshPositionAndAngles(pg.getX(), pg.getY(), pg.getZ(), 0, 0);
			w.spawnEntity(zp);
			Theft.transferOnConversion(pg, zp);
			pg.discard();
			assertTrue(Theft.hasStolen(zp) && zp.isPersistent(), "Converted zombified piglin must keep stolen item");
			zp.discard();
			GameTestSupport.cleanupBattles(server);
			Reputation.clear();
			RapConfig.get().progression = "soft";
		});
	}

	private static <T extends MobEntity> void runThiefCase(TestSingleplayerContext sp,
														   EntityType<T> type, Item expectedSteal, Item untouchedItem,
														   boolean returnViaRematch) {
		sp.getServer().runOnServer(server -> {
			ServerPlayerEntity p = server.getPlayerManager().getPlayerList().get(0);
			ServerWorld w = p.getEntityWorld();
			p.getInventory().clear();
			p.getInventory().setStack(0, new ItemStack(expectedSteal, 1));
			if (untouchedItem != null) p.getInventory().setStack(1, new ItemStack(untouchedItem, 1));

			MobEntity m = type.create(w, SpawnReason.COMMAND);
			m.refreshPositionAndAngles(p.getX(), p.getY(), p.getZ() + 3.5, 180, 0);
			w.spawnEntity(m);

			Battle b = BattleManager.start(p, m);
			b.jumpToForTest(Battle.Phase.RESULT, false, true, 0, 90);
			assertTrue(Theft.hasStolen(m) && Theft.getStolen(m).isOf(expectedSteal),
				type.getName().getString() + " must steal " + expectedSteal);
			assertTrue(m.isPersistent(), "Thief must be persistent");
			assertTrue(p.getInventory().count(expectedSteal) == 0, "Stolen item must be gone from player");
			if (untouchedItem != null) {
				assertTrue(p.getInventory().count(untouchedItem) == 1, "Untouched item must remain in player inventory");
			}

			if (returnViaRematch) {
				Clock.advanceForTest(20_000L);
				Battle rematch = BattleManager.start(p, m);
				rematch.jumpToForTest(Battle.Phase.RESULT);
				assertTrue(!Theft.hasStolen(m), "Rematch win must clear stolen item from thief");
				assertTrue(p.getInventory().count(expectedSteal) == 1, "Rematch win must return stolen item to player");
			} else {
				m.kill(w);
				List<ItemEntity> drops = w.getEntitiesByClass(ItemEntity.class, new Box(m.getBlockPos()).expand(3),
					e -> e.getStack().isOf(expectedSteal));
				int total = drops.stream().mapToInt(it -> it.getStack().getCount()).sum();
				assertTrue(total == 1, "Killing thief must drop stolen item exactly once (got " + total + ")");
				for (ItemEntity it : drops) it.discard();
			}
			if (!m.isRemoved()) m.discard();
			GameTestSupport.cleanupBattles(server);
		});
	}

	/** 6.21 Item 3.1: 3-player coop world test — MVP gets 100%, contributor 50%, silent 0%; on loss, steals from lowest-score player. */
	private static void testCoopWorstTheftAndZeroDealt(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		sp.getServer().runOnServer(server -> {
			RapConfig.get().progression = "off";
			ServerPlayerEntity p1 = server.getPlayerManager().getPlayerList().get(0);
			ServerWorld w = p1.getEntityWorld();
			ServerPlayerEntity p2 = FakePlayer.get(w, new GameProfile(UUID.randomUUID(), "CoopMid"));
			p2.refreshPositionAndAngles(p1.getX() + 2, p1.getY(), p1.getZ(), 0, 0);
			ServerPlayerEntity p3 = FakePlayer.get(w, new GameProfile(UUID.randomUUID(), "CoopSilent"));
			p3.refreshPositionAndAngles(p1.getX() - 2, p1.getY(), p1.getZ(), 0, 0);
			BattleManager.registerTestPlayer(p2);
			BattleManager.registerTestPlayer(p3);

			// 1. Coop win: MVP (60) -> 100% cred, mid (30) -> 50% cred, silent (0) -> 0% cred
			ZombieEntity zWin = EntityType.ZOMBIE.create(w, SpawnReason.COMMAND);
			zWin.refreshPositionAndAngles(p1.getX(), p1.getY(), p1.getZ() + 4.0, 180, 0);
			w.spawnEntity(zWin);
			Battle bWin = BattleManager.start(p1, zWin);
			bWin.invite(p2);
			bWin.answerInvite(p2, true);
			bWin.invite(p3);
			bWin.answerInvite(p3, true);
			bWin.setPlayerScoreForTest(p1.getName().getString(), 60);
			bWin.setPlayerScoreForTest(p2.getName().getString(), 30);
			bWin.setPlayerScoreForTest(p3.getName().getString(), 0);
			bWin.jumpToForTest(Battle.Phase.RESULT, true, true, 90, 0);

			assertTrue(PlayerStatus.has(p1.getUuid(), PlayerStatus.STREET_CRED) && Reputation.credLevel(p1.getUuid()) >= 1,
				"MVP must get 100% Street Cred");
			assertTrue(PlayerStatus.has(p2.getUuid(), PlayerStatus.STREET_CRED) && Reputation.credLevel(p2.getUuid()) >= 1,
				"Contributor must get 50% Street Cred");
			assertTrue(!PlayerStatus.has(p3.getUuid(), PlayerStatus.STREET_CRED) && Reputation.credLevel(p3.getUuid()) == 0,
				"Silent teammate (0 dealt) must get 0% Street Cred");
			// Advance clock by 55% of 3 minutes (100s): 50% cred (90s) expires, 100% cred (180s) remains!
			Clock.advanceForTest(100_000L);
			assertTrue(Reputation.credLevel(p1.getUuid()) >= 1, "MVP 100% cred must still be active after 100s");
			assertTrue(Reputation.credLevel(p2.getUuid()) == 0, "Contributor 50% (90s) cred must expire after 100s");
			zWin.discard();

			// 2. Coop loss: p1(60), p2(30), p3(0) -> steals from lowest-score player (p3)
			p1.getInventory().clear();
			p2.getInventory().clear();
			p3.getInventory().clear();
			p1.getInventory().setStack(0, new ItemStack(Items.APPLE, 1));
			p2.getInventory().setStack(0, new ItemStack(Items.COAL, 1));
			p3.getInventory().setStack(0, new ItemStack(Items.BREAD, 1));

			ZombieEntity zLoss = EntityType.ZOMBIE.create(w, SpawnReason.COMMAND);
			zLoss.refreshPositionAndAngles(p1.getX(), p1.getY(), p1.getZ() + 4.0, 180, 0);
			w.spawnEntity(zLoss);
			Battle bLoss = BattleManager.start(p1, zLoss);
			bLoss.invite(p2);
			bLoss.answerInvite(p2, true);
			bLoss.invite(p3);
			bLoss.answerInvite(p3, true);
			bLoss.setPlayerScoreForTest(p1.getName().getString(), 60);
			bLoss.setPlayerScoreForTest(p2.getName().getString(), 30);
			bLoss.setPlayerScoreForTest(p3.getName().getString(), 0);
			bLoss.jumpToForTest(Battle.Phase.RESULT, false, true, 0, 90);

			assertTrue(Theft.hasStolen(zLoss) && Theft.getStolen(zLoss).isOf(Items.BREAD),
				"Coop loss must steal from lowest-score player (p3)");
			assertTrue(p3.getInventory().count(Items.BREAD) == 0, "p3's bread must be stolen");
			assertTrue(p1.getInventory().count(Items.APPLE) == 1 && p2.getInventory().count(Items.COAL) == 1,
				"Higher-score teammates must keep their items");

			zLoss.discard();
			p1.getInventory().clear();
			p2.getInventory().clear();
			p3.getInventory().clear();
			GameTestSupport.cleanupBattles(server);
			Reputation.clear();
			RapConfig.get().progression = "soft";
		});
	}

	private static void testThiefChampionNameAndMapCleanup(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		sp.getServer().runOnServer(server -> {
			ServerPlayerEntity p = server.getPlayerManager().getPlayerList().get(0);
			ServerWorld w = p.getEntityWorld();
			p.getInventory().clear();
			p.getInventory().setStack(0, new ItemStack(Items.APPLE, 1));
			p.getInventory().setSelectedSlot(0);

			ZombieEntity thief = EntityType.ZOMBIE.create(w, SpawnReason.COMMAND);
			thief.refreshPositionAndAngles(p.getX(), p.getY(), p.getZ() + 4.0, 180, 0);
			w.spawnEntity(thief);

			Battle b1 = BattleManager.start(p, thief);
			b1.jumpToForTest(Battle.Phase.RESULT, false, true, 10, 90);
			assertTrue(Theft.hasStolen(thief), "Thief must hold stolen apple");
			String n1 = thief.getCustomName() != null ? thief.getCustomName().getString() : "";
			assertTrue(n1.contains("♛") && n1.contains("["), "1st win name must keep crown and stolen tag: " + n1);

			Battle b2 = BattleManager.start(p, thief);
			b2.jumpToForTest(Battle.Phase.RESULT, false, true, 10, 90);
			String n2 = thief.getCustomName() != null ? thief.getCustomName().getString() : "";
			assertTrue(n2.contains("♛×2") && n2.contains("["), "2nd win name must keep ♛×2 AND stolen tag: " + n2);
			assertTrue(thief.getCustomName().getStyle().getColor() != null, "2nd win name must keep gold color");

			Battle b3 = BattleManager.start(p, thief);
			b3.jumpToForTest(Battle.Phase.RESULT, true, true, 90, 10);
			assertTrue(!Theft.hasStolen(thief), "Stolen item must be returned on win");
			String n3 = thief.getCustomName() != null ? thief.getCustomName().getString() : "";
			assertTrue(n3.contains("♛×2") && !n3.contains("["), "Returned name must keep ♛×2 without stolen tag: " + n3);
			assertTrue(thief.getCustomName().getStyle().getColor() != null, "Returned champion name must keep gold color");
			assertTrue(thief.isPersistent(), "Champion must remain persistent");
			thief.discard();

			// 6.22 #17: named mob ("Бобик") keeps original name without [Item] after returning stolen item
			p.getInventory().setStack(0, new ItemStack(Items.EMERALD, 1));
			p.getInventory().setSelectedSlot(0);
			ZombieEntity bobik = EntityType.ZOMBIE.create(w, SpawnReason.COMMAND);
			bobik.setCustomName(net.minecraft.text.Text.literal("Бобик"));
			bobik.refreshPositionAndAngles(p.getX(), p.getY(), p.getZ() + 4.0, 180, 0);
			w.spawnEntity(bobik);
			BattleManager.start(p, bobik).jumpToForTest(Battle.Phase.RESULT, false, true, 10, 90);
			BattleManager.start(p, bobik).jumpToForTest(Battle.Phase.RESULT, false, true, 10, 90);
			BattleManager.start(p, bobik).jumpToForTest(Battle.Phase.RESULT, true, true, 90, 10);
			String bobikName = bobik.getCustomName() != null ? bobik.getCustomName().getString() : "";
			assertTrue("Бобик ♛×2".equals(bobikName), "Named champion after return must be 'Бобик ♛×2', got: " + bobikName);
			bobik.discard();

			ZombieEntity nonChamp = EntityType.ZOMBIE.create(w, SpawnReason.COMMAND);
			nonChamp.refreshPositionAndAngles(p.getX(), p.getY(), p.getZ() + 4.0, 180, 0);
			w.spawnEntity(nonChamp);
			assertTrue(!nonChamp.isPersistent(), "Fresh mob starts non-persistent");
			nonChamp.setAttached(Theft.STOLEN_ITEM, new Theft.StolenData(new ItemStack(Items.COAL), false, ""));
			nonChamp.setPersistent();
			Theft.dropOnDeathOrExplode(nonChamp);
			assertTrue(!nonChamp.isPersistent(), "Non-champion must restore wasPersistent=false when stolen item drops");
			nonChamp.discard();

			Clock.advanceForTest(60 * 60_000L);
		});
		ctx.waitTicks(12);
		sp.getServer().runOnServer(server -> {
			int left = Reputation.activeMapEntriesForTest();
			assertTrue(left == 0, "All expired Reputation map entries must be cleaned up (left=" + left + ")");
			GameTestSupport.cleanupBattles(server);
			Reputation.clear();
		});
	}

	private static void testBastionLossDelay(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		sp.getServer().runOnServer(server -> {
			RapConfig.get().progression = "off";
			ServerPlayerEntity p = server.getPlayerManager().getPlayerList().get(0);
			p.changeGameMode(net.minecraft.world.GameMode.SURVIVAL);
			p.setInvulnerable(false);
			ServerWorld w = p.getEntityWorld();
			PiglinBruteEntity br = EntityType.PIGLIN_BRUTE.create(w, SpawnReason.COMMAND);
			br.setImmuneToZombification(true);
			br.refreshPositionAndAngles(p.getX() + 4.0, p.getY(), p.getZ() + 4.0, 180, 0);
			w.spawnEntity(br);
			PiglinEntity pg = EntityType.PIGLIN.create(w, SpawnReason.COMMAND);
			pg.setImmuneToZombification(true);
			pg.refreshPositionAndAngles(p.getX() + 5.0, p.getY(), p.getZ() + 4.0, 180, 0);
			w.spawnEntity(pg);
			Battle b = BattleManager.start(p, br);
			b.jumpToForTest(Battle.Phase.RESULT, false, true, 0, 90);
			BastionBattle.onLoss(w, p, br.getBlockPos());

			Clock.advanceForTest(9_000L);
			Later.tick();
			pg.setTarget(p);
			br.setTarget(p);
			assertTrue(pg.getTarget() == null && br.getTarget() == null,
				"During 9s Run! truce, bastion piglins must not attack loser");

			Clock.advanceForTest(2_000L);
			Later.tick();
			assertTrue(pg.getTarget() == p && br.getTarget() == p,
				"After 10s Run! truce expires, bastion piglins must aggro loser");

			br.discard();
			pg.discard();
			GameTestSupport.cleanupBattles(server);
			Reputation.clear();
			RapConfig.get().progression = "soft";
		});
	}
}
