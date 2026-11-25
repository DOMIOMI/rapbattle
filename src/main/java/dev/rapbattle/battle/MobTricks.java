package dev.rapbattle.battle;

import dev.rapbattle.Lang;
import dev.rapbattle.net.BattleView;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Each mob's trick in a battle: interference on the players' table, special rules for the judge, and a
 * one-line hint shown in the lobby so players know what they are up against.
 */
public final class MobTricks {
	private MobTricks() {}

	private static final Map<String, String> HINTS = Map.ofEntries(
		Map.entry("witch", "Кидает зелье-помеху на твою таблицу."),
		Map.entry("fox", "Ворует одну твою рифму: закончи строку своим словом."),
		Map.entry("frog", "Слизывает языком одну твою рифму."),
		Map.entry("blaze", "Рифмы сгорают: видны только 2 доли, запоминай."),
		Map.entry("squid", "Брызгает чернилами: видна только текущая строка."),
		Map.entry("llama", "Плюётся: начало строки заляпано."),
		Map.entry("trader_llama", "Плюётся: начало строки заляпано."),
		Map.entry("goat", "Бодается: таблица отлетает в сторону."),
		Map.entry("illusioner", "Фальшивые рифмы-клоны: настоящая только на 4-й доле."),
		Map.entry("enderman", "Не смотри ему в глаза, пока он читает, иначе перемешает рифмы."),
		Map.entry("creeper", "Промолчишь целый такт — взорвётся."),
		Map.entry("warden", "Молчи, пока он читает: он слышит каждый звук."),
		Map.entry("ghast", "Отбей фаербол: используй его рифму против него."),
		Map.entry("guardian", "Держи одну тему во всех строках."),
		Map.entry("elder_guardian", "Держи одну тему во всех строках."),
		Map.entry("shulker", "Прячется в панцирь и не слушает тебя."),
		Map.entry("skeleton", "Узкое окно бита (0.22 доли), но попадания бьют больнее."),
		Map.entry("stray", "Узкое окно бита (0.22 доли), но попадания бьют больнее."),
		Map.entry("bogged", "Узкое окно бита (0.22 доли), но попадания бьют больнее."),
		Map.entry("parched", "Узкое окно бита (0.22 доли), но попадания бьют больнее."),
		Map.entry("spider", "Паутина рифм: 2-я строка пары скрыта до громкой 1-й."),
		Map.entry("cave_spider", "Паутина рифм: 2-я строка пары скрыта до громкой 1-й."),
		Map.entry("villager", "Изумруд в левой руке на старте снизит его ранг."),
		Map.entry("pillager", "Изумруд в левой руке на старте снизит его ранг."),
		Map.entry("vindicator", "Изумруд в левой руке на старте снизит его ранг."),
		Map.entry("creaking", "Держи его в прицеле, чтобы заморозить помехи."),
		Map.entry("pig", "С морковкой в руке у тебя он отвлекается."),
		Map.entry("piglin", "Уважает золото на тебе."),
		Map.entry("cat", "С рыбой в руке у тебя она добрее."),
		Map.entry("cow", "Медленный флоу: темп ниже."),
		Map.entry("chicken", "Самый лёгкий соперник, идеально для старта."),
		Map.entry("wolf", "Зовёт стаю."),
		Map.entry("zombie", "Ночью приходит ордой, днём слабее на солнце."));

	private static final Map<String, String> HINTS_EN = Map.ofEntries(
		Map.entry("witch", "Throws a disruption potion at your board."),
		Map.entry("fox", "Steals one of your rhymes: finish the line with your own word."),
		Map.entry("frog", "Snatches one of your rhymes with its tongue."),
		Map.entry("blaze", "Rhymes burn up: only visible for 2 beats, memorize them."),
		Map.entry("squid", "Sprays ink: only the current line is visible."),
		Map.entry("llama", "Spits on your board: the start of the line is covered."),
		Map.entry("trader_llama", "Spits on your board: the start of the line is covered."),
		Map.entry("goat", "Headbutts your board: it flies off to the side."),
		Map.entry("illusioner", "Fake clone rhymes: the real one only shows on beat 4."),
		Map.entry("enderman", "Don't look him in the eyes while he raps, or he scrambles your rhymes."),
		Map.entry("creeper", "Stay silent for a full bar and he blows up."),
		Map.entry("warden", "Stay quiet while he raps: he hears every sound."),
		Map.entry("ghast", "Deflect the fireball: use his own rhyme back against him."),
		Map.entry("guardian", "Stick to one topic across all your lines."),
		Map.entry("elder_guardian", "Stick to one topic across all your lines."),
		Map.entry("shulker", "Hides in its shell and doesn't listen to you."),
		Map.entry("skeleton", "Tighter beat window (0.22 beat), but on-beat bars hit harder."),
		Map.entry("stray", "Tighter beat window (0.22 beat), but on-beat bars hit harder."),
		Map.entry("bogged", "Tighter beat window (0.22 beat), but on-beat bars hit harder."),
		Map.entry("parched", "Tighter beat window (0.22 beat), but on-beat bars hit harder."),
		Map.entry("spider", "Webbed rhymes: the 2nd bar stays hidden until you hit the 1st."),
		Map.entry("cave_spider", "Webbed rhymes: the 2nd bar stays hidden until you hit the 1st."),
		Map.entry("villager", "An emerald in your off-hand at start lowers its rank."),
		Map.entry("pillager", "An emerald in your off-hand at start lowers its rank."),
		Map.entry("vindicator", "An emerald in your off-hand at start lowers its rank."),
		Map.entry("creaking", "Keep it in your sights to freeze its interference."),
		Map.entry("pig", "Hold a carrot in your hand and he gets distracted."),
		Map.entry("piglin", "Respects gold armor on you."),
		Map.entry("cat", "Hold fish in your hand and she goes easier on you."),
		Map.entry("cow", "Slow flow: lower tempo."),
		Map.entry("chicken", "Easiest opponent, perfect to start with."),
		Map.entry("wolf", "Calls the pack."),
		Map.entry("zombie", "Comes as a horde at night, weaker in the sun during the day."));

