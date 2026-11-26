# Отчёт 6.20: быстрые геймтесты

Ветка: `gemini/6.20-fast-tests` (от `gemini/6.19-tests`).

## 1. Итоги замера времени (до vs после)

| Прогон | До (`gemini/6.19-tests`) | После (`gemini/6.20-fast-tests`) | Ускорение |
| :--- | :--- | :--- | :--- |
| Полный прогон (`ru_ru` + `en_us`) | `ru_ru`: **8м 07с** (488 644 мс, упал на последнем классе) + `en_us` (~8+ мин) ≈ **16+ мин** | **4м 52с** (293 369 мс; внутри игры **215 176 мс**) | **> 3.3×** (укладывается в цель ≤ 6 мин) |
| Выборочный прогон (`RB_TEST_ONLY=coverage`) | — (не поддерживался) | **1м 32с** (внутри игры **9 471 мс**, группа `coverage` = 639 мс) | Цель ≤ 2–3 мин выполнена |

### Таблица времени по группам сценариев (после 6.20)

| Группа (`RB_TEST_ONLY`) | Время внутри игры, мс | Что проверяет |
| :--- | ---: | :--- |
| `guidebook` | 167 | Первый вход (1 книга), повторный вход (без дубля), `/rapbattle guide`, вёрстка 14 страниц (`ru_ru`) |
| `e2e` | 68 737 | Один сквозной баттл с реальным `RB_TEST_WAV`: бит, куплет моба, аудио игрока, судья, итог (сеть только с `RB_TEST_LIVE=1`) |
| `crowd` | 12 681 | Живая толпа: 100 реальных тиков неподвижности кольца, танец ведьмы, разрядка крипера-опоздавшего, иммунитет пиглина к зомбификации |
| `acts` | 53 196 | Акты 1–5: ночная охота и самодельный микрофон, житель и реальные 3 волны рейда, гость бастиона, тишина Вардена, дракон (HOVER + смерть + Золотая строчка) |
| `gangs` | 57 266 | Соперничество банд + строка разбора, чемпион на солнце и КД 5 мин, реванш, выходы из `PENDING` (удар, таймаут 15с по `Clock`, уход 16+ м), дневная защита, длинный разбор с трофеем |
| `reputation` | 851 | Street Cred + свидетель + незнакомец, консолидированный проигрыш («Беги!» + Скорость I + снятие уважухи + кража + метка + ♛ + перемотка 12с → атака), 2 ступени пиглинов, 5 воров + крипер + конверсия пиглина, **кооп 0-dealt + кража у худшего**, очистка карт по `Clock` |
| `progression` | 1 858 | Консолидированный набор ворот (`strict`, 0 Респекта: Ифрит, Варден без микрофона, `skipChoice`), `soft`/`off`, взятка изумрудом, ранг банды-соперника, ворота Вардена и Дракона, суточный лимит фарма, эндермены после дракона, `StyleScreen` |
| `pvp` | 993 | PvP-вызов `FakePlayer`, перемирие от зомби, стили обоих участников, `forceEndForTest` |
| `coverage` | 359 | Кооп вход/нарушение (`violation()` → `forfeit`), `SoreLosers.tryRage` (детерминированный `Random`, ~50% HP, самооборона, 2× дроп) + `blast`, `GolemDefender.offended` + `tick()` + `chickenedOut` + `respect`, 2v1 + истечение 30с по `Clock`, взгляд эндермена + подсказки 500/1500, `ClipRecorder` WAV |
| `lang_en` | 5 188 | Смена языка на `en_us` без перезапуска клиента, проверка 14 страниц `GuideBook`, `StyleScreen`, `KeysScreen` (фейковые маскированные ключи), `CalibrationScreen`, `RapSettingsScreen`, аудит отсутствия сырых ключей |

---

## 2. Таблица переезда проверок (старые тесты → новые сценарии)

