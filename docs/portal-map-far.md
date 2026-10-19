# Portal map: far plane 40000 / 30000 / 20000

Отдельный контролируемый тест для новой карты с порталами. Не BVH, не BSP и не
перевод основной игры на другой renderer. Родительский
[PORT ON/OFF](portal-map.md) сохранён без изменений.

[JAR](../downloads/Quantum_PortalMap_Far.jar) ·
[JAD](../downloads/Quantum_PortalMap_Far.jad) ·
[Build report](../downloads/Quantum_PortalMap_Far.build-report.json).

## Как сравнивать на телефоне

- Запуск: **FAR 40000**, порталы всегда **ON**.
- **0**: 40000 → 30000 → 20000 → 40000.
- **1**: 40000; **3**: 30000; **\***: 20000. Это **не** переключатель порталов.
- **2/8**: вперёд/назад; **4/6**: поворот; **7/9**: вбок.
- **5**: исходная камера; **#**: выход.

Выбрать ракурс, отпустить движение и снять **40k → 30k → 20k → 40k**, не меняя
позу. Far-клавиша останавливает движение и сбрасывает окно статистики, но не
переставляет камеру и не пересоздаёт Image/room buffers. На каждом режиме
подождать числовой FPS и ещё одно обновление (около **8 секунд**). Нужны фото
с надписью FAR, FPS, N и J/B/K/R/E, а также наблюдение, насколько заметно обрезало
дальнюю сцену. Возврат к 40k помогает заметить дрейф результатов.

## Что действительно меняется

Только аргумент **far native projection**: 40000, 30000 или 20000. Near128,
FOV74, viewport, положение/поворот камеры, perspective ON, alpha ON, Z ON,
DMODE13/RGB-lightmap, материалы и atlas неизменны. Меняются два depth-коэффициента
матрицы проекции; native clipping получает более близкую дальнюю плоскость.
Обрезание дальних объектов здесь ожидаемо, это не ошибка порталов.

**CPU distance culling не добавлялся.** Родительский selector от far не зависит:
он по-прежнему отправляет целиком комнаты, выбранные порталами. При одной позе
**T/DC/VB/Rooms остаются одинаковыми**; T — треугольники, переданные M3G до
native clipping, VB — вместимость отправленных буферов. Например, исходная
поза по-прежнему отправляет комнату6: 1384tri / 1call / VB2990. Этот тест не
проверяет возможную экономию API-вызовов или CPU-отсечение мелких блоков.

Драйвер может меньше растеризовать дальнюю геометрию, но всё ещё получает те
же вершины/индексы и вызовы. **Ускорение не гарантировано**: если ограничивает
другая стадия, FPS может почти не измениться. N и J/B/K/R/E — wall time Java API,
не GPU timestamps и не счётчики уже отсечённых треугольников.

Как у родителя, это **geometry-only**: без неба, погоды и игровой логики. Все
6 исходных файлов карты, включая config с `DIST40000`, сохранены побайтно.
Глобальный `DirectX7.drDist` остаётся40000; far меняется только у диагностической
native projection. Это не прогноз полного игрового FPS.

## Изоляция и воспроизводимость

Родитель: `Quantum_PortalMap_Test.jar` из `6fe1257924b52fa252461c4ef36bb78cb57edc2f`,
SHA256 `116abb3817186dec6c753e4031f4a8d66f03690f6c73db939989592062064be4`.
Все **190 классов** родителя и все его ресурсы неизменны. Добавлены только
`PortalMapFarPreview` и вложенный `Viewer`; заменён manifest/entry point.
В результате **192 CLDC47 класса / 1234 StackMap attributes**. Исходники основной
игры, портал-selector, native renderer и холодные buffers не изменялись.

```sh
# JAVA / ECJ_JAR при необходимости указывают подготовленный JDK и ECJ;
# SDK/API/ProGuard — те же, что у parent build (см. portal-map.md).
python scripts/build_portal_map_far.py --output-dir downloads
python tests/portal_map_far/run.py
python tests/portal_map_far/run.py --packaged
python -m unittest tests/resources/test_portal_map_far.py
python tests/portal_map/run.py
python tests/portal_map/run.py --packaged
python scripts/verify_render_checkpoints.py
```

Проверено на desktop API doubles, **не на телефоне и не native rasterizer**:

- исходники и упакованный CLDC JAR: по **144926 assertions**, **1296 far
  comparisons в 432 позах**, включая 40k→30k→20k→40k;
- одинаковые submission payload/order, resident VB identity, камера, материалы,
  alpha/depth/perspective, atlas и room counters; различаются только два depth
  коэффициента; математическая проверка near/far и отсечения точки25000 при20k;
- отсутствие горячих VertexArray/index allocations, attribute/texture uploads;
- реальные клавиши/UI, одинаковые payload и камера в UI, прогрев/сброс, lifecycle,
  восстановление target после ошибок native API для каждого far;
- регрессия родителя: по **57142 checks / 448 camera cases** source + packaged;
- resource audit проверяет каждый байт родителя, manifest/JAD, class version,
  metadata/source hashes и отсутствие BVH/BSP;
- повторная сборка JAR, JAD и build-report побайтно совпала.

JAR: **1093010 bytes**, SHA256
`9377e88652c47102a81046e80c318e37a3ee187b337e2afabaf2181d2f0485fe`.
Телефонного результата на момент сборки нет.
