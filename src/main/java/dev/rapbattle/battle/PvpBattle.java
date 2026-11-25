package dev.rapbattle.battle;

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
	private CompletableFuture<Llm.Verdict> verdict;
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
		from.sendMessage(Text.literal("Вызов отправлен: " + to.getName().getString() + " решает, выйти ли на баттл.").formatted(Formatting.GOLD), true);
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
			from.sendMessage(Text.literal(p.getName().getString() + " отказался от баттла. Зассал?").formatted(Formatting.GRAY), false);
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
				pb.end("Баттл сломался, смотри лог сервера");
			}
		}
		ALL.removeIf(pb -> pb.finished);
	}

	// ------------------------------------------------------------------ setup

	private PvpBattle(int id, ServerPlayerEntity pa, ServerPlayerEntity pb) {
		this.id = id;
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
		broadcastChat(Text.literal("Рэп-баттл: " + names.get(a) + " против " + names.get(b) + "! Первым читает " + names.get(a) + ".")
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
		v.rankArmor = "ИГРОК";
		v.rankStreet = "один на один";
		v.rankColor = 0xF07167;
		v.respectMax = 100;
		int beat = 4; // countdown
		List<List<String>> groups = RhymeBank.pickBlocks(4, rnd);
		int g = 0;
		for (UUID u : List.of(a, b)) {
			List<String> words = new ArrayList<>();
			for (int i = 0; i < LINES / 4; i++) words.addAll(groups.get(g++));
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
			DamagePop.spawn(world, p, "-2 запнулся", 0xFF6060);
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
		transcripts.put(u, CompletableFuture.supplyAsync(() -> {
			Transcriber.Result r = Transcriber.transcribeTimed(wav, hints);
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
		broadcastChat(Text.literal(who + " слился с баттла. Победа за " + names.get(other(u)) + ".").formatted(Formatting.GRAY));
		recordStats(other(u));
		end(null);
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
		if (verdict == null) {
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
			verdict = CompletableFuture.supplyAsync(() -> {
				Llm.Verdict v = noStt ? null : Llm.judge("—", "игрок против игрока", "PvP",
					"Это баттл ДВУХ ИГРОКОВ, моба нет: mobVerse поставь 0. Сравни игроков между собой, comment — кто кого разнёс и чем.",
					0, 0, List.of(), verses);
				if (v == null) v = Fallback.judge(0, 0, verses, rnd);
				return v;
			}, Battle.POOL);
			BattleView.Result r = new BattleView.Result();
			for (Llm.PlayerVerse pv : verses) r.players.add(new BattleView.PlayerScore(pv.name(), 0, pv.transcript().isBlank() ? "(тишина)" : pv.transcript()));
			view.result = r;
			return;
		}
		if (t < MIN_JUDGING_MS) return;
		if (!verdict.isDone() && now - verdictAsked < JUDGE_WAIT_MS) return;
		Llm.Verdict v = verdict.getNow(null);
		if (v == null) v = Fallback.judge(0, 0, List.of(), rnd);
		BattleView.Result r = view.result;
		r.teamBefore = respectA;
		r.mobBefore = respectB;
		r.respectMax = 100;
		for (UUID u : List.of(a, b)) {
			List<String> words = rhymes.getOrDefault(u, List.of());
			List<Llm.LineScore> ls = v.playerLines().getOrDefault(names.get(u), List.of());
			for (int i = 0; i < ls.size(); i++) {
				Llm.LineScore l = ls.get(i);
				String word = i < words.size() ? words.get(i) : "";
				int d = l.damage();
				r.hits.add(new BattleView.Hit(names.get(u), (word.isEmpty() ? "" : "«" + word + "» · ") + l.note(), d, u.equals(a), l.punch()));
				dealt.merge(u, d, Integer::sum);
			}
			int[] tm = Battle.beatTiming(timed.get(u), recordingStart.get(u), view.rows, names.get(u), beatMs);
			if (tm != null) {
				int on = 0, near = 0;
				for (int x : tm) if (x == 2) on++; else if (x == 1) near++;
				int d = on * 3 + near;
				if (d > 0) {
					r.hits.add(new BattleView.Hit(names.get(u), "рифмы в бит " + on + "/" + tm.length, d, u.equals(a), false));
					dealt.merge(u, d, Integer::sum);
				}
			}
		}
		for (BattleView.Hit h : r.hits) hit(h.toMob ? b : a, h.damage, false);
		r.respectTeam = respectA;
		r.respectMob = respectB;
		r.knockout = respectA <= 0 || respectB <= 0;
		UUID winner = respectB <= 0 ? a : respectA <= 0 ? b : respectA >= respectB ? a : b;
		r.winner = names.get(winner);
		r.playersWin = winner.equals(a);
		r.finalResult = true;
		r.comment = v.comment();
		r.teamLabel = names.get(a);
		r.mobLabel = names.get(b);
		r.dealt = dealt.getOrDefault(a, 0);
		for (BattleView.PlayerScore ps : r.players) {
			for (UUID u : List.of(a, b)) if (names.get(u).equals(ps.name)) ps.score = dealt.getOrDefault(u, 0);
		}
		phase = Phase.RESULT;
		phaseStart = now;
		broadcast();
	}

	private void finish() {
		ServerPlayerEntity pa = player(a);
		UUID winner = view.result != null && names.get(b).equals(view.result.winner) ? b : a;
		broadcastChat(Text.literal(names.get(winner) + " победил " + names.get(other(winner))
			+ (view.result != null && view.result.knockout ? " нокаутом" : " по очкам (" + Math.max(respectA, respectB) + " : " + Math.min(respectA, respectB) + ")"))
			.formatted(Formatting.GREEN));
		recordStats(winner);
		end(null);
	}

	private void recordStats(UUID winner) {
		Stats.of(world.getServer()).recordPvp(winner, other(winner), dealt.getOrDefault(winner, 0), dealt.getOrDefault(other(winner), 0));
	}

	private void end(String reason) {
		finished = true;
		for (UUID u : List.of(a, b)) {
			ServerPlayerEntity p = player(u);
			if (p == null) continue;
			p.setGlowing(false);
			if (reason != null) p.sendMessage(Text.literal(reason).formatted(Formatting.GRAY), false);
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
		v.team = new ArrayList<>();
		for (UUID u : List.of(a, b)) {
			ServerPlayerEntity p = player(u);
			Outfit.Score s = p == null ? new Outfit.Score(0, "") : Outfit.of(p);
			BattleView.Member mm = new BattleView.Member(names.get(u), s.label(), s.level(), p == null ? -1 : p.getId());
			// Their records for the intro cards and the scoreboard.
			Stats.Player st = Stats.of(world.getServer()).player(u);
			mm.wins = st.wins;
			mm.losses = st.losses;
			mm.streak = st.streak;
			mm.mvp = st.mvp;
			mm.best = st.best;
			mm.title = st.title();
			v.team.add(mm);
		}
		v.spectator = false;
		for (UUID u : List.of(a, b)) {
			ServerPlayerEntity p = player(u);
			if (p != null) ServerPlayNetworking.send(p, new Payloads.StateS2C(v.toJson()));
		}
		BattleView sv = v.copy();
		sv.spectator = true;
		double r2 = RapConfig.get().spectatorRadius * RapConfig.get().spectatorRadius;
		for (ServerPlayerEntity p : world.getPlayers()) {
			if (p.getUuid().equals(a) || p.getUuid().equals(b)) continue;
			if (p.squaredDistanceTo(stage) <= r2) {
				spectators.add(p.getUuid());
				ServerPlayNetworking.send(p, new Payloads.StateS2C(sv.toJson()));
			}
		}
	}

	private void broadcastChat(Text t) {
		for (ServerPlayerEntity p : world.getPlayers()) if (p.squaredDistanceTo(stage) < 48 * 48) p.sendMessage(t, false);
	}
}
