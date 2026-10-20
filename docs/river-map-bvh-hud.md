# Карта с рекой: BVH/GRID + HUD FULL / MIN / OFF

Отдельный MIDlet: [JAR](../downloads/Quantum_RiverMap_BvhHud.jar) ·
[JAD](../downloads/Quantum_RiverMap_BvhHud.jad) ·
[отчёт](../downloads/Quantum_RiverMap_BvhHud.build-report.json).

Эта сборка сделана после GRID-версии `Quantum_RiverMap_Hud.jar`, где по
наблюдению пользователя MIN и FULL дали почти одинаковый FPS. Здесь та же
старая деревня/карта с рекой (`maps/kordos/kordos.3d`, запечённый
`lightmap/kordod.vla`, Tex3-материалы forest / `map-selo1.png` / река), но
просмотрщик использует прежний `StaticRoomBvhMesh` из родительского JAR.
Это **не Portal BVH и не BSP**: BVH здесь — frustum-отбор по полным AABB граней
одной комнаты, лист до 8 граней, без occlusion culling и без перестановки
исходного порядка strip. Геометрия, UV, свет, текстуры, река, near128,
far60000, FOV74, DMODE9, alpha/depth и перспективная коррекция ON не меняются.
Родительские классы и assets сохранены побайтно, кроме manifest; добавлены
только два класса нового просмотрщика.

## Клавиши

| Клавиша | Действие |
|---|---|
| **7** | **BVH ON** — отбор граней деревом AABB |
| **9** | **GRID** — тот же mesh с `setBvhEnabled(false)`, прежняя сетка 4×4 |
| **1** | HUD **FULL**: панель 90px, 6 строк каждый кадр |
| **3** | HUD **MIN**: панель 30px, заголовок + `N/C/I` |
| **0** | HUD **OFF**: после копии карты нет панели и текста, статистика собирается |
| **`*`** в OFF | остановить замер и показать последний полный результат |
| **`*`** повторно | продолжить OFF-замер с новым прогревом |
| **5** | home/START; выбранные BVH/GRID и HUD сохраняются, статистика сбрасывается |
| **2/8**, **4/6** | движение и поворот; стрейфа в этой сборке нет, 7/9 заняты BVH/GRID |
| **#** | выход |

По умолчанию: **BVH ON + FULL HUD**. Переключение BVH/GRID или HUD сохраняет
позу и поверхность, останавливает движение и заново прогревает окно
(1с + окно не короче 3с). Клавиши отбора сохраняют текущий HUD; клавиши HUD
сохраняют текущий BVH/GRID. Если в заголовке появилось `MEM`, сработал
whole-room fallback при нехватке памяти под индексную маску: сравнение
BVH/GRID в этом запуске уже недействительно.

## Как снимать

Сначала BVH-серия в одной неподвижной позе:

1. Запуск **BVH FULL** — подождать около 8 секунд, фото.
2. **3 → BVH MIN** — подождать, фото.
3. **0 → BVH OFF** — подождать 8–10 секунд, нажать **`*`**, фото результата.
4. **1 → BVH FULL** — контрольный повтор.

Если нужен GRID-контроль в той же позе: нажать **1** (FULL), затем **9** (GRID),
подождать и снять; дальше по необходимости **3** и **0 + `*`**. Для возврата
к BVH — **7**. Между сравниваемыми режимами не двигаться; если поза изменилась,
нажать **5** и начать серию заново.

На фото нужны заголовок режима, FPS, `N/C/I`, `T/DC/S`, `V/CUT/U`.
В стартовой desktop-позе BVH должен подавать заметно меньше `T`, чем GRID;
это проверка отбора, а не обещание телефонного FPS. Ранее на этой карте BVH уже
сокращал T без прироста FPS у пользователя (A BVH T1218 против B GRID T2864,
FPS8 в обоих), поэтому здесь BVH включён именно как условие для повторной
проверки HUD при меньшей подаче геометрии, а не как доказанное ускорение.

## Счётчики

- `FPS` — десятые FPS оконного wall-clock счётчика, не доказанная частота LCD.
- `N` — вся native render-транзакция в Image, включая Java-подготовку/отбор и
  release; `C` — `drawImage` на Canvas; `I` — интервал начала кадров.
- `T/DC/S` — поданные треугольники, API draw calls и поданные углы.
- `V` — AABB-проверки: узлы BVH или ячейки GRID. `CUT` — отвергнутые узлы/ячейки.
- `U` — суммарно с загрузки построенные новые selection-index buffers. В
  неподвижной позе после прогрева U не должен расти; движение и смена масок
  могут его увеличивать. Вершины/текстуры при этом не перегружаются.

Pump прежний: worker `repaint()+serviceRepaints()+sleep(1)` во всех режимах.
Это не FramePump SELF/S0 и не GameCanvas flush. Таймеры — Java API wall time,
не GPU timestamps.

## Сборка и проверки

```sh
JAVA=/path/to/java ECJ_JAR=/path/to/ecj.jar \
  python scripts/build_river_map_bvh_hud.py --output-dir downloads
JAVA=/path/to/java ECJ_JAR=/path/to/ecj.jar python tests/river_map_bvh_hud/run.py
JAVA=/path/to/java ECJ_JAR=/path/to/ecj.jar python tests/river_map_bvh_hud/run.py --packaged
python -m unittest tests/resources/test_river_map_bvh_hud.py
python scripts/verify_render_checkpoints.py
```

Проверены исходники и готовый CLDC JAR: по **2304 checks / 144 poses/river
frames**. Контракты подтверждают 4750 triangles / 10032 native corners, три
пакета, четыре кадра реки, BVH `T <= GRID T` в каждой проверенной позе и
BVH `T < GRID T` дома, отсутствие stationary rebuild индексов внутри режима,
FULL=6 строк, MIN=2 строки, OFF=0 строк/панелей, замороженный результат без
native render, переключения 7/9 и 1/3/0 без сдвига позы/поверхности, release
target при native API ошибках. Desktop doubles не растеризуют M3G и не
измеряют телефонный FPS.

Parent SHA256 `652626cddcc8b991a4c9ba76ac810cd1d163f7041f393ff7e31fd8f44f3dcb57`.
JAR 819268 B, SHA256
`c68e5121de0f8d981e2da2cf5d008cf6a962331513cc7ee62e16e94feb42c07c`.
`handset_tested=false`: телефонных замеров именно этой сборки при публикации нет.
