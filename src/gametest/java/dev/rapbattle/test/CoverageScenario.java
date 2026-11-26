package dev.rapbattle.test;

import com.mojang.authlib.GameProfile;
import dev.rapbattle.Clock;
import dev.rapbattle.RapConfig;
import dev.rapbattle.battle.*;
import dev.rapbattle.client.ClipRecorder;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.mob.BlazeEntity;
import net.minecraft.entity.mob.EndermanEntity;
import net.minecraft.entity.mob.SkeletonEntity;
import net.minecraft.entity.mob.ZombieEntity;
import net.minecraft.entity.passive.IronGolemEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import static dev.rapbattle.test.GameTestSupport.assertTrue;

/** Coverage scenario (6.19): coop invite/leave/violation, SoreLosers tryRage+blast, GolemDefender offended+tick, 2v1 + 30s expiry, Enderman gaze + hints, ClipRecorder WAV. */
public final class CoverageScenario {
	private CoverageScenario() {}

	public static void run(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		testCoopInviteLeaveViolationAndBlast(ctx, sp);
		testSoreLosersTryRageAndGolemTickAndTwoOnOne(ctx, sp);
		testEndermanGazeAndStyleUnlockHints(ctx, sp);
		testClipRecorder(ctx);
		GameTestSupport.assertNoRawKeys("coverage");
	}

	private static void testCoopInviteLeaveViolationAndBlast(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		sp.getServer().runOnServer(server -> {
			RapConfig.get().progression = "off";
			RapConfig.get().dressCode = false;
			ServerPlayerEntity p1 = server.getPlayerManager().getPlayerList().get(0);
			ServerWorld w = p1.getEntityWorld();

			ServerPlayerEntity p2 = FakePlayer.get(w, new GameProfile(UUID.randomUUID(), "CoopMate"));
			p2.refreshPositionAndAngles(p1.getX() + 2, p1.getY(), p1.getZ(), 0, 0);
			ServerPlayerEntity p3 = FakePlayer.get(w, new GameProfile(UUID.randomUUID(), "Violator"));
			p3.refreshPositionAndAngles(p1.getX() - 2, p1.getY(), p1.getZ(), 0, 0);

			BlazeEntity blaze = EntityType.BLAZE.create(w, SpawnReason.COMMAND);
			blaze.refreshPositionAndAngles(p1.getX(), p1.getY(), p1.getZ() + 4, 180, 0);
			blaze.setAiDisabled(true);
			w.spawnEntity(blaze);

			Battle b = BattleManager.start(p1, blaze);
			b.invite(p2);
			b.answerInvite(p2, true);
			assertTrue(b.hasPlayer(p2.getUuid()), "Accepted teammate must join battle");

			b.invite(p3);
			b.answerInvite(p3, true);
			assertTrue(b.hasPlayer(p3.getUuid()), "Second teammate must join battle");
			b.jumpToForTest(Battle.Phase.TIMELINE);
			p3.setStackInHand(Hand.MAIN_HAND, new ItemStack(Items.IRON_SWORD));
			AttackEntityCallback.EVENT.invoker().interact(p3, w, Hand.MAIN_HAND, blaze, null);
			assertTrue(b.phase() == Battle.Phase.RESULT, "Weapon hit mid-battle must forfeit battle via violation()");

			b.abort("coop test cleanup");
			blaze.discard();
			p1.getInventory().clear();
			p3.getInventory().clear();
			RapConfig.get().progression = "soft";
			GameTestSupport.cleanupBattles(server);
		});
	}

