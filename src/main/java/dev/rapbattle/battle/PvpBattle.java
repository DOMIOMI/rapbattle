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
	private final Map<UUID, ByteArrayOutputStream> audio = new HashMap<>();
	private final Map<UUID, CompletableFuture<String>> transcripts = new ConcurrentHashMap<>();
	private final Map<UUID, Transcriber.Result> timed = new ConcurrentHashMap<>();
	private final Map<UUID, Integer> recordingStart = new ConcurrentHashMap<>();
	private final Map<String, CompletableFuture<Llm.Verdict>> verdicts = new LinkedHashMap<>();
	private final Map<String, BattleView.Result> results = new LinkedHashMap<>();
	private long verdictAsked;
	private boolean sttMissing;
	boolean finished;
	private final Set<UUID> spectators = new HashSet<>();

	// ------------------------------------------------------------------ challenge

	/** An empty-hand hit on another player: a call-out. Returns true if the hit was taken as a challenge. */
	public static boolean challenge(ServerPlayerEntity from, ServerPlayerEntity to) {
		if (busy(from) || busy(to)) return false;
		long now = System.currentTimeMillis();
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
		if (from == null || System.currentTimeMillis() > inv.until()) return true;
		if (!accept) {
			from.sendMessage(Text.translatable("rapbattle.pvp.declined", p.getName().getString()).formatted(Formatting.GRAY), false);
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

	static void tickAll() {
		long now = System.currentTimeMillis();
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
		anchors.put(a, new Vec3d(pa.getX(), pa.getY(), pa.getZ()));
		anchors.put(b, new Vec3d(pb.getX(), pb.getY(), pb.getZ()));
		stage = new Vec3d((pa.getX() + pb.getX()) / 2, Math.min(pa.getY(), pb.getY()), (pa.getZ() + pb.getZ()) / 2);
		List<BeatTracks.Track> pool = new ArrayList<>();
		for (BeatTracks.Track t : BeatTracks.ALL) if (!t.id().equals("boss")) pool.add(t);
		track = pool.isEmpty() ? null : pool.get(rnd.nextInt(pool.size()));
		beatMs = track != null ? track.beatMs() : 650;
		beatZero = System.currentTimeMillis() + INTRO_MS + 600;
		phaseStart = System.currentTimeMillis();
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

	private ServerPlayerEntity player(UUID u) { return world.getServer().getPlayerManager().getPlayer(u); }

	private UUID other(UUID u) { return u.equals(a) ? b : a; }

	private String langOf(UUID u) { return langs.getOrDefault(u, lang); }

	// ------------------------------------------------------------------ during the battle

	public void onBar(ServerPlayerEntity p, boolean loud) {
		if (phase == Phase.INTRO) return;
		if (phase != Phase.TIMELINE) return;
		UUID u = p.getUuid();
		if (loud) {
			int c = combo.merge(u, 1, Integer::sum);
			float mult = c >= 6 ? 2f : c >= 3 ? 1.5f : 1f;
			int d = Math.round(3 * mult);
			hit(other(u), d, true);
			dealt.merge(u, d, Integer::sum);
			ServerPlayerEntity o = player(other(u));
			if (o != null) DamagePop.spawn(world, o, "-" + d + (mult > 1 ? "  x" + (mult == 2f ? "2" : "1.5") : ""), 0xFFD84A);
		} else {
			combo.put(u, 0);
			hit(u, 2, true);
			DamagePop.spawn(world, p, Lang.t(langOf(u), "rapbattle.battle.stumbled_pop"), 0xFF6060);
		}
		broadcast();
	}

	/** Damage to a player's Respect; during the verses it never drops below 1 (the judge decides a knockout). */
	private void hit(UUID target, int d, boolean live) {
		int floor = live ? 1 : 0;
		if (target.equals(a)) respectA = Math.max(floor, respectA - d);
		else respectB = Math.max(floor, respectB - d);
	}

	public void onAudio(ServerPlayerEntity p, Payloads.AudioC2S chunk) {
		UUID u = p.getUuid();
		if (transcripts.containsKey(u)) return;
		ByteArrayOutputStream buf = audio.computeIfAbsent(u, k -> new ByteArrayOutputStream());
		if (buf.size() + chunk.data().length > 8 * 1024 * 1024) return;
		buf.writeBytes(chunk.data());
		if (!chunk.last()) return;
		byte[] wav = buf.toByteArray();
		audio.remove(u);
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

	private void tick(long now) {
		if (finished) return;
		ServerPlayerEntity pa = player(a), pb = player(b);
		if (pa == null || pb == null) {
			leave(pa == null ? a : b);
			return;
		}
		// Both stand on their spots and face each other.
		for (UUID u : List.of(a, b)) {
			ServerPlayerEntity p = player(u);
			Vec3d at = anchors.get(u);
			if (p.squaredDistanceTo(at) > 0.25) p.networkHandler.requestTeleport(at.x, at.y, at.z, p.getYaw(), p.getPitch());
		}
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

	private void judging(long now) {
		long t = now - phaseStart;
		if (t > AUDIO_WAIT_MS) for (UUID u : List.of(a, b)) transcripts.putIfAbsent(u, CompletableFuture.completedFuture(""));
		if (verdicts.isEmpty()) {
			boolean done = transcripts.size() == 2 && transcripts.values().stream().allMatch(CompletableFuture::isDone);
			if (!done && t < AUDIO_WAIT_MS + STT_WAIT_MS) return;
			List<Llm.PlayerVerse> verses = new ArrayList<>();
			for (UUID u : List.of(a, b)) {
				CompletableFuture<String> f = transcripts.get(u);
				String text = f == null ? "" : f.getNow("");
				verses.add(new Llm.PlayerVerse(names.get(u), rhymes.getOrDefault(u, List.of()), text == null ? "" : text));
			}
			boolean noStt = sttMissing;
			verdictAsked = now;
			// One judge per language in the battle: each player reads the breakdown in his own language.
			for (String l : new LinkedHashSet<>(List.of(langOf(a), langOf(b)))) {
				verdicts.put(l, CompletableFuture.supplyAsync(() -> {
					Llm.Verdict v = noStt ? null : Llm.judge("—", Lang.t(l, "rapbattle.pvp.judge_species"), "PvP",
						Lang.t(l, "rapbattle.pvp.judge_expectation"),
						0, 0, List.of(), verses, l);
					if (v == null) v = Fallback.judge(0, 0, verses, rnd, l);
					return v;
				}, Battle.POOL));
				BattleView.Result r = new BattleView.Result();
				for (Llm.PlayerVerse pv : verses) r.players.add(new BattleView.PlayerScore(pv.name(), 0, pv.transcript().isBlank() ? Lang.t(l, "rapbattle.battle.silence") : pv.transcript()));
				results.put(l, r);
			}
			view.result = results.get(langOf(a));
			return;
		}
		if (t < MIN_JUDGING_MS) return;
		boolean allDone = verdicts.values().stream().allMatch(CompletableFuture::isDone);
		if (!allDone && now - verdictAsked < JUDGE_WAIT_MS) return;
		Map<String, Llm.Verdict> vs = new HashMap<>();
		for (Map.Entry<String, CompletableFuture<Llm.Verdict>> e : verdicts.entrySet()) {
			Llm.Verdict v = e.getValue().getNow(null);
			vs.put(e.getKey(), v != null ? v : Fallback.judge(0, 0, List.of(), rnd, e.getKey()));
		}
		int before0 = respectA, before1 = respectB;
		for (UUID u : List.of(a, b)) {
			List<String> words = rhymes.getOrDefault(u, List.of());
			// Damage comes from the judge in the reader's language; the notes are shown in each viewer's language.
			List<Llm.LineScore> own = vs.get(langOf(u)).playerLines().getOrDefault(names.get(u), List.of());
			for (int i = 0; i < own.size(); i++) {
				Llm.LineScore l = own.get(i);
				String word = i < words.size() ? words.get(i) : "";
				int d = l.damage();
				for (Map.Entry<String, BattleView.Result> e : results.entrySet()) {
					List<Llm.LineScore> theirs = vs.get(e.getKey()).playerLines().getOrDefault(names.get(u), List.of());
					String note = i < theirs.size() ? theirs.get(i).note() : l.note();
					e.getValue().hits.add(new BattleView.Hit(names.get(u), (word.isEmpty() ? "" : "«" + word + "» · ") + note, d, u.equals(a), l.punch()));
				}
				dealt.merge(u, d, Integer::sum);
			}
			int[] tm = Battle.beatTiming(timed.get(u), recordingStart.get(u), view.rows, names.get(u), beatMs, langOf(u));
			if (tm != null) {
				int on = 0, near = 0;
				for (int x : tm) if (x == 2) on++; else if (x == 1) near++;
				int d = on * 3 + near;
				if (d > 0) {
					for (Map.Entry<String, BattleView.Result> e : results.entrySet())
						e.getValue().hits.add(new BattleView.Hit(names.get(u), Lang.t(e.getKey(), "rapbattle.battle.hit_rhymes_on_beat", on, tm.length), d, u.equals(a), false));
					dealt.merge(u, d, Integer::sum);
				}
			}
		}
		BattleView.Result base = results.get(langOf(a));
		for (BattleView.Hit h : base.hits) hit(h.toMob ? b : a, h.damage, false);
		boolean ko = respectA <= 0 || respectB <= 0;
		UUID winner = respectB <= 0 ? a : respectA <= 0 ? b : respectA >= respectB ? a : b;
		for (Map.Entry<String, BattleView.Result> e : results.entrySet()) {
			BattleView.Result r = e.getValue();
			r.teamBefore = before0;
			r.mobBefore = before1;
			r.respectMax = 100;
			r.respectTeam = respectA;
			r.respectMob = respectB;
			r.knockout = ko;
			r.winner = names.get(winner);
			r.playersWin = winner.equals(a);
			r.finalResult = true;
			r.comment = vs.get(e.getKey()).comment();
			r.teamLabel = names.get(a);
			r.mobLabel = names.get(b);
			r.dealt = dealt.getOrDefault(a, 0);
			for (BattleView.PlayerScore ps : r.players) {
				for (UUID u : List.of(a, b)) if (names.get(u).equals(ps.name)) ps.score = dealt.getOrDefault(u, 0);
			}
		}
		view.result = base;
		phase = Phase.RESULT;
		phaseStart = now;
		broadcast();
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
		Stats.of(world.getServer()).recordPvp(winner, other(winner), dealt.getOrDefault(winner, 0), dealt.getOrDefault(other(winner), 0));
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
		v.beatElapsedMs = System.currentTimeMillis() - beatZero;
		for (UUID u : List.of(a, b)) {
			ServerPlayerEntity p = player(u);
			if (p != null) ServerPlayNetworking.send(p, new Payloads.StateS2C(localized(v, langOf(u), false).toJson()));
		}
		double r2 = RapConfig.get().spectatorRadius * RapConfig.get().spectatorRadius;
		for (ServerPlayerEntity p : world.getPlayers()) {
			if (p.getUuid().equals(a) || p.getUuid().equals(b)) continue;
			if (p.squaredDistanceTo(stage) <= r2) {
				spectators.add(p.getUuid());
				ServerPlayNetworking.send(p, new Payloads.StateS2C(localized(v, Lang.of(p), true).toJson()));
			}
		}
	}

	/** The battle as one viewer sees it: labels, outfit, titles and the judge's breakdown in his language. */
	private BattleView localized(BattleView v, String l, boolean spectator) {
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
			c.team.add(mm);
		}
		if (v.result != null) c.result = results.getOrDefault(l, v.result);
		return c;
	}

	private void broadcastChat(Text t) {
		for (ServerPlayerEntity p : world.getPlayers()) if (p.squaredDistanceTo(stage) < 48 * 48) p.sendMessage(t, false);
	}
}
