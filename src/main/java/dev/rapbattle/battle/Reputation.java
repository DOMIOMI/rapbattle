package dev.rapbattle.battle;

import dev.rapbattle.Lang;
import net.minecraft.entity.Entity;
import net.minecraft.entity.mob.*;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.raid.RaiderEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.world.World;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Post-battle reputation:
 * - "Street Cred" (respect by rank for 3 minutes, witnesses, head nods & bubbles)
 * - "Run!" (10s truce + 4s mocking laugh after a loss)
 * - Two-tier Piglin respect (Piglin Pal 5m / Bastion Guest 10m)
 */
public final class Reputation {
	private Reputation() {}

	public static final long STREET_CRED_MS = 3 * 60_000L;
	public static final long OVERDRESSED_CRED_MS = 90_000L;
	public static final long RUN_TRUCE_MS = 10_000L;
	public static final long RUN_LAUGH_MS = 4_000L;
	public static final long PIGLIN_PAL_MS = 5 * 60_000L;
	public static final long BASTION_GUEST_MS = 10 * 60_000L;

	public static final double NOD_RADIUS = 6.0;
	public static final long NOD_DURATION_MS = 800L;
	public static final long BUBBLE_PLAYER_COOLDOWN_MS = 12_000L;
	public static final long BUBBLE_MOB_COOLDOWN_MS = 120_000L;

	public record CredState(int level, long until) {}
	private record NodState(float prevPitch, long until) {}
	private record RunLaugh(List<MobEntity> mobs, UUID targetPlayer, long until, long nextJumpAt) {}

	private static final Map<UUID, CredState> CRED = new ConcurrentHashMap<>();
	private static final Map<UUID, Map<UUID, Long>> WITNESSES = new ConcurrentHashMap<>();
	private static final Map<UUID, Long> PLAYER_BUBBLE_CD = new ConcurrentHashMap<>();
	private static final Map<UUID, Long> MOB_BUBBLE_CD = new ConcurrentHashMap<>();
	private static final Map<MobEntity, NodState> ACTIVE_NODS = new IdentityHashMap<>();
	private static final List<RunLaugh> LAUGHS = new ArrayList<>();

	private static final List<String> CRED_LINES_RU = List.of(
		"Респект…", "Это тот самый МС", "Не трогай его", "Уважаю"
	);
	private static final List<String> CRED_LINES_EN = List.of(
		"Respect…", "That's the MC", "Leave him be", "Big respect"
	);
	private static final List<String> LAUGH_LINES_RU = List.of(
		"Ха-ха!", "Зассал?", "Беги, пока можешь", "Иди потренируйся"
	);
	private static final List<String> LAUGH_LINES_EN = List.of(
		"Ha-ha!", "Scared?", "Run while you can", "Go practice"
	);

	public static void clear() {
		CRED.clear();
		WITNESSES.clear();
		PLAYER_BUBBLE_CD.clear();
		MOB_BUBBLE_CD.clear();
		ACTIVE_NODS.clear();
		LAUGHS.clear();
	}

	/** Pure duration calculation for unit tests. */
	public static long computeCredDurationMs(boolean overdressed, boolean isMvp, int dealt) {
		long base = overdressed ? OVERDRESSED_CRED_MS : STREET_CRED_MS;
		if (isMvp) return base;
		if (dealt > 0) return base / 2;
		return 0L;
	}

	/** Pure merge calculation for unit tests (capped at STREET_CRED_MS from now, level = max). */
	public static CredState mergeCred(CredState current, int newRankLevel, long durationMs, long now) {
		if (durationMs <= 0 || newRankLevel <= 0) return current;
		int prevLevel = (current != null && now < current.until()) ? current.level() : 0;
		int level = Math.max(prevLevel, newRankLevel);
		long prevUntil = (current != null && now < current.until()) ? current.until() : 0L;
		long until = Math.min(now + STREET_CRED_MS, Math.max(prevUntil, now + durationMs));
		return new CredState(level, until);
	}

	public static int credLevel(UUID player) {
		CredState st = CRED.get(player);
		if (st == null || dev.rapbattle.Clock.now() >= st.until()) return 0;
		return st.level();
	}

