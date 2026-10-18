# M3G material 12 → EXP Fog — 18.10.2026

Историческая EXP-сборка dd898a4. Текущий downloads/Quantum.jar дополнительно
содержит [оптимизацию для ARM9/OMAP1710](m3g-arm9-omap1710.md); EXP сохранён.

JSR-184 предоставляет `Fog.EXPONENTIAL` (80) и `Fog.setDensity(float)`.
Режим 12 выбранного M3G теперь использует этот API для мировых 3D-полигонов,
вместо прежнего alias 0. Это именно EXP, не EXP² и не линейный Fog.
Mode 11 остаётся DECAL, 3/6 — LINEAR, 9/13 — static RGB без distance Fog.
Software mode 12 остаётся исходным dithering-эффектом.

## Ключи level.txt / levelN.txt

Добавить в общие параметры уровня, рядом с FOGC/FOGD, до секции WTEX:

```ini
M3G_EXP_FOG_COLOR=128,144,160
M3G_EXP_FOG_DENSITY=0.05
```

| Ключ | Значение | Без ключа / при ошибке |
| --- | --- | --- |
| `M3G_EXP_FOG_COLOR` | Три целых R,G,B, каждое 0…255 | Текущий обычный цвет `FOGC` / `FOGCR, FOGCG, FOGCB` |
| `M3G_EXP_FOG_DENSITY` | Число с десятичной **точкой**, 0…1000 | **0.02** |

Оба ключа читаются непосредственно из level-конфига один раз при загрузке.
Каждый уровень сбрасывает параметры к defaults: значения прошлого уровня
не наследуются. Неверный ключ не отменяет корректное значение другого ключа.
Отрицательные/NaN/Infinity/слишком большие density не передаются в M3G.

- Больше density — гуще туман. **0 отключает** EXP; Fog quality OFF также
  отключает его. LQ/HQ при включённом тумане используют native EXP.
- Эти ключи регулируют **только материалы 12**, не назначают режим всему миру
  и не перекрашивают дождь, интерфейс или прочие материалы.
- `FOGD` не является дальностью EXP. Native EXP не имеет LINEAR near/far:
  настраиваются density и color. Настройка меню дополнительно не добавляется.
- Старые файлы продолжают загружаться без новых ключей. Исходные ресурсы
  в поставляемом JAR не переписаны; пример выше надо добавить в нужный уровень.

## Единицы и формула

Сохраняется прежняя проекция M3G: реальная packed eye distance
`zEye = 1 − rz / TexturingPers.move`, а не экранный focal distance/FOV.
Ключ density передаётся в `Fog.setDensity` **без пересчёта**:

`visibility = exp(−density × zEye)`

`RGB = textureRGB × visibility + fogRGB × (1 − visibility)`

При стандартном move=150 и density=0.05 половина видимости достигается примерно
на engine depth 1929; при default 0.02 — примерно 5049. У near plane zEye=1,
поэтому положительный density даёт небольшой туман уже вблизи: искусственный
нулевой near offset не добавлен. Если move меняется, соответствующая дальность
в engine units меняется вместе с ним.

Mode 12 остаётся unlit textured материалом с белыми вершинами, REPLACE и
прежним cutout для нулевых texel'ов. Ненулевая исходная fractional alpha, как
раньше у alias 0, не превращает его в framebuffer ALPHA. Native Fog применяется
после текстуры; vertex lighting/dynamic lights для 12 не добавлены.

## Геометрия, batches и fallback

- Mode 12 использует `packFog`: реальные eye weights сохраняются даже с affine
  UV. Native perspective correction, painter order, world CULL_BACK/CW,
  UV/mip и near+viewport clipping сохранены.
- Состояние пакета включает Fog mode, density, color и eye scale. Изменение
  density не меняет уже ожидающий packet; LINEAR/EXP не объединяются.
  Совместимые пакеты по-прежнему ограничены 64 треугольниками.
- Fog mode/density выставляются повторно только при изменении этих параметров
  private Fog object; прежний LINEAR-путь не получает лишнего setMode каждый draw.
- Как и для 3/6, **native sky shader EXP не реализует**. SkyMesh с материалом 12
  консервативно целиком уходит в CPU, без частичной native-отрисовки. При density
  0 world материал без Fog работает native, но sky preflight остаётся строгим.
- При selected-M3G CPU fallback/отсутствии JSR-184/ошибке native EXP выполняется
  полный прежний CPU replay, но без legacy dithering 12: применяется цветной
  Fog по экспоненциальным vertex factors. Используются прежние HQ colored-fog
  samplers для affine/perspective/floors, в том числе при force-LQ recovery.
  Это CPU-аппроксимация, не обещание pixel-identical GPU raster.
- API-free Mode12Fog вычисляет recovery factors через range reduction/Taylor:
  CLDC Math не требует exp. В native-пути CPU factors не вычисляются, текстурные
  копии и новый framebuffer не создаются.
- Scoped CPU flag и временный материал восстанавливаются в finally, включая
  исключения. Общие `fogc/fDist` не подменяются; обычный Software получает
  прежнюю математику и цвет через неактивную ветку scoped getter.

EXP — стандартная возможность JSR-184, но конкретный драйвер/наличие API и
скорость зависят от телефона. Если вызов native Fog завершается ошибкой,
действует существующее отключение backend с полным CPU replay, а не частичный
кадр или скрытое обнуление B. B:0 для всех материалов/объектов не обещается.

## Проверки и артефакты

Mode12FogTest проверяет defaults/reset/RGB/invalid density, CLDC approximation,
шесть polygon classes, affine/perspective, quality OFF/LQ/HQ, native equation и
fDist=0, cutout/cache/state reset; геометрический CPU fallback, flushAlpha,
native/readback/EXP-setter errors, полный replay; реальные CPU pixels на plane
и floor с force-LQ; snapshot density, LINEAR↔EXP, 65 faces → 2 draws, warm T0;
exception scope и неизменность Software mode12 при изменении новых ключей.
WorldClipTest дополнен mode 12; отсутствие API проверяется реальным classpath
без javax.microedition.m3g. Loader key hook проверяется source contract.

- **161 PASS** default M3G, **168 PASS** resource41, **210 PASS** profile build,
  включая Mode12FogTest с активной FrameProfile/balanced nesting.
- Framebuffer/settings и 8 Python resource/profile tests прошли.
- Все **256 строк** level41 и **86 sky streams** полностью совпадают с adc5596,
  B:0/T:0 в прогретой выборке сохранены. Это regression scene без mode 12,
  не демонстрация аппаратной экспоненты на телефоне.
- CLDC **149 classes / 1893 code methods / 1016 StackMap / 47.0**, без профайлера.
  140 прежних classfiles неизменны; изменены LevelLoader, DirectX7,
  M3GPolygonBatch/State, M3GSceneRenderer, TexturingAffine/Pers/Floors.
  Добавлен Mode12Fog. Классы engine/game равны, все **505 ресурсов** неизменны.
- `downloads/Quantum.jar`: **473227 байт**, SHA-256
  `6e5a20684be2fc03fc7e60f67d4e5ae6092f8ab5c94c144fb5e150a523daa3be`.
- Полный `artifacts/mode12-exp/Quantum_Level41_ExpFog12.jar`: **9770494 байт**,
  SHA-256 `6ed72278a9f50ca94faf91092077b5794acab8e8640c8884a73bd80f7542708c`.
  Matching JAD рядом; Quantum_Engine.jar/.jad — без игровых ресурсов.

API doubles не являются GPU raster/FPS benchmark; на телефоне этот билд не
запускался. Локальные логи: `build/mode12-exp/{default,level41,profile}.log`.
