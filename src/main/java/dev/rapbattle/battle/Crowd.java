package dev.rapbattle.battle;

import dev.rapbattle.Lang;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.passive.AllayEntity;
import net.minecraft.entity.passive.ParrotEntity;
import net.minecraft.entity.passive.SnowGolemEntity;
import net.minecraft.entity.passive.TameableEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Vec3d;

import java.util.*;

/**
 * Mobs around the stage gather into a ring, pick a side and react: cheer, "ооо" on punchlines, boo on silence.
 * Hostile mobs in the crowd keep a truce while the battle lasts (nobody gets hit at a rap battle).
 */
public final class Crowd {
	public enum Side { MOB, PLAYERS, NEUTRAL }

	public enum Moment { START, MOB_PUNCH, PLAYER_HIT, PLAYER_MISS, JUDGING, WIN_PLAYERS, WIN_MOB, UPSET }

	private static final int RADIUS = 16, MAX = 20;
	/** After the battle the truce holds a little longer, then everyone is free to fight. */
	private static final Map<MobEntity, Long> TRUCE = new IdentityHashMap<>();
	private static final Map<MobEntity, Set<UUID>> TRUCE_EXCEPTIONS = new IdentityHashMap<>();
	private static final Set<MobEntity> ALL_PLACED = Collections.newSetFromMap(new IdentityHashMap<>());
	private record Retreat(MobEntity mob, Vec3d awayFrom, long until) {}
	private static final List<Retreat> RETREATING = new ArrayList<>();

	static boolean isAquaticOnly(MobEntity m) {
		return m instanceof net.minecraft.entity.mob.WaterCreatureEntity
			|| m instanceof net.minecraft.entity.mob.GuardianEntity
			|| (m instanceof net.minecraft.entity.passive.AxolotlEntity && m.isTouchingWater());
	}

	final Map<MobEntity, Side> members = new LinkedHashMap<>();
	private final Map<MobEntity, Boolean> wasSilent = new HashMap<>();
	private final Map<MobEntity, Boolean> wasImmuneZombify = new HashMap<>();
	private final Set<MobEntity> lockedControls = new HashSet<>();
	private final Battle battle;
	private final Random rnd = new Random();
	Vec3d center;
	/** Ring radius: the crowd stands on this circle around the stage. */
	double radius = 4.5;
	private boolean fear;
	private boolean stageInWater;
	/** Members already standing on their spot of the ring. */
	final Set<MobEntity> placed = new HashSet<>();
	private int ticks;

	Crowd(Battle battle) { this.battle = battle; }

	static void protectZombify(MobEntity m, Map<MobEntity, Boolean> saved) {
		if (m instanceof net.minecraft.entity.mob.AbstractPiglinEntity ap) {
			saved.putIfAbsent(m, ((dev.rapbattle.mixin.AbstractPiglinAccessor) ap).rapbattle$isImmuneToZombification());
			ap.setImmuneToZombification(true);
		} else if (m instanceof net.minecraft.entity.mob.HoglinEntity hg) {
			saved.putIfAbsent(m, ((dev.rapbattle.mixin.HoglinAccessor) hg).rapbattle$isImmuneToZombification());
			hg.setImmuneToZombification(true);
		}
	}

	static void restoreZombify(MobEntity m, Map<MobEntity, Boolean> saved) {
		Boolean prev = saved.remove(m);
		if (prev == null || !m.isAlive()) return;
		if (m instanceof net.minecraft.entity.mob.AbstractPiglinEntity ap) ap.setImmuneToZombification(prev);
		else if (m instanceof net.minecraft.entity.mob.HoglinEntity hg) hg.setImmuneToZombification(prev);
	}

