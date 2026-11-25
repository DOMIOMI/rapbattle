package dev.rapbattle.battle;

import dev.rapbattle.Lang;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Rap names. Every mob gets its own nickname tied to its species: either a hand-made one ("Мясо в Шубе" / "Mutton in Fur")
 * or a rap prefix + a species root ("Янг Кудряш", "OG Руно"). The name is saved in the mob ({@link MobRecord}),
 * so two sheep in one field are two different rappers, and each keeps its name forever.
 */
public final class Names {
	private Names() {}

	private static final String[] PREFIXES = {"Биг", "Лил", "Янг", "MC", "OG", "Кид", "Мистер", "Дон", "Кинг", "Бро", "DJ", "Папа", "Бэйби", "Мега", "Сэр"};
	private static final String[] PREFIXES_EN = {"Big", "Lil", "Young", "MC", "OG", "Kid", "Mr.", "Don", "King", "Bro", "DJ", "Papa", "Baby", "Mega", "Sir"};

	/** Species roots for "prefix + root" names. */
	private static final Map<String, String[]> ROOTS = new HashMap<>();
	/** Hand-made names with a joke inside. */
	private static final Map<String, String[]> SPECIAL = new HashMap<>();
	private static final Map<String, String[]> ROOTS_EN = new HashMap<>();
	private static final Map<String, String[]> SPECIAL_EN = new HashMap<>();

	private static void n(String id, String[] roots, String... special) {
		ROOTS.put(id, roots);
		SPECIAL.put(id, special);
	}

	private static void nEn(String id, String[] roots, String... special) {
		ROOTS_EN.put(id, roots);
		SPECIAL_EN.put(id, special);
	}

	private static String[] r(String... s) { return s; }

