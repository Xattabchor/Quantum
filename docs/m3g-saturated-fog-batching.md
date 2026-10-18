# M3G: объединение непрозрачных поверхностей за плотным Fog

18.10.2026. Продолжение [диагностики level12](m3g-level12-draw-diagnostic.md),
исходный движок `03d3540`, диагностический commit `bec4f63`.
[Числа и SHA-256](diagnostics/level12-fog-solid.json).

## Изменение

Убраны некоторые разрывы соседних пакетов, когда **обе поверхности уже
полностью скрыты LINEAR Fog**. В этой области итоговый RGB равен цвету
тумана независимо от текстуры. Это не отключение тумана или уменьшение
FOGD/DIST: видимая переходная область остаётся на прежнем native Fog пути.

Условия строгие:

- Только opaque texture kind, реально загруженная альфа каждого texel=255.
  Берётся уже вычисленный `CachedTexture.cutoutThreshold`, без нового
  сканирования картинки. Ни cutout-дыры, ни ALPHA/ADD/MODULATE blending
  не допускаются; все vertex alpha также должны быть 255.
- Только LINEAR, REPLACE, MODULATE texture function; одинаковые Fog RGB и
  PolygonMode. EXP, static 9/13 без Fog, спрайты и остальные материалы не меняются.
- Проверяется **уже упакованная eye depth**, а не только исходный rz:
  ближайшая вершина должна быть глубже `far*1.001 + 0.01`.
  Это запас от числовой границы Fog и near clip. Некорректные масштабы
  отвергаются; все исходные short-компоненты остаются как были.
- Объединение включается **лишь вместо уже необходимого material/scale split**
  и только если хватает ёмкости пакета. Одиночный дальний полигон не
  преобразуется заранее: он по-прежнему может объединиться с ближним
  полигоном той же текстуры. Capacity spill не создаёт преобразованный singleton.

Для такого пакета текстура не нужна, но **native LINEAR Fog остаётся**:
его внутренние near/far = 0/0.5, масштаб положительных homogeneous координат
= 1. Все q>=1, Fog полностью насыщен. Projection XY, порядок треугольников,
UV и RGBA сохранены. Depth test/write на этом пути по-прежнему выключены.
Настройки уровня не перезаписываются; следующий обычный пакет получает
исходные диапазон и текстуру. Альфа и покрытие не меняются.

Формула: `RGB = f*textureRGB + (1-f)*fogRGB`, где f=0 во всей покрытой
области. Выпуклая интерполяция положительных глубин не выходит ближе минимума;
поэтому результат одинаков и для vertex-, и для fragment-evaluated LINEAR Fog.
Камера имеет бесконечный far clip; обе версии гарантированно за near clip.

**Внутренняя физическая XYZ/scale и Fog endpoints полностью затуманенных
пакетов намеренно не побайтно прежние.** Они уже не влияют на видимый RGB.
Мы не выдаём это за прежнее строгое binary rebase: у обычных треугольников
проверяется старый physical-depth stream, у насыщенных — эквивалентный
результат по формуле Fog и точный projected stream. GPU screenshots не снимались.

Постоянные расходы: два boolean в batch, opacity flag в State, два float
для кэша порога. Новых массивов/векторов на полигон нет. Порог кэшируется;
для проверки глубины ищется ближайший integer Z и делается одно умножение.
Исходники изменены только в `M3GSceneRenderer` и `M3GPolygonBatch`.

## Реальный ресурсный fixture level12

Те же 80 поз 240×320, мир/порталы, 6 meshes + 1 sprite + 5 idle NPC,
фиксированный configured rain (10 частиц). Спавнер не активирован,
симуляция ИИ/физики/погоды не выполняется. Это **не FPS** телефона.

| Warm counters, 80 поз | Было | Стало |
|---|---:|---:|
| Тяжёлая поза, все объекты | **108** | **105** |
| Сумма D по 80 позам с объектами | 2459 | 2412 |
| Максимальное сокращение в одной позе с объектами | — | 7 |
| Улучшенные / ухудшенные позы с объектами | — | 12 / 0 |
| Тяжёлая поза, без объектов (sky/rain остаются) | 101 | 99 |
| Сумма D по 80 позам без объектов | 2076 | 2027 |

