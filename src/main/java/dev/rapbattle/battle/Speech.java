package dev.rapbattle.battle;

import dev.rapbattle.Lang;
import dev.rapbattle.RapBattle;
import dev.rapbattle.RapConfig;
import dev.rapbattle.ai.Llm;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Speech bubbles over mobs in the world (a vanilla text display that follows the mob), and the bank of
 * lines hostile mobs shout when they go for a player. The bank is refreshed once a day by the language model.
 */
public final class Speech {
	private Speech() {}

	private record Bubble(DisplayEntity.TextDisplayEntity display, MobEntity mob, long until) {}

	private static final List<Bubble> BUBBLES = new ArrayList<>();
	private static final Map<UUID, Long> MOB_COOLDOWN = new java.util.concurrent.ConcurrentHashMap<>();
	private static final Map<UUID, Long> PLAYER_COOLDOWN = new java.util.concurrent.ConcurrentHashMap<>();
	private static final Map<UUID, Integer> HINTS_SHOWN = new java.util.concurrent.ConcurrentHashMap<>();
	private static final Random RND = new Random();

	private static final Map<String, List<String>> BASE = Map.ofEntries(
		Map.entry("zombie", List.of("Ммозги-и… твои… мои…", "Я мёртвый, а флоу живее твоего.", "{p}… ты… вкусный…", "Ыэээ… ужин пришёл сам.")),
		Map.entry("skeleton", List.of("*клац* Ты уже на прицеле, кожаный.", "Стрела в колено — и ты больше не рэпер.", "{p}, кости не врут: тебе конец.")),
		Map.entry("creeper", List.of("Тссс… обними меня, братишка.", "Тссс… {p}, давай без слов.", "Тссс… у меня для тебя сюрприз.")),
		Map.entry("spider", List.of("Восемь глаз, и все на тебя.", "Ссс… паутина уже готова.", "{p}, ты запутался.")),
		Map.entry("enderman", List.of("Ты. Посмотрел. Мне. В глаза.", "Вррп… зря ты пялился.", "Блок за блоком, и ты следующий.")),
		Map.entry("illager", List.of("Хуэ! Деревня наша, а ты лишний.", "Рейд пришёл, {p}, беги.", "Хуэ-хуэ! Топор наточен.")),
		Map.entry("piglin", List.of("Где золото? ГДЕ ЗОЛОТО?", "Нищеброд на районе!", "Хрр! {p}, без золота не пройдёшь.")),
		Map.entry("zombified_piglin", List.of("Ты тронул одного — ответишь всем.", "Хррр… своих не бросаем.")),
		Map.entry("wolf", List.of("Ауф! Стая учуяла тебя.", "Р-р-р… ты на нашей земле.")),
		Map.entry("bee", List.of("Бзз! Не трогай улей!", "Бзз-бзз, жало наготове.")),
		Map.entry("blaze", List.of("Фшшш… сейчас станет жарко.", "Огонь не спрашивает, {p}.")),
		Map.entry("ghast", List.of("*всхлип* Никто меня не любит… и тебя не будут.", "Лови фаербол и слёзы.")),
		Map.entry("slime", List.of("Плюх! Я липкий и злой.", "Плюх-плюх, догоню.")),
		Map.entry("guardian", List.of("*бззз* Я вижу тебя насквозь.", "Глубина не прощает.")),
		Map.entry("phantom", List.of("Ты не спал три ночи? Я пришёл за тобой.", "Бессонница, {p}, это я.")),
		Map.entry("hoglin", List.of("*ФЫРК* Снесу!", "*фырк-фырк* Беги, пока можешь.")),
		Map.entry("polar_bear", List.of("*рык* Не подходи к моим.", "Белый и злой.")),
		Map.entry("villager", List.of("Хммм! Ты обидел моих людей.", "Деревня под защитой. Микрофон или кулак?")),
		Map.entry("warden", List.of("*тук-тук*… я слышу тебя…", "*тук-тук*… тишина… или смерть…")),
		Map.entry("creaking", List.of("*скрип*… не смотри… не смотри…")),
		Map.entry("breeze", List.of("Вжух! Сдую твой флоу.")),
		Map.entry("silverfish", List.of("*скрр* Мы в стенах.")),
		Map.entry("endermite", List.of("*скрр* Мелкий, но кусачий.")));

