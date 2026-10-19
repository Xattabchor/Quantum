# Software PolyFill — исходный программный Quantum

[JAR](../downloads/Quantum_SoftwarePolyFill_Test.jar) ·
[JAD](../downloads/Quantum_SoftwarePolyFill_Test.jad) ·
[build report / SHA-256](../downloads/Quantum_SoftwarePolyFill_Test.build-report.json).
Отдельное имя MIDlet; старые сборки и настройки игрового renderer не меняются.

Это **настоящая software-ветка Quantum**, а не Java-цикл заливки прямоугольника
и не M3G software backend драйвера. Используются неизменённые бинарные классы
immutable Tex3: DirectX7.SOFTWARE, Mesh, LightedPolygon3V, TexturingPers/Affine,
RenderObjectBuffer. Они сохранены в JAR побайтно; новый код только создаёт
синтетическую сцену/управляет опытом. Основная игра остаётся M3G-required.

## Что прислать

1. Запустить, **5** — полный автопрогон14 нагрузок. По умолчанию **SW P4**.
2. Дождаться таблицы, снять все страницы. **4/6** — страницы. Нужен заголовок
   **SW P4 RESULTS ширина×высота**, а не только отдельные скорости.
3. **\*** переключает на **P0 affine**, очищает таблицу. Затем **5**, повторить.
4. Для оценки разброса повторить при одинаковых условиях; не сворачивать во время
   опыта. Прогон занимает около минуты или дольше на тяжёлых уровнях.

Сравнивать с [native M3G PolyFill](poly-fill.md), **не FPS карты**. На прежних
фото E65 nominal viewport240×230. Проверьте, что software показывает такой же:
он равен ширине Canvas × (высота−90). При другом размере желательно заново
запустить и native тест. Результаты живут в памяти до выхода, без RMS.

Остальные клавиши как в native тесте: **1** POLY, **3** FILL, **2/8** нагрузка,
**0** повтор/восстановление после ошибки, **7** таблица/назад, **5** новый auto
или остановить текущий с частичной таблицей, **#** выход.
Pause/hide останавливает кадры, foreground повторяет прогрев. Resize очищает
таблицу и прекращает auto; нельзя смешать размеры или P4/P0 в одной таблице.

## Ровно те же номинальные нагрузки

POLY: сетки8/16/24/32/48/64/96/120, соответственно
**128/512/1152/2048/4608/8192/18432/28800** треугольников. Общая площадь — один
полный viewport, вершины общие:81/289/625/1089/2401/4225/9409/14641.
Тот же порядок диагоналей, что у native row strips. CPU хранит/диспетчеризует
отдельные настоящие triangle objects, не один native batch — это и есть его путь.

FILL: **1/2/4/8/16/32** непрозрачных полноэкранных слоёв,2 треугольника/4 вершины
на слой. Все слои реально рисуются, painter не имеет early-Z. Та же процедурная
64×64 checker текстура, mode9 brightness220, без mipmaps, bloom, света/фонаря,
погоды/NPC/карты. P4 всегда вызывает настоящий TexturingPers, q24; P0 — штатный
TexturingAffine/renderFast, не новый упрощённый rasterizer.

Модель создаётся при смене уровня/размера. Полная CPU-очередь заранее прогревается
до окна; в кадре — **clearDisplay → transform/project → visibility/enqueue →
sort → raster → drawRGB**. Framebuffer/вершины/грани/очередь повторно используются.
В каждый кадр входят реальная 3D-проекция и сортировка, не только вызов paint.

## Что означают цифры и ограничения сравнения

- **FPS, Ktri/s, nominal Mpix/s** — по полному steady-state wall-clock окну,
  включая вывод/HUD/ожидание paint/GC. Не скорость одного inner loop.
- **R** — CPU pipeline от clear до окончания raster, включая подготовку/сортировку.
- **C** — MIDP drawRGB; **I** — интервал начал кадров. R входит в I.
  Не складывать I+R. В старой native таблице вместо R показан N (native path,
  включая bind/clear/render/release); N не является чистым GPU временем.
Прогрев1с, первый completed-frame anchor, затем≥3с и≥2 кадра, как у native.
  stdout `SOFTWARE_POLY_FILL_RESULT` содержит persQ/viewport/все показатели.

**Это не побитово одинаковые backend/features:**

- Software — painter без Z buffer, native POLY — depth test/write ON. FILL
  в native Z OFF, поэтому именно FILL ближе по отсутствию Z-отбраковки.
- CPU пишет через **drawRGB**, native — Image/drawImage. Это реальные прежние
  пути вывода; различие C может определять FPS независимо от R/N.
- Native — subpixel orthographic grid/strip batch. CPU — integer pixel grid
  при постоянном Z с обычной engine-проекцией. Номинальная площадь/число
  невырожденных треугольников одинаковы, raster edge/UV rounding различаются.
  Если viewport меньше grid, уровень получает ERR, а не ложный triangle rate.
- P4 brightness: la221 после штатного getLight(255/256) даёт220; P0 renderFast
  получает220 напрямую. CPU sampler использует fixed-point/256, M3G — своё
  округление/нормализацию. Возможна небольшая разница цвета/texel boundary.
- Native alpha threshold0.5 остаётся ON; CPU имеет свою проверку ненулевого texel.
  Все texel/вершинный alpha непрозрачные, **тест не проверяет прозрачность**.
- P0 оправдан для этой плоскости постоянного Z; на реальной наклонной карте
  отключение perspective correction может заметно портить UV. Оно не включается
  в игровых настройках, не сохраняется и не выдаётся за равный quality режим.

Выигрыш software на этой нагрузке покажет практический резерв старого пути,
но не гарантирует выигрыш на всей карте с пересечениями, overdraw, light/alpha.
Одинаковый FPS тоже не является доказательством «карта виновата». **Телефонных
software-результатов пока нет; desktop FPS не публикуется как оценка E65.**

## Проверки / воспроизведение

Source и packaged CLDC JAR:28 настоящих CPU raster workloads (14×P4/P0),
сплошное покрытие viewport, положительная ненулевая площадь каждой грани,
правильные counts, CPU brightness, неизменные массивы/прогретая очередь,
покомпонентный drawRGB output. Odd/landscape размеры, реальный dispatch всех
64 треугольников F32, запрет **любого Graphics3D.getInstance**. Материал/сам
растеризатор не подменены тестовыми doubles; doubled только Java ME/LCDUI API.
Controller/14-step auto/table, P4/P0 separation, OOM/ERR/skip/retry,
hide/foreground/resize, clock rollback и window arithmetic проверены отдельно.
Инъецированные часы в controller-тесте проверяют протокол, не измеряют скорость.

Все182 baseline класса/assets и опубликованный native PolyFill остаются прежними.
Только5 новых классов и manifest; class47.0/CLDC preverify/StackMaps, hashes,
детерминированный JAR/JAD/report, resource audit отдельно.

```sh
JAVA="$PWD/build/tools/jdk4py/java-runtime/bin/java" \
  python scripts/build_software_poly_fill.py --output-dir downloads
JAVA="$PWD/build/tools/jdk4py/java-runtime/bin/java" ECJ_JAR="$PWD/build/tools/ecj.jar" \
  python tests/software_poly_fill/run.py --packaged
python -m unittest discover -s tests/resources -p 'test_software_poly_fill.py'
```
