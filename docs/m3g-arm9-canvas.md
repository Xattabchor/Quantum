# ARM9 / OMAP 1710: облегчение native Canvas foreground

18.10.2026. База — `8ec625e`, код TreeFix `e1dc319`.
Продолжение [предыдущих ARM9-оптимизаций](m3g-arm9-omap1710.md) и
[native Canvas этапа 4a](m3g-canvas-foreground.md).

Это переносимые CLDC 1.1 / MIDP 2.0 / JSR-184 bytecode, **не ARM-native JAR**.
Оптимизируется работа, дорогая для медленной Java VM и Java↔M3G boundary.
OMAP 1710 сам по себе не гарантирует аппаратный M3G: телефонный драйвер может
считать 3D на CPU. На реальном ARM9/OMAP FPS и драйверное время не измерены.

## Что изменилось

Из production-кода меняется только `M3GCanvasImages` и его private inner classes.
Мир, материалы, файлы/декодеры, RMS, AI, коллизии, NPC/quests, weather simulation,
DIST/FOGD, ImageResize и качество текстур **не меняются**. Исправление прохода
через дерево и устойчивый TextView сохранены. Нет новых ключей или переключателей.

### 1. Соседние quads — одним packet

Объединяются только **непосредственно соседние** quads с одним Texture2D
(включая null у непрозрачных прямоугольников), до 16 quads на packet.
Сортировки/переноса между слоями нет: A → B → A остаётся тремя группами.
Разные RGB прямоугольников сохраняются в vertex colors. ALPHA и REPLACE
по-прежнему раздельны, индексы треугольников сохраняют painter order.

Для каждого встретившегося числа quads 1…16 лениво создаётся точный Packet:
VertexBuffer, четыре VertexArray и TriangleStripArray. У одного quad **4**
вершины, а не общий буфер на 64. В каждом draw индексируются и передаются
ровно нужные вершины/треугольники; неподдерживаемая графика не пропускается.
Подготовка textures/packets идёт до Canvas bind, повторное создание после
прогрева не требуется. Лимиты очереди 256 commands / 512 prepared quads прежние.

### 2. Меньше attribute uploads и арифметики

- Textured packet обновляет только positions + UV и использует постоянный
  белый VertexArray; прямоугольники — positions + RGB(A), без бессмысленного UV upload.
- Переключение white/colored buffer делается только при необходимости.
- Position scale переустанавливается только при изменении precision конкретного
  Packet. Не выполняется float-деление и setPositions на каждый quad.
- Четыре края прямоугольника считаются один раз: 4 вместо 8 умножений XY.
  Убраны четыре Java-вызова vertex-helper и восемь делений центра на quad;
  положительные Canvas centres вычисляются сдвигом один раз на проход.
- Деление на POT tile edge заменено сдвигами; четыре UV-деления на tile —
  умножением на точные предварительно рассчитанные `4096/POT` коэффициенты.
  Все прежние rounding, NPOT padding, размеры, source subregions и mirrors сохраняются.

### 3. Несинхронизированный bounded LRU и source scratch

Кэш остаётся на **64 tiles / 262144 POT-пикселя**, source-content контракт прежний.
Внутри render-thread-only helper теперь фиксированный Tile[64], direct recent hit
и двусвязный LRU. Hit/eviction не сдвигают Vector и не вызывают его synchronized
методы. На обычном non-recent lookup по-прежнему возможен линейный поиск до
64 элементов: это не обещание O(1) для любого доступа или выигрыша любого VM.
Индекс slot позволяет удалить victim перестановкой array tail, не painter queue.

Временный int[] для source getRGB переиспользуется и растёт только при
необходимости до **16384 ints (64 KiB payload)**. RGBA byte[]/Image2D и native
texture всё ещё создаются на cache miss; чтение исходника и decode не исчезают.
На прогретом помещающемся наборе getRGB/uploads/barriers по-прежнему нулевые.

Это обмен постоянной памяти на меньшие GC/API расходы: scratch остаётся до
освобождения renderer. Если встретились все 16 размеров packet, логические
VertexArray + index payload составляют до **14144 bytes**, плюс Java/native
объекты, CPU staging arrays и возможные копии драйвера. Это **не** общий/пиковый
heap. Прежний RGBA cache payload budget до 1 MiB не увеличен.

## Подтверждённая работа, не FPS

Все числа — прогретые синтетические fixtures, recorded API + независимый
axis-aligned pixel reference, **не GPU rasterization или ARM9 timing**.

| Случай | До | После |
|---|---:|---:|
| 5 rect + 4×A + B + A + 3 rect: native D | 14 | 5 |
| Там же: VertexArray.set | 42 | 10 |
| Там же: position-scale bindings | 14 | 0 |
| Reload outline + partial fill: native D | 5 | 1 |
| 33 соседних одинаковых image quads: native D | 33 | 3 |
| Warm Java geometry/index object creation | нет | нет |
| Warm fitting source getRGB / T / CPU barriers | 0 / 0 / 0 | 0 / 0 / 0 |

Неизменные material packets, camera restoration, alpha/overlap order и каждый
цвет сравниваются с command reference. Проверены 1…256 quads, точная native
vertex capacity, переходы white↔colored с одинаковым размером packet, разные
Canvas precision, LRU oldest/middle/recent hits + eviction, scratch growth/reuse,
ошибки до bind и после успешного первого packet. CPU replay/fallback нет.
Negative control на прежнем коде не проходит D14→D5 контракт.

## Регрессии и сборка

- Обычный и active frame-profile suites — PASS; CLDC compile/preverify — PASS.
- 154 classes, 1966 code methods, 1066 StackMap, version 47.0; 144 sources.
- Все 13 world-fixtures: 2384 строки побайтно совпадают с TreeFix.
- Level12/13 world + idle objects: по 320 строк побайтно совпадают с TreeFix,
  включая native stream fingerprints; strict runtime CPU dispatch = 0.
- 1182 crossing frames с PNG обоих деревьев, 1280 weapon/scope layouts,
  36 reload layouts, TextView, framebuffer, 14 Python tests — PASS.
- 442 ресурса полной Stlkr-сборки неизменны; старые JAR/PDF не заменяются.
- Level41 и реальное динамическое прохождение игры в этом этапе не проверялись.

[Машинная сводка](diagnostics/m3g-arm9-canvas.json).
Для проверки на OMAP нужны одинаковые сцена/настройки старой и новой сборок,
сравнение warm FPS, пауз/GC, памяти, оружия/прицела и LCDUI↔M3G порядка.
D/B/T показывают объём работы, но не заменяют замер времени телефона.
