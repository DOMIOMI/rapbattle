package dev.rapbattle.test;

import dev.rapbattle.battle.Battle;
import dev.rapbattle.battle.BattleManager;
import dev.rapbattle.client.ClientBattle;
import dev.rapbattle.client.Intro;
import dev.rapbattle.net.Payloads;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.option.Perspective;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;

public class BattleClientTest implements FabricClientGameTest {
	private static byte[] wav() {
		try { return java.nio.file.Files.readAllBytes(java.nio.file.Path.of(System.getenv("RB_TEST_WAV"))); }
		catch (java.io.IOException e) { throw new RuntimeException(e); }
	}

	private static boolean forced;

	/** Test only: show the full bling kit on the mob, held every client tick so nothing flickers. */
	private static void snap(ClientGameTestContext ctx, String name) {
		if (!forced) {
			forced = true;
			net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.START_CLIENT_TICK.register(c -> {
				if (ClientBattle.view != null) { ClientBattle.view.rankLevel = 5; ClientBattle.view.gangColor = 0xE03030; }
			});
		}
		ctx.waitTicks(1);
		ctx.takeScreenshot(name);
	}

	/** Test only: a camera in front of the opponent, off to the side. */
	private static void lookAtMob(ClientGameTestContext ctx, String name) {
		ctx.runOnClient(c -> {
			var e = c.world.getEntityById(ClientBattle.view.mobEntityIds.get(0));
			var cam = new net.minecraft.util.math.Vec3d(e.getX() + 2.8, e.getY() + 1.3, e.getZ() - 0.3);
			var look = new net.minecraft.util.math.Vec3d(e.getX(), e.getY() + 1.1, e.getZ());
			var d = look.subtract(cam);
			float yaw = (float) (Math.toDegrees(Math.atan2(d.z, d.x)) - 90);
			float pitch = (float) -Math.toDegrees(Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)));
			Intro.debug = new Intro.Shot(cam, yaw, pitch);
			c.options.hudHidden = true;
		});
		ctx.waitTicks(2);
		ctx.takeScreenshot(name);
		ctx.runOnClient(c -> {
			c.options.hudHidden = false;
			Intro.debug = null;
		});
	}

	/** Test only: a camera in front of the player, off to the side, so the mob does not block the view. */
	private static void lookAtMe(ClientGameTestContext ctx, String name) {
		ctx.runOnClient(c -> {
			var p = c.player;
			var cam = new net.minecraft.util.math.Vec3d(p.getX() + 0.4, p.getY() + 1.3, p.getZ() + 2.6);
			var look = new net.minecraft.util.math.Vec3d(p.getX(), p.getY() + 1.0, p.getZ());
			var d = look.subtract(cam);
			float yaw = (float) (Math.toDegrees(Math.atan2(d.z, d.x)) - 90);
			float pitch = (float) -Math.toDegrees(Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)));
			Intro.debug = new Intro.Shot(cam, yaw, pitch);
			c.options.hudHidden = true;
			c.options.setPerspective(Perspective.THIRD_PERSON_BACK);
		});
		ctx.waitTicks(12);
		ctx.takeScreenshot(name);
		ctx.runOnClient(c -> {
			c.options.hudHidden = false;
			c.options.setPerspective(Perspective.FIRST_PERSON);
			Intro.debug = null;
		});
	}

	private static boolean phase(String p) { return ClientBattle.view != null && p.equals(ClientBattle.view.phase); }

	@Override
	public void runTest(ClientGameTestContext ctx) {
		Battle[] battle = new Battle[1];
		// Settings screens from the title screen (this machine would be the server: keys are editable).
		ctx.runOnClient(c -> c.setScreen(new dev.rapbattle.client.RapSettingsScreen(null)));
		ctx.waitTicks(5);
		ctx.takeScreenshot("settings-main");
		ctx.runOnClient(c -> c.setScreen(new dev.rapbattle.client.KeysScreen(null)));
		ctx.waitTicks(5);
		ctx.takeScreenshot("settings-keys");
		ctx.runOnClient(c -> c.setScreen(new dev.rapbattle.client.CalibrationScreen(null)));
		ctx.waitTicks(5);
		ctx.takeScreenshot("settings-calibration");
		ctx.runOnClient(c -> c.setScreen(null));
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			sp.getClientWorld().waitForChunksRender();
			sp.getServer().runCommand("time set noon");
			sp.getServer().runCommand("gamerule doMobSpawning false");
			sp.getServer().runCommand("gamerule doDaylightCycle false");
			for (String slot : new String[]{"head:iron_helmet", "chest:iron_chestplate", "legs:iron_leggings", "feet:iron_boots"}) {
				String[] a = slot.split(":");
				sp.getServer().runCommand("item replace entity @a armor." + a[0] + " with " + a[1]);
			}
			
			// Settings screens.
			ctx.runOnClient(c -> c.setScreen(new dev.rapbattle.client.RapSettingsScreen(null)));
			ctx.waitTicks(5);
			ctx.takeScreenshot("settings-main");
			ctx.runOnClient(c -> c.setScreen(new dev.rapbattle.client.KeysScreen(null)));
			ctx.waitTicks(5);
			ctx.takeScreenshot("settings-keys");
			ctx.runOnClient(c -> c.setScreen(new dev.rapbattle.client.CalibrationScreen(null)));
			ctx.waitTicks(5);
			ctx.takeScreenshot("settings-calibration");
			ctx.runOnClient(c -> c.setScreen(null));
			ctx.waitTicks(5);
			run(ctx, sp, battle, EntityType.ZOMBIE, "z");
			
		}
	}

	/** Side camera: from the right of the stage, looking at the point between the player and the mob. */
	private static void shot(ClientGameTestContext ctx, String name, Perspective ignored) {
		ctx.runOnClient(c -> {
			var p = c.player;
			var cam = new net.minecraft.util.math.Vec3d(p.getX() + 3.6, p.getY() + 1.8, p.getZ() + 2.2);
			var look = new net.minecraft.util.math.Vec3d(p.getX(), p.getY() + 1.1, p.getZ() + 2.2);
			var d = look.subtract(cam);
			float yaw = (float) (Math.toDegrees(Math.atan2(d.z, d.x)) - 90);
			float pitch = (float) -Math.toDegrees(Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)));
			dev.rapbattle.client.Intro.debug = new dev.rapbattle.client.Intro.Shot(cam, yaw, pitch);
			c.options.hudHidden = true;
		});
		ctx.waitTicks(3);
		ctx.takeScreenshot(name);
		ctx.runOnClient(c -> {
			c.options.hudHidden = false;
			dev.rapbattle.client.Intro.debug = null;
		});
	}

	private static void run(ClientGameTestContext ctx, TestSingleplayerContext sp, Battle[] battle, EntityType<? extends MobEntity> type, String tag) {
		sp.getServer().runOnServer(server -> {
			ServerPlayerEntity p = server.getPlayerManager().getPlayerList().get(0);
			ServerWorld w = p.getEntityWorld();
			p.networkHandler.requestTeleport(p.getX(), p.getY(), p.getZ(), 0, 15);
			EntityType<?>[] crowd = {EntityType.SHEEP, EntityType.SHEEP, EntityType.SHEEP, EntityType.VILLAGER, EntityType.VILLAGER,
				EntityType.COW, EntityType.PIG, EntityType.CHICKEN, EntityType.ZOMBIE, EntityType.COW};
			for (int i = 0; i < crowd.length; i++) {
				double a = 2 * Math.PI * i / crowd.length;
				var c = (MobEntity) crowd[i].create(w, SpawnReason.COMMAND);
				c.refreshPositionAndAngles(p.getX() + Math.cos(a) * 9, p.getY(), p.getZ() + 2 + Math.sin(a) * 9, 0, 0);
				if (c instanceof net.minecraft.entity.mob.ZombieEntity z) z.equipStack(net.minecraft.entity.EquipmentSlot.HEAD, new net.minecraft.item.ItemStack(net.minecraft.item.Items.LEATHER_HELMET));
				w.spawnEntity(c);
			}
			MobEntity m = type.create(w, SpawnReason.COMMAND);
			m.refreshPositionAndAngles(p.getX(), p.getY(), p.getZ() + 6, 180, 0);
			m.setAiDisabled(true);
			w.spawnEntity(m);
			battle[0] = BattleManager.start(p, m);
			battle[0].startEarly(p);
		});
		ctx.waitFor(c -> phase("TIMELINE"), 800);
		ctx.waitFor(c -> !Intro.active(), 400);
		ctx.waitFor(c -> ClientBattle.timeline() && ClientBattle.beat() >= 1, 400);
		snap(ctx, tag + "c-countdown");
		ctx.waitFor(c -> ClientBattle.timeline() && ClientBattle.beat() >= ClientBattle.view.mobStartBeat + 1, 1200);
		snap(ctx, tag + "0-hud-mob");
		ctx.waitTicks(10);
		snap(ctx, tag + "1-hud-mob-b");
		for (int i = 0; i < 6; i++) {
			lookAtMob(ctx, tag + "1m-mob-read-" + i);
			ctx.waitTicks(1);
		}
		lookAtMe(ctx, tag + "1f-listen-front");
		ctx.waitTicks(5);
		lookAtMe(ctx, tag + "1f-listen-front-b");

		ctx.waitTicks(6);
		ctx.waitFor(c -> ClientBattle.timeline() && ClientBattle.beat() >= ClientBattle.view.mobEndBeat + ClientBattle.view.segments.get(0).startBeat - ClientBattle.view.segments.get(0).leadInBeat + 1, 1600);
		snap(ctx, tag + "2h-hud-player");
		ctx.waitTicks(12);
		snap(ctx, tag + "3h-hud-player-b");
		lookAtMob(ctx, tag + "3m-mob-listen");
		lookAtMe(ctx, tag + "3f-rap-front");
		ctx.waitTicks(5);
		lookAtMe(ctx, tag + "3f-rap-front-b");

		ctx.runOnClient(c -> c.options.setPerspective(Perspective.THIRD_PERSON_BACK));
		ctx.waitTicks(3);
		snap(ctx, tag + "3c-crowd-timeline");
		ctx.waitTicks(5);
		snap(ctx, tag + "3d-crowd-timeline-b");
		ctx.runOnClient(c -> c.options.setPerspective(Perspective.FIRST_PERSON));
		sp.getServer().runOnServer(server -> battle[0].onAudio(server.getPlayerManager().getPlayerList().get(0),
			new Payloads.AudioC2S(battle[0].id, 0, true, 0, wav())));
		ctx.waitFor(c -> phase("RESULT"), 2400);
		ctx.waitTicks(30);
		snap(ctx, tag + "4h-hud-result");
		ctx.waitTicks(70);
		snap(ctx, tag + "4i-hud-result-full");
		lookAtMe(ctx, tag + "4f-lose-front");
		ctx.waitTicks(5);
		lookAtMe(ctx, tag + "4f-lose-front-b");

		ctx.runOnClient(c -> c.options.setPerspective(Perspective.THIRD_PERSON_BACK));
		ctx.waitTicks(3);
		snap(ctx, tag + "5-crowd-back");
		ctx.runOnClient(c -> c.options.setPerspective(Perspective.FIRST_PERSON));
		ctx.waitTicks(8);
		ctx.waitFor(c -> ClientBattle.view == null, 600);
		ctx.waitTicks(60);
		sp.getServer().runCommand("kill @e[type=!player]");
		ctx.waitTicks(10);
	}
}
