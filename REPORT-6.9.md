# Report 6.9: Client Gametests — Gangs, Real Raid, First-Person Mic, PvP & Localization

Branch: `gemini/6.9-gametests` (from `gemini/6.8-gametests`).

## 1. Test Runs Executed
- `$env:RB_TEST_WAV = "C:\Users\artem\.gemini\antigravity\scratch\verse.wav"; $env:RB_TEST_LANG = "en_us"; .\gradlew.bat runClientGameTest --no-daemon` — **PASSED**
- `$env:RB_TEST_WAV = "C:\Users\artem\.gemini\antigravity\scratch\verse.wav"; $env:RB_TEST_LANG = "ru_ru"; .\gradlew.bat runClientGameTest --no-daemon` — **PASSED**

All 4 registered client gametest entrypoints (`ShowcaseTest`, `ActsGameTest`, `GangsGameTest`, `PvpGameTest`) passed in both `en_us` and `ru_ru` with zero raw `rapbattle.*` translation keys detected in chat or actionbar messages.

## 2. What Is Covered

### 2.1 Gang Wars, Champions, Rematches & Sun Safety (`GangsGameTest`)
- **Gang Rivalry (`testGangRivalry`)**:
  - Equips 2 pieces of Skeleton gang gear (`Gangs.of(MobProfiles.of(EntityType.SKELETON))`) against a `ZombieEntity` (rival kin).
  - Verifies approach callout does NOT trigger at 9 blocks without rival gear, and DOES trigger at 9 blocks (1.5× range = 10.5 blocks) with rival gear.
  - Verifies battle rank steps up by `+1` (capped at level 5).
  - Runs `TIMELINE` → `RESULT` with `RB_TEST_WAV`, asserts the judge breakdown (`ClientBattle.view.result.hits`) contains the gang rivalry line (`+1 rank (gang rivalry)` / `вражда банд`), and captures `0017_gangs-rival-breakdown.png`.
- **Champion Sun Immunity & Cooldown (`testChampionSunAndCooldown`)**:
  - Spawns a daytime `ZombieEntity` with `MobRecord.wins > 0` in open sunlight for 120 ticks (6 seconds) and asserts `isAlive() && !isOnFire()`.
  - Sets player win streak to `3` and verifies the champion calls out the player within 24 blocks (`0018_gangs-champion-callout.png`).
  - Resolves the battle and verifies via `BattleManager.advanceCooldownsForTest` that the champion does NOT re-challenge after 1 minute (`60_000 ms`), and DOES re-challenge after 5 minutes (`5 * 60_000 ms`).
- **Rematch Callout (`testRematchCallout`)**:
  - Resolves a battle as a close player win (`90` vs `75`, margin `15 < 25`, non-KO) via `forceOutcomeForTest(true, false, 90, 75)`.
  - Asserts the mob survives, enters `Crowd.inTruce`, and remembers the player (`MobRecord.rival(...) != null && !rv.mobWon()`).
  - Clears truce via `Crowd.expireTruceForTest()`, keeps player within 16 blocks, and asserts the mob calls out for a rematch (`0019_gangs-rematch-callout.png`).
- **Daytime Stage Sun Safety (`testDaytimeSunSafety`)**:
  - Starts a daytime battle with a `ZombieEntity` and nearby `SkeletonEntity` spectator (`b.startEarly(p)`), waits 100 ticks (5 seconds), and asserts both remain alive and not on fire.

### 2.2 Act 1 First-Person Microphone & Act 2 Real Raid Waves (`ActsGameTest`)
- **First-Person Mic (`testAct1NightHuntAndHomemadeMic`)**:
  - Switches to `Perspective.FIRST_PERSON` during the player's `TIMELINE` turn with an empty main hand and homemade mic in offhand, capturing `0011_act1-first-person-homemade-mic.png` (showing fist, homemade mic, and beat flash on the rhyme table).
  - Swaps offhand to an `ender_dragon` trophy mic for visual color comparison (`0012_act1-first-person-trophy-mic.png`).
