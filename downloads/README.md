## Quantum Kordos FX: полная игра на kordos с аппаратным M3G-светом и омни-вспышкой оружия

[Quantum_Kordos_Full.jar](Quantum_Kordos_Full.jar) ·
[JAD](Quantum_Kordos_Full.jad) ·
[методика](../docs/kordos-full.md).

Полноигровой билд (меню/арсенал/ХУД/враги) на мире kordos (старая sym9-деревня):
нормали полигонов поданы как M3G VertexNormals, world-проход переведён на
настоящий true-3D (view-координаты + перспективная камера + hardware depth),
аппаратный свет AMBIENT+DIRECTIONAL и OMNI-вспышка выстрела (питание от
`shootIntensity`, как у legacy CPU-`Mode5WeaponLight`). Baked-лайтмапа
сохранена в вершинных цветах. Противники: 6 стрелков DROZH_ENEMY на
позициях деревенских NPC + 6 зомби по лесным респаунам. Флаг `M3G_LIT`
(в setting.txt): =2 без омни, =0 полный откат на старый packed-путь —
см. таблицу ключей и протокол теста в docs; FPS всегда на ХУДе.
Замер на E65 отвечает на главный вопрос: цена hardware T&L+lighting vs
CPU-packed пайплайн на той же карте. В этой сборке дополнительно: статическое
(запечённое) освещение вырезано полностью (`M3G_LIT_NOVCOL=1`), mip —
аппаратный NEAREST_MIPMAP_NEAREST с тумблером (`M3G_LIT_MIP`), вьюпорт мира
масштабируется (`M3G_SCREEN_SCALE`, 25..100%), покачивание спрайта оружия
выключено (`WEAPON_SWAY=0`).

## L2D Metro: стоковое метро из l2d_m3g (indoor-зонд)

[Quantum_L2D_Metro.jar](Quantum_L2D_Metro.jar) ·
[JAD](Quantum_L2D_Metro.jad) ·
[Отчёт](Quantum_L2D_Metro.build-report.json) ·
[методика](../docs/l2d-metro.md).

Полностью indoor сцена — стоковые байты `metro.3d2` + `metro_1.bmp` из
апстрима l2d_m3g (level1), стоковый рендерер `MeshData.loadMeshes3D2` +
`Renderer.addMesh(Node)`: 22 меша / 22 вызова, 1640 треугольника, 3245
вершины, одна 256×256 текстура, ни реки, ни неба. Та же замерная HUD-стойка
(`FPS`, `N/C/I`, `T/DC/VB`), near128/far60000/FOV74. **0** масштаб
100→75→50→25 (только DIR с полосами), **1** S100, **5** старт-точка
(глаз = +2005 как в игре), 2/8 ходьба, 4/6 поворот, 7/9 стрейф.
Задача — замерить indoor предел на E65: быстрый indoor ⇒ проблема реки в
данных (площадь/дальность), медленный ⇒ в цене самого рендер-пути.
Телефонный результат №1 (E65): **S100 10–12 FPS** против 7.0 у речного
S100 DIR — почти та же цена при 1640 треугольниках (модель 74 мс заливки + меньший гео-член indoor); рычаг тот же — масштаб клавишей `0`.
Геометрия/текстуры уровня неизменны; без обещаний FPS до замера.

## River Map Geo: цена геометрии — far и порог по экранному радиусу (F / L)

[Quantum_RiverMap_Geo.jar](Quantum_RiverMap_Geo.jar) ·
[JAD](Quantum_RiverMap_Geo.jad) ·
[Отчёт](Quantum_RiverMap_Geo.build-report.json) ·
[методика](../docs/river-geo.md).

База — исходный форк renderer'а Pixel-сборки над доставкой Upscale-сборки;
по умолчанию `S100 DIR F60K L0 B`. **7** — far 60000→40000→20000
(только проекция/фрустум текущего кадра), **9** — порог отсевания
GRID-клеток по экранному радиусу 0→8→16 px (клетки с камерой внутри не
режутся), **0** масштаб 100→75→50→25, **3** DIR⇄UP (та же доставка апскейла),
**`*`** BASE_LEVEL/ближайший mip, **1** BASE, **5** home.
HUD — `FPS` и `T/N/R/C/I`, где **T** — пропущенные треугольники окна.
Задача: оценить цену фиксированных ~30 мс при S50 UP (9.1 FPS / R50) и
сколько из неё снимается пропуском геометрии. Контроль плато — против
`S100 DIR` апскейла (7.0 / R112). Телефонный результат (E65, близкая
поза): **лучшая комбинация S25 DIR + F20K — до ~30 FPS** против ~16 у
S25 DIR F60K; L (8/16) заметно не влияет (клетки крупные, радиусы
консервативно раздуты) — фикс-30 мс в основном цена дальних
треугольников, а не константа движка; рабочий механизм — уменьшение far.
При L0 поведение побайтно совпадает с
родителем; геометрия/река/свет/текстуры неизменны; рассчитано на M3G 1.0
в E65. Прежние JAR сохранены, ускорение до телефонного A/B не обещается.

## River Map Upscale: чёрные полосы или nearest-апскейл на весь экран (DIR / UP)

[Quantum_RiverMap_Upscale.jar](Quantum_RiverMap_Upscale.jar) ·
[JAD](Quantum_RiverMap_Upscale.jad) ·
[Отчёт](Quantum_RiverMap_Upscale.build-report.json) ·
[методика](../docs/river-upscale.md).

База — renderer Pixel-сборки побайтно; по умолчанию `S100 DIR TEX SM B`.
**3** — главный A/B: полосы 1:1 ↔ `getRGB`+Java nearest-поднятие+`drawRGB`
на весь экран, **0** масштаб 100→75→50→25, **7** атлас/1×1, **9** SMOOTH/FLAT,
**`*`** BASE_LEVEL/ближайший mip, **1** BASE, **5** home.
Живой HUD — `FPS` и `N/R/C/I`, **C** — измеренное окно доставки.
Телефонный результат (E65, близкая поза): `S50 UP` **C33/R50/I109 при FPS 9.1**
против базы `S100 DIR` **R112/I141/7.0** — апскейл рентабелен только при ≥2×
сокращении площади; `S75 UP` мёртвая точка (I140), `S25 UP` ~12.8 с ×4 зерном.
R между DIR и UP при одном масштабе неизменен — модель `R ≈ 30 + 74×площадь`
подтверждена; новый ограничитель при S50 — фикс ~30 мс геометрии.
Геометрия/река/свет/текстуры неизменны; рассчитано на M3G 1.0 в E65.
Прежние JAR сохранены, ускорение до телефонного A/B не обещается.

## River Map Pixel: масштаб viewport, текстура, shading и mip

[Quantum_RiverMap_Pixel.jar](Quantum_RiverMap_Pixel.jar) ·
[JAD](Quantum_RiverMap_Pixel.jad) ·
[Отчёт](Quantum_RiverMap_Pixel.build-report.json) ·
[методика](../docs/river-pixel.md).

База — быстрый A LARGE путь карты с рекой. По умолчанию `S100 TEX SM B`.
**0** масштаб 100→75→50→25%, **3** сразу 50%, **7** атлас/1×1 текстура,
**9** SMOOTH/FLAT, **`*`** BASE_LEVEL/ближайший mip, **1** BASE, **5** home.
Живой HUD — только `FPS` и `N/R/I`. Снять в тяжёлой позе BASE → S75 → S50 → S25,
затем отдельно 1X1, FLAT и MIP. Геометрия/отбор/река неизменны; рассчитано
на M3G 1.0 в E65. Прежние JAR сохранены, ускорение до телефонного A/B не обещается.

## River Map Overdraw: компактные пакеты front-to-back и depth prepass

[Quantum_RiverMap_Overdraw.jar](Quantum_RiverMap_Overdraw.jar) ·
[JAD](Quantum_RiverMap_Overdraw.jad) ·
[Отчёт](Quantum_RiverMap_Overdraw.build-report.json) ·
[методика](../docs/river-overdraw.md).

Та же карта с рекой и GRID-отбор; по умолчанию **C F2B** — компактные пакеты
подаются спереди назад. **1** A LARGE, **3** C ORIG, **7** C F2B, **9** C ZPRE
(depth-only prepass), **0** цикл, **`*`** полные счётчики, **5** home.
Живой HUD — только 2 строки `FPS` и `N/R/I`. Снять в тяжёлой позе
F2B → ORIG → A → F2B, по желанию ZPRE; смотреть прежде всего **R**.
Геометрия/текстуры/свет/река неизменны; рассчитано на M3G 1.0 в E65.
Прежние JAR сохранены, ускорение до телефонного A/B не обещается.

## River Map BVH HUD: карта с рекой, BVH/GRID и FULL / MIN / OFF счётчик

[Quantum_RiverMap_BvhHud.jar](Quantum_RiverMap_BvhHud.jar) ·
[JAD](Quantum_RiverMap_BvhHud.jad) ·
[Отчёт](Quantum_RiverMap_BvhHud.build-report.json) ·
[методика](../docs/river-map-bvh-hud.md).