| Старый тест / метод | Куда переехали проверки | Изменения / ускорение |
| :--- | :--- | :--- |
| `ShowcaseTest` | `E2eScenario` (`e2e`) + `RapBattleGameTestSuite.runEnglishTextPass` (`lang_en`) | Сквозной баттл с `RB_TEST_WAV` и `RapSettingsScreen` сохранены; 10 промо-скриншотов убраны (правило 7: без кадров «на всякий случай»). |
| `GuideBookGameTest` | `GuideBookScenario` (`guidebook`) + `lang_en` | Логика выдачи/команды в `guidebook`; кадры страниц 1 и 8 и проверка вёрстки для `en_us` — в `lang_en`. |
| `CrowdGameTest` | `CrowdScenario` (`crowd`) | Вход в `TIMELINE` и выход в `RESULT` через `Battle.jumpToForTest(...)`; 100 реальных тиков неподвижности и 40 тиков взрывателя крипера сохранены живьём. |
| `ActsGameTest` | `ActsScenario` (`acts`) | Итоги Актов 1–5 переведены на `Battle.jumpToForTest(Phase.RESULT, ...)`; реальные 3 волны рейда и HOVER дракона сохранены. |
| `GangsGameTest` | `GangsScenario` (`gangs`) | Кулдауны чемпиона/реванша, таймауты `PENDING` и перемотка до куплета игрока переведены на `Clock.advanceForTest(...)` и `jumpToForTest`. |
| `ReputationGameTest` | `ReputationScenario` (`reputation`) | Проверки проигрыша объединены в `testLossConsolidated` (Беги! + снятие уважухи + кража + метка + ♛ + `Clock +12s` → атака). Добавлена проверка кооп 0-dealt и кражи у худшего (из 6.19). `advanceTimersForTest` / `expireTruceForTest` заменены на `Clock.advanceForTest`. |
| `ProgressionGameTest` | `ProgressionScenario` (`progression`) | Строгие ворота при 0 Респекта объединены в `testStrictGatesConsolidated`; итоги боёв — через `jumpToForTest`. |
| `PvpGameTest` | `PvpScenario` (`pvp`) | Перенесено 1:1 в единый мир с сокращённым ожиданием тиков (`20` вместо `60`). |
| `CoverageGameTest` | `CoverageScenario` (`coverage`) + `lang_en` | Добавлены недостающие проверки из 6.19 (`SoreLosers.tryRage`, `GolemDefender.offended` + `tick()`, истечение 30с 2v1 по `Clock`). Кадры `KeysScreen`/`CalibrationScreen` перенесены в `lang_en`. |
| `BattleClientTest` (не был в `fabric.mod.json`) | Удалён | Дублировал `ShowcaseTest` (камеры и bling). |
| Юнит-тесты (`src/test`) | `LangCompletenessTest` + `ScoringPerksAndStylesTest` | Добавлены проверка полноты всех ключей `rapbattle.*` в `ru_ru.json` и `en_us.json`, а также тест шума Вардена (`wardenNoiseAddsExtraMobDamage`). |

---

## 3. Что падало после переделки и почему

1. **`BattleManager.recent()` — баг в коде игры (`HashSet` → `LinkedHashSet`)**:
   - Проверка `2v1 brawl after battle must pull mob fan onto second attacker` падала (и в baseline `6.19`, и после переделки), так как `RECENT` хранил `attackers` в `HashSet`. При случайном `UUID` у `FakePlayer` он мог оказаться на индексе `1` вместо `0`, и `PlayerManager.getPlayer(attackers.get(1))` возвращал `null`. Заменено на `LinkedHashSet` в `BattleManager.java`.
2. **`acts` (`Hero of the Village`) — баг в тесте (`jumpToForTest` дефолт)**:
   - В `ActsScenario` вызов `jumpToForTest(Phase.RESULT)` сначала имел дефолт `knockout = false, margin = 15`, из-за чего `RaidBattle.onWin` считал победу нелетальной и не выдавал `Hero of the Village` и тотем. Дефолт `jumpToForTest(Phase)` исправлен на `knockout = true, 90 vs 0`.
3. **`coverage` (`violation()`) — баг в ожидании теста**:
   - В `LOBBY` (`view == null`) вызов `Battle.violation(p3)` через `forfeit` вызывает `abort`, не переводя фазу в `RESULT`. Добавлен переход в `TIMELINE` перед проверкой `violation()`, чтобы проверить полноценный `forfeit` → `Phase.RESULT`.
4. **`gangs` (`mob 1` сгорал на солнце / гонка с `offerOnApproach`) — баг в тесте**:
   - При дневном времени `mobs[1]` (обычный зомби с 0 побед) сгорал на солнце, а при `waitTicks(5)` после истечения `PENDING` `offerOnApproach` на кратном 10 тике успевал переоткрыть `PENDING` на расстоянии 4 блоков. Надета кожаная каска и моб поставлен на 9 блоков с `setAiDisabled(true)`.
5. **`progression` (`Dragon should exist in The End`) — особенность единого мира**:
   - В одном мире сценарий `acts` убивал дракона раньше, чем запускался `progression`. В `ProgressionScenario` добавлен спавн нового `EnderDragonEntity`, если живого дракона в Энде уже нет.

## 4. Архитектурные изменения

- **`dev.rapbattle.Clock`**: единый источник времени (`now()`, `today()`, `advanceForTest()`, `resetForTest()`), на который переведены все 14 классов с таймерами; удалены разрозненные `advanceCooldownsForTest`, `advanceTimersForTest`, `expireTruceForTest`.
- **`Llm.setOfflineStubForTest(boolean)`**: гарантирует отсутствие сетевых запросов во всех тестах (кроме `e2e` при `RB_TEST_LIVE=1`).
- **`Battle.jumpToForTest(Phase, ...)`**: единый вход для перехода в `TIMELINE`, `JUDGING` или `RESULT` (заменил `forceOutcomeForTest`).
- **`RapBattleGameTestSuite` + `GameTestSupport.resetScenario`**: один `TestSingleplayerContext` на все 10 групп с полным сбросом состояния (`BattleManager.clearAllForTest()`) и выделением отдельной чистой площадки на каждый сценарий.
