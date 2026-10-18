# Level41: анимированная река, LightedPolygon4V material=7

18.10.2026. База — `0653358` (ARM9 world buffers).

## Ошибка и воспроизведение

На скриншоте: `M3G required: unsupported object
code.Rendering.Meshes.LightedPolygon4V material=7. Software fallback is disabled.`

Это не отсутствие реализации режима 7 и не причина менять реку на mode9.
Режим 7 уже использует native `CompositingMode.MODULATE`. Но при отказе обычной
упаковки short XYZ он, в отличие от режимов 0/3/6/9/13, не мог повторить
геометрическую подготовку с каноническими depth bands.

Такая же ошибка воспроизведена на **первом реальном quad реки** из исходного
`q 92 20251201y.jar.zip`: камера `(-99642,-2798,22200)`, yaw/pitch 0.
Это тестовая позиция, не восстановление неизвестного положения игрока на скриншоте.
View XYZ: `(0,-10,0), (-7710,-118,0), (-7716,-118,-4920), (-6,-16,-4920)`.
Проекция и provenance настоящие; широкий near/far диапазон отвергает простую
упаковку и прежнюю попытку clipping без bands.

## Исправление

В `M3GSceneRenderer` разрешён прежний bounded depth-band retry **дополнительно
для mode7**. Сам clipper, точность веса <=1/128, предел W=4096 и 64 triangles
на исходный polygon не изменены. Все части quad готовятся до native submission.
Каждый исходный треугольник делится на упорядоченные части, а не рисуется целиком
повторно. Нет texture sorting, смены диагонали, ALPHA/REPLACE вместо MODULATE,
ослабления precision guard, изменения source UV/RGBA или CPU raster/replay.
Уже успешно упакованные polygons идут прежним путём.

Lightmap, исходная геометрия и коллизии не меняются. Режимы ALPHA/ADD/X2/DECAL/
BLEND/EXP и mode4 не получают дополнительный depth retry этой правкой.
При чрезмерных диапазонах/неподдерживаемых ресурсах остаётся явная ошибка M3G.

## Отдельная конфигурация и сборка

[Overlay level41.txt](../configs/level41-river7/level41.txt) сохраняет исходные
CP1251/CRLF bytes, меняя только WTEX реки:

```ini
8:/maps/shooter_final/river0.png:/maps/shooter_final/river1.png:/maps/shooter_final/river2.png:/maps/shooter_final/river3.png,7
```

Четыре оригинальных 32×32 кадра, 8 кадров/с, без изменения PNG. В исходном ZIP
эта строка не содержит явного режима и наследует DMODE=9; в тестовой версии
река явно назначена mode7, остальные материалы/DMODE/дальности/погода прежние.
Из **505** non-code ресурсов изменён только `level41.txt`, остальные **504**
побайтно равны исходному архиву. Сам пользовательский ZIP не переписывается.
Это **не Stlkr**: предыдущие Stlkr-сборки/PDF остаются отдельными.

Сборка: `scripts/build_cldc.py --resource-archive 'q 92 20251201y.jar.zip'
--resource-overlay configs/level41-river7` с прежними Java/ECJ/SDK/ProGuard
аргументами. Archive classes/manifest не используются; код текущего движка.
Все прочие уровни q92 не прошли полный required-M3G gameplay аудит этим этапом.
MIDlet ID прежний: перед установкой сохранить сейвы.

## Проверки

- Negative control: временное отключение mode7 retry воспроизводит **точную**
  ошибку `LightedPolygon4V material=7` в обычном required frame transaction.
- `RiverMode7Test`: первый реальный quad, независимая half-open pixel coverage
  без двойного покрытия на границах bands. Это API/CPU geometry reference,
  **не GPU screenshot/raster proof** для всех ракурсов.
- Четыре принудительно выбранных кадра animation без зависимости от часов;
  HW ON/OFF, affine/perspective, native MODULATE, warm B0/T0, source preservation.
- `Level41Probe`: реальные 24 lighted quads × четыре исходных PNG,
  **11532 видимых pose-frame случаев**; без unsupported geometry.
- Полный Level41 House/chunks/lightmap/model sky/world/16 rain/flash:
  **256 строк**, во всех warm B0/T0/readbacks0/copies0; strict CPU dispatch 0.
- Strict Stlkr 13-world (2384), level12/13 с idle objects (320+320): все строки,
  включая stream fingerprints, побайтно равны предыдущей ARM9 версии.
- Normal + active frame-profile suites, TextView, framebuffer, 14 Python tests,
  CLDC compile/preverify — PASS. 154 classes, 1967 code methods, 1067 StackMap,
  version 47.0; в полной сборке профилировщика нет.
- Из class-файлов движка меняется только `M3GSceneRenderer.class`.

Runner теперь умеет запускать required-M3G audit Level41, а не только Stlkr.
Stlkr real-tree PNG fixture запускается только из содержащего эти PNG архива;
при Stlkr probe их отсутствие остаётся ошибкой. Для q92 печатается явный skip
этого чужого fixture, synthetic crossing всё равно запускается. Отдельный
Stlkr-прогон проверил оба настоящих дерева (1182 crossing frames).

[Машинная сводка](diagnostics/m3g-level41-river7.json).
На устройстве исправление ещё не проверено. Полный игровой loop, FPS, input,
прочие уровни q92 и точная rasterization конкретного драйвера не подтверждены.