По умолчанию **BVH ON + FULL HUD** на той же карте с рекой. **7** BVH,
**9** GRID; HUD: **1** FULL, **3** MIN, **0** OFF, **`*`** показать результат OFF.
Снять сначала BVH: FULL → MIN → OFF+`*` → FULL в одной позе; GRID — контроль
по необходимости. Геометрия/река/свет/текстуры неизменны; BVH здесь старый
frustum BVH деревни, не Portal BVH/BSP. Прежние JAR сохранены.

## River Map HUD: карта с рекой, FULL / MIN / OFF счётчик (отдельный тест)

[Quantum_RiverMap_Hud.jar](Quantum_RiverMap_Hud.jar) ·
[JAD](Quantum_RiverMap_Hud.jad) ·
[Отчёт](Quantum_RiverMap_Hud.build-report.json) ·
[методика](../docs/river-map-hud.md).

Та же старая деревня/карта с рекой и тот же renderer/pump, меняется только
диагностический HUD: **1** FULL (6 строк), **3** MIN (2 строки), **0** OFF
(без панели/текста), **`*`** в OFF показывает замороженный результат, **5** home.
Снять FULL → MIN → OFF+`*` → FULL в одной позе, по 8–10 секунд на режим.
Геометрия/река/свет/текстуры не изменены; это проверка цены самого счётчика,
не обещание ускорения полной игры. Прежние JAR сохранены.

## Frame Pump: Canvas SELF и GameCanvas FLUSH (отдельный тест)

[Quantum_FramePump_Test.jar](Quantum_FramePump_Test.jar) ·
[JAD](Quantum_FramePump_Test.jad) ·
[методика и результаты предыдущего контроля](../docs/frame-pump.md).

**1** WORKER Canvas, **3** SELF Canvas без пробуждения worker на кадр,
**5** однопоточный GameCanvas + flushGraphics. Все без sleep (S0).
**7** FULL/MIN HUD, **#** выход. Подождать 8–10 секунд и снять все три
режима с одинаковым HUD. На телефоне новый тест при публикации ещё не
измерен; прирост FPS не обещается. Игровые сборки и Empty Frame сохранены.

# Quantum — M3G preverified preview

## Empty Frame — расходы пустого кадра, без карты

[JAR](Quantum_EmptyFrame_Test.jar) · [JAD](Quantum_EmptyFrame_Test.jad) ·
[Отчёт](Quantum_EmptyFrame_Test.build-report.json) · [Методика](../docs/empty-frame.md).

Standalone ~10КБ, **никакой карты/текстур/geometry render calls**. 9 режимов:
Canvas fill, Image copy/fill, M3G bind/clearRGB/clearRGBZ/no-depth, optional direct
Canvas, IDLE. **0/2** следующий/предыдущий, **1** Canvas, **3** Image, **5** RGBZ;
**\*** SYNC/QUEUED pump, **7** FULL/MIN HUD, **9** sleep(1)/вообще без sleep, **#** exit.
**I/P/G** — interval / measured paint / остаток между measured paints (не чистый
OS wait). **B/S/K/E** отдельно native bind/state/clear/release, **F/C/H/Q**
fill/copy/HUD/request queue. svc/sl(last) не оконные средние, svc перекрывает paint.
Снять1→3→5, затем9 в том же режиме, по8сек/фото. Остальное сравнивать по одному
переключателю. S0 может увеличить CPU load; только короткие замеры.
Старые JAR/игра не менялись. Phone FPS новой сборки ещё не измерен, выигрыш не обещан.


## Старая деревня — строгое planar-упрощение перед новым bake

[JAR](Quantum_Village_Planar.jar) · [JAD](Quantum_Village_Planar.jad) ·
[Модель для bake](Quantum_Village_Planar_Model.zip) ·
[Отчёт](Quantum_Village_Planar.build-report.json) · [Методика](../docs/village-planar.md).

Старая карта sym9, не portal map. **4750→4748 triangles**, 10032→10028 native
corners: только один exact planar patch8→6, без сдвига позиций/UV/границы.
Дубликатов/вырожденных triangles не найдено. Это минимальный строгий проход,
**заметного FPS-выигрыша не ожидается**, не «массовая оптимизация без потерь».
**0** ORIGINAL/SIMPLE, **1** SIMPLE, **3** ORIGINAL, **\*** к изменённой детали,
**5** исходная камера. Свет **NEUTRAL в обоих вариантах**, исходный bake не применён.
ZIP содержит .3d/.draworder и временную neutral VLA: свет надо запечь заново.
Renderer/GRID/near128/far60000/текстуры/река прежние; старые JAR/основная игра сохранены.


## Portal Map BVH — порталы + BVH внутри комнат, map-selo.png

[JAR](Quantum_PortalMap_Bvh.jar) · [JAD](Quantum_PortalMap_Bvh.jad) ·
[Отчёт](Quantum_PortalMap_Bvh.build-report.json) · [Методика](../docs/portal-map-bvh.md).

Atlas **maps/kordos/map-selo.png из sym9**, не map-selo1; одинаковый в ON/OFF.
Запуск **BVH ON F40000**, порталы всегда ON, BSP нет. **0** BVH toggle,
**1** ON, **3** OFF, **\*** far40k→30k→20k→40k, **5** исходная камера.
В исходном desktop ракурсе1384→498tri при том же1call; это не результат FPS E65.
Холодные вершины/atlas, один call на комнату,2-entry cache выбранных индексов;
новая маска при движении может строить индексы, стационарно — нет (HUD U).
Фото **ON→OFF→ON с одинаковым far и камерой**, около8сек на режим, FPS/N/R/T/DC/VB.
Geometry-only, без неба/погоды/gameplay. Старые сборки/основная игра не изменены.


## Portal Map Far — 40000 / 30000 / 20000

[JAR](Quantum_PortalMap_Far.jar) · [JAD](Quantum_PortalMap_Far.jad) ·
[Отчёт](Quantum_PortalMap_Far.build-report.json) ·
[Методика](../docs/portal-map-far.md).

Отдельный far-only тест новой карты, **порталы всегда ON**, без BVH/BSP.
Запуск40000, **0** цикл40k→30k→20k→40k; **1**40k, **3**30k, **\***20k.
Переключение сохраняет позу и холодные buffers, останавливает движение и заново
прогревает статистику. Всё остальное как в PORT ON/OFF; родитель не изменён.
**T/DC/VB/Rooms при одной камере не уменьшатся**: selector прежний, меняется
только native clipping far. Возможный FPS-выигрыш проверяем на телефоне, не обещаем.
Фото40→30→20→40 без движения, примерно8сек на режим, FPS/N/R и вид обрезания.
Geometry-only, без неба/погоды/gameplay во всех3 режимах; исходные assets неизменны.

## Новая карта с порталами — ON/OFF, без BSP

[JAR](Quantum_PortalMap_Test.jar) · [JAD](Quantum_PortalMap_Test.jad) ·
[Отчёт](Quantum_PortalMap_Test.build-report.json) ·
[Методика и ограничения](../docs/portal-map.md).

15 комнат/18 порталов из нового `lightmap.zip.txt`, оригинальный RGB-lightmap,
**DMODE13/scale6/DIST40000**. Запуск **PORT ON**, **0** ON↔OFF, **1** ON,
**3** OFF, **5** исходная камера. Фото ON→OFF→ON в одной неподвижной позе,
дождаться числового FPS и следующего окна. HUD Rooms/T/DC/VB и J/B/K/R/E.
В стартовом desktop ракурсе ON1комната/1384tri/1call, OFF15/3761/15 — не FPS E65.
Все room vertex/index buffers cold; неподвижный selector тоже кэшируется (`C`).
**Geometry-only**: без неба/дождя/игровой логики в обоих режимах; все6 исходных
файлов и конфиг сохранены без изменений. Это не прогноз полного игрового FPS.
Основная игра/старые сборки сохранены, BSP не возвращён. Телефонных замеров пока нет.

## BSP-тест снят

По отзыву пользователя FPS упал примерно вдвое. BSP-код, подготовленные данные,
сборщик, тесты и отдельные BSP JAR/JAD удалены из текущей ветки. Основная игра
не переводилась на BSP; для следующих сравнений остаётся прежний GRID+Z.
[Результат и исторический откат](../docs/static-room-bsp.md).

## M3G Perspective — ON/OFF на настоящей карте

[JAR](Quantum_StaticRoom_Perspective.jar) · [JAD](Quantum_StaticRoom_Perspective.jad) ·
[Отчёт](Quantum_StaticRoom_Perspective.build-report.json) ·
[Методика и ограничения](../docs/static-room-perspective.md).

