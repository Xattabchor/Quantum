# Static Room A/B — изолированный эксперимент, не игровая сборка

## Контрольные точки и текущая версия

- **Стабильная игра:** `Quantum_Sym9_DrawCalls.jar`, commit
  `73d43ee42dd9d116ad2b3780deff900bbb12d8cd`.
- **Новый режим v1 (5 draws):** `Quantum_StaticRoom_Test.jar`, исходники и артефакты
  в commit `ac57d8946257b44b349a85509b9b7b56def852c9`.
- **Продолжаем новый режим v2 (3 draws):** `Quantum_StaticRoom_Merged.jar`.
  Новый artifact и MIDlet name не перезаписывают оба предыдущих JAR.

Файлы контрольных точек, размеры и SHA-256 закреплены в
`configs/render-checkpoints.json`. Проверка только читает файлы, ничего не
восстанавливает и не сбрасывает: `python scripts/verify_render_checkpoints.py`.
Сборщик запускает её перед новой сборкой. Для отката на телефоне достаточно
старого JAR; для исходников v1 использовать указанный commit **на текущей ветке**,
сначала сохранив новые изменения. `git reset --hard` и смена ветки не нужны.

v2 объединяет только соседние статические REPLACE-пакеты с тем же RawImage,
режимом и perspective flag. Faces конкатенируются в прежнем порядке материалов,
а не сортируются по текстуре. Animation/MODULATE и разные состояния — барьеры.
Если общий пакет превышает vertex budget или не представим без потерь в short,
пакеты остаются раздельными. Все 5 исходных материалов и все 4750 треугольников
сохранены. В 96 poses ordered XYZ/UV/RGBA/texture/depth/camera witnesses совпали
с **исходниками v1 из immutable commit**, не с двумя сборками нового алгоритма.


## Запуск на телефоне

`downloads/Quantum_StaticRoom_Merged.jar` — отдельный MIDlet **Quantum Static Room Merged**
с другим vendor/name. Он не запускает `Main`/`GameScreen`, не открывает сохранения
и не пишет RMS. Основной `Quantum_Sym9_DrawCalls.jar` не изменён.

- `0`: A **cached world** ↔ B **projected painter**.
- `4`/`6`: поворот; `2`/`8`: движение; `7`/`9`: стрейф; `#`: выход.
- Движение привязано ко времени, не к FPS. Это свободная камера без столкновений.
- Сравнивать стоя на одном месте: отпустить движение, подождать 5–10 секунд в A,
  записать FPS, нажать 0, снова дать прогреться и записать B. Сделать A/B/A.
- Сообщить модель телефона, реализацию/настройки эмулятора (если используется),
  FPS A/B и различия картинки. Не сравнивать числа при разных направлениях камеры.

**Оба режима намеренно показывают только первую комнату sym9:** без NPC/AI,
неба, дождя, оружия, оптики, квестов и полного HUD. Это не ускоренная «игра с
вырезанным gameplay», а отдельный тест рендера. Общий FPS игры из него не следует.

## Реализация

Новый код находится только в `experiments/static-room/src`, не в production `src`.

`StaticRoomMesh` строит world-space native VertexBuffer и TriangleStripArray после
загрузки baked lightmap. Native позиции, UV и цвета загружаются один раз. В кадре
меняются камера и ссылка на заранее загруженный кадр анимированной текстуры.
Java-проекции вершин, сортировки граней и сборки экранных пакетов в A нет.
Оба режима теста используют полный Canvas, FOV 74, pixelsQ=2, persQ=4, fogQ=2
(baked color quality), mipMapping=false. Это фиксированные условия standalone
теста, не чтение настроек сохранённой игровой сессии; FPS напрямую с полным
GameScreen сравнивать нельзя. Значение DIST и материалы берутся из level1.txt.
Вывод обоих режимов через M3G offscreen Image → Canvas drawImage; отсутствует
обязательный финальный readback в массив CPU. Это сравнение двух путей в целом,
а не изолированный benchmark одной операции.

Все пять материалов сохранены, включая mode7 river/MODULATE. Opaque/cutout идут
с depth test/write, river — после них с depth test без depth write. Quad strip
ABD/DBC сохраняет диагональ ABD/BCD. Mode9 использует baked gray; mode13/RGB
и LQ clamp покрыты отдельными контрактными тестами. Динамического света нет.

Позиции не округляются для short: используется точный общий шаг (GCD) плюс bias.
Непредставимые без потерь позиции, неподдерживаемые режимы, NPOT/слишком большие
текстуры, превышение бюджетов приводят к явной ошибке, а не исчезновению граней
или понижению качества. Ограничения: 16384 вершины на часть, 128 частей,
262144 texels суммарно на комнату с анимацией, один room cache до dispose.

