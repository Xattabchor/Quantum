# Режим 5 — BLEND с цветом из level-конфига

18.10.2026. Замена применяется **только к выбранному M3G**, включая его CPU
fallback. Исходный Software mode5 и форматы материалов/моделей сохранены.
Предыдущий откат KVM-оптимизаций и прямой вывод Image через drawImage сохранены.

## Конфигурация

В конфиге уровня:

```ini
M3G_BLEND_COLOR=255,128,64
```

Три десятичных компонента R,G,B, каждый 0..255. Пробелы вокруг чисел допустимы.

| Цвет | Пример |
|---|---|
| Чёрный, по умолчанию | `M3G_BLEND_COLOR=0,0,0` |
| Серый | `M3G_BLEND_COLOR=128,128,128` |
| Красный | `M3G_BLEND_COLOR=255,0,0` |
| Оранжевый | `M3G_BLEND_COLOR=255,128,64` |
| Голубой | `M3G_BLEND_COLOR=64,192,255` |
| Синий | `M3G_BLEND_COLOR=64,128,255` |
| Белый | `M3G_BLEND_COLOR=255,255,255` |

Цвет общий для всех материалов **5** текущего уровня. Номер режима в исходных
ресурсах менять не нужно. LevelLoader читает ключ ровно при загрузке уровня,
через `DirectX7.configureM3GBlend`. Это **level-only**, без нового пункта меню,
RMS или fallback к общему setting. Нет ключа — чёрный. Неверное значение
(не три числа, компонент вне диапазона, переполнение, HEX вместо R,G,B) также
безопасно сбрасывается в чёрный. Следующий уровень не наследует прежний цвет.

## Как выглядит BLEND

Используется настоящий M3G **Texture2D.FUNC_BLEND** с белым primary RGB и
`Texture2D.setBlendColor(K)`. Это **texture environment**, а не смешивание
поверхности с уже нарисованным framebuffer, как ALPHA/ADD/MODULATE.

По каждому RGB-каналу при значениях 0..1:

```
out = 1 - texture + K * texture
```

- При K=чёрный — инверсия RGB текстуры.
- При K=белый — белая поверхность.
- Чёрный непрозрачный texel становится белым; белый — выбранным цветом.
- Цветовые компоненты смешиваются независимо: это не обычное умножение/тонирование.
- Нулевой прозрачный texel остаётся отверстием. Как у прежнего режима 5,
  ненулевые texel используют binary cutout, дробная alpha не делает их полупрозрачными.
- Результат намеренно отличается от старого ambient/directional освещения.

BLEND нативно применяется к стандартным шести классам полигонов мира и к
материалу 5 модельного SkyMesh. Небо сохраняет собственную геометрию/камеру/culling.
UV, выбор mip, кэш текстур, painter order, исходные размеры и анимация не заменялись.
Остальные мировые режимы и отдельная нумерация Sprite.mode не меняются.

## Что удалено

Из действующего игрового режима 5 удалены Material, ambient/directional lights,
weapon OMNI, metric-camera/shot-pulse путь, вычисление/упаковка нормалей и normal
VertexArray. Из batch удалены lighting-only normal arrays и light/pulse state.
M3GMode5Lighting больше не входит в исходники/JAR. Оружие сохраняет исходный
Software shootIntensity, но не запускает M3G-свет.

Для совместимости публичные startWeaponLight/startMuzzleGlow — no-op,
getMode5WeaponLight возвращает null. API-free Mode5WeaponLight/mode5Shade
оставлены как legacy-утилиты, игровой рендер их не вызывает и не создаёт pulse.
Это не возврат удалённого fake additive overlay. Обычные ADD-спрайты остаются.

## Состояние и fallback

Соседние совместимые грани сохраняют прежние пакеты до 64 треугольников.
Function и blendColor фиксируются при приёме пакета; новый цвет не перетирает
старый pending draw. Разные функции/цвета разделяют пакеты без изменения порядка.
Смена цвета не создаёт новую Image2D и не перекрашивает CPU-копию текстуры.

При ошибке native draw/readback/OOM весь текущий кадр по-прежнему откатывается
и повторяется на CPU. Для режима 5 CPU использует те же affine/perspective
scanline-координаты, но формулу BLEND вместо освещения: без LUT, временного
кадрового массива, изменения исходных texel или converted texture allocation.
Возможны обычные различия округления native/API реализации до единицы канала.
Без JSR-184 работает этот же CPU fallback. Software выбран отдельно — исходный
mode5, без BLEND и без зависимости от M3G_BLEND_COLOR.

## Проверки и ограничения

- Mode5BlendTest: RGB parsing/reset/invalid input, все 65536 пары байтов texture/K;
  шесть классов полигонов, native function/constant/primary color, отсутствие
  Material/lights/normals/Fog и реакции на shot/torch/lightdir;
- CPU fallback сравнивается с независимой перекраской тестовой текстуры и
  исходным mode0 sampler при affine/perspective и всех fogQ; source pixels неизменны;
- frozen packet colors, общий cache с mode0, native sky5, warm 100 triangles ->
  2 draws, без новых uploads/barriers; mixed CPU/native atomic rollback;
- Software mode5 не зависит от выбранного цвета; Image path не добавляет
  финального getRGB; старый lighting adapter отсутствует, lighting APIs не вызываются;
- прежние M3G/framebuffer/settings и missing-JSR184 suites, настоящая Java ME
  компиляция и CLDC preverification. Тестовые API doubles не входят в MIDlet.

Это контрактные проверки, не визуальная GPU-эмуляция. Скорость на телефоне/
J2ME Loader этой сборкой не измерена. Убрана работа прежнего lighting-пути,
но прирост FPS и аппаратное ускорение конкретной JSR-184 реализации не гарантируются.

## Сборка

Quantum.jar: 460203 байт.
SHA-256: `645a187d933b8df9fc43887296c0a70fbcaf646dd935b007f9523c1baec2b7dd`.
145 classes, 1852 code methods, 993 StackMap attributes, class version 47.0.
Полный M3G suite: 140 PASS groups; framebuffer и settings также пройдены.
Игровые ресурсы в сборку движка не добавлялись.
