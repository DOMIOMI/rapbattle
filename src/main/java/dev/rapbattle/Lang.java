package dev.rapbattle;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.server.network.ServerPlayerEntity;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.IllegalFormatException;
import java.util.Map;

/**
 * The mod speaks Russian or English, chosen by each player's own Minecraft language: {@code ru_*} gets Russian,
 * everything else English. A battle speaks the language of whoever called it.
 * <p>
 * Chat messages go out as {@code Text.translatable} and each client shows them in its own language. Strings the
 * server has to render itself (inside {@code BattleView}, speech bubbles, prompts) are looked up here in the same
 * {@code assets/rapbattle/lang/*.json} files.
 */
public final class Lang {
	private Lang() {}

	public static final String RU = "ru", EN = "en";

	private static final Map<String, Map<String, String>> TABLES = new HashMap<>();

	/** "ru" or "en" from a Minecraft language code like "ru_ru" / "en_us". */
	public static String ofCode(String mcLanguage) {
		return mcLanguage != null && mcLanguage.toLowerCase(java.util.Locale.ROOT).startsWith("ru") ? RU : EN;
	}

	/** The language of this player's Minecraft client; English when unknown. */
	public static String of(ServerPlayerEntity p) {
		if (p == null) return EN;
		try {
			return ofCode(p.getClientOptions().language());
		} catch (RuntimeException e) {
			return EN;
		}
	}

	public static boolean isRu(String lang) { return RU.equals(lang); }

	/** Picks the Russian or the English value. */
	public static <T> T pick(String lang, T ru, T en) { return isRu(lang) ? ru : en; }

	/** Speech-to-text language code: "ru" or "en". */
	public static String sttCode(String lang) { return isRu(lang) ? "ru" : "en"; }

	/**
	 * Server-side translation of a key from the mod's lang files, formatted like Minecraft does ({@code %s},
	 * {@code %1$s}). Falls back to English, then to the key itself.
	 */
	public static String t(String lang, String key, Object... args) {
		String pattern = table(isRu(lang) ? "ru_ru" : "en_us").get(key);
		if (pattern == null) pattern = table("en_us").get(key);
		if (pattern == null) return key;
		if (args.length == 0) return pattern.replace("%%", "%");
		Object[] plain = new Object[args.length];
		for (int i = 0; i < args.length; i++) {
			plain[i] = args[i] instanceof net.minecraft.text.Text tx ? tx.getString() : String.valueOf(args[i]);
		}
		try {
			return String.format(java.util.Locale.ROOT, pattern, plain);
		} catch (IllegalFormatException e) {
			return pattern;
		}
	}

	private static synchronized Map<String, String> table(String code) {
		return TABLES.computeIfAbsent(code, c -> {
			Map<String, String> m = new HashMap<>();
			try (InputStream in = Lang.class.getResourceAsStream("/assets/" + RapBattle.MOD_ID + "/lang/" + c + ".json")) {
				if (in == null) return m;
				JsonObject o = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
				for (var e : o.entrySet()) m.put(e.getKey(), e.getValue().getAsString());
			} catch (Exception e) {
				RapBattle.LOG.error("Could not read lang file {}", c, e);
			}
			return m;
		});
	}
}
