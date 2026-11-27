# Отчёт 6.26: лава, пустота и смерть игрока в фазе подсчёта/итога (`isResolving()`)

Ветка: `gemini/6.26-resolving-lava` (от `gemini/6.25-review2-fixes`).

## 1. Что сделано

| № | Область | Изменения |
|---|---|---|
| 1 | Лава и якорь в `isResolving()` | В `BattleManager.ALLOW_DAMAGE` при `isPlayerResolving(p)` урон от `DamageTypes.LAVA` больше не пропускается (как и огонь — игрок полностью защищён, а `tick()` гасит `fireTicks`). В `Battle.tick()` и `PvpBattle.tick()` удержание якорем (`anchors`) отключается при `isResolving()`, чтобы игрок мог свободно отойти от опасности; остальные блокировки (предметы, удары, сущности) сохраняются до конца баттла. |
| 2 | Пустота (`OUT_OF_WORLD`) в `isResolving()` | В `BattleManager.ALLOW_DAMAGE` перед пропуском `DamageTypes.OUT_OF_WORLD` вызывается `resolveNow(spTarget)`: баттл (или выход данного игрока в коопе) немедленно завершается с текущим итогом (при `JUDGING` без ответа судьи — синхронно через `Fallback.judge(...)`), после чего урон от пустоты проходит. |
| 3 | Смерть игрока в `isResolving()` | В `BattleManager.AFTER_DEATH` для смертей не от `Battle.RAP_DEATH` при `isPlayerResolving(p)` вместо `abortNoPenalty` вызывается `resolveNow(p)`. До `isResolving()` (`/kill` в `TIMELINE`) поведение не изменилось — `abortNoPenalty`. |
| 4 | Мод / PvP / Co-op | В `Battle.resolveNow(p)`: при `activeCount <= 1` вызывается `finish()`; в коопе (`activeCount > 1`) фиксируется статистика и репутация только для `p` (`resolvedPlayers` предотвращает двойной учёт в `finish()`), `p` выходит с `end`-пакетом, остальные продолжают. В `PvpBattle.resolveNow()`: при `JUDGING` синхронно считает вердикт через `fallbackPvpVerdict()` и вызывает `finish()`. |
| 5 | Тесты (`Review2Scenario.testLavaInTimelineVsResult`) | Проверены все 5 сценариев: (1) `TIMELINE` + лава → отмена без последствий; (2) `RESULT` + лава → 0 урона, нет огня, отход на 3 блока без возврата якорем, поражение записано; (3) `JUDGING` + пустота → немедленное завершение с записью итога и проходом урона; (4) `RESULT` + `/kill` → запись поражения без `abortNoPenalty`; (5) PvP `RESULT_LOSS` + лава → 0 урона, игрок жив, поражение записано. |

## 2. Итоги прогона

- Целевой прогон: `.\gradlew.bat runClientGameTest --offline` с `RB_TEST_ONLY=review2` → `PASS review2 (12703 ms), failures=0`.
- Полный прогон: `.\gradlew.bat build test runClientGameTest --offline` (с `RB_TEST_WAV=C:\Users\artem\.gemini\antigravity\scratch\verse.wav`, без `RB_TEST_ONLY`):
  - Статус: `BUILD SUCCESSFUL in 4m 3s`, `failures=0`.
  - Суммарное время сьюта: `217938 ms`.
  - Тайминги по группам (мс): `guidebook=193`, `e2e=75427`, `crowd=12649`, `acts=52268`, `gangs=45602`, `reputation=863`, `progression=1876`, `pvp=986`, `coverage=2704`, `review2=12441`, `lang_en=4780`.
