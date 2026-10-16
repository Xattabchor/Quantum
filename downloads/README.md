# Quantum — M3G preverified preview

CLDC 1.1 / MIDP 2.0. Preverified with ProGuard 6.2.2 -microedition.

Второй рендер: Настройки → Renderer → M3G (experimental).
Туман материалов 1/3/6 перенесён в M3G (линейный, чёрный дистанционный и аддитивный).
По умолчанию Software с оригинальным MIDP Graphics.drawRGB; DirectGraphics удалён.
M3G и его туман сохранены; неподдерживаемые материалы используют программный путь.

Сборка движка без оригинальных игровых ресурсов: без них игра не запустится.
На устройстве не тестировалась. Параметры и SHA-256 — в build-report.json.
Подробности: [M3G](../docs/m3g.md).
