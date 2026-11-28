package dev.rapbattle.test;

import dev.rapbattle.Clock;
import dev.rapbattle.Lang;
import dev.rapbattle.battle.*;
import dev.rapbattle.client.ClientBattle;
import dev.rapbattle.client.Intro;
import dev.rapbattle.net.BattleView;
import dev.rapbattle.net.Payloads;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
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

/** Gang wars, champions, rematches, pending exits, daytime sun safety, and long trophy breakdown scenario. */
public final class GangsScenario {
	private GangsScenario() {}

	private static boolean phase(String p) {
		return ClientBattle.view != null && p.equals(ClientBattle.view.phase);
	}

	public static void run(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		sp.getServer().runCommand("time set 6000");
		testGangRivalry(ctx, sp);
		testChampionSunAndCooldown(ctx, sp);
		testRematchCallout(ctx, sp);
		testPendingAutoChallengeExit(ctx, sp);
		testDaytimeSunSafety(ctx, sp);
		testLongBreakdownTrophyVisible(ctx, sp);
		GameTestSupport.assertNoRawKeys("gangs");
	}

	private static void testGangRivalry(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		ZombieEntity[] zRef = new ZombieEntity[1];
		Rank[] baseRank = new Rank[1];
		Battle[] battle = new Battle[1];
		sp.getServer().runOnServer(server -> {
			ServerPlayerEntity p = server.getPlayerManager().getPlayerList().get(0);
			ServerWorld w = p.getEntityWorld();
			p.changeGameMode(GameMode.SURVIVAL);
			p.setInvulnerable(true);
			GameTestSupport.clearKeepMic(p);
			BattleManager.clearPendingForTest(p.getUuid());

			ZombieEntity z = EntityType.ZOMBIE.create(w, SpawnReason.COMMAND);
			z.refreshPositionAndAngles(p.getX(), p.getY(), p.getZ() + 9.0, 180, 0);
			z.setAiDisabled(true);
			z.setTarget(p);
			w.spawnEntity(z);
			zRef[0] = z;
			baseRank[0] = Opponent.of(z, new Random(0), p, Lang.of(p)).rank();
		});
		ctx.waitTicks(22);
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
		ctx.waitTicks(22);
		sp.getServer().runOnServer(server -> {
			ServerPlayerEntity p = server.getPlayerManager().getPlayerList().get(0);
			assertTrue(BattleManager.hasPendingForTest(p.getUuid()), "Zombie at 9 blocks MUST challenge at 1.5x range with rival gear");
			BattleManager.clearPendingForTest(p.getUuid());

			battle[0] = BattleManager.start(p, zRef[0]);
			int expectedLevel = Math.min(5, baseRank[0].level() + 1);
			assertTrue(battle[0].rank.level() == expectedLevel,
				"Rival battle rank must step up +1 (capped at 5): expected " + expectedLevel + ", got " + battle[0].rank.level());
			battle[0].jumpToForTest(Battle.Phase.TIMELINE);
		});
		ctx.waitFor(c -> phase("TIMELINE"), 400);
		Intro.skip();
		// Advance clock past mob verse instead of waiting 1000+ real ticks
		sp.getServer().runOnServer(server -> {
			Clock.advanceForTest(30_000L);
			battle[0].onAudio(
				server.getPlayerManager().getPlayerList().get(0),
				new Payloads.AudioC2S(battle[0].id, 0, true, 0, GameTestSupport.wav()));
		});
		ctx.waitFor(c -> phase("RESULT"), 600);
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
		sp.getServer().runOnServer(server -> {
			GameTestSupport.cleanupBattles(server);
			if (zRef[0] != null && !zRef[0].isRemoved()) zRef[0].discard();
			GameTestSupport.clearKeepMic(server.getPlayerManager().getPlayerList().get(0));
		});
	}

