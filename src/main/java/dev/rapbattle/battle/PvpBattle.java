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
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.Vec3d;

import java.io.ByteArrayOutputStream;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Player against player. Hit someone with an empty hand to call them out; they answer Y / N. The challenger reads
 * first, then the other one: 8 lines each on their own rhymes, over a real beat. Both verses are recognised and the
 * judge compares the two players; Respect, live damage, word timing and the breakdown work like in a mob battle.
 * No real damage: in a player battle only Respect (and the stats) are at stake.
 */
public final class PvpBattle {
	private static final AtomicInteger IDS = new AtomicInteger(1_000_000);
	static final List<PvpBattle> ALL = new ArrayList<>();
	private record Invite(UUID from, int id, long until) {}
	private static final Map<UUID, Invite> INVITES = new HashMap<>();

	/** INTRO is sent as "PREP": the clients fly the camera around the two players, then the beat starts. */
	private enum Phase { INTRO, TIMELINE, JUDGING, RESULT }

	private static final long INTRO_MS = 7000;

	private static final int LINES = 8, LEAD_IN = 4;
	private static final long AUDIO_WAIT_MS = 12000, STT_WAIT_MS = 45000, JUDGE_WAIT_MS = 60000, MIN_JUDGING_MS = 2500, RESULT_MS = 12000;
	private static final int[] COLORS = {0x6EC6F0, 0xF07167};

	public final int id;
	/** The challenger's language: only a default. Everyone gets the battle in their own Minecraft language. */
	public final String lang;
	private final Map<UUID, String> langs = new HashMap<>();
	private final ServerWorld world;
	private final UUID a, b;
	private final Map<UUID, String> names = new HashMap<>();
	private final Map<UUID, Vec3d> anchors = new HashMap<>();
	private final BeatTracks.Track track;
	private final long beatMs;
	private final long beatZero;
	private final Vec3d stage;
	private final BattleView view;
	private final Map<UUID, List<String>> rhymes = new HashMap<>();
	private final Random rnd = new Random();
	private Phase phase = Phase.INTRO;
	private long phaseStart;
	private int respectA = 100, respectB = 100;
	private final Map<UUID, Integer> combo = new HashMap<>();
	private final Map<UUID, Integer> dealt = new HashMap<>();
	private final Map<String, List<Boolean>> barLoud = new ConcurrentHashMap<>();
	private final Map<String, List<Integer>> barDeltaMs = new ConcurrentHashMap<>();
	private final Map<String, List<Boolean>> barSilent = new ConcurrentHashMap<>();
	private final Map<UUID, Integer> hitsCount = new ConcurrentHashMap<>();
	private final Map<UUID, Integer> missesCount = new ConcurrentHashMap<>();
	private final Map<UUID, ByteArrayOutputStream> audio = new HashMap<>();
	private final Map<UUID, Integer> audioSeq = new HashMap<>();
	private final Map<UUID, CompletableFuture<String>> transcripts = new ConcurrentHashMap<>();
	private final Map<UUID, Transcriber.Result> timed = new ConcurrentHashMap<>();
	private final Map<UUID, Integer> recordingStart = new ConcurrentHashMap<>();
	private CompletableFuture<Llm.PvpVerdict> pvpVerdictFuture;
	private final Map<String, BattleView.Result> results = new LinkedHashMap<>();
	private final Map<UUID, BattleView.Result> playerResults = new HashMap<>();
	private long verdictAsked;
	private volatile boolean sttMissing;
	boolean finished;
	private final Set<UUID> spectators = new HashSet<>();
	private ServerPlayerEntity testPeer;

	/** Test-only helper so a FakePlayer participant stays connected during tick tests. */
	public void setTestPeerForTest(ServerPlayerEntity peer) {
		this.testPeer = peer;
	}

	// ------------------------------------------------------------------ challenge

	/** An empty-hand hit on another player: a call-out. Returns true if the hit was taken as a challenge. */
	public static boolean challenge(ServerPlayerEntity from, ServerPlayerEntity to) {
		if (busy(from) || busy(to)) return false;
		long now = dev.rapbattle.Clock.now();
		Invite pending = INVITES.get(to.getUuid());
		if (pending != null && pending.from().equals(from.getUuid()) && now < pending.until()) return true; // already asked
		int id = IDS.getAndIncrement();
		INVITES.put(to.getUuid(), new Invite(from.getUuid(), id, now + 15000));
		BattleView v = new BattleView();
		v.type = "invite";
		v.id = id;
		v.pvp = true;
		v.challenger = from.getName().getString();
		v.allowed = true;
		ServerPlayNetworking.send(to, new Payloads.StateS2C(v.toJson()));
		from.sendMessage(Text.translatable("rapbattle.pvp.challenge_sent", to.getName().getString()).formatted(Formatting.GOLD), true);
		return true;
	}

	private static boolean busy(ServerPlayerEntity p) {
		return BattleManager.byPlayer(p.getUuid()) != null || byPlayer(p.getUuid()) != null;
	}

