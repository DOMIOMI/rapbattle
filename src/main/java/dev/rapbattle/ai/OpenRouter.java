package dev.rapbattle.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.rapbattle.Lang;
import dev.rapbattle.RapBattle;
import dev.rapbattle.RapConfig;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/** OpenRouter chat completions (OpenAI-compatible): lets the judge run on any cheap model. */
final class OpenRouter {
	private OpenRouter() {}

	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

	static JsonObject ask(String system, String user, Map<String, Object> schema, long maxTokens) {
		return ask(system, user, schema, maxTokens, 0.8, Lang.RU);
	}

	static JsonObject ask(String system, String user, Map<String, Object> schema, long maxTokens, String lang) {
		return ask(system, user, schema, maxTokens, 0.8, lang);
	}

	static JsonObject ask(String system, String user, Map<String, Object> schema, long maxTokens, double temperature, String lang) {
		RapConfig cfg = RapConfig.get();
		JsonObject body = new JsonObject();
		body.addProperty("model", cfg.openRouterModel);
		body.addProperty("max_tokens", maxTokens);
		body.addProperty("temperature", temperature);
		// Reasoning models (DeepSeek V4 etc.) otherwise spend the whole budget thinking: slow and no answer.
		JsonObject reasoning = new JsonObject();
		reasoning.addProperty("enabled", false);
		body.add("reasoning", reasoning);
		JsonArray messages = new JsonArray();
		String jsonRule = Lang.isRu(lang)
			? "\nОтвечай только JSON-объектом по схеме, без пояснений и без ```."
			: "\nReply only with a JSON object matching the schema, with no explanations and no ```.";
		messages.add(msg("system", system + jsonRule));
		messages.add(msg("user", user));
		body.add("messages", messages);

		JsonObject jsonSchema = new JsonObject();
		jsonSchema.addProperty("name", "answer");
		jsonSchema.addProperty("strict", true);
		jsonSchema.add("schema", com.google.gson.JsonParser.parseString(new com.google.gson.Gson().toJson(schema)));
		JsonObject format = new JsonObject();
		format.addProperty("type", "json_schema");
		format.add("json_schema", jsonSchema);
		body.add("response_format", format);
		// Only route to providers that actually honour the JSON schema.
		JsonObject provider = new JsonObject();
		provider.addProperty("require_parameters", true);
		body.add("provider", provider);

		try {
			HttpRequest req = HttpRequest.newBuilder(URI.create("https://openrouter.ai/api/v1/chat/completions"))
				.timeout(Duration.ofSeconds(60))
				.header("Authorization", "Bearer " + cfg.openRouterKey())
				.header("Content-Type", "application/json")
				.header("X-Title", "Rap Battle (Minecraft mod)")
				.POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
				.build();
			HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
			if (resp.statusCode() / 100 != 2) {
				RapBattle.LOG.error("OpenRouter HTTP {}: {}", resp.statusCode(), resp.body());
				return null;
			}
			JsonObject o = JsonParser.parseString(resp.body()).getAsJsonObject();
			JsonElement content = o.getAsJsonArray("choices").get(0).getAsJsonObject()
				.getAsJsonObject("message").get("content");
			if (content == null || content.isJsonNull()) {
				RapBattle.LOG.error("OpenRouter returned no content: {}", resp.body());
				return null;
			}
			return parseLenient(content.getAsString());
		} catch (Exception e) {
			RapBattle.LOG.error("OpenRouter request failed", e);
			return null;
		}
	}

	/** Some models still wrap JSON in ```json fences or add text around it. */
	static JsonObject parseLenient(String text) {
		String t = text.trim();
		int start = t.indexOf('{'), end = t.lastIndexOf('}');
		if (start >= 0 && end > start) t = t.substring(start, end + 1);
		return JsonParser.parseString(t).getAsJsonObject();
	}

	private static JsonObject msg(String role, String content) {
		JsonObject m = new JsonObject();
		m.addProperty("role", role);
		m.addProperty("content", content);
		return m;
	}
}
