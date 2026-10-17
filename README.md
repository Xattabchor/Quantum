# Quantum  
J2ME 3D Game engine based on Iceberg 3D.  

Uses software rendering and zone/portal-based culling. Frames are presented through the original MIDP Graphics.drawRGB path.
Supports some kind of scripting (should be replaced with lua!), has its own lightmapper.  
I wrote it when I was just learning Java and OOP, so the code quality is really low, but maybe this engine can be useful to someone.  

## Оригинальный программный рендер

Для Software восстановлен исходный вывод через MIDP `Graphics.drawRGB`,
включая фон паузы и меню. Nokia DirectGraphics и зависимость от Nokia UI API
удалены; второй рендер M3G сохранён.
[Сборка и проверки](docs/build-cldc.md).

## M3G / JSR-184

Добавлен экспериментальный второй рендер: **Settings → Renderer → M3G**.
По умолчанию работает Software. M3G растеризует поддерживаемые полигоны
и обрабатывает туман материалов 1/3/6. Спрайты сцены (обычные, ADD и alpha)
тоже рисуются M3G-квадами, с анимацией, зеркалированием и туманом.
Нестандартный MUL и неподдерживаемые материалы сохраняют программный путь.
Панорамное 2D-небо тоже рисуется M3G-квадом с исходным разрешением и кэшем текстуры;
при превышении аппаратных лимитов остаётся CPU fallback.
Небо и мир объединены в один M3G-проход без промежуточного sky readback/upload
на прогретом кэше полностью native-сцены.
После выбора M3G доступен **HW perspective: ON / OFF** — аппаратная
перспективная интерполяция (по умолчанию ON). Выбор сохраняется отдельно,
применяется после паузы и не меняет PERSQ или Software.
Для NPC и остальных 3D-мешей M3G включён аппаратный backface culling:
`CULL_BACK` с правильным winding; исходное CPU-отсечение сохранено.
Небо, спрайты и экранные эффекты этим куллингом не затрагиваются.
Соседние совместимые полигоны мира и мешей собираются в пакеты до 64 треугольников:
меньше draw-вызовов и native-загрузок вершин, без сортировки по текстурам
и изменения painter-order. Legacy multi-pass эффекты сохраняют отдельные проходы.
Форматы ресурсов и игровая логика не менялись.
[Включение, совместимость, ограничения и тесты](docs/m3g.md).