	/** Y / N on a player's call-out. Returns false if there is no such call-out. */
	public static boolean answer(ServerPlayerEntity p, int id, boolean accept) {
		Invite inv = INVITES.get(p.getUuid());
		if (inv == null || inv.id() != id) return false;
		INVITES.remove(p.getUuid());
		BattleView close = new BattleView();
		close.type = "invite_close";
		close.id = id;
		ServerPlayNetworking.send(p, new Payloads.StateS2C(close.toJson()));
		ServerPlayerEntity from = p.getEntityWorld().getServer().getPlayerManager().getPlayer(inv.from());
		if (from == null || dev.rapbattle.Clock.now() > inv.until()) return true;
		if (!accept) {
			from.sendMessage(Text.translatable("rapbattle.pvp.declined", p.getName().getString()).formatted(Formatting.GRAY), false);
			return true;
		}
		if (from.getEntityWorld() != p.getEntityWorld()) {
			Text msg = Text.translatable("rapbattle.pvp.different_world").formatted(Formatting.RED);
			from.sendMessage(msg, false);
			p.sendMessage(msg, false);
			return true;
		}
		if (busy(from) || busy(p)) return true;
		ALL.add(new PvpBattle(id, from, p));
		return true;
	}

	public static PvpBattle byPlayer(UUID u) {
		for (PvpBattle pb : ALL) if (!pb.finished && (pb.a.equals(u) || pb.b.equals(u))) return pb;
		return null;
	}

	public static PvpBattle byId(int id) {
		for (PvpBattle pb : ALL) if (!pb.finished && pb.id == id) return pb;
		return null;
	}

	static void clear() {
		ALL.clear();
		INVITES.clear();
	}

	/** Test-only helper to end a PvP battle cleanly. */
	public void forceEndForTest() {
		if (!finished) end(Text.literal("test cleanup"));
	}

	/** Test-only helper to inspect the localized BattleView for a participant. */
	public BattleView snapshotForTest(ServerPlayerEntity viewer) {
		return localized(view, Lang.of(viewer), false);
	}

	/** Test-only helper to get pending PvP invite id for a player. */
	public static int pendingInviteIdForTest(UUID playerId) {
		Invite i = INVITES.get(playerId);
		return i == null ? -1 : i.id();
	}

	/** Test-only helper to check if a player has a transcript entry. */
	public boolean hasTranscriptForTest(UUID playerId) {
		return transcripts.containsKey(playerId);
	}

	/** Test-only helper to check how many bar signals were recorded for a participant. */
	public int reportedBarsCountForTest(String who) {
		return barLoud.getOrDefault(who, List.of()).size();
	}

	/** Test-only helper to jump PvP battle into TIMELINE or JUDGING. */
	public void jumpToForTest(String targetPhase) {
		long now = dev.rapbattle.Clock.now();
		if ("TIMELINE".equals(targetPhase)) {
			phase = Phase.TIMELINE;
			phaseStart = now;
		} else if ("JUDGING".equals(targetPhase)) {
			phase = Phase.JUDGING;
			phaseStart = now;
		} else if ("RESULT".equals(targetPhase)) {
			phase = Phase.RESULT;
			phaseStart = now;
			view.result = new BattleView.Result();
			view.result.winner = names.get(a);
			view.result.playersWin = true;
		} else if ("RESULT_LOSS".equals(targetPhase)) {
			phase = Phase.RESULT;
			phaseStart = now;
			view.result = new BattleView.Result();
			view.result.winner = names.get(b);
			view.result.playersWin = false;
		}
	}

	static void tickAll() {
		long now = dev.rapbattle.Clock.now();
		INVITES.values().removeIf(i -> now > i.until());
		for (PvpBattle pb : new ArrayList<>(ALL)) {
			try {
				pb.tick(now);
			} catch (RuntimeException e) {
				RapBattle.LOG.error("PvP battle {} crashed", pb.id, e);
				pb.end(Text.translatable("rapbattle.battle.crashed"));
			}
		}
		ALL.removeIf(pb -> pb.finished);
	}

	// ------------------------------------------------------------------ setup

	private PvpBattle(int id, ServerPlayerEntity pa, ServerPlayerEntity pb) {
		this.id = id;
		this.lang = Lang.of(pa);
		langs.put(pa.getUuid(), Lang.of(pa));
		langs.put(pb.getUuid(), Lang.of(pb));
		this.world = pa.getEntityWorld();
		this.a = pa.getUuid();
		this.b = pb.getUuid();
		names.put(a, pa.getName().getString());
		names.put(b, pb.getName().getString());
		if (pa.hasVehicle()) pa.stopRiding();
		if (pb.hasVehicle()) pb.stopRiding();
		anchors.put(a, new Vec3d(pa.getX(), pa.getY(), pa.getZ()));
		anchors.put(b, new Vec3d(pb.getX(), pb.getY(), pb.getZ()));
		pa.extinguish();
		pa.setAir(pa.getMaxAir());
		pb.extinguish();
		pb.setAir(pb.getMaxAir());
		stage = new Vec3d((pa.getX() + pb.getX()) / 2, Math.min(pa.getY(), pb.getY()), (pa.getZ() + pb.getZ()) / 2);
		List<BeatTracks.Track> pool = new ArrayList<>();
		for (BeatTracks.Track t : BeatTracks.ALL) if (!t.id().equals("boss")) pool.add(t);
		track = pool.isEmpty() ? null : pool.get(rnd.nextInt(pool.size()));
		beatMs = track != null ? track.beatMs() : 650;
		beatZero = dev.rapbattle.Clock.now() + INTRO_MS + 600;
		phaseStart = dev.rapbattle.Clock.now();
		view = buildView();
		broadcastChat(Text.translatable("rapbattle.pvp.started", names.get(a), names.get(b), names.get(a))
			.formatted(Formatting.GOLD));
		broadcast();
	}

