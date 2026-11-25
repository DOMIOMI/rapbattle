package dev.rapbattle.ai;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/** Offline content used when the language model / speech-to-text are not configured or fail. */
public final class Fallback {
	private Fallback() {}

	private static final List<List<String>> VERSES = List.of(
		List.of("Ты пришёл на баттл, а выглядишь как {p} бетон",
			"Я тебя разнесу, ты не нужен даже как фон",
			"Твой флоу как гравий, падает вниз без следа",
			"Иди копай алмазы, тебе туда навсегда"),
		List.of("Слышь, {p}, ты в этом мире просто нуб",
			"Я читаю жёстче, чем голодный зомби в суп",
			"Твой меч деревянный, и панчи из картона",
			"Ты проиграл ещё до первого патрона"),
		List.of("Ты строил дом, а получилась конура",
			"Тебя крипер обнял, и закончилась игра",
			"Я на районе главный, это мой биом",
			"А ты, {p}, тут гость, и то с трудом"),
		List.of("Твой скин из дефолта, {p}, ты как Стив",
			"Я читаю быстро, ты стоишь как в архив",
			"Ни брони, ни рифм, ни алмазной кирки",
			"Иди домой, тебя ждут твои грядки"),
		List.of("Ты пришёл без брони, {p}, смело или глупо",
			"После моего куплета будет только скорлупа",
			"Ты копал руками, пока я качал район",
			"Твой максимум — землянка, мой — алмазный трон"),
		List.of("Я видел, {p}, как ты падал в лаву дважды",
			"Ты горел, как спичка, и кричал отважно",
			"Твой сундук пустой, в нём паутина и мох",
			"На твоих похоронах даже зомби не издох"),
		List.of("Твоя кирка из дерева, как и твои слова",
			"Я читаю так, что кругом идёт голова",
			"Ты бежишь от паука, хоть у тебя есть меч",
			"Не тебе, {p}, о районе со мной тут речь"),
		List.of("Ночь пришла, {p}, а ты всё без кровати",
			"Сидишь в норе, как крот, и ждёшь благодати",
			"Я выхожу на сцену, и зал уже в огне",
			"А ты стоишь, как жердь, и молчишь в тишине"),
		List.of("Ты торговался с жителем и вышел без штанов",
			"Отдал все изумруды за пару пирогов",
			"Мой флоу как лавина, твой — как ручеёк",
			"Беги домой, {p}, пока ещё цел твой бок"),
		List.of("Слышь, турист, тут мой биом и мои правила",
			"Тебя бы стадо куриц на место поставило",
			"Ты ищешь алмазы третью неделю подряд",
			"А нашёл только гравий и свой же позорный взгляд")
	);

	public static List<Llm.MobLine> mobVerse(List<String> speakers, List<String> opponents, int lines, Random rnd) {
		// Rhyming couplets from all verses, shuffled: each battle gets its own mix.
		List<List<String>> couplets = new ArrayList<>();
		for (List<String> v : VERSES) for (int i = 0; i + 1 < v.size(); i += 2) couplets.add(List.of(v.get(i), v.get(i + 1)));
		java.util.Collections.shuffle(couplets, rnd);
		String p = opponents.isEmpty() ? "нуб" : opponents.get(rnd.nextInt(opponents.size()));
		List<Llm.MobLine> out = new ArrayList<>();
		for (int i = 0; i < lines; i++) {
			String speaker = speakers.isEmpty() ? "" : speakers.get(i % speakers.size());
			String text = couplets.get((i / 2) % couplets.size()).get(i % 2);
			out.add(new Llm.MobLine(speaker, text.replace("{p}", p), i % 4 == 3));
		}
		return out;
	}

	private static final List<String> WIN = List.of(
		"Рифмы легли как блоки в ровную стену. Моб ушёл плакать в свой биом.",
		"Ты попал в слова, а моб попал под раздачу. Чистая победа.",
		"Моб пришёл с понтами, а ушёл на дроп. Красава!",
		"Это был не баттл, это была экскурсия: ты показал мобу, где его место.",
		"Зал встал. Моб сел. Всё по-честному.",
		"Каждая рифма в точку, как стрела скелета в упор. Разнос.",
		"Моб пытался огрызаться, но ты его просто перечитал.",
		"Такой флоу на районе последний раз слышали до обновления пещер.",
		"Ты его не победил, ты его переписал в учебник истории.",
		"Моб теперь будет спавниться только по выходным, ему стыдно.",
		"Микрофон дымится, моб нет. Уверенная победа.",
		"Ты читал так, что даже крипер передумал взрываться.",
		"Чисто, ровно, с панчами. Моб, иди тренируйся на курицах.",
		"Моб ждал лёгкую добычу, а получил лекцию по рэпу.",
		"Этот куплет можно вешать в рамку над кроватью. Если она у тебя есть.");
	private static final List<String> LOSE = List.of(
		"Ты мямлил мимо рифм, а моб просто переехал тебя куплетом.",
		"Слова были, смысла не было. Моб забирает раунд.",
		"Даже курица читает ровнее. Позорище.",
		"Ты вышел на сцену, а рифмы остались дома.",
		"Моб даже не вспотел. А ты, кажется, уже плачешь.",
		"Твой куплет звучал как житель, которого обманули при торговле.",
		"Это было тихо, криво и мимо. Моб доволен.",
		"Ты так долго искал рифму, что моб успел вздремнуть.",
		"Микрофон у тебя был, а вот слов не завезли.",
		"Моб разобрал тебя на блоки и сложил в сундук.",
		"Даже зомби читает с душой, а ты как деревянная табличка.",
		"Ты пришёл за уважением, а ушёл за кирпичами.",
		"Зал хотел огня, а получил мокрый порох.",
		"Моб тебя перечитал, переиграл и переспавнил.",
		"В следующий раз потренируйся на свиньях. Они не так строго судят.");

	/** Scores every line by whether its rhyme word was actually said; the mob's verse lands inside its rank range. */
	public static Llm.Verdict judge(int lo, int hi, List<Llm.PlayerVerse> players, Random rnd) {
		Map<String, List<Llm.LineScore>> lines = new LinkedHashMap<>();
		int hits = 0, targets = 0, sum = 0;
		for (Llm.PlayerVerse p : players) {
			String t = p.transcript().toLowerCase(Locale.ROOT).replace('ё', 'е');
			List<Llm.LineScore> ls = new java.util.ArrayList<>();
			for (String w : p.rhymeWords()) {
				String stem = w.toLowerCase(Locale.ROOT).replace('ё', 'е');
				stem = stem.length() > 4 ? stem.substring(0, stem.length() - 2) : stem;
				Llm.LineScore s;
				if (t.isBlank()) s = new Llm.LineScore(0, false, "тишина");
				else if (t.contains(stem)) {
					int sc = 5 + rnd.nextInt(4);
					s = new Llm.LineScore(sc, sc >= 8 && rnd.nextBoolean(), "рифма в точку");
					hits++;
				} else s = new Llm.LineScore(1 + rnd.nextInt(2), false, "мимо рифмы");
				ls.add(s);
				sum += s.score();
			}
			targets += p.rhymeWords().size();
			lines.put(p.name(), ls);
		}
		int mob = lo + rnd.nextInt(Math.max(1, hi - lo + 1));
		boolean good = targets > 0 && (double) sum / targets > mob;
		List<String> pool = good ? WIN : LOSE;
		return new Llm.Verdict(mob, lines, pool.get(rnd.nextInt(pool.size())) + " (рифм в точку: " + hits + "/" + targets + ")");
	}
}