	private void addMember(MobEntity m, String kin) {
		MobProfiles.Profile p = MobProfiles.of(m.getType());
		Side side;
		if (p.kin().equals(kin)) side = Side.MOB;
		else if (m instanceof VillagerEntity || p.kin().equals("villager") || m instanceof AllayEntity || m instanceof ParrotEntity
			|| m instanceof SnowGolemEntity || (m instanceof TameableEntity t && t.isTamed())) side = Side.PLAYERS;
		else side = Side.NEUTRAL;
		members.put(m, side);
		wasSilent.put(m, m.isSilent());
		m.setSilent(true);
		protectZombify(m, wasImmuneZombify);
	}

	/** Called when the lobby closes. */
	void gather() {
		MobEntity star = battle.mob;
		ServerWorld w = battle.world;
		fear = battle.rank.isBoss();
		var challenger = battle.challenger();
		center = challenger == null ? new Vec3d(star.getX(), star.getY(), star.getZ())
			: new Vec3d((star.getX() + challenger.getX()) / 2, star.getY(), (star.getZ() + challenger.getZ()) / 2);
		String kin = battle.opp.profile().kin();
		stageInWater = w.getFluidState(net.minecraft.util.math.BlockPos.ofFloored(center)).isIn(net.minecraft.registry.tag.FluidTags.WATER);
		List<MobEntity> near = w.getEntitiesByClass(MobEntity.class, star.getBoundingBox().expand(RADIUS),
			m -> m.isAlive() && !battle.mobTeam.contains(m) && BattleManager.byMob(m) == null
				&& !BattleManager.inAnyCrowd(m) && !Opponent.isBoss(m)
				&& (!isAquaticOnly(m) || stageInWater));
		near.sort(Comparator.comparingDouble(m -> m.squaredDistanceTo(star)));
		for (MobEntity m : near) {
			if (members.size() >= MAX) break;
			addMember(m, kin);
		}
		radius = Math.max(4.5, members.size() * 1.7 / (2 * Math.PI));
		react(Moment.START);
	}