	private BattleView buildView() {
		BattleView v = new BattleView();
		v.type = "state";
		v.id = id;
		v.pvp = true;
		v.beatMs = beatMs;
		v.beatTrack = track == null ? null : track.id();
		v.rounds = 1;
		v.round = 1;
		v.challenger = names.get(a);
		v.mobName = names.get(b);
		v.rankColor = 0xF07167;
		v.respectMax = 100;
		int beat = 4; // countdown
		for (UUID u : List.of(a, b)) {
			// Each reader gets rhymes in his own language.
			List<String> words = new ArrayList<>();
			for (List<String> grp : RhymeBank.pickBlocks(LINES / 4, rnd, langOf(u))) words.addAll(grp);
			rhymes.put(u, words.subList(0, LINES));
			BattleView.Segment seg = new BattleView.Segment();
			seg.reader = names.get(u);
			seg.leadInBeat = beat;
			beat += LEAD_IN;
			seg.startBeat = beat;
			for (int i = 0; i < LINES; i++) {
				BattleView.Row r = new BattleView.Row();
				r.word = words.get(i);
				r.color = COLORS[u.equals(a) ? 0 : 1];
				r.reader = names.get(u);
				ServerPlayerEntity p = player(u);
				r.readerEntityId = p == null ? -1 : p.getId();
				r.line = i + 1;
				r.lines = LINES;
				r.startBeat = beat;
				r.cols = new String[]{"", "", ""};
				r.fullLine = "";
				v.rows.add(r);
				beat += 4;
			}
			seg.endBeat = beat;
			v.segments.add(seg);
		}
		v.totalBeats = beat;
		v.stageX = stage.x;
		v.stageY = stage.y;
		v.stageZ = stage.z;
		v.stageRadius = 4.5;
		return v;
	}

	private ServerPlayerEntity player(UUID u) {
		if (testPeer != null && testPeer.getUuid().equals(u)) return testPeer;
		return world.getServer().getPlayerManager().getPlayer(u);
	}

	private UUID other(UUID u) { return u.equals(a) ? b : a; }

	private String langOf(UUID u) { return langs.getOrDefault(u, lang); }

	// ------------------------------------------------------------------ during the battle

	private List<BattleView.Row> playerRows(String name) {
		if (view == null || name == null) return List.of();
		List<BattleView.Row> out = new ArrayList<>();
		for (BattleView.Row r : view.rows) {
			if (name.equals(r.reader)) out.add(r);
		}
		return out;
	}

	public void onBar(ServerPlayerEntity p, boolean loud) {
		onBar(p, loud, -1, !loud);
	}

	public void onBar(ServerPlayerEntity p, boolean loud, int deltaMs, boolean silentBar) {
		long now = dev.rapbattle.Clock.now();
		boolean inWindow = phase == Phase.TIMELINE || (phase == Phase.JUDGING && now - phaseStart <= 500L);
		if (!inWindow || view == null) return;
		UUID u = p.getUuid();
		if (!u.equals(a) && !u.equals(b)) return;
		String name = names.getOrDefault(u, p.getName().getString());
		List<BattleView.Row> myRows = playerRows(name);
		List<Boolean> loudList = barLoud.computeIfAbsent(name, k -> Collections.synchronizedList(new ArrayList<>()));
		int barIdx = loudList.size();
		if (barIdx >= myRows.size()) return;
		int curBeat = (int) Math.floorDiv(now - beatZero, Math.max(1L, beatMs));
		if (curBeat < myRows.get(barIdx).startBeat + 2) return;
		int clampedDelta = deltaMs < 0 ? -1 : Math.min(deltaMs, (int) Math.max(1L, beatMs));
		loudList.add(loud);
		barDeltaMs.computeIfAbsent(name, k -> Collections.synchronizedList(new ArrayList<>())).add(clampedDelta);
		barSilent.computeIfAbsent(name, k -> Collections.synchronizedList(new ArrayList<>())).add(silentBar);
		if (loud) {
			hitsCount.merge(u, 1, Integer::sum);
			// Volume alone deals nothing: the judge and the beat timing do.
			int c = combo.merge(u, 1, Integer::sum);
			if (c >= 3) DamagePop.spawn(world, p, "x" + c, 0xFFD84A);
		} else {
			missesCount.merge(u, 1, Integer::sum);
			combo.put(u, 0);
			DamagePop.spawn(world, p, Lang.t(langOf(u), "rapbattle.battle.stumbled_pop"), 0xFF6060);
		}
		broadcast();
	}

	/** Damage to a player's Respect; during the verses it never drops below 1 (the judge decides a knockout). */

