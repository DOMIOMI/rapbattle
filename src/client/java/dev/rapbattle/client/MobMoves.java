package dev.rapbattle.client;

import dev.rapbattle.client.mixin.ModelPartAccessor;
import dev.rapbattle.net.BattleView;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.entity.model.EntityModel;
import net.minecraft.client.render.entity.state.LivingEntityRenderState;
import net.minecraft.registry.Registries;

import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mobs on the mic: a held pose plus one sharp accent on the beat, instead of everything swaying at once.
 * Smoothly blends between poses over 180 ms using the last stored bone angles per entity (never through vanilla's
 * arms-out pose), and includes a universal bone resolver for models without a dedicated mixin.
 */
public final class MobMoves {
	private MobMoves() {}

	private static final long BLEND_MS = 180;
	private record PoseState(BattleDance.Mode mode, long at, Map<ModelPart, float[]> prev, Map<ModelPart, float[]> current) {}
	private static final Map<Integer, PoseState> POSES = new HashMap<>();

	public record Bones(
		ModelPart head, ModelPart leftHead, ModelPart rightHead,
		ModelPart body, ModelPart rightArm, ModelPart leftArm,
		ModelPart rightFrontLeg, ModelPart leftFrontLeg,
		ModelPart rightHindLeg, ModelPart leftHindLeg,
		ModelPart tail, ModelPart jaw, ModelPart rods
	) {}

	private static final Map<Class<?>, Bones> BONE_CACHE = new ConcurrentHashMap<>();

	/** 0 → 1 over the first 8% of a beat, then an exponential settle: a hit, then a hold. */
	static float pulse(float frac) {
		return frac < 0.08f ? frac / 0.08f : (float) Math.exp(-(frac - 0.08f) * 4.5);
	}

	/** How far into the new mode this entity is (0..1), snapshotting the last pose when mode changes. */
	static float blend(int id, BattleDance.Mode mode) {
		long now = System.currentTimeMillis();
		PoseState s = POSES.get(id);
		if (s == null) {
			s = new PoseState(mode, now, new WeakHashMap<>(), new WeakHashMap<>());
			POSES.put(id, s);
			if (POSES.size() > 256) POSES.clear();
		} else if (s.mode() != mode) {
			Map<ModelPart, float[]> snapshot = new WeakHashMap<>(s.current());
			s = new PoseState(mode, now, snapshot, new WeakHashMap<>());
			POSES.put(id, s);
		}
		float t = Math.min(1f, (now - s.at()) / (float) BLEND_MS);
		return t * t * (3 - 2 * t);
	}

