package dev.rapbattle.battle;

import dev.rapbattle.Lang;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.CreeperEntity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.passive.TameableEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.*;

/**
 * Mobs that don't accept the defeat: "Судья куплен!" and they go for the MVP with half health and rage.
 * The win still counts; kill the sore loser and its drops are doubled. Also home of the double-drop helper.
 */
public final class SoreLosers {
	private SoreLosers() {}

	/** Mob UUID → until when its death drops are doubled. */
	private static final Map<UUID, Long> DOUBLE_DROP = new HashMap<>();
	private static final Set<UUID> RAGED = java.util.concurrent.ConcurrentHashMap.newKeySet();

	public static boolean isRaged(MobEntity m) {
		return m != null && RAGED.contains(m.getUuid());
	}

	static void clear() {
		DOUBLE_DROP.clear();
		RAGED.clear();
		BLASTS.clear();
	}

	private static final Map<String, Integer> CHANCE = new HashMap<>();
	private static final Set<String> PETTY = Set.of("sheep", "chicken", "pig", "cow", "mooshroom", "rabbit");

	static {
		for (String s : List.of("piglin_brute", "vindicator", "ravager", "hoglin", "zoglin", "zombified_piglin")) CHANCE.put(s, 60);
		for (String s : List.of("zombie", "husk", "drowned", "zombie_villager", "piglin", "spider", "cave_spider", "enderman", "wolf",
			"polar_bear", "goat")) CHANCE.put(s, 35);
		for (String s : List.of("skeleton", "stray", "bogged", "parched", "wither_skeleton", "creeper", "witch", "blaze", "guardian",
			"bee")) CHANCE.put(s, 15);
	}

	private static final List<String> GENERIC = List.of(
		"Судья куплен!", "Это не считается, у меня бит лагал!", "Да вы все сговорились!", "Реванш, сука! Прямо сейчас, кулаками!",
		"Не по понятиям судили!", "Я тебе сейчас без бита объясню.", "Слова слабые, зато кулаки у меня рифмуются.",
		"Ничья! Это была ничья!", "Микрофон сломан, давай по-другому.", "Ты не выиграл, ты просто громче орал.");
	private static final Map<String, List<String>> OWN = Map.ofEntries(
		Map.entry("zombie", List.of("Ммозги… засудили… мозги…", "Я мёртвый, мне терять нечего!", "Апелляция… ыэээ… кулаком!")),
		Map.entry("piglin", List.of("Это золото было фальшивое! И судья фальшивый!", "Нищеброд меня не победит!", "ХРРР! Отдай моё золото!")),
		Map.entry("zombified_piglin", List.of("Ты ударил одного из нас словами. Теперь мы ударим тебя все.")),
		Map.entry("illager", List.of("ДЖОННИ НЕ ПРОИГРЫВАЕТ!", "Топор тоже умеет рифмовать.", "Хуэ! Рейд не закончен!")),
		Map.entry("hoglin", List.of("*фырк*… *ФЫРК*!")),
		Map.entry("spider", List.of("Восемь лап против двух ног. Посмотрим, кто кого.", "Запутался я в рифмах? Сейчас ты запутаешься в паутине.")),
		Map.entry("enderman", List.of("Ты. Смотрел. Слишком. Много.", "Я засчитал себе победу. В другом измерении.")),
		Map.entry("wolf", List.of("Стая не забудет.")),
		Map.entry("polar_bear", List.of("Я за свои рифмы порву.")),
		Map.entry("goat", List.of("БЕЕЕЕЕ!")),
		Map.entry("skeleton", List.of("*клац* Я промазал в рифму, зато не промажу стрелой.", "Тебе повезло, кожаный. Удача кончилась.")),
		Map.entry("blaze", List.of("Ты меня разжёг. Зря.")),
		Map.entry("bee", List.of("Бзз! Жало в рифму, жало в рожу!")),
		Map.entry("llama", List.of("*тьфу*. Это мой ответ судье. И тебе. *ТЬФУ*.")),
		Map.entry("creeper", List.of("Тссс… если я не выиграл… то никто не выиграл…")),
		Map.entry("sheep", List.of("Бе-е-е! Я тебе сейчас шерстью по лицу!", "Мелочь в ярости, говорил же!")),
		Map.entry("chicken", List.of("КО-КО-КО-КО!")),
		Map.entry("pig", List.of("Хрю! Это была моя морковка, а не твоя победа!")),
		Map.entry("cow", List.of("Муу… я медленная, но я тебя найду.")),
		Map.entry("rabbit", List.of("Я быстрее тебя! И кусаюсь!")));

