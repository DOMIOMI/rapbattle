package dev.rapbattle.test;

import dev.rapbattle.RapConfig;
import dev.rapbattle.battle.*;
import dev.rapbattle.client.ClientBattle;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Hand;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static dev.rapbattle.test.GameTestSupport.assertTrue;

/**
 * 6.30 First session, step by step as a new player plays it (strict progression, dress code on):
 * 1. no mic: every mob refuses with "need a mic";
 * 2. homemade mic in the inventory: zombie, skeleton, spider, sheep, cow, pig, chicken battle a naked player;
 * 3. rank 2 (chain-helmet zombie): naked refused, full leather ok;
 * 4. rank 3 (iron-helmet zombie): leather refused, full copper armor ok;
 * 5. rank 4 (creeper): closed until 2 rank-3 species wins or 300 Respect, then open in iron;
 * plus choice-card screenshots (no mic, allowed with key buttons, need armor) in ru and en.
 */
public final class FirstSessionScenario {
	private FirstSessionScenario() {}

	private static final int[] TARGET = {-1};

	private static ItemStack homemadeMic() {
		ItemStack mic = new ItemStack(Items.LIGHTNING_ROD);
		NbtCompound tag = new NbtCompound();
		tag.putString(Trophies.NBT_KEY, Trophies.TrophyPerk.HOMEMADE.key);
		mic.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(tag));
		return mic;
	}

	private static void wear(ServerPlayerEntity p, Item head, Item chest, Item legs, Item feet) {
		p.equipStack(EquipmentSlot.HEAD, head == null ? ItemStack.EMPTY : new ItemStack(head));
		p.equipStack(EquipmentSlot.CHEST, chest == null ? ItemStack.EMPTY : new ItemStack(chest));
		p.equipStack(EquipmentSlot.LEGS, legs == null ? ItemStack.EMPTY : new ItemStack(legs));
		p.equipStack(EquipmentSlot.FEET, feet == null ? ItemStack.EMPTY : new ItemStack(feet));
	}

	private static <T extends MobEntity> T spawn(ServerWorld w, ServerPlayerEntity p, EntityType<T> type, double dx, Item helmet) {
		T m = type.create(w, SpawnReason.COMMAND);
		m.refreshPositionAndAngles(p.getX() + dx, p.getY(), p.getZ() + 3, 180, 0);
		m.setAiDisabled(true);
		w.spawnEntity(m);
		// After spawning: StarterGear rolls random armor on load (more likely far from spawn and on later days).
		for (EquipmentSlot s : EquipmentSlot.values()) {
			if (s.isArmorSlot()) m.equipStack(s, ItemStack.EMPTY);
		}
		if (helmet != null) {
			m.equipStack(EquipmentSlot.HEAD, new ItemStack(helmet));
			m.setEquipmentDropChance(EquipmentSlot.HEAD, 0f);
		}
		return m;
	}

	private static boolean allowed(ServerPlayerEntity p, MobEntity m) {
		return Progression.canStart(p, m, true, 0, new Random(0)).allowed();
	}

	/** Hits {@link #TARGET} with an empty hand so the server opens the choice card, then screenshots it. */
	private static void shootChoice(ClientGameTestContext ctx, TestSingleplayerContext sp, String name) {
		sp.getServer().runOnServer(server -> {
			ServerPlayerEntity p = server.getPlayerManager().getPlayerList().get(0);
			var e = p.getEntityWorld().getEntityById(TARGET[0]);
			p.networkHandler.requestTeleport(p.getX(), p.getY(), p.getZ(), 0f, 25f);
			if (e != null) p.attack(e);
		});
		ctx.waitTicks(5);
		GameTestSupport.takeScreenshot(ctx, name);
		sp.getServer().runOnServer(server -> {
			ServerPlayerEntity p = server.getPlayerManager().getPlayerList().get(0);
			BattleManager.clearPendingForTest(p.getUuid());
		});
		ctx.runOnClient(c -> ClientBattle.choice = null);
		ctx.waitTicks(2);
	}

	public static void run(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		List<MobEntity> spawned = new ArrayList<>();
		int[] ids = new int[3]; // sheep (allowed), naked zombie (no mic), iron zombie (need armor)

		// 0. The first mic is the cheapest craft there is: any planks over a stick (6.31).
		sp.getServer().runOnServer(server -> {
			ServerWorld w = server.getOverworld();
			for (Item planks : new Item[]{Items.OAK_PLANKS, Items.SPRUCE_PLANKS, Items.CRIMSON_PLANKS}) {
				var input = net.minecraft.recipe.input.CraftingRecipeInput.create(1, 2,
					List.of(new ItemStack(planks), new ItemStack(Items.STICK)));
				var match = server.getRecipeManager().getFirstMatch(net.minecraft.recipe.RecipeType.CRAFTING, input, w);
				assertTrue(match.isPresent(), "Planks over a stick must craft the homemade mic (" + planks + ")");
				ItemStack mic = match.get().value().craft(input, w.getRegistryManager());
				assertTrue(Trophies.perkOf(mic) == Trophies.TrophyPerk.HOMEMADE, "Crafted item must be the homemade mic, got " + mic);
			}
		});

		// 1-2. Mic first, then every starter mob is open to a naked player.
		sp.getServer().runOnServer(server -> {
			ServerPlayerEntity p = server.getPlayerManager().getPlayerList().get(0);
			ServerWorld w = p.getEntityWorld();
			RapConfig.get().progression = "strict";
			RapConfig.get().dressCode = true;
			p.getInventory().clear();
			p.setStackInHand(Hand.MAIN_HAND, ItemStack.EMPTY);
			p.setStackInHand(Hand.OFF_HAND, ItemStack.EMPTY);
			wear(p, null, null, null, null);
			Stats.Player st = Stats.of(server).player(p.getUuid());
			st.respect = 0;
			st.speciesWins.clear();
			st.rankedSpeciesWins.clear();

			MobEntity zombie = spawn(w, p, EntityType.ZOMBIE, 0, null);
			MobEntity skeleton = spawn(w, p, EntityType.SKELETON, -6, null);
			MobEntity spider = spawn(w, p, EntityType.SPIDER, 6, null);
			MobEntity sheep = spawn(w, p, EntityType.SHEEP, -3, null);
			MobEntity cow = spawn(w, p, EntityType.COW, 3, null);
			MobEntity pig = spawn(w, p, EntityType.PIG, -9, null);
			MobEntity chicken = spawn(w, p, EntityType.CHICKEN, 9, null);
			List<MobEntity> starters = List.of(zombie, skeleton, spider, sheep, cow, pig, chicken);
			spawned.addAll(starters);

			for (MobEntity m : starters) {
				Opponent o = Opponent.of(m, new Random(0), p, "ru");
				assertTrue(o.rank() == Rank.LEATHER, "Starter mob " + o.profile().id() + " must be rank 1, got " + o.rank());
				Progression.StartCheck sc = Progression.canStart(p, m, true, 0, new Random(0));
				assertTrue(!sc.allowed() && !sc.micAllowed(), "Without a mic " + o.profile().id() + " must refuse for the mic");
			}
			p.getInventory().setStack(8, homemadeMic());
			for (MobEntity m : starters) {
				assertTrue(allowed(p, m), "With a mic and no armor " + m.getType().getUntranslatedName() + " must battle");
			}
			p.getInventory().setStack(8, ItemStack.EMPTY);
			ids[0] = sheep.getId();
			ids[1] = zombie.getId();
		});
		TARGET[0] = ids[1];
		shootChoice(ctx, sp, "6.30-session-1-no-mic");

		sp.getServer().runOnServer(server -> {
			ServerPlayerEntity p = server.getPlayerManager().getPlayerList().get(0);
			p.getInventory().setStack(8, homemadeMic());
		});
		TARGET[0] = ids[0];
		shootChoice(ctx, sp, "6.30-session-2-choice-buttons");

		// 3-4. Armored mobs: chain helmet = rank 2 (leather ok), iron helmet = rank 3 (copper ok).
		sp.getServer().runOnServer(server -> {
			ServerPlayerEntity p = server.getPlayerManager().getPlayerList().get(0);
			ServerWorld w = p.getEntityWorld();
			MobEntity chainZ = spawn(w, p, EntityType.ZOMBIE, -1.5, Items.CHAINMAIL_HELMET);
			MobEntity ironZ = spawn(w, p, EntityType.ZOMBIE, 1.5, Items.IRON_HELMET);
			spawned.add(chainZ);
			spawned.add(ironZ);
			assertTrue(Opponent.of(chainZ, new Random(0), p, "ru").rank() == Rank.CHAIN, "Chain-helmet zombie must be rank 2");
			assertTrue(Opponent.of(ironZ, new Random(0), p, "ru").rank() == Rank.IRON, "Iron-helmet zombie must be rank 3");

			assertTrue(!allowed(p, chainZ), "Rank 2 must refuse a naked player");
			wear(p, Items.LEATHER_HELMET, Items.LEATHER_CHESTPLATE, Items.LEATHER_LEGGINGS, Items.LEATHER_BOOTS);
			assertTrue(allowed(p, chainZ), "Rank 2 must battle a player in full leather");
			assertTrue(!allowed(p, ironZ), "Rank 3 must refuse a player in leather");
			ids[2] = ironZ.getId();
		});
		TARGET[0] = ids[2];
		shootChoice(ctx, sp, "6.30-session-3-need-armor");

		// 6.32: refused for armor -> put the armor on -> hit again = a fresh battle offer, not a brawl.
		float[] hp = new float[1];
		sp.getServer().runOnServer(server -> {
			ServerPlayerEntity p = server.getPlayerManager().getPlayerList().get(0);
			MobEntity ironZ = (MobEntity) p.getEntityWorld().getEntityById(ids[2]);
			BattleManager.clearAllForTest(); // forget the refusal from the screenshot above: this is a fresh first hit
			p.attack(ironZ); // in leather: refused, the card stays open
			assertTrue(BattleManager.hasPending(p.getUuid()), "Refusal must leave the choice card open");
			wear(p, Items.COPPER_HELMET, Items.COPPER_CHESTPLATE, Items.COPPER_LEGGINGS, Items.COPPER_BOOTS);
			hp[0] = ironZ.getHealth();
			p.attack(ironZ); // now dressed right
			assertTrue(ironZ.getHealth() == hp[0], "A hit after fixing the outfit must not be a brawl (mob took damage)");
			assertTrue(BattleManager.hasPending(p.getUuid()), "A hit after fixing the outfit must open a new battle offer");
		});
		ctx.waitTicks(3);
		ctx.runOnClient(c -> assertTrue(ClientBattle.choice != null && ClientBattle.choice.allowed,
			"The new offer must be an open battle card"));
		GameTestSupport.takeScreenshot(ctx, "6.32-session-refused-then-dressed");
		// Still not dressed right: the second hit stays a fight, as before.
		sp.getServer().runOnServer(server -> {
			ServerPlayerEntity p = server.getPlayerManager().getPlayerList().get(0);
			BattleManager.clearPendingForTest(p.getUuid());
			MobEntity ironZ = (MobEntity) p.getEntityWorld().getEntityById(ids[2]);
			wear(p, Items.LEATHER_HELMET, Items.LEATHER_CHESTPLATE, Items.LEATHER_LEGGINGS, Items.LEATHER_BOOTS);
			p.attack(ironZ);
			hp[0] = ironZ.getHealth();
			p.attack(ironZ);
			assertTrue(!BattleManager.hasPending(p.getUuid()), "Second hit while still refused must close the card (a fight)");
			ironZ.setHealth(ironZ.getMaxHealth());
			ironZ.setTarget(null);
			BattleManager.clearAllForTest();
		});
		ctx.runOnClient(c -> ClientBattle.choice = null);
		ctx.waitTicks(2);

		// 5. Rank 4 gate: copper opens rank 3, two rank-3 species wins open rank 4.
		sp.getServer().runOnServer(server -> {
			ServerPlayerEntity p = server.getPlayerManager().getPlayerList().get(0);
			ServerWorld w = p.getEntityWorld();
			MobEntity ironZ = (MobEntity) w.getEntityById(ids[2]);
			wear(p, Items.COPPER_HELMET, Items.COPPER_CHESTPLATE, Items.COPPER_LEGGINGS, Items.COPPER_BOOTS);
			assertTrue(Outfit.of(p, "ru").level() == 2, "Full copper armor must be outfit level 2");
			assertTrue(allowed(p, ironZ), "Rank 3 must battle a player in full copper");

			MobEntity creeper = spawn(w, p, EntityType.CREEPER, 4.5, null);
			MobEntity ironSk = spawn(w, p, EntityType.SKELETON, -4.5, Items.IRON_HELMET);
			spawned.add(creeper);
			spawned.add(ironSk);
			wear(p, Items.IRON_HELMET, Items.IRON_CHESTPLATE, Items.IRON_LEGGINGS, Items.IRON_BOOTS);
			assertTrue(!allowed(p, creeper), "Rank 4 must stay closed without wins or Respect");

			Stats stats = Stats.of(server);
			var u = p.getUuid();
			stats.record(List.of(u), Map.of(u, 40), u, true, Opponent.of(ironZ, new Random(0), p, "ru"), Rank.IRON, Map.of(u, 2), 0L);
			assertTrue(!allowed(p, creeper), "One rank-3 species is not enough for rank 4");
			stats.record(List.of(u), Map.of(u, 40), u, true, Opponent.of(ironSk, new Random(0), p, "ru"), Rank.IRON, Map.of(u, 2), 0L);
			assertTrue(allowed(p, creeper), "Two rank-3 species must open rank 4");

			Stats.Player st = stats.player(u);
			st.speciesWins.clear();
			st.rankedSpeciesWins.clear();
			st.respect = ProgressionRules.RANK4_RESPECT;
			assertTrue(allowed(p, creeper), "300 Respect must open rank 4");
		});

		// Same allowed card in English, then back to Russian.
		GameTestSupport.setLang(ctx, "en_us");
		TARGET[0] = ids[0];
		shootChoice(ctx, sp, "6.30-session-2-choice-buttons-en");
		GameTestSupport.setLang(ctx, "ru_ru");

		sp.getServer().runOnServer(server -> {
			for (MobEntity m : spawned) m.discard();
			ServerPlayerEntity p = server.getPlayerManager().getPlayerList().get(0);
			wear(p, null, null, null, null);
			p.getInventory().clear();
			RapConfig.get().progression = "soft";
		});
		GameTestSupport.assertNoRawKeys("first_session");
	}
}