Запуск **PERS ON**, **0** переключает ON↔OFF, **1** всегда ON, **3** всегда OFF,
**5** исходная камера. Снять ON→OFF→ON в неподвижной позе, после прогрева
и обновления числовых окон (примерно8секунд на режим).
Меняется только `PolygonMode.setPerspectiveCorrectionEnable`, камера/GRID/буферы/
индексы/свет/alpha/река/far60000 прежние. OFF — **подсказка M3G**, не гарантированное
принудительное affine: драйвер может её игнорировать. Выигрыш Software P0 здесь
не обещается; нужны FPS/N/R и вид текстур на E65. Основная игра и старые JAR сохранены.
По отзыву пользователя на E65 эффект ON/OFF почти незаметен; точных чисел нет.

## Software PolyFill — полигонаж/fillrate старого CPU Quantum

[JAR](Quantum_SoftwarePolyFill_Test.jar) · [JAD](Quantum_SoftwarePolyFill_Test.jad) ·
[Отчёт](Quantum_SoftwarePolyFill_Test.build-report.json) ·
[Методика/ограничения](../docs/software-poly-fill.md).

**5** полный auto14 нагрузок, таблица **4/6**. По умолчанию **SW P4**,
**\*** переключает **P0 affine** и очищает результаты; снова **5** для второго прогона.
Настоящий frozen DirectX7.SOFTWARE: clear/project/visibility/queue/sort/raster/drawRGB.
Те же сетки128..28800 треугольников, слои1..32, checker64, nominal viewport W×(H−90).
R=CPU pipeline, C=drawRGB, I=frame interval; сравнивать с native PolyFill при том же
viewport. **Нет Z-буфера**, не равный native feature set и не прогноз FPS карты.
Ни M3G вызовов, ни Nokia DirectGraphics; основная игра и старые JAR не менялись.
Фото нужны отдельно **P4/P0**, с заголовком/размером. Скорость телефона ещё не измерена.

## Карта на L2D_M3G — настоящий Node renderer против Quantum FULL

[JAR](Quantum_L2D_Map.jar) · [JAD](Quantum_L2D_Map.jad) ·
[Отчёт](Quantum_L2D_Map.build-report.json) · [Методика/адаптации](../docs/l2d-map.md).

Запуск **B L2D IMAGE**; **1 = A Q FULL IMAGE**, **3 = B**, **0 = A↔B**,
**5** исходная камера. Сравнить B/A/B в одной позе после числовых окон FPS.
**\*** — дополнительный C L2D CANVAS; при ошибке **3** возвращает Image.
Реальные MeshData3D2 и Renderer.addMesh(Node) из rmn20/l2d_m3g35c5c26.
Везде полные4750 треугольников/10032 вершины, те же UV/цвета/река/камера,
near128/far60000, без Java culling. Конвертация без потерь и order sidecar.
L2D RGB против Q RGBA(alpha255) — намеренно оставлен штатный формат.
Это renderer viewer, **не полный порт игровой логики**; старые сборки сохранены.
Не сравнивать абсолютный FPS с прежним GRID (там меньше подаваемых граней).
Телефонных замеров пока нет; сходная скорость не доказывает «виновата карта».

## Static Room Chunks — CHUNK_SIZE 60000 / 40000

[JAR](Quantum_StaticRoom_Chunks.jar) · [JAD](Quantum_StaticRoom_Chunks.jad) ·
[Отчёт](Quantum_StaticRoom_Chunks.build-report.json) ·
[Управление и методика](../docs/static-room-chunks.md).

Запуск **CHUNK60000**, **0** переключает60000↔40000 в одной неподвижной позе,
**1** — контроль прежней GRID4x4, **3** —40000, **5** — исходная камера.
Сравнить60000→40000→60000 после полных числовых FPS-окон.
Реальные world-unit ячейки с полными AABB граней; не старое3×3 ограничение соседями.
Дальность60000/alpha/свет/река прежние, исходные крупные VBs и calls по родителям,
не отдельный call на ячейку. HUD T/DC/VB/S/V/CUT и J/B/K/R/E.
Меньший размер не гарантирует ускорение; это измерение, не готовая оптимизация.
Tex3/Packets/Layouts и игровой renderer сохранены. Телефонных замеров ещё нет.

## Static Room Layouts — одинаковые calls, exact shared vertices, разбивка N

[JAR](Quantum_StaticRoom_Layouts.jar) · [JAD](Quantum_StaticRoom_Layouts.jad) ·
[Отчёт](Quantum_StaticRoom_Layouts.build-report.json) ·
[Управление и методика](../docs/static-room-layouts.md).

**1 = A LARGE**, **3 = D SHARED**, **0 = A→B→C→D**, **5 = исходная точка**.
Сначала сравнить неподвижно **A/D/A**: ровно прежние calls, D объединяет только
одинаковые XYZ/UV/RGBA (8790 вместо10032 резидентных вершин, не обещание FPS).
B LARGE/SPLIT и C COMPACT512 имеют одинаковые calls/strip batches — контроль
стоимости организации буферов без прежнего смешения с ростом calls.
**B специально тяжёлый**; ждать числового FPS, не судить по warming.
J/B/K/R/E разделяют N на prepare/bind/state+clear/render/release; не GPU timestamps.
Карта, атрибуты, порядок граней, alpha, река, проекция и Image target прежние.
Все182 класса/все assets Tex3 сохранены; отдельный MIDlet, старые JAR не заменены.
На телефоне эта сборка ещё не измерена.

## Static Room Packets — A/B размера vertex buffer на той же карте

[JAR](Quantum_StaticRoom_Packets.jar) · [JAD](Quantum_StaticRoom_Packets.jad) ·
[Отчёт](Quantum_StaticRoom_Packets.build-report.json) ·
[Как сравнить и что означают счётчики](../docs/static-room-packets.md).

**A LARGE по умолчанию / 0 → B COMPACT512 / 0 → A**, **5 = исходная точка**.
Одинаковые GRID visibility, T/strip order/XYZ/UV/свет/alpha/материалы/река,
near128/far60000/FOV74. B — точные срезы прежних buffers, ≤512 вершин,
пространственно-локальные непрерывные группы без перестановки граней.
HUD: FPS, N/C/I, **T / D / VB**, S/RES. VB — ёмкость переданных буферов,
**не аппаратный счётчик обработки вершин**. Меньше VB, но больше calls —
ускорение не обещается. Замерять неподвижно A/B/A после8секунд ожидания.
Все182 старых класса и assets Tex3 неизменны, добавлены9 diagnostic classes.
Это отдельный тест, не замена основной сборки; Tex3/PolyFill сохранены.

## PolyFill — отдельный тест полигонажа и fillrate

[JAR](Quantum_PolyFill_Test.jar) · [JAD](Quantum_PolyFill_Test.jad) ·
[Отчёт](Quantum_PolyFill_Test.build-report.json) ·
[Управление, метрики и ограничения](../docs/poly-fill.md).

**5 = полный автопрогон**, около минуты, затем таблица. **1 = POLY**, **3 = FILL**,
**2/8 = нагрузка**, **7 = результаты**, **# = выход**. POLY: 128…28800 треугольников
при постоянной площади; FILL: 1…32 fullscreen-слоя, depth test/write OFF.
Один native render call, opaque checker64², alpha threshold0.5 всегда ON.
FPS, Ktri/s и nominal Mpix/s относятся к целому тестовому пути, **не к паспортной
производительности GPU**. N/C/I отдельно; первый телефонный прогон записан в описании.
Все182 класса Tex3 и все его assets побайтно сохранены; карта и река не менялись.
Отдельное имя MIDlet, исходный Tex3 и остальные rollback JAR остаются на месте.

## Static Room Tex3 — один atlas-слот, река прежняя

[JAR](Quantum_StaticRoom_Tex3.jar) · [JAD](Quantum_StaticRoom_Tex3.jad) ·
[Отчёт](Quantum_StaticRoom_Tex3.build-report.json) ·
[Изменения и проверки](../docs/static-room-textures.md).

Три записи `map-selo1.png` сведены к одной: **5 material slots → 3**
(лес0, карта1, река2). У реки только перенумерован слот4→2; все4 изображения,
скорость6, режим7 и остальная строка WTEX сохранены. Геометрия/UV/свет прежние,
`.vla` побайтно прежняя. Startup-permutation сохраняет старый native draw order.

Default **B GRID**, **0 = A BVH / B GRID**; alpha test всегда ON, near128/far60000.
1728 original-ZIP / edited-packet comparisons PASS, включая736 river draw states;
11 resource tests / preverify PASS. PNG и раньше был один, native packets
уже объединялись: это cleanup лишних слотов, не обещание FPS. Старые JAR сохранены.

## Static Room BVH — другое отсечение, alpha test включён

[JAR](Quantum_StaticRoom_Bvh.jar) · [JAD](Quantum_StaticRoom_Bvh.jad) ·
[Отчёт](Quantum_StaticRoom_Bvh.build-report.json) ·
[Описание и проверки](../docs/static-room-bvh.md).

**A BVH по умолчанию / 0 → B GRID / обратно A**. BVH отсекает поддеревья полных
face-AABB, до8 граней/лист; B — прежняя сетка4×4. Alpha discard всегда исходный
ON, кода отключения и StaticRoomAlpha-классов **в новом JAR нет**. Старый
Alpha-тест ниже оставлен отдельно для отката. Near128/far60000 фиксированы,
back-face CULL_BACK/CW, текстуры, свет, геометрия и конфиги прежние.

