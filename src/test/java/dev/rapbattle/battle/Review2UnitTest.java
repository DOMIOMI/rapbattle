package dev.rapbattle.battle;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests added during the second code review (6.24):
 * - Stats & MobRecord Codec persistence round-trips (world save/load)
 * - Full progression gate sequence (Act 1 -> Rank 4 -> Warden -> Ender Dragon)
 */
class Review2UnitTest {

	private static Opponent fakeOpp(String id, int rank) {
		var prof = new MobProfiles.Profile(id, rank, id, "mob", "*sound*", "style", List.of("MC " + id), id, false);
		return new Opponent(null, prof, Rank.ofLevel(rank), "MC " + id, "", List.of());
	}

	@Test
	void statsCodecRoundTripPreservesProgressionDailyCapAndLegend() {
		Stats stats = new Stats();
		UUID u = UUID.randomUUID();
		stats.record(List.of(u), Map.of(u, 85), u, true, fakeOpp("blaze", 4), Map.of(u, 4), 12L);
		stats.record(List.of(u), Map.of(u, 110), u, true, fakeOpp("ender_dragon", 6), Map.of(u, 5), 12L);
		stats.player(u).respect = 1200;
		stats.player(u).style = McStyle.METRONOME.id;
		stats.recordLegend("DOMI", "Тестовая легендарная строка");

		JsonElement encoded = Stats.CODEC.encodeStart(JsonOps.INSTANCE, stats).getOrThrow();
		Stats decoded = Stats.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow();

		Stats.Player p = decoded.player(u);
		assertEquals(2, p.wins);
		assertEquals(1200, p.respect);
		assertTrue(p.dragonWon);
		assertEquals(McStyle.METRONOME, p.mcStyle());
		assertEquals(1, p.dailyWinsFor("blaze", 12L));
		assertEquals(0, p.dailyWinsFor("blaze", 13L));
		assertTrue(p.titles.contains("Укротитель дракона"));
		assertEquals("DOMI", decoded.legend.player());
		assertEquals("Тестовая легендарная строка", decoded.legend.line());
		assertArrayEquals(new int[]{0, 1}, decoded.species("blaze"));
	}

	@Test
	void mobRecordCodecRoundTripPreservesChampionAndOrigName() {
		MobRecord rec = new MobRecord(3, 1, 2, "МС Зомби", "MC Zombie",
			List.of("DOMI\t1\tстрока"), 42000L, "Бобик");

		JsonElement encoded = MobRecord.CODEC.encodeStart(JsonOps.INSTANCE, rec).getOrThrow();
		MobRecord decoded = MobRecord.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow();

		assertEquals(3, decoded.wins());
		assertEquals(1, decoded.losses());
		assertEquals(2, decoded.streak());
		assertEquals("МС Зомби", decoded.name("ru"));
		assertEquals("MC Zombie", decoded.name("en"));
		assertEquals("Бобик", decoded.origName());
		assertEquals(42000L, decoded.lastDiscountTick());
		assertEquals(1, decoded.memory().size());
	}

	@Test
	void fullProgressionGateSequenceFromAct1ToDragon() {
		// Rank 4 soft-gated initially, then opens after 2 rank-3 species wins
		assertFalse(ProgressionRules.checkRankGate(ProgressionRules.Mode.SOFT, 4, false, 0, 1, false).bonusesAllowed());
		assertTrue(ProgressionRules.checkRankGate(ProgressionRules.Mode.SOFT, 4, false, 0, 2, false).bonusesAllowed());

		// Warden requires mic + 500 respect + 1 rank-4+ win
		assertFalse(ProgressionRules.checkBossGate(ProgressionRules.Mode.SOFT, "warden", 0, false, false, 600, 0, 0, 1, 0).allowed());
		assertTrue(ProgressionRules.checkBossGate(ProgressionRules.Mode.SOFT, "warden", 0, true, false, 500, 0, 0, 1, 0).allowed());

		// Ender Dragon requires 0 crystals + high-tier trophy mic + 1000 respect + 1 rank-5/boss win
		assertFalse(ProgressionRules.checkBossGate(ProgressionRules.Mode.SOFT, "ender_dragon", 1, true, true, 1200, 0, 0, 2, 1).allowed());
		assertFalse(ProgressionRules.checkBossGate(ProgressionRules.Mode.SOFT, "ender_dragon", 0, true, false, 1200, 0, 0, 2, 1).allowed());
		assertTrue(ProgressionRules.checkBossGate(ProgressionRules.Mode.SOFT, "ender_dragon", 0, true, true, 1000, 0, 0, 2, 1).allowed());
	}
}
