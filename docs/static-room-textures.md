# Tex3 — три записи атласа → одна, река сохранена

По запросу пользователя объединены три слота `map-selo1.png` в один.
Это отдельная версия **текущего теста деревни**, не замена полной игры.
База — неизменённый BVH JAR из commit `b8ef81fcda64ca155ee72dc4edb6bcf932f4e4b9`,
SHA256 `1eecd62d8a01917eff0d7b5354f15f44fd6199351ef8ce5d5937c2a1ba4f93aa`.

[JAR](../downloads/Quantum_StaticRoom_Tex3.jar) ·
[JAD](../downloads/Quantum_StaticRoom_Tex3.jad) ·
[Состав, remap и SHA256](../downloads/Quantum_StaticRoom_Tex3.build-report.json)

## Что изменено

Было **5 texture/material slots**, стало **3**:

| Назначение | Старый индекс | Новый индекс |
|---|---|---|
| `forestOrigy.png` | 0 | 0 |
| `map-selo1.png` | 1, 2, 3 | 1 |
| Река, 4 анимационных кадра | 4 | 2 |

Из WTEX удалены только две повторные строки атласа с `std,-3500` и
`std,-7000`. Сохранена первая строка `/maps/kordos/map-selo1.png` без опций.
Строка леса, **вся строка реки**, CRLF/исходная кодировка и все байты конфига
вне этих двух удалённых строк сохранены. В `.3d` меняются **только значения
int16 material markers**: 1/2/3→1 и 4→2. Не удаляются и не переставляются
грани/вершины; позиции, UV, порядок углов, индексы геометрии и диагонали прежние.
Материалов у граней теперь: forest0=33, atlas1=2584, river2=24.

**Река не изменена**, кроме необходимой перенумерации слота:

- `river0.bmp`…`river3.bmp` — исходные байты, размеры и пиксели;
- те же 4 кадра, animation_speed=6;
- режим7, исходный MODULATE, depth test ON / depth write OFF;
- те же UV, позиции, baked-цвета, диагонали, грани и порядок отрисовки;
- nearest/base-level, без mipmap, те же texture alpha / blending.

`kordod.vla` остаётся **побайтно прежней**. Всего так же 2729 исходных вершин,
2641 грань, 4750 треугольников и 10032 угла в native vertex buffers.
Полигональный оптимизатор не применяется.

## Почему нужен draw-order sidecar

Просто объединить material IDs недостаточно для точного сохранения native
порядка: ранее renderer собирал сначала грани material1, затем2 и3, включая
triangles/quads каждой группы. После слияния это был бы общий file-order.
Он мог бы изменить результат при совпадающей глубине поверхностей.

Добавлен маленький `/maps/kordos/kordos.draworder`: delta-coded permutation
из 2641 face ordinal (5286 байт, 73 байта после ZIP-сжатия). Это **не** старые
texture slots, не дубли PNG и не второй набор геометрии. При загрузке:

1. Room читает `.3d` в прежнем порядке, уже с тремя material IDs.
2. Исходная `.vla` привязывается к углам в прежнем порядке.
3. После этого объекты граней переставляются по сохранённой перестановке.
   Их UV/light-сэмплы уже прикреплены к самим объектам, ничего не перепутывается.
4. Прежний native mesh builder строит точно такие же пакеты, как до слияния.

Операция выполняется только на старте, линейно, без перекадровой сортировки.
Некорректный sidecar отклоняется **до** перестановки исходного массива.

Это пакет для нового viewer: при переносе только ресурсов в другую версию
движка сохранение native draw order не обеспечивается автоматически. Кроме
того, удалённые `addsz`-значения не использовались этим native viewer, но могут
быть значимы в Software/full-game сортировке. Утилита не патчит такую версию
без отдельной проверки. Исходные файлы и предыдущие JAR не перезаписаны.

## Режимы и ожидаемый эффект

По умолчанию **B GRID**: по последнему сравнению пользователя BVH не ускорил
сцену (A N98/I126/T1218/V363 и B N97/I122/T2864/V33, FPS8 у обоих).
**0** по-прежнему переключает **A BVH / B GRID**, не alpha test.
В HUD добавлено `Tex3`, чтобы отличать сборку.

Alpha test всегда исходный ON, кода отключения нет. Near128/far60000,
CULL_BACK/CW, blending и свет не меняются. Старые renderer-классы используются
побайтно: из Java добавлены только новый preview и его Viewer.

Важное ограничение: PNG атласа **уже хранился один раз**; RawImage cache был
общим, а native renderer уже объединял три смежных одинаковых atlas-материала.
Удалены два логических Texture-слота/две записи конфигурации, **не три копии
пикселей**. Native packets остались3, calls по-прежнему <=3. FPS не измерен и
прирост не обещается. Это cleanup, не доказанное устранение причины тормозов.

## Проверки

- Source и actual packaged suites PASS, **1728** pose/river/mode сравнений:
  Grid и BVH, 3 позиции, 3 pitch, 24 yaw, 4 кадра реки.
- Эталон берётся непосредственно из **старого ZIP**: старый `.3d` с 5 слотами,
  старый WTEX, старая `.vla`. Все новые native XYZ/UV/RGBA/primitive order/
  strips/indices/camera/alpha/blending/depth совпадают с ним точно.
- **736** отправленных состояний реки проверены, все 4 кадра и исходные параметры.
- В фиксированной позе нет новых vertex/texture/mask uploads; vertex buffers
  и количество отправленных примитивов совпадают с эталоном в каждом режиме.
- Холодный viewer: 3 материала, 6 исходных image resources, default GRID,
  key0 roundtrip, корректный HUD и near/far.
- Ошибочные permutations: duplicate/out-of-range/truncation/trailing/count
  mismatch отклоняются транзакционно. Валидная перестановка проверена по ссылкам.
- Независимый Python binary decode: у всех 2641 граней только material IDs
  изменены, topology/XYZ/UV/face order неизменны; остальные bytes вне marker
  payloads исходные. WTEX3 и byte identity реки/lightmap/остальных ресурсов.
- Все **180** базовых классов и все ресурсы кроме двух разрешённых файлов
  сохранены (manifest изменён для нового MIDlet). Всего182 класса, CLDC1.1 / class47.0 / preverify PASS.
- 11 resource tests, прежние 3456 Depth cases и 7 исторических checkpoints PASS.

Это native API doubles / binary audit, не реальный растр E65 или FPS-бенчмарк.

```sh
JAVA="$PWD/build/tools/jdk4py/java-runtime/bin/java" \
python scripts/build_static_room_textures.py --output-dir downloads

JAVA="$PWD/build/tools/jdk4py/java-runtime/bin/java" \
ECJ_JAR="$PWD/build/tools/ecj.jar" \
python tests/static_room/run.py --packaged \
  --jar downloads/Quantum_StaticRoom_Bvh.jar \
  --textures-jar downloads/Quantum_StaticRoom_Tex3.jar

python -m unittest discover -s tests/resources -p 'test_static_room_*.py'
```