	private static final Map<String, List<String>> BASE_EN = Map.ofEntries(
		Map.entry("zombie", List.of("Brrains… yours… mine…", "I'm dead and my flow's still livelier than yours.", "{p}… you look… tasty…", "Ughhh… dinner walked right up.")),
		Map.entry("skeleton", List.of("*clack* Already got you in my sights, meatbag.", "Arrow to the knee — no more rapping for you.", "{p}, bones don't lie: you're done.")),
		Map.entry("creeper", List.of("Tsss… give me a hug, bro.", "Tsss… {p}, let's skip the talk.", "Tsss… got a little surprise for you.")),
		Map.entry("spider", List.of("Eight eyes, all on you.", "Sss… web's already spun.", "{p}, you're all tangled up.")),
		Map.entry("enderman", List.of("You. Looked. Me. In the eyes.", "Vrrp… shouldn't have stared.", "Block by block, and you're next.")),
		Map.entry("illager", List.of("Hmph! This village is ours, you're in the way.", "The raid is here, {p}, run.", "Hmph-hmph! Axe is freshly sharpened.")),
		Map.entry("piglin", List.of("Where's the gold? WHERE'S THE GOLD?", "Broke-ass on our turf!", "Snort! {p}, no gold, no pass.")),
		Map.entry("zombified_piglin", List.of("Touch one of us, answer to all of us.", "Snort… we don't leave our own behind.")),
		Map.entry("wolf", List.of("Awoo! The pack caught your scent.", "Grrr… you're on our turf.")),
		Map.entry("bee", List.of("Bzz! Hands off the hive!", "Bzz-bzz, stinger's locked and loaded.")),
		Map.entry("blaze", List.of("Fshhh… about to get hot in here.", "Fire doesn't ask twice, {p}.")),
		Map.entry("ghast", List.of("*sob* Nobody loves me… and they won't love you either.", "Catch a fireball and some tears.")),
		Map.entry("slime", List.of("Splat! I'm sticky and mad.", "Splat-splat, I'll catch up.")),
		Map.entry("guardian", List.of("*bzzz* I see right through you.", "The deep doesn't forgive.")),
		Map.entry("phantom", List.of("No sleep for three nights? I'm here for you.", "Insomnia, {p}, that's me.")),
		Map.entry("hoglin", List.of("*SNORT* Gonna flatten you!", "*snort-snort* Run while you can.")),
		Map.entry("polar_bear", List.of("*growl* Stay away from mine.", "White fur, pure rage.")),
		Map.entry("villager", List.of("Hmmm! You messed with my people.", "This village is protected. Mic or fists?")),
		Map.entry("warden", List.of("*thump-thump*… I hear you…", "*thump-thump*… silence… or death…")),
		Map.entry("creaking", List.of("*creak*… don't look… don't look…")),
		Map.entry("breeze", List.of("Whoosh! Gonna blow your flow away.")),
		Map.entry("silverfish", List.of("*skrr* We're in the walls.")),
		Map.entry("endermite", List.of("*skrr* Tiny, but I bite.")));

	private static final Map<String, Map<String, List<String>>> generated = new java.util.concurrent.ConcurrentHashMap<>();
	private static final Map<String, Long> generatedAt = new java.util.concurrent.ConcurrentHashMap<>();
	private static final Set<String> generating = java.util.concurrent.ConcurrentHashMap.newKeySet();

	// ------------------------------------------------------------------ bubbles

	/** Computes bubble display duration from text length: min 4s, +0.06s/char, max 10s. */
	public static long durationMs(String text) {
		int len = text == null ? 0 : text.length();
		return Math.max(4000L, Math.min(10_000L, 4000L + len * 60L));
	}

	/** Shows {@code text} over the mob for {@code ms} milliseconds (at least {@link #durationMs(String)}); everyone nearby sees it. */
	public static void bubble(MobEntity mob, String text, int color, long ms) {
		if (!(mob.getEntityWorld() instanceof ServerWorld w)) return;
		long effectiveMs = Math.max(ms, durationMs(text));
		// One bubble per mob: the new line replaces the old one.
		for (Bubble b : new ArrayList<>(BUBBLES)) {
			if (b.mob() == mob) {
				b.display().discard();
				BUBBLES.remove(b);
			}
		}
		DisplayEntity.TextDisplayEntity d = EntityType.TEXT_DISPLAY.create(w, SpawnReason.COMMAND);
		if (d == null) return;
		d.setText(Text.literal(text).styled(s -> s.withColor(color)));
		d.setBillboardMode(DisplayEntity.BillboardMode.CENTER);
		d.setBackground(0xB0101018);
		d.setLineWidth(170);
		d.refreshPositionAndAngles(mob.getX(), mob.getY() + mob.getHeight() + 0.55, mob.getZ(), 0, 0);
		w.spawnEntity(d);
		BUBBLES.add(new Bubble(d, mob, dev.rapbattle.Clock.now() + effectiveMs));
	}

	/** Server tick: bubbles follow their mob and disappear in time. */
	public static void tick() {
		long now = dev.rapbattle.Clock.now();
		for (Bubble b : new ArrayList<>(BUBBLES)) {
			if (now > b.until() || !b.mob().isAlive() || b.display().isRemoved()) {
				b.display().discard();
				BUBBLES.remove(b);
				continue;
			}
			MobEntity m = b.mob();
			b.display().setPosition(m.getX(), m.getY() + m.getHeight() + 0.55, m.getZ());
		}
		MOB_COOLDOWN.values().removeIf(t -> now - t > 120_000);
		PLAYER_COOLDOWN.values().removeIf(t -> now - t > 60_000);
	}

	public static void clearAll() {
		for (Bubble b : BUBBLES) b.display().discard();
		BUBBLES.clear();
		MOB_COOLDOWN.clear();
		PLAYER_COOLDOWN.clear();
		HINTS_SHOWN.clear();
	}

