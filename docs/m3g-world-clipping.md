# Отсечение геометрии для native M3G — 18.10.2026

Продолжение [удаления лишнего B неба](m3g-sky-background.md). Исправлен следующий
источник CPU-переходов: полигоны мира, отвергаемые исходным упаковщиком из-за
`behind-camera` и чрезмерного диапазона координат/весов.

## Реализация

- Успешный прежний `ProjectedGeometry.pack` / `packFog` остаётся основным путём.
  Только при отказе включается ограниченный переиспользуемый `WorldClipGeometry`.
- Выбранный M3G сохраняет view X/Y/Z **до** перезаписи проекцией. Это сделано
  в обоих overload `DirectX7.transformAndProjectVertices` и в `Vertex.project`.
  Координаты модели и последняя глобальная камера не подменяют view space:
  MeshImage может иметь собственную матрицу и изменяемые вершины.
- Перед использованием проверяются глубина и совпадение повторной проекции
  с текущими sx/sy. Непроецированные/вручную изменённые данные не принимаются
  как достоверные: остаётся прежний CPU fallback.
- Исходный треугольник отсекается по engine near plane `rz=0`. Quad сначала
  разбивается по прежней диагонали ABD/BCD либо ABC/ACD для соответствующего
  Fog-материала. Интерполируются UV и RGBA, сохраняются исходные front endpoints.
- Затем выполняется homogeneous viewport clipping. Геометрия/глубина сохраняют
  W, а UV/RGBA учитывают native perspective correction: для affine/HW OFF
  используется экранная интерполяция, в том числе при affine UV + native Fog.
  Вес по-прежнему определяется `TexturingPers.move`, не focal distance/FOV.
- Полученные convex polygons разбиваются веером, экранные endpoints приводятся
  к целой пиксельной решётке. Пересечения вычисляются канонически, независимо
  от направления общего ребра. Нулевые по площади треугольники не отправляются.
- **Все** новые треугольники одного исходного полигона проверяются прежним
  упаковщиком **до** изменения native state/загрузки текстуры/добавления пакета.
  Если поздний треугольник не представим точно, весь исходный полигон остаётся
  CPU fallback: нет частичного alpha/ADD, нарисованного дважды.
- Успешный результат отправляется через существующий M3GPolygonBatch с прежним
  painter order, текстурой, mip/UV remap, blend, static RGB, Fog и CULL_BACK/CW.
  Полностью невидимый результат не требует native draw или texture upload.

Это **CPU-подготовка геометрии с native M3G-растеризацией**, не новая GPU API
для clipping и не CPU-текстурирование. Удалён переход framebuffer → CPU raster
→ M3G для поддержанных случаев. Счётчик B не сбрасывается и не маскируется.

## Стоимость и границы

Кэш добавляет три int на каждый Vertex — 12 байт полезных данных плюс возможное
VM-выравнивание, включая вершины, существующие при выбранном Software. Только
основной меш level41 (2713 вершин) получает 32556 байт таких данных; это не
оценка всего heap. При Software кэш не заполняется, математика и вывод прежние.
В M3G кэш записывается при проекции; clipping работает только для отказов.
Helper создаётся лениво один раз, имеет фиксированные массивы и лимит 16
треугольников на исходный полигон, без per-polygon allocations/framebuffer.

CPU fallback сохранён для неизвестных/устаревших view-данных, непредставимой
точности весов, неподдержанных материалов и отказов API. Специальный paintFill
mode 8 с различной выборкой первого UV каждого треугольника не расширен:
успешный старый путь работает как раньше, rejected pack остаётся CPU fallback.

Не обещается B:0 для всей игры. Холодные texture uploads, snapshot/пауза,
неподдержанные объекты/материалы и существующий внешний MeshImage barrier
не отменены. В тесте фактического moving MeshImage clipped polygons рисуются
M3G; у wrapper остаётся прежний один B, без дополнительного B от clipping.

