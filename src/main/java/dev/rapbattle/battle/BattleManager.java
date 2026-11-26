package dev.rapbattle.battle;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.rapbattle.Lang;
import dev.rapbattle.RapBattle;
import dev.rapbattle.RapConfig;
import dev.rapbattle.net.BattleView;
import dev.rapbattle.net.Payloads;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.Entity;
import net.minecraft.entity.boss.dragon.EnderDragonPart;
import net.minecraft.entity.mob.Angerable;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.passive.TameableEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;

import java.util.*;

/** Starts battles (rap-or-fight choice, dress code), routes packets, ticks battles, keeps participants safe. */
public final class BattleManager {
	private BattleManager() {}

	private static final List<Battle> BATTLES = new ArrayList<>();
	/** Player → the mob they hit with an empty hand and must now choose about. */
	private static final Map<UUID, Pending> PENDING = new HashMap<>();
	private static final long CHOICE_MS = 4000;

	private record Pending(MobEntity mob, long until, boolean allowed, boolean golem, boolean fromApproach,
						   ProgressionRules.GateCheck gate) {}

	/** Recently finished battles: if a fight breaks out right after, the crowd keeps it fair. */
	private record Stage(MobEntity mob, Crowd crowd, long until, Set<UUID> attackers, Set<MobEntity> engaged) {}

	private static final List<Stage> RECENT = new ArrayList<>();

	static void recent(Battle b) {
		if (b.crowd != null) RECENT.add(new Stage(b.mob, b.crowd, System.currentTimeMillis() + 30_000, new HashSet<>(), new HashSet<>()));
	}

	/** Someone hit the mob of a finished battle. 1 on 1 the crowd watches; 2 on 1 its fans even the odds. */
	private static void fightAfterBattle(ServerPlayerEntity p, MobEntity mob) {
		long now = System.currentTimeMillis();
		RECENT.removeIf(s -> now > s.until() || !s.mob().isAlive());
		for (Stage s : RECENT) {
			if (s.mob() != mob) continue;
			s.attackers().add(p.getUuid());
			int needed = s.attackers().size() - 1;
			if (needed <= 0) return;
			List<UUID> attackers = new ArrayList<>(s.attackers());
			for (MobEntity fan : s.crowd().fighters(Crowd.Side.MOB)) {
				if (s.engaged().size() >= needed) break;
				if (s.engaged().contains(fan)) continue;
				ServerPlayerEntity target = p.getEntityWorld().getServer().getPlayerManager()
					.getPlayer(attackers.get(1 + s.engaged().size() % Math.max(1, attackers.size() - 1)));
				if (target == null) continue;
				fan.setTarget(target);
				s.engaged().add(fan);
				Speech.bubble(fan, Lang.t(Lang.of(target), "rapbattle.manager.two_on_one"), 0xFF8080, 2500);
			}
		}
	}

	/** An auto-challenge from a mob (golem, champion, rematch, raid captain, brute): answer with R, or it's a fight. */
	static void challengeFrom(ServerPlayerEntity p, MobEntity mob) {
		challengeFrom(p, mob, "rapbattle.manager.golem_challenge");
	}

	static void challengeFrom(ServerPlayerEntity p, MobEntity mob, String titleKey) {
		String lang = Lang.of(p);
		Progression.StartCheck sc = Progression.canStart(p, mob, false, 0, new Random());
		Opponent o = sc.opp();
		Rank displayRank = sc.ranks().battleRank();
		boolean isGolem = mob instanceof net.minecraft.entity.passive.IronGolemEntity;
		PENDING.put(p.getUuid(), new Pending(mob, System.currentTimeMillis() + 15_000, true, isGolem, !isGolem, sc.gate()));
		if (!isGolem) {
			mob.setTarget(null);
			mob.getNavigation().stop();
			stareAt(mob, p, 15_000);
		}
		BattleView v = new BattleView();
		v.type = "choice";
		v.mobEntityId = mob.getId();
		v.mobName = o.name();
		v.mobSpecies = o.profile().species(lang);
		v.rankArmor = displayRank.armor(lang);
		v.rankStreet = displayRank.street(lang);
		v.rankLevel = displayRank.level();
		v.rankColor = displayRank.color;
		v.allowed = true;
		v.challengeFrom = Lang.t(lang, titleKey);
		v.choiceMs = 15_000;
		if (!sc.gate().bonusesAllowed() && !sc.gate().messageKey().isEmpty()) {
			v.gateWarning = Lang.t(lang, sc.gate().messageKey(), sc.gate().args());
		}
		ServerPlayNetworking.send(p, new Payloads.StateS2C(v.toJson()));
	}

