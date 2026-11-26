# Отчёт 6.15: дёрганая толпа и шатающаяся ведьма

Ветка: `gemini/6.15-crowd` (от `gemini/6.14-guidebook`).

## Что исправлено

1. **Стабильность толпы без дёрганий (`Crowd.java`, `MobGoalAccessor`, `MoveControlMixin`, `BattleManager.isPlacedCrowd`)**
   - Причина дёрганий: у зрителей, уже вставших на своё место (`placed`), ванильные цели ИИ (`WanderAroundGoal` и др.) и `MoveControl.tick()` продолжали двигать моба каждый тик, а `Crowd.tick` телепортировал его назад при сдвиге > 0.2 блока.
   - Решение: при постановке на место у зрителей отключаются `Goal.Control.MOVE` и `Goal.Control.JUMP` (при этом `Goal.Control.LOOK` остаётся, чтобы зрители смотрели на сцену), а `MoveControlMixin` отменяет `MoveControl.tick()` и обнуляет `forwardSpeed`/`sidewaysSpeed`, пока моб стоит в толпе (`BattleManager.isPlacedCrowd`). `setPosition` оставлен только как страховка при внешнем толчке (> 1.0 блока).
   - В `Crowd.release()` контролы `MOVE` и `JUMP` включаются обратно.

2. **Ведьма и «носатые» гуманоиды (`WitchModelMixin`, `BattleDance.java`)**
   - В 1.21.11 `WitchEntityModel` наследует `EntityModel<WitchEntityRenderState>`, а не `VillagerResemblingModel`, поэтому `VillagerModelMixin` на неё не действовал и ведьма получала только общий наклон корпуса из `BattleDance.apply`.
   - Добавлен `WitchModelMixin` (`RapDancer` -> `Moves.simple(state, head, arms, null)`), чтобы ведьма танцевала как гуманоид-житель (руки скрещены, кивки головой, без крена).
   - В `BattleDance.apply` для всех носатых гуманоидов (`villager`, `wandering_trader`, `pillager`, `vindicator`, `evoker`, `illusioner`, `witch`) убран боковой крен корпуса и плавание; для остальных моделей в ветке `default` крен строго ограничен диапазоном `[-2f, 2f]` градусов.

3. **Криперы в толпе и `mobTeam` (`CreeperMixin`, `Crowd.java`)**
   - `CreeperMixin` перехватывает `tick` (при нахождении в `BattleManager.byMob` или `BattleManager.inAnyCrowd` сбрасывает `IGNITED = false`, `setFuseSpeed(-1)`, `currentFuseTime = 0`, `lastFuseTime = 0`) и отменяет `ignite()`. После баттла криперы ведут себя как обычно.

4. **Пиглины и хоглины в верхнем мире (`AbstractPiglinAccessor`, `HoglinAccessor`, `Crowd.java`, `Battle.java`)**
   - Для пиглинов, брутов и хоглинов в толпе и `mobTeam` сохраняется прежнее значение `isImmuneToZombification()` и выставляется `setImmuneToZombification(true)` на время баттла; в `Crowd.release()` / `Battle.releaseMobs()` возвращается исходное состояние.

5. **Опоздавшие мобы (`Crowd.tick`)**
   - Раз в 20 тиков `Crowd.tick` проверяет новых мобов в радиусе `RADIUS`: пока `members.size() < MAX`, добавляет их в толпу и пересчитывает радиус; если мест нет — враждебным новичкам выдаётся перемирие (`holdTruce`), цель сбрасывается и моб отводится к краю круга. Боссы и участники других баттлов не затрагиваются.

## Проверки

- Добавлен клиентский геймтест `CrowdGameTest`:
  - замер позиций всех зрителей каждый тик за 5 секунд (100 тиков): ни один моб не сдвинулся > 0.1 блока;
  - ведьма-соперник и ведьма в толпе: кадры `0023_crowd-witch-reading.png` и `0024_crowd-ring-stable.png` проверены на `ru_ru` и `en_us`;
  - зажжённый крипер-опоздавший встаёт в толпу и не взрывается;
  - пиглин в толпе защищён от зомбификации во время баттла и возвращает исходное состояние после.
- `.\gradlew.bat build test runClientGameTest --offline` (`RB_TEST_LANG=ru_ru`) — `BUILD SUCCESSFUL`.
- `.\gradlew.bat runClientGameTest --offline` (`RB_TEST_LANG=en_us`) — `BUILD SUCCESSFUL`.