	private static void testChampionSunAndCooldown(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		ZombieEntity[] champRef = new ZombieEntity[1];
		sp.getServer().runOnServer(server -> {
			ServerPlayerEntity p = server.getPlayerManager().getPlayerList().get(0);
			ServerWorld w = p.getEntityWorld();
			Stats.of(server).player(p.getUuid()).streak = 0;

			ZombieEntity z = EntityType.ZOMBIE.create(w, SpawnReason.COMMAND);
			z.refreshPositionAndAngles(p.getX(), p.getY(), p.getZ() + 5.0, 180, 0);
			MobRecord.win(z);
			w.spawnEntity(z);
			champRef[0] = z;
		});
		ctx.waitTicks(30);
		sp.getServer().runOnServer(server -> {
			ZombieEntity z = champRef[0];
			assertTrue(z != null && z.isAlive() && !z.isOnFire(), "Champion zombie must not burn in daytime sun");
			ServerPlayerEntity p = server.getPlayerManager().getPlayerList().get(0);
			Stats.of(server).player(p.getUuid()).streak = 3;
		});
		ctx.waitTicks(22);
		sp.getServer().runOnServer(server -> {
			ServerPlayerEntity p = server.getPlayerManager().getPlayerList().get(0);
			assertTrue(BattleManager.hasPendingForTest(p.getUuid()), "Champion must call out player on 3+ win streak");
			Battle b = BattleManager.acceptPendingForTest(p);
			assertTrue(b != null, "Accepting champion callout must start battle");
			b.jumpToForTest(Battle.Phase.RESULT, false, true, 30, 90);
			Stats.of(server).player(p.getUuid()).streak = 3;
			Clock.advanceForTest(60_000L);
		});
		ctx.waitTicks(22);
		sp.getServer().runOnServer(server -> {
			ServerPlayerEntity p = server.getPlayerManager().getPlayerList().get(0);
			assertTrue(!BattleManager.hasPendingForTest(p.getUuid()), "Champion must NOT re-challenge before 5-minute cooldown");
			Clock.advanceForTest(5 * 60_000L);
		});
		ctx.waitTicks(22);
		sp.getServer().runOnServer(server -> {
			ServerPlayerEntity p = server.getPlayerManager().getPlayerList().get(0);
			assertTrue(BattleManager.hasPendingForTest(p.getUuid()), "Champion MUST re-challenge after 5-minute cooldown");
			GameTestSupport.cleanupBattles(server);
			if (champRef[0] != null && !champRef[0].isRemoved()) champRef[0].discard();
			Stats.of(server).player(p.getUuid()).streak = 0;
		});
	}

	private static void testRematchCallout(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		ZombieEntity[] rivalRef = new ZombieEntity[1];
		sp.getServer().runOnServer(server -> {
			ServerPlayerEntity p = server.getPlayerManager().getPlayerList().get(0);
			ServerWorld w = p.getEntityWorld();
			ZombieEntity z = EntityType.ZOMBIE.create(w, SpawnReason.COMMAND);
			z.refreshPositionAndAngles(p.getX(), p.getY(), p.getZ() + 4.0, 180, 0);
			w.spawnEntity(z);
			rivalRef[0] = z;

			Battle b = BattleManager.start(p, z);
			b.jumpToForTest(Battle.Phase.RESULT, true, false, 90, 75);
			assertTrue(z.isAlive(), "Mob must survive and flee on close win");
			assertTrue(Crowd.inTruce(z), "Fleeing mob must enter truce");
			MobRecord.Rival rv = MobRecord.rival(z, p.getName().getString());
			assertTrue(rv != null && !rv.mobWon(), "Mob must remember losing to the player");
			Clock.advanceForTest(20_000L);
			z.refreshPositionAndAngles(p.getX(), p.getY(), p.getZ() + 6.0, 180, 0);
		});
		ctx.waitTicks(22);
		sp.getServer().runOnServer(server -> {
			ServerPlayerEntity p = server.getPlayerManager().getPlayerList().get(0);
			assertTrue(BattleManager.hasPendingForTest(p.getUuid()), "Remembered mob within 16 blocks must call player out for rematch");
			GameTestSupport.cleanupBattles(server);
			if (rivalRef[0] != null && !rivalRef[0].isRemoved()) rivalRef[0].discard();
		});
	}

