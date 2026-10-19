# Static Room Depth — Nokia E65, стены и near/far

## Сообщение пользователя и гипотеза

Nokia E65 / TI OMAP1710, кэш «32/16». Во всех зелёных домах деревни внутренние
столы, стулья и пол видны через наружные стены. По уточнению пользователя это
есть и в предыдущих сборках, а на эмуляторе всё нормально. Конкретный набор
затронутых JAR и изображение дефекта ещё не получены. Поэтому не считаем ошибку
регрессией strip joining, но и не исключаем другие причины без проверки.

Во всех наших cached native projections найдена **near=1 / far=68500**.
При конечной точности depth сильное сжатие глубин вдали может приводить к
одинаковому depth code у стены и находящейся за ней мебели. Это согласуется с
описанием, но **реальный формат/битность depth E65 не измерены**, и ошибка пока
не воспроизведена на устройстве разработчика. Другие причины (driver/material/
geometry/сортировка) не объявляются исключёнными.

Иллюстративная модель **16-bit depth** на фактических float projection coefficients
показывает одинаковый code на eye distances10000/10100 при near1 — как при
far68500, так и при far60000. С near128 эти две поверхности различимы. Это
аналитический пример, **не эмуляция формата E65 и не доказанное исправление**.
Уменьшение far на12,4% само по себе почти не меняет нелинейную точность вдали
при near1. Уменьшить нагрузку/количество дальних поверхностей оно может.

## Отдельная диагностическая сборка

`downloads/Quantum_StaticRoom_Depth.jar` — **Quantum Static Room Depth**.
761697 байт / SHA-256
`053252da302fa8e9c8661617e6b4985b20887c57af942fb8e10939a8f0079316`.

- **0**: A near128 / B near1.
- **5**: far60000 / исходный far68500.
- По умолчанию: **near128, far60000**.
- `4/6` поворот, `2/8` движение, `7/9` стрейф, `#` выход.
- HUD явно показывает FAR, near mode, T, FPS и coarse native timings.

Два переключателя независимы. Для сравнения только глубины стоять у проблемного
дома и переключать0, не меняя5. Для изоляции влияния сокращения дальности
удерживать выбранный near и переключать5. B near1 + far68500 возвращает
исходную проекцию. После каждой смены дать5–10s на прогрев и обновление HUD.
Проверить также подход вплотную к стенам и вход внутрь дома.

**Компромиссы явные:** near128 переносит ближнюю плоскость и может подрезать
очень близкие поверхности; far60000 отсекает более далёкую геометрию. Не
обещаем идентичную картинку между разными clipping domains. В float far plane
также есть обычное округление coefficients. Запрошенная дальность не подменяется
другим значением скрыто: она выбирается отдельной клавишей и отображается.

Пользователь разрешил уменьшить дальность до60000, если поможет. Для сохранения
точного пользовательского файла **level1.txt не меняется**: runtime default
`min(60000, configured DIST)` и key5 восстанавливает исходный DIST=68500.
Все10 ресурсов и165 исходных class bytes Present `0ec8560` сохранены побайтно.
Добавлены новый viewer и DepthMesh (7classes). Предыдущие JAR/JAD/reports не
перезаписаны. Отдельный комнатный тест, не полная игра.

## Что не меняется

Полная геометрия:10032 vertices/4750 source triangles, те же XYZ/UV/RGBA,
порядок, три native material packets, baked mode9, отсутствие Fog, река mode7
и все её кадры. Исходные **необъединённые** strips, без новых degenerates.
Обе near-настройки используют тот же экземпляр cache и тот же Image target;
не возвращается ненужный legacy DirectX7 CPU framebuffer. Нет CPU-проекции,
wall/furniture hacks, sorting override или polygon depth offsets.

Frustum culling получает фактические изменённые projection coefficients,
не старую near1 plane. При index-cache OOM сохраняется прежний whole native
fallback без CPU и пропусков. Новые visibility masks могут аллоцировать индексы;
прогретая фиксированная near/far/pose их не перестраивает.

## Проверки

```sh
export JAVA="$PWD/build/tools/jdk4py/java-runtime/bin/java"
export ECJ_JAR="$PWD/build/tools/ecj.jar"
python scripts/build_static_room_depth.py
python tests/static_room/run.py --depth --present --packaged \
  --jar artifacts/static-room-depth/Quantum_StaticRoom_Depth.jar
python -m unittest discover -s tests/resources -p 'test_static_room_*.py' -v
python scripts/verify_render_checkpoints.py
```

- Java ME compile/CLDC preverify PASS:172 classes /2082 code methods /
  1138 StackMaps /47.0. Все baseline class/resource bytes проверены.
- Готовый JAR проверен с API doubles, не renderer, пересобранным вместо JAR.
  Все предыдущие cached/culling/Present contracts PASS.
- **3456** cases:3points×3pitch×24yaw×4river frames×2near×2far. Near1 точно
  совпадает с прежним cached renderer при той же дальности; полные native
  geometry/UV/colors/indices/state одинаковы, кроме разрешённых P10/P11.
- Оба clipping domains независимо проверены double homogeneous triangle
  clipping: culling не выбрасывает потенциально видимые треугольники в новом
  frustum. Настоящее near clipping не скрывается и проверено отдельно.
- Fixed-mask повтор не создаёт native indices/vertices/textures; <=3 API calls.
  Invalid near /near>=far rejected; mask OOM whole native и error target release PASS.
- Cold Viewer, все6 изображений из JAR, четыре комбинации0/5 и восстановление
  defaults PASS. Семь resource/package tests и семь rollback checkpoints PASS.

Заглушки/resource anchors Room и StringTools — только desktop tests, не JAR.
Не handset depth-bit/FPS/pixel test. Логи: `build/static-room-depth-{source-tests,
build,packaged-tests,packaging-tests}.log` (build игнорируется Git).

**Ожидаем обратную связь:** видны ли столы/пол через стены в A и B при одной и
той же FAR; есть ли новое подрезание вплотную; затем FPS60000 против68500.
Если дефект не исчезнет, нужен кадр проблемного места для следующей гипотезы.
