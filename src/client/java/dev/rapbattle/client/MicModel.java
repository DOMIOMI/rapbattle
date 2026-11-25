package dev.rapbattle.client;

import dev.rapbattle.battle.Trophies;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.util.math.MatrixStack;

/**
 * Shared detailed microphone model (used in third person on mobs/players and in first person):
 * tapered 2-part dark handle, coloured button, bright metallic collar ring, and a 3-cube rounded light-grey mesh head.
 */
public final class MicModel {
	private MicModel() {}

	/**
	 * Renders the microphone in model-pixel coordinates centered at {@code (0, 0, 0)} with the handle along {@code +Z}
	 * (toward the palm) and the mesh head along {@code -Z} (toward the mouth).
	 *
	 * @param scale 1/16f for entity model pixels, or ~0.045f for first-person view.
	 */
	public static void render(MatrixStack m, OrderedRenderCommandQueue q, int light, float scale, Trophies.TrophyPerk perk) {
		BlockState lowerGrip = Blocks.BLACK_CONCRETE.getDefaultState();
		BlockState upperGrip = Blocks.GRAY_TERRACOTTA.getDefaultState();
		BlockState ring = Blocks.IRON_BLOCK.getDefaultState();
		BlockState mesh = Blocks.LIGHT_GRAY_CONCRETE.getDefaultState();
		BlockState button = Blocks.RED_CONCRETE.getDefaultState();
		int headLight = light;

		if (perk != null) {
			headLight = 0xF000F0;
			button = switch (perk) {
				case WARDEN -> Blocks.CYAN_CONCRETE.getDefaultState();
				case ENDER_DRAGON -> Blocks.PURPLE_CONCRETE.getDefaultState();
				case WITHER -> Blocks.SOUL_SOIL.getDefaultState();
				case ELDER_GUARDIAN -> Blocks.PRISMARINE_BRICKS.getDefaultState();
				case DEFAULT -> Blocks.GOLD_BLOCK.getDefaultState();
			};
		}

		// Lower tapered grip (narrower)
		block(m, q, light, scale, lowerGrip, 0f, 0f, -0.35f, 0.75f, 0.75f, 1.7f);
		// Upper grip (slightly wider) + power button
		block(m, q, light, scale, upperGrip, 0f, 0f, -1.85f, 0.95f, 0.95f, 1.45f);
		block(m, q, headLight, scale, button, 0f, -0.52f, -1.75f, 0.38f, 0.25f, 0.48f);
		// Bright metallic ring collar
		block(m, q, headLight, scale, ring, 0f, 0f, -2.78f, 1.25f, 1.25f, 0.45f);
		// Light-grey rounded mesh head (3 intersecting cubes, wider than handle, <= 2px)
		block(m, q, headLight, scale, mesh, 0f, 0f, -3.75f, 1.65f, 1.65f, 1.65f);
		block(m, q, headLight, scale, mesh, 0f, 0f, -3.75f, 1.95f, 1.35f, 1.35f);
		block(m, q, headLight, scale, mesh, 0f, 0f, -3.75f, 1.35f, 1.95f, 1.35f);
	}

	private static void block(MatrixStack m, OrderedRenderCommandQueue q, int light, float scale, BlockState b,
							  float x, float y, float z, float sx, float sy, float sz) {
		m.push();
		m.scale(scale, scale, scale);
		m.translate(x - sx / 2f, y - sy / 2f, z - sz / 2f);
		m.scale(sx, sy, sz);
		q.submitBlock(m, b, light, OverlayTexture.DEFAULT_UV, 0);
		m.pop();
	}
}
