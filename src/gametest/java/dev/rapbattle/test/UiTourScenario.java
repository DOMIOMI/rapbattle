package dev.rapbattle.test;

import dev.rapbattle.RapConfig;
import dev.rapbattle.battle.Battle;
import dev.rapbattle.battle.BattleManager;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;

import java.util.ArrayList;
import java.util.List;

/**
 * 6.30 UI tour: one ordinary battle against a zombie with a small crowd, screenshot at every phase
 * (intro card, lobby, mob verse, player turn, judging, result) for a visual review of the battle HUD.
 * It asserts nothing about gameplay; it only fails if a phase cannot be reached.
 */
public final class UiTourScenario {
	private UiTourScenario() {}

	public static void run(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		// Earlier groups can leave the player on the death screen.
		ctx.runOnClient(mc -> {
			if (mc.player != null && mc.player.isDead()) mc.player.requestRespawn();
		});
		ctx.waitFor(mc -> mc.player != null && !mc.player.isDead(), 200);
		ctx.setScreen(() -> null);
		ctx.waitTicks(40); // let the chunks around the respawn point render
		List<MobEntity> spawned = new ArrayList<>();
		Battle[] battle = new Battle[1];
		sp.getServer().runOnServer(server -> {
			ServerPlayerEntity p = server.getPlayerManager().getPlayerList().get(0);
			ServerWorld w = p.getEntityWorld();
			w.setTimeOfDay(1000);
			RapConfig.get().progression = "soft";
			p.networkHandler.requestTeleport(p.getX(), p.getY(), p.getZ(), 0f, 10f);
			// Nothing may kill the player mid-tour (a death screen would cover the HUD we want to see).
			p.getAbilities().invulnerable = true;
			p.sendAbilitiesUpdate();
			p.setHealth(p.getMaxHealth());
			for (MobEntity old : w.getEntitiesByClass(MobEntity.class, p.getBoundingBox().expand(48), e -> true)) old.discard();
			MobEntity z = EntityType.ZOMBIE.create(w, SpawnReason.COMMAND);
			for (net.minecraft.entity.EquipmentSlot s : net.minecraft.entity.EquipmentSlot.values()) z.equipStack(s, net.minecraft.item.ItemStack.EMPTY);
			z.refreshPositionAndAngles(p.getX(), p.getY(), p.getZ() + 4, 180, 0);
			w.spawnEntity(z);
			spawned.add(z);
			EntityType<?>[] crowd = {EntityType.SHEEP, EntityType.COW, EntityType.PIG, EntityType.CHICKEN, EntityType.SKELETON};
			for (int i = 0; i < crowd.length; i++) {
				MobEntity m = (MobEntity) crowd[i].create(w, SpawnReason.COMMAND);
				m.refreshPositionAndAngles(p.getX() - 6 + i * 3, p.getY(), p.getZ() + 8, 180, 0);
				w.spawnEntity(m);
				spawned.add(m);
			}
			battle[0] = BattleManager.start(p, z);
		});
		GameTestSupport.assertTrue(battle[0] != null, "UI tour battle must start");

		ctx.waitTicks(12);
		GameTestSupport.takeScreenshot(ctx, "6.30-ui-1-intro");
		ctx.waitTicks(80);
		GameTestSupport.takeScreenshot(ctx, "6.30-ui-2-lobby");

		sp.getServer().runOnServer(server -> battle[0].jumpToForTest(Battle.Phase.TIMELINE));
		String[] names = {"6.30-ui-3-timeline-a", "6.30-ui-3-timeline-b", "6.30-ui-3-timeline-c", "6.30-ui-3-timeline-d", "6.30-ui-3-timeline-e"};
		for (String n : names) {
			ctx.waitTicks(100);
			GameTestSupport.takeScreenshot(ctx, n);
		}

		sp.getServer().runOnServer(server -> battle[0].jumpToForTest(Battle.Phase.JUDGING));
		ctx.waitTicks(15);
		GameTestSupport.takeScreenshot(ctx, "6.30-ui-4-judging");

		sp.getServer().runOnServer(server -> {
			battle[0].enterResultForTest(true, 64, 41);
			battle[0].setResultCommentForTest("Рифмы ровные, подача уверенная — зомби не вывез.");
			battle[0].broadcastStateForTest();
		});
		ctx.waitTicks(40);
		GameTestSupport.takeScreenshot(ctx, "6.30-ui-5-result");
		ctx.waitTicks(80);
		GameTestSupport.takeScreenshot(ctx, "6.30-ui-6-result-late");

		// 6.32: Space on the result screen closes it (solo: one press is everyone's vote).
		sp.getServer().runOnServer(server -> {
			ServerPlayerEntity p = server.getPlayerManager().getPlayerList().get(0);
			GameTestSupport.assertTrue(BattleManager.byPlayer(p.getUuid()) == battle[0], "Result screen must still be up before Space");
			battle[0].skipResult(p);
		});
		ctx.waitTicks(5);
		sp.getServer().runOnServer(server -> {
			ServerPlayerEntity p = server.getPlayerManager().getPlayerList().get(0);
			GameTestSupport.assertTrue(BattleManager.byPlayer(p.getUuid()) == null, "Space on the result screen must end the battle");
		});

		sp.getServer().runOnServer(server -> {
			GameTestSupport.cleanupBattles(server);
			ServerPlayerEntity p = server.getPlayerManager().getPlayerList().get(0);
			p.getAbilities().invulnerable = false;
			p.sendAbilitiesUpdate();
			for (MobEntity m : spawned) if (m.isAlive()) m.discard();
		});
		ctx.waitTicks(5);
		sheepMicStand(ctx, sp);
	}

	/** 6.33: an animal's mic stand stands on the ground while it reads (it used to hang from its mouth). */
	private static void sheepMicStand(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		Battle[] battle = new Battle[1];
		MobEntity[] sheep = new MobEntity[1];
		sp.getServer().runOnServer(server -> {
			ServerPlayerEntity p = server.getPlayerManager().getPlayerList().get(0);
			ServerWorld w = p.getEntityWorld();
			MobEntity m = (MobEntity) EntityType.SHEEP.create(w, SpawnReason.COMMAND);
			m.refreshPositionAndAngles(p.getX() + 3, p.getY(), p.getZ() + 3, 90, 0);
			m.setAiDisabled(true);
			w.spawnEntity(m);
			sheep[0] = m;
			battle[0] = BattleManager.start(p, m);
		});
		GameTestSupport.assertTrue(battle[0] != null, "Sheep battle must start");
		sp.getServer().runOnServer(server -> battle[0].jumpToForTest(Battle.Phase.TIMELINE));
		for (int i = 0; i < 6; i++) {
			ctx.waitTicks(60);
			GameTestSupport.takeScreenshot(ctx, "6.33-sheep-stand-" + i);
		}
		sp.getServer().runOnServer(server -> {
			GameTestSupport.cleanupBattles(server);
			if (sheep[0].isAlive()) sheep[0].discard();
		});
		ctx.waitTicks(5);
	}
}
