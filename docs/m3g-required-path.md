# M3G-only, этап 3: обязательный native-путь игровой сцены

18.10.2026. Продолжение [depth bands, этап 2](m3g-depth-bands.md).
Точка возврата к опубликованному Software + M3G движку — **`b0ee49b`**.

**Продолжение:** [этап 4a — native Canvas foreground](m3g-canvas-foreground.md)
уже переносит оружие/прицелы/перезарядку и overlay; ниже зафиксирован этап 3.

**Сделано:** игровая Scene требует M3G; нет автоматического CPU dispatch,
whole-frame replay и backup-массива для восстановления кадра в этом пути.
**Не сделано:** физическое удаление legacy samplers и перенос всего HUD,
оружия, bitmap text, меню и остальных прямых pixel-путей. Это ещё не полностью
M3G-only приложение и не новая опубликованная полная сборка игры.

## Изменения в runtime

- Конструктор `Scene` вызывает `DirectX7.requireM3G()`. GameScreen повторно
  подтверждает этот latch перед кадром; повторный вызов не сбрасывает ошибку.
- `Main.renderer` теперь константа M3G. Выбор Software удалён из Settings;
  HW perspective сохранён вместе с отдельным существующим RMS-параметром.
- Старые `RENDERER` и `Quantum.Renderer` не переключают gameplay в Software.
  Ресурсные файлы и RMS-схемы не переписываются. Обычное сохранение настроек
  может по-прежнему записать M3G в прежнюю отдельную однобайтовую запись Renderer.
- Для required Scene вызов `setRenderer(SOFTWARE)` запрещён. Latch сохраняется
  при resize, release native-кэша, ошибке и явной повторной попытке.
- `renderRequired` сохраняет исходный painter order, sky-prefix и native
  материалы. Точный стандартный MeshImage может анимировать и проектировать
  свои дочерние полигоны на CPU; растеризация детей проходит через native dispatch.
- Отказ `SceneRenderer.draw` больше не вызывает `barrier → drawSoftwareObject`.
  Возникает явная ошибка с классом объекта и номером материала. Неизвестные
  объекты, неподдержанные текстуры/геометрия не пропускаются молча.
- Прямые `drawSoftwareObject`, `renderSoftware`, CPU `drawLine`, debug overdraw
  и `flushAlpha` не могут включить растеризатор для required Scene.
  Неподдержанный SkyMesh, overlay или weather также завершают кадр ошибкой.
- В required render/overlay нет выделения `frameBackup`, его покадрового
  копирования или whole-frame CPU replay. На framebuffer 240×320 это убирает
  **307200 байт (300 KiB)** примитивного payload backup-массива, без учёта
  заголовка. При другом размере экономия равна `width × height × 4`.
  Это не утверждение об общем heap приложения или FPS.

Декодеры, форматы и пути ресурсов, lightmaps, level/setting-ключи материалов,
порталы, скрипты и игровая логика не заменены. Материалы 0–13 сохраняют прежнюю
M3G-семантику: ALPHA/ADD/BLEND/MODULATE/MODULATE_X2/DECAL, LINEAR/EXP,
static 9/13 без Fog. Новых пользовательских ключей для включения strict-пути нет.

## Ошибки, пауза и повторная попытка

Отсутствие JSR-184, native RuntimeException, ошибка загрузки текстур, OOM,
release/readback failure не запускают Software. Renderer фиксирует причину,
освобождает bound target, отбрасывает pending Image и очищает очередь.
`getDisplay()` и `flush()` не выдают failed-frame как корректный кадр.
Автоматического повторного создания backend каждый paint нет.

GameScreen останавливает текущий paint до HUD/weather fallback и до обычного
шага simulation. Он сбрасывает клавиши, останавливает игру и музыку и открывает
LCDUI `RendererErrorScreen` с причиной:

- **Retry M3G / Fire** — явный сброс native-кэша и очередей, чёрная начальная
  подложка вместо частичных failure pixels, затем новая попытка. Позиция игрока
  не перезагружается, сохранение игры не записывается.
- **Menu** — закрытие сцены и возврат в меню без неявной записи save.
- **Up/Down** — прокрутка длинной диагностики.

Это диагностический LCDUI экран, не Software-растеризация игровой сцены.
Пауза после удачного Image-кадра всё ещё получает snapshot через getDisplay.
Ошибка такого snapshot тоже открывает диагностику, а не фон из устаревших пикселей.
Реальная исчерпанная heap может помешать выделить даже экран диагностики;
инъекции OOM в тестах не доказывают возможность восстановления при любом OOM телефона.

WeatherGenerator в required-пути не превращает невозможность native preparation
или OOM в null/LCDUI replay. GameScreen дополнительно проверяет native weather
status до presentation. Нативные дождь/снег, лимит 16 rain slots и sky-only
вспышка не заменены новыми эффектами.

## Что сознательно осталось переходным

