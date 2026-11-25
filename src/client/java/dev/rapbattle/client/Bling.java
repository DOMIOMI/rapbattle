package dev.rapbattle.client;

import dev.rapbattle.net.BattleView;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.entity.state.LivingEntityRenderState;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.DyeColor;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.RotationAxis;

import java.util.Set;

/**
 * Rapper bling on the models during a battle: chains (iron → gold → diamond by rank / record), dark glasses and a
 * backwards snapback in the crew colour. Built from tiny vanilla blocks, so no new items or textures are needed.
 */
public final class Bling {
	private Bling() {}

	private static final Set<String> BIPEDS = Set.of("player", "zombie", "husk", "drowned", "zombie_villager", "skeleton", "stray",
		"bogged", "parched", "wither_skeleton", "piglin", "piglin_brute", "zombified_piglin", "villager", "wandering_trader",
		"pillager", "vindicator", "evoker", "illusioner", "witch", "iron_golem", "enderman", "creaking", "warden", "snow_golem",
		"giant", "copper_golem");
	private static final Set<String> QUADS = Set.of("sheep", "cow", "mooshroom", "pig", "wolf", "fox", "cat", "ocelot", "horse",
		"donkey", "mule", "llama", "trader_llama", "goat", "polar_bear", "hoglin", "zoglin", "panda", "camel", "camel_husk",
		"skeleton_horse", "zombie_horse", "sniffer", "armadillo", "turtle", "frog", "rabbit", "chicken", "ravager", "strider");

	record Kit(BlockState chain, BlockState pendant, boolean glasses, BlockState cap, boolean cigarette) {}

	/** Does this entity have a cigarette right now (the mob on stage, and about every third fan in the crowd). */
	static boolean smokes(int id) {
		BattleView v = ClientBattle.view;
		if (v == null || id < 0 || "LOBBY".equals(v.phase) || !ClientConfig.get().bling) return false;
		if (v.mobEntityIds.contains(id)) return true;
		return v.crowdIds.contains(id) && Math.floorMod(id * 7 + 1, 3) == 0;
	}

	static boolean isBiped(String type) { return BIPEDS.contains(type); }

	/** What this entity wears in the current battle, or null. */
	static Kit kitFor(int id) {
		BattleView v = ClientBattle.view;
		if (v == null || id < 0 || "LOBBY".equals(v.phase)) return null;
		// Anyone already wearing a helmet (or a crew bandana) gets no snapback on top.
		var mc = net.minecraft.client.MinecraftClient.getInstance();
		var e = mc.world == null ? null : mc.world.getEntityById(id);
		boolean helmet = e instanceof net.minecraft.entity.LivingEntity le
			&& !le.getEquippedStack(net.minecraft.entity.EquipmentSlot.HEAD).isEmpty();
		if (v.mobEntityIds.contains(id)) {
			int r = v.rankLevel;
			BlockState chain = r <= 1 ? Blocks.IRON_BLOCK.getDefaultState() : r <= 4 ? Blocks.GOLD_BLOCK.getDefaultState()
				: r == 5 ? Blocks.DIAMOND_BLOCK.getDefaultState() : Blocks.GOLD_BLOCK.getDefaultState();
			BlockState pendant = r >= 6 ? Blocks.DIAMOND_BLOCK.getDefaultState() : r >= 4 ? Blocks.EMERALD_BLOCK.getDefaultState() : chain;
			boolean glasses = r >= 3 || "На понтах".equals(v.mobMood) || "Опасный".equals(v.mobMood)
				|| "Flexing".equals(v.mobMood) || "Dangerous".equals(v.mobMood);
			BlockState cap = v.gangColor >= 0 && !helmet ? concrete(v.gangColor) : null;
			return new Kit(chain, pendant, glasses, cap, true);
		}
		if (smokes(id)) return new Kit(null, null, false, null, true);
		for (BattleView.Member mm : v.team) {
			if (mm.entityId != id) continue;
			boolean boss = mm.title.contains("Босс") || mm.title.contains("дракон")
				|| mm.title.contains("Boss") || mm.title.contains("Dragon");
			BlockState chain = boss ? Blocks.DIAMOND_BLOCK.getDefaultState() : mm.wins >= 1 ? Blocks.GOLD_BLOCK.getDefaultState()
				: Blocks.IRON_BLOCK.getDefaultState();
			BlockState pendant = mm.mvp >= 3 ? Blocks.EMERALD_BLOCK.getDefaultState() : chain;
			BlockState cap = mm.title.isEmpty() || helmet ? null : concrete(0xFF000000 | (mm.name.hashCode() & 0xFFFFFF));
			return new Kit(chain, pendant, mm.mvp >= 1 || mm.wins >= 3, cap, false);
		}
		return null;
	}

	/** The concrete colour closest to {@code rgb}. */
	private static BlockState concrete(int rgb) {
		DyeColor best = DyeColor.WHITE;
		double bestD = Double.MAX_VALUE;
		for (DyeColor c : DyeColor.values()) {
			int e = c.getEntityColor();
			double d = sq(((e >> 16) & 255) - ((rgb >> 16) & 255)) + sq(((e >> 8) & 255) - ((rgb >> 8) & 255)) + sq((e & 255) - (rgb & 255));
			if (d < bestD) {
				bestD = d;
				best = c;
			}
		}
		return Registries.BLOCK.get(Identifier.ofVanilla(best.getId() + "_concrete")).getDefaultState();
	}

	private static double sq(int x) { return (double) x * x; }
}
