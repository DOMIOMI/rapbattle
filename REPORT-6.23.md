# Отчёт 6.23 — Защита игрока во время баттла (огонь, вода, эффекты мобов)

**Ветка:** `gemini/6.23-protection` (от `gemini/6.22-review-fixes`)
**Прогон тестов:** `.\gradlew.bat test runClientGameTest --offline` (`RB_TEST_WAV=...\verse.wav`, без `RB_TEST_ONLY`) — **PASSED** (`failures=0`, 10/10 сценариев `RapBattleGameTestSuite` за `205122 ms` + все JUnit-тесты `:test`).

---

## 1. Отмена баттла только от лавы и пустоты
- В `BattleManager.java` введена константа `BATTLE_ABORT_ENV_DAMAGE = Set.of(DamageTypes.LAVA, DamageTypes.OUT_OF_WORLD);` (заменила `MINOR_ENV_DAMAGE`).
- `ServerLivingEntityEvents.AFTER_DAMAGE` отменяет баттл без последствий (`abortNoPenalty` + `rapbattle.battle.abort_env`) **только** при получении урона из `BATTLE_ABORT_ENV_DAMAGE`.

## 2. Блокировка урона во время баттла
- В `ServerLivingEntityEvents.ALLOW_DAMAGE` для участника баттла/PvP (`BattleManager.isBattling(p)`) пропускается **только**:
  - `DamageTypeTags.BYPASSES_INVULNERABILITY` (включая `/kill` — `GENERIC_KILL` и `OUT_OF_WORLD`);
  - `BATTLE_ABORT_ENV_DAMAGE` (`DamageTypes.LAVA`, `DamageTypes.OUT_OF_WORLD`);
  - `Battle.RAP_DEATH` (собственный урон мода при проигрыше в `punishPlayers` / `bossFinisher`).
- Весь остальной урон (огонь `ON_FIRE`/`IN_FIRE`/фаерболы, утопление `DROWN`, удушье `IN_WALL`, заморозка `FREEZE`, падение `FALL`, яд/иссушение/магия, кактус, ягоды, сталагмит, магма-блок, молния, взрывы, голод, атаки мобов/игроков/снаряды) блокируется с 0 урона и не отменяет баттл.

## 3. Защита от вредных эффектов
- Добавлен `LivingEntityProtectionMixin` (`LivingEntity.canHaveStatusEffect`): отклоняет любые новые или продлеваемые эффекты с `StatusEffectCategory.HARMFUL` для `BattleManager.isBattling(sp)`, если не активен `BattleManager.isModForcedStatusEffect()`.
- Добавлен явный обход `BattleManager.forceStatusEffect(LivingEntity, StatusEffectInstance)` (через `ThreadLocal<Boolean> MOD_FORCED_EFFECT`) по аналогии с `forceMobTarget`.
- Все наложения эффектов на игрока в `Battle.java` (`WITHER` в `bossFinisher`, `WEAKNESS`, `NAUSEA`, `SPEED` в `punishPlayers`) переведены на `BattleManager.forceStatusEffect`.
- Прежние эффекты, полученные до старта баттла (полезные и вредные), сохраняются и не продлеваются внешними источниками.

## 4. Огонь и воздух под водой
- Добавлен `EntityProtectionMixin` (`Entity.setFireTicks`, `Entity.setOnFireForTicks`, `Entity.setOnFireFor`, `Entity.setAir`): во время баттла блокирует поджигание (`fireTicks > 0` / `ticks > 0` / `seconds > 0`) и снижение воздуха (`air < maxAir`).
- При старте баттла (`Battle.addPlayer` и конструктор `PvpBattle`) вызывается `p.extinguish()` и `p.setAir(p.getMaxAir())`; в `Battle.tick()` и `PvpBattle.tick()` поддерживаются `fireTicks == 0` и `air == maxAir`, чтобы после завершения или отмены баттла игрок не вспыхнул и не начал задыхаться мгновенно.

## 5. Аудит вредных действий мобов (`MobProfiles` + ванильные механики 1.21.11)