### Почему ещё нельзя включить в полной игре

- Буфер глубины и группировка материалов не эквивалентны painter ordering и
  legacy `addsz`; в A `addsz` не воспроизводится. Геометрическое перекрытие
  деревьев/поверхностей нужно проверить глазами.
- Настоящая 3D-перспектива текстуры отличается от старого `TexturingPers.move`.
  Исходные UV сохранены, **pixel equivalence не заявляется**.
- Legacy integer projection и fixed-point rounding не повторяются точно;
  общий focal shift соответствует формуле при равных distX/distY.
- Far clipping нативный, в B — legacy polygon culling. Значение DIST взято из
  того же конфига, но семантика граничных полигонов различается.
- Порталы, многокомнатная видимость, NPC/прозрачность и weather ещё не интегрированы.
- A и B держат собственные native caches для переключения. Это дополнительная
  память теста, не измерение финального heap игрового renderer.

## База и проверка сборки

Скрипт **не пересобирает старый gameplay из исходников**: берёт точный опубликованный
`downloads/Quantum_Sym9_DrawCalls.jar`, SHA-256
`e07b8d98d2e8b74d2c6f3261b09f8684a0ec5e9da578662a693e680e56c2fcd1`.
Добавляет только шесть `StaticRoom*.class` и меняет manifest на тестовый MIDlet.
Все исходные engine classes и ресурсы побайтно неизменны, включая полный level1.txt
пользователя, 256×256 skies и оба lightmap. CLDC 1.1/MIDP 2.0/JSR184 API и ProGuard
microedition preverify настоящие, тестовые заглушки в JAR не попадают.

```sh
export JAVA="$PWD/build/tools/jdk4py/java-runtime/bin/java"
export ECJ_JAR="$PWD/build/tools/ecj.jar"
python scripts/build_static_room_preview.py
# tests/static_room/run.py использует merged API jar из build/cldc/api.jar,
# который создаётся обычным scripts/build_cldc.py.
python tests/static_room/run.py
```

## Выполненные проверки (не GPU rasterization/FPS)

- Реальный kordos: 2641 face, 10032 uploaded vertices, 4750 triangles, 3 API draws.
- 24 направления камеры × 4 river frames = 96 poses: все source XYZ, UV, baked
  значения и native texture bytes сверены; геометрия не теряется.
- Все 10032 значения фактического `/lightmap/kordod.vla` проверены против файла;
  truncated/trailing data отвергаются. Нет асинхронной подмены lightmap.
- На прогретом render: native vertex sets/allocations = 0, index allocations = 0,
  texture uploads = 0, новых Transform = 0. Это счётчики API, не ARM9 timings.
- Реальный Viewer.load с опубликованным config/WTEX/scale, затем A/B/A paint — PASS.
  Только desktop resource anchors в двух временных копиях зависимостей, без
  изменения исходников или классов публикуемого движка.
- Сверка с v1: 5 → 3 calls, exact ordered triangle/state/texture/camera witnesses;
  synthetic tests для adjacent order, mode/perspective/animation barriers и
  fallback при несовместимых bounds/vertex budgets — PASS.
- Analytic camera/aspect/focal-shift; RGB/gray/clamp/cutout; unsupported material,
  NPOT/lossy packing; constructor OOM; bind/viewport/clear/render/release failure
  и освобождение target; dispose — PASS.
- Неизменённый production source suite: 193 PASS-строки на синтетических fixtures,
  без подмешивания старого q92/Stlkr. Первоначальный запуск с ресурсами sym9 выявил
  старую привязку SkyBatchTest к `/skies/skybox5.png`; полный synthetic-run выполнен
  с resource-free dependency JAR. Реальный sym9 покрыт отдельным тестом выше.

Handset visual/FPS test пока **не выполнен**. Следующий шаг — результат A/B на
телефоне, затем интеграция с portal/depth/transparent paths, а не замена сразу.

## Референсы

- l2d_m3g `35c5c26a967aec4562c707f7aba532db20d17d21`, `src/com/MeshData.java`,
  `Renderer.java`: resident geometry, transforms/camera.
- OpenSM3D `c376c2282bf25a9d4479b3818bdae8374e496ab7`, `ResourceLoader.java`,
  `GameScene.java`: native buffers and scene submission.

Код эксперимента написан для структур Quantum; чужие ассеты/форматы не переносились.
