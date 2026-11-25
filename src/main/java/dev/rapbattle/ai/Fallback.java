package dev.rapbattle.ai;

import dev.rapbattle.Lang;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

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

	private static final List<List<String>> VERSES_EN = List.of(
		List.of("You pulled up to the battle, {p}, built like a dirt block",
			"One verse from me and your whole crew goes into shock",
			"Your flow falls down like gravel at the bottom of the mine",
			"Pack up your wooden pickaxe and step out of the line"),
		List.of("Listen up, {p}, out here you are just zombie meat",
			"I drop bars harder than a creeper on the beat",
			"Your rhymes are cardboard and you swing a wooden sword",
			"You lost the round before you spoke, the crowd is bored"),
		List.of("You built a dirt shack and you called that thing a room",
			"A creeper hugged your doorway and your base went boom",
			"I run this whole biome, every mob knows my zone",
			"And you, {p}, are just a tourist out here all alone"),
		List.of("Default skin, {p}, you just booted up the game",
			"I spit fire on the mic while your whole verse is lame",
			"No armor, no diamonds, not a single loaf of bread",
			"Go run back to your village and hide under your bed"),
		List.of("No armor on your back, {p}, brave or dumb to fight",
			"After my verse drops you will respawn in sheer fright",
			"You punched trees with your fists while I carved out the stone",
			"Your peak is a dirt hole, mine is a diamond throne"),
		List.of("I watched you, {p}, dive in lava just to burn",
			"You lost your whole inventory and still did not learn",
			"Your chest is full of cobwebs, rotten flesh, and dust",
			"Even your iron armor is just falling into rust"),
		List.of("Your pickaxe is all wood, same as your weak flow",
			"I rap circles around you while your brain loads slow",
			"You bolt from one spider and you hide inside a cave",
			"Do not step to me, {p}, pretending that you are brave"),
		List.of("The sun went down, {p}, and you got caught in the night",
			"Hiding in a dirt box begging for a torch light",
			"I step onto the stage and the whole crowd sees a flash",
			"You stand there mute and turn your whole verse into ash"),
		List.of("You traded with a villager and lost all your gold",
			"Gave up your best emeralds for junk that he sold",
			"My bars hit like an avalanche, your rhymes are not deep",
			"Run home now, {p}, before I put your crew to sleep"),
		List.of("Listen tourist, this biome is my turf and my hall",
			"A flock of angry chickens could make a noob like you fall",
			"Three weeks in the mines and you still got no stack",
			"Just gravel in your pockets and a target on your back")
	);

	public static List<Llm.MobLine> mobVerse(List<String> speakers, List<String> opponents, int lines, Random rnd, String lang) {
		// Rhyming couplets from all verses, shuffled: each battle gets its own mix.
		List<List<String>> source = Lang.isRu(lang) ? VERSES : VERSES_EN;
		List<List<String>> couplets = new ArrayList<>();
		for (List<String> v : source) for (int i = 0; i + 1 < v.size(); i += 2) couplets.add(List.of(v.get(i), v.get(i + 1)));
		java.util.Collections.shuffle(couplets, rnd);
		String p = opponents.isEmpty() ? Lang.pick(lang, "нуб", "noob") : opponents.get(rnd.nextInt(opponents.size()));
		List<Llm.MobLine> out = new ArrayList<>();
		for (int i = 0; i < lines; i++) {
			String speaker = speakers.isEmpty() ? "" : speakers.get(i % speakers.size());
			String text = couplets.get((i / 2) % couplets.size()).get(i % 2);
			out.add(new Llm.MobLine(speaker, text.replace("{p}", p), i % 4 == 3));
		}
		return out;
	}

	@Deprecated
	public static List<Llm.MobLine> mobVerse(List<String> speakers, List<String> opponents, int lines, Random rnd) {
		return mobVerse(speakers, opponents, lines, rnd, Lang.RU);
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

	private static final List<String> WIN_EN = List.of(
		"Your rhymes stacked clean like blocks in a solid wall. The mob went crying back to its biome.",
		"You nailed the words and the mob took the beating. Clean win.",
		"The mob showed up flexing and left as loot drops. Respect!",
		"That was not a battle, that was a guided tour: you showed the mob its place.",
		"The crowd stood up. The mob sat down. Fair and square.",
		"Every rhyme hit dead center like a skeleton arrow point-blank. Total wipeout.",
		"The mob tried to bark back, but you straight up out-rapped it.",
		"Nobody on this turf has heard a flow like that since before the Caves update.",
		"You did not just beat it, you wrote it into the history books.",
		"That mob is only spawning on weekends now, it is too embarrassed.",
		"The mic is smoking, the mob is not. Easy win.",
		"You rapped so hard even the creeper decided not to blow up.",
		"Clean, tight, with punchlines. Mob, go practice on chickens.",
		"The mob expected easy loot and got a lecture in rap instead.",
		"Frame that verse and hang it above your bed. If you even have one.");
	private static final List<String> LOSE_EN = List.of(
		"You mumbled past the rhymes and the mob ran right over you with its verse.",
		"There were words, just no sense. The mob takes the round.",
		"Even a chicken keeps a steadier flow. Embarrassing.",
		"You stepped on stage, but you left your rhymes at home.",
		"The mob did not even break a sweat. You look like you are about to cry.",
		"Your verse sounded like a villager who just got scammed in a trade.",
		"Quiet, crooked, and off-rhyme. The mob is loving this.",
		"You took so long hunting for a rhyme the mob almost fell asleep.",
		"You brought a mic, just forgot to bring any bars.",
		"The mob broke you down into blocks and stuffed you in a chest.",
		"Even a zombie raps with soul, and you read like an oak sign.",
		"You came looking for respect and left carrying bricks.",
		"The crowd wanted fire and got wet gunpowder.",
		"The mob out-rapped you, outplayed you, and respawned you.",
		"Next time practice on pigs. They are not as harsh when they judge.");

	/** Extracts anchor words from a mob verse for the rebuttal target bar. */
	public static List<String> extractTargets(List<Llm.MobLine> lines, String lang) {
		return extractTargets(lines, lang, 2);
	}

	public static List<String> extractTargets(List<Llm.MobLine> lines, String lang, int maxTargets) {
		boolean ru = Lang.isRu(lang);
		int limit = Math.max(1, maxTargets);
		List<String> out = new ArrayList<>();
		java.util.Set<String> stop = ru
			? Set.of("это", "как", "что", "для", "про", "тебя", "меня", "тебе", "мне", "тут", "здесь", "даже", "просто", "если", "только")
			: Set.of("this", "that", "with", "your", "from", "just", "even", "here", "there", "into", "about", "like", "you", "the");
		for (Llm.MobLine ml : lines) {
			for (String raw : ml.text().split("\\s+")) {
				String w = raw.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}]", "");
				if (w.length() >= (ru ? 4 : 4) && !stop.contains(w) && !out.contains(w)) {
					out.add(w);
					if (out.size() >= limit) return out;
				}
			}
		}
		return out;
	}

	/** Scores every line by whether its rhyme word was actually said; the mob's verse lands inside its rank range. */
	public static Llm.Verdict judge(int lo, int hi, List<Llm.PlayerVerse> players, Random rnd, String lang) {
		return judge(lo, hi, players, Map.of(), false, List.of(), rnd, lang);
	}

	public static Llm.Verdict judge(int lo, int hi, List<Llm.PlayerVerse> players, Map<String, List<Boolean>> loudBars,
									boolean sttMissing, Random rnd, String lang) {
		return judge(lo, hi, players, loudBars, sttMissing, List.of(), rnd, lang);
	}

	/** Offline judge; when {@code sttMissing} is true, grades each bar from client mic loudness (5..7 loud, 0 silent). */
	public static Llm.Verdict judge(int lo, int hi, List<Llm.PlayerVerse> players, Map<String, List<Boolean>> loudBars,
									boolean sttMissing, List<String> targetWords, Random rnd, String lang) {
		boolean ru = Lang.isRu(lang);
		Map<String, List<Llm.LineScore>> lines = new LinkedHashMap<>();
		int hits = 0, targets = 0, sum = 0;
		for (Llm.PlayerVerse p : players) {
			List<Boolean> loud = loudBars == null ? List.of() : loudBars.getOrDefault(p.name(), List.of());
			List<Llm.BarPrompt> bars = p.bars() != null && !p.bars().isEmpty() ? p.bars() : null;
			String fullT = ru ? p.transcript().toLowerCase(Locale.ROOT).replace('ё', 'е') : normalizeEn(p.transcript());
			Set<String> fullEnWords = ru ? Set.of() : wordsEn(fullT);
			List<Llm.LineScore> ls = new java.util.ArrayList<>();
			for (int i = 0; i < p.rhymeWords().size(); i++) {
				String w = p.rhymeWords().get(i);
				Llm.LineScore s;
				if (sttMissing) {
					boolean heard = i < loud.size() && Boolean.TRUE.equals(loud.get(i));
					if (heard) {
						int sc = 5 + rnd.nextInt(3);
						s = new Llm.LineScore(sc, 2, 1, false, false, ru ? "рифма в точку" : "on the rhyme");
						hits++;
					} else {
						s = Llm.LineScore.silent(lang);
					}
				} else if (bars != null && i < bars.size() && bars.get(i).silent()) {
					s = Llm.LineScore.silent(lang);
				} else {
					String barText = bars != null && i < bars.size()
						? (ru ? bars.get(i).text().toLowerCase(Locale.ROOT).replace('ё', 'е') : normalizeEn(bars.get(i).text()))
						: fullT;
					boolean isReb = false;
					if (targetWords != null && !barText.isBlank()) {
						for (String tw : targetWords) {
							String normTw = ru ? tw.toLowerCase(Locale.ROOT).replace('ё', 'е').replaceAll("[^\\p{L}]", "") : normalizeEn(tw);
							String stemTw = normTw.length() > 4 ? normTw.substring(0, normTw.length() - 2) : normTw;
							if (!stemTw.isEmpty() && barText.contains(stemTw)) {
								isReb = true;
								break;
							}
						}
					}
					boolean freestyle = w == null || w.isBlank();
					if (freestyle) {
						if (barText.isBlank()) s = Llm.LineScore.silent(lang);
						else {
							int meaning = 2 + (rnd.nextInt(3) == 0 ? 1 : 0);
							s = Llm.LineScore.fromRubric(2, meaning, isReb, ru ? "своя рифма" : "own rhyme");
							hits++;
						}
					} else {
						Set<String> enWords = bars != null && i < bars.size() ? (ru ? Set.of() : wordsEn(barText)) : fullEnWords;
						boolean matched;
						if (ru) {
							String last = lastWord(w).toLowerCase(Locale.ROOT).replace('ё', 'е').replaceAll("[^\\p{L}]", "");
							String stem = last.length() > 4 ? last.substring(0, last.length() - 2) : last;
							matched = !barText.isBlank() && !stem.isEmpty() && barText.contains(stem);
						} else {
							matched = !barText.isBlank() && matchesEn(barText, enWords, w);
						}
						if (barText.isBlank()) s = Llm.LineScore.silent(lang);
						else if (matched) {
							int meaning = 1 + rnd.nextInt(2) + (rnd.nextInt(5) == 0 ? 1 : 0);
							s = Llm.LineScore.fromRubric(2, Math.min(3, meaning), isReb, ru ? "рифма в точку" : "on the rhyme");
							hits++;
						} else {
							s = Llm.LineScore.fromRubric(0, 1, isReb, ru ? "мимо рифмы" : "missed the rhyme");
						}
					}
				}
				ls.add(s);
				sum += s.score();
			}
			targets += p.rhymeWords().size();
			lines.put(p.name(), ls);
		}
		int mob = lo + rnd.nextInt(Math.max(1, hi - lo + 1));
		boolean good = targets > 0 && (double) sum / targets > mob;
		List<String> pool = good ? (ru ? WIN : WIN_EN) : (ru ? LOSE : LOSE_EN);
		String suffix = ru ? " (рифм в точку: " + hits + "/" + targets + ")" : " (rhymes hit: " + hits + "/" + targets + ")";
		return new Llm.Verdict(mob, lines, pool.get(rnd.nextInt(pool.size())) + suffix);
	}

	@Deprecated
	public static Llm.Verdict judge(int lo, int hi, List<Llm.PlayerVerse> players, Random rnd) {
		return judge(lo, hi, players, rnd, Lang.RU);
	}

	private static String normalizeEn(String s) {
		return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9\\s]", " ").replaceAll("\\s+", " ").trim();
	}

	private static Set<String> wordsEn(String normalized) {
		if (normalized.isEmpty()) return Set.of();
		Set<String> out = new HashSet<>();
		for (String part : normalized.split(" ")) {
			if (!part.isEmpty()) out.add(part);
		}
		return out;
	}

	private static final Map<String, Set<String>> HOMOPHONES_EN = buildHomophones(
		List.of("knight", "night"),
		List.of("board", "bored"),
		List.of("throne", "thrown"),
		List.of("groan", "grown"),
		List.of("pain", "pane"),
		List.of("rain", "reign", "rein"),
		List.of("plain", "plane"),
		List.of("red", "read"),
		List.of("bread", "bred"),
		List.of("beat", "beet"),
		List.of("meat", "meet"),
		List.of("feet", "feat"),
		List.of("break", "brake"),
		List.of("stake", "steak"),
		List.of("weak", "week"),
		List.of("soul", "sole"),
		List.of("whole", "hole"),
		List.of("miner", "minor")
	);

	@SafeVarargs
	private static Map<String, Set<String>> buildHomophones(List<String>... groups) {
		Map<String, Set<String>> m = new LinkedHashMap<>();
		for (List<String> g : groups) {
			Set<String> set = Set.copyOf(g);
			for (String w : g) m.put(w, set);
		}
		return m;
	}

	/** English homophone equivalents for words in the rhyme bank (e.g. knight/night). */
	public static Set<String> homophonesEn(String word) {
		return HOMOPHONES_EN.getOrDefault(word, Set.of(word));
	}

	/** Last whitespace-separated token of a rhyme phrase (e.g. "в пещере" -> "пещере"). */
	public static String lastWord(String phrase) {
		if (phrase == null) return "";
		String[] parts = phrase.trim().split("\\s+");
		return parts.length == 0 ? "" : parts[parts.length - 1];
	}

	private static boolean matchesEn(String normalizedTranscript, Set<String> words, String rawTarget) {
		String target = normalizeEn(lastWord(rawTarget));
		if (target.isEmpty()) return false;
		for (String cand : homophonesEn(target)) {
			if (words.contains(cand)) return true;
			if (words.contains(cand + "s") || words.contains(cand + "es")
				|| words.contains(cand + "ed") || words.contains(cand + "d")) return true;
			if (cand.endsWith("s") && cand.length() > 3 && words.contains(cand.substring(0, cand.length() - 1))) return true;
		}
		return false;
	}
}
