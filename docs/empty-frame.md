# Empty Frame — где время пустого кадра

[JAR](../downloads/Quantum_EmptyFrame_Test.jar) ·
[JAD](../downloads/Quantum_EmptyFrame_Test.jad) ·
[Build report](../downloads/Quantum_EmptyFrame_Test.build-report.json).

**Отдельный standalone MIDlet ~10 КБ, без карты, текстур, VertexArray и вызовов
Graphics3D.render.** Не меняет игру, портал/BVH/деревню или старые JAR.
Цель — найти задержку, а не обещать устранение всех24мс.

Повод: на телефоне PortalMap_Bvh при ON и T0/DC0 показывает FPS31.6, N7/C0/I31,
J/B/K/R/E0/1/5/0/0; OFF в том же описанном ракурсе FPS21.6, N20/C0/I46,
T3761/DC15/VB7955. OFF не пуст по submissions. При ON значительная часть интервала
остаётся вне N/C. Этот тест не воспроизводит весь renderer, а разделяет минимальные
пути presentation, HUD, очистки и цикла запроса paint.

## Сначала четыре сравнения

На каждом состоянии дождаться numeric FPS и следующего обновления, примерно8сек.
Снять весь HUD; сохранять одинаковые размер экрана, FULL/MIN и прочие переключатели.

1. **1** → `0 CANVAS FILL`, фото (по умолчанию SYNC/FULL/S1).
2. **3** → `1 IMAGE COPY`, фото.
3. **5** → `5 M3G RGBZ+COPY`, фото.
4. В этом же режиме **9** → **S0** (вообще без Thread.sleep), фото. Снова **9**
   возвращаетS1. Это отдельная проверка: запрошенный `sleep(1)` на телефоне
   не обязан длиться ровно1мс. Пока его фактическую стоимость не измерили.

Далее при одном sleep/HUD: **\*** сравнивает SYNC/QUEUED, **7** — FULL/MIN.
При необходимости повторить sleep-тест на Canvas через **1**. Менять по одному
переключателю, иначе нельзя приписать выигрыш конкретной причине.

## Клавиши и режимы

**0** следующий режим, **2** предыдущий (с wrap); **1** Canvas, **3** Image,
**5** M3G RGB+Z; **\*** pump SYNC/QUEUED; **7** HUD FULL/MIN; **9** S1/S0;
**#** выход. Цифра перед названием — **номер режима, не клавиша прямого перехода**:
например, режим7 DIRECT выбирается через0/2, клавиша7 переключает HUD.

| Номер | Путь кадра |
|---|---|
| 0 | Canvas.fillRect, без M3G и Image |
| 1 | drawImage готового cached Image, без fill/M3G |
| 2 | Image.fillRect + drawImage |
| 3 | M3G bind/state/release на Image, depth attachment ON, **без clear**, затем copy |
| 4 | M3G RGB clear + copy, depth attachment ON, depth clear OFF |
| 5 | M3G RGB+Z clear + copy, depth attachment ON |
| 6 | M3G RGB clear + copy, **depth attachment OFF**, depth clear OFF |
| 7 | M3G RGB+Z прямо на Graphics Canvas, **без Image/copy**, optional support |
| 8 | Canvas IDLE: только HUD и маленький мигающий marker, без fullscreen fill |

Ошибки помечаются **UNSUPPORTED / FAILED**, не скрываются переходом на другой
renderer/target. При неподдерживаемом DIRECT нажать0/2 и продолжить остальные
режимы. Ошибка конкретного режима не вызывает повторной native попытки каждый
кадр. Это диагностическое отключение Z в пустой сцене, не правка игрового Z.

## Что означают цифры

Все времена — **wall time**, не GPU timestamps и не hardware counters.

- **I** — интервал между началами измеряемых paint callbacks.
- **P** — тело **предыдущего** paint, включая HUD и восстановление Graphics.
- **G** — от предыдущего measured end до следующего начала. Включает epilogue
  статистики/форматирование раз в3сек, возврат из paint, pump, sleep, display и
  scheduling. Это **НЕ чистое время ОС/ожидания**, и не доказательство CPU load.
