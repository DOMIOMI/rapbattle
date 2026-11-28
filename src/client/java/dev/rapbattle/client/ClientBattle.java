package dev.rapbattle.client;

import dev.rapbattle.client.mixin.MobEntityAccessor;
import dev.rapbattle.net.BattleView;
import dev.rapbattle.net.Payloads;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.resource.language.I18n;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.entity.Entity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.Arrays;

/** Client-side copy of the current battle: clock, beat machine, mic recording, invites, the rap/fight choice. */
public final class ClientBattle {
	private ClientBattle() {}

	/** I am in a battle that is on (not the lobby, not watching from the side): the battle HUD replaces the hotbar. */
	public static boolean onStage() {
		BattleView v = view;
		if (v == null || v.spectator) return false;
		return switch (v.phase) {
			case "TIMELINE", "JUDGING", "ROUND_RESULT", "RESULT" -> true;
			default -> false;
		};
	}

	public static BattleView view;
	private static long receivedAt;

	public static BattleView invite;
	private static long inviteAt;

	public static BattleView choice;
	private static long choiceAt;
	private static long choiceLookAt;

	private static final MicRecorder MIC = new MicRecorder();
	private static int recordingFor = -1;
	/** battle id * 100 + round we already recorded. */
	private static int recordedKey = -1;
	private static int timelineKey = -1;
	private static long lastHalfBeat = Long.MIN_VALUE;
	// Per-row loudness for the crowd / creeper fuse.
	private static int trackedRow = -1;
	private static float rowPeak;
	// Warden: the mic listens (without recording) while he reads.
	private static boolean monitoring;
	private static int wardenMicKey = -1;
	private static int noiseCount;
	private static boolean wasLoud;
	// Enderman: looked him in the eyes this round.
	private static int gazedKey = -1;

	/**
	 * A beat style: drums per eighth note of a bar (0 = silent, otherwise the volume in tenths), the bass line
	 * (root note per bar and the eighths it plays on), the lead riff (pitch per eighth, 0 = rest) and a swing.
	 */
	private record Style(String name, int[] kick, int[] snare, int[] hat, float[] roots, int[] bassSteps, float[] bassInt,
						 float[] riff, RegistryEntry<SoundEvent> lead, float hatPitch) {}

	private static final Style[] STYLES = {
		// Classic boom bap: kick on 1 and 3 (+ the "and" of 3), snare on 2 and 4, straight hats.
		new Style("rapbattle.client.style.boombap", new int[]{10, 0, 0, 0, 10, 7, 0, 0}, new int[]{0, 0, 8, 0, 0, 0, 8, 0}, new int[]{3, 2, 3, 2, 3, 2, 3, 2},
			new float[]{0.707f, 0.595f, 0.794f, 0.53f}, new int[]{0, 4}, new float[]{1f, 1.189f},
			new float[]{0, 1.0f, 0, 1.189f, 0, 1.335f, 1.189f, 1.0f}, null, 1.8f),
		// Trap: half-time snare on 3, sparse 808 kick, busy hats, long low bass.
		new Style("rapbattle.client.style.trap", new int[]{10, 0, 0, 7, 0, 0, 9, 0}, new int[]{0, 0, 0, 0, 9, 0, 0, 0}, new int[]{3, 3, 3, 3, 3, 3, 4, 4},
			new float[]{0.5f, 0.5f, 0.595f, 0.445f}, new int[]{0, 3, 6}, new float[]{1f, 1f, 0.891f},
			new float[]{1.498f, 0, 0, 1.335f, 0, 1.189f, 0, 0}, null, 2.0f),
		// Drill: sliding syncopated kick, snare on 3, choppy hats, dark minor riff.
		new Style("rapbattle.client.style.drill", new int[]{10, 0, 0, 8, 0, 0, 7, 6}, new int[]{0, 0, 0, 0, 9, 0, 0, 0}, new int[]{3, 0, 3, 3, 0, 3, 3, 0},
			new float[]{0.53f, 0.5f, 0.472f, 0.5f}, new int[]{0, 3, 7}, new float[]{1f, 1.059f, 0.944f},
			new float[]{1.0f, 1.059f, 0, 1.0f, 0.944f, 0, 1.335f, 0}, null, 1.9f),
		// Old-school funk: kick on 1 and the "and" of 2, open groove, walking bass, bright lead.
		new Style("rapbattle.client.style.oldschool", new int[]{10, 0, 0, 7, 0, 0, 8, 0}, new int[]{0, 0, 8, 0, 0, 0, 8, 2}, new int[]{0, 3, 0, 3, 0, 3, 0, 3},
			new float[]{0.794f, 0.707f, 0.891f, 0.667f}, new int[]{0, 2, 4, 6}, new float[]{1f, 1.26f, 1.498f, 1.26f},
			new float[]{1.335f, 0, 1.498f, 0, 1.682f, 0, 1.498f, 1.335f}, SoundEvents.BLOCK_NOTE_BLOCK_BIT, 1.7f),
		// Phonk: cowbell melody, punchy kick on the beats, snare on 2 and 4.
		new Style("rapbattle.client.style.phonk", new int[]{10, 0, 8, 0, 10, 0, 8, 0}, new int[]{0, 0, 9, 0, 0, 0, 9, 0}, new int[]{0, 2, 0, 2, 0, 2, 0, 2},
			new float[]{0.595f, 0.595f, 0.53f, 0.667f}, new int[]{0, 4}, new float[]{1f, 1f},
			new float[]{1.0f, 1.0f, 1.189f, 1.0f, 1.335f, 1.189f, 1.0f, 0.891f}, SoundEvents.BLOCK_NOTE_BLOCK_COW_BELL, 1.6f),
	};

