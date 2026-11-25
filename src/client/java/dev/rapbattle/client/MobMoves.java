package dev.rapbattle.client;

import dev.rapbattle.net.BattleView;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.entity.state.LivingEntityRenderState;
import net.minecraft.registry.Registries;

import java.util.HashMap;
import java.util.Map;

/**
 * Mobs on the mic: a held pose plus one sharp accent on the beat, instead of everything swaying at once.
 * <ul>
 *   <li>{@link #pulse} snaps to 1 right on the beat and settles back quickly: that is the "hit".</li>
 *   <li>Only a couple of parts move on a beat: the head nods, one arm makes the gesture, the rest holds.</li>
 *   <li>Mode changes (listening → reading) blend over 150 ms, so poses never jump.</li>
 *   <li>The text drives gestures: on the rhyme the mob hits harder, on a punchline it points at the opponent.</li>
 * </ul>
 * Each family moves its own way: zombies sway heavy shoulders, skeletons snap like a robot, piglins slam,
 * endermen do a stiff pop, illagers and villagers gesture, golems stomp, four-legged mobs headbang and stomp.
 */
public final class MobMoves {
	private MobMoves() {}

	private static final long BLEND_MS = 150;
	private record Since(BattleDance.Mode mode, long at) {}
	private static final Map<Integer, Since> SINCE = new HashMap<>();

	/** 0 → 1 over the first 8% of a beat, then an exponential settle: a hit, then a hold. */
	static float pulse(float frac) {
		return frac < 0.08f ? frac / 0.08f : (float) Math.exp(-(frac - 0.08f) * 4.5);
	}

	/** How far into the new mode this entity is (0..1), for blending from the previous pose. */
	static float blend(int id, BattleDance.Mode mode) {
		long now = System.currentTimeMillis();
		Since s = SINCE.get(id);
		if (s == null || s.mode() != mode) {
			s = new Since(mode, now);
			SINCE.put(id, s);
			if (SINCE.size() > 256) SINCE.clear();
		}
		float t = Math.min(1f, (now - s.at()) / (float) BLEND_MS);
		return t * t * (3 - 2 * t);
	}

	/** Moves a part from the pose vanilla gave it toward the target, by {@code b}. */
	static void to(ModelPart part, float b, float pitch, float yaw, float roll) {
		if (part == null) return;
		part.pitch += (pitch - part.pitch) * b;
		part.yaw += (yaw - part.yaw) * b;
		part.roll += (roll - part.roll) * b;
	}

	/** Beat of the line being read (0..3, 3 = the rhyme), or -1 between lines. */
	private static int lineBeat() {
		BattleView.Row r = ClientBattle.liveRow();
		return r == null ? -1 : ClientBattle.beat() - r.startBeat;
	}

	private static boolean punchLine() {
		BattleView.Row r = ClientBattle.liveRow();
		return r != null && r.punch;
	}

	private static String type(LivingEntityRenderState s) { return Registries.ENTITY_TYPE.getId(s.entityType).getPath(); }

	// ------------------------------------------------------------------ humanoid mobs

