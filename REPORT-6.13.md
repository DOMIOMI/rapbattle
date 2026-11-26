# Отчёт 6.13: доделки по ревью

Ветка: `gemini/6.13-review` (от `gemini/6.12-fixes`).

## Выполненные исправления

1. **Системная строка сработавшего трофея в разборе (`BattleView.Hit`, `BroadcastHud`, `Battle`, `PvpBattle`)**
   - В `BattleView.Hit` добавлено поле `public boolean system` (с перегруженным конструктором, сохраняющим обратную совместимость).
   - В `Battle.java` и `PvpBattle.java` уведомления о сработавшем трофее передаются с `toMob = false, system = true` (в PvP имя владельца остаётся в `hit.who`).
   - В `BroadcastHud.java` строки с `hit.system == true` рисуются золотым системным стилем (`GOLD`), не получают серую плашку `0` справа и не схлопываются в «тишина ×N» при длинном логе.
   - Добавлен геймтест `GangsGameTest.testLongBreakdownTrophyVisible` (> 14 строк, с тихими строками и сработавшим микрофоном Иссушителя); проверено визуально на `ru_ru` и `en_us` (`0020_trophy-long-breakdown.png`).

2. **Проверка измерения в `SculkShriekerMixin`**
   - В `SculkShriekerMixin.rapbattle$wardenQuiet` в `PlayerStatus.hasInRadius(...)` теперь передаётся `ServerWorld world`.

3. **Сброс кулдауна дракона при остановке сервера**
   - Добавлен `DragonBattle.clear()`, обнуляющий `cooldownUntil`, и его вызов в `BattleManager` в `ServerLifecycleEvents.SERVER_STOPPING`.

4. **Игнорирование игроков пиглинами в PvP и при ожидании вызова**
   - Добавлен публичный метод `BattleManager.hasPending(UUID)` (и `hasPendingForTest(UUID)` делегирует в него).
   - В `PiglinBrainMixin` по декомпилированному коду 1.21.11 добавлен `@Inject` в `PiglinBrain.getPreferredTarget` (`@At("RETURN")`), возвращающий `Optional.empty()`, если выбранная цель — игрок в `BattleManager.byPlayer`, `PvpBattle.byPlayer` или `BattleManager.hasPending`. Та же проверка расширена в `becomeAngryWith`.
   - `BASTION_GUEST` сюда не добавлен: он по-прежнему защищает только сундуки и золотые блоки в `onGuardedBlockInteracted`.

5. **Самодельный микрофон (`TrophyPerk.HOMEMADE`)**
   - Проверено по `Trophies.java` и `Battle.java`: `beatWindowBonus()` и любых других игровых бонусов к окну доли у `HOMEMADE` нет (он даёт только визуальную вспышку доли, как и положено по 6.1). Упоминание `beatWindowBonus()` на предыдущем шаге ревью было ошибкой по памяти без проверки файла `Trophies.java`.

## Оптимизации

6. **`Crowd.TRUCE` за O(1)**
   - Заменён `List<Map.Entry<MobEntity, Long>>` на `IdentityHashMap<MobEntity, Long>`. `inTruce` и `holdTruce` работают за `O(1)`, а `tickTruce` удаляет просроченных и мёртвых мобов через `entrySet().removeIf(...)`.

7. **PvP-перемирие раз в 5 тиков**
   - В `PvpBattle.tick` вызов `holdMobTruce(now)` выполняется раз в 5 тиков (`(truceTick++ % 5) == 0`) вместо каждого тика (перемирие держится 1500 мс).

8. **Защита от дневного горения без ежетикового скана (`MobEntityDaylightMixin`)**
   - По коду `MobEntity` в 1.21.11 (`tickMovement` -> `tickBurnInDaylight` -> `isAffectedByDaylight`) добавлен `MobEntityDaylightMixin`, возвращающий `false` в `isAffectedByDaylight` для `BattleManager.isProtectedFromSun(MobEntity)`: чемпионов (`MobRecord.wins > 0`), участников и зрителей активного баттла (`byMob`, `inAnyCrowd`), мобов с висящим вызовом (`PENDING`) и мобов в перемирии (`Crowd.inTruce`).
   - Из `BattleManager.tickChampions` удалён ежетиковый скан куба `expand(48)` для тушения; остался только тик раз в 20 тиков с радиусом 24 блока для автовызовов чемпионов.
   - `testChampionSunAndCooldown` и `testDaytimeSunSafety` пройдены, на кадре `0019_gangs-rematch-callout.png` моб днём не горит.

## Проверки

- `.\gradlew.bat build test --offline` — `BUILD SUCCESSFUL`.
- `.\gradlew.bat runClientGameTest --offline` с `RB_TEST_LANG=ru_ru` — `BUILD SUCCESSFUL`.
- `.\gradlew.bat runClientGameTest --offline` с `RB_TEST_LANG=en_us` — `BUILD SUCCESSFUL`.
- Проверены скриншоты в `ru_ru` и `en_us`: `0020_trophy-long-breakdown.png` и `0019_gangs-rematch-callout.png`.
