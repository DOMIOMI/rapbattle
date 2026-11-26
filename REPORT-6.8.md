# Report 6.8: Client Gametests (Acts 1–5) & Showcase Verification

Branch: `gemini/6.8-gametests` (merged `origin/main` `ced1bb3` onto `gemini/6.7-fixes`).

## 1. Test Runs Executed
- `.\gradlew.bat build test --no-daemon` — **PASSED**
- `$env:RB_TEST_WAV = "C:\Users\artem\.gemini\antigravity\scratch\verse.wav"; .\gradlew.bat runClientGameTest --no-daemon` — **PASSED** (`ShowcaseTest` + `ActsGameTest`)

## 2. Scenarios & Assertions (`ActsGameTest`)

Registered alongside `ShowcaseTest` in `src/gametest/resources/fabric.mod.json` (`dev.rapbattle.test.ActsGameTest`).

1. **Act 1 — Night Hunt & Homemade Microphone (`testAct1NightHuntAndHomemadeMic`)**
   - Equips `LIGHTNING_ROD` tagged with `Trophies.NBT_KEY = "homemade"` in offhand at night (`time set 14500`).
   - Asserts `Trophies.equippedPerk(p) == TrophyPerk.HOMEMADE`.
   - Starts a battle against a `ZombieEntity`, sends `Payloads.AudioC2S` using `RB_TEST_WAV`, and verifies `Acts.isNightHuntDoubleLoot(...)` is `true` for a `GOLD` night battle.
   - Asserts the zombie drops `Rot Crew` gear (`Gear.hasAnyGear(p)` or dropped `ItemEntity` with `Gear` NBT) after winning.
   - Screenshot: `0010_act1-homemade-mic.png` (night battle HUD with Homemade Mic in offhand).

2. **Act 2 — Villager Friendly Battle & Raid Rules (`testAct2VillagerAndRaidRules`)**
   - Verifies `Acts.isFinalWaveCaptainMatch(true, true, 1, 3, true) == false` and `Acts.isFinalWaveCaptainMatch(true, true, 3, 3, false) == false`.
   - Starts and wins a battle against a `VillagerEntity`.
   - Asserts the villager stays alive (`v.isAlive() && !v.isRemoved()`) and grants positive gossip (`MAJOR_POSITIVE` / `MINOR_POSITIVE > 0`).
   - Screenshot: `0011_act2-villager-alive.png` (villager alive after win, chat confirmation of trade discounts).

3. **Act 3 — Bastion Guest Immunity (`testAct3BastionGuest`)**
   - Teleports the player to `World.NETHER`, places `minecraft:bastion_remnant` at `(0, 70, 0)`, and sets `BastionBattle.setTestBastionBox(...)` for the placed bastion bounds.
   - Spawns a `PiglinBruteEntity` inside the bastion, starts and wins the battle.
   - Asserts `BastionBattle.inBastion(nw, brute.getBlockPos())` is `true` and `PlayerStatus.has(p.getUuid(), PlayerStatus.BASTION_GUEST)` is active.
   - Screenshot: `0012_act3-bastion-guest.png` (`Bastion Guest 9:59` on actionbar, `Golden Microphone` trophy, chat message).

4. **Act 4 — Warden Quiet / Silence (`testAct4WardenQuiet`)**
   - Places a `SCULK_SHRIEKER` (`CAN_SUMMON = true`), grants `PlayerStatus.WARDEN_QUIET` for 15 minutes with radius 48.
   - Asserts `PlayerStatus.hasInRadius(...)` covers the shrieker, triggers `shrieker.shriek(w, p)`, and asserts no `WardenEntity` spawns.
   - Screenshot: `0013_act4-warden-quiet.png` (`Silence 14:59` active on actionbar).

5. **Act 5 — Ender Dragon Boss Battle (`testAct5Dragon`)**
   - Teleports the player (in Creative + flying) to `World.END` `(0.5, 70, 0.5)`.
   - Asserts `DragonBattle.canChallenge(p, dragon)` is `false` while End Crystals are alive, destroys crystals, then asserts `canChallenge` is `true`.
   - Starts battle and asserts `dragon.getPhaseManager().getCurrent().getType() == PhaseType.HOVER`.
   - Resolves battle as player win and asserts the dragon enters vanilla death (`!isAlive() || ticksSinceDeath > 0 || PhaseType.DYING`) and `Stats.of(server).legend` is non-empty.
   - Screenshot: `0014_act5-dragon-hover.png` (Boss VS intro screen against `End Dragon`).

## 3. `ShowcaseTest` Verification
- `ShowcaseTest` passed without regressions and produced all 10 expected frames (`0000_01-hero-wide.png` through `0009_11-settings.png`) in `build/run/clientGameTest/screenshots/`.

## 4. Test Helpers & Issues Resolved During Bring-Up
- Added `Battle.forceOutcomeForTest(boolean playersWin, boolean knockout)` in `src/main/java/dev/rapbattle/battle/Battle.java` for deterministic battle resolution in Act 2/3/5 gametests.
- Added `BastionBattle.setTestBastionBox(BlockBox)` in `src/main/java/dev/rapbattle/battle/BastionBattle.java` because Fabric's default gametest world disables structure generation and `/place structure` places blocks without creating a `StructureStart`.
- Fixed dimension transitions in `ActsGameTest` to use `ServerPlayerEntity.teleport(...)` instead of server-console `tp @s` (which has no executor entity), and enabled Creative flight in `testAct5Dragon` so the player does not fall into the End void before battle resolution.

## 5. Not Covered
- Full multi-wave vanilla Raid progression in-world (wave 1/2/3 spawning and captain tracking is covered by unit checks on `Acts.isFinalWaveCaptainMatch`, not a full 3-wave live raid).
- Live Whisper/LLM API calls (gametests run in offline/fallback mode with `RB_TEST_WAV` and no `config/rapbattle-server.json`).
- Natural `StructureStart` lookup in a structure-enabled Nether world (covered via `BastionBattle.setTestBastionBox` over a placed `minecraft:bastion_remnant`).