	public void onAudio(ServerPlayerEntity p, Payloads.AudioC2S chunk) {
		UUID u = p.getUuid();
		if ((!u.equals(a) && !u.equals(b)) || (phase != Phase.TIMELINE && phase != Phase.JUDGING)) return;
		if (transcripts.containsKey(u)) return;
		int expectedSeq = audioSeq.getOrDefault(u, 0);
		if (chunk.seq() != expectedSeq) return;
		audioSeq.put(u, expectedSeq + 1);
		ByteArrayOutputStream buf = audio.computeIfAbsent(u, k -> new ByteArrayOutputStream());
		if (buf.size() + chunk.data().length > 8 * 1024 * 1024) return;
		buf.writeBytes(chunk.data());
		if (!chunk.last()) return;
		byte[] wav = buf.toByteArray();
		audio.remove(u);
		audioSeq.remove(u);
		recordingStart.put(u, chunk.startMs());
		List<String> hints = rhymes.getOrDefault(u, List.of());
		String lang = langOf(u);
		transcripts.put(u, CompletableFuture.supplyAsync(() -> {
			Transcriber.Result r = Transcriber.transcribeTimed(wav, hints, lang);
			if (r == null) {
				sttMissing = true;
				return "";
			}
			timed.put(u, r);
			return r.text();
		}, Battle.POOL));
	}

	public void leave(UUID u) {
		if (finished) return;
		String who = names.getOrDefault(u, "?");
		broadcastChat(Text.translatable("rapbattle.pvp.player_left", who, names.get(other(u))).formatted(Formatting.GRAY));
		recordStats(other(u));
		end((Text) null);
	}

	public void abortNoPenalty(Text reason) {
		if (finished) return;
		for (UUID u : List.of(a, b)) {
			ServerPlayerEntity p = player(u);
			if (p != null && reason != null) p.sendMessage(reason.copy().formatted(Formatting.YELLOW), false);
		}
		end(null);
	}

	private final Map<UUID, Integer> pushTicks = new HashMap<>();
	private int truceTick = 0;

	public boolean isResolving() {
		return phase == Phase.JUDGING || phase == Phase.RESULT;
	}

	private void tick(long now) {
		if (finished) return;
		ServerPlayerEntity pa = player(a), pb = player(b);
		if (pa == null || pb == null) {
			leave(pa == null ? a : b);
			return;
		}
		if (!pa.isAlive() || !pb.isAlive()) {
			if (!isResolving()) abortNoPenalty(Text.translatable("rapbattle.battle.abort_env"));
			return;
		}
		if (pa.getEntityWorld() != world) {
			leave(a);
			return;
		}
		if (pb.getEntityWorld() != world) {
			leave(b);
			return;
		}
		// Both stand on their spots horizontally (gravity works) and face each other.
		for (UUID u : List.of(a, b)) {
			ServerPlayerEntity p = player(u);
			if (p != null && p != testPeer) {
				if (p.hasVehicle()) p.stopRiding();
				if (p.getFireTicks() > 0) p.extinguish();
				if (p.getAir() < p.getMaxAir()) p.setAir(p.getMaxAir());
			}
			Vec3d at = anchors.get(u);
			if (p != testPeer && p.networkHandler != null && at != null && !isResolving()) {
				if (p.getY() - at.y > 2.0) {
					p.networkHandler.requestTeleport(at.x, at.y, at.z, p.getYaw(), p.getPitch());
					continue;
				}
				double dx = p.getX() - at.x, dz = p.getZ() - at.z;
				double d2 = dx * dx + dz * dz;
				if (d2 > 0.3 * 0.3) {
					int pt = pushTicks.getOrDefault(u, 0) + 1;
					if (pt > 4 && (d2 > 0.8 * 0.8 || p.isInsideWall())) {
						abortNoPenalty(Text.translatable("rapbattle.battle.abort_env"));
						return;
					}
					pushTicks.put(u, pt);
					p.networkHandler.requestTeleport(at.x, p.getY(), at.z, p.getYaw(), p.getPitch());
				} else {
					pushTicks.put(u, 0);
				}
			}
		}
		if ((truceTick++ % 5) == 0) holdMobTruce(now);
		int beat = (int) Math.floorDiv(now - beatZero, beatMs);
		glow(beat);
		switch (phase) {
			case INTRO -> {
				if (now >= beatZero - 200) {
					phase = Phase.TIMELINE;
					broadcast();
				}
			}
			case TIMELINE -> {
				if (now >= beatZero + view.totalBeats * beatMs + 300) {
					phase = Phase.JUDGING;
					phaseStart = now;
					broadcast();
				} else if ((now / 1000) != ((now - 50) / 1000)) {
					broadcast();
				}
			}
			case JUDGING -> judging(now);
			case RESULT -> {
				long read = RESULT_MS + (view.result == null ? 0 : view.result.hits.size() * 400L);
				if (now - phaseStart >= read) finish();
			}
		}
	}