	private static final Map<String, String> JUDGE = Map.of(
		"ghast", "Если игрок использовал рифму или слово из куплета гаста против него — это «отбитый фаербол», большой бонус игроку.",
		"guardian", "Игрок должен держать одну тему во всех строках: штрафуй за скачки темы.",
		"elder_guardian", "Игрок должен держать одну тему во всех строках: штрафуй за скачки темы.",
		"shulker", "Шалкер прятался в панцире и не слушал соперника: оцени куплет шалкера на 2 балла ниже.",
		"skeleton", "Скелет строг к ритму: заметно штрафуй рваную, сбивчивую читку.",
		"parrot", "Попугай повторяет за другими: штрафуй игрока, если он сам повторялся.",
		"donkey", "Осёл повторяет одну и ту же строку: штрафуй повторы с обеих сторон.",
		"mule", "Мул повторяет одну и ту же строку: штрафуй повторы с обеих сторон.");

	private static final Map<String, String> JUDGE_EN = Map.of(
		"ghast", "If the player used a rhyme or word from the ghast's verse back against him, that's a 'deflected fireball' — big bonus for the player.",
		"guardian", "The player must stick to one topic across all lines: penalize topic jumping.",
		"elder_guardian", "The player must stick to one topic across all lines: penalize topic jumping.",
		"shulker", "The shulker hid in its shell and didn't listen to the opponent: score the shulker's verse 2 points lower.",
		"skeleton", "The skeleton is strict on rhythm: heavily penalize choppy, off-beat delivery.",
		"parrot", "The parrot repeats after others: penalize the player if they repeated themselves.",
		"donkey", "The donkey repeats the same line over and over: penalize repetition on both sides.",
		"mule", "The mule repeats the same line over and over: penalize repetition on both sides.");

	public static String hint(String id, String lang) { return (Lang.isRu(lang) ? HINTS : HINTS_EN).getOrDefault(id, ""); }

	@Deprecated
	public static String hint(String id) { return hint(id, Lang.RU); }

	public static String judgeHint(String id, String lang) { return (Lang.isRu(lang) ? JUDGE : JUDGE_EN).getOrDefault(id, ""); }

	@Deprecated
	public static String judgeHint(String id) { return judgeHint(id, Lang.RU); }

	/** Mobs whose interference is decided on the client from the player's own camera / microphone. */
	public static void flags(String id, BattleView v, String lang) {
		v.gaze = id.equals("enderman");
		v.fuse = id.equals("creeper");
		v.silence = id.equals("warden");
		v.creakingGaze = id.equals("creaking");
		v.trick = hint(id, lang);
	}

	@Deprecated
	public static void flags(String id, BattleView v) { flags(id, v, Lang.RU); }

	/** Puts the mob's interference on the players' rows. {@code rowsByPlayer} keeps each player's rows in order. */
	public static void apply(String id, List<List<BattleView.Row>> rowsByPlayer, Random rnd) {
		for (List<BattleView.Row> rows : rowsByPlayer) {
			if (rows.isEmpty()) continue;
			switch (id) {
				case "witch" -> {
					String[] potions = {"shake", "blind", "hidden"};
					String potion = potions[rnd.nextInt(potions.length)];
					if (potion.equals("hidden")) rows.get(rnd.nextInt(rows.size())).effect = "hidden";
					else for (BattleView.Row r : rows) r.effect = potion;
				}
				case "fox", "frog" -> rows.get(rnd.nextInt(rows.size())).effect = "stolen";
				case "blaze" -> rows.forEach(r -> r.effect = "burn");
				case "squid" -> rows.forEach(r -> r.effect = "ink");
				case "llama", "trader_llama" -> rows.get(0).effect = "spit";
				case "goat" -> {
					for (int i = 0; i < rows.size(); i += 4) rows.get(i).effect = "ram";
				}
				case "illusioner" -> rows.forEach(r -> r.effect = "illusion");
				case "spider", "cave_spider" -> {
					for (int i = 1; i < rows.size(); i += 2) rows.get(i).effect = "web_pair";
				}
				case "creaking" -> rows.forEach(r -> r.effect = "shake");
				default -> {}
			}
		}
	}

}
