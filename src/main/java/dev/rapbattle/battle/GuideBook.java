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
	public static final int PAGE_COUNT = 19;
	/** Bump when the pages change, so players' old copies get replaced on join. 2 = 6.30 icons and reference. */
	public static final String VERSION_KEY = "rapbattle_guide_version";
	public static final int VERSION = 2;
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
		tag.putInt(VERSION_KEY, VERSION);
		book.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(tag));
		return book;
	}

	/** Icon token in page text: {@code {i:iron_ingot}} = item sprite, {@code {b:lightning_rod}} = block sprite. */
	private static final java.util.regex.Pattern ICON = java.util.regex.Pattern.compile("\\{([ib]):([a-z0-9_]+)}");

	/** Formats the first line as a dark-blue heading and the rest as black body text; icon tokens become sprites. */
	public static Text formatPage(String raw) {
		int nl = raw.indexOf('\n');
		if (nl < 0) {
			return withIcons(raw, Formatting.DARK_BLUE);
		}
		MutableText heading = withIcons(raw.substring(0, nl), Formatting.DARK_BLUE);
		return heading.append(withIcons(raw.substring(nl), Formatting.BLACK));
	}

	private static MutableText withIcons(String raw, Formatting color) {
		MutableText out = Text.empty();
		java.util.regex.Matcher m = ICON.matcher(raw);
		int last = 0;
		while (m.find()) {
			if (m.start() > last) out.append(Text.literal(raw.substring(last, m.start())).formatted(color));
			boolean item = m.group(1).equals("i");
			out.append(Text.object(new net.minecraft.text.object.AtlasTextObjectContents(
				item ? net.minecraft.util.Atlases.ITEMS : net.minecraft.util.Atlases.BLOCKS,
				net.minecraft.util.Identifier.ofVanilla((item ? "item/" : "block/") + m.group(2))))
				.formatted(Formatting.WHITE)); // text colour tints sprites; white keeps the real colours
			last = m.end();
		}
		if (last < raw.length()) out.append(Text.literal(raw.substring(last)).formatted(color));
		return out;
	}

	/** Page length as the reader sees it: each icon token counts as one character. */
	public static int visibleLength(String raw) {
		return ICON.matcher(raw).replaceAll("#").length();
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
		if (player == null) return;
		refreshOutdated(player);
		if (hasGuideBook(player)) return;
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
		refreshOutdated(player);
	}

	/** Swaps every older edition of the handbook in the player's inventory for the current one, in place. */
	public static void refreshOutdated(ServerPlayerEntity player) {
		if (player == null) return;
		var inv = player.getInventory();
		for (int i = 0; i < inv.size(); i++) {
			ItemStack s = inv.getStack(i);
			if (isGuideBook(s) && versionOf(s) < VERSION) inv.setStack(i, create(Lang.of(player)));
		}
	}

	static int versionOf(ItemStack stack) {
		NbtComponent custom = stack.get(DataComponentTypes.CUSTOM_DATA);
		return custom == null ? 0 : custom.copyNbt().getInt(VERSION_KEY).orElse(1);
	}
}
