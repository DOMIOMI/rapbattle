package dev.rapbattle.client;

import com.zigythebird.playeranim.animation.PlayerAnimationController;
import com.zigythebird.playeranim.api.PlayerAnimationAccess;
import com.zigythebird.playeranimcore.animation.layered.modifier.SpeedModifier;
import dev.rapbattle.net.BattleView;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.entity.PlayerLikeEntity;
import net.minecraft.util.Identifier;

import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Players' battle animations through Player Animation Library: keyframed moves made in
 * {@code tools/gen_player_anims.py} (rapping with the mic, listening, hype, four victory dances, defeat).
 * We play them on the library's own player layer, pick the move from the battle state and match the tempo.
 */
public final class PlayerMoves {
	private PlayerMoves() {}

	/** The moves are made at 120 bpm: one beat is 500 ms. */
	private static final float MADE_AT_BEAT_MS = 500f;

	private record Layer(PlayerAnimationController controller, SpeedModifier speed) {}

	private static final Map<PlayerLikeEntity, Layer> LAYERS = new WeakHashMap<>();
	private static final Map<Integer, String> PLAYING = new HashMap<>();

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(PlayerMoves::tick);
	}

	/** The library's own animation layer of this player, with our tempo modifier attached once. */
	private static Layer layer(AbstractClientPlayerEntity p) {
		Layer l = LAYERS.get(p);
		if (l != null) return l;
		var anim = PlayerAnimationAccess.getPlayerAnimationLayer(p, com.zigythebird.playeranim.PlayerAnimLibMod.ANIMATION_LAYER_ID);
		if (!(anim instanceof PlayerAnimationController c)) return null;
		SpeedModifier speed = new SpeedModifier(1f);
		c.addModifierLast(speed);
		l = new Layer(c, speed);
		LAYERS.put(p, l);
		return l;
	}

	/** Our keyframed moves drive this player right now (the old code-made poses stand down). */
	public static boolean active(int entityId) {
		return PLAYING.get(entityId) != null;
	}

	private static void tick(MinecraftClient mc) {
		if (mc.world == null) {
			PLAYING.clear();
			return;
		}
		for (AbstractClientPlayerEntity p : mc.world.getPlayers()) {
			Layer layer = layer(p);
			if (layer == null) continue;
			String want = ClientConfig.get().animations ? wanted(p.getId()) : null;
			String now = PLAYING.get(p.getId());
			BattleView v = ClientBattle.view;
			boolean onBeat = want != null && (want.startsWith("rap") || ((want.equals("listen") || want.equals("hype")) && BattleDance.onBeat()));
			float baseSpeed = onBeat && v != null && v.beatMs > 0 ? MADE_AT_BEAT_MS / v.beatMs : want != null && want.equals("listen") ? 0.2f : 1f;
			if (want != null && want.startsWith("rap")) {
				baseSpeed *= (0.6f + 0.8f * ClientBattle.smoothMicLevel());
			}
			layer.speed().speed = baseSpeed;
			if (java.util.Objects.equals(want, now)) continue;
			// Align beat-driven animation restarts with ClientBattle.beatFrac() so head nods stay in phase.
			if (want != null && want.startsWith("rap") && now != null && ClientBattle.beatFrac() > 0.15f) continue;
			try {
				if (want == null) {
					layer.controller().stop();
				} else {
					layer.controller().triggerAnimation(Identifier.of("rapbattle", want));
				}
				PLAYING.put(p.getId(), want);
			} catch (RuntimeException e) {
				// A missing or broken animation must never crash the game: fall back to the code-made poses.
				PLAYING.put(p.getId(), null);
			}
		}
	}

	/** Which move this player should do now, or null for none. */
	private static String wanted(int id) {
		BattleDance.Mode mode = BattleDance.mode(id);
		switch (mode) {
			case READ -> {
				BattleView.Row r = ClientBattle.liveRow();
				return r != null && r.line % 2 == 0 ? "rap_b" : "rap_a";
			}
			case LISTEN -> {
				BattleView.Row r = ClientBattle.liveRow();
				boolean mateHit = r != null && !r.mob && r.readerEntityId != id && ClientBattle.beat() - r.startBeat == 3;
				return mateHit ? "hype" : "listen";
			}
			case WIN -> {
				int style = Math.floorMod(id * 31 + 7, 4);
				var mc = MinecraftClient.getInstance();
				if (mc.player != null && mc.player.getId() == id) style = ClientConfig.get().victoryDance % 4;
				return "win_" + style;
			}
			case LOSE -> {
				return "lose";
			}
			default -> {
				return null;
			}
		}
	}
}