Software-растеризация и формат исходных файлов не менялись. Возле near plane
теперь рисуется отсечённая геометрия, поэтому пиксельное совпадение с прежним
CPU fallback не является целью. Реальный аппаратный raster/драйвер нужно
проверить на телефоне; API doubles не рисуют GPU screenshot и не измеряют FPS.

## Level41: сравнение с d4ecde5

Та же диагностика: 240×320, две точки × 32 ориентации × четыре компонента,
256 кадров после прогрева семи изображений. Исходные ресурсы и цвета lightmap.
Компоненты: sky+rain, combined, world+solid+rain, world+flash+rain. Это выборка
всех renderable rooms без portals/chunks/NPCs, не частоты игровых кадров.

| Combined, точка (0,1155,0) | D до → после | B до → после | Полных readback/upload до → после |
| --- | ---: | ---: | ---: |
| yaw 0 / pitch 0 | 22 → 21 | **2 → 0** | **2/2 → 0/0** |
| yaw 45 / pitch 0 | 52 → 51 | **2 → 0** | **2/2 → 0/0** |
| yaw 90 / pitch 0 | 41 → 41 | **1 → 0** | **1/1 → 0/0** |

- Во всех **256 кадрах B:0, T:0**, реальные getRGB/drawRGB: 0/0.
- Sky+rain по-прежнему **D:2/B:0/T:0** во всех 64 ориентациях.
- В combined 64 ракурсов восстановлены **70 из 70** rejected polygons:
  49 дали **120 native triangles**, 21 полностью вне видимой области.
- До изменения 39 combined ракурсов имели world CPU barriers, теперь ни один.
- Все 86 sky golden streams совпадают с предыдущим билдом.
- Проекции, исходные очереди, sky/weather/solid draws и T неизменны во всех
  256 строках. Там, где раньше не было world fallback, D/world/split counters
  также неизменны. Изменение общего D зависит от объединения соседних пакетов,
  а не от удаления видимых полигонов.
- Временная test-инструментализация отдельно считает исходные pack rejects,
  успешные clip recovery, новые triangles и full discards. Первичный отказ
  pack больше не считается доказательством реально исполненного CPU fallback.

Логи: `build/world-clip/final-level41.log`, baseline
`build/sky-background/final-level41.log`. Они локальные, не включены в Git.

## Проверки и сборка

- `WorldClipTest`: near + viewport, UV/RGB intersections, winding-independent
  shared endpoints, offscreen overflow recovery, all-behind discard, missing
  и stale provenance, HW OFF/affine Fog screen UV, whole-quad preflight с отказом
  второго треугольника; материалы 0/1/2/3/4/5/6/7/9/10/13; static RGB, culling,
  moving MeshImage; native failure/полный original CPU replay; Software isolation.
- **158 PASS** resource41/M3G, **151 PASS** default; **188 PASS** profile build,
  включая повтор WorldClipTest с активной FrameProfile и balanced nesting.
  Framebuffer/settings и 8 Python resource/profile tests прошли.
- Обычный CLDC: **147 классов, 1878 методов с кодом, 1011 StackMap, 47.0**.
  Без FrameProfile. 142 прежних classfiles неизменны; изменены DirectX7,
  Vertex, M3GSceneRenderer и его CachedTexture; добавлен WorldClipGeometry.
- Классы engine/game равны; все **505 ресурсов** полного JAR byte-identical.
- Движок `downloads/Quantum.jar`: **469590 байт**, SHA-256
  `2610ac7157a53fc4279b4efdd4eb11e9e441a5b00fabc06c5aed7fe4d5aafd3d`.
- Полный `artifacts/world-clip/Quantum_Level41_NativeClip.jar`: **9766857 байт**,
  SHA-256 `b6b550c0a241bd726e4508e5dd21018e653a775f6e26c625bf46e99c311c306d`.
  Рядом matching JAD; отдельный Quantum_Engine.jar/.jad без игровых ресурсов.

Ускорение по времени и FPS на телефоне этой проверкой не измерялось.
