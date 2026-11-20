# Исследование аппаратного DSP-offload для Nokia E65

## Короткий вывод

Nokia E65 использует TI OMAP1710 с ARM926 и отдельным TMS320C55x DSP. DSP аппаратно подходит для аудиосигналов, но **наличие DSP не даёт обычному Symbian-приложению готовой функции «передать произвольный Quake mixer на DSP»**.

Поддерживаемый путь приложения — MMF/MDA (`CMdaAudioPlayerUtility`, `CMdaAudioOutputStream` или `CMMFDevSound`). Этот путь идёт через device-specific controller/DevSound/codec adaptation. Некоторые адаптации могут использовать аппаратный DSP/codec, другие могут исполнять код на ARM; по общему API нельзя гарантировать, какой именно путь использует конкретная прошивка E65.

## Что это означает для текущей реализации

- `CMdaAudioPlayerUtility` для файлов — наиболее подходящий имеющийся API, если нужно дать системному multimedia stack возможность использовать свой codec/device plugin. Текущий Quantum audio backend уже пользуется этим путём для `wth.mp3`, `fn2000.wav` и `r.wav`; это не доказывает, что именно E65 декодирует их на C55x.
- `CMdaAudioOutputStream` принимает PCM-буферы. С нашим `quantum_snddma_mixer.cpp` resampling и смешивание каналов выполняются в application code на ARM926. OutputStream/DevSound обслуживает вывод, но это **не** offload нашего mixer на C55x.
- Файлы `fn2000.wav` и `r.wav` — уже распакованный PCM16. Для них аппаратный MP3/AAC decoder не устраняет работу по смешиванию; короткие PCM effects разумно оставить на штатном MMF пути и не вводить отдельный DSP runtime без измеренной необходимости.
- Имеющиеся `omap1710_dsp.S`, `omap1710_effects.S` и другие OMAP kernels используют ARMv5TE DSP-extension instructions. Это оптимизации ARM9 CPU, **не** код для отдельного TMS320C55x DSP.

## Что потребовалось бы для настоящего C55x offload

TI описывает OMAP DSP/BIOS Bridge с DSP nodes, ARM-side gateway components, message/data-stream APIs и DSP-side firmware. Чтобы выгрузить Quake-style mixer, нужно было бы:

1. Иметь E65-compatible C55x compiler, DSP/BIOS/DSP Bridge runtime и точное DSP firmware/ABI, поставляемые или разрешённые для этой Nokia-прошивки.
2. Собрать микшер как DSP node и определить формат сообщений, PCM buffers, синхронизацию, запуск/останов и обработку ошибок.
3. Иметь ARM-side gateway/API и driver/service, который в этой прошивке разрешает приложению запускать node и передавать ему buffers.
4. Проверить, что этот gateway экспортирован для сторонних приложений и что для вызова не нужны неподходящие для SIS capabilities или приватные Nokia API.

В текущем Quantum workspace таких C55x tools, firmware, DSP Gateway headers/libraries или E65 DSP driver/API не найдено. Ранее используемый SDK build cache сейчас отсутствует, поэтому его DSP-side contents в этом цикле перепроверить нельзя; имеющийся для приложения build path — ARMv5T/EKA2. Это не просто другой CMake target: без vendor runtime и доступного bridge нельзя безопасно загрузить собственный код на C55x из SIS.

Прямую запись в DSP/DMA registers делать нельзя: эти ресурсы принадлежат kernel/device audio stack, а пользовательское приложение должно обращаться к ним через системный media API.

## Рекомендация

Если acceptance criterion — **не выполнять собственный mixer на ARM**, не включать `QUANTUM_BUILD_LEVEL41_SNDDMA_ADAPTER`: этот prototype намеренно программно микширует PCM на ARM. Оставить audio file playback через MDA/DevSound, а повторный вылет расследовать отдельно; он сам по себе не доказывает нехватку ARM-производительности и не доказывает, что DSP не используется системой.

Дальнейший DSP эксперимент возможен только после получения Nokia/TI E65 DSP Gateway runtime/toolchain/documentation либо доказательства, что установленная прошивка предоставляет такой API. Даже если DSP audio codec присутствует, использование его штатного codec через MMF не равнозначно запуску произвольного Quake mixer на DSP.

## Источники

- E65 platform / OMAP1710 identification: [Legacy Portable Computing Wiki](https://lpcwiki.miraheze.org/wiki/Nokia_E65) (secondary hardware reference).
- TI OMAP1710 ARM926 + C55x/DMA/audio platform overview: [TI Wireless Terminals Solutions Guide 2006](https://www.ti.com/pdfs/wtbu/ti_wireless_solutions_guide_2006.pdf).
- TI DSP/BIOS Bridge architecture and the ARM-to-DSP API model: [TI Technical White Paper SWPA001](https://www.ti.com/lit/an/swpa001/swpa001.pdf).
- Symbian DevSound/MMF uses controller, codec and hardware-device plug-in layers: [DevSound Overview](https://docs.huihoo.com/symbian/nokia-symbian3-developers-library-v0.8/GUID-2D3F7471-9B92-5E49-B8BC-E0FA7AA709D7.html).
- `CMdaAudioOutputStream` can decode supported formats only where a matching DevSound codec exists; this is device-dependent: [Symbian Multimedia API overview](https://docs.huihoo.com/symbian/s60-5th-edition-cpp-developers-library-v2.1/GUID-C5738F3D-E1D5-492D-89C9-5AC28F22BE71.html).
- For device-specific codec/hardware adaptation background: [Multimedia on Symbian OS: Inside the Convergence Device](https://epdf.pub/multimedia-on-symbian-os-inside-the-convergence-device.html).
