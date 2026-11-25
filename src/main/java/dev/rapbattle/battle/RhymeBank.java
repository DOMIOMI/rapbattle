package dev.rapbattle.battle;

import dev.rapbattle.Lang;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/** Groups of words with the same ending. A player gets 4 words from one group per block of 4 lines. */
public final class RhymeBank {
	private RhymeBank() {}

	public static final List<List<String>> GROUPS = List.of(
		// Word forms: cases, plurals, verbs in the past, adjectives in different genders.
		List.of("кирками", "руками", "врагами", "веками", "блоками", "мобами", "углами"),
		List.of("сгорел", "умел", "задел", "пропел", "посмел", "успел", "сумел", "вспотел"),
		List.of("копала", "упала", "пропала", "достала", "устала", "попала", "застряла"),
		List.of("алмазов", "приказов", "отказов", "показов", "заказов", "рассказов", "экстазов"),
		List.of("ночами", "мечами", "лучами", "плечами", "ключами", "свечами", "бичами"),
		List.of("крипера", "репера", "вампира", "кумира", "сапфира", "мундира", "банкира"),
		List.of("горишь", "стоишь", "молчишь", "кричишь", "дрожишь", "летишь", "спешишь"),
		List.of("в пещере", "к двери", "на вере", "в карьере", "по мере", "в Незере", "в интерьере"),
		List.of("деревянный", "окаянный", "постоянный", "песчаный", "стеклянный", "рваный", "пьяный"),
		List.of("забили", "убили", "разбили", "добыли", "забыли", "любили", "скрафтили"),
		List.of("заснёт", "найдёт", "придёт", "умрёт", "соврёт", "поймёт", "крадёт"),
		List.of("кроватей", "жителей", "приятелей", "строителей", "зрителей", "родителей", "смотрителей"),
		List.of("лавой", "славой", "отравой", "оравой", "забавой", "державой", "расправой"),
		List.of("копаешь", "строишь", "теряешь", "играешь", "мечтаешь", "читаешь", "пропадаешь"),
		List.of("тобой", "толпой", "киркой", "тропой", "травой", "головой", "золой"),
		List.of("бытовуха", "невезуха", "толстуха", "уважуха", "заваруха", "житуха", "краснуха"),
		List.of("пчелиный", "крысиный", "совиный", "утиный", "гусиный", "мышиный", "звериный"),
		List.of("итог", "единорог", "налог", "носорог", "порог", "пирог", "урок", "замок"),
		List.of("поросёнок", "мышонок", "ягнёнок", "котёнок", "волчонок", "цыплёнок", "ребёнок"),
		List.of("ненужный", "жемчужный", "дружный", "южный", "натужный", "недужный", "наружный"),
		List.of("кнопка", "стопка", "коробка", "похлёбка", "пробка", "тропка", "попка"),
		List.of("корзина", "причина", "резина", "витрина", "скотина", "ангина", "малина", "лавина"),
		List.of("алиса", "лариса", "киса", "актриса", "крыса", "кулиса", "Бориса"),
		List.of("отменю", "догоню", "уроню", "хороню", "меню", "звоню", "казню"),
		List.of("алмаз", "приказ", "экстаз", "унитаз", "каркас", "запас", "атас"),
		List.of("кирпич", "паралич", "кулич", "сыч", "клич", "дичь", "спич"),
		List.of("бетон", "миллион", "барон", "ворон", "патрон", "закон", "шпион", "бидон"),
		List.of("кровать", "убивать", "воровать", "блевать", "зимовать", "крафтовать", "копать"),
		List.of("шахтёр", "костёр", "актёр", "монтёр", "дирижёр", "вахтёр", "суфлёр"),
		List.of("сосиска", "записка", "миска", "прописка", "ириска", "расписка", "редиска"),
		List.of("кубик", "рубик", "тюбик", "зубик", "чубик", "голубик", "клубик"),
		List.of("лопата", "зарплата", "граната", "палата", "вата", "расплата", "заплата"),
		List.of("алмазы", "заразы", "вазы", "проказы", "указы", "фразы", "отказы"),
		List.of("копыта", "сбита", "корыта", "избита", "забыта", "небрита", "элита"),
		List.of("кишок", "шок", "горшок", "порошок", "мешок", "вершок", "стишок"),
		List.of("дубина", "свинина", "рябина", "щетина", "пучина", "перина", "картина"),
		List.of("позор", "забор", "топор", "приговор", "вор", "мажор", "упор", "мотор")
	);