	private static final List<String> GENERIC_EN = List.of(
		"The judge is bought!", "Doesn't count, my beat was lagging!", "You're all in on this!", "Rematch, bitch! Right now, fists up!",
		"That judging was rigged as hell!", "Let me explain this without a beat.", "Weak bars, huh? Watch my fists rhyme.",
		"Draw! That was a draw!", "Mic's broken, let's settle it the old way.", "You didn't win, you just yelled louder.");
	private static final Map<String, List<String>> OWN_EN = Map.ofEntries(
		Map.entry("zombie", List.of("Brrains… robbed… brains…", "I'm already dead, got nothing to lose!", "Appeal… ughhh… by fist!")),
		Map.entry("piglin", List.of("That gold was fake! And the judge is fake!", "No broke-ass beats me!", "SNORT! Give me my gold!")),
		Map.entry("zombified_piglin", List.of("You hit one of us with words. Now we all hit you.")),
		Map.entry("illager", List.of("JOHNNY DOESN'T LOSE!", "My axe knows how to rhyme too.", "Hmph! The raid ain't over!")),
		Map.entry("hoglin", List.of("*snort*… *SNORT*!")),
		Map.entry("spider", List.of("Eight legs against two. Let's see who wins.", "Tangled in my rhymes? Now get tangled in my web.")),
		Map.entry("enderman", List.of("You. Stared. Too. Much.", "I'm calling that my win. In another dimension.")),
		Map.entry("wolf", List.of("The pack won't forget this.")),
		Map.entry("polar_bear", List.of("I'll maul you over my rhymes.")),
		Map.entry("goat", List.of("BAAAAH!")),
		Map.entry("skeleton", List.of("*clack* Missed the rhyme, won't miss the arrow.", "You got lucky, meatbag. Luck's out.")),
		Map.entry("blaze", List.of("You fired me up. Big mistake.")),
		Map.entry("bee", List.of("Bzz! Stinger in the rhyme, stinger in your face!")),
		Map.entry("llama", List.of("*ptoo*. That's for the judge. And for you. *PTOO*.")),
		Map.entry("creeper", List.of("Tsss… if I didn't win… nobody wins…")),
		Map.entry("sheep", List.of("Baa-a-a! Catch this wool to the face!", "Small fry in a rage, told you!")),
		Map.entry("chicken", List.of("BAWK-BAWK-BAWK-BAWK!")),
		Map.entry("pig", List.of("Oink! That was my carrot, not your win!")),
		Map.entry("cow", List.of("Moo… I'm slow, but I will find you.")),
		Map.entry("rabbit", List.of("I'm faster than you! And I bite!")));

	private record Blast(MobEntity mob, long at) {}

	private static final List<Blast> BLASTS = new ArrayList<>();

	/** Queues a non-destructive blast at the mob after {@code delayMs}. */
	public static void blast(MobEntity m, long delayMs) {
		BLASTS.add(new Blast(m, System.currentTimeMillis() + delayMs));
	}

	/** Called every server tick. */
	public static void tick() {
		long now = System.currentTimeMillis();
		for (Blast b : new ArrayList<>(BLASTS)) {
			if (now < b.at()) continue;
			BLASTS.remove(b);
			MobEntity m = b.mob();
			if (m.isAlive() && m.getEntityWorld() instanceof ServerWorld sw) {
				sw.createExplosion(m, m.getX(), m.getY(), m.getZ(), 3.0f, World.ExplosionSourceType.NONE);
				m.discard();
			}
		}
	}