В контрольной позе desktop-теста T2090→493; 864 независимые clipping-проверки,
208 tree cases, original GRID equivalence, native OOM safety и preverify PASS.
Это не FPS: BVH занимает heap и делает больше CPU-проверок. Пользователь
сообщил A N98/I126/T1218/V363, B N97/I122/T2864/V33, FPS8 у обоих —
прирост не подтверждён; в следующем Tex3 по умолчанию оставлена сетка. HUD **T** — отправленные треугольники, **V** — проверки bounding box.

## Static Room Alpha — временно отключить alpha test

[JAR](Quantum_StaticRoom_Alpha.jar) · [JAD](Quantum_StaticRoom_Alpha.jad) ·
[Отчёт](Quantum_StaticRoom_Alpha.build-report.json) ·
[Управление и ограничения](../docs/static-room-alpha.md).

Отдельный A/B-тест деревни на базе неизменённого Depth: **по умолчанию A alpha
OFF**, **0 = B alpha ON / обратно OFF**. Меняется только discard-порог,
не blending, не RGBA-пиксели и не геометрия. Near128/far60000 фиксированы;
**5 здесь ничего не меняет**, в отличие от прежнего Depth. Старые JAR сохранены.

OFF может заполнить прозрачные вырезы прямоугольниками и изменить перекрытие
дальних поверхностей. Любая разница FPS включает и изменение overdraw, а не
только стоимость alpha test. 864 real pose/frame comparisons, 1320 cutout draw
states, ON exactly Depth, OFF only threshold, cold Viewer / animation / native
failure / OOM / preverify PASS. Пользователь сообщил A OFF N82/I109,
B ON N85/I109, FPS9/8: устойчивое ускорение не установлено; в новом BVH
alpha test оставлен включённым.

## Quantum Model Optimizer — отдельная офлайн-утилита для ПК

[Скачать один HTML](Quantum_Model_Optimizer.html) ·
[ZIP с CLI и исходниками](Quantum_Model_Optimizer.zip) ·
[Инструкция](../tools/model-optimizer/README.md) ·
[Проверка исходной деревни](../docs/model-optimizer-validation.json).

Откройте скачанный HTML в современном браузере: выбрать `.3d`, исходную
`.vla` (если используется baked-свет), настройки и скачать **новую пару**.
Сеть, установка и загрузка моделей на сервер не нужны. CLI — Node18+.
Это не JAR для E65 и не патч игры. Native-углы считаются отдельно от
треугольников; рост потока при неудачной триангуляции отклоняется.

17 Node/WASM-тестов, независимое чтение 4 вариантов реальной деревни,
перенос UV/света, все 58 positional-компонент и неизменность material4 PASS.
Проверены загрузка, WASM, скачивание `.3d` / `.vla` / JSON и мобильная вёрстка
в Chromium, включая запуск единственного HTML через `file://`, без сетевых
запросов. Бережно: **4750→4742** треугольника; экспериментально с большим
допуском и разрешением менять швы: **4750→2534**. Последнее может портить
текстуры/свет. На телефоне не проверено, больший FPS не обещается.
**Все прежние игровые сборки и исходные ресурсы сохранены.**

## Static Room Depth — E65: проверка просвечивания стен и дальности

[JAR](Quantum_StaticRoom_Depth.jar) · [JAD](Quantum_StaticRoom_Depth.jad) ·
[Отчёт](Quantum_StaticRoom_Depth.build-report.json) ·
[Ограничения и проверки](../docs/static-room-depth.md).

Отдельный лёгкий тест без объединения полос: **0 = A near128 / B near1**,
**5 = far60000 / 68500**, по умолчанию A/60000. Сокращение far до60000 разрешено
пользователем; исходный level1.txt оставлен побайтно прежним, меняется runtime
параметр с явным возвратом. Проверяем точность глубины после сообщения о мебели
через внешние стены всех зелёных домов на E65, включая предыдущие сборки.
Near128 может подрезать очень близкую поверхность! Это диагностическая гипотеза,
не подтверждённое исправление на телефоне. 3456 near/far/pose/frame cases,
original near1 equivalence, independent culling, byte identity и preverify PASS.
Все прошлые JAR сохранены.

## Static Room Strips — проверка тысяч полос внутри M3G calls

[JAR](Quantum_StaticRoom_Strips.jar) · [JAD](Quantum_StaticRoom_Strips.jad) ·
[Отчёт](Quantum_StaticRoom_Strips.build-report.json) ·
[Показания и ограничения](../docs/static-room-strips.md).

**0 = A joined / B original strips**, одинаковые culling и Image target.
Full-room strips 2641→3 при тех же 10032 вершинах/4750 исходных треугольниках.
Это не число GPU drawcalls: связывающие нулевые треугольники увеличивают indices
10032→15840 и могут как помочь драйверу, так и замедлить. 864 exact ordered
pose/river comparisons PASS; прежние артефакты сохранены. Новый J/B/D/R HUD
разделяет Java prep, bind+clear, render calls и release. Нужен тест устройства,
не обещание FPS и не полноценная игра.

## Static Room Present — прямой вывод и диагностика FPS

[JAR](Quantum_StaticRoom_Present.jar) · [JAD](Quantum_StaticRoom_Present.jad) ·
[Отчёт](Quantum_StaticRoom_Present.build-report.json) ·
[Расшифровка времени кадра](../docs/static-room-presentation.md).

Отдельный облегчённый тест: **0 = A direct Canvas / B Image + drawImage**.
**Оба режима с отсечением**, это не прежнее cull/whole A/B. A не создаёт
промежуточную полноэкранную Image и не копирует её через LCDUI. Новый viewer
не выделяет ненужный DirectX7 CPU framebuffer; HUD-строки кэшируются на 1s.
Те же ресурсы, дальность и native renderer; прежний Cull Lite не изменён.
N/C/P/F/I — wall-clock миллисекунды, не GPU timestamps. На телефоне ещё не
проверено: нужен FPS и строки времени A/B из одной позиции после прогрева.
Если Canvas target не поддерживается, **0** выбирает native Image после ошибки.

## Static Room Cull Lite — облегчённый тест комнаты

[JAR](Quantum_StaticRoom_Cull_Lite.jar) · [JAD](Quantum_StaticRoom_Cull_Lite.jad) ·
[Состав и хеши](Quantum_StaticRoom_Cull_Lite.build-report.json) ·
[Описание и проверки](../docs/static-room-lite.md).

**735 КБ вместо 7,88 МБ (−90,68%)**. Только 10 необходимых ресурсов: целые
конфиги, модель комнаты, baked lightmap, две текстуры и четыре кадра реки.
Неиспользуемые музыка/звуки/NPC/небо/прочие карты исключены из Lite. Все 162
preverified class-файла оставлены без изменений для совместимости Java ME.
Кнопка `0`: те же **cached cull / cached whole**; прежние картинка и дальность.
Cold load и A/B готового Lite JAR, без подложенного texture cache: PASS.
Размер JAR не является измерением heap/FPS. На телефоне Lite ещё не проверен.
Полный Cull и все старые сборки для отката сохранены.

## Static Room Cull — отсечение без роста draw calls

[JAR](Quantum_StaticRoom_Cull.jar) · [JAD](Quantum_StaticRoom_Cull.jad) ·
[Отчёт](Quantum_StaticRoom_Cull.build-report.json) ·
[Инструкция и ограничения](../docs/static-room-culling.md).

**Новые A/B:** `0` переключает **cached cull / cached whole**. B теперь тоже
кэшированный, не projected. До 3 draws, в стартовой точке в среднем 2337 вместо
4750 отправленных треугольников по 24 углам камеры; это не замер FPS телефона.
864 вида проверены независимым homogeneous triangle clipping — ни одного
potentially visible треугольника не отброшено. При новой маске видимости возможна
аллокация native indices; тёплая фиксированная маска не аллоцирует. Все прежние
JAR сохранены; исходники v2 и его артефакты закреплены для отката.


## Static Room v2 — продолжаем новый режим, откат сохранён

[JAR](Quantum_StaticRoom_Merged.jar) · [JAD](Quantum_StaticRoom_Merged.jad) ·
[Отчёт](Quantum_StaticRoom_Merged.build-report.json) ·
[Инструкция / откат](../docs/static-room-experiment.md).

Первая комната: 3 вызова вместо 5, все 5 исходных материалов сохранены.
Соседние совместимые статические пакеты объединяются без перестановки граней.
96 ordered triangle/state/texture/camera witnesses против исходников v1 — PASS.
Это всё ещё отдельный A/B viewer, не полноценная игра и не замер FPS телефона.
Стабильный DrawCalls JAR и первый StaticRoom_Test ниже **не заменены**;
контрольные SHA-256: `configs/render-checkpoints.json`.


## sym9 — эксперимент Static Room A/B (не игра)