	private static void testSoreLosersTryRageAndGolemTickAndTwoOnOne(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		IronGolemEntity[] naturalGolemRef = new IronGolemEntity[1];
		Battle[] twoBattleRef = new Battle[1];
		SkeletonEntity[] twoMobRef = new SkeletonEntity[1];
		SkeletonEntity[] twoFanRef = new SkeletonEntity[1];
		ServerPlayerEntity[] twoP2Ref = new ServerPlayerEntity[1];

		sp.getServer().runOnServer(server -> {
			ServerPlayerEntity p = server.getPlayerManager().getPlayerList().get(0);
			ServerWorld w = p.getEntityWorld();

			// 1. SoreLosers.tryRage (deterministic Random -> always triggers) + self-defense cred preservation + double drops
			ZombieEntity raged = EntityType.ZOMBIE.create(w, SpawnReason.COMMAND);
			raged.refreshPositionAndAngles(p.getX(), p.getY(), p.getZ() + 4, 180, 0);
			w.spawnEntity(raged);
			PlayerStatus.grant(p.getUuid(), PlayerStatus.STREET_CRED, 60_000L, p.getBlockPos(), 32);
			Opponent opp = Opponent.of(raged, new Random(0), p, "ru");
			Random alwaysZero = new Random() {
				@Override public int nextInt(int bound) { return 0; }
			};
			boolean triggered = SoreLosers.tryRage(opp, MobRecord.of(raged), 10, p, alwaysZero, "ru");
			assertTrue(triggered, "SoreLosers.tryRage must trigger with deterministic Random on Zombie");
			assertTrue(SoreLosers.isRaged(raged), "Zombie must be registered as RAGED");
			assertTrue(raged.getHealth() <= raged.getMaxHealth() * 0.55f, "Raged mob must survive with ~50% HP");
			assertTrue(raged.getTarget() == p, "Raged mob must target MVP");
			Reputation.onPlayerHostileAction(p, raged);
			assertTrue(PlayerStatus.has(p.getUuid(), PlayerStatus.STREET_CRED),
				"Hitting a RAGED mob (self-defense) must NOT revoke Street Cred");
			raged.kill(w);
			SoreLosers.doubleFreshDrops(w, raged.getEntityPos());
			List<ItemEntity> drops = w.getEntitiesByClass(ItemEntity.class, new Box(raged.getBlockPos()).expand(3), e -> e.isAlive());
			for (ItemEntity it : drops) it.discard();
			if (!raged.isRemoved()) raged.discard();

			// SoreLosers.blast must NOT break blocks
			BlockPos under = p.getBlockPos().add(0, -1, 4);
			w.setBlockState(under, Blocks.STONE.getDefaultState());
			ZombieEntity blaster = EntityType.ZOMBIE.create(w, SpawnReason.COMMAND);
			blaster.refreshPositionAndAngles(p.getX(), p.getY(), p.getZ() + 4, 180, 0);
			w.spawnEntity(blaster);
			SoreLosers.blast(blaster, 0);
			SoreLosers.tick();
			assertTrue(w.getBlockState(under).isOf(Blocks.STONE), "SoreLosers blast must not break blocks");
			if (!blaster.isRemoved()) blaster.discard();

			// 2. GolemDefender: player-created ignored, natural golem hunts & opens PENDING via tick()
			VillagerEntity v = EntityType.VILLAGER.create(w, SpawnReason.COMMAND);
			v.refreshPositionAndAngles(p.getX() + 2, p.getY(), p.getZ() + 3, 180, 0);
			w.spawnEntity(v);

			IronGolemEntity playerGolem = EntityType.IRON_GOLEM.create(w, SpawnReason.COMMAND);
			playerGolem.setPlayerCreated(true);
			playerGolem.refreshPositionAndAngles(p.getX() + 4, p.getY(), p.getZ() + 3, 180, 0);
			w.spawnEntity(playerGolem);

			GolemDefender.offended(p, v);
			assertTrue(!BattleManager.hasPendingForTest(p.getUuid()), "Player-created golem must be ignored");
			GolemDefender.chickenedOut(p, playerGolem);
			GolemDefender.respect(playerGolem, p, "ru");
			assertTrue(p.getInventory().count(Items.IRON_INGOT) >= 5, "GolemDefender.respect must give 5 iron ingots");
			assertTrue(p.getInventory().count(Items.POPPY) >= 1, "GolemDefender.respect must give 1 poppy");
			playerGolem.discard();

			IronGolemEntity naturalGolem = EntityType.IRON_GOLEM.create(w, SpawnReason.COMMAND);
			naturalGolem.setPlayerCreated(false);
			naturalGolem.refreshPositionAndAngles(p.getX() + 3, p.getY(), p.getZ() + 3, 180, 0);
			w.spawnEntity(naturalGolem);
			naturalGolemRef[0] = naturalGolem;
			GolemDefender.offended(p, v);
			v.discard();
		});
		ctx.waitTicks(4);
		sp.getServer().runOnServer(server -> {
			ServerPlayerEntity p = server.getPlayerManager().getPlayerList().get(0);
			assertTrue(BattleManager.hasPendingForTest(p.getUuid()), "Natural golem must hunt and open PENDING via GolemDefender.tick()");
			BattleManager.clearPendingForTest(p.getUuid());
			if (naturalGolemRef[0] != null) naturalGolemRef[0].discard();
			p.getInventory().clear();

			// 3. 2v1 post-battle crowd defense
			ServerWorld w = p.getEntityWorld();
			ServerPlayerEntity p2 = FakePlayer.get(w, new GameProfile(UUID.randomUUID(), "FirstAttacker"));
			p2.refreshPositionAndAngles(p.getX() + 2, p.getY(), p.getZ() + 3, 0, 0);
			twoP2Ref[0] = p2;

			SkeletonEntity mob = EntityType.SKELETON.create(w, SpawnReason.COMMAND);
			mob.refreshPositionAndAngles(p.getX(), p.getY(), p.getZ() + 4, 180, 0);
			mob.setAiDisabled(true);
			w.spawnEntity(mob);
			twoMobRef[0] = mob;

			SkeletonEntity fan = EntityType.SKELETON.create(w, SpawnReason.COMMAND);
			fan.refreshPositionAndAngles(p.getX() + 5, p.getY(), p.getZ() + 4, 180, 0);
			w.spawnEntity(fan);
			twoFanRef[0] = fan;

			Battle b = BattleManager.start(p, mob);
			b.jumpToForTest(Battle.Phase.TIMELINE);
			twoBattleRef[0] = b;
		});
		ctx.waitFor(c -> twoBattleRef[0] != null && twoBattleRef[0].phase() == Battle.Phase.TIMELINE, 200);
		sp.getServer().runOnServer(server -> {
			ServerPlayerEntity p1 = server.getPlayerManager().getPlayerList().get(0);
			ServerWorld w = p1.getEntityWorld();
			SkeletonEntity mob = twoMobRef[0];
			SkeletonEntity fan = twoFanRef[0];
			ServerPlayerEntity p2 = twoP2Ref[0];

			twoBattleRef[0].jumpToForTest(Battle.Phase.RESULT, true, false, 85, 75);
			Clock.advanceForTest(20_000L);
			Reputation.clear();

			p1.setStackInHand(Hand.MAIN_HAND, new ItemStack(Items.WOODEN_SWORD));
			p2.setStackInHand(Hand.MAIN_HAND, new ItemStack(Items.WOODEN_SWORD));
			// First hit opens PENDING; second hit enters brawl / fightAfterBattle
			AttackEntityCallback.EVENT.invoker().interact(p2, w, Hand.MAIN_HAND, mob, null);
			AttackEntityCallback.EVENT.invoker().interact(p2, w, Hand.MAIN_HAND, mob, null);
			assertTrue(fan.getTarget() == null, "1v1 brawl after battle must NOT pull mob fans");

			AttackEntityCallback.EVENT.invoker().interact(p1, w, Hand.MAIN_HAND, mob, null);
			AttackEntityCallback.EVENT.invoker().interact(p1, w, Hand.MAIN_HAND, mob, null);
			assertTrue(fan.getTarget() == p1, "2v1 brawl after battle must pull mob fan onto second attacker");

			// After >30s RECENT window expires, hitting mob no longer pulls crowd fans
			fan.setTarget(null);
			Clock.advanceForTest(31_000L);
			AttackEntityCallback.EVENT.invoker().interact(p1, w, Hand.MAIN_HAND, mob, null);
			assertTrue(fan.getTarget() == null, "After 30s RECENT expires, hitting mob must NOT pull fan");

			p1.setStackInHand(Hand.MAIN_HAND, ItemStack.EMPTY);
			p2.setStackInHand(Hand.MAIN_HAND, ItemStack.EMPTY);
			mob.discard();
			fan.discard();
			GameTestSupport.cleanupBattles(server);
		});
	}

