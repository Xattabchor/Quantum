# M3G material 11 → DECAL — 18.10.2026

> Следующая правка: [material 12 → EXP Fog](m3g-mode12-exp-fog.md).
> Описание mode 12 как alias 0 ниже относится к предыдущей сборке adc5596.

Режим 11 в выбранном M3G больше не является alias режима 0. Для world polygons
и модельного SkyMesh выставляется настоящий `Texture2D.FUNC_DECAL` (226).
Mode 12 остаётся alias 0, Software mode 11/12 — прежние dithering-эффекты.
Номера и содержимое ресурсов, конфиги и настройки менять не требуется.

## Формула и прозрачность

Native состояние: RGBA texture с сохранением исходной альфы, белые непрозрачные
вершины, CompositingMode.REPLACE, alpha threshold 0, без Fog и Material/lights.
DECAL смешивает **текстуру с цветом вершины**, не с framebuffer:

`RGB = texture.RGB × texture.A + white × (1 − texture.A)`; выходная альфа — 1.

- A=255: исходный RGB текстуры.
- A=128: текстура приблизительно наполовину смешана с белым.
- A=0, включая исходный нулевой texel: **белый**, не прозрачное отверстие.

Это намеренно не framebuffer ALPHA режима 1 и не прежний cutout режима 0.
Нельзя сохранять отверстия прежним alpha threshold: DECAL имеет непрозрачную
выходную альфу. Аппаратные округления/фильтрация могут отличаться от CPU.

## Пути отрисовки и fallback

Сохранены UV/mip, clipping, painter order, world backface culling, bounded
batching и существующий texture cache. Alpha image может разделяться с mode 1;
function/composite boundaries сохраняют очередь без мутации pending packets.
Native clipping дополнительно проверяется с mode 11 и HW perspective ON/OFF.

Сертификат непрозрачного model sky допускает 11 с тем же консервативным
nonzero-texel условием, что и 0. DECAL сохраняет opaque vertex alpha; переключение
0↔11 не делает opacity cache небезопасным. Для полностью закрытого nonzero sky
прежняя оптимизация прогретого B:0/T:0 сохранена. Нули по-прежнему консервативно
отключают сертификат, хотя сам DECAL выводит их белыми.

При selected-M3G CPU fallback/полном replay API-free Mode11Decal подготавливает
непрозрачные texel-копии по той же формуле и передаёт прежнему mode 0 raster.
Это не возвращает исходный dithering и не зависит от waterDistance/fDist.
Источники pixels/mip/material не меняются. Кэш ленивый, до четырёх RawImage,
учитывает замену image/pixel array, размеры, scale и animation; есть копии до
трёх используемых mip-уровней. На cache hit нет повторного копирования pixels.
Первые CPU-отказы требуют дополнительной памяти под копии; native-only mode 11
их не создаёт. При изменении массива на месте, как и для native texture cache,
нужен releaseRenderer; он очищает и этот кэш. Software его не использует.

## Проверки

- Mode11DecalTest: шесть polygon classes, affine/perspective, исходные alpha
  255/128/0; настоящий FUNC_DECAL, REPLACE, white base вместо framebuffer,
  threshold/Fog/lights; cache/state reset и неизменность источников.
- Geometry/flushAlpha fallback, native/readback failures: полный CPU replay
  совпадает с CPU-эталоном DECAL для каждого проверенного alpha и качества.
- Mip cache, array/image replacements; native model sky с fractional alpha;
  certified sky B:0/T:0; общие source/native images mode 11↔0 и 11↔1, порядок
  и отсутствие повторных warm uploads. Сохранены mode 12 alias/Software tests.
- **155 PASS** default M3G, **162 PASS** resource41, **197 PASS** profile build,
  включая активную инструментализацию Mode11DecalTest и balanced nesting.
  Framebuffer/settings и 8 Python resource/profile tests прошли.
- Все 256 диагностических строк level41 (включая B:0/T:0) и 86 sky streams
  **полностью совпадают** с f2e52a3: эта сцена не использует новый mode 11.
  Это regression check, а не демонстрация DECAL на реальном GPU.

## Сборки

Обычный CLDC 47.0: 148 classes, 1882 code methods, 1013 StackMap; FrameProfile
не включён. Из прежних 147 classes изменены DirectX7, M3GSceneRenderer,
M3GSkyRenderer, SkyCoverage; 143 неизменны. Добавлен Mode11Decal.
Engine/game class maps одинаковы, все 505 исходных ресурсов byte-identical.

- `downloads/Quantum.jar`: **470984 байт**, SHA-256
  `d3130d0d7ad2d32b3572c80f2732d69ab0cafb9976f6438de1946085042bcc19`.
- `artifacts/mode11-decal/Quantum_Level41_Decal11.jar`: **9768251 байт**, SHA-256
  `c859cadc7f336cbe89817506f558cc754140585a6a55c4a4b2d45d615c2fd9c8`.
- Matching JAD рядом с каждым JAR; Quantum_Engine.jar/.jad без игровых ресурсов.

API doubles проверяют dispatch/state/арифметику, не GPU-растр и не FPS телефона.
Логи локально: `build/mode11-decal/{default,level41,profile}.log`.
