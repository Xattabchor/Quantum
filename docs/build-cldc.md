# Сборка Quantum: оригинальный Software и M3G

Программный рендер использует оригинальный `Graphics.drawRGB` для вывода кадра,
фонов паузы и меню. Автоматический выбор Nokia DirectGraphics удалён вместе с
адаптером и зависимостью от Nokia UI API. Это касается и вывода результата M3G:
его readback, как и прежде, попадает в общий `DirectX7.display`, затем в MIDP.

Сам программный растеризатор, форматы ресурсов и игровая логика не заменялись.
Сохранены `flushAlpha`, исходные RGB/ARGB-буферы, параметры clipping/translation
и отладочная карта перерисовки. Переключатель Software/M3G и M3G-туман сохранены.

## NetBeans / Java ME SDK

Нужны исходные инструменты проекта: NetBeans Mobility/Java ME SDK, CLDC 1.1,
MIDP 2.0 (или 2.1), MMAPI, JSR-184, JSR-75 и библиотеки из подмодулей:

```sh
git submodule update --init --recursive
```

Настройте установленную Java ME platform в NetBeans и добавьте оригинальные
ресурсы в `res/` с исходными путями. `nokiaui.jar` больше не нужен.
SDK-классы и тестовые заглушки нельзя включать в MIDlet.

## Автономная сборка preverified JAR

Без NetBeans можно использовать Python 3.8+ и скрипт:

```sh
python3 scripts/build_cldc.py \
  --java /path/to/java \
  --ecj-jar /path/to/ecj.jar \
  --api-dir /path/to/j2me/lib \
  --proguard-jar /path/to/proguard.jar
```

Использованный ECJ 3.16.0 находится в npm-пакете `coc-java@1.0.0`
(`server/plugins/org.eclipse.jdt.core_3.16.0.v20181004-1305.jar`).
API jar и ProGuard 6.2.2 взяты из
[выкладки Java ME build tools](https://github.com/vipaoL/j2me-build-tools).
Их нужно получить отдельно; скрипт ничего не скачивает.
В `--api-dir` ожидаются `cldc11.jar`, `midp21.jar`,
`jsr135.jar`, `jsr184.jar`, `jsr75_file.jar`.

Скрипт компилирует все исходники и подмодули с `-source 1.3 -target 1.3`
против Java ME API, запускает ProGuard с `-microedition` **без** обфускации,
оптимизации и удаления классов, затем проверяет версию class-файлов,
наличие CLDC `StackMap` и отсутствие Java SE `StackMapTable`.
Проверка структуры не заменяет тестирование на телефоне.

Результаты в `artifacts/`: `Quantum.jar`, `Quantum.jad`, `build-report.json`.
Промежуточные файлы и логи — в `build/cldc/`. Эти каталоги не добавляются в Git.
`res/README.md` не включается; остальные файлы из `res/` упаковываются
с исходными путями. Если ресурсов нет, JAR всё равно собирается, но отчёт
явно указывает `resource_files: 0`. JAD указывает размер готового JAR,
а отчёт содержит SHA-256 и результаты структурной проверки.

## Проверки

После сборки на desktop JDK с Python 3:

```sh
python3 tests/framebuffer/run.py
python3 tests/m3g/run.py
```

Поддерживаются `JAVA`/`JAVAC` или `JAVA`/`ECJ_JAR`. Первый набор проверяет
настоящий `DirectX7.flush`: прямой MIDP-вызов, RGB/ARGB, параметры, идентичность
массива, translation/clipping в модели Graphics, resize и отладочные цвета.
Также проверяется отсутствие Nokia-адаптеров в исходниках/JAR и Nokia API в
classpath сборки. M3G-тесты проверяют второй backend и его туман отдельно.
Это контрактные тесты со заглушками API, не проверка изображения на телефоне.

Полная компиляция против Java ME API и ProGuard preverify выполнены без Nokia UI
API. Визуальная проверка и FPS требуют оригинальных ресурсов и устройства либо
эмулятора. Игровых файлов в checkout пока нет, поэтому собранный JAR — движок
без ресурсов, а не готовая игра.
