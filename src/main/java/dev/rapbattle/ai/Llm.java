package dev.rapbattle.ai;

import com.google.gson.JsonObject;
import dev.rapbattle.Lang;
import dev.rapbattle.RapBattle;
import dev.rapbattle.RapConfig;
import dev.rapbattle.battle.Scoring;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The language model writes the mob's verse and judges the battle. Both calls return JSON constrained by a schema.
 * Provider: OpenRouter (any model it serves, the default is a cheap one). Keys stay on the server; without a key the
 * mod falls back to {@link Fallback}.
 */
public final class Llm {
	private Llm() {}

	public static final double VERSE_TEMPERATURE = 0.8;
	public static final double JUDGE_TEMPERATURE = 0.15;

	public static boolean available() {
		return !RapConfig.get().openRouterKey().isEmpty();
	}

	/** Sends one request and returns the parsed JSON object, or null on any failure. */
	private static JsonObject ask(String system, String user, Map<String, Object> schema, long maxTokens, double temperature, String lang) {
		if (!available()) return null;
		return OpenRouter.ask(system, user, schema, maxTokens, temperature, lang);
	}

	private static String tone(String lang) {
		if (Lang.isRu(lang)) {
			return RapConfig.get().allowSwearing
				? "Можно жёстко и с матом, это взрослый юмор между друзьями, но без расизма, гомофобии и угроз реальным людям."
				: "Без мата: жёстко, но цензурно.";
		}
		return RapConfig.get().allowSwearing
			? "Go hard and swearing is fine — it is adult banter between friends — but no racism, homophobia, or real-life threats."
			: "No swearing: go hard, but keep it clean.";
	}

	// ---------------------------------------------------------------- round content

	/** One mob line: who of the mob team reads it, the text, and whether it is a punchline. */
	public record MobLine(String speaker, String text, boolean punch) {}

	/** The mob team's verse, rhyme groups (4 words each) for players, and 2 target words for rebuttals. */
	public record Content(List<MobLine> lines, List<List<String>> rhymes, List<String> targets) {
		public Content(List<MobLine> lines, List<List<String>> rhymes) {
			this(lines, rhymes, List.of());
		}
	}

	public record Rapper(String name, String species, String adlib, String style) {}

	/** A different line of attack each round, so verses do not come out the same. */
	private static final List<String> ANGLES = List.of(
		"высмеять его броню и шмот", "высмеять его постройки и дом", "напомнить, как он умирает в лаве",
		"его кирка и инструменты — позор", "он боится ночи и мобов", "он копает руками, нищеброд",
		"его ник и скин", "он ворует у жителей", "он заблудился в пещере", "его питомцы его не уважают",
		"он проиграл криперу", "у него ферма пустая", "он не нашёл ни одного алмаза", "он спит в грязи без кровати",
		"он скучный, как блок земли", "флекс моба: его район и банда", "флекс моба: его сила и звук",
		"сравнить игрока с курицей", "игрок пришёл с мечом, а микрофон не держит", "игрок в Незере трясётся",
		"игрок — турист в этом биоме", "его рифмы деревянные", "он читает как житель торгуется");

	private static final List<String> ANGLES_EN = List.of(
		"roast their armor and gear", "roast their builds and dirt shack", "remind them how they keep dying in lava",
		"their pickaxe and tools are an embarrassment", "they are terrified of the night and mobs", "they mine with bare hands like a broke noob",
		"their username and skin", "they steal from villagers", "they got lost in a cave", "even their own pets do not respect them",
		"they lost a fight to a creeper", "their farm is empty", "they have never found a single diamond", "they sleep in the dirt with no bed",
		"they are as boring as a dirt block", "mob flex: its turf and gang", "mob flex: its strength and signature sound",
		"compare the player to a chicken", "the player brought a sword but cannot hold a mic", "the player shakes in the Nether",
		"the player is a tourist in this biome", "their rhymes are wooden", "they rap like a villager haggling");

	/**
	 * Writes the mob team's verse and the players' rhyme groups in one request.
	 * @param difficulty 0..4, see {@code Rank.rhymeDifficulty}
	 */
	public static Content roundContent(List<Rapper> mobs, String rankLabel, String mood, String language, int lineCount,
									   List<String> opponents, int rhymeGroups, int difficulty, int round, String previous, String rivalry,
									   String lang) {
		return roundContent(mobs, rankLabel, mood, language, lineCount, opponents, rhymeGroups, difficulty, round, previous, rivalry, "", lang);
	}

	public static Content roundContent(List<Rapper> mobs, String rankLabel, String mood, String language, int lineCount,
									   List<String> opponents, int rhymeGroups, int difficulty, int round, String previous, String rivalry,
									   String chosenTopic, String lang) {
		return roundContent(mobs, rankLabel, mood, language, lineCount, opponents, rhymeGroups, difficulty, round, previous, rivalry, chosenTopic, 2, lang);
	}

