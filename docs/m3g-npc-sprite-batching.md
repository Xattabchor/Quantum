# M3G: batching спрайтов NPC

18.10.2026. Продолжение точного Fog batching (`71ffa86`).
Это автоматическая оптимизация native billboard renderer; AI, выбор
анимации, исходные PNG, Software и 3D-модели NPC не переписаны.

## Что изменено

Раньше каждый видимый Sprite вызывал отдельный `Graphics3D.render`.
Теперь смежные совместимые спрайты объединяются в пакеты до **32 спрайтов**:
128 indexed vertices / 64 треугольника. У quad остаются 4 вершины, а не 6
дублированных. Диагональ и порядок `0,1,3 / 1,2,3` сохранены.

Объединение разрешено только при одинаковых native Texture2D, blend,
alpha threshold, фильтре, точном XYZ scale и параметрах Fog. Между спрайтами
с разными текстурами нет сортировки. Разная глубина native Fog остаётся
разрывом, даже если скин одинаковый: туман не заменён CPU vertex colors.
Clipping/mirroring и прежняя точность UV 1/4096 не менялись.

Пакеты сбрасываются перед миром/моделью/небом/weather/effects, CPU barrier,
сменой HW perspective, ресурсным upload и завершением кадра. Состояние
сохраняется до последующего изменения cached Texture2D. При сбое native
пакета работает прежний полный CPU replay, а не повтор частично отправленных
треугольников.

Постоянный белый RGBA задаётся один раз на buffer, а не передаётся заново
для каждого NPC. Снимок одиночного quad — 40 байт short payload. Буферы
пакета создаются только при появлении второго совместимого Sprite:
1280 байт CPU short payload плюс headers и native position/UV/color arrays.
Index buffers кэшируются по размеру группы; после прогрева per-NPC arrays
и native arrays/indices не создаются. Новых texture atlases/изображений нет.

## Результаты — без завышения эффекта

**Контрольный тест 70 совместимых billboard-спрайтов: 70 → 3 sprite draws.**
С отдельным solid sky — 4 draws, warm B=0/T=0. Всего 8 `VertexArray.set`
вызовов в таком прогретом кадре: 6 position/UV transfers для трёх пакетов
и 2 для неба. Белые vertex colors повторно не загружаются.
Это синтетические billboards, не 70 запущенных AI и не FPS телефона.

**В 80 проверенных позах Stlkr level13 число draw-вызовов НЕ уменьшилось.**
Все 320 cold/warm строк и ordered triangle SHA-256 побайтно совпали
с предыдущей сборкой: совместимых смежных спрайтов в этой выборке не было.
Тяжёлый кадр остаётся **D=75**, включая 6 sprite draws. Уже полученное
уменьшение общей D за счёт Fog batching **95 → 75** этим изменением не отменяется.
Обещать дополнительное ускорение level13 или FPS по этому тесту нельзя.

Чтобы объединять разные скины/кадры анимации, нужен отдельный atlas design:
с лимитом памяти, точным UV mapping, защитой границ тайлов и учётом Fog.
Эта правка намеренно не меняет/не упаковывает игровые ресурсы в атлас.

## Проверки

- Default M3G **172 PASS**, Stlkr level13 **183 PASS**, level41 **179 PASS**,
  активный frame profiler **235 PASS**.
- `SpriteBatchTest`: cutout/alpha/ADD/MUL, HW ON/OFF, Fog ON/OFF, crop/mirror,
  точные physical XYZ/UV/RGBA/material/Fog/projection и порядок треугольников.
  70 совместимых спрайтов дают 3 пакета; 10 различных групп native Fog depth
  остаются 10 пакетами. Это API streams, не GPU raster.
- Warm native arrays/indices переиспользуются; 4 indexed vertices на Sprite.
  Сбой второго sprite-пакета после первого успешного восстанавливает весь
  CPU framebuffer, включая prefix/suffix и отдельный queued CPU tail.
- Проверены A/B/A texture order, blend/cache kind, Fog color/far snapshot,
  pending HW perspective toggle, invisible Sprite, CPU barrier и unsupported
  Sprite dispatch. Старые Sprite/MUL failure tests теперь принудительно
  создают разные пакеты: одинаковые спрайты уже нельзя считать двумя draws.
- Все **320** level13 counters/stream witnesses совпали с настоящим baseline
  до правки и с test-only merge-disabled reference. Level41: все **256**
  строки и **86** native sky streams совпали с reference.
- Framebuffer, settings, frame timing и 9 Python resource/profile tests PASS.
- CLDC preverification: **149 классов**, version 47.0, 1901 code methods,
  1022 StackMap attributes. **147 classfiles побайтно прежние**; изменены
  только `M3GSceneRenderer.class` и `M3GSpriteRenderer.class`.
- Engine/game classfiles одинаковы; все **442 ресурса и имена** Stlkr
  побайтно сохранены, включая Unicode. SDK/tests/profiler не включены.
  Физический телефон, GPU pixels и FPS не проверялись.

## Воспроизведение

После обычного `scripts/build_cldc.py` с Java/ECJ/SDK:

```sh
python tests/m3g/run.py
QUANTUM_RESOURCE_JAR="$PWD/build/npc-batch/stlkr/resources.jar" \
  QUANTUM_LEVEL13_PROBE=1 python tests/m3g/run.py > optimized.log 2>&1
QUANTUM_RESOURCE_JAR="$PWD/build/npc-batch/stlkr/resources.jar" \
  QUANTUM_LEVEL13_PROBE=1 QUANTUM_SPRITE_BATCH_REFERENCE=1 \
  python tests/m3g/run.py > reference.log 2>&1
```

Assets: `python scripts/game_resources.py --archive 'Stlkr by ebk.jar (1).zip'
--output-dir build/npc-batch/stlkr`. Reference меняет только временную копию
тестового исходника, сбрасывая Sprite-пакет перед следующим Sprite.
Это не game option и не отключение Fog. Новый тест, требующий меньшего D,
в reference не запускается. Для профиля сначала `--profile-frame` build,
затем `QUANTUM_FRAME_PROFILE=1 python tests/m3g/run.py`; release без профиля.

## Сборки

- `downloads/Quantum.jar`: **476014 байт**, движок без ресурсов.
  SHA-256 `4b5b222c8b56409533b8cf31561b1eb1de128c04000ee62fd46bbbb530c4775d`.
- `downloads/Quantum_Stlkr_FogBatch_ARM9.jar`: **10073760 байт**, полная
  Stlkr, включает предыдущий Fog batching и новый Sprite batching.
  SHA-256 `9a40b13623e2c62e52966f532000e090bc20fcf174e0dc39488b87796bc34723`.
- Соответствующие JAD содержат правильные имена/размеры.