Тяжёлая поза: камера `76610,1503,-36271`, yaw=0, pitch=0.
После правки: 97 обычных world draws + 2 saturated Fog packets + 2 mesh +
1 sprite + 2 sky + 1 rain = **105**. Warm T=0, B=1 как прежде.
Это **небольшой**, а не многократный выигрыш. Остальные 68 поз не изменились.
До отключения Fog (D52 в контрольном тесте) эта правка не приближает полностью:
обычная видимая геометрия всё ещё требует точного eye-space масштаба.

Level13: D и все 320 строгих native triangle streams остались прежними,
максимум 75. Level41: все 256 rows и 86 sky streams набора прежние.

Первый эксперимент — разбиение уже упакованного quad на исходные ordered
треугольники при несовместимости масштаба — дал **0 выигравших поз**.
Он удалён; дополнительных копирований от него в release нет.

## Проверки

- Обычный M3G набор: **175 PASS**, profile build: **242 PASS**.
- `FogSolidTest`: граничные/нулевые eye depth, альфа, opacity metadata,
  разный цвет, EXP, ALPHA/ADD, реальные cutout textures, capacity и отсутствие
  eager split; произвольные исходные масштабы; algebra/XY/UV/RGBA.
- 500 seeded смешанных near/far/material/scale streams: не выросли draw counts,
  сохранились порядок и projected XY/UV/RGBA.
- Actual renderer, Fog 3/6, HW perspective ON/OFF: synthetic 70 дальних
  непрозрачных quads с чередующимися текстурами → 3 world packets;
  после прогрева B0/T0, native VertexArray/index caches не растут.
  Это **не** число draw-вызовов настоящего level12.
- Сбой второго native пакета после успешного первого: полный selected-M3G
  CPU replay с исходными текстурами и ровно одним выполнением queued CPU tail.
- 320 level12 и 320 level13 result witnesses совпали с временной reference,
  отключающей только новую оптимизацию. Независимая проверка результата
  проверяет загруженную alpha, Fog equation, clamp на треугольнике и XY/W/UV/RGBA;
  все остальные треугольники хэшируются со строгой physical XYZ/depth/state.
  У level12 273/320 строгих stream hashes тоже совпали; 47 отличаются ожидаемо
  из-за насыщенных пакетов. Это API/algebra tests, не GPU raster emulation.
- Framebuffer/settings/frame timing, AI regression и 9 Python tests PASS.
- CLDC: 149 классов v47.0, 1905 code methods, 1026 StackMaps.
  **146 classfiles побайтно прежние**; изменены SceneRenderer, PolygonBatch
  и его State. Game/engine classes одинаковы; 442 ресурса побайтно прежние,
  включая имена. SDK/tests/profiler не включены. Runtime на телефоне не проверен.

## Воспроизведение

После `scripts/build_cldc.py` и подготовки Stlkr `resources.jar`:

```sh
export JAVA=/path/to/java ECJ_JAR=/path/to/ecj.jar
python tests/m3g/run.py
QUANTUM_RESOURCE_JAR="$PWD/build/stlkr/resources.jar" \
  QUANTUM_LEVEL12_PROBE=1 python tests/m3g/run.py > new.log 2>&1
QUANTUM_RESOURCE_JAR="$PWD/build/stlkr/resources.jar" \
  QUANTUM_LEVEL12_PROBE=1 QUANTUM_FOG_SOLID_REFERENCE=1 \
  python tests/m3g/run.py > reference.log 2>&1
```

`visibleStream` — результат с канонизацией насыщенного Fog; `stream` — старый
строгий physical stream. Reference правит **только временные** исходники,
не конфиг/опцию игры. Для level13 тот же запуск с `QUANTUM_LEVEL13_PROBE=1`.
Release JAR/JAD и актуальные SHA-256 — в [downloads](../downloads/README.md).
