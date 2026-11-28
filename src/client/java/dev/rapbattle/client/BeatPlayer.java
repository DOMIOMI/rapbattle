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
	private static long warmedAtMs = -1;
	public static volatile long lastWarmupCallMs = -1;
	public static volatile long lastBeat0PlayCallMs = -1;
	public static volatile long lastBeat0ClockMs = -1;
	public static volatile long lastWarmupLeadMs = -1;

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
			warmedAtMs = System.currentTimeMillis();
			long t0 = System.nanoTime();
			warming = PositionedSoundInstance.ui(SoundEvent.of(Identifier.of("rapbattle", "beat." + t.id())), 1.0f, 0.001f);
			MinecraftClient.getInstance().getSoundManager().play(warming);
			lastWarmupCallMs = (System.nanoTime() - t0) / 1_000_000L;
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
		long inLoop = clock - idx * loopMs;
		if (idx == loop) {
			keepInSync(inLoop, loopMs, t, v);
			return;
		}
		stop();
		loop = idx;
		// 6.32: started late (joined mid-loop, a lag spike) - play anyway; keepInSync seeks it to the clock.
		// Only the last second of a loop is left silent: a seek that late is not worth it.
		if (inLoop > loopMs - 1000) return;
		float vol = v.spectator ? ClientConfig.get().spectatorBeatVolume : ClientConfig.get().beatVolume;
		if (vol <= 0) return;
		long t0 = System.nanoTime();
		playing = PositionedSoundInstance.ui(SoundEvent.of(Identifier.of("rapbattle", "beat." + t.id())), 1.0f, Math.min(1f, vol));
		MinecraftClient.getInstance().getSoundManager().play(playing);
		if (idx == 0) {
			lastBeat0PlayCallMs = (System.nanoTime() - t0) / 1_000_000L;
			lastBeat0ClockMs = clock;
			lastWarmupLeadMs = warmedAtMs > 0 ? System.currentTimeMillis() - warmedAtMs : -1;
			dev.rapbattle.RapBattle.LOG.info("BeatPlayer beat0 track={} clock={}ms playCall={}ms warmupCall={}ms warmupLead={}ms",
				t.id(), lastBeat0ClockMs, lastBeat0PlayCallMs, lastWarmupCallMs, lastWarmupLeadMs);
		}
	}

	private static long lastSyncMs, lastReplayMs;
	public static volatile int seeks;

	/**
	 * 6.32: after a lag spike the sound and the battle clock part ways and the old code left a whole loop of
	 * silence. Every 400 ms compare the OpenAL play position with the clock and seek the source when it is off by
	 * more than 70 ms; if the sound engine dropped the source, start it again (at most once in 2 s).
	 */
	private static void keepInSync(long inLoop, long loopMs, BeatTracks.Track t, BattleView v) {
		long now = System.currentTimeMillis();
		if (now - lastSyncMs < 400 || inLoop > loopMs - 600) return;
		lastSyncMs = now;
		var sm = MinecraftClient.getInstance().getSoundManager();
		if (playing == null || !sm.isPlaying(playing)) {
			float vol = v.spectator ? ClientConfig.get().spectatorBeatVolume : ClientConfig.get().beatVolume;
			if (vol <= 0 || now - lastReplayMs < 2000) return;
			lastReplayMs = now;
			stop();
			playing = PositionedSoundInstance.ui(SoundEvent.of(Identifier.of("rapbattle", "beat." + t.id())), 1.0f, Math.min(1f, vol));
			sm.play(playing);
			return;
		}
		try {
			var system = ((dev.rapbattle.client.mixin.SoundManagerAccessor) sm).rapbattle$soundSystem();
			var src = ((dev.rapbattle.client.mixin.SoundSystemAccessor) system).rapbattle$sources().get(playing);
			if (src == null) return;
			long asked = System.nanoTime();
			src.run(source -> {
				int ptr = ((dev.rapbattle.client.mixin.SourceAccessor) source).rapbattle$pointer();
				if (org.lwjgl.openal.AL10.alGetSourcei(ptr, org.lwjgl.openal.AL10.AL_SOURCE_STATE) != org.lwjgl.openal.AL10.AL_PLAYING) return;
				float expected = (inLoop + (System.nanoTime() - asked) / 1_000_000L) / 1000f;
				float actual = org.lwjgl.openal.AL10.alGetSourcef(ptr, org.lwjgl.openal.AL11.AL_SEC_OFFSET);
				if (Math.abs(actual - expected) > 0.07f) {
					org.lwjgl.openal.AL10.alSourcef(ptr, org.lwjgl.openal.AL11.AL_SEC_OFFSET, expected);
					seeks++;
				}
			});
		} catch (RuntimeException ignored) {
			// The sound engine changed under us (device switch): the next loop starts clean anyway.
		}
	}

	public static void stop() {
		if (playing != null) MinecraftClient.getInstance().getSoundManager().stop(playing);
		playing = null;
	}
}
