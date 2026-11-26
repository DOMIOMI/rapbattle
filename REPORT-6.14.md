# Отчёт 6.14: гайдбук в игре — «Книга МС»

Ветка: `gemini/6.14-guidebook` (от `gemini/6.13-review`).

## Что реализовано

1. **Предмет книги (`GuideBook.java`)**
   - Ванильная `written_book` с компонентами:
     - `WRITTEN_BOOK_CONTENT`: заголовок `Книга МС` / `MC Handbook` (`rapbattle.guide.title`), автор `DOMI`, `generation = 0`, `resolved = true`, 14 страниц из `rapbattle.guide.page.1`..`rapbattle.guide.page.14`.
     - `CUSTOM_DATA`: `{rapbattle_guide: 1}`.
   - Первая строка каждой страницы (заголовок) выделена тёмно-синим жирным (`Formatting.DARK_BLUE`, `Formatting.BOLD`), основной текст — чёрный (`Formatting.BLACK`).

2. **Способы получения**
   - **Первый вход в мир**: в `Stats.Player` добавлено поле `guideGiven` (с дефолтом `false` в кодеке, чтобы старые миры грузились без ошибок). При `ServerPlayConnectionEvents.JOIN` вызывается `GuideBook.giveOnFirstJoin(player, server)` — кладёт книгу в инвентарь (или бросает под ноги, если инвентарь полон) один раз на мир. Повторный вход книгу не дублирует.
   - **Команда `/rapbattle guide`**: зарегистрирована в `RapBattleClient` и обрабатывается на сервере в `BattleManager` (`ActionC2S` `"guide"` -> `GuideBook.give(p)`) — всегда выдаёт книгу на текущем языке игрока (`Lang.of(player)`).
   - **Кнопка «Книга МС» в `/rapbattle`**: добавлена в `RapSettingsScreen` рядом с кнопкой «Почерк МС» (активна в мире).
   - **Крафт и рецепт**: добавлен бесформенный рецепт `data/rapbattle/recipe/guide_book.json` (`minecraft:book` + `minecraft:note_block` -> `written_book` с `rapbattle_guide: 1` и переводимыми страницами) и достижение `data/rapbattle/advancement/recipes/guide_book.json` с триггером `minecraft:tick`, открывающее рецепт сразу при входе.

3. **Сверка содержания с кодом (14 страниц)**
   - **Клавиши и команды**: проверены по `RapBattleClient` и `BattleManager`: `[R]` — вызов/смена рифмы, `[Y]`/`[N]` — принятие/отказ приглашений и PvP, `[1–3]` — выбор темы в лобби; команды `/rapbattle`, `/rapbattle guide`, `/rapbattle style`, `/rapbattle legend`, `/rapbattle clip`.
   - **Почерки МС (`McStyle`)**: Панчер (`0`), Метроном (`500`), Провокатор (`1500`).
   - **Акт 1 (`Acts`)**: ×2 лут и ×1.5 опыта ночью только с одиночных мобов 1–2 ранга.
   - **Акт 2 (`Acts`, `RaidBattle`)**: скидка у жителя раз в игровой день (24 000 тиков), капитан рейда вызывает на последней волне, победа завершает рейд и даёт Тотем.
   - **Акт 3 (`BastionBattle`, `Acts`)**: 10 мин «Гостя бастиона» (сундуки/золото не злят пиглинов), ифриты — стержни, визер-скелеты — черепа.
   - **Акт 4 (`Battle`, `SculkShriekerMixin`)**: эндермены — жемчуг (не смотреть в глаза именно на их ходе), Варден — до 2 побед, тишина на его ходе, 15 мин «Затишья» для крикунов в 48 блоках.
   - **Акт 5 (`DragonBattle`)**: кристаллы в 192 блоках, удар пустой рукой, до 2 побед (можно командой), `/rapbattle legend`.
   - **Банды, чемпионы и PvP (`Gangs`, `BattleManager`, `PvpBattle`)**: вражеская банда сильнее на +1 ранг (до 5), чемпион ♛ ищет игрока с серией 3+, реванш при малом отрыве (< 25 без нокаута), PvP — удар пустой правой рукой по игроку (`[Y]`/`[N]`, 1 раунд, перемирие мобов вокруг).

## Проверки

- **Юнит-тест `GuideBookTest`**: проверяет наличие заголовка и всех 14 страниц в `ru_ru.json` и `en_us.json` и ограничение длины (`<= 240` символов).
- **Клиентский геймтест `GuideBookGameTest`**:
  - первый вход в новый мир выдаёт 1 книгу и ставит `guideGiven = true`;
  - повторный вход (`giveOnFirstJoin`) вторую книгу не выдаёт;
  - `/rapbattle guide` выдаёт книгу;
  - на клиенте все 14 страниц проверены через `textRenderer.wrapLines(..., 114).size() <= 14` в обоих языках;
  - сняты и визуально проверены кадры `0021_guidebook-page-1.png` и `0022_guidebook-page-8-act2.png` на `ru_ru` и `en_us` (текст не вылезает за страницу).
- `.\gradlew.bat build test runClientGameTest --offline` (`RB_TEST_LANG=ru_ru`) — `BUILD SUCCESSFUL`.
- `.\gradlew.bat runClientGameTest --offline` (`RB_TEST_LANG=en_us`) — `BUILD SUCCESSFUL`.
