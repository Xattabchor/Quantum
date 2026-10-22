# Quantum Kordos FX — полная игра на kordos с аппаратным M3G-освещением

**Артефакт:** `downloads/Quantum_Kordos_Full.jar` (+`.jad`, MIDlet
«Quantum Kordos FX», собирается `tools/kordos-full/build_kordos_full.py`).

Запрос (2026-10-21): добавить нормали в модель карты **kordos** и собрать
полный билд с оружием, врагами и аппаратным освещением + омни для оружия.
Ответ — полноценная игра (меню/арсенал/инвентарь/HUD/враги), где мир
рендерится настоящим M3G true-3D проходом с аппаратными источниками света.

## Что внутри

**Контент** (собран из уже опубликованных в репозитории сборок — ничего
принципиально нового не рисовали):
- карта, текстуры, WMAP-лайтмапа и погода kordos (старая sym9-деревня,
  `Quantum_Village_Planar.jar`);
- полный набор полноигровых ассетов (HUD/иконки/шрифты/звуки/музыка-3 трека,
  пресеты врагов `DROZH_ENEMY` + спрайты «shiver bot», зомби monster.3d,
  спрайтовое оружие) из `Quantum_Level41_River7_48bd4d0.jar`;
- НЕ взяты: карты/тюрьмы River7 (5.5 МБ) и почти вся музыка;
- скайбокс skybox5.3d (был у River7; в оригинальном уровне kordos тот же).

**Уровень** (`tools/kordos-full/overlay/level1.txt`): тот же kordos
(WORLD_SCALE=6, DMODE=9, лайтмапа, дождь, старт/финиш как в релизной
деревне), противники:
- 6 спрайтовых стрелков `DROZH_ENEMY` на позициях, где у деревни стояли
  ARMY1/STALKER/PLOT;
- 6 зомби (5 Zombie + BigZombie) по респаунам ENEMIES= (бывшие точки
  STALKER/ZOMBIE/пещеры);
- радиационные зоны (3 точки, hp-=1) — без звука (wav гейгера нет ни в одной
  опубликованной сборке); атмосферные звуковые триггеры и пропы
  (/prop/md.3d) исключены — этих ассетов нет ни в одном репо-сборнике.

**Свет** (продакшн-код, изолирован за `M3G_LIT`, см. ниже).

## Архитектура M3G_LIT-прохода

Почему «нормали в kordos» — отдельная работа: базовый M3G-путь
(`M3GSceneRenderer`) подаёт полигоны уже спроецированными на экран
(«packed» — M3G используется как blitter). Аппаратные Light+VertexNormal к
таким данным неприменимы физиотачески. Поэтому:

1. **Новый класс `src/code/Rendering/M3GLitRenderer.java`** — только при
   `M3G_LIT!=0`:
   - полигоны мира и мешей уходят в M3G в **view-пространстве** (данные
     есть: `Vertex.recordView` пишет view-координаты на проекции; добавлен
     геттер `getViewDepth`); рендерится через настоящую перспективную
     камеру (generic 4×4, фокал = engine distX/distY — без atan, CLDC-чисто);
   - **нормали** — плоские, вычисляются кросс-произведением из view-данных
     (работает и для статичной карты, и для анимированных мешей; авто-
     ориентация к камере — разметка виндинга не важна) и подаются как
     M3G VertexNormals;
   - **аппаратные источники света** (M3G 1.0 Light): AMBIENT +
     DIRECTIONAL («комнатное солнце») + **OMNI у оружия**: вспышка выстрела
     ведётся `DirectX7.shootIntensity/shootLength` — тот же канал, который
     раньше читал CPU-класс `Mode5WeaponLight` (т.е. «омни для оружия» =
     muzzle-flash; стоячий `flashlightEnabled` из движка тоже читается).
     Источники пересоздаются после каждого bind (Graphics3D.resetLights
     там же живёт);
   - baked-лайтмапа остаётся в vertex colors (M3G модулирует их с материалом)
     — картинка должна быть «та же, но с настоящим светом/вспышкой»;
   - M3G hardware near-clip вместо CPU-клиппера; depth-buffer работает
     (depth-clear раз в кадр при первом bind).
2. **Небольшие правки**: `M3GSceneRenderer` (ветка полигонов вызывает
   lit-проход; beginFrame-хук; доступы к color-буферу/flush), `DirectX7`
   (вызов beginFrame на старте нативного прохода), `Vertex` (геттер
   глубины), `Main` (10 флагов/настроек, default 0 — старое поведение
   неизменно).

Небо (skybox-меш через M3GSkyRenderer), билборды-спрайты врагов/эффектов,
дождь и HUD идут прежними ветками — композиция кадра не менялась.

## Настройки (setting.txt / ключа-эвакуаторы)

