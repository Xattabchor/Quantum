# Резерв рендера

Продолжаем новый world-space режим в `experiments/static-room/`. Основной
projected painter в `src/` и его опубликованный JAR пока не заменяем.
Рабочая ветка остаётся `arena/b40c445d-quantum`.

## Вернуться к игре

Точная игровая сборка до эксперимента:
https://github.com/Xattabchor/Quantum/raw/73d43ee42dd9d116ad2b3780deff900bbb12d8cd/downloads/Quantum_Sym9_DrawCalls.jar

## Вернуться к первой версии нового режима

Тестовый JAR:
https://github.com/Xattabchor/Quantum/raw/ac57d8946257b44b349a85509b9b7b56def852c9/downloads/Quantum_StaticRoom_Test.jar

Исходники эксперимента, его сборщик и тесты целиком сохранены в commit
`ac57d8946257b44b349a85509b9b7b56def852c9`. Исходники v1 уже используются
тестами v2 как независимый reference. Эти версии не требуют архивов q92/Stlkr.

Проверка сохранности всех JAR/JAD/report контрольных точек:

```sh
python scripts/verify_render_checkpoints.py
```

Команда не пишет файлы, не откатывает изменения и не обращается к сети.
Для исходников восстанавливать нужные файлы из указанного commit на текущей
ветке, после сохранения более новых изменений; не сбрасывать историю ветки.
Будущие выпуски нового режима публиковать отдельными именами и не перезаписывать
файлы из `configs/render-checkpoints.json`.
