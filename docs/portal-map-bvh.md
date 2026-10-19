# Portal map + per-room BVH, atlas map-selo.png

[JAR](../downloads/Quantum_PortalMap_Bvh.jar) ·
[JAD](../downloads/Quantum_PortalMap_Bvh.jad) ·
[Build report](../downloads/Quantum_PortalMap_Bvh.build-report.json).

Новый отдельный тест по просьбе пользователя. Геометрия/порталы/RGB-lightmap из
новой карты; **текстура `maps/kordos/map-selo.png` из последнего `sym 9.zip.zip`**.
Это не `map-selo1.png` и не `maps/shooter_final/map-selo.png` из старого q92.
В BVH ON и OFF загружается один и тот же новый atlas 256×256, без ресайза и без
пересчёта UV. Цвета DMODE13, alpha test, depth и perspective сохранены.
Прямое сравнение FPS со старым viewer не изолирует BVH: там была другая текстура.
Сравнивать нужно **ON/OFF внутри этого JAR**.

## Клавиши и замер

- Запуск: **BVH ON F40000**, порталы **всегда ON**.
- **0** — BVH ON↔OFF; **1** — ON; **3** — OFF.
- **\*** — дальность 40000→30000→20000→40000, не меняя BVH.
- **5** — исходная камера, без изменения BVH/far.
- **2/8** — вперёд/назад; **4/6** — поворот; **7/9** — вбок; **#** — выход.

Far/BVH-клавиши останавливают движение, сохраняют позу/target и заново прогревают
статистику: 1сек прогрев, затем3сек измерения. В неподвижном ракурсе снять
**ON → OFF → ON при одинаковом F40000**, около8сек на режим. Нужны FPS/N/R,
T/DC/VB, U и вид сцены. Затем при желании повторить на F30000/F20000, не смешивая
дальности внутри одного ON/OFF сравнения. Нужен также отзыв о движении: новый
индексный mask на движущейся камере может стоить заметного CPU/GC времени.

HUD:
- **T** — переданные M3G треугольники; не число после native clipping.
- **DC** — native draw calls, не число листьев BVH.
- **VB** — вместимость отправленных resident room buffers, не число реально
  обработанных драйвером вершин. При снижении T значение VB может остаться тем же.
- **Rooms** — комнаты-кандидаты от порталов; некоторые BVH может отсечь целиком.
  `OUT -> ALL15` означает15 комнат-кандидатов; BVH ON затем всё равно проверяет
  их по полному camera frustum, а не отправляет обязательно все треугольники.
- **P** — проверки порталов; **C** — точный camera/viewport/far/room cache BVH.
- **U** — накопленное число построенных частичных native index masks. При
  неподвижной камере не должно расти, включая прогретый ON/OFF возврат.
- **N**, **J/B/K/R/E** — Java API wall time, не GPU timestamps.
- **BVH FALLBACK** — не хватило памяти на index mask: кэш освобождён, используются
  полные исходные индексы выбранных комнат. Это не software renderer.

## Что реализовано

1. Прежний conservative portal traversal:15 комнат/18 порталов, union/requeue
   screen windows, исходный floor-based room membership, exact native camera.
   Изменение fork selector — только read-only accessor окна комнаты.
2. Для каждой комнаты один cold-built median BVH по AABB **полных исходных
   полигонов**, до8 полигонов/лист. Дерево статическое, не перестраивается при
   движении камеры. Нет BSP, сортировки или угадывания occlusion за зданиями.
3. AABB узлов проверяются по inverse **той же квантованной camera transform**, что
   передаётся M3G, с учётом portal window комнаты и native near/far coefficients.
   Пограничные узлы сохраняются с conservative guard. BVH не разрезает полигоны.
4. Сохраняются исходные XYZ/UV/RGBA и порядок strip/face/room. Выбранные faces
   собираются в **один index buffer на комнату**, а не в вызов на каждый лист.
   В кадре максимум один call на непустую выбранную комнату, один bind/clear/release.