	/** Every server tick: a neat ring around the stage, everyone on their own spot, facing the one who reads. */
	void tick(net.minecraft.entity.Entity reader) {
		if (center == null) return;
		ticks++;
		ServerWorld w = battle.world;
		if (ticks % 20 == 0 && !fear) {
			String kin = battle.opp.profile().kin();
			var scanBox = new net.minecraft.util.math.Box(
				center.x - RADIUS, center.y - 8, center.z - RADIUS,
				center.x + RADIUS, center.y + 8, center.z + RADIUS);
			List<MobEntity> late = w.getEntitiesByClass(MobEntity.class, scanBox,
				m -> m.isAlive() && !members.containsKey(m) && !battle.mobTeam.contains(m)
					&& BattleManager.byMob(m) == null && !BattleManager.inAnyCrowd(m) && !Opponent.isBoss(m)
					&& (!isAquaticOnly(m) || stageInWater));
			late.sort(Comparator.comparingDouble(m -> m.squaredDistanceTo(center)));
			boolean added = false;
			long now = dev.rapbattle.Clock.now();
			for (MobEntity m : late) {
				if (members.size() < MAX) {
					addMember(m, kin);
					added = true;
				} else if (m instanceof HostileEntity) {
					holdTruce(m, now + 2000L);
					m.setTarget(null);
					double minR = radius + 1.5;
					if (m.squaredDistanceTo(center.x, m.getY(), center.z) < minR * minR) {
						steerAway(m, center);
					}
				}
			}
			if (added) {
				radius = Math.max(4.5, members.size() * 1.7 / (2 * Math.PI));
			}
		}
		var challenger = battle.challenger();
		// Slots are counted from the challenger side, so the ring looks the same for every battle.
		double base = challenger == null ? 0 : Math.atan2(challenger.getZ() - center.z, challenger.getX() - center.x);
		int i = 0, n = Math.max(1, members.size());
		for (MobEntity m : members.keySet()) {
			int slot = i++;
			if (!m.isAlive()) continue;
			if (m.isOnFire()) m.extinguish();
			if (m instanceof HostileEntity || m.getTarget() != null) m.setTarget(null);
			if (m instanceof net.minecraft.entity.mob.CreeperEntity c) c.setFuseSpeed(-1);
			if (fear) {
				// Peaceful mobs keep their distance from a boss.
				if (m.squaredDistanceTo(center) < 10 * 10) m.getNavigation().startMovingTo(
					m.getX() + (m.getX() - center.x), m.getY(), m.getZ() + (m.getZ() - center.z), 1.2);
				continue;
			}
			double a = base + 2 * Math.PI * (slot + 0.5) / n;
			double tx = center.x + Math.cos(a) * radius, tz = center.z + Math.sin(a) * radius;
			Vec3d spot = ground(w, m, tx, tz);
			if (!placed.contains(m)) {
				double d2 = m.squaredDistanceTo(tx, m.getY(), tz);
				if (spot != null && (d2 < 1.2 * 1.2 || ticks > 100)) {
					// Arrived (or took too long to walk): step onto the exact spot and lock movement goals.
					if (d2 >= 1.2 * 1.2) w.spawnParticles(ParticleTypes.POOF, spot.x, spot.y + 0.3, spot.z, 5, 0.2, 0.2, 0.2, 0.01);
					m.refreshPositionAndAngles(spot.x, spot.y, spot.z, m.getYaw(), m.getPitch());
					m.getNavigation().stop();
					m.setVelocity(0, m.getVelocity().y, 0);
					var gs = ((dev.rapbattle.mixin.MobGoalAccessor) m).rapbattle$goalSelector();
					gs.disableControl(net.minecraft.entity.ai.goal.Goal.Control.MOVE);
					gs.disableControl(net.minecraft.entity.ai.goal.Goal.Control.JUMP);
					lockedControls.add(m);
					placed.add(m);
					ALL_PLACED.add(m);
				} else if (m.getNavigation().isIdle() || ticks % 20 == 0) {
					m.getNavigation().startMovingTo(tx, m.getY(), tz, 1.1);
				}
			} else {
				// Stay smoothly on the spot: movement/jump goals are disabled; teleport only if pushed > 1 block away.
				m.getNavigation().stop();
				m.setForwardSpeed(0);
				m.setSidewaysSpeed(0);
				if (spot != null && m.squaredDistanceTo(spot) > 1.0 * 1.0 && m.isOnGround()) {
					m.setPosition(spot.x, spot.y, spot.z);
				}
				m.setVelocity(0, m.getVelocity().y, 0);
			}
			// Face the stage: the reader if someone is reading, else the centre.
			double lx = reader != null ? reader.getX() : center.x, lz = reader != null ? reader.getZ() : center.z;
			float yaw = (float) (Math.toDegrees(Math.atan2(lz - m.getZ(), lx - m.getX())) - 90);
			if (placed.contains(m)) {
				m.setYaw(yaw);
				m.setBodyYaw(yaw);
			}
			m.setHeadYaw(yaw);
			if (reader != null) m.getLookControl().lookAt(reader, 30f, 30f);
			else m.getLookControl().lookAt(center.x, center.y + 1, center.z);
		}
	}

	/** Standing spot on the ground near (x, z): free space, solid below. */
	private Vec3d ground(ServerWorld w, MobEntity m, double x, double z) {
		boolean needWater = isAquaticOnly(m);
		for (int dy : new int[]{0, 1, -1, 2, -2, 3}) {
			Vec3d at = new Vec3d(x, Math.floor(center.y) + dy, z);
			if (needWater && !w.getFluidState(net.minecraft.util.math.BlockPos.ofFloored(at)).isIn(net.minecraft.registry.tag.FluidTags.WATER)) continue;
			var box = m.getBoundingBox().offset(at.subtract(m.getX(), m.getY(), m.getZ()));
			if (w.isSpaceEmpty(m, box) && !w.isSpaceEmpty(m, box.offset(0, -0.2, 0))) return at;
		}
		return null;
	}

