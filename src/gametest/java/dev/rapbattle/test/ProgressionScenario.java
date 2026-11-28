package dev.rapbattle.test;

import dev.rapbattle.RapConfig;
import dev.rapbattle.battle.*;
import dev.rapbattle.client.ClientBattle;
import dev.rapbattle.client.StyleScreen;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.boss.dragon.EnderDragonEntity;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.mob.BlazeEntity;
import net.minecraft.entity.mob.EndermanEntity;
import net.minecraft.entity.mob.WardenEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Hand;
import net.minecraft.world.GameMode;
import net.minecraft.world.World;

import java.util.List;

import static dev.rapbattle.test.GameTestSupport.assertTrue;

/** Progression gates (strict consolidated, soft/off, bribes/rival), boss gates, daily cap, post-dragon Endermen, and StyleScreen. */
public final class ProgressionScenario {
	private ProgressionScenario() {}

	public static void run(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		testStrictGatesConsolidated(ctx, sp);
		testSoftAndEffectiveGates(ctx, sp);
		testWardenAndDragonGates(ctx, sp);
		testDailyFarmCapAndEndermanAndStyleScreen(ctx, sp);
		GameTestSupport.assertNoRawKeys("progression");
	}

	private static void clearItems(ServerWorld w, ServerPlayerEntity p) {
		for (ItemEntity ie : w.getEntitiesByClass(ItemEntity.class, p.getBoundingBox().expand(24), e -> true)) {
			ie.discard();
		}
	}

	private static int countItems(ServerWorld w, ServerPlayerEntity p, net.minecraft.item.Item item) {
		int total = 0;
		for (ItemEntity ie : w.getEntitiesByClass(ItemEntity.class, p.getBoundingBox().expand(24), e -> e.isAlive())) {
			if (ie.getStack().isOf(item)) total += ie.getStack().getCount();
		}
		return total;
	}

