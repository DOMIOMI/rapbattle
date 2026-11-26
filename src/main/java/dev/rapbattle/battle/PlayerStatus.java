package dev.rapbattle.battle;

import dev.rapbattle.Lang;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared server-side timed status manager (e.g. Bastion Guest, Warden Quiet) with a unified actionbar timer display.
 */
public final class PlayerStatus {
	private PlayerStatus() {}

	public static final String BASTION_GUEST = "bastion_guest";
	public static final String WARDEN_QUIET = "warden_quiet";

	private record Entry(long until, RegistryKey<World> world, BlockPos center, double radiusSq) {}

	private static final Map<UUID, Map<String, Entry>> STATUSES = new ConcurrentHashMap<>();

	static void clear() {
		STATUSES.clear();
	}

	public static void grant(UUID player, String key, long durationMs) {
		grant(player, key, durationMs, null, null, 0);
	}

	public static void grant(UUID player, String key, long durationMs, BlockPos center, int radius) {
		grant(player, key, durationMs, null, center, radius);
	}

	public static void grant(UUID player, String key, long durationMs, RegistryKey<World> world, BlockPos center, int radius) {
		STATUSES.computeIfAbsent(player, k -> new ConcurrentHashMap<>())
			.put(key, new Entry(System.currentTimeMillis() + durationMs, world, center, (double) radius * radius));
	}

	public static boolean has(UUID player, String key) {
		return getValid(player, key) != null;
	}

	public static boolean hasInRadius(UUID player, String key, BlockPos pos) {
		return hasInRadius(player, key, null, pos);
	}

	public static boolean hasInRadius(UUID player, String key, ServerWorld world, BlockPos pos) {
		Entry e = getValid(player, key);
		if (e == null) return false;
		if (e.world() != null && world != null && !e.world().equals(world.getRegistryKey())) return false;
		if (e.center() == null || e.radiusSq() <= 0) return true;
		return pos != null && pos.getSquaredDistance(e.center()) <= e.radiusSq();
	}

	private static Entry getValid(UUID player, String key) {
		Map<String, Entry> map = STATUSES.get(player);
		if (map == null) return null;
		Entry e = map.get(key);
		if (e == null) return null;
		if (System.currentTimeMillis() >= e.until()) {
			map.remove(key);
			return null;
		}
		return e;
	}

	static void tick(MinecraftServer server) {
		if (server.getTicks() % 20 != 0) return;
		long now = System.currentTimeMillis();
		for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
			Map<String, Entry> map = STATUSES.get(p.getUuid());
			if (map == null || map.isEmpty()) continue;
			map.entrySet().removeIf(e -> e.getValue().until() <= now);
			if (map.isEmpty()) continue;
			if (BattleManager.byPlayer(p.getUuid()) != null
				|| PvpBattle.byPlayer(p.getUuid()) != null
				|| BattleManager.hasPendingForTest(p.getUuid())) continue;
			ServerWorld w = p.getEntityWorld();
			BlockPos pos = p.getBlockPos();
			StringBuilder sb = new StringBuilder();
			for (var entry : map.entrySet()) {
				String key = entry.getKey();
				if (WARDEN_QUIET.equals(key) && !hasInRadius(p.getUuid(), WARDEN_QUIET, w, pos)) continue;
				if (BASTION_GUEST.equals(key) && (w.getRegistryKey() != World.NETHER || !BastionBattle.inBastion(w, pos))) continue;
				long sec = Math.max(0, (entry.getValue().until() - now) / 1000L);
				String timer = String.format("%d:%02d", sec / 60, sec % 60);
				if (sb.length() > 0) sb.append(" · ");
				sb.append(Lang.t(Lang.of(p), "rapbattle.status." + key, timer));
			}
			if (sb.length() > 0) {
				p.sendMessage(Text.literal(sb.toString()).formatted(Formatting.GOLD), true);
			}
		}
	}
}
