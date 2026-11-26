package dev.rapbattle.client;

import dev.rapbattle.net.BattleView;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.entity.state.LivingEntityRenderState;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.RotationAxis;

/**
 * Dances. Everyone taking part moves to the beat:
 * READ (it's your line: spitting, holding the mic), LISTEN (nodding), WIN / LOSE (on the result screen).
 * Whole-body moves (bounce, sway, slime squash, creeper swell, fish flop) work for any entity; limb moves are
 * applied per model family by the model mixins (bipeds and players, quadrupeds, villagers, illagers, golems).
 */
public final class BattleDance {
	private BattleDance() {}

	public enum Mode { NONE, READ, LISTEN, WIN, LOSE, CROWD }

	public static Mode mode(int entityId) {
		BattleView v = ClientBattle.view;
		if (entityId < 0 || v == null || !ClientConfig.get().animations) return Mode.NONE;
		boolean mob = v.mobEntityIds.contains(entityId);
		boolean player = false;
		for (BattleView.Member m : v.team) if (m.entityId == entityId) player = true;
		if (!mob && !player) {
			// The crowd grooves only while the beat plays; when the verses are over, it stands still and listens.
			if (!v.crowdIds.contains(entityId)) return Mode.NONE;
			return "TIMELINE".equals(v.phase) && ClientBattle.clock() >= 0 ? Mode.CROWD : Mode.NONE;
		}
		switch (v.phase) {
			case "TIMELINE" -> {
				if (ClientBattle.clock() < 0) return Mode.NONE;
				return ClientBattle.readerId() == entityId ? Mode.READ : Mode.LISTEN;
			}
			case "ROUND_RESULT", "RESULT" -> {
				if (v.result == null) return Mode.NONE;
				if (!BroadcastHud.verdictReady(v)) return Mode.LISTEN;
				if (v.pvp) {
					// Player battle: the winner dances, the other one hangs his head.
					for (BattleView.Member m : v.team) if (m.entityId == entityId) return m.name.equals(v.result.winner) ? Mode.WIN : Mode.LOSE;
				}
				boolean playersWon = v.result.playersWin;
				return player == playersWon ? Mode.WIN : Mode.LOSE;
			}
			case "JUDGING" -> {
				return Mode.LISTEN;
			}
			default -> {
				return Mode.NONE;
			}
		}
	}

	/** Beat phase helpers: hit decays from 1 to 0 within each beat; wave goes -1..1 over two beats. */
	private static float hit() {
		float f = ClientBattle.beatFrac();
		return (1 - f) * (1 - f);
	}

	private static float wave() {
		return (float) Math.sin(Math.PI * (ClientBattle.beat() + ClientBattle.beatFrac()) / 2.0);
	}

	/** Result screens have no beat grid: dance to a steady 110 bpm clock. */
	private static float freeHit() {
		float f = (System.currentTimeMillis() % 545) / 545f;
		return (1 - f) * (1 - f);
	}

	private static float freeWave() {
		return (float) Math.sin(Math.PI * (System.currentTimeMillis() % 1090) / 545.0);
	}

	/** Is the beat clock running (the battle table), or are we on the free 110 bpm clock (results)? */
	public static boolean onBeat() {
		BattleView v = ClientBattle.view;
		return v != null && "TIMELINE".equals(v.phase) && ClientBattle.clock() >= 0;
	}

	/** Each fan has their own groove: 0 nods every beat, 1 bounces every other beat, 2 sways. */
	public static int crowdStyle(int id) { return Math.floorMod(id * 7 + 3, 3); }

	/** How excited the crowd is: 0..1, from the hype meter (and full at the end). */
	public static float crowdHype() {
		BattleView v = ClientBattle.view;
		if (v == null) return 0;
		if ("RESULT".equals(v.phase) || "ROUND_RESULT".equals(v.phase)) return 1;
		return Math.min(1, 0.35f + Math.abs(v.hype) / 100f);
	}

	/** Crowd hit: like hit(), but half-time for style 1 and shifted per fan so they never move as one. */
	static float crowdHit(int id) {
		float pos = (onBeat() ? ClientBattle.beat() + ClientBattle.beatFrac() : (System.currentTimeMillis() % 1_000_000L) / 545f)
			+ (Math.floorMod(id * 13, 5)) * 0.04f;
		int style = crowdStyle(id);
		float len = style == 1 ? 2 : 1;
		float f = (pos % len) / len;
		return (1 - f) * (1 - f);
	}

	/** Vertical bounce, also used to lift the bling with the body. */
	public static float bounce(int entityId, LivingEntityRenderState s) {
		Mode m = mode(entityId);
		return switch (m) {
			case READ -> 0.09f * MobMoves.pulse(ClientBattle.beatFrac()) * s.height * 0.5f;
			case LISTEN -> onBeat() ? 0.03f * MobMoves.pulse(ClientBattle.beatFrac()) * s.height * 0.5f : 0f;
			case WIN -> 0.15f * freeHit() * s.height * 0.5f;
			case CROWD -> (crowdStyle(entityId) == 2 ? 0.03f : 0.09f) * crowdHit(entityId) * (0.6f + 0.6f * crowdHype()) * s.height * 0.5f;
			default -> 0f;
		};
	}

