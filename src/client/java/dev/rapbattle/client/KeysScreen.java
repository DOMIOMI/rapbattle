package dev.rapbattle.client;

import dev.rapbattle.RapConfig;
import dev.rapbattle.net.Payloads;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * API keys: see whether they are set and type new ones. Keys belong to whoever runs the world, so they can be edited
 * here in single player (this machine is the server) and from the main menu; on someone else's server the screen only
 * shows whether that server has its keys. Keys are shown masked and never sent anywhere except the services themselves
 * (the "Проверить" button).
 */
public class KeysScreen extends Screen {
	private final Screen parent;
	private TextFieldWidget openRouter, groq;
	private boolean editable;
	private String message = "";
	private int messageColor = 0xFFB0B6CC;

	private static Boolean remoteLlm, remoteStt;

	public KeysScreen(Screen parent) {
		super(Text.literal("Рэп-баттлы: ключи API"));
		this.parent = parent;
	}

	/** The server answered: are its keys set. */
	public static void serverStatus(boolean llm, boolean stt) {
		remoteLlm = llm;
		remoteStt = stt;
	}

	@Override
	protected void init() {
		editable = client.world == null || client.isIntegratedServerRunning();
		if (editable) {
			RapConfig.load();
		} else {
			remoteLlm = null;
			remoteStt = null;
			ClientPlayNetworking.send(new Payloads.ActionC2S("{\"type\":\"keystatus\"}"));
		}
		int w = 300, x = width / 2 - w / 2;
		openRouter = field(x, 44, w, "OpenRouter — нейросеть (куплеты и судья)");
		groq = field(x, 82, w, "Groq — распознавание голоса");
		int bw = 96, by = height - 26;
		addDrawableChild(ButtonWidget.builder(Text.literal("Сохранить"), b -> save()).dimensions(width / 2 - bw * 3 / 2 - 4, by, bw, 20).build())
			.active = editable;
		addDrawableChild(ButtonWidget.builder(Text.literal("Проверить"), b -> check()).dimensions(width / 2 - bw / 2, by, bw, 20).build())
			.active = editable;
		addDrawableChild(ButtonWidget.builder(Text.literal("Назад"), b -> close()).dimensions(width / 2 + bw / 2 + 4, by, bw, 20).build());
	}

	private TextFieldWidget field(int x, int y, int w, String label) {
		TextFieldWidget f = new TextFieldWidget(textRenderer, x, y, w, 18, Text.literal(label));
		f.setMaxLength(300);
		f.setEditable(editable);
		// Typed keys show as dots: nobody looking over your shoulder (or at your stream) sees them.
		f.addFormatter((text, start) -> OrderedText.styledForwardsVisitedString("•".repeat(text.length()), Style.EMPTY));
		addDrawableChild(f);
		return f;
	}

	private void save() {
		try {
			RapConfig.saveKeys(blankToNull(openRouter.getText()), blankToNull(groq.getText()));
			openRouter.setText("");
			groq.setText("");
			say("Сохранено в config/rapbattle-server.json. Новые ключи работают со следующего баттла.", 0xFF7CF0A0);
		} catch (Exception e) {
			say("Не удалось сохранить: " + e.getMessage(), 0xFFFF6060);
		}
	}

	private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s; }

	/** Asks each service whether the saved key works (only the services themselves see the key). */
	private void check() {
		RapConfig c = RapConfig.get();
		say("Проверяю…", 0xFFFFD84A);
		CompletableFuture.supplyAsync(() -> {
			StringBuilder out = new StringBuilder();
			out.append("OpenRouter: ").append(c.openRouterKey().isEmpty() ? "нет ключа"
				: ok("https://openrouter.ai/api/v1/key", c.openRouterKey()));
			out.append(" · Groq: ").append(c.sttKey().isEmpty() ? "нет ключа"
				: ok(c.sttBaseUrl.replaceAll("/+$", "") + "/models", c.sttKey()));
			return out.toString();
		}).thenAccept(r -> client.execute(() -> say(r, r.contains("ошибка") || r.contains("нет ключа") ? 0xFFFFB84A : 0xFF7CF0A0)));
	}

	private static String ok(String url, String key) {
		try {
			HttpResponse<Void> resp = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build().send(
				HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).header("Authorization", "Bearer " + key).GET().build(),
				HttpResponse.BodyHandlers.discarding());
			int code = resp.statusCode();
			return code / 100 == 2 ? "работает" : code == 401 || code == 403 ? "ошибка: ключ не принят" : "ошибка HTTP " + code;
		} catch (Exception e) {
			return "ошибка: нет связи";
		}
	}

	private void say(String text, int color) {
		message = text;
		messageColor = color;
	}

	@Override
	public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
		super.render(ctx, mouseX, mouseY, delta);
		ctx.drawCenteredTextWithShadow(textRenderer, title, width / 2, 14, 0xFFFFFFFF);
		int x = width / 2 - 150;
		RapConfig c = RapConfig.get();
		if (editable) {
			status(ctx, x, 33, "OpenRouter", c.openRouterKey());
			status(ctx, x, 71, "Groq", c.sttKey());
			int ty = 110;
			for (var line : textRenderer.wrapLines(Text.literal("Пустое поле — ключ не меняется. Где взять: openrouter.ai/keys, console.groq.com/keys. "
				+ "Без ключей мод работает в запасном режиме: готовые куплеты и судья «на слух»."), 300)) {
				ctx.drawTextWithShadow(textRenderer, line, x, ty, 0xFF9AA0B8);
				ty += 10;
			}
		} else {
			int ty = 33;
			for (var line : textRenderer.wrapLines(Text.literal("Ты на чужом сервере: ключи задаёт его владелец в config/rapbattle-server.json."), 300)) {
				ctx.drawTextWithShadow(textRenderer, line, x, ty, 0xFFFFD84A);
				ty += 10;
			}
			remote(ctx, x, 71, "Нейросеть", remoteLlm);
			remote(ctx, x, 109, "Распознавание голоса", remoteStt);
		}
		if (!message.isEmpty()) {
			var lines = textRenderer.wrapLines(Text.literal(message), 320);
			int my = height - 38 - lines.size() * 10;
			for (var line : lines) {
				ctx.drawCenteredTextWithShadow(textRenderer, line, width / 2, my, messageColor);
				my += 10;
			}
		}
	}

	private void status(DrawContext ctx, int x, int y, String name, String key) {
		boolean set = !key.isEmpty();
		ctx.drawTextWithShadow(textRenderer, Text.literal(name + ": " + (set ? "введён ✔ (" + RapConfig.mask(key) + ")" : "не введён ✘")),
			x, y, set ? 0xFF7CF0A0 : 0xFFFF8080);
	}

	private void remote(DrawContext ctx, int x, int y, String name, Boolean set) {
		String s = set == null ? "спрашиваю сервер…" : set ? "на сервере есть ✔" : "на сервере нет ✘";
		ctx.drawTextWithShadow(textRenderer, Text.literal(name + ": " + s), x, y, set == null ? 0xFFB0B6CC : set ? 0xFF7CF0A0 : 0xFFFF8080);
	}

	@Override
	public void close() {
		client.setScreen(parent);
	}
}
