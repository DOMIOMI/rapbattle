package dev.rapbattle.battle;

import com.mojang.serialization.Codec;
import dev.rapbattle.RapBattle;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.util.Set;

/**
 * Equips a single non-dropping armor piece (leather/chainmail/iron) on a fraction of starter mobs at spawn,
 * scaling with distance from world spawn and overworld day, so mob rank is readable by sight.
 */
@SuppressWarnings("UnstableApiUsage")
public final class StarterGear {
	private StarterGear() {}

	private static final Set<String> ELIGIBLE = Set.of(
		"zombie", "husk", "drowned", "zombie_villager",
		"skeleton", "stray", "bogged", "parched");

	private static final AttachmentType<Boolean> ROLLED =
		AttachmentRegistry.createPersistent(Identifier.of(RapBattle.MOD_ID, "starter_gear_rolled"), Codec.BOOL);

	public static boolean isEligibleSpecies(String id) {
		return id != null && ELIGIBLE.contains(id);
	}

	/**
	 * Pure roll for starter armor tier: 0 = none (rank 1), 1 = leather (rank 1), 2 = chain (rank 2), 3 = iron (rank 3).
	 * Near spawn on day 0, ~95% are rank 1; chance grows with distance and overworld day.
	 */
	public static int rollTier(double distFromSpawn, long overworldDay, double roll) {
		double distFactor = Math.min(1.0, Math.max(0.0, (distFromSpawn - 48.0) / 600.0));
		double dayFactor = Math.min(1.0, Math.max(0.0, overworldDay / 14.0));
		double ironChance = 0.01 + 0.14 * dayFactor + 0.10 * distFactor;
		double chainChance = 0.04 + 0.16 * dayFactor + 0.15 * distFactor;
		double leatherChance = 0.10;
		if (roll < ironChance) return 3;
		if (roll < ironChance + chainChance) return 2;
		if (roll < ironChance + chainChance + leatherChance) return 1;
		return 0;
	}

	public static void register() {
		ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
			if (!(entity instanceof MobEntity mob) || !mob.isAlive()) return;
			if (mob.hasAttached(ROLLED)) return;
			mob.setAttached(ROLLED, true);

			String id = MobProfiles.of(mob.getType()).id();
			if (!isEligibleSpecies(id)) return;
			// Do not overwrite existing armor (vanilla spawn gear, tests, or custom mobs).
			for (EquipmentSlot s : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
				if (!mob.getEquippedStack(s).isEmpty()) return;
			}

			BlockPos spawn = world.getServer().getOverworld().getSpawnPoint().getPos();
			double dx = mob.getX() - spawn.getX();
			double dz = mob.getZ() - spawn.getZ();
			double dist = Math.hypot(dx, dz);
			long day = ProgressionRules.currentDay(world.getServer().getOverworld().getTime());
			int tier = rollTier(dist, day, world.random.nextDouble());
			if (tier <= 0) return;

			boolean helmet = world.random.nextBoolean();
			EquipmentSlot slot = helmet ? EquipmentSlot.HEAD : EquipmentSlot.CHEST;
			Item item = switch (tier) {
				case 1 -> helmet ? Items.LEATHER_HELMET : Items.LEATHER_CHESTPLATE;
				case 2 -> helmet ? Items.CHAINMAIL_HELMET : Items.CHAINMAIL_CHESTPLATE;
				default -> helmet ? Items.IRON_HELMET : Items.IRON_CHESTPLATE;
			};
			mob.equipStack(slot, new ItemStack(item));
			mob.setEquipmentDropChance(slot, 0.0f);
		});
	}
}
