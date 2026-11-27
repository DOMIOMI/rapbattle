package dev.rapbattle.test;

import com.mojang.authlib.GameProfile;
import dev.rapbattle.Clock;
import dev.rapbattle.RapConfig;
import dev.rapbattle.battle.*;
import dev.rapbattle.client.ClipRecorder;
import dev.rapbattle.net.BattleView;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.mob.BlazeEntity;
import net.minecraft.entity.mob.EndermanEntity;
import net.minecraft.entity.mob.SkeletonEntity;
import net.minecraft.entity.mob.WitchEntity;
import net.minecraft.entity.mob.ZombieEntity;
import net.minecraft.entity.passive.IronGolemEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.entity.projectile.ArrowEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import static dev.rapbattle.test.GameTestSupport.assertTrue;

/** Coverage scenario (6.19 & 6.21): no fighting in battle, 2v1 with witnesses & truce, SoreLosers tryRage+blast, GolemDefender, Enderman gaze, ClipRecorder. */
public final class CoverageScenario {
	private CoverageScenario() {}

	public static void run(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		testNoFightingDuringBattle(ctx, sp);
		testSoreLosersTryRageAndGolemTickAndTwoOnOne(ctx, sp);
		testEndermanGazeAndStyleUnlockHints(ctx, sp);
		testReview622SecurityAndLifecycle(ctx, sp);
		testBattleProtection623(ctx, sp);
		testClipRecorder(ctx);
		GameTestSupport.assertNoRawKeys("coverage");
	}

