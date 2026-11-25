package dev.rapbattle.client;

import dev.rapbattle.net.BattleView;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.entity.state.LivingEntityRenderState;
import net.minecraft.registry.Registries;

/**
 * Keyframe dance engine. A move is a few key poses per bar (one value per beat); the engine eases between them in
 * time with the beat, so every limb lands on the beat. Moves differ per model family and per mob species:
 * rappers spit with a mic and a free hand, zombies sway their arms, skeletons rattle, endermen do a stiff robot,
 * four-legged mobs headbang and stomp, and the winners dance (fist pump, dab, flex, making it rain).
 */
public final class Moves {
	private Moves() {}

	/** Value at bar position {@code p} (0..n) easing between keyframes {@code k[0..n-1]}, looping. */
	static float kf(float p, float... k) {
		int n = k.length;
		float x = ((p % n) + n) % n;
		int i = (int) Math.floor(x);
		float f = x - i;
		float e = (1 - (float) Math.cos(Math.PI * f)) / 2; // ease in-out
		return k[i] + (k[(i + 1) % n] - k[i]) * e;
	}

	/** Snappy keyframes (robot moves): jumps to each key on the beat with a short ease. */
	static float snap(float p, float... k) {
		int n = k.length;
		float x = ((p % n) + n) % n;
		int i = (int) Math.floor(x);
		float f = Math.min(1, (x - i) * 5);
		return k[(i + n - 1) % n] + (k[i] - k[(i + n - 1) % n]) * f;
	}

	/** Position in beats inside the battle (for READ / LISTEN). */
	static float beatPos() { return ClientBattle.beat() + ClientBattle.beatFrac(); }

	/** Free-running beat for the result screen (110 bpm). */
	static float freePos() { return (System.currentTimeMillis() % 1_000_000L) / 545f; }

	static float hit() {
		float f = ClientBattle.beatFrac();
		return (1 - f) * (1 - f);
	}

	private static String type(LivingEntityRenderState s) { return Registries.ENTITY_TYPE.getId(s.entityType).getPath(); }

	private static int id(LivingEntityRenderState s) { return ((RapRenderState) s).rapbattle$entityId(); }

	/** A teammate (not me, not the mob) just landed a rhyme: arms up for a moment. */
	private static boolean teammateHit(int self) {
		BattleView.Row r = ClientBattle.liveRow();
		if (r == null || r.mob || r.readerEntityId == self) return false;
		int k = ClientBattle.beat() - r.startBeat;
		return k == 3;
	}

	// ------------------------------------------------------------------ humanoids and players