	public static void revokeCred(UUID player) {
		CRED.remove(player);
		WITNESSES.values().forEach(map -> map.remove(player));
		PlayerStatus.revoke(player, PlayerStatus.STREET_CRED);
	}

	/** Test-only helper returning total tracked entries across Reputation maps. */
	public static int activeMapEntriesForTest() {
		return CRED.size() + WITNESSES.size() + PLAYER_BUBBLE_CD.size() + MOB_BUBBLE_CD.size();
	}

	static void grantOnWin(Battle battle, ServerPlayerEntity mvpPlayer) {
		grantOnWin(battle, mvpPlayer, battle == null ? 1.0 : battle.dailyBonusScale);
	}

	static void grantOnWin(Battle battle, ServerPlayerEntity mvpPlayer, double effectiveDailyScale) {
		if (battle == null || battle.boss) return;
		MobEntity mob = battle.mob;
		long now = dev.rapbattle.Clock.now();

		// Piglin two-tier respect (gated if act bonuses are closed in soft mode or daily farm cap hit)
		UUID mvpId = mvpPlayer != null ? mvpPlayer.getUuid() : null;
		if (battle.actBonusesAllowed && effectiveDailyScale > 0.0) {
			if (mob instanceof PiglinEntity || mob instanceof PiglinBruteEntity) {
				boolean isBrute = mob instanceof PiglinBruteEntity;
				String statusKey = isBrute ? PlayerStatus.BASTION_GUEST : PlayerStatus.PIGLIN_PAL;
				long baseDur = isBrute ? BASTION_GUEST_MS : PIGLIN_PAL_MS;
				int activeCount = battle.activePlayers().size();
				for (UUID u : battle.players) {
					if (battle.leftPlayers.contains(u)) continue;
					String name = battle.names.get(u);
					int dealt = name != null ? battle.battleDealt.getOrDefault(name, 0) : 0;
					boolean isMvp = u.equals(mvpId) || activeCount == 1;
					if (!isMvp && dealt <= 0) continue;
					long dur = isMvp ? baseDur : baseDur / 2;
					PlayerStatus.grant(u, statusKey, dur);
				}
			}
		}

		// Street Cred applies only for hostile/grudge-holding mobs (never iron golems)
		if (!(mob instanceof HostileEntity) && !(mob instanceof Angerable)) return;
		if (mob instanceof net.minecraft.entity.passive.IronGolemEntity) return;

		int rankLevel = battle.rank.level();
		List<UUID> creditedPlayers = new ArrayList<>();

		int activeCount = battle.activePlayers().size();
		for (UUID u : battle.players) {
			if (battle.leftPlayers.contains(u)) continue;
			String name = battle.names.get(u);
			int dealt = name != null ? battle.battleDealt.getOrDefault(name, 0) : 0;
			boolean isMvp = u.equals(mvpId) || activeCount == 1;
			long dur = computeCredDurationMs(battle.overdressed, isMvp, dealt);
			if (dur <= 0) continue;
			CredState next = mergeCred(CRED.get(u), rankLevel, dur, now);
			if (next != null) {
				CRED.put(u, next);
				PlayerStatus.grant(u, PlayerStatus.STREET_CRED, next.until() - now);
				creditedPlayers.add(u);
			}
		}

		// Crowd witnesses respect the credited players for 3 minutes regardless of witness rank.
		if (battle.crowd != null && !creditedPlayers.isEmpty()) {
			long witnessUntil = now + STREET_CRED_MS;
			for (MobEntity wMob : battle.crowd.members.keySet()) {
				if (!wMob.isAlive() || Opponent.isBoss(wMob)) continue;
				Map<UUID, Long> map = WITNESSES.computeIfAbsent(wMob.getUuid(), k -> new ConcurrentHashMap<>());
				for (UUID u : creditedPlayers) {
					map.put(u, witnessUntil);
				}
			}
		}
	}