	private static void testEndermanGazeAndStyleUnlockHints(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		Battle[] ref = new Battle[1];
		EndermanEntity[] emRef = new EndermanEntity[1];
		sp.getServer().runOnServer(server -> {
			ServerPlayerEntity p = server.getPlayerManager().getPlayerList().get(0);
			ServerWorld w = p.getEntityWorld();

			Stats stats = Stats.of(server);
			Stats.Player st = stats.player(p.getUuid());
			st.respect = 500;
			st.hinted500 = false;
			st.hinted1500 = false;
			ZombieEntity zHint = EntityType.ZOMBIE.create(w, SpawnReason.COMMAND);
			zHint.refreshPositionAndAngles(p.getX(), p.getY(), p.getZ() + 4, 180, 0);
			w.spawnEntity(zHint);
			Battle bHint1 = BattleManager.start(p, zHint);
			bHint1.jumpToForTest(Battle.Phase.RESULT);
			assertTrue(st.hinted500, "hinted500 must be set after winning at 500+ Respect");
			st.respect = 1500;
			Battle bHint2 = BattleManager.start(p, zHint);
			bHint2.jumpToForTest(Battle.Phase.RESULT);
			assertTrue(st.hinted1500, "hinted1500 must be set after winning at 1500+ Respect");
			zHint.discard();

			EndermanEntity em = EntityType.ENDERMAN.create(w, SpawnReason.COMMAND);
			em.refreshPositionAndAngles(p.getX(), p.getY(), p.getZ() + 4, 180, 0);
			w.spawnEntity(em);
			emRef[0] = em;
			ref[0] = BattleManager.start(p, em);
			ref[0].jumpToForTest(Battle.Phase.TIMELINE);
		});
		ctx.waitFor(c -> ref[0] != null && ref[0].phase() == Battle.Phase.TIMELINE, 200);
		sp.getServer().runOnServer(server -> {
			ServerPlayerEntity p = server.getPlayerManager().getPlayerList().get(0);
			ref[0].onGaze(p);
			ref[0].jumpToForTest(Battle.Phase.RESULT);
			if (emRef[0] != null && !emRef[0].isRemoved()) emRef[0].discard();
			GameTestSupport.cleanupBattles(server);
		});
	}

