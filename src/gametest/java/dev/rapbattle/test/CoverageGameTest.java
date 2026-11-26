package dev.rapbattle.test;

import com.mojang.authlib.GameProfile;
import dev.rapbattle.RapConfig;
import dev.rapbattle.battle.*;
import dev.rapbattle.client.CalibrationScreen;
import dev.rapbattle.client.ClipRecorder;
import dev.rapbattle.client.KeysScreen;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.mob.*;
import net.minecraft.entity.passive.IronGolemEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.GameMode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/**
 * 6.19 coverage for previously untested mechanics:
 * - Block A: Coop (FakePlayer), mid-battle weapon violation, SoreLosers (rage/double-drop/blast/self-defense),
 *   GolemDefender (offended/player-created/chickenedOut/respect), 2v1 post-battle fight.
 * - Block B#8: Enderman onGaze, style unlock one-shot hints.
 * - Block C: KeysScreen (fake masked keys only), CalibrationScreen, ClipRecorder WAV output.
 */
public class CoverageGameTest implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext ctx) {
		GameTestSupport.installKeyAudit();
		ctx.getInput().resizeWindow(1280, 720);
		ctx.waitTicks(5);
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			sp.getClientWorld().waitForChunksRender();
			GameTestSupport.applyLang(ctx);
			sp.getServer().runCommand("time set 14500");

			testCoopFakePlayerAndViolation(ctx, sp);
			testSoreLosersAndGolemAndTwoOnOne(ctx, sp);
			testEndermanGazeAndStyleUnlockHints(ctx, sp);
			testScreensAndClipRecorder(ctx, sp);

