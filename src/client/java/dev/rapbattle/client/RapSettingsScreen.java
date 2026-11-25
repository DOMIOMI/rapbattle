package dev.rapbattle.client;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.CyclingButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.text.Text;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.DoubleSupplier;

/** Rap battle settings: sound, looks, the victory dance, and the way to API keys and latency calibration. */
public class RapSettingsScreen extends Screen {
	private final Screen parent;

	public RapSettingsScreen(Screen parent) {
		super(Text.translatable("rapbattle.settings.title"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		ClientConfig c = ClientConfig.get();
		int w = 150, gap = 4, x1 = width / 2 - w - gap / 2, x2 = width / 2 + gap / 2;
		int y = 38;
		addDrawableChild(slider(x1, y, w, "rapbattle.settings.beat_volume", () -> c.beatVolume, v -> c.beatVolume = v.floatValue()));
		addDrawableChild(slider(x2, y, w, "rapbattle.settings.spectator_beat_volume", () -> c.spectatorBeatVolume, v -> c.spectatorBeatVolume = v.floatValue()));
		y += 24;
		addDrawableChild(toggle(x1, y, w, "rapbattle.settings.real_beats", c.realBeats, v -> c.realBeats = v));
		addDrawableChild(toggle(x2, y, w, "rapbattle.settings.animations", c.animations, v -> c.animations = v));
		y += 24;
		addDrawableChild(toggle(x1, y, w, "rapbattle.settings.intro", c.intro, v -> c.intro = v));
		addDrawableChild(toggle(x2, y, w, "rapbattle.settings.bling", c.bling, v -> c.bling = v));
		y += 24;
		addDrawableChild(toggle(x1, y, w, "rapbattle.settings.stage_fx", c.stageFx, v -> c.stageFx = v));
		addDrawableChild(toggle(x2, y, w, "rapbattle.settings.spectator_hud", c.spectatorHud, v -> c.spectatorHud = v));
		y += 24;
		List<Integer> dances = List.of(0, 1, 2, 3);
		addDrawableChild(CyclingButtonWidget.<Integer>builder(i -> Text.translatable("rapbattle.settings.dance." + i), c.victoryDance % 4).values(dances)
			.build(x1, y, w * 2 + gap, 20, Text.translatable("rapbattle.settings.victory_dance"), (b, v) -> c.victoryDance = v));
		y += 32;
		addDrawableChild(ButtonWidget.builder(Text.translatable("rapbattle.settings.api_keys"), b -> client.setScreen(new KeysScreen(this)))
			.dimensions(x1, y, w, 20).build());
		addDrawableChild(ButtonWidget.builder(Text.translatable("rapbattle.settings.latency", c.latencyMs), b -> client.setScreen(new CalibrationScreen(this)))
			.dimensions(x2, y, w, 20).build());
		addDrawableChild(ButtonWidget.builder(Text.translatable("rapbattle.settings.done"), b -> close()).dimensions(width / 2 - 100, height - 28, 200, 20).build());
	}

	@Override
	public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
		super.render(ctx, mouseX, mouseY, delta);
		ctx.drawCenteredTextWithShadow(textRenderer, title, width / 2, 10, 0xFFFFFFFF);
		ctx.drawCenteredTextWithShadow(textRenderer, Text.translatable("rapbattle.settings.crowd_hint"),
			width / 2, 24, 0xFF9AA0B8);
	}

	@Override
	public void close() {
		ClientConfig.save();
		client.setScreen(parent);
	}

	private static CyclingButtonWidget<Boolean> toggle(int x, int y, int w, String key, boolean value, Consumer<Boolean> set) {
		return CyclingButtonWidget.onOffBuilder(value).build(x, y, w, 20, Text.translatable(key), (b, v) -> set.accept(v));
	}

	private static SliderWidget slider(int x, int y, int w, String key, DoubleSupplier get, Consumer<Double> set) {
		return new SliderWidget(x, y, w, 20, Text.empty(), Math.max(0, Math.min(1, get.getAsDouble()))) {
			{
				updateMessage();
			}

			@Override
			protected void updateMessage() {
				setMessage(Text.translatable("rapbattle.settings.slider_value", Text.translatable(key), Math.round(value * 100)));
			}

			@Override
			protected void applyValue() {
				set.accept(value);
			}
		};
	}
}
