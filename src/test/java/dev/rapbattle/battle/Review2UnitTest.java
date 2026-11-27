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

	@Test
	void wavHeaderValidationChecks16kMono16Bit() {
		byte[] valid = new byte[44 + 100];
		java.nio.ByteBuffer b = java.nio.ByteBuffer.wrap(valid).order(java.nio.ByteOrder.LITTLE_ENDIAN);
		b.put("RIFF".getBytes()).putInt(36 + 100).put("WAVE".getBytes());
		b.put("fmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) 1)
			.putInt(16000).putInt(32000).putShort((short) 2).putShort((short) 16);
		b.put("data".getBytes()).putInt(100);
		assertTrue(dev.rapbattle.ai.Transcriber.isValidWav16kMono16(valid));

		assertFalse(dev.rapbattle.ai.Transcriber.isValidWav16kMono16(new byte[10]));
		byte[] garbage = "NOT_A_WAV_FILE_JUST_RANDOM_BYTES_12345678901234567890".getBytes();
		assertFalse(dev.rapbattle.ai.Transcriber.isValidWav16kMono16(garbage));

		byte[] stereo = valid.clone();
		stereo[22] = 2; // 2 channels instead of 1
		assertFalse(dev.rapbattle.ai.Transcriber.isValidWav16kMono16(stereo));
	}

	@Test
	void mergeCredDoesNotShortenExistingTimer() {
		long now = 100_000L;
		Reputation.CredState first = Reputation.mergeCred(null, 3, 180_000L, now);
		assertNotNull(first);
		assertEquals(3, first.level());
		assertEquals(now + 180_000L, first.until());

		// Second win 10s later with shorter 90s (overdressed) duration must NOT shorten remaining 170s timer
		long later = now + 10_000L;
		Reputation.CredState second = Reputation.mergeCred(first, 2, 90_000L, later);
		assertEquals(3, second.level());
		assertEquals(first.until(), second.until());
	}

	@Test
	void coopSilentPlayerDoesNotUnlockSpeciesOrDragonGates() {
		Stats stats = new Stats();
		UUID active = UUID.randomUUID();
		UUID silent = UUID.randomUUID();
		stats.record(List.of(active, silent), Map.of(active, 90, silent, 0), active, true,
			fakeOpp("ender_dragon", 6), Map.of(active, 5, silent, 5), 1L);
		assertTrue(stats.player(active).dragonWon);
		assertEquals(1, stats.player(active).speciesWins.getOrDefault("ender_dragon", 0));
		assertEquals(1, stats.player(silent).wins);
		assertFalse(stats.player(silent).dragonWon);
		assertEquals(0, stats.player(silent).speciesWins.getOrDefault("ender_dragon", 0));
	}
}
