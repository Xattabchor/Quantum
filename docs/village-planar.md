# Старая деревня: строгий planar-remesh A/B перед новым bake

[JAR](../downloads/Quantum_Village_Planar.jar) ·
[JAD](../downloads/Quantum_Village_Planar.jad) ·
[Модель для bake](../downloads/Quantum_Village_Planar_Model.zip) ·
[Отчёт](../downloads/Quantum_Village_Planar.build-report.json).

## Результат — небольшой, без обещания FPS

По уточнению пользователя взята **старая деревня sym9 `maps/kordos/kordos.3d`**,
не новая карта с порталами. База — принятый Tex3 asset set из Perspective JAR.
В source4750 native triangles нет вырожденных или точных oriented duplicates
(проверено для всех материалов). Строгий проход нашёл одну подтверждённую
возможность без смещения геометрии/UV: четыре плоских quads с внутренним центром
заменены тремя quads с той же восьмиугольной границей: **8 → 6 triangles**.

| Полная карта | ORIGINAL | SIMPLE |
|---|---:|---:|
| Треугольники | 4750 | 4748 |
| Грани .3d | 2641 | 2640 |
| Углы в native VB | 10032 | 10028 |
| Исходные позиции в .3d | 2729 | 2729 |

Удалено **2 / 4750 (~0,042%)**, не сотни полигонов. **Заметного ускорения не
ожидается**; телефонный FPS не измерялся. Это результат данного консервативного
прохода, не доказательство невозможности другого упрощения карты. QEM/LOD,
срезание силуэтов, UV seams и «невидимых на глаз» стен здесь не применялись.
Для существенного сокращения нужен отдельно согласованный допуск/проверка визуала.

## Как запустить и сравнить

- По умолчанию **SIMPLE**, **0** ORIGINAL↔SIMPLE, **1** SIMPLE, **3** ORIGINAL.
- **\*** — ракурс у изменённой детали (её source centre −4833,−78,1728).
  Native pose: −28998,432,12168, yaw0; не меняет выбранный вариант.
- **5** — исходная камера старой деревни; **2/8**, **4/6**, **7/9** — движение;
  **#** — выход.
- Нажать **\***, сравнить ORIGINAL→SIMPLE→ORIGINAL без движения. Около8сек на
  вариант: 1сек warmup +3сек окно, затем следующее обновление. В исходном spawn
  изменённая деталь далеко и может вообще не попасть в submitted geometry.

**Свет в ОБОИХ вариантах NEUTRAL255**, не старый bake. Пользователь планирует
перепечь свет: старую `kordod.vla` нельзя применять к новой topology по прежним
face ordinals. Существующие texture pixels/UV не менялись, но освещение в этом
тесте намеренно отличается от старого baked preview. HUD всегда пишет NEUTRAL
в строке клавиш; это не готовая переосвещённая игровая карта.

Renderer один и тот же, побайтно прежний `StaticRoomPerspectiveMesh`: GRID4×4,
perspective ON, near128/far60000/FOV74, mode9, оригинальный alpha test и depth.
Для обоих вариантов два cold-built набора geometry/texture buffers; A/B лишь
переключает ссылку, не строит геометрию заново. Индексные маски видимости имеют
обычный bounded2-entry cache: новые маски при движении могут аллоцировать индексы.
Разный triangulation/AABB может немного менять conservative grid submissions;
T — API submissions, не число после native clipping. Сравнивать следует сначала
форму/текстуры, не пытаться вывести speedup из разницы2 triangles.

Материалы Tex3: forest0, **map-selo1.png** atlas1, river2. Лес/река, все4 кадра
реки/speed6/режим7/UV/face order сохранены. Небо/погода/gameplay не запускаются,
как в остальных world-only previews. Производственная карта, коллизия, gameplay,
старые lighting/assets и все предыдущие JAR **не заменяются**. BVH/BSP не добавлены.

## Модель для нового запекания

ZIP содержит `kordos-planar.3d`, `kordos-planar.draworder`, exact geometry report,
README и **`planar-neutral.vla` — временный белый placeholder, НЕ новый bake**.
Нужно запечь новую VLA по каноническому порядку граней НОВОГО `.3d`. В runtime
применить sidecar draw order **после загрузки новой VLA**, как в Tex3 loader.
Sidecar: BE int32 count, затем int16 delta-coded permutation file-face ordinals.
Материалы используют3 Tex3 slots, не5 исходного sym9. Масштаб исходных координат6.
Оригинальные позиции все сохранены, в том числе больше не используемый центр.

## Алгоритм и проверка

`scripts/simplify_village_planar.py`: точные integer plane normals и rational
U/V affine maps, только material1; полные connected faces, одна строго convex
boundary, без holes/nonmanifold/смещения или удаления boundary corners. Точный
математический coverage/UV не является обещанием побитно одинакового аппаратного
растра или сохранения старого света после нового bake.

- Независимый Fraction polygon clipping: каждая новая triangle полностью
  покрыта исходными, каждая исходная — новыми; нет area overlaps/holes; граница,
  winding, plane, UV и все посторонние faces/native order неизменны.
- Synthetic positive case, protected forest/river, UV seam, nonplanar,
  duplicate/nonmanifold/missing-quadrant, malformed order/extra bytes fail-closed.
- Исходники и packaged CLDC: по **16826 checks / 576 poses/river frames**:
  те же camera/projection/materials, protected forest/river payload/order,
  все4 кадра реки, whole/grid counters, stationary cache, no hot VA/texture upload,
  UI A/B/focus/home, warmup/lifecycle, target release при native API errors.
- Независимый существующий JavaScript `.3d` reader подтвердил4748tri/10028corners.
- Все190 parent classes/assets побайтно неизменны;2 новых UI classes и4 resources;
  **192 CLDC47 classes / 1229 StackMap attributes**. JAR/JAD/report/model ZIP
  пересобраны с побайтно одинаковым результатом. Это desktop contracts, не native
  rasterizer/phone performance. Полный исторический resource suite с отсутствующим
  старым fixture здесь не заявляется прошедшим.

```sh
# JAVA, ECJ_JAR и SDK/API/ProGuard как у других diagnostic builders.
python scripts/build_village_planar.py --output-dir downloads
python -m unittest tests/resources/test_village_planar.py
python tests/village_planar/run.py
python tests/village_planar/run.py --packaged
```

Parent SHA256 `652626cddcc8b991a4c9ba76ac810cd1d163f7041f393ff7e31fd8f44f3dcb57`.
JAR856125B, SHA256
`538c161480a95b8d93847daa37dbad2469cfb4c3ff05db986045f3006df11caf`.