	public static void react(Battle b, Moment moment) {
		if (b.crowd != null) b.crowd.react(moment);
	}

	/** The whole hall's voice for this moment: one real crowd sound, not a pile of animal noises. */
	private void hallSound(Moment moment) {
		ServerWorld w = battle.world;
		Vec3d at = center != null ? center : new Vec3d(battle.mob.getX(), battle.mob.getY(), battle.mob.getZ());
		int n = members.size();
		long mobFans = members.values().stream().filter(s -> s == Side.MOB).count();
		long ourFans = members.values().stream().filter(s -> s == Side.PLAYERS).count();
		switch (moment) {
			case START -> CrowdSounds.play(w, at, rnd.nextBoolean() ? CrowdSounds.HYPE : CrowdSounds.CHEER, n, 0.8f);
			case MOB_PUNCH -> CrowdSounds.play(w, at, CrowdSounds.OOH, n, 1.0f);
			case PLAYER_HIT -> {
				// Not on every line: the hall reacts now and then, louder when it is with you.
				if (rnd.nextInt(100) < 45) CrowdSounds.play(w, at, rnd.nextInt(3) == 0 ? CrowdSounds.WHISTLE : CrowdSounds.CHEER, n, 0.7f);
			}
			case PLAYER_MISS -> {
				if (rnd.nextInt(100) < 55) CrowdSounds.play(w, at, rnd.nextBoolean() ? CrowdSounds.BOO : CrowdSounds.LAUGH, n, 0.75f);
			}
			case JUDGING -> {}
			case WIN_PLAYERS -> {
				CrowdSounds.play(w, at, mobFans > ourFans + 1 ? CrowdSounds.BOO : CrowdSounds.APPLAUSE, n, 1.1f);
				Later.run(500, () -> CrowdSounds.play(w, at, CrowdSounds.WHISTLE, n, 0.7f));
			}
			case WIN_MOB -> {
				CrowdSounds.play(w, at, ourFans > mobFans ? CrowdSounds.BOO : rnd.nextBoolean() ? CrowdSounds.LAUGH : CrowdSounds.CHEER, n, 1.0f);
				if (mobFans > 0) Later.run(600, () -> CrowdSounds.play(w, at, CrowdSounds.CHEER, n, 0.8f));
			}
			case UPSET -> {
				CrowdSounds.play(w, at, CrowdSounds.GASP, n, 1.0f);
				Later.run(700, () -> CrowdSounds.play(w, at, CrowdSounds.APPLAUSE, n, 1.2f));
			}
		}
	}

	private long lastShout;

	/**
	 * Now and then one fan yells a whole phrase: the voice comes from where they stand and the words show over them.
	 * The fan is picked from the side the phrase suits (cheering from your fans, mocking from the mob's).
	 */
	private void shout(Moment moment) {
		java.util.List<String> lines = (dev.rapbattle.Lang.isRu(battle.lang) ? Shouts.LINES : ShoutsEn.LINES).get(moment);
		if (lines == null || members.isEmpty()) return;
		long now = dev.rapbattle.Clock.now();
		boolean big = moment == Moment.START || moment == Moment.WIN_PLAYERS || moment == Moment.WIN_MOB;
		if (now - lastShout < 3500 || rnd.nextInt(100) >= (big ? 80 : 35)) return;
		Side want = switch (moment) {
			case PLAYER_HIT, WIN_PLAYERS -> Side.PLAYERS;
			case MOB_PUNCH, PLAYER_MISS, WIN_MOB -> Side.MOB;
			default -> null;
		};
		java.util.List<MobEntity> pool = new java.util.ArrayList<>();
		for (Map.Entry<MobEntity, Side> e : members.entrySet()) {
			if (e.getKey().isAlive() && (want == null || e.getValue() == want || e.getValue() == Side.NEUTRAL)) pool.add(e.getKey());
		}
		if (pool.isEmpty()) return;
		MobEntity fan = pool.get(rnd.nextInt(pool.size()));
		int i = rnd.nextInt(lines.size());
		lastShout = now;
		Speech.bubble(fan, lines.get(i), want == Side.MOB ? 0xFFB080 : 0xB0FFB0, 2200);
		var s = CrowdSounds.shout(moment, i, battle.lang);
		if (s != null) battle.world.playSound(null, fan.getX(), fan.getY() + fan.getHeight(), fan.getZ(), s,
			net.minecraft.sound.SoundCategory.NEUTRAL, 1.4f, 0.97f + rnd.nextFloat() * 0.06f);
	}

