package dev.rapbattle;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

/**
 * Server settings, stored in config/rapbattle-server.json.
 *
 * The file (and the API keys in it) is created only when a server starts on this machine: a dedicated server,
 * or your own world in singleplayer / LAN. A friend who only joins your server never gets it, so sharing the
 * game folder cannot leak keys. Player-side settings live in {@code ClientConfig} (config/rapbattle.json).
 */
public class RapConfig {

	/** OpenRouter key. Empty = read OPENROUTER_API_KEY env var. */
	public String openRouterApiKey = "";
	/** Any OpenRouter model id. A battle costs well under a cent on cheap models. */
	public String openRouterModel = "deepseek/deepseek-v4-flash";


	/** OpenAI-compatible speech-to-text endpoint (Groq, OpenAI, local whisper server...). */
	public String sttBaseUrl = "https://api.groq.com/openai/v1";
	/** Empty = read RAPBATTLE_STT_KEY env var. */
	public String sttApiKey = "";
	public String sttModel = "whisper-large-v3";
	public String sttLanguage = "ru";

	/** Seconds the lobby stays open for friends to join (bosses get double). */
	public int lobbySeconds = 10;
	public double inviteRadius = 24.0;
	/** Players this close who did not join hear the beat and see the result. */
	public double spectatorRadius = 32.0;
	/** Old behaviour: an empty-hand hit starts the battle at once, without the rap/fight choice. */
	public boolean skipChoice = false;
	/** Mobs refuse players dressed below their rank. */
	public boolean dressCode = true;
	/** Choosing to fight instead of rapping makes the mob's kin nearby attack you. */
	public boolean kinRevenge = true;
	/** Losing to a boss kills the whole team. */
	public boolean bossLossKills = true;
	/** Hostile mobs shout a line in a speech bubble when they go for a player. */
	public boolean aggroLines = true;
	/** Judge and mobs may swear (as in the reference video). */
	public boolean allowSwearing = true;
	/** Multiplier for all rank tempos (1.0 = as designed). */
	public double tempoScale = 1.0;

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	private static final Set<String> SERVER_FIELDS = Set.of(
		"openRouterApiKey", "openRouterModel",
		"sttBaseUrl", "sttApiKey", "sttModel", "sttLanguage", "inviteRadius", "allowSwearing", "requireEmptyHand",
		"bpm", "linesSolo", "linesPerPlayerCoop", "mobLines");
	private static RapConfig instance = new RapConfig();

	public static RapConfig get() { return instance; }

	/** Called when a server starts on this machine. Creates the file and moves keys out of the old shared file. */
	public static void load() {
		Path dir = FabricLoader.getInstance().getConfigDir();
		Path path = dir.resolve("rapbattle-server.json");
		Path legacy = dir.resolve("rapbattle.json");
		try {
			JsonObject merged = new JsonObject();
			// Version 0.1 kept keys in config/rapbattle.json; carry them over once, then strip them from there.
			JsonObject old = null;
			boolean touched = false;
			if (Files.exists(legacy)) {
				old = JsonParser.parseString(Files.readString(legacy, StandardCharsets.UTF_8)).getAsJsonObject();
				for (String f : SERVER_FIELDS) {
					if (old.has(f)) {
						merged.add(f, old.get(f));
						old.remove(f);
						touched = true;
					}
				}
			}
			if (Files.exists(path)) {
				JsonObject cur = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
				for (var e : cur.entrySet()) {
					JsonElement prev = merged.get(e.getKey());
					// Never overwrite a migrated key with an empty one.
					boolean emptyString = e.getValue().isJsonPrimitive() && e.getValue().getAsJsonPrimitive().isString()
						&& e.getValue().getAsString().isBlank();
					if (prev == null || !emptyString) merged.add(e.getKey(), e.getValue());
				}
			}
			RapConfig c = GSON.fromJson(merged, RapConfig.class);
			if (c != null) instance = c;
			// Write the new file first; only then strip the keys from the old one, so they can never get lost.
			Files.writeString(path, GSON.toJson(instance), StandardCharsets.UTF_8);
			if (touched) {
				Files.writeString(legacy, GSON.toJson(old), StandardCharsets.UTF_8);
				RapBattle.LOG.info("Moved server settings and API keys from rapbattle.json to rapbattle-server.json");
			}
		} catch (IOException | RuntimeException e) {
			RapBattle.LOG.error("Failed to load config/rapbattle-server.json, using defaults", e);
		}
	}

	/**
	 * Saves new API keys (null = leave as is, "" = remove) into config/rapbattle-server.json and reloads it.
	 * Used by the settings screen in single player, where this machine is the server.
	 */
	public static synchronized void saveKeys(String openRouter, String stt) throws IOException {
		load();
		if (openRouter != null) instance.openRouterApiKey = openRouter.trim();
		if (stt != null) instance.sttApiKey = stt.trim();
		Path path = FabricLoader.getInstance().getConfigDir().resolve("rapbattle-server.json");
		Files.writeString(path, GSON.toJson(instance), StandardCharsets.UTF_8);
	}

	/** Shows a key as "введён (…a1b2)" without revealing it. */
	public static String mask(String key) {
		if (key == null || key.isBlank()) return "";
		String k = key.trim();
		return "…" + k.substring(Math.max(0, k.length() - 4));
	}

	public String openRouterKey() { return keyOrEnv(openRouterApiKey, "OPENROUTER_API_KEY"); }

	public String sttKey() { return keyOrEnv(sttApiKey, "RAPBATTLE_STT_KEY"); }

	private static String keyOrEnv(String key, String env) {
		if (key != null && !key.isBlank()) return key.trim();
		String v = System.getenv(env);
		return v == null ? "" : v.trim();
	}

	public boolean hasLlm() { return !openRouterKey().isEmpty(); }
}