	private static final List<String> REFUSALS = List.of(
		"Ты в этом пришёл? Иди переоденься.",
		"С таким прикидом я не баттлюсь.",
		"Сначала оденься, потом микрофон проси.",
		"Ты одет как блок земли.");
	private static final List<String> REFUSALS_EN = List.of(
		"You showed up in THAT? Go change.",
		"I'm not battling anyone in that fit.",
		"Get some gear first, then ask for the mic.",
		"You're dressed like a dirt block.");
	private static final Map<String, String> SPECIES_REFUSAL = Map.of(
		"piglin", "Ни грамма золота? Нищеброд.",
		"enderman", "Ты одет как блок земли. Не смотри на меня.",
		"warden", "*тук-тук*… Я слышу, как дребезжит твоя кожанка.",
		"villager", "Хммм. Сначала оденься, потом торгуйся.",
		"zombie", "Ммозги… и прикид… у тебя… нету…",
		"skeleton", "*клац* Даже у меня на костях больше брони.");
	private static final Map<String, String> SPECIES_REFUSAL_EN = Map.of(
		"piglin", "Not a speck of gold? Broke.",
		"enderman", "You're dressed like a dirt block. Don't look at me.",
		"warden", "*thump-thump*… I can hear your leather creaking.",
		"villager", "Hmmm. Get dressed first, then we talk.",
		"zombie", "Brrraiiins… and drip… you got… neither…",
		"skeleton", "*clack* Even my bare bones got more armor.");
	/** Peaceful mobs hit instead of rapped with: no threats, they are hurt and scared. */
	private static final List<String> PEACEFUL_HURT = List.of(
		"Ай! За что?!",
		"Я просто траву жевал…",
		"Мама! Он дерётся!",
		"Ну и иди, раз микрофон не осилил…",
		"Псих! Все врассыпную!",
		"Больно же, дурак!");
	private static final List<String> PEACEFUL_HURT_EN = List.of(
		"Ow! What was that for?!",
		"I was just chewing grass…",
		"Mom! He's hitting me!",
		"Fine, leave then, guess you can't handle a mic…",
		"Psycho! Scatter, everybody!",
		"That hurts, idiot!");
	/** A player the mob refused over the outfit: a hit from him within this time is a fight, no second refusal. */
	private static final long REFUSED_MS = 60_000;
	private record Refused(MobEntity mob, long until) {}
	private static final Map<UUID, Refused> REFUSED = new HashMap<>();
	/** A fist fight already started with this mob: further hits are just hits, no new choice and no new lines. */
	private static final Map<UUID, Refused> BRAWLS = new HashMap<>();
	private static final long BRAWL_MS = 60_000;

	/** Mobs that already offered a choice on approach; they do not ask again for a while. */
	private static final Map<UUID, Long> ASKED = new HashMap<>();
	private static final long ASK_AGAIN_MS = 5 * 60_000;
	/** Last time each player hit a mob or took damage: approach callouts stay quiet during active combat. */
	private static final Map<UUID, Long> LAST_COMBAT = new HashMap<>();
	private static final long COMBAT_QUIET_MS = 10_000;

	private static boolean isRangedChallenger(MobEntity m) {
		return m instanceof net.minecraft.entity.ai.RangedAttackMob
			|| m instanceof net.minecraft.entity.mob.AbstractSkeletonEntity
			|| m instanceof net.minecraft.entity.mob.PillagerEntity;
	}

