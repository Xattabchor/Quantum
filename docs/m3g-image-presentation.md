# M3G: вывод готового Image без обязательного финального readback

Дата: 18.10.2026. Изменение касается вывода кадра, не форматов ресурсов,
материалов, порядка painter rendering или размера batch. Статические native
меши/другой формат моделей в эту работу **не входят**.

> После отката 18.10.2026 эта правка сохранена поверх исходников до KVM.
> CPU-кэши, LUT и fast-path упаковка из предыдущих экспериментов удалены.
> [Точный состав](m3g-pre-kvm-rollback.md).

## Что изменилось

Раньше игровой кадр всегда завершался цепочкой:

```
M3G -> releaseTarget -> Image.getRGB(display) -> Graphics.drawRGB(display)
```

Теперь GameScreen вызывает `DirectX7.renderGameFrameImage(...)`:

```
M3G -> releaseTarget -> Graphics.drawImage(existingSurface, x, y, TOP | LEFT)
```

`SceneRenderer.finishImage` отправляет оставшийся batch и освобождает target,
но не копирует весь кадр в Java-массив. `DirectX7.flush` рисует уже существующий
mutable LCDUI Image с прежним screenY, translation и clip. Второй Image или
второй новый кадровый int[] для этого не выделяется. Прежний frameBackup остаётся.
HUD, оружие и LCDUI fallback погоды по-прежнему идут после вывода сцены.

Успех native weather подтверждается после завершения всей native-транзакции
и освобождения target. Подготовка маски/погоды, sky -> world -> post -> weather
и сортировка не меняются. Native ошибка на любом этапе не публикует частичный
Image: восстанавливается backup, вся текущая сцена и эффекты повторяются на CPU,
а погода рисуется штатным LCDUI fallback.

## Когда чтение пикселей остаётся необходимым

1. CPU-материал, неподдерживаемый объект или загрузка холодной текстуры после
   native draws: прежний barrier/finish/getRGB сохраняется.
2. `getDisplay()`: читает pending Image **до** возврата исходного writable
   `display`, затем отменяет image-only presentation. Так снимок паузы содержит
   последний завершённый кадр, а последующие изменения массива видны в flush.
3. Следующий кадр с частичным фоном/без фона: предыдущий Image сначала читается
   в display, чтобы сохранить окружение и корректный pre-frame backup.
4. Переключение renderer/явный release сохраняет последние пиксели. Resize и
   destroy, напротив, просто отбрасывают старый Image — чтение им не нужно.
5. CPU line, старые eager API, debug и flushAlpha синхронизируют pending Image
   перед работой с пикселями. Полная CPU clear сразу отбрасывает его без чтения.

### Почему нельзя просто удалить все getRGB

Один mutable Image перезаписывается следующим native-кадром. Если предыдущие
пиксели не прочитать, а новая геометрия не заполняет весь экран, CPU backup
останется устаревшим: при native сбое смешанного кадра восстановится неверный фон.
Исправлять это вторым framebuffer на малопамятном телефоне в этой версии не стали.

Чтение предыдущего кадра пропускается, только если **первый** объект отсортированного
sky prefix гарантированно перекрывает весь framebuffer:

- SolidSky;
- SkyFlash (native clear);
- PanoramaSky с прямоугольником `(0,0,width,height)` и неинвертированным диапазоном Y.

Тогда CPU replay нового полного неба сам восстановит фон даже из старого backup.
SkyMesh, cropped panorama и просто «много полигонов» такой гарантией не считаются.
Полное небо, стоящее после другого background-объекта, тоже не разрешает этот shortcut.

**Следствие:** на прогретом полностью native-кадре с полным opaque sky больше нет
ни финального getRGB, ни его скрытого переноса на начало каждого следующего кадра.
Без полного фона необходимое чтение перед повторным использованием Image остаётся.
Поэтому это не обещание отсутствия всех кадровых копий во всех комнатах.

## Совместимость и сбои

- Software остаётся на исходном MIDP drawRGB, без Nokia DirectGraphics.
- `render()`, `renderGameFrame(...)`, `renderWithEffects(...)`, `renderOverlay(...)`
  сохраняют eager-контракт: после возврата display содержит результат.