	private static void testPendingAutoChallengeExit(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		ZombieEntity[] currentMob = new ZombieEntity[1];
		for (int idx = 0; idx < 2; idx++) {
			final int i = idx;
			sp.getServer().runOnServer(server -> {
				ServerPlayerEntity p = server.getPlayerManager().getPlayerList().get(0);
				ServerWorld w = p.getEntityWorld();
				p.setStackInHand(net.minecraft.util.Hand.MAIN_HAND, ItemStack.EMPTY);
				Stats.of(server).player(p.getUuid()).streak = 3;

				ZombieEntity m = EntityType.ZOMBIE.create(w, SpawnReason.COMMAND);
				m.refreshPositionAndAngles(p.getX(), p.getY(), p.getZ() + 9.0, 180, 0);
				m.setAiDisabled(true);
				m.equipStack(EquipmentSlot.HEAD, new ItemStack(Items.LEATHER_HELMET));
				if (i == 0) MobRecord.win(m);
				else MobRecord.remember(m, p.getName().getString(), false, "test line");
				w.spawnEntity(m);
				currentMob[0] = m;
				BattleManager.clearPendingForTest(p.getUuid());
				Clock.advanceForTest(10 * 60_000L);
			});
			ctx.waitTicks(22);
			sp.getServer().runOnServer(server -> {
				ServerPlayerEntity p = server.getPlayerManager().getPlayerList().get(0);
				ServerWorld w = p.getEntityWorld();
				assertTrue(BattleManager.hasPendingForTest(p.getUuid()), "Auto-challenge should open PENDING for mob " + i);
				net.fabricmc.fabric.api.event.player.AttackEntityCallback.EVENT.invoker()
					.interact(p, w, net.minecraft.util.Hand.MAIN_HAND, currentMob[0], null);
				assertTrue(!BattleManager.hasPendingForTest(p.getUuid()), "Punch during PENDING must clear choice without crash");
				assertTrue(currentMob[0].getTarget() == p, "Mob " + i + " must aggro after punch refusal");

				currentMob[0].setTarget(null);
				BattleManager.clearPendingForTest(p.getUuid());
				Clock.advanceForTest(10 * 60_000L);
			});
			ctx.waitTicks(22);
			sp.getServer().runOnServer(server -> {
				ServerPlayerEntity p = server.getPlayerManager().getPlayerList().get(0);
				assertTrue(BattleManager.hasPendingForTest(p.getUuid()), "PENDING should open before timeout test");
				Clock.advanceForTest(20_000L);
			});
			ctx.waitTicks(5);
			sp.getServer().runOnServer(server -> {
				ServerPlayerEntity p = server.getPlayerManager().getPlayerList().get(0);
				assertTrue(!BattleManager.hasPendingForTest(p.getUuid()), "PENDING must clear on 15s timeout without crash");
				assertTrue(currentMob[0].getTarget() == p, "Mob " + i + " must aggro after timeout");

				currentMob[0].setTarget(null);
				BattleManager.clearPendingForTest(p.getUuid());
				Clock.advanceForTest(10 * 60_000L);
			});
			ctx.waitTicks(22);
			sp.getServer().runOnServer(server -> {
				ServerPlayerEntity p = server.getPlayerManager().getPlayerList().get(0);
				ServerWorld w = p.getEntityWorld();
				assertTrue(BattleManager.hasPendingForTest(p.getUuid()), "PENDING should open before walk-away test");
				double origX = p.getX(), origY = p.getY(), origZ = p.getZ();
				p.teleport(w, currentMob[0].getX() + 18.0, currentMob[0].getY(), currentMob[0].getZ(),
					java.util.Set.of(), p.getYaw(), p.getPitch(), false);
				currentMob[0].setCustomName(net.minecraft.text.Text.literal(origX + "," + origY + "," + origZ));
			});
			ctx.waitTicks(5);
			sp.getServer().runOnServer(server -> {
				ServerPlayerEntity p = server.getPlayerManager().getPlayerList().get(0);
				ServerWorld w = p.getEntityWorld();
				assertTrue(!BattleManager.hasPendingForTest(p.getUuid()), "PENDING must clear on 16+ block walk-away without crash");
				assertTrue(currentMob[0].getTarget() == p, "Mob " + i + " must aggro after walk-away");
				String[] coords = currentMob[0].getCustomName().getString().split(",");
				currentMob[0].setCustomName(null);
				p.teleport(w, Double.parseDouble(coords[0]), Double.parseDouble(coords[1]), Double.parseDouble(coords[2]),
					java.util.Set.of(), p.getYaw(), p.getPitch(), false);
				currentMob[0].discard();
			});
		}

		sp.getServer().runOnServer(server -> {
			ServerPlayerEntity p = server.getPlayerManager().getPlayerList().get(0);
			GameTestSupport.cleanupBattles(server);
			Stats.of(server).player(p.getUuid()).streak = 0;
		});
	}