[JAR](Quantum_StaticRoom_Test.jar) · [JAD](Quantum_StaticRoom_Test.jad) ·
[Отчёт](Quantum_StaticRoom_Test.build-report.json) ·
[Инструкция и ограничения](../docs/static-room-experiment.md).

Отдельный MIDlet первой комнаты: `0` переключает resident world-space M3G geometry
и прежний projected painter. `4/6` поворот, `2/8` движение, `7/9` стрейф, `#` выход.
Нет NPC/неба/дождя/сохранений — это не замена полноценной игре.
Новый путь: 5 API draws, warm vertex/index allocations и uploads = 0 в contract test.
Настоящий depth buffer и perspective отличаются от painter/addsz; картинка и FPS
ещё требуют проверки на телефоне. Исходный движок/ресурсы DrawCalls JAR побайтно
сохранены; только новый тестовый entry point и 5 экспериментальных классов.


## Level41: исправление анимированной реки mode7, тест 48bd4d0

[JAR с исходными ресурсами q92](Quantum_Level41_River7_48bd4d0.jar) ·
[JAD](Quantum_Level41_River7_48bd4d0.jad) ·
[Отчёт сборки](Quantum_Level41_River7_48bd4d0.build-report.json).

**Это q92 с Level41, не Stlkr.** Исправлена ошибка `M3G required: unsupported object
code.Rendering.Meshes.LightedPolygon4V material=7`: [ordered depth-band
retry для MODULATE](../docs/m3g-level41-river7.md), без Software fallback.
В overlay только river WTEX явно назначен mode7; четыре исходных PNG и 8 fps
анимации сохранены. Остальные 504 ресурса побайтно равны исходному q92 ZIP;
геометрия, lightmap, DIST, погода и scripts не изменены. ARM9 оптимизации включены.

11532 видимых river pose-frame случаев / 24 реальных quads / четыре кадра — PASS;
256 полных Level41 rows — warm B0 T0, strict CPU dispatch 0. Stlkr regression
строки неизменны. На устройстве и в полном игровом loop ещё не проверено;
прочие уровни q92 не прошли required-M3G gameplay аудит. Без профилировщика,
JSR-184 обязателен. Старые JAR/PDF сохранены.

**Перед установкой сохраните сейвы:** MIDlet ID прежний, установка может заменить
другую установленную Quantum и использовать её RMS. Проверка этой сборки —
загрузка Level41, подход к реке/берегу, разные ракурсы и смена animation frames.

## ARM9 / OMAP 1710: world buffers, тестовая сборка 9905fff

[JAR с ресурсами Stlkr](Quantum_Stlkr_M3G_ARM9_9905fff.jar) ·
[JAD](Quantum_Stlkr_M3G_ARM9_9905fff.jad) ·
[Отчёт сборки](Quantum_Stlkr_M3G_ARM9_9905fff.build-report.json).

Дополнение к предыдущему Canvas ARM9: [grow-only world buffers и кеш привязок
positions](../docs/m3g-arm9-world-buffers.md). Первый packet из двух треугольников
получает ёмкость 6 вместо 192 вершин; при необходимости она растёт до 192.
Это не сокращение отрисовываемых треугольников или гарантия меньшего GPU времени.
В fixture с 20 triangle/quad draws и изменением scale — 20→6 вызовов setPositions.
Качество, игровая логика и все 442 ресурса сохранены. Strict native строки
13 world-fixtures и level12/13 с idle objects совпадают с предыдущим ARM9;
CPU dispatch = 0. TreeFix и Canvas batching сохранены.

Без профилировщика, JSR-184 обязателен. На самом OMAP ещё не проверялось,
FPS/heap не измерены. Один retained world-buffer set, но постепенный рост может
создавать дополнительный cold garbage до GC. Перед установкой сохраните сейвы:
MIDlet ID прежний. Предыдущие JAR/JAD и PDF не заменяются.

## ARM9 / OMAP 1710: тестовая сборка db03b82

[JAR с ресурсами Stlkr](Quantum_Stlkr_M3G_ARM9_db03b82.jar) ·
[JAD](Quantum_Stlkr_M3G_ARM9_db03b82.jad) ·
[Отчёт сборки](Quantum_Stlkr_M3G_ARM9_db03b82.build-report.json).

[Оптимизация native Canvas foreground](../docs/m3g-arm9-canvas.md): соседние
packets до 16 quads, exact vertex counts, меньше attribute updates/делений,
array LRU без Vector и reusable source scratch. Синтетический mixed case:
D14→5, VertexArray.set 42→10; reload D5→1. Это не замер FPS телефона.
Все 442 ресурса прежние, tree-crossing fix сохранён; NPC, погода, DIST и качество
не менялись. 13 world-fixtures и level12/13 с объектами: CPU dispatch 0,
диагностические строки совпадают с TreeFix.

Без профилировщика, JSR-184 обязателен; на реальном OMAP ещё не проверено.
Это переносимый Java ME JAR, а не ARM-native машинный код. Есть обмен памяти на
меньше GC/API работы (source scratch до 64 KiB плюс lazy packet pools).
Перед установкой сохраните сейвы: MIDlet ID прежний. Предыдущие сборки сохранены.

## Исправленная тестовая сборка TreeFix e1dc319

[JAR с ресурсами Stlkr](Quantum_Stlkr_M3G_TreeFix_e1dc319.jar) ·
[JAD](Quantum_Stlkr_M3G_TreeFix_e1dc319.jad) ·
[Отчёт](Quantum_Stlkr_M3G_TreeFix_e1dc319.build-report.json).

Исправлены отказ native fog при проходе через плоскость спрайта и сбой/цикл
переноса текста диагностики. [Причины и проверки](../docs/m3g-sprite-crossing-fix.md).
1182 тестовых crossing frames, включая PNG обоих деревьев Stlkr; это API doubles,
не проверка на телефоне. Все 442 ресурса прежние; коллизии не менялись.
Без профилировщика, JSR-184 обязателен, CPU fallback нет.
Перед установкой сохраните сейвы: MIDlet ID прежний.
Старые тестовые и опубликованные файлы ниже не заменены.

## Тестовая M3G-only сборка a470031 (до исправления деревьев)

[JAR с ресурсами Stlkr](Quantum_Stlkr_M3G_Test_a470031.jar) ·
[JAD](Quantum_Stlkr_M3G_Test_a470031.jad) ·
[Отчёт сборки](Quantum_Stlkr_M3G_Test_a470031.build-report.json).

Этап 4a: native Scene и Canvas foreground оружия/прицелов/reload/overlay.
Без профилировщика; JSR-184 обязателен, Software fallback отсутствует.
442 ресурса совпадают с прежней полной сборкой, включая агрессивные конфиги.
CLDC compile/preverify выполнены; на телефоне ещё не проверено.
MIDlet ID прежний: перед установкой сохраните сейвы.
Это отдельный тестовый файл; опубликованная двухрендерная сборка ниже не заменена.


[Справочник эффектов опубликованной версии b0ee49b](../docs/m3g-modes-guide.md):
0–13, DECAL/EXP/BLEND, спрайты, небо, урон/смерть/виньетка и CPU fallback.
Это документация двухрендерной сборки ниже, **не готовой M3G-only версии**.
[PDF — справочник эффектов b0ee49b](Quantum_M3G_Render_Modes_RU.pdf).

**18.10.2026 — агрессивный профиль всех 13 уровней Stlkr:**
на открытых картах DIST 20000–24000 и более плотный Fog; канализация 12000,
бар 10000, комната 8000, промзона 18000 (статический mode9 без Fog).
**Дальняя видимость заметно сокращена**. Не удалены погода, NPC, задания или
скрипты; меняются только DIST/FOGD. Оригинальный архив сохранён.

Все 13 world-карт проверены на 2384 ракурсах: сумма D **31039 → 23634 (−23,9%)**.
В отдельных проверках с idle-объектами: level12 максимум **105 → 69**,
level13 **75 → 71** относительно исходных конфигов. В одном ракурсе level13
стало на 2 draws больше. Уровни 3/4 не выиграли по D. Это **не FPS телефона**.
[Параметры всех уровней, методика и откат](../docs/stlkr-all-levels-aggressive.md).

**Текущая полная Stlkr:** [JAR](Quantum_Stlkr_FogBatch_ARM9.jar) /
[JAD](Quantum_Stlkr_FogBatch_ARM9.jad), **10075378 байт**, SHA-256
`23d890bffa9fd9fd1534ea38133ea6e4e5a19c2c1ae9cf56044111a4257a2072`.
[Все 13 конфигов ZIP](Stlkr_Aggressive_Levels.zip) ·
[Build report](Quantum_Stlkr_FogBatch_ARM9.build-report.json).
149 классов движка прежние; изменены только 13 из 442 ресурсов.
`Quantum.jar` без игровых ресурсов не обновлялся. Опциональный overlay:
`--resource-overlay configs/stlkr`, только вместе с исходным Stlkr-архивом.

**Ниже — история предыдущих этапов; их хеши и настройки не описывают текущий JAR.**