	private static void testClipRecorder(ClientGameTestContext ctx) {
		Path clipsDir = FabricLoader.getInstance().getGameDir().resolve("rapbattle-clips");
		ctx.runOnClient(mc -> {
			try {
				if (Files.exists(clipsDir)) {
					try (var s = Files.list(clipsDir)) {
						s.forEach(f -> { try { Files.deleteIfExists(f); } catch (Exception ignored) {} });
					}
				}
				byte[] wav = GameTestSupport.wav();
				ClipRecorder.keep(wav, 0, 0, 2000, null, 500, "TestMob");
				ClipRecorder.save();
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		});
		ctx.waitFor(mc -> {
			try {
				if (!Files.isDirectory(clipsDir)) return false;
				try (var s = Files.list(clipsDir)) {
					return s.anyMatch(p -> p.getFileName().toString().endsWith(".wav"));
				}
			} catch (Exception e) {
				return false;
			}
		}, 200);
		ctx.runOnClient(mc -> {
			try (var s = Files.list(clipsDir)) {
				Path wavFile = s.filter(p -> p.getFileName().toString().endsWith(".wav")).findFirst().orElseThrow();
				byte[] bytes = Files.readAllBytes(wavFile);
				assertTrue(bytes.length > 44, "Saved clip WAV must contain audio data");
				assertTrue(bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F', "WAV must start with RIFF");
				assertTrue(bytes[8] == 'W' && bytes[9] == 'A' && bytes[10] == 'V' && bytes[11] == 'E', "WAV must contain WAVE header");
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		});
	}
}
