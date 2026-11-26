# Report 6.12: Auto-Challenge Crash Fix & Static Cache / Status Cleanup

Branch: `gemini/6.12-fixes` (from `gemini/6.11-polish`).

## 1. What Was Fixed

1. **`ClassCastException` on Auto-Challenge Exit + Freezing Pending Mobs (`BattleManager.java`, `97852d7`)**:
   - In `challengeFrom(p, mob, titleKey)`, replaced hardcoded `golem = true` with `boolean isGolem = mob instanceof IronGolemEntity`, passing `new Pending(mob, ..., true, isGolem, !isGolem)`.
   - Stopped non-golem auto-challengers immediately on callout (`mob.setTarget(null)`, `mob.getNavigation().stop()`, `stareAt(mob, p, 15_000)`) and updated the `PENDING` tick loop to keep them stationary while waiting for `[R]`.
   - Replaced unsafe `(IronGolemEntity)` casts in `restorePendingTarget` and the punch-during-`PENDING` handler with `instanceof IronGolemEntity ig`, falling back to normal hostile aggro (`mob.setTarget(p)`) for champions, rematches, raid captains, and brutes.

2. **Static Cache & Status Leaks Across Worlds (`BattleManager.java`, `PlayerStatus.java`, `BastionBattle.java`, `RaidBattle.java`, `PvpBattle.java`, `fcb0dd0`, `d9e13fe`)**:
   - Cleared `REFUSED`, `BRAWLS`, `STARES`, `CHAMPION_COOLDOWN`, `REMATCH_COOLDOWN`, `PlayerStatus.STATUSES`, `BastionBattle` state, `RaidBattle` state, and `PvpBattle` state in `ServerLifecycleEvents.SERVER_STOPPING` so nothing leaks between worlds in singleplayer.

3. **Unified Auto-Challenge Guard (`BattleManager.canAutoChallenge`, `BastionBattle.java`, `RaidBattle.java`, `fcb0dd0`)**:
   - Added `BattleManager.canAutoChallenge(ServerPlayerEntity)` checking `p != null && !p.isSpectator() && p.isAlive() && byPlayer == null && PvpBattle.byPlayer == null && !PENDING.containsKey`.
   - Applied it across `BastionBattle.tick`, `RaidBattle.tick`, and `BattleManager.tickChampions` so auto-callouts cannot interrupt an open `PENDING` prompt, `PvpBattle`, or a dead player.

4. **Status Actionbar Suppression & Range/Dimension Filtering (`PlayerStatus.java`, `Battle.java`, `d9e13fe`)**:
   - Suppressed status actionbar updates whenever the player is in a mob battle, `PvpBattle`, or has an open `PENDING` choice prompt.
   - Stored `RegistryKey<World>` on `WARDEN_QUIET` and filtered `PlayerStatus.tick` so `WARDEN_QUIET` only displays within 48 blocks in the same dimension, and `BASTION_GUEST` only displays in `World.NETHER` while inside a bastion.

## 2. Tests Added & Executed
- **`GangsGameTest.testPendingAutoChallengeExit`**:
  - Tests both a champion zombie (`MobRecord.wins > 0`) and a rematch zombie (`MobRecord.remember(..., false, ...)`) through:
    1. Fist punch during `PENDING` (via `AttackEntityCallback`),
    2. 15-second timeout (`BattleManager.expirePendingForTest`),
    3. Walking 18 blocks away (`16+` blocks).
  - Asserts `PENDING` clears cleanly without `ClassCastException` and the mob re-aggros onto the player in every case.
- `.\gradlew.bat build test --no-daemon` — **PASSED**.
- `RB_TEST_LANG=ru_ru .\gradlew.bat runClientGameTest --no-daemon` — **PASSED** (all 4 suites, 0 raw keys).
- `RB_TEST_LANG=en_us .\gradlew.bat runClientGameTest --no-daemon` — **PASSED** (all 4 suites, 0 raw keys).

## 3. Screenshots Confirmed
- `screenshots/ru_ru/0018_gangs-champion-callout.png` & `screenshots/en_us/0018_gangs-champion-callout.png`:
  - No leaked `Silence` / `Bastion Guest` timers from `ActsGameTest`, and no actionbar timer overlapping the choice popup.
- `screenshots/ru_ru/0019_gangs-rematch-callout.png` & `screenshots/en_us/0019_gangs-rematch-callout.png`:
  - Clean choice popup, no status timer overlap, zombie stationary and not burning.
