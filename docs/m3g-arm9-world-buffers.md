# ARM9 / OMAP 1710: world buffers и position bindings

18.10.2026. Продолжение [оптимизации Canvas](m3g-arm9-canvas.md), база `4fe5c00`.
Меняются только два production Java-файла: `M3GPolygonBatch` и `M3GSceneRenderer`.
Это переносимый CLDC/MIDP/JSR-184 код, не ARM-native бинарник. Замеров FPS,
времени драйвера и heap на настоящем OMAP нет; M3G может исполняться на CPU.

## 1. Не выделять 192 вершины ради двух треугольников

Раньше первый объединённый world packet всегда создавал три VertexArray на
192 вершины. Теперь один комплект начинается с подходящей ёмкости из ряда
**6 → 12 → 24 → 48 → 96 → 192**, увеличиваясь только по необходимости.
При первом packet из двух треугольников ёмкость — **6 вместо 192**.

Это **ёмкость**, а не уменьшение числа реально рисуемых вершин/треугольников:
индексы, active attribute uploads и draw count прежние. Без замера драйвера
нельзя утверждать, что прежний GPU действительно обрабатывал все 192 вершины.
После роста до 192 последующие маленькие packets продолжают использовать 192;
это grow-only буфер, не exact-count пул для каждого packet.

Постоянно удерживается **один** комплект буфера/трёх массивов. Его логический
VertexArray payload сначала 84 вместо 2688 bytes для маленького packet, в
максимуме прежние 2688 bytes. Индексы и CPU staging arrays не меняются.
При постепенном росте возможны шесть комплектов за lifetime: предыдущие
переходят к GC. Поэтому суммарные cold allocations и пиковая память могут
оказаться выше прежнего одноразового выделения; actual native footprint
и внутренние копии драйвера не измерены. Десятков постоянно кешируемых
world-buffer sets нет. Повторный такой же или меньший packet не перевыделяет массивы.

Новый комплект публикуется только после успешного создания всех его native
объектов и bindings. Allocation/API failure не оставляет старый VertexBuffer
в паре с частью новых массивов. Прежний fail-closed required-M3G путь сохраняется;
CPU replay и скрытой попытки перейти на Software нет.

## 2. Не повторять setPositions при неизменной привязке

`VertexArray.set` меняет содержимое существующего массива. Не нужно каждый раз
заново привязывать тот же массив к тому же VertexBuffer с прежним scale.

- Batch помнит applied scale; новый/увеличенный буфер обязательно привязывается.
- Два отдельных triangle/quad буфера SceneRenderer имеют независимые scale caches.
- Все четыре места установки positions в SceneRenderer используют общий helper:
  SolidSky, неоднородный fill, непосредственный polygon и renderSingle.
- Изменение Fog scale сохраняет точный float, вызывает новую привязку и не меняет
  eye depth. Никакого округления, приближённого reciprocal или новой геометрии.
- Dispose инвалидирует triangle/quad caches. Смена camera/appearance не меняет
  принадлежащую этому VertexBuffer привязку массива.

В fixture из 20 чередующихся triangle/quad draws с изменениями scale `.25 → .5 → .25`
число `VertexBuffer.setPositions` — **20 → 6**. Повторный packet с неизменным
буфером/scale — **0** таких вызовов. Positions/UV/colors всё равно загружаются,
число draws и треугольников не сокращается этим этапом.

## Проверки

`Arm9WorldBuffersTest` проверяет рост 6…192, повторное использование после большого
packet, отсутствие warm reallocations/rebindings, все XYZ/UV/RGBA, Fog scale
переходы, discard, dispose и OOM посередине роста с recovery исходного буфера.
Два negative controls действительно падают на старом коде: oversized capacity
и повторный triangle/quad binding соответственно.

- Обычный и active frame-profile M3G suite — PASS.
- CLDC compile/preverify — PASS: 154 classes, 1967 code methods, 1067 StackMap,
  class version 47.0, без профилировщика в полной тестовой сборке.
- Строки strict 13-world suite (2384), level12 и level13 с idle objects
  (320 + 320) полностью совпадают с предыдущим ARM9, включая stream hashes;
  CPU dispatch = 0. Это не прохождение всех динамических игровых сценариев.
- Crossing обоих деревьев, foreground, weapon/scope/reload, TextView,
  framebuffer и 14 Python tests — PASS.
- Все 442 ресурса полной Stlkr-сборки неизменны. Старые JAR сохранены.
- Из class-файлов меняются два renderer-класса и только source-line metadata
  `M3GSceneRenderer$CachedTexture` (сдвиг четырёх line numbers на 2).

[Машинная сводка](diagnostics/m3g-arm9-world-buffers.json).
Качество, DIST, fog/lighting modes, форматы, коллизии, AI, quests и RMS не меняются.
Ранее отменённые CPU shade/OMNI caches и append fast paths не возвращаются.
Необходим реальный A/B тест на телефоне: одинаковая сцена, warm FPS/паузa GC,
память, вход/выход из меню и смешивание LCDUI + M3G. До этого сборка тестовая.
