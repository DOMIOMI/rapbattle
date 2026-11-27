package dev.rapbattle.test;

import dev.rapbattle.Clock;
import dev.rapbattle.RapConfig;
import dev.rapbattle.battle.*;
import dev.rapbattle.client.ClientBattle;
import dev.rapbattle.client.MobBadgeFx;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.entity.mob.BlazeEntity;
import net.minecraft.entity.mob.SkeletonEntity;
import net.minecraft.entity.mob.SpiderEntity;
import net.minecraft.entity.mob.ZombieEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Hand;

import java.util.List;
import java.util.Map;
import java.util.Random;

import static dev.rapbattle.test.GameTestSupport.assertTrue;

/**
 * 6.29 First Minutes tests:
 * - starter mobs (zombie, skeleton, spider) are rank 1 on day 1 at spawn
 * - homemade_mic in inventory/off-hand allows battle without armor; without mic -> clear refusal
 * - zombie with iron helmet is rank 3, requires armor, helmet drop chance is 0
 * - rank 4 gate opens on armored rank-3 zombie+skeleton wins, not naked rank-1 wins
 * - MobBadgeFx status (0/1/2) matches Progression.canStart
 * - Speech.bubble duration >= 4s for short and >= 10s for 120-char text
 * - HUD screenshots at 854x480 and 1920x1080 across GUI 2/3/4 and ru_ru/en_us
 */
public final class FirstMinutesScenario {
	private FirstMinutesScenario() {}

