# Карта Quantum в настоящем l2d_m3g: controlled renderer comparison

[JAR](../downloads/Quantum_L2D_Map.jar) · [JAD](../downloads/Quantum_L2D_Map.jad) ·
[build report / SHA-256](../downloads/Quantum_L2D_Map.build-report.json).

Используется реальный исходный код [rmn20/l2d_m3g](https://github.com/rmn20/l2d_m3g/tree/35c5c26a967aec4562c707f7aba532db20d17d21),
commit `35c5c26a967aec4562c707f7aba532db20d17d21`, GPLv3. Код, лицензия,
[original hashes](../experiments/l2d-map/ORIGIN.json) и
[полный patch адаптации](../experiments/l2d-map/renderer.patch) сохранены.
Это **статический viewer нашей карты на renderer L2D**, не перенос NPC, скриптов,
игровой логики или полный запуск Quantum-мода в игре Left2Die.

## Режимы в одном JAR

| Кнопка | Режим | Путь |
|---|---|---|
| Запуск / **3** | **B L2D IMAGE** | L2D MeshData.loadMeshes3D2 → native Mesh → Renderer.addMesh(Node) → Image → LCDUI |
| **1** | **A Q FULL IMAGE** | Immutable Tex3 StaticRoomBvhMesh → Graphics3D.render(VB,indices,appearance) → тот же Image → LCDUI |
| **0** | A ↔ B | Поза и target не меняются, окно FPS сбрасывается |
| **\*** | **C L2D CANVAS** | Тот же L2D renderer, напрямую в Canvas; отдельный тест вывода |

**Во всех режимах T4750, VB10032 и3 Java render calls**. Полная геометрия, без
Java grid/BVH/дистанционного отбора граней. GPU/native clipping остаётся обычным.
Сравнивать B с A внутри этого JAR, **не с прошлым GRID**, где передавалась лишь
отсечённая часть карты. Canvas меняет ещё один фактор, поэтому вынесен отдельно.
Если Canvas не поддержан телефоном — явная ошибка, **3** возвращает B Image.
Никакого молчаливого переключения или CPU raster fallback.

### Телефон

1. Запуск B, **5** — исходная поза/yaw0. Не двигаться. Подождать числовой FPS и ещё
   одно обновление окна (обычно8секунд). Фото всего HUD, включая изображение карты.
2. **1 → A Q FULL IMAGE**, так же подождать и сфотографировать.
3. **3 → B**, контрольный повтор.
4. По желанию **\* → C**, затем **3 → B**. Нужны и скорости, и сообщение ошибки,
   если Canvas не поддержан.

**4/6** поворот, **2/8** вперёд/назад, **7/9** вбок, **5** исходная камера,
**#** выход. При смене режима движение сбрасывается, поза сохраняется.
FPS дробный,1с прогрев, completed-frame anchor, затем≥3с/≥2 кадра.
N — весь renderer API path; C — drawImage (в Canvas близок к0), I — frame interval,
N входит в I. Это wall time, **не GPU timings**. HUD90px поверх полного нативного
viewport, не уменьшенная высота рендера. Оба renderer/текстуры загружены заранее,
чтобы не смешивать FPS с созданием буферов и давлением памяти другого JAR.

## Что перенесено и нормализовано

- Та же актуальная карта/WTEX/PNG/baked VLA из immutable Tex3. Все2641 грани:
  33 forest quads; atlas2052 quads +532 tris;24 river quads. Ни decimation,
  welding, удаления материала, ни изменения диагоналей/порядка граней.
- Offline-конвертер сначала использует подлинный frozen Quantum loader/lightmap
  loader и захватывает full native inputs. Вершины переводятся в `.3d2` с
  **точным** scale6, UV1/256; проверяется отсутствие потерь/переполнения.
- Stock `.3d2` группирует quads перед tris, но наша карта содержит чередование.
  Поэтому форматный файл корректно сгруппирован, а маленький order sidecar
  восстанавливает исходные TriangleStripArray после штатного MeshData loading.
  Native Mesh использует **полученный L2D VertexBuffer**, не Quantum buffer.
- Все6 cache images (forest/atlas/4 river frames) экспортированы из native
  inputs как точные RGBA bytes. Нет переупаковки в новый атлас или пересжатия
  с потерями. River update остаётся по прежней анимации/speed6; один frame
  выбирается обоими путями. Mode7 MODULATE/depth-write-off сохранён.
- L2D штатно хранит vertex colors как **RGB3**, Quantum как **RGBA4 с alpha255**.
  Значения RGB/итоговый alpha одинаковы, но представление отличается. Это часть
  архитектуры/организации данных L2D, не чистый тест стоимости одного Java цикла.
- Smooth shading, CW/back cull, perspective correction, nearest MODULATE/repeat,
  black blend color, alpha thresholds и depth state выравнены под Quantum.
  Fog/material/динамический свет не добавлены.
- У оригинального L2D другие near/far/FOV и Euler camera. В benchmark adapter
  передаются **точные** Quantum projection/world camera floats, включая прежний
  focal-plane shift: near128/far60000/FOV74, текущий полный размер Canvas.
  Выравнены color/depth clear и bind hints0. Иначе сравнивались бы разные кадры.

Изменены только2 upstream файла: MeshData resource anchor (`Object.class` →
`MeshData.class`, необходим и для packaged JVM-проверки); Renderer получает
configureBenchmark, явные ошибки viewport в benchmark mode и флаг target ownership
для корректного finally даже при сбое в prepareRender. **addMesh(Node) не заменён
своим renderer.** Остальной upstream source побайтно соответствует pinned commit.
Неиспользуемые игровые классы L2D сохранены, но benchmark не запускает их логики.

## Как интерпретировать

Если B заметно быстрее A при одинаковой картинке — устройство лучше переваривает
путь/представление L2D, и есть смысл переносить конкретные решения в Quantum.
Если C быстрее B — существенен путь Image/LCDUI/вывода, не только карта.
Если A≈B, это **не доказывает «виновата карта»**: общим пределом могут быть
raster/overdraw, текстурирование, Native M3G/драйвер, синхронизация и hardware path.
Для разграничения нужны последующие контролируемые тесты материалов/разрешения.
Никаких измерений E65 и заявления ускорения до телефонного запуска нет.

## Проверки и сборка

Source и **packaged CLDC JAR**:864 комбинации (3 позиции ×3 pitch ×24 yaw ×4 river
frames), покомпонентное равенство мировых XYZ, UV, RGB, alpha255, indices/strips,
projection/camera, texture pixels и material/depth/alpha state. Проверяется,
что L2D реально вызывает Node API, не immediate API напрямую в адаптере;
нет hot vertex allocation/upload. Controller/target recovery/ошибки release
проверены отдельно. Doubles **не растеризуют и не дают phone FPS**.

Все182 класса/все assets Tex3, кроме manifest, побайтно оставлены для контроля A.
Старые JAR не заменены. CLDC47.0/preverify, source/asset/converter hashes,
исходный L2D provenance, отдельный MIDlet и детерминированный JAR проверяются.

```sh
JAVA="$PWD/build/tools/jdk4py/java-runtime/bin/java" ECJ_JAR="$PWD/build/tools/ecj.jar" \
  python tools/l2d-map/convert.py
JAVA="$PWD/build/tools/jdk4py/java-runtime/bin/java" \
  python scripts/build_l2d_map.py --output-dir downloads
JAVA="$PWD/build/tools/jdk4py/java-runtime/bin/java" ECJ_JAR="$PWD/build/tools/ecj.jar" \
  python tests/l2d_map/run.py --packaged
python -m unittest discover -s tests/resources -p 'test_l2d_map.py'
```
