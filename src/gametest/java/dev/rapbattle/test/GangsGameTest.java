package dev.rapbattle.test;

import dev.rapbattle.Lang;
import dev.rapbattle.battle.*;
import dev.rapbattle.client.ClientBattle;
import dev.rapbattle.client.Intro;
import dev.rapbattle.net.BattleView;
import dev.rapbattle.net.Payloads;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.mob.SkeletonEntity;
import net.minecraft.entity.mob.ZombieEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.GameMode;

import java.util.Random;

import static dev.rapbattle.test.GameTestSupport.assertTrue;

/** Client gametests for gang wars, champions, rematches, and daytime stage sun safety. */
public class GangsGameTest implements FabricClientGameTest {
	private static boolean phase(String p) {
		return ClientBattle.view != null && p.equals(ClientBattle.view.phase);
	}

	@Override
	public void runTest(ClientGameTestContext ctx) {
		GameTestSupport.installKeyAudit();
		ctx.getInput().resizeWindow(1280, 720);
		ctx.waitTicks(5);
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			sp.getClientWorld().waitForChunksRender();
			GameTestSupport.applyLang(ctx);
			sp.getServer().runCommand("time set 6000");

			testGangRivalry(ctx, sp);
			testChampionSunAndCooldown(ctx, sp);
			testRematchCallout(ctx, sp);
			testDaytimeSunSafety(ctx, sp);

			GameTestSupport.assertNoRawKeys("GangsGameTest");
		}
	}

	private void testGangRivalry(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		ZombieEntity[] zRef = new ZombieEntity[1];
		Rank[] baseRank = new Rank[1];
		Battle[] battle = new Battle[1];
		sp.getServer().runOnServer(server -> {
			ServerPlayerEntity p = server.getPlayerManager().getPlayerList().get(0);
			ServerWorld w = p.getEntityWorld();
			p.changeGameMode(GameMode.SURVIVAL);
			p.setInvulnerable(true);
			p.getInventory().clear();
			BattleManager.clearPendingForTest(p.getUuid());

			ZombieEntity z = EntityType.ZOMBIE.create(w, SpawnReason.COMMAND);
			z.refreshPositionAndAngles(p.getX(), p.getY(), p.getZ() + 9.0, 180, 0);
			z.setAiDisabled(true);
			z.setTarget(p);
			w.spawnEntity(z);
			zRef[0] = z;
			baseRank[0] = Opponent.of(z, new Random(0), p, Lang.of(p)).rank();
		});
		ctx.waitTicks(25);
		sp.getServer().runOnServer(server -> {
			ServerPlayerEntity p = server.getPlayerManager().getPlayerList().get(0);
			assertTrue(!BattleManager.hasPendingForTest(p.getUuid()), "Zombie at 9 blocks must NOT challenge without rival gear");

			Gangs.Gang skelGang = Gangs.of(MobProfiles.of(EntityType.SKELETON));
			assertTrue(skelGang != null, "Skeleton gang must exist");
			assertTrue("skeleton".equals(Gangs.rivalOf(MobProfiles.of(EntityType.ZOMBIE).kin())), "Skeleton must be zombie's rival");
			p.equipStack(EquipmentSlot.HEAD, Gangs.piece(skelGang, 0));
			p.equipStack(EquipmentSlot.CHEST, Gangs.piece(skelGang, 1));
			p.equipStack(EquipmentSlot.LEGS, new ItemStack(Items.NETHERITE_LEGGINGS));
			p.equipStack(EquipmentSlot.FEET, new ItemStack(Items.NETHERITE_BOOTS));
			zRef[0].refreshPositionAndAngles(p.getX(), p.getY(), p.getZ() + 9.0, 180, 0);
			zRef[0].setTarget(p);
		});
		ctx.waitTicks(25);
		sp.getServer().runOnServer(server -> {
			ServerPlayerEntity p = server.getPlayerManager().getPlayerList().get(0);
			assertTrue(BattleManager.hasPendingForTest(p.getUuid()), "Zombie at 9 blocks MUST challenge at 1.5x range with rival gear");
			BattleManager.clearPendingForTest(p.getUuid());

			battle[0] = BattleManager.start(p, zRef[0]);
			int expectedLevel = Math.min(5, baseRank[0].level() + 1);
			assertTrue(battle[0].rank.level() == expectedLevel,
				"Rival battle rank must step up +1 (capped at 5): expected " + expectedLevel + ", got " + battle[0].rank.level());
			battle[0].startEarly(p);
		});
		ctx.waitFor(c -> phase("TIMELINE"), 800);
		ctx.waitFor(c -> !Intro.active(), 400);
		ctx.waitFor(c -> ClientBattle.timeline() && ClientBattle.beat() >= ClientBattle.view.mobEndBeat + 2, 1600);
		sp.getServer().runOnServer(server -> battle[0].onAudio(
			server.getPlayerManager().getPlayerList().get(0),
			new Payloads.AudioC2S(battle[0].id, 0, true, 0, GameTestSupport.wav())));
		ctx.waitFor(c -> phase("RESULT"), 3600);
		ctx.waitTicks(20);
		boolean[] hasRivalNote = new boolean[1];
		ctx.runOnClient(c -> {
			if (ClientBattle.view != null && ClientBattle.view.result != null && ClientBattle.view.result.hits != null) {
				for (BattleView.Hit h : ClientBattle.view.result.hits) {
					String low = h.note == null ? "" : h.note.toLowerCase();
					if (low.contains("вражд") || low.contains("rival")) {
						hasRivalNote[0] = true;
						break;
					}
				}
			}
		});
		assertTrue(hasRivalNote[0], "Breakdown must contain gang rivalry line");
		ctx.takeScreenshot("gangs-rival-breakdown");
		ctx.waitFor(c -> ClientBattle.view == null, 900);
		sp.getServer().runOnServer(server -> {
			GameTestSupport.cleanupBattles(server);
			if (zRef[0] != null && !zRef[0].isRemoved()) zRef[0].discard();
			server.getPlayerManager().getPlayerList().get(0).getInventory().clear();
		});
		ctx.waitTicks(5);
	}

	private void testChampionSunAndCooldown(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		ZombieEntity[] champRef = new ZombieEntity[1];
		sp.getServer().runCommand("time set 6000");
		sp.getServer().runOnServer(server -> {
			ServerPlayerEntity p = server.getPlayerManager().getPlayerList().get(0);
			ServerWorld w = p.getEntityWorld();
			p.changeGameMode(GameMode.SURVIVAL);
			p.setInvulnerable(true);
			Stats.of(server).player(p.getUuid()).streak = 0;

			ZombieEntity z = EntityType.ZOMBIE.create(w, SpawnReason.COMMAND);
			z.refreshPositionAndAngles(p.getX(), p.getY(), p.getZ() + 5.0, 180, 0);
			MobRecord.win(z);
			w.spawnEntity(z);
			champRef[0] = z;
		});
		ctx.waitTicks(120);
		sp.getServer().runOnServer(server -> {
			ZombieEntity z = champRef[0];
			assertTrue(z != null && z.isAlive() && !z.isOnFire(), "Champion zombie must not burn in daytime sun");
			ServerPlayerEntity p = server.getPlayerManager().getPlayerList().get(0);
			Stats.of(server).player(p.getUuid()).streak = 3;
		});
		ctx.waitTicks(25);
		sp.getServer().runOnServer(server -> {
			ServerPlayerEntity p = server.getPlayerManager().getPlayerList().get(0);
			assertTrue(BattleManager.hasPendingForTest(p.getUuid()), "Champion must call out player on 3+ win streak");
		});
		ctx.waitTicks(5);
		ctx.takeScreenshot("gangs-champion-callout");
		sp.getServer().runOnServer(server -> {
			ServerPlayerEntity p = server.getPlayerManager().getPlayerList().get(0);
			Battle b = BattleManager.acceptPendingForTest(p);
			assertTrue(b != null, "Accepting champion callout must start battle");
			b.forceOutcomeForTest(false, true);
			Stats.of(server).player(p.getUuid()).streak = 3;
			BattleManager.advanceCooldownsForTest(60_000L);
		});
		ctx.waitTicks(25);
		sp.getServer().runOnServer(server -> {
			ServerPlayerEntity p = server.getPlayerManager().getPlayerList().get(0);
			assertTrue(!BattleManager.hasPendingForTest(p.getUuid()), "Champion must NOT re-challenge before 5-minute cooldown");
			BattleManager.advanceCooldownsForTest(5 * 60_000L);
		});
		ctx.waitTicks(25);
		sp.getServer().runOnServer(server -> {
			ServerPlayerEntity p = server.getPlayerManager().getPlayerList().get(0);
			assertTrue(BattleManager.hasPendingForTest(p.getUuid()), "Champion MUST re-challenge after 5-minute cooldown");
			GameTestSupport.cleanupBattles(server);
			if (champRef[0] != null && !champRef[0].isRemoved()) champRef[0].discard();
			Stats.of(server).player(p.getUuid()).streak = 0;
		});
		ctx.waitTicks(5);
	}

	private void testRematchCallout(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		ZombieEntity[] rivalRef = new ZombieEntity[1];
		sp.getServer().runOnServer(server -> {
			ServerPlayerEntity p = server.getPlayerManager().getPlayerList().get(0);
			ServerWorld w = p.getEntityWorld();
			p.changeGameMode(GameMode.SURVIVAL);
			p.setInvulnerable(true);

			ZombieEntity z = EntityType.ZOMBIE.create(w, SpawnReason.COMMAND);
			z.refreshPositionAndAngles(p.getX(), p.getY(), p.getZ() + 4.0, 180, 0);
			w.spawnEntity(z);
			rivalRef[0] = z;

			Battle b = BattleManager.start(p, z);
			// Close win: margin 90 - 75 = 15 (< 25) and non-KO -> mob flees & remembers player.
			b.forceOutcomeForTest(true, false, 90, 75);
			assertTrue(z.isAlive(), "Mob must survive and flee on close win");
			assertTrue(Crowd.inTruce(z), "Fleeing mob must enter truce");
			MobRecord.Rival rv = MobRecord.rival(z, p.getName().getString());
			assertTrue(rv != null && !rv.mobWon(), "Mob must remember losing to the player");
			Crowd.expireTruceForTest();
			z.refreshPositionAndAngles(p.getX(), p.getY(), p.getZ() + 6.0, 180, 0);
		});
		ctx.waitTicks(25);
		sp.getServer().runOnServer(server -> {
			ServerPlayerEntity p = server.getPlayerManager().getPlayerList().get(0);
			assertTrue(BattleManager.hasPendingForTest(p.getUuid()), "Remembered mob within 16 blocks must call player out for rematch");
		});
		ctx.waitTicks(5);
		ctx.takeScreenshot("gangs-rematch-callout");
		sp.getServer().runOnServer(server -> {
			GameTestSupport.cleanupBattles(server);
			if (rivalRef[0] != null && !rivalRef[0].isRemoved()) rivalRef[0].discard();
		});
		ctx.waitTicks(5);
	}

	private void testDaytimeSunSafety(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		Entity[] refs = new Entity[2];
		sp.getServer().runCommand("time set 6000");
		sp.getServer().runOnServer(server -> {
			ServerPlayerEntity p = server.getPlayerManager().getPlayerList().get(0);
			ServerWorld w = p.getEntityWorld();
			ZombieEntity z = EntityType.ZOMBIE.create(w, SpawnReason.COMMAND);
			z.refreshPositionAndAngles(p.getX(), p.getY(), p.getZ() + 4.0, 180, 0);
			w.spawnEntity(z);
			SkeletonEntity sk = EntityType.SKELETON.create(w, SpawnReason.COMMAND);
			sk.refreshPositionAndAngles(p.getX() + 5.0, p.getY(), p.getZ() + 3.0, 180, 0);
			w.spawnEntity(sk);
			refs[0] = z;
			refs[1] = sk;
			Battle b = BattleManager.start(p, z);
			b.startEarly(p);
		});
		ctx.waitTicks(100);
		sp.getServer().runOnServer(server -> {
			ZombieEntity z = (ZombieEntity) refs[0];
			SkeletonEntity sk = (SkeletonEntity) refs[1];
			assertTrue(z.isAlive() && !z.isOnFire(), "Battling zombie must not burn in the sun");
			assertTrue(sk.isAlive() && !sk.isOnFire(), "Spectator skeleton must not burn in the sun during battle");
			GameTestSupport.cleanupBattles(server);
			z.discard();
			sk.discard();
		});
		ctx.waitTicks(5);
	}
}
