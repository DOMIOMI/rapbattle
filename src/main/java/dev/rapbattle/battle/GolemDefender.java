package dev.rapbattle.battle;

import dev.rapbattle.Lang;
import net.minecraft.entity.passive.IronGolemEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.*;

/**
 * The village iron golem protects its people: hit a villager or lose a battle to one, and the golem walks up
 * to you, says its piece and calls you out. Answer on the mic, or it's a fistfight.
 */
public final class GolemDefender {
	private GolemDefender() {}

	private record Hunt(IronGolemEntity golem, UUID player, long until) {}

	private static final List<Hunt> HUNTS = new ArrayList<>();
	private static final List<String> LINES = List.of(
		"Ты обидел моих людей. Теперь отвечай за базар.",
		"Деревня под моей защитой. Микрофон или кулак?",
		"*БОНГ* Жители говорят, ты тут борзый.");
	private static final List<String> LINES_EN = List.of(
		"You messed with my people. Now answer for it.",
		"This village is under my protection. Mic or fists?",
		"*CLANG* Word around the village is you're talking tough.");

	/** A player wronged a villager. The nearest free golem comes for them. */
	public static void offended(ServerPlayerEntity player, VillagerEntity villager) {
		if (!(villager.getEntityWorld() instanceof ServerWorld w)) return;
		for (Hunt h : HUNTS) if (h.player().equals(player.getUuid())) return;
		List<IronGolemEntity> golems = w.getEntitiesByClass(IronGolemEntity.class, villager.getBoundingBox().expand(32),
			g -> g.isAlive() && !g.isPlayerCreated() && BattleManager.byMob(g) == null);
		if (golems.isEmpty()) return;
		golems.sort(Comparator.comparingDouble(g -> g.squaredDistanceTo(player)));
		IronGolemEntity g = golems.get(0);
		HUNTS.add(new Hunt(g, player.getUuid(), dev.rapbattle.Clock.now() + 30_000));
	}

	/** Server tick: walk to the offender, then call them out. */
	static void tick() {
		long now = dev.rapbattle.Clock.now();
		for (Hunt h : new ArrayList<>(HUNTS)) {
			IronGolemEntity g = h.golem();
			ServerPlayerEntity p = g.getEntityWorld().getServer() == null ? null
				: g.getEntityWorld().getServer().getPlayerManager().getPlayer(h.player());
			if (p == null || !g.isAlive() || p.getEntityWorld() != g.getEntityWorld() || now > h.until() || BattleManager.byPlayer(p.getUuid()) != null) {
				HUNTS.remove(h);
				continue;
			}
			g.setTarget(null); // no punching yet: first the words
			if (g.squaredDistanceTo(p) > 4.5 * 4.5) {
				g.getNavigation().startMovingTo(p, 1.0);
				continue;
			}
			HUNTS.remove(h);
			g.getNavigation().stop();
			g.getLookControl().lookAt(p, 30, 30);
			int idx = new Random().nextInt(LINES.size());
			String line = Lang.pick(Lang.of(p), LINES, LINES_EN).get(idx);
			Speech.bubble(g, line, 0xFFE0A0, 4000);
			for (ServerPlayerEntity near : ((ServerWorld) g.getEntityWorld()).getPlayers()) {
				if (near.squaredDistanceTo(g) < 32 * 32) {
					near.sendMessage(Text.translatable("rapbattle.golem.callout",
						Opponent.nameOf(g, MobProfiles.of(g.getType()), new Random(), Lang.of(near)),
						Text.translatable("rapbattle.golem.line." + idx))
						.formatted(Formatting.GOLD), false);
				}
			}
			BattleManager.challengeFrom(p, g);
		}
	}

	/** The player didn't answer the golem's call: a normal fight, and everyone hears about it. */
	public static void chickenedOut(ServerPlayerEntity p, IronGolemEntity g) {
		BattleManager.forceMobTarget(g, p);
		for (ServerPlayerEntity near : ((ServerWorld) g.getEntityWorld()).getPlayers()) {
			if (near.squaredDistanceTo(g) < 48 * 48) {
				near.sendMessage(Text.translatable("rapbattle.golem.chickened", p.getName().getString(),
					Opponent.nameOf(g, MobProfiles.of(g.getType()), new Random(), Lang.of(near)))
					.formatted(Formatting.GRAY), false);
			}
		}
	}

	/** Lost the battle to the players: the golem accepts it, stays alive, and the village respects them. */
	@Deprecated
	public static void respect(IronGolemEntity g, ServerPlayerEntity winner) { respect(g, winner, Lang.RU); }

	public static void respect(IronGolemEntity g, ServerPlayerEntity winner, String lang) {
		g.setTarget(null);
		g.stopAnger();
		if (winner != null && g.getEntityWorld() instanceof ServerWorld sw) {
			long day = ProgressionRules.currentDay(sw.getServer().getOverworld().getTime());
			Stats stats = Stats.of(sw.getServer());
			Stats.Player st = stats.player(winner.getUuid());
			if (st.dailyWinsFor("iron_golem_reward", day) > 0) {
				Speech.bubble(g, Lang.pick(lang, "*БОНГ* …Мы уже в расчёте на сегодня.", "*CLANG* …We're already square for today."), 0xFFE0A0, 3500);
				return;
			}
			st.recordDailyWin("iron_golem_reward", day);
			stats.markDirty();
			Speech.bubble(g, Lang.pick(lang, "*БОНГ* …Уважаю. Деревня твоя.", "*CLANG* …Respect. The village is yours."), 0xA0FFA0, 4000);
			winner.giveItemStack(new net.minecraft.item.ItemStack(net.minecraft.item.Items.IRON_INGOT, 5));
			winner.giveItemStack(new net.minecraft.item.ItemStack(net.minecraft.item.Items.POPPY, 1));
			return;
		}
		Speech.bubble(g, Lang.pick(lang, "*БОНГ* …Уважаю. Деревня твоя.", "*CLANG* …Respect. The village is yours."), 0xA0FFA0, 4000);
	}

	static void clear() { HUNTS.clear(); }
}
