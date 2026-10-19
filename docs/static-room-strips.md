# Static Room Strips — полосы внутри M3G-вызова и подробное время

## Что показал пользовательский тест Present

| Режим | FPS | D (API calls) | N | C | P | F | I | T |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| A direct | 7 | 2 | 112 | 0 | 0 | 113 | 140 | 3421 |
| B image | не сообщён | не сообщён | 112 | 0 | 0 | 112 | 140 | 3421 |

Время — миллисекунды по HUD. Позже пользователь уточнил устройство:
**Nokia E65, TI OMAP1710, кэш «32/16»**. Тактовая частота, версия M3G,
аппаратное 3D-ускорение, точность таймера, позиции и условия не проверены
независимо. Данные и источник уточнения сохранены в render-checkpoints.json.

На разрешении этого таймера **нет различимого выигрыша от direct Canvas**.
C=0 не доказывает бесплатное копирование, но его устранение в данном тесте
не изменило N/F/I существенно. N≈112 мс занимает ~80% I≈140 мс; ещё ~27–28 мс
вне измеренного paint. Нельзя объявить этот остаток чистым scheduling или
убрать его простой отменой Thread.sleep(1): там возможны repaint, экранная
синхронизация, отложенный driver work и другие накладные расходы.

N включает Java-подготовку native mesh, не только graphics.render. Поэтому
новый HUD делит его на J/B/D/R. По прежним значениям нельзя заключить, что все
112 мс потратил аппаратный GPU либо что J=0.

### Почему не начали с dedup вершин

Точная проверка XYZ/UV/RGBA отдельно внутри каждого packet дала **9149**
уникальных записей вместо **10032**: только 883 дубля (~8,8%). Световые/UV-швы
нельзя склеивать по одним XYZ. Такой резерв существует, но даже число
уникальных вершин не является гарантией vertex-work/FPS выигрыша драйвера.
**В этой сборке dedup не применяется**, остаются прежние 10032 вершины.

## Кандидат: множество маленьких полос

Старый cached renderer отправляет до 3 M3G API calls, но его полная геометрия
имеет **2641 triangle strip** — по одной на исходный полигон. API D=2/3 **не
измеряет внутренние hardware draws**. Драйвер может дорого обрабатывать
несколько тысяч маленьких strips, а может уже объединять их сам. Это гипотеза.

Новый `StaticRoomStripsMesh` получен из исходного Cull renderer с добавлением
опционального соединения полос и профилирования, без изменения VB/material/
camera/culling logic. На каждый видимый packet остаётся одна полоса:

- оригинальные indices/strips сохраняются для B и fallback;
- исходные полосы идут строго в прежнем порядке;
- соединители состоят из повторных индексов, то есть нулевых по площади
  треугольников. Чётность длины учитывается, winding следующей полосы прежний;
- XYZ, UV, RGBA, native buffers, материалы, река, alpha/depth, порядок видимых
  треугольников и дистанция одинаковы; CPU-проекция не появляется.

**Компромисс, не бесплатное ускорение:** full-room indices **10032 → 15840**,
strips **2641 → 3**. Геометрически видимых исходных треугольников по-прежнему
4750, но связки добавляют **11084 вырожденных primitive candidates**. На некоторых
реализациях M3G это может быть медленнее. Увеличены index allocation/native
storage и возможная работа драйвера. Никакого обещания FPS до проверки устройства.

## Отдельный JAR и управление

`downloads/Quantum_StaticRoom_Strips.jar`, MIDlet **Quantum Static Room Strips**,
762967 байт. SHA-256:
`1ab0983a1aa135119eef7e7f095f67aaaaf25a3c0a3f00588b4287eb4b0f5e75`.

**0** теперь сравнивает **A joined / B original strips**. Обе стороны:

- используют тот же экземпляр native room/VBs/textures и отсечение;
- рисуют в **тот же переиспользуемый Image**, затем drawImage;
- не меняют дальность/разрешение/FOV/качество/конфиги/ресурсы.

Это не прежний direct/Image A/B и не cull/whole A/B. Image выбран одинаковым для
обеих сторон ради совместимости; по пользовательскому Present-тесту это не
различимый bottleneck. Ненужный DirectX7 CPU framebuffer не возвращается.
`4/6`: поворот, `2/8`: движение, `7/9`: стрейф, `#`: выход.

HUD:

