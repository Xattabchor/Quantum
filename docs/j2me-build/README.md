# Сборка J2ME без NetBeans и без WTK

## Готовый JAR (лежит в репозитории)

Собранный и препроверенный MIDlet сохранён в `artifacts/`, чтобы его можно было
скачать прямо из GitHub, не собирая проект:

* файл в дереве: <https://github.com/Xattabchor/Quantum/blob/arena/50252a64-quantum/artifacts/QuantumOptimize.jar>
* прямая загрузка: <https://github.com/Xattabchor/Quantum/raw/arena/50252a64-quantum/artifacts/QuantumOptimize.jar>
* JAD: <https://github.com/Xattabchor/Quantum/raw/arena/50252a64-quantum/artifacts/QuantumOptimize.jad>

Имя приложения в меню телефона - «Quantum Optimize» (`MIDlet-Name` и подпись
`MIDlet-1` переписывает скрипт сборки); имена файлов без пробела -
`QuantumOptimize.jar`/`.jad`, чтобы ссылки и OTA-установка не требовали
экранирования.

После слияния ветки в `master` те же ссылки работают с `master` вместо имени
ветки. Рядом с JAR нужен `.jad` только для установки по HTTP; при установке
файла напрямую телефон читает манифест из самого JAR. Игровые ресурсы
(`images/`, `levels/`, `languages/`, `setting.txt`) в репозиторий не входят:
`res/` содержит только `README.md`, поэтому JAR собирается и препроверяется, но
запускается лишь с оригинальными данными игры.

## Конвертер текстур PNG -> QCT

Отдельный MIDlet `QctConverter.jar` (не входит в JAR игры) пакует текстуры в
формат QCT прямо на аппарате или в эмуляторе: браузер карты памяти (JSR-75),
отметка файлов галочками, пакетная конвертация с прогрессом и журналом,
настройки палитры (2..64 цвета) и самопроверка. Формат и энкодер описаны в
`docs/ARM9.md` §10; движок подхватывает `.qct`, лежащие в JAR рядом с `.png`
(ключ `QCT=1` по умолчанию).

```sh
T=$T SRC=$SRC JAVA=$JAVA sh build-qctconv.sh   # dist/QctConverter.jar + .jad
```

## Сборка с нуля

Два скрипта повторяют сборку NetBeans (компиляция -> препроверка -> JAR/JAD),
но работают на обычной JRE: ни `javac`, ни `jar`, ни `preverify` из Wireless
Toolkit не нужны.

```sh
export T=~/tools/j2me            # куда положить инструменты
export JAVA=java                 # путь к java (достаточно JRE)
export SRC=$(cd ../.. && pwd)    # корень репозитория

sh get-toolchain.sh                       # ECJ, ProGuard, банки CLDC/MIDP/JSR
T=$T SRC=$SRC JAVA=$JAVA sh build-j2me.sh  # сборка
```

Если в системе вообще нет Java, её ставит pip-пакет `jdk4py` (он же даёт `java`):

```sh
python3 -m venv ~/.venv && ~/.venv/bin/pip install jdk4py
export JAVA=~/.venv/lib/python3.11/site-packages/jdk4py/java-runtime/bin/java
```

`get-toolchain.sh` качает банки через GitHub API: файлы меньше 1 МиБ приходят
base64-содержимым в ответе contents API, большие (ProGuard 10 МБ, ECJ 1.5 МБ) -
через blob API с `Accept: application/vnd.github.raw`. Запасной путь - тарбол
репозитория. Вся загрузка занимает несколько секунд.

Результат:

| путь | содержимое |
|------|-----------|
| `build/<config>/compiled/` | классы после ECJ (class file version 48) |
| `build/<config>/preverified/` | классы после препроверки (атрибуты `StackMap`) |
| `dist/QuantumOptimize.jar`, `dist/QuantumOptimize.jad` | готовый MIDlet; атрибуты манифеста берутся из `nbproject/project.properties` |

`build/` и `dist/` уже в `.gitignore`; из `artifacts/` в репозиторий попадают
только `QuantumOptimize.jar` и `QuantumOptimize.jad` (ZIP с промежуточными классами игнорируется).
Скрипт сам проверяет собранный JAR:
манифест первой записью, все классы версии 48, нет атрибутов `StackMapTable`
(их KVM не понимает), класс MIDlet на месте, размер в JAD совпадает.
Более глубокую проверку делает `check-jar.py`: он разбирает class-файлы и
докладывает число методов с `StackMap`, отсутствующие классы вне CLDC/MIDP/JSR
(сверка с банками из `--lib`) и согласованность манифеста с JAD:

```sh
python3 check-jar.py ../../dist/QuantumOptimize.jar \
    --lib $T/lib/cldc_1.1.jar --lib $T/lib/midp_2.0.jar \
    --lib $T/lib/jsr184_1.1.jar --lib $T/lib/jsr075_stub.jar
```

Препроверка настоящая, а не номинальная: в собранном JAR 732 атрибута
`StackMap` на 116 классов и 0 атрибутов `StackMapTable`. ProGuard работает со
shrink, оптимизация байткода выключена `-dontoptimize` (рефлексии в коде нет,
MIDlet-класс закреплён `-keep`, обфускация выключена): верификатор IBM J9 на
реальных Nokia S60 (E65) отвергает оптимизированный ProGuard байткод —
MIDlet вылетает сразу при запуске, подробности в `docs/ARM9.md` §23. Размер
JAR 423 575 -> 364 920 байт (после shrink и
вырезанных подсистем - звука ходьбы, снега, вида от третьего лица). Сама заглушка JSR-75 в
JAR не попадает (проверка: в архиве нет записей `javax/`).

Важно про ресурсы: в этом репозитории каталог `res/` пуст, поэтому JAR содержит
только код. Перед установкой положите в `res/` файлы оригинального дистрибутива
(`images/`, `levels/`, `languages/`, `setting.txt`) и пересоберите — скрипт
кладёт содержимое `res/` в корень JAR так же, как сборка NetBeans.

`jsr075_stub.jar`, который собирает `get-toolchain.sh`, нужен только
компилятору (в платформенных банках нет JSR-75, а `LightMapper.java` его
импортирует). В JAR заглушка не попадает, на устройстве работает настоящая
реализация JSR-75, формат `lightmap.vla` не меняется.

Подробности и обоснования — в `docs/ARM9.md`, раздел 8.

Готовые артефакты текущей сборки лежат в `artifacts/` (тоже в `.gitignore`):
`QuantumOptimize.jar`, `QuantumOptimize.jad` и `preverified-classes.zip` — каталог
`build/<config>/preverified/` одним файлом.
