package dev.rapbattle.net;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.util.ArrayList;
import java.util.List;

/**
 * Everything the client needs to draw a battle. Sent as JSON.
 *
 * Timing: for each round the server fixes a beat grid when the countdown starts (beat 0). The client keeps
 * its own clock from {@link #beatElapsedMs} and derives the current row, beat dot, countdown, and its own
 * recording window from the rows/segments below, so it stays in sync without a packet per beat.
 */
public class BattleView {
	public static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

	/** "state", "invite", "invite_close", "choice", "end". */
	public String type = "state";
	public int id;
	/** LOBBY, PREP, TIMELINE, JUDGING, ROUND_RESULT, RESULT. */
	public String phase = "LOBBY";
	/** This copy goes to a bystander: beat and result only, no table, no mic. */
	public boolean spectator;
	public long beatMs = 667;
	/** Milliseconds since beat 0 of the current round when the packet was sent (negative before it starts). */
	public long beatElapsedMs;
	public long lobbyMsLeft;

	public int round = 1;
	public int rounds = 1;
	/** How many beats ahead a player's rhyme word shows up (0 = the whole verse is visible). */
	public int revealBeats;
	public int roundWinsPlayers;
	public int roundWinsMob;

	public List<Member> team = new ArrayList<>();
	public String challenger = "";
	public boolean boss;

	public String mobName = "";
	public String mobSpecies = "";
	public String mobLanguage = "";
	public String mobMood = "";
	public String mobType = "";
	public int mobEntityId = -1;
	public List<Integer> mobEntityIds = new ArrayList<>();
	public List<String> mobTeam = new ArrayList<>();
	public String rankArmor = "";
	public String rankStreet = "";
	public int rankLevel = 1;
	public int rankColor = 0xFFFFFF;
	public List<String> tags = new ArrayList<>();
	public int mobWins;
	public int mobLosses;
	public String requiredOutfit = "";
	/** Enderman: looking him in the eyes during his verse shuffles your rhymes. */
	public boolean gaze;
	/** Creeper: a whole silent bar on your turn lights the fuse. */
	public boolean fuse;
	/** Warden: he hears every noise you make while he reads. */
	public boolean silence;
	/** Creaking: looking directly at him freezes his interference. */
	public boolean creakingGaze;
	/** Anchor words from the mob's verse that players can flip for rebuttals. */
	public List<String> targets = new ArrayList<>();
	/** Chosen lobby topic: 0 = random, 1 = outfit/flex, 2 = turf/biome, 3 = no brakes. */
	public int topic;
	/** Whether the receiving player still has their once-per-round rhyme reroll. */
	public boolean canReroll = true;
	/** One-line hint about this mob's trick, shown in the lobby. */
	public String trick = "";
	public int mobStreak;
	/** The mob crew's colour for its snapback, -1 if it has no crew. */
	public int gangColor = -1;
	/** Live Respect bars (the battle's health) and the team's combo streak. */
	public int respectTeam, respectMob, respectMax, combo;
	/** The real beat of this battle (see BeatTracks), or null for the note-block beat. */
	public String beatTrack;
	/** Server allowSwearing flag, used by client HUD/broadcast to select clean vs uncensored keys. */
	public boolean allowSwearing = true;
	/** Type "keys": are the server's keys set (never the keys themselves). */
	public boolean keysLlm, keysStt;
	/** Type "style": player's cumulative Respect and active MC style id. */
	public int playerRespect;
	public String playerStyle = "";
	/** Player against player: team[0] is the challenger (left, blue), mobName is the other player (right, red). */
	public boolean pvp;
	/** Stage centre and crowd ring radius (0 = no stage yet): for the haze and the spotlights. */
	public double stageX, stageY, stageZ, stageRadius;
	/** Entity ids of the crowd around the stage (they groove to the beat on the clients). */
	public List<Integer> crowdIds = new ArrayList<>();
	/** Crowd mood: -100 all for the mob … 100 all for the players. */
	public int hype;
	/** This species against the players of this world: [mob wins, player wins]. */
	public int speciesMobWins, speciesPlayerWins;
	/** Choice prompt: who calls you out (e.g. the village golem) and how long you have. */
	public String challengeFrom = "";
	public long choiceMs = 4000;

	public List<Row> rows = new ArrayList<>();
	public List<Segment> segments = new ArrayList<>();
	/** First beat of the mob's verse; beats 0..3 are the 4-3-2-1 countdown. */
	public int mobStartBeat = 4;
	public int mobEndBeat = 4;
	public int totalBeats;

