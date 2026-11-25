package dev.rapbattle.battle;

import dev.rapbattle.Lang;

/**
 * Player MC style (horizontal progression unlocked by cumulative Respect).
 * Each style trades off one aspect of scoring/board setup for another without flat power creep.
 */
public enum McStyle {
	PUNCHER("puncher", 0),
	METRONOME("metronome", 500),
	PROVOKER("provoker", 1500);

	public final String id;
	public final int unlockRespect;

	McStyle(String id, int unlockRespect) {
		this.id = id;
		this.unlockRespect = unlockRespect;
	}

	public static McStyle of(String id) {
		if (id != null) {
			for (McStyle s : values()) {
				if (s.id.equalsIgnoreCase(id)) return s;
			}
		}
		return PUNCHER;
	}

	public boolean isUnlocked(int respect) {
		return respect >= unlockRespect;
	}

	public String label(String lang) {
		return Lang.t(lang, "rapbattle.style." + id + ".name");
	}

	public String plus(String lang) {
		return Lang.t(lang, "rapbattle.style." + id + ".plus");
	}

	public String minus(String lang) {
		return Lang.t(lang, "rapbattle.style." + id + ".minus");
	}

	/** Number of mob anchor target words shown to the player. */
	public int targetCount() {
		return this == PROVOKER ? 3 : 2;
	}

	public int punchBonus(boolean freestyle, boolean rebuttal) {
		int base = switch (this) {
			case PUNCHER -> 3;
			case METRONOME -> 2;
			case PROVOKER -> rebuttal ? 2 : 1;
		};
		return base + (freestyle ? 1 : 0);
	}

	public int flowStreakStart() {
		return this == METRONOME ? 2 : 3;
	}

	public double baseOnBeatFlow() {
		return this == METRONOME ? 1.30 : 1.25;
	}

	public int lowMeaningPenalty() {
		return this == METRONOME ? 1 : 0;
	}

	public int rebuttalHeal() {
		return this == PROVOKER ? 4 : 0;
	}

	public Scoring.LineInput lineInput(String player, String targetWord, int score, int meaning,
									   boolean punch, boolean rebuttal, String note, int beatState,
									   boolean silent, boolean freestyle, double extraFlowScale, boolean silenceForgiven) {
		return new Scoring.LineInput(
			player, targetWord, score, meaning, punch, rebuttal, note, beatState, silent,
			punchBonus(freestyle, rebuttal), extraFlowScale,
			flowStreakStart(), baseOnBeatFlow(), lowMeaningPenalty(), rebuttalHeal(), silenceForgiven
		);
	}
}