| Ключ | Значение по умолч. | Смысл |
|---|---|---|
| `M3G_LIT` | 1 в этой сборке | 0 = старый packed-путь (полный откат), 1 = +омни, 2 = без омни (диагностика) |
| `M3G_LIT_AMBIENT` / `M3G_LIT_DIR` | 96 / 150 | яркости ламп (0..255) |
| `M3G_OMNI_FLASH` / `M3G_OMNI_BASE` | 204 / 0 | пик вспышки / пусть |
| `M3G_OMNI_HALFX` | 4000 | half-distance вспышки, % от фокала (≈20 м для kordos-масштаба) |
| `M3G_OMNI_X/Y/Z` | 8 / -6 / -45 | позиция омни, % от фокала (Z <0 = вперёд) |
| `M3G_LIT_AMBPULSE` | 64 | усилитель ambient при выстреле (изолятор: глобальный свет «немой» быть не может — если амбиент вспыхивает, а омни нет, виновата реализация OMNI на устройстве, а не код) |
| `M3G_LIT_NEAR` / `M3G_LIT_FAR` | 16 / 600000 | планы камеры в мировых юнитах |
| `M3G_LIT_SELFTEST` | 3 | секунд сплошной вспышки после спауна (проверка пути независимо от оружия) |
| `M3G_LIT_NOVCOL` | 1 (эта сборка) | 1 = статическое освещение вырезано полностью: вершинные цвета всегда белые (и лайтмапа не играет нигде), весь свет — аппаратный |
| `M3G_LIT_MIP` | 1 | аппаратный mip NEAREST_MIPMAP_NEAREST (Image2D-цепочка; вода через старый кеш ради анимации), 0 = FILTER_BASE_LEVEL |
| `M3G_SCREEN_SCALE` | 100 | вьюпорт мира в % канвы (25..100): мировой проход рендерится в меньшей поверхности и nearest-апсейлится при выдаче; HUD остаётся чётким |
| `WEAPON_SWAY` | 0 | покачивание спрайта оружия (melee/выстрел): в этой сборке вырезано |
| `M3G_LIT_FLIP` | 0 | 1 = сменить winding (спасалка «мир вывернут») |

**Важно про INI:** `/setting.txt` читается групповым GameIni — все ключи
после первой `[СЕКЦИИ]` попадают в неё и глобально не видны. Поэтому весь
M3G-блок обязан лежать в глобальной зоне (до первого `[`). Первый
телефонный прогон (2026-10-22, «ничего не мигает») сел ровно на это: блок
случайно оказался под `[CAR]`, и билд грузился с `M3G_LIT=0`.

## Протокол теста на E65 (что сказать после первого запуска)

1. **Запускается ли и смотрится ли мир** — и (D); FPS в HUD есть всегда
   (DeveloperMenu.showFps по умолчанию).
2. Если мир вывернут/через задние грани: `M3G_LIT_FLIP=1` → сообщить.
3. Если всё черное или совсем белое: сначала `M3G_LIT_NOVCOL=1`, потом
   `M3G_LIT=2` — сузит виновника (вершинные цветы × свет).
4. **Оружие**: стрельба должна давать локальную вспышку на стенах рядом
   (если не видно — поднять `M3G_OMNI_FLASH` до 255/`M3G_OMNI_HALFX` меньше).
5. **FPS** в трёх точках: старт (поляна), середина деревни, бордюр каньона
   у финиша; сравнить с ожиданием из docs/l2d-metro.md / модели.
6. Материал этого билда отвечает на главный вопрос саги: цена on-device
   M3G hardware T&L+lighting против CPU-packed пайплайна на той же карте.

## Откат

Весь код за флагом `M3G_LIT` (default 0) — предыдущие рендер-пути побайтово
не меняются; RMS/save-layout не тронут (сейвы называются `QUANTUM_KORDOS_FX`).
Предыдущие JAR сохраняются.

## Hardware settings menu (replaces software quality items)

The in-game **Settings → Perfomance** section no longer exposes software
raster quality items; those rows are now hardware toggles, persisted in RMS
store `Quantum.M3GFx` (4 bytes: light mode, hw mip, viewport scale, baked-flag):

| Menu row (label → fallback) | Drives | Values |
|---|---|---|
| `M3G_VIEWPORT` → "Viewport:NN%" | `Main.m3gScreenScale` | 25/50/75/100 (step 25 per key press, clamped); below 100 the scene renders into a smaller surface and is blitted centered at native resolution with black bars — no upscale |
| `M3G_HWLIGHT` → "HW light:OFF/FULL/NO OMNI" | `Main.m3gWorldLighting` | 0 off, 1 full omni flash, 2 without omni |
| `M3G_HWMIP` → "HW mip:ON/OFF" | `Main.m3gLitMip` | hardware NEAREST_MIPMAP chain for lit textures |
| `M3G_BAKED` → "Baked light:ON/OFF" | `Main.m3gLitNoVertexColors` (inverted) | ON = vertex lightmap, OFF = pure hardware light |
| `M3G_OMNI` → "Omni flash:ON/OFF" | `Main.m3gWorldLighting` 1↔2 quick toggle | only when light > 0 |

Software rows removed from the menu (engine fields remain, no longer
reachable from settings): DISPLAY_SIZE (legacy screen size), PERSQ, TEXQ,
FOGQ, MIPMAPPING. CORPSES / BLOOD / FRAMESKIP and the trailing
M3G-only `M3G_PERSPECTIVE` item are unchanged.
