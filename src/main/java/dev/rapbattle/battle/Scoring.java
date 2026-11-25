package dev.rapbattle.battle;

import dev.rapbattle.Lang;
import dev.rapbattle.ai.Fallback;
import dev.rapbattle.ai.Transcriber;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Pure, Minecraft-free battle scoring and timing math (Hybrid Model A + B).
 * Covered by JUnit tests in {@code ScoringTest}.
 */
public final class Scoring {
	private Scoring() {}

	public static final int RESPECT_MAX = 100;
	public static final int SILENCE_DAMAGE_PER_BAR = 5;
	public static final int LETHAL_MARGIN = 25;

	/** Converts the judge's discrete rubric (rhyme 0..2, meaning 0..3) into a 0..10 line score. */
	public static int scoreFromRubric(int rhyme, int meaning) {
		int r = Math.max(0, Math.min(2, rhyme));
		int m = Math.max(0, Math.min(3, meaning));
		return m == 0 ? 0 : r * 2 + m * 2;
	}

	public static boolean punchFromRubric(int meaning) {
		return meaning >= 3;
	}

	/**
	 * Hybrid beat timing for one line:
	 * 2 = on beat, 1 = near beat, 0 = off beat / missed.
	 * Uses Whisper word timing first; if Whisper did not find the word, falls back to client voice peak near beat 4
	 * when the judge gave {@code rhyme >= 1} (or when STT is not configured).
	 */
	public static int hybridBeat(Double whisperDeltaBeats, int judgeRhyme, boolean sttMissing,
								 int clientPeakDeltaMs, long beatMs) {
		return hybridBeat(whisperDeltaBeats, judgeRhyme, sttMissing, clientPeakDeltaMs, beatMs, 0.30, 0.35);
	}

	public static int hybridBeat(Double whisperDeltaBeats, int judgeRhyme, boolean sttMissing,
								 int clientPeakDeltaMs, long beatMs, double whisperOnWindow, double micOnWindow) {
		if (whisperDeltaBeats != null && whisperDeltaBeats < Double.MAX_VALUE) {
			return whisperDeltaBeats <= whisperOnWindow ? 2 : whisperDeltaBeats <= 0.70 ? 1 : 0;
		}
		if ((judgeRhyme >= 1 || sttMissing) && clientPeakDeltaMs >= 0 && beatMs > 0) {
			double d = clientPeakDeltaMs / (double) beatMs;
			return d <= micOnWindow ? 2 : d <= 0.70 ? 1 : 0;
		}
		return 0;
	}

	/** Flow multiplier F for a line given its beat state (0/1/2) and the resulting consecutive on-beat streak. */
	public static double flowMultiplier(int beatState, int streakAfter) {
		if (beatState == 2) {
			return streakAfter >= 6 ? 1.50 : streakAfter >= 3 ? 1.35 : 1.25;
		}
		return beatState == 1 ? 1.00 : 0.75;
	}

	/** Input for evaluating one player line in timeline order. */
	public record LineInput(String player, String targetWord, int score, boolean punch, boolean rebuttal,
							String note, int beatState, boolean silent, int punchBonus, double extraFlowScale) {
		public LineInput(String player, String targetWord, int score, boolean punch, boolean rebuttal,
						 String note, int beatState, boolean silent) {
			this(player, targetWord, score, punch, rebuttal, note, beatState, silent, 2, 1.0);
		}
	}

	/** Computed breakdown for a single player line. */
	public record EvaluatedLine(LineInput input, int damage, int textBaseDamage, int flowDeltaDamage,
								int rebuttalDamage, double flowMultiplier, int streak) {}

	/** Team-wide bonuses (capped at +10 total). */
	public record Bonuses(int hypeBonus, int styleBonus, int allyBonus, int total) {}

	/** Full round calculation result for both sides. */
	public record RoundOutcome(
		List<EvaluatedLine> lines,
		Bonuses bonuses,
		int barTextTotal,
		int barBeatDelta,
		int barRebuttalDamage,
		int mobLiveDamage,
		int mobVerseRawDamage,
		int mobVerseParried,
		int mobVerseFinalDamage,
		int silenceDamage,
		int extraMobDamage,
		int totalToMob,
		int totalToTeam,
		int respectMobAfter,
		int respectTeamAfter,
		int silentBars,
		int rebuttalCount,
		int strongOnBeatCount,
		int onBeatCount,
		int nearBeatCount,
		int bestCombo,
		boolean knockout,
		boolean playersWinRound
	) {
		public int damageByPlayer(String name) {
			int s = 0;
			for (EvaluatedLine l : lines) if (name.equals(l.input().player())) s += l.damage();
			return s;
		}
	}

