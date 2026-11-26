package dev.rapbattle.test;

import dev.rapbattle.battle.*;
import dev.rapbattle.client.ClientBattle;
import dev.rapbattle.client.Intro;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
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
import java.util.Set;

/** Client gametests for Acts 1-5 & 6.7 fixes: runs mechanics on a real server/client and asserts outcomes + screenshots. */
public class ActsGameTest implements FabricClientGameTest {

	private static boolean phase(String p) { return ClientBattle.view != null && p.equals(ClientBattle.view.phase); }

	private static void assertTrue(boolean cond, String msg) {
		if (!cond) throw new AssertionError(msg);
	}

	@Override
	public void runTest(ClientGameTestContext ctx) {
		ctx.getInput().resizeWindow(1280, 720);
		ctx.waitTicks(5);
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			sp.getClientWorld().waitForChunksRender();
			sp.getServer().runCommand("gamerule doMobSpawning false");
			sp.getServer().runCommand("gamerule doDaylightCycle false");
			sp.getServer().runCommand("gamerule doWeatherCycle false");

			testAct1NightHuntAndHomemadeMic(ctx, sp);
			testAct2VillagerAndRaidRules(ctx, sp);
			testAct3BastionGuest(ctx, sp);
			testAct4WardenQuiet(ctx, sp);
			testAct5Dragon(ctx, sp);
		}
	}

	private void testAct1NightHuntAndHomemadeMic(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		sp.getServer().runCommand("time set 14500");
		Battle[] b = new Battle[1];
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
			b[0] = BattleManager.start(p, z);
			b[0].startEarly(p);
		});
		ctx.waitFor(c -> phase("TIMELINE"), 800);
		Intro.skip();
		ctx.waitFor(c -> ClientBattle.timeline() && ClientBattle.beat() >= ClientBattle.view.mobEndBeat + 2, 1200);
		ctx.waitTicks(3);
		ctx.takeScreenshot("act1-homemade-mic");
		sp.getServer().runOnServer(server -> {
			ServerPlayerEntity p = server.getPlayerManager().getPlayerList().get(0);
			ServerWorld w = p.getEntityWorld();
			assertTrue(Acts.isNightHuntDoubleLoot(Rank.LEATHER, Opponent.isNight(w), 1, false), "Night hunt should be active");
			b[0].forceOutcomeForTest(true, true);
		});
		ctx.waitFor(c -> ClientBattle.view == null, 400);
		ctx.waitTicks(5);
	}

	private void testAct2VillagerAndRaidRules(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		sp.getServer().runCommand("time set 1000");
		Battle[] b = new Battle[1];
		VillagerEntity[] vRef = new VillagerEntity[1];
		sp.getServer().runOnServer(server -> {
			ServerPlayerEntity p = server.getPlayerManager().getPlayerList().get(0);
			ServerWorld w = p.getEntityWorld();
			VillagerEntity v = EntityType.VILLAGER.create(w, SpawnReason.COMMAND);
			v.refreshPositionAndAngles(p.getX(), p.getY(), p.getZ() + 4, 180, 0);
			w.spawnEntity(v);
			vRef[0] = v;

			// Regular pillager without final-wave captain status must not trigger raid mode (6.7 fix).
			PillagerEntity pil = EntityType.PILLAGER.create(w, SpawnReason.COMMAND);
			pil.refreshPositionAndAngles(p.getX() + 2, p.getY(), p.getZ() + 4, 180, 0);
			w.spawnEntity(pil);
			assertTrue(!Acts.isFinalWaveCaptainMatch(true, true, 1, 3, true), "Wave 1 must not trigger raid mode");
			assertTrue(!Acts.isFinalWaveCaptainMatch(true, true, 3, 3, false), "Non-captain must not trigger raid mode");
			pil.discard();

			b[0] = BattleManager.start(p, v);
			b[0].forceOutcomeForTest(true, true);
			assertTrue(v.isAlive() && !v.isRemoved(), "Villager must survive friendly battle win");
			int rep = v.getGossip().getReputationFor(p.getUuid(), g -> g == VillagerGossipType.MAJOR_POSITIVE || g == VillagerGossipType.MINOR_POSITIVE);
			assertTrue(rep > 0, "Villager should grant positive gossip after win, got " + rep);
		});
		ctx.waitTicks(5);
		ctx.takeScreenshot("act2-villager-alive");
		sp.getServer().runOnServer(server -> {
			if (vRef[0] != null) vRef[0].discard();
		});
	}

	private void testAct3BastionGuest(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		sp.getServer().runOnServer(server -> {
			ServerPlayerEntity p = server.getPlayerManager().getPlayerList().get(0);
			ServerWorld nw = server.getWorld(World.NETHER);
			p.teleport(nw, 0.5, 80, 0.5, java.util.Set.of(), p.getYaw(), p.getPitch(), false);
		});
		ctx.waitTicks(20);
		sp.getServer().runCommand("execute in minecraft:the_nether run place structure minecraft:bastion_remnant 0 70 0");
		ctx.waitTicks(10);
		sp.getServer().runOnServer(server -> {
			ServerPlayerEntity p = server.getPlayerManager().getPlayerList().get(0);
			ServerWorld nw = server.getWorld(World.NETHER);
			BastionBattle.setTestBastionBox(new net.minecraft.util.math.BlockBox(-64, 50, -64, 64, 120, 64));
			PiglinBruteEntity brute = EntityType.PIGLIN_BRUTE.create(nw, SpawnReason.COMMAND);
			brute.refreshPositionAndAngles(p.getX(), p.getY(), p.getZ() + 4, 180, 0);
			nw.spawnEntity(brute);
			Battle b = BattleManager.start(p, brute);
			b.forceOutcomeForTest(true, true);
			assertTrue(BastionBattle.inBastion(nw, brute.getBlockPos()), "Brute should be inside a bastion structure");
			assertTrue(PlayerStatus.has(p.getUuid(), PlayerStatus.BASTION_GUEST), "Bastion Guest status must be active after brute win");
		});
		ctx.waitTicks(25);
		ctx.takeScreenshot("act3-bastion-guest");
		sp.getServer().runOnServer(server -> {
			BastionBattle.setTestBastionBox(null);
			ServerPlayerEntity p = server.getPlayerManager().getPlayerList().get(0);
			ServerWorld ow = server.getWorld(World.OVERWORLD);
			p.teleport(ow, 0.5, 80, 0.5, java.util.Set.of(), p.getYaw(), p.getPitch(), false);
		});
		ctx.waitTicks(10);
	}

	private void testAct4WardenQuiet(ClientGameTestContext ctx, TestSingleplayerContext sp) {
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
		});
		ctx.waitTicks(25);
		ctx.takeScreenshot("act4-warden-quiet");
	}

	private void testAct5Dragon(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		sp.getServer().runOnServer(server -> {
			ServerPlayerEntity p = server.getPlayerManager().getPlayerList().get(0);
			p.changeGameMode(net.minecraft.world.GameMode.CREATIVE);
			p.getAbilities().flying = true;
			p.sendAbilitiesUpdate();
			ServerWorld ew = server.getWorld(World.END);
			p.teleport(ew, 0.5, 70, 0.5, java.util.Set.of(), p.getYaw(), p.getPitch(), false);
		});
		ctx.waitTicks(40);
		Battle[] b = new Battle[1];
		EnderDragonEntity[] dRef = new EnderDragonEntity[1];
		sp.getServer().runOnServer(server -> {
			ServerPlayerEntity p = server.getPlayerManager().getPlayerList().get(0);
			ServerWorld ew = server.getWorld(World.END);
			List<EnderDragonEntity> dragons = ew.getEntitiesByClass(EnderDragonEntity.class, p.getBoundingBox().expand(256), Entity::isAlive);
			assertTrue(!dragons.isEmpty(), "Ender Dragon should be present in The End");
			EnderDragonEntity dragon = dragons.get(0);
			dRef[0] = dragon;
			List<EndCrystalEntity> crystals = ew.getEntitiesByClass(EndCrystalEntity.class, dragon.getBoundingBox().expand(192), Entity::isAlive);
			if (!crystals.isEmpty()) {
				assertTrue(!DragonBattle.canChallenge(p, dragon), "Cannot challenge dragon while crystals are alive");
				for (EndCrystalEntity c : crystals) c.kill(ew);
			}
			assertTrue(DragonBattle.canChallenge(p, dragon), "Should allow challenging dragon once crystals are destroyed");
			b[0] = BattleManager.start(p, dragon);
			assertTrue(dragon.getPhaseManager().getCurrent().getType() == PhaseType.HOVER, "Dragon should be in HOVER phase during battle");
		});
		ctx.waitTicks(5);
		ctx.takeScreenshot("act5-dragon-hover");
		sp.getServer().runOnServer(server -> {
			if (b[0] != null && dRef[0] != null) {
				b[0].forceOutcomeForTest(true, true);
				EnderDragonEntity dragon = dRef[0];
				assertTrue(!dragon.isAlive() || dragon.ticksSinceDeath > 0
					|| dragon.getPhaseManager().getCurrent().getType() == PhaseType.DYING,
					"Dragon should enter vanilla death after battle win");
				assertTrue(!Stats.of(server).legend.isEmpty(), "Golden Line legend should be recorded after dragon win");
			}
		});
		ctx.waitTicks(10);
	}
}