	public static void biped(LivingEntityRenderState s, ModelPart head, ModelPart body, ModelPart rightArm, ModelPart leftArm,
							 ModelPart rightLeg, ModelPart leftLeg, boolean player) {
		int id = id(s);
		BattleDance.Mode mode = BattleDance.mode(id);
		if (mode == BattleDance.Mode.NONE) return;
		// Players dance through Player Animation Library; these code-made poses are only the fallback.
		if (player && PlayerMoves.active(id)) return;
		if (!player && (mode == BattleDance.Mode.READ || mode == BattleDance.Mode.LISTEN)) {
			MobMoves.biped(s, id, mode, head, body, rightArm, leftArm, rightLeg, leftLeg);
			return;
		}
		String t = type(s);
		float p = beatPos(), fp = freePos(), hit = hit();
		switch (mode) {
			case READ -> {
				head.pitch += 0.2f * hit;
				head.yaw += kf(p, 0.15f, -0.1f, 0.12f, -0.15f);
				body.yaw = kf(p, 0.12f, -0.08f, 0.1f, -0.12f);
				rightLeg.pitch = kf(p, 0.18f, -0.08f, 0.18f, -0.08f);
				leftLeg.pitch = kf(p, -0.08f, 0.18f, -0.08f, 0.18f);
				if (player) {
					// Mic to the mouth, free hand tells the story: point, rise, chop, up high.
					rightArm.pitch = -1.95f + 0.08f * hit;
					rightArm.yaw = -0.45f;
					rightArm.roll = 0;
					leftArm.pitch = kf(p, -0.5f, -1.7f, -0.9f, -2.5f);
					leftArm.yaw = kf(p, 0.2f, 0.55f, -0.15f, 0.3f);
					leftArm.roll = kf(p, 0f, -0.35f, 0f, -0.2f);
				} else if (t.equals("enderman")) {
					head.yaw = snap(p, 0.5f, -0.5f, 0.3f, -0.3f);
					rightArm.pitch = snap(p, -1.6f, -0.2f, -1.6f, -0.2f);
					leftArm.pitch = snap(p, -0.2f, -1.6f, -0.2f, -1.6f);
					body.yaw = snap(p, 0.2f, -0.2f, 0.2f, -0.2f);
				} else if (t.contains("skeleton") || t.equals("stray") || t.equals("bogged") || t.equals("parched")) {
					head.roll = (float) Math.sin(p * Math.PI * 4) * 0.12f; // rattling
					rightArm.pitch = kf(p, -1.4f, -0.5f, -1.4f, -2.2f);
					leftArm.pitch = kf(p, -0.4f, -1.5f, -0.4f, -1.2f);
				} else if (t.contains("zombie") || t.equals("husk") || t.equals("drowned") || t.equals("giant")) {
					rightArm.pitch = kf(p, -1.5f, -1.9f, -1.4f, -2.1f);
					leftArm.pitch = kf(p, -1.9f, -1.5f, -2.1f, -1.4f);
					body.roll = kf(p, 0.08f, -0.08f, 0.08f, -0.08f);
				} else {
					rightArm.pitch = kf(p, -0.4f, -1.6f, -0.6f, -2.0f);
					leftArm.pitch = kf(p, -1.6f, -0.4f, -2.0f, -0.6f);
				}
			}
			case LISTEN -> {
				head.pitch += 0.3f * hit;
				body.yaw = kf(p, 0.05f, -0.05f);
				if (player) {
					if (teammateHit(id)) {
						rightArm.pitch = -2.9f;
						leftArm.pitch = -2.9f;
						rightArm.roll = 0.3f;
						leftArm.roll = -0.3f;
					} else {
						// Arms crossed, nodding.
						rightArm.pitch = -0.95f;
						rightArm.yaw = -0.5f;
						leftArm.pitch = -0.95f;
						leftArm.yaw = 0.5f;
					}
				} else {
					rightArm.roll += kf(p, 0.12f, -0.12f);
					leftArm.roll += kf(p, 0.12f, -0.12f);
				}
			}
			case WIN -> {
				int style = Math.floorMod(id * 31 + 7, 4);
				if (player && id == selfId()) style = ClientConfig.get().victoryDance % 4;
				rightLeg.pitch = kf(fp, 0.2f, -0.2f);
				leftLeg.pitch = kf(fp, -0.2f, 0.2f);
				switch (style) {
					case 0 -> { // fist pump
						rightArm.pitch = kf(fp * 2, -2.95f, -2.2f);
						rightArm.roll = 0.1f;
						leftArm.roll = -0.5f;
						head.pitch = -0.25f;
					}
					case 1 -> { // dab, switching sides
						boolean left = ((int) Math.floor(fp / 2)) % 2 == 0;
						head.pitch = 0.55f;
						head.yaw = left ? -0.6f : 0.6f;
						(left ? rightArm : leftArm).pitch = -1.9f;
						(left ? rightArm : leftArm).yaw = left ? -0.9f : 0.9f;
						(left ? leftArm : rightArm).pitch = -2.3f;
						(left ? leftArm : rightArm).yaw = left ? 0.45f : -0.45f;
					}
					case 2 -> { // flex
						rightArm.pitch = -2.4f;
						rightArm.roll = 0.9f + 0.25f * kf(fp, 1f, -1f);
						leftArm.pitch = -2.4f;
						leftArm.roll = -0.9f - 0.25f * kf(fp, 1f, -1f);
						head.pitch = -0.15f;
						body.yaw = kf(fp, 0.2f, -0.2f);
					}
					default -> { // making it rain
						leftArm.pitch = -1.0f;
						leftArm.yaw = 0.3f;
						rightArm.pitch = kf(fp * 2, -1.2f, -2.6f);
						rightArm.yaw = kf(fp * 2, -0.2f, 0.4f);
						head.pitch = -0.2f;
					}
				}
			}
			case CROWD -> {
				int style = BattleDance.crowdStyle(id);
				float ch = BattleDance.crowdHit(id), hype = BattleDance.crowdHype();
				float q = (BattleDance.onBeat() ? p : fp) + (id % 4) * 0.25f;
				head.pitch += 0.35f * ch;
				body.yaw = kf(q / 2, 0.12f, -0.12f);
				rightLeg.pitch = 0.12f * ch;
				switch (style) {
					case 0 -> { // fist pump on the beat
						rightArm.pitch = hype > 0.6f ? kf(q, -2.8f, -2.0f) : -0.4f - 0.5f * ch;
						rightArm.roll = 0.15f;
						leftArm.pitch = -0.25f;
					}
					case 1 -> { // both hands up, waving side to side
						float up = -2.4f - 0.4f * hype;
						rightArm.pitch = up;
						leftArm.pitch = up;
						rightArm.roll = kf(q / 2, 0.45f, -0.1f);
						leftArm.roll = kf(q / 2, 0.1f, -0.45f);
					}
					default -> { // hands at the sides, clapping on the beat when it gets hot
						if (hype > 0.5f) {
							rightArm.pitch = -1.2f;
							leftArm.pitch = -1.2f;
							rightArm.yaw = -0.35f + 0.35f * ch;
							leftArm.yaw = 0.35f - 0.35f * ch;
						} else {
							rightArm.pitch = kf(q, 0.2f, -0.2f);
							leftArm.pitch = kf(q, -0.2f, 0.2f);
						}
					}
				}
			}
			case LOSE -> {
				head.pitch = 0.75f;
				head.yaw = kf(fp / 2, 0.25f, -0.25f); // shaking the head slowly
				body.pitch = 0.15f;
				rightArm.pitch = 0.15f;
				leftArm.pitch = 0.15f;
				rightArm.roll = 0.05f;
				leftArm.roll = -0.05f;
			}
			default -> {}
		}
	}