	/** Whole-body moves, from LivingEntityRenderer#setupTransforms. */
	public static void apply(int entityId, LivingEntityRenderState s, MatrixStack m) {
		Mode mode = mode(entityId);
		if (mode == Mode.NONE) return;
		String type = Registries.ENTITY_TYPE.getId(s.entityType).getPath();
		// Squash and stretch on the beat: the body flattens a little on the hit and springs back.
		// Slimes and creepers have their own bigger version below.
		if (!type.equals("slime") && !type.equals("magma_cube") && !type.equals("creeper")) {
			float sq = switch (mode) {
				case READ -> 0.08f * MobMoves.pulse(ClientBattle.beatFrac());
				case LISTEN -> onBeat() ? 0.03f * MobMoves.pulse(ClientBattle.beatFrac()) : 0f;
				case CROWD -> 0.045f * crowdHit(entityId) * (0.6f + 0.6f * crowdHype());
				default -> 0f;
			};
			if (sq > 0) m.scale(1 + sq * 0.55f, 1 - sq, 1 + sq * 0.55f);
		}
		if (mode == Mode.LOSE) {
			m.multiply(RotationAxis.POSITIVE_X.rotationDegrees(8)); // slumped
			return;
		}
		boolean player = type.equals("player");
		boolean mob = ClientBattle.view != null && ClientBattle.view.mobEntityIds.contains(entityId);
		if (player && onBeat()) {
			float punch = ClientBattle.mobPunchImpulse();
			if (punch > 0) m.multiply(RotationAxis.POSITIVE_X.rotationDegrees(-12f * punch));
		}
		if ((mob || player) && !onBeat() && ClientBattle.view != null && !BroadcastHud.verdictReady(ClientBattle.view)) {
			float flinch = BroadcastHud.recentHitImpulse(ClientBattle.view, mob);
			if (flinch > 0) m.multiply(RotationAxis.POSITIVE_X.rotationDegrees(-10f * flinch));
		}
		if (PlayerMoves.active(entityId)) return;
		boolean beat = mode == Mode.READ || mode == Mode.LISTEN || (mode == Mode.CROWD && onBeat());
		float hit = mode == Mode.CROWD ? crowdHit(entityId) : beat ? hit() : freeHit();
		float wave = beat ? wave() : freeWave();
		if (mode == Mode.CROWD) wave = (float) Math.sin(Math.asin(Math.max(-1, Math.min(1, wave))) + entityId);
		float amp = mode == Mode.READ ? 2.2f : mode == Mode.WIN ? 1.8f : mode == Mode.CROWD ? 0.6f + 0.8f * crowdHype() : 1f;
		if (mode == Mode.READ && player) amp *= (0.6f + 0.8f * ClientBattle.smoothMicLevel());
		m.translate(0, bounce(entityId, s), 0);
		switch (type) {
			case "slime", "magma_cube" -> {
				// Squash and stretch on every beat.
				float sq = 0.22f * hit * amp * 0.5f;
				m.scale(1 + sq * 0.6f, 1 - sq, 1 + sq * 0.6f);
			}
			case "creeper" -> {
				float sw = 1 + 0.07f * hit * amp;
				m.scale(sw, 1 + 0.03f * hit, sw);
			}
			case "cod", "salmon", "tropical_fish", "pufferfish", "tadpole" -> {
				if (!s.touchingWater) m.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(90 + 25 * wave)); // flop on land
				else m.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(18 * wave * amp));
			}
			case "bat", "bee", "allay", "vex", "phantom", "ghast", "happy_ghast", "blaze", "breeze" -> {
				m.translate(0, 0.15f * wave * amp, 0);
				m.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(12 * wave * amp));
			}
			case "spider", "cave_spider" -> m.multiply(RotationAxis.POSITIVE_X.rotationDegrees(-6 * hit * amp));
			case "villager", "wandering_trader", "pillager", "vindicator", "evoker", "illusioner", "witch" -> {
				// Nose humanoids: limbs and head do the dancing (Villager/Witch/IllagerModelMixin), no side-to-side body sway.
			}
			default -> {
				// Everyone else: limbs do the dancing (MobMoves), body roll capped strictly within ±2 degrees.
				if (mode == Mode.CROWD || mode == Mode.WIN) {
					float roll = Math.max(-2f, Math.min(2f, 1.4f * wave * amp));
					m.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(roll));
				}
			}
		}
	}

	private static int selfId() {
		var mc = net.minecraft.client.MinecraftClient.getInstance();
		return mc.player == null ? -1 : mc.player.getId();
	}
}
