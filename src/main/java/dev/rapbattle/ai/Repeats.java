package dev.rapbattle.ai;

import dev.rapbattle.Lang;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 6.34: finds a lazy verse for the judge - the same words over and over, the same word ending several lines, and cheap
 * "жопа / член" filler rhymes. The facts go into the judge prompt so the host roasts the monotony and does not reward it.
 */
public final class Repeats {
	private Repeats() {}

	private static final Set<String> STOP_RU = Set.of(
		"это", "как", "что", "так", "все", "всё", "тебя", "тебе", "меня", "мне", "ты", "я", "мы", "вы", "он", "она", "они",
		"его", "её", "ее", "их", "мой", "твой", "твоя", "моя", "свой", "был", "была", "было", "есть", "нет", "не", "да",
		"на", "в", "во", "с", "со", "и", "а", "но", "или", "по", "за", "из", "от", "до", "у", "к", "ко", "о", "об", "же",
		"ну", "вот", "там", "тут", "здесь", "уже", "ещё", "еще", "только", "даже", "чем", "для", "при", "без", "под", "над",
		"кто", "где", "когда", "если", "чтоб", "чтобы", "тот", "та", "те", "эти", "этот", "эта", "бы", "ли", "сам");
	private static final Set<String> STOP_EN = Set.of(
		"the", "a", "an", "and", "or", "but", "you", "your", "i", "me", "my", "we", "they", "he", "she", "it", "is", "are",
		"was", "were", "be", "to", "of", "in", "on", "at", "for", "with", "that", "this", "so", "like", "just", "got", "get",
		"im", "i'm", "dont", "don't", "can't", "cant", "not", "no", "yeah", "up", "out", "all", "what", "when", "who");
	/** Roots of cheap filler rhymes: anatomy and toilet words that rhyme with anything if you are lazy. */
	private static final List<String> CHEAP_RU = List.of("жоп", "член", "хуй", "хуя", "хуе", "хер", "пизд", "сра", "сру",
		"говн", "дерьм", "залуп", "яйц", "попа", "попу", "попе", "попк", "пук", "пёрд", "перд", "ссал", "ссу", "мочу", "моча");
	private static final List<String> CHEAP_EN = List.of("ass", "butt", "dick", "cock", "poop", "shit", "crap", "fart", "pee", "balls", "piss");

	static String norm(String w) {
		return w.toLowerCase(Locale.ROOT).replace('ё', 'е').replaceAll("[^\\p{L}']", "");
	}

	static boolean cheap(String w, boolean ru) {
		for (String root : ru ? CHEAP_RU : CHEAP_EN) {
			if (w.startsWith(root) || ru && w.length() > 3 && w.contains(root) && root.length() >= 4) return true;
		}
		return false;
	}

	/** The facts about one player's verse, or "" when it is varied enough. Texts are the spoken bars (silent ones skipped). */
	public static String note(String player, List<String> bars, String lang) {
		boolean ru = Lang.isRu(lang);
		Set<String> stop = ru ? STOP_RU : STOP_EN;
		Map<String, Integer> count = new LinkedHashMap<>();
		Map<String, Integer> endings = new LinkedHashMap<>();
		Map<String, Integer> cheap = new LinkedHashMap<>();
		int total = 0;
		for (String bar : bars) {
			if (bar == null || bar.isBlank()) continue;
			String last = "";
			for (String raw : bar.split("\\s+")) {
				String w = norm(raw);
				if (w.isEmpty()) continue;
				last = w;
				if (w.length() < 3 || stop.contains(w)) continue;
				total++;
				count.merge(w, 1, Integer::sum);
				if (cheap(w, ru)) cheap.merge(w, 1, Integer::sum);
			}
			if (last.length() >= 2) endings.merge(last, 1, Integer::sum);
		}
		List<String> facts = new ArrayList<>();
		List<String> rep = new ArrayList<>();
		count.entrySet().stream().filter(e -> e.getValue() >= 3).sorted((a, b) -> b.getValue() - a.getValue()).limit(4)
			.forEach(e -> rep.add("«" + e.getKey() + "» ×" + e.getValue()));
		if (!rep.isEmpty()) facts.add((ru ? "одни и те же слова: " : "the same words again and again: ") + String.join(", ", rep));
		List<String> end = new ArrayList<>();
		endings.entrySet().stream().filter(e -> e.getValue() >= 2).sorted((a, b) -> b.getValue() - a.getValue()).limit(3)
			.forEach(e -> end.add("«" + e.getKey() + "» ×" + e.getValue()));
		if (!end.isEmpty()) facts.add((ru ? "заканчивает строки одним и тем же словом: " : "ends bars on the same word: ") + String.join(", ", end));
		int cheapTotal = cheap.values().stream().mapToInt(Integer::intValue).sum();
		if (cheapTotal >= 2) {
			facts.add((ru ? "дешёвые туалетные рифмы: " : "cheap toilet rhymes: ") + String.join(", ", cheap.keySet().stream().limit(4).map(w -> "«" + w + "»").toList()));
		}
		if (total >= 12) {
			long unique = count.size();
			if (unique * 100 / total < 45) facts.add(ru ? "словарный запас бедный: из " + total + " слов только " + unique + " разных"
				: "poor vocabulary: only " + unique + " different words out of " + total);
		}
		if (facts.isEmpty()) return "";
		return (ru ? "ПОВТОРЫ у " + player + ": " : "REPEATS by " + player + ": ") + String.join("; ", facts) + ".";
	}

	/** The judge rule that goes with the facts above. */
	public static String rule(String lang) {
		return Lang.isRu(lang)
			? """
				Если у игрока есть пометка «ПОВТОРЫ»: строки с повторённым словом или дешёвой рифмой вроде «жопа / член» получают meaning не выше 1, \
				а повтор той же рифмы-концовки — rhyme не выше 1. В comment обязательно ткни в это: он как заевшая пластинка, \
				попугай с тремя словами, животное, которое умеет только одно и то же, — подбери свой подкол, назови конкретное слово.
				"""
			: """
				If a player has a "REPEATS" note: bars with the repeated word or a cheap rhyme like "ass / butt" get meaning at most 1, \
				and reusing the same end word gets rhyme at most 1. The comment must roast it: a broken record, a parrot with three words, \
				an animal that can only say one thing — pick your own jab and name the exact word.
				""";
	}
}
