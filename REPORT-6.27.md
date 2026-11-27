# Отчёт 6.27 — фиксы третьего ревью (beta 7)

Ветка: `gemini/6.27-review3-fixes` (создана от `gemini/6.26-resolving-lava`).

## Итоговая таблица исправлений (блоки А–Ж)

| Пункт | Что сделал | Тест / проверка |
|---|---|---|
| **А1** | `Battle.onPlayerLeft(UUID)` в фазах `isResolving()` вызывает `resolveNow(UUID)` и для онлайн-, и для оффлайн-игроков без телепортаций (оффлайн: Stats/MobRecord/Reputation/потери босса применяются, кража пропускается при оффлайне худшего/челленджера) | `Review2Scenario.testReview3BattleAndPvpFixes` (`review2`) |
| **А2** | `Battle.resolveNow(UUID)` при `!result.finalResult` проверяет `winsPlayers >= winsNeeded`, чтобы победа в 1/3 раунде против Дракона + `/kill` в `ROUND_RESULT` не засчитывалась как победа над Драконом | `Review2Scenario.testReview3BattleAndPvpFixes` (`review2`) |
| **А3** | `PvpBattle.leave(UUID)` в `Phase.RESULT` вызывает `finish()` (сохраняя победителя из `view.result`), в `Phase.JUDGING` вызывает `resolveNow()` | `Review2Scenario.testReview3BattleAndPvpFixes` (`review2`) |
| **А4** | Добавлено множество `anchorReleased` в `Battle` и `PvpBattle`; `BattleManager.ALLOW_DAMAGE` отпускает якорь только при попадании в лаву/огонь в `isResolving()`; `Battle.beginRoundContent` очищает `anchorReleased` и возвращает игроков на `(a.x, a.y, a.z)` | `Review2Scenario.testLavaVoidAndResultProtection` (`review2`) |
| **А5** | `Battle.resolveNow(UUID)` проверяет `activeCount <= 1` до оценки `JUDGING`, чтобы ранний выход в коопе не отменял `verdictFuture` для оставшихся тиммейтов | Сборка + `review2` (кооп-сценарии) |
| **А6** | Добавлен `Battle.activePlayers()`; ливер в `LOBBY` удаляется полностью; `leftPlayers` исключаются из `challenger()`, `namesList()`, `beginRoundContent()`, `playerLines()`, `buildTimeline()`, `DamagePop`, `mvp`, `finish()`, `recordStats()`, `Reputation.grantOnWin` | `Review2Scenario.testReview3BattleAndPvpFixes` (`review2`) |
| **А7** | `Battle.answerInvite` проверяет `!BattleManager.isBattling(p)`, тот же мир, дистанцию `<= 32` и очищает pending-выбор; `BattleManager.rap`/`start` и `PvpBattle.answer` также проверяют `isBattling` и очищают pending-выборы | `Review2Scenario.testReview3BattleAndPvpFixes` (`review2`) |
| **А8** | Намеренно оставлено без изменений (см. заметку ниже) | — |
| **Б1** | Добавлены `Transcriber.isValidWav16kMono16` и `transcribeTimedChecked` (битый WAV и 4xx → тишина игрока; отсутствие ключа/5xx/таймаут → `serviceFailure`); `Battle` и `PvpBattle` ведут `sttMissingPlayers`, очищают в `beginRoundContent` и игнорируют устаревший STT | `Review2UnitTest.wavHeaderValidationChecks16kMono16Bit` (`test`), `Review2Scenario.testReview3BattleAndPvpFixes` (`review2`) |
| **Б2** | Добавлен 20s `LEAVE_COOLDOWN` в `BattleManager`; буфер `onAudio` ограничен `totalBeats * beatMs + 5000ms`; `Battle.POOL` ограничен (`LinkedBlockingQueue<>(64)`); futures отменяются в `abort`/`finish`/`PvpBattle.end` | `gradlew test` + `runClientGameTest` (`e2e`, `pvp`, `review2`) |
| **Б3** | `GuideBook.give` проверяет `hasGuideBook(player)` и 5s cooldown | `GuideBookScenario` (`guidebook` с `Clock.advanceForTest(5_500L)` после join) |
| **Б4** | `Battle.onNoise` ограничен фазой `Phase.TIMELINE` на строках моба (`mobStartBeat..mobEndBeat`) и лимитом 10 на раунд на игрока (`playerNoise`) | Сборка + `runClientGameTest` (`e2e`, `acts`) |
| **Б5** | Ограничено логирование плохих action-пакетов в `BattleManager` (10s, 120 символов); `Speech.refreshIfStale` ставит `generatedAt` сразу и снимает `generating` в `finally`; в `Llm.judge`/`judgePvp` добавлена защита от prompt-injection и `trimComment` (`<= 250`); `RapConfig.instance` сделан `volatile` с атомарной заменой в `saveKeys` | Сборка + `gradlew test` + `runClientGameTest` |
| **В1, В2, В4** | `ServerPlayNetworkHandlerMixin` инъектится после `NetworkThreadUtils.forceMainThread`, блокирует `Action.STAB`, `onCreativeInventoryAction`, `onPickItemFromBlock` | Сборка + `runClientGameTest` |
| **В3** | `Battle` сохраняет `initialSelectedSlot` в `addPlayer`; `Theft.stealOnLoss` использует его | `Review2Scenario.testReview3BattleAndPvpFixes` (`review2`) |
| **В5, В8b, В9** | `BattleManager` пропускает `BYPASSES_INVULNERABILITY`/`OUT_OF_WORLD` для мобов баттла/толпы, абортит при `AFTER_DEATH` моба когда `!isResolving()`, чистит мёртвых/удалённых мобов из `RECENT`/`BRAWLS` в `tickStares` и обрабатывает `MOB_CONVERSION` (очищает `NoAI`/`Silent` и абортит активный баттл) | Сборка + `runClientGameTest` |
| **В6** | `MobEntityTargetMixin` вызывает `stopAnger()` и `BattleManager.forceMobTarget(self, null)` при отмене `setTarget` | Сборка + `runClientGameTest` (`reputation`, `crowd`) |
| **В7** | `PiglinBruteBrainMixin` проверяет `Crowd.inTruce` и `Reputation.respects`; `Crowd.tickTruce` сбрасывает `ATTACK_TARGET` и `ANGRY_AT` | Сборка + `runClientGameTest` (`reputation`) |
| **В8a** | `SoreLosers.RAGED` получил 5-минутный TTL (`Map<UUID, Long>`) | Сборка + `runClientGameTest` (`gangs`) |
| **Г1** | `CreeperMixin` дропает украденный предмет в `@At("TAIL")` метода `explode` | `Review2Scenario.testReview3LootReputationAndDecisions` (`review2`) |
| **Г2** | `Theft.stealOnLoss` не экипирует `MAINHAND` для `AllayEntity` и `FoxEntity` | Сборка + `runClientGameTest` |
| **Г3, Д4** | `SoreLosers.doubleFreshDrops` отслеживает `Set<UUID> seen`; `Battle.finish` спавнит бонус-дропы актов после цикла убийства мобов (с восстановленным блоком финала Дракона); `keepThePeace` запускается раз в 5 тиков | Сборка + `acts` + `gangs` + `progression` |
| **Д1, Д2** | `Reputation.revokeCred` очищает `WITNESSES` для игрока; `mergeCred` берёт `max(prevUntil, now + durationMs)` с ограничением `STREET_CRED_MS` | `Review2UnitTest.mergeCredDoesNotShortenExistingTimer` (`test`), `Review2Scenario.testReview3LootReputationAndDecisions` (`review2`) |
| **Д3** | `Crowd.gather` исключает `!BattleManager.inAnyCrowd(m)` | Сборка + `crowd` |
| **Е1** | `ClientConfig.save` сначала вызывает `RapConfig.load()`, если `rapbattle.json` ещё содержит legacy-ключи | `Review2Scenario.testReview3LootReputationAndDecisions` (`review2`) |
| **Е2** | В `sounds.json` выставлен `"preload": true` на всех `beat.*`; `BeatPlayer` предзагружает трек в `LOBBY`/`PREP` | Сборка + `e2e` |
| **Е3, Е4** | `MicRecorder.start()` выходит сразу если `running`, `stop()` закрывает `line` до `join`; `CalibrationScreen` задаёт `start.active = micStart < 0` и останавливает микрофон в `removed()`; `ClientBattle` ограничивает Warden `MIC.start()` 1 разом за раунд (`wardenMicKey`) и вызывает `Intro.reset()` в `disconnect()` | Сборка + `runClientGameTest` |
| **Е5, Е6** | `KeysScreen` переопределяет `getNarrationMessage()` без чтения секретного текста; `FirstPersonMic.offhandPerk` кэширует последний `ItemStack`+count; `BattleDance.mode` кэширует `IntOpenHashSet` по `BattleView` | Сборка + `runClientGameTest` |
| **Е7** | Описание в `fabric.mod.json` на английском; `fabricloader >=0.18.6`, `fabric-api >=0.141.6+1.21.11` | Сборка + `runClientGameTest` (`lang_en`) |
| **Ж1** | `GolemDefender.respect` использует `Stats.Player.dailyWinsFor("iron_golem_reward", day)` для награды 1 раз/день; `Reputation.grantOnWin` исключает `IronGolemEntity` | `Review2Scenario.testReview3LootReputationAndDecisions` (`review2`) |
| **Ж2** | `Crowd.dropGift` пропускает `TameableEntity` / `AbstractHorseEntity`, приручённых участником баттла | Сборка + `crowd` |
| **Ж3, Ж4** | `Stats.record` гейтит `speciesWins`/`dailyWins`/`dragonWon`/gate-титулы по `!coop \|\| gained > 0`; `Stats.recordPvp` больше не выдаёт Respect (сохраняя титул `Серийный` при `streak >= 5`) | `Review2UnitTest.coopSilentPlayerDoesNotUnlockSpeciesOrDragonGates`, `StatsTest` (`test`), `Review2Scenario.testReview3BattleAndPvpFixes` (`review2`) |
| **Ж5** | В `ru_ru.json` (строки 6–7) `death.attack.rapbattle.rap_battle*` заменено с `"разъебан"` на `"разгромлен"` | `Review2Scenario` (`review2`), `lang_en` |

