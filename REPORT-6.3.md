# Отчёт по части 6.3: Акт 3 — Незер

Ветка: `gemini/6.3-act3` (от `gemini/6.2-act2`).

## Реализация

1. **Бастион и Пиглин-Брут (`BastionBattle.java`, `PiglinBrainMixin.java`, `PlayerStatus.java`, `Battle.java`)**:
   - Проверка структуры `StructureKeys.BASTION_REMNANT` через `world.getStructureAccessor().getStructureContaining(pos, structure)`.
   - В `BastionBattle.tick` свободный брут в радиусе 16 блоков вызывает игрока на обычный баттл (без мгновенного агра).
   - `PiglinBrainMixin` подавляет злость пиглинов во время баттла (`BattleManager.byPlayer != null`) и отменяет `onGuardedBlockInteracted` (сундуки и золото в бастионе) при активном статусе `bastion_guest`.
   - Общий менеджер таймеров `PlayerStatus` выводит в actionbar строку «Гость бастиона 9:59» на 10 минут после победы над брутом; при проигрыше все пиглины и бруты в радиусе 32 блоков получают `setTarget(loser)`.
   - *Упрощение*: список зрителей в радиусе 24 блоков использует стандартный подбор толпы (`Crowd`) без отдельного фильтра только по пиглинам, а их не-агр во время баттла гарантируется через `PiglinBrainMixin`.

2. **Крепость Незера (`Acts.java`, `Battle.java`)**:
   - Ифрит: при чистой победе (`lethal`) дополнительно выпадают `2–3` огненных стержня, при малой победе — `1` стержень.
   - Иссушенный скелет: при нокауте или перевесе `40+` шанс черепа `30%`, при перевесе `25–39` — `10%` (константы в `Acts`), при выпадении — золотая строка в чат.

## Проверка
- `gradlew build test --no-daemon` — `BUILD SUCCESSFUL` (включая `ActsTest.fortressRewardsMatchBlazeAndWitherSkullRules`).
- **Как проверить руками в игре**:
  1. **Бастион**: `/execute in minecraft:the_nether run tp @s 0 80 0`, `/locate structure minecraft:bastion_remnant`, `/tp` в бастион. Подойди на ≤ 16 блоков к бруту — придёт вызов. Победи — в actionbar появится таймер `Гость бастиона 9:59`, сундуки и золотые блоки не агрят пиглинов. Проиграй (или ударь повторно) — пиглины в 32 блоках нападут.
  2. **Крепость**: `/summon blaze` и `/summon wither_skeleton` — проверь бонусные стержни и выпадение черепа с золотым сообщением при перевесе 25+ / 40+.
