package dev.rapbattle.battle;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.PersistentState;
import net.minecraft.world.PersistentStateType;

import java.util.*;

/**
 * Battle statistics of the world: every player's record (wins, losses, streaks, MVPs, best score, titles,
 * wins per species, times they chickened out into a fight) and the score of each species against players.
 * Saved with the world.
 */
public class Stats extends PersistentState {
	public static final class Player {
		public int wins, losses, streak, bestStreak, mvp, best, chickened, respect;
		public Map<String, Integer> speciesWins = new HashMap<>();
		public List<String> titles = new ArrayList<>();

		static final Codec<Player> CODEC = RecordCodecBuilder.create(i -> i.group(
			Codec.INT.optionalFieldOf("wins", 0).forGetter(p -> p.wins),
			Codec.INT.optionalFieldOf("losses", 0).forGetter(p -> p.losses),
			Codec.INT.optionalFieldOf("streak", 0).forGetter(p -> p.streak),
			Codec.INT.optionalFieldOf("bestStreak", 0).forGetter(p -> p.bestStreak),
			Codec.INT.optionalFieldOf("mvp", 0).forGetter(p -> p.mvp),
			Codec.INT.optionalFieldOf("bestDamage", 0).forGetter(p -> p.best),
			Codec.INT.optionalFieldOf("respect", 0).forGetter(p -> p.respect),
			Codec.INT.optionalFieldOf("chickened", 0).forGetter(p -> p.chickened),
			Codec.unboundedMap(Codec.STRING, Codec.INT).optionalFieldOf("species", Map.of()).forGetter(p -> p.speciesWins),
			Codec.STRING.listOf().optionalFieldOf("titles", List.of()).forGetter(p -> p.titles)
		).apply(i, (w, l, s, bs, m, b, r, c, sp, t) -> {
			Player p = new Player();
			p.wins = w; p.losses = l; p.streak = s; p.bestStreak = bs; p.mvp = m; p.best = b; p.respect = r; p.chickened = c;
			p.speciesWins = new HashMap<>(sp);
			p.titles = new ArrayList<>(t);
			return p;
		}));

		public String title() { return titles.isEmpty() ? "" : titles.get(titles.size() - 1); }

		void addTitle(String t) { if (!titles.contains(t)) titles.add(t); }
	}

	/** players: UUID → record; species: id → [mob wins, player wins]. */
	private final Map<String, Player> players;
	private final Map<String, List<Integer>> species;

	public static final Codec<Stats> CODEC = RecordCodecBuilder.create(i -> i.group(
		Codec.unboundedMap(Codec.STRING, Player.CODEC).optionalFieldOf("players", Map.of()).forGetter(s -> s.players),
		Codec.unboundedMap(Codec.STRING, Codec.INT.listOf()).optionalFieldOf("species", Map.of()).forGetter(s -> s.species)
	).apply(i, Stats::new));

	public static final PersistentStateType<Stats> TYPE = new PersistentStateType<>("rapbattle_stats", Stats::new, CODEC, null);

	public Stats() { this(Map.of(), Map.of()); }

	private Stats(Map<String, Player> players, Map<String, List<Integer>> species) {
		this.players = new HashMap<>(players);
		this.species = new HashMap<>();
		species.forEach((k, v) -> this.species.put(k, new ArrayList<>(v)));
	}

	public static Stats of(MinecraftServer server) {
		return server.getOverworld().getPersistentStateManager().getOrCreate(TYPE);
	}

	public Player player(UUID uuid) { return players.computeIfAbsent(uuid.toString(), k -> new Player()); }

	/** [mob wins, player wins] for this species in the world. */
	public int[] species(String id) {
		List<Integer> l = species.getOrDefault(id, List.of(0, 0));
		return new int[]{l.get(0), l.get(1)};
	}

	/** Records one finished battle. Returns titles earned just now, per player name. */
	public Map<UUID, List<String>> record(List<UUID> team, Map<UUID, Integer> scores, UUID mvp, boolean playersWin, Opponent opp,
										  Map<UUID, Integer> outfitLevels) {
		Map<UUID, List<String>> earned = new HashMap<>();
		String sp = opp.profile().id();
		List<Integer> s = species.computeIfAbsent(sp, k -> new ArrayList<>(List.of(0, 0)));
		s.set(playersWin ? 1 : 0, s.get(playersWin ? 1 : 0) + 1);
		for (UUID u : team) {
			Player p = player(u);
			List<String> before = new ArrayList<>(p.titles);
			if (playersWin) {
				p.wins++;
				p.streak = Math.max(0, p.streak) + 1;
				p.bestStreak = Math.max(p.bestStreak, p.streak);
				p.speciesWins.merge(sp, 1, Integer::sum);
				if (p.speciesWins.getOrDefault("sheep", 0) >= 10) p.addTitle("Овцеубийца");
				if (p.speciesWins.getOrDefault("zombie", 0) >= 10) p.addTitle("Гробовщик");
				if (p.speciesWins.getOrDefault("creeper", 0) >= 5) p.addTitle("Сапёр");
				if (opp.rank().level() >= 5 && outfitLevels.getOrDefault(u, 6) <= 1) p.addTitle("Кожаный король");
				if (sp.equals("ender_dragon")) p.addTitle("Укротитель дракона");
				if (sp.equals("wither")) p.addTitle("Трёхголовый ноль");
				if (sp.equals("warden")) p.addTitle("Громче Вардена");
				if (opp.rank().isBoss()) p.addTitle("Босс-киллер");
				if (p.streak >= 5) p.addTitle("Серийный");
			} else {
				p.losses++;
				p.streak = Math.min(0, p.streak) - 1;
			}
			if (u.equals(mvp)) p.mvp++;
			Integer sc = scores.get(u);
			if (sc != null) {
				p.respect += Math.max(0, sc);
				if (sc > p.best) p.best = sc;
			}
			List<String> fresh = new ArrayList<>(p.titles);
			fresh.removeAll(before);
			if (!fresh.isEmpty()) earned.put(u, fresh);
		}
		markDirty();
		return earned;
	}

	/** A player battle: wins, losses, streaks and Respect for both. */
	public void recordPvp(UUID winner, UUID loser, int winnerDealt, int loserDealt) {
		Player w = player(winner), l = player(loser);
		w.wins++;
		w.streak = Math.max(0, w.streak) + 1;
		w.bestStreak = Math.max(w.bestStreak, w.streak);
		w.respect += Math.max(0, winnerDealt);
		w.best = Math.max(w.best, winnerDealt);
		l.losses++;
		l.streak = Math.min(0, l.streak) - 1;
		l.respect += Math.max(0, loserDealt);
		l.best = Math.max(l.best, loserDealt);
		if (w.streak >= 5) w.addTitle("Серийный");
		markDirty();
	}

	/** The player chose to fight instead of rapping. */
	public String chickened(UUID u) {
		Player p = player(u);
		p.chickened++;
		markDirty();
		if (p.chickened >= 3 && !p.titles.contains("Зассал")) {
			p.addTitle("Зассал");
			return "Зассал";
		}
		return null;
	}
}
