package dev.rapbattle.client;

import dev.rapbattle.net.BattleView;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.entity.Entity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

import java.util.Random;

/**
 * Stage atmosphere around a battle: a glowing haze wall just behind the crowd ring, and spotlights from above
 * that follow whoever is reading (and sweep the stage between lines). Pure client-side light, no blocks placed.
 */
public final class StageFx {
	private StageFx() {}

	private static final RenderLayer GLOW = RenderLayers.lightning();
	/** Alpha-blended (not additive) colour quads: real fog that hides what is behind it instead of glowing. */
	private static final RenderLayer FOG = RenderLayers.debugQuads();
	private static final int FOG_RGB = 0xD6D2E2;
	private static final int[] BEAM_COLORS = {0xFFF2E6, 0xE070FF, 0x60D8FF, 0xFFD060};
	private static final Random RND = new Random();

	private static final class Ring {
		final int seg;
		final double[] ang;
		final float[] cos, sin;

		Ring(int seg) {
			this.seg = seg;
			this.ang = new double[seg + 1];
			this.cos = new float[seg + 1];
			this.sin = new float[seg + 1];
			for (int i = 0; i <= seg; i++) {
				double a = 2 * Math.PI * i / seg;
				ang[i] = a;
				cos[i] = (float) Math.cos(a);
				sin[i] = (float) Math.sin(a);
			}
		}
	}

	private static final Ring RING_40 = new Ring(40);
	private static final Ring RING_32 = new Ring(32);
	private static final Ring RING_20 = new Ring(20);
	private static final Ring RING_8 = new Ring(8);

	public static void register() {
		WorldRenderEvents.BEFORE_TRANSLUCENT.register(StageFx::render);
		ClientTickEvents.END_CLIENT_TICK.register(StageFx::tick);
	}

	/** The battle I take part in or watch, with a known stage. */
	private static BattleView stage() {
		BattleView v = ClientBattle.view;
		if (v == null || v.stageRadius <= 0 || !ClientConfig.get().stageFx) return null;
		return "LOBBY".equals(v.phase) ? null : v;
	}

	private static float time() { return (System.currentTimeMillis() % 1_000_000L) / 1000f; }

