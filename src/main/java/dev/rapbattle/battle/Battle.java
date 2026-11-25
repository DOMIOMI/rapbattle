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

	static final ExecutorService POOL = Executors.newCachedThreadPool(r -> {
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
	private final boolean boss;
	private final int rounds, winsNeeded;
	private final Random rnd = new Random();
	private final RapConfig cfg = RapConfig.get();
	private final long beatMs;
	/** The real beat this battle plays; the battle's tempo is the track's tempo. */
	private final BeatTracks.Track track;

	final List<UUID> players = new ArrayList<>();
	private final Map<UUID, String> names = new HashMap<>();
	final Set<UUID> invited = new HashSet<>();
	private final Map<UUID, Vec3d> anchors = new HashMap<>();
	private final Set<UUID> spectators = new HashSet<>();

	/** Mob team: the challenged mob first, then kin that joined. */
	final List<MobEntity> mobTeam = new ArrayList<>();
	private final Map<MobEntity, String> mobNames = new HashMap<>();
	private final Map<MobEntity, Boolean> hadNoAi = new HashMap<>();
	private final Map<MobEntity, Boolean> wasSilent = new HashMap<>();
	private final Set<Entity> glowing = new HashSet<>();
	private final Set<MobEntity> dressed = new HashSet<>();
	/** Mobs around the stage watching and reacting. */
	Crowd crowd;
	private final Set<Integer> punchesFired = new HashSet<>();
	private Entity currentReader;

	private Phase phase = Phase.LOBBY;
	private long phaseStart = System.currentTimeMillis();
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
	private boolean overdressed;

	private final Map<UUID, ByteArrayOutputStream> audio = new HashMap<>();
	private final Map<UUID, CompletableFuture<String>> transcripts = new HashMap<>();
	/** Word timestamps from speech recognition, and where each recording starts on the battle clock. */
	private final Map<UUID, dev.rapbattle.ai.Transcriber.Result> timed = new java.util.concurrent.ConcurrentHashMap<>();
	private final Map<UUID, Integer> recordingStart = new java.util.concurrent.ConcurrentHashMap<>();
	private volatile boolean sttMissing;
	/** Warden: noises the team made while he was reading. */
	private int noise;
	/** Crowd: rhyme slots players hit loudly / missed in silence this round. */
	int barsHit, barsMissed;
	/** Respect: the battle's health bars. Not reset between rounds. */
	int respectTeam, respectMob, respectMax;
	/** The team's run of lines on the beat (x1.5 from the 3rd, x2 from the 6th). */
	private int combo, bestCombo;
	private int dealtTotal, linesOnBeat, linesTotal, roundToMob, roundToTeam, bestLineDamage;
	private String bestLine = "";
	private final Map<String, Integer> roundDealt = new HashMap<>();
	private final Map<String, Integer> battleDealt = new HashMap<>();
	private final Set<Integer> mobLinesFired = new HashSet<>();
	private int allyBonus;
	private CompletableFuture<Llm.Verdict> verdictFuture;
	private long judgingStart, verdictStart;
	private BattleView.Result result;

	public Battle(ServerPlayerEntity challenger, MobEntity mob) {
		this.lang = Lang.of(challenger);
		this.world = (ServerWorld) mob.getEntityWorld();
		this.mob = mob;
		this.opp = Opponent.of(mob, rnd, challenger, lang);
		this.rank = opp.rank();
		this.boss = rank.isBoss();
		this.rounds = boss ? 3 : 1;
		this.winsNeeded = boss ? 2 : 1;
		this.respectMax = boss ? 200 : 100;
		this.respectTeam = respectMax;
		this.respectMob = respectMax;
		long wantMs = Math.round(60000.0 / Math.max(40, Math.min(220, rank.bpm * cfg.tempoScale * tempoOf(opp.profile().id()))));
		this.track = pickTrack(60000.0 / wantMs, rank.isBoss());
		this.beatMs = track != null ? track.beatMs() : wantMs;
		this.lobbyEnd = phaseStart + cfg.lobbySeconds * 1000L * (boss ? 2 : 1);
		addMob(mob, opp.name());
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
		if (Gangs.dressUp(m)) dressed.add(m); // crew bandana on stage
	}

	private void addPlayer(ServerPlayerEntity p) {
		players.add(p.getUuid());
		names.put(p.getUuid(), p.getName().getString());
		anchors.put(p.getUuid(), new Vec3d(p.getX(), p.getY(), p.getZ()));
		invited.remove(p.getUuid());
		spectators.remove(p.getUuid());
	}

	public boolean hasPlayer(UUID uuid) { return players.contains(uuid); }

	public boolean hasMob(Entity e) { return e instanceof MobEntity m && mobTeam.contains(m); }

	public Phase phase() { return phase; }

	public ServerPlayerEntity challenger() { return player(players.get(0)); }

	private ServerPlayerEntity player(UUID uuid) { return world.getServer().getPlayerManager().getPlayer(uuid); }

	private List<String> namesList() {
		List<String> out = new ArrayList<>();
		for (UUID u : players) out.add(names.get(u));
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
		if (accept && phase == Phase.LOBBY && !hasPlayer(p.getUuid())) {
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
		if (phase == Phase.LOBBY && p.getUuid().equals(players.get(0))) lobbyEnd = System.currentTimeMillis();
	}

	public void onPlayerLeft(UUID uuid) {
		if (!hasPlayer(uuid)) {
			invited.remove(uuid);
			spectators.remove(uuid);
			return;
		}
		if (players.size() == 1 || phase == Phase.LOBBY && uuid.equals(players.get(0))) {
			abort(Text.translatable("rapbattle.battle.abort_player_left"));
			return;
		}
		// Coop partner left mid-battle: keep going, their verse just counts as silence.
		if (!transcripts.containsKey(uuid)) transcripts.put(uuid, CompletableFuture.completedFuture(""));
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
		for (double y : new double[]{0, 1, -1}) {
			Vec3d at = new Vec3d(target.x, Math.floor(m.y) + y, target.z);
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
			for (double y : new double[]{0, 1, -1}) {
				Vec3d at = new Vec3d(target.x, Math.floor(m.y) + y, target.z);
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
		// Dress code bonus / overdressed check, from the team's average outfit.
		double sum = 0;
		for (UUID u : players) {
			ServerPlayerEntity p = player(u);
			if (p != null) sum += Outfit.of(p, lang).value();
		}
		double avg = sum / Math.max(1, players.size());
		int above = (int) Math.floor(avg) - Outfit.required(rank);
		outfitBonus = Math.max(0, Math.min(3, above));
		// Someone on the team wears the mob's crew colours: "свой на районе".
		for (UUID u : players) {
			ServerPlayerEntity p = player(u);
			if (p != null && Gangs.isOwn(p, opp.profile())) {
				outfitBonus = Math.min(4, outfitBonus + 1);
				broadcastChat(Text.translatable("rapbattle.battle.gang_colors", p.getName().getString(), Gangs.of(opp.profile()).name(lang))
					.formatted(Formatting.GOLD));
				break;
			}
		}
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
		ByteArrayOutputStream buf = audio.computeIfAbsent(u, k -> new ByteArrayOutputStream());
		if (buf.size() + chunk.data().length > 8 * 1024 * 1024) return;
		buf.writeBytes(chunk.data());
		if (chunk.last()) {
			byte[] wav = buf.toByteArray();
			audio.remove(u);
			recordingStart.put(u, chunk.startMs());
			List<String> hints = rhymeWords.getOrDefault(u, List.of());
			transcripts.put(u, CompletableFuture.supplyAsync(() -> {
				Transcriber.Result r = Transcriber.transcribeTimed(wav, hints, lang);
				if (r == null) {
					sttMissing = true;
					return "";
				}
				timed.put(u, r);
				return r.text();
			}, POOL));
		}
	}

	// ------------------------------------------------------------------ events reported by clients

	/** Creeper: a player stayed silent for a whole bar of their own. The fuse burns out. */
	public void onSilentBar(ServerPlayerEntity p) {
		if (phase != Phase.TIMELINE || !hasPlayer(p.getUuid()) || !(mob instanceof net.minecraft.entity.mob.CreeperEntity)) return;
		broadcastChat(Text.translatable("rapbattle.battle.creeper_silent_chat", p.getName().getString()).formatted(Formatting.RED));
		forfeit(Lang.t(lang, "rapbattle.battle.creeper_silent_comment"));
		SoreLosers.blast(mob, 1500);
	}

	/** Warden: noise from a player's mic while he was reading. */
	public void onNoise(ServerPlayerEntity p, int count) {
		if (hasPlayer(p.getUuid()) && opp.profile().id().equals("warden")) noise += Math.max(0, Math.min(10, count));
	}

	/** Rhymes on the beat: 2 each (1 for "almost"), and a run of them multiplies: x1.5 from 3 in a row, x2 from 6. */
	static int flowDamage(int[] tm) {
		double sum = 0;
		int streak = 0;
		for (int x : tm) {
			if (x == 2) {
				streak++;
				sum += 2 * (streak >= 6 ? 2.0 : streak >= 3 ? 1.5 : 1.0);
			} else {
				streak = 0;
				if (x == 1) sum += 1;
			}
		}
		return (int) Math.round(sum);
	}

	/** One rhyme slot of a player: said loud on the beat, or silence. Feeds the crowd. */
	public void onBar(ServerPlayerEntity p, boolean loud) {
		if (!hasPlayer(p.getUuid()) || phase != Phase.TIMELINE) return;
		if (loud) barsHit++;
		else barsMissed++;
		linesTotal++;
		String who = p.getName().getString();
		if (loud) {
			combo++;
			bestCombo = Math.max(bestCombo, combo);
			linesOnBeat++;
			// Volume alone deals nothing now: the judge and the beat timing do. It only hypes the crowd and the combo.
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
			String target = ru ? stemRu(row.word) : lastWordEn(row.word);
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
		if (w.equals(target)) return true;
		if (w.equals(target + "s") || w.equals(target + "es") || w.equals(target + "ed") || w.equals(target + "d")) return true;
		if (target.equals(w + "s") || target.equals(w + "es") || target.equals(w + "ed") || target.equals(w + "d")) return true;
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
	public void violation(ServerPlayerEntity p) {
		if (finished || phase == Phase.RESULT) return;
		broadcastChat(Text.translatable("rapbattle.battle.street_law_chat", p.getName().getString()).formatted(Formatting.DARK_RED));
		Speech.bubble(mob, Lang.t(lang, "rapbattle.battle.street_law_bubble"), 0xFF4040, 3500);
		streetLaw = true;
		if (crowd != null) for (MobEntity m : crowd.fighters(Crowd.Side.MOB)) m.setTarget(p);
		forfeit(Lang.t(lang, "rapbattle.battle.street_law_comment", p.getName().getString()));
	}

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
		setPhase(Phase.RESULT, System.currentTimeMillis());
	}

	// ------------------------------------------------------------------ tick

	/**
	 * Nobody near the stage goes for the battlers until the battle is over: a zombie that walks up (or a villager the
	 * crowd turned into a zombie villager) drops its target instead of swinging at someone who is busy rapping.
	 */
	private boolean streetLaw;

	private void keepThePeace() {
		if (streetLaw) return; // someone threw a punch: the mob's people are allowed to come for him
		var box = mob.getBoundingBox().expand(24, 8, 24);
		for (MobEntity m : world.getEntitiesByClass(MobEntity.class, box, m -> m.getTarget() instanceof ServerPlayerEntity)) {
			if (players.contains(m.getTarget().getUuid())) {
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
		long now = System.currentTimeMillis();

		if (!mob.isAlive() || mob.isRemoved()) {
			abort(Text.translatable("rapbattle.battle.abort_mob_gone"));
			return;
		}
		mobTeam.removeIf(m -> m != mob && (!m.isAlive() || m.isRemoved()));
		keepThePeace();
		for (UUID u : players) {
			ServerPlayerEntity p = player(u);
			if (p == null || !p.isAlive()) continue;
			// Players stand still during the battle but can look around.
			Vec3d a = anchors.get(u);
			if (a != null && p.squaredDistanceTo(a) > 0.25) p.networkHandler.requestTeleport(a.x, a.y, a.z, p.getYaw(), p.getPitch());
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
					// The boss catches his breath between rounds.
					if (boss) respectMob = Math.min(respectMax, respectMob + 20);
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

	/** The crowd goes "ооо" right on the rhyme of a mob punchline. */
	private void firePunches(long now) {
		int beat = (int) Math.floorDiv(now - beatZero, beatMs);
		for (int i = 0; i < view.rows.size(); i++) {
			BattleView.Row r = view.rows.get(i);
			if (r.punch && beat == r.startBeat + 3 && punchesFired.add(i)) Crowd.react(this, Crowd.Moment.MOB_PUNCH);
			if (r.mob && beat == r.startBeat + 3 && mobLinesFired.add(i)) {
				int d = rank.lineDamage * (r.punch ? 2 : 1);
				liveHit(false, d, null);
				for (UUID u : players) DamagePop.spawn(world, player(u), "-" + d + (r.punch ? " " + Lang.t(lang, "rapbattle.battle.punch_pop") : ""), 0xFF6060);
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
		punchesFired.clear();
		mobLinesFired.clear();
		timed.clear();
		recordingStart.clear();
		roundToMob = 0;
		roundToTeam = 0;
		roundDealt.clear();
		barsHit = 0;
		barsMissed = 0;
		transcripts.clear();
		audio.clear();
		verdictFuture = null;
		result = null;
		List<Llm.Rapper> rappers = new ArrayList<>();
		for (MobEntity m : mobTeam) {
			MobProfiles.Profile p = MobProfiles.of(m.getType());
			rappers.add(new Llm.Rapper(mobNames.get(m), p.species(lang), p.adlib(lang), p.style(lang)));
		}
		// Usually the rank's length, sometimes a longer verse (two more lines), never shorter.
		int lineCount = rank.mobLines + Math.max(0, players.size() - 1) + (rnd.nextInt(3) == 0 ? 2 : 0);
		List<String> opponents = namesList();
		int groups = 0;
		for (int i = 0; i < players.size(); i++) groups += (playerLines() + 3) / 4;
		int groupsNeeded = groups;
		String prev = history.toString();
		String rivalry = rivalryText();
		int r = round;
		contentFuture = CompletableFuture.supplyAsync(() -> {
			Llm.Content c = Llm.roundContent(rappers, rank.label(lang), opp.mood(), opp.profile().language(lang), lineCount,
				opponents, groupsNeeded, rank.rhymeDifficulty, r, prev, rivalry, lang);
			if (c == null) {
				List<String> speakers = new ArrayList<>();
				for (Llm.Rapper rp : rappers) speakers.add(rp.name());
				c = new Llm.Content(Fallback.mobVerse(speakers, opponents, lineCount, rnd, lang), List.of());
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
		return Math.max(1, players.size() > 1 ? rank.playerLinesCoop : rank.playerLinesSolo);
	}

	private void buildTimeline(Llm.Content content, long zero) {
		beatZero = zero;
		List<String> speakers = mobTeamNames();
		List<Llm.MobLine> lines = content != null ? content.lines()
			: Fallback.mobVerse(speakers, namesList(), rank.mobLines, rnd, lang);
		mobLines = lines;
		BattleView v = baseView();
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

		// Rhymes: generated ones for harder ranks, the curated bank for easy ones and as a top-up.
		int perPlayer = playerLines();
		int blocks = (perPlayer + 3) / 4;
		List<List<String>> groups = new ArrayList<>();
		if (content != null) groups.addAll(content.rhymes());
		Collections.shuffle(groups, rnd);
		if (groups.size() < blocks * players.size()) groups.addAll(RhymeBank.pickBlocks(blocks * players.size() - groups.size(), rnd, lang));
		Map<UUID, List<String>> words = new LinkedHashMap<>();
		int g = 0;
		for (UUID u : players) {
			List<String> w = new ArrayList<>();
			for (int b = 0; b < blocks; b++) w.addAll(groups.get(g++));
			words.put(u, w.subList(0, perPlayer));
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
		boolean pingPong = players.size() > 1 && rank.level() >= 4;
		if (pingPong) {
			// Friends alternate line by line after one shared get-ready bar.
			int leadIn = beat;
			beat += rank.leadInBeats;
			Map<UUID, BattleView.Segment> segs = new LinkedHashMap<>();
			for (int i = 0; i < perPlayer; i++) {
				for (int pi = 0; pi < players.size(); pi++) {
					UUID u = players.get(pi);
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
			for (UUID u : players) {
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
		view = v;
	}

	private BattleView.Row playerRow(UUID u, int i, int total, String word, int color, int beat) {
		BattleView.Row r = new BattleView.Row();
		r.word = word;
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

	private void tickJudging(long now) {
		long t = now - judgingStart;
		if (t > AUDIO_WAIT_MS) {
			for (UUID u : players) transcripts.putIfAbsent(u, CompletableFuture.completedFuture(""));
		}
		if (verdictFuture == null) {
			boolean allIn = players.stream().allMatch(transcripts::containsKey);
			boolean allDone = allIn && players.stream().allMatch(u -> transcripts.get(u).isDone());
			if (!allDone && t < AUDIO_WAIT_MS + STT_WAIT_MS) return;
			List<Llm.PlayerVerse> verses = new ArrayList<>();
			for (UUID u : players) {
				CompletableFuture<String> f = transcripts.get(u);
				String text = f != null ? f.getNow("") : "";
				int[] tm = beatTiming(u);
				String note = tm == null || text == null || text.isBlank() ? ""
					: Lang.isRu(lang)
						? " [по таймингу: в бит " + count(tm, 2) + " из " + tm.length + " рифм, рядом с долей " + count(tm, 1) + "]"
						: " [timing: on beat " + count(tm, 2) + " of " + tm.length + " rhymes, near beat " + count(tm, 1) + "]";
				verses.add(new Llm.PlayerVerse(names.get(u), rhymeWords.getOrDefault(u, List.of()), text == null ? "" : text + note));
			}
			List<String> mobCopy = new ArrayList<>();
			for (Llm.MobLine l : mobLines) mobCopy.add(l.speaker() + ": " + l.text());
			boolean noStt = sttMissing;
			verdictStart = now;
			verdictFuture = CompletableFuture.supplyAsync(() -> {
				String expectation = rank.judgeExpectation(lang) + " " + MobTricks.judgeHint(opp.profile().id(), lang);
				Llm.Verdict v = noStt ? null : Llm.judge(opp.name(), opp.profile().species(lang), rank.label(lang), expectation,
					rank.verseLo, rank.verseHi, mobCopy, verses, lang);
				if (v == null) {
					v = Fallback.judge(rank.verseLo, rank.verseHi, verses, rnd, lang);
					if (noStt) v = new Llm.Verdict(v.mobVerse(), v.playerLines(),
						Lang.t(lang, "rapbattle.battle.no_stt_prefix") + v.comment());
				}
				return v;
			}, POOL);
			result = new BattleView.Result();
			for (Llm.PlayerVerse v : verses) {
				result.players.add(new BattleView.PlayerScore(v.name(), 0, v.transcript().isBlank() ? Lang.t(lang, "rapbattle.battle.silence") : v.transcript()));
				history.append(v.name()).append(": ").append(v.transcript().isBlank() ? Lang.t(lang, "rapbattle.battle.silent_history") : v.transcript()).append('\n');
			}
			return;
		}
		if (t < MIN_JUDGING_MS) return;
		if (!verdictFuture.isDone() && now - verdictStart < JUDGE_WAIT_MS) return;
		Llm.Verdict v = verdictFuture.getNow(null);
		if (v == null) v = Fallback.judge(rank.verseLo, rank.verseHi, List.of(), rnd, lang);

		// The breakdown: every player line hits the mob, the bonuses too; the mob's verse hits the team.
		int mobVerse = Math.max(rank.verseLo, Math.min(rank.verseHi, v.mobVerse()));
		result.teamBefore = respectTeam;
		result.mobBefore = respectMob;
		result.respectMax = respectMax;
		List<BattleView.Hit> hits = result.hits;
		for (BattleView.PlayerScore ps : result.players) {
			List<String> words = List.of();
			for (UUID u : players) if (names.get(u).equals(ps.name)) words = rhymeWords.getOrDefault(u, List.of());
			List<Llm.LineScore> ls = v.playerLines().getOrDefault(ps.name, List.of());
			for (int i = 0; i < ls.size(); i++) {
				Llm.LineScore l = ls.get(i);
				String word = i < words.size() ? words.get(i) : "";
				// Weak bars bounce off strong mobs: below the rank's bar a line deals nothing, the rest goes through its defence.
				int d = l.score() < rank.minLineScore() ? 0 : (int) Math.round(l.damage() * rank.defense());
				hits.add(new BattleView.Hit(ps.name, (word.isEmpty() ? "" : "«" + word + "» · ") + l.note(), d, true, l.punch()));
				roundDealt.merge(ps.name, d, Integer::sum);
				battleDealt.merge(ps.name, d, Integer::sum);
				if (d > bestLineDamage) {
					bestLineDamage = d;
					bestLine = ps.name + ": «" + word + "» · " + l.note();
				}
			}
			ps.score = roundDealt.getOrDefault(ps.name, 0);
		}
		int onBeatTotal = 0, timedTotal = 0;
		for (UUID u : players) {
			int[] tm = beatTiming(u);
			if (tm == null) continue;
			int on = count(tm, 2), near = count(tm, 1);
			onBeatTotal += on;
			timedTotal += tm.length;
			int d = flowDamage(tm);
			if (d > 0) {
				hits.add(new BattleView.Hit(names.get(u),
					Lang.t(lang, "rapbattle.battle.hit_rhymes_on_beat", on, tm.length)
						+ (near > 0 ? Lang.t(lang, "rapbattle.battle.hit_rhymes_near", near) : ""),
					d, true, false));
				roundDealt.merge(names.get(u), d, Integer::sum);
				battleDealt.merge(names.get(u), d, Integer::sum);
			}
		}
		if (outfitBonus > 0) hits.add(new BattleView.Hit("", Lang.t(lang, "rapbattle.battle.hit_outfit"), outfitBonus, true, false));
		if (allyBonus > 0) hits.add(new BattleView.Hit("", Lang.t(lang, "rapbattle.battle.hit_backup"), allyBonus, true, false));
		int prize = crowd == null ? 0 : crowd.prize();
		if (prize > 0) hits.add(new BattleView.Hit("", Lang.t(lang, "rapbattle.battle.hit_crowd"), prize, true, false));
		hits.add(new BattleView.Hit(opp.name(), Lang.t(lang, "rapbattle.battle.hit_mob_verse"), mobVerse * 3, false, false));
		// Warden heard the team's noise during his verse.
		if (noise > 0) {
			hits.add(new BattleView.Hit(opp.name(), Lang.t(lang, "rapbattle.battle.hit_warden_noise", noise), 2 * noise, false, false));
			noise = 0;
		}
		for (BattleView.Hit h : hits) {
			if (h.toMob) {
				respectMob = Math.max(0, respectMob - h.damage);
				roundToMob += h.damage;
				dealtTotal += h.damage;
			} else {
				respectTeam = Math.max(0, respectTeam - h.damage);
				roundToTeam += h.damage;
			}
		}
		result.respectTeam = respectTeam;
		result.respectMob = respectMob;
		boolean ko = respectMob <= 0 || respectTeam <= 0;
		boolean roundPlayers = respectMob <= 0 || (respectTeam > 0 && roundToMob > roundToTeam);
		if (roundPlayers) winsPlayers++;
		else winsMob++;
		boolean done = ko || winsPlayers >= winsNeeded || winsMob >= winsNeeded || round >= rounds;
		result.knockout = ko;
		result.finalResult = done;
		if (boss) {
			result.playersWin = done
				? (respectMob <= 0 ? true : respectTeam <= 0 ? false : winsPlayers > winsMob)
				: roundPlayers;
		} else {
			result.playersWin = done ? (respectMob <= 0 || (respectTeam > 0 && respectTeam > respectMob)) : roundPlayers;
		}
		result.comment = v.comment();
		result.mvp = players.size() > 1 ? battleDealt.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("") : "";
		result.outfitBonus = outfitBonus;
		result.overdressed = overdressed;
		result.mobLabel = mobTeam.size() > 1 ? Lang.t(lang, "rapbattle.battle.mob_and_crew", opp.profile().species(lang)) : opp.profile().species(lang);
		result.teamLabel = String.join(" + ", namesList());
		result.dealt = dealtTotal;
		// Accuracy: from the word timings when speech recognition gave them, from the mic level otherwise.
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
	}

	// ------------------------------------------------------------------ sync

	private BattleView baseView() {
		BattleView v = new BattleView();
		v.id = id;
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
		v.lobbyMsLeft = phase == Phase.LOBBY ? Math.max(0, lobbyEnd - System.currentTimeMillis()) : 0;
		v.beatElapsedMs = timeline ? System.currentTimeMillis() - beatZero : -1;
		v.spectator = false;
		for (UUID u : players) {
			ServerPlayerEntity p = player(u);
			if (p != null) send(p, v);
		}
		// Bystanders: beat, header and result only.
		double r2 = cfg.spectatorRadius * cfg.spectatorRadius;
		BattleView sv = v.copy();
		sv.spectator = true;
		sv.rows = new ArrayList<>(v.rows);
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
		if (crowd != null) crowd.release();
		for (MobEntity m : dressed) Gangs.undress(m);
		dressed.clear();
		for (MobEntity m : mobTeam) {
			if (m.isAlive()) {
				m.setAiDisabled(hadNoAi.getOrDefault(m, false));
				m.setSilent(wasSilent.getOrDefault(m, false));
			}
		}
		clearGlow();
	}

	public void abort(Text reason) {
		if (finished) return;
		finished = true;
		releaseMobs();
		broadcastChat(reason.copy().formatted(Formatting.GRAY));
		sendEnd();
	}

	public void abort(String reason) {
		abort(Text.literal(reason));
	}

	private void finish() {
		finished = true;
		sendEnd();
		releaseMobs();
		BattleManager.recent(this);
		String team = String.join(" + ", namesList());
		ServerPlayerEntity mvpPlayer = playerByName(result.mvp.isEmpty() ? names.get(players.get(0)) : result.mvp);
		if (mvpPlayer == null) mvpPlayer = challenger();
		recordStats(mvpPlayer);
		if (result.playersWin) {
			MobRecord.loss(mob);
			rememberPlayers(false);
			Text verb = players.size() > 1
				? Text.translatable("rapbattle.battle.team_win_verb." + rnd.nextInt(TEAM_WIN_VERBS))
				: Text.translatable("rapbattle.battle.win_verb." + rnd.nextInt(WIN_VERBS));
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
			Trophies.give(killer, opp, lang);
			if (mob instanceof net.minecraft.entity.passive.IronGolemEntity golem) {
				// The village golem is a character: it accepts the defeat instead of dying.
				GolemDefender.respect(golem, killer, lang);
				ExperienceOrbEntity.spawn(world, pos, xp);
				return;
			}
			// Some mobs refuse to accept it and go for the MVP instead of dying.
			int margin = respectTeam - respectMob;
			// A knockout or a clear win finishes the mob; a close one only hurts it, and it walks away.
			boolean lethal = result.knockout || margin >= 40 || boss;
			if (!lethal) {
				DamageSource rap = new DamageSource(world.getRegistryManager().getOrThrow(RegistryKeys.DAMAGE_TYPE).getOrThrow(RAP_DEATH));
				for (MobEntity m : new ArrayList<>(mobTeam)) {
					float frac = (float) Math.max(0.3, Math.min(0.9, 0.3 + margin / 60.0));
					float dmg = Math.min(m.getMaxHealth() * frac, m.getHealth() - 1);
					if (dmg > 0) {
						m.damage(world, rap, dmg);
						DamagePop.spawn(world, m, "-" + Math.max(1, Math.round(dmg / 2)) + " ❤", 0xFF5050);
					}
				}
				broadcastChat(Text.translatable("rapbattle.battle.mob_escaped", opp.name(),
					Text.translatable("rapbattle.battle.escaped." + rnd.nextInt(ESCAPED_LINES))).formatted(Formatting.GRAY));
				ExperienceOrbEntity.spawn(world, pos, Math.max(1, xp / 2));
				return;
			}
			boolean raged = SoreLosers.tryRage(opp, MobRecord.of(mob), margin, killer, rnd, lang);
			// The battle is already marked finished, so the damage guard no longer protects the mob team.
			for (MobEntity m : new ArrayList<>(mobTeam)) {
				if (raged && m == mob) continue;
				DamageSource src = killer != null ? world.getDamageSources().playerAttack(killer) : world.getDamageSources().generic();
				Vec3d at = new Vec3d(m.getX(), m.getY(), m.getZ());
				m.damage(world, src, m.getMaxHealth() * 100f);
				// Street level and up: the loot comes doubled.
				if (rank.level() >= 3 && !overdressed && !m.isAlive()) SoreLosers.doubleFreshDrops(world, at);
			}
			ExperienceOrbEntity.spawn(world, pos, xp * Math.max(1, players.size()));
			// A piece of the crew's gear for the winner.
			Gangs.Gang gang = Gangs.of(opp.profile());
			if (gang != null && !overdressed && rnd.nextInt(100) < Gangs.dropChance(rank)) {
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
			Text verb = Text.translatable("rapbattle.battle.win_verb." + rnd.nextInt(WIN_VERBS));
			Text msg = result.knockout
				? Text.translatable("rapbattle.battle.win_ko", opp.name(), verb, team)
				: Text.translatable("rapbattle.battle.win_pts", opp.name(), verb, team, boss ? winsMob : respectMob, boss ? winsPlayers : respectTeam);
			broadcastChat(msg.copy().formatted(Formatting.RED));
			punishPlayers();
			if (!boss) {
				int wins = MobRecord.of(mob).wins();
				mob.setCustomName(Text.literal(opp.name() + " ♛" + (wins > 1 ? "×" + wins : "")).formatted(Formatting.GOLD));
				mob.setCustomNameVisible(true);
				mob.setPersistent();
			}
			// Lost to a villager: the village golem takes it personally.
			if (mob instanceof net.minecraft.entity.passive.VillagerEntity villager && challenger() != null) {
				GolemDefender.offended(challenger(), villager);
			}
		}
		RapBattle.LOG.info("Battle {} finished: players {} respect {} vs mob {} (ko {}, rank {})", id, result.playersWin ? "won" : "lost",
			respectTeam, respectMob, result.knockout, rank);
	}

	private void recordStats(ServerPlayerEntity mvpPlayer) {
		Stats stats = Stats.of(world.getServer());
		Map<UUID, Integer> scores = new HashMap<>();
		Map<UUID, Integer> outfits = new HashMap<>();
		for (UUID u : players) {
			scores.put(u, battleDealt.getOrDefault(names.get(u), 0));
			ServerPlayerEntity p = player(u);
			if (p != null) outfits.put(u, Outfit.of(p, lang).level());
		}
		Map<UUID, List<String>> titles = stats.record(players, scores, mvpPlayer == null ? null : mvpPlayer.getUuid(),
			result.playersWin, opp, outfits);
		titles.forEach((u, list) -> {
			for (String t : list) {
				for (ServerPlayerEntity p : world.getPlayers()) {
					p.sendMessage(Text.translatable("rapbattle.battle.earned_title", names.get(u), Stats.translateTitle(t, Lang.of(p)))
						.formatted(Formatting.LIGHT_PURPLE), false);
				}
			}
		});
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
				pl.addStatusEffect(new StatusEffectInstance(StatusEffects.WITHER, 20 * 30, 1));
				pl.addExperienceLevels(-5);
			}
		});
	}

	private void punishPlayers() {
		for (UUID u : players) {
			ServerPlayerEntity p = player(u);
			if (p == null) continue;
			switch (rank) {
				case LEATHER, CHAIN -> p.addStatusEffect(new StatusEffectInstance(StatusEffects.WEAKNESS, 20 * 60, 0));
				case IRON, GOLD -> {
					p.addStatusEffect(new StatusEffectInstance(StatusEffects.WEAKNESS, 20 * 60, 0));
					p.addExperienceLevels(rank == Rank.IRON ? -1 : -2);
				}
				case DIAMOND -> {
					p.addStatusEffect(new StatusEffectInstance(StatusEffects.WEAKNESS, 20 * 90, 1));
					p.addExperienceLevels(-2);
					ItemStack held = p.getMainHandStack();
					if (!held.isEmpty() && mob.getEquippedStack(EquipmentSlot.MAINHAND).isEmpty()) {
						// The legend keeps your item as a trophy; win a rematch to get it back.
						mob.equipStack(EquipmentSlot.MAINHAND, held.copy());
						mob.setDropGuaranteed(EquipmentSlot.MAINHAND);
						p.getInventory().setStack(p.getInventory().getSelectedSlot(), ItemStack.EMPTY);
						p.sendMessage(Text.translatable("rapbattle.battle.mob_took_trophy", opp.name(), held.getName())
							.formatted(Formatting.GOLD), false);
					}
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
			p.addStatusEffect(new StatusEffectInstance(StatusEffects.NAUSEA, 20 * 6, 0));
		}
	}
}
