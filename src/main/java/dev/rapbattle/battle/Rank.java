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
	NETHERITE("НЕЗЕРИТОВЫЙ", "Босс",       "NETHERITE", "Boss",      0x8B5CF6, 116, 6,    8,   4,   2,     4,     7,     10,   100,   5,      5);

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

	public static Rank ofLevel(int level) {
		return values()[Math.max(1, Math.min(6, level)) - 1];
	}

	/** What the judge expects at this rank, for the prompt. */
	public String judgeExpectation(String lang) {
		if (Lang.isRu(lang)) {
			return switch (this) {
				case LEATHER -> "Это совсем слабый моб. Игроку достаточно попасть хотя бы в половину рифм и не молчать.";
				case CHAIN -> "Слабый моб. Нужны попадания в большинство рифм и хоть какой-то смысл.";
				case IRON -> "Средний моб. Нужны попадания почти во все рифмы, связный смысл и пара панчей.";
				case GOLD -> "Сильный моб. Нужны все рифмы, панчи, ответ на диссы моба и держать ритм.";
				case DIAMOND -> "Легенда. Нужно всё: рифмы, смысл, панчи, ответ на диссы, харизма. Будь строгим.";
				case NETHERITE -> "Босс. Будь очень строгим: прощай только идеальный куплет с жёсткими панчами.";
			};
		}
		return switch (this) {
			case LEATHER -> "Very weak mob. Hitting at least half the rhymes and not going silent is enough for the player.";
			case CHAIN -> "Weak mob. Needs most rhymes to land and at least some sense.";
			case IRON -> "Mid-tier mob. Needs almost all rhymes to land, coherent bars, and a couple of punchlines.";
			case GOLD -> "Strong mob. Needs every rhyme, punchlines, rebuttals to the mob's disses, and solid flow.";
			case DIAMOND -> "Legend. Needs it all: rhymes, sense, punchlines, rebuttals, charisma. Be strict.";
			case NETHERITE -> "Boss. Be very strict: only forgive a flawless verse packed with hard punchlines.";
		};
	}

	@Deprecated
	public String judgeExpectation() { return judgeExpectation(Lang.RU); }
}