	// ------------------------------------------------------------------ four-legged

	public static void quadruped(LivingEntityRenderState s, ModelPart head, ModelPart rightFront, ModelPart leftFront,
								 ModelPart rightHind, ModelPart leftHind) {
		BattleDance.Mode mode = BattleDance.mode(id(s));
		if (mode == BattleDance.Mode.NONE) return;
		if (mode == BattleDance.Mode.READ || mode == BattleDance.Mode.LISTEN) {
			MobMoves.quadruped(s, id(s), mode, head, rightFront, leftFront, rightHind, leftHind);
			return;
		}
		float p = beatPos(), fp = freePos(), hit = hit();
		switch (mode) {
			case READ -> {
				head.pitch += kf(p, 0.6f, -0.15f, 0.65f, -0.25f); // headbang
				head.roll = kf(p, 0.1f, -0.1f, 0.1f, -0.1f);
				rightFront.pitch = kf(p, -0.6f, 0f, -0.6f, 0f);
				leftFront.pitch = kf(p, 0f, -0.6f, 0f, -0.6f);
				rightHind.pitch = kf(p, 0.15f, -0.1f);
				leftHind.pitch = kf(p, -0.1f, 0.15f);
			}
			case LISTEN -> {
				head.pitch += 0.32f * hit;
				rightFront.pitch = 0.1f * hit;
			}
			case WIN -> { // happy hop on the front legs
				head.pitch = -0.35f;
				float up = kf(fp * 2, -0.9f, 0f);
				rightFront.pitch = up;
				leftFront.pitch = up;
			}
			case CROWD -> {
				int cid = id(s);
				float ch = BattleDance.crowdHit(cid), hype = BattleDance.crowdHype();
				float q = (BattleDance.onBeat() ? p : fp) + (cid % 4) * 0.25f;
				head.pitch += (0.25f + 0.3f * hype) * ch;
				head.roll = kf(q / 2, 0.12f, -0.12f) * hype;
				if (BattleDance.crowdStyle(cid) != 2) { // stomping along
					rightFront.pitch = kf(q, -0.45f, 0f) * hype;
					leftFront.pitch = kf(q, 0f, -0.45f) * hype;
				}
			}
			case LOSE -> {
				head.pitch = 0.95f;
				rightFront.pitch = -0.35f;
				leftFront.pitch = -0.35f;
			}
			default -> {}
		}
	}

	// ------------------------------------------------------------------ villagers, illagers, golems

	public static void simple(LivingEntityRenderState s, ModelPart head, ModelPart arms, ModelPart otherArm) {
		BattleDance.Mode mode = BattleDance.mode(id(s));
		if (mode == BattleDance.Mode.NONE) return;
		if (mode == BattleDance.Mode.READ || mode == BattleDance.Mode.LISTEN) {
			MobMoves.simple(s, id(s), mode, head, arms, otherArm);
			return;
		}
		boolean golem = type(s).equals("iron_golem");
		float p = beatPos(), fp = freePos(), hit = hit();
		switch (mode) {
			case READ -> {
				head.pitch += 0.35f * hit;
				head.yaw += kf(p, 0.2f, -0.2f);
				if (arms != null) arms.pitch += golem ? kf(p, -1.9f, -0.2f, -1.6f, -0.2f) : kf(p, -0.1f, -0.6f, -0.1f, -0.8f);
				if (otherArm != null) otherArm.pitch += golem ? kf(p, -0.2f, -1.9f, -0.2f, -1.6f) : kf(p, -0.6f, -0.1f, -0.8f, -0.1f);
			}
			case LISTEN -> head.pitch += 0.28f * hit;
			case CROWD -> {
				int cid = id(s);
				float ch = BattleDance.crowdHit(cid), hype = BattleDance.crowdHype();
				float q = (BattleDance.onBeat() ? p : fp) + (cid % 4) * 0.25f;
				head.pitch += 0.3f * ch;
				head.yaw += kf(q / 2, 0.2f, -0.2f) * hype;
				if (arms != null) arms.pitch += golem ? kf(q, -0.9f, -0.2f) * hype : -0.35f * ch * hype;
				if (otherArm != null) otherArm.pitch += golem ? kf(q, -0.2f, -0.9f) * hype : kf(q, -1.6f, -0.3f) * hype;
			}
			case WIN -> {
				head.pitch = -0.3f;
				if (arms != null) arms.pitch += kf(fp * 2, -2.2f, -1.2f);
				if (otherArm != null) otherArm.pitch += kf(fp * 2, -1.2f, -2.2f);
			}
			case LOSE -> {
				head.pitch = 0.7f;
				head.yaw = kf(fp / 2, 0.2f, -0.2f);
			}
			default -> {}
		}
	}

	private static int selfId() {
		var mc = net.minecraft.client.MinecraftClient.getInstance();
		return mc.player == null ? -1 : mc.player.getId();
	}
}
