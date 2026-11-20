# Экспериментальный SNDDMA-style порт для Level 41

## Аппаратная проверка

Nokia E65 построен на TI OMAP1710 (ARM926TEJ + DSP) и Symbian OS 9.1/S60 3rd Edition. В OMAP1710 есть аппаратный DMA-путь; TI показывает общую память/DMA, McBSP и аудиокодек в платформенной схеме. Это подтверждает наличие DMA в системе на уровне SoC, но **не** означает, что обычное EKA2-приложение может напрямую управлять DMA-регистрами или аудиокодеком.

Для приложения правильная граница — `CMdaAudioOutputStream`/DevSound: передавать PCM-буферы, удерживать каждый буфер до `MaoscBufferCopied`, а аппаратную передачу оставлять системному драйверу. Реализация не пишет в DMA-регистры, не использует kernel driver и не требует небезопасного privileged path.

## Что уже написано

Код остаётся отдельным opt-in backend: обычный Level 41 preset теперь явно использует MDA и выключает SNDDMA; эксперимент включается только отдельным `e65-level41-walk-snddma-asm` preset. Он не заменяет MDA исходники или rollback SISX:

- `quantum_snddma_mixer.h/.cpp` — собственный allocation-free PCM16 RIFF/WAVE parser и Quake-inspired mixer: несколько голосов, Q15 gain/pan, 16-bit saturating mix, линейный resampling, выбор заменяемого канала по оставшемуся времени. У него нет зависимостей от Quake `client.h`, `cvar`, `FS_*` или Quake globals.
- `quantum_snddma_output.h/.cpp` — EKA2 adapter к `CMdaAudioOutputStream`: PCM16 stereo 11,025 Hz, три постоянных 256-frame output block, асинхронное refill вне MDA callback, обработка `WriteL` errors, учёт underflow, остановка без spin-wait. WAV data buffer остаётся во владении вызывающей стороны.
- `quantum_level41_snddma_audio.*` подключает экспериментальный backend к отдельному SNDDMA Level 41 target; обычный preset продолжает использовать `quantum_level41_audio.*`.
- `../host_tests/snddma_mixer_test.cpp` и `run_snddma_mixer_test.sh` проверяют RIFF parsing, malformed/truncated входы, mono pan, overlap/clipping, 2x resampling и предел voice pool. Host ASan/UBSan suite проходит.

Quake-источники используются как архитектурный референс (painted-channel mixer / voice allocation), а не копируются: приложенный `snd_dma.c` привязан к Quake `cl`, `cls`, `cl_entities`, файловой системе и другим engine API. Это позволяет написать Quantum-specific слой без неподходящего Quake client runtime.

## Ограничения текущего результата

- Важно: parser, resampler и микширование в `quantum_snddma_mixer.cpp` выполняются на ARM. `CMdaAudioOutputStream` передаёт PCM в системный audio stack, но не переносит этот пользовательский mixer на C55x. Наличие DSP в OMAP1710 само по себе такого offload не гарантирует; см. [`DSP-OFFLOAD-RESEARCH.md`](DSP-OFFLOAD-RESEARCH.md).
- Отдельный v1.0.44 SNDDMA SISX был cross-built и прошёл локальный package audit, но пользователь сообщает, что на E65 он **не запустился**. Поэтому не считать этот backend рабочим и не выбирать его как обычный билд.
- Причина сбоя запуска по имеющимся данным не установлена; успешные host tests и ARM cross-build не подтверждают runtime. Микшер и вывод эффектов остаются отдельным экспериментом; музыка в этом варианте использует MDA Player Utility.
- Музыка `wth.mp3` остаётся отдельным MDA Player Utility путём: `CMdaAudioOutputStream` получает PCM и не декодирует этот MP3.
- Успешные host tests проверяют только портативный parser/mixer, а не MDA stream, hardware DMA, codec или работу на устройстве.

## Справочные источники

- Идентификация платформы Nokia E65 как TI OMAP1710 / Symbian OS 9.1: [Legacy Portable Computing Wiki](https://lpcwiki.miraheze.org/wiki/Nokia_E65) (вторичный справочник).
- Блок-схема OMAP1710, ARM926 + C55x DSP, McBSP/audio и общий DMA: [Texas Instruments Wireless Terminals Solutions Guide 2006](https://www.ti.com/pdfs/wtbu/ti_wireless_solutions_guide_2006.pdf).
- `CMdaAudioOutputStream` как буферный PCM API с callback-ами, в отличие от прямого доступа приложения к DMA: [Symbian Audio Output Streaming Tutorial](https://docs.huihoo.com/symbian/nokia-symbian3-developers-library-v0.8/GUID-ECBA6331-2187-52C9-A5DF-20CD1EEFE781.html).
- В SDK `symbian-platform` v0.3.0 потоковый API экспортируется target-ом `Symbian::AudioStream`: [пример CMake](https://github.com/hpnkv/symbian-platform/blob/v0.3.0/examples/audio_app_classic/CMakeLists.txt).

## Rollback и выбранный обычный профиль

- Обычный `e65-level41-walk-asm` preset явно задаёт `QUANTUM_BUILD_LEVEL41_SNDDMA_ADAPTER=OFF` и `QUANTUM_LEVEL41_SNDDMA_AUDIO=OFF`; он выбирает существующий MDA backend. Экспериментальный SNDDMA preset оставлен отдельным и не становится профилем по умолчанию.
- Аудиоисточники MDA `symbian9/level41_device_test/quantum_level41_audio.*` сохранены. Проверенная локальным package audit MDA v1.0.43 SISX не перезаписывалась: SHA-256 `c01410a5fc9883b4fb95c1438a83b12dab60a70d08455648d3ebc571b1a9d3da`; Drive ID `1uCqoNjgXSYLAryO1r7n2c7MydWtlYy3h`.
- Исторические v1.0.43 MDA и v1.0.44 SNDDMA артефакты сохраняют 150-world-unit/10-degree event-step движение. Текущий рабочий native source теперь выравнивает ходьбу и weapon sway по Java frame-update: скорость добавляется к velocity, перемещение масштабируется frame time, grounded velocity затухает friction 4, а shake включается до Weapon.update. MDA остаётся обычным профилем, SNDDMA — отдельным экспериментом. E65 runtime-проверка нового movement в текущем окружении не выполнялась.
