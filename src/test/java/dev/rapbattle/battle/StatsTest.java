package dev.rapbattle.battle;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class StatsTest {

	private static Opponent fakeOpp(String id, int rank) {
		var prof = new MobProfiles.Profile(id, rank, id, "mob", "*sound*", "style", List.of("MC " + id), id, false);
		return new Opponent(null, prof, Rank.ofLevel(rank), "MC " + id, "", List.of());
	}

	@Test
	void thresholdTitlesEarnedExactlyAtThresholdAndTranslated() {
		Stats stats = new Stats();
		UUID u = UUID.randomUUID();

		// 9 sheep wins -> no Овцеубийца; 10th -> Овцеубийца
		for (int i = 0; i < 9; i++) {
			var t = stats.record(List.of(u), Map.of(u, 50), u, true, fakeOpp("sheep", 1), Map.of(u, 2), 0L);
			assertFalse(t.getOrDefault(u, List.of()).contains("Овцеубийца"), "Should not earn Sheep Slayer before 10 wins");
		}
		var t10 = stats.record(List.of(u), Map.of(u, 50), u, true, fakeOpp("sheep", 1), Map.of(u, 2), 0L);
		assertEquals(List.of("Овцеубийца"), t10.get(u));
		assertEquals("Sheep Slayer", Stats.translateTitle("Овцеубийца", dev.rapbattle.Lang.EN));
		assertEquals("Овцеубийца", Stats.translateTitle("Овцеубийца", dev.rapbattle.Lang.RU));

		// 4 creeper wins -> no Сапёр; 5th -> Сапёр
		for (int i = 0; i < 4; i++) {
			assertFalse(stats.player(u).titles.contains("Сапёр"));
			stats.record(List.of(u), Map.of(u, 50), u, true, fakeOpp("creeper", 2), Map.of(u, 2), 0L);
		}
		var tC5 = stats.record(List.of(u), Map.of(u, 50), u, true, fakeOpp("creeper", 2), Map.of(u, 2), 0L);
		assertEquals(List.of("Сапёр"), tC5.get(u));
		assertEquals("Bomb Squad", Stats.translateTitle("Сапёр", dev.rapbattle.Lang.EN));

		// 9 zombie wins -> no Гробовщик; 10th -> Гробовщик
		for (int i = 0; i < 9; i++) {
			stats.record(List.of(u), Map.of(u, 50), u, true, fakeOpp("zombie", 1), Map.of(u, 2), 0L);
		}
		assertFalse(stats.player(u).titles.contains("Гробовщик"));
		var tZ10 = stats.record(List.of(u), Map.of(u, 50), u, true, fakeOpp("zombie", 1), Map.of(u, 2), 0L);
		assertEquals(List.of("Гробовщик"), tZ10.get(u));
		assertEquals("Undertaker", Stats.translateTitle("Гробовщик", dev.rapbattle.Lang.EN));

		// Chickened out: 2 -> null, 3 -> Зассал, 4 -> null
		assertNull(stats.chickened(u, dev.rapbattle.Lang.RU));
		assertNull(stats.chickened(u, dev.rapbattle.Lang.RU));
		assertEquals("Chickened Out", stats.chickened(u, dev.rapbattle.Lang.EN));
		assertNull(stats.chickened(u, dev.rapbattle.Lang.RU));
	}

	@Test
	void recordPvpDoesNotPolluteMobSpeciesStats() {
		Stats stats = new Stats();
		UUID p1 = UUID.randomUUID();
		UUID p2 = UUID.randomUUID();
		stats.recordPvp(p1, p2, 80, 45);
		assertEquals(1, stats.player(p1).wins);
		assertEquals(80, stats.player(p1).respect);
		assertEquals(1, stats.player(p2).losses);
		assertEquals(45, stats.player(p2).respect);
		assertTrue(stats.player(p1).speciesWins.isEmpty());
		assertArrayEquals(new int[]{0, 0}, stats.species("zombie"));
	}
}