	/** This round's style: picked per battle, changes from round to round. */
	static Style style() {
		BattleView v = view;
		if (v == null) return STYLES[0];
		return STYLES[Math.floorMod(v.id * 7919 + v.round * 3, STYLES.length)];
	}

	public static String styleName() {
		var t = BeatPlayer.track();
		if (t != null) {
			String code = MinecraftClient.getInstance().getLanguageManager().getLanguage();
			return t.name(dev.rapbattle.Lang.ofCode(code));
		}
		return I18n.translate(style().name());
	}

	/** The note-block instrument that leads this mob's beat (bone xylophone for skeletons, guitar for sheep...). */
	private static RegistryEntry<SoundEvent> lead(String mobType) {
		String id = mobType.contains(":") ? mobType.substring(mobType.indexOf(':') + 1) : mobType;
		return switch (id) {
			case "skeleton", "stray", "bogged", "parched", "skeleton_horse" -> SoundEvents.BLOCK_NOTE_BLOCK_XYLOPHONE;
			case "sheep", "llama", "trader_llama" -> SoundEvents.BLOCK_NOTE_BLOCK_GUITAR;
			case "cow", "mooshroom" -> SoundEvents.BLOCK_NOTE_BLOCK_COW_BELL;
			case "horse", "donkey", "mule", "pig", "camel" -> SoundEvents.BLOCK_NOTE_BLOCK_BANJO;
			case "villager", "wandering_trader", "zombie_villager" -> SoundEvents.BLOCK_NOTE_BLOCK_BIT;
			case "piglin", "piglin_brute", "zombified_piglin", "hoglin" -> SoundEvents.BLOCK_NOTE_BLOCK_BELL;
			case "iron_golem", "copper_golem" -> SoundEvents.BLOCK_NOTE_BLOCK_IRON_XYLOPHONE;
			case "witch", "allay", "vex", "evoker", "illusioner" -> SoundEvents.BLOCK_NOTE_BLOCK_FLUTE;
			case "elder_guardian", "guardian", "snow_golem", "polar_bear", "glow_squid", "squid", "dolphin" -> SoundEvents.BLOCK_NOTE_BLOCK_CHIME;
			case "warden", "ravager", "sniffer" -> SoundEvents.BLOCK_NOTE_BLOCK_DIDGERIDOO;
			case "blaze", "ghast", "happy_ghast", "magma_cube", "strider", "breeze" -> SoundEvents.BLOCK_NOTE_BLOCK_PLING;
			default -> SoundEvents.BLOCK_NOTE_BLOCK_HARP;
		};
	}