	/** A hostile mob walking up to a player gives him the same choice as an empty-hand hit: rap or fight. */
	private static void offerOnApproach(net.minecraft.server.MinecraftServer server) {
		long now = System.currentTimeMillis();
		ASKED.values().removeIf(t -> now > t);
		for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
			if (p.isSpectator() || p.isCreative() || !p.isAlive()) continue;
			UUID u = p.getUuid();
			if (byPlayer(u) != null || PvpBattle.byPlayer(u) != null || PENDING.containsKey(u)) continue;
			if (now - LAST_COMBAT.getOrDefault(u, 0L) < COMBAT_QUIET_MS) continue;
			Refused fight = BRAWLS.get(u);
			if (fight != null && now < fight.until()) continue;
			List<MobEntity> near = p.getEntityWorld().getEntitiesByClass(MobEntity.class, p.getBoundingBox().expand(18),
				m -> {
					if (!m.isAlive() || m.getTarget() != p || !(m instanceof net.minecraft.entity.mob.Monster)) return false;
					if (Opponent.isBoss(m) || byMob(m) != null || inAnyCrowd(m) || Crowd.inTruce(m) || ASKED.containsKey(m.getUuid())) return false;
					if (Reputation.respects(m, p)) return false;
					double r = isRangedChallenger(m) ? 12.0 : 7.0;
					if (Gangs.isRival(p, MobProfiles.of(m.getType()))) r *= 1.5;
					return m.squaredDistanceTo(p) <= r * r;
				});
			if (near.isEmpty()) continue;
			near.sort(Comparator.comparingDouble(m -> m.squaredDistanceTo(p)));
			Random rnd = new Random();
			for (MobEntity mob : near) {
				Progression.StartCheck sc = Progression.canStart(p, mob, true, 0, rnd);
				// If the mob walked up on its own and the player fails dress code or strict progression gate, do not stop the mob at all.
				if (!sc.allowed()) continue;
				ASKED.put(mob.getUuid(), now + ASK_AGAIN_MS);
				mob.setTarget(null);
				stareAt(mob, p, CHOICE_MS);
				offerChoice(p, mob, true, sc);
				break;
			}
		}
	}

	private static void restorePendingTarget(Pending pd, ServerPlayerEntity pl) {
		if (pd == null || pl == null || !pd.mob.isAlive() || !pl.isAlive() || pl.isCreative() || pl.isSpectator()) return;
		STARES.removeIf(s -> s.mob() == pd.mob);
		if (pd.mob instanceof net.minecraft.entity.passive.IronGolemEntity ig) {
			GolemDefender.chickenedOut(pl, ig);
		} else if (pd.fromApproach) {
			pd.mob.setTarget(pl);
		}
	}

	private static void brawl(ServerPlayerEntity p, MobEntity mob) {
		BRAWLS.put(p.getUuid(), new Refused(mob, System.currentTimeMillis() + BRAWL_MS));
	}
	/** Mobs turning to look at someone while they talk to him. */
	private record Stare(MobEntity mob, UUID player, long until) {}
	private static final List<Stare> STARES = new ArrayList<>();

	/** The mob turns to the player and keeps looking at him for {@code ms}. */
	static void stareAt(MobEntity mob, ServerPlayerEntity p, long ms) {
		STARES.removeIf(s -> s.mob() == mob);
		STARES.add(new Stare(mob, p.getUuid(), System.currentTimeMillis() + ms));
		faceNow(mob, p);
	}

	private static void faceNow(MobEntity mob, ServerPlayerEntity p) {
		double dx = p.getX() - mob.getX(), dz = p.getZ() - mob.getZ();
		float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
		mob.getNavigation().stop();
		mob.setHeadYaw(yaw);
		mob.setBodyYaw(yaw);
		mob.setYaw(yaw);
		mob.getLookControl().lookAt(p, 30f, 30f);
	}

	private static void tickStares(net.minecraft.server.MinecraftServer server) {
		long now = System.currentTimeMillis();
		STARES.removeIf(s -> {
			ServerPlayerEntity p = server.getPlayerManager().getPlayer(s.player());
			if (now > s.until() || p == null || !s.mob().isAlive() || byMob(s.mob()) != null) return true;
			faceNow(s.mob(), p);
			return false;
		});
		REFUSED.values().removeIf(r -> now > r.until() || !r.mob().isAlive());
		LAST_COMBAT.values().removeIf(t -> now - t > 60_000);
	}

	private static boolean peaceful(MobEntity mob) {
		return !(mob instanceof HostileEntity || mob instanceof Angerable);
	}

	private static final List<String> DISRESPECT = List.of(
		"Без слов? Пацаны, нас не уважают!",
		"Ах так? Ну давай по-плохому.",
		"Микрофон не осилил, полез драться? Свои, сюда!");
	private static final List<String> DISRESPECT_EN = List.of(
		"No words? Boys, we're getting disrespected!",
		"Oh, like that? Alright, the hard way it is.",
		"Couldn't handle the mic so you threw hands? Crew, over here!");

	public static Battle byPlayer(UUID uuid) {
		for (Battle b : BATTLES) if (!b.finished && b.hasPlayer(uuid)) return b;
		return null;
	}

	public static Battle byMob(Entity mob) {
		for (Battle b : BATTLES) if (!b.finished && b.hasMob(mob)) return b;
		return null;
	}

	/** A battle is running within {@code radius} blocks of this entity. */
	public static boolean battleNear(Entity e, double radius) {
		for (Battle b : BATTLES) if (!b.finished && b.mob.squaredDistanceTo(e) < radius * radius) return true;
		return false;
	}

	public static boolean inAnyCrowd(Entity e) {
		if (e == null) return false;
		for (Battle b : BATTLES) if (!b.finished && b.inCrowd(e)) return true;
		return false;
	}

	public static boolean isPlacedCrowd(MobEntity m) {
		if (m == null || BATTLES.isEmpty()) return false;
		return Crowd.isPlaced(m);
	}

	public static Battle byId(int id) {
		for (Battle b : BATTLES) if (!b.finished && b.id == id) return b;
		return null;
	}

	/** Starts a battle and invites nearby free players into the challenger's team. */
	public static Battle start(ServerPlayerEntity challenger, MobEntity mob) {
		return start(challenger, mob, null);
	}

	public static Battle start(ServerPlayerEntity challenger, MobEntity mob, ProgressionRules.GateCheck precheckedGate) {
		Battle b = new Battle(challenger, mob, precheckedGate);
		BATTLES.add(b);
		double r = mob instanceof net.minecraft.entity.boss.dragon.EnderDragonEntity
			? Math.max(RapConfig.get().inviteRadius, 48.0)
			: RapConfig.get().inviteRadius;
		for (ServerPlayerEntity other : challenger.getEntityWorld().getPlayers()) {
			if (other != challenger && byPlayer(other.getUuid()) == null && other.squaredDistanceTo(challenger) <= r * r) b.invite(other);
		}
		b.broadcastState();
		RapBattle.LOG.info("{} challenged {} ({}, {})", challenger.getName().getString(), b.opp.name(),
			mob.getType().getUntranslatedName(), b.rank);
		return b;
	}

	private static String refusalLine(Opponent o, String lang, int fallbackIdx) {
		Map<String, String> spec = Lang.isRu(lang) ? SPECIES_REFUSAL : SPECIES_REFUSAL_EN;
		List<String> pool = Lang.isRu(lang) ? REFUSALS : REFUSALS_EN;
		String kin = o.profile().kin();
		String s = spec.get(o.profile().id());
		if (s == null) s = spec.get(kin);
		return s != null ? s : pool.get(fallbackIdx % pool.size());
	}

	/** Empty-hand hit: ask the player whether to rap or to fight. */
	private static void offerChoice(ServerPlayerEntity p, MobEntity mob) {
		offerChoice(p, mob, false, null);
	}

	private static void offerChoice(ServerPlayerEntity p, MobEntity mob, boolean fromApproach, Progression.StartCheck prechecked) {
		String lang = Lang.of(p);
		Random rnd = new Random();
		Progression.StartCheck sc = prechecked != null ? prechecked : Progression.canStart(p, mob, true, 0, rnd);
		Opponent o = sc.opp();
		Rank displayRank = sc.ranks().battleRank();
		Rank gateRank = sc.ranks().gateRank();
		boolean dressAllowed = sc.dressAllowed();
		ProgressionRules.GateCheck gate = sc.gate();
		boolean allowed = sc.allowed();
		PENDING.put(p.getUuid(), new Pending(mob, System.currentTimeMillis() + CHOICE_MS, allowed, false, fromApproach, gate));
		BattleView v = new BattleView();
		v.type = "choice";
		v.mobEntityId = mob.getId();
		v.mobName = o.name();
		v.mobSpecies = o.profile().species(lang);
		v.rankArmor = displayRank.armor(lang);
		v.rankStreet = displayRank.street(lang);
		v.rankLevel = displayRank.level();
		v.rankColor = displayRank.color;
		v.boss = displayRank.isBoss();
		v.tags = o.tags();
		v.allowed = allowed;
		v.yourOutfit = sc.score().label(lang);
		v.requiredOutfit = !dressAllowed ? Battle.levelLabel(Outfit.required(gateRank), lang) : "";
		int refIdx = rnd.nextInt(REFUSALS.size());
		if (!dressAllowed) {
			v.refusal = refusalLine(o, lang, refIdx);
		} else if (!gate.allowed() && !gate.messageKey().isEmpty()) {
			v.refusal = Lang.t(lang, gate.messageKey(), gate.args());
		} else if (!gate.bonusesAllowed() && !gate.messageKey().isEmpty()) {
			v.gateWarning = Lang.t(lang, gate.messageKey(), gate.args());
		}
		ServerPlayNetworking.send(p, new Payloads.StateS2C(v.toJson()));
		if (!allowed) {
			broadcastRefusal(p, mob, o, dressAllowed, gate, v.refusal, refIdx, rnd);
		}
	}

	private static void broadcastRefusal(ServerPlayerEntity p, MobEntity mob, Opponent o, boolean dressAllowed,
										 ProgressionRules.GateCheck gate, String refusal, int refIdx, Random rnd) {
		String lang = Lang.of(p);
		REFUSED.put(p.getUuid(), new Refused(mob, System.currentTimeMillis() + REFUSED_MS));
		stareAt(mob, p, 3500);
		Speech.bubble(mob, refusal, 0xFF8080, 3500);
		for (ServerPlayerEntity near : p.getEntityWorld().getPlayers()) {
			if (near.squaredDistanceTo(mob) < 24 * 24) {
				String nl = Lang.of(near);
				String nm = nl.equals(lang) ? o.name() : Opponent.nameOf(mob, o.profile(), rnd, nl);
				String nearMsg = !dressAllowed
					? refusalLine(o, nl, refIdx)
					: Lang.t(nl, gate.messageKey(), gate.args());
				near.sendMessage(Text.literal("<" + nm + "> " + nearMsg).formatted(Formatting.GOLD), false);
			}
		}
	}

	/** Chose to fight instead of rap: disrespect. Kin nearby jumps in. */
	private static void disrespect(ServerPlayerEntity p, MobEntity mob) {
		String lang = Lang.of(p);
		String title = Stats.of(p.getEntityWorld().getServer()).chickened(p.getUuid(), lang);
		if (title != null) p.sendMessage(Text.translatable("rapbattle.manager.chickened_title", title)
			.formatted(Formatting.GRAY), false);
		boolean calm = peaceful(mob);
		// Peaceful mobs do not threaten anyone: they are hurt and run. Hostile ones call their people.
		Random rnd = new Random();
		List<String> poolRu = calm ? PEACEFUL_HURT : DISRESPECT;
		List<String> poolEn = calm ? PEACEFUL_HURT_EN : DISRESPECT_EN;
		int idx = rnd.nextInt(poolRu.size());
		String line = (Lang.isRu(lang) ? poolRu : poolEn).get(idx);
		Speech.bubble(mob, line, calm ? 0xFFE0A0 : 0xFF5050, 3000);
		MobProfiles.Profile prof = MobProfiles.of(mob.getType());
		for (ServerPlayerEntity near : p.getEntityWorld().getPlayers()) {
			if (near.squaredDistanceTo(mob) < 24 * 24) {
				String nl = Lang.of(near);
				String nearLine = (Lang.isRu(nl) ? poolRu : poolEn).get(idx);
				near.sendMessage(Text.literal("<" + Opponent.nameOf(mob, prof, rnd, nl) + "> " + nearLine)
					.formatted(calm ? Formatting.YELLOW : Formatting.RED), false);
			}
		}
		if (!RapConfig.get().kinRevenge || calm) return;
		List<MobEntity> kin = p.getEntityWorld().getEntitiesByClass(MobEntity.class, mob.getBoundingBox().expand(16),
			m -> m.isAlive() && byMob(m) == null && MobProfiles.of(m.getType()).kin().equals(prof.kin())
				&& !(m instanceof TameableEntity t && t.isTamed()));
		for (MobEntity m : kin) {
			if (m instanceof HostileEntity || m instanceof Angerable) {
				m.setTarget(p);
			}
		}
	}

	public static void register() {
		MobRecord.init();
		SoreLosers.register();

		AttackEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
			// Only decide on the server; the client must still send the attack packet.
			if (world.isClient() || !(player instanceof ServerPlayerEntity sp)) return ActionResult.PASS;
			// An empty-hand hit on another player: a call-out to a player battle.
			if (entity instanceof ServerPlayerEntity other && other != sp && sp.getMainHandStack().isEmpty() && !sp.isSpectator()) {
				PvpBattle running = PvpBattle.byPlayer(sp.getUuid());
				if (running != null || PvpBattle.byPlayer(other.getUuid()) != null) return ActionResult.FAIL;
				if (byPlayer(sp.getUuid()) == null && PvpBattle.challenge(sp, other)) return ActionResult.FAIL;
				return ActionResult.PASS;
			}
			// The dragon is hit through its body parts.
			Entity target = entity instanceof EnderDragonPart part ? part.owner : entity;
			if (!(target instanceof MobEntity mob) || !mob.isAlive() || sp.isSpectator()) return ActionResult.PASS;

			Battle own = byPlayer(sp.getUuid());
			if (own != null && !sp.getMainHandStack().isEmpty()) {
				// A participant swinging a weapon mid-battle breaks the street law.
				own.violation(sp);
				return ActionResult.FAIL;
			}
			Battle existing = byMob(mob);
			if (existing != null) {
				// Hitting a mob that is already battling: join if invited, otherwise just don't hurt it.
				if (existing.invited.contains(sp.getUuid())) existing.answerInvite(sp, true);
				return ActionResult.FAIL;
			}
			if (byPlayer(sp.getUuid()) != null) return ActionResult.FAIL;
			if (mob instanceof net.minecraft.entity.boss.dragon.EnderDragonEntity dragon && sp.getMainHandStack().isEmpty()) {
				if (!DragonBattle.canChallenge(sp, dragon)) return ActionResult.FAIL;
			}

			long now = System.currentTimeMillis();
			Refused fight = BRAWLS.get(sp.getUuid());
			if (fight != null && fight.mob() == mob && now < fight.until()) {
				brawl(sp, mob); // keep the fight going while he keeps swinging
				LAST_COMBAT.put(sp.getUuid(), now);
				return ActionResult.PASS;
			}
			Pending pending = PENDING.get(sp.getUuid());
			if (pending != null && pending.mob == mob && now < pending.until) {
				// Second hit while the choice is open = "набить ебало": a normal fight, and the kin takes offence.
				PENDING.remove(sp.getUuid());
				REFUSED.remove(sp.getUuid());
				STARES.removeIf(s -> s.mob() == mob);
				closeChoice(sp);
				if (pending.mob instanceof net.minecraft.entity.passive.IronGolemEntity ig) {
					GolemDefender.chickenedOut(sp, ig);
				} else {
					if (pending.fromApproach) mob.setTarget(sp);
					disrespect(sp, mob);
				}
				if (mob instanceof net.minecraft.entity.passive.VillagerEntity v) GolemDefender.offended(sp, v);
				Reputation.onPlayerHostileAction(sp, mob);
				fightAfterBattle(sp, mob);
				brawl(sp, mob);
				LAST_COMBAT.put(sp.getUuid(), now);
				return ActionResult.PASS;
			}
			Refused refused = REFUSED.get(sp.getUuid());
			if (refused != null && refused.mob() == mob && now < refused.until()) {
				// It already told him off about the outfit: a hit now is just a fight, no second lecture.
				REFUSED.remove(sp.getUuid());
				PENDING.remove(sp.getUuid());
				closeChoice(sp);
				disrespect(sp, mob);
				if (mob instanceof net.minecraft.entity.passive.VillagerEntity v) GolemDefender.offended(sp, v);
				Reputation.onPlayerHostileAction(sp, mob);
				fightAfterBattle(sp, mob);
				brawl(sp, mob);
				LAST_COMBAT.put(sp.getUuid(), now);
				return ActionResult.PASS;
			}
			if (!sp.getMainHandStack().isEmpty()) {
				// Weapons just fight. Hurting a villager brings the golem; brawling at a finished battle wakes the crowd.
				if (mob instanceof net.minecraft.entity.passive.VillagerEntity v) GolemDefender.offended(sp, v);
				Reputation.onPlayerHostileAction(sp, mob);
				fightAfterBattle(sp, mob);
				brawl(sp, mob);
				LAST_COMBAT.put(sp.getUuid(), now);
				return ActionResult.PASS;
			}
			if (RapConfig.get().skipChoice) {
				Random rnd = new Random();
				Progression.StartCheck sc = Progression.canStart(sp, mob, true, 0, rnd);
				if (!sc.allowed()) {
					int refIdx = rnd.nextInt(REFUSALS.size());
					String refusal = !sc.dressAllowed()
						? refusalLine(sc.opp(), Lang.of(sp), refIdx)
						: Lang.t(Lang.of(sp), sc.gate().messageKey(), sc.gate().args());
					broadcastRefusal(sp, mob, sc.opp(), sc.dressAllowed(), sc.gate(), refusal, refIdx, rnd);
					return ActionResult.FAIL;
				}
				start(sp, mob, sc.gate());
				return ActionResult.FAIL;
			}
			offerChoice(sp, mob);
			return ActionResult.FAIL;
		});

		ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
			boolean allowed = !(entity instanceof ServerPlayerEntity p && (byPlayer(p.getUuid()) != null || PvpBattle.byPlayer(p.getUuid()) != null))
				&& byMob(entity) == null
				// The crowd is a truce zone: no zombie turning its neighbour villager into a zombie villager mid-show.
				&& !inAnyCrowd(entity) && !inAnyCrowd(source.getAttacker());
			if (allowed && amount > 0) {
				long now = System.currentTimeMillis();
				if (entity instanceof ServerPlayerEntity sp) LAST_COMBAT.put(sp.getUuid(), now);
				if (entity instanceof MobEntity && source.getAttacker() instanceof ServerPlayerEntity sp) LAST_COMBAT.put(sp.getUuid(), now);
			}
			return allowed;
		});

		ServerTickEvents.END_SERVER_TICK.register(server -> {
			for (Battle b : new ArrayList<>(BATTLES)) {
				try {
					b.tick();
				} catch (RuntimeException e) {
					RapBattle.LOG.error("Battle {} crashed", b.id, e);
					b.abort(Text.translatable("rapbattle.battle.crashed"));
				}
			}
			BATTLES.removeIf(b -> b.finished);
			SoreLosers.tick();
			Speech.tick();
			DamagePop.tick();
			Crowd.tickTruce();
			long now = System.currentTimeMillis();
			for (Map.Entry<UUID, Pending> e : new ArrayList<>(PENDING.entrySet())) {
				Pending pd = e.getValue();
				ServerPlayerEntity pl = server.getPlayerManager().getPlayer(e.getKey());
				boolean walkedAway = pl != null && pd.mob.isAlive() && pl.squaredDistanceTo(pd.mob) > 15 * 15;
				if (now <= pd.until && !walkedAway) continue;
				PENDING.remove(e.getKey());
				if (pl != null) {
					closeChoice(pl);
					restorePendingTarget(pd, pl);
				}
			}
			// While he decides, the mob that asked stands still and waits (protected from sunburn).
			for (Pending pd : PENDING.values()) {
				if (pd.mob.isAlive() && pd.mob.isOnFire()) pd.mob.extinguish();
				if (!pd.golem && pd.mob.isAlive()) {
					if (pd.mob.getTarget() != null) pd.mob.setTarget(null);
					if (!pd.mob.getNavigation().isIdle()) pd.mob.getNavigation().stop();
				}
			}
			if (server.getTicks() % 10 == 0) offerOnApproach(server);
			Later.tick();
			GolemDefender.tick();
			RaidBattle.tick(server);
			BastionBattle.tick(server);
			PlayerStatus.tick(server);
			Reputation.tick(server);
			tickChampions(server);
			PvpBattle.tickAll();
			tickStares(server);
		});

		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			UUID u = handler.player.getUuid();
			PENDING.remove(u);
			LAST_COMBAT.remove(u);
			for (Battle b : new ArrayList<>(BATTLES)) b.onPlayerLeft(u);
			PvpBattle pvp = PvpBattle.byPlayer(u);
			if (pvp != null) pvp.leave(u);
		});

		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			ServerPlayerEntity player = handler.player;
			GuideBook.giveOnFirstJoin(player, server);
			if (!RapConfig.get().hasLlm() || RapConfig.get().sttKey().isEmpty()) {
				if (server.isSingleplayer() && server.isHost(player.getPlayerConfigEntry())) {
					player.sendMessage(Text.translatable("rapbattle.manager.fallback_notice").formatted(Formatting.YELLOW), false);
				}
			}
		});

		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			for (Battle b : BATTLES) b.abort(Text.translatable("rapbattle.manager.server_stopped"));
			Speech.clearAll();
			DamagePop.clearAll();
			Later.clear();
			GolemDefender.clear();
			Crowd.clear();
			RECENT.clear();
			BATTLES.clear();
			PENDING.clear();
			REFUSED.clear();
			BRAWLS.clear();
			STARES.clear();
			LAST_COMBAT.clear();
			ASKED.clear();
			CHAMPION_COOLDOWN.clear();
			REMATCH_COOLDOWN.clear();
			PlayerStatus.clear();
			Reputation.clear();
			SoreLosers.clear();
			BastionBattle.clear();
			RaidBattle.clear();
			DragonBattle.clear();
			PvpBattle.clear();
		});

		ServerPlayNetworking.registerGlobalReceiver(Payloads.ActionC2S.ID, (payload, ctx) -> {
			ServerPlayerEntity p = ctx.player();
			try {
				JsonObject o = JsonParser.parseString(payload.json()).getAsJsonObject();
				switch (o.get("type").getAsString()) {
					case "invite" -> {
						Battle b = byId(o.get("id").getAsInt());
						if (b != null) b.answerInvite(p, o.get("accept").getAsBoolean());
						else PvpBattle.answer(p, o.get("id").getAsInt(), o.get("accept").getAsBoolean());
					}
					case "go" -> {
						Battle b = byPlayer(p.getUuid());
						if (b != null) b.startEarly(p);
					}
					case "topic" -> {
						Battle b = byPlayer(p.getUuid());
						if (b != null) b.setTopic(p, o.get("topic").getAsInt());
					}
					case "reroll" -> {
						Battle b = byPlayer(p.getUuid());
						if (b != null) b.onReroll(p);
					}
					case "rap" -> {
						Pending pending = PENDING.remove(p.getUuid());
						if (pending == null || !pending.allowed || !pending.mob.isAlive() || byMob(pending.mob) != null
							|| byPlayer(p.getUuid()) != null || pending.mob.squaredDistanceTo(p) > 16 * 16) {
							closeChoice(p);
							return;
						}
						STARES.removeIf(s -> s.mob() == pending.mob);
						closeChoice(p);
						start(p, pending.mob, pending.gate);
					}
					case "cancel" -> {
						Pending pd = PENDING.remove(p.getUuid());
						closeChoice(p);
						restorePendingTarget(pd, p);
					}
					case "gaze" -> {
						Battle b = byPlayer(p.getUuid());
						if (b != null) b.onGaze(p);
					}
					case "keystatus" -> {
						// Only whether the keys are set, never the keys.
						BattleView kv = new BattleView();
						kv.type = "keys";
						kv.keysLlm = RapConfig.get().hasLlm();
						kv.keysStt = !RapConfig.get().sttKey().isEmpty();
						ServerPlayNetworking.send(p, new Payloads.StateS2C(kv.toJson()));
					}
					case "style_status" -> sendStyleStatus(p);
					case "guide" -> GuideBook.give(p);
					case "legend" -> {
						Stats.Legend leg = Stats.of(p.getEntityWorld().getServer()).legend;
						if (leg == null || leg.isEmpty()) {
							p.sendMessage(Text.translatable("rapbattle.act5.legend_empty").formatted(Formatting.GRAY), false);
						} else {
							p.sendMessage(Text.translatable("rapbattle.act5.legend_show", leg.player(), leg.line(), leg.date())
								.formatted(Formatting.GOLD), false);
						}
					}
					case "set_style" -> {
						Stats stats = Stats.of(p.getEntityWorld().getServer());
						Stats.Player st = stats.player(p.getUuid());
						McStyle ms = McStyle.of(o.has("style") ? o.get("style").getAsString() : "");
						if (ms.isUnlocked(st.respect)) {
							st.style = ms.id;
							stats.markDirty();
						}
						sendStyleStatus(p);
					}
					case "silent" -> {
						Battle b = byPlayer(p.getUuid());
						if (b != null) b.onSilentBar(p);
					}
					case "noise" -> {
						Battle b = byPlayer(p.getUuid());
						if (b != null) b.onNoise(p, o.get("n").getAsInt());
					}
					case "bar" -> {
						boolean loud = o.get("loud").getAsBoolean();
						int deltaMs = o.has("deltaMs") ? o.get("deltaMs").getAsInt() : -1;
						boolean silentBar = o.has("silent") && o.get("silent").getAsBoolean();
						Battle b = byPlayer(p.getUuid());
						if (b != null) b.onBar(p, loud, deltaMs, silentBar);
						PvpBattle pvp = PvpBattle.byPlayer(p.getUuid());
						if (pvp != null) pvp.onBar(p, loud, deltaMs, silentBar);
					}
					case "leave" -> {
						Battle b = byPlayer(p.getUuid());
						if (b != null) b.onPlayerLeft(p.getUuid());
						PvpBattle pvp = PvpBattle.byPlayer(p.getUuid());
						if (pvp != null) pvp.leave(p.getUuid());
					}
					default -> {}
				}
			} catch (RuntimeException e) {
				RapBattle.LOG.warn("Bad action from {}: {}", p.getName().getString(), payload.json());
			}
		});

		ServerPlayNetworking.registerGlobalReceiver(Payloads.AudioC2S.ID, (payload, ctx) -> {
			Battle b = byId(payload.battleId());
			if (b != null) b.onAudio(ctx.player(), payload);
			PvpBattle pvp = PvpBattle.byId(payload.battleId());
			if (pvp != null) pvp.onAudio(ctx.player(), payload);
		});
	}

	private static final Map<UUID, Long> CHAMPION_COOLDOWN = new HashMap<>();
	private static final Map<UUID, Long> REMATCH_COOLDOWN = new HashMap<>();

	/** Test-only helper to advance champion/rematch cooldowns without waiting real minutes. */
	public static void advanceCooldownsForTest(long deltaMs) {
		CHAMPION_COOLDOWN.replaceAll((k, v) -> v - deltaMs);
		REMATCH_COOLDOWN.replaceAll((k, v) -> v - deltaMs);
	}

	/** Shared eligibility check for all automatic mob callouts (champions, rematches, raid captains, bastion brutes). */
	public static boolean canAutoChallenge(ServerPlayerEntity p) {
		if (p == null || p.isSpectator() || !p.isAlive()) return false;
		UUID u = p.getUuid();
		return byPlayer(u) == null && PvpBattle.byPlayer(u) == null && !PENDING.containsKey(u);
	}

	/** Test-only helper to expire the player's PENDING choice timer immediately. */
	public static void expirePendingForTest(UUID playerId) {
		Pending pd = PENDING.get(playerId);
		if (pd != null) {
			PENDING.put(playerId, new Pending(pd.mob, 0L, pd.allowed, pd.golem, pd.fromApproach, pd.gate));
		}
	}

	/** Returns true if a player currently has a pending approach/callout choice. */
	public static boolean hasPending(UUID playerId) {
		return PENDING.containsKey(playerId);
	}

	/** Test-only helper to check if a player has a pending approach/callout choice. */
	public static boolean hasPendingForTest(UUID playerId) {
		return hasPending(playerId);
	}

	/** Test-only helper to clear pending choices between test scenarios. */
	public static void clearPendingForTest(UUID playerId) {
		PENDING.remove(playerId);
		ASKED.clear();
	}

	/** Test-only helper to accept a pending choice/callout as if the player pressed R. */
	public static Battle acceptPendingForTest(ServerPlayerEntity p) {
		Pending pending = PENDING.remove(p.getUuid());
		if (pending == null || !pending.allowed || !pending.mob.isAlive()
			|| byMob(pending.mob) != null || byPlayer(p.getUuid()) != null) {
			return null;
		}
		closeChoice(p);
		return start(p, pending.mob, pending.gate);
	}

	/** True if this mob should not catch fire in daylight (champion, active battle participant/crowd, pending choice, or in truce). */
	public static boolean isProtectedFromSun(MobEntity mob) {
		if (mob == null) return false;
		if (MobRecord.hasWins(mob) || Crowd.inTruce(mob)) return true;
		if (!BATTLES.isEmpty()) {
			if (Crowd.isPlaced(mob) || byMob(mob) != null || inAnyCrowd(mob)) return true;
		}
		if (!PENDING.isEmpty()) {
			for (Pending pd : PENDING.values()) {
				if (pd.mob == mob) return true;
			}
		}
		return false;
	}

	private static void tickChampions(net.minecraft.server.MinecraftServer server) {
		if (server.getTicks() % 20 != 0) return;
		long now = System.currentTimeMillis();
		CHAMPION_COOLDOWN.entrySet().removeIf(e -> now > e.getValue());
		REMATCH_COOLDOWN.entrySet().removeIf(e -> now > e.getValue());
		Stats stats = Stats.of(server);
		for (var w : server.getWorlds()) {
			for (ServerPlayerEntity p : w.getPlayers()) {
				if (!canAutoChallenge(p)) continue;
				// 1. Rematch: a mob that fled after a close loss remembers the player and calls for a rematch.
				String pName = p.getName().getString();
				List<MobEntity> rivals = w.getEntitiesByClass(MobEntity.class, p.getBoundingBox().expand(16),
					m -> {
						if (!m.isAlive() || Opponent.isBoss(m) || byMob(m) != null || inAnyCrowd(m) || Crowd.inTruce(m)) return false;
						if (REMATCH_COOLDOWN.containsKey(m.getUuid())) return false;
						MobRecord.Rival rv = MobRecord.rival(m, pName);
						return rv != null && !rv.mobWon();
					});
				if (!rivals.isEmpty()) {
					rivals.sort(Comparator.comparingDouble(m -> m.squaredDistanceTo(p)));
					MobEntity rival = rivals.get(0);
					if (Progression.canAutoChallengeMob(p, rival)) {
						REMATCH_COOLDOWN.put(rival.getUuid(), now + 2 * 60_000L);
						Speech.bubble(rival, Lang.t(Lang.of(p), "rapbattle.act6.rematch_callout"), 0xFF8A7A, 4000);
						challengeFrom(p, rival, "rapbattle.manager.rematch_challenge");
						continue;
					}
				}
				// 2. Champion (wins > 0) calls out a player on a 3+ win streak.
				if (stats.player(p.getUuid()).streak < 3) continue;
				List<MobEntity> champs = w.getEntitiesByClass(MobEntity.class, p.getBoundingBox().expand(24),
					m -> m.isAlive() && !Opponent.isBoss(m) && byMob(m) == null && !inAnyCrowd(m)
						&& MobRecord.of(m).wins() > 0 && !CHAMPION_COOLDOWN.containsKey(m.getUuid()));
				if (champs.isEmpty()) continue;
				champs.sort(Comparator.comparingDouble(m -> m.squaredDistanceTo(p)));
				MobEntity champ = champs.get(0);
				if (!Progression.canAutoChallengeMob(p, champ)) continue;
				CHAMPION_COOLDOWN.put(champ.getUuid(), now + 5 * 60_000L);
				Speech.bubble(champ, Lang.t(Lang.of(p), "rapbattle.act6.champion_callout"), 0xF2C94C, 4000);
				challengeFrom(p, champ, "rapbattle.manager.champion_challenge");
			}
		}
	}

	private static void sendStyleStatus(ServerPlayerEntity p) {
		Stats.Player st = Stats.of(p.getEntityWorld().getServer()).player(p.getUuid());
		BattleView sv = new BattleView();
		sv.type = "style_status";
		sv.playerRespect = st.respect;
		sv.playerStyle = st.mcStyle().id;
		sv.playerGateSummary = Progression.styleSummary(p, Lang.of(p));
		ServerPlayNetworking.send(p, new Payloads.StateS2C(sv.toJson()));
	}

	private static void closeChoice(ServerPlayerEntity p) {
		BattleView v = new BattleView();
		v.type = "choice_close";
		ServerPlayNetworking.send(p, new Payloads.StateS2C(v.toJson()));
	}
}