	// ------------------------------------------------------------------ aggro lines

	/** A hostile mob just picked a player as its target. Says something, if nobody spoke recently. */
	public static void onAggro(MobEntity mob, ServerPlayerEntity player) {
		if (!RapConfig.get().aggroLines || BattleManager.byMob(mob) != null || BattleManager.battleNear(mob, 32)) return;
		for (Bubble b : BUBBLES) if (b.mob() == mob) return; // it's already saying something
		long now = dev.rapbattle.Clock.now();
		Long m = MOB_COOLDOWN.get(mob.getUuid());
		if (m != null && now - m < 60_000) return;
		Long p = PLAYER_COOLDOWN.get(player.getUuid());
		if (p != null && now - p < 8_000) return;
		MobProfiles.Profile prof = MobProfiles.of(mob.getType());
		String lang = Lang.of(player);
		String line = pick(prof, player.getName().getString(), lang);
		if (line == null) return;
		MOB_COOLDOWN.put(mob.getUuid(), now);
		PLAYER_COOLDOWN.put(player.getUuid(), now);
		String name = Opponent.nameOf(mob, prof, RND, lang);
		bubble(mob, name + ": " + line, 0xFFD27F, 3500);
		mob.playAmbientSound();
		int shown = HINTS_SHOWN.merge(player.getUuid(), 1, Integer::sum);
		if (shown <= 3) {
			player.sendMessage(Text.translatable("rapbattle.speech.aggro_hint").formatted(Formatting.DARK_GRAY), true);
		}
	}

	private static String pick(MobProfiles.Profile prof, String playerName, String lang) {
		refreshIfStale(lang);
		Map<String, List<String>> base = Lang.isRu(lang) ? BASE : BASE_EN;
		Map<String, List<String>> gen = generated.getOrDefault(lang, Map.of());
		List<String> pool = new ArrayList<>();
		pool.addAll(base.getOrDefault(prof.id(), base.getOrDefault(prof.kin(), List.of())));
		pool.addAll(gen.getOrDefault(prof.kin(), List.of()));
		if (pool.isEmpty()) return null;
		String line = pool.get(RND.nextInt(pool.size()));
		if (line.contains("{p}")) line = line.replace("{p}", playerName);
		else if (RND.nextInt(100) < 30) line = playerName + ", " + Character.toLowerCase(line.charAt(0)) + line.substring(1);
		return line;
	}

	// ------------------------------------------------------------------ daily refresh by the model

	private static Path cacheFile(String lang) {
		return FabricLoader.getInstance().getConfigDir().resolve(Lang.isRu(lang) ? "rapbattle-phrases.json" : "rapbattle-phrases-en.json");
	}

	private static void refreshIfStale(String lang) {
		long now = dev.rapbattle.Clock.now();
		long at = generatedAt.getOrDefault(lang, 0L);
		if (at == 0) {
			loadCache(lang);
			at = generatedAt.getOrDefault(lang, 1L);
		}
		if (generating.contains(lang) || now - at < 24 * 3600_000L || !RapConfig.get().hasLlm()) return;
		generating.add(lang);
		generatedAt.put(lang, now);
		Map<String, List<String>> base = Lang.isRu(lang) ? BASE : BASE_EN;
		CompletableFuture.runAsync(() -> {
			try {
				Map<String, List<String>> out = new HashMap<>();
				for (String kin : base.keySet()) {
					MobProfiles.Profile any = null;
					for (MobProfiles.Profile p : MobProfiles.all()) if (p.kin().equals(kin) || p.id().equals(kin)) { any = p; break; }
					if (any == null) continue;
					List<String> lines = Llm.aggroLines(any.species(lang), any.style(lang), any.adlib(lang), base.get(kin), 10, lang);
					if (lines != null) out.put(kin, lines);
				}
				if (!out.isEmpty()) {
					long ts = dev.rapbattle.Clock.now();
					generated.put(lang, out);
					generatedAt.put(lang, ts);
					try {
						Files.writeString(cacheFile(lang), new Gson().toJson(Map.of("at", ts, "lines", out)), StandardCharsets.UTF_8);
					} catch (Exception e) {
						RapBattle.LOG.warn("Could not save phrase cache", e);
					}
				}
			} finally {
				generating.remove(lang);
			}
		});
	}

	private static void loadCache(String lang) {
		generatedAt.put(lang, 1L);
		try {
			Path f = cacheFile(lang);
			if (!Files.exists(f)) return;
			Map<String, Object> m = new Gson().fromJson(Files.readString(f, StandardCharsets.UTF_8),
				new TypeToken<Map<String, Object>>() {}.getType());
			generatedAt.put(lang, ((Number) m.get("at")).longValue());
			Map<String, List<String>> lines = new Gson().fromJson(new Gson().toJson(m.get("lines")),
				new TypeToken<Map<String, List<String>>>() {}.getType());
			if (lines != null) generated.put(lang, lines);
		} catch (Exception e) {
			RapBattle.LOG.warn("Could not read phrase cache", e);
		}
	}
}
