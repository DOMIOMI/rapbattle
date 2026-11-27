package dev.rapbattle.battle;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.rapbattle.Lang;
import dev.rapbattle.RapBattle;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;

import java.util.*;

/**
 * Stealing items on a decisive loss (KO or 35+ point margin):
 * - Whitelists by rank (1-3 minor, 4+ valuables, 5+ includes diamond & main-hand priority)
 * - Strict blacklist (armor, tools, weapons, enchanted/named, mod items, totems, shulkers, bundles, maps)
 * - Persistent Fabric Data Attachment on any mob (including armless/tiny mobs), survives conversion & creeper blast.
 */
public final class Theft {
	private Theft() {}

	public static final int STEAL_MARGIN = TheftRules.STEAL_MARGIN;

	public record StolenData(ItemStack stack, boolean wasPersistent, String originalName) {
		public static final Codec<StolenData> CODEC = RecordCodecBuilder.create(i -> i.group(
			ItemStack.CODEC.fieldOf("stack").forGetter(StolenData::stack),
			Codec.BOOL.optionalFieldOf("was_persistent", false).forGetter(StolenData::wasPersistent),
			Codec.STRING.optionalFieldOf("orig_name", "").forGetter(StolenData::originalName)
		).apply(i, StolenData::new));
	}

	public static final AttachmentType<StolenData> STOLEN_ITEM =
		AttachmentRegistry.createPersistent(Identifier.of(RapBattle.MOD_ID, "stolen_item"), StolenData.CODEC);

