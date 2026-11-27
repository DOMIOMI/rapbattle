package dev.rapbattle.client;

import dev.rapbattle.battle.BeatTracks;
import dev.rapbattle.net.BattleView;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.client.sound.SoundInstance;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;

/**
 * Plays the battle's real beat (a track made with ElevenLabs Music and fitted to the grid). The track starts on the
 * first beat of the countdown and is restarted exactly at every loop boundary of the battle clock, so it never drifts
 * from the table. While it plays, the note-block drums stay quiet.
 */
public final class BeatPlayer {
	private BeatPlayer() {}

	private static SoundInstance playing;
	private static long loop = Long.MIN_VALUE;
	private static int battle = -1;
	private static int warmedBattle = -1;
	private static SoundInstance warming;

	/** The track of the current battle, if real beats are on and the track exists. */
	public static BeatTracks.Track track() {
		BattleView v = ClientBattle.view;
		if (v == null || v.beatTrack == null || !ClientConfig.get().realBeats) return null;
		return BeatTracks.byId(v.beatTrack);
	}

	/** Called every frame. */
	public static void update() {
		BattleView v = ClientBattle.view;
		BeatTracks.Track t = track();
		if (warming != null) {
			MinecraftClient.getInstance().getSoundManager().stop(warming);
			warming = null;
		}
		if (t != null && v != null && warmedBattle != v.id && ("LOBBY".equals(v.phase) || "PREP".equals(v.phase))) {
			warmedBattle = v.id;
			warming = PositionedSoundInstance.ui(SoundEvent.of(Identifier.of("rapbattle", "beat." + t.id())), 1.0f, 0.001f);
			MinecraftClient.getInstance().getSoundManager().play(warming);
		}
		boolean on = t != null && "TIMELINE".equals(v.phase) && v.totalBeats > 0;
		long clock = on ? ClientBattle.clock() : -1;
		if (!on || clock < 0 || clock >= (long) v.totalBeats * v.beatMs) {
			stop();
			loop = Long.MIN_VALUE;
			return;
		}
		if (v.id != battle) {
			stop();
			loop = Long.MIN_VALUE;
			battle = v.id;
		}
		long loopMs = (long) t.bars() * 4 * v.beatMs;
		long idx = clock / loopMs;
		if (idx == loop) return;
		stop();
		loop = idx;
		// Late by more than half a beat (joined mid-loop, lag): wait for the next loop instead of playing out of time.
		if (clock - idx * loopMs > v.beatMs / 2) return;
		float vol = v.spectator ? ClientConfig.get().spectatorBeatVolume : ClientConfig.get().beatVolume;
		if (vol <= 0) return;
		playing = PositionedSoundInstance.ui(SoundEvent.of(Identifier.of("rapbattle", "beat." + t.id())), 1.0f, Math.min(1f, vol));
		MinecraftClient.getInstance().getSoundManager().play(playing);
	}

	public static void stop() {
		if (playing != null) MinecraftClient.getInstance().getSoundManager().stop(playing);
		playing = null;
	}
}
