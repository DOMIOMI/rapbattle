package dev.rapbattle.battle;

import dev.rapbattle.Lang;

/**
 * The six mob ranks, named after armour materials (leather → netherite) plus a street title.
 * Everything that makes a battle harder hangs off this table.
 */
public enum Rank {
	//        armor          street        armorEn      streetEn     color      bpm mobLn solo coop leadIn rhymes verseLo verseHi xp  lineDmg hearts
	LEATHER  ("КОЖАНЫЙ",     "Мелочь",     "LEATHER",   "Small Fry", 0xA0663A,  80, 4,    4,   4,   8,     0,     1,      3,     3,   2,      2),
	CHAIN    ("КОЛЬЧУЖНЫЙ",  "Шпана",      "CHAIN",     "Punk",      0x9AA3AD,  88, 4,    8,   4,   4,     1,     2,      4,     8,   2,      2),
	IRON     ("ЖЕЛЕЗНЫЙ",    "Уличный",    "IRON",      "Street",    0xD8D8D8,  94, 6,    8,   4,   4,     2,     3,      6,    15,   3,      3),
	GOLD     ("ЗОЛОТОЙ",     "Авторитет",  "GOLD",      "Big Shot",  0xF2C94C, 100, 8,   12,   4,   4,     3,     4,      7,    25,   4,      4),
	DIAMOND  ("АЛМАЗНЫЙ",    "Легенда",    "DIAMOND",   "Legend",    0x5EE0E6, 108, 8,   12,   4,   2,     3,     6,      8,    40,   4,      5),
	NETHERITE("НЕЗЕРИТОВЫЙ", "Босс",       "NETHERITE", "Boss",      0x8B5CF6, 116, 6,    8,   4,   2,     4,     8,     10,   100,   5,      5);

	public final String armor, street;
	private final String armorEn, streetEn;
	public final int color, bpm, mobLines, playerLinesSolo, playerLinesCoop, leadInBeats;
	/** 0 easy (bank only) … 4 boss (hard, multi-syllable, generated). */
	public final int rhymeDifficulty;
	/** The judge's 0..10 mark for the mob's verse is kept inside this range, so ranks really feel different. */
	public final int verseLo, verseHi;
	public final int xp;
	/** Respect a mob line takes from the team when its rhyme lands (punchlines double it). */
	public final int lineDamage;
	/** Hearts a player loses after losing to a mob of this rank (bosses kill instead). */
	public final int heartsOnLoss;

	Rank(String armor, String street, String armorEn, String streetEn, int color, int bpm, int mobLines, int solo, int coop, int leadIn, int rhymes,
		 int verseLo, int verseHi, int xp, int lineDamage, int heartsOnLoss) {
		this.armor = armor;
		this.street = street;
		this.armorEn = armorEn;
		this.streetEn = streetEn;
		this.color = color;
		this.bpm = bpm;
		this.mobLines = mobLines;
		this.playerLinesSolo = solo;
		this.playerLinesCoop = coop;
		this.leadInBeats = leadIn;
		this.rhymeDifficulty = rhymes;
		this.verseLo = verseLo;
		this.verseHi = verseHi;
		this.xp = xp;
		this.lineDamage = lineDamage;
		this.heartsOnLoss = heartsOnLoss;
	}

	/** 1..6 */
	public int level() { return ordinal() + 1; }

	public boolean isBoss() { return this == NETHERITE; }

	public String armor(String lang) { return Lang.isRu(lang) ? armor : armorEn; }

	public String street(String lang) { return Lang.isRu(lang) ? street : streetEn; }

	public String label(String lang) { return armor(lang) + " · " + street(lang); }

	@Deprecated
	public String label() { return label(Lang.RU); }

	/** How much of a player line's damage gets through: strong mobs shrug off weak bars. */
	public double defense() {
		return switch (this) { case LEATHER -> 1.3; case CHAIN -> 1.15; case IRON -> 1.0; case GOLD -> 0.85; case DIAMOND -> 0.7; case NETHERITE -> 0.55; };
	}

	/** A line the judge scored below this deals nothing at all. */
	public int minLineScore() {
		return switch (this) { case LEATHER, CHAIN -> 0; case IRON -> 2; case GOLD -> 3; case DIAMOND -> 4; case NETHERITE -> 5; };
	}

	/** How many beats ahead the rhyme word is shown: the stronger the mob, the less time to think (0 = all at once). */
	public int revealBeats() {
		return switch (this) { case LEATHER, CHAIN -> 0; case IRON -> 8; case GOLD, DIAMOND -> 4; case NETHERITE -> 2; };
	}

	public static Rank ofLevel(int level) {
		return values()[Math.max(1, Math.min(6, level)) - 1];
	}

	/** What the judge expects at this rank, for the prompt. */
	public String judgeExpectation(String lang) {
		if (Lang.isRu(lang)) {
			return switch (this) {
				case LEATHER -> "Совсем слабый моб. Будь мягким: достаточно попасть в рифму и сказать хоть что-то по делу.";
				case CHAIN -> "Слабый моб. Рифма должна быть, и строка должна что-то значить, а не быть набором слов.";
				case IRON -> "Средний моб. Строка без смысла или не по теме баттла получает не больше 3, даже если рифма есть. Повторы и слова-паразиты снижай.";
				case GOLD -> "Сильный моб. Будь строгим: строки должны быть связаны между собой, бить по сопернику и отвечать на его диссы. Шаблонные фразы и заполнители не выше 4.";
				case DIAMOND -> "Легенда. Будь очень строгим: 6+ только за строку с образом или игрой слов, которая метит в соперника. Банальщина, повторы и бессвязность не выше 3.";
				case NETHERITE -> "Босс. Будь беспощадным: 7+ только за настоящий панч или точный ответ на дисс, 5-6 за крепкую связную строку, всё остальное не выше 3. Любая запинка, повтор, клише или строка не по теме сразу тянет оценку вниз.";
			};
		}
		return switch (this) {
			case LEATHER -> "Very weak mob. Be lenient: landing the rhyme and saying something relevant is enough.";
			case CHAIN -> "Weak mob. The rhyme must land and the line must mean something, not be a pile of words.";
			case IRON -> "Mid-tier mob. A line with no sense or off the battle topic gets 3 at most, even if it rhymes. Mark down repetition and filler words.";
			case GOLD -> "Strong mob. Be strict: lines must connect, hit the opponent and answer their disses. Stock phrases and filler get 4 at most.";
			case DIAMOND -> "Legend. Be very strict: 6+ only for a line with an image or wordplay aimed at the opponent. Clichés, repeats and incoherence get 3 at most.";
			case NETHERITE -> "Boss. Be merciless: 7+ only for a real punchline or a sharp rebuttal, 5-6 for a solid coherent line, everything else 3 at most. Any stumble, repeat, cliché or off-topic line drags the score down.";
		};
	}

	@Deprecated
	public String judgeExpectation() { return judgeExpectation(Lang.RU); }
}