- Класс DirectX7 всё ещё содержит прежний dual-renderer API для отдельных
  экземпляров, которым **не** был установлен requireM3G. Поэтому наличие
  старых Software/replay тестов нормально: это контроль legacy API, а не
  разрешение gameplay вернуться в Software.
- `TexturingAffine`, `TexturingPers`, `TexturingFloors` и другие старые классы
  пока физически входят в JAR. Удалять их до разбора оставшихся прямых вызовов
  нельзя. Guard общего dispatch не является доказательством отсутствия всех
  возможных прямых pixel writes при любом игровом событии. Старый Benchmark
  в меню также пока вызывает samplers напрямую и требует отдельного переноса.
- CPU projection/clipping, подготовка UV/цветов и распаковка файлов сохранены.
  Перенос Camera/Transform — отдельная задача, не условие удаления scanline-пути.
- Один CPU `display` остаётся для snapshot, legacy API и промежуточных native
  resource barriers. Удалён **backup**, а не все framebuffer-копии.
- Cold texture cache, partial/no-sky кадры и snapshot могут делать readback.
  Успешный native Image-кадр по-прежнему выводится через drawImage; старый
  eager drawRGB presentation при неподдержанном blit пока сохранён. Это вывод
  уже растеризованного M3G кадра, не CPU sampler/replay.
- HUD/оружие/текст/меню и часть bitmap/pixel presentation остаются LCDUI.
  Их перенос и аудит динамических эффектов — следующий этап.
- SkyMesh mode12 по-прежнему не поддержан native sky shader: теперь required
  Scene явно останавливается, а не делает CPU fallback. Материалы мира 12
  используют native EXP как раньше. Неподдержанные возможности нельзя считать
  реализованными только из-за нового error screen.

## Проверки

Тесты вызывают настоящие декодеры, геометрию и adapter с инструментированными
API doubles. Это **не** JSR-184 визуальный эмулятор, не скриншот-тест и не FPS телефона.

| Набор | Результат |
|---|---|
| `RequiredM3GTest` | 0–13; sprites 0–3; native post-effects/weather; MeshImage/SkyMesh; no-backup; explicit failures/retry/resize |
| Missing JSR-184 с API физически удалённым из classpath | Явная ошибка и 0 вызовов CPU объекта, включая пустой кадр после retry |
| Все 13 миров Stlkr | 2384 ракурса × 4 cold/warm прохода; required runtime + strict audit: **0 CPU dispatch** |
| Level12 с world/idle-объектами | 80 поз × world/objects × cold/warm: **0 CPU dispatch** |
| Level13 с world/idle-объектами | 80 поз × world/objects × cold/warm: **0 CPU dispatch** |
| Native adapter и legacy API regression suite | PASS, включая negative controls CPU fallback audit |
| Diagnostic frame-profiler build + active hooks | PASS, включая RequiredM3GTest; SORT охватывает оба пути |
| MIDP framebuffer suite | PASS для сохранённого legacy API |
| Python resource/frame-profile tests | 14 PASS |
| CLDC 1.1/MIDP/JSR-184 compile + preverify | 150 классов, 1927 code methods, 1043 StackMap, version 47 |

В world-проверке 2384 прогретые строки D/triangles/B/T полностью совпали
с этапом 2. Все 320 диагностических строк каждого object-inclusive level12/13
набора тоже полностью совпали, включая fingerprints native streams.
API-поток и frozen idle fixtures не проверяют все анимации, выстрелы,
инвентарь, все сохранения, камеры, OOM/лимиты устройств или бесшовность GPU.
Не заменяют запуск на телефоне. Downloads и конфиги этим этапом не обновляются.

[Машиночитаемый отчёт](diagnostics/m3g-required-path.json).

### Воспроизведение

```sh
export JAVA="$PWD/build/tools/jdk4py/java-runtime/bin/java"
export ECJ_JAR="$PWD/build/tools/ecj.jar"
python scripts/build_cldc.py --java "$JAVA" --ecj-jar "$ECJ_JAR" \
  --api-dir build/sdk/lib --proguard-jar build/sdk/proguard/lib/proguard.jar \
  --output-dir artifacts/native-required
python tests/m3g/run.py
python tests/framebuffer/run.py
python -m unittest discover -s tests/resources -p 'test_*.py'

QUANTUM_RESOURCE_JAR="$PWD/build/native-compat/resources/resources.jar" \
  QUANTUM_STLKR_LEVELS_PROBE=1 QUANTUM_REQUIRE_NATIVE=1 python tests/m3g/run.py
```

Для object-inclusive проверок заменить STLKR_LEVELS_PROBE на LEVEL12_PROBE
или LEVEL13_PROBE с тем же префиксом QUANTUM_. Теперь REQUIRE_NATIVE включает
не только recorder, но и **реальный requireM3G** в этих probes.
Архив ресурсов подготавливается как на предыдущем этапе. Сборку и эти тесты
нельзя запускать одновременно: они используют общий build/cldc.