	/** Keeps hostile mobs within 16 blocks of the stage in truce (no attacking or creeper fuse) and facing the stage. */
	private void holdMobTruce(long now) {
		var box = new net.minecraft.util.math.Box(stage.x - 16, stage.y - 8, stage.z - 16, stage.x + 16, stage.y + 8, stage.z + 16);
		for (net.minecraft.entity.mob.MobEntity m : world.getEntitiesByClass(net.minecraft.entity.mob.MobEntity.class, box,
			e -> e.isAlive() && !Opponent.isBoss(e) && e instanceof net.minecraft.entity.mob.HostileEntity)) {
			Crowd.holdTruce(m, now + 1500L);
			if (m.getTarget() != null) m.setTarget(null);
			m.getNavigation().stop();
			m.getLookControl().lookAt(stage.x, stage.y + 1.0, stage.z);
			if (m instanceof net.minecraft.entity.mob.CreeperEntity c) c.setFuseSpeed(-1);
		}
	}

	private void glow(int beat) {
		for (UUID u : List.of(a, b)) {
			ServerPlayerEntity p = player(u);
			if (p == null) continue;
			boolean reading = false;
			if (phase == Phase.TIMELINE) {
				for (BattleView.Row r : view.rows) if (beat >= r.startBeat && beat < r.startBeat + 4 && names.get(u).equals(r.reader)) reading = true;
			}
			p.setGlowing(reading);
		}
	}

	// ------------------------------------------------------------------ judging

	private final Map<UUID, List<Scoring.SlicedBar>> slicedBars = new HashMap<>();
	private List<Llm.PlayerVerse> pendingVerses = List.of();

	private boolean allBarsReported() {
		for (UUID u : List.of(a, b)) {
			String pName = names.get(u);
			if (barLoud.getOrDefault(pName, List.of()).size() < playerRows(pName).size()) return false;
		}
		return true;
	}

	private void judging(long now) {
		long t = now - phaseStart;
		if (t > AUDIO_WAIT_MS) for (UUID u : List.of(a, b)) transcripts.putIfAbsent(u, CompletableFuture.completedFuture(""));
		if (pvpVerdictFuture == null) {
			boolean done = transcripts.size() == 2 && transcripts.values().stream().allMatch(CompletableFuture::isDone);
			if (!done && t < AUDIO_WAIT_MS + STT_WAIT_MS) return;
			if (t < 500 && !allBarsReported()) return;
			List<Llm.PlayerVerse> verses = prepareJudgingVerses(now, false);
			Map<String, List<Boolean>> loudSnapshot = new HashMap<>();
			barLoud.forEach((k, list) -> loudSnapshot.put(k, List.copyOf(list)));
			boolean noStt = sttMissing;
			Llm.PlayerVerse vA = verses.get(0), vB = verses.get(1);
			String lA = langOf(a), lB = langOf(b);
			pvpVerdictFuture = CompletableFuture.supplyAsync(() -> {
				Llm.PvpVerdict pv = noStt ? null : Llm.judgePvp(vA, vB, lA, lB);
				if (pv == null) {
					Llm.Verdict fRu = Fallback.judge(0, 0, verses, loudSnapshot, noStt, rnd, Lang.RU);
					Llm.Verdict fEn = Fallback.judge(0, 0, verses, loudSnapshot, noStt, rnd, Lang.EN);
					return new Llm.PvpVerdict(fRu.playerLines(), fEn.playerLines(), fRu.comment(), fEn.comment());
				}
				return pv;
			}, Battle.POOL);
			return;
		}
		if (t < MIN_JUDGING_MS) return;
		if (!pvpVerdictFuture.isDone() && now - verdictAsked < JUDGE_WAIT_MS) return;
		Llm.PvpVerdict pvpVerdict = pvpVerdictFuture.getNow(null);
		if (pvpVerdict == null) pvpVerdict = fallbackPvpVerdict();
		applyVerdict(pvpVerdict, now);
	}

