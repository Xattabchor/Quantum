# Static Room Cull — cached A/B, консервативное отсечение

## Статус и запуск

Пользователь сообщил 7 FPS cached против 5 projected и подтвердил правильную
картинку. Это пользовательский результат, модель телефона и метод измерения
не уточнены. Стабильная игра, первый эксперимент и cached merged v2 сохранены:
`python scripts/verify_render_checkpoints.py` проверяет все три контрольные точки.

**Новый файл: `downloads/Quantum_StaticRoom_Cull.jar`.** Это отдельный MIDlet
Quantum Static Room Cull, не замена игровому Quantum и не перезапись v2.

- **A cached cull** — кэшированная геометрия с отсечением за пределами frustum.
- **B cached whole** — та же геометрия, но вся комната, без отсечения.
- `0`: A/B. `4/6`: поворот, `2/8`: движение, `7/9`: стрейф, `#`: выход.
- HUD `T x/4750` — число отправленных треугольников; `D` — native API draws.
- Если не хватает памяти для нового index mask, `MEM->whole` означает переход
  на уже подготовленную полную native-комнату, **не CPU renderer**.

**B теперь тоже cached!** Для старого сравнения с projected painter остаётся
`Quantum_StaticRoom_Merged.jar`. Сравнить новую A/B стоя в одном месте после
5–10 секунд прогрева. При движении также обратить внимание на паузы/GC.
NPC/AI, небо, дождь, оружие, прицел и сохранения в просмотрщике отсутствуют,
как и раньше. Общая игровая производительность из этих FPS не следует.

## Почему не сделали по draw на каждый блок

Разбиение на независимые native meshes могло бы сильно поднять число draws.
Здесь сохраняются **те же 3 VertexBuffer/material packets**, а пространственные
блоки используются для выбора **индексов**. Полные vertex arrays остаются в M3G.
Обработка всего vertex buffer или только использованных вершин зависит от
драйвера: уменьшение submitted triangles не является замером GPU vertex work.

В каждом packet при загрузке построены до 16 клеток по X/Z. Грань назначается
по центру её AABB, но bounds клетки расширяются **на всю грань**, включая
длинные полигоны, пересекающие несколько клеток. В кадре проверяются только
AABB клеток против шести clip planes. Нет Java-проекции исходных вершин.

Clip planes получены из точной float-проекции и обратной той же квантованной
camera transform, что передана M3G. Используются фактические near/far float
coefficients, а не идеальное значение far, которое может отличаться из-за
округления матрицы. Вокруг plane tests есть консервативный численный guard;
невалидная/сингулярная матрица не отсекает геометрию.

**Порядок граней не меняется:** собирается подпоследовательность исходных strips,
а не сортировка по клеткам. Геометрия, UV, baked RGBA, winding, depth/cutout,
water mode7 и texture animation остаются прежними. DIST, FOV, viewport и
native presentation совпадают в A/B. Occlusion culling, снижение дальности и
замена ресурсов не применяются.

## Кэш и память

- Полный native index buffer создан при загрузке и всегда сохранён для B/fallback.
- На packet — **два** кэшированных partial index masks; FIFO вытеснение ограничено.
- Если видимость не изменилась, нет новых indices, vertex arrays или texture uploads.
- При **новой** маске видимости перед bindTarget строится новый index buffer.
  Это выделение памяти, и при движении возможен GC; warm-only тест его не скрывает.
- Source indices/strips и cell bounds удерживаются на CPU; сами vertex arrays
  не перестраиваются и не загружаются заново. Handset heap не измерялся.
- При OOM создания partial mask кэш масок освобождается, все packets возвращаются
  к полным native indices. Повторных попыток аллокации до пересоздания room нет.
- Любая ошибка во время native draw по-прежнему освобождает target через finally.

## Проверки

`python tests/static_room/run.py`:

1. 96 full-cache witnesses против immutable исходников v1: исходные world XYZ,
   UV, RGBA, texture/state/camera и порядок треугольников совпали.
2. **864 позы** настоящего kordos: 3 точки, 3 pitch, 24 yaw, 4 river frames.
   Каждый отброшенный треугольник независимо клиппирован на double в homogeneous
   clip space (Sutherland–Hodgman), с отдельным Gauss–Jordan inverse камеры.
   Ни одного potentially visible треугольника не потеряно. Остаток — строго
   исходная подпоследовательность индексов с неизменными vertex/UV/color buffers.
3. 10000 случайных AABB, включая non-orthonormal camera и singular fallback.
4. Стабильная маска: ноль новых native indices/vertex arrays/texture uploads.
   Cache limit = 2, mask OOM -> whole native, actual Viewer A/B/A paint — PASS.
5. Preverify реальными Java ME SDK/JSR184 и ProGuard; все исходные engine classes
   и ресурсы опубликованного DrawCalls JAR побайтно неизменны. Заглушки в JAR
   не включаются.

### API-результат, не FPS

- Стартовая точка, pitch=0, 24 yaw × 4 frames: среднее **2337,29 / 4750** submitted
  triangles (примерно −50,8%), диапазон 1538–3745; yaw=0: **2090 / 4750**.
- Все 864 poses, включая взгляды вне мира/вверх: 0–3923, сумма 1408340 вместо
  4104000. Этот набор не является «средним игровым кадром».
- API draws: 0–3, не растут относительно whole cache.
- 264 cold visibility-mask constructions за последовательный sweep 864 poses;
  повторный render каждой фиксированной позы не создавал native buffers.

Новая A на телефоне ещё не проверена. Следующий шаг — FPS A/B и плавность при
повороте; только после этого переносить отсечение в дальнейшую игровую интеграцию.

## Сборка

```sh
export JAVA="$PWD/build/tools/jdk4py/java-runtime/bin/java"
export ECJ_JAR="$PWD/build/tools/ecj.jar"
python scripts/build_static_room_preview.py
python tests/static_room/run.py
python scripts/verify_render_checkpoints.py
```

Сборщик по-прежнему добавляет только экспериментальные классы к точно закреплённому
`Quantum_Sym9_DrawCalls.jar`. Условия теста: full Canvas, FOV 74, pixelsQ=2,
persQ=4, fogQ=2 (качество baked-цветов), mipMapping=false. Config/asset bytes
не меняются. Контрольные точки и инструкция отката: `docs/render-rollback.md`.