	/** 6.21 Item 1: Participant in battle cannot hit opponent, crowd mob, other player, or deal bow damage; mic-only hint shows once per 3s. */
	private static void testNoFightingDuringBattle(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		GameTestSupport.clearActionbarMessages();
		Battle[] bRef = new Battle[1];
		ZombieEntity[] crowdMobRef = new ZombieEntity[1];
		BlazeEntity[] blazeRef = new BlazeEntity[1];
		ServerPlayerEntity[] p2Ref = new ServerPlayerEntity[1];

		sp.getServer().runOnServer(server -> {
			RapConfig.get().progression = "off";
			RapConfig.get().dressCode = false;
			ServerPlayerEntity p1 = server.getPlayerManager().getPlayerList().get(0);
			ServerWorld w = p1.getEntityWorld();
			p1.changeGameMode(net.minecraft.world.GameMode.SURVIVAL);
			p1.setInvulnerable(true);

			ServerPlayerEntity p2 = FakePlayer.get(w, new GameProfile(UUID.randomUUID(), "Bystander"));
			p2.refreshPositionAndAngles(p1.getX() + 2, p1.getY(), p1.getZ(), 0, 0);
			p2.setHealth(p2.getMaxHealth());
			p2Ref[0] = p2;

			BlazeEntity blaze = EntityType.BLAZE.create(w, SpawnReason.COMMAND);
			blaze.refreshPositionAndAngles(p1.getX(), p1.getY(), p1.getZ() + 4, 180, 0);
			blaze.setAiDisabled(true);
			w.spawnEntity(blaze);
			blazeRef[0] = blaze;

			ZombieEntity crowdMob = EntityType.ZOMBIE.create(w, SpawnReason.COMMAND);
			crowdMob.refreshPositionAndAngles(p1.getX() + 4, p1.getY(), p1.getZ() + 4, 180, 0);
			w.spawnEntity(crowdMob);
			crowdMobRef[0] = crowdMob;

			Battle b = BattleManager.start(p1, blaze);
			b.jumpToForTest(Battle.Phase.TIMELINE);
			bRef[0] = b;

			p1.setStackInHand(Hand.MAIN_HAND, new ItemStack(Items.IRON_SWORD));
			float blazeHp = blaze.getHealth();
			float crowdHp = crowdMob.getHealth();
			float p2Hp = p2.getHealth();

			// Hit opponent, crowd mob, and second player -> all cancelled with FAIL, battle continues
			ActionResult r1 = AttackEntityCallback.EVENT.invoker().interact(p1, w, Hand.MAIN_HAND, blaze, null);
			ActionResult r2 = AttackEntityCallback.EVENT.invoker().interact(p1, w, Hand.MAIN_HAND, crowdMob, null);
			ActionResult r3 = AttackEntityCallback.EVENT.invoker().interact(p1, w, Hand.MAIN_HAND, p2, null);
			assertTrue(r1 == ActionResult.FAIL && r2 == ActionResult.FAIL && r3 == ActionResult.FAIL,
				"All attacks during battle must be cancelled");

			// Bow arrow shot by participant must deal zero damage
			ArrowEntity arrow = new ArrowEntity(w, p1, new ItemStack(Items.ARROW), null);
			boolean damaged = crowdMob.damage(w, w.getDamageSources().arrow(arrow, p1), 6.0f);
			arrow.discard();
			assertTrue(!damaged, "Bow arrow from battle participant must not deal damage");
			assertTrue(blaze.getHealth() == blazeHp && crowdMob.getHealth() == crowdHp && p2.getHealth() == p2Hp,
				"No entity may take damage during battle");
			assertTrue(b.phase() == Battle.Phase.TIMELINE, "Battle must remain active without forfeit");
		});
		ctx.waitTicks(3);
		int hintCount = GameTestSupport.countActionbarContaining("микрофон") + GameTestSupport.countActionbarContaining("Mic only");
		assertTrue(hintCount == 1, "Mic-only actionbar hint must appear exactly once within 3s cooldown (got " + hintCount + ")");

		// After battle ends, sword hit deals damage again
		sp.getServer().runOnServer(server -> {
			ServerPlayerEntity p1 = server.getPlayerManager().getPlayerList().get(0);
			ServerWorld w = p1.getEntityWorld();
			bRef[0].jumpToForTest(Battle.Phase.RESULT);
			Clock.advanceForTest(20_000L);
			ZombieEntity target = EntityType.ZOMBIE.create(w, SpawnReason.COMMAND);
			target.refreshPositionAndAngles(p1.getX(), p1.getY(), p1.getZ() + 3, 180, 0);
			w.spawnEntity(target);
			float before = target.getHealth();
			p1.attack(target);
			assertTrue(target.getHealth() < before, "Sword hit after battle ends must deal damage again");

			target.discard();
			if (!blazeRef[0].isRemoved()) blazeRef[0].discard();
			if (!crowdMobRef[0].isRemoved()) crowdMobRef[0].discard();
			p1.setStackInHand(Hand.MAIN_HAND, ItemStack.EMPTY);
			p1.getInventory().clear();
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
		ServerPlayerEntity[] innocentP3Ref = new ServerPlayerEntity[1];

		sp.getServer().runOnServer(server -> {
			ServerPlayerEntity p = server.getPlayerManager().getPlayerList().get(0);
			ServerWorld w = p.getEntityWorld();

			// 1. SoreLosers.tryRage: survives at ~50% HP, targets MVP via forceMobTarget even with Street Cred, double drops, self-defense preserves Cred
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
			assertTrue(raged.getTarget() == p, "Raged mob must target MVP via mod-forced path");
			Reputation.onPlayerHostileAction(p, raged);
			assertTrue(PlayerStatus.has(p.getUuid(), PlayerStatus.STREET_CRED),
				"Hitting a RAGED mob (self-defense) must NOT revoke Street Cred");
			ItemEntity dropSample = new ItemEntity(w, raged.getX(), raged.getY(), raged.getZ(), new ItemStack(Items.ROTTEN_FLESH, 1));
			w.spawnEntity(dropSample);
			SoreLosers.doubleFreshDrops(w, raged.getEntityPos());
			List<ItemEntity> fleshDrops = w.getEntitiesByClass(ItemEntity.class, new Box(raged.getBlockPos()).expand(3),
				e -> e.getStack().isOf(Items.ROTTEN_FLESH));
			int totalFlesh = fleshDrops.stream().mapToInt(it -> it.getStack().getCount()).sum();
			assertTrue(totalFlesh == 2, "SoreLosers doubleFreshDrops must duplicate fresh drops (got " + totalFlesh + ")");
			for (ItemEntity it : fleshDrops) it.discard();
			raged.kill(w);
			for (ItemEntity it : w.getEntitiesByClass(ItemEntity.class, new Box(raged.getBlockPos()).expand(3), e -> e.isAlive())) {
				it.discard();
			}
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
			ServerPlayerEntity p1 = server.getPlayerManager().getPlayerList().get(0);
			assertTrue(BattleManager.hasPendingForTest(p1.getUuid()), "Natural golem must hunt and open PENDING via GolemDefender.tick()");
			BattleManager.clearPendingForTest(p1.getUuid());
			if (naturalGolemRef[0] != null) naturalGolemRef[0].discard();
			p1.getInventory().clear();

			// 3. 6.21 Item 2: 2v1 post-battle crowd defense WITH active truce & WITNESSES (no clear/expire!)
			ServerWorld w = p1.getEntityWorld();
			ServerPlayerEntity p2 = FakePlayer.get(w, new GameProfile(UUID.randomUUID(), "FirstAttacker"));
			p2.refreshPositionAndAngles(p1.getX() + 2, p1.getY(), p1.getZ() + 3, 0, 0);
			BattleManager.registerTestPlayer(p2);
			twoP2Ref[0] = p2;

			ServerPlayerEntity p3 = FakePlayer.get(w, new GameProfile(UUID.randomUUID(), "InnocentMate"));
			p3.refreshPositionAndAngles(p1.getX() - 2, p1.getY(), p1.getZ() + 3, 0, 0);
			BattleManager.registerTestPlayer(p3);
			innocentP3Ref[0] = p3;

			SkeletonEntity mob = EntityType.SKELETON.create(w, SpawnReason.COMMAND);
			mob.refreshPositionAndAngles(p1.getX(), p1.getY(), p1.getZ() + 4, 180, 0);
			mob.setAiDisabled(true);
			w.spawnEntity(mob);
			twoMobRef[0] = mob;

			SkeletonEntity fan = EntityType.SKELETON.create(w, SpawnReason.COMMAND);
			fan.refreshPositionAndAngles(p1.getX() + 5, p1.getY(), p1.getZ() + 4, 180, 0);
			w.spawnEntity(fan);
			twoFanRef[0] = fan;

			Battle b = BattleManager.start(p1, mob);
			b.invite(p3);
			b.answerInvite(p3, true);
			b.setPlayerScoreForTest(p1.getName().getString(), 60);
			b.setPlayerScoreForTest(p3.getName().getString(), 40);
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
			ServerPlayerEntity p3 = innocentP3Ref[0];

			// Non-lethal win: truce and WITNESSES are both active right now!
			twoBattleRef[0].jumpToForTest(Battle.Phase.RESULT, true, false, 85, 75);
			assertTrue(Crowd.inTruce(fan, p1.getUuid()) && Crowd.inTruce(fan, p3.getUuid()), "Fan must be in truce after win");
			assertTrue(Reputation.respects(fan, p1) && Reputation.respects(fan, p3), "Fan witness must respect both winners");

			p1.setStackInHand(Hand.MAIN_HAND, new ItemStack(Items.WOODEN_SWORD));
			p2.setStackInHand(Hand.MAIN_HAND, new ItemStack(Items.WOODEN_SWORD));
			// First hit (p2, 1v1): 2 hits -> enters brawl, fan does not intervene
			AttackEntityCallback.EVENT.invoker().interact(p2, w, Hand.MAIN_HAND, mob, null);
			AttackEntityCallback.EVENT.invoker().interact(p2, w, Hand.MAIN_HAND, mob, null);
			assertTrue(fan.getTarget() == null, "1v1 brawl after battle must NOT pull mob fans");

			// Second attacker (p1, 2v1): fan breaks truce/witnesses for p1 and targets p1; innocent p3 stays protected!
			AttackEntityCallback.EVENT.invoker().interact(p1, w, Hand.MAIN_HAND, mob, null);
			AttackEntityCallback.EVENT.invoker().interact(p1, w, Hand.MAIN_HAND, mob, null);
			assertTrue(fan.getTarget() == p1, "2v1 brawl must pull mob fan onto second attacker even with active truce/witnesses");
			assertTrue(!Reputation.respects(fan, p1), "Attacker p1 must lose witness respect");
			assertTrue(!Crowd.inTruce(fan, p1.getUuid()), "Fan truce must break for attacker p1");
			assertTrue(Reputation.respects(fan, p3) && Crowd.inTruce(fan, p3.getUuid()),
				"Innocent player p3 must keep witness respect and truce");

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

	/** 6.22: #1 bar spam cap, #3 crowdIds dedup, #8 all coop disconnect, #10 env damage abort vs mob dmg block, #10a lock & leave, #20 last bar on-beat. */
	private static void testReview622SecurityAndLifecycle(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		Battle[] bRef = new Battle[1];
		ZombieEntity[] zRef = new ZombieEntity[1];
		double[] anchor = new double[2];

		// #1 + #3 + #20
		sp.getServer().runOnServer(server -> {
			RapConfig.get().progression = "off";
			ServerPlayerEntity p = server.getPlayerManager().getPlayerList().get(0);
			ServerWorld w = p.getEntityWorld();
			ZombieEntity crowd = EntityType.ZOMBIE.create(w, SpawnReason.COMMAND);
			crowd.refreshPositionAndAngles(p.getX() + 4, p.getY(), p.getZ() + 4, 180, 0);
			w.spawnEntity(crowd);
			ZombieEntity z = EntityType.ZOMBIE.create(w, SpawnReason.COMMAND);
			z.refreshPositionAndAngles(p.getX(), p.getY(), p.getZ() + 4, 180, 0);
			w.spawnEntity(z);
			zRef[0] = z;
			Battle b = BattleManager.start(p, z);
			bRef[0] = b;
			int crowdBefore = b.crowdIdsCountForTest();
			for (int i = 0; i < 20; i++) b.broadcastStateForTest();
			assertTrue(b.crowdIdsCountForTest() == crowdBefore, "crowdIds must not grow on repeated broadcastState()");
			crowd.discard();

			b.jumpToForTest(Battle.Phase.TIMELINE);
			BattleView v = b.viewForTest();
			BattleView.Row firstPlayerRow = v.rows.stream().filter(r -> !r.mob).findFirst().orElseThrow();
			BattleView.Row lastPlayerRow = v.rows.stream().filter(r -> !r.mob).reduce((a, c) -> c).orElseThrow();
			long beatMs = Math.max(200L, v.beatMs);
			// Advance clock into first player row's beat window (+2 beats)
			long targetMs = (long) (firstPlayerRow.startBeat + 2.5) * beatMs;
			Clock.advanceForTest(Math.max(0L, targetMs));
			for (int i = 0; i < 50; i++) {
				b.onBar(p, true, 0, false);
			}
			String pName = p.getName().getString();
			assertTrue(b.reportedBarsCountForTest(pName) == 1, "50 bar packets in 1 tick must record only 1 bar");
			assertTrue(b.hypeForTest(pName) <= 18, "Hype must be capped when spamming bar packets");

			// #20: Advance to last bar and report on-beat right at JUDGING grace window
			int playerRowCount = (int) v.rows.stream().filter(r -> !r.mob).count();
			for (int idx = 1; idx < playerRowCount - 1; idx++) {
				Clock.advanceForTest(4L * beatMs);
				b.onBar(p, true, 0, false);
			}
			Clock.advanceForTest(4L * beatMs);
			b.jumpToForTest(Battle.Phase.JUDGING);
			b.onBar(p, true, 0, false);
			assertTrue(b.reportedBarsCountForTest(pName) == playerRowCount, "Last bar within 500ms JUDGING grace must be recorded");
			int lastDelta = b.lastBarDeltaForTest(pName);
			assertTrue(Scoring.hybridBeat(null, 1, false, lastDelta, (int) beatMs) == 2,
				"Last bar on-beat within 500ms JUDGING grace must yield beatState=2 (delta=" + lastDelta + ")");
			GameTestSupport.cleanupBattles(server);
			z.discard();
		});
		ctx.waitTicks(2);

		// #8: 3 coop players all disconnect -> battle aborted, mob has no crown or stolen item
		sp.getServer().runOnServer(server -> {
			ServerPlayerEntity p1 = server.getPlayerManager().getPlayerList().get(0);
			ServerWorld w = p1.getEntityWorld();
			p1.getInventory().setStack(0, new ItemStack(Items.EMERALD, 1));
			ServerPlayerEntity p2 = FakePlayer.get(w, new GameProfile(UUID.randomUUID(), "CoopA"));
			ServerPlayerEntity p3 = FakePlayer.get(w, new GameProfile(UUID.randomUUID(), "CoopB"));
			p2.refreshPositionAndAngles(p1.getX() + 1, p1.getY(), p1.getZ(), 0, 0);
			p3.refreshPositionAndAngles(p1.getX() + 2, p1.getY(), p1.getZ(), 0, 0);
			ZombieEntity z = EntityType.ZOMBIE.create(w, SpawnReason.COMMAND);
			z.refreshPositionAndAngles(p1.getX(), p1.getY(), p1.getZ() + 4, 180, 0);
			w.spawnEntity(z);
			Battle b = BattleManager.start(p1, z);
			b.invite(p2);
			b.answerInvite(p2, true);
			b.invite(p3);
			b.answerInvite(p3, true);
			b.onPlayerLeft(p1.getUuid());
			b.onPlayerLeft(p2.getUuid());
			b.onPlayerLeft(p3.getUuid());
			assertTrue(!BattleManager.isBattling(p1), "Coop battle must abort when all players leave");
			assertTrue(z.getCustomName() == null && !Theft.hasStolen(z), "Aborted coop battle mob must have no crown or stolen item");
			z.discard();
			p1.getInventory().clear();
			GameTestSupport.cleanupBattles(server);
		});
		ctx.waitTicks(2);

		// #10: zombie hit blocked (0 dmg), lava damage passes & aborts battle without penalty
		sp.getServer().runOnServer(server -> {
			ServerPlayerEntity p = server.getPlayerManager().getPlayerList().get(0);
			ServerWorld w = p.getEntityWorld();
			p.changeGameMode(net.minecraft.world.GameMode.SURVIVAL);
			p.setInvulnerable(false);
			p.setHealth(p.getMaxHealth());
			ZombieEntity z = EntityType.ZOMBIE.create(w, SpawnReason.COMMAND);
			z.refreshPositionAndAngles(p.getX(), p.getY(), p.getZ() + 4, 180, 0);
			w.spawnEntity(z);
			zRef[0] = z;
			bRef[0] = BattleManager.start(p, z);
			boolean allowMob = ServerLivingEntityEvents.ALLOW_DAMAGE.invoker().allowDamage(p, w.getDamageSources().mobAttack(z), 4.0f);
			assertTrue(!allowMob && BattleManager.isBattling(p), "Mob attack during battle must be blocked and battle continue");
			float hpBefore = p.getHealth();
			p.damage(w, w.getDamageSources().lava(), 4.0f);
			assertTrue(p.getHealth() < hpBefore, "Lava damage must pass through during battle");
			assertTrue(!BattleManager.isBattling(p), "Non-minor environmental damage must abort battle");
			assertTrue(z.getCustomName() == null && !Theft.hasStolen(z), "Env abort must not grant mob crown or steal items");
			p.setHealth(p.getMaxHealth());
			p.extinguish();
			z.discard();
			GameTestSupport.cleanupBattles(server);
		});
		ctx.waitTicks(2);

		// #10a: block/item/container lock + anchor + /rapbattle leave
		GameTestSupport.clearActionbarMessages();
		sp.getServer().runOnServer(server -> {
			ServerPlayerEntity p = server.getPlayerManager().getPlayerList().get(0);
			ServerWorld w = p.getEntityWorld();
			anchor[0] = p.getX();
			anchor[1] = p.getZ();
			p.setStackInHand(Hand.MAIN_HAND, new ItemStack(Items.ENDER_PEARL, 1));
			ZombieEntity z = EntityType.ZOMBIE.create(w, SpawnReason.COMMAND);
			z.refreshPositionAndAngles(p.getX(), p.getY(), p.getZ() + 4, 180, 0);
			w.spawnEntity(z);
			zRef[0] = z;
			bRef[0] = BattleManager.start(p, z);
			BlockPos chestPos = p.getBlockPos().south(1);
			ActionResult blk = UseBlockCallback.EVENT.invoker().interact(p, w, Hand.MAIN_HAND,
				new BlockHitResult(Vec3d.ofCenter(chestPos), Direction.UP, chestPos, false));
			ActionResult itm = UseItemCallback.EVENT.invoker().interact(p, w, Hand.MAIN_HAND);
			assertTrue(blk == ActionResult.FAIL && itm == ActionResult.FAIL,
				"UseBlockCallback and UseItemCallback must fail during battle");
			p.setPos(anchor[0] + 2.0, p.getY(), anchor[1]);
		});
		ctx.waitTicks(2);
		sp.getServer().runOnServer(server -> {
			ServerPlayerEntity p = server.getPlayerManager().getPlayerList().get(0);
			double dx = p.getX() - anchor[0];
			double dz = p.getZ() - anchor[1];
			assertTrue(dx * dx + dz * dz <= 0.31 * 0.31, "Anchor must keep player within +-0.3 blocks during battle");
		});
		ctx.waitTicks(1);
		sp.getServer().runCommand("execute as " + sp.getServer().computeOnServer(s -> s.getPlayerManager().getPlayerList().get(0).getName().getString()) + " run rapbattle leave");
		ctx.waitTicks(2);
		sp.getServer().runOnServer(server -> {
			ServerPlayerEntity p = server.getPlayerManager().getPlayerList().get(0);
			assertTrue(!BattleManager.isBattling(p), "/rapbattle leave must end the active battle");
			ActionResult itmAfter = UseItemCallback.EVENT.invoker().interact(p, p.getEntityWorld(), Hand.MAIN_HAND);
			assertTrue(itmAfter != ActionResult.FAIL, "After /rapbattle leave, item use must no longer be cancelled");
			p.setStackInHand(Hand.MAIN_HAND, ItemStack.EMPTY);
			if (zRef[0] != null && !zRef[0].isRemoved()) zRef[0].discard();
			GameTestSupport.cleanupBattles(server);
			RapConfig.get().progression = "soft";
		});
	}

	/** 6.23: Blaze fireball, Elder Guardian underwater air & Mining Fatigue, Witch potions, Warden Darkness, mod loss effects, pre-battle effects, inFire, lava, void, /kill bypass. */
	private static void testBattleProtection623(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		Battle[] bRef = new Battle[1];
		ZombieEntity[] zRef = new ZombieEntity[1];
		BlockPos[] waterPosRef = new BlockPos[2];

		sp.getServer().runOnServer(server -> {
			RapConfig.get().progression = "off";
			ServerPlayerEntity p = server.getPlayerManager().getPlayerList().get(0);
			ServerWorld w = p.getEntityWorld();
			p.changeGameMode(net.minecraft.world.GameMode.SURVIVAL);
			p.setInvulnerable(false);
			p.setHealth(p.getMaxHealth());
			p.clearStatusEffects();

			// Pre-battle beneficial + harmful effects
			p.addStatusEffect(new net.minecraft.entity.effect.StatusEffectInstance(
				net.minecraft.entity.effect.StatusEffects.STRENGTH, 400, 0));
			p.addStatusEffect(new net.minecraft.entity.effect.StatusEffectInstance(
				net.minecraft.entity.effect.StatusEffects.SLOWNESS, 80, 0));

			// Submerge player in water to test underwater air + Elder Guardian
			BlockPos feet = p.getBlockPos();
			BlockPos head = feet.up();
			waterPosRef[0] = feet;
			waterPosRef[1] = head;
			w.setBlockState(feet, Blocks.WATER.getDefaultState());
			w.setBlockState(head, Blocks.WATER.getDefaultState());

			ZombieEntity z = EntityType.ZOMBIE.create(w, SpawnReason.COMMAND);
			z.refreshPositionAndAngles(p.getX(), p.getY(), p.getZ() + 4, 180, 0);
			w.spawnEntity(z);
			zRef[0] = z;
			bRef[0] = BattleManager.start(p, z);

			// Blaze fireball + setOnFireFor
			BlazeEntity blaze = EntityType.BLAZE.create(w, SpawnReason.COMMAND);
			blaze.refreshPositionAndAngles(p.getX() + 3, p.getY(), p.getZ() + 3, 180, 0);
			w.spawnEntity(blaze);
			net.minecraft.entity.projectile.SmallFireballEntity fb = new net.minecraft.entity.projectile.SmallFireballEntity(
				w, blaze, new net.minecraft.util.math.Vec3d(0, 0, -1));
			p.damage(w, w.getDamageSources().fireball(fb, blaze), 5.0f);
			p.setOnFireFor(5.0f);
			assertTrue(!p.isOnFire() && p.getFireTicks() <= 0, "Blaze fireball must not ignite battling player (fireTicks=" + p.getFireTicks() + ")");
			assertTrue(p.getHealth() >= p.getMaxHealth(), "Blaze fireball must deal 0 damage during battle");
			assertTrue(BattleManager.isBattling(p), "Blaze fireball must not abort battle");
			blaze.discard();

			// Elder Guardian Mining Fatigue, Witch harm+poison, Warden Darkness, mob trying to extend pre-battle Slowness
			p.addStatusEffect(new net.minecraft.entity.effect.StatusEffectInstance(
				net.minecraft.entity.effect.StatusEffects.MINING_FATIGUE, 6000, 2));
			assertTrue(!p.hasStatusEffect(net.minecraft.entity.effect.StatusEffects.MINING_FATIGUE),
				"Elder Guardian Mining Fatigue must be blocked during battle");

			WitchEntity witch = EntityType.WITCH.create(w, SpawnReason.COMMAND);
			witch.refreshPositionAndAngles(p.getX() - 3, p.getY(), p.getZ() + 3, 180, 0);
			w.spawnEntity(witch);
			p.damage(w, w.getDamageSources().indirectMagic(witch, witch), 6.0f);
			p.addStatusEffect(new net.minecraft.entity.effect.StatusEffectInstance(
				net.minecraft.entity.effect.StatusEffects.POISON, 200, 0));
			assertTrue(!p.hasStatusEffect(net.minecraft.entity.effect.StatusEffects.POISON), "Witch Poison must be blocked");
			assertTrue(p.getHealth() >= p.getMaxHealth(), "Witch Harming potion must deal 0 damage");
			witch.discard();

			p.addStatusEffect(new net.minecraft.entity.effect.StatusEffectInstance(
				net.minecraft.entity.effect.StatusEffects.DARKNESS, 200, 0));
			assertTrue(!p.hasStatusEffect(net.minecraft.entity.effect.StatusEffects.DARKNESS), "Warden Darkness must be blocked");

			p.addStatusEffect(new net.minecraft.entity.effect.StatusEffectInstance(
				net.minecraft.entity.effect.StatusEffects.SLOWNESS, 500, 0));
			var slow = p.getStatusEffect(net.minecraft.entity.effect.StatusEffects.SLOWNESS);
			assertTrue(slow != null && slow.getDuration() <= 80, "Pre-battle Slowness stays but must not be extended");
			assertTrue(p.hasStatusEffect(net.minecraft.entity.effect.StatusEffects.STRENGTH), "Pre-battle Strength must remain");

			// Fire under feet (inFire)
			p.damage(w, w.getDamageSources().inFire(), 2.0f);
			p.setOnFireFor(5.0f);
			assertTrue(p.getHealth() >= p.getMaxHealth() && !p.isOnFire() && p.getFireTicks() <= 0 && BattleManager.isBattling(p),
				"inFire must deal 0 damage, not ignite, and not abort battle");
		});
		// Simulate >30s underwater air check across ticks
		ctx.waitTicks(35);
		sp.getServer().runOnServer(server -> {
			ServerPlayerEntity p = server.getPlayerManager().getPlayerList().get(0);
			ServerWorld w = p.getEntityWorld();
			assertTrue(p.getAir() == p.getMaxAir(), "Underwater air must stay full during battle");
			assertTrue(p.getHealth() >= p.getMaxHealth(), "Player must not drown during battle");
			assertTrue(BattleManager.isBattling(p), "Underwater battle must remain active");
			w.setBlockState(waterPosRef[0], Blocks.AIR.getDefaultState());
			w.setBlockState(waterPosRef[1], Blocks.AIR.getDefaultState());

			// Mod loss effects (Nausea, Weakness, Run! Speed I) must apply
			bRef[0].jumpToForTest(Battle.Phase.RESULT, false, true, 10, 90);
			assertTrue(p.hasStatusEffect(net.minecraft.entity.effect.StatusEffects.WEAKNESS), "Mod loss Weakness must apply");
			assertTrue(p.hasStatusEffect(net.minecraft.entity.effect.StatusEffects.NAUSEA), "Mod loss Nausea must apply");
			assertTrue(p.hasStatusEffect(net.minecraft.entity.effect.StatusEffects.SPEED), "Mod loss Run! Speed I must apply");
			assertTrue(!p.isOnFire() && p.getFireTicks() <= 0, "Player must not burn after battle ends");
			p.clearStatusEffects();
			p.setHealth(p.getMaxHealth());

			// Void (outOfWorld) aborts battle
			ZombieEntity zClean = EntityType.ZOMBIE.create(w, SpawnReason.COMMAND);
			zClean.refreshPositionAndAngles(p.getX(), p.getY(), p.getZ() + 4, 180, 0);
			w.spawnEntity(zClean);
			Battle bVoid = BattleManager.start(p, zClean);
			p.timeUntilRegen = 0;
			p.damage(w, w.getDamageSources().outOfWorld(), 4.0f);
			assertTrue(!BattleManager.isBattling(p) && p.getHealth() < p.getMaxHealth(),
				"Void damage must pass and abort battle");
			p.setHealth(p.getMaxHealth());

			// Lava aborts battle without penalty
			Battle bLava = BattleManager.start(p, zClean);
			p.timeUntilRegen = 0;
			p.damage(w, w.getDamageSources().lava(), 4.0f);
			assertTrue(!BattleManager.isBattling(p) && p.getHealth() < p.getMaxHealth(),
				"Lava damage must pass and abort battle");
			assertTrue(zClean.getCustomName() == null && !Theft.hasStolen(zClean),
				"Lava abort must not grant mob crown or steal items");
			p.setHealth(p.getMaxHealth());
			p.extinguish();

			// /kill (genericKill / BYPASSES_INVULNERABILITY) passes damage
			Battle bKill = BattleManager.start(p, zClean);
			float hpBeforeKill = p.getHealth();
			p.timeUntilRegen = 0;
			p.damage(w, w.getDamageSources().genericKill(), 4.0f);
			assertTrue(p.getHealth() < hpBeforeKill, "/kill (BYPASSES_INVULNERABILITY) must pass during battle");

			p.setHealth(p.getMaxHealth());
			p.clearStatusEffects();
			zClean.discard();
			if (zRef[0] != null && !zRef[0].isRemoved()) zRef[0].discard();
			GameTestSupport.cleanupBattles(server);
			RapConfig.get().progression = "soft";
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
