package dev.rapbattle.battle;

import dev.rapbattle.Lang;
import dev.rapbattle.ai.Repeats;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class FairRhymesAndRepeatsTest {
	@Test
	void pvpPlayersGetTheSameRhymeGroups() {
		for (int seed = 0; seed < 20; seed++) {
			var pair = RhymeBank.pickPairedBlocks(2, new Random(seed), Lang.RU, Set.of());
			for (int i = 0; i < 2; i++) {
				List<String> a = pair.get(0).get(i), b = pair.get(1).get(i);
				assertEquals(4, a.size());
				assertEquals(4, b.size());
				List<String> group = RhymeBank.GROUPS.stream().filter(g -> g.containsAll(a)).findFirst().orElseThrow();
				assertTrue(group.containsAll(b), "both blocks come from one rhyme group");
				Set<String> shared = new HashSet<>(a);
				shared.retainAll(b);
				assertTrue(shared.size() <= 1, "each player has their own words");
			}
		}
	}

	@Test
	void repeatsAreNoticed() {
		String note = Repeats.note("DOMI", List.of("ты жопа", "твоя жопа", "моя жопа", "член и жопа"), Lang.RU);
		assertTrue(note.contains("жопа"), note);
		assertTrue(note.contains("туалетные"), note);
		assertEquals("", Repeats.note("DOMI", List.of("я копаю алмазы в пещере", "ты сгорел в лаве у двери"), Lang.RU));
	}
}
