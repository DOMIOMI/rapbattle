package dev.rapbattle.battle;

import dev.rapbattle.Lang;
import dev.rapbattle.RapBattle;
import net.minecraft.entity.EntityType;
import net.minecraft.registry.Registries;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Who every mob is on the mic: base rank, rap names, the "language" shown above the translation table,
 * its ad-lib, its style for the language model, and which kin backs it up.
 */
public final class MobProfiles {
	private MobProfiles() {}

	public record Profile(String id, int baseRank, String species, String language, String adlib, String style,
						  List<String> names, String kin, boolean pack) {
		public String species(String lang) {
			if (Lang.isRu(lang)) return species;
			En en = EN.get(id);
			return en != null ? en.species : species;
		}

		public String language(String lang) {
			if (Lang.isRu(lang)) return language;
			En en = EN.get(id);
			return en != null ? en.language : "Mob";
		}

		public String adlib(String lang) {
			if (Lang.isRu(lang)) return adlib;
			En en = EN.get(id);
			return en != null ? en.adlib : "*mob sound*";
		}

		public String style(String lang) {
			if (Lang.isRu(lang)) return style;
			En en = EN.get(id);
			return en != null ? en.style : "An ordinary mob with no special quirks.";
		}

		public List<String> names(String lang) {
			if (Lang.isRu(lang)) return names;
			En en = EN.get(id);
			return en != null ? en.names : names;
		}
	}

	private record En(String species, String language, String adlib, String style, List<String> names) {}

	private static final Map<String, Profile> MAP = new HashMap<>();
	private static final Map<String, En> EN = new HashMap<>();

	/** Species whose nearby kin join the mob's team (wolf packs, zombie hordes, raids...). */
	private static final Set<String> PACKS = Set.of("wolf", "zombie", "husk", "drowned", "pillager", "vindicator", "bee",
		"zombified_piglin", "piglin", "endermite", "silverfish", "cod", "salmon", "tropical_fish", "skeleton_horse",
		"zombie_villager", "stray", "bogged", "parched");

	private static void p(String id, int rank, String species, String lang, String adlib, String kin, String style, String... names) {
		MAP.put(id, new Profile(id, rank, species, lang, adlib, style, List.of(names), kin, PACKS.contains(id)));
	}

	private static void e(String id, String species, String lang, String adlib, String style, String... names) {
		EN.put(id, new En(species, lang, adlib, style, List.of(names)));
	}