	private static void testDaytimeSunSafety(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		ZombieEntity[] zRef = new ZombieEntity[1];
		SkeletonEntity[] skRef = new SkeletonEntity[1];
		sp.getServer().runOnServer(server -> {
			ServerPlayerEntity p = server.getPlayerManager().getPlayerList().get(0);
			ServerWorld w = p.getEntityWorld();
			ZombieEntity z = EntityType.ZOMBIE.create(w, SpawnReason.COMMAND);
			z.refreshPositionAndAngles(p.getX(), p.getY(), p.getZ() + 4.0, 180, 0);
			w.spawnEntity(z);
			SkeletonEntity sk = EntityType.SKELETON.create(w, SpawnReason.COMMAND);
			sk.refreshPositionAndAngles(p.getX() + 5.0, p.getY(), p.getZ() + 3.0, 180, 0);
			w.spawnEntity(sk);
			zRef[0] = z;
			skRef[0] = sk;
			Battle b = BattleManager.start(p, z);
			b.jumpToForTest(Battle.Phase.TIMELINE);
		});
		ctx.waitTicks(30);
		sp.getServer().runOnServer(server -> {
			assertTrue(zRef[0].isAlive() && !zRef[0].isOnFire(), "Battling zombie must not burn in the sun");
			assertTrue(skRef[0].isAlive() && !skRef[0].isOnFire(), "Spectator skeleton must not burn in the sun during battle");
			GameTestSupport.cleanupBattles(server);
			zRef[0].discard();
			skRef[0].discard();
		});
	}

	private static void testLongBreakdownTrophyVisible(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		ZombieEntity[] zRef = new ZombieEntity[1];
		Battle[] battle = new Battle[1];
		sp.getServer().runOnServer(server -> {
			ServerPlayerEntity p = server.getPlayerManager().getPlayerList().get(0);
			ServerWorld w = p.getEntityWorld();
			ItemStack witherMic = new ItemStack(Items.END_ROD);
			net.minecraft.nbt.NbtCompound tag = new net.minecraft.nbt.NbtCompound();
			tag.putString(Trophies.NBT_KEY, "wither");
			witherMic.set(net.minecraft.component.DataComponentTypes.CUSTOM_DATA, net.minecraft.component.type.NbtComponent.of(tag));
			p.setStackInHand(net.minecraft.util.Hand.OFF_HAND, witherMic);

			ZombieEntity z = EntityType.ZOMBIE.create(w, SpawnReason.COMMAND);
			z.refreshPositionAndAngles(p.getX(), p.getY(), p.getZ() + 4.0, 180, 0);
			w.spawnEntity(z);
			zRef[0] = z;

			battle[0] = BattleManager.start(p, z);
			battle[0].jumpToForTest(Battle.Phase.TIMELINE);
		});
		ctx.waitFor(c -> phase("TIMELINE"), 400);
		Intro.skip();
		sp.getServer().runOnServer(server -> {
			Clock.advanceForTest(30_000L);
			battle[0].onAudio(
				server.getPlayerManager().getPlayerList().get(0),
				new Payloads.AudioC2S(battle[0].id, 0, true, 0, GameTestSupport.wav()));
		});
		ctx.waitFor(c -> phase("RESULT") || phase("ROUND_RESULT"), 600);
		ctx.runOnClient(c -> {
			if (ClientBattle.view != null && ClientBattle.view.result != null) {
				var hits = ClientBattle.view.result.hits;
				String pname = c.player.getName().getString();
				String lang = c.getLanguageManager().getLanguage().startsWith("ru") ? "ru" : "en";
				String silentNote = "«...» · " + (lang.equals("ru") ? "тишина" : "silence");
				String witherNote = Trophies.TrophyPerk.WITHER.triggeredNote(lang);
				hits.removeIf(h -> witherNote.equals(h.note));
				while (hits.size() < 15) {
					hits.add(0, new BattleView.Hit(pname, silentNote, 0, true, false));
				}
				hits.add(new BattleView.Hit(pname, witherNote, 0, false, false, true));
				assertTrue(hits.size() >= 12, "Breakdown must have at least 12 hits");
				BattleView.Hit last = hits.get(hits.size() - 1);
				assertTrue(last.system && !last.toMob && last.damage == 0, "Triggered trophy hit must be a zero-damage system row");
			}
		});
		sp.getServer().runOnServer(server -> {
			GameTestSupport.cleanupBattles(server);
			if (zRef[0] != null && !zRef[0].isRemoved()) zRef[0].discard();
			GameTestSupport.clearKeepMic(server.getPlayerManager().getPlayerList().get(0));
		});
	}
}
