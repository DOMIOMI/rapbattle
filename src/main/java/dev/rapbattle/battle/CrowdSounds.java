package dev.rapbattle.battle;

import dev.rapbattle.RapBattle;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;

import java.util.Random;

/**
 * Real crowd voices at the stage: cheering, "ooh" on punchlines, booing, whistles, laughter, gasps, applause and
 * hype chants. Each event has several recordings (see assets/rapbattle/sounds.json) and every play gets a slightly
 * different pitch, so the crowd never sounds like one loop.
 */
public final class CrowdSounds {
	private CrowdSounds() {}

	public static SoundEvent CHEER, OOH, BOO, WHISTLE, LAUGH, GASP, APPLAUSE, HYPE;
	/** Voiced fan shouts, same order as {@link Shouts#LINES}. */
	private static final java.util.Map<Crowd.Moment, java.util.List<SoundEvent>> SHOUTS = new java.util.EnumMap<>(Crowd.Moment.class);
	private static final java.util.Map<Crowd.Moment, java.util.List<SoundEvent>> SHOUTS_EN = new java.util.EnumMap<>(Crowd.Moment.class);
	private static final Random RND = new Random();

	public static void register() {
		CHEER = reg("crowd.cheer");
		OOH = reg("crowd.ooh");
		BOO = reg("crowd.boo");
		WHISTLE = reg("crowd.whistle");
		LAUGH = reg("crowd.laugh");
		GASP = reg("crowd.gasp");
		APPLAUSE = reg("crowd.applause");
		HYPE = reg("crowd.hype");
		for (int v = 0; v < VOICES; v++) {
			SOLO_BOO[v] = reg("solo.boo.v" + v);
			SOLO_CHEER[v] = reg("solo.cheer.v" + v);
		}
		Shouts.LINES.forEach((moment, lines) -> {
			java.util.List<SoundEvent> list = new java.util.ArrayList<>();
			for (int i = 1; i <= lines.size(); i++) list.add(reg("shout." + moment.name().toLowerCase(java.util.Locale.ROOT) + "." + i));
			SHOUTS.put(moment, list);
		});
		ShoutsEn.LINES.forEach((moment, lines) -> {
			java.util.List<SoundEvent> list = new java.util.ArrayList<>();
			for (int i = 1; i <= lines.size(); i++) list.add(reg("shout_en." + moment.name().toLowerCase(java.util.Locale.ROOT) + "." + i));
			SHOUTS_EN.put(moment, list);
		});
	}

	/** Six fans' own voices: boos and cheers per voice (each event holds several takes). */
	public static final int VOICES = 6;
	private static final SoundEvent[] SOLO_BOO = new SoundEvent[VOICES];
	private static final SoundEvent[] SOLO_CHEER = new SoundEvent[VOICES];

	/** A fan's own boo or cheer; the voice is fixed per fan, so each one sounds like a different person. */
	public static SoundEvent solo(boolean cheer, int voice) {
		return (cheer ? SOLO_CHEER : SOLO_BOO)[Math.floorMod(voice, VOICES)];
	}

	/** The voiced shout number {@code i} for this moment in the battle's language, or null. */
	public static SoundEvent shout(Crowd.Moment moment, int i, String lang) {
		java.util.List<SoundEvent> l = (dev.rapbattle.Lang.isRu(lang) ? SHOUTS : SHOUTS_EN).get(moment);
		return l == null || i < 0 || i >= l.size() ? null : l.get(i);
	}

	private static SoundEvent reg(String path) {
		Identifier id = Identifier.of(RapBattle.MOD_ID, path);
		return Registry.register(Registries.SOUND_EVENT, id, SoundEvent.of(id));
	}

	/** Plays at the stage centre; volume grows with the crowd size (an empty stage still has a few voices). */
	public static void play(ServerWorld w, Vec3d at, SoundEvent s, int crowd, float volume) {
		if (s == null || at == null) return;
		float size = Math.min(1.6f, 0.6f + crowd * 0.08f);
		float pitch = 0.92f + RND.nextFloat() * 0.16f;
		w.playSound(null, at.x, at.y + 1, at.z, s, SoundCategory.NEUTRAL, volume * size, pitch);
	}
}