	static void onLoss(Battle battle) {
		if (battle == null) return;
		long now = dev.rapbattle.Clock.now();
		long truceUntil = now + RUN_TRUCE_MS;

		for (UUID u : battle.players) {
			if (battle.leftPlayers.contains(u)) continue;
			revokeCred(u);
			PlayerStatus.grant(u, PlayerStatus.RUN_AWAY, RUN_TRUCE_MS);
		}

		List<MobEntity> laughers = new ArrayList<>();
		for (MobEntity m : battle.mobTeam) {
			if (m.isAlive()) {
				Crowd.holdTruce(m, truceUntil);
				m.setTarget(null);
				if (m instanceof Angerable a) a.stopAnger();
				laughers.add(m);
			}
		}
		if (battle.crowd != null) {
			for (MobEntity m : battle.crowd.members.keySet()) {
				if (m.isAlive()) {
					Crowd.holdTruce(m, truceUntil);
					m.setTarget(null);
					laughers.add(m);
				}
			}
		}
		var box = battle.mob.getBoundingBox().expand(16);
		for (MobEntity m : battle.world.getEntitiesByClass(MobEntity.class, box,
			e -> e.isAlive() && !Opponent.isBoss(e) && e instanceof HostileEntity)) {
			Crowd.holdTruce(m, truceUntil);
			m.setTarget(null);
		}

		ServerPlayerEntity target = battle.challenger();
		if (!laughers.isEmpty() && target != null) {
			LAUGHS.add(new RunLaugh(laughers, target.getUuid(), now + RUN_LAUGH_MS, now));
			List<String> pool = Lang.isRu(battle.lang) ? LAUGH_LINES_RU : LAUGH_LINES_EN;
			String line = pool.get(new Random().nextInt(pool.size()));
			Speech.bubble(battle.mob, line, 0xFF7070, 3500);
			battle.world.playSound(null, battle.mob.getX(), battle.mob.getY() + battle.mob.getHeight(), battle.mob.getZ(),
				CrowdSounds.LAUGH, SoundCategory.NEUTRAL, 1.3f, 1.0f);
		}
	}

	public static boolean isNeverRespecting(MobEntity m) {
		if (m == null) return true;
		if (Opponent.isBoss(m)) return true;
		if (MobRecord.hasWins(m)) return true;
		if (SoreLosers.isRaged(m)) return true;
		if (m instanceof RaiderEntity r) {
			var raid = r.getRaid();
			if (raid != null && !raid.isFinished() && !raid.hasStopped() && !raid.hasWon()) return true;
		}
		return false;
	}

	public static boolean respects(MobEntity m, PlayerEntity p) {
		if (m == null || p == null || isNeverRespecting(m)) return false;
		long now = dev.rapbattle.Clock.now();
		Map<UUID, Long> wm = WITNESSES.get(m.getUuid());
		if (wm != null) {
			Long wu = wm.get(p.getUuid());
			if (wu != null && now < wu) return true;
		}
		CredState st = CRED.get(p.getUuid());
		if (st == null || now >= st.until()) return false;
		int mobRank = MobProfiles.of(m.getType()).baseRank();
		return mobRank <= st.level();
	}

	public static void onPlayerHostileAction(ServerPlayerEntity p, MobEntity mob) {
		if (p == null || mob == null) return;
		UUID u = p.getUuid();
		if (mob instanceof PiglinEntity || mob instanceof PiglinBruteEntity) {
			PlayerStatus.revoke(u, PlayerStatus.PIGLIN_PAL);
			PlayerStatus.revoke(u, PlayerStatus.BASTION_GUEST);
		}
		if (!(mob instanceof HostileEntity) && !(mob instanceof Angerable)) return;
		if (SoreLosers.isRaged(mob)) return;
		for (Map<UUID, Long> wm : WITNESSES.values()) {
			wm.remove(u);
		}
		CredState st = CRED.get(u);
		if (st != null && dev.rapbattle.Clock.now() < st.until()) {
			revokeCred(u);
			p.sendMessage(Text.translatable("rapbattle.reputation.lost_brawl").formatted(Formatting.RED), false);
		}
	}

