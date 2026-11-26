package dev.rapbattle.battle;

import dev.rapbattle.Lang;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.raid.RaiderEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.Vec3d;
import net.minecraft.village.raid.Raid;

import java.util.*;

/**
 * Act 2: Raid finale as a "battle for the district".
 * On the final wave, the raid captain (patrol leader with banner) steps forward and challenges the nearest player.
 */
public final class RaidBattle {
	private RaidBattle() {}

	private static final Set<Integer> CHALLENGED_RAIDS = new HashSet<>();
	private static final Set<UUID> FINAL_CAPTAINS = new HashSet<>();

	static void tick(MinecraftServer server) {
		if (server.getTicks() % 20 != 0) return;
		for (ServerWorld w : server.getWorlds()) {
			for (ServerPlayerEntity p : w.getPlayers()) {
				if (p.isSpectator() || BattleManager.byPlayer(p.getUuid()) != null) continue;
				Raid raid = w.getRaidAt(p.getBlockPos());
				if (raid == null) continue;
				int raidKey = System.identityHashCode(raid);
				if (CHALLENGED_RAIDS.contains(raidKey)) continue;
				boolean active = !raid.isFinished() && !raid.hasStopped() && !raid.hasWon();
				int groups = raid.getGroupsSpawned();
				int maxWaves = raid.getMaxWaves(w.getDifficulty());
				RaiderEntity captain = findCaptain(raid);
				if (!Acts.isFinalWaveWithCaptain(true, active, groups, maxWaves, captain != null && captain.isAlive())) {
					continue;
				}
				if (captain.squaredDistanceTo(p) > 24 * 24 || BattleManager.byMob(captain) != null) continue;
				CHALLENGED_RAIDS.add(raidKey);
				FINAL_CAPTAINS.add(captain.getUuid());
				captain.getNavigation().startMovingTo(p, 1.1);
				Speech.bubble(captain, Lang.t(Lang.of(p), "rapbattle.act2.raid_callout"), 0xF07167, 4000);
				BattleManager.challengeFrom(p, captain, "rapbattle.manager.raid_captain_challenge");
			}
		}
	}

	static Raid activeRaidFor(MobEntity mob) {
		if (mob instanceof RaiderEntity r) {
			Raid raid = r.getRaid();
			if (raid == null || raid.isFinished() || raid.hasStopped() || raid.hasWon()) return null;
			if (FINAL_CAPTAINS.contains(mob.getUuid())) return raid;
			if (mob.getEntityWorld() instanceof ServerWorld sw) {
				int groups = raid.getGroupsSpawned();
				int maxWaves = raid.getMaxWaves(sw.getDifficulty());
				RaiderEntity captain = findCaptain(raid);
				boolean isCap = captain != null && captain.getUuid().equals(mob.getUuid());
				if (Acts.isFinalWaveCaptainMatch(true, true, groups, maxWaves, isCap)) {
					FINAL_CAPTAINS.add(mob.getUuid());
					return raid;
				}
			}
		}
		return null;
	}

	private static RaiderEntity findCaptain(Raid raid) {
		RaiderEntity cap = raid.getCaptain(raid.getGroupsSpawned());
		if (cap != null && cap.isAlive()) return cap;
		for (RaiderEntity r : raid.getAllRaiders()) {
			if (r.isAlive() && r.isPatrolLeader()) return r;
		}
		return null;
	}

	/** Handles raid victory (lethal / 25+ margin) or close win (captain flees). */
	static void onWin(ServerWorld world, Raid raid, List<UUID> players, boolean lethal,
					  Vec3d pos, Vec3d awayFrom, MobEntity captain, Random rnd) {
		if (!lethal) {
			if (captain instanceof RaiderEntity cr) {
				raid.removeFromWave(world, cr, true);
				cr.setRaid(null);
			}
			return;
		}
		List<RaiderEntity> all = new ArrayList<>(raid.getAllRaiders());
		List<MobEntity> remaining = new ArrayList<>();
		for (RaiderEntity r : all) {
			raid.removeFromWave(world, r, true);
			r.setRaid(null);
			if (r.isAlive() && r != captain) {
				r.equipStack(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
				remaining.add(r);
			}
		}
		for (UUID u : players) {
			ServerPlayerEntity p = world.getServer().getPlayerManager().getPlayer(u);
			if (p != null) {
				p.addStatusEffect(new StatusEffectInstance(StatusEffects.HERO_OF_THE_VILLAGE, 48_000, 0, false, false, true));
			}
		}
		if (!remaining.isEmpty()) {
			Crowd.retreat(remaining, awayFrom, 20_000L);
			Later.run(20_000L, () -> {
				for (MobEntity m : remaining) {
					if (m.isAlive()) m.discard();
				}
			});
		}
		int emeralds = 8 + rnd.nextInt(9);
		ItemEntity emDrop = new ItemEntity(world, pos.x, pos.y, pos.z, new ItemStack(Items.EMERALD, emeralds));
		emDrop.setToDefaultPickupDelay();
		world.spawnEntity(emDrop);
		ItemEntity totemDrop = new ItemEntity(world, pos.x, pos.y, pos.z, new ItemStack(Items.TOTEM_OF_UNDYING));
		totemDrop.setToDefaultPickupDelay();
		world.spawnEntity(totemDrop);

		for (ServerPlayerEntity p : world.getPlayers()) {
			if (p.squaredDistanceTo(pos) < 64 * 64) {
				p.sendMessage(Text.translatable("rapbattle.act2.raid_broken").formatted(Formatting.GOLD), false);
			}
		}
	}

	/** Handles raid defeat: surviving raiders gain Strength I and Speed I for 60 seconds. */
	static void onLoss(ServerWorld world, Raid raid, Vec3d pos) {
		for (RaiderEntity r : raid.getAllRaiders()) {
			if (r.isAlive()) {
				r.addStatusEffect(new StatusEffectInstance(StatusEffects.STRENGTH, 1200, 0));
				r.addStatusEffect(new StatusEffectInstance(StatusEffects.SPEED, 1200, 0));
			}
		}
		for (ServerPlayerEntity p : world.getPlayers()) {
			if (p.squaredDistanceTo(pos) < 64 * 64) {
				p.sendMessage(Text.translatable("rapbattle.act2.raid_enraged").formatted(Formatting.RED), false);
			}
		}
	}
}
