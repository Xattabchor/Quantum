# Резерв рендера

Продолжаем новый world-space режим в `experiments/static-room/`. Основной
projected painter в `src/` и его опубликованный JAR пока не заменяем.
Рабочая ветка остаётся `arena/b40c445d-quantum`.

## Вернуться к игре

Точная игровая сборка до эксперимента:
https://github.com/Xattabchor/Quantum/raw/73d43ee42dd9d116ad2b3780deff900bbb12d8cd/downloads/Quantum_Sym9_DrawCalls.jar

## Вернуться к cached merged v2 (до отсечения)

https://github.com/Xattabchor/Quantum/raw/98e00e7125e047cedb7ee5e9cfd17ef1da38225f/downloads/Quantum_StaticRoom_Merged.jar

Исходники и артефакты закреплены commit `98e00e7125e047cedb7ee5e9cfd17ef1da38225f`.
Пользователь подтвердил правильную картинку cached и сообщил 7 FPS против 5
projected. Модель телефона и метод измерения не уточнены; точный установленный
artifact не проверялся независимо. Дальнейшая работа с отсечением идёт отдельным
`Quantum_StaticRoom_Cull.jar`, не перезаписывая эту контрольную точку.

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


## Принятый Cull и его Lite-упаковка перед Presentation A/B

- Cull `fcb51419fdfdf08a773cdc5f047f800bd96ca9e5`:
  [JAR](https://github.com/Xattabchor/Quantum/raw/fcb51419fdfdf08a773cdc5f047f800bd96ca9e5/downloads/Quantum_StaticRoom_Cull.jar).
- Cull Lite `cd61dc98c45133229b13e898ceb13f46077e0c39`:
  [JAR](https://github.com/Xattabchor/Quantum/raw/cd61dc98c45133229b13e898ceb13f46077e0c39/downloads/Quantum_StaticRoom_Cull_Lite.jar).

Их JAR/JAD/reports добавлены в read-only checkpoint verifier вместе с прежними
stable/v1/v2. Пользователь сообщил для Cull 8–9 FPS и отсутствие пропадания
видимых поверхностей; модель/метод не уточнены, Lite/Present этим не измерены.
[Новый Presentation A/B](static-room-presentation.md) — отдельные имя и файлы,
обе стороны используют тот же culled renderer, переключается только target.


## Presentation baseline перед native strip-layout экспериментом

Present `0ec8560d908d9ff2bce14a589f98a462bd9767ab`:
[JAR](https://github.com/Xattabchor/Quantum/raw/0ec8560d908d9ff2bce14a589f98a462bd9767ab/downloads/Quantum_StaticRoom_Present.jar).
Его JAR/JAD/report добавлены в checkpoint verifier как шестая контрольная точка.
Пользовательские N112/C0/F112–113/I140 в A/B сохранены отдельно от независимых
измерений (устройство не уточнено). [Strips](static-room-strips.md) имеет свои
имя и артефакты, не заменяет Present или принятый Cull/Lite.
