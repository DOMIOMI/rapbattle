package dev.rapbattle.ai;

import dev.rapbattle.Lang;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class LlmTargetTest {
	@Test
	void cleanTargetSkipsRussianPronounsAndPicksNoun() {
		assertEquals("сундук", Llm.cleanTarget("твой сундук"));
		assertEquals("", Llm.cleanTarget("твой"));
	}

	@Test
	void cleanTargetFallsBackToThreeLetterSignificantWordWhenNoLongerWordExists() {
		assertEquals("дом", Llm.cleanTarget("это мой дом"));
	}

	@Test
	void cleanTargetSkipsEnglishStopWordsAndPicksLongest() {
		assertEquals("pickaxe", Llm.cleanTarget("your rusty pickaxe"));
	}

	@Test
	void cleanTargetAndExtractTargetsReturnEmptyForStopWordsOnly() {
		assertEquals("", Llm.cleanTarget("я ты он она в на и но быть"));
		assertEquals("", Llm.cleanTarget("I you my your this that and the"));
		assertTrue(Fallback.extractTargets(List.of(new Llm.MobLine("Mob", "это мой твой и на", false)), Lang.RU, 2).isEmpty());
	}

	@Test
	void rebuttalMatchesInflectedTargetWordInOfflineJudge() {
		List<Llm.PlayerVerse> verses = List.of(new Llm.PlayerVerse(
			"DOMI",
			List.of("сундук"),
			"я заберу всё в твоём сундуке"
		));
		Llm.Verdict v = Fallback.judge(6, 12, verses, Map.of("DOMI", List.of(true)), false, List.of("сундук"), new Random(0), Lang.RU);
		assertTrue(v.playerLines().get("DOMI").get(0).rebuttal());
	}
}