	public static Content roundContent(List<Rapper> mobs, String rankLabel, String mood, String language, int lineCount,
									   List<String> opponents, int rhymeGroups, int difficulty, int round, String previous, String rivalry,
									   String chosenTopic, int maxTargets, String lang) {
		int targetLimit = Math.max(2, maxTargets);
		boolean ru = Lang.isRu(lang);
		String system;
		String diff;
		StringBuilder u = new StringBuilder();
		if (ru) {
			system = """
				Ты пишешь куплет для мобов в рэп-баттле внутри Minecraft. Мобы читают первыми и диссят игроков.
				Главное — СМЫСЛ. Каждая строка должна быть законченной понятной фразой, которую человек поймёт с первого раза.
				Сначала придумай theme: один конкретный наезд на игрока (его ник, броню, то что он нуб, строит хижины, умирает в лаве и т.п.)
				и один флекс моба (его сила, повадки, звук, место обитания). Весь куплет держит эту тему.
				Правила:
				- Строки разной длины: от 4 до 9 слов, чередуй короткие хлёсткие и длинные, не делай все одинаковыми.
				- Рифмовка AABB: строки 1-2 рифмуются между собой, 3-4 между собой и т.д.
				- Рифмуй слова в разных формах: падежи, множественное число, глаголы в прошедшем и будущем,
				  прилагательные в разных родах (топором/потом, сгорели/недели), а не только начальная форма.
				- Рифма настоящая: совпадают ударный гласный и окончание (шерсть/дерзость — плохо, нуба/зуба — хорошо).
				- Никаких случайных слов ради рифмы и никакой бессмыслицы. Если рифма не ложится по смыслу — перестрой строку.
				- Пары строк связаны: вторая строка отвечает на первую, развивает её или добивает панчем.
				- Отсылки к Minecraft конкретные (крафт, алмазы, крипер, кровать, лава, Незер, дерево руками), а не общие слова.
				- Говори в манере моба (характер ниже), иногда вставляй его фирменный звук в начало строки.
				- Последнее слово строки — рифма, отдельным словом, без знаков препинания после него.
				- Если мобов несколько, строки распределяются между ними по очереди, у каждого свой голос; speaker = имя моба.
				- punch = true у 1-2 самых жёстких строк.
				Пример хорошей пары (овца против игрока Steve):
				  «Бе-е! Ты в кожанке, Steve, и это позор»
				  «Даже моя шерсть дороже, чем твой топор»
				Ещё придумай группы рифм для игроков: в каждой ровно 4 русских слова, которые точно рифмуются между собой
				(одинаковое ударное окончание), удобные для ответного панча. Не повторяй рифмы мобов.
				Слова в группе бери в разных формах, не только начальной: падежи, множественное число, глаголы
				(например: «кирками, руками, врагами, веками» или «сгорел, умел, задел, пропел»).
				Каждый раз новый куплет: не повторяй типовые фразы, придумывай свежие образы.
				Также укажи targets: ровно """ + targetLimit + """
				 коротких опорных слова (существительных или ярких образа) прямо из твоего куплета мобов, которые игрок может обыграть в ответ.
				""" + tone(lang);
			diff = switch (difficulty) {
				case 0, 1 -> "простые, короткие, бытовые (кот/рот/живот/компот)";
				case 2 -> "обычные, двусложные";
				case 3 -> "сложные: трёхсложные и составные, с ударением не на последнем слоге";
				default -> "очень сложные: длинные слова, неочевидные созвучия, но всё ещё настоящие рифмы";
			};
			u.append("Ранг соперника: ").append(rankLabel).append(". Настроение: ").append(mood).append(". Язык: перевод с ").append(language).append(".\n");
			u.append("Мобы в команде:\n");
			for (Rapper r : mobs) {
				u.append("- ").append(r.name()).append(" (").append(r.species()).append("), звук: ").append(r.adlib())
					.append(". Характер: ").append(r.style()).append('\n');
			}
			u.append("Соперники (игроки): ").append(String.join(", ", opponents)).append(".\n");
			String angle = (chosenTopic != null && !chosenTopic.isBlank()) ? chosenTopic
				: ANGLES.get(new java.util.Random().nextInt(ANGLES.size()));
			u.append("Угол атаки в этот раз: ").append(angle).append(".\n");
			if (rivalry != null && !rivalry.isBlank()) u.append(rivalry).append(' ');
			if (round > 1) u.append("Это раунд ").append(round).append(". В прошлом раунде было:\n").append(previous).append("\nОтветь на это и не повторяйся.\n");
			u.append("Напиши ровно ").append(lineCount).append(" строк для мобов и ").append(rhymeGroups)
				.append(" групп рифм для игроков. Сложность рифм игроков: ").append(diff).append('.');
		} else {
			system = """
				You are writing a verse for mobs in a Minecraft rap battle. The mobs rap first and diss the players.
				Most important is MEANING. Every line must be a complete, clear phrase that a human gets on the first read.
				First invent a theme: one specific attack on the player (their username, armor, being a noob, dirt shacks, dying in lava, etc.)
				and one mob flex (its strength, habits, sound, biome). The whole verse sticks to this theme.
				Rules:
				- Vary line lengths: 4 to 9 words, mix short snappy bars with longer ones, do not make them all the same.
				- AABB rhyme scheme: lines 1-2 rhyme with each other, 3-4 with each other, etc.
				- Use varied word forms: plurals, past tense, -ing verbs, adjectives (burned/learned, blocks/shocks), not just base nouns.
				- Real rhymes: the stressed vowel and ending must match (wool/full is good, sword/world is bad).
				- No random filler words for the sake of a rhyme and no nonsense. If a rhyme does not make sense, rewrite the line.
				- Couplets connect: the second line answers the first, builds on it, or finishes it with a punchline.
				- Use concrete Minecraft references (crafting, diamonds, creeper, bed, lava, Nether, punching trees), not generic fluff.
				- Stay in the mob's voice (style below), occasionally opening a line with its signature sound.
				- The last word of each line is the rhyme, as a standalone word, with no punctuation after it.
				- If there are multiple mobs, alternate lines between them, each in their own voice; speaker = the mob's name.
				- Set punch = true on the 1-2 hardest lines.
				Example of a good couplet (sheep vs player Steve):
				  "Baa! You're in leather gear, Steve, what a shame"
				  "Even my wool is worth more than your name"
				Also invent rhyme groups for the players: each group has exactly 4 English words that truly rhyme with each other
				(same stressed vowel and ending), easy to use for a clapback. Do not repeat the mobs' rhymes.
				Use varied word forms in each group, not just base nouns: plurals, past tense, -ing verbs
				(for example: "mining, shining, whining, dining" or "burned, turned, learned, earned").
				Fresh verse every time: do not recycle generic lines, invent new imagery.
				Also return targets: exactly """ + targetLimit + """
				 short anchor words (nouns/images) from the mob's verse that the player can flip in a rebuttal.
				""" + tone(lang);
			diff = switch (difficulty) {
				case 0, 1 -> "simple, short, everyday words (cat/hat/bat/flat)";
				case 2 -> "normal, two-syllable words";
				case 3 -> "tricky: three-syllable or compound words with non-final stress";
				default -> "very hard: long words and unexpected matches, still true rhymes";
			};
			u.append("Opponent rank: ").append(rankLabel).append(". Mood: ").append(mood).append(". Language: translated from ").append(language).append(".\n");
			u.append("Mobs on the team:\n");
			for (Rapper r : mobs) {
				u.append("- ").append(r.name()).append(" (").append(r.species()).append("), sound: ").append(r.adlib())
					.append(". Style: ").append(r.style()).append('\n');
			}
			u.append("Opponents (players): ").append(String.join(", ", opponents)).append(".\n");
			String angle = (chosenTopic != null && !chosenTopic.isBlank()) ? chosenTopic
				: ANGLES_EN.get(new java.util.Random().nextInt(ANGLES_EN.size()));
			u.append("Angle of attack this time: ").append(angle).append(".\n");
			if (rivalry != null && !rivalry.isBlank()) u.append(rivalry).append(' ');
			if (round > 1) u.append("This is round ").append(round).append(". In the previous round:\n").append(previous).append("\nRespond to it and do not repeat yourself.\n");
			u.append("Write exactly ").append(lineCount).append(" lines for the mobs and ").append(rhymeGroups)
				.append(" rhyme groups for the players. Player rhyme difficulty: ").append(diff).append('.');
		}
		Map<String, Object> line = Map.of("type", "object",
			"properties", Map.of("speaker", Map.of("type", "string"), "text", Map.of("type", "string"), "punch", Map.of("type", "boolean")),
			"required", List.of("speaker", "text", "punch"), "additionalProperties", false);
		Map<String, Object> props = new LinkedHashMap<>();
		props.put("theme", Map.of("type", "string"));
		props.put("lines", Map.of("type", "array", "items", line));
		props.put("rhymes", Map.of("type", "array", "items", Map.of("type", "array", "items", Map.of("type", "string"))));
		props.put("targets", Map.of("type", "array", "items", Map.of("type", "string")));
		Map<String, Object> schema = Map.of(
			"type", "object",
			"properties", props,
			"required", List.of("theme", "lines", "rhymes", "targets"),
			"additionalProperties", false);
		JsonObject o = ask(system, u.toString(), schema, 3000, VERSE_TEMPERATURE, lang);
		if (o == null || !o.has("lines")) return null;
		try {
			List<MobLine> lines = new ArrayList<>();
			for (var e : o.getAsJsonArray("lines")) {
				JsonObject l = e.getAsJsonObject();
				String text = l.get("text").getAsString().trim();
				if (text.isEmpty()) continue;
				lines.add(new MobLine(l.get("speaker").getAsString().trim(), text, l.has("punch") && l.get("punch").getAsBoolean()));
			}
			List<List<String>> rhymes = new ArrayList<>();
			if (o.has("rhymes")) {
				for (var g : o.getAsJsonArray("rhymes")) {
					List<String> words = new ArrayList<>();
					for (var w : g.getAsJsonArray()) {
						String word = w.getAsString().trim().toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}-]", "");
						if (!word.isEmpty() && !words.contains(word)) words.add(word);
					}
					if (words.size() >= 4) rhymes.add(words.subList(0, 4));
				}
			}
			List<String> targets = new ArrayList<>();
			if (o.has("targets")) {
				for (var tw : o.getAsJsonArray("targets")) {
					String w = tw.getAsString().trim().toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}-]", "");
					if (!w.isEmpty() && !targets.contains(w) && targets.size() < targetLimit) targets.add(w);
				}
			}
			if (lines.size() < lineCount) return null;
			return new Content(lines.subList(0, lineCount), rhymes, targets);
		} catch (RuntimeException e) {
			RapBattle.LOG.error("Bad verse JSON: {}", o, e);
			return null;
		}
	}

	@Deprecated
	public static Content roundContent(List<Rapper> mobs, String rankLabel, String mood, String language, int lineCount,
									   List<String> opponents, int rhymeGroups, int difficulty, int round, String previous, String rivalry) {
		return roundContent(mobs, rankLabel, mood, language, lineCount, opponents, rhymeGroups, difficulty, round, previous, rivalry, Lang.RU);
	}

	// ---------------------------------------------------------------- aggro lines

	/** Short lines a mob shouts when it goes for a player. {p} stands for the player's nick. */
	public static List<String> aggroLines(String species, String style, String adlib, List<String> examples, int count, String lang) {
		String system;
		String user;
		if (Lang.isRu(lang)) {
			system = """
				Ты пишешь короткие пафосные фразы, которые моб из Minecraft выкрикивает, когда нападает на игрока.
				До 10 слов, по-русски, смешно и дерзко, в характере моба, с его звуком. Каждая фраза — законченная мысль.
				Можно использовать {p} вместо ника игрока (не во всех фразах).
				""" + tone(lang);
			user = "Моб: " + species + ". Характер: " + style + " Звук: " + adlib + ".\nПримеры: " + String.join(" | ", examples)
				+ "\nПридумай " + count + " новых фраз, не повторяя примеры.";
		} else {
			system = """
				You write short, cocky one-liners that a Minecraft mob shouts when charging at a player.
				Up to 10 words, in English, funny and bold, in the mob's character, with its sound. Each line is a complete thought.
				You may use {p} for the player's username (not in every line).
				""" + tone(lang);
			user = "Mob: " + species + ". Style: " + style + " Sound: " + adlib + ".\nExamples: " + String.join(" | ", examples)
				+ "\nCome up with " + count + " new lines without repeating the examples.";
		}
		Map<String, Object> schema = Map.of("type", "object",
			"properties", Map.of("lines", Map.of("type", "array", "items", Map.of("type", "string"))),
			"required", List.of("lines"), "additionalProperties", false);
		JsonObject o = ask(system, user, schema, 1500, VERSE_TEMPERATURE, lang);
		if (o == null || !o.has("lines")) return null;
		List<String> out = new ArrayList<>();
		o.getAsJsonArray("lines").forEach(e -> {
			String s = e.getAsString().trim();
			if (!s.isEmpty() && s.length() < 90) out.add(s);
		});
		return out.isEmpty() ? null : out;
	}

	@Deprecated
	public static List<String> aggroLines(String species, String style, String adlib, List<String> examples, int count) {
		return aggroLines(species, style, adlib, examples, count, Lang.RU);
	}

	// ---------------------------------------------------------------- judge 2.0

	/** One bar prepared for the judge: 1-based line number, target rhyme, timing label, spoken text, and whether the bar was silent. */
	public record BarPrompt(int line, String targetWord, String timing, String text, boolean silent) {}

	public record PlayerVerse(String name, List<String> rhymeWords, String transcript, String timingNote, List<BarPrompt> bars) {
		public PlayerVerse(String name, List<String> rhymeWords, String transcript) {
			this(name, rhymeWords, transcript, "", List.of());
		}

		public PlayerVerse(String name, List<String> rhymeWords, String transcript, String timingNote) {
			this(name, rhymeWords, transcript, timingNote, List.of());
		}
	}

	/**
	 * Judge 2.0 line evaluation:
	 * rhyme (0..2), meaning (0..3), rebuttal (true/false), note (<= 30 chars).
	 * Code computes {@code score = meaning == 0 ? 0 : rhyme * 2 + meaning * 2} and {@code punch = meaning == 3}.
	 */
	public record LineScore(int score, int rhyme, int meaning, boolean punch, boolean rebuttal, String note) {
		public LineScore(int score, boolean punch, String note) {
			this(score, score >= 6 ? 2 : score >= 3 ? 1 : 0, punch ? 3 : score >= 6 ? 2 : score > 0 ? 1 : 0, punch, false, note);
		}

		public static LineScore fromRubric(int rhyme, int meaning, boolean rebuttal, String note) {
			int r = Math.max(0, Math.min(2, rhyme));
			int m = Math.max(0, Math.min(3, meaning));
			int sc = Scoring.scoreFromRubric(r, m);
			boolean p = sc > 0 && Scoring.punchFromRubric(m);
			return new LineScore(sc, r, m, p, sc > 0 && rebuttal, trimNote(note));
		}

		public static LineScore silent(String lang) {
			return new LineScore(0, 0, 0, false, false, Lang.isRu(lang) ? "тишина" : "silence");
		}
	}

	/** The judge's verdict for one round: the mob's verse 0..10, every player's lines, and the host's comment. */
	public record Verdict(int mobVerse, Map<String, List<LineScore>> playerLines, String comment) {}

	/** Single-call PvP verdict supporting both same-language and bilingual (RU vs EN) player battles. */
	public record PvpVerdict(Map<String, List<LineScore>> linesRu, Map<String, List<LineScore>> linesEn,
							 String commentRu, String commentEn) {
		public Verdict forLang(String lang) {
			boolean ru = Lang.isRu(lang);
			return new Verdict(0, ru ? linesRu : linesEn, ru ? commentRu : commentEn);
		}
	}

	private static String trimNote(String raw) {
		if (raw == null) return "";
		String n = raw.trim();
		return n.length() > 30 ? n.substring(0, 29) + "…" : n;
	}

	private static List<BarPrompt> ensureBars(PlayerVerse p, String lang) {
		if (p.bars() != null && !p.bars().isEmpty()) return p.bars();
		List<Scoring.BarSlot> slots = new ArrayList<>();
		for (int i = 0; i < p.rhymeWords().size(); i++) slots.add(new Scoring.BarSlot(i + 1, i * 4, p.rhymeWords().get(i)));
		List<Scoring.SlicedBar> sliced = Scoring.sliceBars(null, null, p.transcript(), slots, 600, lang);
		List<BarPrompt> out = new ArrayList<>(sliced.size());
		for (Scoring.SlicedBar b : sliced) out.add(new BarPrompt(b.lineIndex(), b.targetWord(), "—", b.text(), b.silent()));
		return out;
	}

	public static Verdict judge(String mobName, String species, String rankLabel, String expectation, int lo, int hi,
								List<String> mobLines, List<PlayerVerse> players, String lang) {
		boolean ru = Lang.isRu(lang);
		Map<String, List<BarPrompt>> barsByPlayer = new LinkedHashMap<>();
		boolean anySpoken = false;
		for (PlayerVerse p : players) {
			List<BarPrompt> bp = ensureBars(p, lang);
			barsByPlayer.put(p.name(), bp);
			for (BarPrompt b : bp) if (!b.silent()) anySpoken = true;
		}
		// Silent bars are immediately 0 — if nobody said anything at all, skip asking the model about player lines.
		if (!anySpoken) {
			RapBattle.LOG.warn("Judge skipped: all player bars marked silent (players={})", players.size());
			return null;
		}

		String system;
		StringBuilder u = new StringBuilder();
		if (ru) {
			system = """
				Ты объективный судья рэп-баттла в Minecraft. Против моба (или команды мобов) читает команда игроков.
				Речь игроков распознана автоматически и разбита по тактам (строкам), возможны мелкие ошибки распознавания:
				не штрафуй за опечатки STT, если слышно созвучие и понятен смысл.
				ВАЖНО: строки с пометкой «(промолчал такт)» уже получили 0 автоматически — НЕ оценивай их и НЕ включай в массив lines!
				Оценивай ТОЛЬКО непустые строки, указывая точный номер строки `line` из списка.
				Рубрика для каждой непустой строки:
				- rhyme (0..2): 0 — мимо заданной рифмы, 1 — неточная рифма / созвучие / искажение STT, 2 — точная рифма в конце строки. Если у строки указано «СВОЯ РИФМА к предыдущей», оценивай, зарифмовал ли игрок свою строку с концом предыдущей строки!
				- meaning (0..3): 0 — бессмысленный набор слов ради рифмы, 1 — простая связная фраза, 2 — адресный наезд по теме или сопернику, 3 — яркий образ, игра слов или мощный панч.
				- rebuttal (boolean): true, только если строка прямо отбивает или переворачивает конкретный тезис из куплета моба.
				- note: дерзкая подпись до 30 символов по-русски («рифма в точку», «панч про шерсть», «ответ про лаву», «вода»).
				mobVerse (0..10): насколько силён куплет мобов.
				comment: 1–2 живые фразы ведущего по-русски (до 200 символов), упомяни конкретную строчку игрока и вынеси вердикт без цифр и баллов.
				""" + tone(lang);
			u.append("Соперник: ").append(mobName).append(" (").append(species).append("), ранг ").append(rankLabel).append(".\n");
			u.append(expectation).append(" Обычно куплет моба этого ранга оценивается от ").append(lo).append(" до ").append(hi).append(".\n");
			u.append("Куплет мобов:\n");
			mobLines.forEach(l -> u.append("  ").append(l).append('\n'));
			for (PlayerVerse p : players) {
				u.append("\nИгрок ").append(p.name()).append(" (по тактам):\n");
				for (BarPrompt b : barsByPlayer.get(p.name())) {
					String targetInfo = b.targetWord().isEmpty() ? "СВОЯ РИФМА к предыдущей строке" : "цель-рифма: «" + b.targetWord() + "»";
					u.append("  ").append(b.line()).append(". [").append(targetInfo)
						.append(" | тайминг: ").append(b.timing()).append("] → ")
						.append(b.silent() ? "(промолчал такт)" : "«" + b.text() + "»").append('\n');
				}
			}
		} else {
			system = """
				You are the objective judge of a Minecraft rap battle. One or more players battle a mob (or mob crew).
				Player speech was auto-transcribed and sliced into bars; minor STT errors happen, so forgive near-homophones when sense and sound match.
				IMPORTANT: bars marked "(silent bar)" are already scored 0 automatically — DO NOT evaluate them and DO NOT include them in `lines`!
				Evaluate ONLY non-silent bars, returning their exact 1-based `line` number from the list.
				Rubric for each non-silent bar:
				- rhyme (0..2): 0 — missed target rhyme, 1 — slant rhyme / near-homophone / STT typo, 2 — exact rhyme landed at bar end. If a bar says "OWN RHYME to previous bar", judge whether it rhymes with the end of the previous bar!
				- meaning (0..3): 0 — incoherent word salad, 1 — simple coherent line, 2 — aimed diss at opponent/topic, 3 — vivid image, wordplay, or hard punchline.
				- rebuttal (boolean): true only if the bar directly counters or flips a specific claim from the mob's verse.
				- note: punchy screen label up to 30 chars in English ("dead-on rhyme", "wool punchline", "flipped lava diss", "filler").
				mobVerse (0..10): how strong the mob team's verse was.
				comment: 1–2 sentences in English (up to 200 chars) in a cocky battle-host voice, referencing a concrete player bar with no numbers.
				""" + tone(lang);
			u.append("Opponent: ").append(mobName).append(" (").append(species).append("), rank ").append(rankLabel).append(".\n");
			u.append(expectation).append(" Usually a mob verse of this rank scores between ").append(lo).append(" and ").append(hi).append(".\n");
			u.append("Mob verse:\n");
			mobLines.forEach(l -> u.append("  ").append(l).append('\n'));
			for (PlayerVerse p : players) {
				u.append("\nPlayer ").append(p.name()).append(" (bar by bar):\n");
				for (BarPrompt b : barsByPlayer.get(p.name())) {
					String targetInfo = b.targetWord().isEmpty() ? "OWN RHYME to previous bar" : "target rhyme: \"" + b.targetWord() + "\"";
					u.append("  ").append(b.line()).append(". [").append(targetInfo)
						.append(" | timing: ").append(b.timing()).append("] → ")
						.append(b.silent() ? "(silent bar)" : "\"" + b.text() + "\"").append('\n');
				}
			}
		}

		Map<String, Object> lineProps = new LinkedHashMap<>();
		lineProps.put("line", Map.of("type", "integer"));
		lineProps.put("rhyme", Map.of("type", "integer"));
		lineProps.put("meaning", Map.of("type", "integer"));
		lineProps.put("rebuttal", Map.of("type", "boolean"));
		lineProps.put("note", Map.of("type", "string"));
		Map<String, Object> lineSchema = Map.of("type", "object", "properties", lineProps,
			"required", List.of("line", "rhyme", "meaning", "rebuttal", "note"), "additionalProperties", false);
		Map<String, Object> ps = Map.of("type", "object",
			"properties", Map.of("name", Map.of("type", "string"), "lines", Map.of("type", "array", "items", lineSchema)),
			"required", List.of("name", "lines"), "additionalProperties", false);
		Map<String, Object> schema = Map.of(
			"type", "object",
			"properties", Map.of(
				"mobVerse", Map.of("type", "integer"),
				"players", Map.of("type", "array", "items", ps),
				"comment", Map.of("type", "string")),
			"required", List.of("mobVerse", "players", "comment"),
			"additionalProperties", false);

		JsonObject o = ask(system, u.toString(), schema, 4000, JUDGE_TEMPERATURE, lang);
		if (o == null) {
			RapBattle.LOG.warn("Judge LLM returned null (OpenRouter/model error or empty response)");
			return null;
		}
		try {
			Map<String, JsonObject> playerJsonByName = new LinkedHashMap<>();
			List<JsonObject> playerJsonOrdered = new ArrayList<>();
			for (var e : o.getAsJsonArray("players")) {
				JsonObject pj = e.getAsJsonObject();
				playerJsonByName.put(pj.get("name").getAsString().trim(), pj);
				playerJsonOrdered.add(pj);
			}
			Map<String, List<LineScore>> fixed = new LinkedHashMap<>();
			for (int i = 0; i < players.size(); i++) {
				PlayerVerse pv = players.get(i);
				JsonObject pj = playerJsonByName.containsKey(pv.name()) ? playerJsonByName.get(pv.name())
					: i < playerJsonOrdered.size() ? playerJsonOrdered.get(i) : null;
				fixed.put(pv.name(), mergeBarScores(barsByPlayer.get(pv.name()), pj, "note", lang));
			}
			int mob = Math.max(0, Math.min(10, o.get("mobVerse").getAsInt()));
			return new Verdict(mob, fixed, o.get("comment").getAsString().trim());
		} catch (RuntimeException e) {
			RapBattle.LOG.error("Bad judge JSON: {}", o, e);
			return null;
		}
	}

	/**
	 * Single-call PvP judge for two players: tells the model who went first and that the second reader could rebut.
	 * When the two players use different languages, asks for notes and comments in both RU and EN in one response.
	 */
	public static PvpVerdict judgePvp(PlayerVerse first, PlayerVerse second, String langFirst, String langSecond) {
		List<BarPrompt> bars1 = ensureBars(first, langFirst);
		List<BarPrompt> bars2 = ensureBars(second, langSecond);
		boolean anySpoken = bars1.stream().anyMatch(b -> !b.silent()) || bars2.stream().anyMatch(b -> !b.silent());
		if (!anySpoken) return null;

		boolean bilingual = !Lang.isRu(langFirst) == Lang.isRu(langSecond);
		String primaryLang = bilingual ? Lang.EN : langFirst;
		boolean ru = Lang.isRu(primaryLang);

		String system;
		StringBuilder u = new StringBuilder();
		if (!bilingual && ru) {
			system = """
				Ты объективный судья PvP рэп-баттла 1-на-1 между двумя игроками в Minecraft.
				Игрок 1 читал ПЕРВЫМ и задавал тон (у него rebuttal = false). Игрок 2 читал ВТОРЫМ после куплета Игрока 1 и мог прямо отвечать на его строки (rebuttal = true за точный ответ на наезд Игрока 1).
				Строки «(промолчал такт)» НЕ оценивай и НЕ включай в массив lines (за них уже стоит 0).
				Рубрика для непустых строк: rhyme (0..2), meaning (0..3), rebuttal (boolean), note (до 30 символов по-русски).
				comment: 1–2 дерзкие фразы ведущего по-русски (до 200 символов) без цифр — сравни обоих и скажи, кто кого перечитал.
				""" + tone(Lang.RU);
			appendPvpBarsRu(u, first, bars1, true);
			appendPvpBarsRu(u, second, bars2, false);
		} else if (!bilingual) {
			system = """
				You are the objective judge of a 1v1 PvP Minecraft rap battle between two players.
				Player 1 rapped FIRST to open the battle (rebuttal = false for Player 1). Player 2 rapped SECOND after hearing Player 1 and could directly answer/flip Player 1's bars (rebuttal = true when countering Player 1's lines).
				DO NOT evaluate or include "(silent bar)" entries in `lines` (they automatically score 0).
				Rubric for non-silent bars: rhyme (0..2), meaning (0..3), rebuttal (boolean), note (up to 30 chars in English).
				comment: 1–2 cocky host sentences in English (up to 200 chars) with no numbers, comparing both rappers.
				""" + tone(Lang.EN);
			appendPvpBarsEn(u, first, bars1, true);
			appendPvpBarsEn(u, second, bars2, false);
		} else {
			system = """
				You are the bilingual (Russian + English) judge of a 1v1 PvP Minecraft rap battle. Each player rapped in their own language.
				Player 1 rapped FIRST to open the battle (rebuttal = false for Player 1). Player 2 rapped SECOND after hearing Player 1 and could directly answer/flip Player 1's disses even across languages (rebuttal = true when countering Player 1's points).
				DO NOT evaluate or include "(silent bar)" entries in `lines` (they automatically score 0).
				Use ONE fair scale for both languages:
				- rhyme (0..2): 0 missed target rhyme, 1 slant rhyme / STT near-match, 2 exact target rhyme in that player's language.
				- meaning (0..3): 0 word salad, 1 simple coherent bar, 2 aimed diss, 3 vivid punchline/wordplay.
				- rebuttal (boolean): true only when Player 2 directly counters a point from Player 1's verse.
				- noteRu (Russian, <=30 chars) and noteEn (English, <=30 chars) for every evaluated bar.
				- commentRu (Russian) and commentEn (English): 1–2 cocky host sentences (<=200 chars) with no numbers.
				""" + tone(Lang.EN);
			u.append("Player 1 (rapped FIRST, language ").append(Lang.isRu(langFirst) ? "Russian" : "English").append(") — ").append(first.name()).append(":\n");
			for (BarPrompt b : bars1) {
				u.append("  ").append(b.line()).append(". [target rhyme: \"").append(b.targetWord()).append("\" | timing: ").append(b.timing()).append("] → ")
					.append(b.silent() ? "(silent bar)" : "\"" + b.text() + "\"").append('\n');
			}
			u.append("\nPlayer 2 (rapped SECOND — could answer Player 1, language ").append(Lang.isRu(langSecond) ? "Russian" : "English").append(") — ").append(second.name()).append(":\n");
			for (BarPrompt b : bars2) {
				u.append("  ").append(b.line()).append(". [target rhyme: \"").append(b.targetWord()).append("\" | timing: ").append(b.timing()).append("] → ")
					.append(b.silent() ? "(silent bar)" : "\"" + b.text() + "\"").append('\n');
			}
		}

		Map<String, Object> lineProps = new LinkedHashMap<>();
		lineProps.put("line", Map.of("type", "integer"));
		lineProps.put("rhyme", Map.of("type", "integer"));
		lineProps.put("meaning", Map.of("type", "integer"));
		lineProps.put("rebuttal", Map.of("type", "boolean"));
		List<String> lineReq;
		if (bilingual) {
			lineProps.put("noteRu", Map.of("type", "string"));
			lineProps.put("noteEn", Map.of("type", "string"));
			lineReq = List.of("line", "rhyme", "meaning", "rebuttal", "noteRu", "noteEn");
		} else {
			lineProps.put("note", Map.of("type", "string"));
			lineReq = List.of("line", "rhyme", "meaning", "rebuttal", "note");
		}
		Map<String, Object> lineSchema = Map.of("type", "object", "properties", lineProps, "required", lineReq, "additionalProperties", false);
		Map<String, Object> ps = Map.of("type", "object",
			"properties", Map.of("name", Map.of("type", "string"), "lines", Map.of("type", "array", "items", lineSchema)),
			"required", List.of("name", "lines"), "additionalProperties", false);
		Map<String, Object> rootProps = new LinkedHashMap<>();
		rootProps.put("players", Map.of("type", "array", "items", ps));
		List<String> rootReq;
		if (bilingual) {
			rootProps.put("commentRu", Map.of("type", "string"));
			rootProps.put("commentEn", Map.of("type", "string"));
			rootReq = List.of("players", "commentRu", "commentEn");
		} else {
			rootProps.put("comment", Map.of("type", "string"));
			rootReq = List.of("players", "comment");
		}
		Map<String, Object> schema = Map.of("type", "object", "properties", rootProps, "required", rootReq, "additionalProperties", false);

		JsonObject o = ask(system, u.toString(), schema, 4000, JUDGE_TEMPERATURE, primaryLang);
		if (o == null) return null;
		try {
			Map<String, JsonObject> byName = new LinkedHashMap<>();
			List<JsonObject> ordered = new ArrayList<>();
			for (var e : o.getAsJsonArray("players")) {
				JsonObject pj = e.getAsJsonObject();
				byName.put(pj.get("name").getAsString().trim(), pj);
				ordered.add(pj);
			}
			List<PlayerVerse> pair = List.of(first, second);
			List<List<BarPrompt>> pairBars = List.of(bars1, bars2);
			Map<String, List<LineScore>> mapRu = new LinkedHashMap<>();
			Map<String, List<LineScore>> mapEn = new LinkedHashMap<>();
			for (int i = 0; i < 2; i++) {
				PlayerVerse pv = pair.get(i);
				JsonObject pj = byName.containsKey(pv.name()) ? byName.get(pv.name()) : i < ordered.size() ? ordered.get(i) : null;
				if (bilingual) {
					mapRu.put(pv.name(), mergeBarScores(pairBars.get(i), pj, "noteRu", Lang.RU));
					mapEn.put(pv.name(), mergeBarScores(pairBars.get(i), pj, "noteEn", Lang.EN));
				} else {
					List<LineScore> ls = mergeBarScores(pairBars.get(i), pj, "note", primaryLang);
					mapRu.put(pv.name(), ls);
					mapEn.put(pv.name(), ls);
				}
			}
			String cRu = bilingual ? o.get("commentRu").getAsString().trim() : o.get("comment").getAsString().trim();
			String cEn = bilingual ? o.get("commentEn").getAsString().trim() : cRu;
			return new PvpVerdict(mapRu, mapEn, cRu, cEn);
		} catch (RuntimeException e) {
			RapBattle.LOG.error("Bad PvP judge JSON: {}", o, e);
			return null;
		}
	}

	private static void appendPvpBarsRu(StringBuilder u, PlayerVerse p, List<BarPrompt> bars, boolean first) {
		u.append(first ? "\nИгрок 1 (читал ПЕРВЫМ, задавал тему) — " : "\nИгрок 2 (читал ВТОРЫМ, мог отвечать Игроку 1) — ")
			.append(p.name()).append(":\n");
		for (BarPrompt b : bars) {
			u.append("  ").append(b.line()).append(". [цель-рифма: «").append(b.targetWord())
				.append("» | тайминг: ").append(b.timing()).append("] → ")
				.append(b.silent() ? "(промолчал такт)" : "«" + b.text() + "»").append('\n');
		}
	}

	private static void appendPvpBarsEn(StringBuilder u, PlayerVerse p, List<BarPrompt> bars, boolean first) {
		u.append(first ? "\nPlayer 1 (rapped FIRST, opened the battle) — " : "\nPlayer 2 (rapped SECOND, could rebut Player 1) — ")
			.append(p.name()).append(":\n");
		for (BarPrompt b : bars) {
			u.append("  ").append(b.line()).append(". [target rhyme: \"").append(b.targetWord())
				.append("\" | timing: ").append(b.timing()).append("] → ")
				.append(b.silent() ? "(silent bar)" : "\"" + b.text() + "\"").append('\n');
		}
	}

	/** Maps non-silent bar scores back onto the full bar list while locking silent bars at 0. */
	static List<LineScore> mergeBarScores(List<BarPrompt> bars, JsonObject playerJson, String noteField, String lang) {
		int total = bars.size();
		LineScore[] out = new LineScore[total];
		List<Integer> nonSilent = new ArrayList<>();
		for (int i = 0; i < total; i++) {
			if (bars.get(i).silent()) out[i] = LineScore.silent(lang);
			else nonSilent.add(i);
		}
		List<LineScore> extras = new ArrayList<>();
		if (playerJson != null && playerJson.has("lines")) {
			for (var le : playerJson.getAsJsonArray("lines")) {
				JsonObject l = le.getAsJsonObject();
				int lineNum = l.has("line") ? l.get("line").getAsInt() : -1;
				int rhyme = l.get("rhyme").getAsInt();
				int meaning = l.get("meaning").getAsInt();
				boolean reb = l.has("rebuttal") && l.get("rebuttal").getAsBoolean();
				String note = l.has(noteField) ? l.get(noteField).getAsString() : "";
				LineScore score = LineScore.fromRubric(rhyme, meaning, reb, note);
				int idx = lineNum - 1;
				if (idx >= 0 && idx < total && !bars.get(idx).silent() && out[idx] == null) {
					out[idx] = score;
				} else {
					extras.add(score);
				}
			}
		}
		int ex = 0;
		String missNote = Lang.isRu(lang) ? "мимо рифмы" : "missed the rhyme";
		for (int idx : nonSilent) {
			if (out[idx] == null) {
				out[idx] = ex < extras.size() ? extras.get(ex++) : new LineScore(0, 0, 0, false, false, missNote);
			}
		}
		return List.of(out);
	}

	@Deprecated
	public static Verdict judge(String mobName, String species, String rankLabel, String expectation, int lo, int hi,
								List<String> mobLines, List<PlayerVerse> players) {
		return judge(mobName, species, rankLabel, expectation, lo, hi, mobLines, players, Lang.RU);
	}
}
