package dev.rapbattle.battle;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The rhyme words each player got in their last battles (6.32), so a rematch or the next battle hands out new ones
 * instead of the same set: the model is asked to avoid them and any group that repeats one is dropped.
 * Kept in memory for the server's lifetime only.
 */
public final class RecentRhymes {
	private RecentRhymes() {}

	public static final int KEEP = 48;
	private static final Map<UUID, Deque<String>> RECENT = new HashMap<>();

	private static String norm(String w) {
		return w == null ? "" : w.trim().toLowerCase(Locale.ROOT).replace('ё', 'е');
	}

	public static synchronized void remember(UUID player, Collection<String> words) {
		Deque<String> d = RECENT.computeIfAbsent(player, k -> new ArrayDeque<>());
		for (String w : words) {
			String n = norm(w);
			if (n.isEmpty()) continue;
			d.remove(n);
			d.addLast(n);
			while (d.size() > KEEP) d.removeFirst();
		}
	}

	/** All recent words of these players, oldest first. */
	public static synchronized Set<String> of(Collection<UUID> players) {
		Set<String> out = new LinkedHashSet<>();
		for (UUID u : players) {
			Deque<String> d = RECENT.get(u);
			if (d != null) out.addAll(d);
		}
		return out;
	}

	/** True if the rhyme group repeats any recent word. */
	public static boolean repeats(List<String> group, Set<String> recent) {
		if (recent.isEmpty()) return false;
		for (String w : group) if (recent.contains(norm(w))) return true;
		return false;
	}

	public static synchronized void clear() {
		RECENT.clear();
	}
}