	/**
	 * Individual fans answer the moment, each in their own voice and a little out of sync: your fans cheer, the mob's
	 * fans boo, neutral ones go with the moment. A few at a time, more at the end.
	 */
	private void soloReactions(Moment moment) {
		if (members.isEmpty() || moment == Moment.JUDGING) return;
		List<MobEntity> fans = new ArrayList<>();
		for (MobEntity m : members.keySet()) if (m.isAlive()) fans.add(m);
		Collections.shuffle(fans, rnd);
		boolean big = moment == Moment.START || moment == Moment.WIN_PLAYERS || moment == Moment.WIN_MOB || moment == Moment.UPSET;
		int n = Math.min(fans.size(), big ? Math.max(2, fans.size() * 2 / 3) : Math.max(1, Math.min(4, fans.size() / 3)));
		ServerWorld w = battle.world;
		for (int i = 0; i < n; i++) {
			MobEntity fan = fans.get(i);
			Side side = members.get(fan);
			Boolean cheer = switch (moment) {
				case START, UPSET -> true;
				case PLAYER_HIT, WIN_PLAYERS -> side == Side.MOB ? Boolean.FALSE : Boolean.TRUE;
				case MOB_PUNCH, WIN_MOB -> side == Side.PLAYERS ? Boolean.FALSE : Boolean.TRUE;
				case PLAYER_MISS -> side == Side.PLAYERS ? null : side == Side.MOB ? Boolean.TRUE : Boolean.FALSE;
				default -> null;
			};
			if (cheer == null) continue;
			int voice = Math.floorMod(fan.getId() * 7 + 3, CrowdSounds.VOICES);
			var s = CrowdSounds.solo(cheer, voice);
			boolean c = cheer;
			Later.run(80 + rnd.nextInt(big ? 900 : 450), () -> {
				if (!fan.isAlive()) return;
				w.playSound(null, fan.getX(), fan.getY() + fan.getHeight(), fan.getZ(), s, net.minecraft.sound.SoundCategory.NEUTRAL,
					0.95f, 0.94f + rnd.nextFloat() * 0.14f);
				particles(w, c ? ParticleTypes.HAPPY_VILLAGER : ParticleTypes.ANGRY_VILLAGER,
					fan.getX(), fan.getY() + fan.getHeight() + 0.3, fan.getZ(), c ? 3 : 1);
			});
		}
	}

	void react(Moment moment) {
		hallSound(moment);
		if (!fear) {
			shout(moment);
			soloReactions(moment);
			swaySides(moment);
		}
		ServerWorld w = battle.world;
		for (Map.Entry<MobEntity, Side> e : members.entrySet()) {
			MobEntity m = e.getKey();
			if (!m.isAlive() || rnd.nextInt(100) < 25) continue; // not everybody reacts every time: sounds like a crowd
			Reaction r = reactionOf(e.getValue(), moment);
			if (r == Reaction.NONE) continue;
			double x = m.getX(), y = m.getY() + m.getHeight() + 0.3, z = m.getZ();
			switch (r) {
				case CHEER -> {
					if (m.isOnGround()) m.setVelocity(m.getVelocity().add(0, 0.42, 0));
					m.velocityDirty = true;
					particles(w, ParticleTypes.HAPPY_VILLAGER, x, y, z, 6);
				}
				case WOW -> {
					if (m.isOnGround()) m.setVelocity(m.getVelocity().add(0, 0.25, 0));
					m.velocityDirty = true;
					particles(w, ParticleTypes.CRIT, x, y, z, 8);
				}
				case BOO -> {
					particles(w, ParticleTypes.ANGRY_VILLAGER, x, y, z, 2);
					m.setHeadYaw(m.getHeadYaw() + (rnd.nextBoolean() ? 40 : -40));
				}
				case FEAR -> {
					particles(w, ParticleTypes.SMOKE, x, y - 0.3, z, 4);
				}
				default -> {}
			}
		}
	}

