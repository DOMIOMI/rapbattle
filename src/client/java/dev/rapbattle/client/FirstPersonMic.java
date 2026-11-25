package dev.rapbattle.client;

import dev.rapbattle.battle.Trophies;
import dev.rapbattle.net.BattleView;
import net.minecraft.client.network.AbstractClientPlayerEntity;
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
 *   <li>Right hand (empty): raises the fist above the battle table and draws the detailed mic in camera space.</li>
 *   <li>Mob's turn: slightly lower, swaying to the beat; own turn: closer to center-bottom, bouncing with voice level.</li>
 *   <li>Silence &gt; 2 beats on own turn: mic hand lowers.</li>
 *   <li>Left hand: forward jab on loud on-beat rhyme; DJ scratch gesture on rhyme reroll (R).</li>
 * </ul>
 */
public final class FirstPersonMic {
	private FirstPersonMic() {}

	public static boolean shouldRenderRight(AbstractClientPlayerEntity player, Hand hand, ItemStack item) {
		BattleView v = ClientBattle.view;
		if (v == null || v.spectator || !"TIMELINE".equals(v.phase) || !ClientBattle.onStage()) return false;
		Arm arm = hand == Hand.MAIN_HAND ? player.getMainArm() : player.getMainArm().getOpposite();
		return arm == Arm.RIGHT && item.isEmpty();
	}

	/** Camera-space offset so the fist + mic sit cleanly above the bottom HUD table. */
	public static void applyFistOffset(AbstractClientPlayerEntity player, MatrixStack m) {
		boolean myTurn = ClientBattle.readerId() == player.getId();
		float beatFrac = ClientBattle.beatFrac();
		float pu = BattleDance.onBeat() ? MobMoves.pulse(beatFrac) : 0f;
		float wave = (float) Math.sin(Math.PI * (ClientBattle.beat() + beatFrac) / 2.0);
		float voice = ClientBattle.smoothMicLevel();
		boolean silentDrop = myTurn && ClientBattle.silentBeats() > 2f;

		if (silentDrop) {
			m.translate(-0.08f, 0.14f, 0.0f);
			m.multiply(RotationAxis.POSITIVE_X.rotationDegrees(-20f));
		} else if (myTurn) {
			float bounce = 0.04f * voice + 0.015f * pu;
			m.translate(-0.22f, 0.42f + bounce, 0.04f);
		} else {
			m.translate(-0.12f, 0.32f - 0.02f * pu, 0.0f);
			m.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(5f * wave));
		}
	}

	/** Renders the detailed mic in camera space aligned with the raised fist. */
	public static void renderMicCameraSpace(AbstractClientPlayerEntity player, MatrixStack m, OrderedRenderCommandQueue q, int light) {
		boolean myTurn = ClientBattle.readerId() == player.getId();
		float beatFrac = ClientBattle.beatFrac();
		float pu = BattleDance.onBeat() ? MobMoves.pulse(beatFrac) : 0f;
		float wave = (float) Math.sin(Math.PI * (ClientBattle.beat() + beatFrac) / 2.0);
		float voice = ClientBattle.smoothMicLevel();
		boolean silentDrop = myTurn && ClientBattle.silentBeats() > 2f;

		m.push();
		if (silentDrop) {
			m.translate(0.42f, -0.26f, -0.68f);
			m.multiply(RotationAxis.POSITIVE_X.rotationDegrees(15f));
		} else if (myTurn) {
			float bounce = 0.04f * voice + 0.015f * pu;
			m.translate(0.32f, -0.02f + bounce, -0.64f);
			m.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(22f));
			m.multiply(RotationAxis.POSITIVE_X.rotationDegrees(66f - 6f * voice));
		} else {
			m.translate(0.40f, -0.11f - 0.02f * pu, -0.68f);
			m.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(5f * wave));
			m.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(16f));
			m.multiply(RotationAxis.POSITIVE_X.rotationDegrees(62f));
		}
		Trophies.TrophyPerk perk = offhandPerk(player.getOffHandStack());
		MicModel.render(m, q, light, 0.055f, perk);
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
}
