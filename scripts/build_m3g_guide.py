#!/usr/bin/env python3
"""Render the Russian effect guide as a searchable PDF with embedded fonts.

Optional dependencies: reportlab, markdown, beautifulsoup4.
Example: python scripts/build_m3g_guide.py --fonts-dir /path/to/dejavu
"""
import argparse
from html import escape
from pathlib import Path
import subprocess
from urllib.parse import urljoin

import markdown
from bs4 import BeautifulSoup, NavigableString
from reportlab.lib import colors
from reportlab.lib.enums import TA_LEFT
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle
from reportlab.lib.units import mm
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.platypus import (
    SimpleDocTemplate, Paragraph, Spacer, PageBreak, LongTable, Table, TableStyle,
    Preformatted,
)
from reportlab.platypus.tableofcontents import TableOfContents

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'docs/m3g-modes-guide.md'
INK = '#173047'
ACCENT = '#087e8b'
LINK = '#126c9c'


def inline(node, link_base):
    if isinstance(node, NavigableString):
        return escape(str(node))
    text = ''.join(inline(child, link_base) for child in node.children)
    if node.name in ('strong', 'b'):
        return '<b>' + text + '</b>'
    if node.name in ('em', 'i'):
        return '<i>' + text + '</i>'
    if node.name == 'code':
        return '<font face="GuideMono" size="8">' + text + '</font>'
    if node.name == 'a':
        href = urljoin(link_base, node.get('href', ''))
        if href.startswith(('https://', 'http://')):
            return '<link color="%s" href="%s">%s</link>' % (
                LINK, escape(href, quote=True), text)
    if node.name == 'br':
        return '<br/>'
    return text


class GuideDocument(SimpleDocTemplate):
    """Use the final layout for both numbered contents and PDF bookmarks."""
    def afterFlowable(self, flowable):
        heading = getattr(flowable, 'guide_heading', None)
        if heading is None:
            return
        level, key, text = heading
        self.canv.bookmarkPage(key)
        self.canv.addOutlineEntry(text, key, level=level, closed=False)
        self.notify('TOCEntry', (level, text, self.page, key))


