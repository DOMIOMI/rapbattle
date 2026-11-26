package dev.rapbattle.battle;

import dev.rapbattle.net.BattleView;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class MobTricksTest {

	private static List<BattleView.Row> makeRows(String player, int count) {
		List<BattleView.Row> rows = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			BattleView.Row r = new BattleView.Row();
			r.reader = player;
			r.line = i + 1;
			r.word = "word" + i;
			rows.add(r);
		}
		return rows;
	}

	@Test
	void allTrickEffectsApplyWithoutDroppingWordsOrAffectingOtherPlayersUnexpectedly() {
		Map<String, String> speciesToEffect = Map.of(
			"fox", "stolen",
			"frog", "stolen",
			"blaze", "burn",
			"squid", "ink",
			"llama", "spit",
			"goat", "ram",
			"illusioner", "illusion",
			"spider", "web_pair"
		);

		for (var entry : speciesToEffect.entrySet()) {
			List<BattleView.Row> p1 = makeRows("Steve", 8);
			List<BattleView.Row> p2 = makeRows("Alex", 8);
			MobTricks.apply(entry.getKey(), List.of(p1, p2), new Random(42));

			assertTrue(p1.stream().anyMatch(r -> entry.getValue().equals(r.effect)),
				"Player 1 must receive effect " + entry.getValue() + " for " + entry.getKey());
			assertTrue(p2.stream().anyMatch(r -> entry.getValue().equals(r.effect)),
				"Player 2 must receive effect " + entry.getValue() + " for " + entry.getKey());
			assertEquals(8, p1.size());
			assertTrue(p1.stream().allMatch(r -> r.word != null && !r.word.isEmpty()));
		}

		// Witch picks one of shake / blind / hidden
		List<BattleView.Row> witchRows = makeRows("Steve", 8);
		MobTricks.apply("witch", List.of(witchRows), new Random(42));
		assertTrue(witchRows.stream().anyMatch(r ->
			"shake".equals(r.effect) || "blind".equals(r.effect) || "hidden".equals(r.effect)));
	}

	@Test
	void clientFlagsMatchMobMechanics() {
		BattleView em = new BattleView();
		MobTricks.flags("enderman", em, "ru_ru");
		assertTrue(em.gaze && !em.fuse && !em.silence && !em.creakingGaze);

		BattleView cr = new BattleView();
		MobTricks.flags("creeper", cr, "ru_ru");
		assertTrue(cr.fuse && !cr.gaze);

		BattleView wd = new BattleView();
		MobTricks.flags("warden", wd, "en_us");
		assertTrue(wd.silence && !wd.gaze);

		BattleView ck = new BattleView();
		MobTricks.flags("creaking", ck, "en_us");
		assertTrue(ck.creakingGaze && !ck.silence);
	}
}
