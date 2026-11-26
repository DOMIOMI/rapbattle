package dev.rapbattle.test;

import dev.rapbattle.ai.Llm;
import dev.rapbattle.battle.Battle;
import dev.rapbattle.battle.BattleManager;
import dev.rapbattle.client.ClientBattle;
import dev.rapbattle.client.Intro;
import dev.rapbattle.net.Payloads;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;

/**
 * Single full end-to-end battle scenario with real RB_TEST_WAV:
 * beat, mob verse, player verse, audio/recognition, judge verdict, result.
 * Uses network LLM only when RB_TEST_LIVE=1; otherwise uses offline Fallback stub.
 */
public final class E2eScenario {
	private E2eScenario() {}

	private static boolean phase(String p) {
		return ClientBattle.view != null && p.equals(ClientBattle.view.phase);
	}

	public static void run(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		boolean live = "1".equals(System.getenv("RB_TEST_LIVE"));
		Llm.setOfflineStubForTest(!live);
		try {
			sp.getServer().runCommand("time set 14500");
			Battle[] battle = new Battle[1];
			sp.getServer().runOnServer(server -> {
				ServerPlayerEntity p = server.getPlayerManager().getPlayerList().get(0);
				ServerWorld w = p.getEntityWorld();
				MobEntity m = EntityType.ZOMBIE.create(w, SpawnReason.COMMAND);
				m.refreshPositionAndAngles(p.getX(), p.getY(), p.getZ() + 6, 180, 0);
				m.setAiDisabled(true);
				m.equipStack(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
				w.spawnEntity(m);
				battle[0] = BattleManager.start(p, m);
				battle[0].startEarly(p);
			});
			ctx.waitFor(c -> phase("TIMELINE"), 800);
			Intro.skip();
			ctx.waitFor(c -> ClientBattle.timeline() && ClientBattle.beat() >= ClientBattle.view.mobEndBeat + 2, 1200);
			sp.getServer().runOnServer(server -> battle[0].onAudio(
				server.getPlayerManager().getPlayerList().get(0),
				new Payloads.AudioC2S(battle[0].id, 0, true, 0, GameTestSupport.wav())));
			ctx.waitFor(c -> phase("RESULT"), 1200);
			ctx.waitFor(c -> ClientBattle.view == null, 600);
			GameTestSupport.assertNoRawKeys("e2e");
		} finally {
			Llm.setOfflineStubForTest(true);
		}
	}
}
