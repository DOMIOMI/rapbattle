# Отчёт по задаче 6.19 — Тесты на непокрытое (`gemini/6.19-tests`)

## Сводная таблица покрытия

| Пункт | Тест | Статус | Примечание |
|---|---|---|---|
| 1. Кооп с `FakePlayer` (`invite`, `requiredToJoin`, `onPlayerLeft`) | `CoverageGameTest.testCoopFakePlayerAndViolation` | Прошёл | Проверены порог `requiredToJoin`, выход напарника в бою и выход лидера в лобби |
| 1. Кооп доли статусов (`STREET_CRED`, `BASTION_GUEST`, `PIGLIN_PAL`) и кража у худшего | `Reputation.grantOnWin`, `Battle.finish` | Частично / спорно | Кооп-вклад проверен на уровне логики `Battle`/`Reputation`; в мире `FakePlayer` не числится в `PlayerManager`, поэтому для полного e2e с 3 игроками нужен мультиплеерный стенд |
| 2. Нарушение во время баттла (`violation`) | `CoverageGameTest.testCoopFakePlayerAndViolation` | **Нашёл и починил баг** | Удар оружием по мобу баттла молча отменялся вместо `violation`; пустая рука нарушением не считается |
| 3. «Не признающие поражение» (`SoreLosers`) | `CoverageGameTest.testSoreLosersAndGolemAndTwoOnOne` | Прошёл | `blast` не ломает блоки, удар по обычному мобу снимает уважуху |
| 4. Голем-защитник (`GolemDefender`) | `CoverageGameTest.testSoreLosersAndGolemAndTwoOnOne` | Прошёл | `isPlayerCreated` игнорируется; `chickenedOut` агрит; `respect` выдаёт 5 железа и 1 мак |
| 5. Драка 2 на 1 после баттла (`fightAfterBattle`) | `CoverageGameTest.testSoreLosersAndGolemAndTwoOnOne` | **Нашёл взаимодействие** | Проходит при очистке `Reputation`; см. описание ниже |
| 6. Перки трофейных микрофонов (5 перков) | `ScoringPerksAndStylesTest.trophyPerksMatchLangDescriptions` | Прошёл | `DEFAULT`, `ENDER_DRAGON`, `ELDER_GUARDIAN`, `WITHER` (+ `WARDEN` в `CoverageGameTest` / `Battle`) |
| 7. Почерки МС (`McStyle`) и пороги 500/1500 | `ScoringPerksAndStylesTest`, `CoverageGameTest.testEndermanGazeAndStyleUnlockHints` | Прошёл | Разница в панчах, серии с 2-го удара ×1.3, штрафе -1, лечении и однократных подсказках |
| 8. Помехи мобов (`MobTricks`) и `onGaze` | `MobTricksTest`, `CoverageGameTest.testEndermanGazeAndStyleUnlockHints` | Прошёл | Все 10 эффектов `apply`, флаги `gaze`/`fuse`/`silence`/`creakingGaze`, `onGaze` в баттле |
| 9. Титулы и `Stats.recordPvp` | `StatsTest` | Прошёл | Пороги 10 овец, 10 зомби, 5 криперов, 3 `chickened`, перевод и изоляция PvP-статистики |
| 10. `KeysScreen` (фейковые маскированные ключи) | `CoverageGameTest.testScreensAndClipRecorder` | Прошёл | Ключи замаскированы (`…l-or`), настоящих ключей в конфиге и `build/run` нет |
| 11. `CalibrationScreen` | `CoverageGameTest.testScreensAndClipRecorder` | Прошёл | Экран открывается и рендерится без сырых ключей |
| 12. `ClipRecorder` | `CoverageGameTest.testScreensAndClipRecorder` | Прошёл | Сохраняет WAV в `<gameDir>/rapbattle-clips/`, заголовок `RIFF`/`WAVE`, размер > 44 байт |

## Найденные баги и взаимодействия

1. **Починено (`fix(battle)` в `BattleManager.java`)**: В `AttackEntityCallback` условие `if (own != null && !own.hasMob(mob) && !sp.getMainHandStack().isEmpty())` не вызывало `own.violation(sp)`, если участник бил оружием **самого моба текущего баттла** (ветка `byMob(mob)` ниже молча возвращала `ActionResult.FAIL`). Условие `!own.hasMob(mob)` удалено: теперь любой удар оружием участника посреди баттла корректно засчитывается как нарушение закона улицы (`violation`).
2. **Обнаруженное взаимодействие в `fightAfterBattle` (не менялось, описано)**: После победы игрока в баттле зрители толпы становятся свидетелями (`Reputation.WITNESSES`) на 3 минуты, а `Crowd.release` ставит 5-секундное перемирие (`TRUCE`). В результате `MobEntityTargetMixin` отменяет `fan.setTarget(p)` в `BattleManager.fightAfterBattle`, если бой 2 на 1 начался сразу после баттла или против победителя-свидетеля (`onPlayerHostileAction` сбрасывает `CRED`, но не `WITNESSES`).

## Что не успел / вынесено за рамки
- Полный живой раунд с несколькими реальными игроками (не `FakePlayer`, которых нет в `PlayerManager.getPlayerList()`) для проверки кооп-статусов и кражи в одном e2e-сценарии.
- Детерминированный прогон `SoreLosers.tryRage` с подменённым `Random` внутри `Battle` (в `CoverageGameTest` проверены `blast` и `Reputation.onPlayerHostileAction`).

## Результаты проверок
- `.\gradlew.bat build test --offline` — `BUILD SUCCESSFUL` (все 44 unit-теста пройдены).
- `.\gradlew.bat runClientGameTest --offline` (`RB_TEST_LANG=ru_ru` и `RB_TEST_LANG=en_us`) — `BUILD SUCCESSFUL` (все 9 геймтест-наборов пройдены).
- Проверка `build/run` на паттерны `sk-or-v1-|gsk_|sk_` — совпадений не найдено.
- Скриншоты `0034_keys-screen-masked.png` и `0035_calibration-screen.png` проверены в `ru_ru` и `en_us`.
