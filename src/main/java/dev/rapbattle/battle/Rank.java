package dev.rapbattle.battle;

import dev.rapbattle.Lang;

/**
 * The six mob ranks, named after armour materials (leather → netherite) plus a street title.
 * Everything that makes a battle harder hangs off this table.
 */
public enum Rank {
	//        armor          street        armorEn      streetEn     color      bpm mobLn solo coop leadIn rhymes verseLo verseHi xp   hearts
	LEATHER  ("КОЖАНЫЙ",     "Мелочь",     "LEATHER",   "Small Fry", 0xA0663A,  80, 4,    6,   4,   8,     0,     1,      3,     10,   2),
	CHAIN    ("КОЛЬЧУЖНЫЙ",  "Шпана",      "CHAIN",     "Punk",      0x9AA3AD,  88, 4,    8,   4,   8,     1,     2,      4,     18,   2),
	IRON     ("ЖЕЛЕЗНЫЙ",    "Уличный",    "IRON",      "Street",    0xD8D8D8,  94, 6,    8,   4,   4,     2,     3,      6,     30,   3),
	GOLD     ("ЗОЛОТОЙ",     "Авторитет",  "GOLD",      "Big Shot",  0xF2C94C, 100, 8,   10,   6,   4,     3,     4,      7,     50,   4),
	DIAMOND  ("АЛМАЗНЫЙ",    "Легенда",    "DIAMOND",   "Legend",    0x5EE0E6, 108, 8,   12,   6,   4,     3,     6,      8,     85,   5),
	NETHERITE("НЕЗЕРИТОВЫЙ", "Босс",       "NETHERITE", "Boss",      0x8B5CF6, 116, 6,   12,   6,   4,     4,     8,     10,    250,   5);

	public final String armor, street;
	private final String armorEn, streetEn;
	public final int color, bpm, mobLines, playerLinesSolo, playerLinesCoop, leadInBeats;
	/** 0 easy (bank only) … 4 boss (hard, multi-syllable, generated). */
	public final int rhymeDifficulty;
	/** The judge's 0..10 mark for the mob's verse is kept inside this range, so ranks really feel different. */
	public final int verseLo, verseHi;
	public final int xp;
	/** Hearts a player loses after losing to a mob of this rank (bosses kill instead). */
	public final int heartsOnLoss;

	Rank(String armor, String street, String armorEn, String streetEn, int color, int bpm, int mobLines, int solo, int coop, int leadIn, int rhymes,
		 int verseLo, int verseHi, int xp, int heartsOnLoss) {
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

	/** Rank multiplier M_rank applied to normalized player line damage. */
	public double damageMultiplier() {
		return switch (this) {
			case LEATHER -> 1.35;
			case CHAIN -> 1.18;
			case IRON -> 1.05;
			case GOLD -> 1.00;
			case DIAMOND -> 0.88;
			case NETHERITE -> 0.80;
		};
	}

	/** Fixed live-damage budget dealt by the mob's verse across the round (punches have 2x weight; extra lines do not inflate it). */
	public int liveBudget() {
		return switch (this) {
			case LEATHER -> 12;
			case CHAIN -> 18;
			case IRON -> 26;
			case GOLD -> 38;
			case DIAMOND -> 44;
			case NETHERITE -> 48;
		};
	}

	/** Lines per player for a team of {@code teamSize} players. */
	/** Rebuttal targets ("ОТБЕЙ") for this rank: one on ranks 1-2, where the easy rhymes leave no room for two. */
	public int targetCap(int styleCount) {
		return level() <= 2 ? 1 : styleCount;
	}

	public int linesPerPlayer(int teamSize) {
		if (teamSize <= 1) return playerLinesSolo;
		if (teamSize == 2) return playerLinesCoop;
		return 4;
	}

	/** How many beats ahead the rhyme word is shown: the stronger the mob, the less time to think (0 = all at once). */
	public int revealBeats() {
		return switch (this) { case LEATHER, CHAIN -> 0; case IRON -> 12; case GOLD -> 8; case DIAMOND -> 6; case NETHERITE -> 4; };
	}

	public static Rank ofLevel(int level) {
		return values()[Math.max(1, Math.min(6, level)) - 1];
	}

	/** Context about this rank for the judge: one objective rubric across all ranks, difficulty comes from the mob itself. */
	public String judgeExpectation(String lang) {
		if (Lang.isRu(lang)) {
			String unified = "Шкала едина для всех рангов — не занижай и не завышай оценки из-за ранга соперника: "
				+ "rhyme (0 мимо рифмы, 1 неточная рифма или созвучие/опечатка распознавания, 2 точная рифма в конце строки), "
				+ "meaning (0 тишина или бессмысленный набор слов, 1 простая связная фраза, 2 адресный наезд по теме или сопернику, "
				+ "3 яркий образ, игра слов или жёсткий панч), rebuttal = true только за прямой ответ на тезис из куплета моба. ";
			return unified + switch (this) {
				case LEATHER -> "Соперник — мелкая сошка (обычно его куплет на 1–3 балла).";
				case CHAIN -> "Соперник — дворовая шпана (куплет моба обычно 2–4 балла).";
				case IRON -> "Соперник — крепкий уличный боец (куплет моба обычно 3–6 баллов).";
				case GOLD -> "Соперник — районный авторитет с плотными диссами (куплет моба обычно 4–7 баллов).";
				case DIAMOND -> "Соперник — легенда улиц с сильной читкой (куплет моба обычно 6–8 баллов).";
				case NETHERITE -> "Соперник — финальный босс высочайшего уровня (куплет босса обычно 8–10 баллов).";
			};
		}
		String unifiedEn = "Use ONE consistent scale across all ranks — do not inflate or deflate player marks based on rank: "
			+ "rhyme (0 missed rhyme, 1 slant rhyme or speech-to-text near-homophone, 2 exact target rhyme at line end), "
			+ "meaning (0 silence or incoherent word salad, 1 simple coherent phrase, 2 targeted diss at the opponent/topic, "
			+ "3 vivid imagery, wordplay, or hard punchline), rebuttal = true only when directly answering a point from the mob's verse. ";
		return unifiedEn + switch (this) {
			case LEATHER -> "Opponent is small fry (mob verse typically 1–3).";
			case CHAIN -> "Opponent is a street punk (mob verse typically 2–4).";
			case IRON -> "Opponent is a solid street rapper (mob verse typically 3–6).";
			case GOLD -> "Opponent is a neighbourhood big shot with sharp disses (mob verse typically 4–7).";
			case DIAMOND -> "Opponent is a street legend with heavy bars (mob verse typically 6–8).";
			case NETHERITE -> "Opponent is a top-tier boss (boss verse typically 8–10).";
		};
	}

	@Deprecated
	public String judgeExpectation() { return judgeExpectation(Lang.RU); }
}