	static {
		// --- Leather (1): small fry
		p("chicken", 1, "Курица", "куриного", "Ко-ко-КО!", "chicken", "Истеричная, тараторит, вечно про яйца и KFC.", "Лил Клюв", "Яйцо Бой");
		p("rabbit", 1, "Кролик", "кроличьего", "*пип*", "rabbit", "Тараторит, но трусливый, всё про морковку и норы.", "Быстрый Ушан", "Лил Хоп");
		p("cod", 1, "Треска", "рыбьего", "*буль*", "fish", "Скользкая мелочь, задыхается на суше и паникует.", "Лил Чешуя");
		p("salmon", 1, "Лосось", "рыбьего", "*буль*", "fish", "Плывёт против течения и этим гордится.", "Розовый Флоу");
		p("tropical_fish", 1, "Тропическая рыба", "рыбьего", "*буль*", "fish", "Яркая, понтовая, курортный стиль.", "Курорт Тру");
		p("bat", 1, "Летучая мышь", "мышиного", "*пиии*", "bat", "Висит вниз головой, видит мир наоборот.", "Ночной Пиии");
		p("axolotl", 1, "Аксолотль", "аксолотлего", "*мур-буль*", "axolotl", "Милый, но коварный, притворяется мёртвым.", "Розовый Убийца");
		p("armadillo", 1, "Броненосец", "броненосьего", "*цок*", "armadillo", "Стеснительный, сворачивается в шар от любого шума.", "Панцирь Тру");
		p("donkey", 1, "Осёл", "ослиного", "Иа!", "horse", "Упрямый, повторяет одно и то же.", "Ослиный Упрям");
		p("mule", 1, "Мул", "мулячьего", "Иа!", "horse", "Упрямый трудяга, вечно с поклажей.", "Мул Тяжеловоз");
		p("endermite", 1, "Эндермит", "эндермитского", "*скрр*", "endermite", "Мелкий, нервный, кусачий.", "Мелкий Грызун");
		p("silverfish", 1, "Чешуйница", "чешуйного", "*скрр*", "silverfish", "Живёт в камне, выскакивает исподтишка.", "Каменный Жук");
		p("tadpole", 1, "Головастик", "головастикового", "*бульк*", "frog", "Мелкий, но обещает вырасти и вернуться.", "Лил Квак");
		p("allay", 1, "Аллей", "аллейского", "*ля-ля*", "allay", "Добрый, любит игроков и музыку.", "Лил Помогай");
		p("squid", 1, "Спрут", "спрутьего", "*пшш*", "squid", "Брызгает чернилами, когда нервничает.", "Чернильный");

		// --- Chain (2): street punks
		p("sheep", 2, "Овца", "овечьего", "Бе-е-е!", "sheep", "Мелкая, но злая. Гордится шерстью, ненавидит ножницы.", "Биг Шерсть", "Лил Руно", "МС Бе-е");
		p("cow", 2, "Корова", "коровьего", "Муу-у!", "cow", "Медленная, басовитая, флексит молоком и тем, что тут с первого дня.", "Мууу Флоу", "Биг Говядина");
		p("mooshroom", 2, "Грибная корова", "грибного", "Муу~", "cow", "Психоделика, трип-хоп, говорит загадками про грибы.", "Грибной Шаман");
		p("pig", 2, "Свинья", "свиного", "Хрю!", "pig", "Грязная, наглая, обожает морковь.", "Хрю Тру", "Лил Бекон");
		p("fox", 2, "Лиса", "лисьего", "*хи-хи*", "fox", "Хитрая, ворует всё подряд.", "Лил Хвост");
		p("cat", 2, "Кошка", "кошачьего", "Мррр", "cat", "Высокомерная, «я выше этого», игнорирует.", "Мяу Мэн", "Лил Когти");
		p("ocelot", 2, "Оцелот", "кошачьего", "Мррр", "cat", "Дикий кот джунглей, недоверчивый.", "Джунгли Кэт");
		p("bee", 2, "Пчела", "пчелиного", "Бзз", "bee", "Жужжит на одной ноте, всё про мёд и улей.", "Жужа");
		p("llama", 2, "Лама", "ламьего", "*тьфу*", "llama", "Высокомерная, плюётся.", "Плюй-Тру");
		p("trader_llama", 2, "Торговая лама", "ламьего", "*тьфу*", "llama", "Курьер торговца, плюётся и охраняет хозяина.", "Лама-Курьер");
		p("slime", 2, "Слизень", "слизневого", "Плюх", "slime", "Липкий, прыгучий.", "Слизь Ган", "Лил Желе");
		p("horse", 2, "Лошадь", "лошадиного", "*фррр*", "horse", "Гордая и быстрая, разгоняется к концу.", "Иго-Го Флоу");
		p("camel", 2, "Верблюд", "верблюжьего", "*хрррм*", "camel", "Двугорбый, медленный, пустынный философ.", "Двугорбый");
		p("goat", 2, "Коза", "козьего", "БЕЕЕ!", "goat", "Горная, бодливая, орёт без причины.", "Горный Бодун");
		p("parrot", 2, "Попугай", "попугайского", "*повтор*", "parrot", "Повторяет за другими вместо своих строк.", "Капитан Повтор");
		p("snow_golem", 2, "Снежный голем", "снежного", "*хрум снега*", "snow_golem", "Холодный, тает в жару.", "Снеговик-Тру");
		p("copper_golem", 2, "Медный голем", "медного", "*дзынь*", "copper_golem", "Педант, всё сортирует.", "Медяк");
		p("sniffer", 2, "Нюхач", "нюхачьего", "*сниф*", "sniffer", "Древний, вынюхивает всё наперёд.", "Древний Нюх");
		p("dolphin", 2, "Дельфин", "дельфиньего", "*клик-клик*", "dolphin", "Весёлый, умный, щёлкает сонаром.", "Флиппер");
		p("frog", 2, "Лягушка", "лягушачьего", "*ква*", "frog", "Ловит слова языком, как мух.", "Квак Тру");
		p("turtle", 2, "Черепаха", "черепашьего", "*хрум*", "turtle", "Медленная, но никогда не сбивается.", "Панцирь");
		p("nautilus", 2, "Наутилус", "наутилусьего", "*бульк*", "nautilus", "Закручивает строки спиралью.", "Спираль");
		p("strider", 2, "Страйдер", "страйдерского", "*дрожь*", "strider", "Мёрзнет вне лавы, дрожит.", "Дрожащий");
		p("happy_ghast", 2, "Счастливый гаст", "гастского", "*хи-хи*", "ghast", "Добрый и счастливый, не дерётся.", "Добрый Гаст");
		p("panda", 2, "Панда", "пандьего", "*чих*", "panda", "Характер зависит от настроения: ленивая, злая, игривая.", "Бамбук Бой");
		p("vex", 2, "Векс", "вексского", "*пиии*", "illager", "Мелкий злобный дух на побегушках у вызывателя.", "Лил Векс");
		p("glow_squid", 2, "Светящийся спрут", "светящегося", "*пшш~*", "squid", "Светится и добрый.", "Неон Спрут");
		p("pufferfish", 2, "Иглобрюх", "иглобрюшьего", "*пфф*", "fish", "Колючий, раздувается от злости.", "Колючий");

		// --- Iron (3): street level
		p("zombie", 3, "Зомби", "зомбячьего", "Ммозгии…", "zombie", "Медленный, тупой, вечно голодный, тянет гласные.", "Гнилой Бит", "Мёртвый Флоу", "Мозгоед");
		p("husk", 3, "Кадавр", "пустынного", "Кхх…", "zombie", "Сухой зомби из пустыни, не горит на солнце.", "Песок Бит");
		p("drowned", 3, "Утопленник", "утопленского", "Буль…", "zombie", "Мокрый, с трезубцем, из глубины.", "Мокрый Флоу");
		p("zombie_villager", 3, "Зомби-житель", "зомбячьего", "Хммм… мозги…", "zombie", "Бывший барыга, торгует мозгами.", "Барыга-Мертвец");
		p("skeleton", 3, "Скелет", "костяного", "*клац-клац*", "skeleton", "Сухой, язвительный, панчи «стрелами».", "Биг Кость", "Лил Скелли", "Сухарь");
		p("stray", 3, "Зимогор", "ледяного", "*хрусь*", "skeleton", "Ледяной скелет, холодный тон.", "Ледяная Кость");
		p("bogged", 3, "Болотник", "болотного", "*хлюп*", "skeleton", "Тинный скелет с ядовитыми стрелами.", "Тинная Кость");
		p("parched", 3, "Засохший", "засохшего", "*хрусь*", "skeleton", "Иссохший пустынный скелет, всё про жажду.", "Сухарь Пустыни");
		p("spider", 3, "Паук", "паучьего", "Ссс", "spider", "Плетёт рифмы как паутину.", "Восемь Лап", "Лил Паутина");
		p("villager", 3, "Житель", "жительского", "Хммм", "villager", "Барыга, всё мерит изумрудами.", "Хмм Дилер", "Изумруд Бой");
		p("wandering_trader", 3, "Странствующий торговец", "торгашского", "Хммм?", "villager", "Бродячий барыга, всё продаётся.", "Бродячий Барыга");
		p("wolf", 3, "Волк", "волчьего", "Ауф!", "wolf", "Дворовый, с понтами про стаю.", "Серый Флоу", "Лил Клык");
		p("piglin", 3, "Пиглин", "пиглинского", "Хрр-голд!", "piglin", "Помешан на золоте.", "Голд Хрю", "Блестяшка");
		p("zombified_piglin", 3, "Зомби-пиглин", "пиглинского", "*хррр*", "zombified_piglin", "Гнилой, но за своих порвёт.", "Гнилой Голд");
		p("polar_bear", 3, "Белый медведь", "медвежьего", "*рык*", "polar_bear", "Суровый, особенно рядом с медвежатами.", "Белый Шатун");
		p("skeleton_horse", 3, "Скелет-лошадь", "костяного", "*клац*", "skeleton", "Приходит с молнией и всадниками.", "Костяной Скакун");
		p("zombie_horse", 3, "Зомби-лошадь", "зомбячьего", "*хрр*", "zombie", "Гнилая, еле шевелится.", "Гнилая Кобыла");
		p("camel_husk", 3, "Верблюд-кадавр", "пустынного", "*кхх*", "camel", "Мёртвый верблюд, песчаная буря.", "Песчаный Горб");
		p("zombie_nautilus", 3, "Зомби-наутилус", "утопленского", "*бульк*", "zombie", "Гнилая раковина из глубин.", "Гнилая Раковина");
		p("phantom", 3, "Фантом", "фантомьего", "*скрежет*", "phantom", "Прилетает к тем, кто не спит, диссит за бессонницу.", "Бессонница");
		p("magma_cube", 3, "Магмовый куб", "магмового", "*бульк лавы*", "slime", "Горячий, делится на части.", "Лава Куб");

		// --- Gold (4): authorities
		p("creeper", 4, "Крипер", "криперского", "Тсссс…", "creeper", "Нервный, на взводе, взрывается от злости.", "Тссс Бум", "Зелёный Шум", "Лил Тротил");
		p("enderman", 4, "Эндермен", "эндерского", "Вррп", "enderman", "Холодный, загадочный, бесится от взгляда в глаза.", "MC Не Смотри", "Тёмный Тип", "Блок Вор");
		p("witch", 4, "Ведьма", "ведьминого", "Хи-хи-хи", "illager", "Варит зелья, хихикает, подлая.", "Ведьма Вайб", "Зелье Ган");
		p("blaze", 4, "Ифрит", "огненного", "Фшшш", "blaze", "Горячий, вспыльчивый, читает быстро.", "Лил Огонь");
		p("ghast", 4, "Гаст", "гастского", "*всхлип*", "ghast", "Грустный эмо-рэпер, плачет посреди строк.", "Плакса", "Эмо Гаст");
		p("pillager", 4, "Разбойник", "разбойничьего", "Хуэ!", "illager", "Гопник с арбалетом.", "Арбалет Бро");
		p("vindicator", 4, "Поборник", "разбойничьего", "Хуэ!", "illager", "Псих с топором, зовут Джонни.", "Джонни");
		p("guardian", 4, "Страж", "стражьего", "*бззз*", "guardian", "Пялится лазером, держит одну тему.", "Глаз");
		p("shulker", 4, "Шалкер", "шалкерского", "*клак*", "shulker", "Прячется в панцирь, не слушает соперника.", "Коробочка");
		p("breeze", 4, "Вихрь", "вихревого", "Вжух", "breeze", "Быстрый сбивчивый флоу, порывы ветра.", "Ветродуй");
		p("creaking", 4, "Скрипун", "скрипучего", "*скрип*", "creaking", "Двигается, только когда на него не смотрят.", "Скрип");
		p("cave_spider", 4, "Пещерный паук", "паучьего", "Ссс", "spider", "Ядовитый, мелкий, злой.", "Яд Тру");
		p("wither_skeleton", 4, "Иссушенный скелет", "иссушённого", "*клац*", "skeleton", "Мрачный скелет Незера с мечом.", "Чёрная Кость");

		// --- Diamond (5): legends
		p("iron_golem", 5, "Железный голем", "железного", "*БОНГ*", "villager", "Огромный, суровый охранник деревни, по понятиям.", "Биг Железо");
		p("ravager", 5, "Разоритель", "разорительского", "*РЁВ*", "illager", "Тупая мощь, не говорит, а ревёт.", "Танк");
		p("evoker", 5, "Вызыватель", "разбойничьего", "*шепот заклинаний*", "illager", "Маг-позёр, вызывает вексов.", "Маг-Позёр");
		p("illusioner", 5, "Иллюзионист", "разбойничьего", "*хлоп*", "illager", "Фокусник, подкидывает фальшивки.", "Фокусник");
		p("piglin_brute", 5, "Пиглин-брут", "пиглинского", "ХРРР!", "piglin", "Охранник бастиона, бьёт топором.", "Голд Брут");
		p("hoglin", 5, "Хоглин", "хоглиньего", "*фырк*", "hoglin", "Кабан Незера, тупая сила.", "Кабан");
		p("zoglin", 5, "Зоглин", "хоглиньего", "*ФЫРК*", "hoglin", "Гнилой кабан, бешеный.", "Гнилой Кабан");
		p("giant", 5, "Гигант", "зомбячьего", "*МММОЗГИ*", "zombie", "Огромный шутливый зомби.", "Огромный");

		// --- Netherite (6): bosses
		p("warden", 6, "Варден", "варденского", "*тук-тук*", "warden", "Слепой, ориентируется на звук, давящий и древний.", "Глухой", "Тишина");
		p("elder_guardian", 6, "Древний страж", "стражьего древнего", "*гул*", "guardian", "Древний, давящий, накладывает усталость.", "Старейшина");
		p("wither", 6, "Иссушитель", "иссушённого", "*хрррр*", "skeleton", "Три головы: злая, ноющая и философская.", "Три Головы");
		p("ender_dragon", 6, "Эндер-дракон", "драконьего", "*РРРАААР*", "ender_dragon", "Пафосный, древний, правит Краем.", "Дракон Края", "Финальный Босс");
	}

