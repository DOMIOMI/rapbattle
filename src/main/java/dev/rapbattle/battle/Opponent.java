package dev.rapbattle.battle;

import dev.rapbattle.Lang;
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
	private static final List<String> MOODS_EN = List.of(
		"Raging Runt", "Dead Serious", "Street Punk", "Flexing", "Starving", "Salty", "Total Joke", "Dangerous");

	public static boolean isBoss(MobEntity mob) {
		return mob instanceof EnderDragonEntity || mob instanceof WitherEntity || mob instanceof WardenEntity
			|| mob instanceof ElderGuardianEntity;
	}

	public static Opponent of(MobEntity mob, Random rnd, String lang) { return of(mob, rnd, null, lang); }

	@Deprecated
	public static Opponent of(MobEntity mob, Random rnd) { return of(mob, rnd, null, Lang.RU); }

	/** @param challenger who calls the mob out; some mobs react to what that player wears or holds */
	public static Opponent of(MobEntity mob, Random rnd, net.minecraft.entity.player.PlayerEntity challenger, String lang) {
		boolean ru = Lang.isRu(lang);
		MobProfiles.Profile p = MobProfiles.of(mob.getType());
		List<String> tags = new ArrayList<>();
		String name = nameOf(mob, p, rnd, lang);
		if (isBoss(mob)) {
			return new Opponent(mob, p, Rank.NETHERITE, name, ru ? "Босс" : "Boss", tags);
		}

		int r = p.baseRank();
		if ("spider".equals(p.id())) {
			List<String> moods = ru ? MOODS : MOODS_EN;
			return new Opponent(mob, p, Rank.LEATHER, name, moods.get(rnd.nextInt(moods.size())), tags);
		}
		// Slimes and magma cubes rank by size.
		if (mob instanceof SlimeEntity slime) {
			int size = slime.getSize();
			r = size >= 4 ? 3 : size >= 2 ? 2 : 1;
			if (mob instanceof MagmaCubeEntity) r++;
		}
		if (mob.isBaby()) {
			r--;
			name = (ru ? "Лил " : "Lil ") + name.replaceFirst("^(Лил|Биг|Янг|Кид|Бэйби|Lil|Big|Young|Kid|Baby|MC|МС|OG) ", "");
			tags.add(ru ? "ДЕТЁНЫШ" : "BABY");
		}
		// A player's name tag makes the mob famous (our own champion crown doesn't count).
		if (mob.hasCustomName() && !mob.getCustomName().getString().contains("♛")) {
			r++;
			tags.add(ru ? "ИЗВЕСТНЫЙ" : "FAMOUS");
		}
		MobRecord rec = MobRecord.of(mob);
		if (rec.wins() > 0) {
			int bonus = Math.min(2, rec.wins());
			r += bonus;
			tags.add(rec.wins() > 1
				? (ru ? "ЧЕМПИОН ×" + rec.wins() : "CHAMPION ×" + rec.wins())
				: (ru ? "ЧЕМПИОН" : "CHAMPION"));
		}
		World w = mob.getEntityWorld();
		Difficulty d = w.getDifficulty();
		if (d == Difficulty.PEACEFUL) r--;
		if (d == Difficulty.HARD && p.baseRank() > 1) {
			r++;
			tags.add(ru ? "ХАРД" : "HARD");
		}
		if (mob.getType().isIn(net.minecraft.registry.tag.EntityTypeTags.UNDEAD) && p.baseRank() > 1) {
			if (isNight(w)) {
				r++;
				tags.add(ru ? "НОЧЬ" : "NIGHT");
			} else if (w.isSkyVisible(mob.getBlockPos()) && !p.id().equals("husk") && !p.id().equals("parched")) {
				r--;
				tags.add(ru ? "НА СОЛНЦЕ" : "IN THE SUN");
			}
		}
		if (homeDimension(p.id(), w)) {
			r++;
			tags.add(ru ? "ДОМА" : "HOME TURF");
		}
		if (mob instanceof RaiderEntity raider && raider.isCaptain()) {
			r++;
			tags.add(ru ? "КАПИТАН" : "CAPTAIN");
		}
		// Villagers by profession: the librarian is a word nerd, the nitwit talks nonsense.
		if (mob instanceof net.minecraft.entity.passive.VillagerEntity v) {
			var prof = v.getVillagerData().profession();
			if (prof.matchesKey(net.minecraft.village.VillagerProfession.LIBRARIAN)) {
				r++;
				tags.add(ru ? "БИБЛИОТЕКАРЬ" : "LIBRARIAN");
			} else if (prof.matchesKey(net.minecraft.village.VillagerProfession.NITWIT)) {
				r = 1;
				tags.add(ru ? "ДУРАЧОК" : "NITWIT");
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
					tags.add(ru ? "СВОЙ ПАЦАН" : "ONE OF US");
				} else {
					r++;
					tags.add(ru ? "НИЩЕБРОД" : "BROKE");
				}
			}
			if ((id.equals("cat") || id.equals("ocelot"))
				&& (challenger.getMainHandStack().isOf(net.minecraft.item.Items.COD) || challenger.getMainHandStack().isOf(net.minecraft.item.Items.SALMON))) {
				r--;
				tags.add(ru ? "ПРИКОРМЛЕН" : "BRIBED");
			}
		}
		// A mob wearing armour is at least as tough as its armour.
		int armour = 0;
		for (EquipmentSlot s : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
			armour = Math.max(armour, Outfit.material(mob.getEquippedStack(s)));
		}
		if (armour > r) {
			r = armour;
			tags.add(ru ? "В БРОНЕ" : "ARMORED");
		}
		Rank rank = Rank.ofLevel(Math.max(1, Math.min(5, r)));
		List<String> moods = ru ? MOODS : MOODS_EN;
		return new Opponent(mob, p, rank, name, moods.get(rnd.nextInt(moods.size())), tags);
	}

	@Deprecated
	public static Opponent of(MobEntity mob, Random rnd, net.minecraft.entity.player.PlayerEntity challenger) {
		return of(mob, rnd, challenger, Lang.RU);
	}

	/**
	 * The mob's rap name: a player's name tag if it has one, else the nickname saved in the mob,
	 * else a fresh species nickname (saved right away, so it never changes and neighbours differ).
	 */
	public static String nameOf(MobEntity mob, MobProfiles.Profile p, Random rnd, String lang) {
		if (mob.hasCustomName()) {
			String tag = mob.getCustomName().getString().replaceAll("\\s*♛.*$", "").trim();
			if (!tag.isEmpty()) return tag;
		}
		MobRecord rec = MobRecord.of(mob);
		String saved = rec.name(lang);
		if (!saved.isEmpty()) return saved;
		List<String> taken = new ArrayList<>();
		for (MobEntity other : mob.getEntityWorld().getEntitiesByClass(MobEntity.class, mob.getBoundingBox().expand(64),
			m -> m != mob && m.getType() == mob.getType())) {
			String n = MobRecord.of(other).name(lang);
			if (!n.isEmpty()) taken.add(n);
		}
		String name = Names.fresh(p, rnd, taken, lang);
		MobRecord.setName(mob, name, lang);
		return name;
	}

	@Deprecated
	public static String nameOf(MobEntity mob, MobProfiles.Profile p, Random rnd) {
		return nameOf(mob, p, rnd, Lang.RU);
	}

	/** Side-effect-free effective rank level (base rank + equipped armor / boss / slime size). */
	public static int levelWithArmor(MobEntity mob) {
		if (mob == null) return 1;
		if (isBoss(mob)) return Rank.NETHERITE.level();
		MobProfiles.Profile p = MobProfiles.of(mob.getType());
		if ("spider".equals(p.id())) return 1;
		int r = p.baseRank();
		if (mob instanceof SlimeEntity slime) {
			int size = slime.getSize();
			r = size >= 4 ? 3 : size >= 2 ? 2 : 1;
			if (mob instanceof MagmaCubeEntity) r++;
		}
		int armour = 0;
		for (EquipmentSlot s : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
			armour = Math.max(armour, Outfit.material(mob.getEquippedStack(s)));
		}
		return Math.max(1, Math.min(5, Math.max(r, armour)));
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
