package dev.rapbattle.client;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.entity.feature.FeatureRenderer;
import net.minecraft.client.render.entity.feature.FeatureRendererContext;
import net.minecraft.client.render.entity.model.EntityModel;
import net.minecraft.client.render.entity.state.LivingEntityRenderState;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.registry.Registries;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

/**
 * Bling attached to the model itself: glasses and the snapback ride on the head part, the chain on the body part
 * (or under the head for animals), so everything follows the head, the dance and every animation.
 * Sizes come from the head cuboid, so it fits any model, vanilla or modded.
 */
public class BlingFeature extends FeatureRenderer<LivingEntityRenderState, EntityModel<LivingEntityRenderState>> {
	public BlingFeature(FeatureRendererContext<LivingEntityRenderState, EntityModel<LivingEntityRenderState>> ctx) {
		super(ctx);
	}

	/** A part's transform and its main cuboid, in model pixels. */
	private record Part(Matrix4f pos, Matrix3f normal, ModelPart.Cuboid c) {}

	@Override
	public void render(MatrixStack m, OrderedRenderCommandQueue q, int light, LivingEntityRenderState s, float limbAngle, float limbDistance) {
		if (!ClientConfig.get().bling || s.invisible) return;
		Bling.Kit kit = Bling.kitFor(((RapRenderState) s).rapbattle$entityId());
		if (kit == null) return;
		EntityModel<LivingEntityRenderState> model = getContextModel();
		// The queue renders the model later; pose it now for this entity so the parts are where this entity has them.
		model.setAngles(s);
		RapDancer.after(model, s);
		Part[] found = new Part[4]; // head, real head fallback, body, right arm
		model.getRootPart().forEachCuboid(m, (entry, path, index, cuboid) -> {
			if (index != 0) return;
			String last = lastSegment(path);
			int slot = last.equals("head") ? 0 : last.contains("head") && !last.contains("wear") ? 1 : last.equals("body") ? 2
				: last.equals("right_arm") ? 3 : -1;
			if (slot >= 0 && found[slot] == null)
				found[slot] = new Part(new Matrix4f(entry.getPositionMatrix()), new Matrix3f(entry.getNormalMatrix()), cuboid);
		});
		Part head = found[0] != null ? found[0] : found[1];
		if (head == null) return;
		String type = Registries.ENTITY_TYPE.getId(s.entityType).getPath();
		boolean biped = Bling.isBiped(type) && found[2] != null;
		// Sheep and other furry heads: sit on top of the wool layer.
		float fur = type.equals("sheep") ? 1.0f : 0.4f;

		ModelPart.Cuboid c = head.c();
		float w = c.maxX - c.minX, h = c.maxY - c.minY;
		float cx = (c.minX + c.maxX) / 2, cz = (c.minZ + c.maxZ) / 2;

		if (kit.glasses()) {
			BlockState black = Blocks.BLACK_CONCRETE.getDefaultState();
			float eyeY = c.minY + h * (biped ? 0.55f : 0.45f);
			float z = c.minZ - fur, lens = Math.max(1.6f, w * 0.3f), lensH = Math.max(1.1f, h * 0.17f), off = w * 0.23f;
			draw(m, head, q, light, black, cx - off, eyeY, z, lens, lensH, 0.5f);
			draw(m, head, q, light, black, cx + off, eyeY, z, lens, lensH, 0.5f);
			draw(m, head, q, light, black, cx, eyeY - lensH * 0.3f, z, w * 0.18f, 0.5f, 0.5f);
			// Arms of the glasses along the sides of the head.
			draw(m, head, q, light, black, c.minX - fur * 0.5f, eyeY - lensH * 0.3f, c.minZ + (c.maxZ - c.minZ) * 0.3f, 0.4f, 0.4f, (c.maxZ - c.minZ) * 0.6f);
			draw(m, head, q, light, black, c.maxX + fur * 0.5f, eyeY - lensH * 0.3f, c.minZ + (c.maxZ - c.minZ) * 0.3f, 0.4f, 0.4f, (c.maxZ - c.minZ) * 0.6f);
		}
		if (kit.cigarette()) {
			// A cigarette in the corner of the mouth, pointing forward and a little down: filter, paper, glowing tip.
			float mouthY = c.minY + h * (biped ? 0.8f : 0.72f), mx = cx + w * 0.18f, z0 = c.minZ - fur * 0.5f;
			draw(m, head, q, light, Blocks.ORANGE_TERRACOTTA.getDefaultState(), mx, mouthY, z0 - 0.5f, 0.7f, 0.7f, 1.0f);
			draw(m, head, q, light, Blocks.WHITE_CONCRETE.getDefaultState(), mx, mouthY + 0.25f, z0 - 2.3f, 0.65f, 0.65f, 2.6f);
			int glow = 0xF000F0; // full brightness: the ember glows in the dark
			draw(m, head, q, glow, Blocks.ORANGE_CONCRETE.getDefaultState(), mx, mouthY + 0.45f, z0 - 3.85f, 0.75f, 0.75f, 0.6f);
		}
		if (kit.cap() != null) {
			// Snapback worn backwards: crown on top of the head, visor sticking out at the back.
			float d = c.maxZ - c.minZ, top = c.minY - fur;
			draw(m, head, q, light, kit.cap(), cx, top - 1.0f, cz, w + 2 * fur, 2.0f, d + 2 * fur);
			draw(m, head, q, light, kit.cap(), cx, top + 0.3f, c.maxZ + fur + 1.6f, w * 0.85f, 0.5f, 3.2f);
		}
		int entityId = ((RapRenderState) s).rapbattle$entityId();
		boolean reading = BattleDance.mode(entityId) == BattleDance.Mode.READ;
		if (reading) {
			if (found[3] != null && Bling.isBiped(type)) {
				// The mic in the fist while reading: compact grip inside the fist, <= 2px head beside the chin (to the side of the nose for nosed mobs).
				Part arm = found[3];
				ModelPart.Cuboid a = arm.c();
				boolean nosed = Bling.nosed(entityId);
				float ax = (a.minX + a.maxX) / 2 + (nosed ? -0.85f : -0.35f);
				float ay = a.maxY - 1.2f + (nosed ? 0.5f : 0f);
				float az = (a.minZ + a.maxZ) / 2;
				draw(m, arm, q, light, Blocks.BLACK_CONCRETE.getDefaultState(), ax, ay, az - 1.2f, 1.0f, 1.0f, 3.4f);
				draw(m, arm, q, light, Blocks.GRAY_CONCRETE.getDefaultState(), ax, ay, az - 3.4f, 1.7f, 1.7f, 1.7f);
			} else if (!Bling.isBiped(type)) {
				// Small & quadruped mobs: microphone stand in front of the muzzle, scaled to their height.
				float mouthY = c.minY + h * 0.72f;
				float mz = c.minZ - fur - 2.5f;
				float standH = Math.max(5.5f, Math.min(18f, s.height * 10f));
				draw(m, head, q, light, Blocks.GRAY_CONCRETE.getDefaultState(), cx, mouthY, mz, 1.8f, 1.8f, 1.8f);
				draw(m, head, q, light, Blocks.BLACK_CONCRETE.getDefaultState(), cx, mouthY + 1.0f, mz - 0.6f, 1.0f, 2.0f, 1.0f);
				draw(m, head, q, light, Blocks.IRON_BLOCK.getDefaultState(), cx, mouthY + 1.5f + standH * 0.5f, mz - 0.6f, 0.7f, standH, 0.7f);
				draw(m, head, q, light, Blocks.BLACK_CONCRETE.getDefaultState(), cx, mouthY + 1.5f + standH, mz - 0.6f, 3.2f, 0.7f, 3.2f);
			}
		}
		if (kit.chain() != null) {
			if (biped) {
				// On the chest: links hanging in a V from the collar, pendant at the bottom. Over a chestplate the
				// chain sits on the armour (which is ~1 pixel thicker than the body), not inside it.
				Part body = found[2];
				ModelPart.Cuboid b = body.c();
				boolean plate = s instanceof net.minecraft.client.render.entity.state.BipedEntityRenderState bs
					&& !bs.equippedChestStack.isEmpty();
				float bw = b.maxX - b.minX, bcx = (b.minX + b.maxX) / 2, z = b.minZ - (plate ? 1.55f : 0.5f);
				for (int i = -4; i <= 4; i++) {
					float x = bcx + i * bw * (plate ? 0.11f : 0.1f);
					float y = b.minY + (plate ? 0.2f : 0.6f) + (16 - i * i) * 0.2f;
					draw(m, body, q, light, kit.chain(), x, y, z, 1.1f, 1.1f, 0.8f);
				}
				draw(m, body, q, light, kit.pendant(), bcx, b.minY + 4.4f, z - 0.35f, 1.7f, 1.9f, 0.7f);
			} else {
				// Animals: the chain hangs round the neck, just under the back of the head.
				float y = c.maxY + 0.3f, r = w * 0.5f + fur + 0.4f, zc = c.maxZ - Math.min(3f, (c.maxZ - c.minZ) * 0.35f);
				for (int i = -4; i <= 4; i++) {
					double a = Math.toRadians(i * 20);
					float x = cx + (float) (r * Math.sin(a));
					float z = zc - (float) (r * Math.cos(a));
					float yy = y + (float) Math.cos(a) * 1.0f;
					draw(m, head, q, light, kit.chain(), x, yy, z, 1.2f, 1.2f, 1.2f);
				}
				draw(m, head, q, light, kit.pendant(), cx, y + 2.4f, zc - r - 0.2f, 2.0f, 2.0f, 1.0f);
			}
		}
	}

	private static String lastSegment(String path) {
		String p = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
		int i = p.lastIndexOf('/');
		return i < 0 ? p : p.substring(i + 1);
	}

	/** A block scaled to (sx, sy, sz) model pixels, centred on (x, y, z) in the part's space. */
	private static void draw(MatrixStack m, Part part, OrderedRenderCommandQueue q, int light, BlockState b,
							 float x, float y, float z, float sx, float sy, float sz) {
		m.push();
		m.peek().getPositionMatrix().set(part.pos());
		m.peek().getNormalMatrix().set(part.normal());
		m.scale(1 / 16f, 1 / 16f, 1 / 16f);
		m.translate(x - sx / 2, y - sy / 2, z - sz / 2);
		m.scale(sx, sy, sz);
		q.submitBlock(m, b, light, OverlayTexture.DEFAULT_UV, 0);
		m.pop();
	}
}
