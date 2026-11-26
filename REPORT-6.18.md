# Отчёт по задаче 6.18 — Исправления по ревью 6.14–6.17 (`gemini/6.18-fixes`)

## Выполненные пункты

### 1. Ворота прогрессии по итоговому рангу баттла
- **Что сделано**:
  - В [ProgressionRules.java](file:///C:/Users/artem/.gemini/antigravity/scratch/rapbattle/src/main/java/dev/rapbattle/battle/ProgressionRules.java) добавлен чистый расчёт `computeEffectiveRanks(baseRank, isRaidCaptain, gangRival, bribed)`:
    - вражеская банда (`+1` до `5`) и взятка изумрудом (`-1` до `1`) меняют как `battleRank`, так и `gateRank`;
    - повышение капитана рейда до `GOLD` меняет только `battleRank`, оставляя `gateRank` без повышения (Акт 2 не запирается гейтом 4 ранга).
  - В [Progression.java](file:///C:/Users/artem/.gemini/antigravity/scratch/rapbattle/src/main/java/dev/rapbattle/battle/Progression.java) добавлен общий метод `computeRanks(...)`, который не списывает изумруд при просмотре карточки выбора; списание происходит только при реальном старте баттла в [Battle.java](file:///C:/Users/artem/.gemini/antigravity/scratch/rapbattle/src/main/java/dev/rapbattle/battle/Battle.java).
  - В [Stats.java](file:///C:/Users/artem/.gemini/antigravity/scratch/rapbattle/src/main/java/dev/rapbattle/battle/Stats.java) добавлена перегрузка `record(..., Rank battleRank, ...)`, и `Battle.recordStats` передаёт итоговый `this.rank`.
- **Какой тест проверяет**:
  - Unit: `ProgressionTest.effectiveRanksForGatesAndStats` (капитан рейда, вражеская банда, взятка изумрудом).
  - GameTest: `ProgressionGameTest.testEffectiveRankGatesAndSkipChoice` (подкупленный Поборник при `0` Респекта в `strict` без траты изумруда на карточке и с тратой на старте; Эндермен во вражеских цветах зомби-банды при `0` Респекта в `strict` получает отказ как ранг 4).

### 2. Единая проверка старта (`skipChoice`, `offerChoice`, подход, авто-вызовы)
- **Что сделано**:
  - В [Progression.java](file:///C:/Users/artem/.gemini/antigravity/scratch/rapbattle/src/main/java/dev/rapbattle/battle/Progression.java) добавлен `canStart(p, mob, checkDressCode, precomputedCrystals, rnd)`.
  - В [BattleManager.java](file:///C:/Users/artem/.gemini/antigravity/scratch/rapbattle/src/main/java/dev/rapbattle/battle/BattleManager.java) эта проверка используется в `offerChoice`, `offerOnApproach`, `canAutoChallengeMob` и при `skipChoice = true`. При отказе в `skipChoice` срабатывает общий `broadcastRefusal(...)` (та же реплика в чат/бабл, `stareAt` и `REFUSED`).
- **Какой тест проверяет**:
  - GameTest: `ProgressionGameTest.testEffectiveRankGatesAndSkipChoice` (`skipChoice = true`: Варден без микрофона и Ифрит в `strict` при `0` Респекта не запускают баттл).

### 3. Единая сборка имени моба-вора/чемпиона и восстановление `wasPersistent`
- **Что сделано**:
  - В [MobGoalAccessor.java](file:///C:/Users/artem/.gemini/antigravity/scratch/rapbattle/src/main/java/dev/rapbattle/mixin/MobGoalAccessor.java) добавлен `@Accessor("persistent") void rapbattle$setPersistent(boolean)`.
  - В [Theft.java](file:///C:/Users/artem/.gemini/antigravity/scratch/rapbattle/src/main/java/dev/rapbattle/battle/Theft.java) добавлен единый метод `refreshMobName(MobEntity, String)`, объединяющий исходную бирку, золотую корону `♛` / `♛×N` и метку `[Предмет]`. Он же вызывается в [Battle.java](file:///C:/Users/artem/.gemini/antigravity/scratch/rapbattle/src/main/java/dev/rapbattle/battle/Battle.java) при победе моба, при конверсии моба и при возврате предмета.
  - В `Theft.clearMobVisuals` и `Theft.dropOnDeathOrExplode` восстанавливается `wasPersistent`, если моб не стал чемпионом (`!MobRecord.hasWins(mob)`).
- **Какой тест проверяет**:
  - GameTest: `ReputationGameTest.testThiefChampionNameAndMapCleanup` (повторная победа вора сохраняет `♛×2` и `[Предмет]` с золотым цветом; при возврате остаётся золотое `♛×2` без метки и `isPersistent() == true`; у не-чемпиона после дропа `isPersistent()` возвращается в `false`).

### 4. Очистка карт в `Reputation`
- **Что сделано**:
  - В `Reputation.tick` ([Reputation.java](file:///C:/Users/artem/.gemini/antigravity/scratch/rapbattle/src/main/java/dev/rapbattle/battle/Reputation.java)) добавлена периодическая очистка истёкших записей в `PLAYER_BUBBLE_CD`, `MOB_BUBBLE_CD` и `WITNESSES` (включая удаление пустых внутренних карт и мёртвых/удалённых мобов).
- **Какой тест проверяет**:
  - GameTest: `ReputationGameTest.testThiefChampionNameAndMapCleanup` (`Reputation.advanceTimersForTest(...)` + тик сервера -> `Reputation.activeMapEntriesForTest() == 0`).

### 5. Оптимизация горячих миксинов
- **Что сделано**:
  - В [MobRecord.java](file:///C:/Users/artem/.gemini/antigravity/scratch/rapbattle/src/main/java/dev/rapbattle/battle/MobRecord.java) добавлен `hasWins(Entity)` с предварительной проверкой `e.hasAttached(TYPE)`.
  - В [Crowd.java](file:///C:/Users/artem/.gemini/antigravity/scratch/rapbattle/src/main/java/dev/rapbattle/battle/Crowd.java) добавлен `ALL_PLACED` (`IdentityHashMap`) и `isPlaced(MobEntity)` (очищается в `release`, `retreat`, `clear`), а также быстрый выход `TRUCE.isEmpty()` в `inTruce`.
  - В [BattleManager.java](file:///C:/Users/artem/.gemini/antigravity/scratch/rapbattle/src/main/java/dev/rapbattle/battle/BattleManager.java) `isPlacedCrowd` и `isProtectedFromSun` проверяют `BATTLES.isEmpty()` / `PENDING.isEmpty()` / `MobRecord.hasWins` / `Crowd.isPlaced` без лишних обходов.
- **Какой тест проверяет**:
  - GameTest: `CrowdGameTest` (`testChampionSunAndCooldown`, `testDaytimeSunSafety`, неподвижность толпы).

### 6. Однократный поиск кристаллов Края и передача результата гейта в `Battle`
- **Что сделано**:
  - В [DragonBattle.java](file:///C:/Users/artem/.gemini/antigravity/scratch/rapbattle/src/main/java/dev/rapbattle/battle/DragonBattle.java) выделен `countCrystals(dragon)`: `canChallenge` считает кристаллы один раз и передаёт `0` в `Progression.check(..., 0)`.
  - В [Battle.java](file:///C:/Users/artem/.gemini/antigravity/scratch/rapbattle/src/main/java/dev/rapbattle/battle/Battle.java) и [BattleManager.java](file:///C:/Users/artem/.gemini/antigravity/scratch/rapbattle/src/main/java/dev/rapbattle/battle/BattleManager.java) добавлен конструктор с `precheckedGate`, чтобы не перепроверять ворота на том же тике при старте из `Pending` или `skipChoice`.
- **Какой тест проверяет**:
  - GameTest: `ActsGameTest.testAct5Dragon`, `ProgressionGameTest.testWardenAndDragonGates`.

### 7. Единая выдача `BASTION_GUEST`
- **Что сделано**:
  - Выдача `PlayerStatus.BASTION_GUEST` оставлена только в `Reputation.grantOnWin` ([Reputation.java](file:///C:/Users/artem/.gemini/antigravity/scratch/rapbattle/src/main/java/dev/rapbattle/battle/Reputation.java)) с проверкой `actBonusesAllowed && dailyBonusScale > 0.0` и кооп-участия (`isMvp ? baseDur : baseDur / 2`, игроки с 0 вклада в коопе статус не получают).
  - Из `BastionBattle.onWin` ([BastionBattle.java](file:///C:/Users/artem/.gemini/antigravity/scratch/rapbattle/src/main/java/dev/rapbattle/battle/BastionBattle.java)) удалён дублирующий вызов `PlayerStatus.grant`, оставлено только сообщение в чат.
- **Какой тест проверяет**:
  - GameTest: `ActsGameTest.testAct3BastionGuest`, `ReputationGameTest.testPiglinTwoTiers`.

### 8. Удаление мёртвого `Crowd.voice`
- **Что сделано**:
  - Метод `Crowd.voice(MobEntity, boolean)` и его три вызова удалены из [Crowd.java](file:///C:/Users/artem/.gemini/antigravity/scratch/rapbattle/src/main/java/dev/rapbattle/battle/Crowd.java).
  - **История отключения**: по `git log -S "if (true) return"`: заглушка `if (true) return;` присутствовала с самого первого коммита `b249874` («Rap Battle 0.1.0») с комментарием `// Animals stay silent during a battle: the hall sound carries the reaction.`, так как ванильные звуки животных конфликтовали со звуком толпы (`CrowdSounds`).

## Новые наблюдения (без правок)
- В `BattleManager.offerChoice` при вызове `Progression.canStart(..., precomputedCrystals = 0, ...)` для обычных мобов кристаллы не нужны, а для Эндер-дракона поиск кристаллов уже выполнен чуть раньше в `DragonBattle.canChallenge`. Если кто-то вызовет `offerChoice` на Эндер-дракона в обход `AttackEntityCallback`, кристаллы бы не пересчитались (на практике все вызовы идут через `AttackEntityCallback`).

## Результаты проверок
- `.\gradlew.bat build test --offline` — `BUILD SUCCESSFUL`
- `.\gradlew.bat runClientGameTest --offline` (`RB_TEST_LANG=ru_ru`) — `BUILD SUCCESSFUL`
- `.\gradlew.bat runClientGameTest --offline` (`RB_TEST_LANG=en_us`) — `BUILD SUCCESSFUL`
- Скриншоты `0031_progression-strict-refusal.png` и `0032_progression-dragon-refusal.png` проверены в `ru_ru` и `en_us`.