	private enum Reaction { NONE, CHEER, WOW, BOO, FEAR }

	private Reaction reactionOf(Side side, Moment moment) {
		if (fear && side != Side.MOB) return moment == Moment.START || moment == Moment.MOB_PUNCH ? Reaction.FEAR : Reaction.NONE;
		return switch (moment) {
			case START -> Reaction.WOW;
			case MOB_PUNCH -> side == Side.MOB ? Reaction.CHEER : side == Side.NEUTRAL ? Reaction.WOW : Reaction.BOO;
			case PLAYER_HIT -> side == Side.PLAYERS ? Reaction.CHEER : side == Side.NEUTRAL ? Reaction.WOW : Reaction.BOO;
			case PLAYER_MISS -> side == Side.PLAYERS ? Reaction.NONE : Reaction.BOO;
			case JUDGING -> Reaction.NONE;
			case WIN_PLAYERS -> side == Side.MOB ? Reaction.BOO : Reaction.CHEER;
			case WIN_MOB -> side == Side.PLAYERS ? Reaction.BOO : Reaction.CHEER;
			case UPSET -> Reaction.CHEER;
		};
	}

	private void swaySides(Moment moment) {
		if (members.isEmpty()) return;
		ServerWorld w = battle.world;
		if (moment == Moment.PLAYER_HIT && battle.combo >= 3) {
			Side targetSide = battle.combo >= 6 ? Side.MOB : Side.NEUTRAL;
			MobEntity candidate = null;
			for (Map.Entry<MobEntity, Side> e : members.entrySet()) {
				if (e.getKey().isAlive() && e.getValue() == targetSide) {
					candidate = e.getKey();
					break;
				}
			}
			if (candidate == null && battle.combo >= 6) {
				for (Map.Entry<MobEntity, Side> e : members.entrySet()) {
					if (e.getKey().isAlive() && e.getValue() == Side.NEUTRAL) {
						candidate = e.getKey();
						break;
					}
				}
			}
			if (candidate != null) {
				members.put(candidate, Side.PLAYERS);
				Speech.bubble(candidate, Lang.t(battle.lang, "rapbattle.crowd.defected_players"), 0x7CF0A0, 2200);
				particles(w, ParticleTypes.HAPPY_VILLAGER, candidate.getX(), candidate.getY() + candidate.getHeight() + 0.3, candidate.getZ(), 8);
			}
		} else if (moment == Moment.PLAYER_MISS) {
			for (Map.Entry<MobEntity, Side> e : members.entrySet()) {
				if (e.getKey().isAlive() && e.getValue() == Side.NEUTRAL) {
					members.put(e.getKey(), Side.MOB);
					Speech.bubble(e.getKey(), Lang.t(battle.lang, "rapbattle.crowd.defected_mob"), 0xFF8080, 2000);
					particles(w, ParticleTypes.ANGRY_VILLAGER, e.getKey().getX(), e.getKey().getY() + e.getKey().getHeight() + 0.3, e.getKey().getZ(), 4);
					break;
				}
			}
		}
	}

