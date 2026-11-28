package dev.rapbattle.battle;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DossierTest {
	@Test
	void chickenedIsNotRepeatedOnceThePlayerWins() {
		assertFalse(Battle.chickenedWorthMention(0, 0));
		assertFalse(Battle.chickenedWorthMention(1, 0));
		assertTrue(Battle.chickenedWorthMention(2, 0));
		assertTrue(Battle.chickenedWorthMention(3, 1));
		assertFalse(Battle.chickenedWorthMention(2, 3));
	}
}
