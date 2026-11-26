package dev.rapbattle.client;

import dev.rapbattle.battle.Trophies;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.util.math.MatrixStack;

/**
 * Shared detailed microphone model (used in third person on mobs/players and in first person):
 * tapered black handle with an end cap and a coloured button, a flared metal collar and a rounded silver head.
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
		BlockState grip = Blocks.BLACK_CONCRETE.getDefaultState();
		BlockState cap = Blocks.GRAY_CONCRETE.getDefaultState();
		BlockState ring = Blocks.IRON_BLOCK.getDefaultState();
		BlockState mesh = Blocks.WHITE_CONCRETE.getDefaultState();
		BlockState seam = Blocks.GRAY_CONCRETE.getDefaultState();
		BlockState button = Blocks.RED_CONCRETE.getDefaultState();
		int headLight = light;

		if (perk == Trophies.TrophyPerk.HOMEMADE) {
			button = Blocks.ORANGE_TERRACOTTA.getDefaultState();
		} else if (perk != null) {
			headLight = 0xF000F0;
			button = switch (perk) {
				case HOMEMADE -> Blocks.ORANGE_TERRACOTTA.getDefaultState();
				case WARDEN -> Blocks.CYAN_CONCRETE.getDefaultState();
				case ENDER_DRAGON -> Blocks.PURPLE_CONCRETE.getDefaultState();
				case WITHER -> Blocks.SOUL_SOIL.getDefaultState();
				case ELDER_GUARDIAN -> Blocks.PRISMARINE_BRICKS.getDefaultState();
				case DEFAULT -> Blocks.GOLD_BLOCK.getDefaultState();
			};
		}

		// Handle: black, tapering toward the bottom, with a grey end cap and a full-360° perk-coloured band.
		block(m, q, light, scale, cap, 0f, 0f, 2.55f, 0.75f, 0.75f, 0.5f);
		block(m, q, light, scale, grip, 0f, 0f, 1.2f, 0.85f, 0.85f, 2.3f);
		block(m, q, light, scale, grip, 0f, 0f, -0.9f, 1.05f, 1.05f, 2.0f);
		block(m, q, headLight, scale, button, 0f, 0f, -1.1f, 1.12f, 1.12f, 0.4f);
		// Collar: a flared silver band under the head.
		block(m, q, headLight, scale, ring, 0f, 0f, -2.05f, 1.3f, 1.3f, 0.35f);
		block(m, q, headLight, scale, ring, 0f, 0f, -2.35f, 1.6f, 1.6f, 0.3f);
		// Ball head: a smooth silver body, a narrower dome on top and a darker seam band, so it reads as round.
		block(m, q, headLight, scale, mesh, 0f, 0f, -3.4f, 2.3f, 2.3f, 1.7f);
		block(m, q, headLight, scale, seam, 0f, 0f, -3.4f, 2.42f, 2.42f, 0.3f);
		block(m, q, headLight, scale, mesh, 0f, 0f, -4.45f, 1.7f, 1.7f, 0.45f);
		block(m, q, headLight, scale, mesh, 0f, 0f, -2.65f, 1.85f, 1.85f, 0.25f);
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
