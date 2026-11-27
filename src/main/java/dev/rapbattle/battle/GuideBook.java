package dev.rapbattle.battle;

import dev.rapbattle.Lang;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.component.type.WrittenBookContentComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.MutableText;
import net.minecraft.text.RawFilteredPair;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * In-game handbook ("MC Handbook" / "Книга МС") built on vanilla written_book.
 */
public final class GuideBook {
	private GuideBook() {}

	public static final String NBT_KEY = "rapbattle_guide";
	public static final int PAGE_COUNT = 14;
	public static final int MAX_PAGE_CHARS = 240;
	private static final Map<UUID, Long> GIVE_COOLDOWN = new HashMap<>();
	private static final long GIVE_COOLDOWN_MS = 5_000L;

	public static ItemStack create(String lang) {
		ItemStack book = new ItemStack(Items.WRITTEN_BOOK);
		List<RawFilteredPair<Text>> pages = new ArrayList<>(PAGE_COUNT);
		for (int i = 1; i <= PAGE_COUNT; i++) {
			String raw = Lang.t(lang, "rapbattle.guide.page." + i);
			pages.add(RawFilteredPair.of(formatPage(raw)));
		}
		String title = Lang.t(lang, "rapbattle.guide.title");
		if (title.length() > WrittenBookContentComponent.MAX_TITLE_LENGTH) {
			title = title.substring(0, WrittenBookContentComponent.MAX_TITLE_LENGTH);
		}
		book.set(DataComponentTypes.WRITTEN_BOOK_CONTENT,
			new WrittenBookContentComponent(RawFilteredPair.of(title), "DOMI", 0, pages, true));
		NbtCompound tag = new NbtCompound();
		tag.putInt(NBT_KEY, 1);
		book.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(tag));
		return book;
	}

	/** Formats the first line as a dark-blue heading and the rest as black body text. */
	public static Text formatPage(String raw) {
		int nl = raw.indexOf('\n');
		if (nl < 0) {
			return Text.literal(raw).formatted(Formatting.DARK_BLUE);
		}
		MutableText heading = Text.literal(raw.substring(0, nl)).formatted(Formatting.DARK_BLUE);
		MutableText body = Text.literal(raw.substring(nl)).formatted(Formatting.BLACK);
		return heading.append(body);
	}

	public static boolean isGuideBook(ItemStack stack) {
		if (stack == null || stack.isEmpty() || !stack.isOf(Items.WRITTEN_BOOK)) return false;
		NbtComponent custom = stack.get(DataComponentTypes.CUSTOM_DATA);
		if (custom == null) return false;
		return custom.copyNbt().getInt(NBT_KEY).orElse(0) == 1;
	}

	public static boolean hasGuideBook(ServerPlayerEntity player) {
		if (player == null) return false;
		var inv = player.getInventory();
		for (int i = 0; i < inv.size(); i++) {
			if (isGuideBook(inv.getStack(i))) return true;
		}
		return false;
	}

	/** Gives the handbook in the player's current language; drops at feet if inventory is full. */
	public static void give(ServerPlayerEntity player) {
		if (player == null || hasGuideBook(player)) return;
		long now = dev.rapbattle.Clock.now();
		if (now < GIVE_COOLDOWN.getOrDefault(player.getUuid(), 0L)) return;
		GIVE_COOLDOWN.put(player.getUuid(), now + GIVE_COOLDOWN_MS);
		ItemStack book = create(Lang.of(player));
		if (!player.getInventory().insertStack(book)) {
			player.dropItem(book, false);
		}
	}

	/** Gives the handbook only on the player's very first join to this world. */
	public static void giveOnFirstJoin(ServerPlayerEntity player, net.minecraft.server.MinecraftServer server) {
		if (player == null || server == null) return;
		Stats stats = Stats.of(server);
		Stats.Player sp = stats.player(player.getUuid());
		if (!sp.guideGiven) {
			sp.guideGiven = true;
			stats.markDirty();
			give(player);
		}
	}
}
