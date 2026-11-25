package dev.rapbattle.battle;

import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.boss.WitherEntity;
import net.minecraft.entity.boss.dragon.EnderDragonEntity;
import net.minecraft.entity.mob.ElderGuardianEntity;
import net.minecraft.entity.mob.MagmaCubeEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.SlimeEntity;
import net.minecraft.entity.mob.WardenEntity;
import net.minecraft.entity.raid.RaiderEntity;
import net.minecraft.world.Difficulty;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** A mob as a rapper in this particular battle: profile, final rank after modifiers, name, mood and tags. */
public record Opponent(MobEntity mob, MobProfiles.Profile profile, Rank rank, String name, String mood, List<String> tags) {

	private static final List<String> MOODS = List.of(
		"Мелочь в ярости", "Серьёзный", "Шпана", "На понтах", "Голодный", "Обиженный", "Шляпа", "Опасный");

	public static boolean isBoss(MobEntity mob) {
		return mob instanceof EnderDragonEntity || mob instanceof WitherEntity || mob instanceof WardenEntity
			|| mob instanceof ElderGuardianEntity;
	}

	public static Opponent of(MobEntity mob, Random rnd) { return of(mob, rnd, null); }

	/** @param challenger who calls the mob out; some mobs react to what that player wears or holds */
	public static Opponent of(MobEntity mob, Random rnd, net.minecraft.entity.player.PlayerEntity challenger) {
		MobProfiles.Profile p = MobProfiles.of(mob.getType());
		List<String> tags = new ArrayList<>();
		String name = nameOf(mob, p, rnd);
		if (isBoss(mob)) {
			return new Opponent(mob, p, Rank.NETHERITE, name, "Босс", tags);
		}

		int r = p.baseRank();
		// Slimes and magma cubes rank by size.
		if (mob instanceof SlimeEntity slime) {
			int size = slime.getSize();
			r = size >= 4 ? 3 : size >= 2 ? 2 : 1;
			if (mob instanceof MagmaCubeEntity) r++;
		}
		if (mob.isBaby()) {
			r--;
			name = "Лил " + name.replaceFirst("^(Лил|Биг|Янг|Кид|Бэйби|MC|МС|OG) ", "");
			tags.add("ДЕТЁНЫШ");
		}
		// A player's name tag makes the mob famous (our own champion crown doesn't count).
		if (mob.hasCustomName() && !mob.getCustomName().getString().contains("♛")) {
			r++;
			tags.add("ИЗВЕСТНЫЙ");
		}
		MobRecord rec = MobRecord.of(mob);
		if (rec.wins() > 0) {
			int bonus = Math.min(2, rec.wins());
			r += bonus;
			tags.add(rec.wins() > 1 ? "ЧЕМПИОН ×" + rec.wins() : "ЧЕМПИОН");
		}
		World w = mob.getEntityWorld();
		Difficulty d = w.getDifficulty();
		if (d == Difficulty.PEACEFUL) r--;
		if (d == Difficulty.HARD) {
			r++;
			tags.add("ХАРД");
		}
		if (mob.getType().isIn(net.minecraft.registry.tag.EntityTypeTags.UNDEAD)) {
			if (isNight(w)) {
				r++;
				tags.add("НОЧЬ");
			} else if (w.isSkyVisible(mob.getBlockPos()) && !p.id().equals("husk") && !p.id().equals("parched")) {
				r--;
				tags.add("НА СОЛНЦЕ");
			}
		}
		if (homeDimension(p.id(), w)) {
			r++;
			tags.add("ДОМА");
		}
		if (mob instanceof RaiderEntity raider && raider.isCaptain()) {
			r++;
			tags.add("КАПИТАН");
		}
		// Villagers by profession: the librarian is a word nerd, the nitwit talks nonsense.
		if (mob instanceof net.minecraft.entity.passive.VillagerEntity v) {
			var prof = v.getVillagerData().profession();
			if (prof.matchesKey(net.minecraft.village.VillagerProfession.LIBRARIAN)) {
				r++;
				tags.add("БИБЛИОТЕКАРЬ");
			} else if (prof.matchesKey(net.minecraft.village.VillagerProfession.NITWIT)) {
				r = 1;
				tags.add("ДУРАЧОК");
			}
		}
		if (challenger != null) {
			String id = p.id();
			if (id.equals("piglin") || id.equals("piglin_brute")) {
				boolean gold = false;
				for (EquipmentSlot s : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
					gold |= Outfit.material(challenger.getEquippedStack(s)) == 4;
				}
				if (gold) {
					r--;
					tags.add("СВОЙ ПАЦАН");
				} else {
					r++;
					tags.add("НИЩЕБРОД");
				}
			}
			if ((id.equals("cat") || id.equals("ocelot"))
				&& (challenger.getMainHandStack().isOf(net.minecraft.item.Items.COD) || challenger.getMainHandStack().isOf(net.minecraft.item.Items.SALMON))) {
				r--;
				tags.add("ПРИКОРМЛЕН");
			}
		}
		// A mob wearing armour is at least as tough as its armour.
		int armour = 0;
		for (EquipmentSlot s : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
			armour = Math.max(armour, Outfit.material(mob.getEquippedStack(s)));
		}
		if (armour > r) {
			r = armour;
			tags.add("В БРОНЕ");
		}
		Rank rank = Rank.ofLevel(Math.max(1, Math.min(5, r)));
		return new Opponent(mob, p, rank, name, MOODS.get(rnd.nextInt(MOODS.size())), tags);
	}

	/**
	 * The mob's rap name: a player's name tag if it has one, else the nickname saved in the mob,
	 * else a fresh species nickname (saved right away, so it never changes and neighbours differ).
	 */
	public static String nameOf(MobEntity mob, MobProfiles.Profile p, Random rnd) {
		if (mob.hasCustomName()) {
			String tag = mob.getCustomName().getString().replaceAll("\\s*♛.*$", "").trim();
			if (!tag.isEmpty()) return tag;
		}
		MobRecord rec = MobRecord.of(mob);
		if (!rec.name().isEmpty()) return rec.name();
		List<String> taken = new ArrayList<>();
		for (MobEntity other : mob.getEntityWorld().getEntitiesByClass(MobEntity.class, mob.getBoundingBox().expand(64),
			m -> m != mob && m.getType() == mob.getType())) {
			String n = MobRecord.of(other).name();
			if (!n.isEmpty()) taken.add(n);
		}
		String name = Names.fresh(p, rnd, taken);
		MobRecord.setName(mob, name);
		return name;
	}

	/** Night by the clock (World#isNight lags behind /time set on the server). */
	public static boolean isNight(World w) {
		long t = w.getTimeOfDay() % 24000L;
		return t >= 13000 && t < 23000;
	}

	private static boolean homeDimension(String id, World w) {
		boolean nether = w.getRegistryKey() == World.NETHER;
		boolean end = w.getRegistryKey() == World.END;
		return switch (id) {
			case "piglin", "piglin_brute", "zombified_piglin", "hoglin", "blaze", "ghast", "magma_cube", "strider",
				 "wither_skeleton" -> nether;
			case "enderman", "shulker", "endermite" -> end;
			default -> false;
		};
	}
}
