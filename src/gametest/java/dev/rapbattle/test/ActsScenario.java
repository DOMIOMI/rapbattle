package dev.rapbattle.test;

import dev.rapbattle.battle.*;
import dev.rapbattle.client.ClientBattle;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.block.Blocks;
import net.minecraft.block.SculkShriekerBlock;
import net.minecraft.block.entity.SculkShriekerBlockEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.boss.dragon.EnderDragonEntity;
import net.minecraft.entity.boss.dragon.phase.PhaseType;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.PiglinBruteEntity;
import net.minecraft.entity.mob.PillagerEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.village.VillagerGossipType;
import net.minecraft.world.World;

import java.util.List;

import static dev.rapbattle.test.GameTestSupport.assertTrue;

/** Acts 1-5 scenario using Battle.jumpToForTest for fast outcome verification. */
public final class ActsScenario {
	private ActsScenario() {}

	public static void run(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		testAct1NightHuntAndHomemadeMic(ctx, sp);
		testAct2VillagerAndRaidRules(ctx, sp);
		testRealRaidWaves(ctx, sp);
		testAct3BastionGuest(ctx, sp);
		testAct4WardenQuiet(ctx, sp);
		testAct5Dragon(ctx, sp);
		GameTestSupport.assertNoRawKeys("acts");
	}

