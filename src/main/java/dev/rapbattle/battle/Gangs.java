package dev.rapbattle.battle;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.DyedColorComponent;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.*;

/**
 * Gang gear: every mob crew has its own dyed street outfit (bandana, jacket, track pants, sneakers).
 * It drops from beaten mobs, counts in the dress code as a real armour tier (street cred, not protection),
 * a full set adds style, and mobs respect their own crew's colours: they never refuse someone wearing them.
 * Plain vanilla leather items with components, so nothing new has to be registered on the client.
 */
public final class Gangs {
	private Gangs() {}

	public record Gang(String id, String name, int color, int tier, String members) {}

	private static final String TAG = "rapbattle_gang";
	private static final Map<String, Gang> BY_KIN = new HashMap<>();

	private static void g(String kin, String name, int color, int tier, String members) {
		BY_KIN.put(kin, new Gang(kin, name, color, tier, members));
	}

	static {
		g("zombie", "Гниль", 0x4C7A34, 3, "зомби, кадавры, утопленники");
		g("skeleton", "Кости", 0xE8E2D0, 3, "скелеты, зимогоры, болотники");
		g("illager", "Рейд", 0x5A5F66, 4, "разбойники, поборники, вызыватели, ведьмы");
		g("piglin", "Голд", 0xF2C94C, 4, "пиглины и бруты");
		g("zombified_piglin", "Гнилой Голд", 0x9C6B5A, 3, "зомби-пиглины");
		g("spider", "Паутина", 0x2B1B1B, 3, "пауки");
		g("villager", "Деревня", 0x8B5A2B, 3, "жители, торговцы, големы");
		g("enderman", "Варп", 0x2A1240, 4, "эндермены");
		g("creeper", "Тротил", 0x3E8E3E, 4, "криперы");
		g("wolf", "Стая", 0x9AA0A6, 3, "волки");
		g("sheep", "Шерсть", 0xF0F0F0, 2, "овцы");
		g("cow", "Ферма", 0x5C4033, 2, "коровы");
		g("pig", "Хрю", 0xF4A6B8, 2, "свиньи");
		g("chicken", "Курятник", 0xD93A3A, 1, "курицы");
		g("slime", "Слизь", 0x7CCF5C, 2, "слизни и магмовые кубы");
		g("blaze", "Огонь", 0xE86A17, 4, "ифриты");
		g("bee", "Улей", 0xF2C230, 2, "пчёлы");
		g("guardian", "Глубина", 0x3E7C87, 4, "стражи");
		g("hoglin", "Кабаны", 0x8B4A3C, 5, "хоглины");
		g("ghast", "Слёзы", 0xDCDCE6, 4, "гасты");
	}

	private static final Item[] PIECES = {Items.LEATHER_HELMET, Items.LEATHER_CHESTPLATE, Items.LEATHER_LEGGINGS, Items.LEATHER_BOOTS};
	private static final String[] PIECE_NAMES = {"Бандана", "Куртка", "Треники", "Кроссы"};

	public static Gang of(MobProfiles.Profile p) { return BY_KIN.get(p.kin()); }

	/** One piece of the gang's outfit: 0 bandana, 1 jacket, 2 track pants, 3 sneakers. */
	public static ItemStack piece(Gang g, int index) {
		ItemStack s = new ItemStack(PIECES[index]);
		s.set(DataComponentTypes.DYED_COLOR, new DyedColorComponent(g.color()));
		s.set(DataComponentTypes.CUSTOM_NAME, Text.literal(PIECE_NAMES[index] + " банды «" + g.name() + "»")
			.styled(st -> st.withItalic(false).withColor(g.color() == 0xF0F0F0 || g.color() == 0xE8E2D0 ? 0xFFFFFF : g.color())));
		s.set(DataComponentTypes.LORE, new LoreComponent(List.of(
			Text.literal("Шмот банды «" + g.name() + "»").formatted(Formatting.GOLD),
			Text.literal("Свой у: " + g.members()).formatted(Formatting.GRAY),
			Text.literal("Дресс-код: считается как " + Rank.ofLevel(g.tier()).armor).formatted(Formatting.AQUA),
			Text.literal("Полный комплект: +стиль, свои не отказывают").formatted(Formatting.DARK_GRAY))));
		NbtCompound nbt = new NbtCompound();
		nbt.putString(TAG, g.id());
		s.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(nbt));
		return s;
	}

	/** The gang of a gear piece, or null. */
	public static Gang gangOf(ItemStack s) {
		if (s.isEmpty()) return null;
		NbtComponent c = s.get(DataComponentTypes.CUSTOM_DATA);
		if (c == null) return null;
		String id = c.copyNbt().getString(TAG, "");
		return id.isEmpty() ? null : BY_KIN.get(id);
	}

	/** How many pieces of this gang's gear the entity wears. */
	public static int wearing(LivingEntity e, Gang g) {
		if (g == null) return 0;
		int n = 0;
		for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
			Gang w = gangOf(e.getEquippedStack(slot));
			if (w != null && w.id().equals(g.id())) n++;
		}
		return n;
	}

	/** Two pieces or more of the mob's crew colours: you're one of them. */
	public static boolean isOwn(LivingEntity player, MobProfiles.Profile mob) {
		return wearing(player, of(mob)) >= 2;
	}

	/** Mobs whose models render a helmet: they wear their crew bandana on stage. */
	private static final Set<String> SHOWS_HELMET = Set.of("zombie", "husk", "drowned", "zombie_villager", "skeleton", "stray",
		"bogged", "parched", "wither_skeleton", "piglin", "piglin_brute", "zombified_piglin");

	/** Puts the crew bandana on the mob for the battle. Returns true if it did (so it can be taken off later). */
	public static boolean dressUp(MobEntity m) {
		MobProfiles.Profile p = MobProfiles.of(m.getType());
		Gang g = of(p);
		if (g == null || !SHOWS_HELMET.contains(p.id()) || !m.getEquippedStack(EquipmentSlot.HEAD).isEmpty()) return false;
		m.equipStack(EquipmentSlot.HEAD, piece(g, 0));
		m.setEquipmentDropChance(EquipmentSlot.HEAD, 0f);
		return true;
	}

	public static void undress(MobEntity m) {
		if (gangOf(m.getEquippedStack(EquipmentSlot.HEAD)) != null) m.equipStack(EquipmentSlot.HEAD, ItemStack.EMPTY);
	}

	/** Chance in percent that a beaten mob drops a piece of its gang gear. */
	public static int dropChance(Rank r) { return r.isBoss() ? 0 : 25 + 10 * r.level(); }

	public static ItemStack randomPiece(Gang g, Random rnd) { return piece(g, rnd.nextInt(4)); }
}
