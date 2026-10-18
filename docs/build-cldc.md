# Сборка Quantum: оригинальный Software и M3G

**Текущая ветка:** [обязательный M3G-путь Scene, этап 3](m3g-required-path.md).
Software не выбирается для gameplay; legacy API и его классы остаются до
следующих этапов. Публикуемые downloads пока относятся к двухрендерной b0ee49b.

Сохранённый legacy Software API использует исходный Graphics.drawRGB.
GameScreen выводит успешный M3G Image через drawImage; pause snapshot и
необходимые CPU/resource переходы сохраняют readback. Nokia DirectGraphics,
адаптер и зависимость от Nokia UI API не возвращаются.

Форматы ресурсов и игровая логика не заменялись. Compile/preverify сами по себе
не доказывают отсутствие всех прямых pixel writes или готовность полного
M3G-only приложения. Для оценки текущей совместимости нужны required-path
fixtures и запуск на устройстве.

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
