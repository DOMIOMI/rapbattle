package dev.rapbattle.battle;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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

	private record Pending(MobEntity mob, long until, boolean allowed, boolean golem) {}

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
				Speech.bubble(fan, "Двое на одного? Не по-честному!", 0xFF8080, 2500);
			}
		}
	}

	/** The village golem calls the player out: answer with R, or it's a fight. */
	static void challengeFrom(ServerPlayerEntity p, MobEntity golem) {
		Opponent o = Opponent.of(golem, new Random(), p);
		PENDING.put(p.getUuid(), new Pending(golem, System.currentTimeMillis() + 15_000, true, true));
		BattleView v = new BattleView();
		v.type = "choice";
		v.mobEntityId = golem.getId();
		v.mobName = o.name();
		v.mobSpecies = o.profile().species();
		v.rankArmor = o.rank().armor;
		v.rankStreet = o.rank().street;
		v.rankLevel = o.rank().level();
		v.rankColor = o.rank().color;
		v.allowed = true;
		v.challengeFrom = "ВЫЗОВ ОТ ГОЛЕМА";
		v.choiceMs = 15_000;
		ServerPlayNetworking.send(p, new Payloads.StateS2C(v.toJson()));
	}

	private static final List<String> REFUSALS = List.of(
		"Ты в этом пришёл? Иди переоденься.",
		"С таким прикидом я не баттлюсь.",
		"Сначала оденься, потом микрофон проси.",
		"Ты одет как блок земли.");
	private static final Map<String, String> SPECIES_REFUSAL = Map.of(
		"piglin", "Ни грамма золота? Нищеброд.",
		"enderman", "Ты одет как блок земли. Не смотри на меня.",
		"warden", "*тук-тук*… Я слышу, как дребезжит твоя кожанка.",
		"villager", "Хммм. Сначала оденься, потом торгуйся.",
		"zombie", "Ммозги… и прикид… у тебя… нету…",
		"skeleton", "*клац* Даже у меня на костях больше брони.");
	/** Peaceful mobs hit instead of rapped with: no threats, they are hurt and scared. */
	private static final List<String> PEACEFUL_HURT = List.of(
		"Ай! За что?!",
		"Я просто траву жевал…",
		"Мама! Он дерётся!",
		"Ну и иди, раз микрофон не осилил…",
		"Псих! Все врассыпную!",
		"Больно же, дурак!");
	/** A player the mob refused over the outfit: a hit from him within this time is a fight, no second refusal. */
	private static final long REFUSED_MS = 60_000;
	private record Refused(MobEntity mob, long until) {}
	private static final Map<UUID, Refused> REFUSED = new HashMap<>();
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
	}

	private static boolean peaceful(MobEntity mob) {
		return !(mob instanceof HostileEntity || mob instanceof Angerable);
	}

	private static final List<String> DISRESPECT = List.of(
		"Без слов? Пацаны, нас не уважают!",
		"Ах так? Ну давай по-плохому.",
		"Микрофон не осилил, полез драться? Свои, сюда!");

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

	public static Battle byId(int id) {
		for (Battle b : BATTLES) if (!b.finished && b.id == id) return b;
		return null;
	}

	/** Starts a battle and invites nearby free players into the challenger's team. */
	public static Battle start(ServerPlayerEntity challenger, MobEntity mob) {
		Battle b = new Battle(challenger, mob);
		BATTLES.add(b);
		double r = RapConfig.get().inviteRadius;
		for (ServerPlayerEntity other : challenger.getEntityWorld().getPlayers()) {
			if (other != challenger && byPlayer(other.getUuid()) == null && other.squaredDistanceTo(challenger) <= r * r) b.invite(other);
		}
		b.broadcastState();
		RapBattle.LOG.info("{} challenged {} ({}, {})", challenger.getName().getString(), b.opp.name(),
			mob.getType().getUntranslatedName(), b.rank);
		return b;
	}

	private static String refusalLine(Opponent o) {
		String kin = o.profile().kin();
		String s = SPECIES_REFUSAL.get(o.profile().id());
		if (s == null) s = SPECIES_REFUSAL.get(kin);
		return s != null ? s : REFUSALS.get(new Random().nextInt(REFUSALS.size()));
	}

	/** Empty-hand hit: ask the player whether to rap or to fight. */
	private static void offerChoice(ServerPlayerEntity p, MobEntity mob) {
		Opponent o = Opponent.of(mob, new Random(), p);
		Outfit.Score score = Outfit.of(p);
		boolean allowed = !RapConfig.get().dressCode || score.level() >= Outfit.required(o.rank())
			|| Gangs.isOwn(p, o.profile()); // crew colours: they never refuse their own
		PENDING.put(p.getUuid(), new Pending(mob, System.currentTimeMillis() + CHOICE_MS, allowed, false));
		BattleView v = new BattleView();
		v.type = "choice";
		v.mobEntityId = mob.getId();
		v.mobName = o.name();
		v.mobSpecies = o.profile().species();
		v.rankArmor = o.rank().armor;
		v.rankStreet = o.rank().street;
		v.rankLevel = o.rank().level();
		v.rankColor = o.rank().color;
		v.boss = o.rank().isBoss();
		v.tags = o.tags();
		v.allowed = allowed;
		v.yourOutfit = score.label();
		v.requiredOutfit = Battle.levelLabel(Outfit.required(o.rank()));
		if (!allowed) v.refusal = refusalLine(o);
		ServerPlayNetworking.send(p, new Payloads.StateS2C(v.toJson()));
		if (!allowed) {
			REFUSED.put(p.getUuid(), new Refused(mob, System.currentTimeMillis() + REFUSED_MS));
			stareAt(mob, p, 3500);
			Speech.bubble(mob, v.refusal, 0xFF8080, 3500);
			for (ServerPlayerEntity near : p.getEntityWorld().getPlayers()) {
				if (near.squaredDistanceTo(mob) < 24 * 24) {
					near.sendMessage(Text.literal("<" + o.name() + "> " + v.refusal).formatted(Formatting.GOLD), false);
				}
			}
		}
	}

	/** Chose to fight instead of rap: disrespect. Kin nearby jumps in. */
	private static void disrespect(ServerPlayerEntity p, MobEntity mob) {
		String title = Stats.of(p.getEntityWorld().getServer()).chickened(p.getUuid());
		if (title != null) p.sendMessage(Text.literal("Новый титул: «" + title + "». Три раза выбрал кулаки вместо микрофона.")
			.formatted(Formatting.GRAY), false);
		boolean calm = peaceful(mob);
		// Peaceful mobs do not threaten anyone: they are hurt and run. Hostile ones call their people.
		List<String> pool = calm ? PEACEFUL_HURT : DISRESPECT;
		String line = pool.get(new Random().nextInt(pool.size()));
		Speech.bubble(mob, line, calm ? 0xFFE0A0 : 0xFF5050, 3000);
		for (ServerPlayerEntity near : p.getEntityWorld().getPlayers()) {
			if (near.squaredDistanceTo(mob) < 24 * 24) {
				near.sendMessage(Text.literal("<" + Opponent.of(mob, new Random()).name() + "> " + line)
					.formatted(calm ? Formatting.YELLOW : Formatting.RED), false);
			}
		}
		if (!RapConfig.get().kinRevenge || calm) return;
		MobProfiles.Profile prof = MobProfiles.of(mob.getType());
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
			if (own != null && !own.hasMob(mob) && !sp.getMainHandStack().isEmpty()) {
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

			Pending pending = PENDING.get(sp.getUuid());
			long now = System.currentTimeMillis();
			if (pending != null && pending.mob == mob && now < pending.until) {
				// Second hit while the choice is open = "набить ебало": a normal fight, and the kin takes offence.
				PENDING.remove(sp.getUuid());
				REFUSED.remove(sp.getUuid());
				closeChoice(sp);
				if (pending.golem) GolemDefender.chickenedOut(sp, (net.minecraft.entity.passive.IronGolemEntity) mob);
				else disrespect(sp, mob);
				if (mob instanceof net.minecraft.entity.passive.VillagerEntity v) GolemDefender.offended(sp, v);
				fightAfterBattle(sp, mob);
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
				fightAfterBattle(sp, mob);
				return ActionResult.PASS;
			}
			if (!sp.getMainHandStack().isEmpty()) {
				// Weapons just fight. Hurting a villager brings the golem; brawling at a finished battle wakes the crowd.
				if (mob instanceof net.minecraft.entity.passive.VillagerEntity v) GolemDefender.offended(sp, v);
				fightAfterBattle(sp, mob);
				return ActionResult.PASS;
			}
			if (RapConfig.get().skipChoice) {
				start(sp, mob);
				return ActionResult.FAIL;
			}
			offerChoice(sp, mob);
			return ActionResult.FAIL;
		});

		ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) ->
			!(entity instanceof ServerPlayerEntity p && (byPlayer(p.getUuid()) != null || PvpBattle.byPlayer(p.getUuid()) != null))
				&& byMob(entity) == null);

		ServerTickEvents.END_SERVER_TICK.register(server -> {
			for (Battle b : new ArrayList<>(BATTLES)) {
				try {
					b.tick();
				} catch (RuntimeException e) {
					RapBattle.LOG.error("Battle {} crashed", b.id, e);
					b.abort("Баттл сломался, смотри лог сервера");
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
				if (now <= pd.until) continue;
				PENDING.remove(e.getKey());
				// Ignored the golem's call: that's a fight.
				ServerPlayerEntity pl = server.getPlayerManager().getPlayer(e.getKey());
				if (pd.golem && pl != null && pd.mob.isAlive()) {
					closeChoice(pl);
					GolemDefender.chickenedOut(pl, (net.minecraft.entity.passive.IronGolemEntity) pd.mob);
				}
			}
			Later.tick();
			GolemDefender.tick();
			PvpBattle.tickAll();
			tickStares(server);
		});

		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			UUID u = handler.player.getUuid();
			PENDING.remove(u);
			for (Battle b : new ArrayList<>(BATTLES)) b.onPlayerLeft(u);
			PvpBattle pvp = PvpBattle.byPlayer(u);
			if (pvp != null) pvp.leave(u);
		});

		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			if (!RapConfig.get().hasLlm() || RapConfig.get().sttKey().isEmpty()) {
				if (server.isSingleplayer() && server.isHost(handler.player.getPlayerConfigEntry())) {
					handler.player.sendMessage(Text.literal("[Рэп-баттл] Нейросеть или распознавание голоса не настроены, баттлы в запасном режиме. "
						+ "Ключи: config/rapbattle-server.json").formatted(Formatting.YELLOW), false);
				}
			}
		});

		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			for (Battle b : BATTLES) b.abort("Сервер остановлен");
			Speech.clearAll();
			DamagePop.clearAll();
			Later.clear();
			GolemDefender.clear();
			RECENT.clear();
			BATTLES.clear();
			PENDING.clear();
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
					case "rap" -> {
						Pending pending = PENDING.remove(p.getUuid());
						if (pending == null || !pending.allowed || !pending.mob.isAlive() || byMob(pending.mob) != null
							|| byPlayer(p.getUuid()) != null || pending.mob.squaredDistanceTo(p) > 12 * 12) {
							closeChoice(p);
							return;
						}
						closeChoice(p);
						start(p, pending.mob);
					}
					case "cancel" -> PENDING.remove(p.getUuid());
					case "keystatus" -> {
						// Only whether the keys are set, never the keys.
						BattleView kv = new BattleView();
						kv.type = "keys";
						kv.keysLlm = RapConfig.get().hasLlm();
						kv.keysStt = !RapConfig.get().sttKey().isEmpty();
						ServerPlayNetworking.send(p, new Payloads.StateS2C(kv.toJson()));
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
						Battle b = byPlayer(p.getUuid());
						if (b != null) b.onBar(p, o.get("loud").getAsBoolean());
						PvpBattle pvp = PvpBattle.byPlayer(p.getUuid());
						if (pvp != null) pvp.onBar(p, o.get("loud").getAsBoolean());
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

	private static void closeChoice(ServerPlayerEntity p) {
		BattleView v = new BattleView();
		v.type = "choice_close";
		ServerPlayNetworking.send(p, new Payloads.StateS2C(v.toJson()));
	}
}
