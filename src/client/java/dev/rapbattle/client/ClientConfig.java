package dev.rapbattle.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Player-side settings in config/rapbattle.json. Never contains API keys (those live in rapbattle-server.json). */
public class ClientConfig {
	public float beatVolume = 1.0f;
	/** Volume of the beat when you only watch someone else's battle. */
	public float spectatorBeatVolume = 0.5f;
	public boolean spectatorHud = true;
	/** Mobs and players bounce to the beat. */
	public boolean animations = true;
	/** Gentle camera nod on the beat and punch FOV kick. */
	public boolean cameraMotion = true;
	/** Camera fly-over before the first round (Space skips it). */
	public boolean intro = true;
	/** Chains, glasses and snapbacks on everyone in a battle. */
	public boolean bling = true;
	/** Haze and spotlights around the stage. */
	public boolean stageFx = true;
	/** Real recorded beats (true) or the old note-block beat (false). */
	public boolean realBeats = true;
	/** Measured audio latency (beat heard → voice recorded), ms; set by the calibration in the settings. */
	public int latencyMs = 0;
	/** Your victory dance: 0 fist pump, 1 dab, 2 flex, 3 making it rain. */
	public int victoryDance = 0;

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static ClientConfig instance = new ClientConfig();

	public static ClientConfig get() { return instance; }

	public static void save() {
		Path path = FabricLoader.getInstance().getConfigDir().resolve("rapbattle.json");
		try {
			if (Files.exists(path)) {
				JsonObject o = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
				if (o.has("openRouterApiKey") || o.has("sttApiKey") || o.has("anthropicApiKey")) {
					dev.rapbattle.RapConfig.load();
				}
			}
			Files.writeString(path, GSON.toJson(instance), StandardCharsets.UTF_8);
		} catch (Exception ignored) {
			// Settings stay in memory for this session.
		}
	}

	public static void load() {
		Path path = FabricLoader.getInstance().getConfigDir().resolve("rapbattle.json");
		try {
			boolean legacyKeys = false;
			if (Files.exists(path)) {
				JsonObject o = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
				// An old-version file still holding keys: leave it alone, the server side moves the keys out first.
				legacyKeys = o.has("openRouterApiKey") || o.has("sttApiKey") || o.has("anthropicApiKey");
				ClientConfig c = GSON.fromJson(o, ClientConfig.class);
				if (c != null) instance = c;
			}
			if (!legacyKeys) Files.writeString(path, GSON.toJson(instance), StandardCharsets.UTF_8);
		} catch (Exception e) {
			instance = new ClientConfig();
		}
	}
}
