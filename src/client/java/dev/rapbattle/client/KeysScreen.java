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
	private Text message = Text.empty();
	private int messageColor = 0xFFB0B6CC;

	private static Boolean remoteLlm, remoteStt;

	private record Probe(Text text, boolean ok) {}

	public KeysScreen(Screen parent) {
		super(Text.translatable("rapbattle.keys.title"));
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
		openRouter = field(x, 44, w, "rapbattle.keys.openrouter_label");
		groq = field(x, 82, w, "rapbattle.keys.groq_label");
		int bw = 96, by = height - 26;
		addDrawableChild(ButtonWidget.builder(Text.translatable("rapbattle.keys.save"), b -> save()).dimensions(width / 2 - bw * 3 / 2 - 4, by, bw, 20).build())
			.active = editable;
		addDrawableChild(ButtonWidget.builder(Text.translatable("rapbattle.keys.check"), b -> check()).dimensions(width / 2 - bw / 2, by, bw, 20).build())
			.active = editable;
		addDrawableChild(ButtonWidget.builder(Text.translatable("rapbattle.keys.back"), b -> close()).dimensions(width / 2 + bw / 2 + 4, by, bw, 20).build());
	}

	private TextFieldWidget field(int x, int y, int w, String labelKey) {
		TextFieldWidget f = new TextFieldWidget(textRenderer, x, y, w, 18, Text.translatable(labelKey));
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
			say(Text.translatable("rapbattle.keys.saved"), 0xFF7CF0A0);
		} catch (Exception e) {
			say(Text.translatable("rapbattle.keys.save_failed", e.getMessage()), 0xFFFF6060);
		}
	}

	private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s; }

	/** Asks each service whether the saved key works (only the services themselves see the key). */
	private void check() {
		RapConfig c = RapConfig.get();
		say(Text.translatable("rapbattle.keys.checking"), 0xFFFFD84A);
		CompletableFuture.supplyAsync(() -> {
			Probe or = c.openRouterKey().isEmpty() ? new Probe(Text.translatable("rapbattle.keys.no_key"), false)
				: ok("https://openrouter.ai/api/v1/key", c.openRouterKey());
			Probe gr = c.sttKey().isEmpty() ? new Probe(Text.translatable("rapbattle.keys.no_key"), false)
				: ok(c.sttBaseUrl.replaceAll("/+$", "") + "/models", c.sttKey());
			return new Probe(Text.translatable("rapbattle.keys.check_result", or.text(), gr.text()), or.ok() && gr.ok());
		}).thenAccept(r -> client.execute(() -> say(r.text(), r.ok() ? 0xFF7CF0A0 : 0xFFFFB84A)));
	}

	private static Probe ok(String url, String key) {
		try {
			HttpResponse<Void> resp = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build().send(
				HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).header("Authorization", "Bearer " + key).GET().build(),
				HttpResponse.BodyHandlers.discarding());
			int code = resp.statusCode();
			if (code / 100 == 2) return new Probe(Text.translatable("rapbattle.keys.ok"), true);
			if (code == 401 || code == 403) return new Probe(Text.translatable("rapbattle.keys.rejected"), false);
			return new Probe(Text.translatable("rapbattle.keys.http_error", code), false);
		} catch (Exception e) {
			return new Probe(Text.translatable("rapbattle.keys.no_connection"), false);
		}
	}

	private void say(Text text, int color) {
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
			for (var line : textRenderer.wrapLines(Text.translatable("rapbattle.keys.hint_local"), 300)) {
				ctx.drawTextWithShadow(textRenderer, line, x, ty, 0xFF9AA0B8);
				ty += 10;
			}
		} else {
			int ty = 33;
			for (var line : textRenderer.wrapLines(Text.translatable("rapbattle.keys.hint_remote"), 300)) {
				ctx.drawTextWithShadow(textRenderer, line, x, ty, 0xFFFFD84A);
				ty += 10;
			}
			remote(ctx, x, 71, Text.translatable("rapbattle.keys.remote_llm"), remoteLlm);
			remote(ctx, x, 109, Text.translatable("rapbattle.keys.remote_stt"), remoteStt);
		}
		if (!message.getString().isEmpty()) {
			var lines = textRenderer.wrapLines(message, 320);
			int my = height - 38 - lines.size() * 10;
			for (var line : lines) {
				ctx.drawCenteredTextWithShadow(textRenderer, line, width / 2, my, messageColor);
				my += 10;
			}
		}
	}

	private void status(DrawContext ctx, int x, int y, String name, String key) {
		boolean set = !key.isEmpty();
		Text line = set ? Text.translatable("rapbattle.keys.status_set", name, RapConfig.mask(key))
			: Text.translatable("rapbattle.keys.status_unset", name);
		ctx.drawTextWithShadow(textRenderer, line, x, y, set ? 0xFF7CF0A0 : 0xFFFF8080);
	}

	private void remote(DrawContext ctx, int x, int y, Text name, Boolean set) {
		String key = set == null ? "rapbattle.keys.remote_asking" : set ? "rapbattle.keys.remote_yes" : "rapbattle.keys.remote_no";
		ctx.drawTextWithShadow(textRenderer, Text.translatable(key, name), x, y, set == null ? 0xFFB0B6CC : set ? 0xFF7CF0A0 : 0xFFFF8080);
	}

	@Override
	public void close() {
		client.setScreen(parent);
	}
}