## Заметки

- **А8 (PvP `TIMELINE` и лава/бездна):** Оставлено без изменений по требованиям. В `TIMELINE` `BattleManager.ALLOW_DAMAGE` блокирует входящий урон (включая лаву и бездну), а игрок остаётся на якоре арены и телепортируется обратно при уходе с неё — таким образом умышленный заход в лаву/бездну во время `TIMELINE` не даёт мгновенно прервать или переиграть PvP-раунд без штрафа (в отличие от `JUDGING`, где урон лавы/бездны разрешается сразу).
- **Ж4 (PvP-титулы):** Титул `Серийный` за `streak >= 5` уже существовал в `Stats.recordPvp`; начисление Respect за PvP-победы убрано.
- **Ж5 (другие нецензурные/грубые ключи в `ru_ru.json` / `en_us.json`, не гейтящиеся `allowSwearing`):**
  - `rapbattle.hud.final_win`
  - `rapbattle.hud.final_lose_*`
  - `rapbattle.hud.spec_final_*`
  - `rapbattle.broadcast.*`
  - `rapbattle.trophies.lore.crushed`
  - `rapbattle.battle.win_verb.0`
  - `rapbattle.battle.team_win_verb.0`

## Результаты полного прогона (`.\gradlew.bat build test runClientGameTest --offline`)

- Итог: `BUILD SUCCESSFUL in 4m 7s`, `failures=0`.
- Суммарное время Client GameTests: `218833 ms` (~3 мин 39 сек, в пределах лимита 6 мин).
- Разбивка по группам (`[RB-SUITE] SUMMARY`):
  - `guidebook`: `164 ms`
  - `e2e`: `75639 ms`
  - `crowd`: `12661 ms`
  - `acts`: `52299 ms`
  - `gangs`: `47506 ms`
  - `reputation`: `875 ms`
  - `progression`: `1875 ms`
  - `pvp`: `973 ms`
  - `coverage`: `2709 ms`
  - `review2`: `8258 ms`
  - `lang_en`: `4696 ms`
