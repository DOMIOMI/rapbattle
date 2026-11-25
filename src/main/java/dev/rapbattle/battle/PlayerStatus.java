package dev.rapbattle.battle;

import dev.rapbattle.Lang;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared server-side timed status manager (e.g. Bastion Guest, Warden Quiet) with a unified actionbar timer display.
 */
public final class PlayerStatus {
	private PlayerStatus() {}

	public static final String BASTION_GUEST = "bastion_guest";
	public static final String WARDEN_QUIET = "warden_quiet";

	private static final Map<UUID, Map<String, Long>> STATUSES = new ConcurrentHashMap<>();

	public static void grant(UUID player, String key, long durationMs) {
		STATUSES.computeIfAbsent(player, k -> new ConcurrentHashMap<>())
			.put(key, System.currentTimeMillis() + durationMs);
	}

	public static boolean has(UUID player, String key) {
		Map<String, Long> map = STATUSES.get(player);
		if (map == null) return false;
		Long until = map.get(key);
		if (until == null) return false;
		if (System.currentTimeMillis() >= until) {
			map.remove(key);
			return false;
		}
		return true;
	}

	static void tick(MinecraftServer server) {
		if (server.getTicks() % 20 != 0) return;
		long now = System.currentTimeMillis();
		for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
			Map<String, Long> map = STATUSES.get(p.getUuid());
			if (map == null || map.isEmpty()) continue;
			map.entrySet().removeIf(e -> e.getValue() <= now);
			if (map.isEmpty()) continue;
			if (BattleManager.byPlayer(p.getUuid()) != null) continue;
			StringBuilder sb = new StringBuilder();
			for (var entry : map.entrySet()) {
				long sec = Math.max(0, (entry.getValue() - now) / 1000L);
				String timer = String.format("%d:%02d", sec / 60, sec % 60);
				if (sb.length() > 0) sb.append(" · ");
				sb.append(Lang.t(Lang.of(p), "rapbattle.status." + entry.getKey(), timer));
			}
			p.sendMessage(Text.literal(sb.toString()).formatted(Formatting.GOLD), true);
		}
	}
}
