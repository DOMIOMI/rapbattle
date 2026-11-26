package dev.rapbattle.test;

import dev.rapbattle.RapConfig;
import dev.rapbattle.battle.*;
import dev.rapbattle.client.ClientBattle;
import dev.rapbattle.client.StyleScreen;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
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

/** Client gametest for 6.17: Progression gates (soft/strict/off), boss gates, daily farm cap, post-dragon Endermen, and UI. */
public class ProgressionGameTest implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext ctx) {
		GameTestSupport.installKeyAudit();
		ctx.getInput().resizeWindow(1280, 720);
		ctx.waitTicks(5);
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			sp.getClientWorld().waitForChunksRender();
			GameTestSupport.applyLang(ctx);

			testSoftAndStrictBlaze(ctx, sp);
			testEffectiveRankGatesAndSkipChoice(ctx, sp);
			testWardenAndDragonGates(ctx, sp);
			testDailyFarmCapAndEndermanAndStyleScreen(ctx, sp);

			GameTestSupport.assertNoRawKeys("ProgressionGameTest");
		}
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

	private void testSoftAndStrictBlaze(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		// 1. Soft mode, 0 Respect, Blaze (rank 4): allowed with warning, NO extra blaze rods on win
		sp.getServer().runOnServer(server -> {
			RapConfig.get().progression = "soft";
			RapConfig.get().dressCode = false;
			ServerPlayerEntity p = server.getPlayerManager().getPlayerList().get(0);
			p.changeGameMode(GameMode.SURVIVAL);
			p.setInvulnerable(true);
			p.setStackInHand(Hand.MAIN_HAND, ItemStack.EMPTY);
			Stats stats = Stats.of(server);
			Stats.Player st = stats.player(p.getUuid());
			st.respect = 0;
			st.speciesWins.clear();
			st.dailySpeciesWins.clear();

			ServerWorld w = p.getEntityWorld();
			clearItems(w, p);
			BlazeEntity blaze = EntityType.BLAZE.create(w, SpawnReason.COMMAND);
			blaze.refreshPositionAndAngles(p.getX(), p.getY(), p.getZ() + 3.0, 180, 0);
			blaze.setAiDisabled(true);
			w.spawnEntity(blaze);
			p.lookAt(net.minecraft.command.argument.EntityAnchorArgumentType.EntityAnchor.EYES, blaze.getEyePos());
			net.fabricmc.fabric.api.event.player.AttackEntityCallback.EVENT.invoker()
				.interact(p, w, Hand.MAIN_HAND, blaze, null);
		});
		ctx.waitTicks(6);
		GameTestSupport.assertTrue(ClientBattle.choice != null && "choice".equals(ClientBattle.choice.type)
			&& ClientBattle.choice.allowed && !ClientBattle.choice.gateWarning.isEmpty(),
			"Soft mode should show choice card with non-empty gateWarning for 0-Respect blaze");
		GameTestSupport.takeScreenshot(ctx, "progression-soft-warning");

		sp.getServer().runOnServer(server -> {
			ServerPlayerEntity p = server.getPlayerManager().getPlayerList().get(0);
			ServerWorld w = p.getEntityWorld();
			Battle b = BattleManager.acceptPendingForTest(p);
			GameTestSupport.assertTrue(b != null, "Soft mode gated blaze should still allow battle");
			b.forceOutcomeForTest(true, false); // non-lethal win: only act bonus rods could spawn
			int rods = countItems(w, p, Items.BLAZE_ROD);
			GameTestSupport.assertTrue(rods == 0, "Soft mode gated blaze must not drop extra blaze rods (got " + rods + ")");
		});
		ctx.waitTicks(5);

		// 2. Soft mode, 300 Respect, Blaze: extra blaze rods DO drop
		sp.getServer().runOnServer(server -> {
			ServerPlayerEntity p = server.getPlayerManager().getPlayerList().get(0);
			ServerWorld w = p.getEntityWorld();
			clearItems(w, p);
			Stats.of(server).player(p.getUuid()).respect = 300;
			BlazeEntity blaze = EntityType.BLAZE.create(w, SpawnReason.COMMAND);
			blaze.refreshPositionAndAngles(p.getX(), p.getY(), p.getZ() + 3.0, 180, 0);
			w.spawnEntity(blaze);
			Battle b = BattleManager.start(p, blaze);
			b.forceOutcomeForTest(true, true);
			int rods = countItems(w, p, Items.BLAZE_ROD);
			GameTestSupport.assertTrue(rods > 0, "Unlocked 300-Respect blaze should drop blaze rods");
		});
		ctx.waitTicks(5);

		// 3. Strict mode, 0 Respect, Blaze: refused with reason
		sp.getServer().runOnServer(server -> {
			RapConfig.get().progression = "strict";
			ServerPlayerEntity p = server.getPlayerManager().getPlayerList().get(0);
			Stats.Player st = Stats.of(server).player(p.getUuid());
			st.respect = 0;
			st.speciesWins.clear();
			ServerWorld w = p.getEntityWorld();
			BlazeEntity blaze = EntityType.BLAZE.create(w, SpawnReason.COMMAND);
			blaze.refreshPositionAndAngles(p.getX(), p.getY(), p.getZ() + 3.0, 180, 0);
			blaze.setAiDisabled(true);
			w.spawnEntity(blaze);
			p.lookAt(net.minecraft.command.argument.EntityAnchorArgumentType.EntityAnchor.EYES, blaze.getEyePos());
			net.fabricmc.fabric.api.event.player.AttackEntityCallback.EVENT.invoker()
				.interact(p, w, Hand.MAIN_HAND, blaze, null);
		});
		ctx.waitTicks(6);
		GameTestSupport.assertTrue(ClientBattle.choice != null && "choice".equals(ClientBattle.choice.type)
			&& !ClientBattle.choice.allowed && !ClientBattle.choice.refusal.isEmpty(),
			"Strict mode should refuse 0-Respect blaze with non-empty refusal");
		GameTestSupport.takeScreenshot(ctx, "progression-strict-refusal");

		// 4. Off mode, 0 Respect, Blaze: allowed without warning
		sp.getServer().runOnServer(server -> {
			RapConfig.get().progression = "off";
			ServerPlayerEntity p = server.getPlayerManager().getPlayerList().get(0);
			BattleManager.clearPendingForTest(p.getUuid());
			ServerWorld w = p.getEntityWorld();
			BlazeEntity blaze = EntityType.BLAZE.create(w, SpawnReason.COMMAND);
			blaze.refreshPositionAndAngles(p.getX(), p.getY(), p.getZ() + 3.0, 180, 0);
			w.spawnEntity(blaze);
			GameTestSupport.assertTrue(Progression.canAutoChallengeMob(p, blaze), "Off mode must allow blaze challenge");
			blaze.discard();
			RapConfig.get().progression = "soft";
		});
		ctx.runOnClient(mc -> ClientBattle.choice = null);
		ctx.waitTicks(5);
	}

	private void testEffectiveRankGatesAndSkipChoice(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		// 1. Vindicator (base rank 4) + emerald in off-hand, 0 Respect, strict: allowed as rank 3; emerald consumed only on start
		sp.getServer().runOnServer(server -> {
			RapConfig.get().progression = "strict";
			RapConfig.get().dressCode = false;
			RapConfig.get().skipChoice = false;
			ServerPlayerEntity p = server.getPlayerManager().getPlayerList().get(0);
			Stats.Player st = Stats.of(server).player(p.getUuid());
			st.respect = 0;
			st.speciesWins.clear();
			p.setStackInHand(Hand.MAIN_HAND, ItemStack.EMPTY);
			p.setStackInHand(Hand.OFF_HAND, new ItemStack(Items.EMERALD, 1));

			ServerWorld w = p.getEntityWorld();
			var vind = EntityType.VINDICATOR.create(w, SpawnReason.COMMAND);
			vind.refreshPositionAndAngles(p.getX(), p.getY(), p.getZ() + 3.0, 180, 0);
			vind.setAiDisabled(true);
			w.spawnEntity(vind);
			net.fabricmc.fabric.api.event.player.AttackEntityCallback.EVENT.invoker()
				.interact(p, w, Hand.MAIN_HAND, vind, null);
			GameTestSupport.assertTrue(p.getOffHandStack().getCount() == 1, "Emerald must NOT be consumed on choice card");
		});
		ctx.waitTicks(5);
		GameTestSupport.assertTrue(ClientBattle.choice != null && ClientBattle.choice.allowed,
			"Bribed vindicator must be allowed at 0 Respect in strict mode");
		sp.getServer().runOnServer(server -> {
			ServerPlayerEntity p = server.getPlayerManager().getPlayerList().get(0);
			Battle b = BattleManager.acceptPendingForTest(p);
			GameTestSupport.assertTrue(b != null, "Bribed vindicator battle must start");
			GameTestSupport.assertTrue(p.getOffHandStack().isEmpty(), "Emerald must be consumed when battle starts");
			GameTestSupport.assertTrue(b.rank == Rank.IRON, "Bribed vindicator battle rank must be IRON (3)");
			b.forceOutcomeForTest(true, true);
			GameTestSupport.cleanupBattles(server);
		});
		ctx.runOnClient(mc -> ClientBattle.choice = null);
		ctx.waitTicks(3);

		// 2. Rank-3 mob (Enderman) in rival gang colors, 0 Respect, strict: refused as rank 4
		sp.getServer().runOnServer(server -> {
			ServerPlayerEntity p = server.getPlayerManager().getPlayerList().get(0);
			Stats.Player st = Stats.of(server).player(p.getUuid());
			st.respect = 0;
			st.speciesWins.clear();
			Gangs.Gang zombieGang = Gangs.of(MobProfiles.of(EntityType.ZOMBIE));
			ItemStack rivalGear = Gangs.randomPiece(zombieGang, new java.util.Random());
			var slot = p.getPreferredEquipmentSlot(rivalGear);
			p.equipStack(slot, rivalGear);

			ServerWorld w = p.getEntityWorld();
			EndermanEntity em = EntityType.ENDERMAN.create(w, SpawnReason.COMMAND);
			em.refreshPositionAndAngles(p.getX(), p.getY(), p.getZ() + 3.0, 180, 0);
			em.setAiDisabled(true);
			w.spawnEntity(em);
			net.fabricmc.fabric.api.event.player.AttackEntityCallback.EVENT.invoker()
				.interact(p, w, Hand.MAIN_HAND, em, null);
			p.equipStack(slot, ItemStack.EMPTY);
			em.discard();
		});
		ctx.waitTicks(5);
		GameTestSupport.assertTrue(ClientBattle.choice != null && !ClientBattle.choice.allowed,
			"Rival gang rank-3 mob must be refused as rank 4 in strict mode at 0 Respect");
		ctx.runOnClient(mc -> ClientBattle.choice = null);

		// 3. skipChoice = true: Warden without mic refused; Blaze in strict at 0 Respect refused
		sp.getServer().runOnServer(server -> {
			RapConfig.get().skipChoice = true;
			RapConfig.get().progression = "strict";
			ServerPlayerEntity p = server.getPlayerManager().getPlayerList().get(0);
			BattleManager.clearPendingForTest(p.getUuid());
			p.setStackInHand(Hand.MAIN_HAND, ItemStack.EMPTY);
			p.setStackInHand(Hand.OFF_HAND, ItemStack.EMPTY);
			Stats.Player st = Stats.of(server).player(p.getUuid());
			st.respect = 0;
			st.speciesWins.clear();

			ServerWorld w = p.getEntityWorld();
			WardenEntity warden = EntityType.WARDEN.create(w, SpawnReason.COMMAND);
			warden.refreshPositionAndAngles(p.getX(), p.getY(), p.getZ() + 3.0, 180, 0);
			warden.setAiDisabled(true);
			w.spawnEntity(warden);
			net.fabricmc.fabric.api.event.player.AttackEntityCallback.EVENT.invoker()
				.interact(p, w, Hand.MAIN_HAND, warden, null);
			GameTestSupport.assertTrue(BattleManager.byPlayer(p.getUuid()) == null,
				"skipChoice=true must NOT start Warden battle without mic");
			warden.discard();

			BlazeEntity blaze = EntityType.BLAZE.create(w, SpawnReason.COMMAND);
			blaze.refreshPositionAndAngles(p.getX(), p.getY(), p.getZ() + 3.0, 180, 0);
			blaze.setAiDisabled(true);
			w.spawnEntity(blaze);
			net.fabricmc.fabric.api.event.player.AttackEntityCallback.EVENT.invoker()
				.interact(p, w, Hand.MAIN_HAND, blaze, null);
			GameTestSupport.assertTrue(BattleManager.byPlayer(p.getUuid()) == null,
				"skipChoice=true must NOT start strict 0-Respect blaze battle");
			blaze.discard();

			RapConfig.get().skipChoice = false;
			RapConfig.get().progression = "soft";
			BattleManager.clearPendingForTest(p.getUuid());
		});
		ctx.waitTicks(3);
	}

	private void testWardenAndDragonGates(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		// Warden: no mic refused -> homemade mic + 500 Respect + rank 4 win allowed
		sp.getServer().runOnServer(server -> {
			RapConfig.get().progression = "soft";
			ServerPlayerEntity p = server.getPlayerManager().getPlayerList().get(0);
			p.setStackInHand(Hand.OFF_HAND, ItemStack.EMPTY);
			Stats.Player st = Stats.of(server).player(p.getUuid());
			st.respect = 600;
			st.speciesWins.put("blaze", 1);
			ServerWorld w = p.getEntityWorld();
			WardenEntity warden = EntityType.WARDEN.create(w, SpawnReason.COMMAND);
			warden.refreshPositionAndAngles(p.getX(), p.getY(), p.getZ() + 3.0, 180, 0);
			w.spawnEntity(warden);
			Opponent opp = Opponent.of(warden, new java.util.Random(), p, "ru");
			GameTestSupport.assertTrue(!Progression.check(p, warden, opp).allowed(), "Warden must refuse player with no mic");
			p.setStackInHand(Hand.OFF_HAND, makeHomemadeMic());
			GameTestSupport.assertTrue(Progression.check(p, warden, opp).allowed(), "Warden must accept homemade mic + 500 Respect + rank 4 win");
			warden.discard();
		});

		// Dragon in The End (crystals cleared)
		sp.getServer().runOnServer(server -> {
			ServerPlayerEntity p = server.getPlayerManager().getPlayerList().get(0);
			p.changeGameMode(GameMode.CREATIVE);
			p.getAbilities().flying = true;
			p.sendAbilitiesUpdate();
			ServerWorld ew = server.getWorld(World.END);
			p.teleport(ew, 0.5, 70, 0.5, java.util.Set.of(), p.getYaw(), p.getPitch(), false);
		});
		ctx.waitTicks(35);
		sp.getServer().runOnServer(server -> {
			ServerPlayerEntity p = server.getPlayerManager().getPlayerList().get(0);
			ServerWorld ew = server.getWorld(World.END);
			List<EnderDragonEntity> dragons = ew.getEntitiesByClass(EnderDragonEntity.class, p.getBoundingBox().expand(256), Entity::isAlive);
			GameTestSupport.assertTrue(!dragons.isEmpty(), "Dragon should exist in The End");
			EnderDragonEntity dragon = dragons.get(0);
			for (EndCrystalEntity c : ew.getEntitiesByClass(EndCrystalEntity.class, dragon.getBoundingBox().expand(192), Entity::isAlive)) {
				c.kill(ew);
			}
			Stats.Player st = Stats.of(server).player(p.getUuid());
			st.respect = 900;
			st.speciesWins.put("iron_golem", 1);

			// Homemade mic refused
			p.setStackInHand(Hand.OFF_HAND, makeHomemadeMic());
			GameTestSupport.assertTrue(!DragonBattle.canChallenge(p, dragon), "Dragon must refuse homemade mic");

			// Trophy mic + 900 Respect refused (shows message in chat)
			p.setStackInHand(Hand.OFF_HAND, makeTrophyMic());
			GameTestSupport.assertTrue(!DragonBattle.canChallenge(p, dragon), "Dragon must refuse 900 Respect");
		});
		ctx.waitTicks(5);
		GameTestSupport.takeScreenshot(ctx, "progression-dragon-refusal");

		sp.getServer().runOnServer(server -> {
			ServerPlayerEntity p = server.getPlayerManager().getPlayerList().get(0);
			ServerWorld ew = server.getWorld(World.END);
			EnderDragonEntity dragon = ew.getEntitiesByClass(EnderDragonEntity.class, p.getBoundingBox().expand(256), Entity::isAlive).get(0);
			Stats.Player st = Stats.of(server).player(p.getUuid());
			st.respect = 1000;
			GameTestSupport.assertTrue(DragonBattle.canChallenge(p, dragon), "Dragon must allow trophy mic + 1000 Respect + rank 5 win");
			Battle b = BattleManager.start(p, dragon);
			b.forceOutcomeForTest(true, true);
			GameTestSupport.assertTrue(st.dragonWon, "dragonWon must be set after dragon rap battle victory");

			ServerWorld ow = server.getWorld(World.OVERWORLD);
			p.teleport(ow, 0.5, 80, 0.5, java.util.Set.of(), p.getYaw(), p.getPitch(), false);
		});
		ctx.waitTicks(15);
	}

	private void testDailyFarmCapAndEndermanAndStyleScreen(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		// Daily farm cap: 4th blaze win in the same day drops 0 extra rods
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
			b.forceOutcomeForTest(true, false);
			int rods = countItems(w, p, Items.BLAZE_ROD);
			GameTestSupport.assertTrue(rods == 0, "4th blaze win same day must yield 0 bonus blaze rods (got " + rods + ")");

			// Enderman post-dragon: trophy mic stops eye-contact aggro
			EndermanEntity enderman = EntityType.ENDERMAN.create(w, SpawnReason.COMMAND);
			enderman.refreshPositionAndAngles(p.getX(), p.getY(), p.getZ() + 4.0, 180, 0);
			w.spawnEntity(enderman);
			p.lookAt(net.minecraft.command.argument.EntityAnchorArgumentType.EntityAnchor.EYES, enderman.getEyePos());
			p.setStackInHand(Hand.OFF_HAND, makeTrophyMic());
			GameTestSupport.assertTrue(!enderman.isAngry(), "Enderman must not be angry when dragonWon && trophy mic");
			p.setStackInHand(Hand.OFF_HAND, ItemStack.EMPTY);
			enderman.discard();

			// Prep StyleScreen state
			st.respect = 320;
			st.speciesWins.clear();
			st.speciesWins.put("enderman", 1);
			st.speciesWins.put("guardian", 1);
		});
		ctx.waitTicks(5);
		ctx.setScreen(() -> new StyleScreen(null));
		ctx.waitTicks(8);
		GameTestSupport.takeScreenshot(ctx, "progression-style-screen");
		ctx.setScreen(() -> null);
	}
}
