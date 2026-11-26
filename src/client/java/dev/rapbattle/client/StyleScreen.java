package dev.rapbattle.client;

import dev.rapbattle.battle.McStyle;
import dev.rapbattle.net.Payloads;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.resource.language.I18n;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;

import java.util.List;

/** MC style selection screen: three horizontal playstyles unlocked by cumulative Respect. */
public class StyleScreen extends Screen {
	private final Screen parent;
	private static Integer serverRespect;
	private static String serverStyle = McStyle.PUNCHER.id;
	private static String serverGateSummary = "";
	private final ButtonWidget[] selectButtons = new ButtonWidget[McStyle.values().length];

	public StyleScreen(Screen parent) {
		super(Text.translatable("rapbattle.style.title"));
		this.parent = parent;
	}

	public static void serverStatus(int respect, String style, String gateSummary) {
		serverRespect = respect;
		serverStyle = McStyle.of(style).id;
		serverGateSummary = gateSummary == null ? "" : gateSummary;
	}

	@Override
	protected void init() {
		boolean inWorld = client != null && client.world != null;
		if (inWorld) {
			serverRespect = null;
			serverGateSummary = "";
			ClientPlayNetworking.send(new Payloads.ActionC2S("{\"type\":\"style_status\"}"));
		}
		int cardW = 128, gap = 8;
		int totalW = cardW * 3 + gap * 2;
		int x0 = (width - totalW) / 2;
		int cardY = 52, cardH = Math.min(144, height - 96);

		McStyle[] styles = McStyle.values();
		for (int i = 0; i < styles.length; i++) {
			McStyle s = styles[i];
			int cx = x0 + i * (cardW + gap);
			selectButtons[i] = addDrawableChild(ButtonWidget.builder(Text.translatable("rapbattle.style.choose"), b -> {
				if (client != null && client.world != null) {
					ClientPlayNetworking.send(new Payloads.ActionC2S("{\"type\":\"set_style\",\"style\":\"" + s.id + "\"}"));
				}
			}).dimensions(cx + 8, cardY + cardH - 24, cardW - 16, 18).build());
		}
		refreshButtons();
		addDrawableChild(ButtonWidget.builder(Text.translatable("rapbattle.keys.back"), b -> close())
			.dimensions(width / 2 - 60, height - 28, 120, 20).build());
	}

	private void refreshButtons() {
		boolean inWorld = client != null && client.world != null;
		int respect = serverRespect != null ? serverRespect : 0;
		McStyle[] styles = McStyle.values();
		for (int i = 0; i < styles.length; i++) {
			ButtonWidget btn = selectButtons[i];
			if (btn == null) continue;
			McStyle s = styles[i];
			boolean loaded = !inWorld || serverRespect != null;
			boolean unlocked = inWorld && loaded && s.isUnlocked(respect);
			boolean active = inWorld && loaded && s.id.equals(serverStyle);
			btn.active = unlocked && !active;
			if (!inWorld) {
				btn.setMessage(Text.translatable("rapbattle.style.btn_offline"));
			} else if (!loaded) {
				btn.setMessage(Text.literal("..."));
			} else if (active) {
				btn.setMessage(Text.translatable("rapbattle.style.btn_active"));
			} else if (!unlocked) {
				btn.setMessage(Text.translatable("rapbattle.style.btn_locked"));
			} else {
				btn.setMessage(Text.translatable("rapbattle.style.choose"));
			}
		}
	}

	@Override
	public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
		refreshButtons();
		super.render(ctx, mouseX, mouseY, delta);
		ctx.drawCenteredTextWithShadow(textRenderer, title, width / 2, 12, 0xFFFFFFFF);

		boolean inWorld = client != null && client.world != null;
		Text sub = !inWorld
			? Text.translatable("rapbattle.style.need_world")
			: serverRespect == null
				? Text.translatable("rapbattle.style.loading")
				: Text.translatable("rapbattle.style.respect_have", serverRespect);
		ctx.drawCenteredTextWithShadow(textRenderer, sub, width / 2, 24, inWorld ? 0xFFFFD84A : 0xFF9AA0B8);
		if (inWorld && !serverGateSummary.isEmpty()) {
			ctx.drawCenteredTextWithShadow(textRenderer, Text.literal(serverGateSummary), width / 2, 36, 0xFFB8C4E0);
		}

		int cardW = 128, gap = 8;
		int totalW = cardW * 3 + gap * 2;
		int x0 = (width - totalW) / 2;
		int cardY = 52, cardH = Math.min(144, height - 96);
		int respect = serverRespect != null ? serverRespect : 0;

		McStyle[] styles = McStyle.values();
		for (int i = 0; i < styles.length; i++) {
			McStyle s = styles[i];
			int cx = x0 + i * (cardW + gap);
			boolean loaded = inWorld && serverRespect != null;
			boolean unlocked = loaded && s.isUnlocked(respect);
			boolean active = loaded && s.id.equals(serverStyle);
			int bg = active ? 0xE01B281E : unlocked ? 0xD8141622 : 0xC8101016;
			int border = active ? 0xFF5CE07B : unlocked ? 0xFF6A7599 : 0xFF3B3E4D;
			ctx.fill(cx, cardY, cx + cardW, cardY + cardH, bg);
			ctx.drawStrokedRectangle(cx, cardY, cardW, cardH, border);

			int ty = cardY + 8;
			Text name = Text.translatable("rapbattle.style." + s.id + ".name");
			ctx.drawCenteredTextWithShadow(textRenderer, name, cx + cardW / 2, ty, active ? 0xFF7CF0A0 : unlocked ? 0xFFFFFFFF : 0xFF888CA0);
			ty += 13;

			String req = s.unlockRespect == 0
				? I18n.translate("rapbattle.style.unlock_free")
				: I18n.translate("rapbattle.style.unlock_req", s.unlockRespect);
			ctx.drawCenteredTextWithShadow(textRenderer, req, cx + cardW / 2, ty, unlocked ? 0xFF9AE6B4 : 0xFFFFB86C);
			ty += 14;

			ty = drawWrapped(ctx, "+ " + I18n.translate("rapbattle.style." + s.id + ".plus"), cx + 7, ty, cardW - 14, 0xFF7CF0A0);
			ty += 4;
			drawWrapped(ctx, "− " + I18n.translate("rapbattle.style." + s.id + ".minus"), cx + 7, ty, cardW - 14, 0xFFFF9A8A);
		}
	}

	private int drawWrapped(DrawContext ctx, String text, int x, int y, int maxW, int color) {
		List<OrderedText> lines = textRenderer.wrapLines(Text.literal(text), maxW);
		for (OrderedText line : lines) {
			ctx.drawTextWithShadow(textRenderer, line, x, y, color);
			y += 10;
		}
		return y;
	}

	@Override
	public void close() {
		if (client != null) client.setScreen(parent);
	}
}
