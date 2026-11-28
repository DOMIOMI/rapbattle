package dev.rapbattle.battle;

import dev.rapbattle.Lang;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.DyedColorComponent;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;

/**
 * Dress code. Each armour slot scores by material (leather 1 … netherite 6, gold above iron because rap is
 * about flex, not protection), plus up to +1 "style" for trims, a full set, a matching dyed leather fit and glint.
 */
public final class Outfit {
	private Outfit() {}

	private static final EquipmentSlot[] SLOTS = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

	public record Score(double value, String note, boolean stylish) {
		public Score(double value, String note) {
			this(value, note, false);
		}

		/** Rank-style level 0..6 (0 = naked). */
		public int level() { return (int) Math.floor(value + 1e-6); }

		public String label(String lang) {
			int l = level();
			return l <= 0 ? (Lang.isRu(lang) ? "ГОЛЫЙ" : "NAKED") : Rank.ofLevel(l).armor(lang);
		}

		@Deprecated
		public String label() { return label(Lang.RU); }
	}

	@Deprecated
	public static Score of(LivingEntity e) { return of(e, Lang.RU); }

	public static Score of(LivingEntity e, String lang) {
		boolean ru = Lang.isRu(lang);
		double sum = 0, style = 0;
		int material = -1;
		boolean sameMaterial = true;
		Integer dye = null;
		boolean sameDye = true;
		int leather = 0;
		int trims = 0;
		StringBuilder note = new StringBuilder();
		for (EquipmentSlot slot : SLOTS) {
			ItemStack s = e.getEquippedStack(slot);
			int m = material(s);
			if (s.isOf(Items.CARVED_PUMPKIN)) {
				style -= 1;
				note.append(ru ? "тыква на голове; " : "pumpkin on head; ");
			}
			if (isMobHead(s)) {
				style += 0.5;
				note.append(ru ? "трофейная голова; " : "trophy head; ");
			}
			sum += Math.max(0, m);
			if (material == -1) material = m;
			else if (material != m) sameMaterial = false;
			if (s.get(DataComponentTypes.TRIM) != null) {
				style += 0.25;
				trims++;
			}
			if (s.hasGlint()) style += 0.1;
			if (m == 1) {
				leather++;
				DyedColorComponent c = s.get(DataComponentTypes.DYED_COLOR);
				Integer rgb = c == null ? null : c.rgb();
				if (rgb == null) sameDye = false;
				else if (dye == null) dye = rgb;
				else if (!rgb.equals(dye)) sameDye = false;
			}
		}
		Gangs.Gang set = Gangs.gangOf(e.getEquippedStack(EquipmentSlot.HEAD));
		boolean fullGang = set != null;
		for (EquipmentSlot slot : SLOTS) {
			Gangs.Gang g = Gangs.gangOf(e.getEquippedStack(slot));
			if (g == null || set == null || !g.id().equals(set.id())) fullGang = false;
		}
		if (fullGang) {
			style += 0.5;
			if (ru) note.append("форма банды «").append(set.name(lang)).append("»; ");
			else note.append("full ").append(set.name(lang)).append(" crew fit; ");
		} else if (sameMaterial && material > 0) {
			style += 0.25;
			note.append(ru ? "полный комплект; " : "full matching set; ");
		}
		boolean matchingDyed = leather == 4 && sameDye && dye != null;
		if (matchingDyed) {
			style += 0.5;
			note.append(ru ? "кожанка в цвет; " : "matching dyed leather; ");
		}
		boolean stylish = fullGang || matchingDyed || trims >= 1;
		style = Math.max(-1, Math.min(1, style));
		double value = Math.max(0, sum / 4.0 + style);
		return new Score(value, note.toString().trim(), stylish);
	}

	/** 0 empty / not armour, 1 leather, 2 chain or copper, 3 iron (and turtle), 4 gold (and elytra), 5 diamond, 6 netherite. */
	static int material(ItemStack s) {
		if (s.isEmpty()) return 0;
		Gangs.Gang gang = Gangs.gangOf(s);
		if (gang != null) return gang.tier(); // street cred counts like real armour
		if (s.isOf(Items.ELYTRA)) return 4;
		if (s.isOf(Items.TURTLE_HELMET)) return 3;
		String id = Registries.ITEM.getId(s.getItem()).getPath();
		if (id.startsWith("leather_")) return 1;
		if (id.startsWith("chainmail_")) return 2;
		if (id.startsWith("copper_")) return 2; // craftable early, unlike chainmail
		if (id.startsWith("iron_")) return 3;
		if (id.startsWith("golden_")) return 4;
		if (id.startsWith("diamond_")) return 5;
		if (id.startsWith("netherite_")) return 6;
		return 0;
	}

	private static boolean isMobHead(ItemStack s) {
		String id = Registries.ITEM.getId(s.getItem()).getPath();
		return id.endsWith("_head") || id.endsWith("_skull");
	}

	/** Lowest outfit level the mob accepts as challenger (one rank of slack). */
	public static int required(Rank rank) { return Math.max(0, rank.level() - 1); }

	/** Lowest level for a teammate to join (two ranks of slack, so one poor friend doesn't kill the lobby). */
	public static int requiredToJoin(Rank rank) { return Math.max(0, rank.level() - 2); }
}