	static void tick(MinecraftServer server) {
		long now = dev.rapbattle.Clock.now();
		// 1. Run! laugh & hops for the first 4 seconds
		Iterator<RunLaugh> it = LAUGHS.iterator();
		while (it.hasNext()) {
			RunLaugh rl = it.next();
			if (now >= rl.until()) {
				it.remove();
				continue;
			}
			ServerPlayerEntity p = server.getPlayerManager().getPlayer(rl.targetPlayer());
			boolean hop = now >= rl.nextJumpAt();
			for (MobEntity m : rl.mobs()) {
				if (!m.isAlive()) continue;
				if (p != null) m.getLookControl().lookAt(p, 30f, 30f);
				if (hop && m.isOnGround()) {
					m.setVelocity(m.getVelocity().x, 0.36, m.getVelocity().z);
					m.velocityDirty = true;
				}
			}
			if (hop) {
				LAUGHS.set(LAUGHS.indexOf(rl), new RunLaugh(rl.mobs(), rl.targetPlayer(), rl.until(), now + 900L));
			}
		}

		// Restore finished head nods
		Iterator<Map.Entry<MobEntity, NodState>> nodIt = ACTIVE_NODS.entrySet().iterator();
		while (nodIt.hasNext()) {
			var e = nodIt.next();
			if (now >= e.getValue().until() || !e.getKey().isAlive()) {
				if (e.getKey().isAlive()) e.getKey().setPitch(e.getValue().prevPitch());
				nodIt.remove();
			}
		}

		if (server.getTicks() % 10 != 0) return;
		CRED.entrySet().removeIf(e -> now >= e.getValue().until());
		PLAYER_BUBBLE_CD.entrySet().removeIf(e -> now >= e.getValue());
		MOB_BUBBLE_CD.entrySet().removeIf(e -> now >= e.getValue());
		WITNESSES.entrySet().removeIf(e -> {
			e.getValue().entrySet().removeIf(pe -> now >= pe.getValue());
			if (e.getValue().isEmpty()) return true;
			Entity ent = null;
			for (ServerWorld sw : server.getWorlds()) {
				ent = sw.getEntity(e.getKey());
				if (ent != null) break;
			}
			return !(ent instanceof MobEntity m) || !m.isAlive();
		});

		// 2. Respecting mobs within 6 blocks turn & nod (no movement!) + rare bubble
		Random rnd = new Random();
		Stats stats = Stats.of(server);
		for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
			if (p.isSpectator() || !p.isAlive()) continue;
			if (BattleManager.byPlayer(p.getUuid()) != null || PvpBattle.byPlayer(p.getUuid()) != null) continue;
			boolean postDragonEnderman = stats.player(p.getUuid()).dragonWon && Trophies.isTrophyMic(p);
			CredState st = CRED.get(p.getUuid());
			if (st == null && !postDragonEnderman && !WITNESSES.values().stream().anyMatch(m -> m.getOrDefault(p.getUuid(), 0L) > now)) continue;
			ServerWorld w = p.getEntityWorld();
			List<MobEntity> near = w.getEntitiesByClass(MobEntity.class, p.getBoundingBox().expand(NOD_RADIUS),
				m -> m.isAlive() && BattleManager.byMob(m) == null && !BattleManager.inAnyCrowd(m)
					&& (respects(m, p) || (postDragonEnderman && m instanceof EndermanEntity && m.getTarget() == null)));
			for (MobEntity m : near) {
				m.getLookControl().lookAt(p, 30f, 30f);
				if (!ACTIVE_NODS.containsKey(m)) {
					ACTIVE_NODS.put(m, new NodState(m.getPitch(), now + NOD_DURATION_MS));
					m.setPitch(25f);
				}
				long pCd = PLAYER_BUBBLE_CD.getOrDefault(p.getUuid(), 0L);
				long mCd = MOB_BUBBLE_CD.getOrDefault(m.getUuid(), 0L);
				if (now >= pCd && now >= mCd && rnd.nextInt(100) < 25) {
					PLAYER_BUBBLE_CD.put(p.getUuid(), now + BUBBLE_PLAYER_COOLDOWN_MS);
					MOB_BUBBLE_CD.put(m.getUuid(), now + BUBBLE_MOB_COOLDOWN_MS);
					if (postDragonEnderman && m instanceof EndermanEntity && !respects(m, p)) {
						Speech.bubble(m, Lang.t(Lang.of(p), "rapbattle.progression.enderman_gaze"), 0xC89BFF, 2400);
					} else {
						List<String> pool = Lang.isRu(Lang.of(p)) ? CRED_LINES_RU : CRED_LINES_EN;
						Speech.bubble(m, pool.get(rnd.nextInt(pool.size())), 0x80FFB0, 2200);
					}
				}
			}
		}
	}
}
