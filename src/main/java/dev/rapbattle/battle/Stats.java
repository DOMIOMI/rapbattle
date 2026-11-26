package dev.rapbattle.battle;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.rapbattle.Lang;
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
	private static final Map<String, String> TITLE_EN = Map.of(
		"Овцеубийца", "Sheep Slayer",
		"Гробовщик", "Undertaker",
		"Сапёр", "Bomb Squad",
		"Кожаный король", "Leather King",
		"Укротитель дракона", "Dragon Tamer",
		"Трёхголовый ноль", "Three-Headed Zero",
		"Громче Вардена", "Louder Than the Warden",
		"Босс-киллер", "Boss Killer",
		"Серийный", "Streak Machine",
		"Зассал", "Chickened Out");

	/** Translates a stored (Russian) title for display in {@code lang}. */
	public static String translateTitle(String title, String lang) {
		if (title == null || title.isEmpty() || Lang.isRu(lang)) return title == null ? "" : title;
		return TITLE_EN.getOrDefault(title, title);
	}

	public static final class Player {
		public int wins, losses, streak, bestStreak, mvp, best, chickened, respect;
		public String style = McStyle.PUNCHER.id;
		public boolean hinted500, hinted1500, guideGiven, dragonWon;
		public long dailyDay;
		public Map<String, Integer> speciesWins = new HashMap<>();
		public Map<String, Integer> dailySpeciesWins = new HashMap<>();
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
			Codec.STRING.optionalFieldOf("style", McStyle.PUNCHER.id).forGetter(p -> p.style),
			Codec.BOOL.optionalFieldOf("hinted500", false).forGetter(p -> p.hinted500),
			Codec.BOOL.optionalFieldOf("hinted1500", false).forGetter(p -> p.hinted1500),
			Codec.BOOL.optionalFieldOf("guideGiven", false).forGetter(p -> p.guideGiven),
			Codec.BOOL.optionalFieldOf("dragonWon", false).forGetter(p -> p.dragonWon),
			Codec.unboundedMap(Codec.STRING, Codec.INT).optionalFieldOf("species", Map.of()).forGetter(p -> p.speciesWins),
			Codec.STRING.listOf().optionalFieldOf("titles", List.of()).forGetter(p -> p.titles),
			Codec.mapPair(
				Codec.LONG.optionalFieldOf("dailyDay", 0L),
				Codec.unboundedMap(Codec.STRING, Codec.INT).optionalFieldOf("dailySpeciesWins", Map.of())
			).forGetter(p -> com.mojang.datafixers.util.Pair.of(p.dailyDay, p.dailySpeciesWins))
		).apply(i, (w, l, s, bs, m, b, r, c, st, h500, h1500, gg, dw, sp, t, daily) -> {
			Player p = new Player();
			p.wins = w; p.losses = l; p.streak = s; p.bestStreak = bs; p.mvp = m; p.best = b; p.respect = r; p.chickened = c;
			McStyle ms = McStyle.of(st);
			p.style = ms.isUnlocked(r) ? ms.id : McStyle.PUNCHER.id;
			p.hinted500 = h500; p.hinted1500 = h1500; p.guideGiven = gg; p.dragonWon = dw;
			p.speciesWins = new HashMap<>(sp);
			p.titles = new ArrayList<>(t);
			p.dailyDay = daily.getFirst();
			p.dailySpeciesWins = new HashMap<>(daily.getSecond());
			return p;
		}));

		public int dailyWinsFor(String speciesId, long day) {
			if (dailyDay != day) return 0;
			return dailySpeciesWins.getOrDefault(speciesId, 0);
		}

		public void recordDailyWin(String speciesId, long day) {
			if (dailyDay != day) {
				dailyDay = day;
				dailySpeciesWins.clear();
			}
			dailySpeciesWins.merge(speciesId, 1, Integer::sum);
		}

		public McStyle mcStyle() {
			McStyle ms = McStyle.of(style);
			return ms.isUnlocked(respect) ? ms : McStyle.PUNCHER;
		}

		public String title() { return titles.isEmpty() ? "" : titles.get(titles.size() - 1); }

		public String title(String lang) { return translateTitle(title(), lang); }

		void addTitle(String t) { if (!titles.contains(t)) titles.add(t); }
	}

	public record Legend(String player, String line, String date) {
		public static final Legend EMPTY = new Legend("", "", "");
		public static final Codec<Legend> CODEC = RecordCodecBuilder.create(i -> i.group(
			Codec.STRING.optionalFieldOf("player", "").forGetter(Legend::player),
			Codec.STRING.optionalFieldOf("line", "").forGetter(Legend::line),
			Codec.STRING.optionalFieldOf("date", "").forGetter(Legend::date)
		).apply(i, Legend::new));

		public boolean isEmpty() { return line == null || line.isBlank(); }
	}

	/** players: UUID → record; species: id → [mob wins, player wins]. */
	private final Map<String, Player> players;
	private final Map<String, List<Integer>> species;
	public Legend legend;

	public static final Codec<Stats> CODEC = RecordCodecBuilder.create(i -> i.group(
		Codec.unboundedMap(Codec.STRING, Player.CODEC).optionalFieldOf("players", Map.of()).forGetter(s -> s.players),
		Codec.unboundedMap(Codec.STRING, Codec.INT.listOf()).optionalFieldOf("species", Map.of()).forGetter(s -> s.species),
		Legend.CODEC.optionalFieldOf("legend", Legend.EMPTY).forGetter(s -> s.legend)
	).apply(i, Stats::new));

	public static final PersistentStateType<Stats> TYPE = new PersistentStateType<>("rapbattle_stats", Stats::new, CODEC, null);

	public Stats() { this(Map.of(), Map.of(), Legend.EMPTY); }

	private Stats(Map<String, Player> players, Map<String, List<Integer>> species, Legend legend) {
		this.players = new HashMap<>(players);
		this.species = new HashMap<>();
		species.forEach((k, v) -> this.species.put(k, new ArrayList<>(v)));
		this.legend = legend == null ? Legend.EMPTY : legend;
	}

	public void recordLegend(String player, String line) {
		if (line == null || line.isBlank()) return;
		String date = dev.rapbattle.Clock.today().format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy"));
		this.legend = new Legend(player == null ? "" : player, line.trim(), date);
		markDirty();
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
		return record(team, scores, mvp, playersWin, opp, outfitLevels, -1L);
	}

	/** Records one finished battle with optional overworld day for daily farm cap tracking. */
	public Map<UUID, List<String>> record(List<UUID> team, Map<UUID, Integer> scores, UUID mvp, boolean playersWin, Opponent opp,
										  Map<UUID, Integer> outfitLevels, long overworldDay) {
		return record(team, scores, mvp, playersWin, opp, opp.rank(), outfitLevels, overworldDay);
	}

	/** Records one finished battle using the effective battle rank. */
	public Map<UUID, List<String>> record(List<UUID> team, Map<UUID, Integer> scores, UUID mvp, boolean playersWin, Opponent opp,
										  Rank battleRank, Map<UUID, Integer> outfitLevels, long overworldDay) {
		Map<UUID, List<String>> earned = new HashMap<>();
		String sp = opp.profile().id();
		int oppRank = (battleRank != null ? battleRank : opp.rank()).level();
		List<Integer> s = species.computeIfAbsent(sp, k -> new ArrayList<>(List.of(0, 0)));
		s.set(playersWin ? 1 : 0, s.get(playersWin ? 1 : 0) + 1);
		UUID initiator = team.isEmpty() ? null : team.get(0);
		for (UUID u : team) {
			Player p = player(u);
			List<String> before = new ArrayList<>(p.titles);
			if (playersWin) {
				p.wins++;
				p.streak = Math.max(0, p.streak) + 1;
				p.bestStreak = Math.max(p.bestStreak, p.streak);
				p.speciesWins.merge(sp, 1, Integer::sum);
				if (overworldDay >= 0 && u.equals(initiator)) {
					p.recordDailyWin(sp, overworldDay);
				}
				if (p.speciesWins.getOrDefault("sheep", 0) >= 10) p.addTitle("Овцеубийца");
				if (p.speciesWins.getOrDefault("zombie", 0) >= 10) p.addTitle("Гробовщик");
				if (p.speciesWins.getOrDefault("creeper", 0) >= 5) p.addTitle("Сапёр");
				if (oppRank >= 5 && outfitLevels.getOrDefault(u, 6) <= 1) p.addTitle("Кожаный король");
				if (sp.equals("ender_dragon")) {
					p.dragonWon = true;
					p.addTitle("Укротитель дракона");
				}
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
				int gained = ProgressionRules.adjustRespectGain(p.respect, oppRank, sc);
				p.respect += gained;
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

	public String chickened(UUID u, String lang) {
		String t = chickened(u);
		return t == null ? null : translateTitle(t, lang);
	}
}
