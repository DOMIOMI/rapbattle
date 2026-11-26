package dev.rapbattle.battle;

import java.util.List;
import java.util.Set;

/**
 * Pure, Minecraft-registry-free item selection rules for post-loss stealing (6.16).
 */
public final class TheftRules {
	private TheftRules() {}

	public static final int STEAL_MARGIN = 35;

	public static final Set<String> MINOR_ITEM_IDS = Set.of(
		"wheat_seeds", "wheat", "bread", "apple", "carrot", "potato",
		"beef", "cooked_beef", "porkchop", "cooked_porkchop",
		"chicken", "cooked_chicken", "mutton", "cooked_mutton",
		"rabbit", "cooked_rabbit",
		"gold_nugget", "iron_nugget", "string", "bone", "feather", "coal"
	);

	public static final Set<String> VALUABLE_ITEM_IDS = Set.of(
		"emerald", "gold_ingot", "iron_ingot", "lapis_lazuli",
		"redstone", "ender_pearl", "blaze_rod", "diamond"
	);

	public record SlotItem(String itemId, boolean forbidden) {}

	/** Returns the slot index to steal from, or -1 if nothing in the inventory is eligible. */
	public static int pickStealIndex(List<SlotItem> inv, int selectedSlot, int rankLevel, boolean peaceful) {
		if (inv == null || inv.isEmpty()) return -1;
		if (peaceful) {
			return findFirst(inv, MINOR_ITEM_IDS);
		}
		if (rankLevel >= 5 && selectedSlot >= 0 && selectedSlot < inv.size()) {
			SlotItem held = inv.get(selectedSlot);
			if (held != null && !held.forbidden() && !held.itemId().isEmpty()) {
				return selectedSlot;
			}
		}
		if (rankLevel >= 4) {
			for (int i = 0; i < inv.size(); i++) {
				SlotItem st = inv.get(i);
				if (st == null || st.forbidden()) continue;
				if ("diamond".equals(st.itemId()) && rankLevel < 5) continue;
				if (VALUABLE_ITEM_IDS.contains(st.itemId())) return i;
			}
		}
		return findFirst(inv, MINOR_ITEM_IDS);
	}

	private static int findFirst(List<SlotItem> inv, Set<String> allowed) {
		for (int i = 0; i < inv.size(); i++) {
			SlotItem st = inv.get(i);
			if (st != null && !st.forbidden() && allowed.contains(st.itemId())) return i;
		}
		return -1;
	}
}
