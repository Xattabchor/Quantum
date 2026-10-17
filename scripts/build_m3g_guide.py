#!/usr/bin/env python3
"""Render the Russian M3G guide as an embedded-font, searchable PDF.
Optional dependencies: reportlab, markdown, beautifulsoup4.
Example: python scripts/build_m3g_guide.py --fonts-dir /path/to/dejavu
"""
import argparse
from html import escape
from pathlib import Path

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
    SimpleDocTemplate, Paragraph, Spacer, PageBreak, LongTable, TableStyle,
)

ROOT = Path(__file__).resolve().parents[1]


def inline(node):
    if isinstance(node, NavigableString):
        return escape(str(node))
    text = ''.join(inline(child) for child in node.children)
    if node.name in ('strong', 'b'):
        return '<b>' + text + '</b>'
    if node.name in ('em', 'i'):
        return '<i>' + text + '</i>'
    if node.name == 'code':
        return '<font face="GuideMono" size="8">' + text + '</font>'
    if node.name == 'a':
        href = node.get('href', '')
        if href.startswith(('https://', 'http://', '#')):
            return '<link color="#145e88" href="' + escape(href, quote=True) + '">' + text + '</link>'
    if node.name == 'br':
        return '<br/>'
    return text


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--fonts-dir', type=Path, default=Path('/usr/share/fonts/truetype/dejavu'))
    parser.add_argument('--output', type=Path, default=ROOT / 'downloads/Quantum_M3G_Render_Modes_RU.pdf')
    args = parser.parse_args()
    for name, filename in (
        ('Guide', 'DejaVuSans.ttf'), ('GuideBold', 'DejaVuSans-Bold.ttf'),
        ('GuideItalic', 'DejaVuSans-Oblique.ttf'), ('GuideMono', 'DejaVuSansMono.ttf'),
    ):
        font = args.fonts_dir / filename
        if name == 'GuideItalic' and not font.exists():
            font = args.fonts_dir / 'DejaVuSans.ttf'
        pdfmetrics.registerFont(TTFont(name, str(font)))
    pdfmetrics.registerFontFamily('Guide', normal='Guide', bold='GuideBold', italic='GuideItalic', boldItalic='GuideBold')
    body = ParagraphStyle('Body', fontName='Guide', fontSize=9, leading=13, spaceAfter=7,
                          alignment=TA_LEFT, splitLongWords=True)
    small = ParagraphStyle('Small', parent=body, fontSize=8, leading=11, spaceAfter=0)
    h1 = ParagraphStyle('Title', parent=body, fontName='GuideBold', fontSize=23, leading=30, spaceAfter=17)
    h2 = ParagraphStyle('H2', parent=body, fontName='GuideBold', fontSize=14, leading=19, spaceBefore=15, spaceAfter=9, keepWithNext=True)
    h3 = ParagraphStyle('H3', parent=h2, fontSize=11, leading=15, spaceBefore=10)
    soup = BeautifulSoup(markdown.markdown((ROOT / 'docs/m3g-modes-guide.md').read_text(), extensions=['tables', 'fenced_code']), 'html.parser')
    width = A4[0] - 32 * mm
    story = [Paragraph('QUANTUM / M3G / 18.10.2026', h3), Paragraph(inline(soup.h1), h1),
             Paragraph('Режимы материалов, спрайты, небо и восстановление кадра', body),
             Spacer(1, 9 * mm), Paragraph('Содержание', h2)]
    for i, heading in enumerate(soup.find_all('h2')):
        story.append(Paragraph('<link color="#145e88" href="#section-%d">%s</link>' % (i, inline(heading)), body))
    story.append(PageBreak())
    section = 0
    for node in soup.children:
        if isinstance(node, NavigableString) or node.name == 'h1':
            continue
        if node.name == 'h2':
            story.append(Paragraph('<a name="section-%d"/>%s' % (section, inline(node)), h2))
            section += 1
        elif node.name == 'h3':
            story.append(Paragraph(inline(node), h3))
        elif node.name == 'p':
            story.append(Paragraph(inline(node), body))
        elif node.name in ('ul', 'ol'):
            for i, item in enumerate(node.find_all('li', recursive=False)):
                bullet = str(i + 1) + '.' if node.name == 'ol' else '•'
                story.append(Paragraph(bullet + ' ' + inline(item), body))
        elif node.name == 'pre':
            story.append(Paragraph(inline(node).replace('\n', '<br/>'), body))
        elif node.name == 'table':
            rows = [[Paragraph(inline(cell), small) for cell in row.find_all(['td', 'th'], recursive=False)] for row in node.find_all('tr')]
            count = len(rows[0])
            if count == 3 and 'R,G,B' in rows[0][1].getPlainText():
                widths = [width * 0.28, width * 0.20, width * 0.52]
            elif count == 2:
                widths = [width * 0.36, width * 0.64]
            elif count == 3:
                widths = [width * 0.15, width * 0.52, width * 0.33]
            else:
                widths = [width / count] * count
            table = LongTable(rows, colWidths=widths, repeatRows=1, hAlign='LEFT')
            table.setStyle(TableStyle([
                ('BACKGROUND', (0, 0), (-1, 0), colors.HexColor('#dceaf2')),
                ('ROWBACKGROUNDS', (0, 1), (-1, -1), [colors.white, colors.HexColor('#f5f8fa')]),
                ('VALIGN', (0, 0), (-1, -1), 'TOP'),
                ('LEFTPADDING', (0, 0), (-1, -1), 6), ('RIGHTPADDING', (0, 0), (-1, -1), 6),
                ('TOPPADDING', (0, 0), (-1, -1), 6), ('BOTTOMPADDING', (0, 0), (-1, -1), 6),
                ('LINEBELOW', (0, 0), (-1, 0), 0.7, colors.HexColor('#8cabbc')),
            ]))
            story.extend([table, Spacer(1, 7)])

    def page(canvas, doc):
        canvas.saveState()
        canvas.setFont('Guide', 8)
        canvas.setFillColor(colors.HexColor('#576879'))
        canvas.drawString(16 * mm, A4[1] - 11 * mm, 'QUANTUM  /  Справочник M3G')
        canvas.drawRightString(A4[0] - 16 * mm, 10 * mm, str(doc.page))
        canvas.restoreState()

    args.output.parent.mkdir(parents=True, exist_ok=True)
    SimpleDocTemplate(str(args.output), pagesize=A4, leftMargin=16 * mm, rightMargin=16 * mm,
                      topMargin=20 * mm, bottomMargin=18 * mm, title='Quantum — режимы рендера M3G',
                      author='Quantum', pageCompression=1).build(story, onFirstPage=page, onLaterPages=page)
    print(args.output)


if __name__ == '__main__':
    main()