**18.10.2026 — отдельный оптимизированный конфиг Stlkr level12:**
`DIST=39000 → 30000`; туман `FOGD=26000`, дождь, объекты и скрипты прежние.
Это **сокращение дальности**, не визуально идентичная оптимизация.
В 80 прогретых ракурсах с idle-объектами: сумма D **2412 → 2217 (−8,1%)**,
максимум **105 → 93**, треугольники **7981 → 7026**. 24 позы улучшены,
55 прежние, одна +1 draw. Это не замер FPS телефона.
[Описание, выбор настроек и откат](../docs/level12-config-optimization.md) ·
[Изменённый level12.txt](../configs/stlkr/level12.txt).

**Полная Stlkr предыдущего этапа:** [JAR](Quantum_Stlkr_FogBatch_ARM9.jar) /
[JAD](Quantum_Stlkr_FogBatch_ARM9.jad), **10075348 байт**, SHA-256
`b7307cc5fd1b6e653ac03debccb36616ecbfb71e324fc0a5cecd24404bb69692`.
[Build report полной сборки](Quantum_Stlkr_FogBatch_ARM9.build-report.json).
Все 149 class entries прежние; из 442 ресурсов изменён только level12.txt.
Оригинальный архив нетронут. Level13: 320/320 контрольных строк прежние.
**Движок Quantum.jar не обновлялся:** 477604 байт, без игровых ресурсов и
профиля дальности. Профиль включается при сборке только через
`--resource-overlay configs/stlkr`; прочие игры не затрагиваются.

Ниже — история этапов; старые хеши полной сборки не относятся к текущему JAR.

CLDC 1.1 / MIDP 2.0. Preverified with ProGuard 6.2.2 -microedition.

**18.10.2026 — точное упрощение полностью насыщенного native Fog:**
в тяжёлой контрольной позе Stlkr level12 **D108 → 105**, в отдельных
ракурсах до 7 draws меньше; 12/80 поз улучшены, ухудшений в наборе нет.
Только непрозрачные поверхности, целиком за плотным LINEAR Fog. Видимая
граница тумана и cutout/alpha сохранены; Fog не отключён. Level13 D75 прежний.
Это небольшой выигрыш по API-вызовам, **не замер FPS телефона**.
[Методика, ограничения и проверки](../docs/m3g-saturated-fog-batching.md).
Текущий движок: **477604 байт**, SHA-256
`af4461067cd430df34ea2eba585f9aa59a67d7578068e1eb968cd350c63c22b9`.
Полная Stlkr: **[JAR](Quantum_Stlkr_FogBatch_ARM9.jar)** /
[JAD](Quantum_Stlkr_FogBatch_ARM9.jad), **10075349 байт**, SHA-256
`3aa534751a3b3cfa952be849dfc4328c8e0ba9f43cc6f9a5e6193d80f2845b76`.
Все предыдущие Fog/Sprite/AI оптимизации включены, 442 ресурса неизменны.
CLDC preverification пройдена; `runtime_tested=false`.

**18.10.2026 — оптимизация CPU-нагрузки ИИ NPC:** один проход для врага и
следования, ранняя фильтрация фракций, без временных FOV-векторов и с точной
пограничной математикой. В тесте 96 NPC: чтения списка **36 864 → 9 216**;
при FOV on sqrt **29 008 → 264**. Реакция, атаки, raycast и частота update
не замедлялись. Это счётчики операций, **не FPS телефона**.
[Проверки и ограничения](../docs/ai-selection-optimization.md).
Движок этапа ИИ: **476884 байт**, SHA-256
`d7d4527655a4b6807d0685f905cc5ab68c3d5601e883c80a02d413c32dbbe092`.
Сборка этапа ИИ: **[Quantum_Stlkr_FogBatch_ARM9.jar](Quantum_Stlkr_FogBatch_ARM9.jar)**
/ [JAD](Quantum_Stlkr_FogBatch_ARM9.jad), **10074630 байт**, SHA-256
`89746039134f288eb7c3767f56a8780a8d37f4eb21efc8964ad57201c87834f2`.
Имя сохранено; включает Fog/Sprite batching и ИИ, все 442 ресурса неизменны.
CLDC preverification пройдена; `runtime_tested=false`.

**18.10.2026 — batching спрайтов NPC:** совместимые соседние спрайты
одного скина/кадра собираются по 32, без сортировки и изменения native Fog.
Тест 70 совместимых billboards: **70 → 3 sprite draws**, warm B:0/T:0;
белые vertex colors больше не передаются для каждого NPC. **В 80 позах
Stlkr level13 D не уменьшился** (тяжёлый кадр остаётся 75): совместимых
смежных текстур там не было. Для разных скинов нужен отдельный atlas design.
[Результаты, ограничения и проверки](../docs/m3g-npc-sprite-batching.md).
Движок этапа Sprite batching: **476014 байт**, SHA-256
`4b5b222c8b56409533b8cf31561b1eb1de128c04000ee62fd46bbbb530c4775d`.
Полная сборка с ресурсами: **[Quantum_Stlkr_FogBatch_ARM9.jar](Quantum_Stlkr_FogBatch_ARM9.jar)**
/ [JAD](Quantum_Stlkr_FogBatch_ARM9.jad); имя сохранено; актуальная сборка описана выше.
Это API submission tests, не замер FPS на телефоне.

**18.10.2026 — точное объединение native Fog-пакетов:** в тяжёлом кадре
Stlkr level13 **D:95 → 75**, world **74 → 54**. Туман не отключён:
используются только точные двоичные сдвиги координат, без округления.
Во всех 320 контрольных замерах совпал упорядоченный native triangle stream;
Software, порядок, UV/цвет/глубина и CPU recovery сохранены. Это draw counts,
не замер FPS на телефоне. [Результаты и воспроизведение](../docs/m3g-fog-exact-rebase.md).
Движок этого этапа: **474685 байт**, SHA-256
`b086af16b5617be66692c9e2201b6b100847c3dfeb02c89f3c777d3d62e4d0d4`.
`Quantum.jar` здесь — движок без игровых ресурсов; полная Stlkr-сборка
с 442 неизменёнными ресурсами опубликована по ссылке выше.

**18.10.2026 — оптимизация M3G для ARM9 / OMAP1710:** убраны лишние
framebuffer round trips точного MeshImage; нужные CPU barriers детей сохранены.
В прогретом тесте 20 meshes: **D:21 → 2, B:20 → 0**, без перестановки геометрии.
Для 16 видимых капель передаются **64 вершины вместо 128**; native rain/snow
сохранены. World projection переиспользуется, смежная текстура не сканирует
Vector. Software и все 505 оригинальных ресурсов сохранены. Это сокращение
работы/API transfers, не замер FPS и не утверждение о 3D-ускорителе OMAP1710.
[Результаты, ограничения памяти и проверки](../docs/m3g-arm9-omap1710.md).
SHA-256 предыдущего движка: `d7a03d96d0855d4d7103d11a67b12da10a68478073414a2f21c1b6491e707374`.

**18.10.2026 — material 12 = native EXP Fog:** M3G world-полигоны используют
Fog.EXPONENTIAL. Для материалов режима 12 в общих параметрах level.txt:

```ini
M3G_EXP_FOG_COLOR=128,144,160
M3G_EXP_FOG_DENSITY=0.05
```

Density больше — туман гуще; 0 отключает. Без ключей: обычный FOGC и density
0.02. Fog quality OFF отключает эффект. LINEAR 3/6, DECAL 11 и Software сохранены.
EXP учитывается в batching/clipping; CPU replay использует экспоненциальные
vertex factors, не прежний dithering. Native sky shader EXP не поддерживает.
[Единицы плотности, ограничения и проверки](../docs/m3g-mode12-exp-fog.md).
SHA-256 предыдущего движка: `6e5a20684be2fc03fc7e60f67d4e5ae6092f8ab5c94c144fb5e150a523daa3be`.

**18.10.2026 — material 11 = native DECAL:** для M3G world и model sky
используется Texture2D.FUNC_DECAL с исходной RGBA-альфой, без Fog/dithering.
Текстура смешивается с белым цветом вершины, не с фоном кадра: A=0 даёт белый,
не отверстие. CPU fallback/replay воспроизводит эту формулу; Software не изменён.
Mode 12 позднее заменён на EXP Fog — см. правку выше. Clipping, cache/batching и certified sky B:0 сохранены.
[Формула, ограничения и проверки](../docs/m3g-mode11-decal.md).
SHA-256 предыдущего движка: `d3130d0d7ad2d32b3572c80f2732d69ab0cafb9976f6438de1946085042bcc19`.

**18.10.2026 — отсечение геометрии для native M3G:** пересекающие near plane
полигоны и чрезмерно выходящие за viewport грани теперь подготавливаются
геометрически и рисуются M3G вместо CPU raster. UV/RGB, Fog, порядок и atomic
fallback сохранены. После прогрева во всех 256 диагностических кадрах level41
**B:0/T:0**, включая combined, где раньше B был 0…2. Это CPU clipping + native
растеризация, не обещание нулевого B при любых объектах и не FPS-замер.
Software и все 505 исходных ресурсов сохранены. Кэш view добавляет 3 int/Vertex.
[Реализация, память, ограничения и проверки](../docs/m3g-world-clipping.md).
SHA-256 предыдущего движка: `2610ac7157a53fc4279b4efdd4eb11e9e441a5b00fabc06c5aed7fe4d5aafd3d`.