	public static final List<List<String>> GROUPS_EN = List.of(
		// Word forms: plurals, past tense, -ing forms, adjectives, nouns, Minecraft-flavoured where possible.
		List.of("mine", "shine", "spine", "line", "pine", "fine", "vine", "swine"),
		List.of("block", "rock", "clock", "knock", "shock", "lock", "flock", "sock"),
		List.of("night", "fight", "light", "bright", "fright", "bite", "knight", "flight"),
		List.of("cave", "grave", "save", "brave", "wave", "crave", "shave"),
		List.of("sword", "horde", "lord", "board", "bored", "cord", "scored"),
		List.of("stone", "bone", "throne", "zone", "groan", "alone", "blown"),
		List.of("gold", "cold", "bold", "sold", "hold", "fold", "rolled"),
		List.of("boom", "doom", "room", "gloom", "tomb", "loom", "broom"),
		List.of("scream", "dream", "beam", "steam", "team", "stream", "gleam"),
		List.of("smash", "crash", "trash", "ash", "flash", "dash", "clash"),
		List.of("burn", "turn", "learn", "earn", "fern", "spurn", "return"),
		List.of("king", "ring", "swing", "sting", "wing", "bling", "bring"),
		List.of("flow", "glow", "bow", "snow", "crow", "slow", "throw", "blow"),
		List.of("pain", "rain", "chain", "brain", "drain", "slain", "plain"),
		List.of("bed", "red", "dead", "head", "bread", "dread", "fled"),
		List.of("ground", "sound", "pound", "round", "found", "hound", "crowned"),
		List.of("kill", "chill", "hill", "drill", "grill", "skill", "thrill"),
		List.of("beat", "heat", "street", "feet", "meat", "cheat", "sweet"),
		List.of("creep", "sheep", "deep", "sleep", "keep", "sweep", "steep"),
		List.of("dust", "rust", "crust", "trust", "bust", "gust", "must"),
		List.of("flame", "game", "name", "shame", "lame", "fame", "tame"),
		List.of("wall", "fall", "tall", "crawl", "brawl", "hall", "call"),
		List.of("stack", "crack", "back", "pack", "smack", "track", "snack"),
		List.of("mining", "shining", "whining", "dining", "lining", "signing", "pining"),
		List.of("smashing", "crashing", "flashing", "dashing", "clashing", "thrashing", "stashing"),
		List.of("creeper", "deeper", "reaper", "sleeper", "keeper", "sweeper", "cheaper"),
		List.of("fighter", "lighter", "brighter", "biter", "writer", "tighter", "smiter"),
		List.of("blocks", "rocks", "clocks", "knocks", "shocks", "locks", "socks"),
		List.of("bones", "stones", "thrones", "zones", "groans", "clones", "drones"),
		List.of("caves", "graves", "waves", "saves", "raves", "craves", "paves"),
		List.of("towers", "powers", "flowers", "showers", "hours", "cowers", "devours"),
		List.of("burned", "turned", "learned", "earned", "spurned", "churned", "returned"),
		List.of("stacked", "cracked", "packed", "smacked", "tracked", "hacked", "backed"),
		List.of("screaming", "dreaming", "steaming", "beaming", "gleaming", "scheming", "teaming"),
		List.of("fighting", "biting", "lighting", "writing", "smiting", "sighting", "igniting"),
		List.of("fire", "wire", "higher", "liar", "dire", "spire", "choir"),
		List.of("cake", "fake", "shake", "snake", "break", "quake", "stake", "lake")
	);

	/** Picks {@code blocks} groups of 4 words from distinct random groups in the given language. */
	public static List<List<String>> pickBlocks(int blocks, Random rnd, String lang) {
		List<List<String>> groups = new ArrayList<>(Lang.isRu(lang) ? GROUPS : GROUPS_EN);
		Collections.shuffle(groups, rnd);
		List<List<String>> out = new ArrayList<>();
		for (int b = 0; b < blocks; b++) {
			List<String> g = new ArrayList<>(groups.get(b % groups.size()));
			Collections.shuffle(g, rnd);
			out.add(new ArrayList<>(g.subList(0, 4)));
		}
		return out;
	}

	@Deprecated
	public static List<List<String>> pickBlocks(int blocks, Random rnd) {
		return pickBlocks(blocks, rnd, Lang.RU);
	}
}