	public Result result;

	// --- "choice" / "invite" extras
	/** Challenger passes the dress code (choice) / this invitee may join (invite). */
	public boolean allowed = true;
	public String refusal = "";
	public String refusalReason = "";
	public String yourOutfit = "";
	public boolean needsMic;
	public int requiredArmorLevel;
	public int yourArmorLevel;
	/** Soft progression gate warning on the choice card (bonuses closed). */
	public String gateWarning = "";
	/** Formatted progression access summary for StyleScreen. */
	public String playerGateSummary = "";

	public static class Member {
		public String name;
		public String outfit;
		public int outfitLevel;
		public int entityId;
		public int wins, losses, streak, mvp, best;
		public String title = "";
		public String style = "";

		public Member() {}

		public Member(String name, String outfit, int outfitLevel, int entityId) {
			this.name = name;
			this.outfit = outfit;
			this.outfitLevel = outfitLevel;
			this.entityId = entityId;
		}
	}

	public static class Row {
		/** The three dark columns: words of the mob's line, or empty for players. */
		public String[] cols = new String[3];
		public String word = "";
		public int color;
		public boolean mob;
		/** Mob team member for mob rows, player name for player rows. */
		public String reader;
		public int readerEntityId = -1;
		public String fullLine = "";
		/** The language model marked this mob line as a punchline (the crowd will react to it later). */
		public boolean punch;
		/** Mob trick on this player row: shake, blind, hidden, stolen, burn, ink, spit, ram, illusion. */
		public String effect;
		public int line;
		public int lines;
		public int startBeat;
		/** True for the 4th bar of a block on Gold+ ranks: player invents their own rhyme. */
		public boolean freestyle;
	}

	/** One player's recording window: from the get-ready bar to the end of their last line. */
	public static class Segment {
		public String reader;
		public int leadInBeat;
		public int startBeat;
		public int endBeat;
	}

	public static class Result {
		public boolean playersWin;
		public boolean finalResult;
		/** Respect after this round, and before it (the screen counts down from before to after). */
		public int respectTeam, respectMob, respectMax, teamBefore, mobBefore;
		/** A bar hit zero. */
		public boolean knockout;
		/** Player battles: who won (players read it against their own name). */
		public String winner = "";
		/** Whole battle so far: damage the team dealt, its share of lines on the beat (%), best combo, best line. */
		public int dealt, accuracy, bestCombo;
		public String bestLine = "";
		/** The judge's breakdown, in the order it is shown. */
		public List<Hit> hits = new ArrayList<>();
		public String comment = "";
		public String mobLabel = "";
		public String teamLabel = "";
		public String mvp = "";
		public int outfitBonus;
		public boolean overdressed;
		/** Summary bars at the top of the breakdown: Text & Meaning, Beat (flow delta), Rebuttals, Bonuses, Mob breakdown. */
		public int barTextTotal, barBeatDelta, barRebuttalTotal, mobParried, rebuttalCount;
		public int bonusHype, bonusStyle, bonusAlly, bonusTotal;
		public int mobLiveDamage, mobVerseDamage, silenceDamage, wardenNoiseDamage;
		public List<PlayerScore> players = new ArrayList<>();
	}

	/** One line of the breakdown: who hit whom for how much, and why. */
	public static class Hit {
		public String who = "";
		public String note = "";
		public int damage;
		public boolean toMob;
		public boolean punch;
		public boolean system;

		public Hit() {}

		public Hit(String who, String note, int damage, boolean toMob, boolean punch) {
			this(who, note, damage, toMob, punch, false);
		}

		public Hit(String who, String note, int damage, boolean toMob, boolean punch, boolean system) {
			this.who = who;
			this.note = note;
			this.damage = damage;
			this.toMob = toMob;
			this.punch = punch;
			this.system = system;
		}
	}

	public static class PlayerScore {
		public String name;
		public int score;
		public String transcript = "";

		public PlayerScore() {}

		public PlayerScore(String name, int score, String transcript) {
			this.name = name;
			this.score = score;
			this.transcript = transcript;
		}
	}

	public String toJson() { return GSON.toJson(this); }

	public static BattleView fromJson(String json) { return GSON.fromJson(json, BattleView.class); }

	public BattleView copy() { return fromJson(toJson()); }
}
