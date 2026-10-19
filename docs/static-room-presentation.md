# Static Room Present — прямой Canvas и измерение затрат кадра

## Пользовательский результат после публикации

Сообщено: A direct — 7 FPS, D2, N112/C0/P0/F113/I140/T3421;
B image — N112/C0/P0/F112/I140/T3421, FPS не указан. Устройство/метод
не уточнены. Различимого преимущества direct target в этих показаниях нет.
Не считаем C=0 доказательством бесплатного копирования. Продолжение —
[отдельный Strips A/B с подробным J/B/D/R](static-room-strips.md).

## Почему пока нельзя назвать причину 7–9 FPS

Это сообщение пользователя, не собственный замер устройства. 7–9 FPS — примерно
111–143 мс на кадр. Lite уменьшил JAR, но исключённые ресурсы и раньше не
загружались тестом: runtime render workload от упаковки не изменился.

Количество вызовов M3G API уже ≤3, однако это не означает три дешёвые операции
на hardware GPU. Драйвер может выполнять M3G программно. Полные VertexBuffer
по-прежнему содержат 10032 вершины; выбор меньшего числа индексов не гарантирует,
что драйвер обрабатывает только выбранные вершины. Кроме геометрии остаются
растеризация/текстурирование/глубина, river MODULATE pass и вывод кадра.

**Конкретно найденный дополнительный шаг:** старый viewer рисует M3G в
полноэкранный mutable Image, затем каждый кадр делает LCDUI drawImage на Canvas.
Ещё он создаёт DirectX7 с ненужным CPU framebuffer и хранит исходный Mesh.
Это кандидаты на расходы времени/памяти, **не доказанная причина FPS**.

## Новая отдельная облегчённая сборка

`downloads/Quantum_StaticRoom_Present.jar`, MIDlet **Quantum Static Room Present**.
Не перезаписывает Cull Lite или любую другую сборку. Состав ресурсов — те же
10 файлов; каждый исходный ресурс и все 162 исходных класса, в том числе
StaticRoomMesh и StaticRoomVisibility, **побайтно идентичны** Cull Lite `cd61dc9`.
Добавлены только новый viewer и сборщик статистики (3 новых class-файла).
Старый просмотрщик в JAR не запускается.

Кнопка **0** теперь сравнивает **только способ вывода**:

- **A direct** (по умолчанию): M3G получает Graphics из Canvas.paint. В коде нет
  промежуточного Image и drawImage этого кадра.
- **B image**: тот же native room рисует в Image, после releaseTarget следует
  LCDUI drawImage, как раньше. Image и его Graphics создаются лениво и
  переиспользуются, при переходе обратно в A ссылки освобождаются.

**Отсечение включено в обоих режимах**. Геометрия, камера/FOV74/округление
фокальных длин, дистанция, baked colors, UV, texture quality, depth/cutout,
порядок и все кадры реки одинаковые. Полного DirectX7-объекта/CPU framebuffer и
ссылки на source Mesh в новом viewer нет. Конфиги не редактируются, дальность
не уменьшается, backbuffer/render resolution не снижается.

`4/6` — поворот, `2/8` — движение, `7/9` — стрейф, `#` — выход.
Если прямой Canvas не поддерживается драйвером, ошибка показана явно:
**0** переключает на B image и сбрасывает ошибку. Это явный выбор другого
native target, **не CPU raster fallback**. Как и раньше, index-mask OOM
может перевести оба target-режима на полную native-комнату (`MEM->whole`).

## Что прислать с устройства

Постоять в одинаковой позиции 5–10 секунд в A, затем в B. Прислать FPS и строки
статистики (или два скриншота), модель устройства/эмулятора. Обратить внимание
на правильность картинки A и плавность поворотов. A/B должны показывать тот же T
при одинаковом положении камеры, если не активен memory fallback.

HUD, усреднение по успешным кадрам за окно ≥1 секунды:

| Метка | Смысл (миллисекунды wall clock) |
| --- | --- |
| P | Java до вызова nativeRoom.render, включая движение/animation/target setup |
| N | Весь nativeRoom.render: его Java camera/culling/mask setup, bind/clear/draw/release |
| C | LCDUI drawImage после releaseTarget, только B; A не вызывает копирование |
| F | paint от начала до конца HUD, без публикации новых строк и восстановления Graphics |
| I | Интервал между началами paint, включая очередь repaint, scheduling и возможное presentation wait |
| D / T | M3G API calls / число отправленных треугольников последнего кадра окна |

Строки HUD собираются раз в окно, не через конкатенацию на каждом кадре.
Показания сбрасываются при **0**; первый холодный кадр B может включать аллокацию.
Счётчик System.currentTimeMillis на телефоне может быть грубым: нулевое C значит
«не различимо этим таймером», а не доказанную нулевую стоимость. Обновление
строк/восстановление Graphics и окончательный экранный flush могут учитываться
в I, но не в F. N не является GPU timestamp: отложенное выполнение/синхронизация
драйвера могут проявиться в других местах. Профилирование само имеет накладные
расходы, одинаковые для A/B; сравнение с прежним JAR не идеально контролируемое.

Если C велико и A быстрее, перенос вывода подтверждён устройством. Если N
доминирует в обоих режимах — следующим нужен разбор native geometry/raster work.
Если I заметно больше F — исследовать repaint/presentation/scheduling.
Из одних 7–9 FPS ни один из вариантов пока не доказан.

## Проверки и воспроизведение

```sh
export JAVA="$PWD/build/tools/jdk4py/java-runtime/bin/java"
export ECJ_JAR="$PWD/build/tools/ecj.jar"
python scripts/build_static_room_present.py
python tests/static_room/run.py --present --packaged \
  --jar artifacts/static-room-present/Quantum_StaticRoom_Present.jar
python -m unittest discover -s tests/resources -p 'test_static_room_*.py' -v
python scripts/verify_render_checkpoints.py
```

- Java ME SDK / JSR184 compile + ProGuard CLDC preverification PASS.
- Структурный CLDC47.0 аудит: 165 classes / 2037 code methods / 1111 StackMaps.
- Готовый JAR проверен с API doubles, **без компиляции вместо него viewer или
  renderer**. Старые 96 full-cache witnesses, 864 culling poses, 10000 AABBs,
  mask OOM/cache bounds/stationary uploads и old Viewer cold load — PASS.
- **96** старый-cull vs direct vs image witnesses (24 yaw ×4 настоящих river
  frames): ordered geometry/camera/projection/UV/colors/texture/state одинаковы.
  Анимационные часы в этом сравнении фиксированы на выбранном кадре.
- 7 размеров viewport, включая нечётные и landscape: focalX/focalY совпадают
  с опубликованным DirectX7, а не только формулой из старого source tree.
- Cold texture reads, no missing/error images; A не создаёт Image и не делает
  drawImage; B создаёт Image/Graphics единожды и переиспользует; A/B/A и resize,
  clip/translation restore, unsupported-Canvas recovery и native-error release PASS.
- Синтетическая проверка P/N/C/F/I, 1s windows, переключение/clock rollback PASS.
- Пять immutable rollback checkpoints (stable/v1/v2/Cull/Cull Lite) — PASS.

Заглушки/временные resource anchors Room и StringTools используются только
в desktop-тестах и не включены в JAR. Это **не** Canvas raster/pixel equivalence,
handset heap/GPU benchmark или обещание FPS. Драйвер устройства ещё не проверен.
Логи: `build/static-room-present-{source-tests,build,packaged-tests}.log`.
Состав/хеши: соседний `Quantum_StaticRoom_Present.build-report.json`.
