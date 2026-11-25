package dev.rapbattle.ai;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.rapbattle.Lang;
import dev.rapbattle.RapBattle;
import dev.rapbattle.RapConfig;

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

	public static boolean available() {
		return !RapConfig.get().openRouterKey().isEmpty();
	}

	/** Sends one request and returns the parsed JSON object, or null on any failure. */
	private static JsonObject ask(String system, String user, Map<String, Object> schema, long maxTokens, String lang) {
		if (!available()) return null;
		return OpenRouter.ask(system, user, schema, maxTokens, lang);
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

	/** The mob team's verse plus rhyme groups (4 words each) for the players. */
	public record Content(List<MobLine> lines, List<List<String>> rhymes) {}

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
			u.append("Угол атаки в этот раз: ").append(ANGLES.get(new java.util.Random().nextInt(ANGLES.size()))).append(".\n");
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
			u.append("Angle of attack this time: ").append(ANGLES_EN.get(new java.util.Random().nextInt(ANGLES_EN.size()))).append(".\n");
			if (rivalry != null && !rivalry.isBlank()) u.append(rivalry).append(' ');
			if (round > 1) u.append("This is round ").append(round).append(". In the previous round:\n").append(previous).append("\nRespond to it and do not repeat yourself.\n");
			u.append("Write exactly ").append(lineCount).append(" lines for the mobs and ").append(rhymeGroups)
				.append(" rhyme groups for the players. Player rhyme difficulty: ").append(diff).append('.');
		}
		Map<String, Object> line = Map.of("type", "object",
			"properties", Map.of("speaker", Map.of("type", "string"), "text", Map.of("type", "string"), "punch", Map.of("type", "boolean")),
			"required", List.of("speaker", "text", "punch"), "additionalProperties", false);
		// Property order matters: the model writes the theme first and then sticks to it.
		Map<String, Object> props = new java.util.LinkedHashMap<>();
		props.put("theme", Map.of("type", "string"));
		props.put("lines", Map.of("type", "array", "items", line));
		props.put("rhymes", Map.of("type", "array", "items", Map.of("type", "array", "items", Map.of("type", "string"))));
		Map<String, Object> schema = Map.of(
			"type", "object",
			"properties", props,
			"required", List.of("theme", "lines", "rhymes"),
			"additionalProperties", false);
		JsonObject o = ask(system, u.toString(), schema, 3000, lang);
		if (o == null || !o.has("lines")) return null;
		try {
			List<MobLine> lines = new java.util.ArrayList<>();
			for (var e : o.getAsJsonArray("lines")) {
				JsonObject l = e.getAsJsonObject();
				String text = l.get("text").getAsString().trim();
				if (text.isEmpty()) continue;
				lines.add(new MobLine(l.get("speaker").getAsString().trim(), text, l.has("punch") && l.get("punch").getAsBoolean()));
			}
			List<List<String>> rhymes = new java.util.ArrayList<>();
			if (o.has("rhymes")) {
				for (var g : o.getAsJsonArray("rhymes")) {
					List<String> words = new java.util.ArrayList<>();
					for (var w : g.getAsJsonArray()) {
						String word = w.getAsString().trim().toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}-]", "");
						if (!word.isEmpty() && !words.contains(word)) words.add(word);
					}
					if (words.size() >= 4) rhymes.add(words.subList(0, 4));
				}
			}
			if (lines.size() < lineCount) return null;
			return new Content(lines.subList(0, lineCount), rhymes);
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
		JsonObject o = ask(system, user, schema, 1500, lang);
		if (o == null || !o.has("lines")) return null;
		List<String> out = new java.util.ArrayList<>();
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

	// ---------------------------------------------------------------- judge

	public record PlayerVerse(String name, List<String> rhymeWords, String transcript) {}

	/** One line of a player as the judge saw it: 0..10, a punchline or not, a short note for the screen. */
	public record LineScore(int score, boolean punch, String note) {
		/** Respect this line takes from the mob: the mark, plus half of it on top for a punchline. */
		public int damage() { return score + (punch ? (score + 1) / 2 : 0); }
	}

	/** The judge's verdict for one round: the mob's verse 0..10, every player's lines, and the host's comment. */
	public record Verdict(int mobVerse, Map<String, List<LineScore>> playerLines, String comment) {
		public int damageBy(String name) {
			return playerLines.getOrDefault(name, List.of()).stream().mapToInt(LineScore::damage).sum();
		}
	}

	public static Verdict judge(String mobName, String species, String rankLabel, String expectation, int lo, int hi,
								List<String> mobLines, List<PlayerVerse> players, String lang) {
		String system;
		StringBuilder u = new StringBuilder();
		if (Lang.isRu(lang)) {
			system = """
				Ты судья рэп-баттла в Minecraft. Против моба (или команды мобов) читают один или несколько игроков (одна команда).
				Игроки читали голосом под бит, их речь распознана автоматически одним текстом, поэтому возможны ошибки
				распознавания: не придирайся к ним. Каждой строке игрока была назначена рифма (слово, которым строка должна кончаться).
				Оцени КАЖДУЮ строку игрока по порядку заданных рифм: найди в тексте, где он читал строку на эту рифму.
				score от 0 до 10: 0 строки нет или мимо рифмы и смысла, 3 рифма есть, но смысла нет, 6 рифма в точку и связно,
				8 сильная строка, 10 разнос. punch: true, если строка — настоящий панч или ответ на дисс моба.
				note: пометка до 30 символов для экрана, по-русски, дерзко: «рифма в точку», «мимо рифмы», «панч про шерсть», «тишина».
				mobVerse от 0 до 10: насколько силён куплет мобов.
				comment: одна-две фразы на русском (до 200 символов) в стиле дерзкого ведущего баттла: процитируй или упомяни
				конкретную строчку игрока и вынеси вердикт. Не упоминай баллы и цифры. Каждый раз по-новому:
				не начинай с одних и тех же слов, меняй образы, сравнения и подачу.
				""" + tone(lang);
			u.append("Соперник: ").append(mobName).append(" (").append(species).append("), ранг ").append(rankLabel).append(".\n");
			u.append("Ожидания: ").append(expectation).append(" Обычно куплет мобов этого ранга стоит от ").append(lo).append(" до ").append(hi).append(" баллов.\n");
			u.append("Куплет мобов:\n");
			mobLines.forEach(l -> u.append("  ").append(l).append('\n'));
			for (PlayerVerse p : players) {
				u.append("\nИгрок ").append(p.name()).append(". Заданные рифмы по порядку строк: ").append(String.join(", ", p.rhymeWords()))
					.append(" (строк: ").append(p.rhymeWords().size()).append(")\n");
				u.append("Что он прочитал: ").append(p.transcript().isBlank() ? "(молчал)" : p.transcript()).append('\n');
			}
		} else {
			system = """
				You are the judge of a Minecraft rap battle. One or more players (one team) rap against a mob (or mob team).
				The players rapped by voice over a beat and their speech was auto-transcribed as a single block of text, so
				transcription errors happen: do not nitpick them. Each player line was assigned a target rhyme word (the word the line should end with).
				Score EVERY player line in the order of the assigned rhymes: find where in the transcript they rapped that rhyme.
				score 0 to 10: 0 missing line or off-rhyme and no sense, 3 rhyme is there but no sense, 6 on the rhyme and coherent,
				8 strong bar, 10 total destruction. punch: true if the line is a real punchline or a direct clapback to the mob.
				note: short label up to 30 characters for the screen, in English, punchy: "on the rhyme", "missed the rhyme", "wool punchline", "silence".
				mobVerse 0 to 10: how strong the mob team's verse was.
				comment: one or two sentences in English (up to 200 characters) in the voice of a cocky battle host: quote or reference
				a specific line from the player and deliver the verdict. Do not mention scores or numbers. Keep it fresh every time:
				do not start with the same phrasing, vary your imagery, comparisons, and delivery.
				""" + tone(lang);
			u.append("Opponent: ").append(mobName).append(" (").append(species).append("), rank ").append(rankLabel).append(".\n");
			u.append("Expectations: ").append(expectation).append(" Usually a mob verse of this rank scores between ").append(lo).append(" and ").append(hi).append(".\n");
			u.append("Mob verse:\n");
			mobLines.forEach(l -> u.append("  ").append(l).append('\n'));
			for (PlayerVerse p : players) {
				u.append("\nPlayer ").append(p.name()).append(". Assigned rhymes in line order: ").append(String.join(", ", p.rhymeWords()))
					.append(" (lines: ").append(p.rhymeWords().size()).append(")\n");
				u.append("What they rapped: ").append(p.transcript().isBlank() ? "(silent)" : p.transcript()).append('\n');
			}
		}
		Map<String, Object> line = new java.util.LinkedHashMap<>();
		line.put("type", "object");
		line.put("properties", Map.of("score", Map.of("type", "integer"), "punch", Map.of("type", "boolean"), "note", Map.of("type", "string")));
		line.put("required", List.of("score", "punch", "note"));
		line.put("additionalProperties", false);
		Map<String, Object> ps = Map.of("type", "object",
			"properties", Map.of("name", Map.of("type", "string"), "lines", Map.of("type", "array", "items", line)),
			"required", List.of("name", "lines"), "additionalProperties", false);
		Map<String, Object> schema = Map.of(
			"type", "object",
			"properties", Map.of(
				"mobVerse", Map.of("type", "integer"),
				"players", Map.of("type", "array", "items", ps),
				"comment", Map.of("type", "string")),
			"required", List.of("mobVerse", "players", "comment"),
			"additionalProperties", false);
		JsonObject o = ask(system, u.toString(), schema, 4000, lang);
		if (o == null) return null;
		try {
			Map<String, List<LineScore>> byName = new java.util.LinkedHashMap<>();
			List<List<LineScore>> ordered = new java.util.ArrayList<>();
			for (var e : o.getAsJsonArray("players")) {
				JsonObject p = e.getAsJsonObject();
				List<LineScore> ls = new java.util.ArrayList<>();
				for (var le : p.getAsJsonArray("lines")) {
					JsonObject l = le.getAsJsonObject();
					String note = l.get("note").getAsString().trim();
					if (note.length() > 34) note = note.substring(0, 33) + "…";
					ls.add(new LineScore(Math.max(0, Math.min(10, l.get("score").getAsInt())), l.get("punch").getAsBoolean(), note));
				}
				byName.put(p.get("name").getAsString().trim(), ls);
				ordered.add(ls);
			}
			// The model may mangle a nickname or miscount lines; fall back to order and pad / cut to the real line count.
			Map<String, List<LineScore>> fixed = new java.util.LinkedHashMap<>();
			for (int i = 0; i < players.size(); i++) {
				PlayerVerse pv = players.get(i);
				List<LineScore> ls = byName.containsKey(pv.name()) ? byName.get(pv.name()) : i < ordered.size() ? ordered.get(i) : List.of();
				fixed.put(pv.name(), fit(ls, pv, lang));
			}
			int mob = Math.max(0, Math.min(10, o.get("mobVerse").getAsInt()));
			return new Verdict(mob, fixed, o.get("comment").getAsString().trim());
		} catch (RuntimeException e) {
			RapBattle.LOG.error("Bad judge JSON: {}", o, e);
			return null;
		}
	}

	@Deprecated
	public static Verdict judge(String mobName, String species, String rankLabel, String expectation, int lo, int hi,
								List<String> mobLines, List<PlayerVerse> players) {
		return judge(mobName, species, rankLabel, expectation, lo, hi, mobLines, players, Lang.RU);
	}

	/** Exactly one score per assigned rhyme. */
	static List<LineScore> fit(List<LineScore> ls, PlayerVerse pv, String lang) {
		List<LineScore> out = new java.util.ArrayList<>(ls.subList(0, Math.min(ls.size(), pv.rhymeWords().size())));
		boolean silent = pv.transcript().isBlank();
		String note = Lang.isRu(lang) ? (silent ? "тишина" : "мимо рифмы") : (silent ? "silence" : "missed the rhyme");
		while (out.size() < pv.rhymeWords().size()) out.add(new LineScore(0, false, note));
		return out;
	}

	static List<LineScore> fit(List<LineScore> ls, PlayerVerse pv) {
		return fit(ls, pv, Lang.RU);
	}

}