	public static void register() {
		ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
			Long until = DOUBLE_DROP.remove(entity.getUuid());
			if (until != null && System.currentTimeMillis() < until && entity.getEntityWorld() instanceof ServerWorld w) {
				doubleFreshDrops(w, new Vec3d(entity.getX(), entity.getY(), entity.getZ()));
			}
		});
	}

	/** Duplicates the item drops that just landed around {@code pos} (the vanilla loot of a mob that just died). */
	public static void doubleFreshDrops(ServerWorld w, Vec3d pos) {
		for (ItemEntity it : w.getEntitiesByClass(ItemEntity.class, new Box(pos, pos).expand(2.5), e -> e.getItemAge() <= 1)) {
			ItemEntity copy = new ItemEntity(w, it.getX(), it.getY(), it.getZ(), it.getStack().copy());
			copy.setToDefaultPickupDelay();
			w.spawnEntity(copy);
		}
	}

	/**
	 * Rolls whether the beaten mob refuses to accept it. If it does, it stays alive in a rage and goes for the MVP.
	 * @return true when the mob raged (the caller must not kill it)
	 */
	public static boolean tryRage(Opponent opp, MobRecord before, int margin, ServerPlayerEntity mvp, Random rnd, String lang) {
		MobEntity mob = opp.mob();
		String id = opp.profile().id();
		if (opp.rank().isBoss() || mvp == null || (mob instanceof TameableEntity t && t.isTamed())) return false;
		// Peaceful mobs always accept the defeat: only hostile and grudge-holding mobs can blow up.
		if (!(mob instanceof HostileEntity) && !(mob instanceof net.minecraft.entity.mob.Angerable)) return false;
		Integer base = CHANCE.get(id);
		if (base == null) return false;
		int chance = base;
		String mood = opp.mood();
		if (mood.equals("Мелочь в ярости") || mood.equals("Обиженный") || mood.equals("Опасный")
			|| mood.equals("Raging Runt") || mood.equals("Salty") || mood.equals("Dangerous")) chance += 20;
		if (mood.equals("Серьёзный") || mood.equals("Dead Serious")) chance -= 10;
		if (before.wins() > 0 && before.losses() <= 1) chance += 25; // first defeat of a champion (already counted)
		if (margin >= 10) chance += 15;
		if (margin <= 2) chance -= 10;
		World w = mob.getEntityWorld();
		if (mob.getType().isIn(net.minecraft.registry.tag.EntityTypeTags.UNDEAD) && Opponent.isNight(w)) chance += 10;
		if (rnd.nextInt(100) >= chance) return false;

		List<String> generic = Lang.isRu(lang) ? GENERIC : GENERIC_EN;
		Map<String, List<String>> own = Lang.isRu(lang) ? OWN : OWN_EN;
		List<String> pool = own.getOrDefault(id, own.getOrDefault(opp.profile().kin(), generic));
		String line = rnd.nextInt(3) == 0 ? generic.get(rnd.nextInt(generic.size())) : pool.get(rnd.nextInt(pool.size()));
		for (ServerPlayerEntity p : ((ServerWorld) w).getPlayers()) {
			if (p.squaredDistanceTo(mob) < 32 * 32) {
				p.sendMessage(Text.translatable("rapbattle.sorelosers.refuse").formatted(Formatting.DARK_RED, Formatting.BOLD)
					.append(Text.literal("<" + opp.name() + "> " + line).formatted(Formatting.RED)), false);
			}
		}
		Speech.bubble(mob, Lang.pick(lang, "НЕ ПРИЗНАЁТ ПОРАЖЕНИЕ! ", "WON'T ACCEPT DEFEAT! ") + line, 0xFF4040, 4000);
		DOUBLE_DROP.put(mob.getUuid(), System.currentTimeMillis() + 5 * 60_000);
		RAGED.add(mob.getUuid());
		mob.setHealth(Math.max(1, mob.getMaxHealth() / 2));

		if (mob instanceof CreeperEntity) {
			// The creeper's tantrum is a blast: damage, but no broken blocks, and 1.5 s to run.
			for (ServerPlayerEntity p : ((ServerWorld) w).getPlayers()) {
				if (p.squaredDistanceTo(mob) < 16 * 16) p.sendMessage(Text.translatable("rapbattle.sorelosers.run").formatted(Formatting.RED, Formatting.BOLD), true);
			}
			w.playSound(null, mob.getBlockPos(), net.minecraft.sound.SoundEvents.ENTITY_CREEPER_PRIMED, net.minecraft.sound.SoundCategory.HOSTILE, 1f, 0.5f);
			BLASTS.add(new Blast(mob, System.currentTimeMillis() + 1500));
			return true;
		}
		if (PETTY.contains(id)) {
			// Small fry can't really fight: one angry headbutt, almost no damage.
			Vec3d dir = new Vec3d(mvp.getX() - mob.getX(), 0, mvp.getZ() - mob.getZ()).normalize();
			mvp.takeKnockback(0.8, -dir.x, -dir.z);
			mvp.damage((ServerWorld) w, w.getDamageSources().mobAttack(mob), 1.0f);
			return true;
		}
		mob.addStatusEffect(new StatusEffectInstance(StatusEffects.STRENGTH, 20 * 30, 0));
		mob.addStatusEffect(new StatusEffectInstance(StatusEffects.SPEED, 20 * 30, 0));
		mob.setTarget(mvp);
		if (!(mob instanceof HostileEntity)) mob.setAttacker(mvp);
		return true;
	}

	@Deprecated
	public static boolean tryRage(Opponent opp, MobRecord before, int margin, ServerPlayerEntity mvp, Random rnd) {
		return tryRage(opp, before, margin, mvp, rnd, Lang.RU);
	}
}
