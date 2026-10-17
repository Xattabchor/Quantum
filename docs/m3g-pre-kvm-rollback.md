# До KVM + последняя правка Image presentation

Дата: 18.10.2026. По запросу пользователя отменены CPU-оптимизации,
добавленные после KVM-исследования. Сохранена только последующая правка
вывода готового M3G Image через drawImage.

## Точная база

`c981fce0` — Remove fake additive weapon illumination while preserving mode 5 OMNI,
последняя версия игрового кода перед KVM-исследованием `04d67f5`.
Полный SHA исходной базы можно получить через `git rev-parse c981fce`.

Производственные файлы побайтно восстановлены из этой базы:

- `src/code/Rendering/FrameEffects.java`: прежний арифметический CPU recovery,
  без добавленной tint-LUT и cpuTintUnavailable;
- `src/code/Rendering/Mode5WeaponLight.java`: прежние OMNI pack/shade,
  без новых кэшей normal и изменённой метрической упаковки;
- `src/code/Rendering/M3GMode5Lighting.java`: прежняя упаковка native-нормалей,
  без добавленного scalar normal cache;
- `src/code/Rendering/M3GPolygonBatch.java`: прежний private append с упорядоченным
  циклом arraycopy, без extracted appendVertices и triangle/quad bulk fast paths.

В `DirectX7.mode5Shade` восстановлен исходный расчёт без новых shade-кэшей.
Остальные изменения DirectX7 оставлены только для Image presentation.

Проверка `git diff c981fce --name-only -- src` показывает ровно четыре файла:

```
src/code/Gameplay/GameScreen.java
src/code/Rendering/DirectX7.java
src/code/Rendering/M3GSceneRenderer.java
src/code/Rendering/SceneRenderer.java
```

Это и есть [последняя правка Image presentation](m3g-image-presentation.md):
finishImage/releaseTarget, прямой drawImage, синхронизация CPU/паузы,
сохранение partial background и полного CPU rollback. Других изменений игрового
кода относительно базы до KVM нет. Старые материалы, native OMNI 120 мс,
погода, painter order, отсутствие fake additive flash и оригинальный Software сохранены.

## Тестовая инфраструктура

Сравнения с исходными формулами сохранены: CpuOptimizationTest теперь проверяет
арифметическую реализацию, а NativePrepTest вызывает исходный private append
через reflection только в desktop-тесте. Никаких test hooks в production не добавлено.

Удалён KvmNativePrep, зависевший от отменённого static appendVertices. Базовые
KvmPerformance/runner восстановлены из исходного KVM-исследования `04d67f5`.
Это инструменты вне JAR, не внедрение KVM в движок.

Старые benchmark JSON и отчёты сохранены как архив с отметкой об отмене.
Их timings не относятся к текущему JAR; повторного KVM/FPS замера не выполнялось.

## Результат проверок

- M3G suite: 137 групп PASS, в том числе оригинальные формулы CPU/OMNI,
  14000 вариантов native-нормалей и 12000 потоков вершин, Image presentation,
  mixed CPU/native, failures/readback/OOM rollback и удалённый JSR-184 API.
- Framebuffer: исходный Software RGB/ARGB, clip/translation, identity/resize/debug.
- Settings: сохранение Renderer/HW perspective, настройки и pause layout.
- Полная компиляция против Java ME API, ProGuard CLDC preverification.
- JAR: 145 classes, 1851 code methods, 998 StackMap attributes, class version 47.0.
- Quantum.jar: **462367 байт**.
- SHA-256: `30b09cddccf3eeea83dc3fc862df5297c371f1751d703848bcc7d23c2bbd2e9f`.

JAD согласован с размером. SDK/test doubles/KVM harness не входят в JAR.
Игровые ресурсы в сборку движка не добавлены. Визуальная проверка и FPS
на телефоне этой сборкой не измерялись.
