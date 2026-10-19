# M3G perspective correction: контролируемый ON/OFF на карте

Отдельный MIDlet: [JAR](../downloads/Quantum_StaticRoom_Perspective.jar),
[JAD](../downloads/Quantum_StaticRoom_Perspective.jad),
[отчёт и SHA-256](../downloads/Quantum_StaticRoom_Perspective.build-report.json).
Основная игра, Tex3, PolyFill, SoftwarePolyFill и остальные опубликованные сборки
не заменены. Это просмотрщик реальной карты, не вся игровая логика и не новый
синтетический PolyFill.

## Что проверяем

На E65 пользователь получил заметный выигрыш Software P0 против P4.
Это повод проверить M3G, но не основание обещать такой же выигрыш: другой тракт
рендеринга. В M3G меняется **только запрос**
`PolygonMode.setPerspectiveCorrectionEnable(false/true)` на resident Appearance.
**OFF может быть проигнорирован реализацией.** Заголовок показывает запрос API,
а не доказанное переключение аппаратного интерполятора в affine. ON учитывает
исходный `material.perspectiveCorrection`, не принуждает отдельные материалы.
Перспективная камера при этом остаётся перспективной; это не orthographic mode
и не переключатель P0/P4 CPU-растеризатора.

Если реализация действительно использует affine UV, возможны «плавание» текстур
при повороте, перекосы на длинных поверхностях и заметные диагонали квадов.
Сравниваем одновременно скорость и качество. Даже одинаковая картинка и FPS
сами по себе не доказывают, почему эффекта нет — игнорирование hint или отсутствие
существенной стоимости коррекции.

## Замер на телефоне

1. Запуск **PERS ON**. Не двигаться. Дождаться числового FPS и ещё одного
   обновления окна (обычно около8секунд); фото всего экрана/HUD.
2. **0 → PERS OFF**, камера сохраняется, движение останавливается. Снова ждать
   прогрева/обновления, второе фото. Смотреть также на дорогу и стены.
3. **0 → PERS ON**, контрольный повтор в той же позе.

**1** всегда ON, **3** всегда OFF, **5** исходная камера/yaw0;
**4/6** поворот, **2/8** движение, **7/9** вбок, **#** выход.
Можно выбрать другой ракурс, но между сравниваемыми режимами не двигать камеру.
Переключение сбрасывает статистику, не сбрасывает положение/разрешение/цель.
При ошибке явный экран `Failed`, без тихого fallback: выйти и перезапустить.

### HUD

- `FPS`: десятые FPS, окно≥3секунд после1секунды прогрева и первого anchor-кадра.
- `N`: wall time всей native render-транзакции; `C`: копирование Image на Canvas;
  `I`: интервал кадров.
- `J/B/K/R/E`: подготовка/видимость, bind, камера/state/clear, цикл render, release.
- `T/DC/VB`: поданные треугольники, draw calls, вместимость поданных vertex buffers.
  `S/V/CUT`: выбранные углы граней, проверки ячеек, отвергнутые ячейки.

В неподвижной позе T/DC/VB/S/V/CUT должны совпадать между режимами.
N и J/B/K/R/E — время Java API с возможной синхронизацией, **не GPU timestamps**.
VB не равно количеству фактически обработанных железом вершин. Фазы реки
продолжают меняться по штатной анимации, как в исходном просмотрщике.

## Неизменные условия

Контроль — legacy fixed GRID4×4, а не переключение60000/40000 из Chunks.
За основу взят тот же native mesh с stage-таймерами; новые chunk grids удалены.
Near128, far60000, FOV74, тот же полный Canvas/Image viewport и порядок граней.
Сохраняются исходные большие буферы, XYZ/UV/baked RGBA, drawmode9, alpha thresholds,
depth, blending, текстуры и4 заранее загруженных кадра реки/speed6/mode7.

Геометрия/UV/цвета/full indices строятся при загрузке. Видимость использует
прежние полные AABB ячеек. Только **новая** маска может построить index buffer,
есть прежний двухэлементный кэш. В одной позе ON→OFF→ON не создаёт native geometry/
index buffers и не вызывает Java API upload атрибутов или текстур. Неизменный
флаг повторно не устанавливается на Appearance каждый кадр. Внутренние действия
драйвера этим не измеряются.

## Воспроизведение и проверки

Нужны ECJ4.8, Java, CLDC/MIDP/JSR184 API и ProGuard из обычного toolchain репозитория.
Как в соседних harness, `build/cldc/api.jar` — объединение CLDC/MIDP/JSR135/
JSR184/JSR75_file классов SDK; подмодули Room/StringTools должны быть доступны.

```sh
python scripts/build_static_room_perspective.py --output-dir downloads
python tests/static_room_perspective/run.py
python tests/static_room_perspective/run.py --packaged
python -m unittest tests/resources/test_static_room_perspective.py
python scripts/verify_render_checkpoints.py
```

Для harness при отсутствии javac задать `JAVA` и `ECJ_JAR`, как в соседних тестах.
Проверены source и **готовый CLDC JAR**: по40974 assertions/864 camera/river cases.
ON сравнивается с frozen Tex3 GRID; OFF отличается только perspective flag;
ON→OFF→ON сохраняет exact packed атрибуты, indices/order, state, camera/texture,
счётчики. Проверены отсутствие hot uploads/создания indices при смене режима,
no-op повторного значения, исходный запрет материала, отпускание target при
ошибках, реальное UI→renderer переключение, заморозка ввода/позы, hide,
прогрев/таймеры/откат часов. Doubles **не растеризуют M3G и не измеряют E65**.

Детерминированные JAR/JAD/report побайтно воспроизводятся;190 CLDC47.0 classes,
все182 исходных класса и assets Tex3 сохранены побайтно, добавлены8 классов.
Resource-аудиты StaticRoom/PolyFill/Software/L2D:26 PASS;7 rollback-checkpoints PASS.
Полный resource discovery:39 PASS и1 ошибка из-за отсутствующего старого fixture
`Stlkr by ebk.jar (1).zip` — не связана с этим тестом, не скрыта и не заменена
другим архивом. Телефонных замеров ON/OFF пока нет.