5. Vertex arrays, native vertex buffers и atlas загружаются **один раз**. У M3G
   immutable индексы: новая частичная маска требует сборки index arrays и
   `TriangleStripArray` **до bindTarget**. На комнату только2 cache entries;
   пустая/полная маска не строит новых индексов. Стационарный exact-camera cache
   пропускает и BVH traversal, и index builds. Это не обещание отсутствия
   аллокаций при движении — новая видимость может вызвать cache miss.

Far по умолчанию40000, near128, FOV74, DMODE13/scale6, alpha/depth/perspective ON.
Far-клавиша меняет native projection **и** BVH far culling, поэтому здесь T уже
может уменьшаться с дальностью — в отличие от предыдущего far-only теста.
Конфиг с DIST40000 не переписан; global drDist остаётся40000.

**Geometry-only**, как у родителя: небо, погода и gameplay не запускаются.
Все6 оригинальных файлов новой карты сохранены побайтно; замена atlas явная
через новый loader/resource namespace, не подмена содержимого оригинального PNG.
Производственная игра и предыдущие JAR не изменялись.

## Проверки и результаты

Desktop API doubles, **не native rasterizer и не замер телефона**:

- исходники и packaged CLDC: по **903328 checks / 486 camera cases**;
-432 sweep позы плюс54 случая на центрах18 порталов, сдвигах глаза ±1, pitch±75,
  odd viewport и far20k/30k;
- BVH OFF payload/camera/room windows точно совпадают с parent renderer,
  загруженным с той же новой текстурой;
- каждый удалённый треугольник независимо проверен double Gauss-Jordan inverse
  и homogeneous triangle clipping по native frustum + portal window;
- оставшиеся атрибуты, strips, порядок, материалы/alpha/depth и camera/projection
  неизменны; per-leaf draw calls не добавлены;
- random/coincident tree cardinalities: полнота leaves/parents, max8 faces/leaf,
  исходные bounds не переставляются; invalid frustum fail-open;
- нет горячих vertex/attribute/texture uploads, нет stationary index builds;
  bounded2-entry masks, прогретый ON/OFF roundtrip, camera/far/near/viewport/focal key;
- UI-клавиши, сохранение камеры/target, warmup/stats, far cycle, lifecycle;
  target release при native API errors, allocation OOM → полные room indices.

При исходной позе F40000: **OFF1384 → ON498 треугольников**, обе стороны по1 call,
один и тот же room6 vertex buffer. В sweep:580637→82362 submitted triangles;
ON0..1084 по исследованным позам. Это **не** коэффициент ускорения: native driver
может обрабатывать полный VB, а CPU BVH/index cache имеет свою цену. FPS узнаем
только из ON/OFF замера на E65.

## Сборка / аудит

```sh
# Те же Java/ECJ/SDK/API/ProGuard, что в portal-map.md.
python scripts/build_portal_map_bvh.py --output-dir downloads
python tests/portal_map_bvh/run.py
python tests/portal_map_bvh/run.py --packaged
python -m unittest tests/resources/test_portal_map_bvh.py
python tests/portal_map/run.py
python tests/portal_map/run.py --packaged
python scripts/verify_render_checkpoints.py
```

Parent `Quantum_PortalMap_Test.jar`, commit
`6fe1257924b52fa252461c4ef36bb78cb57edc2f`, SHA256
`116abb3817186dec6c753e4031f4a8d66f03690f6c73db939989592062064be4`.
Все190 parent classes/resources неизменны, добавлены11 новых классов и atlas,
заменён manifest/entry point. **201 CLDC47 classes / 1268 StackMap attributes**.
Аудит проверяет каждый байт parent, выбранный PNG против sym9, source/report
hashes, manifest/JAD, version47 и точность fork portal traversal.
Повторная сборка JAR/JAD/report побайтно воспроизводима.

Atlas:144265B, SHA256
`b90d9db6fd0653585cec73677114935553ce33ab76450c6a6918bfb926d44efb`.
JAR:1260678B, SHA256
`3ceb3dc29c86518f2880908c8732fb6080f67ac18705f3cb4fcb8fe77a21ebc4`.
Телефонных результатов пока нет. BSP не возвращён.
