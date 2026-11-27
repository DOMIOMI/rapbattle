package dev.rapbattle.battle;

import dev.rapbattle.Lang;
import dev.rapbattle.RapBattle;
import dev.rapbattle.RapConfig;
import dev.rapbattle.ai.Fallback;
import dev.rapbattle.ai.Llm;
import dev.rapbattle.ai.Transcriber;
import dev.rapbattle.net.BattleView;
import dev.rapbattle.net.Payloads;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.ExperienceOrbEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.damage.DamageType;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;

import java.io.ByteArrayOutputStream;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One rap battle.
 * LOBBY (friends join) → for each round: PREP (the model writes the verse and rhymes) → TIMELINE (countdown,
 * mob team verse, players' verses on a fixed beat grid) → JUDGING (speech-to-text + judge) → ROUND_RESULT →
 * … → RESULT → done. Bosses play best of three. Runs on the server thread; network/AI work happens on
 * {@link #POOL} and is polled from {@link #tick}.
 */
public class Battle {
	public enum Phase { LOBBY, PREP, TIMELINE, JUDGING, ROUND_RESULT, RESULT }

	static final ExecutorService POOL = new java.util.concurrent.ThreadPoolExecutor(4, 8, 60L, java.util.concurrent.TimeUnit.SECONDS,
		new java.util.concurrent.LinkedBlockingQueue<>(64), r -> {
			Thread t = new Thread(r, "rapbattle-ai");
			t.setDaemon(true);
			return t;
		});
	private static final AtomicInteger IDS = new AtomicInteger(1);
	private static final long PREP_WAIT_MS = 20000, AUDIO_WAIT_MS = 12000, STT_WAIT_MS = 45000, JUDGE_WAIT_MS = 60000,
		MIN_JUDGING_MS = 2500, ROUND_RESULT_MS = 7000, RESULT_MS = 11000;
	private static final int[] BLOCK_COLORS = {0xF07167, 0x6EC6F0, 0x7EE0A8, 0xF6D55C, 0xD08CF0, 0x7FDBDA};
	private static final int[] MOB_COLORS = {0xF4A261, 0xE9C46A, 0xE76F51, 0xF28482};
	public static final RegistryKey<DamageType> RAP_DEATH =
		RegistryKey.of(RegistryKeys.DAMAGE_TYPE, Identifier.of(RapBattle.MOD_ID, "rap_battle"));

	public final int id = IDS.getAndIncrement();
	/** "ru" or "en": the challenger's Minecraft language; everything the battle says is in it. */
	public final String lang;
	public final ServerWorld world;
	public final MobEntity mob;
	public final Opponent opp;
	public final Rank rank;
	final boolean boss;
	private final int rounds, winsNeeded;
	private final Random rnd = new Random();
	private final RapConfig cfg = RapConfig.get();
	private final long beatMs;
	/** The real beat this battle plays; the battle's tempo is the track's tempo. */
	private final BeatTracks.Track track;

	final List<UUID> players = new ArrayList<>();
	final Set<UUID> leftPlayers = new HashSet<>();
	final Set<UUID> resolvedPlayers = new HashSet<>();
	final Set<UUID> pendingJudgeExit = new HashSet<>();
	private final Set<UUID> anchorReleased = new HashSet<>();
	final Map<UUID, String> names = new HashMap<>();
	final Set<UUID> invited = new HashSet<>();
	private final Map<UUID, Vec3d> anchors = new HashMap<>();
	final Map<UUID, Integer> initialSelectedSlot = new HashMap<>();
	private final Map<UUID, Integer> pushTicks = new HashMap<>();
	private final Set<UUID> spectators = new HashSet<>();

	/** Mob team: the challenged mob first, then kin that joined. */
	final List<MobEntity> mobTeam = new ArrayList<>();
	private final Map<MobEntity, String> mobNames = new HashMap<>();
	private final Map<MobEntity, Boolean> hadNoAi = new HashMap<>();
	private final Map<MobEntity, Boolean> wasSilent = new HashMap<>();
	private final Map<MobEntity, Boolean> wasImmuneZombify = new HashMap<>();
	private final Set<Entity> glowing = new HashSet<>();
	private final Set<MobEntity> dressed = new HashSet<>();
	/** Mobs around the stage watching and reacting. */
	Crowd crowd;
	private final net.minecraft.village.raid.Raid raid;
	private final boolean gangRival;
	private final Set<Integer> punchesFired = new HashSet<>();
	private Entity currentReader;

	private Phase phase = Phase.LOBBY;
	private long phaseStart = dev.rapbattle.Clock.now();
	private long lobbyEnd;
	private long beatZero;
	boolean finished;

	private int round = 1, winsPlayers, winsMob;
	private CompletableFuture<Llm.Content> contentFuture;
	private List<Llm.MobLine> mobLines = List.of();
	private final StringBuilder history = new StringBuilder();
	private BattleView view;
	private final Map<UUID, List<String>> rhymeWords = new HashMap<>();
	private int outfitBonus;
	boolean overdressed;
	final boolean actBonusesAllowed;
	final double dailyBonusScale;

	private final Map<UUID, ByteArrayOutputStream> audio = new HashMap<>();
	private final Map<UUID, Integer> audioSeq = new HashMap<>();
	private final Map<UUID, CompletableFuture<String>> transcripts = new HashMap<>();
	/** Word timestamps from speech recognition, and where each recording starts on the battle clock. */
	private final Map<UUID, dev.rapbattle.ai.Transcriber.Result> timed = new java.util.concurrent.ConcurrentHashMap<>();
	private final Map<UUID, Integer> recordingStart = new java.util.concurrent.ConcurrentHashMap<>();
	private final Set<UUID> sttMissingPlayers = java.util.concurrent.ConcurrentHashMap.newKeySet();

	public boolean isSttMissingForTest(UUID u) { return sttMissingPlayers.contains(u); }
	/** Warden: noises the team made while he was reading. */
	private int noise;
	/** Crowd: rhyme slots players hit loudly / missed in silence this round. */
	int barsHit, barsMissed;
	private final Map<String, List<Boolean>> playerBarLoud = new HashMap<>();
	private final Map<String, List<Integer>> playerBarDeltaMs = new HashMap<>();
	private final Map<String, List<Boolean>> playerBarSilent = new HashMap<>();
	private final Set<UUID> gazedPlayers = new HashSet<>();
	private final Set<UUID> rerolledPlayers = new HashSet<>();
	private final Set<UUID> wardenTriggered = new HashSet<>();
	private final Map<UUID, Integer> playerNoise = new HashMap<>();
	private List<String> targets = List.of();
	private int chosenTopic;
	private boolean hasStyleBonus;
	private int[] mobLiveDamages = new int[0];
	private List<Llm.PlayerVerse> pendingVerses = List.of();
	/** Respect: the battle's health bars. Not reset between rounds. */
	int respectTeam, respectMob, respectMax;
	/** The team's run of lines on the beat (x1.5 from the 3rd, x2 from the 6th). */
	int combo, bestCombo;
	private int dealtTotal, linesOnBeat, linesTotal, roundToMob, roundToTeam, bestLineDamage;
	private String bestLine = "";
	private final Map<String, Integer> roundDealt = new HashMap<>();
	final Map<String, Integer> battleDealt = new HashMap<>();
	private final Set<Integer> mobLinesFired = new HashSet<>();
	private int allyBonus;
	private CompletableFuture<Llm.Verdict> verdictFuture;
	private long judgingStart, verdictStart;
	BattleView.Result result;

	public Battle(ServerPlayerEntity challenger, MobEntity mob) {
		this(challenger, mob, null);
	}

	public Battle(ServerPlayerEntity challenger, MobEntity mob, ProgressionRules.GateCheck precheckedGate) {
		this.lang = Lang.of(challenger);
		this.world = (ServerWorld) mob.getEntityWorld();
		this.mob = mob;
		this.opp = Opponent.of(mob, rnd, challenger, lang);
		Progression.EffectiveRanks ranks = Progression.computeRanks(challenger, mob, opp);
		this.raid = ranks.raid();
		this.gangRival = ranks.gangRival();
		if (ranks.canBribe() && challenger != null) {
			challenger.getOffHandStack().decrement(1);
			Speech.bubble(mob, Lang.t(lang, "rapbattle.trick.bribe_bubble"), 0x7CF0A0, 3000);
			challenger.sendMessage(Text.translatable("rapbattle.trick.bribe_chat", opp.name()).formatted(net.minecraft.util.Formatting.GREEN), false);
		}
		this.rank = ranks.battleRank();
		this.boss = rank.isBoss();
		ProgressionRules.GateCheck gate = precheckedGate != null
			? precheckedGate
			: Progression.check(challenger, mob, opp, ranks.gateRank(),
				mob instanceof net.minecraft.entity.boss.dragon.EnderDragonEntity d ? DragonBattle.countCrystals(d) : 0);
		this.actBonusesAllowed = gate.bonusesAllowed();
		long overworldDay = ProgressionRules.currentDay(world.getServer().getOverworld().getTime());
		int prevToday = challenger != null
			? Stats.of(world.getServer()).player(challenger.getUuid()).dailyWinsFor(opp.profile().id(), overworldDay)
			: 0;
		this.dailyBonusScale = ProgressionRules.dailyBonusScale(prevToday);
		this.rounds = boss ? 3 : 1;
		this.winsNeeded = boss ? 2 : 1;
		this.respectMax = Scoring.RESPECT_MAX;
		this.respectTeam = respectMax;
		this.respectMob = respectMax;
		long wantMs = Math.round(60000.0 / Math.max(40, Math.min(220, rank.bpm * cfg.tempoScale * tempoOf(opp.profile().id()))));
		this.track = pickTrack(60000.0 / wantMs, rank.isBoss());
		this.beatMs = track != null ? track.beatMs() : wantMs;
		this.lobbyEnd = phaseStart + cfg.lobbySeconds * 1000L * (boss ? 2 : 1);
		addMob(mob, opp.name());
		if (mob instanceof net.minecraft.entity.boss.dragon.EnderDragonEntity dragon) {
			DragonBattle.holdHover(dragon);
		}
		if (this.raid != null) {
			List<net.minecraft.entity.raid.RaiderEntity> crew = new ArrayList<>();
			for (net.minecraft.entity.raid.RaiderEntity raider : this.raid.getAllRaiders()) {
				if (raider != mob && raider.isAlive() && BattleManager.byMob(raider) == null) crew.add(raider);
			}
			crew.sort(Comparator.comparingDouble(e -> e.squaredDistanceTo(mob)));
			for (int i = 0; i < Math.min(3, crew.size()); i++) {
				MobEntity ally = crew.get(i);
				addMob(ally, Opponent.nameOf(ally, MobProfiles.of(ally.getType()), rnd, lang));
			}
		}
		addPlayer(challenger);
		greetRival(challenger);
	}

	/** Some mobs have their own pace: the cow is lazy, the blaze is hot-headed. */
	private static double tempoOf(String id) {
		return switch (id) {
			case "cow", "mooshroom", "zombie_horse", "camel", "camel_husk", "sniffer" -> 0.85;
			case "turtle" -> 0.75;
			case "zombie", "husk", "drowned", "giant" -> 0.92;
			case "blaze", "breeze", "rabbit", "vex", "bee" -> 1.12;
			case "horse", "fox", "phantom" -> 1.05;
			default -> 1.0;
		};
	}

	// ------------------------------------------------------------------ members

	private void addMob(MobEntity m, String name) {
		mobTeam.add(m);
		mobNames.put(m, name);
		hadNoAi.put(m, m.isAiDisabled());
		m.setAiDisabled(true);
		wasSilent.put(m, m.isSilent());
		m.setSilent(true);
		Crowd.protectZombify(m, wasImmuneZombify);
		if (Gangs.dressUp(m)) dressed.add(m); // crew bandana on stage
	}

	private void addPlayer(ServerPlayerEntity p) {
		if (p.hasVehicle()) p.stopRiding();
		players.add(p.getUuid());
		names.put(p.getUuid(), p.getName().getString());
		anchors.put(p.getUuid(), new Vec3d(p.getX(), p.getY(), p.getZ()));
		initialSelectedSlot.put(p.getUuid(), p.getInventory().getSelectedSlot());
		p.extinguish();
		p.setAir(p.getMaxAir());
		invited.remove(p.getUuid());
		spectators.remove(p.getUuid());
	}

	public boolean hasPlayer(UUID uuid) { return players.contains(uuid) && !leftPlayers.contains(uuid); }

	public boolean hasMob(Entity e) { return e instanceof MobEntity m && mobTeam.contains(m); }

	public Phase phase() { return phase; }

	public boolean isResolving() {
		return phase == Phase.JUDGING || phase == Phase.ROUND_RESULT || phase == Phase.RESULT;
	}

	public void releaseAnchor(ServerPlayerEntity p) {
		if (p != null) anchorReleased.add(p.getUuid());
	}

	public ServerPlayerEntity challenger() {
		for (UUID u : players) {
			if (!leftPlayers.contains(u)) {
				ServerPlayerEntity p = player(u);
				if (p != null) return p;
			}
		}
		return players.isEmpty() ? null : player(players.get(0));
	}

	List<UUID> activePlayers() {
		if (leftPlayers.isEmpty()) return players;
		List<UUID> out = new ArrayList<>(players.size());
		for (UUID u : players) if (!leftPlayers.contains(u)) out.add(u);
		return out;
	}

	private ServerPlayerEntity player(UUID uuid) { return BattleManager.findPlayer(world.getServer(), uuid); }

	private List<String> namesList() {
		List<String> out = new ArrayList<>();
		for (UUID u : activePlayers()) out.add(names.get(u));
		return out;
	}

	// ------------------------------------------------------------------ lobby

	public void invite(ServerPlayerEntity p) {
		invited.add(p.getUuid());
		String pl = Lang.of(p);
		Outfit.Score score = Outfit.of(p, pl);
		BattleView inv = baseView();
		inv.type = "invite";
		inv.allowed = !cfg.dressCode || score.level() >= Outfit.requiredToJoin(rank) || Gangs.isOwn(p, opp.profile());
		inv.yourOutfit = score.label(pl);
		inv.requiredOutfit = levelLabel(Outfit.requiredToJoin(rank), pl);
		send(p, inv);
	}

	/** Invite answer; joining is only possible while the lobby is open. */
	public void answerInvite(ServerPlayerEntity p, boolean accept) {
		if (!invited.contains(p.getUuid())) return;
		if (accept && phase == Phase.LOBBY && !hasPlayer(p.getUuid())
			&& !BattleManager.isBattling(p)
			&& p.getEntityWorld() == world && p.squaredDistanceTo(mob) <= 32 * 32) {
			BattleManager.clearPending(p);
			String pl = Lang.of(p);
			Outfit.Score score = Outfit.of(p, pl);
			if (cfg.dressCode && score.level() < Outfit.requiredToJoin(rank) && !Gangs.isOwn(p, opp.profile())) {
				p.sendMessage(Text.translatable("rapbattle.battle.dress_code_reject", opp.name(),
					levelLabel(Outfit.requiredToJoin(rank), pl), score.label(pl)).formatted(Formatting.RED), false);
				invite(p); // re-send so the banner shows the current outfit
				return;
			}
			addPlayer(p);
			broadcastChat(Text.translatable("rapbattle.battle.player_joined", p.getName().getString()).formatted(Formatting.AQUA));
			broadcastState();
		} else {
			invited.remove(p.getUuid());
			BattleView close = new BattleView();
			close.type = "invite_close";
			close.id = id;
			send(p, close);
		}
	}

	/** Challenger pressed "go" before the lobby timer ran out. */
	public void startEarly(ServerPlayerEntity p) {
		if (phase == Phase.LOBBY && p.getUuid().equals(players.get(0))) lobbyEnd = dev.rapbattle.Clock.now();
	}

	/** Challenger picks a battle topic in the lobby (1..3). */
	public void setTopic(ServerPlayerEntity p, int topic) {
		if (phase == Phase.LOBBY && p.getUuid().equals(players.get(0)) && topic >= 1 && topic <= 3) {
			this.chosenTopic = topic;
			broadcastState();
		}
	}

	private String topicPromptText() {
		return switch (chosenTopic) {
			case 1 -> Lang.isRu(lang) ? "высмеять шмот, броню и понты соперника" : "roast the opponent's outfit, armor, and flex";
			case 2 -> Lang.isRu(lang) ? "чей это район и биом, кто здесь хозяин" : "whose turf and biome this is, who runs the block";
			case 3 -> Lang.isRu(lang) ? "жёсткий личный разбор без тормозов и пощады" : "brutal personal roast with no brakes and no mercy";
			default -> "";
		};
	}

	public void onPlayerLeft(UUID uuid) {
		if (!hasPlayer(uuid)) {
			invited.remove(uuid);
			spectators.remove(uuid);
			return;
		}
		if (isResolving()) {
			resolveNow(uuid);
			return;
		}
		leftPlayers.add(uuid);
		boolean anyOnline = false;
		for (UUID u : players) {
			if (!leftPlayers.contains(u) && player(u) != null) {
				anyOnline = true;
				break;
			}
		}
		if (players.size() == 1 || (phase == Phase.LOBBY && uuid.equals(players.get(0))) || !anyOnline) {
			BattleManager.markLeaveCooldown(uuid);
			abort(Text.translatable("rapbattle.battle.abort_player_left"));
			return;
		}
		// Coop partner left in LOBBY: remove completely so they aren't included in timeline/rewards.
		ServerPlayerEntity leaver = player(uuid);
		if (phase == Phase.LOBBY) {
			players.remove(uuid);
			leftPlayers.remove(uuid);
			names.remove(uuid);
			anchors.remove(uuid);
			pushTicks.remove(uuid);
		}
		// Coop partner left mid-battle: release them cleanly, keep going for remaining players.
		if (leaver != null) {
			if (glowing.remove(leaver)) leaver.setGlowing(false);
			BattleView end = new BattleView();
			end.type = "end";
			end.id = id;
			send(leaver, end);
		}
		if (phase != Phase.LOBBY && !transcripts.containsKey(uuid)) transcripts.put(uuid, CompletableFuture.completedFuture(""));
		broadcastState();
	}

	/** Cancels the battle for {@code p} without any win/loss penalties (environmental hazard or stuck). */
	public void abortNoPenalty(ServerPlayerEntity p, Text reason) {
		if (finished || p == null || !hasPlayer(p.getUuid())) return;
		UUID u = p.getUuid();
		p.sendMessage(reason.copy().formatted(Formatting.YELLOW), false);
		long activeCount = players.stream().filter(id -> !leftPlayers.contains(id)).count();
		if (activeCount <= 1 || (phase == Phase.LOBBY && u.equals(players.get(0)))) {
			abort(reason);
			return;
		}
		players.remove(u);
		leftPlayers.remove(u);
		anchors.remove(u);
		pushTicks.remove(u);
		BattleView end = new BattleView();
		end.type = "end";
		end.id = id;
		send(p, end);
		broadcastState();
	}

	/** Puts the mob on its mark, ~3 blocks (plus its own half-width) in front of the challenger, if there is room. */
	private void setStage() {
		ServerPlayerEntity c = challenger();
		if (c == null || boss) return;
		Vec3d p = new Vec3d(c.getX(), c.getY(), c.getZ());
		Vec3d m = new Vec3d(mob.getX(), mob.getY(), mob.getZ());
		Vec3d dir = m.subtract(p).multiply(1, 0, 1);
		if (dir.lengthSquared() < 1e-4) return;
		double dist = Math.sqrt(dir.lengthSquared());
		double mark = 3.0 + mob.getWidth() / 2;
		if (Math.abs(dist - mark) < 0.4) return;
		Vec3d target = p.add(dir.normalize().multiply(mark));
		boolean needWater = Crowd.isAquaticOnly(mob);
		for (double y : new double[]{0, 1, -1}) {
			Vec3d at = new Vec3d(target.x, Math.floor(m.y) + y, target.z);
			if (needWater && !world.getFluidState(net.minecraft.util.math.BlockPos.ofFloored(at)).isIn(net.minecraft.registry.tag.FluidTags.WATER)) continue;
			var box = mob.getBoundingBox().offset(at.subtract(m));
			if (world.isSpaceEmpty(mob, box) && !world.isSpaceEmpty(mob, box.offset(0, -0.2, 0))) {
				mob.refreshPositionAndAngles(at.x, at.y, at.z, mob.getYaw(), mob.getPitch());
				world.spawnParticles(net.minecraft.particle.ParticleTypes.CLOUD, at.x, at.y + 0.2, at.z, 6, 0.3, 0.1, 0.3, 0.01);
				return;
			}
		}
	}

	/**
	 * The crew stands with its leader: shoulder to shoulder in one line facing the challenger, alternating right and
	 * left of the main mob and half a step back, so everyone who reads is on stage and in the spotlights.
	 */
	private void placeCrew() {
		ServerPlayerEntity c = challenger();
		if (c == null || boss || mobTeam.size() < 2) return;
		Vec3d p = new Vec3d(c.getX(), c.getY(), c.getZ());
		Vec3d m = new Vec3d(mob.getX(), mob.getY(), mob.getZ());
		Vec3d dir = m.subtract(p).multiply(1, 0, 1);
		if (dir.lengthSquared() < 1e-4) return;
		dir = dir.normalize();
		Vec3d side = new Vec3d(-dir.z, 0, dir.x);
		int k = 0;
		double reachRight = mob.getWidth() / 2, reachLeft = mob.getWidth() / 2;
		for (MobEntity e : mobTeam) {
			if (e == mob) continue;
			boolean right = k++ % 2 == 0;
			double gap = 0.35 + e.getWidth() / 2;
			double off = (right ? reachRight : reachLeft) + gap;
			if (right) reachRight = off + e.getWidth() / 2; else reachLeft = off + e.getWidth() / 2;
			Vec3d target = m.add(side.multiply(right ? off : -off)).add(dir.multiply(0.5));
			Vec3d look = p.subtract(target);
			float yaw = (float) (Math.toDegrees(Math.atan2(look.z, look.x)) - 90);
			Vec3d cur = new Vec3d(e.getX(), e.getY(), e.getZ());
			boolean needWater = Crowd.isAquaticOnly(e);
			for (double y : new double[]{0, 1, -1}) {
				Vec3d at = new Vec3d(target.x, Math.floor(m.y) + y, target.z);
				if (needWater && !world.getFluidState(net.minecraft.util.math.BlockPos.ofFloored(at)).isIn(net.minecraft.registry.tag.FluidTags.WATER)) continue;
				var box = e.getBoundingBox().offset(at.subtract(cur));
				if (world.isSpaceEmpty(e, box) && !world.isSpaceEmpty(e, box.offset(0, -0.2, 0))) {
					e.refreshPositionAndAngles(at.x, at.y, at.z, yaw, 0);
					e.setHeadYaw(yaw);
					e.setBodyYaw(yaw);
					world.spawnParticles(net.minecraft.particle.ParticleTypes.CLOUD, at.x, at.y + 0.2, at.z, 6, 0.3, 0.1, 0.3, 0.01);
					break;
				}
			}
		}
	}

	private void closeLobby() {
		setStage();
		for (UUID u : new ArrayList<>(invited)) {
			ServerPlayerEntity p = player(u);
			if (p != null) answerInvite(p, false);
		}
		invited.clear();
		// Kin backs the mob up: wolf packs, zombie hordes, raids. Never more than one extra per player.
		if (opp.profile().pack() && !boss) {
			int extra = players.size();
			List<MobEntity> kin = world.getEntitiesByClass(MobEntity.class, mob.getBoundingBox().expand(12),
				m -> m != mob && m.isAlive() && BattleManager.byMob(m) == null
					&& MobProfiles.of(m.getType()).kin().equals(opp.profile().kin()));
			kin.sort(Comparator.comparingDouble(m -> m.squaredDistanceTo(mob)));
			for (MobEntity m : kin) {
				if (extra-- <= 0) break;
				Opponent o = Opponent.of(m, rnd, lang);
				String n = o.name();
				if (mobNames.containsValue(n)) n = n + " " + (mobTeam.size() + 1);
				addMob(m, n);
			}
			placeCrew();
			if (mobTeam.size() > 1) {
				broadcastChat(Text.translatable("rapbattle.battle.mob_called_crew", opp.name(), String.join(", ", mobTeamNames())).formatted(Formatting.GOLD));
			}
		}
		crowd = new Crowd(this);
		crowd.gather();
		// Tamed wolves and cats of the team back their owners up from the side: +1 Respect each, two at most.
		allyBonus = 0;
		for (UUID u : players) {
			ServerPlayerEntity p = player(u);
			if (p == null) continue;
			for (net.minecraft.entity.passive.TameableEntity t : world.getEntitiesByClass(net.minecraft.entity.passive.TameableEntity.class,
				p.getBoundingBox().expand(12), t -> t.isTamed() && t.isOwner(p) && t.isAlive())) {
				if (allyBonus < 2) {
					allyBonus++;
					broadcastChat(Text.translatable("rapbattle.battle.backup_vocals", t.getName().getString(), p.getName().getString())
						.formatted(Formatting.AQUA));
				}
			}
		}
		// Style bonus (+2 for full gang gear, matching dyed leather, or trim; not raw armor material) & overdressed check.
		double sum = 0;
		boolean anyStylish = false;
		for (UUID u : players) {
			ServerPlayerEntity p = player(u);
			if (p != null) {
				Outfit.Score os = Outfit.of(p, lang);
				sum += os.value();
				if (os.stylish() || Gangs.isOwn(p, opp.profile())) anyStylish = true;
			}
		}
		hasStyleBonus = anyStylish;
		outfitBonus = hasStyleBonus ? 2 : 0;
		double avg = sum / Math.max(1, players.size());
		overdressed = !boss && Math.floor(avg) >= rank.level() + 2;
	}

	private List<String> mobTeamNames() {
		List<String> out = new ArrayList<>();
		for (MobEntity m : mobTeam) out.add(mobNames.get(m));
		return out;
	}

	// ------------------------------------------------------------------ audio

	public void onAudio(ServerPlayerEntity p, Payloads.AudioC2S chunk) {
		UUID u = p.getUuid();
		if (!hasPlayer(u) || transcripts.containsKey(u)) return;
		if (phase != Phase.TIMELINE && phase != Phase.JUDGING) return;
		int expectedSeq = audioSeq.getOrDefault(u, 0);
		if (chunk.seq() != expectedSeq) return;
		audioSeq.put(u, expectedSeq + 1);
		ByteArrayOutputStream buf = audio.computeIfAbsent(u, k -> new ByteArrayOutputStream());
		int maxBytes = view != null
			? (int) Math.min(4 * 1024 * 1024L, 44L + (view.totalBeats * Math.max(200L, beatMs) + 5000L) * 32L)
			: 2 * 1024 * 1024;
		if (buf.size() + chunk.data().length > maxBytes) return;
		buf.writeBytes(chunk.data());
		if (chunk.last()) {
			byte[] wav = buf.toByteArray();
			audio.remove(u);
			audioSeq.remove(u);
			recordingStart.put(u, chunk.startMs());
			List<String> hints = rhymeWords.getOrDefault(u, List.of());
			final int reqRound = this.round;
			transcripts.put(u, CompletableFuture.supplyAsync(() -> {
				if (finished || this.round != reqRound) return "";
				Transcriber.Outcome out = Transcriber.transcribeTimedChecked(wav, hints, lang);
				if (finished || this.round != reqRound) return "";
				if (out.serviceFailure()) {
					sttMissingPlayers.add(u);
					return "";
				}
				if (out.result() != null) {
					timed.put(u, out.result());
					return out.result().text();
				}
				return "";
			}, POOL));
		}
	}

	// ------------------------------------------------------------------ events reported by clients

	/** Creeper: a player stayed silent for a whole bar of their own. The fuse burns out. */
	public void onSilentBar(ServerPlayerEntity p) {
		if (phase != Phase.TIMELINE || view == null || !hasPlayer(p.getUuid()) || !(mob instanceof net.minecraft.entity.mob.CreeperEntity)) return;
		String who = names.getOrDefault(p.getUuid(), p.getName().getString());
		int curBeat = (int) Math.floorDiv(dev.rapbattle.Clock.now() - beatZero, Math.max(1L, beatMs));
		List<BattleView.Row> myRows = playerRows(who);
		int matchIdx = -1;
		for (int i = 0; i < myRows.size(); i++) {
			BattleView.Row r = myRows.get(i);
			if (curBeat >= r.startBeat + 3 && curBeat <= r.startBeat + 5) {
				matchIdx = i;
				break;
			}
		}
		if (matchIdx < 0) return;
		List<Boolean> louds = playerBarLoud.getOrDefault(who, List.of());
		if (matchIdx < louds.size() && Boolean.TRUE.equals(louds.get(matchIdx))) return;
		broadcastChat(Text.translatable("rapbattle.battle.creeper_silent_chat", p.getName().getString()).formatted(Formatting.RED));
		forfeit(Lang.t(lang, "rapbattle.battle.creeper_silent_comment"));
		SoreLosers.blast(mob, 1500);
	}

	/** Warden: noise from a player's mic while he was reading. */
	public void onNoise(ServerPlayerEntity p, int count) {
		if (phase != Phase.TIMELINE || view == null || !hasPlayer(p.getUuid()) || !opp.profile().id().equals("warden")) return;
		int curBeat = (int) Math.floorDiv(dev.rapbattle.Clock.now() - beatZero, Math.max(1L, beatMs));
		if (curBeat < view.mobStartBeat || curBeat >= view.mobEndBeat) return;
		UUID u = p.getUuid();
		int prev = playerNoise.getOrDefault(u, 0);
		int add = Math.max(0, Math.min(10 - prev, count));
		if (add > 0) {
			playerNoise.put(u, prev + add);
			noise += add;
		}
	}

	/** Enderman: looking him in the eyes during his verse shuffles this player's upcoming rhymes on the server. */
	public void onGaze(ServerPlayerEntity p) {
		UUID u = p.getUuid();
		if (phase != Phase.TIMELINE || view == null || !hasPlayer(u) || !opp.profile().id().equals("enderman")) return;
		if (Trophies.equippedPerk(p) == Trophies.TrophyPerk.WARDEN && wardenTriggered.add(u)) return;
		if (!gazedPlayers.add(u)) return;
		String name = names.get(u);
		int curBeat = (int) Math.floorDiv(dev.rapbattle.Clock.now() - beatZero, Math.max(1, beatMs));
		List<BattleView.Row> upcoming = new ArrayList<>();
		for (BattleView.Row r : view.rows) {
			if (!r.mob && name.equals(r.reader) && r.startBeat > curBeat) upcoming.add(r);
		}
		if (upcoming.size() < 2) return;
		List<String> pool = new ArrayList<>();
		for (BattleView.Row r : upcoming) pool.add(r.word);
		Collections.shuffle(pool, rnd);
		boolean changed = false;
		for (int i = 0; i < upcoming.size(); i++) if (!pool.get(i).equals(upcoming.get(i).word)) changed = true;
		if (!changed) Collections.rotate(pool, 1);
		for (int i = 0; i < upcoming.size(); i++) upcoming.get(i).word = pool.get(i);
		List<String> updatedAll = new ArrayList<>();
		for (BattleView.Row r : view.rows) {
			if (!r.mob && name.equals(r.reader)) updatedAll.add(r.word);
		}
		rhymeWords.put(u, updatedAll);
		broadcastState();
	}

	/** Rerolls the current or next upcoming non-freestyle rhyme word for {@code p} once per round (R key). */
	public void onReroll(ServerPlayerEntity p) {
		UUID u = p.getUuid();
		if (phase != Phase.TIMELINE || view == null || !hasPlayer(u) || rerolledPlayers.contains(u)) return;
		String name = names.get(u);
		int curBeat = (int) Math.floorDiv(dev.rapbattle.Clock.now() - beatZero, Math.max(1, beatMs));
		BattleView.Row targetRow = null;
		for (BattleView.Row r : view.rows) {
			if (!r.mob && name.equals(r.reader) && !r.freestyle && r.startBeat + 2 >= curBeat) {
				targetRow = r;
				break;
			}
		}
		if (targetRow == null) return;
		List<String> used = new ArrayList<>();
		for (BattleView.Row r : view.rows) if (!r.mob && name.equals(r.reader) && !r.word.isEmpty()) used.add(r.word.toLowerCase(Locale.ROOT));
		String replacement = RhymeBank.spareWord(targetRow.word, used, rnd, lang);
		if (replacement == null || replacement.isEmpty()) return;
		targetRow.word = replacement;
		List<String> updatedAll = new ArrayList<>();
		for (BattleView.Row r : view.rows) if (!r.mob && name.equals(r.reader)) updatedAll.add(r.word);
		rhymeWords.put(u, updatedAll);
		rerolledPlayers.add(u);
		world.playSound(null, p.getBlockPos(), net.minecraft.sound.SoundEvents.BLOCK_GRINDSTONE_USE,
			net.minecraft.sound.SoundCategory.PLAYERS, 0.9f, 1.85f);
		broadcastState();
	}

	private List<BattleView.Row> playerRows(String who) {
		if (view == null || who == null) return List.of();
		List<BattleView.Row> out = new ArrayList<>();
		for (BattleView.Row r : view.rows) {
			if (!r.mob && who.equals(r.reader)) out.add(r);
		}
		return out;
	}

	/** One rhyme slot of a player: said loud on the beat, peak offset from beat 4 (ms), or silence. */
	public void onBar(ServerPlayerEntity p, boolean loud) {
		onBar(p, loud, -1, !loud);
	}

	public void onBar(ServerPlayerEntity p, boolean loud, int deltaMs, boolean silentBar) {
		long now = dev.rapbattle.Clock.now();
		boolean inWindow = phase == Phase.TIMELINE || (phase == Phase.JUDGING && now - judgingStart <= 500L);
		if (!hasPlayer(p.getUuid()) || !inWindow || view == null) return;
		String who = names.getOrDefault(p.getUuid(), p.getName().getString());
		List<BattleView.Row> myRows = playerRows(who);
		List<Boolean> loudList = playerBarLoud.computeIfAbsent(who, k -> new ArrayList<>());
		int barIdx = loudList.size();
		if (barIdx >= myRows.size()) return;
		int curBeat = (int) Math.floorDiv(now - beatZero, Math.max(1L, beatMs));
		if (curBeat < myRows.get(barIdx).startBeat + 2) return;
		int clampedDelta = deltaMs < 0 ? -1 : Math.min(deltaMs, (int) Math.max(1L, beatMs));
		loudList.add(loud);
		playerBarDeltaMs.computeIfAbsent(who, k -> new ArrayList<>()).add(clampedDelta);
		playerBarSilent.computeIfAbsent(who, k -> new ArrayList<>()).add(silentBar);
		// Spider web_pair: hitting bar 0/2/4 loudly unlocks the webbed 2nd bar of the pair.
		if (loud && view != null && (opp.profile().id().equals("spider") || opp.profile().id().equals("cave_spider"))) {
			int curIdx = 0;
			BattleView.Row nextToUnlock = null;
			for (BattleView.Row r : view.rows) {
				if (!r.mob && who.equals(r.reader)) {
					if (curIdx == barIdx + 1 && "web_pair".equals(r.effect)) {
						nextToUnlock = r;
						break;
					}
					curIdx++;
				}
			}
			if (nextToUnlock != null) {
				nextToUnlock.effect = null;
				broadcastState();
			}
		}
		if (loud) barsHit++;
		else barsMissed++;
		linesTotal++;
		if (loud) {
			combo++;
			bestCombo = Math.max(bestCombo, combo);
			linesOnBeat++;
			if (combo >= 3) DamagePop.spawn(world, p, "x" + combo, 0xFFD84A);
		} else {
			combo = 0;
			DamagePop.spawn(world, p, Lang.t(lang, "rapbattle.battle.stumbled_pop"), 0xFF6060);
		}
		Crowd.react(this, loud ? Crowd.Moment.PLAYER_HIT : Crowd.Moment.PLAYER_MISS);
		broadcastState(); // the crowd meter moved
	}

	private static final int WIN_VERBS = 12, TEAM_WIN_VERBS = 9, ESCAPED_LINES = 6;

	private void rememberPlayers(boolean mobWon) {
		if (result == null) return;
		for (BattleView.PlayerScore ps : result.players) MobRecord.remember(mob, ps.name, mobWon, quote(ps.transcript));
	}

	/** A rematch: the mob recognises the player and says so. */
	private void greetRival(ServerPlayerEntity p) {
		MobRecord.Rival rv = MobRecord.rival(mob, p.getName().getString());
		if (rv == null) return;
		String n = p.getName().getString();
		List<String> lines;
		if (Lang.isRu(lang)) {
			lines = rv.mobWon()
				? List.of("Опять ты, " + n + "? Мало тебе прошлого раза?", n + ", я ещё помню, как ты мямлил. Повторим?",
					"О, " + n + " вернулся за добавкой!")
				: List.of("Реванш, " + n + ". Сегодня ты ляжешь.", n + "… Я ждал тебя. Теперь моя очередь.",
					"Тебе тогда повезло, " + n + ". Второй раз не прокатит.");
		} else {
			lines = rv.mobWon()
				? List.of("You again, " + n + "? Didn't get enough last time?", n + ", I still remember you mumbling. Run it back?",
					"Oh, " + n + " came back for seconds!")
				: List.of("Rematch, " + n + ". Tonight you're going down.", n + "… I've been waiting. My turn now.",
					"You got lucky last time, " + n + ". Not happening twice.");
		}
		Speech.bubble(mob, lines.get(rnd.nextInt(lines.size())), rv.mobWon() ? 0xFFD27F : 0xFF8080, 3500);
	}

	/** What the mob remembers about these players, for the verse prompt (empty if they never met). */
	private String rivalryText() {
		StringBuilder sb = new StringBuilder();
		for (UUID u : players) {
			MobRecord.Rival rv = MobRecord.rival(mob, names.get(u));
			if (rv == null) continue;
			if (Lang.isRu(lang)) {
				sb.append("Моб уже баттлился с ").append(rv.player()).append(" и ").append(rv.mobWon() ? "победил его" : "проиграл ему").append('.');
				if (!rv.line().isBlank()) sb.append(" Тогда игрок читал: «").append(rv.line()).append("».");
				sb.append(rv.mobWon() ? " Напомни ему тот позор и процитируй его слабую строку." : " Это реванш: моб злой, хочет отыграться, процитируй строку игрока и переверни её против него.");
			} else {
				sb.append("The mob has battled ").append(rv.player()).append(" before and ").append(rv.mobWon() ? "beat them" : "lost to them").append('.');
				if (!rv.line().isBlank()) sb.append(" Back then the player rapped: \"").append(rv.line()).append("\".");
				sb.append(rv.mobWon() ? " Remind them of that embarrassment and quote their weak line." : " This is a rematch: the mob is mad and wants payback, quote the player's line and flip it on them.");
			}
			sb.append(' ');
		}
		return sb.toString().trim();
	}

	/** A short quote of a player's verse to remember: the last words they said. */
	private static String quote(String transcript) {
		if (transcript == null || transcript.isBlank() || transcript.startsWith("(")) return "";
		String[] w = transcript.trim().split("\\s+");
		int from = Math.max(0, w.length - 9);
		return String.join(" ", java.util.Arrays.copyOfRange(w, from, w.length));
	}

	private static int count(int[] a, int v) {
		int n = 0;
		for (int x : a) if (x == v) n++;
		return n;
	}

	/**
	 * For each of this player's lines this round: did the rhyme word land on its beat (the 4th of the line)?
	 * 2 on the beat (within 0.3 of a beat), 1 close (within 0.7), 0 off or not said. Null without word timestamps.
	 */
	private int[] beatTiming(UUID u) {
		return view == null ? null : beatTiming(timed.get(u), recordingStart.get(u), view.rows, names.get(u), beatMs, lang);
	}

	static int[] beatTiming(dev.rapbattle.ai.Transcriber.Result r, Integer start, List<BattleView.Row> rows, String name, long beatMs, String lang) {
		if (r == null || start == null || r.words().isEmpty()) return null;
		List<BattleView.Row> mine = new ArrayList<>();
		for (BattleView.Row row : rows) if (!row.mob && name.equals(row.reader)) mine.add(row);
		if (mine.isEmpty()) return null;
		boolean ru = Lang.isRu(lang);
		int[] out = new int[mine.size()];
		for (int i = 0; i < mine.size(); i++) {
			BattleView.Row row = mine.get(i);
			String target = ru ? stemRu(Fallback.lastWord(row.word)) : lastWordEn(row.word);
			double expected = (row.startBeat + 3) * (double) beatMs;
			double best = Double.MAX_VALUE;
			for (dev.rapbattle.ai.Transcriber.Word w : r.words()) {
				if (ru) {
					String ws = stemRu(w.text());
					if (!ws.startsWith(target) && !target.startsWith(ws)) continue;
					if (ws.length() < 3) continue;
				} else {
					if (!matchesEn(w.text(), target)) continue;
				}
				double at = start + w.start() * 1000;
				best = Math.min(best, Math.abs(at - expected) / beatMs);
			}
			out[i] = best <= 0.3 ? 2 : best <= 0.7 ? 1 : 0;
		}
		return out;
	}

	private static String stemRu(String word) {
		String w = word.toLowerCase(java.util.Locale.ROOT).replace('ё', 'е').replaceAll("[^\\p{L}]", "");
		return w.length() > 4 ? w.substring(0, w.length() - 2) : w;
	}

	private static String lastWordEn(String phrase) {
		String[] parts = phrase.trim().split("\\s+");
		String last = parts.length == 0 ? "" : parts[parts.length - 1];
		return last.toLowerCase(java.util.Locale.ROOT).replaceAll("[^\\p{L}]", "");
	}

	private static boolean matchesEn(String spoken, String target) {
		if (target.isEmpty()) return false;
		String w = spoken.toLowerCase(java.util.Locale.ROOT).replaceAll("[^\\p{L}]", "");
		if (w.length() < 2) return false;
		for (String cand : Fallback.homophonesEn(target)) {
			if (w.equals(cand)) return true;
			if (w.equals(cand + "s") || w.equals(cand + "es") || w.equals(cand + "ed") || w.equals(cand + "d")) return true;
			if (cand.equals(w + "s") || cand.equals(w + "es") || cand.equals(w + "ed") || cand.equals(w + "d")) return true;
		}
		return false;
	}

	/** A beat close to the rank's tempo (bosses get the boss track), random among the close ones. */
	private BeatTracks.Track pickTrack(double bpm, boolean bossFight) {
		if (bossFight) {
			BeatTracks.Track b = BeatTracks.byId("boss");
			if (b != null) return b;
		}
		List<BeatTracks.Track> near = new ArrayList<>();
		for (BeatTracks.Track t : BeatTracks.ALL) if (!t.id().equals("boss") && Math.abs(t.bpm() - bpm) <= 9) near.add(t);
		if (near.isEmpty()) {
			BeatTracks.Track best = null;
			for (BeatTracks.Track t : BeatTracks.ALL) if (best == null || Math.abs(t.bpm() - bpm) < Math.abs(best.bpm() - bpm)) best = t;
			return best;
		}
		return near.get(new java.util.Random().nextInt(near.size()));
	}

	private void liveHit(boolean toMob, int d, String who) {
		if (toMob) {
			respectMob = Math.max(1, respectMob - d);
			roundToMob += d;
			dealtTotal += d;
			if (who != null) {
				roundDealt.merge(who, d, Integer::sum);
				battleDealt.merge(who, d, Integer::sum);
			}
		} else {
			respectTeam = Math.max(1, respectTeam - d);
			roundToTeam += d;
		}
	}

	/** A participant threw a punch during the battle: street law broken, the mob wins and its people come for him. */
	/** Breaking the rules ends the battle in the mob's favour. */
	public void forfeit(String comment) {
		if (finished || view == null) {
			abort(Text.literal(comment));
			return;
		}
		clearGlow();
		result = new BattleView.Result();
		result.teamBefore = respectTeam;
		result.mobBefore = respectMob;
		result.respectTeam = respectTeam;
		result.respectMob = respectMob;
		result.respectMax = respectMax;
		result.playersWin = false;
		result.finalResult = true;
		result.comment = comment;
		result.mobLabel = opp.profile().species(lang);
		result.teamLabel = String.join(" + ", namesList());
		for (UUID u : players) result.players.add(new BattleView.PlayerScore(names.get(u), 0, Lang.t(lang, "rapbattle.battle.forfeit_status")));
		winsMob = winsNeeded;
		view.result = result;
		setPhase(Phase.RESULT, dev.rapbattle.Clock.now());
	}

	// ------------------------------------------------------------------ tick

	/**
	 * Nobody near the stage goes for the battlers until the battle is over: a zombie that walks up (or a villager the
	 * crowd turned into a zombie villager) drops its target instead of swinging at someone who is busy rapping.
	 */
	private int peaceTick = 0;

	private void keepThePeace() {
		var box = mob.getBoundingBox().expand(24, 8, 24);
		for (MobEntity m : world.getEntitiesByClass(MobEntity.class, box, m -> m.getTarget() instanceof ServerPlayerEntity)) {
			if (hasPlayer(m.getTarget().getUuid())) {
				m.setTarget(null);
				m.getNavigation().stop();
			}
		}
	}

	/** A crowd member of this battle (they neither hurt nor get hurt while the show is on). */
	boolean inCrowd(Entity e) {
		return crowd != null && e instanceof MobEntity m && crowd.members.containsKey(m);
	}

	public void tick() {
		if (finished) return;
		long now = dev.rapbattle.Clock.now();

		if (!mob.isAlive() || mob.isRemoved()) {
			abort(Text.translatable("rapbattle.battle.abort_mob_gone"));
			return;
		}
		mobTeam.removeIf(m -> m != mob && (!m.isAlive() || m.isRemoved()));
		for (MobEntity m : mobTeam) if (m.isOnFire()) m.extinguish();
		if (crowd != null) for (MobEntity m : crowd.members.keySet()) if (m.isOnFire()) m.extinguish();
		if (mob instanceof net.minecraft.entity.boss.dragon.EnderDragonEntity dragon) {
			DragonBattle.holdHover(dragon);
		}
		if ((peaceTick++ % 5) == 0) keepThePeace();
		for (UUID u : new ArrayList<>(players)) {
			if (leftPlayers.contains(u)) continue;
			ServerPlayerEntity p = player(u);
			if (p == null) continue;
			if (p.getEntityWorld() != world) {
				onPlayerLeft(u);
				if (finished) return;
				continue;
			}
			if (!p.isAlive()) continue;
			if (p.hasVehicle()) p.stopRiding();
			if (p.getFireTicks() > 0) p.extinguish();
			if (p.getAir() < p.getMaxAir()) p.setAir(p.getMaxAir());
			// Players stand still horizontally during the battle (gravity still works) and can look around.
			// During resolving, a player who touches lava/fire is freed from the anchor so they can walk out.
			Vec3d a = anchors.get(u);
			if (a != null && !anchorReleased.contains(u)) {
				if (p.getY() - a.y > 2.0) {
					p.networkHandler.requestTeleport(a.x, a.y, a.z, p.getYaw(), p.getPitch());
					continue;
				}
				double dx = p.getX() - a.x, dz = p.getZ() - a.z;
				double d2 = dx * dx + dz * dz;
				if (d2 > 0.3 * 0.3) {
					int pt = pushTicks.getOrDefault(u, 0) + 1;
					if (pt > 4 && (d2 > 0.8 * 0.8 || p.isInsideWall())) {
						abortNoPenalty(p, Text.translatable("rapbattle.battle.abort_env"));
						if (finished) return;
						continue;
					}
					pushTicks.put(u, pt);
					p.networkHandler.requestTeleport(a.x, p.getY(), a.z, p.getYaw(), p.getPitch());
				} else {
					pushTicks.put(u, 0);
				}
			}
		}
		faceTeam();
		if (crowd != null) crowd.tick(currentReader);

		switch (phase) {
			case LOBBY -> {
				if (now >= lobbyEnd) {
					closeLobby();
					startPrep(now);
				} else if ((now - phaseStart) / 1000 != (now - phaseStart - 50) / 1000) {
					broadcastState(); // refresh the lobby timer once a second
				}
			}
			case PREP -> {
				if (contentFuture.isDone() || now - phaseStart > PREP_WAIT_MS) {
					Llm.Content c = contentFuture.getNow(null);
					buildTimeline(c, now + 300);
					setPhase(Phase.TIMELINE, now);
				}
			}
			case TIMELINE -> {
				updateGlow(now);
				firePunches(now);
				if (now >= beatZero + (long) view.totalBeats * beatMs + 300) {
					clearGlow();
					judgingStart = now;
					setPhase(Phase.JUDGING, now);
					Crowd.react(this, Crowd.Moment.JUDGING);
				}
			}
			case JUDGING -> tickJudging(now);
			case ROUND_RESULT -> {
				long readMs = ROUND_RESULT_MS + (result == null ? 0 : Math.min(6000, result.comment.length() * 40L) + result.hits.size() * 400L);
				if (now - phaseStart >= readMs) {
					round++;
					// Each round of a boss fight starts fresh at 100 Respect on both sides.
					respectTeam = respectMax;
					respectMob = respectMax;
					beginRoundContent(now);
				}
			}
			case RESULT -> {
				// Long enough to read the whole judge comment, then the aftermath (drops, tantrums) starts.
				long readMs = RESULT_MS + (result == null ? 0 : Math.min(7000, result.comment.length() * 45L) + result.hits.size() * 400L);
				if (now - phaseStart >= readMs) finish();
			}
		}
	}

	private void faceTeam() {
		ServerPlayerEntity c = challenger();
		if (c == null) return;
		for (MobEntity m : mobTeam) {
			double dx = c.getX() - m.getX(), dz = c.getZ() - m.getZ();
			float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
			m.setYaw(yaw);
			m.setHeadYaw(yaw);
			m.setBodyYaw(yaw);
		}
	}

	/** The one who reads right now glows, so bystanders see whose turn it is. */
	private void updateGlow(long now) {
		int beat = (int) Math.floorDiv(now - beatZero, beatMs);
		Entity reader = null;
		for (BattleView.Row r : view.rows) {
			if (beat >= r.startBeat && beat < r.startBeat + 4) {
				reader = r.mob ? world.getEntityById(r.readerEntityId) : playerByName(r.reader);
				break;
			}
		}
		for (Entity e : new ArrayList<>(glowing)) {
			if (e != reader) {
				e.setGlowing(false);
				glowing.remove(e);
			}
		}
		currentReader = reader;
		if (reader != null && !glowing.contains(reader)) {
			reader.setGlowing(true);
			glowing.add(reader);
		}
	}

	private static final List<String> CREW_ADLIBS_RU = List.of("Факты!", "Туда его!", "Жёстко!", "Слышал?!", "Ооо!", "По делу!");
	private static final List<String> CREW_ADLIBS_EN = List.of("Facts!", "Cook him!", "Cold!", "You hear that?!", "Ooh!", "Bars!");

	/** The crowd goes "ооо" right on the rhyme of a mob punchline; crewmates throw backup ad-libs. */
	private void firePunches(long now) {
		int beat = (int) Math.floorDiv(now - beatZero, beatMs);
		for (int i = 0; i < view.rows.size(); i++) {
			BattleView.Row r = view.rows.get(i);
			if (r.punch && beat == r.startBeat + 3 && punchesFired.add(i)) {
				Crowd.react(this, Crowd.Moment.MOB_PUNCH);
				if (mobTeam.size() > 1) {
					List<String> pool = Lang.isRu(lang) ? CREW_ADLIBS_RU : CREW_ADLIBS_EN;
					for (MobEntity m : mobTeam) {
						if (m.isAlive() && m.getId() != r.readerEntityId) {
							Speech.bubble(m, pool.get(rnd.nextInt(pool.size())), 0xFFD27F, 1800);
						}
					}
				}
			}
			if (r.mob && beat == r.startBeat + 3 && mobLinesFired.add(i)) {
				int d = i < mobLiveDamages.length ? mobLiveDamages[i] : 0;
				liveHit(false, d, null);
				for (UUID u : activePlayers()) DamagePop.spawn(world, player(u), "-" + d + (r.punch ? " " + Lang.t(lang, "rapbattle.battle.punch_pop") : ""), 0xFF6060);
				broadcastState();
			}
		}
	}

	private void clearGlow() {
		currentReader = null;
		for (Entity e : glowing) e.setGlowing(false);
		glowing.clear();
	}

	private ServerPlayerEntity playerByName(String name) {
		for (UUID u : players) if (names.get(u).equals(name)) return player(u);
		return null;
	}

	// ------------------------------------------------------------------ rounds

	private void startPrep(long now) {
		beginRoundContent(now);
	}

	private void beginRoundContent(long now) {
		anchorReleased.clear();
		for (UUID u : players) {
			if (leftPlayers.contains(u)) continue;
			ServerPlayerEntity p = player(u);
			Vec3d a = anchors.get(u);
			if (p != null && p.networkHandler != null && a != null && p.squaredDistanceTo(a) > 0.09) {
				p.networkHandler.requestTeleport(a.x, a.y, a.z, p.getYaw(), p.getPitch());
			}
		}
		sttMissingPlayers.clear();
		punchesFired.clear();
		mobLinesFired.clear();
		timed.clear();
		recordingStart.clear();
		roundToMob = 0;
		roundToTeam = 0;
		roundDealt.clear();
		barsHit = 0;
		barsMissed = 0;
		playerBarLoud.clear();
		playerBarDeltaMs.clear();
		playerBarSilent.clear();
		gazedPlayers.clear();
		rerolledPlayers.clear();
		wardenTriggered.clear();
		playerNoise.clear();
		targets = List.of();
		pendingVerses = List.of();
		transcripts.clear();
		audio.clear();
		audioSeq.clear();
		verdictFuture = null;
		result = null;
		List<Llm.Rapper> rappers = new ArrayList<>();
		for (MobEntity m : mobTeam) {
			MobProfiles.Profile p = MobProfiles.of(m.getType());
			rappers.add(new Llm.Rapper(mobNames.get(m), p.species(lang), p.adlib(lang), p.style(lang)));
		}
		List<UUID> active = activePlayers();
		// Usually the rank's length, sometimes a longer verse (two more lines), never shorter.
		int lineCount = rank.mobLines + Math.max(0, active.size() - 1) + (rnd.nextInt(3) == 0 ? 2 : 0);
		List<String> opponents = namesList();
		int groups = 0;
		for (int i = 0; i < active.size(); i++) groups += (playerLines() + 3) / 4;
		int groupsNeeded = groups;
		String prev = history.toString();
		String rivalry = rivalryText();
		int r = round;
		String topicText = topicPromptText();
		Stats stats = Stats.of(world.getServer());
		int maxTargets = 2;
		for (UUID u : active) maxTargets = Math.max(maxTargets, stats.player(u).mcStyle().targetCount());
		int targetLimit = maxTargets;
		contentFuture = CompletableFuture.supplyAsync(() -> {
			Llm.Content c = Llm.roundContent(rappers, rank.label(lang), opp.mood(), opp.profile().language(lang), lineCount,
				opponents, groupsNeeded, rank.rhymeDifficulty, r, prev, rivalry, topicText, targetLimit, lang);
			if (c == null) {
				List<String> speakers = new ArrayList<>();
				for (Llm.Rapper rp : rappers) speakers.add(rp.name());
				List<Llm.MobLine> fbLines = Fallback.mobVerse(speakers, opponents, lineCount, rnd, lang);
				c = new Llm.Content(fbLines, List.of(), Fallback.extractTargets(fbLines, lang, targetLimit));
			}
			return c;
		}, POOL);
		setPhase(Phase.PREP, now);
	}

	private int avgOutfitLevel() {
		double sum = 0;
		for (UUID u : players) {
			ServerPlayerEntity p = player(u);
			if (p != null) sum += Outfit.of(p).value();
		}
		return (int) Math.floor(sum / Math.max(1, players.size()));
	}

	private int playerLines() {
		return Math.max(1, rank.linesPerPlayer(Math.max(1, activePlayers().size())));
	}

	private void buildTimeline(Llm.Content content, long zero) {
		beatZero = zero;
		List<UUID> active = activePlayers();
		List<String> speakers = mobTeamNames();
		List<Llm.MobLine> lines = content != null ? content.lines()
			: Fallback.mobVerse(speakers, namesList(), rank.mobLines, rnd, lang);
		mobLines = lines;
		Stats stats = Stats.of(world.getServer());
		int maxTargets = 2;
		for (UUID u : active) maxTargets = Math.max(maxTargets, stats.player(u).mcStyle().targetCount());
		targets = (content != null && !content.targets().isEmpty()) ? content.targets() : Fallback.extractTargets(lines, lang, maxTargets);
		BattleView v = baseView();
		v.targets = new ArrayList<>(targets);
		int beat = 4; // 4-3-2-1 countdown
		v.mobStartBeat = beat;
		for (int i = 0; i < lines.size(); i++) {
			Llm.MobLine ml = lines.get(i);
			MobEntity speaker = speakerFor(ml.speaker(), i);
			BattleView.Row r = new BattleView.Row();
			String[] tokens = ml.text().trim().split("\\s+");
			r.word = tokens[tokens.length - 1].replaceAll("[\\p{Punct}«»…—]+$", "");
			r.cols = splitCols(Arrays.copyOf(tokens, tokens.length - 1));
			r.mob = true;
			r.reader = mobNames.get(speaker);
			r.readerEntityId = speaker.getId();
			r.color = MOB_COLORS[mobTeam.indexOf(speaker) % MOB_COLORS.length];
			r.fullLine = ml.text();
			r.punch = ml.punch();
			r.startBeat = beat;
			beat += 4;
			v.rows.add(r);
			history.append(r.reader).append(": ").append(ml.text()).append('\n');
		}
		v.mobEndBeat = beat;
		List<Boolean> mobPunches = new ArrayList<>(lines.size());
		for (Llm.MobLine ml : lines) mobPunches.add(ml.punch());
		mobLiveDamages = Scoring.mobLiveDamages(rank.liveBudget(), mobPunches);

		// Rhymes: generated ones for harder ranks, the curated bank for easy ones and as a top-up.
		int perPlayer = playerLines();
		int blocks = (perPlayer + 3) / 4;
		List<List<String>> groups = new ArrayList<>();
		if (content != null) groups.addAll(content.rhymes());
		Collections.shuffle(groups, rnd);
		if (groups.size() < blocks * active.size()) groups.addAll(RhymeBank.pickBlocks(blocks * active.size() - groups.size(), rnd, lang));
		Map<UUID, List<String>> words = new LinkedHashMap<>();
		int g = 0;
		for (UUID u : active) {
			List<String> w = new ArrayList<>();
			for (int b = 0; b < blocks; b++) w.addAll(groups.get(g++));
			List<String> sliced = new ArrayList<>(w.subList(0, perPlayer));
			if (rank.level() >= 4) {
				for (int i = 3; i < sliced.size(); i += 4) sliced.set(i, "");
			}
			words.put(u, sliced);
		}
		rhymeWords.clear();
		rhymeWords.putAll(words);

		// Pig: a carrot in a player's hand distracts it, one of its lines is just chewing.
		if (opp.profile().id().equals("pig") && players.stream().map(this::player).anyMatch(pl -> pl != null
			&& pl.getMainHandStack().isOf(net.minecraft.item.Items.CARROT))) {
			BattleView.Row chew = v.rows.get(rnd.nextInt(v.rows.size()));
			if (Lang.isRu(lang)) {
				chew.fullLine = "*хрум-хрум* …морковка…";
				chew.cols = new String[]{"*хрум-хрум*", "…", "…"};
				chew.word = "хрум";
			} else {
				chew.fullLine = "*crunch-crunch* …carrot…";
				chew.cols = new String[]{"*crunch-crunch*", "…", "…"};
				chew.word = "crunch";
			}
		}
		int firstPlayerRow = v.rows.size();
		int colorIdx = rnd.nextInt(BLOCK_COLORS.length);
		boolean pingPong = active.size() > 1 && rank.level() >= 4;
		if (pingPong) {
			// Friends alternate line by line after one shared get-ready bar.
			int leadIn = beat;
			beat += rank.leadInBeats;
			Map<UUID, BattleView.Segment> segs = new LinkedHashMap<>();
			for (int i = 0; i < perPlayer; i++) {
				for (int pi = 0; pi < active.size(); pi++) {
					UUID u = active.get(pi);
					BattleView.Row r = playerRow(u, i, perPlayer, words.get(u).get(i), BLOCK_COLORS[(colorIdx + pi) % BLOCK_COLORS.length], beat);
					v.rows.add(r);
					BattleView.Segment s = segs.computeIfAbsent(u, k -> {
						BattleView.Segment ns = new BattleView.Segment();
						ns.reader = names.get(k);
						ns.leadInBeat = leadIn;
						return ns;
					});
					if (s.startBeat == 0) s.startBeat = beat;
					s.endBeat = beat + 4;
					beat += 4;
				}
			}
			v.segments.addAll(segs.values());
		} else {
			for (UUID u : active) {
				BattleView.Segment seg = new BattleView.Segment();
				seg.reader = names.get(u);
				seg.leadInBeat = beat;
				beat += rank.leadInBeats;
				seg.startBeat = beat;
				for (int i = 0; i < perPlayer; i++) {
					v.rows.add(playerRow(u, i, perPlayer, words.get(u).get(i), BLOCK_COLORS[(colorIdx + i / 4) % BLOCK_COLORS.length], beat));
					beat += 4;
				}
				colorIdx += blocks;
				seg.endBeat = beat;
				v.segments.add(seg);
			}
		}
		v.totalBeats = beat;
		List<List<BattleView.Row>> byPlayer = new ArrayList<>();
		for (UUID u : players) {
			List<BattleView.Row> own = new ArrayList<>();
			for (int i = firstPlayerRow; i < v.rows.size(); i++) if (names.get(u).equals(v.rows.get(i).reader)) own.add(v.rows.get(i));
			byPlayer.add(own);
		}
		MobTricks.apply(opp.profile().id(), byPlayer, rnd);
		for (int i = 0; i < players.size(); i++) {
			UUID u = players.get(i);
			if (Trophies.equippedPerk(player(u)) == Trophies.TrophyPerk.WARDEN) {
				boolean blocked = false;
				for (BattleView.Row r : byPlayer.get(i)) {
					if (r.effect != null) {
						r.effect = null;
						blocked = true;
					}
				}
				if (blocked) wardenTriggered.add(u);
			}
		}
		view = v;
	}

	private BattleView.Row playerRow(UUID u, int i, int total, String word, int color, int beat) {
		BattleView.Row r = new BattleView.Row();
		r.freestyle = rank.level() >= 4 && (i + 1) % 4 == 0;
		r.word = r.freestyle ? "" : word;
		r.color = color;
		r.reader = names.get(u);
		ServerPlayerEntity p = player(u);
		r.readerEntityId = p == null ? -1 : p.getId();
		r.line = i + 1;
		r.lines = total;
		r.startBeat = beat;
		return r;
	}

	private MobEntity speakerFor(String name, int index) {
		for (MobEntity m : mobTeam) if (mobNames.get(m).equalsIgnoreCase(name)) return m;
		return mobTeam.get(index % mobTeam.size());
	}

	/** Spreads the words before the rhyme over the three dark columns of the table. */
	private static String[] splitCols(String[] words) {
		String[] cols = {"", "", ""};
		if (words.length == 0) return cols;
		int per = (int) Math.ceil(words.length / 3.0);
		for (int c = 0; c < 3; c++) {
			int from = c * per, to = Math.min(words.length, from + per);
			if (from < to) cols[c] = String.join(" ", Arrays.copyOfRange(words, from, to));
		}
		return cols;
	}

	// ------------------------------------------------------------------ judging

	private final Map<UUID, List<Scoring.SlicedBar>> slicedBarsByPlayer = new HashMap<>();

	private boolean allBarsReported() {
		for (UUID u : players) {
			if (leftPlayers.contains(u)) continue;
			String pName = names.get(u);
			if (playerBarLoud.getOrDefault(pName, List.of()).size() < playerRows(pName).size()) return false;
		}
		return true;
	}

	private void tickJudging(long now) {
		long t = now - judgingStart;
		if (t > AUDIO_WAIT_MS) {
			for (UUID u : players) transcripts.putIfAbsent(u, CompletableFuture.completedFuture(""));
		}
		if (verdictFuture == null) {
			boolean allIn = players.stream().allMatch(transcripts::containsKey);
			boolean allDone = allIn && players.stream().allMatch(u -> transcripts.get(u).isDone());
			if (!allDone && t < AUDIO_WAIT_MS + STT_WAIT_MS) return;
			if (t < 500 && !allBarsReported()) return;
			List<Llm.PlayerVerse> verses = prepareJudgingVerses(now, false);
			Map<String, List<Boolean>> loudSnapshot = new HashMap<>();
			playerBarLoud.forEach((k, list) -> loudSnapshot.put(k, List.copyOf(list)));
			List<String> mobCopy = new ArrayList<>();
			for (Llm.MobLine l : mobLines) mobCopy.add(l.speaker() + ": " + l.text());
			boolean noStt = !activePlayers().isEmpty() && activePlayers().stream().allMatch(sttMissingPlayers::contains);
			String topicInfo = topicPromptText();
			List<String> roundTargets = List.copyOf(targets);
			verdictFuture = CompletableFuture.supplyAsync(() -> {
				String expectation = rank.judgeExpectation(lang)
					+ (topicInfo.isBlank() ? "" : (Lang.isRu(lang) ? " Тема баттла: " : " Battle topic: ") + topicInfo + ".")
					+ (roundTargets.isEmpty() ? "" : (Lang.isRu(lang) ? " Слова-мишени моба: " : " Mob anchor targets: ") + String.join(", ", roundTargets) + ".")
					+ " " + MobTricks.judgeHint(opp.profile().id(), lang);
				Llm.Verdict v = noStt ? null : Llm.judge(opp.name(), opp.profile().species(lang), rank.label(lang), expectation,
					rank.verseLo, rank.verseHi, mobCopy, verses, lang);
				if (v == null) {
					if (!noStt) RapBattle.LOG.warn("LLM judge returned null in battle {}, falling back to offline judge", id);
					v = Fallback.judge(rank.verseLo, rank.verseHi, verses, loudSnapshot, noStt, roundTargets, rnd, lang);
					if (noStt) v = new Llm.Verdict(v.mobVerse(), v.playerLines(),
						Lang.t(lang, "rapbattle.battle.no_stt_prefix") + v.comment());
				}
				return v;
			}, POOL);
			return;
		}
		if (t < MIN_JUDGING_MS) return;
		if (!verdictFuture.isDone() && now - verdictStart < JUDGE_WAIT_MS) return;
		Llm.Verdict v = verdictFuture.getNow(null);
		if (v == null) v = fallbackVerdict();
		applyJudgeVerdict(v, now);
	}

	private Llm.Verdict fallbackVerdict() {
		Map<String, List<Boolean>> loudSnapshot = new HashMap<>();
		playerBarLoud.forEach((k, list) -> loudSnapshot.put(k, List.copyOf(list)));
		boolean noStt = !activePlayers().isEmpty() && activePlayers().stream().allMatch(sttMissingPlayers::contains);
		Llm.Verdict v = Fallback.judge(rank.verseLo, rank.verseHi, pendingVerses, loudSnapshot, noStt, targets, rnd, lang);
		if (noStt) {
			v = new Llm.Verdict(v.mobVerse(), v.playerLines(),
				Lang.t(lang, "rapbattle.battle.no_stt_prefix") + v.comment());
		}
		return v;
	}

	private List<Llm.PlayerVerse> prepareJudgingVerses(long now, boolean fillMissing) {
		if (fillMissing) {
			for (UUID u : players) transcripts.putIfAbsent(u, CompletableFuture.completedFuture(""));
		}
		slicedBarsByPlayer.clear();
		List<Llm.PlayerVerse> verses = new ArrayList<>();
		for (UUID u : players) {
			String pName = names.get(u);
			CompletableFuture<String> f = transcripts.get(u);
			String raw = f != null ? f.getNow("") : "";
			String text = raw == null ? "" : raw.trim();
			List<Scoring.BarSlot> slots = new ArrayList<>();
			int lineNum = 1;
			if (view != null) {
				for (BattleView.Row r : view.rows) {
					if (!r.mob && pName.equals(r.reader)) slots.add(new Scoring.BarSlot(lineNum++, r.startBeat, r.word));
				}
			}
			List<Scoring.SlicedBar> sliced = Scoring.sliceBars(timed.get(u), recordingStart.get(u), text, slots, beatMs, lang);
			slicedBarsByPlayer.put(u, sliced);
			List<Boolean> louds = playerBarLoud.getOrDefault(pName, List.of());
			List<Integer> deltas = playerBarDeltaMs.getOrDefault(pName, List.of());
			List<Llm.BarPrompt> prompts = new ArrayList<>(sliced.size());
			for (int i = 0; i < sliced.size(); i++) {
				Scoring.SlicedBar sb = sliced.get(i);
				boolean sttSilent = sb.silent();
				boolean sttMiss = sttMissingPlayers.contains(u) || timed.get(u) == null;
				int deltaMs = i < deltas.size() ? deltas.get(i) : -1;
				int preBeat = Scoring.hybridBeat(sb.whisperDeltaBeats(), 1, sttMiss, deltaMs, beatMs);
				if (preBeat == 0 && i < louds.size() && Boolean.TRUE.equals(louds.get(i)) && sb.whisperDeltaBeats() == null) {
					preBeat = 1;
				}
				String timingLabel = sttSilent
					? Lang.t(lang, "rapbattle.judge.timing.silent")
					: preBeat == 2 ? Lang.t(lang, "rapbattle.judge.timing.on_beat")
					: preBeat == 1 ? Lang.t(lang, "rapbattle.judge.timing.near")
					: Lang.t(lang, "rapbattle.judge.timing.off");
				prompts.add(new Llm.BarPrompt(sb.lineIndex(), sb.targetWord(), timingLabel, sb.text(), sttSilent));
			}
			verses.add(new Llm.PlayerVerse(pName, rhymeWords.getOrDefault(u, List.of()), text, "", prompts));
		}
		pendingVerses = List.copyOf(verses);
		verdictStart = now;
		result = new BattleView.Result();
		for (Llm.PlayerVerse v : verses) {
			result.players.add(new BattleView.PlayerScore(v.name(), 0, v.transcript().isBlank() ? Lang.t(lang, "rapbattle.battle.silence") : v.transcript()));
			history.append(v.name()).append(": ").append(v.transcript().isBlank() ? Lang.t(lang, "rapbattle.battle.silent_history") : v.transcript()).append('\n');
		}
		return verses;
	}

	private void applyJudgeVerdict(Llm.Verdict v, long now) {

		// Build timeline-ordered LineInputs for the whole team so combo & K = 8/N work seamlessly in solo and co-op.
		result.teamBefore = respectTeam;
		result.mobBefore = respectMob;
		result.respectMax = respectMax;
		List<Scoring.LineInput> teamInputs = new ArrayList<>();
		Map<UUID, Integer> playerCursors = new HashMap<>();
		for (UUID u : players) playerCursors.put(u, 0);

		String mobId = opp.profile().id();
		boolean skeletonStrict = mobId.equals("skeleton") || mobId.equals("stray") || mobId.equals("bogged") || mobId.equals("parched");
		double whisperOnWin = skeletonStrict ? 0.22 : 0.30;
		double micOnWin = skeletonStrict ? 0.26 : 0.35;
		double flowScale = skeletonStrict ? 1.15 : 1.0;

		Stats stats = Stats.of(world.getServer());
		Map<UUID, McStyle> playerStyles = new HashMap<>();
		Map<UUID, Trophies.TrophyPerk> playerPerks = new HashMap<>();
		int extraStyleBonus = 0;
		for (UUID u : players) {
			playerStyles.put(u, stats.player(u).mcStyle());
			Trophies.TrophyPerk perk = Trophies.equippedPerk(player(u));
			playerPerks.put(u, perk);
			if (perk == Trophies.TrophyPerk.DEFAULT || perk == Trophies.TrophyPerk.ENDER_DRAGON) extraStyleBonus = 1;
		}
		Set<UUID> witherForgiven = new HashSet<>();
		Set<UUID> guardianUsed = new HashSet<>();

		int onBeatTotal = 0, timedTotal = 0;
		if (view != null) {
			for (BattleView.Row row : view.rows) {
				if (row.mob) continue;
				UUID u = playerUuidByName(row.reader);
				if (u == null) continue;
				int idx = playerCursors.getOrDefault(u, 0);
				playerCursors.put(u, idx + 1);

				String pName = names.get(u);
				List<Llm.LineScore> ls = v.playerLines().getOrDefault(pName, List.of());
				Llm.LineScore l = idx < ls.size() ? ls.get(idx) : Llm.LineScore.silent(lang);
				List<Scoring.SlicedBar> sbs = slicedBarsByPlayer.getOrDefault(u, List.of());
				Scoring.SlicedBar sb = idx < sbs.size() ? sbs.get(idx) : null;
				List<Boolean> louds = playerBarLoud.getOrDefault(pName, List.of());
				List<Integer> deltas = playerBarDeltaMs.getOrDefault(pName, List.of());
				List<Boolean> silents = playerBarSilent.getOrDefault(pName, List.of());

				boolean sttSilent = sb != null && sb.silent();
				boolean micSilent = idx < silents.size() && Boolean.TRUE.equals(silents.get(idx));
				boolean barSilent = sttMissingPlayers.contains(u) ? micSilent : (sb != null ? sttSilent : micSilent);
				Trophies.TrophyPerk perk = playerPerks.get(u);
				boolean silenceForgiven = barSilent && perk == Trophies.TrophyPerk.WITHER && witherForgiven.add(u);
				double wWin = whisperOnWin + (perk == Trophies.TrophyPerk.ELDER_GUARDIAN ? 0.05 : 0.0);
				double mWin = micOnWin + (perk == Trophies.TrophyPerk.ELDER_GUARDIAN ? 0.05 : 0.0);
				int deltaMs = idx < deltas.size() ? deltas.get(idx) : (idx < louds.size() && Boolean.TRUE.equals(louds.get(idx)) ? 0 : -1);
				int beatState = barSilent ? 0 : Scoring.hybridBeat(sb == null ? null : sb.whisperDeltaBeats(),
					l.rhyme(), sttMissingPlayers.contains(u), deltaMs, beatMs, wWin, mWin);
				timedTotal++;
				if (beatState == 2) {
					onBeatTotal++;
					if (perk == Trophies.TrophyPerk.ELDER_GUARDIAN) guardianUsed.add(u);
				}
				McStyle style = playerStyles.getOrDefault(u, McStyle.PUNCHER);
				teamInputs.add(style.lineInput(pName, row.freestyle ? "★" : row.word, l.score(), l.meaning(),
					l.punch(), l.rebuttal(), l.note(), beatState, barSilent, row.freestyle, flowScale, silenceForgiven));
			}
		}

		int hypeVal = Math.max(-100, Math.min(100, (barsHit - barsMissed) * 18 - punchesFired.size() * 8));
		Scoring.Bonuses bonuses = Scoring.computeBonuses(hypeVal, hasStyleBonus, allyBonus, extraStyleBonus);
		int wardenNoise = noise > 0 ? 2 * noise : 0;
		Scoring.RoundOutcome outcome = Scoring.evaluateRound(rank, teamInputs, bonuses, v.mobVerse(), wardenNoise);

		List<BattleView.Hit> hits = result.hits;
		for (Scoring.EvaluatedLine el : outcome.lines()) {
			Scoring.LineInput in = el.input();
			String word = in.targetWord() == null || in.targetWord().isEmpty() ? "" : "«" + in.targetWord() + "» · ";
			hits.add(new BattleView.Hit(in.player(), word + in.note(), el.damage(), true, in.punch()));
			roundDealt.merge(in.player(), el.damage(), Integer::sum);
			battleDealt.merge(in.player(), el.damage(), Integer::sum);
			if (el.damage() > bestLineDamage) {
				bestLineDamage = el.damage();
				bestLine = in.player() + ": " + word + in.note();
			}
		}
		for (BattleView.PlayerScore ps : result.players) {
			ps.score = roundDealt.getOrDefault(ps.name, 0);
		}
		if (bonuses.styleBonus() > 0) hits.add(new BattleView.Hit("", Lang.t(lang, "rapbattle.battle.hit_outfit"), bonuses.styleBonus(), true, false));
		if (bonuses.allyBonus() > 0) hits.add(new BattleView.Hit("", Lang.t(lang, "rapbattle.battle.hit_backup"), bonuses.allyBonus(), true, false));
		if (bonuses.hypeBonus() > 0) hits.add(new BattleView.Hit("", Lang.t(lang, "rapbattle.battle.hit_crowd"), bonuses.hypeBonus(), true, false));
		if (outcome.mobVerseFinalDamage() > 0) {
			hits.add(new BattleView.Hit(opp.name(), Lang.t(lang, "rapbattle.battle.hit_mob_verse"), outcome.mobVerseFinalDamage(), false, false));
		}
		if (outcome.silenceDamage() > 0) {
			hits.add(new BattleView.Hit(opp.name(), Lang.t(lang, "rapbattle.battle.hit_silence", outcome.silentBars()), outcome.silenceDamage(), false, false));
		}
		if (noise > 0) {
			hits.add(new BattleView.Hit(opp.name(), Lang.t(lang, "rapbattle.battle.hit_warden_noise", noise), wardenNoise, false, false));
			noise = 0;
		}
		if (gangRival) {
			hits.add(new BattleView.Hit(opp.name(), Lang.t(lang, "rapbattle.act6.gang_rival"), 0, false, false));
		}
		for (UUID u : players) {
			Trophies.TrophyPerk perk = playerPerks.get(u);
			if (perk == null) continue;
			boolean triggered = switch (perk) {
				case HOMEMADE -> false;
				case WARDEN -> wardenTriggered.contains(u);
				case ENDER_DRAGON -> rank.revealBeats() > 0;
				case WITHER -> witherForgiven.contains(u);
				case ELDER_GUARDIAN -> guardianUsed.contains(u);
				case DEFAULT -> extraStyleBonus > 0;
			};
			if (triggered) hits.add(new BattleView.Hit(names.get(u), perk.triggeredNote(lang), 0, false, false, true));
		}

		// Apply only the post-judging delta to live respect bars (live mob hits were already applied during TIMELINE).
		respectMob = outcome.respectMobAfter();
		respectTeam = outcome.respectTeamAfter();
		roundToMob = outcome.totalToMob();
		roundToTeam = outcome.totalToTeam();
		dealtTotal += outcome.totalToMob();
		bestCombo = Math.max(bestCombo, outcome.bestCombo());

		result.respectTeam = respectTeam;
		result.respectMob = respectMob;
		result.barTextTotal = outcome.barTextTotal();
		result.barBeatDelta = outcome.barBeatDelta();
		result.barRebuttalTotal = outcome.barRebuttalDamage();
		result.mobParried = outcome.mobVerseParried();
		result.rebuttalCount = outcome.rebuttalCount();
		result.bonusHype = bonuses.hypeBonus();
		result.bonusStyle = bonuses.styleBonus();
		result.bonusAlly = bonuses.allyBonus();
		result.bonusTotal = bonuses.total();
		result.mobLiveDamage = outcome.mobLiveDamage();
		result.mobVerseDamage = outcome.mobVerseFinalDamage();
		result.silenceDamage = outcome.silenceDamage();
		result.wardenNoiseDamage = wardenNoise;

		boolean ko = outcome.knockout();
		boolean roundPlayers = outcome.playersWinRound();
		if (roundPlayers) winsPlayers++;
		else winsMob++;
		boolean done = ko || winsPlayers >= winsNeeded || winsMob >= winsNeeded || round >= rounds;
		result.knockout = ko;
		result.finalResult = done;
		result.playersWin = boss
			? (done ? (respectMob <= 0 ? true : respectTeam <= 0 ? false : winsPlayers > winsMob) : roundPlayers)
			: roundPlayers;
		result.comment = v.comment();
		List<UUID> active = activePlayers();
		result.mvp = active.size() > 1
			? active.stream().map(names::get)
				.max(Comparator.comparingInt(n -> battleDealt.getOrDefault(n, 0))).orElse("")
			: "";
		result.outfitBonus = bonuses.styleBonus();
		result.overdressed = overdressed;
		result.mobLabel = mobTeam.size() > 1 ? Lang.t(lang, "rapbattle.battle.mob_and_crew", opp.profile().species(lang)) : opp.profile().species(lang);
		result.teamLabel = String.join(" + ", namesList());
		result.dealt = dealtTotal;
		result.accuracy = timedTotal > 0 ? onBeatTotal * 100 / timedTotal : linesTotal == 0 ? 0 : linesOnBeat * 100 / linesTotal;
		result.bestCombo = bestCombo;
		result.bestLine = bestLine;
		view.result = result;
		if (done) {
			boolean upset = result.playersWin && avgOutfitLevel() <= rank.level() - 2;
			Crowd.react(this, upset ? Crowd.Moment.UPSET : result.playersWin ? Crowd.Moment.WIN_PLAYERS : Crowd.Moment.WIN_MOB);
		} else {
			Crowd.react(this, roundPlayers ? Crowd.Moment.WIN_PLAYERS : Crowd.Moment.WIN_MOB);
		}
		setPhase(done ? Phase.RESULT : Phase.ROUND_RESULT, now);
		resolvePendingJudgeExits();
	}

	private UUID playerUuidByName(String name) {
		for (UUID u : players) if (names.get(u).equals(name)) return u;
		return null;
	}

	// ------------------------------------------------------------------ sync

	private BattleView baseView() {
		BattleView v = new BattleView();
		v.id = id;
		v.allowSwearing = RapConfig.get().allowSwearing;
		v.beatMs = beatMs;
		v.beatTrack = track == null ? null : track.id();
		v.boss = boss;
		v.rounds = rounds;
		v.revealBeats = rank.revealBeats();
		v.round = round;
		v.roundWinsPlayers = winsPlayers;
		v.roundWinsMob = winsMob;
		v.challenger = names.getOrDefault(players.isEmpty() ? null : players.get(0), "");
		Stats stats = Stats.of(world.getServer());
		for (UUID u : players) {
			ServerPlayerEntity p = player(u);
			Outfit.Score s = p == null ? new Outfit.Score(0, "") : Outfit.of(p, lang);
			BattleView.Member mm = new BattleView.Member(names.get(u), s.label(lang), s.level(), p == null ? -1 : p.getId());
			Stats.Player st = stats.player(u);
			mm.wins = st.wins;
			mm.losses = st.losses;
			mm.streak = st.streak;
			mm.mvp = st.mvp;
			mm.best = st.best;
			mm.title = st.title(lang);
			mm.style = st.mcStyle().label(lang);
			v.team.add(mm);
		}
		int[] sp = stats.species(opp.profile().id());
		v.speciesMobWins = sp[0];
		v.speciesPlayerWins = sp[1];
		v.mobName = opp.name();
		v.mobSpecies = opp.profile().species(lang);
		v.mobLanguage = opp.profile().language(lang);
		v.mobMood = opp.mood();
		v.mobType = net.minecraft.registry.Registries.ENTITY_TYPE.getId(mob.getType()).toString();
		v.mobEntityId = mob.getId();
		for (MobEntity m : mobTeam) {
			v.mobEntityIds.add(m.getId());
			v.mobTeam.add(mobNames.get(m));
		}
		v.rankArmor = rank.armor(lang);
		v.rankStreet = rank.street(lang);
		v.rankLevel = rank.level();
		v.rankColor = rank.color;
		v.tags = new ArrayList<>(opp.tags());
		MobRecord rec = MobRecord.of(mob);
		v.mobWins = rec.wins();
		v.mobLosses = rec.losses();
		v.mobStreak = rec.streak();
		v.topic = chosenTopic;
		v.targets = new ArrayList<>(targets);
		Gangs.Gang gang = Gangs.of(opp.profile());
		v.gangColor = gang == null ? -1 : gang.color();
		v.requiredOutfit = levelLabel(Outfit.required(rank), lang);
		MobTricks.flags(opp.profile().id(), v, lang);
		return v;
	}

	static String levelLabel(int level, String lang) {
		return level <= 0 ? Lang.t(lang, "rapbattle.battle.any_outfit") : Rank.ofLevel(level).armor(lang);
	}

	private void setPhase(Phase p, long now) {
		phase = p;
		phaseStart = now;
		broadcastState();
	}

	public void broadcastState() {
		boolean timeline = view != null && (phase == Phase.TIMELINE || phase == Phase.JUDGING || phase == Phase.ROUND_RESULT || phase == Phase.RESULT);
		BattleView v = timeline ? view : baseView();
		if (timeline) {
			BattleView fresh = baseView();
			v.team = fresh.team;
			v.round = round;
			v.roundWinsPlayers = winsPlayers;
			v.roundWinsMob = winsMob;
			v.mobEntityIds = fresh.mobEntityIds;
			v.mobTeam = fresh.mobTeam;
		}
		v.phase = phase.name();
		v.crowdIds.clear();
		if (crowd != null && crowd.center != null) {
			v.stageX = crowd.center.x;
			v.stageY = crowd.center.y;
			v.stageZ = crowd.center.z;
			v.stageRadius = crowd.members.isEmpty() ? 4.5 : crowd.radius;
			for (MobEntity m : crowd.members.keySet()) if (m.isAlive()) v.crowdIds.add(m.getId());
		}
		v.respectTeam = respectTeam;
		v.respectMob = respectMob;
		v.respectMax = respectMax;
		v.combo = combo;
		v.hype = Math.max(-100, Math.min(100, (barsHit - barsMissed) * 18 - punchesFired.size() * 8));
		v.lobbyMsLeft = phase == Phase.LOBBY ? Math.max(0, lobbyEnd - dev.rapbattle.Clock.now()) : 0;
		v.beatElapsedMs = timeline ? dev.rapbattle.Clock.now() - beatZero : -1;
		v.topic = chosenTopic;
		v.targets = new ArrayList<>(targets);
		v.spectator = false;
		BattleView sv = v.copy();
		sv.spectator = true;
		sv.rows = new ArrayList<>(v.rows);
		Stats stats = Stats.of(world.getServer());
		for (UUID u : players) {
			if (leftPlayers.contains(u)) continue;
			ServerPlayerEntity p = player(u);
			if (p != null) {
				v.canReroll = !rerolledPlayers.contains(u);
				int targetCap = stats.player(u).mcStyle().targetCount();
				v.targets = targets.size() > targetCap ? new ArrayList<>(targets.subList(0, targetCap)) : new ArrayList<>(targets);
				Trophies.TrophyPerk perk = Trophies.equippedPerk(p);
				v.revealBeats = (rank.revealBeats() > 0 && perk == Trophies.TrophyPerk.ENDER_DRAGON)
					? rank.revealBeats() + 2
					: rank.revealBeats();
				send(p, v);
			}
		}
		v.targets = new ArrayList<>(targets);
		// Bystanders: beat, header and result only.
		double r2 = cfg.spectatorRadius * cfg.spectatorRadius;
		for (ServerPlayerEntity p : world.getPlayers()) {
			if (hasPlayer(p.getUuid())) continue;
			if (p.squaredDistanceTo(mob) <= r2) {
				spectators.add(p.getUuid());
				send(p, sv);
			}
		}
	}

	private static void send(ServerPlayerEntity p, BattleView v) {
		ServerPlayNetworking.send(p, new Payloads.StateS2C(v.toJson()));
	}

	private void broadcastChat(Text text) {
		for (UUID u : players) {
			ServerPlayerEntity p = player(u);
			if (p != null) p.sendMessage(text, false);
		}
	}

	// ------------------------------------------------------------------ end

	private void sendEnd() {
		BattleView end = new BattleView();
		end.type = "end";
		end.id = id;
		Set<UUID> all = new HashSet<>(players);
		all.addAll(spectators);
		for (UUID u : all) {
			ServerPlayerEntity p = player(u);
			if (p != null) send(p, end);
		}
		for (UUID u : invited) {
			ServerPlayerEntity p = player(u);
			if (p != null) {
				BattleView close = new BattleView();
				close.type = "invite_close";
				close.id = id;
				send(p, close);
			}
		}
	}

	private void releaseMobs() {
		if (crowd != null) crowd.release(result != null && result.playersWin);
		for (MobEntity m : dressed) Gangs.undress(m);
		dressed.clear();
		for (MobEntity m : mobTeam) {
			Crowd.restoreZombify(m, wasImmuneZombify);
			if (m.isAlive()) {
				m.setAiDisabled(hadNoAi.getOrDefault(m, false));
				m.setSilent(wasSilent.getOrDefault(m, false));
			}
		}
		clearGlow();
	}

	private void cancelFutures() {
		if (contentFuture != null) contentFuture.cancel(true);
		if (verdictFuture != null) verdictFuture.cancel(true);
		for (CompletableFuture<String> f : transcripts.values()) f.cancel(true);
	}

	public void abort(Text reason) {
		if (finished) return;
		finished = true;
		pendingJudgeExit.clear();
		cancelFutures();
		releaseMobs();
		broadcastChat(reason.copy().formatted(Formatting.GRAY));
		sendEnd();
	}

	public void abort(String reason) {
		abort(Text.literal(reason));
	}

	/**
	 * Test-only unified entry point to jump straight to a target phase without waiting for lobbies or full rounds.
	 * Supported phases: TIMELINE (starts timeline immediately with fallback content), JUDGING (moves into judging),
	 * RESULT (resolves the battle immediately with default win/loss scores).
	 */
	public void jumpToForTest(Phase target) {
		jumpToForTest(target, true, true, 90, 0);
	}

	/** Test-only helper to set a participant's round/battle dealt score before jumping to RESULT. */
	public void setPlayerScoreForTest(String name, int dealt) {
		battleDealt.put(name, dealt);
	}

	/** Test-only entry point to jump to a target phase with explicit outcome/scores (used for RESULT). */
	public void jumpToForTest(Phase target, boolean playersWin, boolean knockout, int teamRespect, int mobRespect) {
		if (finished) return;
		long now = dev.rapbattle.Clock.now();
		switch (target) {
			case TIMELINE -> {
				if (phase == Phase.LOBBY) {
					closeLobby();
					beginRoundContent(now);
				}
				Llm.Content c = contentFuture != null ? contentFuture.getNow(null) : null;
				buildTimeline(c, now);
				setPhase(Phase.TIMELINE, now);
			}
			case JUDGING -> {
				if (phase == Phase.LOBBY) {
					closeLobby();
					beginRoundContent(now);
				}
				if (phase == Phase.PREP) {
					Llm.Content c = contentFuture != null ? contentFuture.getNow(null) : null;
					buildTimeline(c, now);
				}
				clearGlow();
				judgingStart = now;
				setPhase(Phase.JUDGING, now);
			}
			case RESULT -> {
				this.result = new BattleView.Result();
				this.result.finalResult = true;
				this.result.playersWin = playersWin;
				this.result.knockout = knockout;
				this.respectTeam = teamRespect;
				this.respectMob = mobRespect;
				if (playersWin) this.bestLine = "test golden line";
				String bestName = "";
				int bestScore = -1;
				for (UUID u : players) {
					BattleView.PlayerScore ps = new BattleView.PlayerScore();
					ps.name = names.getOrDefault(u, "Player");
					ps.score = battleDealt.getOrDefault(ps.name, 0);
					ps.transcript = playersWin ? "test golden line" : "";
					this.result.players.add(ps);
					if (ps.score > bestScore) {
						bestScore = ps.score;
						bestName = ps.name;
					}
				}
				if (playersWin && bestScore > 0) this.result.mvp = bestName;
				finish();
			}
			default -> throw new IllegalArgumentException("Unsupported jumpToForTest phase: " + target);
		}
	}

	public int reportedBarsCountForTest(String who) {
		return playerBarLoud.getOrDefault(who, List.of()).size();
	}

	public int lastBarDeltaForTest(String who) {
		List<Integer> d = playerBarDeltaMs.get(who);
		return d == null || d.isEmpty() ? -1 : d.get(d.size() - 1);
	}

	public int hypeForTest(String who) {
		return view == null ? 0 : view.hype;
	}

	public int crowdIdsCountForTest() {
		return view == null ? 0 : view.crowdIds.size();
	}

	public BattleView viewForTest() {
		return view;
	}

	public void broadcastStateForTest() {
		broadcastState();
	}

	/** Test-only helper to enter Phase.RESULT with a populated result without finishing immediately. */
	public void enterResultForTest(boolean playersWin, int teamRespect, int mobRespect) {
		this.result = new BattleView.Result();
		this.result.playersWin = playersWin;
		this.result.finalResult = true;
		this.respectTeam = teamRespect;
		this.respectMob = mobRespect;
		for (UUID u : players) {
			BattleView.PlayerScore ps = new BattleView.PlayerScore();
			ps.name = names.getOrDefault(u, "Player");
			ps.score = battleDealt.getOrDefault(ps.name, 0);
			this.result.players.add(ps);
		}
		setPhase(Phase.RESULT, dev.rapbattle.Clock.now());
	}

	/** Test-only helper to enter Phase.ROUND_RESULT with explicit round wins and finalResult=false. */
	public void enterRoundResultForTest(int wp, int wm) {
		this.winsPlayers = wp;
		this.winsMob = wm;
		this.result = new BattleView.Result();
		this.result.playersWin = wp > wm;
		this.result.finalResult = false;
		setPhase(Phase.ROUND_RESULT, dev.rapbattle.Clock.now());
	}

	/** Immediately resolves the battle (or this co-op player's participation) with the current outcome. */
	public void resolveNow(ServerPlayerEntity p) {
		if (p != null) resolveNow(p.getUuid());
	}

	/** Immediately resolves the battle (or this co-op player's participation), supporting offline players. */
	public void resolveNow(UUID u) {
		if (finished || u == null || !hasPlayer(u) || !isResolving()) return;
		long activeCount = players.stream().filter(id -> !leftPlayers.contains(id)).count();
		if (activeCount <= 1) {
			if (phase == Phase.JUDGING) {
				long now = dev.rapbattle.Clock.now();
				if (verdictFuture == null) {
					prepareJudgingVerses(now, true);
					applyJudgeVerdict(fallbackVerdict(), now);
				} else {
					Llm.Verdict v = verdictFuture.getNow(null);
					if (v == null) v = fallbackVerdict();
					applyJudgeVerdict(v, now);
				}
			}
			if (result != null && !result.finalResult) {
				result.playersWin = winsPlayers >= winsNeeded;
				result.finalResult = true;
			}
			finish();
			return;
		}
		if (phase == Phase.JUDGING) {
			pendingJudgeExit.add(u);
		} else {
			boolean win = result != null && (result.finalResult ? result.playersWin : winsPlayers >= winsNeeded);
			Stats stats = Stats.of(world.getServer());
			long overworldDay = ProgressionRules.currentDay(world.getServer().getOverworld().getTime());
			recordExitedPlayer(u, win, stats, overworldDay);
		}
		leftPlayers.add(u);
		anchors.remove(u);
		pushTicks.remove(u);
		anchorReleased.remove(u);
		ServerPlayerEntity p = player(u);
		if (p != null) {
			if (glowing.remove(p)) p.setGlowing(false);
			BattleView end = new BattleView();
			end.type = "end";
			end.id = id;
			send(p, end);
		}
		broadcastState();
	}

	private void resolvePendingJudgeExits() {
		if (pendingJudgeExit.isEmpty()) return;
		boolean win = result != null && (result.finalResult ? result.playersWin : winsPlayers >= winsNeeded);
		Stats stats = Stats.of(world.getServer());
		long overworldDay = ProgressionRules.currentDay(world.getServer().getOverworld().getTime());
		for (UUID u : pendingJudgeExit) {
			if (!resolvedPlayers.contains(u)) recordExitedPlayer(u, win, stats, overworldDay);
		}
		pendingJudgeExit.clear();
	}

	private void recordExitedPlayer(UUID u, boolean win, Stats stats, long overworldDay) {
		ServerPlayerEntity p = player(u);
		String nm = names.getOrDefault(u, p != null ? p.getName().getString() : "Player");
		int outfitLvl = p != null ? Outfit.of(p, lang).level() : 0;
		stats.record(List.of(u), Map.of(u, battleDealt.getOrDefault(nm, 0)), null,
			win, opp, this.rank, Map.of(u, outfitLvl), overworldDay);
		if (p != null) notifyStyleUnlock(stats, u, p);
		resolvedPlayers.add(u);
		if (!win) Reputation.revokeCred(u);
	}

	private void finish() {
		finished = true;
		resolvePendingJudgeExits();
		pendingJudgeExit.clear();
		cancelFutures();
		sendEnd();
		releaseMobs();
		BattleManager.recent(this);
		List<UUID> active = activePlayers();
		String team = String.join(" + ", namesList());
		ServerPlayerEntity mvpPlayer = playerByName(result.mvp.isEmpty() ? (active.isEmpty() ? "" : names.get(active.get(0))) : result.mvp);
		if (mvpPlayer == null) mvpPlayer = challenger();
		if (mvpPlayer == null) {
			for (UUID u : active) {
				mvpPlayer = player(u);
				if (mvpPlayer != null) break;
			}
		}
		long overworldDay = ProgressionRules.currentDay(world.getServer().getOverworld().getTime());
		double effectiveDailyScale = 1.0;
		boolean anyContributor = false;
		Stats statsForCap = Stats.of(world.getServer());
		for (UUID u : active) {
			String nm = names.get(u);
			int dealt = nm != null ? battleDealt.getOrDefault(nm, 0) : 0;
			if (dealt > 0) {
				anyContributor = true;
				int prev = statsForCap.player(u).dailyWinsFor(opp.profile().id(), overworldDay);
				effectiveDailyScale = Math.min(effectiveDailyScale, ProgressionRules.dailyBonusScale(prev));
			}
		}
		if (!anyContributor) effectiveDailyScale = this.dailyBonusScale;
		recordStats(mvpPlayer);
		if (result.playersWin) {
			MobRecord.loss(mob);
			rememberPlayers(false);
			Theft.returnOnWin(mob, mvpPlayer != null ? mvpPlayer : challenger());
			Reputation.grantOnWin(this, mvpPlayer, effectiveDailyScale);
			Text verb = winVerb(players.size() > 1);
			Text msg;
			if (result.knockout) {
				msg = result.mvp.isEmpty()
					? Text.translatable("rapbattle.battle.win_ko", team, verb, opp.name())
					: Text.translatable("rapbattle.battle.win_ko_mvp", team, verb, opp.name(), result.mvp);
			} else {
				int sWin = boss ? winsPlayers : respectTeam;
				int sLose = boss ? winsMob : respectMob;
				msg = result.mvp.isEmpty()
					? Text.translatable("rapbattle.battle.win_pts", team, verb, opp.name(), sWin, sLose)
					: Text.translatable("rapbattle.battle.win_pts_mvp", team, verb, opp.name(), sWin, sLose, result.mvp);
			}
			broadcastChat(msg.copy().formatted(Formatting.GREEN));
			ServerPlayerEntity killer = mvpPlayer;
			Vec3d pos = new Vec3d(mob.getX(), mob.getY() + 0.5, mob.getZ());
			int xp = overdressed ? rank.xp / 2 : rank.xp;
			boolean bonuses = actBonusesAllowed && effectiveDailyScale > 0.0;
			boolean fullBonuses = actBonusesAllowed && effectiveDailyScale >= 1.0;
			if (effectiveDailyScale == 0.0) {
				broadcastChat(Text.translatable("rapbattle.progression.daily_cap").formatted(Formatting.GRAY));
			}
			if (bonuses) Trophies.give(killer, opp, lang);
			if (crowd != null) crowd.dropGift(killer);
			if (mob instanceof net.minecraft.entity.passive.IronGolemEntity golem) {
				// The village golem is a character: it accepts the defeat instead of dying.
				GolemDefender.respect(golem, killer, lang);
				ExperienceOrbEntity.spawn(world, pos, xp);
				return;
			}
			if (mob instanceof net.minecraft.entity.passive.VillagerEntity villager) {
				// Friendly villager battle: never kill/damage the villager, grant cured-level discounts at most once per day.
				if (Acts.canGiveVillagerDiscount(MobRecord.of(villager).lastDiscountTick(), world.getTime())) {
					for (UUID u : players) {
						ServerPlayerEntity pl = player(u);
						if (pl != null) villager.onInteractionWith(net.minecraft.entity.EntityInteraction.ZOMBIE_VILLAGER_CURED, pl);
					}
					MobRecord.markDiscount(villager, world.getTime());
					broadcastChat(Text.translatable("rapbattle.act2.villager_discount", opp.name()).formatted(Formatting.GREEN));
				}
				ExperienceOrbEntity.spawn(world, pos, xp);
				return;
			}
			if (mob instanceof net.minecraft.entity.mob.HostileEntity) {
				List<net.minecraft.entity.passive.VillagerEntity> witnesses = world.getEntitiesByClass(
					net.minecraft.entity.passive.VillagerEntity.class, mob.getBoundingBox().expand(32),
					net.minecraft.entity.LivingEntity::isAlive);
				if (Acts.hasVillageDefenseWitnesses(witnesses.size())) {
					for (net.minecraft.entity.passive.VillagerEntity v : witnesses) {
						for (UUID u : players) {
							v.getGossip().startGossip(u, net.minecraft.village.VillagerGossipType.MINOR_POSITIVE, 25);
						}
					}
					broadcastChat(Text.translatable("rapbattle.act2.village_defended").formatted(Formatting.GREEN));
				}
			}
			if (bonuses && mob instanceof net.minecraft.entity.mob.PiglinBruteEntity && BastionBattle.inBastion(world, mob.getBlockPos())) {
				BastionBattle.onWin(world, players, mob.getBlockPos());
			}
			if (bonuses && opp.profile().id().equals("warden")) {
				for (UUID u : players) {
					PlayerStatus.grant(u, PlayerStatus.WARDEN_QUIET, 15 * 60_000L, world.getRegistryKey(), mob.getBlockPos(), 48);
					ServerPlayerEntity pl = player(u);
					if (pl != null) pl.sendMessage(Text.translatable("rapbattle.act4.warden_quiet").formatted(Formatting.DARK_AQUA), false);
				}
			}
			if (mob instanceof net.minecraft.entity.boss.dragon.EnderDragonEntity dragon) {
				if (!bestLine.isEmpty()) {
					Stats.of(world.getServer()).recordLegend(mvpPlayer != null ? mvpPlayer.getName().getString() : "", bestLine);
				}
				ServerPlayerEntity dragonKiller = killer;
				if (dragonKiller == null) {
					for (UUID u : active) {
						ServerPlayerEntity pl = player(u);
						if (pl != null && pl.isAlive()) { dragonKiller = pl; break; }
					}
				}
				DamageSource src = dragonKiller != null
					? world.getDamageSources().playerAttack(dragonKiller)
					: world.getDamageSources().badRespawnPoint(pos);
				dragon.damagePart(world, dragon.head, src, dragon.getMaxHealth() * 100f);
				ExperienceOrbEntity.spawn(world, pos, xp * Math.max(1, active.size()));
				return;
			}
			int margin = respectTeam - respectMob;
			// A knockout, boss, or >= 25% margin finishes the mob; a close win hurts it and sends it packing.
			boolean lethal = Scoring.isLethalWin(result.playersWin, result.knockout, respectTeam, respectMob, boss);
			if (raid != null) {
				Vec3d awayFrom = killer != null ? new Vec3d(killer.getX(), killer.getY(), killer.getZ()) : pos;
				RaidBattle.onWin(world, raid, players, lethal, pos, awayFrom, mob, rnd);
				if (!lethal) {
					Crowd.retreat(mobTeam, awayFrom, 15_000L);
					broadcastChat(Text.translatable("rapbattle.battle.mob_escaped", opp.name(),
						Text.translatable("rapbattle.battle.escaped." + rnd.nextInt(ESCAPED_LINES))).formatted(Formatting.GRAY));
					ExperienceOrbEntity.spawn(world, pos, Math.max(1, xp / 2));
					return;
				}
			}
			if (!lethal) {
				DamageSource rap = new DamageSource(world.getRegistryManager().getOrThrow(RegistryKeys.DAMAGE_TYPE).getOrThrow(RAP_DEATH));
				for (MobEntity m : new ArrayList<>(mobTeam)) {
					float frac = (float) Math.max(0.3, Math.min(0.9, 0.3 + margin / 40.0));
					float dmg = Math.min(m.getMaxHealth() * frac, m.getHealth() - 1);
					if (dmg > 0) {
						m.damage(world, rap, dmg);
						DamagePop.spawn(world, m, "-" + Math.max(1, Math.round(dmg / 2)) + " ❤", 0xFF5050);
					}
				}
				Vec3d awayFrom = killer != null ? new Vec3d(killer.getX(), killer.getY(), killer.getZ()) : pos;
				Crowd.retreat(mobTeam, awayFrom, 15_000L);
				broadcastChat(Text.translatable("rapbattle.battle.mob_escaped", opp.name(),
					Text.translatable("rapbattle.battle.escaped." + rnd.nextInt(ESCAPED_LINES))).formatted(Formatting.GRAY));
				ExperienceOrbEntity.spawn(world, pos, Math.max(1, xp / 2));
				return;
			}
			boolean isNight = Opponent.isNight(world);
			boolean nightHunt = Acts.isNightHuntDoubleLoot(rank, isNight, mobTeam.size(), overdressed);
			boolean raged = SoreLosers.tryRage(opp, MobRecord.of(mob), margin, killer, rnd, lang);
			// The battle is already marked finished, so the damage guard no longer protects the mob team.
			Set<UUID> doubledDrops = new HashSet<>();
			for (MobEntity m : new ArrayList<>(mobTeam)) {
				if (raged && m == mob) continue;
				DamageSource src = killer != null ? world.getDamageSources().playerAttack(killer) : world.getDamageSources().generic();
				Vec3d at = new Vec3d(m.getX(), m.getY(), m.getZ());
				m.damage(world, src, m.getMaxHealth() * 100f);
				// Street level and up, or Act 1 Night Hunt (solo mob, ranks 1-2): the loot comes doubled.
				if (((rank.level() >= 3 && !overdressed && fullBonuses) || (nightHunt && bonuses)) && !m.isAlive()) {
					SoreLosers.doubleFreshDrops(world, at, doubledDrops);
				}
			}
			// Spawn act bonus drops after mob-kill doubling so they are never duplicated by doubleFreshDrops.
			if (bonuses && opp.profile().id().equals("enderman") && !overdressed) {
				int pearls = Acts.enderPearls(result.knockout, rnd);
				if (effectiveDailyScale < 1.0) pearls = pearls / 2;
				if (pearls > 0) {
					var pearlDrop = new net.minecraft.entity.ItemEntity(world, pos.x, pos.y, pos.z,
						new net.minecraft.item.ItemStack(net.minecraft.item.Items.ENDER_PEARL, pearls));
					pearlDrop.setToDefaultPickupDelay();
					world.spawnEntity(pearlDrop);
				}
			}
			if (bonuses && opp.profile().id().equals("blaze") && !overdressed) {
				int extraRods = Acts.blazeExtraRods(lethal, rnd);
				if (effectiveDailyScale < 1.0) extraRods = extraRods / 2;
				if (extraRods > 0) {
					var rodDrop = new net.minecraft.entity.ItemEntity(world, pos.x, pos.y, pos.z,
						new net.minecraft.item.ItemStack(net.minecraft.item.Items.BLAZE_ROD, extraRods));
					rodDrop.setToDefaultPickupDelay();
					world.spawnEntity(rodDrop);
				}
			}
			if (bonuses && opp.profile().id().equals("wither_skeleton") && !overdressed) {
				double skullChance = Acts.witherSkullChance(result.knockout, margin);
				if (effectiveDailyScale < 1.0) skullChance /= 3.0;
				if (skullChance > 0 && rnd.nextDouble() < skullChance) {
					var skullDrop = new net.minecraft.entity.ItemEntity(world, pos.x, pos.y, pos.z,
						new net.minecraft.item.ItemStack(net.minecraft.item.Items.WITHER_SKELETON_SKULL));
					skullDrop.setToDefaultPickupDelay();
					world.spawnEntity(skullDrop);
					broadcastChat(Text.translatable("rapbattle.act3.wither_skull", opp.name()).formatted(Formatting.GOLD));
				}
			}
			int finalXp = Acts.applyNightHuntXp(xp, rank, isNight);
			ExperienceOrbEntity.spawn(world, pos, finalXp * Math.max(1, players.size()));
			if (nightHunt) broadcastChat(Text.translatable("rapbattle.act1.night_hunt").formatted(Formatting.AQUA));
			// A piece of the crew's gear for the winner.
			Gangs.Gang gang = Gangs.of(opp.profile());
			if (gang != null && !overdressed && bonuses && rnd.nextInt(100) < Gangs.dropChance(rank)) {
				ItemStack gear = Gangs.randomPiece(gang, rnd);
				net.minecraft.entity.ItemEntity drop = new net.minecraft.entity.ItemEntity(world, pos.x, pos.y, pos.z, gear);
				drop.setToDefaultPickupDelay();
				world.spawnEntity(drop);
				broadcastChat(Text.translatable("rapbattle.battle.gear_dropped", gear.getName()).formatted(Formatting.GOLD));
			}
			if (overdressed) broadcastChat(Text.translatable("rapbattle.battle.overdressed",
				Text.translatable("rapbattle.rank.armor_lower." + rank.name().toLowerCase(Locale.ROOT))).formatted(Formatting.GRAY));
		} else {
			MobRecord.win(mob);
			rememberPlayers(true);
			Text verb = winVerb(false);
			Text msg = result.knockout
				? Text.translatable("rapbattle.battle.win_ko", opp.name(), verb, team)
				: Text.translatable("rapbattle.battle.win_pts", opp.name(), verb, team, boss ? winsMob : respectMob, boss ? winsPlayers : respectTeam);
			broadcastChat(msg.copy().formatted(Formatting.RED));
			punishPlayers();
			Reputation.onLoss(this);
			if (!boss) {
				mob.setPersistent();
				Theft.refreshMobName(mob, opp.name());
			}
			ServerPlayerEntity worst = null;
			int minScore = Integer.MAX_VALUE;
			for (UUID u : active) {
				ServerPlayerEntity pl = player(u);
				if (pl == null) continue;
				int sc = battleDealt.getOrDefault(names.get(u), 0);
				if (sc < minScore) { minScore = sc; worst = pl; }
			}
			Theft.stealOnLoss(this, worst != null ? worst : challenger());
			// Lost to a villager: the village golem takes it personally.
			if (mob instanceof net.minecraft.entity.passive.VillagerEntity villager && challenger() != null) {
				GolemDefender.offended(challenger(), villager);
			}
			if (raid != null) {
				RaidBattle.onLoss(world, raid, new Vec3d(mob.getX(), mob.getY(), mob.getZ()));
			}
			if (mob instanceof net.minecraft.entity.mob.PiglinBruteEntity && BastionBattle.inBastion(world, mob.getBlockPos())) {
				BastionBattle.onLoss(world, challenger(), mob.getBlockPos());
			}
			if (mob instanceof net.minecraft.entity.boss.dragon.EnderDragonEntity dragon) {
				DragonBattle.onLoss(dragon);
			}
		}
		RapBattle.LOG.info("Battle {} finished: players {} respect {} vs mob {} (ko {}, rank {})", id, result.playersWin ? "won" : "lost",
			respectTeam, respectMob, result.knockout, rank);
	}

	private Text winVerb(boolean team) {
		int idx = rnd.nextInt(team ? TEAM_WIN_VERBS : WIN_VERBS);
		String key = (team ? "rapbattle.battle.team_win_verb." : "rapbattle.battle.win_verb.") + idx;
		if (idx == 0 && !RapConfig.get().allowSwearing) key += ".clean";
		return Text.translatable(key);
	}

	private void recordStats(ServerPlayerEntity mvpPlayer) {
		Stats stats = Stats.of(world.getServer());
		List<UUID> toRecord = new ArrayList<>();
		Map<UUID, Integer> scores = new HashMap<>();
		Map<UUID, Integer> outfits = new HashMap<>();
		for (UUID u : players) {
			if (leftPlayers.contains(u) || resolvedPlayers.contains(u)) continue;
			toRecord.add(u);
			scores.put(u, battleDealt.getOrDefault(names.get(u), 0));
			ServerPlayerEntity p = player(u);
			if (p != null) outfits.put(u, Outfit.of(p, lang).level());
		}
		if (toRecord.isEmpty()) return;
		long overworldDay = ProgressionRules.currentDay(world.getServer().getOverworld().getTime());
		Map<UUID, List<String>> titles = stats.record(toRecord, scores, mvpPlayer == null ? null : mvpPlayer.getUuid(),
			result.playersWin, opp, this.rank, outfits, overworldDay);
		titles.forEach((u, list) -> {
			for (String t : list) {
				for (ServerPlayerEntity p : world.getPlayers()) {
					p.sendMessage(Text.translatable("rapbattle.battle.earned_title", names.get(u), Stats.translateTitle(t, Lang.of(p)))
						.formatted(Formatting.LIGHT_PURPLE), false);
				}
			}
		});
		for (UUID u : players) {
			ServerPlayerEntity p = player(u);
			if (p != null) notifyStyleUnlock(stats, u, p);
		}
	}

	static void notifyStyleUnlock(Stats stats, UUID u, ServerPlayerEntity p) {
		Stats.Player st = stats.player(u);
		if (st.respect >= McStyle.METRONOME.unlockRespect && !st.hinted500) {
			st.hinted500 = true;
			stats.markDirty();
			sendStyleHint(p, McStyle.METRONOME);
		}
		if (st.respect >= McStyle.PROVOKER.unlockRespect && !st.hinted1500) {
			st.hinted1500 = true;
			stats.markDirty();
			sendStyleHint(p, McStyle.PROVOKER);
		}
	}

	private static void sendStyleHint(ServerPlayerEntity p, McStyle style) {
		String pLang = Lang.of(p);
		Text btn = Text.translatable("rapbattle.act1.style_btn").styled(s -> s
			.withColor(Formatting.GOLD)
			.withUnderline(true)
			.withClickEvent(new net.minecraft.text.ClickEvent.RunCommand("/rapbattle style")));
		p.sendMessage(Text.translatable("rapbattle.act1.style_unlocked", style.label(pLang), style.unlockRespect, btn)
			.formatted(Formatting.GREEN), false);
	}

	/** The boss's last word and its signature attack, then the death the team signed up for. */
	private void bossFinisher(ServerPlayerEntity p) {
		String id = opp.profile().id();
		String line = Lang.isRu(lang) ? switch (id) {
			case "ender_dragon" -> "Край вечен. Ты — нет.";
			case "wither" -> "Три головы. Ноль шансов.";
			case "warden" -> "*тук-тук*… тишина…";
			default -> "Ты пришёл не по адресу.";
		} : switch (id) {
			case "ender_dragon" -> "The End is eternal. You are not.";
			case "wither" -> "Three heads. Zero chance.";
			case "warden" -> "*thump-thump*… silence…";
			default -> "You came to the wrong place.";
		};
		Speech.bubble(mob, line, 0xB48CFF, 3000);
		var sound = switch (id) {
			case "ender_dragon" -> net.minecraft.sound.SoundEvents.ENTITY_ENDER_DRAGON_GROWL;
			case "wither" -> net.minecraft.sound.SoundEvents.ENTITY_WITHER_SHOOT;
			case "warden" -> net.minecraft.sound.SoundEvents.ENTITY_WARDEN_SONIC_BOOM;
			default -> net.minecraft.sound.SoundEvents.ENTITY_ELDER_GUARDIAN_CURSE;
		};
		world.playSound(null, p.getBlockPos(), sound, net.minecraft.sound.SoundCategory.HOSTILE, 2f, 1f);
		net.minecraft.particle.ParticleEffect fx = switch (id) {
			case "ender_dragon" -> net.minecraft.particle.ParticleTypes.PORTAL;
			case "wither" -> net.minecraft.particle.ParticleTypes.SMOKE;
			case "warden" -> net.minecraft.particle.ParticleTypes.SONIC_BOOM;
			default -> net.minecraft.particle.ParticleTypes.ELDER_GUARDIAN;
		};
		// A line of particles from the boss to the player, like the attack itself.
		Vec3d from = new Vec3d(mob.getX(), mob.getY() + mob.getHeight() * 0.7, mob.getZ());
		Vec3d to = new Vec3d(p.getX(), p.getY() + 1, p.getZ());
		for (int i = 0; i <= 12; i++) {
			Vec3d at = from.lerp(to, i / 12.0);
			world.spawnParticles(fx, at.x, at.y, at.z, 3, 0.1, 0.1, 0.1, 0.01);
		}
		UUID u = p.getUuid();
		Later.run(2000, () -> {
			ServerPlayerEntity pl = player(u);
			if (pl == null || !pl.isAlive()) return;
			if (cfg.bossLossKills) {
				DamageSource src = new DamageSource(world.getRegistryManager().getOrThrow(RegistryKeys.DAMAGE_TYPE).getOrThrow(RAP_DEATH), mob);
				pl.damage(world, src, Float.MAX_VALUE);
			} else {
				BattleManager.forceStatusEffect(pl, new StatusEffectInstance(StatusEffects.WITHER, 20 * 30, 1));
				pl.addExperienceLevels(-5);
			}
		});
	}

	private void punishPlayers() {
		for (UUID u : players) {
			if (leftPlayers.contains(u)) continue;
			ServerPlayerEntity p = player(u);
			if (p == null) continue;
			switch (rank) {
				case LEATHER, CHAIN -> BattleManager.forceStatusEffect(p, new StatusEffectInstance(StatusEffects.WEAKNESS, 20 * 60, 0));
				case IRON, GOLD -> {
					BattleManager.forceStatusEffect(p, new StatusEffectInstance(StatusEffects.WEAKNESS, 20 * 60, 0));
					p.addExperienceLevels(rank == Rank.IRON ? -1 : -2);
				}
				case DIAMOND -> {
					BattleManager.forceStatusEffect(p, new StatusEffectInstance(StatusEffects.WEAKNESS, 20 * 90, 1));
					p.addExperienceLevels(-2);
				}
				case NETHERITE -> {
					bossFinisher(p);
					continue;
				}
			}
			// Lost on the mic, lost some health too: never below half a heart.
			float hp = Math.min(rank.heartsOnLoss * 2f, p.getHealth() - 1);
			if (hp > 0) {
				DamageSource src = new DamageSource(world.getRegistryManager().getOrThrow(RegistryKeys.DAMAGE_TYPE).getOrThrow(RAP_DEATH), mob);
				p.damage(world, src, hp);
				DamagePop.spawn(world, p, "-" + Math.round(hp / 2) + " ❤", 0xFF5050);
			}
			BattleManager.forceStatusEffect(p, new StatusEffectInstance(StatusEffects.NAUSEA, 20 * 2, 0));
			BattleManager.forceStatusEffect(p, new StatusEffectInstance(StatusEffects.SPEED, 20 * 5, 0));
		}
	}
}
