# Report 6.11: Pre-Beta 0.4.0-beta.2 Polish

Branch: `gemini/6.11-polish` (from `gemini/6.10-polish`).

## 1. What Changed

1. **Perk-Colored Wide Collar on Microphone (`MicModel.java`, `c330f94`)**:
   - Colored the wider collar band under the microphone head (`1.6 × 1.6` at `z = -2.35f`) using the active perk color (`ORANGE_TERRACOTTA` for homemade, perk block for trophies) and `headLight`. When `perk == null`, it stays metal (`IRON_BLOCK`).
   - Kept the upper thin collar (`1.3 × 1.3`) metallic and retained the 360° handle band for third-person views.

2. **Context-Specific Auto-Challenge Titles (`BattleManager.java`, `BastionBattle.java`, `RaidBattle.java`, `en_us.json`, `ru_ru.json`, `cf42776`)**:
   - Added `titleKey` parameter to `BattleManager.challengeFrom(p, mob, titleKey)` (default `rapbattle.manager.golem_challenge`).
   - Added and wired 4 new localized keys:
     - `rapbattle.manager.champion_challenge`: `ВЫЗОВ ЧЕМПИОНА` / `CHAMPION CHALLENGE`
     - `rapbattle.manager.rematch_challenge`: `РЕВАНШ` / `REMATCH`
     - `rapbattle.manager.raid_captain_challenge`: `ВЫЗОВ КАПИТАНА` / `RAID CAPTAIN CHALLENGE`
     - `rapbattle.manager.brute_challenge`: `ВЫЗОВ БРУТА` / `BRUTE CHALLENGE`

3. **PENDING Sun Immunity (`BattleManager.java`, `cf42776`)**:
   - Extinguished `pd.mob` every server tick while waiting for `[R]` in `PENDING` (`if (pd.mob.isAlive() && pd.mob.isOnFire()) pd.mob.extinguish()`).

4. **Breakdown Zero-Damage Info Rows (`BroadcastHud.java`, `cc235df`; `ru_ru.json`, `cc3874d`)**:
   - For informational non-mob-target rows with `damage == 0 && !hit.toMob` (such as gang rivalry), `BroadcastHud` no longer draws a `"0"` badge or reserves width for it, while preserving `"0"` for player silence/stumble rows (`hit.toMob == true`).
   - Shortened `rapbattle.act6.gang_rival` in `ru_ru.json` to `+1 ранг (вражда)` so long mob names + gang rivalry fit cleanly without ellipsis at `1280×720`.

5. **PvP Hostile Mob Truce (`Crowd.java`, `PvpBattle.java`, `PvpGameTest.java`, `3103105`)**:
   - Added `Crowd.holdTruce(MobEntity, long untilMs)` and `PvpBattle.holdMobTruce(long now)` to keep hostile mobs within 16 blocks of the PvP stage in truce every tick (no targeting, navigation stopped, looking toward the stage, creepers defused via `setFuseSpeed(-1)`).
   - Added `PvpBattle.setTestPeerForTest` and verified in `PvpGameTest` with a `FakePlayer` and nearby `ZombieEntity` over 60 ticks that the zombie stays in truce and deals zero damage.

6. **Per-Language Screenshot Folders (`GameTestSupport.java`, `ShowcaseTest.java`, `ActsGameTest.java`, `GangsGameTest.java`, `fa75c15`)**:
   - Added `GameTestSupport.takeScreenshot(ctx, name)`: when `RB_TEST_LANG` is set, moves captured PNGs into `screenshots/<lang>/` so `ru_ru` and `en_us` runs do not overwrite each other.

## 2. Screenshots Confirmed
- `screenshots/ru_ru/0011_act1-first-person-homemade-mic.png` & `screenshots/en_us/0011_act1-first-person-homemade-mic.png`: orange wide collar under the silver head is clearly visible in first-person.
- `screenshots/ru_ru/0012_act1-first-person-trophy-mic.png`: glowing purple collar on trophy mic.
- `screenshots/ru_ru/0017_gangs-rival-breakdown.png` & `screenshots/en_us/0017_gangs-rival-breakdown.png`: gang rivalry line fits cleanly without `"0"` badge or truncation.
- `screenshots/ru_ru/0018_gangs-champion-callout.png` & `screenshots/en_us/0018_gangs-champion-callout.png`: `ВЫЗОВ ЧЕМПИОНА` / `CHAMPION CHALLENGE`.
- `screenshots/ru_ru/0019_gangs-rematch-callout.png` & `screenshots/en_us/0019_gangs-rematch-callout.png`: `РЕВАНШ` / `REMATCH`, zombie not burning in daytime sun.

## 3. Test Results
- `.\gradlew.bat build test --no-daemon` — **PASSED**.
- `RB_TEST_LANG=en_us .\gradlew.bat runClientGameTest --no-daemon` — **PASSED** (all 4 suites, 0 raw keys).
- `RB_TEST_LANG=ru_ru .\gradlew.bat runClientGameTest --no-daemon` — **PASSED** (all 4 suites, 0 raw keys).
