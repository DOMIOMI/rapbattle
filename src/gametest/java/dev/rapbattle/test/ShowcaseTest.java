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
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Vec3d;

/** Screenshots for the mod page: a night battle with a big crowd, shot from several cinematic cameras. */
public class ShowcaseTest implements FabricClientGameTest {
	private static Vec3d P, M;

	private static byte[] wav() {
		try { return java.nio.file.Files.readAllBytes(java.nio.file.Path.of(System.getenv("RB_TEST_WAV"))); }
		catch (java.io.IOException e) { throw new RuntimeException(e); }
	}

	private static boolean phase(String p) { return ClientBattle.view != null && p.equals(ClientBattle.view.phase); }

	/** A camera at `cam` looking at `look`, HUD hidden. */
	private static boolean keepHud;

	private static void cam(ClientGameTestContext ctx, String name, Vec3d cam, Vec3d look, int wait) {
		ctx.runOnClient(c -> {
			c.inGameHud.getChatHud().clear(false);
			var d = look.subtract(cam);
			float yaw = (float) (Math.toDegrees(Math.atan2(d.z, d.x)) - 90);
			float pitch = (float) -Math.toDegrees(Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)));
			Intro.debug = new Intro.Shot(cam, yaw, pitch);
			c.options.hudHidden = !keepHud;
			c.options.setPerspective(Perspective.THIRD_PERSON_BACK);
		});
		ctx.waitTicks(wait);
		GameTestSupport.takeScreenshot(ctx, name);
		ctx.runOnClient(c -> {
			c.options.hudHidden = false;
			c.options.setPerspective(Perspective.FIRST_PERSON);
			Intro.debug = null;
		});
	}

	/** Camera relative to the real positions: `who` 0 = mob, 1 = player; along = towards the other one, side, up. */
	private static void rel(ClientGameTestContext ctx, String name, int who, double along, double side, double up, int lookWho, double lookUp, int wait) {
		Vec3d[] pos = new Vec3d[2];
		ctx.runOnClient(c -> {
			var e = c.world.getEntityById(ClientBattle.view.mobEntityIds.get(0));
			pos[0] = new Vec3d(e.getX(), e.getY(), e.getZ());
			pos[1] = new Vec3d(c.player.getX(), c.player.getY(), c.player.getZ());
		});
		Vec3d f = pos[1].subtract(pos[0]).multiply(1, 0, 1).normalize();
		if (who == 1) f = f.multiply(-1);
		Vec3d r = new Vec3d(-f.z, 0, f.x);
		Vec3d base = pos[who];
		Vec3d camPos = base.add(f.multiply(along)).add(r.multiply(side)).add(0, up, 0);
		Vec3d look = lookWho == 2 ? pos[0].add(pos[1]).multiply(0.5).add(0, lookUp, 0) : pos[lookWho].add(0, lookUp, 0);
		cam(ctx, name, camPos, look, wait);
	}

	private static void clean(ClientGameTestContext ctx) {
		ctx.runOnClient(c -> {
			c.inGameHud.getChatHud().clear(false);
			c.particleManager.clearParticles();
		});
	}

	private static void hud(ClientGameTestContext ctx, String name) {
		ctx.runOnClient(c -> { c.inGameHud.getChatHud().clear(false); c.player.clearStatusEffects(); c.particleManager.clearParticles(); });
		ctx.waitTicks(2);
		GameTestSupport.takeScreenshot(ctx, name);
	}

	@Override
	public void runTest(ClientGameTestContext ctx) {
		GameTestSupport.installKeyAudit();
		ctx.getInput().resizeWindow(1920, 1080);
		ctx.waitTicks(5);
		// Full bling on the mob in every shot.
		net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.START_CLIENT_TICK.register(c -> {
			if (ClientBattle.view != null) { ClientBattle.view.rankLevel = 5; ClientBattle.view.gangColor = 0xE03030; }
		});
		// No glowing outline on the reader in the screenshots: cleared after the battle sets it, every server tick.
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(server -> {
			for (var w : server.getWorlds()) for (var e : w.iterateEntities()) if (e.isGlowing()) e.setGlowing(false);
		});
		Battle[] battle = new Battle[1];
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			sp.getClientWorld().waitForChunksRender();
			GameTestSupport.applyLang(ctx);
			sp.getServer().runCommand("gamerule doMobSpawning false");
			sp.getServer().runCommand("gamerule doDaylightCycle false");
			sp.getServer().runCommand("gamerule doWeatherCycle false");
			sp.getServer().runCommand("time set 14500");
			sp.getServer().runOnServer(server -> {
				ServerPlayerEntity p = server.getPlayerManager().getPlayerList().get(0);
				ServerWorld w = p.getEntityWorld();
				p.networkHandler.requestTeleport(p.getX(), p.getY(), p.getZ(), 0, 10);
				P = new Vec3d(p.getX(), p.getY(), p.getZ());
				M = P.add(0, 0, 6);
				int fy = (int) Math.floor(P.y) - 1;
				int cx = (int) Math.floor(P.x), cz = (int) Math.floor(P.z + 3);
				// A dark stage disc with a ring of lights, the crowd on grass around it.
				java.util.function.Consumer<String> run = cmd -> server.getCommandManager().parseAndExecute(server.getCommandSource(), cmd);
				for (int x = -6; x <= 6; x++) for (int z = -6; z <= 6; z++) {
					double r = Math.sqrt(x * x + z * z);
					String block = r <= 4.6 ? "polished_blackstone" : r <= 5.5 ? "sea_lantern" : null;
					if (block != null) run.accept("setblock " + (cx + x) + " " + fy + " " + (cz + z) + " " + block);
				}
				EntityType<?>[] crowd = {EntityType.VILLAGER, EntityType.ZOMBIE, EntityType.SKELETON, EntityType.PIGLIN, EntityType.VILLAGER,
					EntityType.SHEEP, EntityType.CREEPER, EntityType.HUSK, EntityType.VILLAGER, EntityType.WITCH, EntityType.ZOMBIE,
					EntityType.PILLAGER, EntityType.COW, EntityType.SKELETON, EntityType.VILLAGER, EntityType.DROWNED, EntityType.PIG, EntityType.ZOMBIE};
				for (int i = 0; i < crowd.length; i++) {
					double a = 2 * Math.PI * i / crowd.length;
					var c = (MobEntity) crowd[i].create(w, SpawnReason.COMMAND);
					c.refreshPositionAndAngles(P.x + Math.cos(a) * 9, P.y, P.z + 3 + Math.sin(a) * 9, 0, 0);
					if (c instanceof net.minecraft.entity.mob.ZombieEntity z) z.equipStack(EquipmentSlot.HEAD, new ItemStack(Items.LEATHER_HELMET));
					if (c instanceof net.minecraft.entity.mob.AbstractSkeletonEntity s) s.equipStack(EquipmentSlot.HEAD, new ItemStack(Items.LEATHER_HELMET));
					w.spawnEntity(c);
				}
				MobEntity m = EntityType.ZOMBIE.create(w, SpawnReason.COMMAND);
				m.refreshPositionAndAngles(M.x, M.y, M.z, 180, 0);
				m.setAiDisabled(true);
				m.equipStack(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
				w.spawnEntity(m);
				for (String slot : new String[]{"chest:netherite_chestplate", "legs:netherite_leggings", "feet:netherite_boots"}) {
					String[] s = slot.split(":");
					run.accept("item replace entity @a armor." + s[0] + " with " + s[1]);
				}
				battle[0] = BattleManager.start(p, m);
				battle[0].startEarly(p);
			});
			ctx.waitFor(c -> phase("TIMELINE"), 800);
			ctx.waitFor(c -> !Intro.active(), 400);
			ctx.waitFor(c -> ClientBattle.timeline() && ClientBattle.beat() >= ClientBattle.view.mobStartBeat + 2, 1200);
			Vec3d mid = P.add(0, 1.2, 3);
			// The mob reads.
			clean(ctx);
			ctx.waitTicks(4);
			rel(ctx, "01-hero-wide", 1, -3.5, 7.5, 4.5, 2, 1.0, 4);
			hud(ctx, "02-hud-mob-verse");
			rel(ctx, "03-mob-close", 0, 2.0, 0.9, 1.6, 0, 1.5, 4);
			rel(ctx, "04-over-shoulder", 1, -2.2, 0.9, 2.1, 0, 1.4, 4);
			ctx.waitTicks(20);
			rel(ctx, "05-mob-close-b", 0, 1.8, -1.1, 1.4, 0, 1.6, 4);
			// The player reads.
			ctx.waitFor(c -> ClientBattle.timeline() && ClientBattle.beat() >= ClientBattle.view.mobEndBeat
				+ ClientBattle.view.segments.get(0).startBeat - ClientBattle.view.segments.get(0).leadInBeat + 3, 1600);
			hud(ctx, "06-hud-player-verse");
			rel(ctx, "07-player-rap", 1, 2.2, 1.0, 1.5, 1, 1.3, 12);
			rel(ctx, "08-face-off", 1, 3.0, -3.2, 1.7, 2, 1.1, 4);
			ctx.waitTicks(10);
			sp.getServer().runOnServer(server -> battle[0].onAudio(server.getPlayerManager().getPlayerList().get(0),
				new Payloads.AudioC2S(battle[0].id, 0, true, 0, wav())));
			ctx.waitFor(c -> phase("RESULT"), 3600);
			ctx.waitTicks(140);
			keepHud = true;
			rel(ctx, "10-hud-result", 1, -1.9, 0.5, 2.0, 0, 1.3, 4);
			keepHud = false;
			ctx.runOnClient(c -> c.setScreen(new dev.rapbattle.client.RapSettingsScreen(null)));
			ctx.waitTicks(5);
			GameTestSupport.takeScreenshot(ctx, "11-settings");
			ctx.runOnClient(c -> c.setScreen(null));
			ctx.waitFor(c -> ClientBattle.view == null, 900);
			ctx.waitTicks(20);
			GameTestSupport.assertNoRawKeys("ShowcaseTest");
		}
	}
}
