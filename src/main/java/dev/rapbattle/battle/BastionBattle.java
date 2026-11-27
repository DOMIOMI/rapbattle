package dev.rapbattle.battle;

import net.minecraft.entity.mob.AbstractPiglinEntity;
import net.minecraft.entity.mob.PiglinBruteEntity;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.structure.StructureStart;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.gen.structure.Structure;
import net.minecraft.world.gen.structure.StructureKeys;

import java.util.*;

/**
 * Act 3: Bastion Piglin Brute challenges, 24-block piglin crowd, 10-minute Bastion Guest immunity, and loss aggro.
 */
public final class BastionBattle {
	private BastionBattle() {}

	private static final Map<UUID, Long> CHALLENGED_BRUTES = new HashMap<>();
	private static net.minecraft.util.math.BlockBox testBastionBox;

	/** Test-only helper to mark a bounding box as a bastion when structure generation is disabled. */
	public static void setTestBastionBox(net.minecraft.util.math.BlockBox box) {
		testBastionBox = box;
	}

	static void clear() {
		CHALLENGED_BRUTES.clear();
		testBastionBox = null;
	}

	public static boolean inBastion(ServerWorld world, BlockPos pos) {
		if (testBastionBox != null && testBastionBox.contains(pos)) return true;
		var reg = world.getRegistryManager().getOrThrow(RegistryKeys.STRUCTURE);
		Optional<Structure> bastion = reg.getOptionalValue(StructureKeys.BASTION_REMNANT);
		if (bastion.isEmpty()) return false;
		StructureStart start = world.getStructureAccessor().getStructureContaining(pos, bastion.get());
		return start != null && start.hasChildren();
	}

	static void tick(MinecraftServer server) {
		if (server.getTicks() % 20 != 0) return;
		long now = dev.rapbattle.Clock.now();
		CHALLENGED_BRUTES.entrySet().removeIf(e -> now > e.getValue());
		for (ServerWorld w : server.getWorlds()) {
			for (ServerPlayerEntity p : w.getPlayers()) {
				if (!BattleManager.canAutoChallenge(p)) continue;
				if (PlayerStatus.has(p.getUuid(), PlayerStatus.BASTION_GUEST)) continue;
				if (!inBastion(w, p.getBlockPos())) continue;
				List<PiglinBruteEntity> brutes = w.getEntitiesByClass(PiglinBruteEntity.class,
					p.getBoundingBox().expand(16),
					b -> b.isAlive() && BattleManager.byMob(b) == null && !CHALLENGED_BRUTES.containsKey(b.getUuid()));
				if (brutes.isEmpty()) continue;
				brutes.sort(Comparator.comparingDouble(b -> b.squaredDistanceTo(p)));
				PiglinBruteEntity brute = brutes.get(0);
				if (!Progression.canAutoChallengeMob(p, brute)) continue;
				CHALLENGED_BRUTES.put(brute.getUuid(), now + 120_000L);
				brute.setTarget(null);
				BattleManager.challengeFrom(p, brute, "rapbattle.manager.brute_challenge");
			}
		}
	}

	static void onWin(ServerWorld world, List<UUID> players, BlockPos pos) {
		for (UUID u : players) {
			ServerPlayerEntity p = world.getServer().getPlayerManager().getPlayer(u);
			if (p != null) {
				p.sendMessage(Text.translatable("rapbattle.act3.bastion_guest").formatted(Formatting.GOLD), false);
			}
		}
	}

	public static void onLoss(ServerWorld world, ServerPlayerEntity loser, BlockPos pos) {
		if (loser == null) return;
		UUID loserId = loser.getUuid();
		Later.run(Reputation.RUN_TRUCE_MS, () -> {
			ServerPlayerEntity p = world.getServer().getPlayerManager().getPlayer(loserId);
			if (p == null || !p.isAlive() || p.getEntityWorld() != world) return;
			List<AbstractPiglinEntity> piglins = world.getEntitiesByClass(AbstractPiglinEntity.class,
				p.getBoundingBox().expand(32),
				m -> m.isAlive() && m.squaredDistanceTo(pos.getX(), pos.getY(), pos.getZ()) <= 32 * 32);
			for (AbstractPiglinEntity pig : piglins) {
				Crowd.breakTruceFor(pig, loserId);
				BattleManager.forceMobTarget(pig, p);
			}
		});
	}
}