	public static int hypeBonus(int hype) {
		return Math.max(0, Math.min(6, (int) Math.round(hype / 16.0)));
	}

	public static Bonuses computeBonuses(int hype, boolean hasStyle, int allyCount) {
		int hb = hypeBonus(hype);
		int sb = hasStyle ? 2 : 0;
		int ab = Math.max(0, Math.min(2, allyCount));
		return new Bonuses(hb, sb, ab, Math.min(10, hb + sb + ab));
	}

	/**
	 * Distributes the rank's fixed live damage budget across the mob's lines so punchlines deal 2x normal lines
	 * and the sum equals {@code budget} regardless of extra lines.
	 */
	public static int[] mobLiveDamages(int budget, List<Boolean> punches) {
		int n = punches.size();
		int[] out = new int[n];
		if (n == 0 || budget <= 0) return out;
		int totalWeight = 0;
		for (boolean p : punches) totalWeight += p ? 2 : 1;
		int accWeight = 0, prev = 0;
		for (int i = 0; i < n; i++) {
			accWeight += Boolean.TRUE.equals(punches.get(i)) ? 2 : 1;
			int cur = (int) Math.round((double) budget * accWeight / totalWeight);
			out[i] = cur - prev;
			prev = cur;
		}
		return out;
	}

	/** Evaluates an entire round for a given rank (or {@code rank == null} for 1v1 PvP where M_rank = 1.0). */
	public static RoundOutcome evaluateRound(Rank rank, List<LineInput> teamLines, Bonuses bonuses,
											 int rawMobVerse, int extraMobDamage) {
		int n = Math.max(1, teamLines.size());
		double k = 8.0 / n;
		double mRank = rank == null ? 1.0 : rank.damageMultiplier();

		List<EvaluatedLine> evaluated = new ArrayList<>(teamLines.size());
		int streak = 0, bestCombo = 0;
		int onBeat = 0, nearBeat = 0, silentBars = 0, rebuttals = 0, strongOnBeat = 0;
		int textTotal = 0, beatDeltaTotal = 0, rebuttalDmgTotal = 0, linesDamageTotal = 0;

		for (LineInput in : teamLines) {
			if (in.silent()) silentBars++;
			if (in.silent() || in.score() <= 0) {
				streak = 0;
				evaluated.add(new EvaluatedLine(in, 0, 0, 0, 0, 0.0, 0));
				continue;
			}
			if (in.beatState() == 2) {
				streak++;
				bestCombo = Math.max(bestCombo, streak);
				onBeat++;
			} else {
				streak = 0;
				if (in.beatState() == 1) nearBeat++;
			}
			double f = flowMultiplier(in.beatState(), streak) * (in.beatState() == 2 ? in.extraFlowScale() : 1.0);
			int baseRaw = in.score() + (in.punch() ? in.punchBonus() : 0);
			int fullRaw = baseRaw + (in.rebuttal() ? 2 : 0);

			int textBase = (int) Math.round(k * baseRaw * mRank);
			int withFlow = (int) Math.round(k * baseRaw * f * mRank);
			int dmg = (int) Math.round(k * fullRaw * f * mRank);
			int flowDelta = withFlow - textBase;
			int rebDmg = dmg - withFlow;

			if (in.rebuttal()) rebuttals++;
			if (in.score() >= 8 && in.beatState() == 2) strongOnBeat++;

			textTotal += textBase;
			beatDeltaTotal += flowDelta;
			rebuttalDmgTotal += rebDmg;
			linesDamageTotal += dmg;
			evaluated.add(new EvaluatedLine(in, dmg, textBase, flowDelta, rebDmg, f, streak));
		}

		int clampedMobVerse = rank == null ? 0 : Math.max(rank.verseLo, Math.min(rank.verseHi, rawMobVerse));
		int mobLive = rank == null ? 0 : rank.liveBudget();
		int mobVerseRaw = 3 * clampedMobVerse;
		int mobVerseFinal = Math.max(0, mobVerseRaw - 5 * rebuttals - 3 * strongOnBeat);
		int mobParried = mobVerseRaw - mobVerseFinal;
		int silenceDmg = silentBars * SILENCE_DAMAGE_PER_BAR;

		int totalToMob = linesDamageTotal + (bonuses == null ? 0 : bonuses.total());
		int totalToTeam = mobLive + mobVerseFinal + silenceDmg + Math.max(0, extraMobDamage);

		int respectMobAfter = Math.max(0, RESPECT_MAX - totalToMob);
		int respectTeamAfter = Math.max(0, RESPECT_MAX - totalToTeam);
		boolean ko = respectMobAfter <= 0 || respectTeamAfter <= 0;
		boolean playersWinRound = totalToMob > totalToTeam || (respectMobAfter <= 0 && respectTeamAfter > 0);

		return new RoundOutcome(
			evaluated,
			bonuses == null ? new Bonuses(0, 0, 0, 0) : bonuses,
			textTotal,
			beatDeltaTotal,
			rebuttalDmgTotal,
			mobLive,
			mobVerseRaw,
			mobParried,
			mobVerseFinal,
			silenceDmg,
			Math.max(0, extraMobDamage),
			totalToMob,
			totalToTeam,
			respectMobAfter,
			respectTeamAfter,
			silentBars,
			rebuttals,
			strongOnBeat,
			onBeat,
			nearBeat,
			bestCombo,
			ko,
			playersWinRound
		);
	}