	private List<Llm.PlayerVerse> prepareJudgingVerses(long now, boolean fillMissing) {
		if (fillMissing) for (UUID u : List.of(a, b)) transcripts.putIfAbsent(u, CompletableFuture.completedFuture(""));
		slicedBars.clear();
		List<Llm.PlayerVerse> verses = new ArrayList<>();
		for (UUID u : List.of(a, b)) {
			String pName = names.get(u);
			String uLang = langOf(u);
			CompletableFuture<String> f = transcripts.get(u);
			String raw = f == null ? "" : f.getNow("");
			String text = raw == null ? "" : raw.trim();
			List<Scoring.BarSlot> slots = new ArrayList<>();
			int ln = 1;
			for (BattleView.Row r : view.rows) {
				if (pName.equals(r.reader)) slots.add(new Scoring.BarSlot(ln++, r.startBeat, r.word));
			}
			List<Scoring.SlicedBar> sliced = Scoring.sliceBars(timed.get(u), recordingStart.get(u), text, slots, beatMs, uLang);
			slicedBars.put(u, sliced);
			List<Boolean> louds = barLoud.getOrDefault(pName, List.of());
			List<Integer> deltas = barDeltaMs.getOrDefault(pName, List.of());
			List<Llm.BarPrompt> prompts = new ArrayList<>(sliced.size());
			for (int i = 0; i < sliced.size(); i++) {
				Scoring.SlicedBar sb = sliced.get(i);
				boolean sttMiss = sttMissing || timed.get(u) == null;
				int deltaMs = i < deltas.size() ? deltas.get(i) : -1;
				int preBeat = Scoring.hybridBeat(sb.whisperDeltaBeats(), 1, sttMiss, deltaMs, beatMs);
				if (preBeat == 0 && i < louds.size() && Boolean.TRUE.equals(louds.get(i)) && sb.whisperDeltaBeats() == null) {
					preBeat = 1;
				}
				String timingLabel = sb.silent()
					? Lang.t(uLang, "rapbattle.judge.timing.silent")
					: preBeat == 2 ? Lang.t(uLang, "rapbattle.judge.timing.on_beat")
					: preBeat == 1 ? Lang.t(uLang, "rapbattle.judge.timing.near")
					: Lang.t(uLang, "rapbattle.judge.timing.off");
				prompts.add(new Llm.BarPrompt(sb.lineIndex(), sb.targetWord(), timingLabel, sb.text(), sb.silent()));
			}
			verses.add(new Llm.PlayerVerse(pName, rhymes.getOrDefault(u, List.of()), text, "", prompts));
		}
		pendingVerses = List.copyOf(verses);
		verdictAsked = now;
		String lA = langOf(a), lB = langOf(b);
		for (String l : new LinkedHashSet<>(List.of(lA, lB))) {
			BattleView.Result r = new BattleView.Result();
			for (Llm.PlayerVerse pv : verses) r.players.add(new BattleView.PlayerScore(pv.name(), 0, pv.transcript().isBlank() ? Lang.t(l, "rapbattle.battle.silence") : pv.transcript()));
			results.put(l, r);
		}
		view.result = results.get(lA);
		return verses;
	}

	private Llm.PvpVerdict fallbackPvpVerdict() {
		Map<String, List<Boolean>> loudSnapshot = new HashMap<>();
		barLoud.forEach((k, list) -> loudSnapshot.put(k, List.copyOf(list)));
		Llm.Verdict fRu = Fallback.judge(0, 0, pendingVerses, loudSnapshot, sttMissing, rnd, Lang.RU);
		Llm.Verdict fEn = Fallback.judge(0, 0, pendingVerses, loudSnapshot, sttMissing, rnd, Lang.EN);
		return new Llm.PvpVerdict(fRu.playerLines(), fEn.playerLines(), fRu.comment(), fEn.comment());
	}

