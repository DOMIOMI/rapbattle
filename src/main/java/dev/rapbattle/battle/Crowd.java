package dev.rapbattle.battle;

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
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
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
	private static final List<Map.Entry<MobEntity, Long>> TRUCE = new ArrayList<>();

	final Map<MobEntity, Side> members = new LinkedHashMap<>();
	private final Map<MobEntity, Boolean> wasSilent = new HashMap<>();
	private final Battle battle;
	private final Random rnd = new Random();
	Vec3d center;
	/** Ring radius: the crowd stands on this circle around the stage. */
	double radius = 4.5;
	private boolean fear;
	/** Members already standing on their spot of the ring. */
	private final Set<MobEntity> placed = new HashSet<>();
	private int ticks;

	Crowd(Battle battle) { this.battle = battle; }

	/** Called when the lobby closes. */
	void gather() {
		MobEntity star = battle.mob;
		ServerWorld w = battle.world;
		fear = battle.rank.isBoss();
		var challenger = battle.challenger();
		center = challenger == null ? new Vec3d(star.getX(), star.getY(), star.getZ())
			: new Vec3d((star.getX() + challenger.getX()) / 2, star.getY(), (star.getZ() + challenger.getZ()) / 2);
		String kin = battle.opp.profile().kin();
		List<MobEntity> near = w.getEntitiesByClass(MobEntity.class, star.getBoundingBox().expand(RADIUS),
			m -> m.isAlive() && !battle.mobTeam.contains(m) && BattleManager.byMob(m) == null && !Opponent.isBoss(m));
		near.sort(Comparator.comparingDouble(m -> m.squaredDistanceTo(star)));
		for (MobEntity m : near) {
			if (members.size() >= MAX) break;
			MobProfiles.Profile p = MobProfiles.of(m.getType());
			Side side;
			if (p.kin().equals(kin)) side = Side.MOB;
			else if (m instanceof VillagerEntity || p.kin().equals("villager") || m instanceof AllayEntity || m instanceof ParrotEntity
				|| m instanceof SnowGolemEntity || (m instanceof TameableEntity t && t.isTamed())) side = Side.PLAYERS;
			else side = Side.NEUTRAL;
			members.put(m, side);
			wasSilent.put(m, m.isSilent());
			m.setSilent(true);
		}
		radius = Math.max(4.5, members.size() * 1.7 / (2 * Math.PI));
		react(Moment.START);
	}

	/** Every server tick: a neat ring around the stage, everyone on their own spot, facing the one who reads. */
	void tick(net.minecraft.entity.Entity reader) {
		if (center == null) return;
		ticks++;
		ServerWorld w = battle.world;
		var challenger = battle.challenger();
		// Slots are counted from the challenger side, so the ring looks the same for every battle.
		double base = challenger == null ? 0 : Math.atan2(challenger.getZ() - center.z, challenger.getX() - center.x);
		int i = 0, n = Math.max(1, members.size());
		for (MobEntity m : members.keySet()) {
			int slot = i++;
			if (!m.isAlive()) continue;
			if (m instanceof HostileEntity || m.getTarget() != null) m.setTarget(null);
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
					// Arrived (or took too long to walk): step onto the exact spot.
					if (d2 >= 1.2 * 1.2) w.spawnParticles(ParticleTypes.POOF, spot.x, spot.y + 0.3, spot.z, 5, 0.2, 0.2, 0.2, 0.01);
					m.refreshPositionAndAngles(spot.x, spot.y, spot.z, m.getYaw(), m.getPitch());
					placed.add(m);
				} else if (m.getNavigation().isIdle() || ticks % 20 == 0) {
					m.getNavigation().startMovingTo(tx, m.getY(), tz, 1.1);
				}
			} else {
				// Stay on the spot: no wandering off, no grazing walks.
				m.getNavigation().stop();
				if (spot != null && m.squaredDistanceTo(spot) > 0.2 * 0.2 && m.isOnGround()) m.setPosition(spot.x, spot.y, spot.z);
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
		for (int dy : new int[]{0, 1, -1, 2, -2, 3}) {
			Vec3d at = new Vec3d(x, Math.floor(center.y) + dy, z);
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
		java.util.List<String> lines = Shouts.LINES.get(moment);
		if (lines == null || members.isEmpty()) return;
		long now = System.currentTimeMillis();
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
		var s = CrowdSounds.shout(moment, i);
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
					voice(m, true);
				}
				case WOW -> {
					if (m.isOnGround()) m.setVelocity(m.getVelocity().add(0, 0.25, 0));
					m.velocityDirty = true;
					particles(w, ParticleTypes.CRIT, x, y, z, 8);
					voice(m, true);
				}
				case BOO -> {
					particles(w, ParticleTypes.ANGRY_VILLAGER, x, y, z, 2);
					m.setHeadYaw(m.getHeadYaw() + (rnd.nextBoolean() ? 40 : -40));
					voice(m, false);
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

	private void voice(MobEntity m, boolean happy) {
		// Animals stay silent during a battle: the hall sound carries the reaction.
		if (true) return;
		ServerWorld w = battle.world;
		float pitch = (happy ? 1.15f : 0.75f) + rnd.nextFloat() * 0.3f;
		if (m instanceof VillagerEntity) {
			w.playSound(null, m.getBlockPos(), happy ? SoundEvents.ENTITY_VILLAGER_YES : SoundEvents.ENTITY_VILLAGER_NO,
				SoundCategory.NEUTRAL, 0.9f, pitch);
		} else {
			m.playAmbientSound();
		}
	}

	private static void particles(ServerWorld w, ParticleEffect type, double x, double y, double z, int count) {
		w.spawnParticles(type, x, y, z, count, 0.3, 0.2, 0.3, 0.02);
	}

	/** Fans on the players' side cheering more than the rest: the crowd prize, 0..2 degrees. */
	int prize() {
		if (members.isEmpty()) return 0;
		int hit = battle.barsHit, miss = battle.barsMissed;
		if (hit == 0) return 0;
		return hit >= 2 * miss + 2 ? 2 : hit > miss ? 1 : 0;
	}

	/** Battle over: keep the truce 5 more seconds, then let everyone go. */
	void release() {
		long until = System.currentTimeMillis() + 5000;
		for (MobEntity m : members.keySet()) {
			m.getNavigation().stop();
			if (m.isAlive()) m.setSilent(wasSilent.getOrDefault(m, false));
			if (m instanceof HostileEntity) TRUCE.add(Map.entry(m, until));
		}
	}

	/** Server tick: hostile crowd keeps its hands off until the truce ends. */
	static void tickTruce() {
		long now = System.currentTimeMillis();
		TRUCE.removeIf(e -> now > e.getValue() || !e.getKey().isAlive());
		for (Map.Entry<MobEntity, Long> e : TRUCE) e.getKey().setTarget(null);
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