			GameTestSupport.assertNoRawKeys("CoverageGameTest");
		}
	}

	private void testCoopFakePlayerAndViolation(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		// 1. Coop invite dress code (requiredToJoin is 1 level softer), onPlayerLeft, and theft from worst player
		sp.getServer().runOnServer(server -> {
			String prevProg = RapConfig.get().progression;
			boolean prevDress = RapConfig.get().dressCode;
			RapConfig.get().progression = "off";
			RapConfig.get().dressCode = true;

			ServerPlayerEntity p1 = server.getPlayerManager().getPlayerList().get(0);
			ServerWorld w = p1.getEntityWorld();
			p1.changeGameMode(GameMode.SURVIVAL);
			p1.setInvulnerable(true);
			// Give p1 full Diamond armor (level 5) so initiator passes Rank 4 (Gold)
			p1.equipStack(EquipmentSlot.HEAD, new ItemStack(Items.DIAMOND_HELMET));
			p1.equipStack(EquipmentSlot.CHEST, new ItemStack(Items.DIAMOND_CHESTPLATE));
			p1.equipStack(EquipmentSlot.LEGS, new ItemStack(Items.DIAMOND_LEGGINGS));
			p1.equipStack(EquipmentSlot.FEET, new ItemStack(Items.DIAMOND_BOOTS));

			ServerPlayerEntity p2 = FakePlayer.get(w, new GameProfile(UUID.randomUUID(), "CoopLow"));
			p2.refreshPositionAndAngles(p1.getX() + 2, p1.getY(), p1.getZ(), 0, 0);
			p2.getInventory().clear(); // level 0 -> fails requiredToJoin(GOLD) = 3

			ServerPlayerEntity p3 = FakePlayer.get(w, new GameProfile(UUID.randomUUID(), "CoopOK"));
			p3.refreshPositionAndAngles(p1.getX() + 3, p1.getY(), p1.getZ(), 0, 0);
			p3.getInventory().clear();
			// Full Iron armor -> level 3 == requiredToJoin(GOLD)
			p3.equipStack(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
			p3.equipStack(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
			p3.equipStack(EquipmentSlot.LEGS, new ItemStack(Items.IRON_LEGGINGS));
			p3.equipStack(EquipmentSlot.FEET, new ItemStack(Items.IRON_BOOTS));

			BlazeEntity blaze = EntityType.BLAZE.create(w, SpawnReason.COMMAND);
			blaze.refreshPositionAndAngles(p1.getX(), p1.getY(), p1.getZ() + 4, 180, 0);
			blaze.setAiDisabled(true);
			w.spawnEntity(blaze);

			Battle b = BattleManager.start(p1, blaze);
			b.invite(p2);
			b.invite(p3);
			b.answerInvite(p2, true);
			GameTestSupport.assertTrue(!b.hasPlayer(p2.getUuid()), "Player below requiredToJoin must be rejected from coop");
			b.answerInvite(p3, true);
			GameTestSupport.assertTrue(b.hasPlayer(p3.getUuid()), "Player at requiredToJoin (iron for gold mob) must join coop");

			// Mid-battle: p3 leaves -> battle continues!
			b.startEarly(p1);
			b.onPlayerLeft(p3.getUuid());
			GameTestSupport.assertTrue(BattleManager.byPlayer(p1.getUuid()) == b, "Battle must continue when coop partner leaves");

			// Empty-hand hit on battle mob is NOT a violation
			p1.setStackInHand(Hand.MAIN_HAND, ItemStack.EMPTY);
			AttackEntityCallback.EVENT.invoker().interact(p1, w, Hand.MAIN_HAND, blaze, null);
			GameTestSupport.assertTrue(BattleManager.byPlayer(p1.getUuid()) == b, "Empty-hand hit must NOT trigger violation");

			// Weapon hit mid-battle IS a violation!
			p1.setStackInHand(Hand.MAIN_HAND, new ItemStack(Items.DIAMOND_SWORD));
			AttackEntityCallback.EVENT.invoker().interact(p1, w, Hand.MAIN_HAND, blaze, null);
			GameTestSupport.assertTrue(BattleManager.byPlayer(p1.getUuid()) == null, "Weapon hit mid-battle must end battle via violation");
			p1.setStackInHand(Hand.MAIN_HAND, ItemStack.EMPTY);

			// Leader leaving in lobby aborts battle
			BlazeEntity blaze2 = EntityType.BLAZE.create(w, SpawnReason.COMMAND);
			blaze2.refreshPositionAndAngles(p1.getX(), p1.getY(), p1.getZ() + 4, 180, 0);
			w.spawnEntity(blaze2);
			Battle bLobby = BattleManager.start(p1, blaze2);
			bLobby.onPlayerLeft(p1.getUuid());
			GameTestSupport.assertTrue(BattleManager.byPlayer(p1.getUuid()) == null, "Leader leaving in lobby must abort battle");

			blaze.discard();
			blaze2.discard();
			p1.getInventory().clear();
			p3.getInventory().clear();
			RapConfig.get().progression = prevProg;
			RapConfig.get().dressCode = prevDress;
			GameTestSupport.cleanupBattles(server);
		});
		ctx.waitTicks(5);
	}

	private void testSoreLosersAndGolemAndTwoOnOne(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		// 1. SoreLosers blast (blocks intact) & self-defense keeps STREET_CRED
		sp.getServer().runOnServer(server -> {
			ServerPlayerEntity p = server.getPlayerManager().getPlayerList().get(0);
			ServerWorld w = p.getEntityWorld();
			BlockPos under = p.getBlockPos().add(0, -1, 4);
			w.setBlockState(under, Blocks.STONE.getDefaultState());

			ZombieEntity raged = EntityType.ZOMBIE.create(w, SpawnReason.COMMAND);
			raged.refreshPositionAndAngles(under.getX() + 0.5, under.getY() + 1.0, under.getZ() + 0.5, 180, 0);
			w.spawnEntity(raged);
			SoreLosers.blast(raged, 0);
			SoreLosers.tick();
			GameTestSupport.assertTrue(w.getBlockState(under).isOf(Blocks.STONE), "SoreLosers blast must not break blocks");

			// Self-defense against raged mob does NOT revoke STREET_CRED
			ZombieEntity z1 = EntityType.ZOMBIE.create(w, SpawnReason.COMMAND);
			z1.refreshPositionAndAngles(p.getX(), p.getY(), p.getZ() + 3, 180, 0);
			w.spawnEntity(z1);
			Battle b = BattleManager.start(p, z1);
			b.forceOutcomeForTest(true, true);
			GameTestSupport.assertTrue(Reputation.credLevel(p.getUuid()) >= 1, "Win grants street cred");

			ZombieEntity z2 = EntityType.ZOMBIE.create(w, SpawnReason.COMMAND);
			z2.refreshPositionAndAngles(p.getX() + 2, p.getY(), p.getZ() + 3, 180, 0);
			w.spawnEntity(z2);
			// Normal hostile hit DOES revoke street cred
			Reputation.onPlayerHostileAction(p, z2);
			GameTestSupport.assertTrue(Reputation.credLevel(p.getUuid()) == 0, "Hitting normal zombie must revoke street cred");
			z1.discard();
			z2.discard();
			Reputation.clear();
		});

		// 2. GolemDefender: player-created ignored, offended -> respect gives 5 iron + poppy
		sp.getServer().runOnServer(server -> {
			ServerPlayerEntity p = server.getPlayerManager().getPlayerList().get(0);
			ServerWorld w = p.getEntityWorld();
			p.getInventory().clear();

			VillagerEntity v = EntityType.VILLAGER.create(w, SpawnReason.COMMAND);
			v.refreshPositionAndAngles(p.getX(), p.getY(), p.getZ() + 4, 180, 0);
			w.spawnEntity(v);

			IronGolemEntity playerGolem = EntityType.IRON_GOLEM.create(w, SpawnReason.COMMAND);
			playerGolem.setPlayerCreated(true);
			playerGolem.refreshPositionAndAngles(p.getX() + 2, p.getY(), p.getZ() + 4, 180, 0);
			w.spawnEntity(playerGolem);

			GolemDefender.offended(p, v);
			// No natural golem yet -> playerGolem is ignored; test respect & chickenedOut directly
			GolemDefender.chickenedOut(p, playerGolem);
			GameTestSupport.assertTrue(playerGolem.getTarget() == p, "chickenedOut must aggro golem on player");
			GolemDefender.respect(playerGolem, p, "ru_ru");
			GameTestSupport.assertTrue(playerGolem.isAlive() && playerGolem.getTarget() == null, "respect must calm golem");
			GameTestSupport.assertTrue(p.getInventory().count(Items.IRON_INGOT) == 5, "respect must give 5 iron ingots");
			GameTestSupport.assertTrue(p.getInventory().count(Items.POPPY) == 1, "respect must give 1 poppy");

			v.discard();
			playerGolem.discard();
			p.getInventory().clear();
		});

		// 3. 2v1 post-battle crowd defense
		SkeletonEntity[] twoMobRef = new SkeletonEntity[1];
		SkeletonEntity[] twoFanRef = new SkeletonEntity[1];
		Battle[] twoBattleRef = new Battle[1];
		ServerPlayerEntity[] twoP2Ref = new ServerPlayerEntity[1];
		sp.getServer().runOnServer(server -> {
			ServerPlayerEntity p1 = server.getPlayerManager().getPlayerList().get(0);
			ServerWorld w = p1.getEntityWorld();
			ServerPlayerEntity p2 = FakePlayer.get(w, new GameProfile(UUID.randomUUID(), "FirstAttacker"));
			p2.refreshPositionAndAngles(p1.getX() + 2, p1.getY(), p1.getZ() + 3, 0, 0);
			twoP2Ref[0] = p2;

			// Skeleton is not a PACKS species, so nearby skeleton fan joins Crowd (Side.MOB) rather than mobTeam.
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
			b.startEarly(p1);
			twoBattleRef[0] = b;
		});
		// Wait until Battle.tick() closes the lobby (contentFuture done) and calls crowd.gather()
		ctx.waitFor(c -> twoBattleRef[0] != null && twoBattleRef[0].phase() == Battle.Phase.TIMELINE, 400);
		sp.getServer().runOnServer(server -> {
			ServerPlayerEntity p1 = server.getPlayerManager().getPlayerList().get(0);
			ServerWorld w = p1.getEntityWorld();
			SkeletonEntity mob = twoMobRef[0];
			SkeletonEntity fan = twoFanRef[0];
			ServerPlayerEntity p2 = twoP2Ref[0];

			// Close non-lethal win so mob survives with active crowd Stage
			twoBattleRef[0].forceOutcomeForTest(true, false, 85, 75);
			Crowd.expireTruceForTest();
			Reputation.clear();

			p1.setStackInHand(Hand.MAIN_HAND, new ItemStack(Items.WOODEN_SWORD));
			p2.setStackInHand(Hand.MAIN_HAND, new ItemStack(Items.WOODEN_SWORD));
			// FakePlayer hits first (1v1); real player p1 (in PlayerManager) hits second (2v1) -> fan targets p1
			AttackEntityCallback.EVENT.invoker().interact(p2, w, Hand.MAIN_HAND, mob, null);
			GameTestSupport.assertTrue(fan.getTarget() == null, "1v1 brawl after battle must NOT pull mob fans");
			AttackEntityCallback.EVENT.invoker().interact(p1, w, Hand.MAIN_HAND, mob, null);
			GameTestSupport.assertTrue(fan.getTarget() == p1, "2v1 brawl after battle must pull mob fan onto second attacker");

			p1.setStackInHand(Hand.MAIN_HAND, ItemStack.EMPTY);
			p2.setStackInHand(Hand.MAIN_HAND, ItemStack.EMPTY);
			mob.discard();
			fan.discard();
			GameTestSupport.cleanupBattles(server);
		});
		ctx.waitTicks(3);
	}

	private void testEndermanGazeAndStyleUnlockHints(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		Battle[] ref = new Battle[1];
		EndermanEntity[] emRef = new EndermanEntity[1];
		sp.getServer().runOnServer(server -> {
			ServerPlayerEntity p = server.getPlayerManager().getPlayerList().get(0);
			ServerWorld w = p.getEntityWorld();

			// Style unlock hint fires once at 500 and once at 1500 (via real Battle finish -> recordStats -> notifyStyleUnlock)
			Stats stats = Stats.of(server);
			Stats.Player st = stats.player(p.getUuid());
			st.respect = 500;
			st.hinted500 = false;
			st.hinted1500 = false;
			ZombieEntity zHint = EntityType.ZOMBIE.create(w, SpawnReason.COMMAND);
			zHint.refreshPositionAndAngles(p.getX(), p.getY(), p.getZ() + 4, 180, 0);
			w.spawnEntity(zHint);
			Battle bHint1 = BattleManager.start(p, zHint);
			bHint1.forceOutcomeForTest(true, true);
			GameTestSupport.assertTrue(st.hinted500, "hinted500 must be set after winning at 500+ Respect");
			st.respect = 1500;
			Battle bHint2 = BattleManager.start(p, zHint);
			bHint2.forceOutcomeForTest(true, true);
			GameTestSupport.assertTrue(st.hinted1500, "hinted1500 must be set after winning at 1500+ Respect");
			zHint.discard();

			// Enderman onGaze shuffles upcoming rhymes
			EndermanEntity em = EntityType.ENDERMAN.create(w, SpawnReason.COMMAND);
			em.refreshPositionAndAngles(p.getX(), p.getY(), p.getZ() + 4, 180, 0);
			w.spawnEntity(em);
			emRef[0] = em;
			ref[0] = BattleManager.start(p, em);
			ref[0].startEarly(p);
		});
		ctx.waitFor(c -> ref[0] != null && ref[0].phase() == Battle.Phase.TIMELINE, 600);
		sp.getServer().runOnServer(server -> {
			ServerPlayerEntity p = server.getPlayerManager().getPlayerList().get(0);
			ref[0].onGaze(p);
			ref[0].forceOutcomeForTest(true, true);
			if (emRef[0] != null && !emRef[0].isRemoved()) emRef[0].discard();
			GameTestSupport.cleanupBattles(server);
		});
		ctx.waitTicks(3);
	}

	private void testScreensAndClipRecorder(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		// KeysScreen with fake masked keys only (never real keys!)
		String origOr = RapConfig.get().openRouterApiKey;
		String origStt = RapConfig.get().sttApiKey;
		ctx.setScreen(() -> new KeysScreen(null));
		ctx.waitTicks(2);
		RapConfig.get().openRouterApiKey = "test-key-not-real-or";
		RapConfig.get().sttApiKey = "test-key-not-real-stt";
		try {
			GameTestSupport.assertTrue("…l-or".equals(RapConfig.mask(RapConfig.get().openRouterApiKey)), "Mask must hide prefix");
			ctx.waitTicks(3);
			GameTestSupport.takeScreenshot(ctx, "keys-screen-masked");
			ctx.setScreen(() -> null);
		} finally {
			RapConfig.get().openRouterApiKey = origOr;
			RapConfig.get().sttApiKey = origStt;
		}

		// CalibrationScreen
		ctx.setScreen(() -> new CalibrationScreen(null));
		ctx.waitTicks(5);
		GameTestSupport.takeScreenshot(ctx, "calibration-screen");
		ctx.setScreen(() -> null);

		// ClipRecorder WAV export to <gameDir>/rapbattle-clips
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
				GameTestSupport.assertTrue(bytes.length > 44, "Saved clip WAV must contain audio data");
				GameTestSupport.assertTrue(bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F', "WAV must start with RIFF");
				GameTestSupport.assertTrue(bytes[8] == 'W' && bytes[9] == 'A' && bytes[10] == 'V' && bytes[11] == 'E', "WAV must contain WAVE header");
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		});
	}
}
