# Исправление остановки кадра при проходе через спрайт дерева

18.10.2026. Исправление тестовой сборки `a470031` / публикации `eada0b8`.

## Причины, воспроизведённые в тестах

1. `Sprite.project/isVisible` допускают видимый billboard с origin уже за
   камерой: у большого дерева изображение всё ещё пересекает экран. Раньше
   `M3GSpriteRenderer` для fog вычислял `eye = 1 - rz / TexturingPers.move`.
   При положительном `rz` глубина приближалась к near plane; при `rz >= move`
   становилась неположительной. Adapter возвращал false, required runtime
   останавливался с `unsupported object code.Rendering.Meshes.Sprite`.
2. Экран диагностики использует `TextView.createLines`. Там оставался индекс
   пробела предыдущей строки: длинный exception/class token мог привести к
   `substring(start > end)`. Сам показ ошибки выбрасывал исключение уже после
   остановки GameScreen. Кроме того, при glyph шире всей строки индекс входа
   не менялся — получался бесконечный цикл добавления пустых строк.

Это воспроизведение дефектов кода через API doubles, **не** воспроизведение на
телефоне пользователя. Оно объясняет путь, при котором вместо диагностики
остаётся остановленная игра; реальное устройство ещё требует повторной проверки.

## Исправление

- Fog-depth billboard теперь `1 - min(0, rz) / move`. Отрицательные `rz` дают
  прежние значения. Положительные получают безопасную глубину **1**, то есть
  ровно начало `Fog.LINEAR` и нулевой fog. Это соответствует исходному sprite
  renderer, где fog power для положительного `rz` ограничен нулём.
- Сам спрайт **не скрывается**, collision/visibility/cutoff/UV/order/texture modes
  не заменяются. ADD/MUL по-прежнему без fog. CPU fallback не возвращается.
- TextView забывает пробел после каждого переноса, потребляет хотя бы один
  oversized glyph и использует безопасное сложение ширины. Обычный перенос,
  `*` и текст/кодировки файлов сохраняются; длинные слова корректно разбиваются.

## Проверки

- Negative controls до исправления: required Sprite failure на `rz == move`;
  TextView substring exception на длинном слове после переноса; отдельный
  bounded test обнаруживает отсутствие прогресса oversized glyph. Он специально
  ограничивает число строк и не допускает реального исчерпания heap.
- 538 synthetic crossing frames: modes 0–3, fog context 0/3/6, HW perspective
  ON/OFF, отрицательный/нулевой/положительный rz, `move-1/move/move+1` и дальше;
  production project/visibility, оба cutoff, движение вперёд и назад.
- С реальными `/gamedata/objects/textree1.png` (64×128, SCALE=44) и
  `textree2.png` (64×64, SCALE=50) — **1182** crossing frames суммарно.
  После прогрева с opaque background — 0 CPU barriers; near fog цвет не меняет.
- TextView: нормальные строки, длинные exception/class names, Cyrillic, пробелы,
  `*`, ширины от отрицательной/нулевой/1px до 224px, oversized glyph;
  завершение и сохранность непробельных символов проверены.
- CLDC compile/preverify, обычный adapter/required/legacy/missing-API suite,
  active frame-profile suite, framebuffer и 14 Python tests — PASS.
- Strict fixtures: все 13 миров и level12/13 world + idle objects — CPU dispatch 0.
  Все 2384 world-строки побайтно прежние. В object-наборах все числовые
  счётчики/позы прежние; меняются только stream fingerprints в **10/320** строках
  level12 и **2/320** level13: близкие fogged billboards получили исправленный
  eye-depth. Побайтное совпадение всех object streams не заявляется.
- В полной сборке 153 класса, 1961 code methods, 1061 StackMap, version 47.0.
  Только `M3GSpriteRenderer.class` и `TextView.class` отличаются от теста a470031;
  остальные 151 class bytes и все **442 ресурса** совпадают. ZIP CRC проверена.

Методика/сводка: [diagnostics/m3g-sprite-crossing-fix.json](diagnostics/m3g-sprite-crossing-fix.json).
Запуск line-splitter regression отдельно: `python tests/textview/run.py`
с теми же JAVA/ECJ_JAR, что у остальных desktop тестов.

Это не замер FPS и не проверка реального драйвера M3G, UI lifecycle, всех
динамических игровых сценариев или level41. Старые опубликованные JAR/PDF
сохранены; исправленная тестовая сборка публикуется отдельным файлом.