	static {
		n("sheep", r("Шерсть", "Руно", "Кудряш", "Барашек", "Бе-е", "Пушок", "Ягня"),
			"Мясо в Шубе", "Шерстяной Флоу", "Золотое Руно", "Стриженый Беспредел", "Бе-е-Рэп", "Кудрявый Кошмар", "Пряжа Без Тормозов");
		n("cow", r("Говядина", "Бурёнка", "Мууу", "Копыто", "Телок", "Вымя"),
			"Мууу Флоу", "Молочный Барон", "Стейк Медиум", "Бурёнка Бандитка", "Мраморная Говядина", "Тёлка с Района");
		n("mooshroom", r("Гриб", "Мухомор", "Спора", "Шаман"),
			"Грибной Шаман", "Трипл Мухомор", "Спорный Вопрос", "Лесной Трип");
		n("pig", r("Бекон", "Хрю", "Пятак", "Окорок", "Свин"),
			"Хрю Тру", "Бекон Без Тормозов", "Пятачок Беспредела", "Сало Флоу", "Грязный Хрю");
		n("chicken", r("Клюв", "Наггет", "Цыпа", "Яйцо", "Крыло"),
			"Яйцо Бой", "Наггет Гангстер", "Курица Гриль", "Ко-Ко Кашель", "Крылышко Острое", "Бройлер");
		n("rabbit", r("Ушан", "Хоп", "Морковь", "Кроль"),
			"Быстрый Ушан", "Хоп Хоп", "Морковный Флоу", "Зайка Мафия");
		n("zombie", r("Мозгоед", "Гниль", "Труп", "Зомбяк", "Мертвяк", "Тлен"),
			"Гнилой Бит", "Мёртвый Флоу", "Ходячий Позор", "Брейн Хантер", "Ммозги Тру", "Трупный Яд", "Вечно Голодный");
		n("husk", r("Песок", "Сухарь", "Мумия", "Бархан"), "Песок Бит", "Сухой Закон", "Пустынный Голод");
		n("drowned", r("Тина", "Буль", "Утоп", "Трезубец"), "Мокрый Флоу", "Посейдон Района", "Подводный Голод", "Буль Буль Гангста");
		n("zombie_villager", r("Барыга", "Мертвец", "Хммм"), "Барыга-Мертвец", "Хмм Мёртвый", "Изумрудный Труп");
		n("skeleton", r("Кость", "Скелли", "Сухарь", "Ребро", "Череп", "Позвонок"),
			"Биг Кость", "Сухарь с Луком", "Голые Кости", "Стрела в Колено", "Ребро Адама", "Костяной Снайпер", "Скелли Банк");
		n("stray", r("Лёд", "Иней", "Сосулька"), "Ледяная Кость", "Морозный Флоу", "Холодный Снайпер");
		n("bogged", r("Тина", "Болото", "Мох"), "Тинная Кость", "Болотный Ганг", "Ядовитый Лук");
		n("parched", r("Засуха", "Пыль", "Жажда"), "Сухарь Пустыни", "Без Воды", "Песчаный Череп");
		n("wither_skeleton", r("Чёрная Кость", "Уголь", "Пепел"), "Чёрная Кость", "Угольный Флоу", "Незерский Мясник");
		n("spider", r("Паутина", "Лапы", "Восьмёрка", "Тарантул"), "Восемь Лап", "Паутина Тру", "Восьминогий Флоу", "Сеть Района");
		n("cave_spider", r("Яд", "Укус", "Синяк"), "Яд Тру", "Пещерный Укус", "Мелкий Токсик");
		n("creeper", r("Тротил", "Шум", "Бум", "Фитиль", "Тсссс"),
			"Тссс Бум", "Зелёный Шум", "Ходячий Тротил", "Обнимашки", "Фитиль Короткий", "Последнее Объятие", "Взрывной Характер");
		n("enderman", r("Тень", "Глаз", "Блок", "Варп"),
			"MC Не Смотри", "Тёмный Тип", "Блок Вор", "Длинный Варп", "Взгляд в Пустоту", "Телепорт Тру");
		n("witch", r("Зелье", "Котёл", "Карга", "Ведьма"), "Ведьма Вайб", "Зелье Ган", "Котёл Беспредела", "Хи-Хи Хаос");
		n("villager", r("Хмм", "Изумруд", "Барыга", "Дилер", "Нос"),
			"Хмм Дилер", "Изумруд Бой", "Носатый Барыга", "Скидка Есть", "Хммм Хаслер", "Торговый Монополист");
		n("wandering_trader", r("Барыга", "Странник", "Ламовод"), "Бродячий Барыга", "Товар Лицом", "Лама Логистика");
		n("iron_golem", r("Железо", "Голем", "Кулак", "Мак"), "Биг Железо", "Железный Кулак", "Мак в Руке", "Деревенский Охранник");
		n("wolf", r("Клык", "Стая", "Вой", "Серый"), "Серый Флоу", "Лил Клык", "Волчья Стая", "Ауф Тру", "Одинокий Вой");
		n("fox", r("Хвост", "Рыжий", "Лис"), "Лил Хвост", "Рыжий Вор", "Лисий Хаслер");
		n("cat", r("Когти", "Мяу", "Усы", "Мурка"), "Мяу Мэн", "Лил Когти", "Котофей Флоу", "Мурка Мафия");
		n("bee", r("Жало", "Мёд", "Жужа", "Улей"), "Жужа", "Медовый Флоу", "Жало Тру", "Королева Улья");
		n("piglin", r("Голд", "Хрю", "Блеск", "Слиток"), "Голд Хрю", "Блестяшка", "Золотой Пятак", "Бартер Босс");
		n("piglin_brute", r("Брут", "Топор", "Бастион"), "Голд Брут", "Топор Бастиона", "Охрана Золота");
		n("zombified_piglin", r("Гнилой Голд", "Смрад"), "Гнилой Голд", "Мёртвое Золото", "Своих Не Бросаем");
		n("blaze", r("Огонь", "Пламя", "Жар"), "Лил Огонь", "Горячий Флоу", "Стержень Тру");
		n("ghast", r("Плакса", "Слеза", "Эмо"), "Плакса", "Эмо Гаст", "Слёзный Бит", "Грустный Фаербол");
		n("slime", r("Слизь", "Желе", "Плюх"), "Слизь Ган", "Лил Желе", "Зелёный Плюх");
		n("magma_cube", r("Лава", "Магма", "Куб"), "Лава Куб", "Магма Тру", "Горячий Кубик");
		n("pillager", r("Арбалет", "Рейд", "Гоп"), "Арбалет Бро", "Хуэ Тру", "Рейдовый Гоп");
		n("vindicator", r("Джонни", "Топор"), "Джонни", "Топор Джонни", "Хуэ Джонни");
		n("evoker", r("Маг", "Векс", "Тотем"), "Маг-Позёр", "Тотем Флоу", "Векс Мастер");
		n("ravager", r("Танк", "Рёв"), "Танк", "Рёв Района", "Разнос");
		n("hoglin", r("Кабан", "Клык"), "Кабан", "Клыкастый Флоу");
		n("goat", r("Бодун", "Рог", "Козёл"), "Горный Бодун", "Рогатый Флоу", "Козёл Отпущения");
		n("llama", r("Плевок", "Лама"), "Плюй-Тру", "Лама Драма", "Лама-Лама Дин-Дон");
		n("horse", r("Иго-Го", "Скакун", "Галоп"), "Иго-Го Флоу", "Галоп Тру", "Конь в Пальто");
		n("panda", r("Бамбук", "Панда", "Чих"), "Бамбук Бой", "Кунг-Фу Флоу", "Ленивый Бамбук");
		n("polar_bear", r("Шатун", "Лёд", "Мишка"), "Белый Шатун", "Полярный Флоу");
		n("phantom", r("Бессонница", "Кошмар"), "Бессонница", "Ночной Кошмар", "Не Спал Три Ночи");
		n("warden", r("Тишина", "Глухой"), "Глухой", "Тишина", "Скалк Босс");
		n("wither", r("Головы", "Иссушитель"), "Три Головы", "Трёхглавый Флоу");
		n("ender_dragon", r("Дракон", "Край"), "Дракон Края", "Финальный Босс", "Яйцо Драконье");

		nEn("sheep", r("Wool", "Fleece", "Curls", "Ram", "Baa", "Fluff", "Lamb"),
			"Mutton in Fur", "Woolly Flow", "Golden Fleece", "Sheared Menace", "Baa-Bars", "Curly Nightmare", "Unspun Chaos");
		nEn("cow", r("Beef", "Heifer", "Mooo", "Hoof", "Calf", "Udder"),
			"Mooo Flow", "Dairy Don", "Medium Rare", "Bandit Cow", "Prime Rib", "Pasture Boss");
		nEn("mooshroom", r("Shroom", "Toadstool", "Spore", "Shaman"),
			"Shroom Shaman", "Triple Cap", "Spore Loser", "Fungal Trip");
		nEn("pig", r("Bacon", "Oink", "Snout", "Ham", "Swine"),
			"Oink Tru", "Sizzling Bacon", "Menace Snout", "Pork Chop Flow", "Dirty Oink");
		nEn("chicken", r("Beak", "Nugget", "Chick", "Egg", "Wing"),
			"Egg Boy", "Nugget Gangsta", "Rotisserie", "Cluck Norris", "Hot Wing", "Broiler");
		nEn("rabbit", r("Ears", "Hop", "Carrot", "Bun"),
			"Quick Ears", "Hip-Hop Bun", "Carrot Flow", "Bunny Mafia");
		nEn("zombie", r("Brains", "Rot", "Corpse", "Zed", "Deadman", "Decay"),
			"Rotten Beat", "Dead Flow", "Walking L", "Brain Hunter", "Braaains Tru", "Grave Venom", "Forever Hungry");
		nEn("husk", r("Sand", "Jerky", "Mummy", "Dune"), "Sand Beat", "Bone Dry", "Desert Hunger");
		nEn("drowned", r("Kelp", "Blub", "Sunk", "Trident"), "Soggy Flow", "Hood Poseidon", "Deep-Sea Hunger", "Blub-Blub Gangsta");
		nEn("zombie_villager", r("Hustler", "Undead", "Hrmm"), "Dead Hustler", "Hrmm Undead", "Emerald Corpse");
		nEn("skeleton", r("Bone", "Skelly", "Rattle", "Ribs", "Skull", "Spine"),
			"Big Bone", "Bony Archer", "Bare Bones", "Arrow to the Knee", "Spare Rib", "Bone Sniper", "Skelly Bank");
		nEn("stray", r("Ice", "Frost", "Icicle"), "Ice Bone", "Frostbite Flow", "Cold Sniper");
		nEn("bogged", r("Muck", "Swamp", "Moss"), "Bog Bone", "Swamp Gang", "Venom Bow");
		nEn("parched", r("Drought", "Dust", "Thirst"), "Desert Dry", "No Water", "Sand Skull");
		nEn("wither_skeleton", r("Charbone", "Coal", "Ash"), "Black Bone", "Coal Flow", "Nether Butcher");
		nEn("spider", r("Web", "Legs", "Eight", "Tarantula"), "Eight Legs", "Web Tru", "Eight-Legged Flow", "Block-Wide Web");
		nEn("cave_spider", r("Venom", "Bite", "Sting"), "Venom Tru", "Cave Bite", "Lil Toxic");
		nEn("creeper", r("TNT", "Noise", "Boom", "Fuse", "Tssss"),
			"Tsss Boom", "Green Menace", "Walking TNT", "Free Hugs", "Short Fuse", "Final Hug", "Blast Radius");
		nEn("enderman", r("Shade", "Eye", "Block", "Warp"),
			"MC Don't Look", "Tall Shadow", "Block Thief", "Long Warp", "Void Stare", "Blink Tru");
		nEn("witch", r("Brew", "Cauldron", "Hag", "Hex"), "Witch Vibe", "Potion Gang", "Cauldron Chaos", "Cackle Queen");
		nEn("villager", r("Hrm", "Emerald", "Hustler", "Dealer", "Nose"),
			"Hrm Dealer", "Emerald Boy", "Big-Nose Plug", "No Refunds", "Hrmm Hustler", "Trade Monopoly");
		nEn("wandering_trader", r("Peddler", "Drifter", "Llamaman"), "Wandering Plug", "Scam Artist", "Llama Logistics");
		nEn("iron_golem", r("Iron", "Golem", "Fist", "Poppy"), "Big Iron", "Iron Fist", "Poppy Plug", "Village Bouncer");
		nEn("wolf", r("Fang", "Pack", "Howl", "Grey"), "Grey Flow", "Lil Fang", "Wolf Pack", "Awoo Tru", "Lone Howl");
		nEn("fox", r("Tail", "Red", "Sly"), "Lil Tail", "Red Thief", "Sly Hustler");
		nEn("cat", r("Claws", "Meow", "Whiskers", "Purr"), "Meow Man", "Lil Claws", "Catnip Flow", "Purr Mafia");
		nEn("bee", r("Sting", "Honey", "Buzz", "Hive"), "Buzzy", "Honey Flow", "Sting Tru", "Queen Bee");
		nEn("piglin", r("Gold", "Snout", "Bling", "Ingot"), "Gold Snout", "24K Bling", "Golden Tusk", "Barter Boss");
		nEn("piglin_brute", r("Brute", "Axe", "Bastion"), "Gold Brute", "Bastion Axe", "Vault Guard");
		nEn("zombified_piglin", r("Rotten Gold", "Stench"), "Rotten Gold", "Dead Bling", "Ride or Die");
		nEn("blaze", r("Fire", "Flame", "Heat"), "Lil Flame", "Spittin' Fire", "Blaze Rod Tru");
		nEn("ghast", r("Crybaby", "Tear", "Emo"), "Crybaby", "Emo Ghast", "Tearjerker", "Sad Fireball");
		nEn("slime", r("Ooze", "Jelly", "Splat"), "Slime Gang", "Lil Jelly", "Green Splat");
		nEn("magma_cube", r("Lava", "Magma", "Cube"), "Lava Cube", "Magma Tru", "Hot Block");
		nEn("pillager", r("Crossbow", "Raid", "Thug"), "Crossbow Bro", "Hrrgh Tru", "Raid Thug");
		nEn("vindicator", r("Johnny", "Axe"), "Johnny", "Axe Johnny", "Here's Johnny");
		nEn("evoker", r("Mage", "Vex", "Totem"), "Wololo Mage", "Totem Flow", "Vex Master");
		nEn("ravager", r("Tank", "Roar"), "Tank", "Block Roar", "Wrecking Ball");
		nEn("hoglin", r("Boar", "Tusk"), "Wild Boar", "Tusk Flow");
		nEn("goat", r("Ram", "Horn", "GOAT"), "Peak Rammer", "Horned Flow", "Literal GOAT");
		nEn("llama", r("Spit", "Llama"), "Spit Tru", "Llama Drama", "Llama Ding-Dong");
		nEn("horse", r("Neigh", "Steed", "Gallop"), "Neigh Flow", "Gallop Tru", "Dark Horse");
		nEn("panda", r("Bamboo", "Panda", "Sneeze"), "Bamboo Boy", "Kung-Fu Flow", "Lazy Bamboo");
		nEn("polar_bear", r("Grizz", "Ice", "Bear"), "Arctic Beast", "Polar Flow");
		nEn("phantom", r("Insomnia", "Nightmare"), "Insomnia", "Night Terror", "3-Day No-Sleep");
		nEn("warden", r("Silence", "Blind"), "Blind Fury", "Dead Silence", "Sculk Boss");
		nEn("wither", r("Three-Head", "Wither"), "Three Heads", "Triple-Head Flow");
		nEn("ender_dragon", r("Dragon", "End"), "End Dragon", "Final Boss", "Dragon Egg");
	}

