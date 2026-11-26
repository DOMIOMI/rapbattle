package dev.rapbattle.test;

import dev.rapbattle.battle.*;
import dev.rapbattle.client.ClientBattle;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.*;
import net.minecraft.entity.passive.ChickenEntity;
import net.minecraft.entity.passive.SheepEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Box;
import net.minecraft.world.GameMode;

import java.util.List;

/** Client gametest for 6.16: Street Cred, Run!, Two-tier Piglins, and Theft (incl. creeper & conversion). */
public class ReputationGameTest implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext ctx) {
		GameTestSupport.installKeyAudit();
		ctx.getInput().resizeWindow(1280, 720);
		ctx.waitTicks(5);
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			sp.getClientWorld().waitForChunksRender();
			GameTestSupport.applyLang(ctx);
			sp.getServer().runCommand("time set 14500");

			testStreetCredAndWitness(ctx, sp);
			testRunAfterLoss(ctx, sp);
			testPiglinTwoTiers(ctx, sp);
			testTheftAllThievesAndEdgeCases(ctx, sp);

			GameTestSupport.assertNoRawKeys("ReputationGameTest");
		}
	}

	private void testStreetCredAndWitness(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		ZombieEntity[] zRef = new ZombieEntity[1];
		SkeletonEntity[] skRef = new SkeletonEntity[1];
		CreeperEntity[] witnessRef = new CreeperEntity[1];
		CreeperEntity[] strangerRef = new CreeperEntity[1];

		sp.getServer().runOnServer(server -> {
			ServerPlayerEntity p = server.getPlayerManager().getPlayerList().get(0);
			ServerWorld w = p.getEntityWorld();
			p.changeGameMode(GameMode.SURVIVAL);
			p.setInvulnerable(true);

			ZombieEntity z = EntityType.ZOMBIE.create(w, SpawnReason.COMMAND);
			z.refreshPositionAndAngles(p.getX(), p.getY(), p.getZ() + 4.0, 180, 0);
			w.spawnEntity(z);
			zRef[0] = z;

			CreeperEntity witness = EntityType.CREEPER.create(w, SpawnReason.COMMAND);
			witness.refreshPositionAndAngles(p.getX() + 4.0, p.getY(), p.getZ() + 4.0, 180, 0);
			w.spawnEntity(witness);
			witnessRef[0] = witness;

			Battle b = BattleManager.start(p, z);
			b.startEarly(p);
			b.forceOutcomeForTest(true, true);
		});
		ctx.waitTicks(5);

		sp.getServer().runOnServer(server -> {
			ServerPlayerEntity p = server.getPlayerManager().getPlayerList().get(0);
			ServerWorld w = p.getEntityWorld();
			GameTestSupport.assertTrue(PlayerStatus.has(p.getUuid(), PlayerStatus.STREET_CRED), "Must have Street Cred after winning");
			GameTestSupport.assertTrue(Reputation.credLevel(p.getUuid()) >= 1, "Street Cred level must be >= 1");

			// Witness creeper (rank 4) from the crowd must respect the player
			GameTestSupport.assertTrue(Reputation.respects(witnessRef[0], p), "Crowd witness must respect player regardless of rank");

			SkeletonEntity sk = EntityType.SKELETON.create(w, SpawnReason.COMMAND);
			sk.refreshPositionAndAngles(p.getX() - 3.0, p.getY(), p.getZ() + 3.0, 180, 0);
			w.spawnEntity(sk);
			skRef[0] = sk;

			// Stranger of higher rank (> credLevel) must NOT respect the player
			int cred = Reputation.credLevel(p.getUuid());
			MobEntity stranger = (cred < 4 ? EntityType.CREEPER : EntityType.GIANT).create(w, SpawnReason.COMMAND);
			stranger.refreshPositionAndAngles(p.getX() + 8.0, p.getY(), p.getZ() + 8.0, 180, 0);
			w.spawnEntity(stranger);
			strangerRef[0] = stranger instanceof CreeperEntity ce ? ce : null;

			Crowd.expireTruceForTest();
			sk.setTarget(p);
			witnessRef[0].setTarget(p);
			stranger.setTarget(p);
			GameTestSupport.assertTrue(sk.getTarget() == null, "Skeleton <= cred rank must not target player");
			GameTestSupport.assertTrue(witnessRef[0].getTarget() == null, "Witness creeper must not target player");
			GameTestSupport.assertTrue(!Reputation.respects(stranger, p), "Higher-rank stranger must not respect player");
			GameTestSupport.assertTrue(stranger.getTarget() == p, "Higher-rank stranger must target player (cred=" + cred + ")");
			if (strangerRef[0] == null) stranger.discard();
		});
		ctx.waitTicks(22);
		GameTestSupport.takeScreenshot(ctx, "reputation-nod-actionbar");

		sp.getServer().runOnServer(server -> {
			ServerPlayerEntity p = server.getPlayerManager().getPlayerList().get(0);
			Reputation.onPlayerHostileAction(p, skRef[0]);
			GameTestSupport.assertTrue(!PlayerStatus.has(p.getUuid(), PlayerStatus.STREET_CRED), "Hostile action must revoke Street Cred");
			skRef[0].setTarget(p);
			GameTestSupport.assertTrue(skRef[0].getTarget() == p, "Skeleton must target player after cred loss");

			GameTestSupport.cleanupBattles(server);
			for (var e : java.util.Arrays.asList(zRef[0], skRef[0], witnessRef[0], strangerRef[0])) {
				if (e != null && !e.isRemoved()) e.discard();
			}
			Reputation.clear();
		});
		ctx.waitTicks(5);
	}

	private void testRunAfterLoss(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		ZombieEntity[] zRef = new ZombieEntity[1];
		sp.getServer().runOnServer(server -> {
			ServerPlayerEntity p = server.getPlayerManager().getPlayerList().get(0);
			ServerWorld w = p.getEntityWorld();
			ZombieEntity z = EntityType.ZOMBIE.create(w, SpawnReason.COMMAND);
			z.refreshPositionAndAngles(p.getX(), p.getY(), p.getZ() + 4.0, 180, 0);
			w.spawnEntity(z);
			zRef[0] = z;

			Battle b = BattleManager.start(p, z);
			b.startEarly(p);
			b.forceOutcomeForTest(false, true, 0, 90);
		});
		ctx.waitTicks(5);
		sp.getServer().runOnServer(server -> {
			ServerPlayerEntity p = server.getPlayerManager().getPlayerList().get(0);
			GameTestSupport.assertTrue(PlayerStatus.has(p.getUuid(), PlayerStatus.RUN_AWAY), "Must have RUN_AWAY after loss");
			GameTestSupport.assertTrue(p.hasStatusEffect(StatusEffects.SPEED), "Must have Speed I after loss");
			GameTestSupport.assertTrue(Crowd.inTruce(zRef[0]), "Winning mob must be in 10s truce");
			zRef[0].setTarget(p);
			GameTestSupport.assertTrue(zRef[0].getTarget() == null, "Winning mob must not attack during Run! truce");
		});
		ctx.waitTicks(18);
		GameTestSupport.takeScreenshot(ctx, "reputation-run-laugh");

		sp.getServer().runOnServer(server -> {
			ServerPlayerEntity p = server.getPlayerManager().getPlayerList().get(0);
			Crowd.expireTruceForTest();
			zRef[0].setTarget(p);
			GameTestSupport.assertTrue(zRef[0].getTarget() == p, "Mob must be able to attack after truce expires");

			GameTestSupport.cleanupBattles(server);
			if (zRef[0] != null && !zRef[0].isRemoved()) zRef[0].discard();
			p.clearStatusEffects();
			Reputation.clear();
		});
		ctx.waitTicks(5);
	}

	private void testPiglinTwoTiers(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		PiglinEntity[] pgRef = new PiglinEntity[1];
		PiglinBruteEntity[] brRef = new PiglinBruteEntity[1];
		sp.getServer().runOnServer(server -> {
			ServerPlayerEntity p = server.getPlayerManager().getPlayerList().get(0);
			ServerWorld w = p.getEntityWorld();

			PiglinEntity pg = EntityType.PIGLIN.create(w, SpawnReason.COMMAND);
			pg.setImmuneToZombification(true);
			pg.refreshPositionAndAngles(p.getX(), p.getY(), p.getZ() + 4.0, 180, 0);
			w.spawnEntity(pg);
			pgRef[0] = pg;

			Battle b = BattleManager.start(p, pg);
			b.startEarly(p);
			b.forceOutcomeForTest(true, true);
			GameTestSupport.assertTrue(PlayerStatus.has(p.getUuid(), PlayerStatus.PIGLIN_PAL), "Must gain PIGLIN_PAL after beating piglin");

			PiglinBruteEntity br = EntityType.PIGLIN_BRUTE.create(w, SpawnReason.COMMAND);
			br.setImmuneToZombification(true);
			br.refreshPositionAndAngles(p.getX() + 4.0, p.getY(), p.getZ() + 4.0, 180, 0);
			w.spawnEntity(br);
			brRef[0] = br;

			Battle b2 = BattleManager.start(p, br);
			b2.startEarly(p);
			b2.forceOutcomeForTest(true, true);
			GameTestSupport.assertTrue(PlayerStatus.has(p.getUuid(), PlayerStatus.BASTION_GUEST), "Must gain BASTION_GUEST after beating brute");

			Reputation.onPlayerHostileAction(p, pgRef[0]);
			GameTestSupport.assertTrue(!PlayerStatus.has(p.getUuid(), PlayerStatus.PIGLIN_PAL), "Hitting piglin must revoke PIGLIN_PAL");
			GameTestSupport.assertTrue(!PlayerStatus.has(p.getUuid(), PlayerStatus.BASTION_GUEST), "Hitting piglin must revoke BASTION_GUEST");

			GameTestSupport.cleanupBattles(server);
			if (pgRef[0] != null && !pgRef[0].isRemoved()) pgRef[0].discard();
			if (brRef[0] != null && !brRef[0].isRemoved()) brRef[0].discard();
			Reputation.clear();
		});
		ctx.waitTicks(5);
	}

	private void testTheftAllThievesAndEdgeCases(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		sp.getServer().runCommand("time set 6000");
		// Forbidden items must never be stolen: sword, GuideBook, trophy mic
		sp.getServer().runOnServer(server -> {
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
			b.startEarly(p);
			b.forceOutcomeForTest(false, true, 0, 90);
			GameTestSupport.assertTrue(!Theft.hasStolen(z), "Forbidden items must never be stolen");
			z.discard();
			GameTestSupport.cleanupBattles(server);
		});

		// 5 required thieves: Zombie (minor), Witch (valuable), Spider (rematch return), Sheep, Chicken
		runThiefCase(ctx, sp, EntityType.ZOMBIE, Items.STRING, Items.EMERALD, false, null);
		runThiefCase(ctx, sp, EntityType.WITCH, Items.EMERALD, Items.BREAD, false, null);
		runThiefCase(ctx, sp, EntityType.SPIDER, Items.BONE, null, true, "theft-spider");
		runThiefCase(ctx, sp, EntityType.SHEEP, Items.WHEAT, Items.EMERALD, false, "theft-sheep");
		runThiefCase(ctx, sp, EntityType.CHICKEN, Items.WHEAT_SEEDS, Items.EMERALD, false, "theft-chicken");

		// Creeper thief explodes -> stolen item drops
		sp.getServer().runOnServer(server -> {
			ServerPlayerEntity p = server.getPlayerManager().getPlayerList().get(0);
			ServerWorld w = p.getEntityWorld();
			p.getInventory().clear();
			p.getInventory().setStack(0, new ItemStack(Items.EMERALD, 1));
			CreeperEntity cr = EntityType.CREEPER.create(w, SpawnReason.COMMAND);
			cr.refreshPositionAndAngles(p.getX(), p.getY(), p.getZ() + 4.0, 180, 0);
			w.spawnEntity(cr);
			Battle b = BattleManager.start(p, cr);
			b.startEarly(p);
			b.forceOutcomeForTest(false, true, 0, 90);
			GameTestSupport.assertTrue(Theft.hasStolen(cr), "Creeper must steal emerald");
			Theft.dropOnDeathOrExplode(cr);
			cr.discard();
			List<ItemEntity> drops = w.getEntitiesByClass(ItemEntity.class, new Box(cr.getBlockPos()).expand(3), e -> e.getStack().isOf(Items.EMERALD));
			GameTestSupport.assertTrue(!drops.isEmpty(), "Stolen item must drop when creeper explodes");
			for (ItemEntity it : drops) it.discard();
			GameTestSupport.cleanupBattles(server);
		});

		// Piglin thief conversion -> stolen item transfers to ZombifiedPiglinEntity
		sp.getServer().runOnServer(server -> {
			ServerPlayerEntity p = server.getPlayerManager().getPlayerList().get(0);
			ServerWorld w = p.getEntityWorld();
			p.getInventory().clear();
			p.getInventory().setStack(0, new ItemStack(Items.GOLD_NUGGET, 1));
			PiglinEntity pg = EntityType.PIGLIN.create(w, SpawnReason.COMMAND);
			pg.refreshPositionAndAngles(p.getX(), p.getY(), p.getZ() + 4.0, 180, 0);
			w.spawnEntity(pg);
			Battle b = BattleManager.start(p, pg);
			b.startEarly(p);
			b.forceOutcomeForTest(false, true, 0, 90);
			GameTestSupport.assertTrue(Theft.hasStolen(pg), "Piglin must steal gold nugget");
			ZombifiedPiglinEntity zp = EntityType.ZOMBIFIED_PIGLIN.create(w, SpawnReason.CONVERSION);
			zp.refreshPositionAndAngles(pg.getX(), pg.getY(), pg.getZ(), 0, 0);
			w.spawnEntity(zp);
			Theft.transferOnConversion(pg, zp);
			pg.discard();
			GameTestSupport.assertTrue(Theft.hasStolen(zp) && zp.isPersistent(), "Converted zombified piglin must keep stolen item");
			zp.discard();
			GameTestSupport.cleanupBattles(server);
			Reputation.clear();
		});
	}

	private <T extends MobEntity> void runThiefCase(ClientGameTestContext ctx, TestSingleplayerContext sp,
													EntityType<T> type, Item expectedSteal, Item untouchedItem,
													boolean returnViaRematch, String shotName) {
		MobEntity[] ref = new MobEntity[1];
		sp.getServer().runOnServer(server -> {
			ServerPlayerEntity p = server.getPlayerManager().getPlayerList().get(0);
			ServerWorld w = p.getEntityWorld();
			p.getInventory().clear();
			p.getInventory().setStack(0, new ItemStack(expectedSteal, 1));
			if (untouchedItem != null) p.getInventory().setStack(1, new ItemStack(untouchedItem, 1));

			MobEntity m = type.create(w, SpawnReason.COMMAND);
			m.refreshPositionAndAngles(p.getX(), p.getY(), p.getZ() + 3.5, 180, 0);
			w.spawnEntity(m);
			ref[0] = m;

			Battle b = BattleManager.start(p, m);
			b.startEarly(p);
			b.forceOutcomeForTest(false, true, 0, 90);
			GameTestSupport.assertTrue(Theft.hasStolen(m) && Theft.getStolen(m).isOf(expectedSteal),
				type.getName().getString() + " must steal " + expectedSteal);
			GameTestSupport.assertTrue(m.isPersistent(), "Thief must be persistent");
			GameTestSupport.assertTrue(p.getInventory().count(expectedSteal) == 0, "Stolen item must be gone from player");
			if (untouchedItem != null) {
				GameTestSupport.assertTrue(p.getInventory().count(untouchedItem) == 1, "Untouched item must remain in player inventory");
			}
		});
		if (shotName != null) {
			ctx.waitTicks(3);
			GameTestSupport.takeScreenshot(ctx, shotName);
		}
		sp.getServer().runOnServer(server -> {
			ServerPlayerEntity p = server.getPlayerManager().getPlayerList().get(0);
			ServerWorld w = p.getEntityWorld();
			MobEntity m = ref[0];
			if (returnViaRematch) {
				Crowd.expireTruceForTest();
				Battle rematch = BattleManager.start(p, m);
				rematch.startEarly(p);
				rematch.forceOutcomeForTest(true, true);
				GameTestSupport.assertTrue(!Theft.hasStolen(m), "Rematch win must clear stolen item from thief");
				GameTestSupport.assertTrue(p.getInventory().count(expectedSteal) == 1, "Rematch win must return stolen item to player");
			} else {
				m.kill(w);
				List<ItemEntity> drops = w.getEntitiesByClass(ItemEntity.class, new Box(m.getBlockPos()).expand(3),
					e -> e.getStack().isOf(expectedSteal));
				int total = drops.stream().mapToInt(it -> it.getStack().getCount()).sum();
				GameTestSupport.assertTrue(total == 1, "Killing thief must drop stolen item exactly once (got " + total + ")");
				for (ItemEntity it : drops) it.discard();
			}
			if (m != null && !m.isRemoved()) m.discard();
			GameTestSupport.cleanupBattles(server);
		});
		ctx.waitTicks(3);
	}
}
