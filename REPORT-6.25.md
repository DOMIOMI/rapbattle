# Отчёт 6.25: исправления по `REVIEW-ALL-2.md` и UI-правки

Ветка: `gemini/6.25-review2-fixes` (от `gemini/6.24-review2`).

## 1. Таблица исправлений (№ → что сделал → тест)

| № | Проблема | Что сделал | Тест |
|---|---|---|---|
| 1 | Coop leave не очищал `isBattling`, защиту от урона и не давал уйти от поражения | В `Battle.java`: `hasPlayer` проверяет `!leftPlayers.contains(uuid)`; `onPlayerLeft` шлёт `end` ушедшему, снимает glow и вызывает `broadcastState()`; `keepThePeace`, `allBarsReported()`, `punishPlayers()` и `Reputation.onLoss()` пропускают `leftPlayers`. В `BattleManager.java`: `/rapbattle leave` при `b == null && pvp == null` шлёт `rapbattle.battle.not_in_battle` (добавлено в `ru_ru.json`, `en_us.json`). | `Review2Scenario.testCoopLeaveClearsIsBattling` (ранее `@Disabled`) |
| 2 | PvP `JUDGING` без окна ожидания 500 ms при 8-й строке | В `PvpBattle.java`: добавлены `allBarsReported()` и проверка `if (t < 500 && !allBarsReported()) return;` в `judging(now)`, плюс тестовые хелперы `reportedBarsCountForTest` и `jumpToForTest(String)`. | `Review2Scenario.testPvpJudging500msGraceWindow` (ранее `@Disabled`) |
| 3 | Во время баттла можно было выбрасывать/менять предметы (`Q`, `Ctrl+Q`, `F`, клики в инвентаре) и взаимодействовать с сущностями (осёл, житель и др.) | Добавлен `ServerPlayNetworkHandlerMixin` (зарегистрирован в `rapbattle.mixins.json`): отменяет `DROP_ITEM`, `DROP_ALL_ITEMS`, `SWAP_ITEM_WITH_OFFHAND` в `onPlayerAction` и все клики в `onClickSlot` при `BattleManager.isBattling(player)`, вызывает `BattleManager.micOnlyHint(player)` и `player.currentScreenHandler.syncState()`. В `BattleManager.java`: `UseEntityCallback.EVENT` возвращает `ActionResult.FAIL` с `micOnlyHint`. | `Review2Scenario.testItemAndEntityLocks` |
| 4 | Смерть игрока не от мода (`/kill`, немодовый урон) не прерывала баттл | В `BattleManager.java`: зарегистрирован `ServerLivingEntityEvents.AFTER_DEATH` — для смертей игроков не от `Battle.RAP_DEATH` вызывает `b.abortNoPenalty(p, msg)` / `pvp.abortNoPenalty(msg)`. В `PvpBattle.tick()` добавлена проверка `!pa.isAlive() \|\| !pb.isAlive()`. | `Review2Scenario.testDeathAbortsBattle` (соло, coop, PvP + респаун) |
| 5 | Верховая езда, левитация/полёт и эндер-жемчуг во время баттла | В `Battle.addPlayer` и конструкторе `PvpBattle` вызывается `stopRiding()`; в `Battle.tick()` и `PvpBattle.tick()` вызывается `stopRiding()` и возврат по Y при `p.getY() - anchor.y > 2.0`. Добавлен `EnderPearlMixin` (в `rapbattle.mixins.json`): отменяет `EnderPearlEntity.onCollision` и удаляет жемчуг (`self.discard()`) для сражающегося `ServerPlayerEntity`. | `Review2Scenario.testVehicleAndEnderPearl` |
| 6 | Урон от лавы/пустоты в `JUDGING`/`RESULT` не должен прерывать завершающийся баттл | В `Battle.java` добавлен `isResolving()` (`JUDGING \|\| ROUND_RESULT \|\| RESULT`), в `PvpBattle.java` — `isResolving()` (`JUDGING \|\| RESULT`). В `BattleManager.AFTER_DAMAGE` прерывание по `BATTLE_ABORT_ENV_DAMAGE` пропускается при `isResolving()`. | `Review2Scenario.testLavaInTimelineVsResult` |
| 7 | Coop daily cap для жемчуга, огненных стержней и черепов визера | В `Battle.finish()` для `Items.ENDER_PEARL`, `Items.BLAZE_ROD` и `Items.WITHER_SKELETON_SKULL` использован `effectiveDailyScale` вместо `dailyBonusScale`. | `Review2Scenario.testCoopDailyCapPearlsUsesEffectiveScale` (ранее `@Disabled`) |
| 8 | P2: `PiglinBrain` (перемирие, уважение, стрелы), PvP в разных измерениях, `tickChampions` | В `PiglinBrainMixin.java`: `becomeAngryWith` и `getPreferredTarget` проверяют `Crowd.inTruce(piglin, ...)` и `Reputation.respects(piglin, p)`. В `BattleManager.AFTER_DAMAGE`: вызов `Reputation.onPlayerHostileAction(sp, mob)` при атаке игрока вне баттла (включая стрелы). В `BattleManager.tickChampions`: `!Crowd.inTruce(m, p.getUuid()) && MobRecord.hasWins(m)`. В `PvpBattle.answer()`: проверка `from.getEntityWorld() != p.getEntityWorld()` с сообщением `rapbattle.pvp.different_world`. | `Review2Scenario.testPiglinTruceRespectAndArrow`, `Review2Scenario.testPvpDifferentDimensions` |
| 9 | P2: очистка `SoreLosers.RAGED` и `DOUBLE_DROP` при смерти/выгрузке моба | В `SoreLosers.java`: `AFTER_DEATH` удаляет `RAGED`; добавлен `ServerEntityEvents.ENTITY_UNLOAD` для очистки `RAGED` и `DOUBLE_DROP`. | Проверено в общем наборе тестов (включая `gangs` и `coverage`) |
| 10 | UI-правка: кнопка «Стиль танца победы» в `ru_ru` обрезалась | В `ru_ru.json`: `rapbattle.settings.victory_dance` → `"Танец победы"`, `rapbattle.settings.dance.0` → `"кулак"`. | `Review2Scenario.testResponsiveHudAndScreens` (`0000_review2-settings-small.png`, `0003_review2-settings-fixed.png`) |
| 11 | UI-правка: подпись 15-й строки перка в разборе на `ru_ru` | В `ru_ru.json`: сокращены ключи `rapbattle.trophies.triggered.*`, чтобы строка укладывалась в ширину панели разбора без лишнего обрезания. | Проверено локализацией и `GameTestSupport.assertNoRawKeys("review2")` |

