package dev.rapbattle.battle;

import dev.rapbattle.Lang;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** Microphones for beating legends and bosses, signed with who got destroyed and when. */
public final class Trophies {
	private Trophies() {}

	public static final String NBT_KEY = "rapbattle_trophy";
	public static final String NBT_TIER_KEY = "rapbattle_trophy_tier";
	public static final String TIER_HIGH = "high";

	public enum TrophyPerk {
		HOMEMADE("homemade"),
		WARDEN("warden"),
		ENDER_DRAGON("ender_dragon"),
		WITHER("wither"),
		ELDER_GUARDIAN("elder_guardian"),
		DEFAULT("default");

		public final String key;

		TrophyPerk(String key) {
			this.key = key;
		}

		public static TrophyPerk ofKey(String k) {
			if (k != null) {
				for (TrophyPerk p : values()) {
					if (p.key.equals(k)) return p;
				}
			}
			return DEFAULT;
		}

		public String triggeredNote(String lang) {
			return Lang.t(lang, "rapbattle.trophies.triggered." + key);
		}
	}

	/** Returns the perk of the trophy microphone held in the player's off-hand, or null if none. */
	public static TrophyPerk equippedPerk(ServerPlayerEntity player) {
		if (player == null) return null;
		ItemStack off = player.getOffHandStack();
		if (off.isEmpty()) return null;
		NbtComponent custom = off.get(DataComponentTypes.CUSTOM_DATA);
		if (custom != null) {
			NbtCompound nbt = custom.copyNbt();
			if (nbt.contains(NBT_KEY)) {
				return TrophyPerk.ofKey(nbt.getString(NBT_KEY).orElse("default"));
			}
		}
		// Fallback for trophy mics crafted before Part 4 (glinted end_rod / lightning_rod).
		if ((off.isOf(Items.END_ROD) || off.isOf(Items.LIGHTNING_ROD))
			&& Boolean.TRUE.equals(off.get(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE))) {
			return TrophyPerk.DEFAULT;
		}
		return null;
	}

	/** Returns true if the player holds any microphone (including homemade) in the off-hand. */
	public static boolean hasAnyMic(ServerPlayerEntity player) {
		return equippedPerk(player) != null;
	}

	/** Returns true if the player holds a non-homemade trophy microphone in the off-hand. */
	public static boolean isTrophyMic(ServerPlayerEntity player) {
		TrophyPerk perk = equippedPerk(player);
		return perk != null && perk != TrophyPerk.HOMEMADE;
	}

	/**
	 * Returns true if the player holds a high-tier trophy microphone (from a rank 5+ mob or boss)
	 * in the off-hand. Legacy trophies created before 6.17 (without NBT_TIER_KEY) are treated as high-tier.
	 */
	public static boolean hasHighTierTrophyMic(ServerPlayerEntity player) {
		if (!isTrophyMic(player)) return false;
		ItemStack off = player.getOffHandStack();
		NbtComponent custom = off.get(DataComponentTypes.CUSTOM_DATA);
		if (custom != null) {
			NbtCompound nbt = custom.copyNbt();
			if (nbt.contains(NBT_TIER_KEY)) {
				return TIER_HIGH.equals(nbt.getString(NBT_TIER_KEY).orElse(""));
			}
		}
		return true;
	}

	@Deprecated
	public static void give(ServerPlayerEntity mvp, Opponent opp) { give(mvp, opp, Lang.RU); }

	public static void give(ServerPlayerEntity mvp, Opponent opp, String lang) {
		if (mvp == null || opp.rank().level() < 5) return;
		String id = opp.profile().id();
		String micKey = switch (id) {
			case "ender_dragon", "wither", "warden", "elder_guardian" -> id;
			default -> "default";
		};
		Text name = Text.translatable("rapbattle.trophies.mic." + micKey);
		ItemStack mic = new ItemStack(opp.rank().isBoss() ? Items.END_ROD : Items.LIGHTNING_ROD);
		mic.set(DataComponentTypes.CUSTOM_NAME, name.copy().styled(s -> s.withItalic(false).withColor(opp.rank().isBoss() ? 0xB48CFF : 0xF2C94C)));
		mic.set(DataComponentTypes.LORE, new LoreComponent(List.of(
			Text.translatable("rapbattle.trophies.perk." + micKey).formatted(Formatting.AQUA),
			Text.translatable("rapbattle.trophies.lore.crushed", opp.name(), opp.profile().species(lang)).formatted(Formatting.GOLD),
			Text.translatable("rapbattle.trophies.lore.rank", opp.rank().label(lang)).formatted(Formatting.GRAY),
			Text.translatable("rapbattle.trophies.lore.mvp", mvp.getName().getString(), LocalDate.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy")))
				.formatted(Formatting.DARK_GRAY))));
		mic.set(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
		NbtCompound tag = new NbtCompound();
		tag.putString(NBT_KEY, micKey);
		tag.putString(NBT_TIER_KEY, TIER_HIGH);
		mic.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(tag));
		mvp.giveItemStack(mic);
		mvp.sendMessage(Text.translatable("rapbattle.trophies.got", name).formatted(Formatting.LIGHT_PURPLE), false);
	}
}