	/** Mob-head imitation sound on the first beat of each bar, for the mobs that have one. */
	private static RegistryEntry<SoundEvent> accent(String mobType) {
		String id = mobType.contains(":") ? mobType.substring(mobType.indexOf(':') + 1) : mobType;
		return switch (id) {
			case "zombie", "husk", "drowned", "zombie_villager", "giant" -> SoundEvents.BLOCK_NOTE_BLOCK_IMITATE_ZOMBIE;
			case "skeleton", "stray", "bogged", "parched" -> SoundEvents.BLOCK_NOTE_BLOCK_IMITATE_SKELETON;
			case "creeper" -> SoundEvents.BLOCK_NOTE_BLOCK_IMITATE_CREEPER;
			case "ender_dragon" -> SoundEvents.BLOCK_NOTE_BLOCK_IMITATE_ENDER_DRAGON;
			case "wither", "wither_skeleton" -> SoundEvents.BLOCK_NOTE_BLOCK_IMITATE_WITHER_SKELETON;
			case "piglin", "piglin_brute", "zombified_piglin" -> SoundEvents.BLOCK_NOTE_BLOCK_IMITATE_PIGLIN;
			default -> null;
		};
	}

	// ------------------------------------------------------------------ packets

	public static void onPacket(String json) {
		BattleView v = BattleView.fromJson(json);
		switch (v.type) {
			case "invite" -> {
				invite = v;
				inviteAt = System.currentTimeMillis();
				ui(SoundEvents.BLOCK_NOTE_BLOCK_PLING, 1.2f, 0.8f);
			}
			case "keys" -> KeysScreen.serverStatus(v.keysLlm, v.keysStt);
			case "style_status" -> StyleScreen.serverStatus(v.playerRespect, v.playerStyle, v.playerGateSummary);
			case "invite_close" -> {
				if (invite != null && invite.id == v.id) invite = null;
			}
			case "choice" -> {
				choice = v;
				long t = System.currentTimeMillis();
				choiceAt = t;
				choiceLookAt = t;
				ui(v.allowed ? SoundEvents.BLOCK_NOTE_BLOCK_BELL : SoundEvents.BLOCK_NOTE_BLOCK_BASS, v.allowed ? 1.4f : 0.6f, 0.7f);
			}
			case "choice_close" -> choice = null;
			case "end" -> {
				if (view != null && view.id == v.id) reset();
			}
			default -> {
				long now = System.currentTimeMillis();
				boolean sameTimeline = view != null && view.id == v.id && view.round == v.round && "TIMELINE".equals(view.phase)
					&& "TIMELINE".equals(v.phase);
				long expected = sameTimeline ? clockOf(view, receivedAt, now) : Long.MIN_VALUE;
				view = v;
				receivedAt = now;
				// Re-sync the clock only when it drifted; avoids jitter from network latency.
				if (expected != Long.MIN_VALUE && Math.abs(expected - v.beatElapsedMs) < 120) receivedAt = now - (expected - v.beatElapsedMs);
				int key = v.id * 100 + v.round;
				if ("TIMELINE".equals(v.phase) && key != timelineKey) {
					timelineKey = key;
					lastHalfBeat = Long.MIN_VALUE;
				}
				if (invite != null && invite.id == v.id) invite = null;
				choice = null;
				Intro.onState(v);
			}
		}
	}

	private static void reset() {
		if (MIC.isRunning()) MIC.stop();
		BeatPlayer.stop();
		recordingFor = -1;
		recordedKey = -1;
		timelineKey = -1;
		lastHalfBeat = Long.MIN_VALUE;
		trackedRow = -1;
		rowPeak = 0;
		rhymePeak = 0;
		nearRhymePeak = 0;
		rhymePeakDeltaMs = -1;
		nearPeakDeltaMs = -1;
		smoothMic = 0;
		silentSinceMs = 0;
		mobPunchAt = 0;
		mobFlinchAt = 0;
		playerJabAt = 0;
		scratchAt = 0;
		monitoring = false;
		noiseCount = 0;
		wasLoud = false;
		gazedKey = -1;
		view = null;
	}

	public static void disconnect() {
		reset();
		Intro.reset();
		invite = null;
		choice = null;
	}

