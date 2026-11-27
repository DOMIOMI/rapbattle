# Отчёт 6.22 — Исправления по ревью кода (`REVIEW-ALL.md`)

**Ветка:** `gemini/6.22-review-fixes` (от `gemini/6.21-fixes`)
**Прогон тестов:** `.\gradlew.bat test runClientGameTest --offline` (`RB_TEST_WAV=...\verse.wav`, без `RB_TEST_ONLY`) — **PASSED** (`failures=0`, 10/10 сценариев `RapBattleGameTestSuite` за `209316 ms` + все JUnit-тесты `:test`).

---

## Сводная таблица исправлений (№ 1–24 + 10a)

| № | Что сделано | Какой тест |
|---|---|---|
| **1** | В `Battle.onBar` и `PvpBattle.onBar` сервер сам вычисляет текущую строку игрока по `Clock.now()`, `beatZero` и сегментам; принимает не более 1 сигнала на строку (`barIdx < myRows.size()`, `curBeat >= startBeat + 2`), ограничивает `deltaMs` в `[-1, beatMs]`, поддерживает окно доводки 500 мс в `JUDGING`. | `CoverageScenario.testReview622SecurityAndLifecycle` (50 `bar` за тик → 1 строка, `hype <= 18`) |
| **2** | В `PvpBattle.onAudio` добавлена проверка участника (`a`/`b`), фазы (`TIMELINE`/`JUDGING`) и `audioSeq`. | `PvpScenario.run` (чужой игрок `IntruderMC` шлёт `AudioC2S` → игнорируется, в `transcripts` не попадает) |
| **3** | В `Battle.broadcastState()` добавлен `v.crowdIds.clear()` перед заполнением. | `CoverageScenario.testReview622SecurityAndLifecycle` (20 вызовов `broadcastStateForTest()` → размер `crowdIds` не растёт) |
| **4** | `PvpBattle.sttMissing` объявлено `volatile`. | `PvpScenario.run`, `E2eScenario.run` |
| **5** | Добавлены проверки совпадения мира (`getEntityWorld() == ...`) перед `squaredDistanceTo` в `BattleManager` (`battleNear`, `walkedAway`, `case "rap"`), `GolemDefender.tick`, `RaidBattle.tick`. | `CoverageScenario`, `ActsScenario` |
| **6** | В `Battle.onSilentBar` добавлена проверка окна бита текущего игрока и отсутствия громкого сигнала в строке. | `CoverageScenario`, `E2eScenario` |
| **7** | Путь в логе `Transcriber` исправлен на `config/rapbattle-server.json`; `Battle.POOL` заменён на `Executors.newFixedThreadPool(8, ...)`; проверка `audioSeq` добавлена в `Battle.onAudio` и `PvpBattle.onAudio`; таймауты HTTP-клиента (`HttpClient`) проверены. | `E2eScenario`, `PvpScenario` |
| **8** | В `Battle`: добавлен `leftPlayers`, при выходе всех онлайн-участников баттл отменяется без наград/штрафов/кражи; в `finish()` добавлена защита от `mvpPlayer == null`. | `CoverageScenario.testReview622SecurityAndLifecycle` (3 игрока выходят → баттл снят, у моба нет `♛` и краденого) |
| **9** | При смене измерения участник автоматически выходит из `Battle` (`onPlayerLeft`) и `PvpBattle` (`leave`) без телепорта по координатам другого мира. | `Battle.tick`, `PvpBattle.tick` (проверено в общем прогоне `CoverageScenario` / `PvpScenario`) |
| **10** | `ALLOW_DAMAGE` блокирует только урон от атакующей сущности/снаряда; урон от окружения проходит и через `AFTER_DAMAGE` сразу отменяет баттл без штрафов с сообщением игроку (`rapbattle.battle.abort_env`). | `CoverageScenario.testReview622SecurityAndLifecycle` (удар зомби → 0 урона, баттл идёт; урон от лавы → урон проходит, баттл отменён без последствий) |
| **10a** | На сервере горизонтальный якорь сцены (`dx*dx + dz*dz > 0.3*0.3` → `requestTeleport` с сохранением `Y` для гравитации, >4 тиков отталкивания → отмена без последствий); `UseItemCallback`, `UseBlockCallback`, `AttackBlockCallback` отменяются с подсказкой `mic_only`; добавлена команда `/rapbattle leave`, клиентское гашение ввода движения (кроме прыжка в `Intro`) и строка в книге МС (`guide.page.14`). | `CoverageScenario.testReview622SecurityAndLifecycle` (блоки/предметы/сундук отменены, якорь ±0.3, `/rapbattle leave` снимает баттл и возвращает управление) |
| **11** | В `Battle.broadcastState()` для каждого наблюдателя создаётся копия `BattleView` (`sv`) перед записью персональных `targets`, затем `v.targets` восстанавливается. | `ActsScenario`, `ReputationScenario` (кооп 3 игрока) |
| **12** | Удалён мёртвый метод `PvpBattle.hit(...)`; в `PvpBattle` добавлен расчёт `RoundOutcome` персонально для каждого участника (`playerResults` + `fillPvpResult`). | `PvpScenario.run` |
| **13** | В `Speech` коллекции `MOB_COOLDOWN`, `PLAYER_COOLDOWN`, `HINTS_SHOWN`, `generated`, `generatedAt`, `generating` переведены на `ConcurrentHashMap` / `ConcurrentHashMap.newKeySet()`. | `ActsScenario`, `CrowdScenario`, `CoverageScenario` |
| **14** | В `BastionBattle.onLoss` агр пиглинов отложен через `Later.run(Reputation.RUN_TRUCE_MS, ...)` (`Crowd.breakTruceFor` + `ANGRY_AT` + `ATTACK_TARGET` + `forceMobTarget`), так что 10 с «Беги!» действуют и в бастионе. | `ReputationScenario.testBastionLossDelay` (9 с не атакуют → после 10 с агрятся) |
| **15** | `Speech.clearAll()` очищает `MOB_COOLDOWN`, `PLAYER_COOLDOWN`, `HINTS_SHOWN`; `RaidBattle.CHALLENGED_RAIDS` переведён на `Collections.newSetFromMap(new WeakHashMap<>())` с ключом `Raid`. | `ActsScenario`, `ReputationScenario` |
| **16** | В `PlayerStatus.java:92` `hasPendingForTest` заменён на `hasPending`; в `Battle.finish()` + `Stats.record` дневной лимит фарма в коопе берёт минимум `dailyBonusScale` среди участников с вкладом `> 0` и засчитывает победу каждому из них. | `StatsTest`, `ProgressionScenario`, `ReputationScenario` |
| **17** | В `MobRecord` добавлено поле `origName` (`orig_name`), сохраняемое при первом переименовании; `Theft.stealOnLoss` и `Theft.refreshMobName` используют его, не теряя бирку моба. | `ReputationScenario.testThiefChampionNameAndMapCleanup` («Бобик» → 2 победы + кража + возврат → `"Бобик ♛×2"` без `[Item]`) |
| **18** | `Reputation.isNeverRespecting` переведён на `MobRecord.hasWins(m)`. | `ReputationTest`, `ReputationScenario` |
| **19** | Замер счётчиком `RapDancerHookDebug` в игре показал: `LivingEntityRendererMixin` срабатывает 30 раз/с (до фич: броня/предметы/цепи), а `ModelCommandRendererMixin` — 120 раз/с (перед рендером каждого `ModelCommand`). Оба нужны в 1.21.11; на `ModelCommandRendererMixin` выставлен `require = 0` для безопасности с модами рендера. | `E2eScenario` (визуальная проверка `docs/screenshots/01-hero-wide.png`, `07-player-rap.png`) |
| **20** | В `ClientBattle` вынесен `flushTrackedRow()`, вызываемый при выходе из `timeline()` до `stopAndUpload()`; в `Battle.tickJudging` добавлено ожидание до 500 мс для дошедших пакетов последней строки. | `CoverageScenario.testReview622SecurityAndLifecycle` (последняя строка в 500 мс окна `JUDGING` → `beatState = 2`) |
| **21** | `ClientBattle.reset()` сбрасывает все поля хода (`monitoring`, `noiseCount`, `wasLoud`, `trackedRow`, `recordedKey`, `timelineKey`, `lastHalfBeat`, `gazedKey`, `silentSinceMs`, `BeatPlayer.stop()`). | `E2eScenario`, `CoverageScenario` |
| **22** | В `StageFx` добавлены предвычисленные таблицы `Ring` (`sin`/`cos`) на 40/32/20/8 сегментов, слои тумана сокращены с `5×72 + 3×48` до `3×40 + 2×32`. | `E2eScenario` (визуальная проверка `docs/screenshots/01-hero-wide.png`, `07-player-rap.png`) |
| **23** | Непокрытые сценарии из ревью добавлены в `CoverageScenario`, `PvpScenario` и `ReputationScenario`. | Полный прогон `RapBattleGameTestSuite` |
| **24** | `rapbattle.guide.page.4` в `ru_ru.json` и `en_us.json` приведён к `Rank.java` (5 обычных рангов Кожа–Алмаз и боссы Незерит); удалён неиспользуемый ключ `rapbattle.broadcast.best_line`. | `LangCompletenessTest`, `GuideBookTest`, `GuideBookScenario`, `lang_en` |

