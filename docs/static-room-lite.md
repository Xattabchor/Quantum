# Static Room Cull Lite — только ресурсы просмотрщика комнаты

## Что это

`downloads/Quantum_StaticRoom_Cull_Lite.jar` — отдельный MIDlet **Quantum Static
Room Cull Lite**, облегчённая упаковка принятого пользователем Cull `fcb5141`.
Не полноценная игра. Полный Cull, Merged, Test и стабильный DrawCalls не изменены.

**734744 байта вместо 7883827: −7149083 байта / −90,68%.**
SHA-256: `2f7cf94575b71bfd16f20648140ac49df04a82780ccb934e802aa1e9a38f4fdc`.

Пользователь сообщил для исходного Cull **8–9 FPS** против прежних 7 и затем
подтвердил отсутствие пропадания видимых поверхностей. Устройство, одинаковость
позиций и метод измерения не уточнены. Это не наш независимый benchmark и
**не результат Lite**. Уменьшение размера JAR не означает такого же уменьшения
runtime heap или прироста FPS: исключённые данные раньше не загружались тестом.

## Состав ресурсов

Ровно **10** файлов, побайтно идентичных полному Cull:

- `setting.txt`, `level1.txt` — целиком, без удаления строк или изменения настроек;
- `maps/kordos/kordos.3d` — исходная модель;
- `lightmap/kordod.vla` — запечённое освещение;
- `maps/kordos/forestOrigy.png`, `maps/kordos/map-selo1.png` — текстуры;
- `maps/kordos/river0.bmp` … `river3.bmp` — все четыре кадра реки.

Остальные ресурсы (188 файлов) исключены только из нового Lite: музыка, звуки,
NPC, оружие, меню/шрифты, небо, другие карты, запасные текстуры и lightmaps.
Их исходные версии остаются в полных JAR. Ссылки на них в полном конфиге не
переписаны: просмотрщик **не запускает** gameplay/sky/audio loaders.

Все **162 class-файла** сохранены побайтно вместе с CLDC StackMaps, включая
зависимости движка, которые может проверять Java ME linker. Это ресурсное
облегчение, **не удаление недостижимых методов или классов**. Не пересобираем
старые production sources и не меняем рендер ради размера. Сжатие ZIP сильнее;
меняется только MIDlet-Name и контейнер, не содержимое нужных данных/классов.

## Управление и картинка

Без изменений относительно Cull:

- `0`: **A cached cull / B cached whole** (не projected);
- `4/6`: поворот; `2/8`: движение; `7/9`: стрейф; `#`: выход;
- до 3 M3G API render calls; прежняя дальность/FOV, mode9/static light и mode7
  animated river; HUD FPS/D/T и тот же native whole-room OOM fallback.

Полные ограничения рендера: [static-room-culling.md](static-room-culling.md).

## Воспроизводимая упаковка и проверки

Скрипт принимает **только** полный Cull с SHA-256
`bf3bf49f24bb672af7ed2fb5274c88bdc1ad82522a0604b87a226670537d45c6`.
Вывод по умолчанию идёт в игнорируемую директорию artifacts, а не поверх
опубликованных сборок. Источник и старые артефакты не переписываются.

```sh
python scripts/build_static_room_lite.py
python -m unittest discover -s tests/resources -p test_static_room_lite.py -v
python scripts/verify_render_checkpoints.py

# Существующий desktop toolchain/API doubles; не эмуляция M3G rasterizer:
export JAVA="$PWD/build/tools/jdk4py/java-runtime/bin/java"
export ECJ_JAR="$PWD/build/tools/ecj.jar"
python tests/static_room/run.py --packaged \
  --jar artifacts/static-room-cull-lite/Quantum_StaticRoom_Cull_Lite.jar
# Контроль на исходном полном JAR:
python tests/static_room/run.py --packaged --jar downloads/Quantum_StaticRoom_Cull.jar
```

Результаты:

- 4 упаковочных теста PASS: точная зависимость WTEX (включая все кадры), отказ
  при пропавшем/небезопасном пути и неверном input hash, состав, байтовая
  идентичность, JAD size/URL, воспроизводимость и сохранность исходника.
- ZIP CRC, структурный CLDC47.0 аудит: 162 класса / 2018 code methods /
  1098 StackMap attributes. Исходные preverified class bytes не менялись,
  поэтому повторная компиляция/preverification не нужна.
- Проверен **готовый Lite JAR**, без компиляции experimental renderer вместо
  него: 96 ordered witnesses vs v1, 864 culling poses, 10000 AABBs,
  stationary allocations/cache limit/OOM fallback — PASS. Полный Cull также PASS.
- Cold Viewer.load + A/B/A: очищен Asset cache, шесть изображений действительно
  прочитаны/декодированы из выбранного JAR; missing resources/error-image
  fallback отсутствуют, размеры и все пиксели совпадают с исходными.
- Desktop-тесты используют API doubles и временные resource anchors для Room
  и StringTools (desktop Object.class не видит MIDlet resources). Эти файлы и
  заглушки **не входят** в JAR; это не pixel/GPU/handset benchmark.
- Все три прежних rollback checkpoints проверены, PASS.

Логи (build игнорируется Git): `build/static-room-lite-build.log`,
`build/static-room-lite-packaging-tests.log`, `build/static-room-lite-packaged-tests.log`,
`build/static-room-full-packaged-tests.log`, `build/static-room-lite-source-tests.log`.
Состав/хеши каждого оставленного файла и перечень удалённых — в соседнем
`downloads/Quantum_StaticRoom_Cull_Lite.build-report.json`.
