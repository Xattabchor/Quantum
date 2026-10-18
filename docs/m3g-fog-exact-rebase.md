# Точное объединение native Fog-пакетов

18.10.2026. Продолжение [диагностики Stlkr level13](m3g-level13-check.md).
Исходный 480-строчный CSV оставлен без изменений. Новый
[CSV сравнения](../tests/m3g/reports/level13-fog-exact-rebase.csv) содержит
320 cold/warm строк с исходным Fog ON и SHA-256 упорядоченного потока
native-треугольников, независимо от границ draw-пакетов.

## Результат

Те же 80 поз, 240×320, PERSQ=4, HQ Fog, HW perspective ON, mipmaps OFF;
реальные House/порталы и исходные начальные объекты. Это API doubles,
**не GPU screenshots, не полный игровой кадр с AI/HUD и не FPS телефона**.

Warm draw calls с объектами, диапазон по 16 позам каждой точки:

| Точка | До | После |
|---|---:|---:|
| start | 3–30 | 3–25 |
| лагерь | 4–95 | 3–75 |
| ржавый лес | 3–64 | 3–54 |
| бандиты | 3–31 | 3–28 |
| солдаты | 2–47 | 2–37 |

Тяжёлый кадр лагеря, yaw=0/pitch=0: **D:95 → 75 (−21%)**, world **74 → 54**,
scale splits **77 → 57**. Не изменились: mesh=13, sprites=6, sky=2,
B=1/T=0, readback=1/copy=1, 273 треугольника, 369 projected vertices,
158 queued, 9 texture splits, 7 state splits, 0 capacity splits.

В 40 из 80 warm object samples D уменьшился, в остальных не вырос.
Сумма по этим фиксированным позам **1200 → 1049**, среднее **15 → 13.1125**.
Это не распределение игрового времени и не процент ускорения CPU/GPU/FPS.
Панорамный crop/barrier и редкие clipping recoveries намеренно сохранены.
Fog OFF из прошлого отчёта — изменяющий картинку контроль, не эта оптимизация.

## Реализация и ограничения

Единственный изменённый production Java-файл — `M3GPolygonBatch.java`.
У adjacent пакетов с одинаковыми texture/material/native Fog/camera state
теперь допускается разный scale, **только если его можно привести точно**:

1. Positive normal finite float scales должны иметь одинаковую мантиссу;
   разница экспонент 1…15 доказывает отношение `2^n` без float division.
2. Сначала пробуется более мелкий scale: XYZ более грубого пакета сдвигаются
   влево, только если весь результат помещается в −32767…32767.
3. Иначе более крупный scale разрешён лишь при делимости **каждого**
   компонента мелкого пакета на `2^n`, без остатка. Проверка — bit mask.
4. Полный preflight предшествует изменению pending vertices. При отказе,
   несовместимом state или заполнении 192-вершинного буфера — прежний flush.

Таким образом `XYZ × scale`, экранные концы, относительные perspective W,
UV/RGBA, порядок треугольников и native Fog depth не меняются. Нет повторного
округления уже упакованных координат. Первичная упаковка/её точность прежние.
Fog 3/6 LINEAR и 12 EXPONENTIAL, цвета, near/far/density и настройки уровней
сохранены. Остальные material equations, Software, native sky/weather,
барьеры и atomic recovery не переписаны. Не все scale splits безопасно
объединяемы — оставшиеся 57 не удаляются ценой точности.

Добавлен один постоянный `short[12]` (24 байта payload + header/reference).
Нет новых per-face arrays или native VertexArray/TriangleStripArray
allocations после прогрева. Пакет по-прежнему ограничен 64 треугольниками.
Это уменьшение submissions, не гарантия меньшего времени кадра на каждом
JSR-184 implementation; OMAP1710/ARM9 физически не измерялся.

## Проверки

- **169 PASS** default M3G; **180 PASS** с Stlkr level13; **228 PASS** с
  активным frame profiler. Reference run — 177 PASS: только новые тесты,
  требующие уменьшения D, в нём намеренно не запускаются.
- **16000** randomized rebases: pending first triangles/quads и expanded
  packets, оба направления, точные float XYZ/depth, screen/relative W,
  неизменённый input; overflow/divisibility rejection атомарен. Invalid,
  nonbinary, zero, negative, denormal, NaN/Inf scales не объединяются.
- Native LINEAR+EXP ordered streams: triangles/quads, finer/coarser target,
  обратный порядок и запрещённый merge. Физические XYZ, UV, RGBA и Fog равны.