	/** Sets a part to {@code target} blended from its last stored pose (never through vanilla's pose). */
	static void to(int id, ModelPart part, float b, float pitch, float yaw, float roll) {
		if (part == null) return;
		PoseState s = POSES.get(id);
		float[] from = s != null ? s.prev().get(part) : null;
		if (from != null && b < 1f) {
			part.pitch = from[0] + (pitch - from[0]) * b;
			part.yaw = from[1] + (yaw - from[1]) * b;
			part.roll = from[2] + (roll - from[2]) * b;
		} else {
			part.pitch = pitch;
			part.yaw = yaw;
			part.roll = roll;
		}
		if (s != null) {
			s.current().put(part, new float[]{part.pitch, part.yaw, part.roll});
		}
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

	// ------------------------------------------------------------------ universal bone resolver

	private static Bones resolveBones(EntityModel<?> model) {
		return BONE_CACHE.computeIfAbsent(model.getClass(), cls -> {
			ModelPart[] found = new ModelPart[13];
			collectBones(model.getRootPart(), found);
			return new Bones(found[0], found[1], found[2], found[3], found[4], found[5],
				found[6], found[7], found[8], found[9], found[10], found[11], found[12]);
		});
	}

	private static void collectBones(ModelPart root, ModelPart[] out) {
		if (root == null) return;
		for (var entry : ((ModelPartAccessor) (Object) root).rapbattle$children().entrySet()) {
			String k = entry.getKey();
			ModelPart p = entry.getValue();
			switch (k) {
				case "head", "center_head" -> { if (out[0] == null) out[0] = p; }
				case "left_head" -> { if (out[1] == null) out[1] = p; }
				case "right_head" -> { if (out[2] == null) out[2] = p; }
				case "body", "torso" -> { if (out[3] == null) out[3] = p; }
				case "right_arm" -> { if (out[4] == null) out[4] = p; }
				case "left_arm" -> { if (out[5] == null) out[5] = p; }
				case "right_front_leg", "front_right_leg" -> { if (out[6] == null) out[6] = p; }
				case "left_front_leg", "front_left_leg" -> { if (out[7] == null) out[7] = p; }
				case "right_hind_leg", "back_right_leg", "right_leg" -> { if (out[8] == null) out[8] = p; }
				case "left_hind_leg", "back_left_leg", "left_leg" -> { if (out[9] == null) out[9] = p; }
				case "tail" -> { if (out[10] == null) out[10] = p; }
				case "jaw", "mouth" -> { if (out[11] == null) out[11] = p; }
				case "rods", "upper_body_parts" -> { if (out[12] == null) out[12] = p; }
				default -> {
					if (out[0] == null && k.contains("head") && !k.contains("hat")) out[0] = p;
				}
			}
			collectBones(p, out);
		}
	}

	/** Universal animation for models without a dedicated mixin (bosses, wolves, foxes, goats, hoglins, etc.). */
	public static void universal(EntityModel<?> model, LivingEntityRenderState s) {
		int id = ((RapRenderState) s).rapbattle$entityId();
		BattleDance.Mode mode = BattleDance.mode(id);
		if (mode == BattleDance.Mode.NONE) return;
		Bones bones = resolveBones(model);
		float b = blend(id, mode);
		int beat = ClientBattle.beat();
		float pu = BattleDance.onBeat() ? pulse(ClientBattle.beatFrac()) : 0f;
		String t = type(s);

		if (mode == BattleDance.Mode.LISTEN) {
			if (bones.head() != null) to(id, bones.head(), b, bones.head().pitch + 0.22f * pu, bones.head().yaw, bones.head().roll);
			float flinch = ClientBattle.mobFlinchImpulse();
			if (flinch > 0) {
				if (bones.head() != null) bones.head().pitch -= 0.35f * flinch;
				to(id, bones.rightArm(), b, -1.2f * flinch, -0.4f * flinch, 0f);
				to(id, bones.leftArm(), b, -1.2f * flinch, 0.4f * flinch, 0f);
			}
			return;
		}
		if (mode != BattleDance.Mode.READ) return;

		int k = lineBeat();
		boolean rhyme = k == 3;
		boolean punch = rhyme && punchLine();

		if (t.equals("warden")) {
			// Heavy nod on strong beats (1 and 3), spreads arms wide on punchline.
			float nod = (beat % 2 == 0 ? 0.45f : 0.18f) * pu;
			if (bones.head() != null) to(id, bones.head(), b, nod, 0f, 0f);
			if (punch) {
				to(id, bones.rightArm(), b, -1.1f, -0.7f, 0.9f * pu);
				to(id, bones.leftArm(), b, -1.1f, 0.7f, -0.9f * pu);
			} else {
				float side = beat % 2 == 0 ? 1f : -1f;
				to(id, bones.rightArm(), b, -0.5f - (side > 0 ? 0.6f * pu : 0f), 0f, 0.15f);
				to(id, bones.leftArm(), b, -0.5f - (side < 0 ? 0.6f * pu : 0f), 0f, -0.15f);
			}
		} else if (t.equals("wither")) {
			// Center head raps; side heads turn toward the player on beats 2 and 4.
			if (bones.head() != null) to(id, bones.head(), b, (rhyme ? 0.42f : 0.26f) * pu, 0f, 0f);
			float look = beat % 2 == 1 ? 0.55f * pu : 0.15f;
			if (bones.leftHead() != null) to(id, bones.leftHead(), b, 0.15f * pu, -look, 0f);
			if (bones.rightHead() != null) to(id, bones.rightHead(), b, 0.15f * pu, look, 0f);
		} else if (t.equals("ender_dragon") || t.equals("guardian") || t.equals("elder_guardian")) {
			if (bones.head() != null) to(id, bones.head(), b, (rhyme ? 0.45f : 0.25f) * pu, (beat % 2 == 0 ? 0.12f : -0.12f), 0f);
			if (bones.jaw() != null) to(id, bones.jaw(), b, 0.35f * pu, 0f, 0f);
			if (bones.tail() != null) to(id, bones.tail(), b, 0f, (beat % 2 == 0 ? 0.25f : -0.25f) * pu, 0f);
		} else if (bones.rightFrontLeg() != null && bones.leftFrontLeg() != null) {
			// Quadrupeds without a dedicated mixin (wolf, fox, goat, hoglin, camel, horse, etc.).
			float bang = rhyme ? 0.65f : beat % 2 == 0 ? 0.48f : 0.25f;
			if (bones.head() != null) to(id, bones.head(), b, bang * pu, 0f, 0f);
			boolean right = beat % 2 == 0;
			to(id, bones.rightFrontLeg(), b, right ? -0.5f * pu : 0f, 0f, 0f);
			to(id, bones.leftFrontLeg(), b, right ? 0f : -0.5f * pu, 0f, 0f);
			if (bones.tail() != null) to(id, bones.tail(), b, 0.2f, (right ? 0.35f : -0.35f) * pu, 0f);
		} else {
			if (bones.head() != null) to(id, bones.head(), b, (rhyme ? 0.4f : 0.24f) * pu, 0f, 0f);
			if (bones.rods() != null) to(id, bones.rods(), b, 0f, 0.3f * pu, 0f);
		}
	}

	// ------------------------------------------------------------------ humanoid mobs

	public static void biped(LivingEntityRenderState s, int id, BattleDance.Mode mode, ModelPart head, ModelPart body,
							 ModelPart rightArm, ModelPart leftArm, ModelPart rightLeg, ModelPart leftLeg) {
		float b = blend(id, mode);
		int beat = ClientBattle.beat();
		float pu = BattleDance.onBeat() ? pulse(ClientBattle.beatFrac()) : 0f;
		String t = type(s);
		boolean zombie = t.contains("zombie") || t.equals("husk") || t.equals("drowned") || t.equals("giant");
		boolean skeleton = t.contains("skeleton") || t.equals("stray") || t.equals("bogged") || t.equals("parched");
		boolean piglin = t.contains("piglin");

		float flinch = ClientBattle.mobFlinchImpulse();
		if (mode == BattleDance.Mode.LISTEN) {
			// Waiting for its turn: relaxed arms (no zombie arms-out), a nod on every beat, weight on one leg.
			if (flinch > 0) {
				head.pitch -= 0.35f * flinch;
				to(id, rightArm, b, -1.35f * flinch, -0.45f * flinch, 0f);
				to(id, leftArm, b, -1.35f * flinch, 0.45f * flinch, 0f);
				to(id, body, b, -0.18f * flinch, 0f, 0f);
				return;
			}
			head.pitch += 0.22f * pu * b;
			to(id, rightArm, b, -0.25f, 0.15f, 0.05f);
			to(id, leftArm, b, -0.25f, -0.15f, -0.05f);
			to(id, body, b, 0f, 0.08f, 0f);
			to(id, rightLeg, b, 0.06f, 0f, 0.04f);
			to(id, leftLeg, b, -0.04f, 0f, -0.02f);
			return;
		}

		int k = lineBeat();
		boolean rhyme = k == 3;
		boolean punch = rhyme && punchLine();
		head.pitch += (rhyme ? 0.45f : 0.3f) * pu * b;
		float side = beat % 2 == 0 ? 1f : -1f;
		to(id, body, b, punch ? 0.18f * pu : 0f, 0.12f * side, 0f);
		to(id, rightLeg, b, side > 0 ? 0.16f * pu : 0f, 0f, 0f);
		to(id, leftLeg, b, side < 0 ? 0.16f * pu : 0f, 0f, 0f);

		if (punch) {
			to(id, rightArm, b, -1.55f, 0.1f, 0f);
			to(id, leftArm, b, -0.3f, -0.2f, -0.1f);
			return;
		}
		if (t.equals("enderman")) {
			float[][] poses = {{-1.6f, -0.2f}, {-0.2f, -1.6f}, {-1.3f, -1.3f}, {-0.1f, -0.1f}};
			float[] q = poses[Math.floorMod(beat, 4)];
			to(id, rightArm, b, q[0], 0f, 0.05f);
			to(id, leftArm, b, q[1], 0f, -0.05f);
			head.yaw += (beat % 2 == 0 ? 0.35f : -0.35f) * b;
		} else if (skeleton) {
			float[][] poses = {{-1.75f, -1.35f}, {-1.60f, -0.35f}, {-1.85f, -1.55f}, {-1.65f, -0.45f}};
			float[] q = poses[Math.floorMod(beat, 4)];
			to(id, rightArm, b, q[0], -0.25f, 0.06f);
			to(id, leftArm, b, q[1], 0f, -0.08f);
			head.roll += (float) Math.sin(ClientBattle.beatFrac() * Math.PI * 10) * 0.1f * pu * b;
		} else if (zombie) {
			float nod = rhyme ? 0.35f : 0.2f;
			to(id, rightArm, b, -1.72f - nod * pu, -0.28f, 0.06f);
			to(id, leftArm, b, rhyme ? -0.6f - 1.3f * pu : -0.45f + 0.2f * pu, -0.15f, -0.1f);
		} else if (piglin) {
			to(id, rightArm, b, -1.85f + 0.35f * pu, -0.25f, 0.08f);
			to(id, leftArm, b, -0.5f, -0.1f, -0.1f);
		} else {
			float[] q = switch (Math.floorMod(k < 0 ? beat : k, 4)) {
				case 0 -> new float[]{-1.75f, -0.25f};
				case 1 -> new float[]{-1.60f, -0.30f};
				case 2 -> new float[]{-1.85f, -0.20f};
				default -> new float[]{-1.65f, -0.35f};
			};
			to(id, rightArm, b, q[0] - 0.12f * pu, q[1], 0.05f);
			to(id, leftArm, b, -0.35f, -0.15f, -0.05f);
		}
	}

	// ------------------------------------------------------------------ four-legged

	public static void quadruped(LivingEntityRenderState s, int id, BattleDance.Mode mode, ModelPart head, ModelPart rightFront,
								 ModelPart leftFront, ModelPart rightHind, ModelPart leftHind) {
		float b = blend(id, mode);
		int beat = ClientBattle.beat();
		float pu = BattleDance.onBeat() ? pulse(ClientBattle.beatFrac()) : 0f;
		if (mode == BattleDance.Mode.LISTEN) {
			head.pitch += 0.25f * pu * b;
			return;
		}
		int k = lineBeat();
		boolean rhyme = k == 3;
		float bang = rhyme ? 0.75f : beat % 2 == 0 ? 0.55f : 0.3f;
		head.pitch += bang * pu * b;
		boolean right = beat % 2 == 0;
		rightFront.pitch += (right ? -0.55f * pu : 0f) * b;
		leftFront.pitch += (right ? 0f : -0.55f * pu) * b;
		if (rhyme && punchLine()) {
			rightFront.pitch += -0.7f * pu * b;
			leftFront.pitch += -0.7f * pu * b;
		}
	}

	// ------------------------------------------------------------------ villagers, illagers, golems

	public static void simple(LivingEntityRenderState s, int id, BattleDance.Mode mode, ModelPart head, ModelPart arms, ModelPart otherArm) {
		float b = blend(id, mode);
		int beat = ClientBattle.beat();
		float pu = BattleDance.onBeat() ? pulse(ClientBattle.beatFrac()) : 0f;
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
			boolean right = beat % 2 == 0;
			if (arms != null) arms.pitch += (right ? -1.3f * pu : 0f) * b;
			if (otherArm != null) otherArm.pitch += (right ? 0f : -1.3f * pu) * b;
		} else if (otherArm == null) {
			if (arms != null) arms.pitch += -(rhyme ? 0.55f : 0.3f) * pu * b;
		} else {
			if (arms != null) arms.pitch += -(rhyme ? 1.6f : 0.8f) * pu * b;
			if (otherArm != null) otherArm.pitch += -(rhyme ? 1.6f : 0.2f) * pu * b;
		}
	}
}