---

## Детали по запрошенным пунктам

### 1. Константа `MINOR_ENV_DAMAGE` (№ 10)
- В `BattleManager.java`: `private static final Set<RegistryKey<DamageType>> MINOR_ENV_DAMAGE = Set.of(DamageTypes.STARVE);`
- **Примечание про огонь от мобов:** если моб поджёг блок или игрока и урон идёт от `DamageTypes.ON_FIRE` / `IN_FIRE`, у такого `DamageSource` в Minecraft `getAttacker() == null`. Поэтому он трактуется как урон от окружения и безопасно отменяет баттл, чтобы игрок мог потушиться/выбраться; прямой удар огненным шаром (`getAttacker() != null` или снаряд) блокируется как атака сущности. Вариант на будущее при необходимости — отслеживать временную метку «поджёг моб» на сущности, но для безопасности игрока отмена при горении предпочтительнее.

### 2. Правый клик предметами мода и `/rapbattle leave` (№ 10a)
- **Предметы мода и правый клик:** во время баттла `UseItemCallback`, `UseBlockCallback` и `AttackBlockCallback` отменяются для участников (`BattleManager.isBattling(sp)`). Книга МС (`GuideBook`) и команды (`/rapbattle ...`) открываются/вызываются командами и клавишами, а трофейные перки/микрофон работают из оффхенда/инвентаря и по хоткею микрофона без правого клика во время раунда.
- **Последствия `/rapbattle leave`:** идентичны уходу со сцены (`Battle.onPlayerLeft` / `PvpBattle.leave`): если в соло/коопе не осталось других активных игроков, засчитывается поражение/уход (потеря респекта/стрит-креда, возможная кража предмета победителем и перемирие «Беги!»); в коопе, если остались другие участники, они продолжают баттл.