- **Real Multi-Wave Raid (`testRealRaidWaves`)**:
  - Builds a minimal village (bell, 2 complete beds, 2 villagers) on `Difficulty.EASY` (3 waves) and triggers a real `Raid` via `RAID_OMEN` / `startRaid`.
  - Battles a regular wave-1 raider and asserts `HERO_OF_THE_VILLAGE` is NOT granted.
  - Clears waves 1 and 2 to reach the final wave (wave 3), verifies the final-wave captain auto-challenges the player, and asserts winning grants `HERO_OF_THE_VILLAGE` and drops `TOTEM_OF_UNDYING`.

### 2.3 PvP (`PvpGameTest`)
- Uses Fabric `FakePlayer` (`"RivalMC"`) as the second `ServerPlayerEntity` participant.
- Verifies `PvpBattle.challenge(p1, p2)`, `PvpBattle.answer(p2, inviteId, true)`, `PvpBattle.byPlayer(...) != null`, offhand trophy perk (`TrophyPerk.ENDER_DRAGON`), opponent gang outfit level (`Outfit.of(p2, "en_us").level() >= 3`), both players' outfits present in `PvpBattle.snapshotForTest(p1)`, and clean battle termination without exceptions.

### 2.4 Localization & Raw-Key Audit (`GameTestSupport`)
- Added `RB_TEST_LANG` (`en_us` / `ru_ru`) client language switching + resource reload in `GameTestSupport.applyLang(ctx)`.
- Added `ClientReceiveMessageEvents.GAME` listener in `GameTestSupport.installKeyAudit()` checking all chat and actionbar messages against `\brapbattle\.[a-z0-9_.]+`.
- Both `en_us` and `ru_ru` runs completed with zero raw translation keys.

## 3. Screenshots to Inspect
- `0011_act1-first-person-homemade-mic.png` — first-person fist + homemade mic + beat flash on rhyme table
- `0012_act1-first-person-trophy-mic.png` — first-person trophy mic comparison
- `0014_act3-bastion-guest.png` — `Bastion Guest` in `en_us`
- `0016_act5-dragon-hover.png` — `End Dragon` boss VS screen in `en_us`
- `0017_gangs-rival-breakdown.png` — breakdown showing `+1 rank (gang rivalry)` / `вражда банд`
- `0018_gangs-champion-callout.png` — champion callout bubble + prompt
- `0019_gangs-rematch-callout.png` — rematch callout bubble + prompt
- `0000_01-hero-wide.png` .. `0009_11-settings.png` — `ShowcaseTest` 10-frame suite

## 4. Mechanic Bugs Found & Fixed
1. **Champion sun immunity tick interval (`BattleManager.java`, commit `b520664`)**:
   - `BattleManager.tickChampions` only extinguished `MobRecord.wins > 0` mobs every 5 ticks (`server.getTicks() % 5 == 0`), allowing daytime undead champions (e.g. zombies) to catch fire on the 4 intermediate ticks and flicker/take burn damage.
   - **Fix**: Extinguish burning champions on every server tick (matching `Battle.tick` and `Crowd.tickTruce`).

## 5. Not Covered
- **Two-client live PvP rounds**: `FakePlayer` is not registered in `ServerPlayerManager.getPlayerList()`, so `PvpBattle.tick()` immediately treats it as disconnected on the tick after battle start. Multi-turn PvP audio/judging between two real connected clients is not covered by single-client gametest.
- **Test-only helpers**: `Battle.forceOutcomeForTest(...)`, `BattleManager.advanceCooldownsForTest`, `BattleManager.hasPendingForTest`, `BattleManager.clearPendingForTest`, `BattleManager.acceptPendingForTest`, `Crowd.expireTruceForTest`, and `PvpBattle.forceEndForTest` / `pendingInviteIdForTest` / `snapshotForTest` are used for deterministic gametest control.