	private static void render(WorldRenderContext ctx) {
		BattleView v = stage();
		MinecraftClient mc = MinecraftClient.getInstance();
		if (v == null || mc.world == null) return;
		Vec3d cam = mc.gameRenderer.getCamera().getCameraPos();
		Matrix4f mat = ctx.matrices().peek().getPositionMatrix();
		VertexConsumerProvider consumers = ctx.consumers();
		VertexConsumer vc = consumers.getBuffer(GLOW);
		float cx = (float) (v.stageX - cam.x), cy = (float) (v.stageY - cam.y), cz = (float) (v.stageZ - cam.z);
		float t = time();
		float beat = "TIMELINE".equals(v.phase) ? Moves.hit() : 0.3f;

		// Fog: several soft walls behind the crowd, each drifting at its own speed, thick at the ground and thinning
		// upwards (two gradient steps, so it does not look like a flat curtain), plus fog creeping along the ground
		// and a light haze on the stage itself.
		VertexConsumer fog = consumers.getBuffer(FOG);
		float r0 = (float) v.stageRadius + 1.6f;
		for (int layer = 0; layer < 3; layer++) {
			float r = r0 + layer * 1.8f;
			float hgt = 3.4f + layer * 0.9f;
			float speed = 0.25f + layer * 0.16f;
			for (int i = 0; i < RING_40.seg; i++) {
				float n0 = billow(RING_40.ang[i], t * speed, layer), n1 = billow(RING_40.ang[i + 1], t * speed, layer);
				float x0 = cx + RING_40.cos[i] * r, z0 = cz + RING_40.sin[i] * r;
				float x1 = cx + RING_40.cos[i + 1] * r, z1 = cz + RING_40.sin[i + 1] * r;
				float bottom = 0.55f - layer * 0.08f, mid = bottom * 0.5f;
				float yMid = cy + hgt * 0.4f;
				quad(fog, mat,
					x0, cy - 0.2f, z0, FOG_RGB, bottom * n0,
					x1, cy - 0.2f, z1, FOG_RGB, bottom * n1,
					x1, yMid, z1, FOG_RGB, mid * n1,
					x0, yMid, z0, FOG_RGB, mid * n0);
				quad(fog, mat,
					x0, yMid, z0, FOG_RGB, mid * n0,
					x1, yMid, z1, FOG_RGB, mid * n1,
					x1, cy + hgt * n1, z1, FOG_RGB, 0,
					x0, cy + hgt * n0, z0, FOG_RGB, 0);
			}
		}
		// Ground fog: flat sheets just above the ground, densest at the crowd and fading towards the stage.
		float inner = (float) v.stageRadius - 1.5f, outer = r0 + 5.5f;
		for (int sheet = 0; sheet < 2; sheet++) {
			float y = cy + 0.12f + sheet * 0.45f;
			float dense = 0.42f - sheet * 0.14f;
			float mr = (float) v.stageRadius + 1.0f;
			for (int i = 0; i < RING_32.seg; i++) {
				float n0 = billow(RING_32.ang[i], t * 0.2f, sheet + 7), n1 = billow(RING_32.ang[i + 1], t * 0.2f, sheet + 7);
				float c0 = RING_32.cos[i], s0 = RING_32.sin[i], c1 = RING_32.cos[i + 1], s1 = RING_32.sin[i + 1];
				// inner edge (clear) -> crowd ring (dense)
				quad(fog, mat,
					cx + c0 * inner, y, cz + s0 * inner, FOG_RGB, 0,
					cx + c1 * inner, y, cz + s1 * inner, FOG_RGB, 0,
					cx + c1 * mr, y, cz + s1 * mr, FOG_RGB, dense * n1,
					cx + c0 * mr, y, cz + s0 * mr, FOG_RGB, dense * n0);
				// crowd ring (dense) -> outside (fades out)
				quad(fog, mat,
					cx + c0 * mr, y, cz + s0 * mr, FOG_RGB, dense * n0,
					cx + c1 * mr, y, cz + s1 * mr, FOG_RGB, dense * n1,
					cx + c1 * outer, y, cz + s1 * outer, FOG_RGB, 0,
					cx + c0 * outer, y, cz + s0 * outer, FOG_RGB, 0);
			}
		}
		// A light haze lying on the stage.
		{
			float rr = (float) v.stageRadius;
			for (int i = 0; i < RING_20.seg; i++) {
				quad(fog, mat,
					cx, cy + 0.08f, cz, FOG_RGB, 0.14f,
					cx, cy + 0.08f, cz, FOG_RGB, 0.14f,
					cx + RING_20.cos[i + 1] * rr, cy + 0.08f, cz + RING_20.sin[i + 1] * rr, FOG_RGB, 0.1f,
					cx + RING_20.cos[i] * rr, cy + 0.08f, cz + RING_20.sin[i] * rr, FOG_RGB, 0.1f);
			}
		}
		if (consumers instanceof VertexConsumerProvider.Immediate imm) imm.draw(FOG);
		vc = consumers.getBuffer(GLOW);
		// The lights catch the haze: a faint glow at the foot of the nearest fog wall, pulsing with the beat.
		for (int i = 0; i < RING_40.seg; i++) {
			float x0 = cx + RING_40.cos[i] * r0, z0 = cz + RING_40.sin[i] * r0;
			float x1 = cx + RING_40.cos[i + 1] * r0, z1 = cz + RING_40.sin[i + 1] * r0;
			float al = 0.08f + 0.06f * beat;
			quad(vc, mat, x0, cy - 0.1f, z0, 0xB8A8FF, al, x1, cy - 0.1f, z1, 0xB8A8FF, al,
				x1, cy + 2.2f, z1, 0xB8A8FF, 0, x0, cy + 2.2f, z0, 0xB8A8FF, 0);
		}

		// Spotlights: four beams from high above the ring onto the reader (or sweeping the stage).
		Entity reader = mc.world.getEntityById(ClientBattle.readerId());
		for (int i = 0; i < 4; i++) {
			double a = Math.PI / 4 + i * Math.PI / 2;
			float sx = cx + (float) Math.cos(a) * (r0 + 1), sz = cz + (float) Math.sin(a) * (r0 + 1), sy = cy + 11;
			float tx, ty, tz;
			if (reader != null && "TIMELINE".equals(v.phase)) {
				tx = (float) (reader.getX() - cam.x) + (float) Math.sin(t * 1.3 + i) * 0.35f;
				ty = (float) (reader.getY() - cam.y);
				tz = (float) (reader.getZ() - cam.z) + (float) Math.cos(t * 1.1 + i) * 0.35f;
			} else {
				tx = cx + (float) Math.sin(t * 0.6 + i * 1.7) * 3f;
				ty = cy;
				tz = cz + (float) Math.cos(t * 0.5 + i * 2.3) * 3f;
			}
			int col = BEAM_COLORS[i];
			float strength = 0.75f + 0.25f * beat;
			beam(vc, mat, sx, sy, sz, tx, ty, tz, col, 0.24f * strength, 0.05f * strength);
			pool(vc, mat, tx, ty + 0.03f, tz, 1.5f, col, 0.30f * strength);
		}
		if (consumers instanceof VertexConsumerProvider.Immediate imm) imm.draw(GLOW);
	}