	private void applyVerdict(Llm.PvpVerdict pvpVerdict, long now) {

		int before0 = respectA, before1 = respectB;
		Stats stats = Stats.of(world.getServer());
		Map<UUID, Scoring.RoundOutcome> outcomes = new HashMap<>();
		for (UUID u : List.of(a, b)) {
			String pName = names.get(u);
			String uLang = langOf(u);
			Llm.Verdict ownVerdict = pvpVerdict.forLang(uLang);
			List<Llm.LineScore> ownScores = ownVerdict.playerLines().getOrDefault(pName, List.of());
			List<Scoring.SlicedBar> sbs = slicedBars.getOrDefault(u, List.of());
			List<Boolean> louds = barLoud.getOrDefault(pName, List.of());
			List<Integer> deltas = barDeltaMs.getOrDefault(pName, List.of());
			List<Boolean> silents = barSilent.getOrDefault(pName, List.of());
			List<String> words = rhymes.getOrDefault(u, List.of());

			McStyle style = stats.player(u).mcStyle();
			Trophies.TrophyPerk perk = Trophies.equippedPerk(player(u));
			boolean witherForgiven = false;
			boolean guardianUsed = false;
			double wWin = 0.30 + (perk == Trophies.TrophyPerk.ELDER_GUARDIAN ? 0.05 : 0.0);
			double mWin = 0.35 + (perk == Trophies.TrophyPerk.ELDER_GUARDIAN ? 0.05 : 0.0);

			List<Scoring.LineInput> inputs = new ArrayList<>();
			for (int i = 0; i < words.size(); i++) {
				Llm.LineScore l = i < ownScores.size() ? ownScores.get(i) : Llm.LineScore.silent(uLang);
				Scoring.SlicedBar sb = i < sbs.size() ? sbs.get(i) : null;
				boolean sttSilent = sb != null && sb.silent();
				boolean micSilent = i < silents.size() && Boolean.TRUE.equals(silents.get(i));
				boolean barSilentFlag = sttMissing ? micSilent : (sb != null ? sttSilent : micSilent);
				boolean silenceForgiven = false;
				if (barSilentFlag && perk == Trophies.TrophyPerk.WITHER && !witherForgiven) {
					silenceForgiven = true;
					witherForgiven = true;
				}
				int deltaMs = i < deltas.size() ? deltas.get(i) : (i < louds.size() && Boolean.TRUE.equals(louds.get(i)) ? 0 : -1);
				int beatState = barSilentFlag ? 0 : Scoring.hybridBeat(sb == null ? null : sb.whisperDeltaBeats(),
					l.rhyme(), sttMissing, deltaMs, beatMs, wWin, mWin);
				if (beatState == 2 && perk == Trophies.TrophyPerk.ELDER_GUARDIAN) guardianUsed = true;
				inputs.add(style.lineInput(pName, words.get(i), l.score(), l.meaning(), l.punch(), l.rebuttal(),
					l.note(), beatState, barSilentFlag, false, 1.0, silenceForgiven));
			}
			int hypeVal = Math.max(-100, Math.min(100, (hitsCount.getOrDefault(u, 0) - missesCount.getOrDefault(u, 0)) * 18));
			int extraStyle = perk == Trophies.TrophyPerk.DEFAULT ? 1 : 0;
			int hb = Scoring.hypeBonus(hypeVal);
			Scoring.Bonuses bonuses = new Scoring.Bonuses(hb, extraStyle, 0, Math.min(10, hb + extraStyle));
			Scoring.RoundOutcome o = Scoring.evaluateRound(null, inputs, bonuses, 0, 0);
			outcomes.put(u, o);
			dealt.put(u, o.totalToMob());

			for (int i = 0; i < o.lines().size(); i++) {
				Scoring.EvaluatedLine el = o.lines().get(i);
				String word = i < words.size() ? words.get(i) : "";
				for (Map.Entry<String, BattleView.Result> e : results.entrySet()) {
					List<Llm.LineScore> theirs = pvpVerdict.forLang(e.getKey()).playerLines().getOrDefault(pName, List.of());
					String note = i < theirs.size() ? theirs.get(i).note() : el.input().note();
					e.getValue().hits.add(new BattleView.Hit(pName, (word.isEmpty() ? "" : "«" + word + "» · ") + note, el.damage(), u.equals(a), el.input().punch()));
				}
			}
			if (perk != null) {
				boolean triggered = switch (perk) {
					case WITHER -> witherForgiven;
					case ELDER_GUARDIAN -> guardianUsed;
					case DEFAULT -> extraStyle > 0;
					default -> false;
				};
				if (triggered) {
					for (Map.Entry<String, BattleView.Result> e : results.entrySet()) {
						e.getValue().hits.add(new BattleView.Hit(pName, perk.triggeredNote(e.getKey()), 0, false, false, true));
					}
				}
			}
			if (bonuses.hypeBonus() > 0) {
				for (Map.Entry<String, BattleView.Result> e : results.entrySet()) {
					e.getValue().hits.add(new BattleView.Hit(pName, Lang.t(e.getKey(), "rapbattle.battle.hit_crowd"), bonuses.hypeBonus(), u.equals(a), false));
				}
			}
			if (o.silenceDamage() > 0) {
				for (Map.Entry<String, BattleView.Result> e : results.entrySet()) {
					e.getValue().hits.add(new BattleView.Hit(pName, Lang.t(e.getKey(), "rapbattle.battle.hit_silence", o.silentBars()), o.silenceDamage(), !u.equals(a), false));
				}
			}
		}

		Scoring.RoundOutcome oA = outcomes.get(a);
		Scoring.RoundOutcome oB = outcomes.get(b);
		respectA = Math.min(100, Math.max(0, 100 - oB.totalToMob() - oA.silenceDamage() + oA.teamHeal()));
		respectB = Math.min(100, Math.max(0, 100 - oA.totalToMob() - oB.silenceDamage() + oB.teamHeal()));
		boolean ko = respectA <= 0 || respectB <= 0;
		UUID winner = respectB <= 0 && respectA > 0 ? a : respectA <= 0 && respectB > 0 ? b : respectA >= respectB ? a : b;
		for (Map.Entry<String, BattleView.Result> e : results.entrySet()) {
			fillPvpResult(e.getValue(), e.getKey(), oA, before0, before1, ko, winner, pvpVerdict);
		}
		for (UUID u : List.of(a, b)) {
			String ul = langOf(u);
			BattleView.Result base = results.get(ul);
			BattleView.Result copy = BattleView.GSON.fromJson(BattleView.GSON.toJson(base), BattleView.Result.class);
			fillPvpResult(copy, ul, u.equals(a) ? oA : oB, before0, before1, ko, winner, pvpVerdict);
			playerResults.put(u, copy);
		}
		view.result = playerResults.get(a);
		phase = Phase.RESULT;
		phaseStart = now;
		broadcast();
	}

	private void fillPvpResult(BattleView.Result r, String langKey, Scoring.RoundOutcome own,
							   int before0, int before1, boolean ko, UUID winner, Llm.PvpVerdict pvpVerdict) {
		r.teamBefore = before0;
		r.mobBefore = before1;
		r.respectMax = 100;
		r.respectTeam = respectA;
		r.respectMob = respectB;
		r.knockout = ko;
		r.winner = names.get(winner);
		r.playersWin = winner.equals(a);
		r.finalResult = true;
		r.comment = pvpVerdict.forLang(langKey).comment();
		r.teamLabel = names.get(a);
		r.mobLabel = names.get(b);
		r.dealt = own.totalToMob();
		r.barTextTotal = own.barTextTotal();
		r.barBeatDelta = own.barBeatDelta();
		r.barRebuttalTotal = own.barRebuttalDamage();
		r.bonusHype = own.bonuses().hypeBonus();
		r.bonusTotal = own.bonuses().total();
		r.silenceDamage = own.silenceDamage();
		for (BattleView.PlayerScore ps : r.players) {
			for (UUID u : List.of(a, b)) if (names.get(u).equals(ps.name)) ps.score = dealt.getOrDefault(u, 0);
		}
	}

