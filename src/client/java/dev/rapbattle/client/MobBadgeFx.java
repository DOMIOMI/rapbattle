package dev.rapbattle.client;

import dev.rapbattle.net.Payloads;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Renders a compact rank-item + status-mark (✓ / ✗) badge above nearby mobs outside of battle.
 * Uses entityCutoutNoCull + TextRenderer on the world consumer queue for Sodium/Iris compatibility.
 */
public final class MobBadgeFx {
	private MobBadgeFx() {}

	public record Badge(int rank, boolean boss, int status, long updatedAt) {}

	private static final Map<Integer, Badge> BADGES = new ConcurrentHashMap<>();
	private static final double MAX_DIST_SQ = 8.0 * 8.0;
	private static final long STALE_MS = 3_000L;
	private static final int FULL_LIGHT = 0xF000F0;

	private static final Identifier TEX_LEATHER = Identifier.ofVanilla("textures/item/leather_chestplate.png");
	private static final Identifier TEX_CHAIN = Identifier.ofVanilla("textures/item/chainmail_chestplate.png");
	private static final Identifier TEX_IRON = Identifier.ofVanilla("textures/item/iron_ingot.png");
	private static final Identifier TEX_GOLD = Identifier.ofVanilla("textures/item/gold_ingot.png");
	private static final Identifier TEX_DIAMOND = Identifier.ofVanilla("textures/item/diamond.png");
	private static final Identifier TEX_NETHERITE = Identifier.ofVanilla("textures/item/netherite_ingot.png");
	private static final Identifier TEX_BOSS = Identifier.ofVanilla("textures/item/nether_star.png");

	public static void register() {
		WorldRenderEvents.BEFORE_TRANSLUCENT.register(MobBadgeFx::render);
	}

	public static void onPacket(Payloads.MobBadgesS2C pkt) {
		long now = System.currentTimeMillis();
		BADGES.clear();
		int n = Math.min(pkt.entityIds().size(), Math.min(pkt.ranks().length, pkt.statuses().length));
		for (int i = 0; i < n; i++) {
			int packed = pkt.ranks()[i] & 0xFF;
			int rank = packed & 0x0F;
			boolean boss = (packed & 0x10) != 0;
			int status = pkt.statuses()[i] & 0xFF;
			BADGES.put(pkt.entityIds().get(i), new Badge(rank, boss, status, now));
		}
	}

	public static void clear() {
		BADGES.clear();
	}

	public static Badge badgeFor(int entityId) {
		return BADGES.get(entityId);
	}

	public static ItemStack rankIcon(int rank, boolean boss) {
		if (boss) return new ItemStack(Items.NETHER_STAR);
		return switch (rank) {
			case 1 -> new ItemStack(Items.LEATHER_CHESTPLATE);
			case 2 -> new ItemStack(Items.CHAINMAIL_CHESTPLATE);
			case 3 -> new ItemStack(Items.IRON_INGOT);
			case 4 -> new ItemStack(Items.GOLD_INGOT);
			case 5 -> new ItemStack(Items.DIAMOND);
			default -> new ItemStack(Items.NETHERITE_INGOT);
		};
	}

	private static Identifier rankTexture(int rank, boolean boss) {
		if (boss) return TEX_BOSS;
		return switch (rank) {
			case 1 -> TEX_LEATHER;
			case 2 -> TEX_CHAIN;
			case 3 -> TEX_IRON;
			case 4 -> TEX_GOLD;
			case 5 -> TEX_DIAMOND;
			default -> TEX_NETHERITE;
		};
	}

	private static void render(WorldRenderContext ctx) {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.player == null || mc.world == null || ClientBattle.view != null || BADGES.isEmpty()) return;

		long now = System.currentTimeMillis();
		Camera cam = mc.gameRenderer.getCamera();
		Vec3d camPos = cam.getCameraPos();
		float tickDelta = mc.getRenderTickCounter().getTickProgress(false);
		MatrixStack matrices = ctx.matrices();
		VertexConsumerProvider consumers = ctx.consumers();
		TextRenderer tr = mc.textRenderer;

		for (var it = BADGES.entrySet().iterator(); it.hasNext(); ) {
			var entry = it.next();
			Badge b = entry.getValue();
			if (now - b.updatedAt() > STALE_MS) {
				it.remove();
				continue;
			}
			Entity e = mc.world.getEntityById(entry.getKey());
			if (!(e instanceof MobEntity mob) || !mob.isAlive() || mob.isInvisible()) continue;
			if (mob.squaredDistanceTo(mc.player) > MAX_DIST_SQ) continue;

			Vec3d pos = mob.getLerpedPos(tickDelta);
			double wx = pos.x - camPos.x;
			double wy = pos.y + mob.getHeight() + 0.42 - camPos.y;
			double wz = pos.z - camPos.z;

			matrices.push();
			matrices.translate(wx, wy, wz);
			matrices.multiply(cam.getRotation());
			// Scale to crisp HUD-like pixel units (negative X/Y so text & textures are right-side-up).
			float s = 0.028f;
			matrices.scale(-s, -s, s);

			Matrix4f mat = matrices.peek().getPositionMatrix();
			// 1. Rank item icon (16x16 on the left: x -17..-1, y -8..8)
			int tint = (!b.boss() && b.rank() == 1) ? 0xFFA06540 : 0xFFFFFFFF;
			int r = (tint >> 16) & 0xFF, g = (tint >> 8) & 0xFF, bl = tint & 0xFF;
			VertexConsumer vc = consumers.getBuffer(RenderLayers.entityCutoutNoCull(rankTexture(b.rank(), b.boss())));
			vc.vertex(mat, -17f, 8f, 0f).color(r, g, bl, 255).texture(0f, 1f).overlay(OverlayTexture.DEFAULT_UV).light(FULL_LIGHT).normal(0f, 0f, 1f);
			vc.vertex(mat, -1f, 8f, 0f).color(r, g, bl, 255).texture(1f, 1f).overlay(OverlayTexture.DEFAULT_UV).light(FULL_LIGHT).normal(0f, 0f, 1f);
			vc.vertex(mat, -1f, -8f, 0f).color(r, g, bl, 255).texture(1f, 0f).overlay(OverlayTexture.DEFAULT_UV).light(FULL_LIGHT).normal(0f, 0f, 1f);
			vc.vertex(mat, -17f, -8f, 0f).color(r, g, bl, 255).texture(0f, 0f).overlay(OverlayTexture.DEFAULT_UV).light(FULL_LIGHT).normal(0f, 0f, 1f);

			// 2. Status mark (✓ / ✗) on the right without bold
			String mark = b.status() == 2 ? "✗" : "✓";
			int color = b.status() == 0 ? 0xFF55FF55 : b.status() == 1 ? 0xFFFFDD44 : 0xFFFF5555;
			matrices.translate(0f, 0f, 0.01f);
			tr.draw(Text.literal(mark), 2f, -4f, color, true, matrices.peek().getPositionMatrix(),
				consumers, TextRenderer.TextLayerType.NORMAL, 0, FULL_LIGHT);

			matrices.pop();
		}
	}
}
