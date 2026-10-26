#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Builds docs/Quantum_Render_Modes_Guide.pdf — full rendering keys/modes
reference for the kordos-full build (Russian, DejaVu fonts for Cyrillic)."""
import datetime
from reportlab.lib import colors
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle
from reportlab.lib.units import mm
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.platypus import (Paragraph, SimpleDocTemplate, Spacer, Table,
                                TableStyle, PageBreak)

FD = '/usr/share/fonts/truetype/dejavu'
pdfmetrics.registerFont(TTFont('DJ', FD + '/DejaVuSans.ttf'))
pdfmetrics.registerFont(TTFont('DJB', FD + '/DejaVuSans-Bold.ttf'))
pdfmetrics.registerFont(TTFont('DJM', FD + '/DejaVuSansMono.ttf'))
pdfmetrics.registerFont(TTFont('DJMB', FD + '/DejaVuSansMono-Bold.ttf'))

COMMIT = '13316b210725e77e08a52dc8debc29e0d22bd0d4'
OUT = 'docs/Quantum_Render_Modes_Guide.pdf'

H1 = ParagraphStyle('H1', fontName='DJB', fontSize=15, leading=19,
                    spaceBefore=14, spaceAfter=6, textColor=colors.HexColor('#1a3b6e'))
H2 = ParagraphStyle('H2', fontName='DJB', fontSize=11.5, leading=15,
                    spaceBefore=10, spaceAfter=4, textColor=colors.HexColor('#274f8f'))
P = ParagraphStyle('P', fontName='DJ', fontSize=9, leading=12.6,
                   spaceAfter=4, alignment=4)
M = ParagraphStyle('M', fontName='DJM', fontSize=8, leading=11,
                   spaceAfter=4, backColor=colors.HexColor('#f3f3ef'),
                   borderPadding=4, borderColor=colors.HexColor('#cccccc'),
                   borderWidth=0.5)
NOTE = ParagraphStyle('NOTE', parent=P, fontName='DJ', textColor=colors.HexColor('#7a3b10'),
                      backColor=colors.HexColor('#fdf3e7'), borderPadding=4,
                      borderColor=colors.HexColor('#e0b28a'), borderWidth=0.5)
CELL = ParagraphStyle('CELL', fontName='DJ', fontSize=7.6, leading=10)
CELLB = ParagraphStyle('CELLB', fontName='DJB', fontSize=7.8, leading=10,
                       textColor=colors.white)
CELLM = ParagraphStyle('CELLM', fontName='DJM', fontSize=7.4, leading=9.6)

def cell(t, st=CELL):
    return Paragraph(t.replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;'), st)

def table(header, rows, widths, mono_cols=()):
    data = [[cell(h, CELLB) for h in header]]
    for r in rows:
        data.append([cell(c, CELLM if i in mono_cols else CELL) for i, c in enumerate(r)])
    t = Table(data, colWidths=widths, repeatRows=1, hAlign='LEFT')
    t.setStyle(TableStyle([
        ('BACKGROUND', (0, 0), (-1, 0), colors.HexColor('#274f8f')),
        ('GRID', (0, 0), (-1, -1), 0.4, colors.HexColor('#9db2cc')),
        ('ROWBACKGROUNDS', (0, 1), (-1, -1), [colors.white, colors.HexColor('#eef2f8')]),
        ('VALIGN', (0, 0), (-1, -1), 'TOP'),
        ('LEFTPADDING', (0, 0), (-1, -1), 3), ('RIGHTPADDING', (0, 0), (-1, -1), 3),
        ('TOPPADDING', (0, 0), (-1, -1), 2), ('BOTTOMPADDING', (0, 0), (-1, -1), 2),
    ]))
    return t

S = []  # story

def h1(t): S.append(Paragraph(t, H1))
def h2(t): S.append(Paragraph(t, H2))
def p(t): S.append(Paragraph(t, P))
def m(t): S.append(Paragraph(t, M))
def note(t): S.append(Paragraph(t, NOTE))
def sp(h=3): S.append(Spacer(1, h))
def br(): S.append(PageBreak())

# ---------------------------------------------------------------- title
S.append(Spacer(1, 30 * mm))
S.append(Paragraph('Quantum — сборка Kordos Full',
                   ParagraphStyle('T0', fontName='DJB', fontSize=24, leading=30,
                                  alignment=1, textColor=colors.HexColor('#1a3b6e'))))
S.append(Spacer(1, 8 * mm))
S.append(Paragraph('Полная документация по ключам конфигурации<br/>и режимам рендеринга',
                   ParagraphStyle('T1', fontName='DJB', fontSize=15, leading=21,
                                  alignment=1, textColor=colors.HexColor('#274f8f'))))
S.append(Spacer(1, 14 * mm))
S.append(Paragraph(
    f'Ветка: arena/b40c445d-quantum @ {COMMIT[:10]}<br/>'
    f'Дата: {datetime.date(2026, 10, 26).strftime("%d.%m.%Y")}<br/>'
    'Платформа: CLDC 1.1 / MIDP 2.0 / M3G (JSR-184) 1.0<br/>'
    'Сборка: tools/kordos-full/build_kordos_full.py (reproducible JAR/JAD)',
    ParagraphStyle('T2', fontName='DJ', fontSize=10, leading=16, alignment=1)))
br()

# ---------------------------------------------------------------- 0 intro
h1('0. О документе')
p('Документ описывает всё, что управляет картинкой в сборке <b>Kordos Full</b>: '
  'режимы рендера (<b>drawmode 0–13</b>), синтаксис текстурных строк <b>WTEX</b>, '
  'ключи рендеринга в <b>setting.txt</b> и <b>level*.txt</b>, дальности прорисовки, '
  'персистентность настроек в RMS и известные особенности. Геймплейные ключи '
  '(NPC, оружие, магазин) документом не покрываются.')
p('Обозначения: <b>дефолт</b> — значение, зашитое в код; <b>overlay</b> — текущее '
  'значение в kordos-full (tools/kordos-full/overlay). Сборка читает ключи так: '
  'сначала level-файл, потом setting.txt, потом дефолт.')

# ---------------------------------------------------------------- 1 backends
h1('1. Бэкенды и конвейер кадра')
p('<b>M3G (JSR-184, нативный)</b> — основной путь сборки: мир идёт через '
  'M3GSceneRenderer в аппаратный рендер устройства. <b>CPU (софтверный)</b> — '
  'оригинальный сканлайн-растеризатор (TexturingAffine/TexturingPers), остаётся '
  'фолбэком и эталоном семантики.')
p('Порядок кадра на M3G: CPU-фон/скайбокс → попытка lit-прохода для полигонов '
  'режима 6 → упакованный нативный проход всех остальных полигонов → эффекты '
  '(вспышка, погода) → презентация (учитывая M3G_SCREEN_SCALE).')
note('Связка режимов и света: аппаратный свет (M3G lit pass) обслуживает '
     'ТОЛЬКО текстуры с режимом 6. Глобального ключа включения больше нет — '
     'ключ M3G_LIT вырезан (commit 13316b2).')
h2('1.1. Туман и fogQ')
p('Качество тумана задаёт главное меню (0=выкл, 1=LQ, 2=HQ), в коде — '
  'Main.fogQ. Режимы с туманом (1, 3, 6-на-CPU, 9, 12, 13) при FOG=0 '
  'деградируют к режиму 0 (чистая аффина). На M3G туманные режимы 3/6 '
  'используют линейный native Fog, режим 12 — экспоненциальный.')

# ---------------------------------------------------------------- 2 modes
h1('2. Режимы рендера (drawmode 0–13)')
p('Режим задаётся вторым полем WTEX-строки (см. §3) или глобальным дефолтом '
  'DMODE в level-файле → DirectX7.standartDrawmode. В таблице: CPU — смысл в '
  'софтверном растеризаторе; M3G — отображение на нативный путь.')
rows = [
    ('0', 'plain affine', 'Чистая перспективная/аффинная текстура, без тумана',
     'REPLACE (непрозрач.) + cutout-порог', '—'),
    ('1', 'affine + fog', 'Аффина + туман (при fogQ=0 → мод 0)',
     'ALPHA-смешивание (ALPHA_TEXTURE)', 'линейный'),
    ('2', 'additive', 'Аддитивное сложение (огонь, свечение)',
     'ALPHA_ADD + ADD_TEXTURE', '—'),
    ('3', 'fog LQ/HQ', 'Аффина + туман, варианты качества',
     'нативный линейный Fog; quads бьются по диагонали', 'линейный'),
    ('4', 'alpha mix', 'Текстура с alphaMixing',
     'ALPHA только если raw.alphaMixing, иначе REPLACE', '—'),
    ('5', 'blend color', 'Смешение с внешним цветом, вершинная яркость',
     'FUNC_BLEND + blendColor', '—'),
    ('6', 'M3G light ON (new!)', 'CPU-фолбэк: старый туманный мод (rz/fDist)',
     'Аппаратный lit-проход; при поломке pass → мод 0', '—'),
    ('7', 'glass', 'paint_glass: блик (col−0x808080)·scale>>24 поверх',
     'MODULATE (блик не переносится; см. §9)', '—'),
    ('8', 'fill', 'Плоская заливка, узор текстуры игнорируется',
     'близко к REPLACE; lit-проход отвергает', '—'),
    ('9', 'baked light', 'Лайтмап, запечённый в вершинах (+ туман)',
     'static-lightmap: вершинные цвета, без дистант-тумана', 'запечён'),
    ('10', 'dither grad', 'Дизер-градиент прозрачности по вершинным альфам',
     'MODULATE_X2; lit-проход отвергает', '—'),
    ('11', 'water fade', 'Альфа из rz/waterDistance → дизер-градиент',
     'FUNC_DECAL (+Mode11Decal-обёртка)', 'по воде'),
    ('12', 'exp fog', 'Альфа из rz/fDist → дизер-градиент',
     'нативный экспоненциальный Fog; lit отвергает', 'экспоненц.'),
    ('13', 'vertex RGB', 'Вершинный цвет RGB в углах (LQ усредняет, HQ — точный)',
     'static-lightmap: вершинные цвета, без дистант-тумана', 'запечён'),
]
S.append(table(['№', 'Имя', 'CPU — семантика', 'M3G — отображение', 'Туман'],
               rows, [10 * mm, 26 * mm, 62 * mm, 62 * mm, 16 * mm], mono_cols=(0,)))
sp()
h2('2.1. Мод 6 — включатель аппаратного света')
p('Раньше мод 6 был «туман с затуханием по дистанции». С commit 13316b2 на M3G он '
  'означает: «полигон идёт в true-3D lit-проход» (ambient + опционально '
  'directional + omni-вспышка оружия). Если lit-проход отсутствует/сломан на '
  'устройстве — полигон рисуется как мод 0 (плоская текстура), туманной '
  'семантики на M3G больше нет. CPU-рендер (не используется в сборке) оставляет '
  'старое поведение как фолбэк.')
p('В kordos-full: деревня стоит на /maps/kordos/map-selo1.png,6. Чтобы '
  'завести любую другую текстуру на аппаратный свет — добавьте ,6 в её '
  'WTEX-строку.')
h2('2.2. Мод 7 — стекло')
p('CPU: screen-space шейдер paint_glass — блик: на каждый пиксель '
  '(col−0x808080)·scale>>24 накладывается поверх текстуры (scale зависит от '
  'разрешения экрана). M3G не имеет такого примитива: полигон рисуется '
  'MODULATE — блик теряется. Река использует ,7,watu0.75: скролл работает на '
  'обоих путях; вариант апгрейда вида — MODULATE_X2 (см. §9).')
h2('2.3. Моды 9/13 — запечённый свет')
p('Лайтмап уровня (.vla, ключ LIGHTMAP) записан в вершины полигонов. На M3G '
  'это static-lightmap pass: вершинные цвета выставляются напрямую, дистантный '
  'туман не применяется. M3G_LIT_NOVCOL=1 глобально обнуляет запечённый цвет '
  '(белые вершины) — отладка аппаратного света.')
br()

# ---------------------------------------------------------------- 3 wtex
h1('3. WTEX: синтаксис текстурных строк')
p('Блок [WTEX_BEGIN]…[WTEX_END] в level-файле перечисляет текстуры мира '
  '(по строке на текстуру). Поля разделяются запятой:')
m('путь[:кадр2:кадр3…] , режим|std , addsz , persp , shadow , collision , wat-токен')
rows = [
    ('0', 'путь', '/maps/kordos/map-selo1.png',
     'Файл в JAR. Форма speed:f1:f2:… включает покадровый флипбук (кадры грузятся '
     'списком, переключение по animation_speed)'),
    ('1', 'режим | std', '6', 'Число 0–13 → drawmode. Ключевое слово std = '
     '«взять DMODE уровня» (поле пропускается)'),
    ('2', 'addsz', '-20000', 'Сдвиг/прибавка к размеру (legacy)'),
    ('3', 'persp', '1', '1 = перспективная коррекция текстуры, 0 = аффина'),
    ('4', 'shadow', '0', '1 = полигон отбрасывает тень'),
    ('5', 'collision', '0', '1 = полигоны текстуры участвуют в коллизиях'),
    ('любая', 'wat / watV / watU + speed', 'watu0.75',
     'UV-скролл «воды». wat=watV (скролл вдоль V), watU — вдоль U. '
     'Скорость: обороты текстуры/сек (0.75 = полный цикл за ~1.33 с), '
     'знак задаёт направление'),
]
S.append(table(['#', 'поле', 'пример', 'смысл'], rows,
               [12 * mm, 26 * mm, 34 * mm, 104 * mm], mono_cols=(2,)))
sp()
h2('3.1. Правила wat-токенов')
p('Токены wat* <b>вырезаются из строки до разбора позиционных полей</b> — они могут '
  'стоять в любой позиции, нумерация остальных полей не сдвигается. Применение '
  'происходит ПОСЛЕ загрузки текстуры (иначе placeholder-объект затирал бы '
  'флаги — баг, закрытый commit 0f1c414). На лит-пути скроллится закреплённый '
  'базовый кадр (animMIP[0]), на нативном пути — per-poly setTranslation. В '
  'текущей сборке река:')
m('/maps/kordos/river0.bmp,7,watu0.75')
p('Одна текстура, без цепочки кадров и без скоростного префикса в начале. '
  'Модель .3d для скролла не редактируется.')
note('Урок истории: магические строковые токены в WTEX всегда изымаются ДО '
     'позиционных parseInt — иначе NumberFormatException при загрузке уровня.')

# ---------------------------------------------------------------- 4 setting keys
h1('4. Ключи setting.txt — рендеринг')
h2('4.1. Viewport и качество')
rows = [
    ('M3G_PERSPECTIVE', '0/1', '1', 'on', 'Перспективная коррекция в нативном пути; тумблер — последний ряд меню (только M3G)'),
    ('M3G_SCREEN_SCALE', '25–100', '100', '100', 'Масштаб viewport мира, %; <100 = малая поверхность + upscale (экономия fill-rate)'),
    ('RESIZE_WEAPONS', '0/1', '0', '—', 'Подгон спрайтов оружия под разрешение'),
    ('WEAPON_SWAY', '0/1', '1', '0', 'Покачивание оружия при ходьбе (в сборке выключено)'),
]
S.append(table(['ключ', 'тип', 'дефолт', 'overlay', 'смысл'], rows,
               [36 * mm, 14 * mm, 14 * mm, 14 * mm, 98 * mm], mono_cols=(0,)))
h2('4.2. Аппаратный свет (действует только на текстуры режима 6)')
rows = [
    ('M3G_LIT_AMBIENT', '0–255', '32', '64', 'Интенсивность ambient-света'),
    ('M3G_LIT_AMBCOL', 'R,G,B', '255,255,255', '255,255,255', 'Цвет ambient'),
    ('M3G_LIT_DIR', '0/1', '150', '0', 'Directional «солнце» (0 = выкл)'),
    ('M3G_LIT_DIRCOL', 'R,G,B', '255,255,255', '255,255,255', 'Цвет directional'),
    ('M3G_LIT_AMBPULSE', '0–255', '64', '0', 'Пульс ambient при выстреле (диагностика omni)'),
    ('M3G_LIT_DIST', 'ед.мира', '0', '20000', 'Жёсткий отсев lit-полигонов по дистанции (0=выкл)'),
    ('M3G_LIT_DYN', '0/1', '0', '0', 'Динамический отсев под целевой FPS (M3G_LIT_FPS)'),
    ('M3G_LIT_FPS', '1+', '20', '20', 'Целевой FPS для DYN-режима'),
    ('M3G_LIT_STATIC', '0/1', '0', '1', 'Статический DIST-бюджет (без пересчётов)'),
    ('M3G_LIT_FAR', 'ед.мира', '600000', '40000', 'Дальняя плоскость камеры lit-прохода'),
    ('M3G_LIT_NEAR', 'ед.мира', '-1', '—', 'Ближняя плоскость (-1 = авто)'),
    ('M3G_LIT_MINPX', 'пикс', '0', '0', 'Отсев lit-полигонов мельче N экранных пикселей'),
    ('M3G_LIT_FLAT', '0/1', '0', '1', 'Плоское (flat) освещение без запечённых цветов'),
    ('M3G_LIT_NOVCOL', '0/1', '0', '0', '1 = белые вершины (игнор. лайтмап); ряд «Baked light» в меню'),
    ('M3G_LIT_FLIP', '0/1', '0', '1', 'Инверсия winding (правило отсечения граней)'),
    ('M3G_LIT_CULL', '0/1', '1', '0', 'Отсечение задних граней (0 = CULL_NONE для lit)'),
    ('M3G_LIT_MIP', '0/1', '0', '1', 'Мип-уровень lit-текстур; ряд «HW mip» в меню'),
    ('M3G_LIT_SELFTEST', '0–3', '0', '3', 'Самотест pass при старте (уровень отладки)'),
    ('M3G_LIT_DEBUG', '0+', '0', '0', 'Отладочный вывод статистики pass'),
    ('M3G_LIT_NORMALVIZ', '0/1', '0', '0', 'Визуализация нормалей (диагностика)'),
]
S.append(table(['ключ', 'тип', 'дефолт', 'overlay', 'смысл'], rows,
               [38 * mm, 14 * mm, 16 * mm, 16 * mm, 92 * mm], mono_cols=(0,)))
h2('4.3. Omni-вспышка оружия')
rows = [
    ('M3G_OMNI_FLASH', '0–255', '204', '204', 'Яркость вспышки'),
    ('M3G_OMNI_BASE', '0–255', '0', '0', 'Постоянная подсветка оружия'),
    ('M3G_OMNI_COL', 'R,G,B', '255,255,255', '255,255,255', 'Цвет вспышки'),
    ('M3G_OMNI_HALFX', 'ед.мира', '4000', '400', 'Полудистанция затухания (=400: узкое пятно света)'),
    ('M3G_OMNI_X/Y/Z', 'ед.мира', '8/-6/-45', '8/-6/-45', 'Смещение источника от камеры'),
    ('M3G_OMNI_CULL', 'ед.мира', '0', '8000', 'Отсев полигонов дальше N во время вспышки'),
]
S.append(table(['ключ', 'тип', 'дефолт', 'overlay', 'смысл'], rows,
               [38 * mm, 14 * mm, 16 * mm, 16 * mm, 92 * mm], mono_cols=(0,)))
h2('4.4. Вырезанные ключи (игнорируются/не существуют)')
rows = [
    ('M3G_LIT', 'Мастер-ключ аппаратного света',
     'Заменён пер-текстурным режимом 6 (commit 13316b2)'),
    ('WALK_SOUND / WALK_SOUND_i', 'Звуки шагов', 'Код проигрывания и загрузки вырезан '
     '(cf13414); wav-файлы исключены из JAR (4556c39)'),
    ('JUMP_SOUND / JUMP_SOUND_i', 'Звук прыжка', 'То же, что и шаги'),
    ('(меню) FOOTSTEPS', 'Громкость шагов', 'Ряд удалён из настроек; RMS-слот '
     'сохранён для совместимости старых сейвов (4556c39)'),
]
S.append(table(['ключ', 'что было', 'статус'], rows,
               [42 * mm, 38 * mm, 96 * mm], mono_cols=(0,)))
br()

# ---------------------------------------------------------------- 5 level keys
h1('5. Ключи level*.txt — рендеринг')
rows = [
    ('WORLD_MODEL', 'путь', '/maps/kordos/kordos.3d', 'Модель мира'),
    ('WORLD_SCALE', 'число', '6 (level) / 5 (setting)', 'Масштаб мира'),
    ('LIGHTMAP', 'путь', '/lightmap/kordod.vla', 'Запечённый вершинный лайтмап (режимы 9/13)'),
    ('DMODE', '0–13', '9', 'Глобальный drawmode по умолчанию для текстур без явного режима (WTEX «std»)'),
    ('DMODE_11_DIST', 'ед.мира', '10000', 'waterDistance для мода 11 (затухание воды)'),
    ('DIST', 'ед.мира', 'MAX_VALUE', '68500', 'Глобальный потолок дальности (DirectX7.setDrDist)'),
    ('DRAW_MODES', 'текст', '—', 'Пер-объектная подмена режимов'),
    ('MAP_ADDSZ', 'число', '—', 'Глобальный addsz мира'),
    ('FLOOR_OFFSETSZ', 'число', '0', 'Сдвиг пола'),
    ('SKYBOX_MODEL / SKYBOX_TEXTURE', 'пути', 'skybox5.3d / sus.png', 'Небо'),
    ('WEATHER', 'имя', 'RAIN', 'Погодный пресет'),
    ('WEATHER_PARTICLES_COUNT', 'число', '170', '8', 'Число частиц погоды'),
    ('WEATHER_PARTICLES_SIZE', 'число', '22 (дождь) / 4', '7', 'Размер частиц'),
    ('WEATHER_COLOR_NEAR / _FAR', 'цвет', '—', 'Цвет частиц вблизи/вдали'),
    ('WEATHER_LIGHTING', '—', '—', 'Освещение погодных частиц'),
    ('LIGHT_X / LIGHT_Y / LIGHT_Z', 'ед.мира', '-4096 / 4096 / 4096', 'Вектор запечённого света (бейкер LightMapper)'),
    ('AO_DISTANCE / AO_INTENSITY', 'числа', 'LightMapper.*', 'Ambient occlusion бейкера лайтмапа'),
    ('GI_RAYS / GI_FALLOFF / GI_INTENSITY / GI_LUM_FROM_TEXTURES', '—', 'LightMapper.*', 'Параметры GI-бейка лайтмапа'),
]
S.append(table(['ключ', 'тип', 'дефолт', 'overlay', 'смысл'], rows,
               [46 * mm, 13 * mm, 30 * mm, 30 * mm, 57 * mm], mono_cols=(0,)))
sp()
note('Полный список геймплейных ключей уровня (NPC, зомби, спавнеры, лифты, '
     'объекты) см. grep: getInt/getString в src/code/Gameplay/LevelLoader.java — '
     'в документе только рендер-блок.')

# ---------------------------------------------------------------- 6 distance
h1('6. Дальность прорисовки — иерархия')
rows = [
    ('DIST', 'level*.txt', '68500', 'Потолок всей сцены (DirectX7.fDist)'),
    ('M3G_LIT_DIST', 'setting.txt', '20000', 'Жёсткий отсезв lit-полигонов (режим 6): дальше — НЕ рисуются вообще'),
    ('M3G_LIT_FAR', 'setting.txt', '40000', 'Дальняя плоскость камеры lit-прохода'),
    ('M3G_OMNI_CULL', 'setting.txt', '8000', 'Доп. отсев при оружейной вспышке'),
    ('DELETE_IN_DISTANCE', 'level*.txt', '0', 'Удаление дальних динамических объектов'),
]
S.append(table(['ключ', 'где', 'overlay', 'действие'], rows,
               [34 * mm, 24 * mm, 18 * mm, 100 * mm], mono_cols=(0,)))
sp()
p('<b>Эффективная дальность мира = min(DIST, M3G_LIT_DIST)</b> для полигонов '
  'режима 6: сейчас 20000. Деревня за 20000 попадает под отсев lit-прохода; её '
  'дальнюю оболочку рисует forestOrigy (std → режим 9, без участия lit). '
  'Главный регулятор «как далеко видно деревню» — M3G_LIT_DIST.')

# ---------------------------------------------------------------- 7 menu
h1('7. Меню настроек (рендер-строки)')
rows = [
    ('Viewport %', 'M3G_SCREEN_SCALE', '25–100, шаг 25; RMS fx[2]'),
    ('HW mip', 'M3G_LIT_MIP', 'ON/OFF; RMS fx[1]'),
    ('Baked light', 'M3G_LIT_NOVCOL', 'ON/OFF; RMS fx[3]'),
    ('Omni flash', 'm3gWorldLighting', 'ON=1/OFF=2 (1: вспышка + ambient; '
     '2: только ambient). RMS fx[0] читается? — нет, слот legacy'),
    ('Frameskip', 'Main.frameskip', 'пропуск кадров'),
    ('Perspective', 'M3G_PERSPECTIVE', 'только M3G, последний ряд (выбор)'),
]
S.append(table(['строка меню', 'поле/ключ', 'диапазон / заметки'], rows,
               [30 * mm, 40 * mm, 106 * mm], mono_cols=(1,)))

# ---------------------------------------------------------------- 8 rms
h1('8. Персистентность (RMS)')
p('Два стора, раскладка фиксирована — новые сборки обязаны читать старые записи:')
h2('8.1. Основной сейв')
m('int music, int sounds, int footsteps(legacy → читаем/пишем константу 100),\n'
  'int displaySize, int availableLevel, int persQ, int fogQ, int pixelsQ,\n'
  'bool frameskip, bool corpses, bool blood, …')
h2('8.2. Quantum.M3GFx (byte[4])')
m('[0] legacy HW-light (при чтении игнорируется — мод 6 владеет светом)\n'
  '[1] M3G_LIT_MIP   [2] M3G_SCREEN_SCALE   [3] M3G_LIT_NOVCOL')

# ---------------------------------------------------------------- 9 known issues
h1('9. Известные особенности')
rows = [
    ('Мод 7 на M3G', 'MODULATE вместо стеклянного блика paint_glass',
     'Вести: 1) MODULATE_X2 (центр 0x808080 = нейтраль, светлое светлеет — '
     'ближайшее «стекло»), 2) эмуляция блика вершинными цветами (дорого), '
     '3) перевод воды на мод 11 (DECAL+water fade) — меняет визуал'),
    ('lit-отсев', 'Полигоны режима 6 дальше M3G_LIT_DIST исчезают целиком',
     'Это by design: popping гасится дальней std-оболочкой forestOrigy'),
    ('wat-скролл', 'Без флипбук-цепочки: одна базовая текстура плюс UV-сдвиг',
     'Цепочка frames убрана из реки по требованию (bdec789)'),
    ('WTEX', 'Строго 3 строки [WTEX_BEGIN]…[WTEX_END]; 5-строчный формат не поддержан',
     'AnimationTrack UV на M3G не работает — скролл только через wat'),
]
S.append(table(['тема', 'суть', 'статус / вариант'], rows,
               [24 * mm, 70 * mm, 82 * mm]))

# -------------------------------------------------------------- appendix
h1('Приложение А. Текущий overlay — рендер-блоки')
h2('level1.txt')
m('[WTEX_BEGIN]\n/maps/kordos/forestOrigy.png,std,-20000\n'
  '/maps/kordos/map-selo1.png,6\n/maps/kordos/river0.bmp,7,watu0.75\n[WTEX_END]\n\n'
  'DIST=68500   DMODE=9   WORLD_SCALE=6   LIGHTMAP=/lightmap/kordod.vla')
h2('setting.txt (свет)')
m('M3G_LIT_AMBIENT=64  M3G_LIT_DIR=0  M3G_LIT_AMBCOL=255,255,255\n'
  'M3G_OMNI_FLASH=204  M3G_OMNI_HALFX=400  M3G_OMNI_X/Y/Z=8/-6/-45\n'
  'M3G_OMNI_CULL=8000  M3G_LIT_FAR=40000  M3G_LIT_DIST=20000\n'
  'M3G_LIT_FLAT=1  M3G_LIT_FLIP=1  M3G_LIT_CULL=0  M3G_LIT_STATIC=1\n'
  'M3G_LIT_AMBPULSE=0  M3G_SCREEN_SCALE=100  WEAPON_SWAY=0\n'
  '(мастер-ключ M3G_LIT удалён)')

doc = SimpleDocTemplate(OUT, pagesize=A4,
                        leftMargin=16 * mm, rightMargin=16 * mm,
                        topMargin=16 * mm, bottomMargin=14 * mm,
                        title='Quantum Kordos Full — ключи и режимы рендеринга',
                        author='Arena Agent')

def footer(canvas, d):
    canvas.saveState()
    canvas.setFont('DJ', 7)
    canvas.setFillColor(colors.HexColor('#666666'))
    canvas.drawString(16 * mm, 8 * mm, 'Quantum Kordos Full — рендер-гид @ ' + COMMIT[:10])
    canvas.drawRightString(A4[0] - 16 * mm, 8 * mm, 'стр. %d' % canvas.getPageNumber())
    canvas.restoreState()

doc.build(S, onFirstPage=footer, onLaterPages=footer)
print('PDF:', OUT)