	/** Slow fog density 0.35..1 around the ring: a few drifting sine waves, so it billows instead of pulsing. */
	private static float billow(double a, float t, int layer) {
		double v = Math.sin(a * 3 + t + layer * 1.7) * 0.5 + Math.sin(a * 7 - t * 1.3 + layer) * 0.3 + Math.sin(a * 13 + t * 0.7 + layer * 2.3) * 0.2;
		return (float) (0.675 + 0.325 * v);
	}

	/** A light cone as eight long quads from a thin top to a wide bottom. */
	private static void beam(VertexConsumer vc, Matrix4f m, float sx, float sy, float sz, float tx, float ty, float tz,
							 int col, float topAlpha, float bottomAlpha) {
		float r0 = 0.25f, r1 = 1.3f;
		for (int i = 0; i < RING_8.seg; i++) {
			float c0 = RING_8.cos[i], s0 = RING_8.sin[i], c1 = RING_8.cos[i + 1], s1 = RING_8.sin[i + 1];
			quad(vc, m,
				sx + c0 * r0, sy, sz + s0 * r0, col, topAlpha,
				sx + c1 * r0, sy, sz + s1 * r0, col, topAlpha,
				tx + c1 * r1, ty, tz + s1 * r1, col, bottomAlpha,
				tx + c0 * r1, ty, tz + s0 * r1, col, bottomAlpha);
		}
	}

	/** The lit spot on the ground under a beam. */
	private static void pool(VertexConsumer vc, Matrix4f m, float x, float y, float z, float r, int col, float alpha) {
		for (int i = 0; i < RING_20.seg; i++) {
			quad(vc, m,
				x, y, z, col, alpha,
				x, y, z, col, alpha,
				x + RING_20.cos[i + 1] * r, y, z + RING_20.sin[i + 1] * r, col, 0,
				x + RING_20.cos[i] * r, y, z + RING_20.sin[i] * r, col, 0);
		}
	}

	/** One quad, both faces (the glow layer may cull back faces). */
	private static void quad(VertexConsumer vc, Matrix4f m,
							 float x0, float y0, float z0, int c0, float a0,
							 float x1, float y1, float z1, int c1, float a1,
							 float x2, float y2, float z2, int c2, float a2,
							 float x3, float y3, float z3, int c3, float a3) {
		v(vc, m, x0, y0, z0, c0, a0); v(vc, m, x1, y1, z1, c1, a1); v(vc, m, x2, y2, z2, c2, a2); v(vc, m, x3, y3, z3, c3, a3);
		v(vc, m, x3, y3, z3, c3, a3); v(vc, m, x2, y2, z2, c2, a2); v(vc, m, x1, y1, z1, c1, a1); v(vc, m, x0, y0, z0, c0, a0);
	}

	private static void v(VertexConsumer vc, Matrix4f m, float x, float y, float z, int rgb, float a) {
		vc.vertex(m, x, y, z).color((rgb >> 16) & 255, (rgb >> 8) & 255, rgb & 255, (int) (Math.max(0, Math.min(1, a)) * 255));
	}

	/** Drifting smoke and dust in the haze, a little every tick. */
	private static void tick(MinecraftClient mc) {
		BattleView v = stage();
		if (v == null || mc.world == null || mc.isPaused()) return;
		double r0 = v.stageRadius + 2.5;
		for (int i = 0; i < 9; i++) {
			double a = RND.nextDouble() * 2 * Math.PI, r = r0 + RND.nextDouble() * 2.5;
			double x = v.stageX + Math.cos(a) * r, z = v.stageZ + Math.sin(a) * r;
			mc.world.addParticleClient(ParticleTypes.WHITE_SMOKE, x, v.stageY + 0.2 + RND.nextDouble() * 1.2, z,
				(RND.nextDouble() - 0.5) * 0.02, 0.005, (RND.nextDouble() - 0.5) * 0.02);
		}
		if (RND.nextInt(3) == 0) {
			double a = RND.nextDouble() * 2 * Math.PI, r = RND.nextDouble() * r0;
			mc.world.addParticleClient(ParticleTypes.WHITE_ASH, v.stageX + Math.cos(a) * r, v.stageY + 1 + RND.nextDouble() * 4,
				v.stageZ + Math.sin(a) * r, 0, 0, 0);
		}
	}
}
