package dev.rapbattle.client;

import dev.rapbattle.net.BattleView;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * The intro fly-over before the first round, UFC style: wide shot from above, past the mob's side to a close-up
 * of the mob, then the players' faces, then both sides in one frame. Computed on each client from positions,
 * nothing extra goes over the network. Space skips it (only for you).
 */
public final class Intro {
	private Intro() {}

	private static final long DURATION = 6500;
	private static long startedAt;
	private static int battleKey = -1;
	private static boolean skipped;

	/** Called when a battle state arrives: the intro starts with the first PREP of a battle. */
	static void onState(BattleView v) {
		if (v.spectator || !ClientConfig.get().intro) return;
		int key = v.id;
		if ("PREP".equals(v.phase) && v.round == 1 && battleKey != key) {
			battleKey = key;
			startedAt = System.currentTimeMillis();
			skipped = false;
		}
	}

	public static void skip() { skipped = true; }

	/** 0..1 progress, or -1 when no intro is running. */
	public static float progress() {
		BattleView v = ClientBattle.view;
		if (v == null || skipped || v.id != battleKey) return -1;
		long t = System.currentTimeMillis() - startedAt;
		if (t < 0 || t > DURATION) return -1;
		return t / (float) DURATION;
	}

	public static boolean active() { return progress() >= 0; }

	public record Shot(Vec3d pos, float yaw, float pitch) {}

	/** Fixed camera for automated screenshots (null in normal play). */
	public static Shot debug;

	/** Where the camera is at this moment of the intro. */
	public static Shot shot(float tickDelta) {
		if (debug != null) return debug;
		float p = progress();
		MinecraftClient mc = MinecraftClient.getInstance();
		BattleView v = ClientBattle.view;
		if (p < 0 || mc.world == null || mc.player == null || v == null) return null;
		Entity mob = mc.world.getEntityById(v.pvp ? opponentId(v) : v.mobEntityId);
		if (mob == null) return null;
		Vec3d m = mob.getLerpedPos(tickDelta);
		Vec3d pl = mc.player.getLerpedPos(tickDelta);
		Vec3d c = m.add(pl).multiply(0.5);
		Vec3d axis = m.subtract(pl).multiply(1, 0, 1);
		if (axis.lengthSquared() < 1e-4) axis = new Vec3d(1, 0, 0);
		axis = axis.normalize();
		Vec3d side = new Vec3d(-axis.z, 0, axis.x);
		double mobEye = mob.getHeight() * 0.85;

		// Keyframes: (camera position, point it looks at).
		Vec3d[] pos = {
			c.add(side.multiply(9)).add(0, 8, 0),                         // wide from above
			m.add(axis.multiply(-2.0)).add(side.multiply(3)).add(0, mobEye + 0.3, 0), // sweeping past the mob's side
			m.add(axis.multiply(-2.6)).add(0, mobEye, 0),                 // mob close-up, face on
			pl.add(axis.multiply(2.6)).add(0, 1.6, 0),                    // player close-up, face on
			c.add(side.multiply(6)).add(0, 2.2, 0),                       // both sides in one frame
		};
		Vec3d[] look = {c, m.add(0, mobEye, 0), m.add(0, mobEye, 0), pl.add(0, 1.6, 0), c.add(0, 1, 0)};
		float[] at = {0f, 0.22f, 0.4f, 0.68f, 1f};

		int i = 0;
		while (i < at.length - 2 && p > at[i + 1]) i++;
		float local = (p - at[i]) / (at[i + 1] - at[i]);
		float s = local * local * (3 - 2 * local); // smoothstep
		Vec3d camPos = pos[i].lerp(pos[i + 1], s);
		Vec3d target = look[i].lerp(look[i + 1], s);
		Vec3d d = target.subtract(camPos);
		float yaw = (float) (MathHelper.atan2(d.z, d.x) * 180 / Math.PI) - 90f;
		float pitch = (float) -(MathHelper.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)) * 180 / Math.PI);
		return new Shot(camPos, yaw, pitch);
	}

	/** Player battles: the other player is the one in the red corner. */
	static int opponentId(BattleView v) {
		MinecraftClient mc = MinecraftClient.getInstance();
		int me = mc.player == null ? -1 : mc.player.getId();
		for (BattleView.Member m : v.team) if (m.entityId != me) return m.entityId;
		return -1;
	}

	/** Card windows in intro progress: the red corner while the camera is on the mob, then the team. */
	static final float MOB_FROM = 0.24f, MOB_TO = 0.56f, TEAM_FROM = 0.6f, TEAM_TO = 0.92f;

	/** Which fighter card to show now: "mob", "team", or null. */
	public static String card() {
		float p = progress();
		if (p < 0) return null;
		if (p > MOB_FROM && p < MOB_TO) return "mob";
		if (p >= TEAM_FROM && p < TEAM_TO) return "team";
		return null;
	}

	/** Milliseconds since the intro started (for the card animations), or -1. */
	static float elapsedMs() {
		float p = progress();
		return p < 0 ? -1 : p * DURATION;
	}

	static float msOf(float progress) { return progress * DURATION; }
}
