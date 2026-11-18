"""Fabrique la notice PDF à partir de app/src/main/assets/notice.txt (même source que l'appli).
Usage : python3 outils/notice_pdf.py <version> <sortie.pdf> [icone.png]"""
import re, sys, os
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle
from reportlab.lib.units import mm
from reportlab.lib import colors
from reportlab.platypus import SimpleDocTemplate, Paragraph, Spacer, Image, Table, TableStyle, ListFlowable, ListItem
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont

racine = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
version, sortie = sys.argv[1], sys.argv[2]
icone = sys.argv[3] if len(sys.argv) > 3 else None

D = "/usr/share/fonts/truetype/dejavu/"
pdfmetrics.registerFont(TTFont("Txt", D + "DejaVuSans.ttf"))
pdfmetrics.registerFont(TTFont("TxtB", D + "DejaVuSans-Bold.ttf"))
BLEU = colors.HexColor("#1565C0")
corps = ParagraphStyle("c", fontName="Txt", fontSize=10, leading=14.5, spaceAfter=3)
h1 = ParagraphStyle("h1", fontName="TxtB", fontSize=13.5, leading=17, textColor=BLEU, spaceBefore=11, spaceAfter=5)
h2 = ParagraphStyle("h2", fontName="TxtB", fontSize=11, leading=14, spaceBefore=6, spaceAfter=3)
titre = ParagraphStyle("t", fontName="TxtB", fontSize=22, leading=26, textColor=BLEU)
sous = ParagraphStyle("s", fontName="Txt", fontSize=11, leading=15, textColor=colors.HexColor("#555555"))

def esc(t):
    return t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

texte = open(os.path.join(racine, "app/src/main/assets/notice.txt"), encoding="utf-8").read()
elems = []
entete = [Paragraph("Appel Station", titre), Spacer(1, 4),
          Paragraph(f"Notice d'utilisation — version {esc(version)}", sous)]
if icone:
    t = Table([[Image(icone, 26*mm, 26*mm), entete]], colWidths=[32*mm, None])
    t.setStyle(TableStyle([("VALIGN", (0, 0), (-1, -1), "MIDDLE")]))
    elems.append(t)
else:
    elems += entete
elems.append(Spacer(1, 6))

puces = []
def vider():
    global puces
    if puces:
        elems.append(ListFlowable([ListItem(Paragraph(esc(p), corps), leftIndent=12) for p in puces],
                                  bulletType="bullet", start="•", leftIndent=12, bulletFontName="Txt"))
        puces = []

for l in texte.splitlines():
    m = re.match(r"^# \[\w+\] (.*)$", l)
    if m: vider(); elems.append(Paragraph(esc(m.group(1)), h1)); continue
    if l.startswith("## "): vider(); elems.append(Paragraph(esc(l[3:]), h2)); continue
    if l.startswith("- "): puces.append(l[2:]); continue
    vider()
    if l.strip(): elems.append(Paragraph(esc(l), corps))
vider()

def pied(c, d):
    c.saveState(); c.setFont("Txt", 8); c.setFillColor(colors.grey)
    c.drawString(18*mm, 10*mm, f"Appel Station {version}")
    c.drawRightString(A4[0] - 18*mm, 10*mm, f"Page {d.page}")
    c.restoreState()

SimpleDocTemplate(sortie, pagesize=A4, leftMargin=18*mm, rightMargin=18*mm, topMargin=16*mm,
                  bottomMargin=18*mm, title=f"Notice Appel Station {version}",
                  author="Pascal Coniasse").build(elems, onFirstPage=pied, onLaterPages=pied)
print("PDF :", sortie)