**18.10.2026 — убран лишний B модельного неба:** для проверенного замкнутого,
непрозрачного полноэкранного mesh предыдущий Image больше не читается и не
загружается обратно. Скрытая native clear-подложка сохраняет полный CPU replay
при отказе. В level41 небо + дождь: **D:2, B:0, T:0**; B общей сцены 0…2
вместо 1…3 в проверенных ракурсах. CPU-переходы мира, прозрачное/открытое/
обрезанное небо не отключены. Software сохранён; второго framebuffer нет.
[Условия, ограничения и проверки](../docs/m3g-sky-background.md).
SHA-256 предыдущего движка: `1c01d0cd8fea6be0ac8fbe02785a982aa453d3e440a0febc78f34005ac5d6a66`.

**18.10.2026 — пакетирование 3D-неба:** соседние совместимые треугольники
модельного неба объединены в пакеты до 64 треугольников без перестановок.
В 64 диагностических ракурсах level41 небо: **16–27 draw → 1**;
с дождём без мира: **D:17–28 → D:2**. Геометрия, UV, материалы,
прозрачность, fallback и Software сохранены. B/CPU-переходы мира не устранены;
это уменьшение API-вызовов, не обещание такого же множителя FPS.
[Результаты и проверки](../docs/m3g-sky-batching.md).
SHA-256 предыдущего движка: `ca57f35d867357cd4a670da6c9076dbb87087ab083478137f294d2fc18b1ae92`.

**18.10.2026 — до KVM + Image presentation:** готовый M3G-кадр выводится через `drawImage`
без обязательного финального getRGB. Для CPU-переходов, паузы и сохранения
частичного фона чтение остаётся; при полном opaque sky повторные native-кадры
не требуют и отложенного чтения предыдущего Image. Второй framebuffer не добавлен.
Software — прежний drawRGB. [Условия и проверки](../docs/m3g-image-presentation.md).
FPS на устройстве этой сборкой не измерен.

Второй рендер: Настройки → Renderer → M3G (experimental).
Сразу после Renderer появляется HW perspective: ON/OFF (по умолчанию ON).
Влево — OFF, вправо — ON, FIRE/SELECT — переключить. Выбор сохраняется
при выходе из настроек и применяется после паузы без перезапуска игры.
Меняется native PolygonMode, а не исходный PERSQ, геометрия или Software;
CPU fallback сохраняет старое качество. Текстуры не перезагружаются.
Режим материала 5 заменён на native Texture2D.FUNC_BLEND без Material, нормалей,
ambient/directional и оружейного OMNI. Цвет задаётся в level-конфиге:

```ini
M3G_BLEND_COLOR=255,128,64
```

R,G,B от 0 до 255; без ключа/при ошибке — чёрный, без наследования прошлого уровня.
С белой основой формула RGB: 1 − texture + color × texture. Чёрный цвет даёт
инверсию, белый — белую поверхность; прозрачные отверстия сохраняются.
Это не обычная тонировка и не смешивание с framebuffer. Цвет один на уровень,
читается при загрузке, не каждый кадр. Software mode5 сохранён.
[Подробности и примеры](../docs/m3g-mode5-blend.md).
У NPC и остальных 3D-мешей включено аппаратное отсечение обратных граней
M3G (CULL_BACK, WINDING_CW). Существующее отсечение до сортировки/отправки
полигонов сохранено, включая анимированные MeshImage и прямой addMesh.
Небо, спрайты, погода и экранные эффекты остаются без backface culling.
Software, форматы моделей и анимаций не менялись.
Draw-вызовы сокращены: соседние совместимые полигоны мира/NPC/мешей
объединяются в пакеты до 64 треугольников без перестановки по текстурам.
В синтетических тестах 100 triangles → 2 draw, 100 quad → 4 вместо 100;
это не замер FPS. Сохранены прозрачность, свет, туман, геометрия и CPU rollback.
Буферы/индексы переиспользуются, вершины загружаются один раз на пакет.
Разные eye-space масштабы native Fog и отдельные эффекты
ограничивают объединение; не обещается один draw на всю сцену.
У полностью непрозрачных native-текстур альфа-тест отключается автоматически.
Один прозрачный texel сохраняет cutout; полупрозрачность не заменяется жёсткой
прозрачностью. Проверка совмещена с upload в кэш, повторного сканирования
на каждом кадре нет. Применяется к миру/мешам, спрайтам и модельному небу.
Туман материалов 3/6 и fog-спрайтов выполняется через M3G Fog.LINEAR.
Из 9/13 чёрный туман удалён: только исходные baked цвета, без дистанционного
затухания, в том числе при CPU fallback/replay выбранного M3G. Прежние настройки
качества цветов (fogQ=0 bypass, fogQ=1 grayscale для 13) и Software сохранены.
По умолчанию Software с оригинальным MIDP Graphics.drawRGB; DirectGraphics удалён.
Исправлена упаковка общих вершин M3G (без независимого смещения X/Y/Z),
согласованы веса/пороги перспективы и диагонали тумана с исходным рендером.
Модельное 3D-небо теперь использует отдельный M3G-путь с отсечением до проекции
и пересчётом UV. CPU-текстурирование неба принудительно не включается.
Уровень сохраняет M3G и туман. Дождь снова рисуется через M3G:
до 16 нетекстурированных RGB-полос с исходным наклоном, длиной и цветом по глубине.
Снег использует общий native batch с дождём; успешный проход не дублируется LCDUI.
Лимит симуляции сохранён: update/движения камеры/генерация работают только
с 16 дождевыми слотами. Random и активный список переиспользуются,
неактивные капли приостановлены. Снег сохраняет полный пул.
При отказе M3G остаётся ограниченный LCDUI fallback. Дождь/снег теперь входят
в общую транзакцию мира без отдельного weather backup/upload/readback.
Реальная скорость на телефоне не измерена.
Software сохраняет исходные полную плотность дождя, LCDUI/CPU-линии и грозу.
В M3G режимы лайтмап 9/13 используют статические цвета вершин: серый/RGB свет
без реакции на выстрелы и фонарь, без тумана и дистанционного затухания.
Software сохраняет исходную динамику освещения. Формат LIGHTMAP не менялся.
Фейковый additive-спрайт освещения при выстреле удалён: нет радиальной
текстуры, косметического raycast, отдельного кэша и дополнительного overlay draw.
Оружейный OMNI режима 5 тоже удалён; обычные ADD-спрайты сохранены.
Материалы 2/5/8 используют M3G: ADD, новый texture-combiner BLEND
и одноцветная заливка без загрузки текстуры. У quad режима 8 сохранены
раздельные цвета граней. В 2/5 возможны отличия округления на единицу канала;
исходный Software не менялся.
Материал 7 в M3G — исходная текстура с MODULATE: RGB фона умножается на RGB
текстуры. Белый сохраняет фон, тёмные/цветные участки затемняют и тонируют;
нулевой texel остаётся прозрачным, непрозрачный чёрный затемняет до чёрного.
UV/mip/перспектива и batching сохранены, отдельного захвата фона нет.
При CPU fallback/replay выбранного M3G также используется умножение.
Режим 2 остаётся ADD; номер 7 в ресурсах и оригинальный Software не изменены.
Материал 10 в M3G — MODULATE_X2: умножение RGB ×2 с насыщением. Средний серый
примерно нейтрален, тёмное затемняет, светлое осветляет. Сохраняются UV/mip,
кэш/batching; CPU fallback/replay также использует ×2, без прежнего dithering.
Материал 1 остаётся ALPHA, 2 — ADD, 7 — MODULATE; Software не изменён.
Материал 12 в M3G — EXP Fog с level-ключами density/color и соответствующим
CPU replay; 11 — DECAL с белой основой и сохранением alpha текстуры.
В Software исходные dithering-эффекты сохранены; номера материалов
в ресурсах менять не нужно.
Гроза в M3G упрощена: светло-серое небо на прежние 135 мс через native clear,
без ветвей молнии, текстур и засветки мира. Удалён отдельный additive overlay.
При ошибках сохраняется простое светлое небо; Software-грозу изменения не затрагивают.
Однотонное 2D-небо (Skybox mode 1) в M3G рисуется цветами вершин: один quad
без текстуры, тумана и blending. Цвет из исходных ресурсов сохранён.
Панорамное 2D-небо (mode 2) перенесено в M3G: текстурированный прямоугольник
и заливки неба/земли, поворот/FOV/горизонт/repeat/portal viewport сохранены.
Разрешение панорамы не уменьшается, текстура кэшируется. NPOT, превышение
лимитов устройства или отказ native-прохода сохраняют исходный CPU-путь.
Полный opaque sky не загружает старый кадр; backup остаётся, обязательный финальный
readback в GameScreen удалён (legacy eager API сохранены).
Градиентное 2D-небо (mode 3) в M3G заменено однотонной заливкой без настройки.
Цвет — первый RGB исходной текстуры, без её upload и CPU-градиента.
Software сохраняет исходное градиентное небо; файлы уровней не меняются.
Спрайты сцены теперь идут в M3G: обычный cutout, ADD, alpha и MUL→MODULATE, включая текущие
кадры анимации, зеркалирование, viewport clipping и туман. NPOT-копии текстур
при upload уменьшаются до меньших POT-размеров: 70×90 → 64×64 вместо 128×128.
POT-текстуры сохраняют размер. Исходные файлы и экранный размер спрайтов
не меняются; детализация уменьшается. Ресемплинг выполняется один раз при
загрузке в кэш, Software использует исходное разрешение. Кэш/вершинные буферы
переиспользуются; прогретые спрайты не создают дополнительных CPU-барьеров.
MUL/color=0..6 заменён на native MODULATE: яркость берётся из синего канала,
как в исходных ресурсах; нулевой texel и старый ключ blue=69 не меняют фон.
Поканальные варианты сохранены, fog для MUL игнорируется как прежде.
Вместо прежнего насыщаемого усиления используется обычное умножение /255:
эффект намеренно темнее и больше не осветляет. При CPU fallback/replay выбранного
M3G используется та же замена. Слишком большие текстуры/неизвестные режимы
по-прежнему возвращают CPU fallback.
Software, HUD и LCDUI-оружие не менялись. При отказе — полный CPU replay.
Возможны отличия сэмплинга и округления; FPS/визуальная проверка на телефоне
не выполнялись.
Небо и уровень объединены в один M3G-проход: отдельная sky prefix-очередь
рисуется до отсортированного мира. На прогретом кэше полностью native-сцена
требует одного bind/readback, без промежуточного чтения/загрузки неба.
Аппаратная коррекция включена по умолчанию, но теперь отключается в меню.
Качество текстур этим переключателем не меняется. При ошибке оба этапа
откатываются и повторяются на CPU; Software/debug/alpha сохраняют старый путь.
Необходимые CPU-материалы и cache miss всё ещё могут потребовать обмен кадром.
Неподдерживаемые или неточно представимые полигоны используют программный путь.

