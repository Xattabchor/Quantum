# M3G: сокращение работы для ARM9 / OMAP1710

18.10.2026. База — EXP Fog 12, `dd898a4`. Оптимизация направлена на CPU,
передачи Java↔M3G и временные выделения памяти. Новых настроек нет.
Это не ARM-native сборка: сохраняются переносимые CLDC 1.1 / MIDP 2.0 / JSR-184.
Наличие M3G не означает наличие аппаратного 3D-ускорителя на OMAP1710:
реализация телефона может выполнять 3D на CPU. На самом телефоне FPS не измерен.

## 1. MeshImage: без лишнего round trip framebuffer

Раньше диспетчер считал MeshImage неизвестным RenderObject и перед его render
делал CPU barrier. Сам стандартный MeshImage только обновляет анимацию,
трансформирует вершины и передаёт полигоны обратно тому же диспетчеру.
Поэтому native-меши/NPC без CPU-материалов зря читали весь Image в int[] и
затем копировали int[] обратно при следующем native bind.

Теперь **точный MeshImage с точным ненулевым Mesh** сразу выполняет прежний
render/renderFast внутри native pass. Необходимый barrier выбирает каждый
дочерний полигон/объект. Совместимые соседние полигоны разных MeshImage могут
попасть в один прежний 64-triangle пакет. Перестановок по текстурам нет.
Копирование вершин в пакет сохраняется: следующий animated/shared mesh не
меняет уже подготовленный пакет. Projection, анимации и сортировки прежние.

Произвольные подклассы MeshImage или Mesh сохраняют консервативный barrier:
нельзя предполагать, что их реализация не рисует в display. CPU-дети обычного
меша тоже сохраняют настоящий barrier. При native-ошибке остаётся полный
CPU replay кадра, включая ранее отрисованные MeshImage.

Синтетическая прогретая сцена, 240×320: solid sky + 20 однотреугольных
MeshImage с общей текстурой. Консервативный путь воспроизведён тестовым
подклассом без overrides (тот же render, но прежняя wrapper dispatch).

| Работа за кадр | Консервативный wrapper | Точный engine wrapper |
|---|---:|---:|
| Native draw D, включая sky | 21 | 2 |
| Промежуточные CPU barriers B | 20 | 0 |
| Image.getRGB | 20 | 0 |
| Graphics.drawRGB, загрузка фона | 20 | 0 |
| Texture uploads T | 0 | 0 |

Сравнивается нормализованный **упорядоченный** поток позиций, UV, RGBA и
материальных состояний независимо от границ пакетов. Это не GPU raster.
При таком разрешении убрано 20×240×320×4 = 6 144 000 байт логических пиксельных
данных в каждом направлении за кадр этого примера. Реальные накладные расходы
драйвера, FPS и поведение при большом количестве материалов не измерены.
B:0 и D:2 не обещаются для произвольной сцены.

## 2. Projection камеры: reuse вместо повторных выделений

M3GSceneRenderer держит один Transform и один float[16] для world projection.
Неизменный bind больше не создаёт эту пару и не повторяет Camera.setGeneric.
Проекция обновляется при изменении width/height/centreX/centreY и после dispose.
При этом setCamera, viewport и нужное восстановление состояния после
sky/weather/sprite helpers **не удалены**: их native camera/state отличаются.
Кэшируется определение world camera, а не глобальное состояние M3G.

Это устраняет две прежние явные Java-аллокации на bind и повторную установку
проекции. Небольшой постоянный Transform/массив — сознательный обмен памяти
на уменьшение мусора/GC; весь рендер не объявляется allocation-free.

## 3. Последняя текстура: быстрый путь без Vector scan

Уже существующий lastTexture проверяется до прохода по синхронизированному
Vector. Проверяются все прежние части ключа: RawImage identity, pixel-array
identity, исходные размеры и kind. Обычный поиск и прежний bounded cache
16 entries / 262144 texels сохранены; новые текстурные копии не добавлены.

Замена pixel-array, размеров или kind не даёт stale hit. Удаление из cache
обнуляет lastTexture, если он указывал на вытесненный entry; это важно и если
следующая загрузка прервалась из-за OOM. dispose очищает оба указателя/кэш.
Как и прежде, изменение пикселей **внутри того же массива** требует сброса
renderer: новая логика не вводит контентное хеширование текстур.

## 4. Native rain/snow: меньший upload/index prefix

