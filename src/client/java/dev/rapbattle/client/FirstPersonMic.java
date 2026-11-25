package dev.rapbattle.client;

import dev.rapbattle.battle.Trophies;
import dev.rapbattle.net.BattleView;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.Arm;
import net.minecraft.util.Hand;
import net.minecraft.util.math.RotationAxis;

/**
 * First-person microphone & left-hand reactions during the TIMELINE phase:
 * <ul>
 *   <li>Right arm (empty): positions the fist and draws the mic inside it (with trophy mic colour & glow from off-hand).</li>
 *   <li>Mob's turn: slightly lower, swaying to the beat; own turn: closer to center-bottom, bouncing with voice level.</li>
 *   <li>Silence &gt; 2 beats on own turn: mic hand lowers.</li>
 *   <li>Left hand: forward jab on loud on-beat rhyme; DJ scratch gesture on rhyme reroll (R).</li>
 * </ul>
 */
public final class FirstPersonMic {
	private FirstPersonMic() {}

	public static boolean activeRightArm(Arm arm) {
		if (arm != Arm.RIGHT) return false;
		BattleView v = ClientBattle.view;
		return v != null && !v.spectator && "TIMELINE".equals(v.phase) && ClientBattle.onStage();
	}

	public static void applyArmTransform(MatrixStack m, Arm arm) {
		if (!activeRightArm(arm)) return;
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.player == null) return;
		boolean myTurn = ClientBattle.readerId() == mc.player.getId();
		float beatFrac = ClientBattle.beatFrac();
		float pu = BattleDance.onBeat() ? MobMoves.pulse(beatFrac) : 0f;
		float wave = (float) Math.sin(Math.PI * (ClientBattle.beat() + beatFrac) / 2.0);
		float voice = ClientBattle.smoothMicLevel();
		boolean silentDrop = myTurn && ClientBattle.silentBeats() > 2f;

		if (silentDrop) {
			m.translate(0.05f, -0.22f, 0.02f);
			m.multiply(RotationAxis.POSITIVE_X.rotationDegrees(-20f));
		} else if (myTurn) {
			float bounce = 0.045f * voice + 0.015f * pu;
			m.translate(-0.14f, 0.06f + bounce, 0.12f);
		} else {
			m.translate(0.02f, -0.08f - 0.02f * pu, 0.0f);
			m.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(5f * wave));
		}
	}

	public static void renderMicInArm(MatrixStack m, OrderedRenderCommandQueue q, int light, Arm arm) {
		if (!activeRightArm(arm)) return;
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.player == null) return;

		Trophies.TrophyPerk perk = offhandPerk(mc.player.getOffHandStack());
		BlockState grip = Blocks.BLACK_CONCRETE.getDefaultState();
		BlockState head = Blocks.GRAY_CONCRETE.getDefaultState();
		int useLight = light;
		if (perk != null) {
			useLight = 0xF000F0;
			head = switch (perk) {
				case WARDEN -> Blocks.CYAN_CONCRETE.getDefaultState();
				case ENDER_DRAGON -> Blocks.PURPLE_CONCRETE.getDefaultState();
				case WITHER -> Blocks.SOUL_SOIL.getDefaultState();
				case ELDER_GUARDIAN -> Blocks.PRISMARINE_BRICKS.getDefaultState();
				case DEFAULT -> Blocks.GOLD_BLOCK.getDefaultState();
			};
		}

		m.push();
		m.translate(0.14f, -0.20f, -0.20f);
		m.multiply(RotationAxis.POSITIVE_X.rotationDegrees(-35f));
		m.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-15f));
		block(m, q, useLight, grip, 0f, -0.08f, 0f, 0.065f, 0.24f, 0.065f);
		block(m, q, useLight, head, 0f, 0.06f, 0f, 0.115f, 0.115f, 0.115f);
		m.pop();
	}

	public static void applyLeftHand(AbstractClientPlayerEntity player, Hand hand, MatrixStack m) {
		BattleView v = ClientBattle.view;
		if (v == null || v.spectator || !"TIMELINE".equals(v.phase) || !ClientBattle.onStage()) return;
		Arm arm = hand == Hand.MAIN_HAND ? player.getMainArm() : player.getMainArm().getOpposite();
		if (arm != Arm.LEFT) return;

		float jab = ClientBattle.playerJabImpulse();
		if (jab > 0) {
			m.translate(0.06f * jab, 0.08f * jab, -0.28f * jab);
			m.multiply(RotationAxis.POSITIVE_X.rotationDegrees(-18f * jab));
		}
		float scratch = ClientBattle.scratchImpulse();
		if (scratch > 0) {
			float osc = (float) Math.sin(scratch * Math.PI * 3.0);
			m.translate(0.12f * osc, -0.05f * scratch, -0.14f * scratch);
			m.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(22f * osc));
		}
	}

	private static Trophies.TrophyPerk offhandPerk(ItemStack off) {
		if (off == null || off.isEmpty()) return null;
		NbtComponent custom = off.get(DataComponentTypes.CUSTOM_DATA);
		if (custom != null) {
			NbtCompound nbt = custom.copyNbt();
			if (nbt.contains(Trophies.NBT_KEY)) {
				return Trophies.TrophyPerk.ofKey(nbt.getString(Trophies.NBT_KEY).orElse("default"));
			}
		}
		if ((off.isOf(Items.END_ROD) || off.isOf(Items.LIGHTNING_ROD))
			&& Boolean.TRUE.equals(off.get(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE))) {
			return Trophies.TrophyPerk.DEFAULT;
		}
		return null;
	}

	private static void block(MatrixStack m, OrderedRenderCommandQueue q, int light, BlockState b,
							  float x, float y, float z, float sx, float sy, float sz) {
		m.push();
		m.translate(x - sx / 2f, y - sy / 2f, z - sz / 2f);
		m.scale(sx, sy, sz);
		q.submitBlock(m, b, light, OverlayTexture.DEFAULT_UV, 0);
		m.pop();
	}
}
