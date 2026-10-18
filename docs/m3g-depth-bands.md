# M3G-only, этап 2: near/far геометрия без программной растеризации

18.10.2026. База — `f566f7e`; точка возврата до переноса — **`b0ee49b`**.
**Это ещё не завершённый M3G-only движок.** Опубликованные downloads остаются
прежними: удаление Software, перенос HUD/оружия и прочих pixel paths впереди.

## Причина fallback и исправление

Аудит первого этапа нашёл 448 CPU dispatch calls в 9536 проходах world-наборов
Stlkr. Трассировка показала отказ упаковки уже отсечённого near/far polygon fan:
большой диапазон homogeneous W не всегда помещается в short XYZ с существующим
ограничением ошибки веса <=1/128. View provenance у реальных отказов корректен.
Это не отсутствие поддержки номеров материалов 0/3/6.

`WorldClipGeometry` сначала делает **прежнюю попытку** упаковки без изменений.
Если она не удалась, для режимов **0/3/6/9/13** включается дополнительное
геометрическое разбиение по каноническим power-of-two W bands. Получившиеся
треугольники растеризует M3G; CPU только готовит геометрию, UV и цвета.

- Не меняются исходные карты, модели, UV, текстуры, lightmap, конфиги и скрипты.
- Сохраняются DIAGONAL selection, native LINEAR Fog 3/6, static light/RGB 9/13
  без Fog/динамического света, cutout и исходный порядок объектов.
- Пакеты, для которых прежняя упаковка проходила, не разбиваются.
- Ошибка веса <=1/128 не ослабляется; максимальная глубина retry ограничена
  W=4096, вся подготовка ограничена 64 треугольниками на исходный polygon.
- Подготовка целого quad атомарна: ни один треугольник не отправляется в M3G,
  пока все необходимые части не подготовлены. Чрезмерные диапазоны и ошибки
  по-прежнему отклоняются; старый whole-frame CPU recovery пока остаётся.
- Пересечения обеих границ band вычисляются непосредственно от **исходного
  отсечённого ребра**, а не от уже интерполированного промежуточного ребра.
  Для соседних bands используется одинаковый порядок концов по W.
- Внутренние буферы переиспользуются. Дополнительный payload массивов ленивого
  clipper — **2648 байт** плюс заголовок/выравнивание нового массива; не на polygon.
- ALPHA/ADD/MODULATE/X2/DECAL/BLEND/EXP и mode4 не получают это новое разбиение:
  их прежние native/CPU paths оставлены. Нельзя считать их сложные случаи
  полностью перенесёнными только потому, что Stlkr world suite уже зелёный.

## Проверки

### Реальные карты

С теми же опубликованными ресурсами/агрессивными конфигами:

- Все 13 миров Stlkr: **2384 ракурса × 4 прохода**, CPU raster dispatches
  **448 → 0**; strict `QUANTUM_REQUIRE_NATIVE=1` теперь проходит.
- Level12, world + idle objects: **0 → 0**, все 320 ordered native streams
  побайтно совпали с базой первого этапа.
- Level13, world + idle objects: **48 → 0**, 296/320 native streams прежние.
  Остальные 24 строки теперь содержат геометрию, которая раньше была CPU-only;
  это **не** доказательство идентичного физического/native stream этих строк.
- Level41 из другого исходного архива: suite с actual House/chunks/lightmap,
  native model sky, weather и flash прошла.

Числа CPU dispatches включают cold/warm проходы, а не уникальные полигоны.
Для мира сумма **native D 23634 → 24003**, native triangles **83463 → 84470**,
CPU barriers **1326 → 1252**: ранее программные поверхности теперь учитываются
в native submissions и иногда требуют дополнительных треугольников.
**Это не оптимизация количества draws и не замер FPS.**

### Регрессии геометрии и материалов

`WorldDepthBandsTest` использует фактический отказавший quad Кордона:
обе диагонали, Fog on/off, affine/perspective attribute interpolation,
границы viewport, UV/цвета, отсутствие мутации входов, reuse буферов.
Сравнивается покрытие pixel centers с независимым affine-silhouette вариантом:
нельзя терять внутренние области; у границ допускается 1,5-pixel tolerance
для существующего integer screen rounding. Это **не GPU screenshot test** и
не обещание отсутствия всех субпиксельных швов на реальном устройстве.

Проверены native modes 0/3/6/9/13, warm B0/T0, прежние native material tests
0…13, cutout/alpha, порядок, failure recovery и Software isolation.
Прежняя регрессия огромного quad по-прежнему проверяет отказ **без частичной
native submission**. 14 Python resource/frame-profile tests прошли.

CLDC preverification: **149 классов, 1909 методов, 1029 StackMap, version 47.0**.
Из 149 классов **147 побайтно прежние**; изменены только `WorldClipGeometry`
и `M3GSceneRenderer`. Форматы/декодеры и остальные игровые классы не менялись.

[Машиночитаемые результаты](diagnostics/m3g-depth-bands.json).

## Ограничения и продолжение

Полного визуального прогона и FPS на телефоне нет. Покрыты конечные наборы
поз на 240×320 и API doubles, не все камеры/разрешения/устройства. Новые точки
используют прежнее integer screen rounding и byte UV/color interpolation;
пиксельная идентичность с прежним CPU-fallback не заявляется.

Strict gate регистрирует `drawSoftwareObject`, а не все возможные pixel writes.
HUD/оружие/меню LCDUI, pause screenshots, OOM/API failures, другие лимиты
текстур и не охваченные режимы ещё не позволяют удалить весь Software.
Следующие этапы остаются в [контракте переноса](m3g-only-migration.md).

## Воспроизведение

После обычной сборки `scripts/build_cldc.py`:

```sh
python scripts/game_resources.py \
  --archive downloads/Quantum_Stlkr_FogBatch_ARM9.jar \
  --output-dir build/native-compat/resources
QUANTUM_RESOURCE_JAR="$PWD/build/native-compat/resources/resources.jar" \
  QUANTUM_STLKR_LEVELS_PROBE=1 QUANTUM_REQUIRE_NATIVE=1 python tests/m3g/run.py
```

JAVA/ECJ_JAR должны указывать на Java/ECJ. Для object-inclusive заменить флаг
на `QUANTUM_LEVEL12_PROBE=1` или `QUANTUM_LEVEL13_PROBE=1`.
Для сравнения с baseline использовать исходники первого этапа `f566f7e`:
там strict gate ожидаемо отвергает level1. Ресурсы между этапами одинаковы.