	public static void register() {
		ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
			if (entity instanceof MobEntity mob) {
				dropOnDeathOrExplode(mob);
			}
		});
		ServerLivingEntityEvents.MOB_CONVERSION.register((previous, converted, keepEquipment) -> {
			if (previous instanceof MobEntity prevMob && converted instanceof MobEntity nextMob) {
				transferOnConversion(prevMob, nextMob);
			}
		});
	}

	public static boolean hasStolen(MobEntity mob) {
		if (mob == null) return false;
		StolenData data = mob.getAttached(STOLEN_ITEM);
		return data != null && !data.stack().isEmpty();
	}

	public static ItemStack getStolen(MobEntity mob) {
		if (mob == null) return ItemStack.EMPTY;
		StolenData data = mob.getAttached(STOLEN_ITEM);
		return data == null ? ItemStack.EMPTY : data.stack();
	}

	public static boolean isForbidden(ItemStack stack) {
		if (stack == null || stack.isEmpty()) return true;
		if (stack.isOf(Items.TOTEM_OF_UNDYING) || stack.isOf(Items.MAP) || stack.isOf(Items.FILLED_MAP)) return true;
		if (stack.isIn(ItemTags.SHULKER_BOXES) || stack.isIn(ItemTags.BUNDLES)) return true;
		if (stack.contains(DataComponentTypes.EQUIPPABLE) || stack.contains(DataComponentTypes.WEAPON)
			|| stack.contains(DataComponentTypes.TOOL)) return true;
		if (stack.hasEnchantments()) return true;
		if (stack.contains(DataComponentTypes.CUSTOM_NAME)) return true;
		NbtComponent custom = stack.get(DataComponentTypes.CUSTOM_DATA);
		if (custom != null) {
			NbtCompound nbt = custom.copyNbt();
			for (String k : nbt.getKeys()) {
				if (k.startsWith("rapbattle")) return true;
			}
		}
		return false;
	}

	/** Returns the inventory index to steal from, or -1 if nothing fits. */
	public static int pickStealIndex(List<ItemStack> inv, int selectedSlot, int rankLevel, boolean peaceful) {
		if (inv == null || inv.isEmpty()) return -1;
		List<TheftRules.SlotItem> mapped = new ArrayList<>(inv.size());
		for (ItemStack st : inv) {
			if (st == null || st.isEmpty()) {
				mapped.add(new TheftRules.SlotItem("", true));
			} else {
				String id = Registries.ITEM.getId(st.getItem()).getPath();
				mapped.add(new TheftRules.SlotItem(id, isForbidden(st)));
			}
		}
		return TheftRules.pickStealIndex(mapped, selectedSlot, rankLevel, peaceful);
	}

	static void stealOnLoss(Battle battle, ServerPlayerEntity victim) {
		if (battle == null || battle.boss || victim == null) return;
		int margin = battle.respectMob - battle.respectTeam;
		if (!battle.result.knockout && margin < STEAL_MARGIN) return;
		MobEntity mob = battle.mob;
		if (!mob.isAlive() || hasStolen(mob)) return;

		boolean peaceful = !(mob instanceof HostileEntity) && !(mob instanceof net.minecraft.entity.mob.Angerable);
		var inv = victim.getInventory();
		List<ItemStack> list = new ArrayList<>(inv.size());
		for (int i = 0; i < inv.size(); i++) list.add(inv.getStack(i));
		int slot = pickStealIndex(list, inv.getSelectedSlot(), battle.rank.level(), peaceful);
		if (slot < 0) return;

		ItemStack src = inv.getStack(slot);
		ItemStack stolen = src.split(1);
		if (stolen.isEmpty()) return;

		boolean wasPers = mob.isPersistent();
		// Capture only a true vanilla Name Tag (stored in MobRecord.origName when first seen).
		String origName = MobRecord.of(mob).origName();
		if (origName.isEmpty() && !MobRecord.hasWins(mob) && mob.hasCustomName() && mob.getCustomName() != null) {
			origName = mob.getCustomName().getString();
		}
		mob.setAttached(STOLEN_ITEM, new StolenData(stolen.copy(), wasPers, origName));
		mob.setPersistent();

		refreshMobName(mob, battle.opp.name());

		// Humanoids with an empty main hand can hold it visually with 0% vanilla drop chance
		if (mob.getMainHandStack().isEmpty()) {
			mob.equipStack(EquipmentSlot.MAINHAND, stolen.copy());
			mob.setEquipmentDropChance(EquipmentSlot.MAINHAND, 0.0f);
		}

		String itemLabel = stolen.getName().getString();
		victim.sendMessage(Text.translatable("rapbattle.theft.stole", battle.opp.name(), itemLabel).formatted(Formatting.GOLD), false);
		Speech.bubble(mob, Lang.pick(battle.lang, "Это моё!", "Mine now!"), 0xF2C94C, 3000);

		if (peaceful) {
			Crowd.retreat(List.of(mob), new Vec3d(victim.getX(), victim.getY(), victim.getZ()), 15_000L);
		}
	}

	static void returnOnWin(MobEntity mob, ServerPlayerEntity winner) {
		if (mob == null || winner == null) return;
		StolenData data = mob.getAttached(STOLEN_ITEM);
		if (data == null || data.stack().isEmpty()) return;

		ItemStack back = data.stack().copy();
		Text itemName = back.getName();
		mob.removeAttached(STOLEN_ITEM);
		clearMobVisuals(mob, data);
		if (!winner.getInventory().insertStack(back)) {
			winner.dropItem(back, false);
		}
		winner.sendMessage(Text.translatable("rapbattle.theft.returned", itemName).formatted(Formatting.GREEN), false);
	}

	public static void dropOnDeathOrExplode(MobEntity mob) {
		if (mob == null) return;
		StolenData data = mob.getAttached(STOLEN_ITEM);
		if (data == null || data.stack().isEmpty()) return;
		ItemStack dropStack = data.stack().copy();
		mob.removeAttached(STOLEN_ITEM);
		if (!MobRecord.hasWins(mob)) {
			((dev.rapbattle.mixin.MobGoalAccessor) mob).rapbattle$setPersistent(data.wasPersistent());
		}
		if (mob.getEntityWorld() instanceof ServerWorld sw) {
			ItemEntity it = new ItemEntity(sw, mob.getX(), mob.getY() + 0.5, mob.getZ(), dropStack);
			it.setToDefaultPickupDelay();
			sw.spawnEntity(it);
		}
	}

	public static void transferOnConversion(MobEntity prev, MobEntity next) {
		if (prev == null || next == null) return;
		StolenData data = prev.getAttached(STOLEN_ITEM);
		if (data == null || data.stack().isEmpty()) return;
		prev.removeAttached(STOLEN_ITEM);
		next.setAttached(STOLEN_ITEM, data);
		next.setPersistent();
		refreshMobName(next, "");
		if (next.getMainHandStack().isEmpty()) {
			next.equipStack(EquipmentSlot.MAINHAND, data.stack().copy());
			next.setEquipmentDropChance(EquipmentSlot.MAINHAND, 0.0f);
		}
	}

	/**
	 * Single source of truth for a mob's display name: combines optional vanilla name tag,
	 * golden champion crown ({@code ♛} / {@code ♛×N}), and stolen item tag ({@code [Item]}).
	 */
	public static void refreshMobName(MobEntity mob, String baseRapName) {
		if (mob == null) return;
		StolenData data = mob.getAttached(STOLEN_ITEM);
		ItemStack stolen = data != null ? data.stack() : ItemStack.EMPTY;
		int wins = MobRecord.hasWins(mob) ? MobRecord.of(mob).wins() : 0;

		if (wins <= 0 && stolen.isEmpty()) {
			if (data != null && !data.originalName().isEmpty()) {
				mob.setCustomName(Text.literal(data.originalName()));
				mob.setCustomNameVisible(true);
			} else {
				mob.setCustomName(null);
				mob.setCustomNameVisible(false);
			}
			return;
		}

		String nameCore = "";
		MobRecord rec = MobRecord.of(mob);
		if (!rec.origName().isEmpty()) {
			nameCore = rec.origName();
		} else if (data != null && !data.originalName().isEmpty()) {
			nameCore = data.originalName();
		} else {
			if (!rec.name(Lang.RU).isEmpty()) nameCore = rec.name(Lang.RU);
			else if (!rec.name(Lang.EN).isEmpty()) nameCore = rec.name(Lang.EN);
			else if (baseRapName != null && !baseRapName.isEmpty()) nameCore = baseRapName;
			else nameCore = MobProfiles.of(mob.getType()).species(Lang.RU);
		}

		StringBuilder sb = new StringBuilder();
		if (wins > 0) {
			sb.append(nameCore).append(" ♛");
			if (wins > 1) sb.append("×").append(wins);
			if (!stolen.isEmpty()) sb.append(" [").append(stolen.getName().getString()).append("]");
		} else {
			if (!data.originalName().isEmpty()) sb.append(data.originalName()).append(" ");
			sb.append("[").append(stolen.getName().getString()).append("]");
		}
		mob.setCustomName(Text.literal(sb.toString()).formatted(Formatting.GOLD));
		mob.setCustomNameVisible(true);
	}

	private static void clearMobVisuals(MobEntity mob, StolenData data) {
		if (ItemStack.areItemsAndComponentsEqual(mob.getMainHandStack(), data.stack())) {
			mob.equipStack(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
		}
		if (MobRecord.hasWins(mob)) {
			mob.setPersistent();
		} else {
			((dev.rapbattle.mixin.MobGoalAccessor) mob).rapbattle$setPersistent(data.wasPersistent());
		}
		if (!MobRecord.hasWins(mob)) {
			if (data.originalName().isEmpty()) {
				mob.setCustomName(null);
				mob.setCustomNameVisible(false);
			} else {
				mob.setCustomName(Text.literal(data.originalName()));
			}
		} else {
			refreshMobName(mob, "");
		}
	}
}