	/** Immediately resolves the PvP battle with the current outcome (used on void/death during resolving). */
	public void resolveNow() {
		if (finished || !isResolving()) return;
		if (phase == Phase.JUDGING) {
			long now = dev.rapbattle.Clock.now();
			if (pvpVerdictFuture == null) prepareJudgingVerses(now, true);
			Llm.PvpVerdict pv = pvpVerdictFuture == null ? null : pvpVerdictFuture.getNow(null);
			if (pv == null) pv = fallbackPvpVerdict();
			applyVerdict(pv, now);
		}
		finish();
	}

	private void finish() {
		UUID winner = view.result != null && names.get(b).equals(view.result.winner) ? b : a;
		Text msg = view.result != null && view.result.knockout
			? Text.translatable("rapbattle.pvp.win_ko", names.get(winner), names.get(other(winner)))
			: Text.translatable("rapbattle.pvp.win_pts", names.get(winner), names.get(other(winner)), Math.max(respectA, respectB), Math.min(respectA, respectB));
		broadcastChat(msg.copy().formatted(Formatting.GREEN));
		recordStats(winner);
		end((Text) null);
	}

	private void recordStats(UUID winner) {
		Stats stats = Stats.of(world.getServer());
		stats.recordPvp(winner, other(winner), dealt.getOrDefault(winner, 0), dealt.getOrDefault(other(winner), 0));
		for (UUID u : List.of(a, b)) {
			ServerPlayerEntity p = player(u);
			if (p != null) Battle.notifyStyleUnlock(stats, u, p);
		}
	}

	private void end(Text reason) {
		finished = true;
		for (UUID u : List.of(a, b)) {
			ServerPlayerEntity p = player(u);
			if (p == null) continue;
			p.setGlowing(false);
			if (reason != null) p.sendMessage(reason.copy().formatted(Formatting.GRAY), false);
		}
		BattleView endView = new BattleView();
		endView.type = "end";
		endView.id = id;
		for (UUID u : allViewers()) {
			ServerPlayerEntity p = player(u);
			if (p != null) ServerPlayNetworking.send(p, new Payloads.StateS2C(endView.toJson()));
		}
	}

	// ------------------------------------------------------------------ sync

	private List<UUID> allViewers() {
		List<UUID> out = new ArrayList<>(List.of(a, b));
		out.addAll(spectators);
		return out;
	}

	private void broadcast() {
		BattleView v = view;
		v.phase = phase == Phase.INTRO ? "PREP" : phase.name();
		v.respectTeam = respectA;
		v.respectMob = respectB;
		v.combo = 0;
		v.beatElapsedMs = dev.rapbattle.Clock.now() - beatZero;
		for (UUID u : List.of(a, b)) {
			ServerPlayerEntity p = player(u);
			if (p != null) ServerPlayNetworking.send(p, new Payloads.StateS2C(localized(v, langOf(u), false, u).toJson()));
		}
		double r2 = RapConfig.get().spectatorRadius * RapConfig.get().spectatorRadius;
		for (ServerPlayerEntity p : world.getPlayers()) {
			if (p.getUuid().equals(a) || p.getUuid().equals(b)) continue;
			if (p.squaredDistanceTo(stage) <= r2) {
				spectators.add(p.getUuid());
				ServerPlayNetworking.send(p, new Payloads.StateS2C(localized(v, Lang.of(p), true, null).toJson()));
			}
		}
	}

	/** The battle as one viewer sees it: labels, outfit, titles and the judge's breakdown in his language. */
	private BattleView localized(BattleView v, String l, boolean spectator) {
		return localized(v, l, spectator, null);
	}

	private BattleView localized(BattleView v, String l, boolean spectator, UUID viewerId) {
		BattleView c = v.copy();
		c.spectator = spectator;
		c.rankArmor = Lang.t(l, "rapbattle.pvp.rank_armor");
		c.rankStreet = Lang.t(l, "rapbattle.pvp.rank_street");
		c.team = new ArrayList<>();
		for (UUID u : List.of(a, b)) {
			ServerPlayerEntity p = player(u);
			Outfit.Score s = p == null ? new Outfit.Score(0, "") : Outfit.of(p, l);
			BattleView.Member mm = new BattleView.Member(names.get(u), s.label(l), s.level(), p == null ? -1 : p.getId());
			// Their records for the intro cards and the scoreboard.
			Stats.Player st = Stats.of(world.getServer()).player(u);
			mm.wins = st.wins;
			mm.losses = st.losses;
			mm.streak = st.streak;
			mm.mvp = st.mvp;
			mm.best = st.best;
			mm.title = st.title(l);
			mm.style = st.mcStyle().label(l);
			c.team.add(mm);
		}
		if (v.result != null) {
			BattleView.Result pr = viewerId != null ? playerResults.get(viewerId) : null;
			c.result = pr != null ? pr : results.getOrDefault(l, v.result);
		}
		return c;
	}

	private void broadcastChat(Text t) {
		for (ServerPlayerEntity p : world.getPlayers()) if (p.squaredDistanceTo(stage) < 48 * 48) p.sendMessage(t, false);
	}
}