	private static void testAct1NightHuntAndHomemadeMic(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		sp.getServer().runCommand("time set 14500");
		sp.getServer().runOnServer(server -> {
			ServerPlayerEntity p = server.getPlayerManager().getPlayerList().get(0);
			ServerWorld w = p.getEntityWorld();
			ItemStack mic = new ItemStack(Items.LIGHTNING_ROD);
			NbtCompound tag = new NbtCompound();
			tag.putString(Trophies.NBT_KEY, "homemade");
			mic.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(tag));
			p.setStackInHand(net.minecraft.util.Hand.OFF_HAND, mic);
			assertTrue(Trophies.equippedPerk(p) == Trophies.TrophyPerk.HOMEMADE, "Offhand should have HOMEMADE perk");

			MobEntity z = EntityType.ZOMBIE.create(w, SpawnReason.COMMAND);
			z.refreshPositionAndAngles(p.getX(), p.getY(), p.getZ() + 5, 180, 0);
			w.spawnEntity(z);
			Battle b = BattleManager.start(p, z);
			b.jumpToForTest(Battle.Phase.TIMELINE);
			assertTrue(Acts.isNightHuntDoubleLoot(Rank.LEATHER, Opponent.isNight(w), 1, false), "Night hunt should be active");
			b.jumpToForTest(Battle.Phase.RESULT);
			z.discard();
			p.setStackInHand(net.minecraft.util.Hand.OFF_HAND, ItemStack.EMPTY);
		});
		ctx.waitFor(c -> ClientBattle.view == null, 200);
	}

	private static void testAct2VillagerAndRaidRules(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		sp.getServer().runCommand("time set 1000");
		sp.getServer().runOnServer(server -> {
			ServerPlayerEntity p = server.getPlayerManager().getPlayerList().get(0);
			ServerWorld w = p.getEntityWorld();
			VillagerEntity v = EntityType.VILLAGER.create(w, SpawnReason.COMMAND);
			v.refreshPositionAndAngles(p.getX(), p.getY(), p.getZ() + 4, 180, 0);
			w.spawnEntity(v);

			PillagerEntity pil = EntityType.PILLAGER.create(w, SpawnReason.COMMAND);
			pil.refreshPositionAndAngles(p.getX() + 2, p.getY(), p.getZ() + 4, 180, 0);
			w.spawnEntity(pil);
			assertTrue(!Acts.isFinalWaveCaptainMatch(true, true, 1, 3, true), "Wave 1 must not trigger raid mode");
			assertTrue(!Acts.isFinalWaveCaptainMatch(true, true, 3, 3, false), "Non-captain must not trigger raid mode");
			pil.discard();

			Battle b = BattleManager.start(p, v);
			b.jumpToForTest(Battle.Phase.RESULT);
			assertTrue(v.isAlive() && !v.isRemoved(), "Villager must survive friendly battle win");
			int rep = v.getGossip().getReputationFor(p.getUuid(), g -> g == VillagerGossipType.MAJOR_POSITIVE || g == VillagerGossipType.MINOR_POSITIVE);
			assertTrue(rep > 0, "Villager should grant positive gossip after win, got " + rep);
			v.discard();
		});
	}

	private static void testRealRaidWaves(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		sp.getServer().runOnServer(server -> {
			server.setDifficulty(net.minecraft.world.Difficulty.EASY, true);
			ServerPlayerEntity p = server.getPlayerManager().getPlayerList().get(0);
			ServerWorld w = p.getEntityWorld();
			p.changeGameMode(net.minecraft.world.GameMode.CREATIVE);
			BlockPos base = p.getBlockPos();
			w.setBlockState(base.add(0, 0, 2), Blocks.BELL.getDefaultState());
			for (int i = 0; i < 2; i++) {
				BlockPos bp = base.add(i * 3 + 2, 0, 2);
				w.setBlockState(bp, Blocks.RED_BED.getDefaultState()
					.with(net.minecraft.block.BedBlock.PART, net.minecraft.block.enums.BedPart.FOOT));
				w.setBlockState(bp.south(), Blocks.RED_BED.getDefaultState()
					.with(net.minecraft.block.BedBlock.PART, net.minecraft.block.enums.BedPart.HEAD));
				VillagerEntity v = EntityType.VILLAGER.create(w, SpawnReason.COMMAND);
				v.refreshPositionAndAngles(bp.getX() + 0.5, bp.getY(), bp.getZ() + 2.0, 0, 0);
				v.setInvulnerable(true);
				w.spawnEntity(v);
			}
		});
		boolean[] poiReady = new boolean[1];
		for (int i = 0; i < 25 && !poiReady[0]; i++) {
			ctx.waitTicks(5);
			sp.getServer().runOnServer(server -> {
				ServerPlayerEntity p = server.getPlayerManager().getPlayerList().get(0);
				poiReady[0] = p.getEntityWorld().isNearOccupiedPointOfInterest(p.getBlockPos());
			});
		}
		sp.getServer().runOnServer(server -> {
			ServerPlayerEntity p = server.getPlayerManager().getPlayerList().get(0);
			ServerWorld w = p.getEntityWorld();
			p.addStatusEffect(new net.minecraft.entity.effect.StatusEffectInstance(
				net.minecraft.entity.effect.StatusEffects.RAID_OMEN, 5, 0));
			if (w.getRaidAt(p.getBlockPos()) == null) {
				w.getRaidManager().startRaid(p, p.getBlockPos());
			}
		});
		boolean[] ready = new boolean[1];
		for (int i = 0; i < 60 && !ready[0]; i++) {
			ctx.waitTicks(10);
			sp.getServer().runOnServer(server -> {
				ServerPlayerEntity p = server.getPlayerManager().getPlayerList().get(0);
				var raid = p.getEntityWorld().getRaidAt(p.getBlockPos());
				ready[0] = raid != null && raid.getGroupsSpawned() >= 1 && !raid.getAllRaiders().isEmpty();
			});
		}
		assertTrue(ready[0], "Raid should start and spawn wave 1");
		sp.getServer().runOnServer(server -> {
			ServerPlayerEntity p = server.getPlayerManager().getPlayerList().get(0);
			ServerWorld w = p.getEntityWorld();
			var raid = w.getRaidAt(p.getBlockPos());
			assertTrue(raid != null && raid.getGroupsSpawned() == 1, "Raid should be on wave 1");
			net.minecraft.entity.raid.RaiderEntity regular = null;
			for (var r : raid.getAllRaiders()) {
				if (r.isAlive() && !r.isPatrolLeader()) { regular = r; break; }
			}
			if (regular != null) {
				Battle b = BattleManager.start(p, regular);
				b.jumpToForTest(Battle.Phase.RESULT);
				assertTrue(!p.hasStatusEffect(net.minecraft.entity.effect.StatusEffects.HERO_OF_THE_VILLAGE),
					"Wave 1 regular raider must NOT grant Hero of the Village");
			}
			for (var r : new java.util.ArrayList<>(raid.getAllRaiders())) {
				if (r.isAlive()) r.kill(w);
			}
		});
		boolean[] finalWave = new boolean[1];
		for (int i = 0; i < 120 && !finalWave[0]; i++) {
			ctx.waitTicks(10);
			sp.getServer().runOnServer(server -> {
				ServerPlayerEntity p = server.getPlayerManager().getPlayerList().get(0);
				ServerWorld w = p.getEntityWorld();
				var raid = w.getRaidAt(p.getBlockPos());
				if (raid == null) return;
				int max = raid.getMaxWaves(w.getDifficulty());
				if (raid.getGroupsSpawned() < max) {
					for (var r : new java.util.ArrayList<>(raid.getAllRaiders())) {
						if (r.isAlive()) r.kill(w);
					}
				} else if (!raid.getAllRaiders().isEmpty()) {
					finalWave[0] = true;
				}
			});
		}
		assertTrue(finalWave[0], "Raid should reach final wave");
		sp.getServer().runOnServer(server -> {
			ServerPlayerEntity p = server.getPlayerManager().getPlayerList().get(0);
			ServerWorld w = p.getEntityWorld();
			var raid = w.getRaidAt(p.getBlockPos());
			assertTrue(raid != null && raid.getGroupsSpawned() >= raid.getMaxWaves(w.getDifficulty()), "Raid must reach final wave");
			net.minecraft.entity.raid.RaiderEntity cap = raid.getCaptain(raid.getGroupsSpawned());
			if (cap == null || !cap.isAlive()) {
				for (var r : raid.getAllRaiders()) if (r.isAlive() && r.isPatrolLeader()) { cap = r; break; }
			}
			assertTrue(cap != null && cap.isAlive(), "Final wave must have a live raid captain");
			cap.refreshPositionAndAngles(p.getX(), p.getY(), p.getZ() + 4, 180, 0);
		});
		ctx.waitTicks(20);
		sp.getServer().runOnServer(server -> {
			ServerPlayerEntity p = server.getPlayerManager().getPlayerList().get(0);
			ServerWorld w = p.getEntityWorld();
			assertTrue(BattleManager.hasPendingForTest(p.getUuid()), "Final wave captain must auto-challenge player");
			Battle b = BattleManager.acceptPendingForTest(p);
			assertTrue(b != null, "Accepting final wave captain challenge must start battle");
			b.jumpToForTest(Battle.Phase.RESULT);
			assertTrue(p.hasStatusEffect(net.minecraft.entity.effect.StatusEffects.HERO_OF_THE_VILLAGE),
				"Final wave captain win must grant Hero of the Village");
			var totems = w.getEntitiesByClass(net.minecraft.entity.ItemEntity.class, p.getBoundingBox().expand(16),
				ie -> ie.getStack().isOf(Items.TOTEM_OF_UNDYING));
			assertTrue(!totems.isEmpty(), "Final wave captain win must drop Totem of Undying");
			for (var v : w.getEntitiesByClass(VillagerEntity.class, p.getBoundingBox().expand(32), Entity::isAlive)) v.discard();
			for (var r : w.getEntitiesByClass(net.minecraft.entity.raid.RaiderEntity.class, p.getBoundingBox().expand(64), Entity::isAlive)) r.discard();
		});
	}

	private static void testAct3BastionGuest(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		sp.getServer().runOnServer(server -> {
			ServerPlayerEntity p = server.getPlayerManager().getPlayerList().get(0);
			ServerWorld nw = server.getWorld(World.NETHER);
			p.teleport(nw, 0.5, 80, 0.5, java.util.Set.of(), p.getYaw(), p.getPitch(), false);
		});
		ctx.waitTicks(5);
		sp.getServer().runOnServer(server -> {
			String prevProg = dev.rapbattle.RapConfig.get().progression;
			dev.rapbattle.RapConfig.get().progression = "off";
			ServerPlayerEntity p = server.getPlayerManager().getPlayerList().get(0);
			ServerWorld nw = server.getWorld(World.NETHER);
			BastionBattle.setTestBastionBox(new net.minecraft.util.math.BlockBox(-64, 50, -64, 64, 120, 64));
			PiglinBruteEntity brute = EntityType.PIGLIN_BRUTE.create(nw, SpawnReason.COMMAND);
			brute.refreshPositionAndAngles(p.getX(), p.getY(), p.getZ() + 4, 180, 0);
			nw.spawnEntity(brute);
			Battle b = BattleManager.start(p, brute);
			b.jumpToForTest(Battle.Phase.RESULT);
			assertTrue(BastionBattle.inBastion(nw, brute.getBlockPos()), "Brute should be inside a bastion structure");
			assertTrue(PlayerStatus.has(p.getUuid(), PlayerStatus.BASTION_GUEST), "Bastion Guest status must be active after brute win");
			dev.rapbattle.RapConfig.get().progression = prevProg;
			BastionBattle.setTestBastionBox(null);
			brute.discard();
			ServerWorld ow = server.getWorld(World.OVERWORLD);
			p.teleport(ow, 0.5, 64, 0.5, java.util.Set.of(), p.getYaw(), p.getPitch(), false);
		});
		ctx.waitTicks(3);
	}

	private static void testAct4WardenQuiet(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		sp.getServer().runOnServer(server -> {
			ServerPlayerEntity p = server.getPlayerManager().getPlayerList().get(0);
			ServerWorld w = p.getEntityWorld();
			BlockPos pos = p.getBlockPos().add(0, 0, 2);
			w.setBlockState(pos, Blocks.SCULK_SHRIEKER.getDefaultState().with(SculkShriekerBlock.CAN_SUMMON, true));
			PlayerStatus.grant(p.getUuid(), PlayerStatus.WARDEN_QUIET, 15 * 60_000L, p.getBlockPos(), 48);
			assertTrue(PlayerStatus.hasInRadius(p.getUuid(), PlayerStatus.WARDEN_QUIET, pos), "Warden quiet should cover shrieker");
			if (w.getBlockEntity(pos) instanceof SculkShriekerBlockEntity shrieker) {
				shrieker.shriek(w, p);
			}
			List<?> wardens = w.getEntitiesByClass(net.minecraft.entity.mob.WardenEntity.class, p.getBoundingBox().expand(48), Entity::isAlive);
			assertTrue(wardens.isEmpty(), "Shrieker must not summon Warden while WARDEN_QUIET is active");
			w.setBlockState(pos, Blocks.AIR.getDefaultState());
		});
	}

	private static void testAct5Dragon(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		sp.getServer().runOnServer(server -> {
			dev.rapbattle.RapConfig.get().progression = "off";
			ServerPlayerEntity p = server.getPlayerManager().getPlayerList().get(0);
			p.changeGameMode(net.minecraft.world.GameMode.CREATIVE);
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
			assertTrue(!dragons.isEmpty(), "Ender Dragon should be present in The End");
			EnderDragonEntity dragon = dragons.get(0);
			List<EndCrystalEntity> crystals = ew.getEntitiesByClass(EndCrystalEntity.class, dragon.getBoundingBox().expand(192), Entity::isAlive);
			if (!crystals.isEmpty()) {
				assertTrue(!DragonBattle.canChallenge(p, dragon), "Cannot challenge dragon while crystals are alive");
				for (EndCrystalEntity c : crystals) c.kill(ew);
			}
			assertTrue(DragonBattle.canChallenge(p, dragon), "Should allow challenging dragon once crystals are destroyed");
			Battle b = BattleManager.start(p, dragon);
			assertTrue(dragon.getPhaseManager().getCurrent().getType() == PhaseType.HOVER, "Dragon should be in HOVER phase during battle");
			b.jumpToForTest(Battle.Phase.RESULT);
			assertTrue(!dragon.isAlive() || dragon.ticksSinceDeath > 0
				|| dragon.getPhaseManager().getCurrent().getType() == PhaseType.DYING,
				"Dragon should enter vanilla death after battle win");
			assertTrue(!Stats.of(server).legend.isEmpty(), "Golden Line legend should be recorded after dragon win");
			dev.rapbattle.RapConfig.get().progression = "soft";
		});
	}
}
