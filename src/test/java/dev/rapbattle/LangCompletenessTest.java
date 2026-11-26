package dev.rapbattle;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

class LangCompletenessTest {
	private static JsonObject loadLang(String code) throws Exception {
		String path = "/assets/rapbattle/lang/" + code + ".json";
		try (InputStream in = LangCompletenessTest.class.getResourceAsStream(path)) {
			assertNotNull(in, "Missing lang resource: " + path);
			return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
		}
	}

	@Test
	void allKeysExistAndNonEmptyInBothLanguages() throws Exception {
		JsonObject ru = loadLang("ru_ru");
		JsonObject en = loadLang("en_us");

		TreeSet<String> ruKeys = new TreeSet<>();
		for (Map.Entry<String, JsonElement> e : ru.entrySet()) {
			if (e.getKey().startsWith("rapbattle.")) ruKeys.add(e.getKey());
		}
		TreeSet<String> enKeys = new TreeSet<>();
		for (Map.Entry<String, JsonElement> e : en.entrySet()) {
			if (e.getKey().startsWith("rapbattle.")) enKeys.add(e.getKey());
		}

		assertFalse(ruKeys.isEmpty(), "ru_ru must contain rapbattle.* keys");
		for (String k : ruKeys) {
			assertTrue(enKeys.contains(k), "Missing key in en_us.json: " + k);
			assertFalse(ru.get(k).getAsString().isBlank(), "Blank value in ru_ru.json: " + k);
			assertFalse(en.get(k).getAsString().isBlank(), "Blank value in en_us.json: " + k);
		}
		for (String k : enKeys) {
			assertTrue(ruKeys.contains(k), "Missing key in ru_ru.json: " + k);
		}
	}
}
