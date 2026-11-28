package dev.rapbattle.battle;

import dev.rapbattle.Lang;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RecentRhymesTest {
	@AfterEach
	void clear() {
		RecentRhymes.clear();
	}

	@Test
	void rematchGetsOtherRhymeGroups() {
		UUID p = UUID.randomUUID();
		List<List<String>> first = RhymeBank.pickBlocks(3, new Random(1), Lang.RU);
		for (List<String> b : first) RecentRhymes.remember(p, b);
		Set<String> recent = RecentRhymes.of(List.of(p));
		assertEquals(12, recent.size());
		List<List<String>> second = RhymeBank.pickBlocks(3, new Random(1), Lang.RU, recent);
		for (List<String> b : second) {
			for (String w : b) assertFalse(recent.contains(w.toLowerCase().replace('ё', 'е')), "repeated " + w);
		}
	}

	@Test
	void keepsOnlyTheLastWordsAndNormalizes() {
		UUID p = UUID.randomUUID();
		List<String> many = new ArrayList<>();
		for (int i = 0; i < RecentRhymes.KEEP + 10; i++) many.add("w" + i);
		RecentRhymes.remember(p, many);
		Set<String> recent = RecentRhymes.of(List.of(p));
		assertEquals(RecentRhymes.KEEP, recent.size());
		assertFalse(recent.contains("w0"));
		RecentRhymes.remember(p, List.of("Шахтёр"));
		assertTrue(RecentRhymes.repeats(List.of("шахтер"), RecentRhymes.of(List.of(p))));
	}

	@Test
	void fallsBackWhenEverythingWasUsed() {
		UUID p = UUID.randomUUID();
		for (List<String> g : RhymeBank.GROUPS) RecentRhymes.remember(p, g);
		// KEEP limits memory, but even if all groups repeat, pickBlocks still returns full blocks.
		List<List<String>> blocks = RhymeBank.pickBlocks(4, new Random(2), Lang.RU, RecentRhymes.of(List.of(p)));
		assertEquals(4, blocks.size());
		for (List<String> b : blocks) assertEquals(4, b.size());
	}
}