| Моб | Чем вредит | Что теперь происходит во время баттла |
|---|---|---|
| **Ифрит (`blaze`)** | Огненные шары, поджог (`fireball`, `setOnFireFor`) | Урон = 0 (`ALLOW_DAMAGE`), не поджигает (`EntityProtectionMixin`), баттл идёт |
| **Гаст (`ghast`)** | Взрывные огненные шары, поджог | Урон по игроку = 0 (`ALLOW_DAMAGE`), не поджигает (`EntityProtectionMixin`) |
| **Страж (`guardian`)** | Луч лазера (`indirectMagic`), шипы | Урон = 0 (`ALLOW_DAMAGE`), воздух под водой полный (`EntityProtectionMixin`) |
| **Древний страж (`elder_guardian`)** | Усталость (`MINING_FATIGUE`), луч, шипы, утопление | `MINING_FATIGUE` блокируется (`LivingEntityProtectionMixin`), урон = 0, воздух полный |
| **Варден (`warden`)** | Тьма (`DARKNESS`), звуковая волна, ближний бой | `DARKNESS` блокируется (`LivingEntityProtectionMixin`), урон = 0 |
| **Иссушенный скелет (`wither_skeleton`)** | Иссушение (`WITHER`), удар мечом | `WITHER` блокируется (`LivingEntityProtectionMixin`), урон = 0 |
| **Иссушитель (`wither`)** | Черепа, иссушение (`WITHER`), взрывы | `WITHER` блокируется, урон по игроку = 0 (`ALLOW_DAMAGE`) |
| **Ведьма (`witch`)** | Бросок зелий вреда, яда, замедления, слабости | Вредные эффекты блокируются (`LivingEntityProtectionMixin`), урон зелий = 0 |
| **Пещерный паук (`cave_spider`)** | Укус + яд (`POISON`) | `POISON` блокируется, урон = 0 |
| **Шалкер (`shulker`)** | Снаряд + левитация (`LEVITATION`) | `LEVITATION` блокируется, урон = 0 |
| **Зимогор (`stray`)** | Стрелы с замедлением (`SLOWNESS`) | `SLOWNESS` блокируется, урон стрелы = 0 |
| **Болотник (`bogged`)** | Стрелы с ядом (`POISON`) | `POISON` блокируется, урон стрелы = 0 |
| **Кадавр (`husk`)** | Удар + голод (`HUNGER`) | `HUNGER` блокируется, урон = 0 |
| **Крипер (`creeper`)** | Взрыв (и трус-перемирие в толпе) | В толпе перемирие (`Crowd`), урон взрыва по игроку = 0 (`ALLOW_DAMAGE`) |
| **Вихрь (`breeze`)** | Ветряной заряд (урон + отталкивание) | Урон по игроку = 0 (`ALLOW_DAMAGE`), отталкивание возвращается якорем сцены (6.22) |
| **Эндермен (`enderman`)** | Ближний удар при взгляде | Урон = 0 (`ALLOW_DAMAGE`), взгляд учитывается механикой `onGaze` |
| **Хоглин / Зоглин (`hoglin`, `zoglin`)** | Удар + подкидывание | Урон = 0 (`ALLOW_DAMAGE`), горизонтальный сдвиг возвращается якорем сцены |
| **Разоритель (`ravager`)** | Удар, рывок, рёв | Урон = 0 (`ALLOW_DAMAGE`), отталкивание возвращается якорем сцены |

## 6. Проверка тестами
В `CoverageScenario.testBattleProtection623` проверены:
- огненный шар ифрита и `setOnFireFor` → не горит, 0 урона, баттл идёт;
- погружение в воду на 35 тиков + `MINING_FATIGUE` древнего стража → воздух полный, урон = 0, усталость не наложилась, баттл идёт;
- зелье вреда и яда ведьмы, тьма Вардена → эффекты не наложились, 0 урона;
- прежние эффекты до баттла (`STRENGTH` и `SLOWNESS`) сохраняются, причём `SLOWNESS` не продлевается внешним наложением;
- огонь под ногами (`inFire`) → 0 урона, не горит до и после конца баттла;
- проигрыш в баттле → `WEAKNESS`, `NAUSEA`, `SPEED` от мода накладываются;
- лава (`lava`) и пустота (`outOfWorld`) → урон проходит, баттл отменяется без последствий (`♛` и кражи нет);
- `/kill` (`genericKill`, `BYPASSES_INVULNERABILITY`) → урон проходит.

## Коммиты на ветке `gemini/6.23-protection`
- `6b2f0d7` — 6.23: block all battle damage except lava/void/bypasses_invuln and abort only on lava/void
- `10f3075` — 6.23: block harmful status effects, fire ignition, and air loss during battle
- `e1a9076` — 6.23: add CoverageScenario.testBattleProtection623
- `13a83c1` — 6.23: hook setOnFireFor/setOnFireForTicks and use fresh zombie in lava/void test