	public static void biped(LivingEntityRenderState s, int id, BattleDance.Mode mode, ModelPart head, ModelPart body,
							 ModelPart rightArm, ModelPart leftArm, ModelPart rightLeg, ModelPart leftLeg) {
		float b = blend(id, mode);
		int beat = ClientBattle.beat();
		float pu = pulse(ClientBattle.beatFrac());
		String t = type(s);
		boolean zombie = t.contains("zombie") || t.equals("husk") || t.equals("drowned") || t.equals("giant");
		boolean skeleton = t.contains("skeleton") || t.equals("stray") || t.equals("bogged") || t.equals("parched");
		boolean piglin = t.contains("piglin");

		if (mode == BattleDance.Mode.LISTEN) {
			// Waiting for its turn: relaxed arms (no zombie arms-out), a nod on every beat, weight on one leg.
			head.pitch += 0.22f * pu * b;
			to(rightArm, b, -0.25f, 0.15f, 0.05f);
			to(leftArm, b, -0.25f, -0.15f, -0.05f);
			to(body, b, 0f, 0.08f, 0f);
			to(rightLeg, b, 0.06f, 0f, 0.04f);
			to(leftLeg, b, -0.04f, 0f, -0.02f);
			return;
		}

		int k = lineBeat();
		boolean rhyme = k == 3;
		boolean punch = rhyme && punchLine();
		// Head: a sharp nod on every beat, harder on the rhyme.
		head.pitch += (rhyme ? 0.45f : 0.3f) * pu * b;
		// Shoulders step from side to side on the beat (a held step, not a wobble).
		float side = beat % 2 == 0 ? 1f : -1f;
		to(body, b, punch ? 0.18f * pu : 0f, 0.12f * side, 0f);
		// Feet tap the beat, alternating.
		to(rightLeg, b, side > 0 ? 0.16f * pu : 0f, 0f, 0f);
		to(leftLeg, b, side < 0 ? 0.16f * pu : 0f, 0f, 0f);

		if (punch) {
			// Punchline: the right hand points straight at the opponent, the body leans in.
			to(rightArm, b, -1.55f, 0.1f, 0f);
			to(leftArm, b, -0.3f, -0.2f, -0.1f);
			return;
		}
		if (t.equals("enderman")) {
			// Stiff pop: jumps to a new pose on each beat and freezes there.
			float[][] poses = {{-1.6f, -0.2f}, {-0.2f, -1.6f}, {-1.3f, -1.3f}, {-0.1f, -0.1f}};
			float[] q = poses[Math.floorMod(beat, 4)];
			to(rightArm, b, q[0], 0f, 0.05f);
			to(leftArm, b, q[1], 0f, -0.05f);
			head.yaw += (beat % 2 == 0 ? 0.35f : -0.35f) * b;
		} else if (skeleton) {
			// Robot: snap poses with a rattle right on the hit.
			float[][] poses = {{-1.3f, -0.2f}, {-0.2f, -1.3f}, {-1.6f, -1.6f}, {-0.4f, -0.4f}};
			float[] q = poses[Math.floorMod(beat, 4)];
			to(rightArm, b, q[0], 0f, 0.08f);
			to(leftArm, b, q[1], 0f, -0.08f);
			head.roll += (float) Math.sin(ClientBattle.beatFrac() * Math.PI * 10) * 0.1f * pu * b;
		} else if (zombie) {
			// Heavy street sway: arms low and loose, the right hand chops down on every beat,
			// both arms thrust forward on the rhyme.
			float chop = rhyme ? 1.5f : 0.95f;
			to(rightArm, b, -0.7f - chop * pu, 0.15f, 0.08f);
			to(leftArm, b, rhyme ? -0.6f - 1.3f * pu : -0.45f + 0.2f * pu, -0.15f, -0.1f);
		} else if (piglin) {
			// Aggressive slam: the fist comes down on the beat from up high.
			to(rightArm, b, -2.3f + 1.7f * pu, 0f, 0.1f);
			to(leftArm, b, -0.5f, -0.1f, -0.1f);
		} else {
			// Storyteller: one gesture per beat with the right hand, the left holds.
			float[] q = switch (Math.floorMod(k < 0 ? beat : k, 4)) {
				case 0 -> new float[]{-1.4f, 0.25f};
				case 1 -> new float[]{-0.7f, 0f};
				case 2 -> new float[]{-2.2f, 0.15f};
				default -> new float[]{-1.1f, -0.35f};
			};
			to(rightArm, b, q[0] - 0.15f * pu, q[1], 0.05f);
			to(leftArm, b, -0.35f, -0.15f, -0.05f);
		}
	}

	// ------------------------------------------------------------------ four-legged

	public static void quadruped(LivingEntityRenderState s, int id, BattleDance.Mode mode, ModelPart head, ModelPart rightFront,
								 ModelPart leftFront, ModelPart rightHind, ModelPart leftHind) {
		float b = blend(id, mode);
		int beat = ClientBattle.beat();
		float pu = pulse(ClientBattle.beatFrac());
		if (mode == BattleDance.Mode.LISTEN) {
			head.pitch += 0.25f * pu * b;
			return;
		}
		int k = lineBeat();
		boolean rhyme = k == 3;
		// Headbang: down hard on 1 and 3, lighter on 2 and 4, harder on the rhyme.
		float bang = rhyme ? 0.75f : beat % 2 == 0 ? 0.55f : 0.3f;
		head.pitch += bang * pu * b;
		// Stomp: one front leg lifts on each beat, in turn.
		boolean right = beat % 2 == 0;
		rightFront.pitch += (right ? -0.55f * pu : 0f) * b;
		leftFront.pitch += (right ? 0f : -0.55f * pu) * b;
		if (rhyme && punchLine()) {
			// Punchline: rears up on the front legs.
			rightFront.pitch += -0.7f * pu * b;
			leftFront.pitch += -0.7f * pu * b;
		}
	}

	// ------------------------------------------------------------------ villagers, illagers, golems

	public static void simple(LivingEntityRenderState s, int id, BattleDance.Mode mode, ModelPart head, ModelPart arms, ModelPart otherArm) {
		float b = blend(id, mode);
		int beat = ClientBattle.beat();
		float pu = pulse(ClientBattle.beatFrac());
		boolean golem = type(s).equals("iron_golem");
		if (mode == BattleDance.Mode.LISTEN) {
			head.pitch += 0.22f * pu * b;
			return;
		}
		int k = lineBeat();
		boolean rhyme = k == 3;
		head.pitch += (rhyme ? 0.4f : 0.25f) * pu * b;
		head.yaw += (beat % 2 == 0 ? 0.15f : -0.15f) * b;
		if (golem) {
			// Heavy fists, one per beat.
			boolean right = beat % 2 == 0;
			if (arms != null) arms.pitch += (right ? -1.3f * pu : 0f) * b;
			if (otherArm != null) otherArm.pitch += (right ? 0f : -1.3f * pu) * b;
		} else if (otherArm == null) {
			// Villager: folded arms lift and drop with the words, higher on the rhyme.
			if (arms != null) arms.pitch += -(rhyme ? 0.55f : 0.3f) * pu * b;
		} else {
			// Illager: the right hand jabs on the beat, both go up on the rhyme.
			if (arms != null) arms.pitch += -(rhyme ? 1.6f : 0.8f) * pu * b;
			if (otherArm != null) otherArm.pitch += -(rhyme ? 1.6f : 0.2f) * pu * b;
		}
	}
}