M3GScreenLines сохраняет VertexArray ёмкостью 128 вершин, но передаёт только
нужный power-of-two prefix. Шесть lazy buckets: 1/2/4/8/16/32 strips,
каждый strip имеет свои четыре вершины. На bucket один переиспользуемый
TriangleStripArray с последовательными индексами. Неполный хвост только
выбранного bucket вырождается; старый хвост большого пакета не индексируется.

| Видимые strips в пакете | Прежде: uploaded/indexed vertices | Теперь |
|---|---:|---:|
| 1 | 128 | 4 |
| 3 | 128 | 16 |
| 16 | 128 | 64 |
| 17…32 | 128 | 128 |

Для 16 видимых капель position+RGB payload сокращён с 1152 до 576 байт за
пакет (3 shorts XYZ + 3 bytes RGB на вершину); для одной — до 36 байт.
Не меняются RGB, geometry, painter order, texture-free путь и draw count.
Симуляция дождя по-прежнему ограничена 16; M3G rain/snow остаются основными,
LCDUI — только прежним fallback. При 65 strips остаются три draw: 32+32+1.

Кэш bounded, максимум шесть native index objects; после прогрева нужных buckets
новых index objects нет. Если встретились все размеры, память indices может
быть немного больше прежнего единственного полного index buffer; ёмкость
VertexArray не уменьшена. Prefix upload/indices сами по себе не доказывают,
сколько вершин внутри обрабатывает конкретный телефонный драйвер.

## Совместимость и проверки

Изменены только три production-файла: DirectX7, M3GSceneRenderer,
M3GScreenLines. Software rasterizers, GameScreen, форматы моделей/анимаций,
конфиги и исходные ресурсы этой правкой не меняются. Сохранены все текущие
материалы, DECAL 11, EXP Fog 12 с level-ключами, LINEAR 3/6, BLEND 5,
static colors 9/13, sky, native RGB weather, Image presentation и atomic replay.
Ранее отменённые post-KVM Software-оптимизации не восстановлены.

Arm9PreparationTest добавляет проверки: 20 wrappers с точными счётчиками и
равенством потоков; смешанные CPU/native дети; консервативные подклассы;
ошибка после первого 64-face пакета с полным CPU replay; 50 rebind без новых
Transform/setGeneric; восстановление камеры, centre/resize/dispose; отсутствие
Vector scan на 100 смежных hits; replacement/kind/size, eviction+OOM, dispose;
все weather counts 1…32 дважды, шесть buckets, точные upload prefixes,
stale tail, 65 strips и rollback при OOM создания нового index bucket.
Обновлены прежние assertions фиксированной weather capacity и ожидаемый
wrapper barrier; level41 различает weather по RGB, а не по размеру upload.

- **166 PASS** default M3G, **173 PASS** с реальными ресурсами/level41,
  **221 PASS** profile build, включая balanced profiler suite.
- Framebuffer, settings, frame profiler fake clock и **8 Python tests** прошли.
- **256 строк level41** и **86 sky streams** равны `dd898a4`;
  B:0/T:0 прогретой выборки сохранены. Эта сцена без MeshImage не является
  демонстрацией выигрыша wrapper optimization; он проверяется отдельно.
- Preverified CLDC: **149 classes / 1893 code methods / 1016 StackMap / 47.0**.
  145 прежних classfiles byte-identical; изменены DirectX7, M3GSceneRenderer,
  его CachedTexture (включая debug metadata), M3GScreenLines. Классы
  engine/game совпадают; **505 оригинальных assets byte-identical**.
  API doubles, тесты и диагностический FrameProfile в обычный JAR не входят.

## Сборки

- `downloads/Quantum.jar`, также `artifacts/arm9-omap/Quantum_Engine.jar`:
  **473826 байт**, SHA-256
  `d7a03d96d0855d4d7103d11a67b12da10a68478073414a2f21c1b6491e707374`.
- Полная `artifacts/arm9-omap/Quantum_Level41_ARM9_OMAP1710.jar`:
  **9771093 байт**, SHA-256
  `f279dd65da1a916f8713f1680df1d3405307ec9c780c13e8fb3aa4a9eaad8b20`.
- Matching JAD рядом с каждой сборкой; profile artifact хранится отдельно.
  Повторная сборка проверена по содержимому ZIP entries, не по ZIP timestamps.

Локальные логи: `build/arm9-omap/{default,level41,profile,framebuffer,settings,
frame-profile,resources,engine-build,profile-build,game-build}.log`.
`runtime_tested=false`: API doubles проверяют порядок/состояние/отказы, а не
реальные артефакты M3G и не FPS ARM9. Для оценки ускорения нужно сравнить обе
сборки на одном телефоне, уровне, ракурсе и настройках, отдельно с NPC и дождём.