def table_widths(headers, width):
    count, first = len(headers), headers[0]
    if first == '№':
        ratios = [0.055, 0.60, 0.345]
    elif first == 'Где указан режим':
        ratios = [0.36, 0.27, 0.37]
    elif first == 'Цвет':
        ratios = [0.26, 0.20, 0.54]
    elif first == 'Ключ' and count == 3:
        ratios = [0.33, 0.28, 0.39]
    elif first in ('Skybox.mode', 'COLOR'):
        ratios = [0.17, 0.83]
    elif first == 'Счётчик':
        ratios = [0.12, 0.88]
    elif first == 'Состояние':
        ratios = [0.24, 0.25, 0.51]
    elif first == 'Внутренний номер':
        ratios = [0.14, 0.31, 0.38, 0.17]
    elif count == 4:
        ratios = [0.21, 0.35, 0.22, 0.22]
    elif count == 2:
        ratios = [0.36, 0.64]
    else:
        ratios = [1.0 / count] * count
    return [width * ratio for ratio in ratios]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--fonts-dir', type=Path,
                        default=Path('/usr/share/fonts/truetype/dejavu'))
    parser.add_argument('--output', type=Path,
                        default=ROOT / 'downloads/Quantum_M3G_Render_Modes_RU.pdf')
    parser.add_argument('--link-base', help='Absolute URL of the source docs directory')
    args = parser.parse_args()
    if args.link_base:
        link_base = args.link_base.rstrip('/') + '/'
    else:
        # The linked migration notes are newer than the engine being documented.
        # Pin supporting documentation to the source guide revision, not b0ee49b.
        revision = subprocess.check_output(
            ['git', 'log', '-1', '--format=%H', '--', str(SOURCE.relative_to(ROOT))],
            cwd=str(ROOT), text=True).strip()
        link_base = 'https://github.com/Xattabchor/Quantum/blob/%s/docs/' % revision

    for name, filename in (
        ('Guide', 'DejaVuSans.ttf'), ('GuideBold', 'DejaVuSans-Bold.ttf'),
        ('GuideItalic', 'DejaVuSans-Oblique.ttf'), ('GuideMono', 'DejaVuSansMono.ttf'),
    ):
        font = args.fonts_dir / filename
        if name == 'GuideItalic' and not font.exists():
            font = args.fonts_dir / 'DejaVuSans.ttf'
        pdfmetrics.registerFont(TTFont(name, str(font)))
    pdfmetrics.registerFontFamily('Guide', normal='Guide', bold='GuideBold',
                                 italic='GuideItalic', boldItalic='GuideBold')
    pdfmetrics.registerFontFamily('GuideMono', normal='GuideMono', bold='GuideMono',
                                 italic='GuideMono', boldItalic='GuideMono')
    body = ParagraphStyle('Body', fontName='Guide', fontSize=9, leading=13.5,
                          spaceAfter=7, textColor=colors.HexColor(INK),
                          alignment=TA_LEFT, splitLongWords=True)
    small = ParagraphStyle('Small', parent=body, fontSize=8, leading=11.5, spaceAfter=0)
    head_cell = ParagraphStyle('HeadCell', parent=small, fontName='GuideBold',
                               textColor=colors.white)
    h1 = ParagraphStyle('Title', parent=body, fontName='GuideBold', fontSize=34,
                        leading=42, spaceAfter=16)
    h2 = ParagraphStyle('H2', parent=body, fontName='GuideBold', fontSize=14,
                        leading=19, spaceBefore=16, spaceAfter=9, keepWithNext=True)
    h3 = ParagraphStyle('H3', parent=h2, fontSize=11, leading=15, spaceBefore=10,
                        textColor=colors.HexColor(ACCENT))
    code = ParagraphStyle('Code', parent=body, fontName='GuideMono', fontSize=8,
                          leading=11.5, backColor=colors.HexColor('#edf4f7'),
                          borderPadding=8, spaceBefore=6, spaceAfter=13)
    bullet_style = ParagraphStyle('Bullet', parent=body, leftIndent=11,
                                  firstLineIndent=-10)
    soup = BeautifulSoup(markdown.markdown(
        SOURCE.read_text(encoding='utf-8'), extensions=['tables', 'fenced_code']),
        'html.parser')
    width = A4[0] - 32 * mm

    label = ParagraphStyle('Label', parent=h3, fontSize=11, leading=15)
    cover_subtitle = ParagraphStyle('CoverSubtitle', parent=body, fontSize=14,
                                    leading=21, spaceAfter=20)
    story = [Spacer(1, 18 * mm), Paragraph('QUANTUM  /  ENGINE REFERENCE', label),
             Spacer(1, 12 * mm), Paragraph('Справочник<br/>эффектов', h1),
             Paragraph('Версия <b>b0ee49b</b><br/>Software + M3G', cover_subtitle),
             Paragraph('Материалы 0–13 · спрайты · небо и погода<br/>'
                       'Туман · экранные фильтры · конфигурация', body),
             Spacer(1, 13 * mm)]
    cards = [[Paragraph('<b>05 / BLEND</b><br/>Цвет уровня', body),
              Paragraph('<b>11 / DECAL</b><br/>Белая подложка', body),
              Paragraph('<b>12 / EXP FOG</b><br/>Плотность тумана', body)]]
    card_table = Table(cards, colWidths=[width / 3] * 3)
    card_table.setStyle(TableStyle([
        ('BACKGROUND', (0, 0), (-1, -1), colors.HexColor('#eaf3f6')),
        ('VALIGN', (0, 0), (-1, -1), 'TOP'),
        ('TOPPADDING', (0, 0), (-1, -1), 12),
        ('BOTTOMPADDING', (0, 0), (-1, -1), 9),
        ('LEFTPADDING', (0, 0), (-1, -1), 12),
    ]))
    story.extend([card_table, Spacer(1, 16 * mm),
                  Paragraph('Двухрендерная версия до переноса M3G-only. '
                            'Новые depth bands в этот справочник не входят.', body),
                  Paragraph('Редакция от 18 октября 2026 года<br/>'
                            'Полный текст обновлённого справочника · русский язык', small),
                  PageBreak(), Paragraph('Содержание', h2)])
    toc = TableOfContents()
    toc.levelStyles = [
        ParagraphStyle('Contents0', parent=body, fontSize=10, leading=15,
                       spaceBefore=7, spaceAfter=3),
        ParagraphStyle('Contents1', parent=body, fontSize=8.5, leading=12,
                       leftIndent=14, firstLineIndent=0, spaceAfter=2),
    ]
    toc.dotsMinLevel = 0
    story.extend([toc, Spacer(1, 12 * mm),
                  Paragraph('Навигация: нажмите на пункт оглавления или используйте '
                            'закладки PDF. Ссылки на исходники, сборки и подробные '
                            'отчёты открываются в браузере.', small), PageBreak()])
    section = 0
    for node in soup.children:
        if isinstance(node, NavigableString) or node.name == 'h1':
            continue
        if node.name in ('h2', 'h3'):
            level = 0 if node.name == 'h2' else 1
            text = inline(node, link_base)
            para = Paragraph(text, h2 if level == 0 else h3)
            para.guide_heading = (level, 'section-%d' % section, node.get_text())
            story.append(para)
            section += 1
        elif node.name == 'p':
            story.append(Paragraph(inline(node, link_base), body))
        elif node.name in ('ul', 'ol'):
            for i, item in enumerate(node.find_all('li', recursive=False)):
                bullet = str(i + 1) + '.' if node.name == 'ol' else '•'
                story.append(Paragraph(bullet + ' ' + inline(item, link_base), bullet_style))
        elif node.name == 'pre':
            story.append(Preformatted(node.get_text().rstrip('\n'), code,
                                     maxLineLength=100, splitChars=' ,'))
        elif node.name == 'table':
            source_rows = [row.find_all(['td', 'th'], recursive=False)
                           for row in node.find_all('tr')]
            rows = [[Paragraph(inline(cell, link_base), head_cell if r == 0 else small)
                     for cell in row] for r, row in enumerate(source_rows)]
            headers = [cell.get_text() for cell in source_rows[0]]
            table = LongTable(rows, colWidths=table_widths(headers, width),
                              repeatRows=1, hAlign='LEFT')
            table.setStyle(TableStyle([
                ('BACKGROUND', (0, 0), (-1, 0), colors.HexColor(INK)),
                ('ROWBACKGROUNDS', (0, 1), (-1, -1),
                 [colors.white, colors.HexColor('#f1f6f8')]),
                ('VALIGN', (0, 0), (-1, -1), 'TOP'),
                ('LEFTPADDING', (0, 0), (-1, -1), 6),
                ('RIGHTPADDING', (0, 0), (-1, -1), 6),
                ('TOPPADDING', (0, 0), (-1, -1), 7),
                ('BOTTOMPADDING', (0, 0), (-1, -1), 7),
                ('LINEBELOW', (0, 0), (-1, 0), 1, colors.HexColor(ACCENT)),
                ('LINEBELOW', (0, 1), (-1, -1), 0.2, colors.HexColor('#dce7ec')),
            ]))
            story.extend([table, Spacer(1, 9)])

    def page(canvas, doc):
        canvas.saveState()
        canvas.setFont('Guide', 8)
        canvas.setFillColor(colors.HexColor('#576879'))
        if doc.page > 1:
            canvas.drawString(16 * mm, A4[1] - 11 * mm,
                              'QUANTUM  /  Эффекты b0ee49b  /  Software + M3G')
        canvas.setStrokeColor(colors.HexColor('#dce7ec'))
        canvas.line(16 * mm, 15 * mm, A4[0] - 16 * mm, 15 * mm)
        canvas.drawString(16 * mm, 10 * mm, 'Справочник эффектов  •  18.10.2026')
        canvas.drawRightString(A4[0] - 16 * mm, 10 * mm, str(doc.page))
        canvas.restoreState()

    args.output.parent.mkdir(parents=True, exist_ok=True)
    GuideDocument(str(args.output), pagesize=A4,
                  leftMargin=16 * mm, rightMargin=16 * mm,
                  topMargin=20 * mm, bottomMargin=21 * mm,
                  title='Quantum — справочник эффектов b0ee49b (Software / M3G)',
                  author='Quantum', subject='Материалы, Fog, спрайты, небо и post-effects',
                  pageCompression=1).multiBuild(story, onFirstPage=page, onLaterPages=page)
    print(args.output)


if __name__ == '__main__':
    main()