- Modes 3/6/12, HW ON/OFF: 70 alternating-scale triangles → **2 world draws**;
  70 quads → **3 world draws**, плюс отдельный однотонный sky draw. Warm
  B=0/T=0, native arrays/indices переиспользуются. Сбой второго world-пакета
  после первого успешного восстанавливает CPU framebuffer и queued CPU tail.
- Все **320 SHA-256 stream witnesses** optimized/reference совпали. Witness
  нормализует strip winding и границы пакетов, включает source resource/cache
  kind, material/Fog/projection, physical XYZ для Fog, screen+relative W для
  no-Fog и UV/RGBA. Reference D и все исходные поля совпали с 320 Fog-ON строками
  ранее закоммиченного baseline. Это проверка API stream, не raster pixels GPU.
- Level41: **176 PASS**; все **256 строк счётчиков и 86 sky streams** побайтно
  совпали с ARM9 baseline. Framebuffer, settings, frame timing tests проходят.
- **9 Python unit tests** ресурсы/профиль. В Stlkr найдены 6 не-ASCII имён:
  ProGuard терял ZIP UTF-8 flag. Build script теперь восстанавливает исходные
  ресурсы/имена из проверенного словаря после preverification, не угадывая
  переименования. Regression воспроизводит потерю local/central flags.
- CLDC 1.1/MIDP 2.0: 149 классов, version 47.0, 1896 code methods,
  1019 StackMap attributes. **148 classfiles побайтно прежние**; изменён
  только `code/Rendering/M3GPolygonBatch.class`. Переупаковка Unicode assets
  не меняет classfile bytes. SDK, probes и frame profiler в release не включены.
- Движок и игровая сборка содержат одинаковые classfile bytes. Все **442**
  исходных Stlkr ресурса и их имена совпали, без дополнительных assets.
  Независимая повторная сборка дала одинаковые содержимое/имена ZIP entries.

## Воспроизведение

Подготовка Java/ECJ/SDK и assets — как в предыдущих отчётах; archive SHA-256
`3c77d2fe5db203e4571cdb12aea5d26721058fca327e2a39edce733492719f19`.
Из корня репозитория с настроенными `JAVA`, `ECJ_JAR`:

```sh
python tests/m3g/run.py
QUANTUM_RESOURCE_JAR="$PWD/build/level13/assets/resources.jar" \
  QUANTUM_LEVEL13_PROBE=1 python tests/m3g/run.py > optimized.log 2>&1
QUANTUM_RESOURCE_JAR="$PWD/build/level13/assets/resources.jar" \
  QUANTUM_LEVEL13_PROBE=1 QUANTUM_FOG_REBASE_REFERENCE=1 \
  python tests/m3g/run.py > reference.log 2>&1
python tests/m3g/compare_fog_rebase.py --reference reference.log \
  --optimized optimized.log --output comparison.csv
QUANTUM_RESOURCE_JAR="$PWD/build/game-resources/resources.jar" \
  QUANTUM_LEVEL41_PROBE=1 python tests/m3g/run.py
python -m unittest discover -s tests/resources -v
```

Reference flag меняет только временную копию тестового исходника: native Fog
остаётся включён, новый `rebase` не вызывается. В production нет такого
параметра. Профильные тесты запускаются отдельно после `--profile-frame` build
с `QUANTUM_FRAME_PROFILE=1`; release build — без этого флага.

Build по обычному `scripts/build_cldc.py`; для полной Stlkr добавить
`--resource-archive 'Stlkr by ebk.jar (1).zip'` и отдельный `--output-dir`.

## Сборки

- `downloads/Quantum.jar` — **474685 байт**, движок без игровых ресурсов.
  SHA-256: `b086af16b5617be66692c9e2201b6b100847c3dfeb02c89f3c777d3d62e4d0d4`.
- `artifacts/fog-batch-exact/Quantum_Stlkr_FogBatch_ARM9.jar` — **10072428 байт**,
  полная Stlkr с исходными 442 ресурсами, не прежний мод с level41.
  SHA-256: `334427f3eeaead0428da0feba4d3f560d07c5e9b0144928c9132638307da413c`.
- В той же папке есть соответствующие `.jad` и `Quantum_Engine_FogBatch.jar/.jad`.
  JAD указывают правильные имена и размеры. Визуальный запуск и FPS на
  физическом телефоне не проверены; `runtime_tested=false` в build report.
