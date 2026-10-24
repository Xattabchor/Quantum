#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Генератор PDF-справочника «Новые ключи setting.txt» для движка Quantum (J2ME).

Все сведения берутся из этого файла как из единого источника: таблица ключей
KEYS одновременно строит и сводную таблицу, и подробные разделы, чтобы они не
разъезжались. Данные сверены с кодом:
    src/code/utils/DeviceProfile.java     - разбор ключей, значения по умолчанию
    src/code/utils/Main.java              - PERSPECTIVE_STEP (setPersQ)
    src/code/Gameplay/LevelLoader.java    - NEAR_ONLY -> UPDATE_ONLY_NEAR*
    src/code/AI/Bot.java, NPC.java        - AI_LOD, AI_LOD_DIST
    src/code/Rendering/Meshes/MeshImage.java   - MORPH_LOD, MORPH_LOD_DIST
    src/code/Rendering/Meshes/BoundingBox.java - FAST_CULL
    src/code/Math/MathUtils.java, Rendering/RenderObject.java - FAST_LIGHT
    src/code/Gameplay/GameScreen.java     - постэффекты через родные ключи игры
                                              (BLOOM/VIGNETTE; DOUBLE_BRIGHT
                                              вырезан)
    UtilsText/src/code/utils/IniFile.java - формат файла (ключ=значение, #/;)

Запуск:
    python3 docs/make-keys-pdf.py [куда_положить.pdf]
Нужен reportlab (pip install reportlab) и шрифты DejaVu (в Debian/Ubuntu -
пакет fonts-dejavu-core).
"""
import datetime
import os
import subprocess
import sys

from reportlab.lib import colors
from reportlab.lib.enums import TA_LEFT
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle, getSampleStyleSheet
from reportlab.lib.units import mm
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.platypus import (BaseDocTemplate, Frame, KeepTogether, PageBreak,
                                PageTemplate, Paragraph, Spacer, Table,
                                TableStyle)

# ----------------------------------------------------------------------------- 
# Шрифты (кириллица)
# -----------------------------------------------------------------------------
FONT_DIRS = [
    '/usr/share/fonts/truetype/dejavu',
    '/usr/share/fonts/dejavu',
    '/usr/local/share/fonts/dejavu',
    os.path.expanduser('~/.fonts'),
]
FONT_FILES = {
    'F': 'DejaVuSans.ttf',
    'FB': 'DejaVuSans-Bold.ttf',
    'M': 'DejaVuSansMono.ttf',
    'MB': 'DejaVuSansMono-Bold.ttf',
}


def register_fonts():
    found = {}
    for d in FONT_DIRS:
        for alias, fname in FONT_FILES.items():
            if alias in found:
                continue
            path = os.path.join(d, fname)
            if os.path.exists(path):
                found[alias] = path
    missing = [a for a in FONT_FILES if a not in found]
    if missing:
        raise SystemExit('не найдены шрифты DejaVu: %s\n'
                         'Поставьте их (Debian/Ubuntu: apt-get install '
                         'fonts-dejavu-core) или положите .ttf рядом со скриптом.'
                         % ', '.join(FONT_FILES[m] for m in missing))
    for alias, path in found.items():
        pdfmetrics.registerFont(TTFont(alias, path))
    pdfmetrics.registerFontFamily('F', normal='F', bold='FB', italic='F',
                                  boldItalic='FB')
    return found


# ----------------------------------------------------------------------------- 
# Данные: новые ключи setting.txt
# -----------------------------------------------------------------------------
# arm9 / fast - значения по умолчанию на соответствующем профиле CPU.
KEYS = [
    dict(
        name='CPU_PROFILE',
        values='AUTO | ARM9 | FAST (или 1 | 2)',
        arm9='AUTO', fast='AUTO',
        short='принудительный выбор профиля устройства',
        where='DeviceProfile.init(); вызывается один раз из Main.init() - до '
              'defaultSettings() и до чтения сохранённого профиля из RMS.',
        speed='Не ускорение само по себе, а переключатель: профиль ARM9 включает '
              'щадящие значения по умолчанию для всех остальных ключей, профиль '
              'FAST/AUTO оставляет прежнее поведение.',
        loss='Нет - это выбор режима.',
        extra=[
            'Регистр значения не важен; вместо слов принимаются 1 (= ARM9) и '
            '2 (= FAST). Любое другое значение (включая 0 и пустое) трактуется '
            'как AUTO.',
            'AUTO означает, что платформа не распознана: тогда lowEnd = false и '
            'работают только точные ускорения (те, что не имеют ключа вовсе).',
            'Автоопределение FAST: десктоп и эмуляторы (os.name содержит '
            'win/mac/nix/nux/aix/android), Symbian/S60 (список из 54 моделей '
            'Nokia), pstros, KEmulator, FreeJ2ME, J2ME Loader, UIQ-аппараты '
            'Sony Ericsson (P*, M600, W950, G700, Xperia*).',
            'Автоопределение ARM9: SonyEricsson*/SEMCCP*/SEMC*, Samsung/SAMSUNG, '
            'Motorola/MOT-/MOTO, LG-/LG//LGE, Fly, Philips, SAGEM/Sagem, BenQ, '
            'SIEMENS/Siemens, Panasonic, SHARP, Toshiba, Alcatel, ZTE, Huawei, '
            'Nexian, остальные Nokia* (Series40), а также любая платформа с '
            'microedition.configuration = CLDC-1.0.',
            'System.getProperty() обёрнут в try/catch(Throwable): на части '
            'аппаратов он бросает исключение для неизвестных ключей, и старт не '
            'должен из-за этого падать.',
        ],
    ),
    dict(
        name='FAST_LIGHT',
        values='0 | 1',
        arm9='1', fast='0',
        short='целочисленное освещение вместо double',
        where='MathUtils.calcLightFast() / calcLightDirFast() / '
              'microCalcLightFast(); выбор ветки - в RenderObject.microLight(), '
              'RenderObject.dirLight() и MathUtils.calcLightRender() (drawmode 5, '
              '6 и 12; всего 18 мест в Polygon3V/4V, LightedPolygon3V/4V, '
              'ColorLightedPolygon3V/4V, TexturingAffine, TexturingPers).',
        speed='Убирает из кадра double-арифметику, Math.sqrt и лишние деления: на '
              'ARM9 нет FPU, поэтому float/double и Math.sqrt выполняются '
              'программно (сотни тактов на вызов), а деление - библиотечная '
              'подпрограмма.',
        loss='Освещение может отличаться на 1-4 единицы из 255 (см. точность '
             'ниже). На глаз в реальной сцене не различимо.',
        extra=[
            'Точность проверена перебором (docs/verify/test_light.py): '
            'calcLightFast - 98,1 % значений совпадают бит-в-бит, худшее '
            'отклонение 3 из 255; calcLightDirFast - 96,7 % и 4 из 255. В '
            'реальной сцене (lightdir порядка 4096) отклонение 0-1 из 255.',
            'Все 8 граничных случаев (нулевые векторы, оси, (4096,0,0) против '
            '(-4096,0,0), 30000*6, 32767*3 против (1,1,1)) совпадают точно.',
            'Оригинальные MathUtils.calcLight() и microCalcLight() НЕ изменены: '
            'ими пользуется LightMapper, который печёт лайтмапы и может писать '
            'файлы, - там прежняя математика нужна бит-в-бит. Поэтому формат и '
            'содержимое lightmap.vla не меняются.',
            'Переполнение суммы квадратов в оригинале даёт NaN, а (int)NaN == 0: '
            'быстрая ветка возвращает 0 в том же случае. Clamp сохранён '
            '(calcLightFast - clamp255, microCalcLightFast - только верхний 255, '
            'потому что оригинал может вернуть отрицательное значение и это '
            'используется).',
        ],
    ),
    dict(
        name='PERSPECTIVE_STEP',
        values='0 ... 256',
        arm9='32 (если ключ не задан)', fast='0',
        short='шаг перспективной коррекции (Main.q)',
        where='Main.setPersQ(): q = PERSPECTIVE_STEP, если он больше 0, иначе 24. '
              'Применяется только при persQ меньше либо равно 2 (пункт меню '
              'настроек «перспектива»); при persQ = 3 и 4 q всегда 12.',
        speed='q - длина сегмента в пикселах, который растрируется одним набором '
              'инкрементов. Чем крупнее шаг, тем реже выполняется дорогое '
              '64-битное деление в перспективном растеризаторе.',
        loss='Небольшое искажение текстуры на длинных наклонных сегментах '
             '(пол/потолок вдали от камеры): коррекция выполняется реже.',
        extra=[
            '0 означает «как раньше» (q = 24) - значение по умолчанию на '
            'профилях FAST и AUTO.',
            'На профиле ARM9, если ключ в файле отсутствует, ставится 32. Явный '
            'ключ всегда важнее профиля: PERSPECTIVE_STEP=0 на ARM9 вернёт 24.',
            'Отрицательные значения обрезаются до 0, большие 256 - до 256.',
        ],
    ),
    dict(
        name='NEAR_ONLY',
        values='0 | 1',
        arm9='1', fast='0',
        short='симулировать только объекты рядом с игроком',
        where='LevelLoader: Main.updateOnlyNear = getBoolean("UPDATE_ONLY_NEAR", '
              'lvl, setting, DeviceProfile.nearOnly); затем Scene (5 мест) и '
              'NPCSpawner используют house.getNearObjectsForUpdate() / '
              'getNearParts() вместо полного списка объектов.',
        speed='Обход и обновление всех объектов сцены - одна из главных статей '
              'расходов в кадре с NPC: вместо всей карты обрабатывается только '
              'текущая часть (part) и соседние.',
        loss='Объекты вдали от игрока не обновляются: их ИИ и физика «застывают» '
             'до приближения. NPCSpawner при этом проверяет дальние точки раз в '
             '8 секунд, поэтому возрождение не ломается.',
        extra=[
            'Ключ не вводит новое поведение - он задаёт значение по умолчанию для '
            'существующих ключей оригинала UPDATE_ONLY_NEAR и '
            'UPDATE_ONLY_NEAR_PHYSICS (level.ini или setting.txt). Ключ уровня и '
            'ключ в setting.txt по-прежнему важнее профиля.',
            'UPDATE_ONLY_NEAR_PHYSICS по умолчанию наследует итоговое значение '
            'UPDATE_ONLY_NEAR, как и в оригинале.',
        ],
    ),
    dict(
        name='AI_LOD',
        values='0 | 1',
        arm9='1', fast='0',
        short='далёким ботам реже искать цель',
        where='Bot.update() раз в кадр ставит флаг aiFar (DeviceProfile.aiLod && '
              'distance > aiLodDist); NPC.action() использует react = aiFar ? '
              '(reacttimer меньше на один разряд: reacttimer << 1) : reacttimer.',
        speed='Блок «поиск цели и перестроение маршрута» тянет findBot() по всем '
              'объектам сцены и проверку видимости. Для дальнего бота он '
              'выполняется вдвое реже. Флаг считается один раз на бота в кадр, '
              'потому что action(Scene) игрока не видит, а scene.findPlayer() - '
              'это обход всех объектов.',
        loss='Далёкий NPC замечает игрока и перестраивает маршрут на один «такт '
             'реакции» позже. Атака, движение, анимация и физика работают с '
             'прежней частотой.',
        extra=[
            'Порог «далёкости» задаётся ключом AI_LOD_DIST.',
        ],
    ),
    dict(
        name='AI_LOD_DIST',
        values='единицы карты (по умолчанию 10000)',
        arm9='10000', fast='10000',
        short='порог «далёкости» для AI_LOD',
        where='Bot.update(): character.distance(player.character) > '
              'DeviceProfile.aiLodDist.',
        speed='Позволяет выбрать, какую долю ботов считать «дальними». Внутри '
              'хранится квадрат порога (10000 * 10000 = 100 000 000), поэтому '
              'сравнение идёт без извлечения корня.',
        loss='Чем меньше значение, тем больше ботов переходят в редкий режим и тем '
             'заметнее задержка их реакции.',
        extra=[
            'Значения больше 46000 обрезаются до 46000: 46000 в квадрате = '
            '2 116 000 000 - последнее значение, которое помещается в int без '
            'переполнения.',
            'Значения меньше либо равны 0 игнорируются - остаётся 10000.',
            'Ключ не зависит от профиля CPU и имеет смысл только при AI_LOD=1.',
        ],
    ),
    dict(
        name='MORPH_LOD',
        values='0 | 1',
        arm9='1', fast='0',
        short='дальним мешам не смешивать кадры анимации',
        where='MeshImage.morphLod() и setAnimationLod(): на время '
              'mesh.setAnimation() временно выключается animation.morphEnabled и '
              'сразу восстанавливается.',
        speed='Morphing.interpolation() считает 6 умножений и 6 сдвигов на КАЖДУЮ '
              'вершину каждый кадр. Дальний меш занимает на экране несколько '
              'пикселов, поэтому смешивание кадров для него бессмысленно.',
        loss='У далёких персонажей анимация переключается целыми кадрами, без '
             'плавного смешивания. На дистанции дальше MORPH_LOD_DIST это '
             'практически не заметно.',
        extra=[
            'Флаг morphEnabled принадлежит анимации, а анимация может быть общей '
            'для нескольких NPC (кэш Asset), поэтому он восстанавливается сразу '
            'после вызова - на остальные меши отключение не влияет.',
        ],
    ),
    dict(
        name='MORPH_LOD_DIST',
        values='единицы карты (по умолчанию 12000)',
        arm9='12000', fast='12000',
        short='порог «далёкости» для MORPH_LOD',
        where='MeshImage.morphLod(): sz < -(DeviceProfile.morphLodDist меньше на '
              'два разряда: << 2), где sz = centre.rz * 4.',
        speed='Задаёт, с какой дистанции меш считается дальним. Поскольку перед '
              'камерой rz отрицателен, условие эквивалентно «дистанция больше '
              'MORPH_LOD_DIST».',
        loss='Чем меньше значение, тем больше мешей теряют плавность анимации.',
        extra=[
            'Отрицательные значения обрезаются до 0, значения больше 1 000 000 - '
            'до 1 000 000, чтобы сдвиг на два разряда не переполнил int.',
            'Ключ не зависит от профиля CPU и имеет смысл только при MORPH_LOD=1.',
        ],
    ),
    dict(
        name='FAST_CULL',
        values='0 | 1',
        arm9='1', fast='0',
        short='быстрый отсек BoundingBox перед полным тестом',
        where='BoundingBox.isVisible(): if (DeviceProfile.fastCull && '
              '!maybeVisible(g3d, matrix, x1, y1, x2, y2)) return false; вызовы '
              'isVisible - NPC (2), BigZombie, Zombie, Corpse, MeshObject, '
              'RoomObject (6), House, Room.',
        speed='Полный тест - это 8 преобразований вершин и 16 целочисленных '
              'делений на каждый объект каждый кадр (idiv на ARM9 - библиотечная '
              'подпрограмма). Предварительная проверка центра с радиусом стоит '
              'одно преобразование и два деления: около 30 дешёвых '
              'целочисленных операций.',
        loss='Визуально не теряется ничего: быстрый тест отбрасывает только '
             'заведомо невидимое. На видимых объектах появляются накладные '
             'расходы около 15 % от стоимости проверки видимости (сам рендер '
             'меша на порядки дороже).',
        extra=[
            'Все оценки направлены «в сторону не отсекать»: радиус бокса '
            'умножается на наибольшее по модулю значение строки матрицы, а '
            'знаменатель проекции берётся для ближайшей к камере точки '
            '(максимальное увеличение).',
            'Быстрый тест возвращает false только тогда, когда полный тест тоже '
            'вернул бы false. Проверено перебором: docs/verify/test_cull.py - '
            '182 187 случаев, 0 ошибочных отсечений. На 60 000 реалистичных '
            'случаев точный тест отвергает 92,5 % объектов, быстрый - '
            'самостоятельно 48,3 % (остальные добирает полный тест).',
            'При любом выходе за безопасные целочисленные границы (вырожденный '
            'или перевёрнутый бокс, пустая/перевёрнутая область отрисовки, '
            'слишком большое расстояние, cullOk = false) объект отправляется на '
            'полный тест.',
        ],
    ),
    dict(
        name='QCT',
        values='0 | 1',
        arm9='1', fast='1',
        short='подхватывать упакованные текстуры .qct рядом с .png',
        where='RawImage.createRawImage(): для пути с .png движок ищет в JAR '
              'одноимённый ресурс .qct и, если он есть, читает его родным '
              'загрузчиком qctToRawImage(); иначе - прежний PNG-путь.',
        speed='Меньше вес текстур и память на загрузке: QCT - 0,75 байта на '
              'пиксель плюс палитра против 4 байт на пиксель в int[]; '
              'декодированию не нужен промежуточный Image (PNG-путь держит '
              'Image и int[] одновременно).',
        loss='В рантайме ничего: точность цвета фиксируется при упаковке '
             '(палитра 2-64 цвета; на градиентах с малой палитрой возможен '
             'бендинг). Скорость рендера не меняется - после распаковки '
             'текстура живёт в том же int[].',
        extra=[
            'Формат родной для движка: загрузчик qctToRawImage существовал '
            'всегда, не хватало энкодера. Теперь он есть - code.utils.'
            'QctEncoder (DXT1-подобные блоки 2x2 с глобальной палитрой, '
            'целочисленный k-means, run-поток полутонов альфы), а конвертер '
            'PNG -> QCT собран отдельным MIDlet QctConverter.jar '
            '(docs/j2me-build/build-qctconv.sh, описание в docs/ARM9.md §10).',
            'Ограничение формата: ширина и высота чётные (блоки 2x2); нечётные '
            'текстуры энкодер отклоняет, а движок и раньше читал бы их с '
            'потерей последней строки/колонки.',
            'Ключ не зависит от профиля CPU: это формат хранения, а не '
            'ухудшение. QCT=0 возвращает чисто png-поведение; без .qct в JAR '
            'поведение не меняется вовсе.',
            'Проверено: docs/verify/test_qct.py - round-trip точен на '
            'палитрных текстурах и бинарной альфе, полутона альфы сохраняются, '
            'а настоящий загрузчик движка даёт побитово тот же int[], что и '
            'QctEncoder.decode().',
        ],
    ),
    dict(
        name='FAST_SETUP',
        values='0 | 1',
        arm9='1', fast='0',
        short='подготовка перспективных полигонов без машинных делений '
              '(коэффициенты тумана)',
        where='TexturingPers.paint9Pers(): fogCoef/waterCoef заменяют '
              '0xff + rz*0xFF/fDist и /waterDistance умножением на '
              'floor(2^34/d); магическое число кэшируется и пересчитывается '
              'только при смене делителя. При d < 256 сохраняется прежний '
              'idiv.',
        speed='Из примерно 23 idiv на полигон в setup исчезают 6 туманных; '
              'остальное делает не зависящая от ключа точная часть: 14 '
              'градиентов ((delta<<12)/dy) заменяются одним long-делением на '
              'полигон и 14 умножениями, три /255 - тождеством без деления. В '
              'сценах с сотнями полигонов это миллионы тактов в секунду.',
        loss='Градации тумана и подводной дымки могут отличаться на 1 из 255 '
             '(94 % значений совпадают точно). На глаз неотличимо; видно '
             'только при покадровом сравнении скриншотов.',
        extra=[
            'Точные замены этого этапа ключа не имеют вовсе: div255 (тождество '
            '(a+1+(a>>8))>>8, guard |x| < 65535, вне диапазона - прежний idiv) '
            'и градиенты (ceil(2^37/dy) с однократной коррекцией перелёта - '
            'точно для всех int при dy >= 64, при dy < 64 - прежний idiv).',
            'Погода (WeatherGenerator) хранит координаты частиц в int Q8 '
            '(1/256 пиксела) и не делает делений на частицу вовсе: /50 через '
            'FastMath.div50 (бит-в-бит), деление на глубину 1..65 - умножением '
            'на таблицу INV_DEPTH. Ключа нет: отличие от float субпиксельное '
            '(docs/ARM9.md §11.3).',
            'Деления wz = tempI/(-rz+move) (3 на полигон) оставлены: делитель '
            'разный для каждой вершины, кэш магических чисел не помогает.',
            'Проверено: docs/verify/test_setup_math.py - 0 расхождений div255, '
            'div50 и градиентов (критические точки k*d±1, отрицательные, '
            'случайные по всему int), максимальная погрешность тумана 1; '
            'TestWeather - инварианты 400 кадров движения частиц.',
        ],
    ),
]

# Ключи оригинала, у которых изменилось только значение по умолчанию.
CHANGED_DEFAULTS = [
    ('UPDATE_ONLY_NEAR', 'level.ini или setting.txt', 'false',
     'DeviceProfile.nearOnly (то есть 1 на ARM9, 0 на FAST/AUTO)',
     'Симулировать и сталкивать только объекты рядом с игроком. Ключ '
     'существует в оригинале; изменилось лишь значение, которое подставляется, '
     'если ключ нигде не задан.'),
    ('UPDATE_ONLY_NEAR_PHYSICS', 'level.ini или setting.txt',
     'значение UPDATE_ONLY_NEAR', 'без изменений (наследует UPDATE_ONLY_NEAR)',
     'Отдельное управление физикой ближайших объектов. Поведение то же, но '
     'наследуемое значение теперь может прийти из профиля.'),
]

# Ускорения, у которых ключа нет вовсе: они включены всегда.
ALWAYS_ON = [
    ('FrameClock - один System.currentTimeMillis() на кадр',
     'QFPS.frame() кэширует время; 12 читателей: Sprite.updateFrame, '
     'Texture.updateAnimation, NPC.render/setSprite/action/isTimeToRenew, '
     'Player (интервал шага 450 мс, проверка Y 1000 мс, таймер смерти 3000 мс), '
     'GameScreen (подсветка оружия 135 мс, оверлей, сообщение)',
     'расхождение не больше длительности кадра; событийные записи таймеров '
     '(lastAttack и подобные), ограничитель кадров в GameScreen и измерение '
     'времени загрузки остались на настоящем времени'),
    ('FastMath.div100() вместо деления на 100',
     'индекс кадра анимации: NPC.render, Zombie.render, BigZombie.render, '
     'GameObject.getFrameInterDiv()',
     'бит-в-бит: умножение на 1 374 389 535 и сдвиг на 37, знак обрабатывается '
     'отдельно; проверено на 6 312 931 значении, 0 расхождений '
     '(docs/verify/test_fastmath_java.py)'),
    ('Vector3D.length() через FastMath.isqrt()',
     'все вызовы length() в физике, ИИ и рендере',
     'бит-в-бит: isqrt(n) == (int)Math.sqrt(n) для любого int n'),
    ('FastMath.div2() вместо /2',
     'Sprite.project() (обе версии), NPC.render (muzzleFlash.getHeight() / 2)',
     'бит-в-бит, включая отрицательные значения (>> 1 для них не эквивалентен)'),
    ('32-битные остатки в анимации вместо 64-битных',
     'Sprite.updateFrame(), Texture.updateAnimation()',
     'Sprite - бит-в-бит (время неотрицательно и помещается в int); Texture - '
     'фаза отсчитывается от первого кадра, период, скорость и взаимная '
     'синхронность текстур не меняются'),
    ('Физика и AI (NPC): кэш AABB, фильтры текстур, кэши списков',
     'кэш AABB полигонов Mesh для статической геометрии комнат (инвалидация '
     'в transformSave); фильтр неколлизионных текстур поднят до дорогих '
     'тестов (underRoof сохранён); XZ-отсечка в isPointOnMesh; список '
     'объектов кэшируется на кадр физики (Room.objectsRevision); ближние '
     'комнаты Room[] вместо перестройки Vector; findBot через copyInto',
     'fuzz старых и новых версий sphereCast/computeHeight/isPointOnMesh: '
     '360 тыс. сравнений бит-в-бит, включая мутации позиции сферы '
     '(docs/verify/test_ai_physics.py)'),
    ('Режимы 9/13 и ядра перспективы: FPq, кэш fDist/255, div3, div255',
     'сегментное /q -> *FPq>>fp в пяти ядрах (_1/_3/_3_HQ/_5/_6_HQ, как уже '
     'было в _13); fDist/255 через ключ-кэш; (af+ag+ab)/3 и ar*light/255 '
     'через FastMath.div3/div255',
     'div3/div255 бит-в-бит (540 тыс. значений перебором); FPq - отклонение '
     '<= 0,04 тексела на шаг, не накапливается (docs/verify/test_modes913.py)'),
    ('Стекло (режим 7): преломление через байтовые таблицы',
     'три умножения на пиксель в paint_glass/paintPers_glass -> две выборки '
     'из int[256], таблицы кешируются на размер экрана',
     'бит-в-бит: 120 864 случая - все R,G 0..255 x scale 1..256 и реальные '
     'экраны (docs/verify/test_glass.py)'),
    ('Room.divChunk() / divChunkRender()',
     '18 мест деления в растеризации комнат',
     'бит-в-бит: 3020 пар значений, 0 расхождений (docs/verify/test_room_div.py)'),
    ('Бюджетный гибрид сортировки по глубине',
     'сортировка полигонов в кадре; fallback - устойчивые слияния (§20: '
     'порядок равных sz постоянен, компланарные [MESH] не мерцают)',
     'бит-в-бит + устойчивость: 65 случаев и 50 кадров дрейфа, 0 расхождений '
     '(docs/verify/test_sort.py)'),
    ('RenderObject.kind вместо instanceof',
     '13 мест в рендере и сортировке',
     'бит-в-бит: поле ставится в конструкторе после super()'),
    ('House.getNearObjectsCached()',
     'обновление ближайших объектов (Scene, NPCSpawner)',
     'бит-в-бит: кэш инвалидируется при переприсвоении списка объектов'),
    ('Точная часть подготовки полигонов без idiv',
     'TexturingPers.paint9Pers(): div255 в drawmode 6 (3 места), 14 '
     'градиентов ((delta<<12)/dy) через ceil(2^37/dy) с коррекцией перелёта',
     'бит-в-бит: div255 - тождество при |x| < 65535 (вне - прежний idiv), '
     'градиенты точны для всех int при dy >= 64 (вне - прежний idiv); '
     'перебор в docs/verify/test_setup_math.py'),
    ('FastMath.div50() и погода в int Q8',
     'WeatherGenerator: координаты частиц в 1/256 пиксела вместо float; '
     'деление на 50 и на глубину 1..65 (INV_DEPTH) одним умножением; '
     'mixColor через div100',
     'субпиксельно: div50 бит-в-бит на всём int, дробные константы равны '
     'прежним точно (fractions.Fraction), квантование 1/256 пиксела; '
     'TestWeather - инварианты 400 кадров'),
    ('Развёртки циклов растеризатора',
     'line_0_opaque (6x), paint9Mini (4x), Sprite.renderF (4x), fastbloomScr '
     '(4x), clearDisplay (8x)',
     'бит-в-бит: быстрые пути включаются только для непрозрачных пикселов '
     '(RawImage.opaque)'),
    ('Мелкие hoist-ы и кэши полей',
     'MathUtils.pLength() (>> 2 вместо /4), Sprite.project() (kx/ky вместо '
     'четырёх чтений textures[0].rImg.scale), DirectX7.fDist255, '
     'SphereCast.edgeFar, Dijkstra.Point.dis',
     'бит-в-бит; деление в pLength() оставлено на случай переполнения int'),
]

PRESETS = [
    ('A. Побитово как оригинал (для сравнения скриншотов и отладки)',
     'CPU_PROFILE=FAST\nFAST_LIGHT=0\nPERSPECTIVE_STEP=0\n'
     'NEAR_ONLY=0\nAI_LOD=0\nMORPH_LOD=0\nFAST_CULL=0\n'
     'QCT=0\nFAST_SETUP=0',
     'Все ускорения с потерей качества выключены, остаются только точные замены '
     '(div100, div50, isqrt, FrameClock, градиенты без idiv, развёртки циклов '
     'и т. п.). AI_LOD_DIST и MORPH_LOD_DIST при выключенных AI_LOD и '
     'MORPH_LOD ни на что не влияют; QCT=0 запрещает подхватывать упакованные '
     '.qct, если они есть в JAR; FAST_SETUP=0 возвращает прежние коэффициенты '
     'тумана. Погода хранит координаты в int Q8 (1/256 пиксела) без ключа - '
     'единственное субпиксельное отличие набора A.'),
    ('B. Ничего не менять (рекомендуется)',
     '; файл setting.txt оригинала - без единого нового ключа',
     'На аппарате класса ARM9 автоопределение включит все щадящие значения по '
     'умолчанию, на быстром устройстве или в эмуляторе поведение останется '
     'прежним. Именно поэтому файлы оригинала продолжают работать как раньше.'),
    ('C. Максимум скорости при умеренных потерях',
     'CPU_PROFILE=ARM9\nFAST_LIGHT=1\nPERSPECTIVE_STEP=48\n'
     'NEAR_ONLY=1\nAI_LOD=1\nAI_LOD_DIST=8000\n'
     'MORPH_LOD=1\nMORPH_LOD_DIST=9000\nFAST_CULL=1\nFAST_SETUP=1',
     'Пороги LOD уменьшены, поэтому в редкий режим переходит больше объектов. '
     'Профиль задан явно - настройки применяются даже там, где автоопределение '
     'не узнало аппарат.'),
    ('D. Максимум скорости, потери заметны',
     'CPU_PROFILE=ARM9\nFAST_LIGHT=1\nPERSPECTIVE_STEP=96\n'
     'NEAR_ONLY=1\nAI_LOD=1\nAI_LOD_DIST=5000\n'
     'MORPH_LOD=1\nMORPH_LOD_DIST=6000\nFAST_CULL=1\nFAST_SETUP=1',
     'Дополнительно можно снизить displaySize в меню настроек: разрешение '
     'рендера - самый сильный рычаг, потому что растеризация платит за каждый '
     'пиксел. Постэффекты от профиля не зависят: ими управляют родные ключи '
     'игры BLOOM, VIGNETTE в level.ini/setting.txt (DOUBLE_BRIGHT вырезан).'),
]

LOSE_TABLE = [
    ('FAST_LIGHT=1', 'Освещение отличается на 0-4 единицы из 255 (в реальной '
                     'сцене 0-1). Заметно только при покадровом сравнении.'),
    ('PERSPECTIVE_STEP=48...96', 'Текстура на длинных наклонных сегментах '
                                 '(пол, потолок) слегка «плывёт» - коррекция '
                                 'выполняется реже.'),
    ('NEAR_ONLY=1', 'Дальние объекты не обновляются: их ИИ и физика «застывают» '
                    'до приближения игрока.'),
    ('AI_LOD=1', 'Дальний NPC замечает игрока и перестраивает маршрут на один '
                 'такт реакции позже.'),
    ('MORPH_LOD=1', 'У дальних персонажей анимация переключается целыми '
                    'кадрами, без плавного смешивания.'),
    ('FAST_CULL=1', 'Ничего: отбрасывается только заведомо невидимое '
                    '(подтверждено перебором 182 187 случаев).'),
    ('FAST_SETUP=1', 'Коэффициенты тумана/подводной дымки могут отличаться на '
                     '1 из 255 (94 % совпадают точно). Заметно только при '
                     'покадровом сравнении.'),
]


# ----------------------------------------------------------------------------- 
# Стили
# -----------------------------------------------------------------------------
def make_styles():
    ss = getSampleStyleSheet()
    st = {}
    st['title'] = ParagraphStyle('title', parent=ss['Title'], fontName='FB',
                                 fontSize=19, leading=23, alignment=0,
                                 textColor=colors.HexColor('#12314f'),
                                 spaceAfter=2)
    st['subtitle'] = ParagraphStyle('subtitle', fontName='F', fontSize=10.5,
                                    leading=14, alignment=0,
                                    textColor=colors.HexColor('#4a5a6a'),
                                    spaceAfter=8)
    st['h1'] = ParagraphStyle('h1', fontName='FB', fontSize=13.5, leading=17,
                              textColor=colors.HexColor('#12314f'),
                              spaceBefore=13, spaceAfter=5, keepWithNext=1)
    st['h2'] = ParagraphStyle('h2', fontName='FB', fontSize=11, leading=14,
                              textColor=colors.HexColor('#1d4f7c'),
                              spaceBefore=9, spaceAfter=3, keepWithNext=1)
    st['body'] = ParagraphStyle('body', fontName='F', fontSize=9.3, leading=13,
                                alignment=TA_LEFT, spaceAfter=4)
    st['bullet'] = ParagraphStyle('bullet', parent=st['body'], leftIndent=9,
                                  bulletIndent=1, spaceAfter=2.5)
    st['label'] = ParagraphStyle('label', fontName='FB', fontSize=9.3,
                                 leading=13, spaceAfter=1)
    st['cell'] = ParagraphStyle('cell', fontName='F', fontSize=8.1, leading=10.4)
    st['cellb'] = ParagraphStyle('cellb', fontName='FB', fontSize=8.1,
                                 leading=10.4)
    st['head'] = ParagraphStyle('head', fontName='FB', fontSize=8.3, leading=10.6,
                                textColor=colors.white)
    st['mono'] = ParagraphStyle('mono', fontName='M', fontSize=8.2, leading=11,
                                spaceAfter=0)
    st['cellm'] = ParagraphStyle('cellm', fontName='M', fontSize=7.4,
                                 leading=9.8)
    st['keyname'] = ParagraphStyle('keyname', fontName='MB', fontSize=11.5,
                                   leading=14,
                                   textColor=colors.HexColor('#12314f'),
                                   spaceBefore=0, spaceAfter=1, keepWithNext=1)
    st['note'] = ParagraphStyle('note', fontName='F', fontSize=8.7, leading=12,
                                textColor=colors.HexColor('#333333'))
    return st


def esc(s):
    return (s.replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;'))


def mono(s):
    return '<font face="M">%s</font>' % esc(s)


def km(text, st):
    """Ячейка-ключ: моноширинный текст уменьшенного кегля."""
    return Paragraph(esc(text), st['cellm'])


# ----------------------------------------------------------------------------- 
# Документ
# -----------------------------------------------------------------------------
class Doc(BaseDocTemplate):
    def __init__(self, path, meta):
        BaseDocTemplate.__init__(self, path, pagesize=A4,
                                 leftMargin=16 * mm, rightMargin=16 * mm,
                                 topMargin=17 * mm, bottomMargin=16 * mm,
                                 title=meta['title'], author=meta['author'],
                                 subject=meta['subject'],
                                 creator='docs/make-keys-pdf.py')
        frame = Frame(self.leftMargin, self.bottomMargin, self.width, self.height,
                      id='body')
        self.addPageTemplates([PageTemplate(id='page', frames=[frame],
                                            onPage=self.decorate)])
        self.meta = meta

    def decorate(self, canv, doc):
        canv.saveState()
        w, h = A4
        # линия внизу и колонтитул
        canv.setStrokeColor(colors.HexColor('#c9d4de'))
        canv.setLineWidth(0.5)
        canv.line(16 * mm, 12.5 * mm, w - 16 * mm, 12.5 * mm)
        canv.setFont('F', 7.4)
        canv.setFillColor(colors.HexColor('#5a6a7a'))
        canv.drawString(16 * mm, 9 * mm, self.meta['footer'])
        canv.drawRightString(w - 16 * mm, 9 * mm, 'стр. %d' % doc.page)
        if doc.page > 1:
            canv.setFont('FB', 7.8)
            canv.setFillColor(colors.HexColor('#12314f'))
            canv.drawString(16 * mm, h - 12 * mm, self.meta['title'])
            canv.drawRightString(w - 16 * mm, h - 12 * mm, self.meta['branch'])
            canv.setStrokeColor(colors.HexColor('#c9d4de'))
            canv.line(16 * mm, h - 14 * mm, w - 16 * mm, h - 14 * mm)
        canv.restoreState()


def code_block(text, st):
    """Моноширинный блок с фоном (пример setting.txt). Не рвётся по страницам."""
    inner = [[Paragraph(esc(line) if line.strip() else '&nbsp;', st['mono'])]
             for line in text.split('\n')]
    t = Table(inner, colWidths=[178 * mm], splitByRow=0)
    t.setStyle(TableStyle([
        ('BACKGROUND', (0, 0), (-1, -1), colors.HexColor('#f4f7fa')),
        ('BOX', (0, 0), (-1, -1), 0.5, colors.HexColor('#c9d4de')),
        ('LEFTPADDING', (0, 0), (-1, -1), 6),
        ('RIGHTPADDING', (0, 0), (-1, -1), 6),
        ('TOPPADDING', (0, 0), (-1, -1), 1.2),
        ('BOTTOMPADDING', (0, 0), (-1, -1), 1.2),
    ]))
    return t


def note_block(text, st, bg='#fff8e1', border='#e0c46c'):
    t = Table([[Paragraph(text, st['note'])]], colWidths=[178 * mm])
    t.setStyle(TableStyle([
        ('BACKGROUND', (0, 0), (-1, -1), colors.HexColor(bg)),
        ('BOX', (0, 0), (-1, -1), 0.6, colors.HexColor(border)),
        ('LEFTPADDING', (0, 0), (-1, -1), 7),
        ('RIGHTPADDING', (0, 0), (-1, -1), 7),
        ('TOPPADDING', (0, 0), (-1, -1), 5),
        ('BOTTOMPADDING', (0, 0), (-1, -1), 5),
    ]))
    return t


def grid(rows, widths, st, header=True, align_top=True):
    t = Table(rows, colWidths=widths, repeatRows=1 if header else 0)
    style = [
        ('GRID', (0, 0), (-1, -1), 0.4, colors.HexColor('#c9d4de')),
        ('VALIGN', (0, 0), (-1, -1), 'TOP' if align_top else 'MIDDLE'),
        ('LEFTPADDING', (0, 0), (-1, -1), 4),
        ('RIGHTPADDING', (0, 0), (-1, -1), 4),
        ('TOPPADDING', (0, 0), (-1, -1), 3),
        ('BOTTOMPADDING', (0, 0), (-1, -1), 3),
        ('ROWBACKGROUNDS', (0, 1 if header else 0), (-1, -1),
         [colors.white, colors.HexColor('#f7fafc')]),
    ]
    if header:
        style += [('BACKGROUND', (0, 0), (-1, 0), colors.HexColor('#1d4f7c')),
                  ('LINEBELOW', (0, 0), (-1, 0), 0.6,
                   colors.HexColor('#12314f'))]
    t.setStyle(TableStyle(style))
    return t


def build(path):
    fonts = register_fonts()
    st = make_styles()
    repo = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

    def git(*args):
        try:
            out = subprocess.check_output(['git'] + list(args), cwd=repo,
                                          stderr=subprocess.DEVNULL)
            return out.decode('utf-8', 'replace').strip()
        except Exception:
            return ''

    branch = git('rev-parse', '--abbrev-ref', 'HEAD') or 'arena/50252a64-quantum'
    sha = git('rev-parse', '--short', 'HEAD') or '?'
    today = datetime.date.today().strftime('%d.%m.%Y')
    meta = dict(title='Quantum (J2ME): новые ключи setting.txt',
                author='Quantum engine (ARM9 optimization pack)',
                subject='Справочник по ключам настройки движка под ARM9',
                footer='Ветка %s, коммит %s, %s' % (branch, sha, today),
                branch='ветка %s / %s' % (branch, sha))

    f = []
    # ---------------- титульный блок ----------------
    f.append(Paragraph('Новые ключи <font face="M">setting.txt</font>',
                       st['title']))
    f.append(Paragraph('Справочник по настройке движка Quantum (J2ME, MIDP-2.0 / '
                       'CLDC-1.1) под аппараты класса ARM9. Ветка %s, коммит %s, '
                       'дата %s.' % (esc(branch), esc(sha), today),
                       st['subtitle']))

    f.append(note_block(
        '<b>Коротко о совместимости.</b> Формат <font face="M">setting.txt</font> '
        'не изменился: те же строки <font face="M">КЛЮЧ=ЗНАЧЕНИЕ</font>, те же '
        'комментарии <font face="M">#</font> и <font face="M">;</font>. Все '
        'перечисленные ниже ключи <b>опциональны</b> - с оригинальным '
        '<font face="M">setting.txt</font> движок ведёт себя как раньше, потому '
        'что на «быстром» профиле значения по умолчанию совпадают с прежним '
        'поведением. Форматы уровней, мешей, текстур, лайтмапов '
        '(<font face="M">lightmap.vla</font>) и записей RMS не менялись: новые '
        'ключи влияют только на то, какая ветка кода выполняется.', st))
    f.append(Spacer(1, 7))

    # ---------------- 1. Как читается файл ----------------
    f.append(Paragraph('1. Как читается setting.txt', st['h1']))
    f.append(Paragraph(
        'Файл читается один раз при старте: '
        '<font face="M">Main.init()</font> создаёт '
        '<font face="M">GameIni.createGameIniFromResource("/setting.txt", '
        'true)</font> и сразу передаёт его в '
        '<font face="M">DeviceProfile.init(settings)</font> - раньше '
        '<font face="M">defaultSettings()</font> и раньше чтения сохранённого '
        'профиля из RMS, чтобы <font face="M">setPersQ()</font> уже знал свой '
        'шаг. Разбор выполняет <font face="M">IniFile</font> '
        '(модуль UtilsText): строки режутся по <font face="M">\\n</font>, '
        'пустые пропускаются, строки, начинающиеся с <font face="M">#</font> или '
        '<font face="M">;</font>, считаются комментарием, остальные разрезаются '
        'по первому <font face="M">=</font>, ключ и значение.trim() кладутся в '
        'хеш-таблицу.', st['body']))
    rows = [[Paragraph('Правило', st['head']), Paragraph('Подробность', st['head'])]]
    for a, b in [
        ('Куда писать ключи',
         'setting.txt читается с поддержкой групп, поэтому новые ключи надо '
         'писать <b>в корне файла - до первой строки вида '
         '<font face="M">[группа]</font></b>. DeviceProfile читает их через '
         'getNoLang()/getIntNoLang(), то есть из корневой таблицы.'),
        ('Регистр и пробелы',
         'Имя ключа чувствительно к регистру (пишите заглавными, как в '
         'оригинале). Пробелы вокруг <font face="M">=</font> игнорируются: '
         'ключ и значение.trim()аются. Значение CPU_PROFILE регистр не важен '
         '(AUTO/auto/Auto равнозначны).'),
        ('Комментарии',
         '<b>Только отдельной строкой.</b> Комментарий в конце строки не '
         'поддерживается: в <font face="M">FAST_LIGHT=1 ; включить</font> '
         'значением станет строка <font face="M">1 ; включить</font>.'),
        ('Тип значения',
         'Все числовые ключи разбираются через '
         '<font face="M">Integer.parseInt()</font> без перехвата исключений, '
         'поэтому значение обязано быть целым числом. Нечисловое значение - '
         'это NumberFormatException при старте (см. предупреждение ниже).'),
        ('Неизвестные ключи',
         'Игнорируются: таблица просто не спрашивается о них. Старые ключи '
         'оригинала продолжают работать как работали.'),
        ('Отсутствие ключа',
         'Берётся значение по умолчанию из профиля устройства (колонки '
         '«ARM9» и «FAST/AUTO» в таблице ниже). Отсутствие файла или ключа - '
         'не ошибка.'),
        ('Приоритет',
         'Для ключей уровня (UPDATE_ONLY_NEAR и подобные): level.ini важнее '
         'setting.txt, setting.txt важнее профиля устройства, профиль важнее '
         'встроенного значения. Явный ключ в файле всегда перекрывает профиль.'),
        ('Когда применяется',
         'Один раз при старте. Смена значения требует перезапуска MIDlet.'),
    ]:
        rows.append([Paragraph(a, st['cellb']), Paragraph(b, st['cell'])])
    f.append(grid(rows, [38 * mm, 140 * mm], st))
    f.append(Spacer(1, 5))
    f.append(note_block(
        '<b>Осторожно: нечисловое значение роняет старт.</b> '
        '<font face="M">DeviceProfile.init()</font> не перехватывает '
        '<font face="M">NumberFormatException</font>, поэтому '
        '<font face="M">FAST_LIGHT=on</font>, <font face="M">AI_LOD=1 ; '
        'включить</font> или <font face="M">FAST_CULL=yes</font> приведут к '
        'исключению в <font face="M">Main.init()</font>. Допустимы только целые '
        'числа (для CPU_PROFILE - слова AUTO/ARM9/FAST или цифры 1/2).',
        st, bg='#fdecea', border='#d99a94'))
    f.append(Spacer(1, 4))
    f.append(KeepTogether([
        Paragraph('Рабочий пример - все новые ключи сразу (значения '
                  'профиля ARM9):', st['body']),
        code_block(
            '; --- Quantum: настройка под ARM9 (все ключи опциональны) ---\n'
        'CPU_PROFILE=AUTO\n'
        'FAST_LIGHT=1\n'
        'PERSPECTIVE_STEP=32\n'
        'NEAR_ONLY=1\n'
        'AI_LOD=1\n'
        'AI_LOD_DIST=10000\n'
        'MORPH_LOD=1\n'
        'MORPH_LOD_DIST=12000\n'
        'FAST_CULL=1', st)]))

    # ---------------- 2. Сводная таблица ----------------
    f.append(Paragraph('2. Сводная таблица ключей', st['h1']))
    f.append(Paragraph(
        'Двенадцать новых ключей. «ARM9» и «FAST/AUTO» - значения по умолчанию '
        'на соответствующем профиле; явный ключ в файле перекрывает их в обоих '
        'случаях.', st['body']))
    rows = [[Paragraph('Ключ', st['head']), Paragraph('Значения', st['head']),
             Paragraph('ARM9', st['head']), Paragraph('FAST / AUTO', st['head']),
             Paragraph('Что делает', st['head'])]]
    for k in KEYS:
        rows.append([km(k['name'], st),
                     Paragraph(esc(k['values']), st['cell']),
                     Paragraph(esc(k['arm9']), st['cellb']),
                     Paragraph(esc(k['fast']), st['cellb']),
                     Paragraph(esc(k['short']), st['cell'])])
    f.append(grid(rows, [34 * mm, 31 * mm, 20 * mm, 19 * mm, 74 * mm], st))

    # ---------------- 3. Профили ----------------
    f.append(Paragraph('3. Профиль устройства: что включает ARM9', st['h1']))
    f.append(Paragraph(
        'Профиль определяется один раз и хранится в '
        '<font face="M">DeviceProfile.cpu</font> '
        '(<font face="M">CPU_AUTO</font> = 0, <font face="M">CPU_ARM9</font> = 1, '
        '<font face="M">CPU_FAST</font> = 2); флаг '
        '<font face="M">lowEnd = (cpu == CPU_ARM9)</font> и задаёт значения по '
        'умолчанию для пяти ключей: FAST_LIGHT, NEAR_ONLY, AI_LOD, MORPH_LOD, '
        'FAST_CULL (при lowEnd - 1). На постэффекты профиль не влияет: ими '
        'управляют родные ключи игры BLOOM, VIGNETTE (DOUBLE_BRIGHT вырезан). '
        'PERSPECTIVE_STEP на ARM9 получает 32, если ключа нет в файле.',
        st['body']))
    rows = [[Paragraph('Профиль', st['head']), Paragraph('Когда выбирается',
                                                         st['head']),
             Paragraph('Что это значит', st['head'])]]
    for a, b, c in [
        ('FAST', 'Десктоп, эмулятор, Symbian/S60, UIQ; явно - CPU_PROFILE=FAST '
                 'или =2',
         'Все значения по умолчанию прежние: движок ведёт себя как оригинал. '
         'Работают только точные ускорения (раздел 6).'),
        ('ARM9', 'Семейства J2ME-аппаратов без FPU и аппаратного делителя '
                 '(Sony Ericsson DB20xx, Samsung, Motorola, LG, Siemens, Philips, '
                 'Nokia Series40 и др.), любая платформа с CLDC-1.0; явно - '
                 'CPU_PROFILE=ARM9 или =1',
         'Включаются щадящие значения по умолчанию: целочисленное освещение, без '
         'постэффектов, крупнее шаг перспективной коррекции, обновляются только '
         'ближайшие объекты, LOD для ИИ и морфинга, быстрый отсек боксов.'),
        ('AUTO', 'Платформа не распознана (значение по умолчанию)',
         'lowEnd = false, то есть поведение как на FAST: включены только точные '
         'ускорения. Это страховка - на неизвестном аппарате ничего не '
         'ухудшается.'),
    ]:
        rows.append([km(a, st), Paragraph(b, st['cell']),
                     Paragraph(c, st['cell'])])
    f.append(grid(rows, [22 * mm, 58 * mm, 98 * mm], st))

    # ---------------- 4. Подробно по ключам ----------------
    f.append(Paragraph('4. Подробно по каждому ключу', st['h1']))
    for i, k in enumerate(KEYS, 1):
        block = [Paragraph('%s' % mono(k['name']), st['keyname']),
                 Paragraph('Значения: %s&nbsp;&nbsp;|&nbsp;&nbsp; по умолчанию: '
                           'ARM9 - %s, FAST/AUTO - %s'
                           % (esc(k['values']), esc(k['arm9']), esc(k['fast'])),
                           st['cell'])]
        for label, text in (('Где применяется', k['where']),
                            ('Что ускоряет', k['speed']),
                            ('Что теряется', k['loss'])):
            block.append(Paragraph('<b>%s.</b> %s' % (label, esc(text)),
                                   st['body']))
        if k['extra']:
            block.append(Paragraph('<b>Подробности</b>', st['body']))
            for e in k['extra']:
                block.append(Paragraph(esc(e), st['bullet'], bulletText='-'))
        block.append(Spacer(1, 5))
        f.extend(block)

    f.append(PageBreak())

    # ---------------- 5. Ключи оригинала с новым default ----------------
    f.append(Paragraph('5. Ключи оригинала, у которых изменилось значение по '
                       'умолчанию', st['h1']))
    f.append(Paragraph(
        'Эти ключи существовали раньше и не переименованы. Изменилось только то '
        'значение, которое подставляется, когда ключ не задан ни в level.ini, ни '
        'в setting.txt: теперь оно берётся из профиля устройства (ключ '
        'NEAR_ONLY).', st['body']))
    rows = [[Paragraph('Ключ', st['head']), Paragraph('Где задаётся', st['head']),
             Paragraph('Раньше по умолчанию', st['head']),
             Paragraph('Теперь по умолчанию', st['head']),
             Paragraph('Смысл', st['head'])]]
    for a, b, c, d, e in CHANGED_DEFAULTS:
        rows.append([km(a, st), Paragraph(b, st['cell']),
                     km(c, st), Paragraph(esc(d), st['cell']),
                     Paragraph(esc(e), st['cell'])])
    f.append(grid(rows, [42 * mm, 25 * mm, 25 * mm, 31 * mm, 55 * mm], st))
    f.append(Spacer(1, 4))
    f.append(note_block(
        'Если в level.ini или setting.txt оригинала эти ключи уже прописаны, их '
        'значение важнее - профиль не подменяет явно заданное. Поэтому уровни, '
        'которые рассчитывали на полное обновление объектов, продолжают работать '
        'так же.', st, bg='#eef6ee', border='#9dc39d'))

    # ---------------- 6. Всегда включено ----------------
    f.append(Paragraph('6. Ускорения без ключа: включены всегда', st['h1']))
    f.append(Paragraph(
        'У этих изменений ключа нет вовсе - они либо бит-в-бит эквивалентны '
        'оригиналу, либо их погрешность ограничена и проверена перебором. '
        'Отключать их нечем и незачем: форматы данных и результаты они не '
        'меняют.', st['body']))
    rows = [[Paragraph('Что сделано', st['head']), Paragraph('Где', st['head']),
             Paragraph('Точность', st['head'])]]
    for a, b, c in ALWAYS_ON:
        rows.append([Paragraph(esc(a), st['cellb']), Paragraph(esc(b), st['cell']),
                     Paragraph(esc(c), st['cell'])])
    f.append(grid(rows, [50 * mm, 62 * mm, 66 * mm], st))

    f.append(PageBreak())

    # ---------------- 7. Пресеты ----------------
    f.append(Paragraph('7. Готовые наборы', st['h1']))
    f.append(Paragraph(
        'Каждый набор можно вставить в конец setting.txt (до первой строки с '
        '[группа], если она есть). Значения читаются при старте, поэтому после '
        'правки файл надо перезапустить MIDlet.', st['body']))
    for title, code, comment in PRESETS:
        f.append(KeepTogether([
            Paragraph(title, st['h2']),
            code_block(code, st),
            Spacer(1, 3),
            Paragraph(comment, st['body']),
            Spacer(1, 4),
        ]))

    # ---------------- 8. Что теряется ----------------
    f.append(Paragraph('8. Сводка: что именно теряется при ухудшениях', st['h1']))
    rows = [[Paragraph('Ключ', st['head']),
             Paragraph('Видимый эффект', st['head'])]]
    for a, b in LOSE_TABLE:
        rows.append([km(a, st), Paragraph(esc(b), st['cell'])])
    f.append(grid(rows, [46 * mm, 132 * mm], st))
    f.append(Spacer(1, 4))
    f.append(Paragraph(
        'Порядок включения по убыванию выгоды: сначала CPU_PROFILE=ARM9 - он '
        'включает все щадящие ветки разом, плюс снизьте displaySize в меню '
        'настроек (меньше пикселов в кадре - главный выигрыш), затем FAST_LIGHT '
        'и FAST_CULL, затем NEAR_ONLY и AI_LOD (они убирают работу по NPC - '
        'именно из-за них падал FPS в сценах с большим числом персонажей), и '
        'только потом PERSPECTIVE_STEP и MORPH_LOD. Постэффекты настраиваются '
        'отдельно родными ключами игры BLOOM/VIGNETTE.', st['body']))

    # ---------------- 9. Диагностика ----------------
    f.append(Paragraph('9. Диагностика и проверка', st['h1']))
    f.append(Paragraph(
        'При старте движок печатает итоговый профиль в '
        '<font face="M">System.out</font> - в эмуляторе (KEmulator, FreeJ2ME, '
        'SquirrelJME) это видно в консоли/логе:', st['body']))
    f.append(code_block(
        'DeviceProfile: cpu=ARM9 platform=SonyEricssonK750 fastLight=1 '
        'nearOnly=1 aiLod=1 morphLod=1 fastCull=1 persStep=32', st))
    f.append(Spacer(1, 4))
    f.append(Paragraph('Как сравнивать и чем проверять', st['h2']))
    for t in [
        'Счётчик кадров: пункт «Show QFPS» в настройках (code.utils.QFPS вызывает '
        'FPS.frame() из модуля UtilsFPS). Сравнивать надо одну и ту же сцену - '
        'желательно NPC-тяжёлую, потому что именно там был провал.',
        'Контрольный замер: прогон с набором A (побитово как оригинал) против '
        'набора C или D. Разница - это вклад именно ключей; точные ускорения '
        'из раздела 6 действуют в обоих прогонах.',
        'Если FPS не растёт, первым делом проверьте строку DeviceProfile: при '
        'cpu=AUTO все щадящие значения по умолчанию выключены, и тогда ключи надо '
        'прописать явно (набор C).',
        'Проверки эквивалентности в репозитории (чистый Python, без телефона): '
        'docs/verify/test_fastmath_java.py (0 расхождений на 6 312 931 значении), '
        'test_light.py (отклонение 3 и 4 из 255), test_room_div.py (3020 пар, 0), '
        'test_sort.py (65 случаев + 50 кадров, 0; устойчивость равных - фикс '
        'мерцания §20), test_cull.py (182 187 случаев, 0 ошибочных '
        'отсечений), test_qct.py (round-trip и загрузчик движка побитово), '
        'test_setup_math.py (div255/div50/градиенты - 0 расхождений, туман '
        '+/-1 из 255, погода Q8 - инварианты 400 кадров), '
        'test_rms_settings.py (симметрия чтения/записи настроек RMS - '
        'регрессия на вылет второго запуска), test_glass.py (стекло режима 7: '
        'таблицы преломления бит-в-бит равны умножениям, 120 864 случая), '
        'test_modes913.py (div3/div255 бит-в-бит, FPq субпиксельно - '
        '540 тыс. значений), test_ai_physics.py (физика/AI: кэш AABB и '
        'поднятые фильтры бит-в-бит - 360 тыс. сравнений).',
        'Сборка JAR и проверка препроверки: docs/j2me-build/README.md, '
        'docs/j2me-build/build-j2me.sh, docs/j2me-build/check-jar.py (732 '
        'атрибутов StackMap, 0 StackMapTable, version 48).',
        'Полное описание всех изменений: docs/ARM9.md (разделы 3.2, 7, 9.1-9.9, 11-12).',
    ]:
        f.append(Paragraph(esc(t), st['bullet'], bulletText='-'))

    f.append(Spacer(1, 6))
    f.append(note_block(
        '<b>Итог по совместимости.</b> Ни один формат файла не изменён, ни один '
        'существующий ключ не переименован и не удалён, новые ключи опциональны и '
        'читаются из того же setting.txt. Оригинальный файл настроек продолжает '
        'работать; чтобы вернуть поведение оригинала побитово, достаточно набора '
        'A из раздела 7.', st, bg='#eef6ee', border='#9dc39d'))

    doc = Doc(path, meta)
    doc.build(f)
    return path


def main(argv):
    out = argv[1] if len(argv) > 1 else os.path.join(
        os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
        'docs', 'Quantum-setting-keys.pdf')
    build(out)
    size = os.path.getsize(out)
    print('PDF: %s (%d байт, %.1f КБ)' % (out, size, size / 1024.0))
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv))