	/** A fresh nickname for this species. Never equal to any of {@code taken}. */
	public static String fresh(MobProfiles.Profile p, Random rnd, List<String> taken, String lang) {
		boolean ru = Lang.isRu(lang);
		String[] prefixes = ru ? PREFIXES : PREFIXES_EN;
		String[] special = (ru ? SPECIAL : SPECIAL_EN).get(p.id());
		String[] roots = (ru ? ROOTS : ROOTS_EN).get(p.id());
		List<String> pool = new ArrayList<>();
		if (special != null) pool.addAll(List.of(special));
		pool.addAll(p.names(lang));
		if (roots == null) roots = new String[]{p.species(lang)};
		for (int attempt = 0; attempt < 40; attempt++) {
			String name;
			if (!pool.isEmpty() && rnd.nextInt(100) < 45) name = pool.get(rnd.nextInt(pool.size()));
			else name = prefixes[rnd.nextInt(prefixes.length)] + " " + roots[rnd.nextInt(roots.length)];
			if (!taken.contains(name)) return name;
		}
		return prefixes[rnd.nextInt(prefixes.length)] + " " + roots[rnd.nextInt(roots.length)] + " " + (2 + rnd.nextInt(98));
	}

	@Deprecated
	public static String fresh(MobProfiles.Profile p, Random rnd, List<String> taken) {
		return fresh(p, rnd, taken, Lang.RU);
	}
}
