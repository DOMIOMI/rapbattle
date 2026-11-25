package dev.rapbattle.battle;

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

	public static void give(ServerPlayerEntity mvp, Opponent opp) {
		if (mvp == null || opp.rank().level() < 5) return;
		String id = opp.profile().id();
		String name = switch (id) {
			case "ender_dragon" -> "Микрофон Края";
			case "wither" -> "Трёхглавый микрофон";
			case "warden" -> "Платиновый микрофон";
			case "elder_guardian" -> "Глубоководный микрофон";
			default -> "Золотой микрофон";
		};
		ItemStack mic = new ItemStack(opp.rank().isBoss() ? Items.END_ROD : Items.LIGHTNING_ROD);
		mic.set(DataComponentTypes.CUSTOM_NAME, Text.literal(name).styled(s -> s.withItalic(false).withColor(opp.rank().isBoss() ? 0xB48CFF : 0xF2C94C)));
		mic.set(DataComponentTypes.LORE, new LoreComponent(List.of(
			Text.literal("Разъебал: " + opp.name() + " (" + opp.profile().species() + ")").formatted(Formatting.GOLD),
			Text.literal("Ранг: " + opp.rank().label()).formatted(Formatting.GRAY),
			Text.literal("MVP: " + mvp.getName().getString() + " · " + LocalDate.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy")))
				.formatted(Formatting.DARK_GRAY))));
		mic.set(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
		mvp.giveItemStack(mic);
		mvp.sendMessage(Text.literal("Трофей: " + name).formatted(Formatting.LIGHT_PURPLE), false);
	}
}