	// ------------------------------------------------------------------ clock

	private static long clockOf(BattleView v, long at, long now) { return v.beatElapsedMs + (now - at); }

	/** Milliseconds since beat 0 of the round (negative before the countdown). */
	public static long clock() { return view == null ? 0 : clockOf(view, receivedAt, System.currentTimeMillis()); }

	public static int beat() { return (int) Math.floorDiv(clock(), Math.max(1, view.beatMs)); }

	/** 0..1 progress inside the current beat. */
	public static float beatFrac() {
		if (view == null) return 0;
		long bm = Math.max(1, view.beatMs);
		return Math.floorMod(clock(), bm) / (float) bm;
	}

	/** The beat grid of the current round exists (it may start slightly in the future). */
	public static boolean timeline() { return view != null && "TIMELINE".equals(view.phase) && view.totalBeats > 0; }

	public static String me() {
		MinecraftClient mc = MinecraftClient.getInstance();
		return mc.player == null ? "" : mc.player.getName().getString();
	}

	public static boolean isChallenger() { return view != null && me().equals(view.challenger); }

	public static boolean inLobbyChallenger() { return view != null && "LOBBY".equals(view.phase) && isChallenger(); }

	public static boolean creakingFrozen() {
		if (view == null || !view.creakingGaze) return false;
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.player == null || mc.world == null) return false;
		Entity e = mc.world.getEntityById(view.mobEntityId);
		if (e == null) return false;
		var eye = mc.player.getEyePos();
		var to = e.getEyePos().subtract(eye).normalize();
		return mc.player.getRotationVec(1f).dotProduct(to) >= Math.cos(Math.toRadians(15));
	}

	public static float micLevel() { return MIC.level; }

	public static boolean recording() { return MIC.isRunning(); }

	/** The row being read right now, or null. */
	public static BattleView.Row liveRow() {
		if (!timeline()) return null;
		int b = beat();
		for (BattleView.Row r : view.rows) if (b >= r.startBeat && b < r.startBeat + 4) return r;
		return null;
	}

	/** Entity id of whoever is reading now (-1 if nobody). */
	public static int readerId() {
		BattleView.Row r = liveRow();
		return r == null ? -1 : r.readerEntityId;
	}

	/** Mobs and players taking part, for the dance animation. */
	public static boolean inBattle(int entityId) {
		if (view == null || !timeline()) return false;
		if (view.mobEntityIds.contains(entityId)) return true;
		for (BattleView.Member m : view.team) if (m.entityId == entityId) return true;
		return false;
	}

	// ------------------------------------------------------------------ per frame / tick

	private static boolean isLookingAtChoiceMob() {
		if (choice == null || choice.mobEntityId < 0) return false;
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.player == null || mc.world == null) return false;
		Entity e = mc.world.getEntityById(choice.mobEntityId);
		if (e == null || !e.isAlive() || e.squaredDistanceTo(mc.player) > 10.0 * 10.0) return false;
		var eye = mc.player.getEyePos();
		var to = e.getBoundingBox().getCenter().subtract(eye);
		double len = to.length();
		if (len < 1e-4) return true;
		return mc.player.getRotationVec(1f).dotProduct(to.multiply(1.0 / len)) >= 0.72;
	}

	/** Called every frame and every tick; idempotent within a half-beat. */
	public static void pump() {
		BeatPlayer.update();
		long now = System.currentTimeMillis();
		if (invite != null && now - inviteAt > 30000) invite = null;
		if (choice != null) {
			if (isLookingAtChoiceMob()) choiceLookAt = now;
			if (now - choiceAt > Math.max(1000, choice.choiceMs) && now - choiceLookAt > 2000) choice = null;
		}
		if (view == null) {
			ClipRecorder.flushOffer();
			return;
		}
		if (!timeline()) {
			if (!view.spectator && !"TIMELINE".equals(view.phase)) flushTrackedRow();
			if (recordingFor == view.id && !"TIMELINE".equals(view.phase)) stopAndUpload();
			return;
		}
		long clock = clock();
		long half = Math.max(1, view.beatMs / 2);
		long h = Math.floorDiv(clock, half);
		if (h != lastHalfBeat) {
			if (lastHalfBeat == Long.MIN_VALUE || h - lastHalfBeat > 4) lastHalfBeat = h - 1; // don't replay a burst after lag
			for (long x = lastHalfBeat + 1; x <= h; x++) onHalfBeat(x);
			lastHalfBeat = h;
		}
		if (!view.spectator) {
			handleWarden(clock);
			handleGaze(clock);
			handleRecording(clock);
			trackRows();
		}
	}

	private static void onHalfBeat(long h) {
		if (h < 0) return;
		int beat = (int) (h / 2);
		boolean off = (h & 1) == 1;
		if (beat >= view.totalBeats) return;
		float vol = view.spectator ? ClientConfig.get().spectatorBeatVolume : ClientConfig.get().beatVolume;
		if (vol <= 0) return;
		if (beat < 4) { // countdown
			if (!off) ui(SoundEvents.BLOCK_NOTE_BLOCK_PLING, beat == 3 ? 2.0f : 1.0f, 0.7f * vol);
			return;
		}
		int inBar = beat % 4;
		int bar = beat / 4;
		Style st = style();
		boolean realBeat = BeatPlayer.track() != null;
		int step = inBar * 2 + (off ? 1 : 0);
		float root = st.roots()[bar % st.roots().length];
		if (!realBeat && st.hat()[step] > 0) ui(SoundEvents.BLOCK_NOTE_BLOCK_HAT, off ? st.hatPitch() - 0.3f : st.hatPitch(), st.hat()[step] / 10f * vol);
		if (!realBeat && st.kick()[step] > 0) ui(SoundEvents.BLOCK_NOTE_BLOCK_BASEDRUM, 0.8f, st.kick()[step] / 10f * vol);
		if (!realBeat && st.snare()[step] > 0) ui(SoundEvents.BLOCK_NOTE_BLOCK_SNARE, 1.0f, st.snare()[step] / 10f * vol);
		for (int i = 0; i < (realBeat ? 0 : st.bassSteps().length); i++) {
			if (st.bassSteps()[i] == step) ui(SoundEvents.BLOCK_NOTE_BLOCK_BASS, root * st.bassInt()[i], (i == 0 ? 1f : 0.75f) * vol);
		}
		if (!off) {
			BattleView.Row r = rowAt(beat);
			if (r != null) {
				// The mob "reads" its line in its own voice; a note pops over whoever is reading.
				noteOver(r.readerEntityId, bar * 4 + inBar);
			}
		}
		// The lead (the mob's own instrument, or the style's) carries the riff over the drums.
		float riff = st.riff()[step];
		if (!realBeat && riff > 0) ui(st.lead() != null ? st.lead() : lead(view.mobType), root * 2 * riff / 1.414f, 0.42f * vol);
		RegistryEntry<SoundEvent> acc = accent(view.mobType);
		if (acc != null && !off && inBar == 0 && bar % 2 == 0) ui(acc, 1.0f, 0.35f * vol);
	}

	private static BattleView.Row rowAt(int beat) {
		for (BattleView.Row r : view.rows) if (beat >= r.startBeat && beat < r.startBeat + 4) return r;
		return null;
	}

	private static void noteOver(int entityId, int pitch) {
		if (!ClientConfig.get().animations) return;
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.world == null) return;
		Entity e = mc.world.getEntityById(entityId);
		if (e == null) return;
		mc.world.addParticleClient(ParticleTypes.NOTE, e.getX(), e.getY() + e.getHeight() + 0.4, e.getZ(), (pitch % 24) / 24.0, 0, 0);
	}

	private static void mobVoice(int entityId, float vol) {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.world == null) return;
		Entity e = mc.world.getEntityById(entityId);
		if (e instanceof MobEntity mob) {
			SoundEvent s = ((MobEntityAccessor) mob).rapbattle$getAmbientSound();
			if (s != null) mc.getSoundManager().play(PositionedSoundInstance.ui(s, 0.85f + mc.world.random.nextFloat() * 0.4f, 0.9f * vol));
		}
	}

	private static void ui(RegistryEntry<SoundEvent> s, float pitch, float vol) {
		MinecraftClient.getInstance().getSoundManager().play(PositionedSoundInstance.ui(s.value(), pitch, vol));
	}

	// ------------------------------------------------------------------ mob tricks

	/** Warden hears every sound while he reads: count loud bursts, send the count when his verse ends. */
	private static void handleWarden(long clock) {
		if (!view.silence) return;
		int b = (int) Math.floorDiv(clock, Math.max(1, view.beatMs));
		boolean mobTurn = b >= view.mobStartBeat && b < view.mobEndBeat;
		int key = view.id * 100 + view.round;
		if (mobTurn && !monitoring && !MIC.isRunning() && wardenMicKey != key) {
			wardenMicKey = key;
			if (MIC.start() == null) {
				monitoring = true;
				noiseCount = 0;
				wasLoud = false;
			}
		}
		if (monitoring) {
			boolean loud = MIC.level > 0.3f;
			if (loud && !wasLoud) noiseCount++;
			wasLoud = loud;
			if (!mobTurn) {
				MIC.stop(); // nothing is uploaded: only the count leaves the computer
				monitoring = false;
				ClientPlayNetworking.send(new Payloads.ActionC2S("{\"type\":\"noise\",\"n\":" + noiseCount + "}"));
				if (noiseCount > 0) say(Text.translatable("rapbattle.client.warden_heard", noiseCount), Formatting.DARK_AQUA);
			}
		}
	}

	/** Enderman: looking him in the face during his verse shuffles your upcoming rhymes. */
	private static void handleGaze(long clock) {
		if (!view.gaze) return;
		int key = view.id * 100 + view.round;
		if (gazedKey == key) return;
		int b = (int) Math.floorDiv(clock, Math.max(1, view.beatMs));
		if (b < view.mobStartBeat || b >= view.mobEndBeat) return;
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.player == null || mc.world == null) return;
		Entity e = mc.world.getEntityById(view.mobEntityId);
		if (e == null) return;
		var eye = mc.player.getEyePos();
		var to = e.getEyePos().subtract(eye).normalize();
		var look = mc.player.getRotationVec(1f);
		if (look.dotProduct(to) < Math.cos(Math.toRadians(8))) return;
		gazedKey = key;
		ClientPlayNetworking.send(new Payloads.ActionC2S("{\"type\":\"gaze\"}"));
		say(Text.translatable("rapbattle.client.enderman_gaze"), Formatting.DARK_PURPLE);
		MinecraftClient.getInstance().getSoundManager().play(PositionedSoundInstance.ui(SoundEvents.ENTITY_ENDERMAN_STARE, 1f, 0.8f));
	}

	private static void flushTrackedRow() {
		if (view == null || trackedRow < 0 || trackedRow >= view.rows.size()) {
			trackedRow = -1;
			return;
		}
		BattleView.Row done = view.rows.get(trackedRow);
		if (!done.mob && me().equals(done.reader) && MIC.isRunning() && !monitoring) {
			boolean onBeatLoud = rhymePeak > 0.08f;
			boolean anyNearLoud = onBeatLoud || nearRhymePeak > 0.08f;
			int deltaMs = onBeatLoud ? rhymePeakDeltaMs : anyNearLoud ? nearPeakDeltaMs : -1;
			boolean silentBar = rowPeak < 0.02f;
			ClientPlayNetworking.send(new Payloads.ActionC2S(
				"{\"type\":\"bar\",\"loud\":" + onBeatLoud + ",\"deltaMs\":" + deltaMs + ",\"silent\":" + silentBar + "}"));
			if (view.fuse && silentBar) ClientPlayNetworking.send(new Payloads.ActionC2S("{\"type\":\"silent\"}"));
		}
		trackedRow = -1;
		rowPeak = 0;
		rhymePeak = 0;
		nearRhymePeak = 0;
		rhymePeakDeltaMs = -1;
		nearPeakDeltaMs = -1;
	}

	/** At the end of each of my rows: send whether the rhyme slot was loud, the voice peak offset from beat 4 (ms), and silence. */
	private static void trackRows() {
		BattleView.Row live = liveRow();
		int idx = live == null ? -1 : view.rows.indexOf(live);
		boolean mine = live != null && !live.mob && me().equals(live.reader);
		if (idx != trackedRow) {
			flushTrackedRow();
			trackedRow = idx;
		}
		if (mine) {
			rowPeak = Math.max(rowPeak, MIC.level);
			smoothMic += (MIC.level - smoothMic) * 0.25f;
			if (MIC.level < 0.02f) {
				if (silentSinceMs == 0) silentSinceMs = System.currentTimeMillis();
			} else {
				silentSinceMs = 0;
			}
			long heard = clock() - ClientConfig.get().latencyMs;
			long bm = Math.max(1, view.beatMs);
			long expected = (live.startBeat + 3) * bm;
			long absDelta = Math.abs(heard - expected);
			if (absDelta <= bm * 35 / 100 && MIC.level > rhymePeak) {
				rhymePeak = MIC.level;
				rhymePeakDeltaMs = (int) absDelta;
			}
			if (absDelta <= bm * 70 / 100 && MIC.level > nearRhymePeak) {
				nearRhymePeak = MIC.level;
				nearPeakDeltaMs = (int) absDelta;
			}
			if (beat() - live.startBeat == 3 && MIC.level > 0.08f && absDelta <= bm * 35 / 100) {
				long now = System.currentTimeMillis();
				if (now - playerJabAt > bm * 2) {
					playerJabAt = now;
					if (view.combo >= 2) mobFlinchAt = now;
				}
			}
		} else {
			smoothMic *= 0.85f;
			silentSinceMs = 0;
		}
		if (live != null && live.mob && live.punch && beat() - live.startBeat == 3 && beatFrac() < 0.25f) {
			long now = System.currentTimeMillis();
			if (now - mobPunchAt > Math.max(400, view.beatMs)) mobPunchAt = now;
		}
	}

	private static float rhymePeak, nearRhymePeak, smoothMic;
	private static int rhymePeakDeltaMs = -1, nearPeakDeltaMs = -1;
	private static long silentSinceMs, mobPunchAt, mobFlinchAt, playerJabAt, scratchAt;

	public static float smoothMicLevel() { return smoothMic; }

	public static float silentBeats() {
		if (silentSinceMs == 0 || view == null || view.beatMs <= 0) return 0f;
		return (System.currentTimeMillis() - silentSinceMs) / (float) view.beatMs;
	}

	public static float mobPunchImpulse() { return impulse(mobPunchAt, 220); }
	public static float fovImpulse() { return impulse(mobPunchAt, 120); }
	public static float mobFlinchImpulse() { return impulse(mobFlinchAt, 260); }
	public static float playerJabImpulse() { return impulse(playerJabAt, 220); }
	public static float scratchImpulse() { return impulse(scratchAt, 300); }

	private static float impulse(long at, long durMs) {
		if (at <= 0) return 0f;
		long dt = System.currentTimeMillis() - at;
		if (dt < 0 || dt > durMs) return 0f;
		float t = dt / (float) durMs;
		return (float) Math.sin(t * Math.PI);
	}

	private static void say(Text text, Formatting color) {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.player != null) mc.player.sendMessage(text.copy().formatted(color), true);
	}

	// ------------------------------------------------------------------ mic

	/** My recording window in this round, if I read in it. */
	public static BattleView.Segment mySegment() {
		if (view == null || view.spectator) return null;
		String me = me();
		for (BattleView.Segment s : view.segments) if (me.equals(s.reader)) return s;
		return null;
	}

	private static void handleRecording(long clock) {
		BattleView.Segment s = mySegment();
		if (s == null) return;
		long bm = view.beatMs;
		long start = s.leadInBeat * bm + bm;        // mic warms up during the get-ready bar
		long stop = s.endBeat * bm + bm / 2;        // short tail for the last word
		int key = view.id * 100 + view.round;
		if (recordedKey != key && clock >= start && clock < stop) {
			if (monitoring) {
				MIC.stop();
				monitoring = false;
			}
			recordingFor = view.id;
			recordedKey = key;
			String err = MIC.start();
			recordingStartClock = clock();
			if (err != null) {
				MinecraftClient mc = MinecraftClient.getInstance();
				if (mc.player != null) mc.player.sendMessage(Text.translatable("rapbattle.client.mic_error", err).formatted(Formatting.RED), false);
			}
		}
		if (recordingFor == view.id && clock >= stop) stopAndUpload();
	}

	/** Battle clock when the mic actually started; with the latency it maps the recording onto the beats. */
	private static long recordingStartClock;

	private static void stopAndUpload() {
		int startMs = (int) (recordingStartClock - ClientConfig.get().latencyMs);
		int id = recordingFor;
		BattleView.Segment seg = view == null ? null : mySegment();
		recordingFor = -1;
		byte[] wav = MIC.isRunning() ? MIC.stop() : new byte[0];
		// Offer the verse as a clip: voice over the beat, from the get-ready bar to one beat after the last line.
		if (seg != null && view != null) {
			long bm = view.beatMs;
			ClipRecorder.keep(wav, startMs, seg.leadInBeat * bm, (seg.endBeat + 1) * bm, view.beatTrack, bm,
				view.pvp ? (me().equals(view.mobName) ? view.challenger : view.mobName) : view.mobName);
		}
		int max = Payloads.AudioC2S.MAX_CHUNK;
		int chunks = Math.max(1, (wav.length + max - 1) / max);
		for (int i = 0; i < chunks; i++) {
			int from = i * max, to = Math.min(wav.length, from + max);
			byte[] part = from < to ? Arrays.copyOfRange(wav, from, to) : new byte[0];
			ClientPlayNetworking.send(new Payloads.AudioC2S(id, i, i == chunks - 1, startMs, part));
		}
	}

	// ------------------------------------------------------------------ keys

	/** Y: accept an invite, or "go" if I'm the challenger waiting in the lobby. */
	public static void pressAccept() {
		if (invite != null) {
			ClientPlayNetworking.send(new Payloads.ActionC2S("{\"type\":\"invite\",\"id\":" + invite.id + ",\"accept\":true}"));
			if (!invite.allowed) return; // server answers with the reason; keep the banner
			invite = null;
			return;
		}
		if (view != null && "LOBBY".equals(view.phase) && isChallenger()) {
			ClientPlayNetworking.send(new Payloads.ActionC2S("{\"type\":\"go\"}"));
		}
	}

	/** Space: skips the intro, starts the lobby now (challenger), or closes the judge's result screen. */
	public static void pressSkip() {
		if (Intro.active()) {
			Intro.skip();
			return;
		}
		if (view == null || view.spectator) return;
		if ("LOBBY".equals(view.phase) && isChallenger()) {
			ClientPlayNetworking.send(new Payloads.ActionC2S("{\"type\":\"go\"}"));
		} else if ("RESULT".equals(view.phase) || "ROUND_RESULT".equals(view.phase)) {
			ClientPlayNetworking.send(new Payloads.ActionC2S("{\"type\":\"skip_result\"}"));
		}
	}

	public static void pressDecline() {
		if (invite != null) {
			ClientPlayNetworking.send(new Payloads.ActionC2S("{\"type\":\"invite\",\"id\":" + invite.id + ",\"accept\":false}"));
			invite = null;
			return;
		}
		if (choice != null) {
			ClientPlayNetworking.send(new Payloads.ActionC2S("{\"type\":\"cancel\"}"));
			choice = null;
		}
	}

	/** R: rap battle when choice is open, or once-per-round rhyme reroll during your verse. */
	public static void pressRap() {
		if (choice != null && choice.allowed) {
			ClientPlayNetworking.send(new Payloads.ActionC2S("{\"type\":\"rap\"}"));
			choice = null;
			return;
		}
		if (view != null && !view.spectator && "TIMELINE".equals(view.phase) && view.canReroll && !view.pvp) {
			scratchAt = System.currentTimeMillis();
			ClientPlayNetworking.send(new Payloads.ActionC2S("{\"type\":\"reroll\"}"));
		}
	}

	public static void chooseTopic(int topic) {
		if (!inLobbyChallenger() || topic < 1 || topic > 3) return;
		ClientPlayNetworking.send(new Payloads.ActionC2S("{\"type\":\"topic\",\"topic\":" + topic + "}"));
	}
}
