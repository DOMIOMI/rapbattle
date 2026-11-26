# Отчёт 6.21: без драк во время баттла, драка 2 на 1, хвосты 6.19

Ветка: `gemini/6.21-fixes` (от `gemini/6.20-fast-tests`).

## 1. Во время баттла бить нельзя вообще

- **Что сделано**:
  - В [BattleManager.java](file:///C:/Users/artem/.gemini/antigravity/scratch/rapbattle/src/main/java/dev/rapbattle/battle/BattleManager.java) в `AttackEntityCallback` для любого участника активного баттла (`BattleManager.byPlayer`) или PvP (`PvpBattle.byPlayer`) атака по любой цели (соперник, моб из толпы, другой игрок) отменяется (`ActionResult.FAIL`) без урона и без технического поражения.
  - В `BattleManager.micOnlyHint` добавлена подсказка в actionbar с кулдауном 3 с (`rapbattle.battle.mic_only`: «Во время баттла — только микрофон» / «Mic only during the battle»).
  - В `ServerLivingEntityEvents.ALLOW_DAMAGE` добавлена отмена урона от атак/снарядов (`source.getAttacker()`) участника активного баттла или PvP (стрелы, трезубцы, снежки, зелья, удочка и т. п.).
  - Удалены `Battle.violation(...)`, поле `streetLaw` и устаревшие ключи `rapbattle.battle.street_law_*` в `ru_ru.json` и `en_us.json` (в книге МС упоминаний нарушения не было).
- **Какой тест проверяет**: `CoverageScenario.testNoFightingDuringBattle` (`coverage`) — удар мечом по сопернику, мобу из толпы и второму игроку + выстрел из лука во время баттла: урон 0, баттл остаётся в `TIMELINE`, подсказка в actionbar появилась ровно 1 раз; после завершения баттла удар мечом снова наносит урон.

## 2. Драка 2 на 1 после баттла

- **Что сделано**:
  - В [Reputation.java](file:///C:/Users/artem/.gemini/antigravity/scratch/rapbattle/src/main/java/dev/rapbattle/battle/Reputation.java) `onPlayerHostileAction` теперь снимает не только `CRED`, но и уважение свидетелей (`WITNESSES`) у всех зрителей сцен для атакующего игрока (не затрагивая других игроков).
  - В [Crowd.java](file:///C:/Users/artem/.gemini/antigravity/scratch/rapbattle/src/main/java/dev/rapbattle/battle/Crowd.java) добавлены точечные исключения из перемирия (`TRUCE_EXCEPTIONS`, `breakTruceFor`, `inTruce(mob, playerId)`), а `tickTruce()` больше не сбрасывает цель у моба, если перемирие нарушено для этого игрока.
  - В [BattleManager.java](file:///C:/Users/artem/.gemini/antigravity/scratch/rapbattle/src/main/java/dev/rapbattle/battle/BattleManager.java) и [MobEntityTargetMixin.java](file:///C:/Users/artem/.gemini/antigravity/scratch/rapbattle/src/main/java/dev/rapbattle/mixin/MobEntityTargetMixin.java) добавлен явный путь `BattleManager.forceMobTarget(mob, target)` (`ThreadLocal MOD_FORCED_TARGET`), обходящий проверки перемирия и уважухи при назначении цели самим модом (`fightAfterBattle`, `SoreLosers.tryRage`, `GolemDefender.chickenedOut`).
- **Какой тест проверяет**: `CoverageScenario.testSoreLosersTryRageAndGolemTickAndTwoOnOne` (`coverage`) — после нелетальной победы (при активных `TRUCE` и `WITNESSES`) `p2` бьёт моба (1v1 → фанат не вмешивается), `p1` бьёт моба вторым (2v1 → фанат выходит из перемирия и `WITNESSES` для `p1` и атакует `p1`, а невиновный тиммейт `p3` сохраняет и уважуху, и перемирие); после `Clock.advanceForTest(31_000L)` фанаты больше не вступаются.

## 3. Хвосты 6.19

- **3.1. Кооп в мире**:
  - В `BattleManager` добавлен реестр тестовых игроков (`registerTestPlayer` / `findPlayer`), подключённый в `Battle.player(UUID)`, а в `Battle` — `setPlayerScoreForTest(name, dealt)` и вычисление `mvp` по счёту в `jumpToForTest(Phase.RESULT, ...)`.
  - **Тест**: `ReputationScenario.testCoopWorstTheftAndZeroDealt` (`reputation`) — команда из 3 игроков (`p1` MVP = 60, `p2` вклад = 30, `p3` молчун = 0): при победе `p1` получает 100% длительности `STREET_CRED`, `p2` — 50% (проверено истечением через `Clock +100s`), `p3` — 0%; при проигрыше той же команды моб крадёт предмет именно у `p3`.
- **3.2. Ярость `SoreLosers`**:
  - **Тест**: `CoverageScenario.testSoreLosersTryRageAndGolemTickAndTwoOnOne` (`coverage`) — `SoreLosers.tryRage` с детерминированным `Random`: моб выживает с ~50% ХП, ставит целью MVP через `forceMobTarget` даже при активном `STREET_CRED`, `doubleFreshDrops` дублирует дроп, удар мечом по нему не снимает `STREET_CRED`.

## 4. Проверки и время прогона

- Команда: `.\gradlew.bat test runClientGameTest --offline` с `RB_TEST_ONLY=reputation,coverage,lang_en`.
- Юнит-тесты (`:test`, включая `LangCompletenessTest`): **PASS**.
- Геймтесты (`failures=0`):
  - `reputation`: **1 116 мс**
  - `coverage`: **479 мс**
  - `lang_en`: **5 080 мс**
  - Итого внутри игры: **18 477 мс**, полное время команды: **1м 36с** (`97 654 мс`).
- Ключи в `build/` и `run/` проверены (утечек нет), кадры `en_us` проверены.