- Новый image-only API — явный opt-in. После него читать/менять публичный
  `display` напрямую нельзя, сначала нужен `getDisplay()`. Все реальные CPU
  writers игрового пути проверены; существующие legacy API не становятся lazy.
- Замена самого массива `display` распознаётся: новый массив считается источником
  истины и не перетирается pending Image. Произвольную запись в старый массив по
  ранее сохранённой ссылке перехватить в Java невозможно.
- При RuntimeException/OOM в drawImage текущий Image читается и выводится через
  старый drawRGB; следующие кадры используют eager-вывод до release/resize/смены
  renderer. Сам M3G-растеризатор из-за отказа LCDUI blit не отключается.
- Ошибки native draw/clear/release, подготовки, промежуточного getRGB и native OOM
  сохраняют полный CPU replay текущего кадра и отключение сбойного backend.
- **Отдельный крайний случай:** поздний getRGB для снимка/сохранения предыдущего
  кадра уже не может повторить старую сцену — её очереди очищены после завершения.
  Если этот getRGB бросает исключение, оно распространяется вызывающему коду;
  устаревший массив не возвращается. Последний завершённый Image сохраняется,
  новый native pass ещё не начат, очередь отменённого нового кадра очищается.
  Можно по-прежнему вывести сохранённый Image или повторить чтение. Это не
  «успешный» снимок и не обещание CPU recovery при отказе самого snapshot API.
- DirectX7/SceneRenderer не получают обязательных ссылок на JSR-184 классы.
  Без JSR-184 новый entry point также делает полный CPU fallback.

HUD **B** теперь включает попытки отложенного Image -> display чтения наряду
с прежними intermediate barriers. Старый eager final readback по-прежнему не
входит в B. D и T не менялись; это отдельные peaks за 250 мс, не GPU timings.

## Проверки

`tests/m3g/ImagePresentationTest.java`, реальные производственные классы и
инструментированные LCDUI/M3G doubles:

| Сценарий | Проверяемый контракт |
|---|---|
| 4 последовательных прогретых кадра с full sky/world/cold post/weather | 0 getRGB, 0 drawRGB, 8 drawImage при двух flush на кадр, 0 дополнительных Image |
| Снимок паузы | 1 getRGB, повторный getter не читает заново, CPU mutation видна |
| Offset/translation/clip | результат blit равен старому drawRGB с тем же массивом |
| Image -> CPU; native -> CPU -> native | правильные пиксели, только нужные barriers, прямой вывод последнего native-сегмента |
| No-sky native failure | CPU replay начинается с корректно сохранённого предыдущего framebuffer |
| Full/cropped panorama, storm | полный фон пропускает чтение, crop сохраняет внешние пиксели; clear failure replay |
| Холодная мировая текстура | остаётся только необходимый texture barrier, финального чтения нет |
| Failure sky/world/post/трёх weather packets/release, intermediate getRGB, OOM | полный CPU replay, false nativeWeather, нет публикации частичного Image |
| Поздний snapshot/read-before-reuse failure | исключение вместо stale display, сохранённый Image, нет утечки очереди/target |
| drawImage RuntimeException/OOM | fallback к getRGB/drawRGB, без постоянных повторов сбойного blit |
| line/clear, switch/release, resize/destroy, replacement display, overlay/Software | корректная передача владения пикселями |
| alpha/debug/Software/legacy eager | исходный вывод и контракты сохранены |

Также выполняются прежние m3g tests, в том числе с реально удалённым JSR-184 API
и вызовом нового entry point; framebuffer и settings suites. Сборка остаётся
CLDC 1.1, class version 47.0 с ProGuard StackMap, тестовые doubles не входят в JAR.

Это проверки контрактов, **не** визуальный рендер-тест M3G и не замер FPS.
На телефоне/J2ME Loader новая сборка не измерялась. `drawImage` убирает явный
Java pixel roundtrip, но не гарантирует отсутствие внутренних копий эмулятора,
аппаратное ускорение или прирост FPS. Необходимые CPU barriers и backup arraycopy
могут по-прежнему занимать заметное время.