	/** Knockout, boss defeat, or margin >= 25% of 100 finishes the mob. */
	public static boolean isLethalWin(boolean playersWin, boolean knockout, int respectTeam, int respectMob, boolean boss) {
		return playersWin && (knockout || boss || (respectTeam - respectMob) >= LETHAL_MARGIN);
	}

	// ------------------------------------------------------------------ bar slicing & word matching

	public record BarSlot(int lineIndex, int startBeat, String targetWord) {}

	public record SlicedBar(int lineIndex, String targetWord, String text, boolean silent, Double whisperDeltaBeats) {}

	/**
	 * Slices timed speech-to-text words into each bar of a player's verse by {@code startBeat}, and measures
	 * how close the target rhyme word landed to the 4th beat of its bar.
	 */
	public static List<SlicedBar> sliceBars(Transcriber.Result stt, Integer recordingStartMs, String fallbackTranscript,
											List<BarSlot> slots, long beatMs, String lang) {
		List<SlicedBar> out = new ArrayList<>(slots.size());
		if (slots.isEmpty()) return out;
		boolean ru = Lang.isRu(lang);

		if (stt != null && recordingStartMs != null && !stt.words().isEmpty() && beatMs > 0) {
			List<List<Transcriber.Word>> buckets = new ArrayList<>(slots.size());
			for (int i = 0; i < slots.size(); i++) buckets.add(new ArrayList<>());

			for (Transcriber.Word w : stt.words()) {
				double beat = (recordingStartMs + w.start() * 1000.0) / beatMs;
				int bestIdx = -1;
				for (int i = 0; i < slots.size(); i++) {
					BarSlot s = slots.get(i);
					if (beat >= s.startBeat() - 0.45 && beat <= s.startBeat() + 4.35) {
						if (bestIdx == -1) {
							bestIdx = i;
						} else {
							// Boundary overlap between consecutive bars: keep late rhyme on previous bar;
							// otherwise if word is not earlier than current bar's -0.15 beat, assign to current bar.
							BarSlot prev = slots.get(bestIdx);
							boolean isPrevRhyme = matchesWord(w.text(), prev.targetWord(), ru) && beat <= prev.startBeat() + 4.35;
							if (!isPrevRhyme && beat >= s.startBeat() - 0.15) {
								bestIdx = i;
							}
						}
					}
				}
				if (bestIdx == -1) {
					// Nearest bar within 1.0 beat of its edges, or early pickup during get-ready (up to 4.5 beats before bar 1).
					double firstStart = slots.get(0).startBeat();
					double lastEnd = slots.get(slots.size() - 1).startBeat() + 4.0;
					if (beat < firstStart && beat >= firstStart - 4.5) {
						bestIdx = 0;
					} else if (beat > lastEnd && beat <= lastEnd + 2.0) {
						bestIdx = slots.size() - 1;
					} else {
						double bestDist = 1.0;
						for (int i = 0; i < slots.size(); i++) {
							BarSlot s = slots.get(i);
							double d = beat < s.startBeat() ? s.startBeat() - beat : beat - (s.startBeat() + 4.0);
							if (d <= bestDist) {
								bestDist = d;
								bestIdx = i;
							}
						}
					}
				}
				if (bestIdx >= 0) buckets.get(bestIdx).add(w);
			}

			for (int i = 0; i < slots.size(); i++) {
				BarSlot s = slots.get(i);
				List<Transcriber.Word> ws = buckets.get(i);
				StringBuilder sb = new StringBuilder();
				for (Transcriber.Word w : ws) {
					if (!w.text().isBlank()) {
						if (sb.length() > 0) sb.append(' ');
						sb.append(w.text().trim());
					}
				}
				String barText = sb.toString().trim();
				double expectedBeat = s.startBeat() + 3.0;
				double bestDelta = Double.MAX_VALUE;
				if (s.targetWord() == null || s.targetWord().isBlank()) {
					// Freestyle bar: measure timing of the last spoken word in this bar against beat 4.
					if (!ws.isEmpty()) {
						Transcriber.Word last = ws.get(ws.size() - 1);
						double b = (recordingStartMs + last.start() * 1000.0) / beatMs;
						bestDelta = Math.abs(b - expectedBeat);
					}
				} else {
					// Check words in this bar (and within +-1.5 beats of its 4th beat) for the target rhyme word.
					for (Transcriber.Word w : stt.words()) {
						double b = (recordingStartMs + w.start() * 1000.0) / beatMs;
						if (Math.abs(b - expectedBeat) > 1.5) continue;
						if (matchesWord(w.text(), s.targetWord(), ru)) {
							bestDelta = Math.min(bestDelta, Math.abs(b - expectedBeat));
						}
					}
				}
				out.add(new SlicedBar(s.lineIndex(), s.targetWord(), barText, barText.isBlank(),
					bestDelta < Double.MAX_VALUE ? bestDelta : null));
			}
			boolean allSilent = out.stream().allMatch(SlicedBar::silent);
			String nonEmpty = fallbackTranscript != null && !fallbackTranscript.isBlank()
				? fallbackTranscript.trim()
				: (stt.text() != null ? stt.text().trim() : "");
			if (!allSilent || nonEmpty.isEmpty()) {
				return out;
			}
			// Safety net: word timestamps missed all bar windows while a non-empty transcript exists —
			// fall through to distribute words evenly across bars so spoken verses are never marked silent.
			out.clear();
		}

		// Plain transcript without word timestamps (or empty transcript).
		String plain = fallbackTranscript == null ? "" : fallbackTranscript.trim();
		if (plain.isEmpty()) {
			for (BarSlot s : slots) out.add(new SlicedBar(s.lineIndex(), s.targetWord(), "", true, null));
			return out;
		}
		String[] tokens = plain.split("\\s+");
		int cursor = 0;
		for (int i = 0; i < slots.size(); i++) {
			BarSlot s = slots.get(i);
			if (cursor >= tokens.length) {
				out.add(new SlicedBar(s.lineIndex(), s.targetWord(), "", true, null));
				continue;
			}
			int found = -1;
			int searchLimit = Math.min(tokens.length, cursor + 14);
			for (int j = cursor; j < searchLimit; j++) {
				if (matchesWord(tokens[j], s.targetWord(), ru)) {
					found = j;
					break;
				}
			}
			int end;
			if (found >= 0) {
				end = found + 1;
			} else {
				int remainingBars = slots.size() - i;
				int share = Math.max(1, (tokens.length - cursor + remainingBars - 1) / remainingBars);
				end = Math.min(tokens.length, cursor + share);
			}
			String chunk = String.join(" ", java.util.Arrays.copyOfRange(tokens, cursor, end)).trim();
			cursor = end;
			out.add(new SlicedBar(s.lineIndex(), s.targetWord(), chunk, chunk.isBlank(), null));
		}
		return out;
	}

	public static boolean matchesWord(String spoken, String rawTarget, boolean ru) {
		if (spoken == null || rawTarget == null) return false;
		String targetLast = Fallback.lastWord(rawTarget);
		if (targetLast.isEmpty()) return false;
		if (ru) {
			String ts = stemRu(targetLast);
			String ws = stemRu(spoken);
			if (ts.isEmpty() || ws.length() < 3) return false;
			return ws.startsWith(ts) || ts.startsWith(ws);
		}
		String target = targetLast.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}]", "");
		String w = spoken.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}]", "");
		if (target.isEmpty() || w.length() < 2) return false;
		for (String cand : Fallback.homophonesEn(target)) {
			if (w.equals(cand)) return true;
			if (w.equals(cand + "s") || w.equals(cand + "es") || w.equals(cand + "ed") || w.equals(cand + "d")) return true;
			if (cand.equals(w + "s") || cand.equals(w + "es") || cand.equals(w + "ed") || cand.equals(w + "d")) return true;
		}
		return false;
	}

	public static String stemRu(String word) {
		String w = word.toLowerCase(Locale.ROOT).replace('ё', 'е').replaceAll("[^\\p{L}]", "");
		return w.length() > 4 ? w.substring(0, w.length() - 2) : w;
	}
}