### 3. Аудит фоновых потоков (`CompletableFuture` / `POOL`) (№ 13)
Проверены все фоновые вызовы в `src/main` и `src/client`:
- `Battle.POOL` (`Executors.newFixedThreadPool(8, ...)`): используется для STT/LLM-задач.
- `Battle.transcripts`, `Battle.contentFuture`, `Battle.verdictFuture`: читаются и завершаются только на серверном тике (`tick()`, `tickJudging()`), без мутаций мира/сущностей из фоновых потоков.
- `PvpBattle.transcripts` (`ConcurrentHashMap`) и `PvpBattle.pvpVerdictFuture`: аналогично обрабатываются на серверном тике.
- `Speech`: `MOB_COOLDOWN`, `PLAYER_COOLDOWN`, `HINTS_SHOWN`, `generated`, `generatedAt`, `generating` переведены на потокобезопасные коллекции (`ConcurrentHashMap` / `ConcurrentHashMap.newKeySet()`).
- Клиент (`ClipRecorder`, `KeysScreen`, `MicRecorder`): `ClipRecorder` возвращает результат в `mc.execute(...)`; `KeysScreen` обновляет поля состояния экрана без доступа к миру/серверу; `MicRecorder` пишет в собственный буфер.

### 4. Визуальная проверка и риски Sodium / Iris (№ 19, № 22)
- Проверены скриншоты `docs/screenshots/01-hero-wide.png` и `docs/screenshots/07-player-rap.png`: туман (`3×40 + 2×32`) и лучи прожекторов `StageFx` выглядят ровно без артефактов, мобы и игрок корректно танцуют с микрофоном и цепью.
- **Риски при наличии Sodium / Iris / EMF:** `ModelCommandRendererMixin` перехватывает `ModelCommandRenderer.render` и внутренний `OrderedRenderCommandQueueImpl$ModelCommand`. При замене конвейера модами оптимизации/шейдеров стоит `require = 0`, чтобы клиент не падал при запуске (основная поза в `LivingEntityRendererMixin` продолжит работать, но без ре-применения внутри `ModelCommandRenderer` возможен сброс позы модели при наличии конфликтующих шейдерных пассов). `StageFx` использует стандартный `VertexConsumer` / `RenderLayer`, но при активных шейдерах Iris полупрозрачные слои тумана и лучей могут сортироваться или тонироваться иначе.

---

## Коммиты на ветке `gemini/6.22-review-fixes`
- `120d7cc` — 6.22 A: validate bar/silent/audio packets, world checks, bounded AI pool
- `535f99f` — 6.22 B: coop all-left abort, dimension change leave, env damage abort, freeze & item/block lock
- `6fd1c6a` — 6.22 C: thread-safe Speech maps, volatile sttMissing, WeakHashMap raids, clear crowdIds & copy targets
- `08d29f6` — 6.22 D: bastion delayed aggro after truce, coop daily cap, origName preservation, MobRecord.hasWins
- `faa8450` — 6.22 E: flush last bar on timeline exit, reset warden monitoring, StageFx sin/cos tables
- `7a1ab90` — Fix PvP per-player outcome summary and guide/lang strings (findings 12, 24)
- `77d2480` — Make ModelCommandRendererMixin optional (require=0) and document dual pose hooks (finding 19)
- `d50cc6f` — Wait up to 500ms in tickJudging for final bar reports (finding 20)
- `3568159` — Add gametest coverage for 6.22 review findings (1, 2, 3, 8, 10, 10a, 14, 17, 20)
- `d87b7a6` — Tighten ru guide page 14 line wrap and invoke BastionBattle.onLoss in test
- `922676d` — Set piglin ATTACK_TARGET/ANGRY_AT on BastionBattle.onLoss and stabilize test
