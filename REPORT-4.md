# Отчёт по части 4: Почерк МС и перки микрофонов

Ветка: `gemini/4-style` (от `gemini/3.5-fixes`).

## 4.1. Почерк МС
- **Модель и сохранение (`McStyle.java`, `Stats.java`)**:
  - Добавлен `enum McStyle` (`PUNCHER`, `METRONOME`, `PROVOKER`) с порогами Респекта `0 / 500 / 1500` и параметрами расчёта строки без особых случаев в `Battle` и `PvpBattle`.
  - В `Stats.Player` добавлено поле `style` (по умолчанию `"puncher"`) с `optionalFieldOf` в кодеке для совместимости со старыми мирами.
- **Формулы (`Scoring.java`)**:
  - `LineInput` расширен полями `meaning`, `flowStreakStart`, `baseOnBeatFlow`, `lowMeaningPenalty`, `rebuttalHeal`, `silenceForgiven`.
  - `RoundOutcome` учитывает `teamHeal` (лечение от ответок Провокатора) в `respectTeamAfter` (до 100).
  - В `Llm.roundContent` и `Fallback.extractTargets` поддержано 3 слова-мишени для Провокатора (`targetCount() == 3`), причём каждому игроку в `Battle.broadcastState()` отправляется его число мишеней.
- **Выбор стиля и отображение (`StyleScreen.java`, `RapSettingsScreen.java`, `BattleManager.java`, `ClientBattle.java`, `BattleView.java`, `BattleHud.java`)**:
  - В `/rapbattle` добавлена кнопка «Почерк МС» и экран `StyleScreen` с тремя карточками и отображением порога Респекта для закрытых стилей.
  - Выбор отправляется на сервер (`set_style`), сервер проверяет порог `respect >= unlockRespect` и сохраняет в `Stats`.
  - В `BattleView.Member` добавлено поле `style`, выводимое в подзаголовке карточки бойца (`FighterCard`) как в PvE, так и в PvP.

## 4.2. Перки трофейных микрофонов
- **Определение и не-стакуемость (`Trophies.java`)**:
  - Добавлен `TrophyPerk` (`WARDEN`, `ENDER_DRAGON`, `WITHER`, `ELDER_GUARDIAN`, `DEFAULT`), считывается из `offhand` через `DataComponentTypes.CUSTOM_DATA` (`rapbattle_trophy`) с фолбэком для старых трофейных микрофонов. Правая рука остаётся свободной для вызова.
- **Эффекты в бою (`Battle.java`, `PvpBattle.java`)**:
  - Варден: блокирует первую помеху моба за раунд (`MobTricks.apply` или первый взгляд Эндермена).
  - Дракон: увеличивает `revealBeats` на `+2` доли.
  - Иссушитель: прощает первую тихую строку за раунд (`silenceForgiven = true`, без `+5` урона).
  - Древний страж: расширяет окно «в бит» на `+0.05` доли.
  - Прочие: `+1` к бонусу стиля (в пределах лимита `+10`).
- **Тултип и разбор**:
  - В описание (`LoreComponent`) каждого выдаваемого микрофона добавлен локализованный перк на `ru_ru` и `en_us`.
  - При срабатывании перка в разборе (`BattleView.Hit`) добавляется соответствующая строка (например, «Микрофон Иссушителя: тишина прощена»).

## 4.3. Проверка
- `gradlew build test --no-daemon` — `BUILD SUCCESSFUL` (включая новые тесты `ScoringTest.stylesProduceDistinctButCloseDamageWithin15Percent` и `ScoringTest.trophyPerksApplySilenceForgivenessBeatWindowAndStyleBonus`).
- Обновлены `README.md`, `README_EN.md`, `ru_ru.json`, `en_us.json` (ключи синхронизированы).
- Визуальный геймтест (`runClientGameTest`) в текущем окружении не запускался (требуется задача/WAV-клиент), проверены unit-тесты и полная сборка мода.