## 2. Повторно включённые `@Disabled` тесты

В `Review2Scenario.java` снят `@Disabled` и включены в прогон все 3 теста:
1. `testCoopLeaveClearsIsBattling` (пункт 1)
2. `testPvpJudging500msGraceWindow` (пункт 2)
3. `testCoopDailyCapPearlsUsesEffectiveScale` (пункт 7)

## 3. Замечания по смене слота хотбара, хорусу, эндерменам и `/tp`

- **Переключение слотов хотбара (1–9 / колёсико):** намеренно не блокируется, чтобы игрок мог переключаться на микрофон или видеть слоты; блокируются именно выброс (`Q`/`Ctrl+Q`), перестановка с левой рукой (`F`), клики в инвентаре (`onClickSlot`) и взаимодействие с сущностями (`UseEntityCallback`).
- **Плод хоруса / телепорт эндермена / `/tp`:** во время активного баттла выход за радиус арены уже обрабатывается тиком арены (`Battle.tick()` / `PvpBattle.tick()` возвращает участника или прерывает баттл при смене измерения/выходе), а `EnderPearlMixin` дополнительно гасит телепорт эндер-жемчугом на этапе столкновения.

## 4. Проверка GUI scale на `854x480` и `1920x1080`

В `Review2Scenario.testResponsiveHudAndScreens` проверено окно `854x480` с `guiScale` 2, 3 и 4, а также `1920x1080`:
- При `854x480` Minecraft автоматически ограничивает эффективный GUI scale значением `2` (`854/3 < 320`, `480/3 < 240`), поэтому на всех трёх скриншотах (`0000_review2-settings-small.png`, `0001_review2-settings-scale3.png`, `0002_review2-settings-scale4.png`) экран настроек и `StyleScreen` открываются без наложений и обрезания.
- На `1920x1080` (`0003_review2-settings-fixed.png`) `Танец победы: кулак` и остальные кнопки помещаются полностью.

## 5. Итоги полного прогона (`.\gradlew.bat build test runClientGameTest --offline`)

- Команда: `.\gradlew.bat build test runClientGameTest --offline` (с `RB_TEST_WAV=C:\Users\artem\.gemini\antigravity\scratch\verse.wav`, без `RB_TEST_ONLY`).
- Результат: `BUILD SUCCESSFUL in 4m 3s`, `failures=0`.
- Суммарное время сьюта: `220451 ms` (~3 мин 40 сек).
- Тайминги по группам (мс):
  - `guidebook`: `205`
  - `e2e`: `72607`
  - `crowd`: `12705`
  - `acts`: `52460`
  - `gangs`: `51018`
  - `reputation`: `849`
  - `progression`: `1728`
  - `pvp`: `1003`
  - `coverage`: `2727`
  - `review2`: `9084`
  - `lang_en`: `4628`