Bloom в M3G отключён: ключ BLOOM учитывается только в Software,
включая возврат к нему из M3G без перезагрузки уровня. Другие эффекты сохранены.

Туман полностью native для поддерживаемых M3G-материалов/спрайтов: один проход,
без CPU visibility, per-vertex distance fade, белых fog-mask текстур и extra fog draw.
3 использует FOGC, 6 — чёрное дистанционное затухание; в 9/13 только baked цвета.
LQ/LQFOG в native-пути теперь плавный. Материал 1 — ALPHA по альфа-каналу
текстуры, без Fog, также при CPU fallback/replay выбранного M3G.
Полностью непрозрачные текстуры остаются непрозрачными: общей alpha 50% нет.
Камерный свет режима 6 в native Fog больше не вычисляется.
Affine UV используют отдельный PolygonMode при реальной eye-Z, без изменения PERSQ;
HW perspective по-прежнему управляет остальными native-режимами. M3G-драйвер может
игнорировать affine hint. Fog полигонов выключается при fogQ=0; у спрайтов сохранён
их флаг fog, ADD-спрайты его игнорируют, fDist=0 выключает sprite fog как раньше.
Исходные ресурсы, Software и аварийный CPU replay сохранены. Непредставимые native
диапазоны/геометрия используют прежний fallback, не поддельный CPU fog в native-пути.
Общие границы mip/перспективы и кэш Fog endpoints сохранены; CPU fade-кэш удалён.
Из-за native Fog совместимость масштабов ограничивает batching в 3/6;
к 9/13 эти ограничения больше не относятся: совместимы даже общие runs с mode 0;
число draw может вырасти. FPS/вид на устройстве не проверены.

Постэффекты выбранного M3G заменены одним MODULATE-квадом после мира, до readback:
- Виньетка: исходная форма через кэшированную LUMINANCE-маску до 64×64 (до 4096 байт
  исходных данных; внутреннее хранение зависит от драйвера), LINEAR/CLAMP.
- Урон: дешёвый красный multiply-фильтр (255,64,64), не alpha-пелена.
- Смерть: затемнение множителем 128/255, без прежней grayscale-конверсии.
Все три эффекта совмещаются за один draw, без отдельных fullscreen passes.
DoubleBright пропускается в выбранном M3G даже при CPU fallback. В Software всё
остаётся исходным. На прогретом кадре нет mask/geometry uploads и CPU обхода кадра
ради этих эффектов. При native-ошибке вся сцена повторяется, эффекты применяются
одним объединённым CPU-циклом без doubleBright. Нет обещания пиксельного совпадения:
маска уменьшена/сглажена, фильтр урона и смерть намеренно изменены. FPS не измерен.

GameScreen объединяет sky/world → post-effect → rain/snow → release → drawImage.
На прогретом полностью native-кадре с полным opaque sky один bind/release,
нет финального/отложенного getRGB и остаётся один frame backup,
не отдельная транзакция для каждого overlay. Выстрел не загружает overlay-текстуру
и не вытесняет 16 мировых текстур из FIFO.
WeatherGenerator.prepareM3G только собирает переиспользуемые полосы, не рисует их.
LCDUI-погода пропускается только после успешного завершения всего кадра (либо при
пустом наборе частиц); ошибка даже последнего weather packet/readback возвращает
всю сцену+постэффекты на CPU и оставляет LCDUI fallback.
Software, 16 дождевых слотов, снег, портальный debug, screenY и HUD сохранены.
CPU-барьеры/холодные мировые текстуры всё ещё могут делить кадр; FPS не измерен.

Добавлен HUD `M3G D:n B:n T:n` (при native-отказе префикс CPU):
D — реальные вызовы Graphics3D.render, включая попытку с ошибкой; не число полигонов.
B — промежуточные сбросы native target ради CPU-материала/новой текстуры,
а также попытки отложенного Image -> display чтения для следующего кадра/CPU/паузы;
legacy финальный readback и flush без bound target не учитываются.
T — успешные создания Image2D, включая виньетку; cache hits не учитываются.
Строка видна в M3G при Show QFPS (default ON), даже без Debug. Показывает отдельные
максимумы за кадр в окне 250 мс, чтобы не пропускать короткий upload/CPU spike;
на каждый draw строка не строится. Без optional API счётчики остаются безопасными.

Fog batching улучшен общей сеткой depth step=2^n и scale=step/TexturingPers.move.
Сохранены точные экранные endpoints и прежний допуск eye-depth <=1/128;
при непредставимой сетке выполняется исходная упаковка, не новый CPU fallback.
Разные несовместимые масштабы/материалы не объединяются, painter order сохранён.
50 000 математических fixtures и исходные tests прошли. В синтетическом наборе
100 Fog triangles с изменяющимися глубинами/границами: 100 несовместимых старых
масштабов → 2 draw для 3/6. Для fog-free 9/13 также 2 draw. Это не FPS; округление весов/UV может
чуть отличаться от прежней M3G-упаковки, нужна визуальная проверка на телефоне.
Software и исходные ресурсы не изменены.

Сборка движка без оригинальных игровых ресурсов: без них игра не запустится.
На устройстве не тестировалась. Параметры и SHA-256 — в build-report.json.
Подробности: [M3G](../docs/m3g.md).

Аудит смены режимов: исправлен незаметный CPU fallback модельного неба с новым
режимом 10; небо поддерживает 0/1/2/4/5/7/10/11 нативно.
Fog-материалы 3/6/12 имеют консервативный whole-sky CPU fallback. Исправлен
CPU-only dispatch/replay неба, убран лишний старый Fog-расчёт ALPHA-режима 1.
Сквозной синтетический тест всех мировых режимов 0..13: 100 совместимых граней
→ 2 draws; на прогретом кадре B=0/T=0, один bind/readback в legacy eager API.
В image-only GameScreen обязательного финального readback нет. Общая транзакция
с MUL-спрайтом, post-effect и погодой сохранена. Это не измерение FPS на телефоне.

Справка для настройки материалов: [гайд по всем режимам M3G](../docs/m3g-modes-guide.md).

## База до изменения режима 5: откат после KVM (18.10.2026)

По запросу восстановлены CPU-реализации до KVM (`c981fce`): исходные OMNI
packing/shading, расчёт mode5Shade, арифметический FrameEffects, нормали и цикл
сборки batch. Удалены добавленные кэши/LUT и bulk-copy fast paths.
Из последующих изменений игрового кода оставлен только вывод готового M3G Image
через drawImage, включая синхронизацию CPU/паузы и безопасный rollback.
Материалы, погода, оригинальный Software и файлы ресурсов не менялись.

[Точный состав и проверки](../docs/m3g-pre-kvm-rollback.md).
Старые KVM-отчёты сохранены как архив и не описывают оптимизации этой сборки.
