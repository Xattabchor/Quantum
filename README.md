# Quantum  
J2ME 3D Game engine based on Iceberg 3D.  

Uses software rendering and zone/portal-based culling. Frames are presented through Nokia DirectGraphics when available, with a MIDP fallback.
Supports some kind of scripting (should be replaced with lua!), has its own lightmapper.  
I wrote it when I was just learning Java and OOP, so the code quality is really low, but maybe this engine can be useful to someone.  

## Nokia DirectGraphics

Вывод кадра и пиксельных фонов меню использует Nokia `DirectGraphics.drawPixels`.
Форматы оригинальных ресурсов и программное текстурирование сохранены.
[Сборка, ограничения API и тесты](docs/nokia-directgraphics.md).

## M3G / JSR-184

Добавлен экспериментальный второй рендер: **Settings → Renderer → M3G**.
По умолчанию работает Software. M3G растеризует поддерживаемые полигоны
и обрабатывает туман материалов 1/3/6. Остальные неподдерживаемые материалы
и спрайты сохраняют программный путь.
Форматы ресурсов и игровая логика не менялись.
[Включение, совместимость, ограничения и тесты](docs/m3g.md).