- верхняя **D** — число M3G API calls, не внутренние drawcalls;
- **N / F / I** — прежние complete native call / paint / paint-start interval;
- **J** — Java texture/camera/culling/index prep до bindTarget;
- **B** в строке времени — bindTarget + viewport/camera/resetLights/clear;
- **D** в строке времени — render calls вместе с их Java loop/counters;
- **R** — releaseTarget, где драйвер также может ждать завершения работы;
- **T** — исходные отправленные треугольники, **без вырожденных связок**;
- **S** — фактически отправленные strips; **V** — загруженные вершины (10032).

J/B/D/R измеряются coarse System.currentTimeMillis, не GPU timestamps. Время
между границами API зависит от deferred work/синхронизации драйвера. Нули могут
означать грубый таймер. Замеры добавляют небольшие накладные расходы в обе стороны.
HUD-строки кэшируются на окно ≥1s; показатели сбрасываются при 0.

## Память, отказы и откат

Joined indices строятся **перед bindTarget** только при новом selected mask.
Сохраняются full joined indices и не более двух partial masks на packet; у
каждой partial mask есть original и (при необходимости) joined native indices.
Прогретая фиксированная маска/A-B-A не аллоцирует новые vertex/index/texture
objects. Новая маска при движении может вызвать allocation/GC, как и прежде,
причём joined buffers добавляют к стоимости.

Если не хватает памяти на joined buffer, очищаются joined caches всех packets,
включается **JOIN->orig** и сохраняются исходные native strips с culling. Повторных
попыток до пересоздания комнаты нет. При OOM исходной partial mask — оба новых
кэша отключаются, **MEM->whole** выбирает уже подготовленную полную native-комнату
с исходными полосами. CPU fallback или пропуски видимых граней не используются.
Native draw/release errors показываются явно; 0 позволяет выбрать B после ошибки.

Present `0ec8560`, Cull Lite `cd61dc9` и прежние rollback artifacts не изменены.
Стабильная игра и все тесты остаются отдельными. Это не полная игровая интеграция.

## Проверки

```sh
export JAVA="$PWD/build/tools/jdk4py/java-runtime/bin/java"
export ECJ_JAR="$PWD/build/tools/ecj.jar"
python scripts/build_static_room_strips.py
python tests/static_room/run.py --strips --present --packaged \
  --jar artifacts/static-room-strips/Quantum_StaticRoom_Strips.jar
python -m unittest discover -s tests/resources -p 'test_static_room_*.py' -v
python scripts/verify_render_checkpoints.py
```

- Java ME compile/CLDC preverify PASS: 172 classes, 2085 code methods, 1139
  StackMaps, 47.0. Все 165 исходных class bytes и 10 ресурсов Present сохранены,
  7 новых classes. Ничего из production src не пересобиралось вместо baseline.
- Проверен **готовый JAR** с API doubles, не замена renderer из source. Все
  прежние Cull/Present witnesses/culling/target contracts PASS.
- 128 синтетических смешанных tri/quad patterns, modes0/7/9/13: independent
  strip expansion сохраняет порядок/winding каждой исходной primitive.
- **864** настоящих poses/river frames: новый B и A против исходного Cull.
  Полные buffers/scale/bias/UV/RGBA, camera/projection/state/texture bytes и
  ordered triangle indices точны. Исключаются только повторные index identities,
  не эвристически «похожие» позиции; любые дополнительные non-degenerate
  triangles или смена winding провалили бы сравнение.
- Warm fixed-mask A/B/A: нет новых native indices/vertex/texture uploads.
  Кэш ограничен двумя масками. OOM после первой успешной joined allocation
  возвращает **все** packets на original; partial-mask OOM/full fallback,
  no retry и target release при ошибках PASS.
- Cold Viewer A/B/A: те же шесть изображений из JAR, ни одного missing/error
  image, тот же Image target; T одинаково, S меняется ожидаемо.
- 6 упаковочных тестов Lite/Present/Strips и 6 immutable checkpoints — PASS.

Тестовые stubs/resource anchors не попадают в JAR. Это не pixel equivalence,
handset heap, GPU draw count или FPS benchmark. Логи в игнорируемом build:
`static-room-strips-{source-tests,build,packaged-tests,published-tests,packaging-tests}.log`.

**Следующий полезный результат:** из одной позиции после 5–10s прогрева в A и B
прислать FPS, J/B/D/R, T/S и отметить картинку/повороты. Модель уже уточнена: Nokia E65 / OMAP1710. Результат Strips A/B
на этом аппарате пока не сообщён.
