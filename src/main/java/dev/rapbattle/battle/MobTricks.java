package dev.rapbattle.battle;

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
		Map.entry("skeleton", "Строг к ритму: не сбивайся."),
		Map.entry("pig", "С морковкой в руке у тебя он отвлекается."),
		Map.entry("piglin", "Уважает золото на тебе."),
		Map.entry("cat", "С рыбой в руке у тебя она добрее."),
		Map.entry("cow", "Медленный флоу: темп ниже."),
		Map.entry("chicken", "Самый лёгкий соперник, идеально для старта."),
		Map.entry("wolf", "Зовёт стаю."),
		Map.entry("zombie", "Ночью приходит ордой, днём слабее на солнце."));

	private static final Map<String, String> JUDGE = Map.of(
		"ghast", "Если игрок использовал рифму или слово из куплета гаста против него — это «отбитый фаербол», большой бонус игроку.",
		"guardian", "Игрок должен держать одну тему во всех строках: штрафуй за скачки темы.",
		"elder_guardian", "Игрок должен держать одну тему во всех строках: штрафуй за скачки темы.",
		"shulker", "Шалкер прятался в панцире и не слушал соперника: оцени куплет шалкера на 2 балла ниже.",
		"skeleton", "Скелет строг к ритму: заметно штрафуй рваную, сбивчивую читку.",
		"parrot", "Попугай повторяет за другими: штрафуй игрока, если он сам повторялся.",
		"donkey", "Осёл повторяет одну и ту же строку: штрафуй повторы с обеих сторон.",
		"mule", "Мул повторяет одну и ту же строку: штрафуй повторы с обеих сторон.");

	public static String hint(String id) { return HINTS.getOrDefault(id, ""); }

	public static String judgeHint(String id) { return JUDGE.getOrDefault(id, ""); }

	/** Mobs whose interference is decided on the client from the player's own camera / microphone. */
	public static void flags(String id, BattleView v) {
		v.gaze = id.equals("enderman");
		v.fuse = id.equals("creeper");
		v.silence = id.equals("warden");
		v.trick = hint(id);
	}

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
				default -> {}
			}
		}
	}

}
