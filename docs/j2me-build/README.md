# Сборка J2ME без NetBeans и без WTK

Два скрипта повторяют сборку NetBeans (компиляция -> препроверка -> JAR/JAD),
но работают на обычной JRE: ни `javac`, ни `jar`, ни `preverify` из Wireless
Toolkit не нужны.

```sh
export T=~/tools/j2me            # куда положить инструменты
export JAVA=java                 # путь к java (можно JRE)

sh get-toolchain.sh              # скачать ECJ, ProGuard, банки CLDC/MIDP/JSR
T=$T SRC=$(cd ../.. && pwd) JAVA=$JAVA sh build-j2me.sh
```

Результат: `dist/Quantum.jar` и `dist/Quantum.jad` (имена и атрибуты манифеста
берутся из `nbproject/project.properties`).

Важно про ресурсы: в этом репозитории каталог `res/` пуст, поэтому JAR содержит
только код. Перед установкой положите в `res/` файлы оригинального дистрибутива
(`images/`, `levels/`, `languages/`, `setting.txt`) и пересоберите — скрипт
кладёт содержимое `res/` в корень JAR так же, как сборка NetBeans.

`jsr075_stub.jar`, который собирает `get-toolchain.sh`, нужен только
компилятору (в платформенных банках нет JSR-75, а `LightMapper.java` его
импортирует). В JAR заглушка не попадает, на устройстве работает настоящая
реализация JSR-75, формат `lightmap.vla` не меняется.

Подробности и обоснования — в `docs/ARM9.md`, раздел 8.