	private static ItemStack makeHomemadeMic() {
		ItemStack s = new ItemStack(Items.LIGHTNING_ROD);
		NbtCompound tag = new NbtCompound();
		tag.putString(Trophies.NBT_KEY, Trophies.TrophyPerk.HOMEMADE.key);
		s.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(tag));
		return s;
	}

	private static ItemStack makeTrophyMic() {
		ItemStack s = new ItemStack(Items.LIGHTNING_ROD);
		NbtCompound tag = new NbtCompound();
		tag.putString(Trophies.NBT_KEY, Trophies.TrophyPerk.DEFAULT.key);
		tag.putString(Trophies.NBT_TIER_KEY, Trophies.TIER_HIGH);
		s.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(tag));
		return s;
	}

	/** One gate setup (strict, 0 Respect) checks Blaze, Warden without mic, and skipChoice together. */
	private static void testStrictGatesConsolidated(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		sp.getServer().runOnServer(server -> {
			RapConfig.get().progression = "strict";
			RapConfig.get().dressCode = false;
			RapConfig.get().skipChoice = false;
			ServerPlayerEntity p = server.getPlayerManager().getPlayerList().get(0);
			p.setStackInHand(Hand.MAIN_HAND, ItemStack.EMPTY);
			p.setStackInHand(Hand.OFF_HAND, ItemStack.EMPTY);
			Stats.Player st = Stats.of(server).player(p.getUuid());
			st.respect = 0;
			st.speciesWins.clear();

			ServerWorld w = p.getEntityWorld();
			BlazeEntity blaze = EntityType.BLAZE.create(w, SpawnReason.COMMAND);
			blaze.refreshPositionAndAngles(p.getX(), p.getY(), p.getZ() + 3.0, 180, 0);
			blaze.setAiDisabled(true);
			w.spawnEntity(blaze);
			net.fabricmc.fabric.api.event.player.AttackEntityCallback.EVENT.invoker()
				.interact(p, w, Hand.MAIN_HAND, blaze, null);
			blaze.discard();
		});
		ctx.waitTicks(4);
		assertTrue(ClientBattle.choice != null && "choice".equals(ClientBattle.choice.type)
			&& !ClientBattle.choice.allowed && !ClientBattle.choice.refusal.isEmpty(),
			"Strict mode must refuse 0-Respect blaze with non-empty refusal");
		ctx.runOnClient(mc -> ClientBattle.choice = null);

		sp.getServer().runOnServer(server -> {
			RapConfig.get().skipChoice = true;
			RapConfig.get().progression = "strict";
			ServerPlayerEntity p = server.getPlayerManager().getPlayerList().get(0);
			BattleManager.clearPendingForTest(p.getUuid());
			ServerWorld w = p.getEntityWorld();

			WardenEntity warden = EntityType.WARDEN.create(w, SpawnReason.COMMAND);
			warden.refreshPositionAndAngles(p.getX(), p.getY(), p.getZ() + 3.0, 180, 0);
			warden.setAiDisabled(true);
			w.spawnEntity(warden);
			net.fabricmc.fabric.api.event.player.AttackEntityCallback.EVENT.invoker()
				.interact(p, w, Hand.MAIN_HAND, warden, null);
			assertTrue(BattleManager.byPlayer(p.getUuid()) == null,
				"skipChoice=true must NOT start Warden battle without mic");
			warden.discard();

			BlazeEntity blaze = EntityType.BLAZE.create(w, SpawnReason.COMMAND);
			blaze.refreshPositionAndAngles(p.getX(), p.getY(), p.getZ() + 3.0, 180, 0);
			blaze.setAiDisabled(true);
			w.spawnEntity(blaze);
			net.fabricmc.fabric.api.event.player.AttackEntityCallback.EVENT.invoker()
				.interact(p, w, Hand.MAIN_HAND, blaze, null);
			assertTrue(BattleManager.byPlayer(p.getUuid()) == null,
				"skipChoice=true must NOT start strict 0-Respect blaze battle");
			blaze.discard();

			RapConfig.get().skipChoice = false;
			RapConfig.get().progression = "soft";
			BattleManager.clearPendingForTest(p.getUuid());
		});
	}

	private static void testSoftAndEffectiveGates(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		sp.getServer().runOnServer(server -> {
			RapConfig.get().progression = "soft";
			RapConfig.get().dressCode = false;
			ServerPlayerEntity p = server.getPlayerManager().getPlayerList().get(0);
			p.setStackInHand(Hand.MAIN_HAND, ItemStack.EMPTY);
			Stats.Player st = Stats.of(server).player(p.getUuid());
			st.respect = 0;
			st.speciesWins.clear();
			st.dailySpeciesWins.clear();

			ServerWorld w = p.getEntityWorld();
			clearItems(w, p);
			BlazeEntity blaze = EntityType.BLAZE.create(w, SpawnReason.COMMAND);
			blaze.refreshPositionAndAngles(p.getX(), p.getY(), p.getZ() + 3.0, 180, 0);
			blaze.setAiDisabled(true);
			w.spawnEntity(blaze);
			net.fabricmc.fabric.api.event.player.AttackEntityCallback.EVENT.invoker()
				.interact(p, w, Hand.MAIN_HAND, blaze, null);
		});
		ctx.waitTicks(4);
		assertTrue(ClientBattle.choice != null && "choice".equals(ClientBattle.choice.type)
			&& ClientBattle.choice.allowed && !ClientBattle.choice.gateWarning.isEmpty(),
			"Soft mode should show choice card with non-empty gateWarning for 0-Respect blaze");
		ctx.runOnClient(mc -> ClientBattle.choice = null);

		sp.getServer().runOnServer(server -> {
			ServerPlayerEntity p = server.getPlayerManager().getPlayerList().get(0);
			ServerWorld w = p.getEntityWorld();
			Battle b = BattleManager.acceptPendingForTest(p);
			assertTrue(b != null, "Soft mode gated blaze should still allow battle");
			b.jumpToForTest(Battle.Phase.RESULT, true, false, 90, 75);
			int rods = countItems(w, p, Items.BLAZE_ROD);
			assertTrue(rods == 0, "Soft mode gated blaze must not drop extra blaze rods (got " + rods + ")");

			// 300 Respect -> extra rods DO drop
			clearItems(w, p);
			Stats.of(server).player(p.getUuid()).respect = 300;
			BlazeEntity blaze = EntityType.BLAZE.create(w, SpawnReason.COMMAND);
			blaze.refreshPositionAndAngles(p.getX(), p.getY(), p.getZ() + 3.0, 180, 0);
			w.spawnEntity(blaze);
			Battle b2 = BattleManager.start(p, blaze);
			b2.jumpToForTest(Battle.Phase.RESULT);
			int rods2 = countItems(w, p, Items.BLAZE_ROD);
			assertTrue(rods2 > 0, "Unlocked 300-Respect blaze should drop blaze rods");
			blaze.discard();

			// Off mode -> allowed
			RapConfig.get().progression = "off";
			BattleManager.clearPendingForTest(p.getUuid());
			BlazeEntity blazeOff = EntityType.BLAZE.create(w, SpawnReason.COMMAND);
			blazeOff.refreshPositionAndAngles(p.getX(), p.getY(), p.getZ() + 3.0, 180, 0);
			w.spawnEntity(blazeOff);
			assertTrue(Progression.canAutoChallengeMob(p, blazeOff), "Off mode must allow blaze challenge");
			blazeOff.discard();

			// Bribed Vindicator (rank 4 -> 3 with emerald)
			RapConfig.get().progression = "strict";
			Stats.Player st = Stats.of(server).player(p.getUuid());
			st.respect = 0;
			st.speciesWins.clear();
			p.setStackInHand(Hand.OFF_HAND, new ItemStack(Items.EMERALD, 1));
			var vind = EntityType.VINDICATOR.create(w, SpawnReason.COMMAND);
			vind.refreshPositionAndAngles(p.getX(), p.getY(), p.getZ() + 3.0, 180, 0);
			vind.setAiDisabled(true);
			w.spawnEntity(vind);
			net.fabricmc.fabric.api.event.player.AttackEntityCallback.EVENT.invoker()
				.interact(p, w, Hand.MAIN_HAND, vind, null);
			assertTrue(p.getOffHandStack().getCount() == 1, "Emerald must NOT be consumed on choice card");
			Battle bVind = BattleManager.acceptPendingForTest(p);
			assertTrue(bVind != null, "Bribed vindicator battle must start");
			assertTrue(p.getOffHandStack().isEmpty(), "Emerald must be consumed when battle starts");
			assertTrue(bVind.rank == Rank.IRON, "Bribed vindicator battle rank must be IRON (3)");
			bVind.jumpToForTest(Battle.Phase.RESULT);
			vind.discard();

			// Rank-3 mob (Enderman) in rival gang colors -> refused as rank 4 in strict mode at 0 Respect
			Gangs.Gang zombieGang = Gangs.of(MobProfiles.of(EntityType.ZOMBIE));
			ItemStack rivalGear = Gangs.randomPiece(zombieGang, new java.util.Random());
			var slot = p.getPreferredEquipmentSlot(rivalGear);
			p.equipStack(slot, rivalGear);
			EndermanEntity em = EntityType.ENDERMAN.create(w, SpawnReason.COMMAND);
			em.refreshPositionAndAngles(p.getX(), p.getY(), p.getZ() + 3.0, 180, 0);
			em.setAiDisabled(true);
			w.spawnEntity(em);
			net.fabricmc.fabric.api.event.player.AttackEntityCallback.EVENT.invoker()
				.interact(p, w, Hand.MAIN_HAND, em, null);
			p.equipStack(slot, ItemStack.EMPTY);
			em.discard();
		});
		ctx.waitTicks(4);
		assertTrue(ClientBattle.choice != null && !ClientBattle.choice.allowed,
			"Rival gang rank-3 mob must be refused as rank 4 in strict mode at 0 Respect");
		ctx.runOnClient(mc -> ClientBattle.choice = null);
		sp.getServer().runOnServer(server -> {
			RapConfig.get().progression = "soft";
			GameTestSupport.cleanupBattles(server);
		});
	}

	private static void testWardenAndDragonGates(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		sp.getServer().runOnServer(server -> {
			RapConfig.get().progression = "soft";
			ServerPlayerEntity p = server.getPlayerManager().getPlayerList().get(0);
			p.setStackInHand(Hand.OFF_HAND, ItemStack.EMPTY);
			p.getInventory().setStack(35, ItemStack.EMPTY); // no mic anywhere for the first check
			Stats.Player st = Stats.of(server).player(p.getUuid());
			st.respect = 600;
			st.speciesWins.put("blaze", 1);
			ServerWorld w = p.getEntityWorld();
			WardenEntity warden = EntityType.WARDEN.create(w, SpawnReason.COMMAND);
			warden.refreshPositionAndAngles(p.getX(), p.getY(), p.getZ() + 3.0, 180, 0);
			w.spawnEntity(warden);
			Opponent opp = Opponent.of(warden, new java.util.Random(), p, "ru");
			assertTrue(!Progression.check(p, warden, opp).allowed(), "Warden must refuse player with no mic");
			p.setStackInHand(Hand.OFF_HAND, makeHomemadeMic());
			assertTrue(Progression.check(p, warden, opp).allowed(), "Warden must accept homemade mic + 500 Respect + rank 4 win");
			warden.discard();

			p.changeGameMode(GameMode.CREATIVE);
			p.getAbilities().flying = true;
			p.sendAbilitiesUpdate();
			ServerWorld ew = server.getWorld(World.END);
			p.teleport(ew, 0.5, 70, 0.5, java.util.Set.of(), p.getYaw(), p.getPitch(), false);
		});
		ctx.waitTicks(15);
		sp.getServer().runOnServer(server -> {
			ServerPlayerEntity p = server.getPlayerManager().getPlayerList().get(0);
			ServerWorld ew = server.getWorld(World.END);
			List<EnderDragonEntity> dragons = ew.getEntitiesByClass(EnderDragonEntity.class, p.getBoundingBox().expand(256), Entity::isAlive);
			EnderDragonEntity dragon;
			if (dragons.isEmpty()) {
				dragon = EntityType.ENDER_DRAGON.create(ew, SpawnReason.COMMAND);
				assertTrue(dragon != null, "Must be able to create Ender Dragon in The End");
				dragon.refreshPositionAndAngles(0.5, 80, 0.5, 0, 0);
				ew.spawnEntity(dragon);
			} else {
				dragon = dragons.get(0);
			}
			for (EndCrystalEntity c : ew.getEntitiesByClass(EndCrystalEntity.class, dragon.getBoundingBox().expand(192), Entity::isAlive)) {
				c.kill(ew);
			}
			Stats.Player st = Stats.of(server).player(p.getUuid());
			st.respect = 900;
			st.speciesWins.put("iron_golem", 1);
			st.rankedSpeciesWins.put("iron_golem:5", 1); // gates read ranked wins since 6.29

			p.setStackInHand(Hand.OFF_HAND, makeHomemadeMic());
			assertTrue(!DragonBattle.canChallenge(p, dragon), "Dragon must refuse homemade mic");

			p.setStackInHand(Hand.OFF_HAND, makeTrophyMic());
			assertTrue(!DragonBattle.canChallenge(p, dragon), "Dragon must refuse 900 Respect");

			st.respect = 1000;
			assertTrue(DragonBattle.canChallenge(p, dragon), "Dragon must allow trophy mic + 1000 Respect + rank 5 win");
			Battle b = BattleManager.start(p, dragon);
			b.jumpToForTest(Battle.Phase.RESULT);
			assertTrue(st.dragonWon, "dragonWon must be set after dragon rap battle victory");

			ServerWorld ow = server.getWorld(World.OVERWORLD);
			p.teleport(ow, 0.5, 64, 0.5, java.util.Set.of(), p.getYaw(), p.getPitch(), false);
		});
		ctx.waitTicks(5);
	}

	private static void testDailyFarmCapAndEndermanAndStyleScreen(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		sp.getServer().runOnServer(server -> {
			ServerPlayerEntity p = server.getPlayerManager().getPlayerList().get(0);
			p.changeGameMode(GameMode.SURVIVAL);
			p.setInvulnerable(true);
			ServerWorld w = p.getEntityWorld();
			clearItems(w, p);
			Stats.Player st = Stats.of(server).player(p.getUuid());
			st.respect = 400;
			long day = ProgressionRules.currentDay(server.getOverworld().getTime());
			st.dailyDay = day;
			st.dailySpeciesWins.put("blaze", 3);

			BlazeEntity blaze = EntityType.BLAZE.create(w, SpawnReason.COMMAND);
			blaze.refreshPositionAndAngles(p.getX(), p.getY(), p.getZ() + 3.0, 180, 0);
			w.spawnEntity(blaze);
			Battle b = BattleManager.start(p, blaze);
			b.jumpToForTest(Battle.Phase.RESULT, true, false, 90, 75);
			int rods = countItems(w, p, Items.BLAZE_ROD);
			assertTrue(rods == 0, "4th blaze win same day must yield 0 bonus blaze rods (got " + rods + ")");
			blaze.discard();

			EndermanEntity enderman = EntityType.ENDERMAN.create(w, SpawnReason.COMMAND);
			enderman.refreshPositionAndAngles(p.getX(), p.getY(), p.getZ() + 4.0, 180, 0);
			w.spawnEntity(enderman);
			p.lookAt(net.minecraft.command.argument.EntityAnchorArgumentType.EntityAnchor.EYES, enderman.getEyePos());
			p.setStackInHand(Hand.OFF_HAND, makeTrophyMic());
			assertTrue(!enderman.isAngry(), "Enderman must not be angry when dragonWon && trophy mic");
			p.setStackInHand(Hand.OFF_HAND, ItemStack.EMPTY);
			enderman.discard();

			st.respect = 320;
			st.speciesWins.clear();
			st.speciesWins.put("enderman", 1);
			st.speciesWins.put("guardian", 1);
		});
		ctx.setScreen(() -> new StyleScreen(null));
		ctx.waitTicks(3);
		ctx.setScreen(() -> null);
	}
}