- Для накопленных сумм **I = P + G точно**. Десятичные средние округлены вниз
  независимо, поэтому последние0.1мс могут не складываться.
- **F** — MIDP fill; **C** — drawImage; **H** — HUD + changing marker.
- **B** — bindTarget; **S** — viewport/camera/resetLights/background flags;
  **K** — **только clear**; **E** — releaseTarget. В старом viewer K включал иS.
- **Q** — request timestamp → начало paint, только для samples с известным
  pending request; `-` означает отсутствие таких samples, не нулевую задержку.
- **svc/sl(last)** — последние wall durations serviceRepaints и sleep.
  Это не оконные средние. **svc включает/перекрывает paint**, не прибавлять его
  к P/I/G; QUEUED не вызывает serviceRepaints. В S0 Thread.sleep не вызывается
  вообще, не заменяется на sleep(0)/yield.
- Строка I/P/G в FULL и MIN заканчивается `s1/s0/q1/q0`: s=SYNC, q=QUEUED,
  1=sleep(1), 0=без intentional sleep. FULL показывает конфигурацию и отдельной строкой.

Средние показываются с десятыми мс по окну3сек, но источник времени — грубый
System.currentTimeMillis: это **не** sub-ms разрешение часов, `0.0` не доказывает
бесплатность. FPS — частота paint callbacks, **не подтверждение реальных LCD flips**.

## Контроль опыта

Начальные состояния: режим0, FULL HUD, SYNC pump, S1. Каждое изменение режима,
pump/HUD/sleep, resize или resume запускает новый1сек warmup/3сек window. Первый
completed paint после warmup — только anchor. Незавершённый кадр старой
конфигурации не примешивается к новой. Один changing4×4 marker есть во всех
режимах, включая IDLE/MIN. MIN оставляет2 строки; FULL10 — HUD имеет свою цену.
IDLE — контроль минимальных операций, не обещание сохранности произвольного
Canvas backing store на всех реализациях.

Image и M3G helper создаются лениво один раз, не каждую перерисовку. Image
пересоздаётся только при изменении размеров. Нет map load, texture upload,
triangles, lights или игрового update. M3G camera/transform фиксированы; это
минимальный native transaction, а не полная копия старого prepare/J этапа.

SYNC и QUEUED используют один и тот же механизм одного pending запроса, с
request tickets против stale completion после pause/resume. Monitor не удерживается
во время repaint/serviceRepaints. QUEUED ждёт завершения paint, **не засыпает
потоком repaint requests**. S1/S0 переключается независимо для обоих pump.
S0 может увеличить CPU/батарейную нагрузку: использовать для коротких замеров,
не оставлять тест надолго. Возврат кS1 и исходному состоянию проверяет дрейф.

## Проверки / сборка

```sh
# Java/ECJ/SDK/ProGuard — как у остальных diagnostic builders.
python scripts/build_empty_frame.py --output-dir downloads
python tests/empty_frame/run.py
python tests/empty_frame/run.py --packaged
python -m unittest tests/resources/test_empty_frame.py
```

Исходники и packaged CLDC: по **575 checks** с API doubles. Проверены exact
previous-frame I/P/G/operations pairing, fractional means, unknown queue samples,
warmup/clock rollback,9 точных путей/clear/depth/copy, lazy allocation/resize,
FULL/MIN, ошибки/release/unsupported-direct **без fallback**, config transition,
stale request ticket, реальные две Java threads для SYNC/QUEUED × S1/S0,
pause/hide/resume/close без deadlock/duplicate worker. Это **не native rasterizer,
не замеры E65 и не обещание скорости**. Повторный JAR/JAD/report побайтно совпал.
Аудит: ровно4 CLDC47 класса/21 StackMap attributes, без game classes/assets.

JAR10092B, SHA256
`20fff8c2a18a10cf4e597c34ca421d8ac568dc615b9eb00090c2a30b7c2a09c6`.
Телефонного результата новой сборки пока нет.
