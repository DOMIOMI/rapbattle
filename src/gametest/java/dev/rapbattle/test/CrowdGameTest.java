package dev.rapbattle.test;

import dev.rapbattle.battle.Battle;
import dev.rapbattle.battle.BattleManager;
import dev.rapbattle.client.ClientBattle;
import dev.rapbattle.client.Intro;
import dev.rapbattle.mixin.AbstractPiglinAccessor;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.mob.CreeperEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.PiglinEntity;
import net.minecraft.entity.mob.WitchEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Client gametest for 6.15: stable crowd ring, witch dance, creeper defuse, piglin zombification immunity, latecomers. */
public class CrowdGameTest implements FabricClientGameTest {
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
			sp.getServer().runCommand("time set 14500");
			sp.getServer().runCommand("gamerule doMobSpawning false");
			sp.getServer().runCommand("gamerule doDaylightCycle false");

			List<MobEntity> spawned = new ArrayList<>();
			WitchEntity[] rivalWitch = new WitchEntity[1];
			PiglinEntity[] crowdPiglin = new PiglinEntity[1];
			CreeperEntity[] lateCreeper = new CreeperEntity[1];
			Battle[] battle = new Battle[1];

			sp.getServer().runOnServer(server -> {
				ServerPlayerEntity p = server.getPlayerManager().getPlayerList().get(0);
				ServerWorld w = p.getEntityWorld();

				WitchEntity star = EntityType.WITCH.create(w, SpawnReason.COMMAND);
				star.refreshPositionAndAngles(p.getX(), p.getY(), p.getZ() + 6.0, 180, 0);
				w.spawnEntity(star);
				rivalWitch[0] = star;
				spawned.add(star);

				EntityType<?>[] types = {
					EntityType.WITCH, EntityType.ZOMBIE, EntityType.VILLAGER,
					EntityType.SKELETON, EntityType.PIG, EntityType.CREEPER
				};
				for (int i = 0; i < types.length; i++) {
					double a = 2 * Math.PI * i / types.length;
					MobEntity m = (MobEntity) types[i].create(w, SpawnReason.COMMAND);
					m.refreshPositionAndAngles(p.getX() + Math.cos(a) * 6.0, p.getY(), p.getZ() + 3.0 + Math.sin(a) * 6.0, 0, 0);
					w.spawnEntity(m);
					spawned.add(m);
				}

				PiglinEntity piglin = EntityType.PIGLIN.create(w, SpawnReason.COMMAND);
				piglin.refreshPositionAndAngles(p.getX() - 5.0, p.getY(), p.getZ() + 3.0, 0, 0);
				w.spawnEntity(piglin);
				crowdPiglin[0] = piglin;
				spawned.add(piglin);

				battle[0] = BattleManager.start(p, star);
				battle[0].startEarly(p);
			});

			ctx.waitFor(c -> phase("TIMELINE"), 800);
			Intro.skip();
			ctx.waitFor(c -> ClientBattle.timeline() && ClientBattle.beat() >= 4, 800);
			// Screenshot of the witch rival reading + crowd witch & other spectators around the stage.
			GameTestSupport.takeScreenshot(ctx, "crowd-witch-reading");

			// Wait until crowd has settled (> 105 ticks) and verify horizontal stability for 100 ticks (5 seconds).
			ctx.waitTicks(110);
			Map<MobEntity, Vec3d> baseline = new HashMap<>();
			sp.getServer().runOnServer(server -> {
				for (MobEntity m : spawned) {
					if (m != rivalWitch[0] && BattleManager.inAnyCrowd(m)) {
						baseline.put(m, new Vec3d(m.getX(), m.getY(), m.getZ()));
					}
				}
				GameTestSupport.assertTrue(baseline.size() >= 6, "Expected at least 6 placed crowd spectators");
			});

			for (int t = 0; t < 100; t++) {
				ctx.waitTicks(1);
				sp.getServer().runOnServer(server -> {
					for (var e : baseline.entrySet()) {
						MobEntity m = e.getKey();
						Vec3d pos = e.getValue();
						double dx = m.getX() - pos.x;
						double dz = m.getZ() - pos.z;
						double dist = Math.hypot(dx, dz);
						GameTestSupport.assertTrue(dist <= 0.1,
							"Crowd mob " + m.getType().getName().getString() + " jittered by " + dist + " blocks");
					}
				});
			}
			GameTestSupport.takeScreenshot(ctx, "crowd-ring-stable");

			// Spawn a latecomer creeper and ignite it near the stage; it must join the crowd and NOT explode.
			sp.getServer().runOnServer(server -> {
				ServerPlayerEntity p = server.getPlayerManager().getPlayerList().get(0);
				ServerWorld w = p.getEntityWorld();
				CreeperEntity creeper = EntityType.CREEPER.create(w, SpawnReason.COMMAND);
				creeper.refreshPositionAndAngles(p.getX() + 7.0, p.getY(), p.getZ() + 3.0, 0, 0);
				w.spawnEntity(creeper);
				creeper.ignite();
				lateCreeper[0] = creeper;
				spawned.add(creeper);
			});

			// Wait 60 ticks (> 30-tick creeper fuse) to verify latecomer joins crowd and does not explode.
			ctx.waitTicks(60);
			sp.getServer().runOnServer(server -> {
				CreeperEntity c = lateCreeper[0];
				GameTestSupport.assertTrue(c.isAlive() && !c.isRemoved(), "Latecomer ignited creeper must not explode");
				GameTestSupport.assertTrue(BattleManager.inAnyCrowd(c), "Latecomer creeper must join the crowd");
				GameTestSupport.assertTrue(!c.isIgnited(), "Ignited creeper in crowd must be defused");

				PiglinEntity pg = crowdPiglin[0];
				GameTestSupport.assertTrue(pg.isAlive() && ((AbstractPiglinAccessor) pg).rapbattle$isImmuneToZombification(),
					"Crowd piglin must be immune to zombification during battle");

				battle[0].forceOutcomeForTest(true, true);
				GameTestSupport.assertTrue(!((AbstractPiglinAccessor) pg).rapbattle$isImmuneToZombification(),
					"Piglin zombification immunity must be restored after battle");

				GameTestSupport.cleanupBattles(server);
				for (MobEntity m : spawned) {
					if (m != null && !m.isRemoved()) m.discard();
				}
			});
			ctx.waitTicks(5);
			GameTestSupport.assertNoRawKeys("CrowdGameTest");
		}
	}
}
