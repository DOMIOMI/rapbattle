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
import org.joml.Quaternionf;
import org.joml.Vector3f;

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
			m.translate(0.0f, 0.16f, 0.0f);
		} else if (myTurn) {
			float bounce = 0.04f * voice + 0.015f * pu;
			m.translate(-0.06f, 0.36f + bounce, 0.04f);
		} else {
			m.translate(-0.02f, 0.28f - 0.02f * pu, 0.0f);
			m.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(5f * wave));
		}
	}

	/**
	 * Render-space centre of the right fist. Call right after {@code renderArmHoldingItem}, whose transform is still on
	 * the stack; this repeats what {@code PlayerEntityRenderer.renderArm} does to the arm part (pivot, roll 0.1) and
	 * walks 8.5 pixels down the arm to the middle of the fist.
	 */
	public static Vector3f fistPosition(MatrixStack m) {
		m.push();
		m.translate(-5f / 16f, 2f / 16f, 0f);
		m.multiply(RotationAxis.POSITIVE_Z.rotation(0.1f));
		m.translate(-1f / 16f, 8.5f / 16f, 0f);
		Vector3f p = m.peek().getPositionMatrix().getTranslation(new Vector3f());
		m.pop();
		return p;
	}

	/**
	 * Draws the mic gripped in the fist, standing up with the head toward the player's mouth (not lying flat and
	 * pointing at the opponent). The model's head axis (-Z) is turned toward a blend of "up" and "toward the mouth".
	 */
	public static void renderMicCameraSpace(AbstractClientPlayerEntity player, MatrixStack m, OrderedRenderCommandQueue q, int light,
											Vector3f fist) {
		boolean myTurn = ClientBattle.readerId() == player.getId();
		float beatFrac = ClientBattle.beatFrac();
		float wave = (float) Math.sin(Math.PI * (ClientBattle.beat() + beatFrac) / 2.0);
		float voice = ClientBattle.smoothMicLevel();
		boolean silentDrop = myTurn && ClientBattle.silentBeats() > 2f;

		// fistPosition() is in absolute render space; bring it back into this stack's local frame.
		Vector3f local = new org.joml.Matrix4f(m.peek().getPositionMatrix()).invert().transformPosition(new Vector3f(fist));
		// The head points at the player's mouth (the camera): mostly up, partly back toward the face, a little inward.
		Vector3f toMouth = new Vector3f(local).negate().normalize();
		float up, back, in;
		if (silentDrop) { up = 0.35f; back = 0.25f; in = 0.0f; }             // forgot the words: mic sags forward
		else if (myTurn) { up = 1.0f; back = 0.75f + 0.25f * voice; in = 0.15f; }
		else { up = 1.0f; back = 0.35f; in = 0.1f + 0.08f * wave; }         // listening: upright, swaying to the beat
		Vector3f dir = new Vector3f(0f, up, 0f).add(new Vector3f(toMouth).mul(back)).add(-in, 0f, 0f).normalize();
		if (silentDrop) dir.add(0f, 0f, -0.6f).normalize();

		m.push();
		m.translate(local.x, local.y, local.z);
		m.multiply(new Quaternionf().rotationTo(0f, 0f, -1f, dir.x, dir.y, dir.z));
		m.translate(0f, 0f, -1.4f / 16f); // slide it up out of the fist so the collar and head clear the fingers
		Trophies.TrophyPerk perk = offhandPerk(player.getOffHandStack());
		MicModel.render(m, q, light, 1f / 16f, perk);
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
