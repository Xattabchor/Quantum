# M3G-only, этап 4a: оружие и foreground через native Canvas

18.10.2026. Продолжение [обязательного Scene-пути, этап 3](m3g-required-path.md).
Точка возврата — **`b0ee49b`**, предыдущий этап — `30d250c`.

**Перенесены:** оружие, его настоящая картинка вспышки, оптический прицел,
перекрестие, полоса перезарядки и timed screen overlay.
**Не перенесены:** остальной HUD/text/icons, bitmap text, чёрные поля viewport,
меню, Benchmark с прямыми samplers и другие legacy pixel consumers.
Это не полностью M3G-only приложение и не новая опубликованная полная игра.

## Порядок и отдельная цель

Сохранено исходное расположение прохода в `GameScreen.paint`:

1. Native world/sky/post-effects/weather заканчиваются в world Image.
2. `scene.flush` показывает этот Image через LCDUI `drawImage`.
3. Идут прежние LCDUI portal debug и weather post-scene debug.
4. Новый Canvas-проход исполняет **overlay → weapon/scope/muzzle → reload → crosshair**.
5. Оставшиеся LCDUI HUD/text/icons/viewport bars рисуются поверх, как раньше.

Overlay остаётся перед оружием и работает даже с `hideHud`. Старые условия
смерти, zoom, `show2D`, `hidesight`, `lookAtObj` и `redrawInfo` сохранены.
Для каждого image/rect сохраняется его текущий clip; последующие изменения clip
его не расширяют. Состояние цвета после reload также сохранено для LCDUI HUD.

`ScreenImages` собирает ограниченную очередь без JSR-184 и без растеризатора.
`DirectX7.renderCanvasImages` допускается только в required-M3G policy, вне
world-транзакции. `M3GCanvasImages` сначала готовит ресурсы без bound target,
затем один раз привязывает **текущий paint Graphics**, выставляет полный Canvas
viewport и отдельную screen-space Camera и исполняет очередь без сортировки.
В конце target освобождается, а clip вызывающего кода восстанавливается.
Ненулевой `Graphics.translate` явно отклоняется; GameScreen работает без него.

У картинок native ALPHA, белый vertex color и NEAREST/BASE_LEVEL; у полосок
REPLACE с исходным RGB. Fog, material lighting, culling, depth test/write
отключены. Одинаковая глубина и прямоугольные координаты не зависят от world
HW perspective, FOV, центра или static lighting. Следующий world bind заново
ставит собственные viewport/Camera/lights; foreground их не переиспользует.

## Без уменьшения NPOT и копирования сцены

- Передаются прежние Image после исходных decode/`ImageResize`; загрузчики,
  размеры, ресурсные пути и ключи оружия не менялись.
- Картинки делятся на tiles до **128 px** либо меньшего аппаратного lower-POT
  лимита. Край дополняется прозрачными пикселями до POT; resize/downsampling
  здесь нет. Это отдельный путь от ранее изменённых world-sprite копий.
- Source region, anchor и mirror меняют геометрию и UV, не пиксели исходника.
  Полностью невидимые tiles не читаются и не загружаются.
- Ключ кэша — Image identity + origin tile. LRU ограничен **64 tiles** и
  **262144 POT-пикселями**: до 1048576 байт логического RGBA payload.
  Это **не** оценка общего/пикового heap или памяти GPU: остаются исходные
  Images, native объекты, временные int/byte arrays и prepared references.
- Для прогретого **помещающегося** working set нет source `getRGB`, upload и
  CPU barriers. При eviction, смене оружия/картинки, release/retry, resize или
  новом renderer кэш может загрузиться снова. Working set больше лимита может
  перезагружаться каждый кадр; нулевые uploads не обещаются для любого набора.
- Source Image считается неизменным после decode/resize. При изменении его
  содержимого требуется `releaseRenderer`/пересоздание renderer; кэш не сравнивает
  пиксели каждый кадр. Текущие gameplay-картинки загружаются прежним способом.
- Очередь ограничена 256 командами, подготовка — 512 quads. Превышение лимита
  явно останавливает native путь, а не скрыто отбрасывает оставшуюся графику.