	static {
		// --- Leather (1): small fry
		e("chicken", "Chicken", "Chickenese", "Bok-bok-BOK!", "Hysterical, rattles on, always about eggs and KFC.", "Lil Beak", "Egg Boy");
		e("rabbit", "Rabbit", "Rabbit", "*squeak*", "Fast-talking but jumpy, all about carrots and burrows.", "Fast Ears", "Lil Hop");
		e("cod", "Cod", "Fish", "*bloop*", "Slippery small fry, gasping on land and panicking.", "Lil Scales");
		e("salmon", "Salmon", "Fish", "*bloop*", "Swims upstream and never shuts up about it.", "Pink Flow");
		e("tropical_fish", "Tropical Fish", "Fish", "*bloop*", "Flashy, show-off, resort-life drip.", "Resort Tru");
		e("bat", "Bat", "Bat", "*screee*", "Hangs upside down, sees the whole world inverted.", "Night Screech");
		e("axolotl", "Axolotl", "Axolotl", "*chirp-bloop*", "Cute but sneaky, plays dead to trick foes.", "Pink Killer");
		e("armadillo", "Armadillo", "Armadillo", "*clack*", "Shy, curls into a ball at the slightest noise.", "Shell Tru");
		e("donkey", "Donkey", "Donkey", "Hee-haw!", "Stubborn, repeats the same point over and over.", "Lil Hardhead");
		e("mule", "Mule", "Mule", "Hee-haw!", "Stubborn workhorse, always hauling heavy packs.", "Heavy Haul");
		e("endermite", "Endermite", "Endermite", "*skrrt*", "Tiny, twitchy, loves to bite.", "Lil Biter");
		e("silverfish", "Silverfish", "Silverfish", "*skrrt*", "Lives in stone, ambushes from out of nowhere.", "Stone Bug");
		e("tadpole", "Tadpole", "Tadpole", "*blub*", "Tiny, but swears he'll grow up and come back for revenge.", "Lil Croak");
		e("allay", "Allay", "Allay", "*la-la*", "Sweet, loves players and music.", "Lil Helper");
		e("squid", "Squid", "Squid", "*splurt*", "Squirts ink whenever it gets nervous.", "Inky");

		// --- Chain (2): street punks
		e("sheep", "Sheep", "Sheep", "Baa-a-a!", "Small but mean. Proud of its wool, hates shears.", "Big Wool", "Lil Fleece", "MC Baa");
		e("cow", "Cow", "Cow", "Mooo-o!", "Slow, deep-voiced, flexes its milk and being an OG since day one.", "Moo Flow", "Big Beef");
		e("mooshroom", "Mooshroom", "Mushroom", "Mooo~", "Psychedelic trip-hop, speaks in mushroom riddles.", "Shroom Shaman");
		e("pig", "Pig", "Pig", "Oink!", "Dirty, cocky, obsessed with carrots.", "Oink Tru", "Lil Bacon");
		e("fox", "Fox", "Fox", "*yip-yip*", "Sly, steals anything that isn't nailed down.", "Lil Tail");
		e("cat", "Cat", "Cat", "Purrr", "Arrogant, \"I'm above this\", acts unbothered.", "Meow Man", "Lil Claws");
		e("ocelot", "Ocelot", "Cat", "Purrr", "Wild jungle cat, trusts nobody.", "Jungle Cat");
		e("bee", "Bee", "Bee", "Bzzz", "Buzzes on one note, all about honey and the hive.", "Buzzy");
		e("llama", "Llama", "Llama", "*ptooey*", "Snobby, spits in your face.", "Spit Tru");
		e("trader_llama", "Trader Llama", "Llama", "*ptooey*", "Merchant's courier, spits and guards its boss.", "Courier Llama");
		e("slime", "Slime", "Slime", "Splat", "Sticky, bouncy.", "Slime Gun", "Lil Jelly");
		e("horse", "Horse", "Horse", "*neigh*", "Proud and fast, speeds up toward the end.", "Neigh Flow");
		e("camel", "Camel", "Camel", "*grrrm*", "Slow desert philosopher with two humps.", "Two-Hump");
		e("goat", "Goat", "Goat", "BAAAA!", "Mountain headbutter, screams for no reason.", "Ram Horn");
		e("parrot", "Parrot", "Parrot", "*squawk*", "Repeats everyone else's lines instead of writing its own.", "Cap'n Copy");
		e("snow_golem", "Snow Golem", "Snow", "*snow crunch*", "Ice-cold, melts in the heat.", "Frosty Tru");
		e("copper_golem", "Copper Golem", "Copper", "*clink*", "Nitpicky perfectionist, sorts everything.", "Copper Penny");
		e("sniffer", "Sniffer", "Sniffer", "*sniff*", "Ancient, sniffs out every move ahead of time.", "Ancient Nose");
		e("dolphin", "Dolphin", "Dolphin", "*click-click*", "Upbeat, smart, clicks with sonar.", "Flipper");
		e("frog", "Frog", "Frog", "*ribbit*", "Snatches words out of the air like flies.", "Ribbit Tru");
		e("turtle", "Turtle", "Turtle", "*crunch*", "Slow, but never loses the beat.", "Shellback");
		e("nautilus", "Nautilus", "Nautilus", "*blub*", "Twists its bars into a spiral.", "Spiral");
		e("strider", "Strider", "Strider", "*shiver*", "Freezes outside of lava, always shivering.", "Shiver");
		e("happy_ghast", "Happy Ghast", "Ghast", "*tee-hee*", "Kind and cheerful, doesn't like to fight.", "Good Ghast");
		e("panda", "Panda", "Panda", "*achoo*", "Mood swings hard: lazy, angry, or playful.", "Bamboo Boy");
		e("vex", "Vex", "Vex", "*shriek*", "Tiny spiteful spirit doing the Evoker's dirty work.", "Lil Vex");
		e("glow_squid", "Glow Squid", "Glow Squid", "*splurt~*", "Glowing and friendly.", "Neon Squid");
		e("pufferfish", "Pufferfish", "Pufferfish", "*pffft*", "Spiky, puffs up whenever it gets mad.", "Spiky");

		// --- Iron (3): street level
		e("zombie", "Zombie", "Zombie", "Braaains...", "Slow, dumb, always starving, drags out its vowels.", "Rotten Beat", "Dead Flow", "Brain Eater");
		e("husk", "Husk", "Desert Zombie", "Khh...", "Dry desert zombie, doesn't burn in the sun.", "Sand Beat");
		e("drowned", "Drowned", "Drowned", "Blub...", "Soaking wet, packs a trident, straight from the deep.", "Wet Flow");
		e("zombie_villager", "Zombie Villager", "Zombie", "Hmmm... brains...", "Ex-hustler, now dealing in brains.", "Dead Hustler");
		e("skeleton", "Skeleton", "Skeleton", "*clack-clack*", "Bone-dry, sarcastic, shoots punchlines like arrows.", "Big Bone", "Lil Skelly", "Dry Bones");
		e("stray", "Stray", "Ice Skeleton", "*crunch*", "Icy skeleton with a freezing delivery.", "Ice Bone");
		e("bogged", "Bogged", "Swamp Skeleton", "*squelch*", "Mossy swamp skeleton packing poison arrows.", "Moss Bone");
		e("parched", "Parched", "Parched", "*crunch*", "Dried-out desert skeleton, always rapping about thirst.", "Desert Dry");
		e("spider", "Spider", "Spider", "Hsss", "Weaves rhymes like a web.", "Eight Legs", "Lil Web");
		e("villager", "Villager", "Villager", "Hmmm", "Hustler, measures everything in emeralds.", "Hmm Dealer", "Emerald Boy");
		e("wandering_trader", "Wandering Trader", "Trader", "Hmmm?", "Traveling hustler, everything's for sale.", "Street Peddler");
		e("wolf", "Wolf", "Wolf", "Awoo!", "Street-tough, always flexing its pack.", "Grey Flow", "Lil Fang");
		e("piglin", "Piglin", "Piglin", "Snort-gold!", "Obsessed with gold.", "Gold Snout", "Bling Boy");
		e("zombified_piglin", "Zombified Piglin", "Piglin", "*grrr*", "Rotting, but rides or dies for its crew.", "Rotten Gold");
		e("polar_bear", "Polar Bear", "Bear", "*growl*", "Fierce, especially around its cubs.", "White Grizzly");
		e("skeleton_horse", "Skeleton Horse", "Skeleton", "*clack*", "Shows up with lightning and riders.", "Bone Steed");
		e("zombie_horse", "Zombie Horse", "Zombie", "*grr*", "Rotten, barely shambles along.", "Rotten Mare");
		e("camel_husk", "Camel Husk", "Desert", "*khh*", "Undead camel, brings a sandstorm.", "Sand Hump");
		e("zombie_nautilus", "Zombie Nautilus", "Drowned", "*blub*", "Rotting shell dragged up from the depths.", "Rotten Shell");
		e("phantom", "Phantom", "Phantom", "*screech*", "Swoops down on the sleepless, disses you for insomnia.", "Insomnia");
		e("magma_cube", "Magma Cube", "Magma", "*lava bloop*", "Scorching hot, splits into pieces.", "Lava Cube");

		// --- Gold (4): authorities
		e("creeper", "Creeper", "Creeper", "Tssss...", "Nervous, on edge, literally blows up when mad.", "Tsss Boom", "Green Static", "Lil TNT");
		e("enderman", "Enderman", "Ender", "Vrrp", "Cold, mysterious, snaps if you make eye contact.", "MC Don't Look", "Dark Figure", "Block Thief");
		e("witch", "Witch", "Witch", "Hee-hee-hee", "Brews potions, cackles, plays dirty.", "Witch Vibe", "Potion Gun");
		e("blaze", "Blaze", "Blaze", "Fshhh", "Hot-headed, fiery, spits fast.", "Lil Flame");
		e("ghast", "Ghast", "Ghast", "*sob*", "Sad emo rapper, cries right in the middle of its bars.", "Crybaby", "Emo Ghast");
		e("pillager", "Pillager", "Illager", "Hrrgh!", "Street thug with a crossbow.", "Crossbow Bro");
		e("vindicator", "Vindicator", "Illager", "Hrrgh!", "Axe-swinging psycho, goes by Johnny.", "Johnny");
		e("guardian", "Guardian", "Guardian", "*bzzz*", "Stares you down with a laser, locks onto one topic.", "The Eye");
		e("shulker", "Shulker", "Shulker", "*clack*", "Hides in its shell, refuses to listen to opponents.", "Box Boy");
		e("breeze", "Breeze", "Wind", "Whoosh", "Fast choppy flow, hits like gusts of wind.", "Windblast");
		e("creaking", "Creaking", "Creaking", "*creak*", "Only moves when you aren't looking.", "Creak");
		e("cave_spider", "Cave Spider", "Spider", "Hsss", "Venomous, tiny, vicious.", "Venom Tru");
		e("wither_skeleton", "Wither Skeleton", "Wither", "*clack*", "Grim Nether skeleton wielding a blade.", "Black Bone");

		// --- Diamond (5): legends
		e("iron_golem", "Iron Golem", "Iron", "*CLANG*", "Massive, stern village bouncer, lives by the code.", "Big Iron");
		e("ravager", "Ravager", "Ravager", "*ROAR*", "Brute force, doesn't talk, just roars.", "Tank");
		e("evoker", "Evoker", "Illager", "*spell whisper*", "Show-off sorcerer, summons vexes.", "Poser Mage");
		e("illusioner", "Illusioner", "Illager", "*poof*", "Stage magician, throws fakes and decoys.", "Trickster");
		e("piglin_brute", "Piglin Brute", "Piglin", "SNORT!", "Bastion enforcer, swings an axe.", "Gold Brute");
		e("hoglin", "Hoglin", "Hoglin", "*snort*", "Nether boar, pure brute force.", "Big Boar");
		e("zoglin", "Zoglin", "Hoglin", "*SNORT*", "Rotten boar, totally unhinged.", "Rotten Boar");
		e("giant", "Giant", "Zombie", "*BRAAAINS*", "Colossal joke-cracking zombie.", "Colossus");

		// --- Netherite (6): bosses
		e("warden", "Warden", "Warden", "*thump-thump*", "Blind, hunts by sound, oppressive and ancient.", "Dead Silent", "Hush");
		e("elder_guardian", "Elder Guardian", "Elder Guardian", "*hum*", "Ancient, suffocating, hits you with mining fatigue.", "The Elder");
		e("wither", "Wither", "Wither", "*grrrr*", "Three heads: one furious, one whiny, one philosophical.", "Three Heads");
		e("ender_dragon", "Ender Dragon", "Draconic", "*RRRAAAR*", "Grandiose, ancient, rules the End.", "End Dragon", "Final Boss");

		for (Map.Entry<String, Profile> entry : MAP.entrySet()) {
			En en = EN.get(entry.getKey());
			if (en == null) {
				RapBattle.LOG.error("MobProfiles: missing English entry for '{}'", entry.getKey());
			} else if (en.names.size() != entry.getValue().names.size()) {
				RapBattle.LOG.error("MobProfiles: name count mismatch for '{}' (ru={}, en={})",
					entry.getKey(), entry.getValue().names.size(), en.names.size());
			}
		}
	}

	public static java.util.Collection<Profile> all() { return MAP.values(); }

	public static int baseRankOf(String id) {
		Profile p = MAP.get(id);
		return p != null ? p.baseRank() : 2;
	}

	public static Profile of(EntityType<?> type) {
		String id = Registries.ENTITY_TYPE.getId(type).getPath();
		Profile p = MAP.get(id);
		if (p != null) return p;
		String species = type.getName().getString();
		return new Profile(id, 2, species, "мобского", "*звук моба*", "Обычный моб без особых примет.",
			List.of("MC " + species), id, false);
	}
}