	/** After a player victory, a friendly fan may toss a small gift (emerald, apple, or glowstone dust). */
	void dropGift(net.minecraft.server.network.ServerPlayerEntity winner) {
		if (winner == null || members.isEmpty() || rnd.nextInt(100) >= 45) return;
		MobEntity fan = null;
		for (Map.Entry<MobEntity, Side> e : members.entrySet()) {
			if (e.getKey().isAlive() && e.getValue() == Side.PLAYERS) {
				fan = e.getKey();
				break;
			}
		}
		if (fan == null) return;
		net.minecraft.item.Item[] pool = {net.minecraft.item.Items.EMERALD, net.minecraft.item.Items.APPLE, net.minecraft.item.Items.GLOWSTONE_DUST};
		net.minecraft.item.ItemStack gift = new net.minecraft.item.ItemStack(pool[rnd.nextInt(pool.length)], 1);
		net.minecraft.entity.ItemEntity drop = new net.minecraft.entity.ItemEntity(
			battle.world, winner.getX(), winner.getY() + 0.5, winner.getZ(), gift);
		drop.setToDefaultPickupDelay();
		battle.world.spawnEntity(drop);
		Speech.bubble(fan, Lang.t(battle.lang, "rapbattle.crowd.gift_bubble"), 0xFFE84A, 2200);
		winner.sendMessage(net.minecraft.text.Text.translatable("rapbattle.crowd.gift_chat", gift.getName())
			.formatted(net.minecraft.util.Formatting.GREEN), false);
	}

	private static void particles(ServerWorld w, ParticleEffect type, double x, double y, double z, int count) {
		w.spawnParticles(type, x, y, z, count, 0.3, 0.2, 0.3, 0.02);
	}

	/** Battle over: keep the truce 5 more seconds, restore AI controls & zombification, then let everyone go. */
	void release() {
		release(false);
	}

	void release(boolean playersWin) {
		long until = dev.rapbattle.Clock.now() + 5000;
		for (MobEntity m : members.keySet()) {
			m.getNavigation().stop();
			if (lockedControls.remove(m)) {
				var gs = ((dev.rapbattle.mixin.MobGoalAccessor) m).rapbattle$goalSelector();
				gs.enableControl(net.minecraft.entity.ai.goal.Goal.Control.MOVE);
				gs.enableControl(net.minecraft.entity.ai.goal.Goal.Control.JUMP);
			}
			restoreZombify(m, wasImmuneZombify);
			if (m.isAlive()) m.setSilent(wasSilent.getOrDefault(m, false));
			if (m instanceof HostileEntity) holdTruce(m, until);
			if (playersWin && m.isAlive() && center != null && !fear) {
				double dx = m.getX() - center.x, dz = m.getZ() - center.z;
				double len = Math.hypot(dx, dz);
				if (len < 1e-3) { dx = 1; dz = 0; } else { dx /= len; dz /= len; }
				m.getNavigation().startMovingTo(m.getX() + dx * 6, m.getY(), m.getZ() + dz * 6, 0.85);
			}
		}
		lockedControls.clear();
		wasImmuneZombify.clear();
		for (MobEntity m : placed) ALL_PLACED.remove(m);
		placed.clear();
	}

	/** Escaped mob team: 15 s truce and actively walking away from {@code awayFrom}. */
	public static void retreat(Collection<MobEntity> mobs, Vec3d awayFrom, long durationMs) {
		long until = dev.rapbattle.Clock.now() + durationMs;
		for (MobEntity m : mobs) {
			if (m == null || !m.isAlive()) continue;
			ALL_PLACED.remove(m);
			m.setTarget(null);
			m.setAttacker(null);
			if (m instanceof net.minecraft.entity.mob.Angerable a) a.stopAnger();
			holdTruce(m, until);
			RETREATING.add(new Retreat(m, awayFrom, until));
			steerAway(m, awayFrom);
		}
	}