World Image в этом проходе **не читается, не копируется и не привязывается заново**.
Ленивый `frameImage` для прежнего pause snapshot остаётся world-only, как раньше;
пауза по-прежнему может потребовать readback. Не добавлен CPU backup/replay.

D/B/T учитывают native foreground: один D на quad и T на загруженный tile.
Чтение source Image на cache miss — подготовка ресурса, не world CPU barrier B.
Frame-profile учитывает Canvas bind/release в API; image preparation входит в HUD.
Это не доказательство выигрыша FPS относительно LCDUI на конкретном телефоне.

## Ошибки и граница гарантий

Source read/upload, bind/setup/render/release, OOM и отсутствие JSR-184 дают
явную required-M3G ошибку, очистку очереди и экран Retry M3G / Menu.
LCDUI drawImage/drawRegion или CPU composition в качестве fallback не вызываются.
Существующие legacy `Weapon.draw/drawSight` остаются обёртками для старых вызовов;
GameScreen использует только native-методы. Layout/animation math у обёрток общий.

Canvas foreground расположен **после уже показанного мира**: при позднем native
сбое мир и часть foreground могут уже попасть на экран. Они не откатываются
CPU-копией; симуляция останавливается, diagnostic screen заменяет вывод.
Нельзя переносить гарантию непредъявления failed world Image из этапа 3 на
атомарность всего LCDUI/Canvas кадра этого этапа.

## Выполненные проверки

| Проверка | Результат |
|---|---|
| CLDC compile + preverify | 153 classes, 1961 code methods, 1061 StackMap; version 47.0; 144 sources |
| Weapon/scope layout | 1280 случаев против независимой копии draw-кода `30d250c` |
| Две руки, левое зеркало, muzzle, newanim, melee restore, custom/center | PASS |
| Reload | 36 сочетаний размеров/процента, включая inactive/0/>100%, odd widths, clip и LCDUI color state |
| NPOT 271×233, Canvas 301×279 | 8 anchors × 2 mirrors; subregions, RGBA и ordered reconstruction native команд совпадают с source reference |
| Аппаратный лимит 64/127 | Точные 64px tiles без downsampling; warm reads/uploads = 0 |
| Прогретый подходящий кэш | Source getRGB = 0, uploads = 0, CPU barriers = 0 |
| Lifecycle | LRU bound, release invalidation, clip/target cleanup, unchanged lazy snapshot, следующий world Camera |
| Fault injection | Source read, upload, bind, viewport, OOM, render, release, translation, missing JSR-184 — явная ошибка |
| Adapter/required/legacy/missing-API suite | PASS, обычная сборка и active frame-profile |
| Все 13 миров Stlkr | 2384 diagnostic rows идентичны этапу 3; strict required runtime: CPU dispatch = 0 |
| Level12/13 world + idle objects | По 320 строк идентичны этапу 3; CPU dispatch = 0 |
| Framebuffer + Python | PASS; 14 Python resource/profile tests |

Normal engine-only SHA-256:
`b484f0e3d846b1211527a62e9f4d2cead46477ea80aff564f24b0653f08cce7a`.
143 старых class bytes неизменны, 7 изменены, добавлены 3, удалённых нет.
Машинный отчёт: [diagnostics/m3g-canvas-foreground.json](diagnostics/m3g-canvas-foreground.json).

Native-команды восстанавливаются независимым axis-aligned reference; API doubles
**не** растеризуют настоящие GPU-треугольники. Compile/preverify и static fixtures
не подтверждают реальное смешивание LCDUI/M3G, драйверные half-pixel/alpha детали,
пиковую память, UI lifecycle, динамическое прохождение игры или FPS.
Отдельного level41 прогона в этом этапе нет.

Ресурсы, конфиги, RMS layout, native material modes и опубликованные downloads/PDF
не заменялись. Перед публикацией обязательны запуск на устройстве/полноценной
JSR-184 реализации и визуальная проверка LCDUI → native Canvas → LCDUI порядка.
Следующий этап — оставшиеся HUD/icons/bitmap text с batching, затем оставшиеся
pixel consumers/Benchmark и только после аудита — физическое удаление samplers.