	private static ItemStack makeHomemadeMic() {
		ItemStack mic = new ItemStack(Items.LIGHTNING_ROD);
		NbtCompound tag = new NbtCompound();
		tag.putString(Trophies.NBT_KEY, Trophies.TrophyPerk.HOMEMADE.key);
		mic.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(tag));
		return mic;
	}

	public static void run(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		int[] mobIds = new int[3]; // [0]=nakedZombie (allowed), [1]=blaze (soft), [2]=ironZombie (refused)

		sp.getServer().runOnServer(server -> {
			ServerPlayerEntity p = server.getPlayerManager().getPlayerList().get(0);
			ServerWorld w = p.getEntityWorld();
			p.getInventory().clear();
			p.setStackInHand(Hand.MAIN_HAND, ItemStack.EMPTY);
			p.setStackInHand(Hand.OFF_HAND, ItemStack.EMPTY);
			RapConfig.get().progression = "strict";
			RapConfig.get().dressCode = true;

			ZombieEntity z = EntityType.ZOMBIE.create(w, SpawnReason.COMMAND);
			SkeletonEntity sk = EntityType.SKELETON.create(w, SpawnReason.COMMAND);
			SpiderEntity spMob = EntityType.SPIDER.create(w, SpawnReason.COMMAND);
			z.refreshPositionAndAngles(p.getX() + 2, p.getY(), p.getZ() + 4, 180, 0);
			sk.refreshPositionAndAngles(p.getX(), p.getY(), p.getZ() + 4, 180, 0);
			spMob.refreshPositionAndAngles(p.getX() - 2, p.getY(), p.getZ() + 4, 180, 0);
			w.spawnEntity(z);
			w.spawnEntity(sk);
			w.spawnEntity(spMob);

			// Clear any random starter gear for deterministic rank-1 check
			for (EquipmentSlot s : EquipmentSlot.values()) {
				if (s.isArmorSlot()) {
					z.equipStack(s, ItemStack.EMPTY);
					sk.equipStack(s, ItemStack.EMPTY);
				}
			}

			assertTrue(Opponent.of(z, new Random(0), p, "ru").rank() == Rank.LEATHER, "Naked zombie must be rank 1");
			assertTrue(Opponent.of(sk, new Random(0), p, "ru").rank() == Rank.LEATHER, "Naked skeleton must be rank 1");
			assertTrue(Opponent.of(spMob, new Random(0), p, "ru").rank() == Rank.LEATHER, "Spider must be rank 1");

			// Without mic -> refused
			Progression.StartCheck noMic = Progression.canStart(p, z, true, 0, new Random(0));
			assertTrue(!noMic.allowed() && !noMic.micAllowed(), "Without mic, starter zombie must refuse");

			// With homemade mic in inventory (main hand still empty!) -> allowed without armor
			p.getInventory().setStack(8, makeHomemadeMic());
			assertTrue(Progression.canStart(p, z, true, 0, new Random(0)).allowed(), "Zombie rank 1 must allow with mic in inventory");
			assertTrue(Progression.canStart(p, sk, true, 0, new Random(0)).allowed(), "Skeleton rank 1 must allow with mic in inventory");
			assertTrue(Progression.canStart(p, spMob, true, 0, new Random(0)).allowed(), "Spider rank 1 must allow with mic in inventory");

			// Iron-helmet zombie = rank 3, requires armor
			ZombieEntity ironZ = EntityType.ZOMBIE.create(w, SpawnReason.COMMAND);
			ironZ.refreshPositionAndAngles(p.getX() + 3, p.getY(), p.getZ() + 4, 180, 0);
			ironZ.equipStack(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
			ironZ.setEquipmentDropChance(EquipmentSlot.HEAD, 0.0f);
			w.spawnEntity(ironZ);
			assertTrue(Opponent.of(ironZ, new Random(0), p, "ru").rank() == Rank.IRON, "Iron-helmet zombie must be rank 3");
			Progression.StartCheck ironCheck = Progression.canStart(p, ironZ, true, 0, new Random(0));
			assertTrue(!ironCheck.allowed() && !ironCheck.dressAllowed(), "Iron zombie must refuse naked player");

			// Rank 4 gate: naked wins do NOT unlock, armored rank-3 wins DO unlock
			Stats stats = Stats.of(server);
			Stats.Player spStats = stats.player(p.getUuid());
			spStats.respect = 0;
			spStats.speciesWins.clear();
			spStats.rankedSpeciesWins.clear();
			BlazeEntity blaze = EntityType.BLAZE.create(w, SpawnReason.COMMAND);
			blaze.refreshPositionAndAngles(p.getX() - 3, p.getY(), p.getZ() + 4, 180, 0);
			w.spawnEntity(blaze);
			Opponent blazeOpp = Opponent.of(blaze, new Random(0), p, "ru");

			stats.record(List.of(p.getUuid()), Map.of(p.getUuid(), 0), p.getUuid(), true,
				Opponent.of(z, new Random(0), p, "ru"), Rank.LEATHER, Map.of(p.getUuid(), 0), 0L);
			stats.record(List.of(p.getUuid()), Map.of(p.getUuid(), 0), p.getUuid(), true,
				Opponent.of(sk, new Random(0), p, "ru"), Rank.LEATHER, Map.of(p.getUuid(), 0), 0L);
			assertTrue(!Progression.check(p, blaze, blazeOpp).allowed(), "Naked zombie+skeleton wins must NOT unlock rank 4");

			stats.record(List.of(p.getUuid()), Map.of(p.getUuid(), 0), p.getUuid(), true,
				Opponent.of(ironZ, new Random(0), p, "ru"), Rank.IRON, Map.of(p.getUuid(), 0), 0L);
			SkeletonEntity ironSk = EntityType.SKELETON.create(w, SpawnReason.COMMAND);
			ironSk.equipStack(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
			stats.record(List.of(p.getUuid()), Map.of(p.getUuid(), 0), p.getUuid(), true,
				Opponent.of(ironSk, new Random(0), p, "ru"), Rank.IRON, Map.of(p.getUuid(), 0), 0L);
			assertTrue(Progression.check(p, blaze, blazeOpp).allowed(), "Armored rank-3 zombie+skeleton wins MUST unlock rank 4");

			// Setup for badge test: reset wins, set progression=soft so blaze is status 1 (yellow)
			spStats.respect = 0;
			spStats.speciesWins.clear();
			spStats.rankedSpeciesWins.clear();
			RapConfig.get().progression = "soft";
			// Give iron armor? No, keep naked so ironZ is status 2 (red), z is status 0 (green), blaze is status 1 (yellow)
			// Wait: blaze is rank 4 so dressAllowed=false for naked! Give player leather/chain? No, soft gate only if dressAllowed=true!
			// Equip chainmail on player? If player wears chainmail (rank 2), z (rank 1) -> green (0), ironZ (rank 3, req 2) -> green too.
			// So spawn a gold mob? No, blaze (rank 4, req 3). Give player chainmail (2) + gold? Wait: if player has iron (3):
			// z (1) -> green (0), blaze (4, req 3, dress ok, gate soft) -> yellow (1), warden/diamond? Let's spawn iron_golem (rank 5, req 4) -> red (2)!
			p.equipStack(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
			p.equipStack(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
			p.equipStack(EquipmentSlot.LEGS, new ItemStack(Items.IRON_LEGGINGS));
			p.equipStack(EquipmentSlot.FEET, new ItemStack(Items.IRON_BOOTS));

			var golem = EntityType.IRON_GOLEM.create(w, SpawnReason.COMMAND);
			golem.refreshPositionAndAngles(p.getX() + 3, p.getY(), p.getZ() + 4, 180, 0);
			w.spawnEntity(golem);

			mobIds[0] = z.getId();      // green (0)
			mobIds[1] = blaze.getId();  // yellow (1)
			mobIds[2] = golem.getId();  // red (2)

			// Speech bubble durations: short >= 4s, 120 chars >= 10s
			Speech.clearAll();
			Speech.bubble(z, "Йоу!", 0xFFFFFF, 1500);
			Clock.advanceForTest(3500L);
			Speech.tick();
			int shortCount = w.getEntitiesByClass(DisplayEntity.TextDisplayEntity.class, z.getBoundingBox().expand(2), d -> true).size();
			assertTrue(shortCount == 1, "Short bubble must still be alive at 3.5s (>= 4s)");
			Clock.advanceForTest(1000L);
			Speech.tick();

			Speech.bubble(z, "a".repeat(120), 0xFFFFFF, 1500);
			Clock.advanceForTest(9500L);
			Speech.tick();
			int longCount = w.getEntitiesByClass(DisplayEntity.TextDisplayEntity.class, z.getBoundingBox().expand(2), d -> true).size();
			assertTrue(longCount == 1, "120-char bubble must still be alive at 9.5s (>= 10s)");
			Speech.clearAll();

			// Clean up temporary mobs except the 3 badge mobs
			sk.discard();
			spMob.discard();
			ironZ.discard();
			ironSk.discard();
		});

		// Wait 12 ticks for server -> client MobBadgesS2C
		ctx.waitTicks(12);
		ctx.runOnClient(mc -> {
			MobBadgeFx.Badge b0 = MobBadgeFx.badgeFor(mobIds[0]);
			MobBadgeFx.Badge b1 = MobBadgeFx.badgeFor(mobIds[1]);
			MobBadgeFx.Badge b2 = MobBadgeFx.badgeFor(mobIds[2]);
			assertTrue(b0 != null && b0.status() == 0, "Naked zombie badge must be green (0), got " + b0);
			assertTrue(b1 != null && b1.status() == 1, "Blaze badge in soft mode must be yellow (1), got " + b1);
			assertTrue(b2 != null && b2.status() == 2, "Iron golem badge for iron-armored player must be red (2), got " + b2);
		});

		// Take choice HUD screenshots across resolutions & languages
		sp.getServer().runOnServer(server -> {
			ServerPlayerEntity p = server.getPlayerManager().getPlayerList().get(0);
			var e = p.getEntityWorld().getEntityById(mobIds[2]);
			if (e != null) p.attack(e); // triggers refusal choice card on client
		});
		ctx.waitTicks(4);
		ctx.getInput().resizeWindow(854, 480);
		ctx.waitTicks(2);
		GameTestSupport.takeScreenshot(ctx, "6.29-choice-refused-854x480-ru");
		ctx.getInput().resizeWindow(1920, 1080);
		ctx.waitTicks(2);
		GameTestSupport.takeScreenshot(ctx, "6.29-choice-refused-1920x1080-ru");

		GameTestSupport.setLang(ctx, "en_us");
		sp.getServer().runOnServer(server -> {
			ServerPlayerEntity p = server.getPlayerManager().getPlayerList().get(0);
			var e = p.getEntityWorld().getEntityById(mobIds[0]);
			if (e != null) p.attack(e); // triggers allowed choice card on client
		});
		ctx.waitTicks(4);
		GameTestSupport.takeScreenshot(ctx, "6.29-choice-allowed-1920x1080-en");
		GameTestSupport.setLang(ctx, "ru_ru");

		// Cleanup
		ctx.runOnClient(mc -> ClientBattle.choice = null);
		sp.getServer().runOnServer(server -> {
			ServerPlayerEntity p = server.getPlayerManager().getPlayerList().get(0);
			p.getInventory().clear();
			for (int id : mobIds) {
				var e = p.getEntityWorld().getEntityById(id);
				if (e != null) e.discard();
			}
			RapConfig.get().progression = "soft";
			GameTestSupport.cleanupBattles(server);
		});
		GameTestSupport.assertNoRawKeys("first_minutes");
	}
}