	private static void steerAway(MobEntity m, Vec3d from) {
		if (from == null) return;
		double dx = m.getX() - from.x, dz = m.getZ() - from.z;
		double len = Math.hypot(dx, dz);
		if (len < 1e-3) {
			dx = 1;
			dz = 0;
		} else {
			dx /= len;
			dz /= len;
		}
		m.getNavigation().startMovingTo(m.getX() + dx * 12, m.getY(), m.getZ() + dz * 12, 1.2);
	}

	public static boolean isPlaced(MobEntity m) {
		return m != null && !ALL_PLACED.isEmpty() && ALL_PLACED.contains(m);
	}

	public static boolean inTruce(MobEntity m) {
		if (TRUCE.isEmpty() || m == null) return false;
		Long until = TRUCE.get(m);
		return until != null && dev.rapbattle.Clock.now() <= until;
	}

	public static boolean inTruce(MobEntity m, UUID playerId) {
		if (!inTruce(m)) return false;
		if (playerId != null) {
			Set<UUID> ex = TRUCE_EXCEPTIONS.get(m);
			if (ex != null && ex.contains(playerId)) return false;
		}
		return true;
	}

	public static void breakTruceFor(MobEntity m, UUID playerId) {
		if (m != null && playerId != null) {
			TRUCE_EXCEPTIONS.computeIfAbsent(m, k -> new HashSet<>()).add(playerId);
		}
	}

	/** Keeps a mob in truce until {@code untilMs} (e.g. during a PvP battle near the stage). */
	public static void holdTruce(MobEntity m, long untilMs) {
		if (m == null || !m.isAlive()) return;
		Long prev = TRUCE.get(m);
		if (prev == null || prev < untilMs) TRUCE.put(m, untilMs);
	}

	/** Server tick: hostile crowd and escaped mobs keep their hands off until the truce ends. */
	static void tickTruce() {
		long now = dev.rapbattle.Clock.now();
		TRUCE.entrySet().removeIf(e -> {
			boolean drop = now > e.getValue() || !e.getKey().isAlive();
			if (drop) TRUCE_EXCEPTIONS.remove(e.getKey());
			return drop;
		});
		for (MobEntity m : TRUCE.keySet()) {
			net.minecraft.entity.LivingEntity cur = m.getTarget();
			if (cur instanceof net.minecraft.entity.player.PlayerEntity p && !inTruce(m, p.getUuid())) continue;
			m.setTarget(null);
			if (m instanceof net.minecraft.entity.mob.Angerable a) a.stopAnger();
			m.getBrain().forget(net.minecraft.entity.ai.brain.MemoryModuleType.ATTACK_TARGET);
			m.getBrain().forget(net.minecraft.entity.ai.brain.MemoryModuleType.ANGRY_AT);
			if (m.isOnFire()) m.extinguish();
		}
		RETREATING.removeIf(r -> now > r.until() || !r.mob().isAlive());
		for (Retreat r : RETREATING) {
			MobEntity m = r.mob();
			if (m.getNavigation().isIdle()) steerAway(m, r.awayFrom());
		}
	}

	static void clear() {
		TRUCE.clear();
		TRUCE_EXCEPTIONS.clear();
		RETREATING.clear();
		ALL_PLACED.clear();
	}

	Side sideOf(MobEntity m) { return members.getOrDefault(m, null); }

	/** Crowd members of the given side, strongest first (used when a fight breaks out). */
	List<MobEntity> fighters(Side side) {
		List<MobEntity> out = new ArrayList<>();
		for (Map.Entry<MobEntity, Side> e : members.entrySet()) {
			if (e.getValue() == side && e.getKey().isAlive() && (e.getKey() instanceof HostileEntity
				|| e.getKey() instanceof net.minecraft.entity.passive.IronGolemEntity
				|| e.getKey() instanceof net.minecraft.entity.mob.Angerable)) out.add(e.getKey());
		}
		out.sort(Comparator.comparingDouble(m -> -m.getMaxHealth()));
		return out;
	}
}
