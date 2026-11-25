package dev.rapbattle.battle;

import dev.rapbattle.Lang;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** Microphones for beating legends and bosses, signed with who got destroyed and when. */
public final class Trophies {
	private Trophies() {}

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
			Text.translatable("rapbattle.trophies.lore.crushed", opp.name(), opp.profile().species(lang)).formatted(Formatting.GOLD),
			Text.translatable("rapbattle.trophies.lore.rank", opp.rank().label(lang)).formatted(Formatting.GRAY),
			Text.translatable("rapbattle.trophies.lore.mvp", mvp.getName().getString(), LocalDate.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy")))
				.formatted(Formatting.DARK_GRAY))));
		mic.set(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
		mvp.giveItemStack(mic);
		mvp.sendMessage(Text.translatable("rapbattle.trophies.got", name).formatted(Formatting.LIGHT_PURPLE), false);
	}
}
